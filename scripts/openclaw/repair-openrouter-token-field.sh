#!/usr/bin/env bash
set -euo pipefail
umask 077

repair_atomic_replace() {
    local source="$1" destination="$2" mode="$3" temporary
    temporary="$(mktemp "$(dirname "$destination")/.token-field.partial.XXXXXX")" || return 1
    if cp "$source" "$temporary" && chmod "$mode" "$temporary" && mv -f "$temporary" "$destination"; then
        return 0
    fi
    rm -f -- "$temporary"
    return 1
}

repair_assert_workspace() {
    local workspace="$1" policy="$2" entry name
    [[ -d "$workspace" && ! -L "$workspace" ]] || return 1
    cmp -s "$workspace/AGENTS.md" "$policy" || return 1
    while IFS= read -r -d '' entry; do
        name="${entry##*/}"
        case "$name" in
            AGENTS.md|SOUL.md|USER.md|IDENTITY.md)
                openclaw_assert_private_file "$entry" "reviewed workspace file" || return 1 ;;
            *) return 1 ;;
        esac
    done < <(find "$workspace" -mindepth 1 -maxdepth 1 -print0)
}

repair_prepare_config() {
    local source="$1" destination="$2"
    jq '.agents.defaults.skipBootstrap=true | .agents.defaults.contextInjection="never"' \
        "$source" > "$destination" || return 1
    chmod 600 "$destination" || return 1
    (openclaw_assert_restrictive_config "$destination") || return 1
    # Only these two explicit bootstrap fields may differ; everything else stays byte-value equal.
    jq -e --slurpfile original "$source" '
        del(.agents.defaults.skipBootstrap,.agents.defaults.contextInjection) ==
        ($original[0] | del(.agents.defaults.skipBootstrap,.agents.defaults.contextInjection))
    ' "$destination" >/dev/null
}

repair_quarantine_workspace() {
    local workspace="$1" saved="$2" quarantine="$3" name
    for name in SOUL.md USER.md IDENTITY.md; do
        if [[ -f "$saved/$name" ]]; then
            [[ -f "$workspace/$name" && ! -L "$workspace/$name" && ! -e "$quarantine/$name" ]] || return 1
            cmp -s "$workspace/$name" "$saved/$name" || return 1
            mv "$workspace/$name" "$quarantine/$name" || return 1
            cmp -s "$quarantine/$name" "$saved/$name" || return 1
        fi
    done
}

repair_restore_workspace() {
    local workspace="$1" saved="$2" name
    for name in SOUL.md USER.md IDENTITY.md; do
        if [[ -f "$saved/$name" ]]; then
            # A concurrent/new file is owner data until proven otherwise. Never overwrite it.
            if [[ -e "$workspace/$name" || -L "$workspace/$name" ]]; then
                [[ ! -L "$workspace/$name" ]] && cmp -s "$workspace/$name" "$saved/$name" || return 1
            else
                repair_atomic_replace "$saved/$name" "$workspace/$name" 600 || return 1
            fi
            cmp -s "$workspace/$name" "$saved/$name" || return 1
        fi
    done
}

if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
    return 0
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
source "$script_dir/_common.sh"
source "$script_dir/watchdog.sh"
[[ $# == 0 || ( $# == 1 && "$1" == "--apply" ) ]] || openclaw_fail "Usage: repair-openrouter-token-field.sh [--apply]"
openclaw_load_deployment
install_method="$openclaw_loaded_install_method"
target="$openclaw_runtime_root/lib/node_modules/openclaw/node_modules/@openclaw/ai/dist/openai-completions-stream-Bpns6uNc.mjs"
provenance_target="$openclaw_management_root/local-runtime-provenance.json"
openclaw_assert_owned_nonwritable_file "$target" "pinned OpenRouter compatibility module"
openclaw_assert_private_file "$openclaw_config_path" "existing Gateway config"
repair_assert_workspace "$openclaw_workspace_dir" "$script_dir/templates/AGENTS.md" ||
    openclaw_fail "workspace contains an unreviewed entry; nothing changed"
[[ ! -e "$provenance_target" && ! -L "$provenance_target" ]] ||
    openclaw_fail "existing local runtime provenance requires explicit migration"
"$openclaw_node_path" --permission --allow-fs-read='*' \
    "$script_dir/openrouter-token-field-hotfix.mjs" --verify "$target"
[[ $# == 1 ]] || { echo "PLAN patched2026.8.1: GLM token field, bootstrap isolation, Docker watchdog; no installed state changed"; exit 0; }

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
recovery="$openclaw_backup_root/openrouter-token-field-$stamp-$$"
[[ ! -e "$recovery" && ! -L "$recovery" ]] || openclaw_fail "recovery directory already exists"
openclaw_prepare_private_dir "$recovery" "token-field recovery"
openclaw_prepare_private_dir "$recovery/workspace-before" "workspace byte backup"
openclaw_prepare_private_dir "$recovery/workspace-quarantine" "generated workspace quarantine"
cp -p "$target" "$recovery/original.mjs"
cp -p "$openclaw_deployment_manifest" "$recovery/deployment.json"
cp -p "$openclaw_config_path" "$recovery/openclaw.json"
cp -p "$openclaw_management_common" "$recovery/common.sh"
cp -p "$openclaw_management_watchdog" "$recovery/watchdog.sh"
chmod 600 "$recovery/original.mjs" "$recovery/deployment.json" "$recovery/openclaw.json" \
    "$recovery/common.sh" "$recovery/watchdog.sh"
for name in SOUL.md USER.md IDENTITY.md; do
    if [[ -f "$openclaw_workspace_dir/$name" ]]; then
        cp -p "$openclaw_workspace_dir/$name" "$recovery/workspace-before/$name"
        cmp -s "$openclaw_workspace_dir/$name" "$recovery/workspace-before/$name" ||
            openclaw_fail "workspace backup bytes differ"
    fi
done
workspace_metadata='[]'
for name in SOUL.md USER.md IDENTITY.md; do
    if [[ -f "$recovery/workspace-before/$name" ]]; then
        workspace_metadata="$(jq -c --arg name "$name" \
            --arg sha256 "$(openclaw_sha256 "$recovery/workspace-before/$name")" \
            --argjson bytes "$(stat -f '%z' "$recovery/workspace-before/$name")" \
            '. + [{name:$name,sha256:$sha256,bytes:$bytes}]' <<< "$workspace_metadata")"
    fi
done
printf '%s\n' "$workspace_metadata" > "$recovery/workspace-files.json"
stage="$recovery/patched.mjs"
"$openclaw_node_path" --permission --allow-fs-read='*' --allow-fs-write="$stage" \
    "$script_dir/openrouter-token-field-hotfix.mjs" --stage "$target" "$stage" > "$recovery/patch.json"
chmod 600 "$recovery/patch.json"
"$openclaw_node_path" --check "$stage"
repair_prepare_config "$recovery/openclaw.json" "$recovery/candidate-config.json" ||
    openclaw_fail "candidate changed an unrelated config field"
# Validate the supported pinned config schema without dispatching a model or changing the profile.
env OPENCLAW_CONFIG_PATH="$recovery/candidate-config.json" OPENCLAW_STATE_DIR="$openclaw_state_dir" \
    "$openclaw_node_path" "$openclaw_cli_entry" --profile "$PERSONAL_EDGE_OPENCLAW_PROFILE" config validate > "$recovery/config-validate.log" 2>&1
bash -n "$script_dir/_common.sh"
bash -n "$script_dir/watchdog.sh"

watchdog_target="gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_WATCHDOG_LABEL"
gateway_target="gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL"
launchctl print "$watchdog_target" >/dev/null
launchctl print "$gateway_target" >/dev/null
committed=0
mutated=0
services_suspended=0

repair_start_gateway() {
    local attempt=1
    launchctl bootstrap "gui/$(id -u)" "$openclaw_gateway_plist" || return 1
    while (( attempt <= 6 )); do
        if openclaw_run gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT" >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
        attempt=$((attempt + 1))
    done
    return 1
}

restore_transaction() {
    local result="$?" name
    trap - EXIT INT TERM
    if (( committed == 0 && services_suspended == 1 )); then
        for service in "$watchdog_target" "$gateway_target"; do
            if launchctl print "$service" >/dev/null 2>&1; then
                launchctl bootout "$service" >/dev/null 2>&1 || {
                    echo "FAIL could not suspend service for rollback; recovery: $recovery" >&2
                    exit 1
                }
            fi
        done
        if (( mutated == 1 )); then
            if [[ -e "$provenance_target" || -L "$provenance_target" ]]; then
                if [[ -L "$provenance_target" ]] || ! cmp -s "$recovery/applied.json" "$provenance_target"; then
                    echo "FAIL unexpected provenance was preserved; services remain stopped; recovery: $recovery" >&2
                    exit 1
                fi
                rm "$provenance_target" || exit 1
            fi
            if ! { repair_atomic_replace "$recovery/original.mjs" "$target" 644 &&
                   repair_atomic_replace "$recovery/openclaw.json" "$openclaw_config_path" 600 &&
                   repair_atomic_replace "$recovery/common.sh" "$openclaw_management_common" 600 &&
                   repair_atomic_replace "$recovery/watchdog.sh" "$openclaw_management_watchdog" 700 &&
                   repair_atomic_replace "$recovery/deployment.json" "$openclaw_deployment_manifest" 600 &&
                   repair_restore_workspace "$openclaw_workspace_dir" "$recovery/workspace-before" &&
                   cmp -s "$target" "$recovery/original.mjs" &&
                   cmp -s "$openclaw_config_path" "$recovery/openclaw.json" &&
                   cmp -s "$openclaw_management_common" "$recovery/common.sh" &&
                   cmp -s "$openclaw_management_watchdog" "$recovery/watchdog.sh" &&
                   cmp -s "$openclaw_deployment_manifest" "$recovery/deployment.json"; }; then
                echo "FAIL rollback bytes incomplete; Gateway and watchdog remain stopped; recovery: $recovery" >&2
                exit 1
            fi
        fi
        if ! repair_start_gateway > "$recovery/rollback.log" 2>&1; then
            launchctl bootout "$gateway_target" >/dev/null 2>&1 || true
            echo "FAIL original Gateway recovery failed; services remain stopped; recovery: $recovery" >&2
            exit 1
        fi
        launchctl bootstrap "gui/$(id -u)" "$openclaw_watchdog_plist" || result=1
        printf '%s\n' '{"result":"original-bytes-restored","gatewayHealthy":true}' > "$recovery/rollback.json"
    fi
    exit "$result"
}
trap restore_transaction EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
services_suspended=1
launchctl bootout "$watchdog_target"
launchctl bootout "$gateway_target"
# Recheck exact backups after both services stop, before making any mutation.
cmp -s "$target" "$recovery/original.mjs"
cmp -s "$openclaw_config_path" "$recovery/openclaw.json"
cmp -s "$openclaw_management_common" "$recovery/common.sh"
cmp -s "$openclaw_management_watchdog" "$recovery/watchdog.sh"
cmp -s "$openclaw_deployment_manifest" "$recovery/deployment.json"
repair_assert_workspace "$openclaw_workspace_dir" "$script_dir/templates/AGENTS.md"
mutated=1
repair_atomic_replace "$stage" "$target" 644
repair_atomic_replace "$recovery/candidate-config.json" "$openclaw_config_path" 600
repair_atomic_replace "$script_dir/_common.sh" "$openclaw_management_common" 600
repair_atomic_replace "$script_dir/watchdog.sh" "$openclaw_management_watchdog" 700
repair_quarantine_workspace "$openclaw_workspace_dir" "$recovery/workspace-before" "$recovery/workspace-quarantine"
openclaw_write_deployment_manifest "$install_method" "$openclaw_node_path"
repair_start_gateway > "$recovery/restart.log" 2>&1
launchctl bootstrap "gui/$(id -u)" "$openclaw_watchdog_plist"
"$script_dir/verify-gateway.sh" --acknowledge-transient-session-write > "$recovery/verify.log" 2>&1
# RunAtLoad must finish successfully. A merely loaded or still-running watchdog is not acceptance.
watchdog_passed=0
for (( attempt=1; attempt<=30; attempt++ )); do
    launchctl print "$watchdog_target" > "$recovery/watchdog-first-run.txt"
    if awk '$1 == "runs" && $2 == "=" && $3 ~ /^[1-9][0-9]*$/ { runs=1 }
        $1 == "state" && $2 == "=" && $3 == "not" && $4 == "running" { idle=1 }
        $1 == "last" && $2 == "exit" && $3 == "code" && $4 == "=" && $5 == "0" { passed=1 }
        END { exit !(runs && idle && passed) }' "$recovery/watchdog-first-run.txt"; then
        watchdog_passed=1
        break
    fi
    sleep 2
done
(( watchdog_passed == 1 )) || openclaw_fail "first Docker-aware watchdog run did not complete successfully"
openclaw_load_deployment
# Full verification must not have recreated any bootstrap stubs.
[[ "$(find "$openclaw_workspace_dir" -mindepth 1 -maxdepth 1 ! -name AGENTS.md -print -quit)" == "" ]]
jq -n --slurpfile patch "$recovery/patch.json" --slurpfile workspace "$recovery/workspace-files.json" \
    --slurpfile deployment "$openclaw_deployment_manifest" \
    --arg configSha256 "$(openclaw_sha256 "$openclaw_config_path")" \
    --arg recovery "$recovery" '{
    schemaVersion:1,runtimeIdentity:"patched2026.8.1",officialBinaryIdentical:false,
    baseVersion:"2026.8.1",patch:$patch[0],runtimeTreeSha256:$deployment[0].runtimeTreeSha256,
    commonSha256:$deployment[0].commonSha256,watchdogSha256:$deployment[0].watchdogSha256,
    configSha256:$configSha256,skipBootstrap:true,contextInjection:"never",quarantinedFiles:$workspace[0],
    fullVerifyPassed:true,watchdogFirstRunExitCode:0,inferenceRequests:0,recoveryDirectory:$recovery
}' > "$recovery/applied.json"
repair_atomic_replace "$recovery/applied.json" "$provenance_target" 600
committed=1
echo "OK patched2026.8.1: single-model compatibility, bootstrap isolation, Docker watchdog; recovery: $recovery"
