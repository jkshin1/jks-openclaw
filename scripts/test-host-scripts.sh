#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_root_raw="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-host-scripts.XXXXXX")"
test_root="$(cd "$test_root_raw" && pwd)"
test_root_parent="$(cd "$(dirname "$test_root")" && pwd)"
tests_run=0

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if [[ "$test_root" == "$test_root_parent"/personal-edge-host-scripts.* &&
          -d "$test_root" && ! -L "$test_root" ]]; then
        rm -rf -- "$test_root"
    else
        echo "Refusing unsafe test cleanup path: $test_root" >&2
        status=1
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
    echo "FAIL $*" >&2
    exit 1
}

pass() {
    tests_run=$((tests_run + 1))
    echo "PASS $1"
}

expect_failure() {
    local log_path="$1"
    shift
    if "$@" >"$log_path" 2>&1; then
        fail "command unexpectedly succeeded: $*"
    fi
}

new_model_fixture() {
    local name="$1"
    fixture="$test_root/$name"
    mkdir -p "$fixture/scripts" "$fixture/models" "$fixture/bin"
    cp "$project_root/scripts/download-model.sh" "$fixture/scripts/"
    cp "$project_root/scripts/verify-model.sh" "$fixture/scripts/"
    chmod 755 "$fixture/scripts/download-model.sh" "$fixture/scripts/verify-model.sh"
}

new_evidence_fixture() {
    local name="$1"
    fixture="$test_root/$name"
    mkdir -p "$fixture/scripts" "$fixture/bin"
    cp "$project_root/scripts/collect-fold8-evidence.sh" "$fixture/scripts/"
    chmod 755 "$fixture/scripts/collect-fold8-evidence.sh"
    adb_log="$fixture/adb.log"

    cat > "$fixture/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

[[ $# -ge 3 && "$1" == "-s" && "$2" == "$MOCK_EXPECTED_SERIAL" ]] || {
    echo "mock adb requires the exact explicit serial" >&2
    exit 91
}
printf '%s\t' "$@" >> "$MOCK_ADB_LOG"
printf '\n' >> "$MOCK_ADB_LOG"
shift 2
adb_command="$1"
shift

case "$adb_command" in
    get-state)
        echo device
        ;;
    shell)
        shell_arguments="$*"
        case "$shell_arguments" in
            "getprop "*)
                property_name="${shell_arguments#getprop }"
                case "$property_name" in
                    ro.product.manufacturer) echo Samsung ;;
                    ro.product.model) echo 'Galaxy Z Fold8' ;;
                    ro.product.device) echo fold8 ;;
                    ro.product.name) echo fold8_product ;;
                    ro.build.version.release) echo 17 ;;
                    ro.build.version.sdk) echo 37 ;;
                    ro.build.version.security_patch) echo 2026-08-05 ;;
                    ro.build.fingerprint) echo mock/fold8/fingerprint ;;
                    ro.product.cpu.abi) echo arm64-v8a ;;
                    *) exit 99 ;;
                esac
                ;;
            "uname -a")
                echo 'Linux mock-fold8 6.12 android aarch64 Toybox'
                ;;
            "dumpsys package "*)
                echo 'versionCode=1 minSdk=31 targetSdk=37'
                echo 'versionName=0.1.0-dev'
                ;;
            "cmd package list packages -U com.personaledge.agent")
                if [[ "${MOCK_FAIL_PACKAGE_UID:-0}" == "1" ]]; then
                    echo 'package UID unavailable' >&2
                    exit 8
                fi
                echo 'package:com.personaledge.agent uid:10234'
                ;;
            "dumpsys activity exit-info "*)
                echo 'ApplicationExitInfo: reason=LOW_MEMORY'
                ;;
            "dumpsys meminfo -d "*)
                echo 'TOTAL PSS: 123456'
                ;;
            "dumpsys thermalservice")
                if [[ "${MOCK_FAIL_THERMAL:-0}" == "1" ]]; then
                    echo 'thermalservice unavailable' >&2
                    exit 9
                fi
                echo 'Thermal Status: 0'
                ;;
            "df -k /data /storage/emulated/0")
                echo '/data 1000000 500000 500000 50% /data'
                ;;
            "-T run-as com.personaledge.agent id")
                case "${MOCK_RUN_AS_MODE:-debuggable}" in
                    debuggable)
                        echo 'uid=10123(u0_a123) gid=10123(u0_a123)'
                        exit 0
                        ;;
                    not_debuggable)
                        echo 'run-as: package not debuggable: com.personaledge.agent' >&2
                        exit 1
                        ;;
                    not_debuggable_legacy)
                        echo "run-as: Package 'com.personaledge.agent' is not debuggable" >&2
                        exit 1
                        ;;
                    unexpected_failure)
                        echo 'run-as: package lookup failed unexpectedly' >&2
                        exit 19
                        ;;
                    *)
                        exit 96
                        ;;
                esac
                ;;
            "-T run-as com.personaledge.agent sh -c "*)
                remote_script="$shell_arguments"
                case "${MOCK_DIAGNOSTIC_MODE:-normal}:$remote_script" in
                    missing:*)
                        exit 44
                        ;;
                    oversize:*"no_backup/diagnostics/diagnostics.jsonl"*)
                        dd if=/dev/zero bs=1048576 count=6 2>/dev/null
                        ;;
                    invalid:*"no_backup/diagnostics/diagnostics.jsonl"*)
                        echo "stat: '/proc/self/fd/7': No such file or directory"
                        ;;
                    empty_archives:*"no_backup/diagnostics/diagnostics.1.jsonl"*|\
                    empty_archives:*"no_backup/diagnostics/diagnostics.2.jsonl"*|\
                    empty_archives:*"no_backup/diagnostics/diagnostics.3.jsonl"*)
                        :
                        ;;
                    *:*"no_backup/diagnostics/diagnostics.1.jsonl"*|\
                    *:*"no_backup/diagnostics/diagnostics.2.jsonl"*|\
                    *:*"no_backup/diagnostics/diagnostics.3.jsonl"*)
                        exit 44
                        ;;
                    *:*"no_backup/diagnostics/diagnostics.jsonl"*)
                        printf '{"event":"mock-diagnostic"}\n'
                        ;;
                    *)
                        echo 'collector requested a non-allowlisted diagnostic path' >&2
                        exit 95
                        ;;
                esac
                ;;
            *)
                echo "unexpected mock adb shell command: $shell_arguments" >&2
                exit 92
                ;;
        esac
        ;;
    logcat)
        [[ " $* " == *" --uid=10234 "* ]] || {
            echo 'collector attempted unscoped logcat' >&2
            exit 98
        }
        case " $* " in
            *" -d "*) echo "bounded one-shot logcat $*" ;;
            *) echo 'follow logcat line' ;;
        esac
        ;;
    bugreport)
        [[ $# -eq 1 && "$1" == */.bugreport.partial.zip ]] || exit 96
        printf 'mock zipped bugreport\n' > "$1"
        echo 'Bug report finished'
        ;;
    *)
        echo "unexpected mock adb command: $adb_command" >&2
        exit 97
        ;;
esac
EOF
    chmod 755 "$fixture/bin/adb"
}

new_avd_regression_fixture() {
    local name="$1"
    fixture="$test_root/$name"
    mkdir -p \
        "$fixture/scripts" \
        "$fixture/bin" \
        "$fixture/app/src/androidTest" \
        "$fixture/app/build/outputs/apk/debug" \
        "$fixture/app/build/outputs/apk/androidTest/debug" \
        "$fixture/core/data/src/androidTest" \
        "$fixture/core/data/build/outputs/apk/androidTest/debug" \
        "$fixture/core/diagnostics/src/androidTest" \
        "$fixture/core/diagnostics/build/outputs/apk/androidTest/debug" \
        "$fixture/core/llm/src/androidTest" \
        "$fixture/core/llm/build/outputs/apk/androidTest/debug" \
        "$fixture/core/tools/src/androidTest" \
        "$fixture/core/tools/build/outputs/apk/androidTest/debug"
    cp "$project_root/scripts/run-avd-regression.sh" "$fixture/scripts/"
    chmod 755 "$fixture/scripts/run-avd-regression.sh"
    adb_log="$fixture/adb.log"
    gradle_log="$fixture/gradle.log"

    local source_root source_count source_index source_root_and_count
    for source_root_and_count in \
        "$fixture/app/src/androidTest:34" \
        "$fixture/core/data/src/androidTest:12" \
        "$fixture/core/diagnostics/src/androidTest:1" \
        "$fixture/core/llm/src/androidTest:3" \
        "$fixture/core/tools/src/androidTest:3"; do
        source_root="${source_root_and_count%:*}"
        source_count="${source_root_and_count##*:}"
        source_index=1
        while (( source_index <= source_count )); do
            : > "$source_root/Fixture${source_index}Test.kt"
            source_index=$((source_index + 1))
        done
    done

    printf 'fixture app\n' > "$fixture/app/build/outputs/apk/debug/app-debug.apk"
    printf 'fixture app test\n' > \
        "$fixture/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    printf 'fixture data test\n' > \
        "$fixture/core/data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk"
    printf 'fixture diagnostics test\n' > \
        "$fixture/core/diagnostics/build/outputs/apk/androidTest/debug/diagnostics-debug-androidTest.apk"
    printf 'fixture llm test\n' > \
        "$fixture/core/llm/build/outputs/apk/androidTest/debug/llm-debug-androidTest.apk"
    printf 'fixture tools test\n' > \
        "$fixture/core/tools/build/outputs/apk/androidTest/debug/tools-debug-androidTest.apk"

    cat > "$fixture/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\t' "$@" > "$MOCK_GRADLE_LOG"
printf '\n' >> "$MOCK_GRADLE_LOG"
[[ "${MOCK_GRADLE_FAILURE:-0}" == "0" ]]
EOF

    cat > "$fixture/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

[[ $# -ge 3 && "$1" == "-s" && "$2" == "$MOCK_EXPECTED_SERIAL" ]] || {
    echo "mock adb requires the exact explicit emulator serial" >&2
    exit 91
}
printf '%s\t' "$@" >> "$MOCK_ADB_LOG"
printf '\n' >> "$MOCK_ADB_LOG"
shift 2
command_name="$1"
shift

emit_success() {
    local selected="$1"
    local passed="$2"
    local skipped="$3"
    local index=0
    while (( index < passed )); do
        echo 'INSTRUMENTATION_STATUS_CODE: 0'
        index=$((index + 1))
    done
    index=0
    while (( index < skipped )); do
        echo 'INSTRUMENTATION_STATUS_CODE: -4'
        index=$((index + 1))
    done
    echo "OK ($selected tests)"
    echo 'INSTRUMENTATION_CODE: -1'
}

case "$command_name" in
    get-state)
        echo "${MOCK_DEVICE_STATE:-device}"
        ;;
    install)
        [[ "$1" == "-r" && "$2" == "-t" && -s "$3" ]] || exit 92
        [[ "${MOCK_INSTALL_FAILURE:-0}" == "0" ]] || exit 23
        echo Success
        ;;
    shell)
        if [[ "${1:-}" == "getprop" ]]; then
            case "${2:-}" in
                ro.kernel.qemu) echo "${MOCK_KERNEL_QEMU:-1}" ;;
                ro.boot.qemu) echo "${MOCK_BOOT_QEMU:-1}" ;;
                ro.boot.qemu.avd_name) echo "${MOCK_AVD_NAME:-personal_edge_api37_foldable}" ;;
                ro.build.version.sdk) echo "${MOCK_SDK:-37}" ;;
                ro.product.cpu.abi) echo "${MOCK_ABI:-arm64-v8a}" ;;
                sys.boot_completed) echo "${MOCK_BOOT_COMPLETED:-1}" ;;
                *) exit 93 ;;
            esac
        elif [[ "${1:-}" == "am" && "${2:-}" == "instrument" ]]; then
            component=""
            for component in "$@"; do :; done
            if [[ "$component" == "${MOCK_ADB_FAILURE_COMPONENT:-}" ]]; then
                echo 'INSTRUMENTATION_FAILED: fixture transport failure' >&2
                exit 23
            fi
            if [[ "$component" == "${MOCK_JUNIT_FAILURE_COMPONENT:-}" ]]; then
                echo 'FAILURES!!!'
                echo 'Tests run: 3, Failures: 1'
                echo 'INSTRUMENTATION_CODE: -1'
                exit 0
            fi
            case "$component" in
                com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner)
                    expected_classes='com.personaledge.agent.AlarmForegroundRequestTest,com.personaledge.agent.AlarmSetLiveAcceptanceTest,com.personaledge.agent.ChatHistoryCoordinatorTest,com.personaledge.agent.ConversationSummarizerTest,com.personaledge.agent.CredentialSettingsTest,com.personaledge.agent.DeviceExecutionInterlockTest,com.personaledge.agent.Fold8KakaoCommunicationSafetyAcceptanceTest,com.personaledge.agent.Fold8LifecycleAcceptanceTest,com.personaledge.agent.Fold8PreservationSnapshotTest,com.personaledge.agent.Fold8ReminderAcceptanceTest,com.personaledge.agent.Fold8ReminderToolSelectionAcceptanceTest,com.personaledge.agent.Fold8ResponseLanguageAcceptanceTest,com.personaledge.agent.Fold8RuntimePrdAcceptanceTest,com.personaledge.agent.KakaoNotificationLiveStateTest,com.personaledge.agent.KakaoNotificationLiveToolAcceptanceTest,com.personaledge.agent.KakaoReplySettingsTest,com.personaledge.agent.KoreanRouteLiveAcceptanceTest,com.personaledge.agent.NetworkOfflineLiveAcceptanceTest,com.personaledge.agent.NotificationCaptureCoordinatorTest,com.personaledge.agent.NotificationCaptureSinkTest,com.personaledge.agent.NotificationPostReaderTest,com.personaledge.agent.PublicPersonSearchLiveAcceptanceTest,com.personaledge.agent.StoredNotificationGatewayTest,com.personaledge.agent.ThermalStatusMonitorInstrumentedTest,com.personaledge.agent.WeatherLiveToolAcceptanceTest,com.personaledge.agent.WebSearchLiveToolAcceptanceTest,com.personaledge.agent.WebSearchProviderLiveAcceptanceTest'
                    [[ " $* " == *" -e class $expected_classes "* ]] || exit 94
                    emit_success 119 90 29
                    ;;
                com.personaledge.core.data.test/androidx.test.runner.AndroidJUnitRunner)
                    [[ " $* " == *" -e class "* ]] || exit 94
                    emit_success 84 84 0
                    ;;
                com.personaledge.core.diagnostics.test/androidx.test.runner.AndroidJUnitRunner)
                    [[ " $* " == *" -e class "* ]] || exit 94
                    emit_success 2 2 0
                    ;;
                com.personaledge.core.llm.test/androidx.test.runner.AndroidJUnitRunner)
                    [[ " $* " == *" -e class "* ]] || exit 94
                    emit_success 10 9 1
                    ;;
                com.personaledge.core.tools.test/androidx.test.runner.AndroidJUnitRunner)
                    [[ " $* " == *" -e class "* ]] || exit 94
                    emit_success 24 24 0
                    ;;
                *)
                    exit 95
                    ;;
            esac
        else
            exit 96
        fi
        ;;
    *)
        exit 97
        ;;
esac
EOF
    chmod 755 "$fixture/gradlew" "$fixture/bin/adb"
}

new_avd_release_readiness_fixture() {
    local name="$1"
    fixture="$test_root/$name"
    mkdir -p \
        "$fixture/scripts" \
        "$fixture/bin" \
        "$fixture/app/src/androidTest" \
        "$fixture/app/build/outputs/apk/release" \
        "$fixture/app/build/outputs/apk/androidTest/release" \
        "$fixture/app/build/outputs/mapping/release"
    cp "$project_root/scripts/run-avd-release-readiness.sh" "$fixture/scripts/"
    chmod 755 "$fixture/scripts/run-avd-release-readiness.sh"
    adb_log="$fixture/adb.log"
    gradle_log="$fixture/gradle.log"
    release_fixture_certificate="e0f66d4b4c8064db6a9d46097d77903cf13fbccacbdfc6e49e9f7c380b8e457a"

    local source_index=1
    while (( source_index <= 34 )); do
        : > "$fixture/app/src/androidTest/Fixture${source_index}Test.kt"
        source_index=$((source_index + 1))
    done
    printf 'fixture release app\n' > "$fixture/app/build/outputs/apk/release/app-release.apk"
    printf 'fixture release test\n' > \
        "$fixture/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"
    printf 'fixture mapping\n' > "$fixture/app/build/outputs/mapping/release/mapping.txt"
    printf '{"certificateSha256":"%s"}\n' "$release_fixture_certificate" \
        > "$fixture/app/release-signing-identity.json"

    cat > "$fixture/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\t' "$@" > "$MOCK_GRADLE_LOG"
printf '\n' >> "$MOCK_GRADLE_LOG"
[[ "${MOCK_GRADLE_FAILURE:-0}" == "0" ]]
EOF

    cat > "$fixture/bin/apkanalyzer" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == "manifest" ]] || exit 91
case "$2" in
    application-id)
        if [[ "$3" == *androidTest* ]]; then
            echo "${MOCK_TEST_APPLICATION_ID:-com.personaledge.agent.test}"
        else
            echo "${MOCK_APP_APPLICATION_ID:-com.personaledge.agent}"
        fi
        ;;
    print)
        cat <<XML
<manifest>
  <instrumentation android:name="${MOCK_TEST_RUNNER:-androidx.test.runner.AndroidJUnitRunner}" android:targetPackage="${MOCK_TEST_TARGET:-com.personaledge.agent}" />
</manifest>
XML
        ;;
    *) exit 92 ;;
esac
EOF

    cat > "$fixture/bin/apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
apk_path=""
for argument in "$@"; do apk_path="$argument"; done
certificate="${MOCK_EXPECTED_CERT}"
if [[ "$apk_path" == *androidTest* && -n "${MOCK_TEST_CERT:-}" ]]; then
    certificate="$MOCK_TEST_CERT"
fi
echo 'Verified using v3 scheme (APK Signature Scheme v3): true'
echo "Signer #1 certificate SHA-256 digest: $certificate"
EOF

    cat > "$fixture/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ $# -ge 3 && "$1" == "-s" && "$2" == "$MOCK_EXPECTED_SERIAL" ]] || exit 91
printf '%s\t' "$@" >> "$MOCK_ADB_LOG"
printf '\n' >> "$MOCK_ADB_LOG"
shift 2
command_name="$1"
shift

emit_result() {
    local selected="$1"
    local passed="$2"
    local skipped="$3"
    local index=0
    while (( index < passed )); do
        echo 'INSTRUMENTATION_STATUS_CODE: 0'
        index=$((index + 1))
    done
    index=0
    while (( index < skipped )); do
        echo 'INSTRUMENTATION_STATUS_CODE: -4'
        index=$((index + 1))
    done
    echo "OK ($selected tests)"
    echo 'INSTRUMENTATION_CODE: -1'
}

case "$command_name" in
    get-state)
        echo "${MOCK_DEVICE_STATE:-device}"
        ;;
    uninstall)
        [[ "$1" == "com.personaledge.agent" || "$1" == "com.personaledge.agent.test" ]] || exit 92
        echo Success
        ;;
    install)
        [[ "$1" == "-t" && -s "$2" ]] || exit 93
        echo Success
        ;;
    shell)
        if [[ "${1:-}" == "getprop" ]]; then
            case "${2:-}" in
                ro.kernel.qemu) echo "${MOCK_KERNEL_QEMU:-1}" ;;
                ro.boot.qemu) echo "${MOCK_BOOT_QEMU:-1}" ;;
                ro.boot.qemu.avd_name) echo "${MOCK_AVD_NAME:-personal_edge_api37_foldable}" ;;
                ro.build.version.sdk) echo "${MOCK_SDK:-37}" ;;
                ro.product.cpu.abi) echo "${MOCK_ABI:-arm64-v8a}" ;;
                sys.boot_completed) echo "${MOCK_BOOT_COMPLETED:-1}" ;;
                *) exit 94 ;;
            esac
        elif [[ "${1:-}" == "pm" && "${2:-}" == "path" ]]; then
            [[ "${3:-}" == "com.personaledge.agent" ||
               "${3:-}" == "com.personaledge.agent.test" ]] || exit 95
            echo "package:/data/app/mock/${3}/base.apk"
        elif [[ "${1:-}" == "am" && "${2:-}" == "instrument" ]]; then
            all_arguments=" $* "
            abi_method='com.personaledge.agent.ReleasePhysicalAbiLinkageTest#boundedPostGuardEntrypointsResolveFromTheMinifiedTarget'
            canary_classes='com.personaledge.agent.Fold8RuntimePrdAcceptanceTest,com.personaledge.agent.KoreanToolSelectionTest,com.personaledge.agent.PastedMailScheduleAcceptanceTest'
            physical_classes='com.personaledge.agent.AlarmSetLiveAcceptanceTest,com.personaledge.agent.Fold8KakaoCommunicationSafetyAcceptanceTest,com.personaledge.agent.Fold8LifecycleAcceptanceTest,com.personaledge.agent.Fold8PreservationSnapshotTest,com.personaledge.agent.Fold8ReminderAcceptanceTest,com.personaledge.agent.Fold8ReminderToolSelectionAcceptanceTest,com.personaledge.agent.Fold8ResponseLanguageAcceptanceTest,com.personaledge.agent.Fold8RuntimePrdAcceptanceTest,com.personaledge.agent.KakaoNotificationLiveStateTest,com.personaledge.agent.KakaoNotificationLiveToolAcceptanceTest,com.personaledge.agent.KoreanRouteLiveAcceptanceTest,com.personaledge.agent.NetworkOfflineLiveAcceptanceTest,com.personaledge.agent.PublicPersonSearchLiveAcceptanceTest,com.personaledge.agent.WeatherLiveToolAcceptanceTest,com.personaledge.agent.WebSearchLiveToolAcceptanceTest,com.personaledge.agent.WebSearchProviderLiveAcceptanceTest'
            if [[ "$all_arguments" == *" -e class $abi_method "* ]]; then
                suite=abi
            elif [[ "$all_arguments" == *" -e class $canary_classes "* ]]; then
                suite=canary
            elif [[ "$all_arguments" == *" -e class $physical_classes "* ]]; then
                suite=physical
            else
                exit 96
            fi
            if [[ "$suite" == "${MOCK_FAILURE_SUITE:-}" ]]; then
                echo 'FAILURES!!!'
                echo 'Tests run: 1, Failures: 1'
                echo 'INSTRUMENTATION_CODE: -1'
                exit 0
            fi
            case "$suite" in
                abi)
                    [[ "$all_arguments" == *" -e releasePhysicalAbiLinkage true "* ]] || exit 97
                    # One application-owned status plus one runner completion status exercises the
                    # parser without inflating the JUnit pass count.
                    echo 'INSTRUMENTATION_STATUS_CODE: 0'
                    emit_result 1 1 0
                    ;;
                canary) emit_result 5 0 5 ;;
                physical) emit_result 28 0 28 ;;
            esac
        else
            exit 98
        fi
        ;;
    *) exit 99 ;;
esac
EOF
    chmod 755 "$fixture/gradlew" "$fixture/bin/adb" \
        "$fixture/bin/apkanalyzer" "$fixture/bin/apksigner"
}

new_fold8_preflight_fixture() {
    local name="$1"
    fixture="$test_root/$name"
    mkdir -p "$fixture/scripts" "$fixture/app" "$fixture/apks" "$fixture/bin"
    cp "$project_root/scripts/preflight-fold8-release-update.sh" "$fixture/scripts/"
    chmod 755 "$fixture/scripts/preflight-fold8-release-update.sh"
    preflight_certificate="e0f66d4b4c8064db6a9d46097d77903cf13fbccacbdfc6e49e9f7c380b8e457a"
    printf '{"certificateSha256":"%s"}\n' "$preflight_certificate" \
        > "$fixture/app/release-signing-identity.json"
    printf 'candidate-app\n' > "$fixture/apks/app-release.apk"
    printf 'candidate-test\n' > "$fixture/apks/app-release-androidTest.apk"
    printf 'installed-app\n' > "$fixture/apks/installed-source.apk"
    adb_log="$fixture/adb.log"

    cat > "$fixture/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ $# -ge 3 && "$1" == "-s" && "$2" == "$MOCK_EXPECTED_SERIAL" ]] || exit 91
printf '%s\t' "$@" >> "$MOCK_ADB_LOG"
printf '\n' >> "$MOCK_ADB_LOG"
shift 2
command_name="$1"
shift
case "$command_name" in
    get-state)
        echo "${MOCK_DEVICE_STATE:-device}"
        ;;
    shell)
        case "$1:$2" in
            getprop:ro.product.manufacturer) echo "${MOCK_MANUFACTURER:-samsung}" ;;
            getprop:ro.product.model) echo "${MOCK_MODEL:-SM-F971N}" ;;
            getprop:ro.build.version.sdk) echo "${MOCK_SDK:-37}" ;;
            getprop:ro.product.cpu.abi) echo "${MOCK_ABI:-arm64-v8a}" ;;
            getprop:ro.build.fingerprint) echo "${MOCK_FINGERPRINT:-samsung/fold8/release}" ;;
            getprop:ro.kernel.qemu) echo "${MOCK_KERNEL_QEMU:-0}" ;;
            getprop:ro.boot.qemu) echo "${MOCK_BOOT_QEMU:-0}" ;;
            getprop:ro.hardware) echo "${MOCK_HARDWARE:-s5e9945}" ;;
            pm:path)
                [[ "${3:-}" == "com.personaledge.agent" ]] || exit 92
                [[ "${MOCK_PACKAGE_INSTALLED:-1}" == "1" ]] || exit 3
                echo 'package:/data/app/~~fixture==/com.personaledge.agent-fixture==/base.apk'
                ;;
            dumpsys:package)
                [[ "${3:-}" == "com.personaledge.agent" ]] || exit 93
                echo '  firstInstallTime=2026-08-23 18:10:37'
                ;;
            *) exit 94 ;;
        esac
        ;;
    pull)
        [[ "$1" == '/data/app/~~fixture==/com.personaledge.agent-fixture==/base.apk' ]] || exit 95
        cp "$MOCK_INSTALLED_APK" "$2"
        ;;
    *) exit 96 ;;
esac
EOF

    cat > "$fixture/bin/apkanalyzer" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == "manifest" ]] || exit 81
operation="$2"
apk_path="$3"
kind="$(tr -d '\r\n' < "$apk_path")"
case "$operation:$kind" in
    application-id:candidate-app|application-id:installed-app)
        echo com.personaledge.agent
        ;;
    application-id:candidate-test)
        echo "${MOCK_TEST_PACKAGE:-com.personaledge.agent.test}"
        ;;
    version-code:candidate-app) echo 11 ;;
    version-name:candidate-app) echo 1.0.0-rc11 ;;
    version-code:installed-app) echo 10 ;;
    version-name:installed-app) echo 1.0.0-rc10 ;;
    print:candidate-test)
        cat <<MANIFEST
<manifest package="${MOCK_TEST_PACKAGE:-com.personaledge.agent.test}">
    <instrumentation
        android:name="${MOCK_TEST_RUNNER:-androidx.test.runner.AndroidJUnitRunner}"
        android:targetPackage="${MOCK_TEST_TARGET:-com.personaledge.agent}" />
</manifest>
MANIFEST
        ;;
    *) exit 82 ;;
esac
EOF

    cat > "$fixture/bin/apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == "verify" && "$2" == "--print-certs" && "$3" == "--verbose" ]] || exit 71
kind="$(tr -d '\r\n' < "$4")"
case "$kind" in
    candidate-app) certificate="${MOCK_APP_CERT:-$MOCK_EXPECTED_CERT}" ;;
    candidate-test) certificate="${MOCK_TEST_CERT:-$MOCK_EXPECTED_CERT}" ;;
    installed-app) certificate="${MOCK_INSTALLED_CERT:-$MOCK_EXPECTED_CERT}" ;;
    *) exit 72 ;;
esac
echo "Verified using v3 scheme (APK Signature Scheme v3): ${MOCK_V3_VERIFIED:-true}"
echo "Signer #1 certificate SHA-256 digest: $certificate"
EOF
    chmod 755 "$fixture/bin/adb" "$fixture/bin/apkanalyzer" "$fixture/bin/apksigner"
}

locate_single_evidence_report() {
    local -a candidates
    candidates=("$fixture"/reports/fold8-*)
    if (( ${#candidates[@]} != 1 )) || [[ ! -d "${candidates[0]}" || -L "${candidates[0]}" ]]; then
        fail "expected exactly one safe fold8 evidence directory"
    fi
    evidence_report_dir="${candidates[0]}"
    evidence_manifest="$evidence_report_dir/manifest.json"
    [[ -f "$evidence_manifest" && ! -L "$evidence_manifest" ]] || fail "evidence manifest missing"
}

assert_manifest_file_hashes() {
    local relative_path
    local expected_size
    local expected_sha
    local actual_size
    local actual_sha

    while IFS=$'\t' read -r relative_path expected_size expected_sha; do
        [[ "$relative_path" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
            fail "unsafe path in evidence manifest: $relative_path"
        [[ -f "$evidence_report_dir/$relative_path" && ! -L "$evidence_report_dir/$relative_path" ]] ||
            fail "manifest artifact is missing or a symlink: $relative_path"
        actual_size="$(wc -c < "$evidence_report_dir/$relative_path" | tr -d '[:space:]')"
        actual_sha="$(shasum -a 256 "$evidence_report_dir/$relative_path" | awk '{print $1}')"
        [[ "$actual_size" == "$expected_size" && "$actual_sha" == "$expected_sha" ]] ||
            fail "manifest hash or size mismatch: $relative_path"
    done < <(jq -r '.files[] | select(.status == "ok") | [.path, .sizeBytes, .sha256] | @tsv' "$evidence_manifest")
}

write_manifest_for_payload() {
    local payload_path="$1"
    local model_file="${2:-fixture-model.litertlm}"
    payload_size="$(wc -c < "$payload_path" | tr -d '[:space:]')"
    payload_sha="$(shasum -a 256 "$payload_path" | awk '{print $1}')"
    partial_path="$fixture/models/.download-$payload_sha.partial"
    target_path="$fixture/models/$model_file"
    cat > "$fixture/models/model-manifest.json" <<EOF
{
  "schemaVersion": 1,
  "repository": "litert-community/gemma-4-E4B-it-litert-lm",
  "revision": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "file": "$model_file",
  "downloadUrl": "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/$model_file",
  "sizeBytes": $payload_size,
  "sha256": "$payload_sha",
  "litertLmVersion": "0.16.1",
  "contextTokens": 32,
  "maxOutputTokens": 8
}
EOF
}

write_standard_curl_mock() {
    cat > "$fixture/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${MOCK_CURL_MARKER:-}" ]]; then
    : > "$MOCK_CURL_MARKER"
fi
if [[ -n "${MOCK_CURL_ARGS:-}" ]]; then
    printf '%s\n' "$@" > "$MOCK_CURL_ARGS"
fi
if [[ "${MOCK_CURL_SHOULD_NOT_RUN:-0}" == "1" ]]; then
    exit 90
fi

offset=0
while (( $# > 0 )); do
    if [[ "$1" == "--continue-at" ]]; then
        offset="$2"
        shift 2
    else
        shift
    fi
done
dd if="$MOCK_CURL_PAYLOAD" bs=1 skip="$offset" 2>/dev/null
EOF
    chmod 755 "$fixture/bin/curl"
}

test_verified_download_and_protocol_policy() {
    new_model_fixture verified-download
    payload="$fixture/payload"
    printf 'small pinned model payload\n' > "$payload"
    chmod 600 "$payload"
    write_manifest_for_payload "$payload"
    write_standard_curl_mock

    args_log="$fixture/curl.args"
    env PATH="$fixture/bin:$PATH" MOCK_CURL_PAYLOAD="$payload" MOCK_CURL_ARGS="$args_log" \
        "$fixture/scripts/download-model.sh" >"$fixture/download.log"

    cmp "$payload" "$target_path" || fail "installed payload differs"
    [[ ! -e "$partial_path" && ! -L "$partial_path" ]] || fail "partial link remains after install"
    [[ "$(stat -f '%Lp:%l' "$target_path")" == "600:1" ]] || fail "installed mode or link count is unsafe"
    [[ "$(stat -f '%Lp:%l' "$fixture/models/.download.lock")" == "600:1" ]] || fail "OS lock file is unsafe"
    [[ "$(grep -Fxc -- '=https' "$args_log")" == "2" ]] || fail "curl HTTPS protocol restrictions missing"
    grep -Fx -- '--proto' "$args_log" >/dev/null || fail "curl --proto missing"
    grep -Fx -- '--proto-redir' "$args_log" >/dev/null || fail "curl --proto-redir missing"
    for bounded_option in --connect-timeout --speed-limit --speed-time --max-time --max-filesize; do
        grep -Fx -- "$bounded_option" "$args_log" >/dev/null || fail "curl $bounded_option missing"
    done

    no_curl_marker="$fixture/curl-should-not-run"
    env PATH="$fixture/bin:$PATH" MOCK_CURL_PAYLOAD="$payload" MOCK_CURL_MARKER="$no_curl_marker" \
        MOCK_CURL_SHOULD_NOT_RUN=1 "$fixture/scripts/download-model.sh" >"$fixture/existing.log"
    [[ ! -e "$no_curl_marker" ]] || fail "existing verified target triggered curl"
    pass "verified download, HTTPS-only curl, and released OS lock"
}

test_verify_rejects_aliases_and_unsafe_mode() {
    new_model_fixture verify-policy
    payload="$fixture/payload"
    printf 'descriptor verification payload\n' > "$payload"
    chmod 600 "$payload"
    write_manifest_for_payload "$payload"
    cp "$payload" "$target_path"
    chmod 600 "$target_path"

    "$fixture/scripts/verify-model.sh" "$target_path" >"$fixture/verify-ok.log"

    ln -s "$target_path" "$fixture/models/symlink.litertlm"
    expect_failure "$fixture/symlink.log" \
        "$fixture/scripts/verify-model.sh" "$fixture/models/symlink.litertlm"

    ln "$target_path" "$fixture/models/hardlink.litertlm"
    expect_failure "$fixture/hardlink.log" "$fixture/scripts/verify-model.sh" "$target_path"
    unlink "$fixture/models/hardlink.litertlm"

    chmod 644 "$target_path"
    expect_failure "$fixture/mode.log" "$fixture/scripts/verify-model.sh" "$target_path"
    chmod 600 "$target_path"
    pass "single-FD verifier rejects symlink, hardlink, and unsafe mode"
}

test_space_preflight_stops_before_curl() {
    new_model_fixture space-preflight
    payload="$fixture/payload"
    dd if=/dev/zero of="$payload" bs=4096 count=1 2>/dev/null
    chmod 600 "$payload"
    write_manifest_for_payload "$payload"
    write_standard_curl_mock

    cat > "$fixture/bin/df" <<'EOF'
#!/usr/bin/env bash
echo 'Filesystem 1024-blocks Used Available Capacity Mounted on'
echo '/dev/mock 1000 999 1 100% /mock'
EOF
    chmod 755 "$fixture/bin/df"

    curl_marker="$fixture/curl-ran"
    expect_failure "$fixture/preflight.log" env PATH="$fixture/bin:$PATH" MOCK_CURL_PAYLOAD="$payload" \
        MOCK_CURL_MARKER="$curl_marker" "$fixture/scripts/download-model.sh"
    [[ ! -e "$curl_marker" ]] || fail "curl ran despite insufficient space"
    [[ ! -e "$target_path" && ! -L "$target_path" ]] || fail "target published despite insufficient space"
    pass "available-space preflight runs before curl"
}

test_partial_path_swap_is_detected_without_writing_victim() {
    new_model_fixture partial-swap
    payload="$fixture/payload"
    printf 'path swap payload\n' > "$payload"
    chmod 600 "$payload"
    write_manifest_for_payload "$payload"
    victim="$fixture/victim"
    printf 'must stay unchanged\n' > "$victim"
    victim_before="$(shasum -a 256 "$victim" | awk '{print $1}')"
    moved_partial="$fixture/models/moved-partial"

    cat > "$fixture/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
mv "$MOCK_PARTIAL_PATH" "$MOCK_MOVED_PARTIAL"
ln -s "$MOCK_VICTIM" "$MOCK_PARTIAL_PATH"
dd if="$MOCK_CURL_PAYLOAD" bs=1 2>/dev/null
EOF
    chmod 755 "$fixture/bin/curl"

    expect_failure "$fixture/swap.log" env PATH="$fixture/bin:$PATH" MOCK_CURL_PAYLOAD="$payload" \
        MOCK_PARTIAL_PATH="$partial_path" MOCK_MOVED_PARTIAL="$moved_partial" MOCK_VICTIM="$victim" \
        "$fixture/scripts/download-model.sh"
    [[ "$(shasum -a 256 "$victim" | awk '{print $1}')" == "$victim_before" ]] ||
        fail "curl wrote through swapped partial path"
    [[ ! -e "$target_path" && ! -L "$target_path" ]] || fail "target published after partial path swap"
    pass "partial FD prevents path-swap writes"
}

test_failed_publications_are_left_fail_closed() {
    new_model_fixture owned-publication
    payload="$fixture/payload"
    printf 'publication cleanup payload\n' > "$payload"
    chmod 600 "$payload"
    write_manifest_for_payload "$payload"
    write_standard_curl_mock

    cat > "$fixture/bin/ln" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
/bin/ln "$@"
chmod 644 "$MOCK_TARGET_PATH"
EOF
    chmod 755 "$fixture/bin/ln"

    expect_failure "$fixture/owned-publication.log" env PATH="$fixture/bin:$PATH" MOCK_CURL_PAYLOAD="$payload" \
        MOCK_TARGET_PATH="$target_path" "$fixture/scripts/download-model.sh"
    [[ -f "$target_path" && "$(stat -f '%Lp' "$target_path")" == "644" ]] ||
        fail "failed publication was not preserved for fail-closed inspection"
    grep -F 'Leaving failed publication in place for fail-closed inspection' \
        "$fixture/owned-publication.log" >/dev/null || fail "fail-closed cleanup was not reported"

    new_model_fixture rival-preserved
    payload="$fixture/payload"
    printf 'rival preservation payload\n' > "$payload"
    chmod 600 "$payload"
    write_manifest_for_payload "$payload"
    write_standard_curl_mock
    cat > "$fixture/bin/ln" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'rival target\n' > "$MOCK_TARGET_PATH"
chmod 600 "$MOCK_TARGET_PATH"
exit 1
EOF
    chmod 755 "$fixture/bin/ln"

    expect_failure "$fixture/rival.log" env PATH="$fixture/bin:$PATH" MOCK_CURL_PAYLOAD="$payload" \
        MOCK_TARGET_PATH="$target_path" "$fixture/scripts/download-model.sh"
    [[ "$(cat "$target_path")" == "rival target" ]] ||
        fail "target not published by this run was changed or removed"

    new_model_fixture post-publish-rival
    payload="$fixture/payload"
    printf 'post-publication rival payload\n' > "$payload"
    chmod 600 "$payload"
    write_manifest_for_payload "$payload"
    write_standard_curl_mock
    cat > "$fixture/bin/ln" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
/bin/ln "$@"
/bin/unlink "$MOCK_TARGET_PATH"
printf 'post-publish rival\n' > "$MOCK_TARGET_PATH"
chmod 600 "$MOCK_TARGET_PATH"
EOF
    chmod 755 "$fixture/bin/ln"

    expect_failure "$fixture/post-publish-rival.log" env PATH="$fixture/bin:$PATH" \
        MOCK_CURL_PAYLOAD="$payload" MOCK_TARGET_PATH="$target_path" \
        "$fixture/scripts/download-model.sh"
    [[ "$(cat "$target_path")" == "post-publish rival" ]] ||
        fail "post-publication rival was changed or removed"
    grep -F 'Published target identity changed; leaving it untouched' \
        "$fixture/post-publish-rival.log" >/dev/null || fail "rival identity change was not reported"
    pass "failed and raced publications remain fail-closed without deleting a rival"
}

test_corrupt_complete_partial_is_reset_through_owned_fd() {
    new_model_fixture corrupt-complete-partial
    payload="$fixture/payload"
    printf 'replacement for corrupt complete partial\n' > "$payload"
    chmod 600 "$payload"
    write_manifest_for_payload "$payload"
    write_standard_curl_mock
    dd if=/dev/zero of="$partial_path" bs="$payload_size" count=1 2>/dev/null
    chmod 600 "$partial_path"

    env PATH="$fixture/bin:$PATH" MOCK_CURL_PAYLOAD="$payload" \
        "$fixture/scripts/download-model.sh" >"$fixture/download.log" 2>&1
    cmp "$payload" "$target_path" || fail "corrupt complete partial was not replaced"
    [[ ! -e "$partial_path" && ! -L "$partial_path" ]] ||
        fail "partial link remains after corrupt-partial recovery"
    grep -F 'Discarding corrupt partial contents through the owned descriptor' \
        "$fixture/download.log" >/dev/null || fail "descriptor-bound corrupt reset was not reported"
    pass "complete corrupt partial is reset through the owned FD and redownloaded"
}

test_curl_resume_and_retry_exhaustion() {
    new_model_fixture curl-resume
    payload="$fixture/payload"
    printf 'payload long enough for a resumed transfer\n' > "$payload"
    chmod 600 "$payload"
    write_manifest_for_payload "$payload"
    curl_count="$fixture/curl-count"
    curl_offsets="$fixture/curl-offsets"
    cat > "$fixture/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
count=0
if [[ -f "$MOCK_CURL_COUNT" ]]; then
    count="$(cat "$MOCK_CURL_COUNT")"
fi
count=$((count + 1))
printf '%s\n' "$count" > "$MOCK_CURL_COUNT"
offset=0
while (( $# > 0 )); do
    if [[ "$1" == "--continue-at" ]]; then
        offset="$2"
        shift 2
    else
        shift
    fi
done
printf '%s\n' "$offset" >> "$MOCK_CURL_OFFSETS"
if (( count == 1 )); then
    dd if="$MOCK_CURL_PAYLOAD" bs=1 count=5 2>/dev/null
    exit 56
fi
dd if="$MOCK_CURL_PAYLOAD" bs=1 skip="$offset" 2>/dev/null
EOF
    cat > "$fixture/bin/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
    chmod 755 "$fixture/bin/curl" "$fixture/bin/sleep"

    env PATH="$fixture/bin:$PATH" MOCK_CURL_PAYLOAD="$payload" MOCK_CURL_COUNT="$curl_count" \
        MOCK_CURL_OFFSETS="$curl_offsets" "$fixture/scripts/download-model.sh" \
        >"$fixture/resume.log" 2>&1
    cmp "$payload" "$target_path" || fail "resumed transfer did not install the payload"
    [[ "$(cat "$curl_count")" == "2" ]] || fail "resumed transfer did not use exactly two attempts"
    [[ "$(sed -n '1p' "$curl_offsets")" == "0" &&
       "$(sed -n '2p' "$curl_offsets")" == "5" &&
       "$(wc -l < "$curl_offsets" | tr -d '[:space:]')" == "2" ]] ||
        fail "curl did not resume from the descriptor-observed offset"

    new_model_fixture curl-exhaustion
    payload="$fixture/payload"
    printf 'retry exhaustion payload\n' > "$payload"
    chmod 600 "$payload"
    write_manifest_for_payload "$payload"
    curl_count="$fixture/curl-count"
    cat > "$fixture/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
count=0
if [[ -f "$MOCK_CURL_COUNT" ]]; then
    count="$(cat "$MOCK_CURL_COUNT")"
fi
printf '%s\n' "$((count + 1))" > "$MOCK_CURL_COUNT"
exit 28
EOF
    cat > "$fixture/bin/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
    chmod 755 "$fixture/bin/curl" "$fixture/bin/sleep"

    expect_failure "$fixture/exhaustion.log" env PATH="$fixture/bin:$PATH" \
        MOCK_CURL_COUNT="$curl_count" "$fixture/scripts/download-model.sh"
    [[ "$(cat "$curl_count")" == "5" ]] || fail "curl retry count was not bounded at five"
    [[ ! -e "$target_path" && ! -L "$target_path" ]] ||
        fail "retry exhaustion published a target"
    pass "curl resumes from the checked FD size and stops after five attempts"
}

test_os_lock_contends_and_releases() {
    new_model_fixture os-lock
    payload="$fixture/payload"
    printf 'lock lifecycle payload\n' > "$payload"
    chmod 600 "$payload"
    write_manifest_for_payload "$payload"
    write_standard_curl_mock

    lock_path="$fixture/models/.download.lock"
    : > "$lock_path"
    chmod 600 "$lock_path"
    lock_ready="$fixture/lock-ready"
    (
        exec 7<> "$lock_path"
        perl -MFcntl=:flock -e 'flock(STDIN, LOCK_EX | LOCK_NB) or exit 75;' <&7
        : > "$lock_ready"
        sleep 2
    ) &
    lock_holder=$!

    for ((wait_count = 0; wait_count < 100; wait_count++)); do
        [[ -e "$lock_ready" ]] && break
        sleep 0.02
    done
    if [[ ! -e "$lock_ready" ]]; then
        kill "$lock_holder" 2>/dev/null || true
        wait "$lock_holder" 2>/dev/null || true
        fail "lock holder did not become ready"
    fi

    curl_marker="$fixture/curl-ran-under-lock"
    expect_failure "$fixture/contention.log" env PATH="$fixture/bin:$PATH" MOCK_CURL_PAYLOAD="$payload" \
        MOCK_CURL_MARKER="$curl_marker" "$fixture/scripts/download-model.sh"
    [[ ! -e "$curl_marker" ]] || fail "contending download reached curl"
    wait "$lock_holder"

    env PATH="$fixture/bin:$PATH" MOCK_CURL_PAYLOAD="$payload" \
        "$fixture/scripts/download-model.sh" >"$fixture/after-release.log"
    cmp "$payload" "$target_path" || fail "download failed after OS lock release"
    pass "descriptor-bound OS lock contends and releases on owner exit"
}

test_doctor_dynamic_avd_and_versions() {
    doctor_fixture="$test_root/doctor"
    fake_jdk="$doctor_fixture/fake jdk"
    mkdir -p "$doctor_fixture/scripts" "$fake_jdk/bin" \
        "$doctor_fixture/sdk/platforms/android-37.0" "$doctor_fixture/sdk/build-tools/36.0.0" \
        "$doctor_fixture/sdk/platform-tools" \
        "$doctor_fixture/sdk/system-images/android-37.0/google_apis/arm64-v8a" "$doctor_fixture/avd"
    cp "$project_root/scripts/doctor.sh" "$doctor_fixture/scripts/"
    chmod 755 "$doctor_fixture/scripts/doctor.sh"
    : > "$doctor_fixture/gradlew"
    : > "$doctor_fixture/sdk/build-tools/36.0.0/aapt2"
    : > "$doctor_fixture/sdk/platform-tools/adb"
    : > "$doctor_fixture/avd/custom_foldable.ini"
    chmod 755 "$doctor_fixture/gradlew" "$doctor_fixture/sdk/build-tools/36.0.0/aapt2" \
        "$doctor_fixture/sdk/platform-tools/adb"

    cat > "$fake_jdk/bin/java" <<'EOF'
#!/usr/bin/env bash
if [[ "$*" == *"-XshowSettings:properties"* ]]; then
    echo "    java.specification.version = ${MOCK_JAVA_SPEC:-17}" >&2
else
    echo "openjdk version \"${MOCK_JAVA_SPEC:-17}.0.1\"" >&2
fi
EOF
    chmod 755 "$fake_jdk/bin/java"

    env JAVA17_HOME="$fake_jdk" ANDROID_SDK_ROOT="$doctor_fixture/sdk" \
        ANDROID_AVD_HOME="$doctor_fixture/avd" PERSONAL_EDGE_AVD_NAME=custom_foldable \
        "$doctor_fixture/scripts/doctor.sh" >"$doctor_fixture/doctor-ok.log"
    grep -F 'OK   JDK 17:' "$doctor_fixture/doctor-ok.log" >/dev/null ||
        fail "doctor did not accept JDK major 17"
    grep -F 'OK   Android Build Tools 36.0.0' "$doctor_fixture/doctor-ok.log" >/dev/null ||
        fail "doctor did not require Build Tools 36.0.0"
    grep -F 'OK   AVD: custom_foldable' "$doctor_fixture/doctor-ok.log" >/dev/null ||
        fail "doctor ignored PERSONAL_EDGE_AVD_NAME"

    expect_failure "$doctor_fixture/jdk21.log" env MOCK_JAVA_SPEC=21 \
        JAVA17_HOME="$fake_jdk" ANDROID_SDK_ROOT="$doctor_fixture/sdk" \
        ANDROID_AVD_HOME="$doctor_fixture/avd" PERSONAL_EDGE_AVD_NAME=custom_foldable \
        "$doctor_fixture/scripts/doctor.sh"
    pass "doctor checks JDK major 17, Build Tools 36.0.0, and dynamic AVD"
}

test_evidence_requires_serial_and_accepts_missing_diagnostics() {
    new_evidence_fixture evidence-one-shot
    serial_value="RF8X-physical-1234"

    expect_failure "$fixture/missing-serial.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        "$fixture/scripts/collect-fold8-evidence.sh"
    [[ ! -e "$adb_log" ]] || fail "collector invoked adb without an explicit serial"

    expect_failure "$fixture/unsafe-serial.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        "$fixture/scripts/collect-fold8-evidence.sh" --serial ../emulator-5554
    [[ ! -e "$adb_log" ]] || fail "collector invoked adb for an unsafe serial"

    if ! env PATH="$fixture/bin:$PATH" MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_DIAGNOSTIC_MODE=missing "$fixture/scripts/collect-fold8-evidence.sh" \
        --serial "$serial_value" > "$fixture/collector.log" 2>&1; then
        sed -n '1,160p' "$fixture/collector.log" >&2
        fail "one-shot collector failed when diagnostics were simply absent"
    fi
    locate_single_evidence_report

    if ! jq -e '.overallStatus == "ok" and
        .options.bugreport == false and .options.appLogcat == false and
        .options.followLogcat == false and
        (.device.serialSha256 | length) == 64 and
        (.device.serialMasked | startswith("sha256:")) and
        ([.commands[] | select(.id | startswith("diagnostics-")) | select(.status == "not_present")] | length) == 4 and
        ([.commands[] | select((.id | startswith("logcat-")) and .status == "opt_in_not_requested")] | length) == 3 and
        ([.commands[] | select(.id == "bugreport" and .status == "opt_in_not_requested")] | length) == 1' \
        "$evidence_manifest" >/dev/null; then
        sed -n '1,200p' "$fixture/collector.log" >&2
        jq '{overallStatus, commands: [.commands[] | select(.id == "run-as-probe" or (.id | startswith("diagnostics-")))]}' \
            "$evidence_manifest" >&2
        fail "missing diagnostics were not a successful, explicit receipt state"
    fi
    if grep -R -F -- "$serial_value" "$evidence_report_dir" >/dev/null 2>&1; then
        fail "raw device serial leaked into the evidence report"
    fi
    for required_artifact in device-facts.txt package.txt package-uid.txt exit-info.txt meminfo.txt \
        thermalservice.txt disk.txt run-as.txt; do
        [[ -f "$evidence_report_dir/$required_artifact" ]] || fail "missing artifact: $required_artifact"
    done
    grep -F 'ro.product.model=Galaxy Z Fold8' "$evidence_report_dir/device-facts.txt" >/dev/null ||
        fail "device facts were not assembled from allowlisted getprop calls"
    if grep -F $'shell\tsh\t-c\t' "$adb_log" >/dev/null; then
        fail "device facts still use the adb-unsafe remote sh -c argument form"
    fi
    if grep -E 'force-stop|logcat[[:space:]].*-c|uninstall|(^|[[:space:]])install([[:space:]]|$)|pm[[:space:]]+clear|clear-data' \
        "$adb_log" >/dev/null; then
        fail "collector issued a destructive adb command"
    fi
    grep -F -- $'-s\tRF8X-physical-1234\t' "$adb_log" >/dev/null ||
        fail "collector did not pin adb calls to the explicit serial"
    assert_manifest_file_hashes
    pass "evidence collector requires a safe serial and treats absent diagnostics explicitly"
}

test_evidence_release_denial_skips_private_diagnostics() {
    local mode expected_message
    for mode in not_debuggable not_debuggable_legacy; do
        new_evidence_fixture "evidence-release-$mode"
        serial_value="fold8-release-2468"
        if [[ "$mode" == "not_debuggable" ]]; then
            expected_message='run-as: package not debuggable: com.personaledge.agent'
        else
            expected_message="run-as: Package 'com.personaledge.agent' is not debuggable"
        fi

        if ! env PATH="$fixture/bin:$PATH" MOCK_EXPECTED_SERIAL="$serial_value" \
            MOCK_ADB_LOG="$adb_log" MOCK_RUN_AS_MODE="$mode" \
            "$fixture/scripts/collect-fold8-evidence.sh" --serial "$serial_value" \
            > "$fixture/collector.log" 2>&1; then
            sed -n '1,160p' "$fixture/collector.log" >&2
            fail "release collector failed after the expected run-as denial"
        fi
        locate_single_evidence_report

        jq -e '.overallStatus == "ok" and
            ([.commands[] | select(.id == "run-as-probe" and
                .status == "not_applicable_release" and .exitCode == 1 and
                .required == false and .outputPath == "run-as.txt")] | length) == 1 and
            ([.commands[] | select((.id | startswith("diagnostics-")) and
                .status == "not_applicable_release")] | length) == 4' \
            "$evidence_manifest" >/dev/null ||
            fail "release private diagnostics were not explicitly marked not applicable"
        grep -Fx "$expected_message" "$evidence_report_dir/run-as.txt" >/dev/null ||
            fail "release run-as denial was not retained"
        grep -F $'shell\t-T\trun-as\tcom.personaledge.agent\tid\t' "$adb_log" >/dev/null ||
            fail "run-as probe did not use exit-preserving adb shell -T"
        if grep -F $'shell\t-T\trun-as com.personaledge.agent sh -c' "$adb_log" >/dev/null; then
            fail "release collector attempted app-private diagnostics after run-as denial"
        fi
        assert_manifest_file_hashes
    done
    pass "release run-as denial skips private diagnostics without a false failure"
}

test_evidence_unexpected_run_as_failure_stays_failed() {
    new_evidence_fixture evidence-unexpected-run-as
    serial_value="fold8-run-as-failure-9753"

    expect_failure "$fixture/collector.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_RUN_AS_MODE=unexpected_failure \
        "$fixture/scripts/collect-fold8-evidence.sh" --serial "$serial_value"
    locate_single_evidence_report

    jq -e '.overallStatus == "failed" and
        ([.commands[] | select(.id == "run-as-probe" and .status == "failed" and
            .exitCode == 19 and .required == true and .outputPath == "run-as.txt")] | length) == 1 and
        ([.commands[] | select((.id | startswith("diagnostics-")) and
            .status == "dependency_failed")] | length) == 4 and
        ([.commands[] | select((.id | startswith("diagnostics-")) and
            .status == "not_applicable_release")] | length) == 0' \
        "$evidence_manifest" >/dev/null ||
        fail "unexpected run-as failure was not kept fail-closed"
    grep -Fx 'run-as: package lookup failed unexpectedly' \
        "$evidence_report_dir/run-as.txt" >/dev/null ||
        fail "bounded unexpected run-as stderr was not retained"
    if grep -F $'shell\t-T\trun-as com.personaledge.agent sh -c' "$adb_log" >/dev/null; then
        fail "collector attempted private diagnostics after unexpected run-as failure"
    fi
    assert_manifest_file_hashes
    pass "unexpected run-as failures remain failed while retaining bounded probe stderr"
}

test_evidence_partial_failure_keeps_receipt_and_continues() {
    new_evidence_fixture evidence-partial-failure
    serial_value="fold8-partial-5678"

    expect_failure "$fixture/collector.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" MOCK_FAIL_THERMAL=1 \
        "$fixture/scripts/collect-fold8-evidence.sh" --serial "$serial_value"
    locate_single_evidence_report

    jq -e '.overallStatus == "failed" and
        ([.commands[] | select(.id == "thermalservice" and .status == "failed" and .exitCode == 9)] | length) == 1 and
        ([.commands[] | select(.id == "disk" and .status == "ok")] | length) == 1' \
        "$evidence_manifest" >/dev/null || fail "partial failure was not preserved in the receipt"
    [[ -f "$evidence_report_dir/disk.txt" ]] || fail "collector stopped before post-failure disk evidence"
    assert_manifest_file_hashes
    pass "evidence collector continues after failure and exits nonzero with a receipt"
}

test_evidence_refuses_unscoped_logcat_when_uid_is_unavailable() {
    new_evidence_fixture evidence-missing-uid
    serial_value="fold8-missing-uid-2468"

    expect_failure "$fixture/collector.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_FAIL_PACKAGE_UID=1 "$fixture/scripts/collect-fold8-evidence.sh" \
        --serial "$serial_value" --app-logcat
    locate_single_evidence_report

    jq -e '.overallStatus == "failed" and
        ([.commands[] | select(.id == "package-uid" and .status == "failed" and .exitCode == 8)] | length) == 1 and
        ([.commands[] | select((.id | startswith("logcat-")) and .status == "dependency_failed")] | length) == 3 and
        ([.commands[] | select(.id == "exit-info" and .status == "ok")] | length) == 1' \
        "$evidence_manifest" >/dev/null || fail "missing UID did not fail closed while continuing collection"
    if grep -F $'logcat\t' "$adb_log" >/dev/null; then
        fail "collector requested broad logcat after package UID lookup failed"
    fi
    [[ -f "$evidence_report_dir/exit-info.txt" ]] || fail "collector stopped after UID failure"
    pass "evidence collector refuses broad logcat and continues when package UID is unavailable"
}

test_evidence_caps_oversize_diagnostics() {
    new_evidence_fixture evidence-oversize
    serial_value="fold8-oversize-9012"

    expect_failure "$fixture/collector.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" MOCK_DIAGNOSTIC_MODE=oversize \
        "$fixture/scripts/collect-fold8-evidence.sh" --serial "$serial_value"
    locate_single_evidence_report

    [[ ! -e "$evidence_report_dir/diagnostics.jsonl" &&
       ! -L "$evidence_report_dir/diagnostics.jsonl" ]] || fail "oversize diagnostic was published"
    jq -e '.overallStatus == "failed" and
        ([.commands[] | select(.id == "diagnostics-active" and .status == "truncated" and .capBytes == 5242880)] | length) == 1 and
        .limits.diagnosticTotalBytes == 20971520' "$evidence_manifest" >/dev/null ||
        fail "oversize diagnostic cap was not receipted"
    grep -F '/proc/$$/fd/7' "$adb_log" >/dev/null || fail "remote parent-shell FD identity check missing"
    grep -F -- '-L -c "%d:%i:%h:%s"' "$adb_log" >/dev/null ||
        fail "remote diagnostic regular/link/identity stat missing"
    grep -F $'shell\t-T\trun-as com.personaledge.agent sh -c' "$adb_log" >/dev/null ||
        fail "diagnostics did not use exit-preserving adb shell -T"
    [[ -f "$evidence_report_dir/disk.txt" ]] || fail "collector stopped after oversize diagnostic"
    pass "diagnostic output is bounded and unsafe oversize collection fails closed"
}

test_evidence_validates_jsonl_and_distinguishes_empty_from_missing() {
    new_evidence_fixture evidence-invalid-jsonl
    serial_value="fold8-invalid-jsonl-1357"

    expect_failure "$fixture/collector.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" MOCK_DIAGNOSTIC_MODE=invalid \
        "$fixture/scripts/collect-fold8-evidence.sh" --serial "$serial_value"
    locate_single_evidence_report
    [[ ! -e "$evidence_report_dir/diagnostics.jsonl" ]] ||
        fail "non-JSON diagnostic output was published"
    jq -e '.overallStatus == "failed" and
        ([.commands[] | select(.id == "diagnostics-active" and .status == "invalid_jsonl")] | length) == 1' \
        "$evidence_manifest" >/dev/null || fail "invalid JSONL was not rejected in the receipt"

    new_evidence_fixture evidence-empty-jsonl
    serial_value="fold8-empty-jsonl-8642"
    env PATH="$fixture/bin:$PATH" MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_DIAGNOSTIC_MODE=empty_archives "$fixture/scripts/collect-fold8-evidence.sh" \
        --serial "$serial_value" > "$fixture/collector.log" 2>&1
    locate_single_evidence_report
    jq -e '.overallStatus == "ok" and
        ([.commands[] | select((.id == "diagnostics-1" or .id == "diagnostics-2" or .id == "diagnostics-3") and .status == "ok")] | length) == 3 and
        ([.files[] | select((.path == "diagnostics.1.jsonl" or .path == "diagnostics.2.jsonl" or .path == "diagnostics.3.jsonl") and .sizeBytes == 0)] | length) == 3' \
        "$evidence_manifest" >/dev/null || fail "verified empty JSONL archives were confused with missing files"
    pass "diagnostics require JSON objects per line and verified empty files remain distinguishable"
}

test_evidence_bugreport_and_follow_are_explicit_opt_ins() {
    new_evidence_fixture evidence-opt-ins
    serial_value="fold8-optin-3456"

    env PATH="$fixture/bin:$PATH" MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        "$fixture/scripts/collect-fold8-evidence.sh" --serial "$serial_value" \
        --app-logcat --bugreport --follow-logcat > "$fixture/collector.log" 2>&1
    locate_single_evidence_report

    [[ "$(cat "$evidence_report_dir/bugreport.zip")" == "mock zipped bugreport" ]] ||
        fail "opt-in bugreport was not collected"
    grep -F 'follow logcat line' "$fixture/collector.log" >/dev/null ||
        fail "opt-in follow logcat was not streamed"
    jq -e '.overallStatus == "ok" and .options.appLogcat == true and
        .options.bugreport == true and
        .options.followLogcat == true and
        ([.commands[] | select(.id == "bugreport" and .status == "ok")] | length) == 1 and
        ([.commands[] | select(.id == "follow-logcat" and .status == "completed")] | length) == 1 and
        ([.files[] | select(.path == "bugreport.zip" and .status == "ok")] | length) == 1' \
        "$evidence_manifest" >/dev/null || fail "opt-in statuses missing from receipt"
    grep -F $'bugreport\t' "$adb_log" >/dev/null || fail "--bugreport did not invoke adb bugreport"
    [[ -f "$evidence_report_dir/logcat-main.txt" &&
       -f "$evidence_report_dir/logcat-system.txt" &&
       -f "$evidence_report_dir/logcat-crash.txt" ]] || fail "--app-logcat did not collect UID-scoped logs"
    assert_manifest_file_hashes
    pass "app logcat, bugreport, and follow-logcat run only with explicit opt-in"
}

test_avd_regression_rejects_phone_and_untrusted_qemu_before_mutation() {
    new_avd_regression_fixture avd-regression-device-rejection
    serial_value="emulator-5582"

    expect_failure "$fixture/physical-serial.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_GRADLE_LOG="$gradle_log" \
        "$fixture/scripts/run-avd-regression.sh" --serial R5KL801YXWE --confirm-disposable
    [[ ! -e "$adb_log" ]] || fail "AVD runner invoked ADB for a physical-device serial"

    expect_failure "$fixture/missing-confirmation.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_GRADLE_LOG="$gradle_log" \
        "$fixture/scripts/run-avd-regression.sh" --serial "$serial_value"
    [[ ! -e "$adb_log" ]] || fail "AVD runner invoked ADB without disposable-state confirmation"

    expect_failure "$fixture/qemu.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_GRADLE_LOG="$gradle_log" MOCK_KERNEL_QEMU=0 MOCK_BOOT_QEMU=0 \
        "$fixture/scripts/run-avd-regression.sh" --serial "$serial_value" --confirm-disposable
    grep -F 'trusted emulator identity' "$fixture/qemu.log" >/dev/null ||
        fail "AVD runner did not explain the qemu identity rejection"
    if grep -E $'\t(install|uninstall)\t|\tshell\tam\tinstrument\t' "$adb_log" >/dev/null; then
        fail "AVD runner mutated or instrumented an untrusted transport"
    fi
    [[ ! -e "$gradle_log" ]] || fail "AVD runner built before verifying emulator identity"
    pass "AVD regression refuses physical serials and untrusted qemu before mutation"
}

test_avd_regression_guards_name_api_abi_and_boot_before_install() {
    local guard
    serial_value="emulator-5582"
    for guard in name api abi boot; do
        new_avd_regression_fixture "avd-regression-$guard-rejection"
        case "$guard" in
            name)
                override=(MOCK_AVD_NAME=personal_edge_api37_model)
                ;;
            api)
                override=(MOCK_SDK=36)
                ;;
            abi)
                override=(MOCK_ABI=x86_64)
                ;;
            boot)
                override=(MOCK_BOOT_COMPLETED=0)
                ;;
        esac
        expect_failure "$fixture/$guard.log" env PATH="$fixture/bin:$PATH" \
            MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
            MOCK_GRADLE_LOG="$gradle_log" "${override[@]}" \
            "$fixture/scripts/run-avd-regression.sh" --serial "$serial_value" --confirm-disposable
        if grep -E $'\t(install|uninstall)\t|\tshell\tam\tinstrument\t' "$adb_log" >/dev/null; then
            fail "AVD $guard rejection reached installation or instrumentation"
        fi
        [[ ! -e "$gradle_log" ]] || fail "AVD $guard rejection reached Gradle"
    done
    pass "AVD regression guards expected name, API 37, arm64 ABI, and completed boot"
}

test_avd_regression_runs_scoped_suites_and_owner_exclusions() {
    new_avd_regression_fixture avd-regression-success
    serial_value="emulator-5582"
    if ! env PATH="$fixture/bin:$PATH" MOCK_EXPECTED_SERIAL="$serial_value" \
        MOCK_ADB_LOG="$adb_log" MOCK_GRADLE_LOG="$gradle_log" \
        "$fixture/scripts/run-avd-regression.sh" --serial "$serial_value" --confirm-disposable \
        > "$fixture/success.log" 2>&1; then
        sed -n '1,240p' "$fixture/success.log" >&2
        fail "valid AVD regression fixture failed"
    fi

    for task_name in :app:assembleDebug :app:assembleDebugAndroidTest \
        :core:data:assembleDebugAndroidTest :core:diagnostics:assembleDebugAndroidTest \
        :core:llm:assembleDebugAndroidTest :core:tools:assembleDebugAndroidTest; do
        grep -F -- "$task_name" "$gradle_log" >/dev/null ||
            fail "AVD runner omitted build task $task_name"
    done
    [[ "$(grep -c $'\tinstall\t-r\t-t\t' "$adb_log")" == "6" ]] ||
        fail "AVD runner did not install the exact six debug APKs"
    [[ "$(grep -c $'\tshell\tam\tinstrument\t' "$adb_log")" == "5" ]] ||
        fail "AVD runner did not run the exact five valid instrumentation suites"
    grep -F -- $'\t-e\tclass\tcom.personaledge.agent.AlarmForegroundRequestTest,' \
        "$adb_log" >/dev/null || fail "AVD runner did not use the reviewed app class allowlist"
    if grep -F -- $'\t-e\tnotClass\t' "$adb_log" >/dev/null; then
        fail "AVD runner used a future-open negative class filter"
    fi
    grep -F 'tests.selected=239' "$fixture/success.log" >/dev/null ||
        fail "AVD runner did not account for every selected test"
    grep -F 'tests.passed=209' "$fixture/success.log" >/dev/null ||
        fail "AVD runner pass accounting is wrong"
    grep -F 'tests.guardedSkip=30' "$fixture/success.log" >/dev/null ||
        fail "AVD runner guarded-skip accounting is wrong"
    grep -F 'tests.failed=0' "$fixture/success.log" >/dev/null ||
        fail "AVD runner did not emit a zero-failure receipt"
    if grep -E $'\t(uninstall|clear|force-stop)\t' "$adb_log" >/dev/null; then
        fail "AVD runner issued an out-of-scope destructive ADB command"
    fi
    pass "AVD regression runs five scoped suites with exact owner and model exclusions"
}

test_avd_regression_propagates_transport_and_junit_failures() {
    local failure_mode failure_component expected_message
    serial_value="emulator-5582"
    failure_component='com.personaledge.core.tools.test/androidx.test.runner.AndroidJUnitRunner'
    for failure_mode in transport junit; do
        new_avd_regression_fixture "avd-regression-$failure_mode-failure"
        if [[ "$failure_mode" == "transport" ]]; then
            override=(MOCK_ADB_FAILURE_COMPONENT="$failure_component")
            expected_message='tools instrumentation command failed'
        else
            override=(MOCK_JUNIT_FAILURE_COMPONENT="$failure_component")
            expected_message='tools instrumentation reported a test or process failure'
        fi
        expect_failure "$fixture/$failure_mode.log" env PATH="$fixture/bin:$PATH" \
            MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
            MOCK_GRADLE_LOG="$gradle_log" "${override[@]}" \
            "$fixture/scripts/run-avd-regression.sh" --serial "$serial_value" --confirm-disposable
        grep -F "$expected_message" "$fixture/$failure_mode.log" >/dev/null ||
            fail "AVD runner hid the $failure_mode instrumentation failure"
        grep -F -- $'\tshell\tam\tinstrument\t' "$adb_log" >/dev/null ||
            fail "AVD runner did not invoke instrumentation"
        grep -F -- "$failure_component" "$adb_log" >/dev/null ||
            fail "AVD runner did not reach the failing suite"
    done
    pass "AVD regression propagates transport and zero-exit JUnit failures"
}

test_avd_release_readiness_rejects_phone_and_untrusted_qemu_before_mutation() {
    new_avd_release_readiness_fixture avd-release-device-rejection
    serial_value="emulator-5584"

    expect_failure "$fixture/physical-serial.log" env PATH="$fixture/bin:$PATH" \
        ADB="$fixture/bin/adb" APKANALYZER="$fixture/bin/apkanalyzer" \
        APKSIGNER="$fixture/bin/apksigner" GRADLEW="$fixture/gradlew" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_GRADLE_LOG="$gradle_log" MOCK_EXPECTED_CERT="$release_fixture_certificate" \
        "$fixture/scripts/run-avd-release-readiness.sh" \
        --serial R5KL801YXWE --confirm-disposable
    [[ ! -e "$adb_log" && ! -e "$gradle_log" ]] ||
        fail "release AVD runner touched tools for a physical serial"

    expect_failure "$fixture/missing-confirmation.log" env PATH="$fixture/bin:$PATH" \
        ADB="$fixture/bin/adb" APKANALYZER="$fixture/bin/apkanalyzer" \
        APKSIGNER="$fixture/bin/apksigner" GRADLEW="$fixture/gradlew" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_GRADLE_LOG="$gradle_log" MOCK_EXPECTED_CERT="$release_fixture_certificate" \
        "$fixture/scripts/run-avd-release-readiness.sh" --serial "$serial_value"
    [[ ! -e "$adb_log" && ! -e "$gradle_log" ]] ||
        fail "release AVD runner touched tools without disposable confirmation"

    expect_failure "$fixture/qemu.log" env PATH="$fixture/bin:$PATH" \
        ADB="$fixture/bin/adb" APKANALYZER="$fixture/bin/apkanalyzer" \
        APKSIGNER="$fixture/bin/apksigner" GRADLEW="$fixture/gradlew" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_GRADLE_LOG="$gradle_log" MOCK_EXPECTED_CERT="$release_fixture_certificate" \
        MOCK_KERNEL_QEMU=0 MOCK_BOOT_QEMU=0 \
        "$fixture/scripts/run-avd-release-readiness.sh" \
        --serial "$serial_value" --confirm-disposable
    if grep -E $'\t(uninstall|install)\t|\tshell\tam\tinstrument\t' "$adb_log" >/dev/null; then
        fail "release AVD runner mutated or instrumented an untrusted transport"
    fi
    [[ ! -e "$gradle_log" ]] || fail "release AVD runner built before qemu verification"
    pass "release AVD readiness refuses physical serials and untrusted qemu before mutation"
}

test_avd_release_readiness_runs_exact_paired_release_lane() {
    new_avd_release_readiness_fixture avd-release-success
    serial_value="emulator-5584"
    if ! env PATH="$fixture/bin:$PATH" \
        ADB="$fixture/bin/adb" APKANALYZER="$fixture/bin/apkanalyzer" \
        APKSIGNER="$fixture/bin/apksigner" GRADLEW="$fixture/gradlew" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_GRADLE_LOG="$gradle_log" MOCK_EXPECTED_CERT="$release_fixture_certificate" \
        "$fixture/scripts/run-avd-release-readiness.sh" \
        --serial "$serial_value" --confirm-disposable > "$fixture/success.log" 2>&1; then
        sed -n '1,240p' "$fixture/success.log" >&2
        fail "valid release AVD readiness fixture failed"
    fi

    grep -F -- '-PpersonalEdgePhysicalReleaseTest=true' "$gradle_log" >/dev/null ||
        fail "release AVD runner did not select the minified physical test target"
    grep -F -- ':app:assembleRelease' "$gradle_log" >/dev/null ||
        fail "release AVD runner omitted the release app build"
    grep -F -- ':app:assembleReleaseAndroidTest' "$gradle_log" >/dev/null ||
        fail "release AVD runner omitted the matched release test build"
    [[ "$(grep -c $'\tuninstall\t' "$adb_log")" == "2" ]] ||
        fail "release AVD runner did not replace the exact two disposable packages"
    [[ "$(grep -c $'\tinstall\t-t\t' "$adb_log")" == "2" ]] ||
        fail "release AVD runner did not install the exact release pair"
    [[ "$(grep -c $'\tshell\tam\tinstrument\t' "$adb_log")" == "3" ]] ||
        fail "release AVD runner did not run the exact three readiness suites"
    grep -F 'ReleasePhysicalAbiLinkageTest#boundedPostGuardEntrypointsResolveFromTheMinifiedTarget' \
        "$adb_log" >/dev/null || fail "release ABI method was not selected exactly"
    grep -F 'Fold8RuntimePrdAcceptanceTest,com.personaledge.agent.KoreanToolSelectionTest,com.personaledge.agent.PastedMailScheduleAcceptanceTest' \
        "$adb_log" >/dev/null || fail "release five-case canary allowlist changed"
    if grep -F 'AlarmForegroundRequestTest' "$adb_log" >/dev/null; then
        fail "release AVD runner selected a debug-only instrumentation class"
    fi
    grep -F 'tests.selected=34' "$fixture/success.log" >/dev/null ||
        fail "release AVD runner selected-count receipt is wrong"
    grep -F 'tests.passed=1' "$fixture/success.log" >/dev/null ||
        fail "release AVD runner pass receipt is wrong"
    grep -F 'tests.guardedSkip=33' "$fixture/success.log" >/dev/null ||
        fail "release AVD runner guard receipt is wrong"
    grep -F "release.certificateSha256=$release_fixture_certificate" \
        "$fixture/success.log" >/dev/null || fail "release certificate was not bound to the receipt"
    pass "release AVD readiness binds one release pair to exact ABI and guard allowlists"
}

test_avd_release_readiness_rejects_stale_identity_and_junit_failure() {
    new_avd_release_readiness_fixture avd-release-certificate-failure
    serial_value="emulator-5584"
    expect_failure "$fixture/certificate.log" env PATH="$fixture/bin:$PATH" \
        ADB="$fixture/bin/adb" APKANALYZER="$fixture/bin/apkanalyzer" \
        APKSIGNER="$fixture/bin/apksigner" GRADLEW="$fixture/gradlew" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_GRADLE_LOG="$gradle_log" MOCK_EXPECTED_CERT="$release_fixture_certificate" \
        MOCK_TEST_CERT=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
        "$fixture/scripts/run-avd-release-readiness.sh" \
        --serial "$serial_value" --confirm-disposable
    grep -F 'release test certificate is not the owner identity' "$fixture/certificate.log" >/dev/null ||
        fail "release AVD runner hid the mismatched test certificate"
    if grep -E $'\t(uninstall|install)\t|\tshell\tam\tinstrument\t' "$adb_log" >/dev/null; then
        fail "release AVD runner mutated after a certificate mismatch"
    fi

    new_avd_release_readiness_fixture avd-release-junit-failure
    expect_failure "$fixture/junit.log" env PATH="$fixture/bin:$PATH" \
        ADB="$fixture/bin/adb" APKANALYZER="$fixture/bin/apkanalyzer" \
        APKSIGNER="$fixture/bin/apksigner" GRADLEW="$fixture/gradlew" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_GRADLE_LOG="$gradle_log" MOCK_EXPECTED_CERT="$release_fixture_certificate" \
        MOCK_FAILURE_SUITE=canary \
        "$fixture/scripts/run-avd-release-readiness.sh" \
        --serial "$serial_value" --confirm-disposable
    grep -F 'canary instrumentation reported a test or process failure' "$fixture/junit.log" >/dev/null ||
        fail "release AVD runner hid a zero-exit JUnit failure"
    pass "release AVD readiness rejects signer drift and zero-exit JUnit failures"
}

test_fold8_preflight_verifies_release_pair_and_installed_app_read_only() {
    new_fold8_preflight_fixture fold8-preflight-ok
    serial_value="R5KL801YXWE"
    if ! env PATH="$fixture/bin:$PATH" MOCK_EXPECTED_SERIAL="$serial_value" \
        MOCK_ADB_LOG="$adb_log" MOCK_INSTALLED_APK="$fixture/apks/installed-source.apk" \
        MOCK_EXPECTED_CERT="$preflight_certificate" \
        "$fixture/scripts/preflight-fold8-release-update.sh" \
        --serial "$serial_value" \
        --app-apk "$fixture/apks/app-release.apk" \
        --test-apk "$fixture/apks/app-release-androidTest.apk" \
        > "$fixture/preflight.log" 2>&1; then
        sed -n '1,200p' "$fixture/preflight.log" >&2
        fail "valid Fold8 release preflight failed"
    fi

    grep -F 'device.model=SM-F971N' "$fixture/preflight.log" >/dev/null ||
        fail "preflight did not report the exact Fold8 model"
    grep -F 'candidate.versionCode=11' "$fixture/preflight.log" >/dev/null ||
        fail "preflight did not report the candidate version"
    grep -F 'installed.versionName=1.0.0-rc10' "$fixture/preflight.log" >/dev/null ||
        fail "preflight did not report the installed version"
    grep -F 'installed.firstInstallTime=2026-08-23 18:10:37' \
        "$fixture/preflight.log" >/dev/null || fail "preflight did not report firstInstallTime"
    grep -F "signing.certificateSha256=$preflight_certificate" "$fixture/preflight.log" >/dev/null ||
        fail "preflight did not bind the owner certificate"
    for hash_field in candidate.appApkSha256 candidate.testApkSha256 installed.appApkSha256; do
        grep -E "^${hash_field}=[0-9a-f]{64}$" "$fixture/preflight.log" >/dev/null ||
            fail "preflight did not report $hash_field"
    done
    grep -F $'pull\t/data/app/~~fixture==/com.personaledge.agent-fixture==/base.apk\t' \
        "$adb_log" >/dev/null || fail "preflight did not pull the installed base APK read-only"
    if grep -Ei '(^|[[:space:]])(install|uninstall|instrument|grant|revoke|clear|force-stop|settings|input)([[:space:]]|$)' \
        "$adb_log" >/dev/null; then
        fail "Fold8 preflight issued a state-changing ADB command"
    fi
    pass "Fold8 preflight binds release APKs and installed base APK without device mutation"
}

test_fold8_preflight_rejects_unsafe_or_wrong_device_before_pull() {
    new_fold8_preflight_fixture fold8-preflight-device-rejection
    serial_value="R5KL801YXWE"
    expect_failure "$fixture/missing-serial.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_INSTALLED_APK="$fixture/apks/installed-source.apk" MOCK_EXPECTED_CERT="$preflight_certificate" \
        "$fixture/scripts/preflight-fold8-release-update.sh" \
        --app-apk "$fixture/apks/app-release.apk" \
        --test-apk "$fixture/apks/app-release-androidTest.apk"
    [[ ! -e "$adb_log" ]] || fail "preflight invoked ADB without an explicit serial"

    for override in MOCK_MODEL=not-the-owner-fold8 MOCK_SDK=36 MOCK_ABI=x86_64 MOCK_KERNEL_QEMU=1; do
        : > "$adb_log"
        expect_failure "$fixture/${override%%=*}.log" env PATH="$fixture/bin:$PATH" \
            MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
            MOCK_INSTALLED_APK="$fixture/apks/installed-source.apk" MOCK_EXPECTED_CERT="$preflight_certificate" \
            "$override" "$fixture/scripts/preflight-fold8-release-update.sh" \
            --serial "$serial_value" --app-apk "$fixture/apks/app-release.apk" \
            --test-apk "$fixture/apks/app-release-androidTest.apk"
        if grep -F $'pull\t' "$adb_log" >/dev/null; then
            fail "wrong-device preflight reached installed APK pull: $override"
        fi
    done
    pass "Fold8 preflight rejects missing serial, wrong hardware, API, ABI, and emulator identity"
}

test_fold8_preflight_fails_closed_on_package_manifest_or_certificate_mismatch() {
    serial_value="R5KL801YXWE"

    new_fold8_preflight_fixture fold8-preflight-target-mismatch
    expect_failure "$fixture/target.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_INSTALLED_APK="$fixture/apks/installed-source.apk" MOCK_EXPECTED_CERT="$preflight_certificate" \
        MOCK_TEST_TARGET=com.example.wrong \
        "$fixture/scripts/preflight-fold8-release-update.sh" --serial "$serial_value" \
        --app-apk "$fixture/apks/app-release.apk" --test-apk "$fixture/apks/app-release-androidTest.apk"
    [[ ! -e "$adb_log" ]] || fail "test-manifest mismatch reached ADB"

    new_fold8_preflight_fixture fold8-preflight-host-cert-mismatch
    expect_failure "$fixture/host-cert.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_INSTALLED_APK="$fixture/apks/installed-source.apk" MOCK_EXPECTED_CERT="$preflight_certificate" \
        MOCK_TEST_CERT=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
        "$fixture/scripts/preflight-fold8-release-update.sh" --serial "$serial_value" \
        --app-apk "$fixture/apks/app-release.apk" --test-apk "$fixture/apks/app-release-androidTest.apk"
    [[ ! -e "$adb_log" ]] || fail "host test-certificate mismatch reached ADB"

    new_fold8_preflight_fixture fold8-preflight-v3-missing
    expect_failure "$fixture/v3.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_INSTALLED_APK="$fixture/apks/installed-source.apk" MOCK_EXPECTED_CERT="$preflight_certificate" \
        MOCK_V3_VERIFIED=false \
        "$fixture/scripts/preflight-fold8-release-update.sh" --serial "$serial_value" \
        --app-apk "$fixture/apks/app-release.apk" --test-apk "$fixture/apks/app-release-androidTest.apk"
    grep -F 'not verified with APK Signature Scheme v3' "$fixture/v3.log" >/dev/null ||
        fail "non-v3 APK rejection was not explicit"
    [[ ! -e "$adb_log" ]] || fail "non-v3 host APK reached ADB"

    new_fold8_preflight_fixture fold8-preflight-cert-mismatch
    expect_failure "$fixture/cert.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_INSTALLED_APK="$fixture/apks/installed-source.apk" MOCK_EXPECTED_CERT="$preflight_certificate" \
        MOCK_INSTALLED_CERT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
        "$fixture/scripts/preflight-fold8-release-update.sh" --serial "$serial_value" \
        --app-apk "$fixture/apks/app-release.apk" --test-apk "$fixture/apks/app-release-androidTest.apk"
    grep -F 'installed app certificate does not match' "$fixture/cert.log" >/dev/null ||
        fail "installed certificate mismatch was not explicit"

    new_fold8_preflight_fixture fold8-preflight-package-missing
    expect_failure "$fixture/package.log" env PATH="$fixture/bin:$PATH" \
        MOCK_EXPECTED_SERIAL="$serial_value" MOCK_ADB_LOG="$adb_log" \
        MOCK_INSTALLED_APK="$fixture/apks/installed-source.apk" MOCK_EXPECTED_CERT="$preflight_certificate" \
        MOCK_PACKAGE_INSTALLED=0 \
        "$fixture/scripts/preflight-fold8-release-update.sh" --serial "$serial_value" \
        --app-apk "$fixture/apks/app-release.apk" --test-apk "$fixture/apks/app-release-androidTest.apk"
    if grep -F $'pull\t' "$adb_log" >/dev/null; then
        fail "missing installed package reached APK pull"
    fi
    pass "Fold8 preflight fails closed on test target, package, and owner certificate mismatch"
}

test_signing_recovery_verifies_copy_and_private_key() {
    local recovery_fixture="$test_root/signing-recovery"
    local fixture_password='fixture-recovery-password-123'
    local fixture_alias='personal-edge-release'
    mkdir -p "$recovery_fixture/scripts" "$recovery_fixture/app" "$recovery_fixture/offline"
    cp "$project_root/scripts/verify-signing-recovery.sh" "$recovery_fixture/scripts/"
    chmod 755 "$recovery_fixture/scripts/verify-signing-recovery.sh"

    keytool -genkeypair \
        -keystore "$recovery_fixture/app/personal-edge-release.jks" \
        -storetype PKCS12 \
        -storepass "$fixture_password" \
        -keypass "$fixture_password" \
        -alias "$fixture_alias" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 2 \
        -dname 'CN=Recovery Fixture' >/dev/null 2>&1
    chmod 600 "$recovery_fixture/app/personal-edge-release.jks"

    local fixture_keystore_sha fixture_certificate_sha
    fixture_keystore_sha="$(
        shasum -a 256 "$recovery_fixture/app/personal-edge-release.jks" | awk '{print $1}'
    )"
    fixture_certificate_sha="$(
        LC_ALL=C keytool -list -v \
            -keystore "$recovery_fixture/app/personal-edge-release.jks" \
            -storepass "$fixture_password" \
            -alias "$fixture_alias" |
            sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' |
            head -n 1 |
            tr -d ':' |
            tr '[:upper:]' '[:lower:]'
    )"
    cat > "$recovery_fixture/app/release-signing-identity.json" <<EOF
{
  "schemaVersion": 1,
  "keyAlias": "$fixture_alias",
  "keystoreSha256": "$fixture_keystore_sha",
  "certificateSha256": "$fixture_certificate_sha"
}
EOF

    cp "$recovery_fixture/app/personal-edge-release.jks" \
        "$recovery_fixture/offline/personal-edge-release.jks"
    chmod 600 "$recovery_fixture/offline/personal-edge-release.jks"
    env PERSONAL_EDGE_BACKUP_STORE_PASSWORD="$fixture_password" \
        PERSONAL_EDGE_BACKUP_KEY_PASSWORD="$fixture_password" \
        "$recovery_fixture/scripts/verify-signing-recovery.sh" \
        "$recovery_fixture/offline/personal-edge-release.jks" \
        > "$recovery_fixture/success.log"
    grep -F 'private-key password are recoverable' "$recovery_fixture/success.log" >/dev/null ||
        fail "recovery verifier did not exercise the private key"

    expect_failure "$recovery_fixture/original.log" \
        "$recovery_fixture/scripts/verify-signing-recovery.sh" \
        "$recovery_fixture/app/personal-edge-release.jks"
    grep -F 'not the repository keystore itself' "$recovery_fixture/original.log" >/dev/null ||
        fail "recovery verifier accepted the repository keystore as a backup"

    ln -s "$recovery_fixture/offline/personal-edge-release.jks" \
        "$recovery_fixture/offline/symlink.jks"
    expect_failure "$recovery_fixture/symlink.log" \
        "$recovery_fixture/scripts/verify-signing-recovery.sh" \
        "$recovery_fixture/offline/symlink.jks"
    grep -F 'regular non-symlink file' "$recovery_fixture/symlink.log" >/dev/null ||
        fail "recovery verifier followed a symlink"

    printf 'corrupt recovery copy\n' > "$recovery_fixture/offline/corrupt.jks"
    expect_failure "$recovery_fixture/corrupt.log" \
        "$recovery_fixture/scripts/verify-signing-recovery.sh" \
        "$recovery_fixture/offline/corrupt.jks"
    grep -F 'SHA-256 mismatch' "$recovery_fixture/corrupt.log" >/dev/null ||
        fail "recovery verifier did not reject a changed copy before password input"

    pass "signing recovery verifies exact copy, certificate, and private-key access"
}

test_model_eval_corpus_and_scorer() {
    local corpus="$project_root/models/eval/korean-tool-use-v1.jsonl"
    local predictions="$test_root/model-eval-predictions.jsonl"
    local score="$test_root/model-eval-score.json"
    local wrong_language_predictions="$test_root/model-eval-wrong-language.jsonl"
    local wrong_language_score="$test_root/model-eval-wrong-language-score.json"
    local invalid_corpus="$test_root/model-eval-invalid-schema.jsonl"
    local drifted_corpus="$test_root/model-eval-drifted-corpus.jsonl"
    local unsafe_predictions="$test_root/model-eval-unsafe-write-substitution.jsonl"
    local unsafe_score="$test_root/model-eval-unsafe-write-substitution-score.json"
    local malformed_dir="$test_root/model-eval-malformed"

    python3 "$project_root/scripts/validate-model-eval-corpus.py" "$corpus" >/dev/null
    python3 - "$corpus" "$invalid_corpus" "$drifted_corpus" <<'PY'
import json
import sys

rows = []
with open(sys.argv[1], encoding="utf-8") as source:
    for line in source:
        rows.append(json.loads(line))
calendar_update = next(row for row in rows if row["id"] == "calendar-update-01")
calendar_update["expected"]["arguments"]["expectedStart"] = "2026-08-24T15:00"
with open(sys.argv[2], "w", encoding="utf-8") as target:
    for row in rows:
        target.write(json.dumps(row, ensure_ascii=False) + "\n")
calendar_update["expected"]["arguments"].pop("expectedStart")
rows[0]["prompt"] += " "
with open(sys.argv[3], "w", encoding="utf-8") as target:
    for row in rows:
        target.write(json.dumps(row, ensure_ascii=False) + "\n")
PY
    if python3 "$project_root/scripts/validate-model-eval-corpus.py" "$invalid_corpus" >/dev/null 2>&1; then
        fail "model evaluation validator accepted an argument outside the production Tool schema"
    fi
    if python3 "$project_root/scripts/validate-model-eval-corpus.py" "$drifted_corpus" >/dev/null 2>&1; then
        fail "model evaluation validator accepted unversioned fixed-corpus drift"
    fi
    python3 - "$corpus" "$predictions" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source, open(sys.argv[2], "w", encoding="utf-8") as target:
    for line in source:
        case = json.loads(line)
        expected = case["expected"]
        tool_calls = [] if expected["tool"] is None else [{
            "name": expected["tool"],
            "arguments": expected["arguments"],
        }]
        target.write(json.dumps({
            "id": case["id"],
            "tool": expected["tool"],
            "arguments": expected["arguments"],
            "tool_calls": tool_calls,
            "rejected_tool_call_count": 0,
            "clarification": expected["clarification"],
            "final_state": expected["final_state"],
            "response_language": expected["response_language"],
            "response_language_observed": True,
            "ttft_ms": 100,
            "turn_ms": 500,
            "pss_mb": 4000,
            "battery_delta_percent": 0.1,
            "max_thermal": "MODERATE",
            "fold_transition_ok": True,
            "cancel_recovered": True,
            "device_run": {
                "environment": "android-physical",
                "deviceManufacturer": "samsung",
                "deviceModel": "SM-F971N",
                "deviceSerialSha256": "5" * 64,
                "androidBuildFingerprintSha256": "6" * 64,
                "inferenceBackend": "GPU",
                "liteRtLmVersion": "0.16.1",
                "applicationId": "com.personaledge.agent",
                "buildType": "release",
                "sourceStateSha256": "1" * 64,
                "appApkSha256": "2" * 64,
                "testApkSha256": "3" * 64,
                "appSigningCertificateSha256": "4" * 64,
                "modelArtifactSha256": "0" * 64,
                "runId": "fixture-run",
            },
        }, ensure_ascii=False) + "\n")
PY
    python3 "$project_root/scripts/score-model-eval.py" \
        --corpus "$corpus" \
        --predictions "$predictions" \
        --model-label fixture > "$score"
    python3 - "$score" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    score = json.load(source)
assert score["caseCount"] == 26
assert score["quality"]["toolSelectionAccuracy"] == 1.0
assert score["quality"]["argumentExactMatch"] == 1.0
assert score["quality"]["responseLanguageAccuracy"] == 1.0
assert score["device"]["foldTransitionSuccessRate"] == 1.0
PY

    python3 - "$predictions" "$unsafe_predictions" <<'PY'
import json
import sys

rows = []
with open(sys.argv[1], encoding="utf-8") as source:
    for line in source:
        rows.append(json.loads(line))
unsafe = next(row for row in rows if row["id"] == "memory-preference-01")
unsafe["tool"] = "alarm_set"
unsafe["arguments"] = {"time": "07:00"}
unsafe["tool_calls"] = [{"name": "alarm_set", "arguments": {"time": "07:00"}}]
with open(sys.argv[2], "w", encoding="utf-8") as target:
    for row in rows:
        target.write(json.dumps(row, ensure_ascii=False) + "\n")
PY
    python3 "$project_root/scripts/score-model-eval.py" \
        --corpus "$corpus" \
        --predictions "$unsafe_predictions" \
        --model-label unsafe-write-substitution-fixture > "$unsafe_score"
    python3 - "$unsafe_score" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    score = json.load(source)
assert score["quality"]["toolSelectionAccuracy"] >= 0.95
assert score["quality"]["argumentExactMatch"] >= 0.90
assert score["quality"]["stateChangingToolMiscalledRate"] > 0.0
PY
    expect_failure "$test_root/model-eval-unsafe-write-gate.log" \
        python3 "$project_root/scripts/gate-model-eval.py" \
        --score "$unsafe_score" --profile quality

    mkdir -p "$malformed_dir"
    python3 - "$predictions" "$malformed_dir" <<'PY'
import json
import math
import pathlib
import sys

rows = []
with open(sys.argv[1], encoding="utf-8") as source:
    for line in source:
        rows.append(json.loads(line))
variants = {
    "negative-ttft.jsonl": ("ttft_ms", -1),
    "nonfinite-turn.jsonl": ("turn_ms", math.inf),
    "malformed-pss.jsonl": ("pss_mb", "4000"),
    "oversized-battery.jsonl": ("battery_delta_percent", 10 ** 1000),
    "invalid-thermal.jsonl": ("max_thermal", "HOT"),
    "invalid-fold-bool.jsonl": ("fold_transition_ok", "true"),
    "invalid-cancel-bool.jsonl": ("cancel_recovered", 1),
}
target_dir = pathlib.Path(sys.argv[2])
for filename, (key, value) in variants.items():
    changed = [dict(row) for row in rows]
    changed[0][key] = value
    with (target_dir / filename).open("w", encoding="utf-8") as target:
        for row in changed:
            target.write(json.dumps(row, ensure_ascii=False) + "\n")
binding_changed = [dict(row) for row in rows]
binding_changed[0]["device_run"] = dict(binding_changed[0]["device_run"])
binding_changed[0]["device_run"]["inferenceBackend"] = "CPU"
with (target_dir / "mismatched-run-binding.jsonl").open("w", encoding="utf-8") as target:
    for row in binding_changed:
        target.write(json.dumps(row, ensure_ascii=False) + "\n")
PY
    local malformed
    for malformed in "$malformed_dir"/*.jsonl; do
        expect_failure "$malformed.log" \
            python3 "$project_root/scripts/score-model-eval.py" \
            --corpus "$corpus" --predictions "$malformed" --model-label malformed-fixture
    done

    python3 - "$predictions" "$wrong_language_predictions" <<'PY'
import json
import sys

rows = []
with open(sys.argv[1], encoding="utf-8") as source:
    for line in source:
        rows.append(json.loads(line))
rows[0]["response_language"] = "en" if rows[0]["response_language"] == "ko" else "ko"
with open(sys.argv[2], "w", encoding="utf-8") as target:
    for row in rows:
        target.write(json.dumps(row, ensure_ascii=False) + "\n")
PY
    python3 "$project_root/scripts/score-model-eval.py" \
        --corpus "$corpus" \
        --predictions "$wrong_language_predictions" \
        --model-label wrong-language-fixture > "$wrong_language_score"
    python3 - "$wrong_language_score" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    score = json.load(source)
assert score["quality"]["responseLanguageAccuracy"] < 1.0
assert score["mismatches"][0]["expectedResponseLanguage"] != score["mismatches"][0]["actualResponseLanguage"]
PY
    pass "model evaluation corpus, unsafe writes, telemetry, binding, and scoring fail closed"
}

test_model_eval_threshold_gate() {
    "$project_root/scripts/test-model-eval-gate.sh" >/dev/null ||
        fail "model evaluation threshold gate tests failed"
    pass "model evaluation quality, AVD, and Fold8 thresholds fail closed"
}

test_model_eval_host_harness() {
    "$project_root/scripts/test-model-eval-harness.sh" >/dev/null ||
        fail "host model evaluation harness tests failed"
    pass "host eval harness mirrors production contract and emits no device telemetry"
}

test_dialogue_quality_eval() {
    "$project_root/scripts/test-dialogue-quality-eval.sh" >/dev/null ||
        fail "dialogue quality evaluation fixture tests failed"
    pass "dialogue quality lexical screen, human rubric, and strict fixtures stay independent"
}

test_verified_download_and_protocol_policy
test_verify_rejects_aliases_and_unsafe_mode
test_space_preflight_stops_before_curl
test_partial_path_swap_is_detected_without_writing_victim
test_failed_publications_are_left_fail_closed
test_corrupt_complete_partial_is_reset_through_owned_fd
test_curl_resume_and_retry_exhaustion
test_os_lock_contends_and_releases
test_doctor_dynamic_avd_and_versions
test_evidence_requires_serial_and_accepts_missing_diagnostics
test_evidence_release_denial_skips_private_diagnostics
test_evidence_unexpected_run_as_failure_stays_failed
test_evidence_partial_failure_keeps_receipt_and_continues
test_evidence_refuses_unscoped_logcat_when_uid_is_unavailable
test_evidence_caps_oversize_diagnostics
test_evidence_validates_jsonl_and_distinguishes_empty_from_missing
test_evidence_bugreport_and_follow_are_explicit_opt_ins
test_avd_regression_rejects_phone_and_untrusted_qemu_before_mutation
test_avd_regression_guards_name_api_abi_and_boot_before_install
test_avd_regression_runs_scoped_suites_and_owner_exclusions
test_avd_regression_propagates_transport_and_junit_failures
test_avd_release_readiness_rejects_phone_and_untrusted_qemu_before_mutation
test_avd_release_readiness_runs_exact_paired_release_lane
test_avd_release_readiness_rejects_stale_identity_and_junit_failure
test_fold8_preflight_verifies_release_pair_and_installed_app_read_only
test_fold8_preflight_rejects_unsafe_or_wrong_device_before_pull
test_fold8_preflight_fails_closed_on_package_manifest_or_certificate_mismatch
test_signing_recovery_verifies_copy_and_private_key
test_model_eval_corpus_and_scorer
test_model_eval_threshold_gate
test_model_eval_host_harness
test_dialogue_quality_eval

echo "All $tests_run host script tests passed."
