#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
package_name="com.personaledge.agent"
test_package_name="$package_name.test"
runner_name="androidx.test.runner.AndroidJUnitRunner"
identity_path="$project_root/app/release-signing-identity.json"
app_apk="$project_root/app/build/outputs/apk/release/app-release.apk"
test_apk="$project_root/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"
serial=""

usage() {
    cat <<'EOF'
Usage: ./scripts/preflight-fold8-release-update.sh --serial SERIAL \
  [--app-apk FILE] [--test-apk FILE]

Performs read-only checks before a release update of the owner's API 37 SM-F971N.
It never installs, uninstalls, instruments, grants permissions, or changes device state.
EOF
}

fail() {
    echo "FAIL $*" >&2
    exit 1
}

while (( $# > 0 )); do
    case "$1" in
        --serial)
            (( $# >= 2 )) || fail "--serial requires a value"
            [[ -z "$serial" ]] || fail "--serial may be supplied only once"
            serial="$2"
            shift 2
            ;;
        --app-apk)
            (( $# >= 2 )) || fail "--app-apk requires a value"
            app_apk="$2"
            shift 2
            ;;
        --test-apk)
            (( $# >= 2 )) || fail "--test-apk requires a value"
            test_apk="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail "unknown argument: $1"
            ;;
    esac
done

[[ -n "$serial" ]] || fail "an explicit --serial is required"
if [[ ${#serial} -gt 128 || ! "$serial" =~ ^[A-Za-z0-9._:-]+$ ||
      "$serial" == "." || "$serial" == ".." || "$serial" == -* ]]; then
    fail "unsafe device serial; use the exact value from adb devices -l"
fi

for required_command in jq shasum awk sed grep tr mktemp rm find sort cat; do
    command -v "$required_command" >/dev/null 2>&1 || fail "required command not found: $required_command"
done

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
find_android_tool() {
    local tool_name="$1"
    local candidate
    candidate="$(command -v "$tool_name" || true)"
    if [[ -n "$candidate" ]]; then
        printf '%s\n' "$candidate"
        return
    fi
    if [[ "$tool_name" == "apkanalyzer" && -x "$sdk_root/cmdline-tools/latest/bin/apkanalyzer" ]]; then
        printf '%s\n' "$sdk_root/cmdline-tools/latest/bin/apkanalyzer"
        return
    fi
    while IFS= read -r candidate; do
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done < <(find "$sdk_root" -maxdepth 4 -type f -name "$tool_name" 2>/dev/null | sort -r)
    fail "$tool_name was not found in PATH or the Android SDK"
}

adb_bin="$(find_android_tool adb)"
apkanalyzer_bin="$(find_android_tool apkanalyzer)"
apksigner_bin="$(find_android_tool apksigner)"

for host_file in "$identity_path" "$app_apk" "$test_apk"; do
    [[ -f "$host_file" && ! -L "$host_file" ]] || fail "required regular file not found: $host_file"
done

expected_certificate="$(jq -er '.certificateSha256 | select(type == "string")' "$identity_path")" ||
    fail "release signing identity has no certificateSha256"
expected_certificate="$(printf '%s' "$expected_certificate" | tr '[:upper:]' '[:lower:]' | tr -d ':')"
[[ "$expected_certificate" =~ ^[0-9a-f]{64}$ ]] || fail "release signing certificate is malformed"

apk_application_id() {
    "$apkanalyzer_bin" manifest application-id "$1" | tr -d '\r\n'
}

apk_version_code() {
    "$apkanalyzer_bin" manifest version-code "$1" | tr -d '\r\n'
}

apk_version_name() {
    "$apkanalyzer_bin" manifest version-name "$1" | tr -d '\r\n'
}

apk_certificate() {
    local apk_path="$1"
    local verify_output
    local digests
    if ! verify_output="$("$apksigner_bin" verify --print-certs --verbose "$apk_path" 2>&1)"; then
        fail "APK signature verification failed: $apk_path"
    fi
    if ! printf '%s\n' "$verify_output" |
        grep -Fqx 'Verified using v3 scheme (APK Signature Scheme v3): true'; then
        fail "APK is not verified with APK Signature Scheme v3: $apk_path"
    fi
    digests="$(
        printf '%s\n' "$verify_output" |
            sed -n 's/^.*Signer.*certificate SHA-256 digest:[[:space:]]*//p' |
            tr '[:upper:]' '[:lower:]' |
            tr -d ':' |
            awk 'NF && !seen[$0]++ { print }'
    )"
    [[ "$(printf '%s\n' "$digests" | awk 'NF { count++ } END { print count + 0 }')" == "1" &&
       "$digests" =~ ^[0-9a-f]{64}$ ]] || fail "APK must have one parseable signing certificate: $apk_path"
    printf '%s\n' "$digests"
}

host_app_id="$(apk_application_id "$app_apk")"
host_test_id="$(apk_application_id "$test_apk")"
[[ "$host_app_id" == "$package_name" ]] || fail "release APK package is $host_app_id, expected $package_name"
[[ "$host_test_id" == "$test_package_name" ]] || fail "release androidTest package is $host_test_id, expected $test_package_name"

test_manifest="$("$apkanalyzer_bin" manifest print "$test_apk")"
instrumentation_count="$(printf '%s\n' "$test_manifest" | grep -c '<instrumentation' || true)"
[[ "$instrumentation_count" == "1" ]] || fail "release androidTest must declare exactly one instrumentation"
instrumentation_block="$(printf '%s\n' "$test_manifest" | awk '/<instrumentation/{inside=1} inside{print} inside && /\/>/{exit}')"
test_target="$(printf '%s\n' "$instrumentation_block" | sed -n 's/.*android:targetPackage="\([^"]*\)".*/\1/p')"
test_runner="$(printf '%s\n' "$instrumentation_block" | sed -n 's/.*android:name="\([^"]*\)".*/\1/p')"
[[ "$test_target" == "$package_name" ]] || fail "release androidTest targets $test_target, expected $package_name"
[[ "$test_runner" == "$runner_name" ]] || fail "unexpected instrumentation runner: $test_runner"

host_app_certificate="$(apk_certificate "$app_apk")"
host_test_certificate="$(apk_certificate "$test_apk")"
[[ "$host_app_certificate" == "$expected_certificate" ]] || fail "release APK is not signed by the owner certificate"
[[ "$host_test_certificate" == "$expected_certificate" ]] || fail "release androidTest is not signed by the owner certificate"

device_state="$("$adb_bin" -s "$serial" get-state | tr -d '\r\n')"
[[ "$device_state" == "device" ]] || fail "selected ADB transport is not ready: $device_state"

device_property() {
    "$adb_bin" -s "$serial" shell getprop "$1" | tr -d '\r\n'
}

manufacturer="$(device_property ro.product.manufacturer)"
model="$(device_property ro.product.model)"
sdk="$(device_property ro.build.version.sdk)"
abi="$(device_property ro.product.cpu.abi)"
fingerprint="$(device_property ro.build.fingerprint)"
kernel_qemu="$(device_property ro.kernel.qemu)"
boot_qemu="$(device_property ro.boot.qemu)"
hardware="$(device_property ro.hardware)"
manufacturer_lower="$(printf '%s' "$manufacturer" | tr '[:upper:]' '[:lower:]')"
fingerprint_lower="$(printf '%s' "$fingerprint" | tr '[:upper:]' '[:lower:]')"
hardware_lower="$(printf '%s' "$hardware" | tr '[:upper:]' '[:lower:]')"

[[ "$manufacturer_lower" == "samsung" ]] || fail "selected device manufacturer is not Samsung: $manufacturer"
[[ "$model" == "SM-F971N" ]] || fail "selected device is not the owner's Fold8: $model"
[[ "$sdk" == "37" ]] || fail "selected Fold8 is not API 37: $sdk"
[[ "$abi" == "arm64-v8a" ]] || fail "selected Fold8 is not arm64-v8a: $abi"
[[ -n "$fingerprint" && "$fingerprint_lower" != *generic* && "$fingerprint_lower" != *emulator* &&
   "$kernel_qemu" != "1" && "$boot_qemu" != "1" &&
   "$hardware_lower" != "ranchu" && "$hardware_lower" != "goldfish" ]] ||
    fail "selected transport is an emulator or has an untrusted physical-device identity"

if ! package_paths="$("$adb_bin" -s "$serial" shell pm path "$package_name" 2>&1)"; then
    fail "target package is not installed for the selected device"
fi
base_paths="$(printf '%s\n' "$package_paths" | tr -d '\r' | sed -n 's/^package://p' | grep '/base\.apk$' || true)"
[[ "$(printf '%s\n' "$base_paths" | awk 'NF { count++ } END { print count + 0 }')" == "1" ]] ||
    fail "installed target must expose exactly one base.apk"
installed_base_path="$base_paths"
[[ "$installed_base_path" =~ ^/data/app/[A-Za-z0-9._~+=/@:-]+/base\.apk$ ]] ||
    fail "installed base.apk path is unsafe"

package_dump="$("$adb_bin" -s "$serial" shell dumpsys package "$package_name")"
first_install_times="$(printf '%s\n' "$package_dump" | tr -d '\r' | sed -n 's/^[[:space:]]*firstInstallTime=//p')"
[[ "$(printf '%s\n' "$first_install_times" | awk 'NF { count++ } END { print count + 0 }')" == "1" ]] ||
    fail "could not resolve exactly one firstInstallTime"
first_install_time="$first_install_times"

scratch_directory="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-fold8-preflight.XXXXXX")"
cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ "$scratch_directory" == "${TMPDIR:-/tmp}"/personal-edge-fold8-preflight.* &&
          -d "$scratch_directory" && ! -L "$scratch_directory" ]]; then
        rm -rf -- "$scratch_directory"
    else
        echo "Refusing unsafe preflight cleanup path: $scratch_directory" >&2
        status=1
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

installed_apk="$scratch_directory/installed-base.apk"
"$adb_bin" -s "$serial" pull "$installed_base_path" "$installed_apk" >/dev/null
[[ -f "$installed_apk" && ! -L "$installed_apk" ]] || fail "ADB pull did not produce a regular installed base.apk"

installed_app_id="$(apk_application_id "$installed_apk")"
installed_certificate="$(apk_certificate "$installed_apk")"
[[ "$installed_app_id" == "$package_name" ]] || fail "pulled APK package is $installed_app_id, expected $package_name"
[[ "$installed_certificate" == "$expected_certificate" ]] || fail "installed app certificate does not match the owner certificate"

host_app_sha256="$(shasum -a 256 "$app_apk" | awk '{print $1}')"
host_test_sha256="$(shasum -a 256 "$test_apk" | awk '{print $1}')"
installed_app_sha256="$(shasum -a 256 "$installed_apk" | awk '{print $1}')"
serial_sha256="$(printf '%s' "$serial" | shasum -a 256 | awk '{print $1}')"
host_version_code="$(apk_version_code "$app_apk")"
host_version_name="$(apk_version_name "$app_apk")"
installed_version_code="$(apk_version_code "$installed_apk")"
installed_version_name="$(apk_version_name "$installed_apk")"

for digest in "$host_app_sha256" "$host_test_sha256" "$installed_app_sha256" "$serial_sha256"; do
    [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || fail "could not calculate a required SHA-256"
done
[[ "$host_version_code" =~ ^[0-9]+$ && -n "$host_version_name" ]] || fail "candidate version metadata is malformed"
[[ "$installed_version_code" =~ ^[0-9]+$ && -n "$installed_version_name" ]] || fail "installed version metadata is malformed"

cat <<EOF
OK   Fold8 release-update preflight passed without device mutation.
device.serialMasked=sha256:${serial_sha256:0:12}
device.model=$model
device.api=$sdk
device.abi=$abi
candidate.applicationId=$host_app_id
candidate.versionCode=$host_version_code
candidate.versionName=$host_version_name
candidate.appApkSha256=$host_app_sha256
candidate.testPackage=$host_test_id
candidate.testTargetPackage=$test_target
candidate.instrumentation=$test_package_name/$runner_name
candidate.testApkSha256=$host_test_sha256
installed.applicationId=$installed_app_id
installed.versionCode=$installed_version_code
installed.versionName=$installed_version_name
installed.firstInstallTime=$first_install_time
installed.appApkSha256=$installed_app_sha256
signing.certificateSha256=$expected_certificate
nextStep=owner approval is still required before any adb install -r or named instrumentation run
EOF
