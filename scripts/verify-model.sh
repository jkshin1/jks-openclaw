#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest_path="$project_root/models/model-manifest.json"

if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required to read $manifest_path" >&2
    exit 1
fi
if ! command -v perl >/dev/null 2>&1; then
    echo "perl with Digest::SHA is required for descriptor-bound verification." >&2
    exit 1
fi

model_file="$(jq -er '.file' "$manifest_path")"
expected_size="$(jq -er '.sizeBytes' "$manifest_path")"
expected_sha256="$(jq -er '.sha256' "$manifest_path")"
model_path="${1:-$project_root/models/$model_file}"

if [[ "$model_file" != "$(basename "$model_file")" || "$model_file" == .* ]]; then
    echo "Unsafe model filename in manifest: $model_file" >&2
    exit 1
fi

if [[ ! "$expected_size" =~ ^[1-9][0-9]*$ || ! "$expected_sha256" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Invalid size or SHA-256 in $manifest_path" >&2
    exit 1
fi

verification_result="$(perl - "$model_path" "$expected_size" "$expected_sha256" <<'PERL'
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

my ($path, $expected_size, $expected_sha256) = @ARGV;
my @path_before = lstat($path);
fail("Model is missing or cannot be inspected without following links: $path")
    unless @path_before;
fail("Model is not a regular non-symlink file: $path")
    unless S_ISREG($path_before[2]);

sysopen(my $model, $path, O_RDONLY | O_NOFOLLOW)
    or fail("Could not open model without following links: $path: $!");
binmode($model);

my @fd_before = stat($model);
fail("Could not inspect the opened model descriptor: $path") unless @fd_before;
fail("Model path changed while it was opened: $path")
    unless same_identity(\@path_before, \@fd_before);
fail("Opened model is not a regular file: $path") unless S_ISREG($fd_before[2]);
fail("Model must have exactly one hard link: $path") unless $fd_before[3] == 1;

my $permissions = $fd_before[2] & 07777;
fail(sprintf("Unsafe model mode %04o; expected 0400 or 0600: %s", $permissions, $path))
    unless $permissions == 0400 || $permissions == 0600;
fail("Model size mismatch: expected $expected_size, found $fd_before[7]")
    unless $fd_before[7] == $expected_size;

my $digest = Digest::SHA->new(256);
eval { $digest->addfile($model); 1 }
    or fail("Could not hash the opened model descriptor: $path");
my $actual_sha256 = $digest->hexdigest;

my @fd_after = stat($model);
fail("Could not re-inspect the opened model descriptor: $path") unless @fd_after;
fail("Model descriptor identity changed during verification: $path")
    unless same_identity(\@fd_before, \@fd_after);

my @path_after = lstat($path);
fail("Model path disappeared during verification: $path") unless @path_after;
fail("Model path changed during verification: $path")
    unless same_identity(\@fd_after, \@path_after) && S_ISREG($path_after[2]);

close($model) or fail("Could not close verified model descriptor: $path: $!");
fail("Model SHA-256 mismatch: expected $expected_sha256, found $actual_sha256")
    unless $actual_sha256 eq $expected_sha256;

print "$fd_after[7]\t$actual_sha256\n";
PERL
)"

IFS=$'\t' read -r actual_size actual_sha256 <<< "$verification_result"
if [[ -z "$actual_size" || -z "$actual_sha256" ]]; then
    echo "Verifier returned an incomplete result for $model_path" >&2
    exit 1
fi

echo "Verified model: $model_path"
echo "Size:   $actual_size bytes"
echo "SHA256: $actual_sha256"
