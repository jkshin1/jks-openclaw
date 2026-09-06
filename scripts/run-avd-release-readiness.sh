#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
serial=""
expected_avd_name="${PERSONAL_EDGE_AVD_NAME:-personal_edge_api37_foldable}"
confirmed_disposable=0

usage() {
    cat <<'EOF'
Usage: ./scripts/run-avd-release-readiness.sh --serial emulator-PORT --confirm-disposable \
  [--expected-avd-name NAME]

Builds one matched owner-signed minified release app/test pair, replaces only those two packages
on an already-booted disposable API 37 ARM64 AVD, and runs the frozen release-readiness lane:

  * one provider-free ABI linkage smoke (must pass),
  * five historical ABI/model-boundary canaries (must stop at reviewed guards), and
  * 29 physical/live methods (must stop at their owner opt-in guards).

The selected transport must be an emulator with the expected AVD name. This script never accepts
a physical serial and never starts or stops an AVD. It uninstalls com.personaledge.agent and its
test package from the disposable AVD before installing the release pair, so use a read-only,
no-snapshot AVD and never point it at an emulator whose app data must be retained.
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
        --expected-avd-name)
            (( $# >= 2 )) || fail "--expected-avd-name requires a value"
            expected_avd_name="$2"
            shift 2
            ;;
        --confirm-disposable)
            confirmed_disposable=1
            shift
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

[[ "$serial" =~ ^emulator-[0-9]+$ ]] ||
    fail "an explicit emulator-PORT --serial is required; physical devices are refused"
[[ "$confirmed_disposable" == "1" ]] ||
    fail "--confirm-disposable is required because the release lane replaces AVD packages"
if [[ -z "$expected_avd_name" || ${#expected_avd_name} -gt 128 ||
      ! "$expected_avd_name" =~ ^[A-Za-z0-9._-]+$ || "$expected_avd_name" == -* ]]; then
    fail "unsafe expected AVD name"
fi

for required_command in awk find grep jq mktemp rm sed shasum tail tr; do
    command -v "$required_command" >/dev/null 2>&1 ||
        fail "required command not found: $required_command"
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
    case "$tool_name" in
        adb)
            candidate="$sdk_root/platform-tools/adb"
            ;;
        apkanalyzer)
            candidate="$sdk_root/cmdline-tools/latest/bin/apkanalyzer"
            ;;
        apksigner)
            candidate=""
            while IFS= read -r candidate; do
                [[ -x "$candidate" ]] && break
                candidate=""
            done < <(find "$sdk_root/build-tools" -maxdepth 2 -type f -name apksigner 2>/dev/null | sort -rV)
            ;;
    esac
    [[ -n "$candidate" && -x "$candidate" ]] || fail "$tool_name was not found"
    printf '%s\n' "$candidate"
}

adb_bin="${ADB:-$(find_android_tool adb)}"
apkanalyzer_bin="${APKANALYZER:-$(find_android_tool apkanalyzer)}"
apksigner_bin="${APKSIGNER:-$(find_android_tool apksigner)}"
gradlew_bin="${GRADLEW:-$project_root/gradlew}"
for executable in "$adb_bin" "$apkanalyzer_bin" "$apksigner_bin" "$gradlew_bin"; do
    [[ -x "$executable" ]] || fail "required executable is missing: $executable"
done

resolved_avd_name=""
resolved_sdk=""
resolved_abi=""

device_property() {
    "$adb_bin" -s "$serial" shell getprop "$1" | tr -d '\r\n'
}

assert_disposable_avd() {
    local state kernel_qemu boot_qemu boot_completed
    state="$("$adb_bin" -s "$serial" get-state | tr -d '\r\n')"
    [[ "$state" == "device" ]] || fail "selected emulator transport is not ready: $state"

    kernel_qemu="$(device_property ro.kernel.qemu)"
    boot_qemu="$(device_property ro.boot.qemu)"
    resolved_avd_name="$(device_property ro.boot.qemu.avd_name)"
    resolved_sdk="$(device_property ro.build.version.sdk)"
    resolved_abi="$(device_property ro.product.cpu.abi)"
    boot_completed="$(device_property sys.boot_completed)"

    [[ "$kernel_qemu" == "1" && "$boot_qemu" == "1" ]] ||
        fail "selected transport does not have a trusted emulator identity"
    [[ "$resolved_avd_name" == "$expected_avd_name" ]] ||
        fail "selected AVD is $resolved_avd_name, expected $expected_avd_name"
    [[ "$resolved_sdk" == "37" ]] || fail "selected AVD is not API 37: $resolved_sdk"
    [[ "$resolved_abi" == "arm64-v8a" ]] ||
        fail "selected AVD is not arm64-v8a: $resolved_abi"
    [[ "$boot_completed" == "1" ]] || fail "selected AVD has not completed boot"
}

assert_disposable_avd

# Any new app instrumentation file must be reviewed for release-target compatibility and either
# added to one of the exact allowlists below or deliberately kept in the debug-only lane.
test_source_count="$(find "$project_root/app/src/androidTest" -type f -name '*Test.kt' -print | awk 'END { print NR + 0 }')"
[[ "$test_source_count" == "42" ]] ||
    fail "reviewed app instrumentation inventory changed: expected=42 actual=$test_source_count"

"$gradlew_bin" --offline -PpersonalEdgePhysicalReleaseTest=true \
    :app:assembleRelease :app:assembleReleaseAndroidTest

app_apk="$project_root/app/build/outputs/apk/release/app-release.apk"
test_apk="$project_root/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"
mapping_path="$project_root/app/build/outputs/mapping/release/mapping.txt"
identity_path="$project_root/app/release-signing-identity.json"
for artifact in "$app_apk" "$test_apk" "$mapping_path" "$identity_path"; do
    [[ -s "$artifact" && ! -L "$artifact" ]] || fail "required regular artifact is missing: $artifact"
done

apk_application_id() {
    "$apkanalyzer_bin" manifest application-id "$1" | tr -d '\r\n'
}

apk_certificate() {
    local verify_output digests
    verify_output="$("$apksigner_bin" verify --print-certs --verbose "$1" 2>&1)" ||
        fail "APK signature verification failed: $1"
    printf '%s\n' "$verify_output" |
        grep -Fqx 'Verified using v3 scheme (APK Signature Scheme v3): true' ||
        fail "APK is not verified with APK Signature Scheme v3: $1"
    digests="$(
        printf '%s\n' "$verify_output" |
            sed -n 's/^.*Signer.*certificate SHA-256 digest:[[:space:]]*//p' |
            tr '[:upper:]' '[:lower:]' |
            tr -d ':' |
            awk 'NF && !seen[$0]++ { print }'
    )"
    [[ "$(printf '%s\n' "$digests" | awk 'NF { count++ } END { print count + 0 }')" == "1" &&
       "$digests" =~ ^[0-9a-f]{64}$ ]] || fail "APK must have one parseable certificate: $1"
    printf '%s\n' "$digests"
}

[[ "$(apk_application_id "$app_apk")" == "com.personaledge.agent" ]] ||
    fail "release app APK has the wrong application ID"
[[ "$(apk_application_id "$test_apk")" == "com.personaledge.agent.test" ]] ||
    fail "release test APK has the wrong application ID"
test_manifest="$("$apkanalyzer_bin" manifest print "$test_apk")"
instrumentation_count="$(printf '%s\n' "$test_manifest" | grep -c '<instrumentation' || true)"
[[ "$instrumentation_count" == "1" ]] ||
    fail "release test APK must declare exactly one instrumentation"
instrumentation_block="$(printf '%s\n' "$test_manifest" | awk '/<instrumentation/{inside=1} inside{print} inside && /\/>/{exit}')"
test_target="$(printf '%s\n' "$instrumentation_block" | sed -n 's/.*android:targetPackage="\([^"]*\)".*/\1/p')"
test_runner="$(printf '%s\n' "$instrumentation_block" | sed -n 's/.*android:name="\([^"]*\)".*/\1/p')"
[[ "$test_target" == "com.personaledge.agent" ]] ||
    fail "release test APK targets the wrong package: $test_target"
[[ "$test_runner" == "androidx.test.runner.AndroidJUnitRunner" ]] ||
    fail "release test APK declares the wrong runner: $test_runner"

expected_certificate="$(jq -er '.certificateSha256 | select(type == "string")' "$identity_path")" ||
    fail "release signing identity has no certificateSha256"
expected_certificate="$(printf '%s' "$expected_certificate" | tr '[:upper:]' '[:lower:]' | tr -d ':')"
[[ "$expected_certificate" =~ ^[0-9a-f]{64}$ ]] || fail "release certificate identity is malformed"
app_certificate="$(apk_certificate "$app_apk")"
test_certificate="$(apk_certificate "$test_apk")"
[[ "$app_certificate" == "$expected_certificate" ]] || fail "release app certificate is not the owner identity"
[[ "$test_certificate" == "$expected_certificate" ]] || fail "release test certificate is not the owner identity"

app_sha256="$(shasum -a 256 "$app_apk" | awk '{print $1}')"
test_sha256="$(shasum -a 256 "$test_apk" | awk '{print $1}')"
mapping_sha256="$(shasum -a 256 "$mapping_path" | awk '{print $1}')"
for digest in "$app_sha256" "$test_sha256" "$mapping_sha256"; do
    [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || fail "could not calculate an artifact SHA-256"
done

# Recheck after the paired build and all host inspection. No mutation follows an AVD replacement.
assert_disposable_avd

package_installed() {
    "$adb_bin" -s "$serial" shell pm path "$1" 2>/dev/null | tr -d '\r' | grep -q '^package:'
}

if package_installed com.personaledge.agent.test; then
    "$adb_bin" -s "$serial" uninstall com.personaledge.agent.test >/dev/null
fi
if package_installed com.personaledge.agent; then
    "$adb_bin" -s "$serial" uninstall com.personaledge.agent >/dev/null
fi

"$adb_bin" -s "$serial" install -t "$app_apk" >/dev/null
"$adb_bin" -s "$serial" install -t "$test_apk" >/dev/null

scratch_directory="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-avd-release-readiness.XXXXXX")"
cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ "$scratch_directory" == "${TMPDIR:-/tmp}"/personal-edge-avd-release-readiness.* &&
          -d "$scratch_directory" && ! -L "$scratch_directory" ]]; then
        rm -rf -- "$scratch_directory"
    else
        echo "Refusing unsafe release-readiness cleanup path: $scratch_directory" >&2
        status=1
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

show_failure_log() {
    local log_path="$1"
    sed -n '1,180p' "$log_path" >&2
    if (( $(awk 'END { print NR + 0 }' "$log_path") > 260 )); then
        echo "... final instrumentation output ..." >&2
        tail -n 80 "$log_path" >&2
    fi
}

total_selected=0
total_passed=0
total_skipped=0

run_suite() {
    local label="$1"
    local expected_selected="$2"
    local expected_passed="$3"
    local expected_skipped="$4"
    local log_path final_code selected passed skipped
    shift 4
    log_path="$scratch_directory/$label.log"
    local command=(
        "$adb_bin" -s "$serial" shell am instrument -w -r
        "$@"
        com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner
    )

    if ! "${command[@]}" 2>&1 | tr -d '\r' > "$log_path"; then
        show_failure_log "$log_path"
        fail "$label instrumentation command failed"
    fi
    if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=Process crashed' \
        "$log_path"; then
        show_failure_log "$log_path"
        fail "$label instrumentation reported a test or process failure"
    fi
    final_code="$(sed -n 's/^INSTRUMENTATION_CODE: //p' "$log_path" | tail -n 1)"
    [[ "$final_code" == "-1" ]] || {
        show_failure_log "$log_path"
        fail "$label instrumentation did not finish successfully: code=$final_code"
    }
    selected="$(sed -nE 's/^OK \(([0-9]+) tests?\)$/\1/p' "$log_path" | tail -n 1)"
    [[ "$selected" == "$expected_selected" ]] || {
        show_failure_log "$log_path"
        fail "$label reviewed count changed: expected=$expected_selected actual=${selected:-missing}"
    }
    skipped="$(grep -c '^INSTRUMENTATION_STATUS_CODE: -4$' "$log_path" || true)"
    # A test may emit an application-owned sendStatus(code=0) in addition to the runner's own
    # completion status. The final JUnit OK count is authoritative; only assumption exits are
    # subtracted because all failure/process markers were rejected above.
    passed=$((selected - skipped))
    [[ "$passed" == "$expected_passed" && "$skipped" == "$expected_skipped" ]] || {
        show_failure_log "$log_path"
        fail "$label result changed: expected pass/skip=$expected_passed/$expected_skipped actual=$passed/$skipped"
    }
    (( passed + skipped == selected )) || {
        show_failure_log "$log_path"
        fail "$label result accounting is incomplete"
    }
    total_selected=$((total_selected + selected))
    total_passed=$((total_passed + passed))
    total_skipped=$((total_skipped + skipped))
    echo "PASS $label selected=$selected passed=$passed guardedSkip=$skipped"
}

abi_method="com.personaledge.agent.ReleasePhysicalAbiLinkageTest#boundedPostGuardEntrypointsResolveFromTheMinifiedTarget"
canary_classes="com.personaledge.agent.Fold8RuntimePrdAcceptanceTest,com.personaledge.agent.KoreanToolSelectionTest,com.personaledge.agent.PastedMailScheduleAcceptanceTest"
# OpenClawRemoteUiAcceptanceTest stays debug-only: its Compose fixture is not a release ABI gate.
# Fold8OpenClawSurfaceAcceptanceTest is guarded here; its provider-free opt-in is run separately.
# GroundedWebSearchJourneyTest stays in the debug AVD lane until its production-controller and
# Room-backed path has a direct minified-release AVD receipt.
physical_classes="com.personaledge.agent.AlarmSetLiveAcceptanceTest,com.personaledge.agent.Fold8KakaoCommunicationSafetyAcceptanceTest,com.personaledge.agent.Fold8LifecycleAcceptanceTest,com.personaledge.agent.Fold8MediaTurnAcceptanceTest,com.personaledge.agent.Fold8OpenClawLiveAcceptanceTest,com.personaledge.agent.Fold8OpenClawSurfaceAcceptanceTest,com.personaledge.agent.Fold8PreservationSnapshotTest,com.personaledge.agent.Fold8ReminderAcceptanceTest,com.personaledge.agent.Fold8ReminderToolSelectionAcceptanceTest,com.personaledge.agent.Fold8ResponseLanguageAcceptanceTest,com.personaledge.agent.Fold8RuntimePrdAcceptanceTest,com.personaledge.agent.KakaoNotificationLiveStateTest,com.personaledge.agent.KakaoNotificationLiveToolAcceptanceTest,com.personaledge.agent.KoreanRouteLiveAcceptanceTest,com.personaledge.agent.NetworkOfflineLiveAcceptanceTest,com.personaledge.agent.PublicPersonSearchLiveAcceptanceTest,com.personaledge.agent.WeatherLiveToolAcceptanceTest,com.personaledge.agent.WebSearchLiveToolAcceptanceTest,com.personaledge.agent.WebSearchProviderLiveAcceptanceTest"

run_suite abi 1 1 0 -e releasePhysicalAbiLinkage true -e class "$abi_method"
run_suite canary 5 0 5 -e class "$canary_classes"
run_suite physical-guards 35 0 35 -e class "$physical_classes"

[[ "$total_selected" == "41" && "$total_passed" == "1" && "$total_skipped" == "40" ]] ||
    fail "aggregate release-readiness result changed"

cat <<EOF
OK   Minified release AVD readiness passed.
avd.serial=$serial
avd.name=$resolved_avd_name
avd.api=$resolved_sdk
avd.abi=$resolved_abi
release.appApkSha256=$app_sha256
release.testApkSha256=$test_sha256
release.mappingSha256=$mapping_sha256
release.certificateSha256=$expected_certificate
tests.selected=$total_selected
tests.passed=$total_passed
tests.guardedSkip=$total_skipped
tests.failed=0
EOF
