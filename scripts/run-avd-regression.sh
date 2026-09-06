#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
serial=""
expected_avd_name="${PERSONAL_EDGE_AVD_NAME:-personal_edge_api37_foldable}"
skip_build=0
confirmed_disposable=0

usage() {
    cat <<'EOF'
Usage: ./scripts/run-avd-regression.sh --serial emulator-PORT --confirm-disposable \
  [--expected-avd-name NAME] [--skip-build]

Builds, installs, and runs the account-free API 37 ARM64 AVD regression suites.
The selected transport must be an already-booted, caller-owned disposable emulator with the
expected AVD name. --confirm-disposable acknowledges that this script cannot prove the launch
flags of an AVD it did not start.
The script never starts or stops an AVD and never accepts a physical-device serial.

The suite intentionally mutates only the disposable AVD: debug app data, local test calendar
rows, and emulator Clock data may be changed or cleared by tests. It excludes owner-approved
Samsung Calendar actions and the two long real-model selection flows; run model probes separately.
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
        --skip-build)
            skip_build=1
            shift
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
    fail "--confirm-disposable is required because the AVD tests change disposable emulator state"
if [[ -z "$expected_avd_name" || ${#expected_avd_name} -gt 128 ||
      ! "$expected_avd_name" =~ ^[A-Za-z0-9._-]+$ || "$expected_avd_name" == -* ]]; then
    fail "unsafe expected AVD name"
fi

for required_command in awk find grep mktemp rm sed tail tr; do
    command -v "$required_command" >/dev/null 2>&1 ||
        fail "required command not found: $required_command"
done

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
if [[ -n "${ADB:-}" ]]; then
    adb_bin="$ADB"
elif command -v adb >/dev/null 2>&1; then
    adb_bin="$(command -v adb)"
elif [[ -x "$sdk_root/platform-tools/adb" ]]; then
    adb_bin="$sdk_root/platform-tools/adb"
else
    fail "adb was not found in PATH or the Android SDK"
fi
[[ -x "$adb_bin" ]] || fail "adb is not executable: $adb_bin"

gradlew_bin="${GRADLEW:-$project_root/gradlew}"
if [[ "$skip_build" == "0" ]]; then
    [[ -x "$gradlew_bin" ]] || fail "Gradle wrapper is not executable: $gradlew_bin"
fi

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

verify_test_source_count() {
    local source_root="$1"
    local expected_count="$2"
    local actual_count
    actual_count="$(
        find "$source_root" -type f -name '*Test.kt' -print |
            awk 'END { print NR + 0 }'
    )"
    [[ "$actual_count" == "$expected_count" ]] ||
        fail "reviewed test inventory changed under $source_root: expected=$expected_count actual=$actual_count"
}

# A new top-level test file must be reviewed and explicitly added to the allowlist below.
verify_test_source_count "$project_root/app/src/androidTest" 42
verify_test_source_count "$project_root/core/data/src/androidTest" 12
verify_test_source_count "$project_root/core/diagnostics/src/androidTest" 1
verify_test_source_count "$project_root/core/llm/src/androidTest" 3
verify_test_source_count "$project_root/core/tools/src/androidTest" 3

if [[ "$skip_build" == "0" ]]; then
    "$gradlew_bin" --offline \
        :app:assembleDebug \
        :app:assembleDebugAndroidTest \
        :core:data:assembleDebugAndroidTest \
        :core:diagnostics:assembleDebugAndroidTest \
        :core:llm:assembleDebugAndroidTest \
        :core:tools:assembleDebugAndroidTest
fi

app_apk="$project_root/app/build/outputs/apk/debug/app-debug.apk"
app_test_apk="$project_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
data_test_apk="$project_root/core/data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk"
diagnostics_test_apk="$project_root/core/diagnostics/build/outputs/apk/androidTest/debug/diagnostics-debug-androidTest.apk"
llm_test_apk="$project_root/core/llm/build/outputs/apk/androidTest/debug/llm-debug-androidTest.apk"
tools_test_apk="$project_root/core/tools/build/outputs/apk/androidTest/debug/tools-debug-androidTest.apk"

for apk_path in "$app_apk" "$app_test_apk" "$data_test_apk" \
    "$diagnostics_test_apk" "$llm_test_apk" "$tools_test_apk"; do
    [[ -s "$apk_path" && ! -L "$apk_path" ]] ||
        fail "required regular APK is missing or empty: $apk_path"
done

# Recheck after the build so no state-changing ADB command can follow an AVD replacement.
assert_disposable_avd

for apk_path in "$app_apk" "$app_test_apk" "$data_test_apk" \
    "$diagnostics_test_apk" "$llm_test_apk" "$tools_test_apk"; do
    "$adb_bin" -s "$serial" install -r -t "$apk_path"
done

scratch_directory="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-avd-regression.XXXXXX")"
cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ "$scratch_directory" == "${TMPDIR:-/tmp}"/personal-edge-avd-regression.* &&
          -d "$scratch_directory" && ! -L "$scratch_directory" ]]; then
        rm -rf -- "$scratch_directory"
    else
        echo "Refusing unsafe AVD regression cleanup path: $scratch_directory" >&2
        status=1
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

total_selected=0
total_passed=0
total_skipped=0

show_failure_log() {
    local log_path="$1"
    sed -n '1,160p' "$log_path" >&2
    if (( $(awk 'END { print NR + 0 }' "$log_path") > 240 )); then
        echo "... final instrumentation output ..." >&2
        tail -n 80 "$log_path" >&2
    fi
}

run_suite() {
    local label="$1"
    local component="$2"
    local expected_selected="$3"
    local minimum_skips="$4"
    local maximum_skips="$5"
    local log_path final_code selected passed skipped
    shift 5
    log_path="$scratch_directory/$label.log"
    local command=(
        "$adb_bin" -s "$serial" shell am instrument -w -r
        "$@"
        "$component"
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
    [[ "$selected" =~ ^[1-9][0-9]*$ ]] || {
        show_failure_log "$log_path"
        fail "$label instrumentation did not report a positive test count"
    }
    [[ "$selected" == "$expected_selected" ]] || {
        show_failure_log "$log_path"
        fail "$label reviewed test count changed: expected=$expected_selected actual=$selected"
    }
    passed="$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' "$log_path" || true)"
    skipped="$(grep -c '^INSTRUMENTATION_STATUS_CODE: -4$' "$log_path" || true)"
    (( passed + skipped == selected )) || {
        show_failure_log "$log_path"
        fail "$label result accounting is incomplete: selected=$selected passed=$passed skipped=$skipped"
    }
    (( skipped >= minimum_skips && skipped <= maximum_skips )) || {
        show_failure_log "$log_path"
        fail "$label guarded-skip count changed: expected=$minimum_skips..$maximum_skips actual=$skipped"
    }

    total_selected=$((total_selected + selected))
    total_passed=$((total_passed + passed))
    total_skipped=$((total_skipped + skipped))
    echo "PASS $label selected=$selected passed=$passed guardedSkip=$skipped"
}

app_classes="com.personaledge.agent.AlarmForegroundRequestTest,com.personaledge.agent.AlarmSetLiveAcceptanceTest,com.personaledge.agent.ChatHistoryCoordinatorTest,com.personaledge.agent.ConversationSummarizerTest,com.personaledge.agent.CredentialSettingsTest,com.personaledge.agent.DeviceExecutionInterlockTest,com.personaledge.agent.Fold8KakaoCommunicationSafetyAcceptanceTest,com.personaledge.agent.Fold8LifecycleAcceptanceTest,com.personaledge.agent.Fold8MediaTurnAcceptanceTest,com.personaledge.agent.Fold8OpenClawLiveAcceptanceTest,com.personaledge.agent.Fold8OpenClawSurfaceAcceptanceTest,com.personaledge.agent.Fold8PreservationSnapshotTest,com.personaledge.agent.Fold8ReminderAcceptanceTest,com.personaledge.agent.Fold8ReminderToolSelectionAcceptanceTest,com.personaledge.agent.Fold8ResponseLanguageAcceptanceTest,com.personaledge.agent.Fold8RuntimePrdAcceptanceTest,com.personaledge.agent.GroundedWebSearchJourneyTest,com.personaledge.agent.ImageAttachmentLoaderTest,com.personaledge.agent.KakaoNotificationLiveStateTest,com.personaledge.agent.KakaoNotificationLiveToolAcceptanceTest,com.personaledge.agent.KakaoReplySettingsTest,com.personaledge.agent.KoreanRouteLiveAcceptanceTest,com.personaledge.agent.MediaCaptureStagingTest,com.personaledge.agent.NetworkOfflineLiveAcceptanceTest,com.personaledge.agent.NotificationCaptureCoordinatorTest,com.personaledge.agent.NotificationCaptureSinkTest,com.personaledge.agent.NotificationPostReaderTest,com.personaledge.agent.OpenClawRemoteUiAcceptanceTest,com.personaledge.agent.PublicPersonSearchLiveAcceptanceTest,com.personaledge.agent.StoredNotificationGatewayTest,com.personaledge.agent.ThermalStatusMonitorInstrumentedTest,com.personaledge.agent.WeatherLiveToolAcceptanceTest,com.personaledge.agent.WebSearchLiveToolAcceptanceTest,com.personaledge.agent.WebSearchProviderLiveAcceptanceTest"
data_classes="com.personaledge.core.data.AgentPlanCheckpointRepositoryTest,com.personaledge.core.data.CommitmentProposalRepositoryTest,com.personaledge.core.data.ConversationRepositoryTest,com.personaledge.core.data.MemoryRepositoryTest,com.personaledge.core.data.NotificationRepositoryBoundsTest,com.personaledge.core.data.PersonalEdgeDatabaseMigrationTest,com.personaledge.core.data.ReminderRepositoryTest,com.personaledge.core.data.SecretVaultTest,com.personaledge.core.data.SecureSecretFileSystemTest,com.personaledge.core.data.SettingsRepositoryTest,com.personaledge.core.data.TurnOutcomeRepositoryTest,com.personaledge.core.data.UserDataTransferRepositoryTest"
diagnostics_classes="com.personaledge.core.diagnostics.AndroidDiagnosticsInstrumentationTest"
llm_classes="com.personaledge.core.llm.LiteRtCacheDirectoryTest,com.personaledge.core.llm.LiteRtConversationPolicyTest,com.personaledge.core.llm.ModelArtifactStoreTest"
tools_classes="com.personaledge.core.tools.AndroidAlarmGatewayTest,com.personaledge.core.tools.AndroidCalendarGatewayTest,com.personaledge.core.tools.SqliteActionLedgerTest"

run_suite app com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner 160 36 36 \
    -e class "$app_classes"
run_suite data com.personaledge.core.data.test/androidx.test.runner.AndroidJUnitRunner 90 0 0 \
    -e class "$data_classes"
run_suite diagnostics com.personaledge.core.diagnostics.test/androidx.test.runner.AndroidJUnitRunner 2 0 0 \
    -e class "$diagnostics_classes"
run_suite llm com.personaledge.core.llm.test/androidx.test.runner.AndroidJUnitRunner 12 0 1 \
    -e class "$llm_classes"
run_suite tools com.personaledge.core.tools.test/androidx.test.runner.AndroidJUnitRunner 24 0 0 \
    -e class "$tools_classes"

[[ "$total_selected" == "288" ]] || fail "aggregate reviewed test count changed: $total_selected"
[[ "$total_skipped" == "36" || "$total_skipped" == "37" ]] ||
    fail "aggregate guarded-skip count changed: $total_skipped"

cat <<EOF
OK   Account-free AVD regression passed.
avd.serial=$serial
avd.name=$resolved_avd_name
avd.api=$resolved_sdk
avd.abi=$resolved_abi
tests.selected=$total_selected
tests.passed=$total_passed
tests.guardedSkip=$total_skipped
tests.failed=0
EOF
