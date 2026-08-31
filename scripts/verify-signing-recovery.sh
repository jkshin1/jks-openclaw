#!/usr/bin/env bash
set -euo pipefail

# Read-only recovery check for an independently stored copy of the personal release keystore.
# It verifies exact bytes, the expected certificate, and actual private-key access. It cannot prove
# that the supplied path is on an independent disk or that the password came from a password
# manager; those remain owner assertions.

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
identity_path="$project_root/app/release-signing-identity.json"
local_keystore_path="$project_root/app/personal-edge-release.jks"
backup_path="${1:-}"

if [[ -z "$backup_path" || "${2:-}" != "" ]]; then
    echo "Usage: $0 /absolute/path/to/personal-edge-release.jks" >&2
    exit 2
fi

for required_command in jq perl keytool jar jarsigner; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        echo "$required_command is required for signing recovery verification." >&2
        exit 1
    fi
done

if [[ "$backup_path" != /* ]]; then
    echo "Use an absolute path for the recovery copy: $backup_path" >&2
    exit 1
fi

key_alias="$(jq -er '.keyAlias' "$identity_path")"
expected_keystore_sha256="$(jq -er '.keystoreSha256' "$identity_path")"
expected_certificate_sha256="$(jq -er '.certificateSha256' "$identity_path")"

if [[ ! "$key_alias" =~ ^[A-Za-z0-9._-]+$ ||
      ! "$expected_keystore_sha256" =~ ^[0-9a-f]{64}$ ||
      ! "$expected_certificate_sha256" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Invalid release signing identity manifest: $identity_path" >&2
    exit 1
fi

verification_result="$(perl - "$backup_path" "$local_keystore_path" <<'PERL'
use strict;
use warnings;
use Digest::SHA ();
use Fcntl qw(:DEFAULT :mode);

sub fail {
    my ($message) = @_;
    print STDERR "$message\n";
    exit 1;
}

sub same_identity {
    my ($left, $right) = @_;
    for my $index (0, 1, 2, 3, 7) { # dev, ino, mode, nlink, size
        return 0 if $left->[$index] != $right->[$index];
    }
    return 1;
}

my ($backup_path, $local_path) = @ARGV;
my @path_before = lstat($backup_path);
fail("Recovery keystore is missing: $backup_path") unless @path_before;
fail("Recovery keystore must be a regular non-symlink file: $backup_path")
    unless S_ISREG($path_before[2]);

sysopen(my $backup, $backup_path, O_RDONLY | O_NOFOLLOW)
    or fail("Could not open recovery keystore without following links: $backup_path: $!");
binmode($backup);
my @fd_before = stat($backup);
fail("Could not inspect the opened recovery keystore") unless @fd_before;
fail("Recovery keystore path changed while opening: $backup_path")
    unless same_identity(\@path_before, \@fd_before);
fail("Recovery keystore must have exactly one hard link: $backup_path")
    unless $fd_before[3] == 1;
fail("Recovery keystore is empty: $backup_path") unless $fd_before[7] > 0;

if (-e $local_path) {
    my @local = stat($local_path);
    fail("Pass an independent copy, not the repository keystore itself: $backup_path")
        if @local && $local[0] == $fd_before[0] && $local[1] == $fd_before[1];
}

my $digest = Digest::SHA->new(256);
$digest->addfile($backup);
my $actual_sha256 = $digest->hexdigest;
my @fd_after = stat($backup);
fail("Recovery keystore descriptor changed during hashing")
    unless @fd_after && same_identity(\@fd_before, \@fd_after);
my @path_after = lstat($backup_path);
fail("Recovery keystore path changed during hashing: $backup_path")
    unless @path_after && same_identity(\@fd_after, \@path_after);
close($backup) or fail("Could not close recovery keystore: $!");

print "$fd_after[7]\t$actual_sha256\n";
PERL
)"

IFS=$'\t' read -r actual_size actual_keystore_sha256 <<< "$verification_result"
if [[ "$actual_keystore_sha256" != "$expected_keystore_sha256" ]]; then
    echo "Recovery keystore SHA-256 mismatch." >&2
    echo "Expected: $expected_keystore_sha256" >&2
    echo "Actual:   $actual_keystore_sha256" >&2
    exit 1
fi

store_password="${PERSONAL_EDGE_BACKUP_STORE_PASSWORD:-}"
key_password="${PERSONAL_EDGE_BACKUP_KEY_PASSWORD:-}"
if [[ -z "$store_password" ]]; then
    read -r -s -p "Recovery keystore password: " store_password
    echo
fi
if [[ -z "$key_password" ]]; then
    read -r -s -p "Recovery private-key password (RETURN if same): " key_password
    echo
    if [[ -z "$key_password" ]]; then
        key_password="$store_password"
    fi
fi

export PERSONAL_EDGE_RECOVERY_STORE_PASSWORD="$store_password"
export PERSONAL_EDGE_RECOVERY_KEY_PASSWORD="$key_password"
unset store_password key_password

if ! keytool_output="$(
    LC_ALL=C keytool -list -v \
        -keystore "$backup_path" \
        -alias "$key_alias" \
        -storepass:env PERSONAL_EDGE_RECOVERY_STORE_PASSWORD 2>&1
)"; then
    unset PERSONAL_EDGE_RECOVERY_STORE_PASSWORD PERSONAL_EDGE_RECOVERY_KEY_PASSWORD
    echo "$keytool_output" >&2
    echo "The supplied recovery keystore password or alias is invalid." >&2
    exit 1
fi

actual_certificate_sha256="$(
    printf '%s\n' "$keytool_output" |
        sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' |
        head -n 1 |
        tr -d ':' |
        tr '[:upper:]' '[:lower:]'
)"
unset keytool_output

if [[ "$actual_certificate_sha256" != "$expected_certificate_sha256" ]]; then
    unset PERSONAL_EDGE_RECOVERY_STORE_PASSWORD PERSONAL_EDGE_RECOVERY_KEY_PASSWORD
    echo "Recovery certificate SHA-256 mismatch." >&2
    echo "Expected: $expected_certificate_sha256" >&2
    echo "Actual:   ${actual_certificate_sha256:-missing}" >&2
    exit 1
fi

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-signing-recovery.XXXXXX")"
cleanup() {
    unset PERSONAL_EDGE_RECOVERY_STORE_PASSWORD PERSONAL_EDGE_RECOVERY_KEY_PASSWORD
    rm -rf -- "$temporary_directory"
}
trap cleanup EXIT HUP INT TERM

printf 'Personal Edge signing recovery probe\n' > "$temporary_directory/probe.txt"
(
    cd "$temporary_directory"
    jar --create --file unsigned-probe.jar probe.txt
)

if ! jarsigner_output="$(
    LC_ALL=C jarsigner \
        -keystore "$backup_path" \
        -storepass:env PERSONAL_EDGE_RECOVERY_STORE_PASSWORD \
        -keypass:env PERSONAL_EDGE_RECOVERY_KEY_PASSWORD \
        "$temporary_directory/unsigned-probe.jar" \
        "$key_alias" 2>&1
)"; then
    echo "$jarsigner_output" >&2
    echo "The recovery copy could not access the expected private key." >&2
    exit 1
fi
unset jarsigner_output

if ! LC_ALL=C jarsigner -verify "$temporary_directory/unsigned-probe.jar" >/dev/null 2>&1; then
    echo "The private-key recovery probe did not produce a valid signature." >&2
    exit 1
fi

echo "Recovery keystore: $backup_path"
echo "Size:              $actual_size bytes"
echo "Keystore SHA-256:  $actual_keystore_sha256"
echo "Certificate SHA-256: $actual_certificate_sha256"
echo "OK   Exact keystore copy, expected certificate, and private-key password are recoverable."
echo "NOTE Storage independence and password-manager provenance remain owner assertions."
