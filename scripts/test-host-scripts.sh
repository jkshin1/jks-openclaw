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
    exec-out)
        [[ "${1:-}" == "run-as" && "${2:-}" == "com.personaledge.agent" ]] || exit 93
        if [[ "${3:-}" == "id" ]]; then
            echo 'uid=10123(u0_a123) gid=10123(u0_a123)'
            exit 0
        fi
        exit 94
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

    jq -e '.overallStatus == "ok" and
        .options.bugreport == false and .options.appLogcat == false and
        .options.followLogcat == false and
        (.device.serialSha256 | length) == 64 and
        (.device.serialMasked | startswith("sha256:")) and
        ([.commands[] | select(.id | startswith("diagnostics-")) | select(.status == "not_present")] | length) == 4 and
        ([.commands[] | select((.id | startswith("logcat-")) and .status == "opt_in_not_requested")] | length) == 3 and
        ([.commands[] | select(.id == "bugreport" and .status == "opt_in_not_requested")] | length) == 1' \
        "$evidence_manifest" >/dev/null || fail "missing diagnostics were not a successful, explicit receipt state"
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
test_evidence_partial_failure_keeps_receipt_and_continues
test_evidence_refuses_unscoped_logcat_when_uid_is_unavailable
test_evidence_caps_oversize_diagnostics
test_evidence_validates_jsonl_and_distinguishes_empty_from_missing
test_evidence_bugreport_and_follow_are_explicit_opt_ins

echo "All $tests_run host script tests passed."
