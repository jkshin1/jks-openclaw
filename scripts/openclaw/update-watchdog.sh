#!/usr/bin/env bash
set -euo pipefail
umask 077
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
source "$script_dir/_common.sh"
source "$script_dir/repair-openrouter-token-field.sh"
[[ $# == 0 || ( $# == 1 && "$1" == --apply ) ]] || openclaw_fail "Usage: update-watchdog.sh [--apply]"
openclaw_load_deployment
method="$openclaw_loaded_install_method"
provenance="$openclaw_management_root/local-runtime-provenance.json"
health_snapshot="$openclaw_state_dir/operations/remote-health.json"
openclaw_assert_private_file "$provenance" "existing local runtime provenance"
jq -e --slurpfile deployed "$openclaw_deployment_manifest" '
    .runtimeIdentity == "patched2026.8.1" and .officialBinaryIdentical == false and
    .runtimeTreeSha256 == $deployed[0].runtimeTreeSha256 and
    .commonSha256 == $deployed[0].commonSha256 and .watchdogSha256 == $deployed[0].watchdogSha256
' "$provenance" >/dev/null || openclaw_fail "local runtime provenance drifted"
(openclaw_assert_restrictive_config "$openclaw_config_path")
bash -n "$script_dir/watchdog.sh"
bash -n "$script_dir/_common.sh"
[[ $# == 1 ]] || { echo "PLAN common/watchdog atomic update; current Gateway/config/runtime preserved"; exit 0; }
recovery="$openclaw_backup_root/watchdog-update-$(date -u +%Y%m%dT%H%M%SZ)-$$"
[[ ! -e "$recovery" ]] || openclaw_fail "watchdog recovery directory already exists"
openclaw_prepare_private_dir "$recovery" "watchdog update recovery"
cp "$openclaw_management_watchdog" "$recovery/watchdog.sh"
cp "$openclaw_management_common" "$recovery/_common.sh"
openclaw_assert_private_file "$openclaw_watchdog_plist" "watchdog service definition"
cp "$openclaw_watchdog_plist" "$recovery/watchdog.plist"
plutil -convert json -o - "$openclaw_watchdog_plist" | jq 'if .ProcessType == "Background" or .ProcessType == "Standard" then .ProcessType="Standard" else error("unexpected watchdog ProcessType") end' > "$recovery/watchdog-candidate.json"
openclaw_assert_watchdog_plist "$(cat "$recovery/watchdog-candidate.json")"
plutil -convert xml1 -o "$recovery/watchdog-candidate.plist" "$recovery/watchdog-candidate.json"
cp "$openclaw_deployment_manifest" "$recovery/deployment.json"
cp "$provenance" "$recovery/provenance.json"
# Keep a read-only reference copy; config is never restored or rewritten by this update.
cp "$openclaw_config_path" "$recovery/config-at-start.json"
had_snapshot=0
if [[ -e "$health_snapshot" || -L "$health_snapshot" ]]; then
    openclaw_assert_private_file "$health_snapshot" "existing remote health snapshot"
    cp "$health_snapshot" "$recovery/remote-health.json"
    had_snapshot=1
fi
watchdog_target="gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_WATCHDOG_LABEL"
launchctl print "$watchdog_target" >/dev/null
committed=0
suspended=0
mutated=0
rollback_watchdog() {
    local result="$?"
    trap - EXIT INT TERM
    if (( committed == 0 && suspended == 1 )); then
        if launchctl print "$watchdog_target" >/dev/null 2>&1; then
            launchctl bootout "$watchdog_target" || exit 1
        fi
        if (( mutated == 1 )); then
            if ! { repair_atomic_replace "$recovery/watchdog.sh" "$openclaw_management_watchdog" 700 &&
                   repair_atomic_replace "$recovery/_common.sh" "$openclaw_management_common" 600 &&
                   repair_atomic_replace "$recovery/watchdog.plist" "$openclaw_watchdog_plist" 600 &&
                   repair_atomic_replace "$recovery/deployment.json" "$openclaw_deployment_manifest" 600 &&
                   repair_atomic_replace "$recovery/provenance.json" "$provenance" 600 &&
                   cmp -s "$openclaw_management_watchdog" "$recovery/watchdog.sh" &&
                   cmp -s "$openclaw_management_common" "$recovery/_common.sh" &&
                   cmp -s "$openclaw_watchdog_plist" "$recovery/watchdog.plist" &&
                   cmp -s "$openclaw_deployment_manifest" "$recovery/deployment.json" &&
                   cmp -s "$provenance" "$recovery/provenance.json"; }; then
                echo "FAIL watchdog rollback incomplete; job remains stopped: $recovery" >&2
                exit 1
            fi
            if (( had_snapshot == 1 )); then
                repair_atomic_replace "$recovery/remote-health.json" "$health_snapshot" 600 || exit 1
            elif [[ -e "$health_snapshot" || -L "$health_snapshot" ]]; then
                openclaw_assert_private_file "$health_snapshot" "new remote health snapshot"
                jq -e 'keys == (["schemaVersion","observedAtEpochMillis","gatewayHealthy","dockerHealthy","policyValid","secretsClean"] | sort)' \
                    "$health_snapshot" >/dev/null || exit 1
                rm "$health_snapshot" || exit 1
            fi
        fi
        launchctl bootstrap "gui/$(id -u)" "$openclaw_watchdog_plist" || exit 1
        echo '{"result":"original-watchdog-bytes-restored","gatewayChanged":false,"configChanged":false}' \
            > "$recovery/rollback.json"
    fi
    exit "$result"
}
trap rollback_watchdog EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
suspended=1
launchctl bootout "$watchdog_target"
cmp -s "$openclaw_management_watchdog" "$recovery/watchdog.sh"
cmp -s "$openclaw_management_common" "$recovery/_common.sh"
cmp -s "$openclaw_watchdog_plist" "$recovery/watchdog.plist"
cmp -s "$openclaw_deployment_manifest" "$recovery/deployment.json"
cmp -s "$provenance" "$recovery/provenance.json"
mutated=1
repair_atomic_replace "$script_dir/watchdog.sh" "$openclaw_management_watchdog" 700
repair_atomic_replace "$script_dir/_common.sh" "$openclaw_management_common" 600
repair_atomic_replace "$recovery/watchdog-candidate.plist" "$openclaw_watchdog_plist" 600
openclaw_write_deployment_manifest "$method" "$openclaw_node_path"
launchctl bootstrap "gui/$(id -u)" "$openclaw_watchdog_plist"
passed=0
for (( attempt=1; attempt<=180; attempt++ )); do
    launchctl print "$watchdog_target" > "$recovery/first-run.txt"
    if awk '$1 == "runs" && $2 == "=" && $3 ~ /^[1-9][0-9]*$/ { runs=1 }
        $1 == "state" && $2 == "=" && $3 == "not" && $4 == "running" { idle=1 }
        $1 == "last" && $2 == "exit" && $3 == "code" && $4 == "=" && $5 == "0" { passed=1 }
        END { exit !(runs && idle && passed) }' "$recovery/first-run.txt"; then
        passed=1
        break
    fi
    sleep 2
done
(( passed == 1 )) || openclaw_fail "updated watchdog first run did not pass"
"$script_dir/verify-gateway.sh" --observe-only > "$recovery/verify.log" 2>&1
openclaw_load_deployment
jq --slurpfile deployment "$openclaw_deployment_manifest" --arg recovery "$recovery" \
    --argjson updatedAt "$(( $(date -u +%s) * 1000 ))" '
    .watchdogSha256=$deployment[0].watchdogSha256 | .commonSha256=$deployment[0].commonSha256 |
    .watchdogFirstRunExitCode=0 | .watchdogProcessType="Standard" | .watchdogUpdateAppliedAtEpochMillis=$updatedAt |
    .watchdogUpdateRecoveryDirectory=$recovery
' "$recovery/provenance.json" > "$recovery/applied.json"
repair_atomic_replace "$recovery/applied.json" "$provenance" 600
committed=1
echo "OK managed ingress validation, watchdog context recovery and canonical health publisher applied; recovery: $recovery"
