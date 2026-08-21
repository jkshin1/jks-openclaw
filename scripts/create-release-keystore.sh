#!/usr/bin/env bash
set -euo pipefail

# Creates the one fixed personal signing key used by every release APK.
#
# The certificate identity is what lets `adb install -r` replace the installed app while
# keeping its data and the imported 3.66GB model. Losing this key means a full uninstall,
# reinstall, and model re-import. Back it up before you rely on it.

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
keystore_path="${PERSONAL_EDGE_RELEASE_STORE_FILE:-$project_root/app/personal-edge-release.jks}"
properties_path="$project_root/app/keystore.properties"
key_alias="${PERSONAL_EDGE_RELEASE_KEY_ALIAS:-personal-edge-release}"
validity_days="${PERSONAL_EDGE_RELEASE_VALIDITY_DAYS:-10950}"
distinguished_name="${PERSONAL_EDGE_RELEASE_DNAME:-CN=Personal Edge Agent, OU=Sideload, O=Personal, C=KR}"

if ! command -v keytool >/dev/null 2>&1; then
    echo "keytool not found. Run: source ./scripts/android-env.sh" >&2
    exit 1
fi

if [[ ! "$key_alias" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "Unsafe key alias: $key_alias" >&2
    exit 1
fi

if [[ ! "$validity_days" =~ ^[1-9][0-9]{2,4}$ ]]; then
    echo "Validity must be 100 to 99999 days, got: $validity_days" >&2
    exit 1
fi

# Overwriting an existing keystore would silently create a second, incompatible identity.
if [[ -e "$keystore_path" ]]; then
    echo "Keystore already exists: $keystore_path" >&2
    echo "Refusing to replace it. Delete it deliberately only if no build is installed." >&2
    exit 1
fi

if [[ -e "$properties_path" ]]; then
    echo "$properties_path already exists; remove it before creating a new key." >&2
    exit 1
fi

mkdir -p "$(dirname "$keystore_path")"

echo "Creating release keystore: $keystore_path"
echo "Alias: $key_alias   Validity: $validity_days days"
echo "Choose a password you can restore from your password manager."

read -r -s -p "Keystore password: " store_password
echo
read -r -s -p "Repeat password: " store_password_repeat
echo

if [[ "$store_password" != "$store_password_repeat" ]]; then
    echo "Passwords do not match." >&2
    exit 1
fi

if (( ${#store_password} < 12 )); then
    echo "Use at least 12 characters." >&2
    exit 1
fi

# The password reaches keytool over stdin so it never appears in the process table.
# The trailing blank line answers the optional "RETURN if same as keystore password" prompt.
if ! printf '%s\n%s\n\n' "$store_password" "$store_password" | keytool -genkeypair \
    -keystore "$keystore_path" \
    -storetype PKCS12 \
    -alias "$key_alias" \
    -keyalg RSA \
    -keysize 4096 \
    -sigalg SHA256withRSA \
    -validity "$validity_days" \
    -dname "$distinguished_name" >/dev/null; then
    echo "keytool failed; no keystore was written." >&2
    rm -f -- "$keystore_path"
    exit 1
fi

chmod 600 "$keystore_path"

umask 077
cat > "$properties_path" <<PROPERTIES
# Untracked personal signing material. Never commit this file.
storeFile=$keystore_path
storePassword=$store_password
keyAlias=$key_alias
keyPassword=$store_password
PROPERTIES
chmod 600 "$properties_path"

echo
echo "Wrote $properties_path (mode 600)."
echo "Certificate identity to record in your backup notes:"
printf '%s\n' "$store_password" \
    | keytool -list -v -keystore "$keystore_path" -alias "$key_alias" 2>/dev/null \
    | sed -n 's/^[[:space:]]*SHA256:/  SHA-256:/p'

cat <<'NEXT'

Next steps:
  1. Copy the keystore and its password into offline backup (see docs/RELEASE_AND_BACKUP.md).
  2. ./gradlew :app:assembleRelease
  3. ./scripts/verify-release-signing.sh
NEXT
