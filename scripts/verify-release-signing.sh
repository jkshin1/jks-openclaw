#!/usr/bin/env bash
set -euo pipefail

# Prints the certificate the release APK is actually signed with, and fails when the APK
# is unsigned or debug-signed. Record the SHA-256 once; every later build must match it,
# otherwise `adb install -r` will reject the update and the app data must be wiped.

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk_path="${1:-$project_root/app/build/outputs/apk/release/app-release.apk}"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"

unsigned_apk_path="${apk_path%.apk}-unsigned.apk"

if [[ ! -f "$apk_path" ]]; then
    # AGP names the artifact app-release-unsigned.apk when no signing config applies, so its
    # presence means the build ran and produced something that cannot be installed.
    if [[ -f "$unsigned_apk_path" ]]; then
        echo "FAIL The release build produced an unsigned APK: $unsigned_apk_path" >&2
        echo "Create the fixed personal key first: ./scripts/create-release-keystore.sh" >&2
        echo "See docs/RELEASE_AND_BACKUP.md for why the key must be stable." >&2
        exit 1
    fi
    echo "APK not found: $apk_path" >&2
    echo "Run: ./gradlew :app:assembleRelease" >&2
    exit 1
fi

apksigner_path="$(command -v apksigner || true)"
if [[ -z "$apksigner_path" ]]; then
    # Newest build-tools first so an Android 17 install is preferred over an older one.
    while IFS= read -r candidate; do
        if [[ -x "$candidate" ]]; then
            apksigner_path="$candidate"
            break
        fi
    done < <(find "$sdk_root/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -rV)
fi

if [[ -z "$apksigner_path" ]]; then
    echo "apksigner not found under $sdk_root/build-tools." >&2
    exit 1
fi

echo "APK:        $apk_path"
echo "apksigner:  $apksigner_path"

if ! verify_output="$("$apksigner_path" verify --print-certs --verbose "$apk_path" 2>&1)"; then
    echo "$verify_output" >&2
    echo "FAIL The release APK is not validly signed." >&2
    exit 1
fi

printf '%s\n' "$verify_output" | grep -E '^(Verified using v[0-9]|Signer #1 certificate (DN|SHA-256 digest))'

# The shared Android debug certificate is well known and must never sign a release build.
if printf '%s\n' "$verify_output" | grep -qi 'CN=Android Debug'; then
    echo "FAIL The release APK is signed with the shared Android debug certificate." >&2
    exit 1
fi

if ! printf '%s\n' "$verify_output" | grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true'; then
    echo "FAIL APK Signature Scheme v2 is missing." >&2
    exit 1
fi

echo "OK   Release APK carries a non-debug certificate with a v2 signature."
