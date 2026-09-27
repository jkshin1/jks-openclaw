#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=_common.sh
source "$script_dir/_common.sh"
source "$script_dir/watchdog.sh"

readonly PERSONAL_EDGE_SOAK_SCHEMA_VERSION=2
readonly PERSONAL_EDGE_SOAK_DEFAULT_DURATION_SECONDS=86400
readonly PERSONAL_EDGE_SOAK_DEFAULT_INTERVAL_SECONDS=300
readonly PERSONAL_EDGE_SOAK_MAX_DURATION_SECONDS=604800
readonly PERSONAL_EDGE_SOAK_MIN_INTERVAL_SECONDS=60
readonly PERSONAL_EDGE_SOAK_MAX_INTERVAL_SECONDS=1800

usage() {
    cat <<'EOF'
Usage:
  soak-acceptance.sh --start [--receipt-dir DIR] [--duration-seconds N] [--interval-seconds N]
  soak-acceptance.sh --resume --receipt-dir DIR
  soak-acceptance.sh --validate --receipt-dir DIR

Runs or validates an observation-only OpenClaw availability soak. The default is 24 hours with a
five-minute interval. It never invokes a model, creates/deletes a session, cleans history, or
restarts/repairs a service. Receipts contain only bounded operational metadata.
EOF
}

soak_is_integer() {
    [[ "$1" =~ ^[0-9]+$ ]]
}

soak_now_epoch() {
    date -u +%s 9>&-
}

soak_iso_for_epoch() {
    local epoch="$1"

    soak_is_integer "$epoch" || return 1
    date -u -r "$epoch" +%Y-%m-%dT%H:%M:%SZ 9>&-
}

soak_epoch_for_iso() {
    local timestamp="$1"
    local epoch

    epoch="$(date -j -u -f %Y-%m-%dT%H:%M:%SZ "$timestamp" +%s 9>&- 2>/dev/null)" || return 1
    soak_is_integer "$epoch" || return 1
    [[ "$(soak_iso_for_epoch "$epoch")" == "$timestamp" ]] || return 1
    printf '%s\n' "$epoch"
}

soak_sha256() {
    shasum -a 256 "$1" 9>&- | awk '{ print $1 }' 9>&-
}

SOAK_COLLECTOR_TIMEOUT_SECONDS=30
SOAK_VERIFY_TIMEOUT_SECONDS=180

# Run one external collector in its own process group with a hard time limit. A hung lsof,
# launchctl, Tailscale, pmset or fdesetup call becomes exit 124 (a failed sample) instead of
# stalling the runner; the whole group is terminated, then killed.
soak_bounded() {
    local seconds="$1"
    shift
    /usr/bin/perl -e '
        my $limit = shift @ARGV;
        my $pid = fork();
        exit 125 unless defined $pid;
        if ($pid == 0) { setpgrp(0, 0); exec { $ARGV[0] } @ARGV; exit 127; }
        local $SIG{ALRM} = sub { kill "TERM", -$pid; sleep 2; kill "KILL", -$pid; waitpid($pid, 0); exit 124; };
        alarm $limit;
        waitpid($pid, 0);
        alarm 0;
        exit(($? & 127) ? 128 + ($? & 127) : $? >> 8);
    ' "$seconds" "$@"
}

# Use only inside command substitution. The substitution shell permanently closes the receipt lock
# before it enters a collector, so a hung descendant cannot retain flock after the runner dies.
soak_capture_without_lock() {
    exec 9>&-
    "$@"
}

soak_fsync_path() {
    local target="$1"

    [[ -e "$target" && ! -L "$target" ]] || openclaw_fail "cannot sync an unsafe soak path"
    /usr/bin/perl -MIO::Handle -MFcntl=O_RDONLY -e '
        sysopen(my $handle, $ARGV[0], O_RDONLY) or die "open";
        $handle->sync or die "fsync";
    ' "$target" 9>&- >/dev/null 2>&1 || openclaw_fail "could not durably sync a soak receipt path"
}

soak_boot_epoch() {
    local boot_output boot_epoch

    boot_output="$(sysctl -n kern.boottime 9>&- 2>/dev/null)" || return 1
    boot_epoch="$(awk '
        match($0, /sec = [0-9]+/) {
            value = substr($0, RSTART + 6, RLENGTH - 6)
            print value
            exit
        }
    ' <<< "$boot_output")"
    soak_is_integer "$boot_epoch" || return 1
    printf '%s\n' "$boot_epoch"
}

soak_assert_private_dir() {
    local target="$1"
    local label="$2"
    local owner mode

    [[ -d "$target" && ! -L "$target" ]] || openclaw_fail "$label is not a real directory"
    owner="$(stat -f '%u' "$target")"
    [[ "$owner" == "$(id -u)" ]] || openclaw_fail "$label is not owned by the current user"
    mode="$(stat -f '%Lp' "$target")"
    [[ "$mode" == "700" ]] || openclaw_fail "$label must be mode 700"
}

soak_sweep_owned_raw_temps() {
    local expected_receipt_id="${1:-}"
    local raw_dir raw_name raw_receipt_id raw_runner_pid removed

    [[ -d "$openclaw_tmp_root" && ! -L "$openclaw_tmp_root" ]] ||
        openclaw_fail "soak temporary root is not a real directory"
    removed=0
    while IFS= read -r -d '' raw_dir; do
        raw_name="$(basename "$raw_dir")"
        if [[ ! "$raw_name" =~ ^personal-edge-openclaw-soak-sample\.(soak-[0-9]{8}T[0-9]{6}Z-[0-9]+)\.runner-([0-9]+)\.[A-Za-z0-9]+$ ]]; then
            openclaw_fail "soak raw temporary directory name is invalid"
        fi
        raw_receipt_id="${BASH_REMATCH[1]}"
        raw_runner_pid="${BASH_REMATCH[2]}"
        if [[ -n "$expected_receipt_id" && "$raw_receipt_id" != "$expected_receipt_id" ]]; then
            continue
        fi
        if [[ -z "$expected_receipt_id" ]] && kill -0 "$raw_runner_pid" 2>/dev/null; then
            continue
        fi
        soak_assert_private_dir "$raw_dir" "stale soak raw temporary directory"
        rm -rf -- "$raw_dir" 9>&-
        removed=1
    done < <(find "$openclaw_tmp_root" -mindepth 1 -maxdepth 1 -type d \
        -name 'personal-edge-openclaw-soak-sample.soak-*.runner-*' -print0 9>&-)
    if (( removed == 1 )); then
        soak_fsync_path "$openclaw_tmp_root"
    fi
}

soak_assert_receipt_path() {
    local backup_real receipt_parent_real

    openclaw_assert_safe_path "$receipt_dir" "soak receipt directory"
    soak_assert_private_dir "$openclaw_backup_root" "OpenClaw backup root"
    backup_real="$(cd "$openclaw_backup_root" && pwd -P)"
    receipt_parent_real="$(cd "$(dirname "$receipt_dir")" && pwd -P)"
    [[ "$receipt_parent_real" == "$backup_real" ]] ||
        openclaw_fail "soak receipt must be an immediate child of the private backup root"
}

soak_validate_manifest() {
    local target="$1"
    local created_epoch created_iso_epoch completed_epoch completed_iso_epoch

    openclaw_assert_private_file "$target" "soak manifest"
    if [[ "$(jq -r '.schemaVersion // empty' "$target")" == 1 ]]; then
        openclaw_fail "legacy schema-1 receipt covers Gateway only; preserve it and start a fresh Docker-aware soak"
    fi
    jq -e --arg profile "$PERSONAL_EDGE_OPENCLAW_PROFILE" \
        --arg version "$PERSONAL_EDGE_OPENCLAW_VERSION" '
        def uint: type == "number" and . >= 0 and floor == .;
        def nullable_uint: . == null or uint;
        def timestamp: type == "string" and
            test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$");
        def summary:
            (type == "object") and
            (keys == ([
                "runtimeGenerationChangeCount", "continuityPass", "failedSampleCount", "gatewayCrashObservedCount",
                "gatewayExitObservedCount", "gatewayGenerationChangeCount",
                "gatewayLaunchdRunIncrease", "initialGateSampleCount", "maxGapSeconds",
                "firstObservedEpoch", "lastObservedEpoch", "observedCoverageSeconds",
                "pendingSampleCount", "phaseOrderValid", "rebootCount", "sampleCount",
                "targetReached", "finalGateSampleCount",
                "watchdogCrashObservedCount",
                "watchdogExitObservedCount", "watchdogGenerationChangeCount",
                "watchdogLaunchdRunIncrease"
            ] | sort)) and
            ((.continuityPass | type) == "boolean") and
            ((.phaseOrderValid | type) == "boolean") and
            ((.targetReached | type) == "boolean");
        type == "object" and
        keys == ([
            "bootEpochAtStart", "completedAt", "completedEpoch", "createdAt", "createdEpoch",
            "acceptanceScope", "availabilityClaim", "deploymentManifestSha256", "intervalSeconds",
            "journalRecoveryCount", "kind", "limitations",
            "lastBootEpoch", "lastSampleEpoch", "lastSampleIndex", "nextSampleIndex", "profile",
            "receiptId", "result", "resumeCount", "schemaVersion", "status", "summary",
            "targetDurationSeconds", "version"
        ] | sort) and
        .schemaVersion == 2 and .kind == "personal-edge-openclaw-soak" and
        .acceptanceScope == "observed-window-only" and
        .availabilityClaim == "not-established-beyond-observed-window" and
        .limitations == [
          "filevault-owner-unlock-boundary", "login-session-launchagent-boundary",
          "power-settings-do-not-prove-power-continuity", "single-mac-no-redundancy",
          "ups-not-required-by-this-acceptance"
        ] and
        .profile == $profile and .version == $version and
        (.receiptId | type == "string" and test("^soak-[0-9]{8}T[0-9]{6}Z-[0-9]+$")) and
        (.deploymentManifestSha256 | type == "string" and test("^[a-f0-9]{64}$")) and
        (.createdAt | timestamp) and (.createdEpoch | uint) and
        (.bootEpochAtStart | uint) and (.lastBootEpoch | nullable_uint) and
        (.targetDurationSeconds | uint and . >= 1 and . <= 604800) and
        (.intervalSeconds | uint and . >= 1 and . <= 1800) and
        (.nextSampleIndex | uint) and (.lastSampleIndex | nullable_uint) and
        (.lastSampleEpoch | nullable_uint) and (.resumeCount | uint) and
        (.journalRecoveryCount | uint) and
        (.status == "running" or .status == "interrupted" or
         .status == "complete" or .status == "failed") and
        (.result == "not-complete" or .result == "observed-window-pass" or .result == "pending" or
         .result == "diagnostic" or .result == "failed") and
        (.completedAt == null or (.completedAt | timestamp)) and
        (.completedEpoch | nullable_uint) and
        (.summary == null or (.summary | summary)) and
        (if .nextSampleIndex == 0 then
            .lastSampleIndex == null and .lastSampleEpoch == null and .lastBootEpoch == null
         else
            .lastSampleIndex == (.nextSampleIndex - 1) and
            (.lastSampleEpoch | uint) and (.lastBootEpoch | uint)
         end) and
        (if .status == "complete" or .status == "failed" then
            .completedAt != null and .completedEpoch != null and .summary != null and
            .result != "not-complete"
         else
            .completedAt == null and .completedEpoch == null and .summary == null and
            .result == "not-complete"
         end)
    ' "$target" >/dev/null || openclaw_fail "soak manifest schema is invalid"

    created_epoch="$(jq -r '.createdEpoch' "$target")"
    created_iso_epoch="$(soak_epoch_for_iso "$(jq -r '.createdAt' "$target")")" ||
        openclaw_fail "soak manifest creation timestamp is invalid"
    [[ "$created_iso_epoch" == "$created_epoch" ]] ||
        openclaw_fail "soak manifest creation timestamp disagrees with its epoch"
    completed_epoch="$(jq -r '.completedEpoch // empty' "$target")"
    if [[ -n "$completed_epoch" ]]; then
        completed_iso_epoch="$(soak_epoch_for_iso "$(jq -r '.completedAt' "$target")")" ||
            openclaw_fail "soak manifest completion timestamp is invalid"
        [[ "$completed_iso_epoch" == "$completed_epoch" ]] ||
            openclaw_fail "soak manifest completion timestamp disagrees with its epoch"
        (( completed_epoch >= created_epoch )) ||
            openclaw_fail "soak manifest completes before it was created"
    fi

    if [[ "$(jq -r '.summary == null' "$target")" == "false" ]]; then
        jq -e '
            .summary as $s |
            ($s.sampleCount | type == "number" and . >= 1 and floor == .) and
            ($s.runtimeGenerationChangeCount | type == "number" and . >= 0 and floor == .) and
            ($s.failedSampleCount | type == "number" and . >= 0 and floor == .) and
            ($s.pendingSampleCount | type == "number" and . >= 0 and floor == .) and
            ($s.initialGateSampleCount | type == "number" and . >= 0 and floor == .) and
            ($s.finalGateSampleCount | type == "number" and . >= 0 and floor == .) and
            ($s.firstObservedEpoch | type == "number" and . >= 0 and floor == .) and
            ($s.lastObservedEpoch | type == "number" and . >= 0 and floor == .) and
            ($s.observedCoverageSeconds | type == "number" and . >= 0 and floor == .) and
            ($s.maxGapSeconds | type == "number" and . >= 0 and floor == .) and
            ($s.rebootCount | type == "number" and . >= 0 and floor == .) and
            ($s.gatewayLaunchdRunIncrease | type == "number" and . >= 0 and floor == .) and
            ($s.gatewayGenerationChangeCount | type == "number" and . >= 0 and floor == .) and
            ($s.gatewayExitObservedCount | type == "number" and . >= 0 and floor == .) and
            ($s.gatewayCrashObservedCount | type == "number" and . >= 0 and floor == .) and
            ($s.watchdogLaunchdRunIncrease | type == "number" and . >= 0 and floor == .) and
            ($s.watchdogGenerationChangeCount | type == "number" and . >= 0 and floor == .) and
            ($s.watchdogExitObservedCount | type == "number" and . >= 0 and floor == .) and
            ($s.watchdogCrashObservedCount | type == "number" and . >= 0 and floor == .) and
            $s.sampleCount == .nextSampleIndex and
            (if .result == "observed-window-pass" or .result == "pending" or .result == "diagnostic" then
                $s.sampleCount >= 2 and $s.initialGateSampleCount == 1 and
                $s.finalGateSampleCount == 1 and $s.phaseOrderValid and $s.targetReached
             else true end)
        ' "$target" >/dev/null || openclaw_fail "soak manifest summary is invalid"
    fi
}

soak_validate_sample() {
    local target="$1"
    local expected_receipt_id="$2"
    local observed_epoch observed_iso_epoch

    openclaw_assert_private_file "$target" "soak sample"
    jq -e --arg receiptId "$expected_receipt_id" '
        def uint: type == "number" and . >= 0 and floor == .;
        def nullable_uint: . == null or uint;
        def timestamp: type == "string" and
            test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$");
        def service:
            type == "object" and
            keys == ([
                "crashCount", "generation", "lastExitCode", "lastTerminatingSignal", "loaded",
                "pid", "runs", "state"
            ] | sort) and
            (.loaded | type == "boolean") and
            (.state == "running" or .state == "waiting" or .state == "exited" or
             .state == "not-running" or .state == "unknown" or .state == "missing") and
            (.pid | nullable_uint) and (.runs | nullable_uint) and (.generation | nullable_uint) and
            (.lastExitCode | nullable_uint) and (.lastTerminatingSignal | nullable_uint) and
            (.crashCount | nullable_uint);
        def service_delta:
            type == "object" and
            keys == (["crashObserved", "exitObserved", "generationChanged", "restartDelta"] | sort) and
            (.crashObserved | type == "boolean") and (.exitObserved | type == "boolean") and
            (.generationChanged | type == "boolean") and (.restartDelta | uint);
        def gates:
            type == "object" and
            keys == (["advisory", "executed", "required"] | sort) and
            (.executed | type == "boolean") and
            ((.required | type) == "object") and
            ((.required | keys) == (["statusProbe", "verifyObserveOnly"] | sort)) and
             (.required.statusProbe == null or
              (.required.statusProbe | type == "boolean")) and
             (.required.verifyObserveOnly == null or
              (.required.verifyObserveOnly | type == "boolean")) and
            ((.advisory | type) == "object") and ((.advisory | keys) == ["hostReadiness"]) and
            (.advisory.hostReadiness == null or
             (.advisory.hostReadiness | type == "boolean"));
        def power:
            type == "object" and
            keys == ([
                "ac", "acReady", "available", "customSha256", "upsProfilePresent"
            ] | sort) and
            (.available | type == "boolean") and
            (.customSha256 == null or
             (.customSha256 | type == "string" and test("^[a-f0-9]{64}$"))) and
            (.upsProfilePresent | type == "boolean") and
            (.acReady == null or (.acReady | type == "boolean")) and
            ((.ac | type) == "object") and
            ((.ac | keys) == ([
                "autorestart", "autorestartatconnect", "lowPowerMode", "powernap", "sleep",
                "standby", "tcpkeepalive", "womp"
             ] | sort)) and
            (all(.ac[]; . == null or (type == "number" and floor == .))) and
            (if .available then
                .customSha256 != null and
                .acReady == (.ac.sleep == 0 and .ac.standby == 0 and .ac.autorestart == 1)
             else
                .customSha256 == null and .acReady == null and all(.ac[]; . == null)
             end);
        type == "object" and
        keys == ([
            "bootEpoch", "delta", "deploymentManifestMatch", "elapsedSeconds", "fileVault",
            "gapSeconds", "gates", "gateway", "index", "kind", "listener", "observedAt",
            "observedEpoch", "overall", "phase", "receiptId", "rpcHealthy", "schemaVersion",
            "runtime", "tailscale", "uptimeSeconds", "userSessionMatches", "watchdog", "power"
        ] | sort) and
        .schemaVersion == 2 and .kind == "personal-edge-openclaw-soak-sample" and
        .receiptId == $receiptId and (.index | uint) and (.observedAt | timestamp) and
        (.observedEpoch | uint) and (.bootEpoch | uint) and (.uptimeSeconds | uint) and
        (.elapsedSeconds | uint) and (.gapSeconds == null or (.gapSeconds | uint)) and
        (.phase == "initial" or .phase == "periodic" or .phase == "resume" or .phase == "final") and
        (.deploymentManifestMatch | type == "boolean") and (.rpcHealthy | type == "boolean") and
        (.gateway | service) and (.watchdog | service) and
        (.delta | type == "object" and keys == (["bootChanged", "gateway", "watchdog", "runtime"] | sort) and
         (.bootChanged | type == "boolean") and (.gateway | service_delta) and
         (.watchdog | service_delta) and
         (.runtime | type == "object" and keys == (["vmChanged","daemonChanged","service"] | sort) and
          (.vmChanged | type == "boolean") and (.daemonChanged | type == "boolean") and
          (.service | service_delta))) and
        (.runtime | type == "object" and keys == (["definitionValid","vmRunning","dockerResponsive",
            "defaultContextMatches","vmGeneration","daemonGeneration","healthy","service"] | sort) and
         (.definitionValid | type == "boolean") and (.vmRunning | type == "boolean") and
         (.dockerResponsive | type == "boolean") and (.defaultContextMatches | type == "boolean") and
         (.healthy | type == "boolean") and (.service | service) and
         (.vmGeneration == null or (.vmGeneration | type == "string" and test("^[a-f0-9]{64}$"))) and
         (.daemonGeneration == null or (.daemonGeneration | type == "string" and test("^[a-f0-9]{64}$"))) and
         .healthy == (.definitionValid and .vmRunning and .dockerResponsive and .defaultContextMatches and
            .vmGeneration != null and .daemonGeneration != null)) and
        (.listener | type == "object" and
         keys == (["count", "expectedPort", "loopbackOnly"] | sort) and
         (.count | uint) and .expectedPort == 18789 and (.loopbackOnly | type == "boolean")) and
        (.tailscale | type == "object" and
         keys == (["acceptance", "availability", "backendRunning", "mode", "selfOnline", "serveRouteValid"] | sort) and
         (.availability == "installed" or .availability == "not-installed") and
         (.acceptance == "pass" or .acceptance == "pending" or .acceptance == "fail") and
         (.mode == "off" or .mode == "serve") and
         (.backendRunning == null or (.backendRunning | type == "boolean")) and
         (.selfOnline == null or (.selfOnline | type == "boolean")) and
         (.serveRouteValid == null or (.serveRouteValid | type == "boolean")) and
         (if .availability == "not-installed" then
              ((.mode == "off" and .acceptance == "pending") or
               (.mode == "serve" and .acceptance == "fail")) and
              .backendRunning == null and .selfOnline == null and .serveRouteValid == null
          elif .mode == "off" then
              .acceptance == "pending" and .serveRouteValid == null
          else
              (.backendRunning | type) == "boolean" and (.selfOnline | type) == "boolean" and
              (.serveRouteValid | type) == "boolean" and
              .acceptance ==
                (if .backendRunning and .selfOnline and .serveRouteValid then "pass" else "fail" end)
          end)) and
        (.gates | gates) and (.power == null or (.power | power)) and
        (.fileVault == null or .fileVault == "on" or .fileVault == "off" or .fileVault == "unknown") and
        (.userSessionMatches == null or (.userSessionMatches | type == "boolean")) and
        (.overall == "pass" or .overall == "pending" or .overall == "fail")
    ' "$target" >/dev/null || openclaw_fail "soak sample schema is invalid"
    observed_epoch="$(jq -r '.observedEpoch' "$target")"
    observed_iso_epoch="$(soak_epoch_for_iso "$(jq -r '.observedAt' "$target")")" ||
        openclaw_fail "soak sample timestamp is invalid"
    [[ "$observed_iso_epoch" == "$observed_epoch" ]] ||
        openclaw_fail "soak sample timestamp disagrees with its epoch"
}

soak_expected_overall() {
    local sample_json="$1"

    jq -r '
        (.runtime.healthy and .runtime.service.loaded and .runtime.service.state == "running" and
         (.runtime.service.pid | type) == "number" and .runtime.service.pid > 0 and
         (.runtime.service.runs | type) == "number" and
         .deploymentManifestMatch and .gateway.loaded and .gateway.state == "running" and
         (.gateway.pid | type) == "number" and .gateway.pid > 0 and
         (.gateway.runs | type) == "number" and
         .rpcHealthy and .listener.count > 0 and .listener.loopbackOnly and
         .watchdog.loaded and (.watchdog.runs | type) == "number" and
         (.watchdog.lastExitCode == null or .watchdog.lastExitCode == 0) and
         (.tailscale.acceptance != "fail") and
         (if .gates.executed then
              .gates.required.verifyObserveOnly and .gates.required.statusProbe and
              (.power.available == true) and (.power.acReady == true)
          else true end)) as $hardOk |
        if ($hardOk | not) then "fail"
        elif .tailscale.acceptance == "pending" then "pending"
        else "pass" end
    ' <<< "$sample_json"
}

soak_abandon_manifest_temp() {
    local abandoned=0

    if [[ -n "${current_manifest_temp:-}" && -f "$current_manifest_temp" &&
          ! -L "$current_manifest_temp" ]]; then
        case "$current_manifest_temp" in
            "$receipt_dir"/.manifest.partial.*)
                if rm -f -- "$current_manifest_temp"; then
                    abandoned=1
                fi
                ;;
            *) echo "WARN refusing to remove an unexpected soak manifest partial" >&2 ;;
        esac
    fi
    current_manifest_temp=""
    if (( abandoned == 1 )) && [[ -d "$receipt_dir" && ! -L "$receipt_dir" ]]; then
        (soak_fsync_path "$receipt_dir") >/dev/null 2>&1 || true
    fi
}

soak_update_manifest() {
    local filter="$1"
    local manifest_temp
    shift

    manifest_temp="$(mktemp "$receipt_dir/.manifest.partial.XXXXXX")"
    current_manifest_temp="$manifest_temp"
    if ! jq "$@" "$filter" "$manifest_path" > "$manifest_temp"; then
        soak_abandon_manifest_temp
        openclaw_fail "could not update soak manifest"
    fi
    if ! chmod 600 "$manifest_temp"; then
        soak_abandon_manifest_temp
        openclaw_fail "could not protect the soak manifest update"
    fi
    if ! (soak_validate_manifest "$manifest_temp"); then
        soak_abandon_manifest_temp
        openclaw_fail "refusing an invalid soak manifest update"
    fi
    if ! (soak_fsync_path "$manifest_temp"); then
        soak_abandon_manifest_temp
        openclaw_fail "could not sync the soak manifest update"
    fi
    if ! mv -f "$manifest_temp" "$manifest_path"; then
        soak_abandon_manifest_temp
        openclaw_fail "could not publish the soak manifest update"
    fi
    current_manifest_temp=""
    soak_fsync_path "$receipt_dir"
}

soak_discard_owned_partials() {
    local partial_path partial_name

    while IFS= read -r -d '' partial_path; do
        partial_name="$(basename "$partial_path")"
        case "$partial_path" in
            "$receipt_dir"/.manifest.partial.*)
                [[ "$partial_name" =~ ^\.manifest\.partial\.[A-Za-z0-9]+$ ]] ||
                    openclaw_fail "soak manifest partial name is invalid"
                ;;
            "$samples_dir"/.sample.partial.*)
                [[ "$partial_name" =~ ^\.sample\.partial\.[A-Za-z0-9]+$ ]] ||
                    openclaw_fail "soak sample partial name is invalid"
                ;;
            *) openclaw_fail "unsafe soak partial path" ;;
        esac
        openclaw_assert_private_file "$partial_path" "soak incomplete atomic receipt"
        rm -f -- "$partial_path"
    done < <(find "$receipt_dir" "$samples_dir" -mindepth 1 -maxdepth 1 -type f \
        \( -name '.manifest.partial.*' -o -name '.sample.partial.*' \) -print0)
    soak_fsync_path "$samples_dir"
    soak_fsync_path "$receipt_dir"
}

soak_validate_layout() {
    local allow_partials="${1:-0}"
    local unexpected

    soak_assert_private_dir "$receipt_dir" "soak receipt directory"
    soak_assert_private_dir "$samples_dir" "soak samples directory"
    if [[ "$allow_partials" == "1" ]]; then
        unexpected="$(find "$receipt_dir" -mindepth 1 -maxdepth 1 \
            ! -name manifest.json ! -name samples ! -name .active.lock \
            ! -name '.manifest.partial.*' -print -quit)"
    else
        unexpected="$(find "$receipt_dir" -mindepth 1 -maxdepth 1 \
            ! -name manifest.json ! -name samples ! -name .active.lock \
            -print -quit)"
    fi
    [[ -z "$unexpected" ]] || openclaw_fail "soak receipt contains an unexpected entry"
    if [[ "$allow_partials" == "1" ]]; then
        unexpected="$(find "$samples_dir" -mindepth 1 -maxdepth 1 \
            \( ! -type f -o \
            \( ! -name '[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9].json' \
               ! -name '.sample.partial.*' \) \) -print -quit)"
    else
        unexpected="$(find "$samples_dir" -mindepth 1 -maxdepth 1 \
            \( ! -type f -o \
            ! -name '[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9].json' \) \
            -print -quit)"
    fi
    [[ -z "$unexpected" ]] || openclaw_fail "soak sample journal contains an unexpected entry"
    if [[ "$allow_partials" == "1" ]]; then
        while IFS= read -r -d '' partial_path; do
            partial_name="$(basename "$partial_path")"
            [[ "$partial_name" =~ ^\.manifest\.partial\.[A-Za-z0-9]+$ ]] ||
                openclaw_fail "soak manifest partial name is invalid"
            openclaw_assert_private_file "$partial_path" "soak manifest partial"
        done < <(find "$receipt_dir" -mindepth 1 -maxdepth 1 -name '.manifest.partial.*' -print0)
        while IFS= read -r -d '' partial_path; do
            partial_name="$(basename "$partial_path")"
            [[ "$partial_name" =~ ^\.sample\.partial\.[A-Za-z0-9]+$ ]] ||
                openclaw_fail "soak sample partial name is invalid"
            openclaw_assert_private_file "$partial_path" "soak sample partial"
        done < <(find "$samples_dir" -mindepth 1 -maxdepth 1 -name '.sample.partial.*' -print0)
    fi

    if [[ -f "$receipt_dir/.active.lock" && ! -L "$receipt_dir/.active.lock" ]]; then
        openclaw_assert_private_file "$receipt_dir/.active.lock" "soak active lock"
        [[ "$(stat -f '%z' "$receipt_dir/.active.lock")" == "0" ]] ||
            openclaw_fail "soak active lock must be empty"
    else
        openclaw_fail "soak active lock is not a regular file"
    fi
}

soak_validate_journal() {
    local allow_reconcile="$1"
    local receipt_id expected_index file_path file_name file_index sample_index sample_epoch
    local previous_epoch manifest_next manifest_last_index manifest_last_epoch manifest_last_boot
    local last_file committed_tail actual_summary recorded_summary expected_result recorded_result
    local recorded_status summary_interval summary_target
    local previous_file sample_boot sample_uptime sample_elapsed expected_uptime expected_elapsed
    local expected_gap expected_boot_changed phase final_seen current_gateway current_watchdog
    local previous_gateway previous_watchdog expected_gateway_delta expected_watchdog_delta
    local recorded_gateway_delta recorded_watchdog_delta sample_json expected_overall
    local receipt_created_epoch previous_runtime current_runtime expected_runtime_delta

    soak_validate_manifest "$manifest_path"
    receipt_id="$(jq -r '.receiptId' "$manifest_path")"
    soak_validate_layout 0 "$receipt_id"
    receipt_created_epoch="$(jq -r '.createdEpoch' "$manifest_path")"
    expected_index=0
    previous_epoch=0
    last_file=""
    final_seen=0
    while IFS= read -r file_path; do
        [[ -n "$file_path" ]] || continue
        file_name="$(basename "$file_path")"
        file_index="${file_name%.json}"
        [[ "$file_index" =~ ^[0-9]{9}$ ]] || openclaw_fail "soak sample filename is invalid"
        sample_index=$((10#$file_index))
        (( sample_index == expected_index )) || openclaw_fail "soak sample indices are not contiguous"
        soak_validate_sample "$file_path" "$receipt_id"
        [[ "$(jq -r '.index' "$file_path")" == "$sample_index" ]] ||
            openclaw_fail "soak sample filename/index mismatch"
        sample_epoch="$(jq -r '.observedEpoch' "$file_path")"
        (( sample_epoch >= previous_epoch )) || openclaw_fail "soak sample wall time moved backward"
        sample_boot="$(jq -r '.bootEpoch' "$file_path")"
        sample_uptime="$(jq -r '.uptimeSeconds' "$file_path")"
        sample_elapsed="$(jq -r '.elapsedSeconds' "$file_path")"
        (( sample_epoch >= sample_boot )) || openclaw_fail "soak sample boot epoch is in the future"
        expected_uptime=$((sample_epoch - sample_boot))
        [[ "$sample_uptime" == "$expected_uptime" ]] ||
            openclaw_fail "soak sample uptime does not match its epochs"
        (( sample_epoch >= receipt_created_epoch )) ||
            openclaw_fail "soak sample predates its receipt"
        expected_elapsed=$((sample_epoch - receipt_created_epoch))
        [[ "$sample_elapsed" == "$expected_elapsed" ]] ||
            openclaw_fail "soak sample elapsed time does not match its receipt"

        phase="$(jq -r '.phase' "$file_path")"
        (( final_seen == 0 )) || openclaw_fail "soak journal contains a sample after its final gate"
        if (( sample_index == 0 )); then
            [[ "$phase" == "initial" ]] || openclaw_fail "soak sample zero is not the initial gate"
            [[ "$sample_boot" == "$(jq -r '.bootEpochAtStart' "$manifest_path")" ]] ||
                openclaw_fail "soak initial boot epoch disagrees with its manifest"
            expected_gap=null
            expected_boot_changed=false
            previous_gateway=null
            previous_watchdog=null
            previous_runtime=null
        else
            [[ "$phase" != "initial" ]] || openclaw_fail "soak journal repeats the initial gate"
            previous_file="$last_file"
            expected_gap=$((sample_epoch - previous_epoch))
            if [[ "$sample_boot" == "$(jq -r '.bootEpoch' "$previous_file")" ]]; then
                expected_boot_changed=false
            else
                expected_boot_changed=true
            fi
            previous_gateway="$(jq -c '.gateway' "$previous_file")"
            previous_watchdog="$(jq -c '.watchdog' "$previous_file")"
            previous_runtime="$(jq -c '.runtime' "$previous_file")"
        fi
        [[ "$(jq -r '.gapSeconds' "$file_path")" == "$expected_gap" ]] ||
            openclaw_fail "soak sample gap does not match adjacent observations"
        [[ "$(jq -r '.delta.bootChanged' "$file_path")" == "$expected_boot_changed" ]] ||
            openclaw_fail "soak boot-change flag does not match adjacent observations"
        current_runtime="$(jq -c '.runtime' "$file_path")"
        expected_runtime_delta="$(soak_runtime_delta "$current_runtime" "$previous_runtime")"
        [[ "$(jq -S -c . <<< "$expected_runtime_delta")" == "$(jq -S -c '.delta.runtime' "$file_path")" ]] ||
            openclaw_fail "soak Docker/Colima delta does not match adjacent observations"
        current_gateway="$(jq -c '.gateway' "$file_path")"
        current_watchdog="$(jq -c '.watchdog' "$file_path")"
        expected_gateway_delta="$(soak_service_delta "$current_gateway" "$previous_gateway")"
        expected_watchdog_delta="$(soak_service_delta "$current_watchdog" "$previous_watchdog")"
        recorded_gateway_delta="$(jq -S -c '.delta.gateway' "$file_path")"
        recorded_watchdog_delta="$(jq -S -c '.delta.watchdog' "$file_path")"
        [[ "$(jq -S -c . <<< "$expected_gateway_delta")" == "$recorded_gateway_delta" ]] ||
            openclaw_fail "soak Gateway delta does not match adjacent observations"
        [[ "$(jq -S -c . <<< "$expected_watchdog_delta")" == "$recorded_watchdog_delta" ]] ||
            openclaw_fail "soak watchdog delta does not match adjacent observations"

        if [[ "$phase" == "initial" || "$phase" == "final" ]]; then
            jq -e '
                .gates.executed == true and
                (.gates.required.verifyObserveOnly | type) == "boolean" and
                (.gates.required.statusProbe | type) == "boolean" and
                (.gates.advisory.hostReadiness | type) == "boolean" and
                .power != null and .fileVault != null and .userSessionMatches != null
            ' "$file_path" >/dev/null || openclaw_fail "soak boundary sample omitted its static/live gates"
        else
            jq -e '
                .gates == {
                  executed:false,
                  required:{verifyObserveOnly:null,statusProbe:null},
                  advisory:{hostReadiness:null}
                } and .power == null and .fileVault == null and .userSessionMatches == null
            ' "$file_path" >/dev/null || openclaw_fail "periodic soak sample contains boundary-only fields"
        fi
        [[ "$phase" != "final" ]] || final_seen=1
        sample_json="$(jq -c . "$file_path")"
        expected_overall="$(soak_expected_overall "$sample_json")"
        [[ "$(jq -r '.overall' "$file_path")" == "$expected_overall" ]] ||
            openclaw_fail "soak sample overall result does not match its observations"
        previous_epoch="$sample_epoch"
        last_file="$file_path"
        expected_index=$((expected_index + 1))
    done < <(find "$samples_dir" -mindepth 1 -maxdepth 1 -type f -name '*.json' | LC_ALL=C sort)

    manifest_next="$(jq -r '.nextSampleIndex' "$manifest_path")"
    if (( expected_index != manifest_next )); then
        (( allow_reconcile == 1 && expected_index == manifest_next + 1 )) ||
            openclaw_fail "soak manifest/sample journal is not recoverable"
    fi

    manifest_last_index="$(jq -r '.lastSampleIndex // -1' "$manifest_path")"
    manifest_last_epoch="$(jq -r '.lastSampleEpoch // -1' "$manifest_path")"
    manifest_last_boot="$(jq -r '.lastBootEpoch // -1' "$manifest_path")"
    if (( manifest_next == 0 )); then
        (( manifest_last_index == -1 && manifest_last_epoch == -1 && manifest_last_boot == -1 )) ||
            openclaw_fail "empty committed soak journal has nonempty manifest pointers"
    else
        committed_tail="$samples_dir/$(printf '%09d.json' "$((manifest_next - 1))")"
        [[ -f "$committed_tail" && ! -L "$committed_tail" ]] ||
            openclaw_fail "committed soak journal tail is missing"
        [[ "$manifest_last_index" == "$((manifest_next - 1))" &&
           "$manifest_last_epoch" == "$(jq -r '.observedEpoch' "$committed_tail")" &&
           "$manifest_last_boot" == "$(jq -r '.bootEpoch' "$committed_tail")" ]] ||
            openclaw_fail "pre-recovery manifest does not point to its committed journal tail"
    fi

    if (( expected_index == manifest_next + 1 && allow_reconcile == 1 )); then
        [[ -n "$last_file" ]] || openclaw_fail "soak journal recovery lacks its sample"
        soak_update_manifest '
            .nextSampleIndex=$next | .lastSampleIndex=$lastIndex |
            .lastSampleEpoch=$lastEpoch | .lastBootEpoch=$lastBoot |
            .journalRecoveryCount += 1
        ' --argjson next "$expected_index" --argjson lastIndex "$((expected_index - 1))" \
          --argjson lastEpoch "$(jq -r '.observedEpoch' "$last_file")" \
          --argjson lastBoot "$(jq -r '.bootEpoch' "$last_file")"
        manifest_next="$expected_index"
    fi
    (( expected_index == manifest_next )) || openclaw_fail "soak journal recovery did not converge"

    manifest_last_index="$(jq -r '.lastSampleIndex // -1' "$manifest_path")"
    manifest_last_epoch="$(jq -r '.lastSampleEpoch // -1' "$manifest_path")"
    manifest_last_boot="$(jq -r '.lastBootEpoch // -1' "$manifest_path")"
    if (( expected_index == 0 )); then
        (( manifest_last_index == -1 && manifest_last_epoch == -1 && manifest_last_boot == -1 )) ||
            openclaw_fail "empty soak journal has nonempty manifest pointers"
    else
        [[ "$manifest_last_index" == "$((expected_index - 1))" &&
           "$manifest_last_epoch" == "$previous_epoch" &&
           "$manifest_last_boot" == "$(jq -r '.bootEpoch' "$last_file")" ]] ||
            openclaw_fail "soak manifest does not point to the journal tail"
    fi

    if [[ "$(jq -r '.summary == null' "$manifest_path")" == "false" ]]; then
        (( $(jq -r '.completedEpoch' "$manifest_path") >= previous_epoch )) ||
            openclaw_fail "soak completion epoch predates its journal tail"
        summary_interval="$(jq -r '.intervalSeconds' "$manifest_path")"
        summary_target="$(jq -r '.targetDurationSeconds' "$manifest_path")"
        actual_summary="$(soak_build_summary "$summary_interval" "$summary_target")"
        recorded_summary="$(jq -S -c '.summary' "$manifest_path")"
        [[ "$(jq -S -c . <<< "$actual_summary")" == "$recorded_summary" ]] ||
            openclaw_fail "soak manifest summary does not match its sample journal"
        expected_result="$(soak_result_for_summary "$actual_summary" "$summary_target")"
        recorded_result="$(jq -r '.result' "$manifest_path")"
        recorded_status="$(jq -r '.status' "$manifest_path")"
        [[ "$recorded_result" == "$expected_result" ]] ||
            openclaw_fail "soak result does not match its sample journal"
        if [[ "$expected_result" == "failed" ]]; then
            [[ "$recorded_status" == "failed" ]] || openclaw_fail "failed soak has invalid status"
        else
            [[ "$recorded_status" == "complete" ]] || openclaw_fail "completed soak has invalid status"
        fi
    fi
    next_sample_index="$expected_index"
}

soak_acquire_lock() {
    lock_dir="$receipt_dir/.active.lock"
    openclaw_assert_private_file "$lock_dir" "soak active lock"
    [[ "$(stat -f '%z' "$lock_dir")" == "0" ]] || openclaw_fail "soak active lock must be empty"
    exec 9>>"$lock_dir"
    if ! lockf -s -t 0 9; then
        exec 9>&-
        openclaw_fail "another soak runner owns this receipt"
    fi
    lock_acquired=1
}

soak_release_lock() {
    if (( lock_acquired == 1 )); then
        exec 9>&-
        lock_acquired=0
    fi
}

soak_launchd_field() {
    local source_path="$1"
    local wanted="$2"

    awk -v wanted="$wanted" '
        {
            line = $0
            sub(/^[[:space:]]+/, "", line)
            split(line, pair, /[[:space:]]*=[[:space:]]*/)
            if (pair[1] == wanted) {
                print pair[2]
                exit
            }
        }
    ' "$source_path"
}

soak_json_integer_or_null() {
    if [[ "$1" =~ ^-?[0-9]+$ ]]; then
        printf '%s\n' "$1"
    else
        printf '%s\n' 'null'
    fi
}

soak_collect_service() {
    local target="$1"
    local raw_path="$2"
    local loaded state pid runs generation exit_code signal crash_count raw_state

    loaded=false
    state="missing"
    if soak_bounded "$SOAK_COLLECTOR_TIMEOUT_SECONDS" launchctl print "$target" 9>&- > "$raw_path" 2> "$raw_path.err"; then
        loaded=true
        raw_state="$(soak_launchd_field "$raw_path" state)"
        case "$raw_state" in
            running|waiting|exited) state="$raw_state" ;;
            "not running") state="not-running" ;;
            *) state="unknown" ;;
        esac
    fi
    pid="$(soak_json_integer_or_null "$(soak_launchd_field "$raw_path" pid)")"
    runs="$(soak_json_integer_or_null "$(soak_launchd_field "$raw_path" runs)")"
    generation="$(soak_json_integer_or_null "$(soak_launchd_field "$raw_path" generation)")"
    exit_code="$(soak_json_integer_or_null "$(soak_launchd_field "$raw_path" 'last exit code')")"
    signal="$(soak_json_integer_or_null "$(soak_launchd_field "$raw_path" 'last terminating signal')")"
    crash_count="$(soak_json_integer_or_null "$(soak_launchd_field "$raw_path" 'crash count')")"
    jq -cn --argjson loaded "$loaded" --arg state "$state" --argjson pid "$pid" \
        --argjson runs "$runs" --argjson generation "$generation" \
        --argjson lastExitCode "$exit_code" --argjson lastTerminatingSignal "$signal" \
        --argjson crashCount "$crash_count" '
        {loaded:$loaded,state:$state,pid:$pid,runs:$runs,generation:$generation,
         lastExitCode:$lastExitCode,lastTerminatingSignal:$lastTerminatingSignal,
         crashCount:$crashCount}
    '
}

soak_service_delta() {
    local current_json="$1"
    local previous_json="$2"

    jq -cn --argjson current "$current_json" --argjson previous "$previous_json" '
        def number: type == "number";
        (($previous != null) and
         ((($current.generation | number) and ($previous.generation | number) and
           $current.generation != $previous.generation) or
          (($current.pid | number) and ($previous.pid | number) and
           $current.pid != $previous.pid) or
          ($previous.loaded == true and $current.loaded == false))) as $generationChanged |
        (if ($previous != null and ($current.runs | number) and ($previous.runs | number) and
             $current.runs >= $previous.runs)
         then ($current.runs - $previous.runs)
         elif $generationChanged then 1 else 0 end) as $restartDelta |
        (($previous != null) and ($previous.pid | number) and
         (($current.pid | number | not) or $current.pid != $previous.pid)) as $exitObserved |
        ((($previous != null) and ($current.crashCount | number) and
          ($previous.crashCount | number) and $current.crashCount > $previous.crashCount) or
         ($exitObserved and
          ((($current.lastExitCode | number) and $current.lastExitCode != 0) or
           (($current.lastTerminatingSignal | number) and $current.lastTerminatingSignal != 0)))) as $crashObserved |
        {generationChanged:$generationChanged,restartDelta:$restartDelta,
         exitObserved:$exitObserved,crashObserved:$crashObserved}
    '
}

soak_runtime_delta() {
    local current="$1"
    local previous="$2"
    local service_delta
    service_delta="$(soak_service_delta "$(jq -c '.service' <<< "$current")" \
        "$(jq -c 'if . == null then null else .service end' <<< "$previous")")"
    jq -cn --argjson current "$current" --argjson previous "$previous" \
        --argjson service "$service_delta" '{
        vmChanged:($previous != null and $previous.vmGeneration != $current.vmGeneration),
        daemonChanged:($previous != null and $previous.daemonGeneration != $current.daemonGeneration),
        service:$service
    }'
}

soak_collect_listener() {
    local raw_path="$1"
    local listener_count loopback_only listener_name

    listener_count=0
    loopback_only=true
    soak_bounded "$SOAK_COLLECTOR_TIMEOUT_SECONDS" lsof -nP -iTCP:"$PERSONAL_EDGE_OPENCLAW_PORT" -sTCP:LISTEN -F n 9>&- \
        > "$raw_path" 2> "$raw_path.err" || true
    while IFS= read -r listener_name; do
        [[ -n "$listener_name" ]] || continue
        listener_count=$((listener_count + 1))
        case "$listener_name" in
            "n127.0.0.1:$PERSONAL_EDGE_OPENCLAW_PORT"|"n[::1]:$PERSONAL_EDGE_OPENCLAW_PORT") ;;
            *) loopback_only=false ;;
        esac
    done < <(awk '/^n/ { print }' "$raw_path")
    jq -cn --argjson count "$listener_count" --argjson loopbackOnly "$loopback_only" \
        --argjson expectedPort "$PERSONAL_EDGE_OPENCLAW_PORT" '
        {count:$count,loopbackOnly:$loopbackOnly,expectedPort:$expectedPort}
    '
}

soak_collect_tailscale() {
    local sample_temp="$1"
    local mode tailscale_path availability acceptance backend_running self_online serve_valid

    mode="$(jq -r '.gateway.tailscale.mode' "$openclaw_config_path")"
    tailscale_path=""
    if [[ "${PERSONAL_EDGE_OPENCLAW_SOAK_FIXTURE_MODE:-0}" == "1" &&
          "${PERSONAL_EDGE_OPENCLAW_SOAK_FIXTURE_TAILSCALE_ABSENT:-0}" == "1" ]]; then
        tailscale_path=""
    elif [[ "${PERSONAL_EDGE_OPENCLAW_SOAK_FIXTURE_MODE:-0}" == "1" ]]; then
        [[ "$(cd "$openclaw_user_root" && pwd -P)" != "$(cd "$HOME" && pwd -P)" ]] ||
        openclaw_fail "fixture CLI is forbidden for the owner root"
        tailscale_path="${PERSONAL_EDGE_OPENCLAW_FIXTURE_TAILSCALE_BIN:-}"
        [[ -n "$tailscale_path" ]] || openclaw_fail "fixture Tailscale must be explicitly absent or supplied"
        openclaw_assert_safe_path "$tailscale_path" "fixture Tailscale CLI"
        openclaw_assert_owned_nonwritable_file "$tailscale_path" "fixture Tailscale CLI"
    elif [[ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ]]; then
        tailscale_path="/Applications/Tailscale.app/Contents/MacOS/Tailscale"
    else
        tailscale_path="$(command -v tailscale || true)"
    fi
    if [[ -z "$tailscale_path" || ! -x "$tailscale_path" ]]; then
        availability="not-installed"
        acceptance="pending"
        [[ "$mode" == "off" ]] || acceptance="fail"
        jq -cn --arg availability "$availability" --arg acceptance "$acceptance" \
            --arg mode "$mode" '
            {availability:$availability,acceptance:$acceptance,mode:$mode,
             backendRunning:null,selfOnline:null,serveRouteValid:null}
        '
        return
    fi

    availability="installed"
    backend_running=false
    self_online=false
    if soak_bounded "$SOAK_COLLECTOR_TIMEOUT_SECONDS" env TAILSCALE_BE_CLI=1 "$tailscale_path" status --json 9>&- \
        > "$sample_temp/tailscale-status.json" 2> "$sample_temp/tailscale-status.err"; then
        if jq -e '.BackendState == "Running"' "$sample_temp/tailscale-status.json" >/dev/null; then
            backend_running=true
        fi
        if jq -e '.Self.Online == true' "$sample_temp/tailscale-status.json" >/dev/null; then
            self_online=true
        fi
    fi
    serve_valid=null
    acceptance="pending"
    if [[ "$mode" == "serve" ]]; then
        serve_valid=false
        if soak_bounded "$SOAK_COLLECTOR_TIMEOUT_SECONDS" env TAILSCALE_BE_CLI=1 "$tailscale_path" serve status --json 9>&- \
            > "$sample_temp/tailscale-serve.json" 2> "$sample_temp/tailscale-serve.err" &&
           (openclaw_assert_managed_tailscale_serve_receipt "$sample_temp/tailscale-serve.json") 9>&- >/dev/null 2>&1; then
            serve_valid=true
        fi
        if [[ "$backend_running" == "true" && "$self_online" == "true" &&
              "$serve_valid" == "true" ]]; then
            acceptance="pass"
        else
            acceptance="fail"
        fi
    fi
    jq -cn --arg availability "$availability" --arg acceptance "$acceptance" --arg mode "$mode" \
        --argjson backendRunning "$backend_running" --argjson selfOnline "$self_online" \
        --argjson serveRouteValid "$serve_valid" '
        {availability:$availability,acceptance:$acceptance,mode:$mode,
         backendRunning:$backendRunning,selfOnline:$selfOnline,serveRouteValid:$serveRouteValid}
    '
}

soak_power_value() {
    local source_text="$1"
    local key="$2"
    local value

    value="$(awk -v wanted="$key" '$1 == wanted { print $2; exit }' <<< "$source_text")"
    soak_json_integer_or_null "$value"
}

soak_collect_power() {
    local sample_temp="$1"
    local raw_path ac_settings custom_hash sleep_value standby_value autorestart_value
    local powernap_value low_power_value womp_value tcpkeepalive_value
    local autorestart_at_connect_value ups_present ac_ready

    raw_path="$sample_temp/pmset-custom.txt"
    if ! soak_bounded "$SOAK_COLLECTOR_TIMEOUT_SECONDS" pmset -g custom 9>&- > "$raw_path" 2> "$sample_temp/pmset-custom.err"; then
        jq -cn '
            {available:false,customSha256:null,upsProfilePresent:false,
             acReady:null,ac:{sleep:null,standby:null,autorestart:null,
             autorestartatconnect:null,powernap:null,lowPowerMode:null,womp:null,
             tcpkeepalive:null}}
        '
        return
    fi
    custom_hash="$(soak_sha256 "$raw_path")"
    ac_settings="$(awk '
        /^AC Power:/ { in_ac = 1; next }
        /^[^[:space:]].*:$/ && in_ac { exit }
        in_ac { print }
    ' "$raw_path")"
    sleep_value="$(soak_power_value "$ac_settings" sleep)"
    standby_value="$(soak_power_value "$ac_settings" standby)"
    autorestart_value="$(soak_power_value "$ac_settings" autorestart)"
    autorestart_at_connect_value="$(soak_power_value "$ac_settings" autorestartatconnect)"
    powernap_value="$(soak_power_value "$ac_settings" powernap)"
    low_power_value="$(soak_power_value "$ac_settings" lowpowermode)"
    womp_value="$(soak_power_value "$ac_settings" womp)"
    tcpkeepalive_value="$(soak_power_value "$ac_settings" tcpkeepalive)"
    ups_present=false
    grep -Eq '^UPS Power:' "$raw_path" && ups_present=true
    ac_ready=false
    if [[ "$sleep_value" == "0" && "$standby_value" == "0" &&
          "$autorestart_value" == "1" ]]; then
        ac_ready=true
    fi
    jq -cn --arg customSha256 "$custom_hash" \
        --argjson upsProfilePresent "$ups_present" --argjson acReady "$ac_ready" \
        --argjson sleep "$sleep_value" --argjson standby "$standby_value" \
        --argjson autorestart "$autorestart_value" \
        --argjson autorestartatconnect "$autorestart_at_connect_value" \
        --argjson powernap "$powernap_value" --argjson lowPowerMode "$low_power_value" \
        --argjson womp "$womp_value" --argjson tcpkeepalive "$tcpkeepalive_value" '
        {available:true,customSha256:$customSha256,
         upsProfilePresent:$upsProfilePresent,acReady:$acReady,
         ac:{sleep:$sleep,standby:$standby,autorestart:$autorestart,
             autorestartatconnect:$autorestartatconnect,powernap:$powernap,
             lowPowerMode:$lowPowerMode,womp:$womp,tcpkeepalive:$tcpkeepalive}}
    '
}

soak_collect_gates() {
    local sample_temp="$1"
    local gate_tmp verify_ok status_ok readiness_ok

    gate_tmp="$sample_temp/gate-tmp"
    mkdir "$gate_tmp"
    chmod 700 "$gate_tmp"
    verify_ok=false
    status_ok=false
    readiness_ok=false
    if soak_bounded "$SOAK_VERIFY_TIMEOUT_SECONDS" env TMPDIR="$gate_tmp" "$script_dir/verify-gateway.sh" --observe-only 9>&- \
        >/dev/null 2>&1; then
        verify_ok=true
    fi
    if "$script_dir/status-gateway.sh" 9>&- >/dev/null 2>&1; then
        status_ok=true
    fi
    if "$script_dir/host-readiness.sh" 9>&- >/dev/null 2>&1; then
        readiness_ok=true
    fi
    jq -cn --argjson verifyObserveOnly "$verify_ok" --argjson statusProbe "$status_ok" \
        --argjson hostReadiness "$readiness_ok" '
        {executed:true,
         required:{verifyObserveOnly:$verifyObserveOnly,statusProbe:$statusProbe},
         advisory:{hostReadiness:$hostReadiness}}
    '
}

soak_filevault_status() {
    local value

    value="$(soak_bounded "$SOAK_COLLECTOR_TIMEOUT_SECONDS" fdesetup status 9>&- 2>/dev/null || true)"
    if [[ "$value" == *"On."* ]]; then
        printf '%s\n' on
    elif [[ "$value" == *"Off."* ]]; then
        printf '%s\n' off
    else
        printf '%s\n' unknown
    fi
}

soak_collect_sample() {
    local phase="$1"
    local index="$next_sample_index"
    local sample_temp sample_temp_parent observed_epoch observed_at boot_epoch uptime_seconds
    local created_epoch elapsed_seconds previous_file previous_epoch previous_boot gap_seconds
    local gateway_json watchdog_json previous_gateway previous_watchdog gateway_delta watchdog_delta
    local listener_json rpc_healthy tailscale_json gates_json power_json filevault_value filevault_json
    local user_match_json runtime_json runtime_service runtime_delta previous_runtime
    local manifest_match hard_ok overall sample_temp_file sample_target boot_changed

    sample_temp_parent="$openclaw_tmp_root"
    sample_temp="$(mktemp -d "$sample_temp_parent/personal-edge-openclaw-soak-sample.$receipt_id.runner-$$.XXXXXX")"
    chmod 700 "$sample_temp"
    current_sample_temp="$sample_temp"
    observed_epoch="$(soak_capture_without_lock soak_now_epoch)"
    observed_at="$(soak_capture_without_lock soak_iso_for_epoch "$observed_epoch")"
    boot_epoch="$(soak_capture_without_lock soak_boot_epoch)" ||
        openclaw_fail "could not read the Mac boot epoch"
    (( observed_epoch >= boot_epoch )) || openclaw_fail "Mac boot epoch is in the future"
    uptime_seconds=$((observed_epoch - boot_epoch))
    created_epoch="$(jq -r '.createdEpoch' "$manifest_path")"
    (( observed_epoch >= created_epoch )) || openclaw_fail "soak sample predates its receipt"
    elapsed_seconds=$((observed_epoch - created_epoch))

    previous_file=""
    previous_epoch=""
    previous_boot=""
    previous_gateway=null
    previous_watchdog=null
    previous_runtime=null
    gap_seconds=null
    if (( index > 0 )); then
        previous_file="$samples_dir/$(printf '%09d.json' "$((index - 1))")"
        [[ -f "$previous_file" && ! -L "$previous_file" ]] ||
            openclaw_fail "previous soak sample is missing"
        previous_epoch="$(jq -r '.observedEpoch' "$previous_file")"
        previous_boot="$(jq -r '.bootEpoch' "$previous_file")"
        (( observed_epoch >= previous_epoch )) || openclaw_fail "soak sample time moved backward"
        gap_seconds=$((observed_epoch - previous_epoch))
        previous_gateway="$(jq -c '.gateway' "$previous_file")"
        previous_watchdog="$(jq -c '.watchdog' "$previous_file")"
        previous_runtime="$(jq -c '.runtime' "$previous_file")"
    fi

    runtime_json="$(soak_capture_without_lock openclaw_colima_snapshot "$sample_temp")"
    runtime_service="$(soak_capture_without_lock soak_collect_service \
        "gui/$UID/com.personaledge.colima-runtime" "$sample_temp/colima.launchctl")"
    runtime_json="$(jq -c --argjson service "$runtime_service" '. + {service:$service}' <<< "$runtime_json")"
    runtime_delta="$(soak_capture_without_lock soak_runtime_delta "$runtime_json" "$previous_runtime")"
    gateway_json="$(soak_capture_without_lock soak_collect_service \
        "gui/$UID/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL" "$sample_temp/gateway.launchctl")"
    watchdog_json="$(soak_capture_without_lock soak_collect_service \
        "gui/$UID/$PERSONAL_EDGE_OPENCLAW_WATCHDOG_LABEL" "$sample_temp/watchdog.launchctl")"
    gateway_delta="$(soak_capture_without_lock \
        soak_service_delta "$gateway_json" "$previous_gateway")"
    watchdog_delta="$(soak_capture_without_lock \
        soak_service_delta "$watchdog_json" "$previous_watchdog")"
    listener_json="$(soak_capture_without_lock \
        soak_collect_listener "$sample_temp/listeners.txt")"
    rpc_healthy=false
    if (openclaw_run gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT") 9>&- \
        > "$sample_temp/gateway-health.out" 2> "$sample_temp/gateway-health.err"; then
        rpc_healthy=true
    fi
    tailscale_json="$(soak_capture_without_lock soak_collect_tailscale "$sample_temp")"

    gates_json='{"executed":false,"required":{"verifyObserveOnly":null,"statusProbe":null},"advisory":{"hostReadiness":null}}'
    power_json=null
    filevault_json=null
    user_match_json=null
    if [[ "$phase" == "initial" || "$phase" == "final" ]]; then
        gates_json="$(soak_capture_without_lock soak_collect_gates "$sample_temp")"
        power_json="$(soak_capture_without_lock soak_collect_power "$sample_temp")"
        filevault_value="$(soak_capture_without_lock soak_filevault_status)"
        filevault_json="$(soak_capture_without_lock jq -Rn \
            --arg value "$filevault_value" '$value')"
        if [[ "$(stat -f '%Su' /dev/console 9>&- 2>/dev/null || true)" == "$(id -un)" ]]; then
            user_match_json=true
        else
            user_match_json=false
        fi
    fi

    manifest_match=false
    if [[ -f "$openclaw_deployment_manifest" && ! -L "$openclaw_deployment_manifest" ]] &&
       [[ "$(soak_capture_without_lock soak_sha256 "$openclaw_deployment_manifest")" == \
          "$(jq -r '.deploymentManifestSha256' "$manifest_path")" ]]; then
        manifest_match=true
    fi
    hard_ok="$(jq -nr --argjson runtime "$runtime_json" --argjson gateway "$gateway_json" --argjson watchdog "$watchdog_json" \
        --argjson listener "$listener_json" --argjson rpc "$rpc_healthy" \
        --argjson manifestMatch "$manifest_match" --argjson gates "$gates_json" \
        --argjson power "$power_json" --argjson tailscale "$tailscale_json" '
        ($runtime.healthy and $runtime.service.loaded and $runtime.service.state == "running" and
         ($runtime.service.pid | type) == "number" and $runtime.service.pid > 0 and
         ($runtime.service.runs | type) == "number" and $manifestMatch and $gateway.loaded and $gateway.state == "running" and
         ($gateway.pid | type) == "number" and $gateway.pid > 0 and
         ($gateway.runs | type) == "number" and $rpc and
         $listener.count > 0 and $listener.loopbackOnly and $watchdog.loaded and
         ($watchdog.runs | type) == "number" and
         ($watchdog.lastExitCode == null or $watchdog.lastExitCode == 0) and
         ($tailscale.acceptance != "fail") and
         (if $gates.executed then
              $gates.required.verifyObserveOnly and $gates.required.statusProbe and
              ($power.available == true) and ($power.acReady == true)
          else true end))
    ')"
    if [[ "$hard_ok" != "true" ]]; then
        overall="fail"
    elif [[ "$(jq -r '.acceptance' <<< "$tailscale_json")" == "pending" ]]; then
        overall="pending"
    else
        overall="pass"
    fi

    boot_changed=false
    if [[ -n "$previous_boot" && "$previous_boot" != "$boot_epoch" ]]; then
        boot_changed=true
    fi
    sample_temp_file="$(mktemp "$samples_dir/.sample.partial.XXXXXX")"
    jq -n --arg receiptId "$receipt_id" --argjson index "$index" \
        --arg observedAt "$observed_at" --argjson observedEpoch "$observed_epoch" \
        --argjson bootEpoch "$boot_epoch" --argjson uptimeSeconds "$uptime_seconds" \
        --argjson elapsedSeconds "$elapsed_seconds" --argjson gapSeconds "$gap_seconds" \
        --arg phase "$phase" --argjson deploymentManifestMatch "$manifest_match" \
        --argjson gateway "$gateway_json" --argjson watchdog "$watchdog_json" \
        --argjson gatewayDelta "$gateway_delta" --argjson watchdogDelta "$watchdog_delta" \
        --argjson runtime "$runtime_json" --argjson runtimeDelta "$runtime_delta" \
        --argjson bootChanged "$boot_changed" \
        --argjson listener "$listener_json" --argjson rpcHealthy "$rpc_healthy" \
        --argjson tailscale "$tailscale_json" --argjson gates "$gates_json" \
        --argjson power "$power_json" --argjson fileVault "$filevault_json" \
        --argjson userSessionMatches "$user_match_json" --arg overall "$overall" '
        {
          schemaVersion:2,kind:"personal-edge-openclaw-soak-sample",receiptId:$receiptId,
          index:$index,observedAt:$observedAt,observedEpoch:$observedEpoch,
          bootEpoch:$bootEpoch,uptimeSeconds:$uptimeSeconds,elapsedSeconds:$elapsedSeconds,
          gapSeconds:$gapSeconds,phase:$phase,deploymentManifestMatch:$deploymentManifestMatch,
          gateway:$gateway,watchdog:$watchdog,runtime:$runtime,
          delta:{bootChanged:$bootChanged,gateway:$gatewayDelta,watchdog:$watchdogDelta,runtime:$runtimeDelta},
          listener:$listener,rpcHealthy:$rpcHealthy,tailscale:$tailscale,gates:$gates,
          power:$power,fileVault:$fileVault,userSessionMatches:$userSessionMatches,overall:$overall
        }
    ' > "$sample_temp_file"
    chmod 600 "$sample_temp_file"
    soak_validate_sample "$sample_temp_file" "$receipt_id"
    soak_fsync_path "$sample_temp_file"
    sample_target="$samples_dir/$(printf '%09d.json' "$index")"
    [[ ! -e "$sample_target" && ! -L "$sample_target" ]] ||
        openclaw_fail "soak sample target already exists"
    mv "$sample_temp_file" "$sample_target"
    soak_fsync_path "$samples_dir"
    soak_update_manifest '
        .nextSampleIndex=$next | .lastSampleIndex=$lastIndex |
        .lastSampleEpoch=$lastEpoch | .lastBootEpoch=$lastBoot | .status="running"
    ' --argjson next "$((index + 1))" --argjson lastIndex "$index" \
      --argjson lastEpoch "$observed_epoch" --argjson lastBoot "$boot_epoch"
    next_sample_index=$((index + 1))
    last_sample_overall="$overall"
    rm -rf -- "$sample_temp"
    current_sample_temp=""
}

soak_build_summary() {
    local summary_interval="${1:-$interval_seconds}"
    local summary_target="${2:-$target_duration_seconds}"
    local continuity_grace

    if (( summary_interval < 60 )); then
        continuity_grace=2
    else
        continuity_grace=60
    fi
    jq -s --argjson interval "$summary_interval" --argjson target "$summary_target" \
        --argjson grace "$continuity_grace" '
        def sum_field(path): map(getpath(path)) | add // 0;
        (map(.gapSeconds // 0) | max // 0) as $maxGap |
        (.[0].observedEpoch // 0) as $firstObserved |
        (.[-1].observedEpoch // 0) as $lastObserved |
        (if length >= 1 and $lastObserved >= $firstObserved
         then ($lastObserved - $firstObserved) else 0 end) as $observedCoverage |
        (map(select(.phase == "initial")) | length) as $initialCount |
        (map(select(.phase == "final")) | length) as $finalCount |
        {
          runtimeGenerationChangeCount:(map(select(.delta.runtime.vmChanged or .delta.runtime.daemonChanged or
            .delta.runtime.service.generationChanged or .delta.runtime.service.restartDelta > 0 or
            .delta.runtime.service.exitObserved or .delta.runtime.service.crashObserved)) | length),
          sampleCount:length,
          failedSampleCount:(map(select(.overall == "fail")) | length),
          pendingSampleCount:(map(select(.overall == "pending")) | length),
          initialGateSampleCount:$initialCount,
          finalGateSampleCount:$finalCount,
          firstObservedEpoch:$firstObserved,
          lastObservedEpoch:$lastObserved,
          observedCoverageSeconds:$observedCoverage,
          targetReached:($observedCoverage >= $target),
          phaseOrderValid:(length >= 2 and $initialCount == 1 and $finalCount == 1 and
                           .[0].phase == "initial" and .[-1].phase == "final"),
          maxGapSeconds:$maxGap,
          continuityPass:($maxGap <= ($interval + $grace)),
          rebootCount:(map(select(.delta.bootChanged == true)) | length),
          gatewayLaunchdRunIncrease:sum_field(["delta","gateway","restartDelta"]),
          gatewayGenerationChangeCount:(map(select(.delta.gateway.generationChanged == true)) | length),
          gatewayExitObservedCount:(map(select(.delta.gateway.exitObserved == true)) | length),
          gatewayCrashObservedCount:(map(select(.delta.gateway.crashObserved == true)) | length),
          watchdogLaunchdRunIncrease:sum_field(["delta","watchdog","restartDelta"]),
          watchdogGenerationChangeCount:(map(select(.delta.watchdog.generationChanged == true)) | length),
          watchdogExitObservedCount:(map(select(.delta.watchdog.exitObserved == true)) | length),
          watchdogCrashObservedCount:(map(select(.delta.watchdog.crashObserved == true)) | length)
        }
    ' "$samples_dir"/*.json
}

soak_result_for_summary() {
    local summary_json="$1"
    local summary_target="$2"
    local failed_count pending_count continuity_pass reboot_count gateway_runs
    local gateway_exits gateway_crashes watchdog_crashes phase_and_target runtime_changes

    runtime_changes="$(jq -r '.runtimeGenerationChangeCount' <<< "$summary_json")"
    failed_count="$(jq -r '.failedSampleCount' <<< "$summary_json")"
    pending_count="$(jq -r '.pendingSampleCount' <<< "$summary_json")"
    continuity_pass="$(jq -r '.continuityPass' <<< "$summary_json")"
    reboot_count="$(jq -r '.rebootCount' <<< "$summary_json")"
    gateway_runs="$(jq -r '.gatewayLaunchdRunIncrease' <<< "$summary_json")"
    gateway_exits="$(jq -r '.gatewayExitObservedCount' <<< "$summary_json")"
    gateway_crashes="$(jq -r '.gatewayCrashObservedCount' <<< "$summary_json")"
    watchdog_crashes="$(jq -r '.watchdogCrashObservedCount' <<< "$summary_json")"
    phase_and_target="$(jq -r '.phaseOrderValid and .targetReached' <<< "$summary_json")"
    if [[ "$phase_and_target" != "true" || "$continuity_pass" != "true" ]] ||
       (( runtime_changes > 0 || failed_count > 0 || reboot_count > 0 || gateway_runs > 0 ||
          gateway_exits > 0 || gateway_crashes > 0 || watchdog_crashes > 0 )); then
        printf '%s\n' failed
    elif (( summary_target < PERSONAL_EDGE_SOAK_DEFAULT_DURATION_SECONDS )); then
        printf '%s\n' diagnostic
    elif (( pending_count > 0 )); then
        printf '%s\n' pending
    else
        printf '%s\n' observed-window-pass
    fi
}

soak_finish() {
    local forced_result="${1:-}"
    local completed_epoch completed_at last_observed_epoch summary_json result calculated_result

    completed_epoch="$(soak_capture_without_lock soak_now_epoch)"
    completed_at="$(soak_capture_without_lock soak_iso_for_epoch "$completed_epoch")"
    last_observed_epoch="$(jq -r '.lastSampleEpoch // empty' "$manifest_path")"
    soak_is_integer "$last_observed_epoch" || openclaw_fail "cannot finish a soak without a sample"
    (( completed_epoch >= last_observed_epoch )) ||
        openclaw_fail "wall clock moved backward before soak completion"
    summary_json="$(soak_capture_without_lock soak_build_summary)"
    calculated_result="$(soak_capture_without_lock \
        soak_result_for_summary "$summary_json" "$target_duration_seconds")"
    if [[ -n "$forced_result" ]]; then
        [[ "$forced_result" == "$calculated_result" ]] ||
            openclaw_fail "forced soak result disagrees with the sample journal"
        result="$forced_result"
    else
        result="$calculated_result"
    fi
    soak_update_manifest '
        .status=(if $result == "failed" then "failed" else "complete" end) |
        .result=$result | .completedAt=$completedAt | .completedEpoch=$completedEpoch |
        .summary=$summary
    ' --arg result "$result" --arg completedAt "$completed_at" \
      --argjson completedEpoch "$completed_epoch" --argjson summary "$summary_json"
    final_result="$result"
}

soak_report_completion() {
    case "$final_result" in
        observed-window-pass)
            echo "PASS observed-window-only soak result=$final_result receipt=$receipt_dir"
            ;;
        diagnostic)
            echo "DIAGNOSTIC short observation completed; no 24-hour claim receipt=$receipt_dir"
            ;;
        pending)
            echo "PENDING observed window completed with an unmet external gate receipt=$receipt_dir" >&2
            ;;
        failed)
            echo "FAIL observed-window-only acceptance failed receipt=$receipt_dir" >&2
            ;;
        *) openclaw_fail "unknown final soak result" ;;
    esac
}

soak_interrupt_manifest() {
    if [[ -f "${manifest_path:-}" && ! -L "${manifest_path:-}" ]] &&
       [[ "$(jq -r '.status // empty' "$manifest_path" 2>/dev/null || true)" == "running" ]]; then
        soak_update_manifest '.status="interrupted"' || true
    fi
}

soak_runner_exit() {
    local exit_status="$?"
    trap - EXIT INT TERM
    if [[ -n "$current_sample_temp" && -d "$current_sample_temp" && ! -L "$current_sample_temp" ]]; then
        case "$current_sample_temp" in
            "$openclaw_tmp_root"/personal-edge-openclaw-soak-sample.*)
                rm -rf -- "$current_sample_temp" || true
                ;;
        esac
    fi
    soak_abandon_manifest_temp
    if (( runner_finished == 0 )); then
        if ! (soak_interrupt_manifest); then
            echo "WARN could not mark the interrupted soak receipt" >&2
        fi
    fi
    soak_release_lock
    exit "$exit_status"
}

# Library mode is used by isolated journal regressions; no live collector or CLI action runs.
if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
    return 0
fi

start_mode=0
resume_mode=0
validate_mode=0
receipt_dir=""
duration_seconds="$PERSONAL_EDGE_SOAK_DEFAULT_DURATION_SECONDS"
interval_seconds="$PERSONAL_EDGE_SOAK_DEFAULT_INTERVAL_SECONDS"
duration_explicit=0
interval_explicit=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --start) start_mode=1; shift ;;
        --resume) resume_mode=1; shift ;;
        --validate) validate_mode=1; shift ;;
        --receipt-dir)
            [[ $# -ge 2 ]] || openclaw_fail "--receipt-dir requires a value"
            receipt_dir="$2"
            shift 2
            ;;
        --duration-seconds)
            [[ $# -ge 2 ]] || openclaw_fail "--duration-seconds requires a value"
            duration_seconds="$2"
            duration_explicit=1
            shift 2
            ;;
        --interval-seconds)
            [[ $# -ge 2 ]] || openclaw_fail "--interval-seconds requires a value"
            interval_seconds="$2"
            interval_explicit=1
            shift 2
            ;;
        -h|--help) usage; exit 0 ;;
        *) openclaw_fail "unknown argument: $1" ;;
    esac
done
(( start_mode + resume_mode + validate_mode == 1 )) ||
    openclaw_fail "choose exactly one of --start, --resume, or --validate"
if (( resume_mode == 1 || validate_mode == 1 )); then
    [[ -n "$receipt_dir" ]] || openclaw_fail "--resume/--validate requires --receipt-dir"
    (( duration_explicit == 0 && interval_explicit == 0 )) ||
        openclaw_fail "resume/validate uses the duration and interval pinned in its manifest"
fi
for required_command in awk basename chmod date find id jq lockf rm sort stat; do
    openclaw_require_command "$required_command"
done

manifest_path=""
samples_dir=""
receipt_id=""
current_boot_epoch=""
current_sample_temp=""
current_manifest_temp=""
lock_dir=""
lock_acquired=0
runner_finished=0
next_sample_index=0
last_sample_overall=""
final_result=""

if (( validate_mode == 1 )); then
    [[ "$receipt_dir" == /* ]] || openclaw_fail "soak receipt path must be absolute"
    manifest_path="$receipt_dir/manifest.json"
    samples_dir="$receipt_dir/samples"
    soak_assert_receipt_path
    soak_assert_private_dir "$receipt_dir" "soak receipt directory"
    soak_assert_private_dir "$samples_dir" "soak samples directory"
    soak_acquire_lock
    trap soak_release_lock EXIT
    soak_validate_manifest "$manifest_path"
    receipt_id="$(jq -r '.receiptId' "$manifest_path")"
    soak_sweep_owned_raw_temps "$receipt_id"
    soak_validate_layout 0 "$receipt_id"
    soak_validate_journal 0
    soak_release_lock
    trap - EXIT
    echo "OK soak receipt schema and monotonic sample journal are valid: $receipt_dir"
    exit 0
fi

for required_command in \
    awk date fdesetup grep launchctl lsof mkdir mktemp mv pmset shasum sleep sysctl; do
    openclaw_require_command "$required_command"
done
[[ -x /usr/bin/perl && ! -L /usr/bin/perl ]] ||
    openclaw_fail "trusted system Perl is required for ordered receipt fsync"

minimum_interval="$PERSONAL_EDGE_SOAK_MIN_INTERVAL_SECONDS"
if [[ "${PERSONAL_EDGE_OPENCLAW_SOAK_FIXTURE_MODE:-0}" == "1" ]]; then
    [[ "$openclaw_user_root" != "$HOME" ]] ||
        openclaw_fail "fixture interval override is forbidden for the real user root"
    minimum_interval=1
fi

if (( start_mode == 1 )); then
    soak_is_integer "$duration_seconds" || openclaw_fail "duration must be an integer"
    soak_is_integer "$interval_seconds" || openclaw_fail "interval must be an integer"
    (( duration_seconds >= 1 && duration_seconds <= PERSONAL_EDGE_SOAK_MAX_DURATION_SECONDS )) ||
        openclaw_fail "duration must be between 1 second and 7 days"
    (( interval_seconds >= minimum_interval && interval_seconds <= PERSONAL_EDGE_SOAK_MAX_INTERVAL_SECONDS )) ||
        openclaw_fail "interval must be between $minimum_interval and $PERSONAL_EDGE_SOAK_MAX_INTERVAL_SECONDS seconds"
    soak_sweep_owned_raw_temps
    current_boot_epoch="$(soak_capture_without_lock soak_boot_epoch)" ||
        openclaw_fail "could not read the Mac boot epoch"
    (openclaw_load_deployment) 9>&-
    [[ "$openclaw_deployment_manifest" == "$openclaw_management_root/deployment.json" ]] ||
        openclaw_fail "soak requires a final managed deployment manifest"
    deployment_manifest_sha256="$(soak_capture_without_lock \
        soak_sha256 "$openclaw_deployment_manifest")"
    receipt_id="soak-$(date -u +%Y%m%dT%H%M%SZ)-$$"
    if [[ -z "$receipt_dir" ]]; then
        receipt_dir="$openclaw_backup_root/$receipt_id"
    fi
    openclaw_prepare_private_dir "$openclaw_backup_root" "OpenClaw backup root"
    soak_assert_receipt_path
    [[ ! -e "$receipt_dir" && ! -L "$receipt_dir" ]] ||
        openclaw_fail "soak receipt path already exists"
    mkdir "$receipt_dir"
    chmod 700 "$receipt_dir"
    soak_fsync_path "$openclaw_backup_root"
    samples_dir="$receipt_dir/samples"
    mkdir "$samples_dir"
    chmod 700 "$samples_dir"
    manifest_path="$receipt_dir/manifest.json"
    lock_dir="$receipt_dir/.active.lock"
    (umask 077; : > "$lock_dir")
    chmod 600 "$lock_dir"
    soak_fsync_path "$lock_dir"
    soak_fsync_path "$receipt_dir"
    soak_acquire_lock
    trap soak_runner_exit EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM

    created_epoch="$(soak_capture_without_lock soak_now_epoch)"
    created_at="$(soak_capture_without_lock soak_iso_for_epoch "$created_epoch")"
    manifest_temp="$(mktemp "$receipt_dir/.manifest.partial.XXXXXX")"
    current_manifest_temp="$manifest_temp"
    jq -n --arg receiptId "$receipt_id" --arg profile "$PERSONAL_EDGE_OPENCLAW_PROFILE" \
        --arg version "$PERSONAL_EDGE_OPENCLAW_VERSION" \
        --arg deploymentManifestSha256 "$deployment_manifest_sha256" \
        --arg createdAt "$created_at" --argjson createdEpoch "$created_epoch" \
        --argjson bootEpochAtStart "$current_boot_epoch" \
        --argjson targetDurationSeconds "$duration_seconds" \
        --argjson intervalSeconds "$interval_seconds" '
        {
          schemaVersion:2,kind:"personal-edge-openclaw-soak",receiptId:$receiptId,
          acceptanceScope:"observed-window-only",
          availabilityClaim:"not-established-beyond-observed-window",
          limitations:[
            "filevault-owner-unlock-boundary", "login-session-launchagent-boundary",
            "power-settings-do-not-prove-power-continuity", "single-mac-no-redundancy",
            "ups-not-required-by-this-acceptance"
          ],
          profile:$profile,version:$version,deploymentManifestSha256:$deploymentManifestSha256,
          createdAt:$createdAt,createdEpoch:$createdEpoch,bootEpochAtStart:$bootEpochAtStart,
          targetDurationSeconds:$targetDurationSeconds,intervalSeconds:$intervalSeconds,
          nextSampleIndex:0,lastSampleIndex:null,lastSampleEpoch:null,lastBootEpoch:null,
          resumeCount:0,journalRecoveryCount:0,status:"running",result:"not-complete",
          completedAt:null,completedEpoch:null,summary:null
        }
    ' > "$manifest_temp"
    chmod 600 "$manifest_temp"
    soak_validate_manifest "$manifest_temp"
    soak_fsync_path "$manifest_temp"
    mv "$manifest_temp" "$manifest_path"
    current_manifest_temp=""
    soak_fsync_path "$receipt_dir"
    soak_validate_journal 0
    echo "START observation-only soak receipt=$receipt_dir"
else
    [[ "$receipt_dir" == /* ]] || openclaw_fail "soak receipt path must be absolute"
    manifest_path="$receipt_dir/manifest.json"
    samples_dir="$receipt_dir/samples"
    soak_assert_receipt_path
    soak_assert_private_dir "$receipt_dir" "soak receipt directory"
    soak_assert_private_dir "$samples_dir" "soak samples directory"
    soak_acquire_lock
    trap soak_runner_exit EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    soak_validate_manifest "$manifest_path"
    receipt_id="$(jq -r '.receiptId' "$manifest_path")"
    soak_sweep_owned_raw_temps "$receipt_id"
    soak_validate_layout 1 "$receipt_id"
    duration_seconds="$(jq -r '.targetDurationSeconds' "$manifest_path")"
    interval_seconds="$(jq -r '.intervalSeconds' "$manifest_path")"
    soak_is_integer "$duration_seconds" || openclaw_fail "manifest duration is not an integer"
    soak_is_integer "$interval_seconds" || openclaw_fail "manifest interval is not an integer"
    (( duration_seconds >= 1 && duration_seconds <= PERSONAL_EDGE_SOAK_MAX_DURATION_SECONDS )) ||
        openclaw_fail "manifest duration is outside the supported bound"
    (( interval_seconds >= minimum_interval && interval_seconds <= PERSONAL_EDGE_SOAK_MAX_INTERVAL_SECONDS )) ||
        openclaw_fail "manifest interval is outside the supported bound"
    status="$(jq -r '.status' "$manifest_path")"
    [[ "$status" == "running" || "$status" == "interrupted" ]] ||
        openclaw_fail "only a running or interrupted soak can be resumed"
    current_boot_epoch="$(soak_capture_without_lock soak_boot_epoch)" ||
        openclaw_fail "could not read the Mac boot epoch"
    (openclaw_load_deployment) 9>&-
    [[ "$openclaw_deployment_manifest" == "$openclaw_management_root/deployment.json" ]] ||
        openclaw_fail "soak resume requires a final managed deployment manifest"
    [[ "$(soak_capture_without_lock soak_sha256 "$openclaw_deployment_manifest")" == \
       "$(jq -r '.deploymentManifestSha256' "$manifest_path")" ]] ||
        openclaw_fail "managed deployment manifest changed during the soak"
    soak_discard_owned_partials
    soak_validate_journal 1
fi

target_duration_seconds="$duration_seconds"
interval_seconds="$interval_seconds"
receipt_id="$(jq -r '.receiptId' "$manifest_path")"
resume_needs_initial=0

if (( resume_mode == 1 )); then
    if (( next_sample_index == 0 )); then
        soak_update_manifest \
            '.status="running" | .resumeCount += 1 | .bootEpochAtStart=$bootEpoch' \
            --argjson bootEpoch "$current_boot_epoch"
        resume_needs_initial=1
    else
        soak_update_manifest '.status="running" | .resumeCount += 1'
    fi
fi

if (( start_mode == 1 || resume_needs_initial == 1 )); then
    soak_collect_sample initial
    if [[ "$last_sample_overall" == "fail" ]]; then
        soak_finish failed
        runner_finished=1
        soak_release_lock
        trap - EXIT INT TERM
        echo "FAIL initial observation gate failed; content-free receipt retained at $receipt_dir" >&2
        exit 1
    fi
fi

first_sample="$samples_dir/000000000.json"
[[ -f "$first_sample" && ! -L "$first_sample" ]] || openclaw_fail "initial soak sample is missing"
[[ "$(jq -r '.phase' "$first_sample")" == "initial" ]] ||
    openclaw_fail "soak sample zero is not the initial gate"
first_observed_epoch="$(jq -r '.observedEpoch' "$first_sample")"
target_end_epoch=$((first_observed_epoch + target_duration_seconds))

if (( resume_mode == 1 && resume_needs_initial == 0 )); then
    last_committed_sample="$samples_dir/$(printf '%09d.json' "$((next_sample_index - 1))")"
    if (( next_sample_index == 1 )) &&
       [[ "$(jq -r '.phase + ":" + .overall' "$last_committed_sample")" == "initial:fail" ]]; then
        soak_finish failed
        runner_finished=1
        soak_release_lock
        trap - EXIT INT TERM
        soak_report_completion
        exit 1
    fi
    if [[ "$(jq -r '.phase' "$last_committed_sample")" == "final" ]]; then
        soak_finish
        runner_finished=1
        soak_release_lock
        trap - EXIT INT TERM
        soak_report_completion
        [[ "$final_result" != "failed" ]] || exit 1
        [[ "$final_result" != "pending" ]] || exit 2
        exit 0
    fi
    current_epoch="$(soak_capture_without_lock soak_now_epoch)"
    if (( current_epoch >= target_end_epoch )); then
        soak_collect_sample final
        soak_finish
        runner_finished=1
        soak_release_lock
        trap - EXIT INT TERM
        soak_report_completion
        [[ "$final_result" != "failed" ]] || exit 1
        [[ "$final_result" != "pending" ]] || exit 2
        exit 0
    fi
    soak_collect_sample resume
fi

fixture_stop_after="${PERSONAL_EDGE_OPENCLAW_SOAK_FIXTURE_STOP_AFTER_SAMPLES:-0}"
soak_is_integer "$fixture_stop_after" || openclaw_fail "fixture stop count must be an integer"
if (( fixture_stop_after > 0 && next_sample_index >= fixture_stop_after )); then
    soak_update_manifest '.status="interrupted"'
    runner_finished=1
    soak_release_lock
    trap - EXIT INT TERM
    exit 75
fi

while true; do
    current_epoch="$(soak_capture_without_lock soak_now_epoch)"
    if (( current_epoch >= target_end_epoch )); then
        soak_collect_sample final
        break
    fi
    sleep_seconds="$interval_seconds"
    remaining_seconds=$((target_end_epoch - current_epoch))
    (( sleep_seconds <= remaining_seconds )) || sleep_seconds="$remaining_seconds"
    (( sleep_seconds >= 1 )) || sleep_seconds=1
    sleep "$sleep_seconds" 9>&-
    current_epoch="$(soak_capture_without_lock soak_now_epoch)"
    if (( current_epoch >= target_end_epoch )); then
        soak_collect_sample final
        break
    fi
    soak_collect_sample periodic
    if (( fixture_stop_after > 0 && next_sample_index >= fixture_stop_after )); then
        soak_update_manifest '.status="interrupted"'
        runner_finished=1
        soak_release_lock
        trap - EXIT INT TERM
        exit 75
    fi
done

soak_finish
runner_finished=1
soak_release_lock
trap - EXIT INT TERM
soak_report_completion
[[ "$final_result" != "failed" ]] || exit 1
[[ "$final_result" != "pending" ]] || exit 2
