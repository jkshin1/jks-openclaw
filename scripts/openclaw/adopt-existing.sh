#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

usage() {
    cat <<'EOF'
Usage: adopt-existing.sh --dry-run | --apply --acknowledge-transient-session-write

Adopts the already-installed `personaledge` profile without reinstalling OpenClaw or rewriting
its config, SQLite auth stores, service environment, or SecretRefs. Dry-run performs no
script-authored installed-state write. Apply runs the same preflight, creates and verifies a full
backup before its first script-authored installed-state change, quarantines the old workspace
intact, then installs only reviewed management assets. Apply's final zero-tool gate creates and
deletes one incognito session and therefore requires the explicit acknowledgement flag.
EOF
}

dry_run=0
apply=0
acknowledge_transient_session_write=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) dry_run=1; shift ;;
        --apply) apply=1; shift ;;
        --acknowledge-transient-session-write) acknowledge_transient_session_write=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) openclaw_fail "unknown argument: $1" ;;
    esac
done
(( dry_run + apply == 1 )) || openclaw_fail "choose exactly one of --dry-run or --apply"
if (( apply == 1 && acknowledge_transient_session_write == 0 )); then
    openclaw_fail "adoption apply requires --acknowledge-transient-session-write for its final incognito zero-tool session"
fi
[[ "$(uname -s)" == "Darwin" ]] || openclaw_fail "adoption is macOS-only"
[[ "$(id -u)" != "0" ]] || openclaw_fail "run as the profile owner, not root"

for required_command in \
    awk chmod cmp cp date find id jq launchctl lsof mkdir mktemp mv plutil realpath \
    shasum sort sqlite3 stat uname; do
    openclaw_require_command "$required_command"
done

node_path="${PERSONAL_EDGE_OPENCLAW_NODE_BIN:-/opt/homebrew/opt/node/bin/node}"
service_wrapper="$openclaw_state_dir/service-env/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL-env-wrapper.sh"
service_environment="$openclaw_state_dir/service-env/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL.env"
gateway_target="gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL"
watchdog_target="gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_WATCHDOG_LABEL"
shared_auth_db="$openclaw_state_dir/state/openclaw.sqlite"
agent_auth_db="$openclaw_state_dir/agents/main/agent/openclaw-agent.sqlite"

preflight_temp="$(mktemp -d "$openclaw_tmp_root/personal-edge-openclaw-adopt.XXXXXX")"
cli_invoked=0
preflight_verified=0
cleanup_preflight() {
    local exit_status="$?"
    case "$preflight_temp" in
        /tmp/personal-edge-openclaw-adopt.*|/private/tmp/personal-edge-openclaw-adopt.*|*/T/personal-edge-openclaw-adopt.*)
            if (( exit_status != 0 && cli_invoked == 1 && preflight_verified == 0 )); then
                echo "WARN pre-CLI adoption recovery snapshot retained after failed verification: $preflight_temp" >&2
            else
                rm -rf -- "$preflight_temp"
            fi
            ;;
        *)
            echo "WARN refusing to remove unexpected temp path: $preflight_temp" >&2
            ;;
    esac
}
trap cleanup_preflight EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
chmod 700 "$preflight_temp"

snapshot_sensitive_state() {
    local output_path="$1"
    openclaw_snapshot_security_state \
        "$output_path" "$preflight_temp" "$service_wrapper" "$service_environment"
}

[[ "$openclaw_config_path" == "$openclaw_state_dir/openclaw.json" ]] ||
    openclaw_fail "adoption requires the profile config at state/openclaw.json"
[[ "$openclaw_workspace_dir" == "$openclaw_state_dir/workspace" ]] ||
    openclaw_fail "adoption requires the profile workspace at state/workspace"
[[ "$openclaw_management_root" == "$openclaw_runtime_root/.personal-edge-management" ]] ||
    openclaw_fail "adoption management root must be inside the pinned runtime"
[[ ! -e "$openclaw_deployment_manifest" ]] ||
    openclaw_fail "deployment is already managed; run verify-gateway.sh instead"
[[ ! -e "$openclaw_management_root" && ! -L "$openclaw_management_root" ]] ||
    openclaw_fail "unmanaged management root already exists: $openclaw_management_root"
[[ ! -e "$openclaw_watchdog_plist" ]] ||
    openclaw_fail "an unmanaged watchdog plist already exists"
if launchctl print "$watchdog_target" >/dev/null 2>&1; then
    openclaw_fail "an unmanaged watchdog service is already loaded"
fi

openclaw_assert_safe_path "$openclaw_state_dir" "OpenClaw profile state"
openclaw_assert_owned_nonwritable_dir "$openclaw_state_dir" "OpenClaw profile state"
state_mode="$(stat -f '%Lp' "$openclaw_state_dir")"
(( (8#$state_mode & 8#077) == 0 )) || openclaw_fail "OpenClaw profile state must be mode 700"
openclaw_assert_private_file "$openclaw_config_path" "OpenClaw profile config"
openclaw_assert_owned_nonwritable_dir "$openclaw_workspace_dir" "OpenClaw workspace"
openclaw_assert_private_file "$service_wrapper" "Gateway service environment wrapper"
openclaw_assert_private_file "$service_environment" "Gateway service environment"
openclaw_assert_private_file "$shared_auth_db" "shared SQLite auth store"
openclaw_assert_private_file "$agent_auth_db" "main-agent SQLite auth store"

# Pin authored config/service bytes and logical credential rows before any installed Node or
# OpenClaw code runs. Live WAL/SHM and volatile runtime rows are intentionally not compared.
snapshot_sensitive_state "$preflight_temp/protected-before"
recovery_dir="$preflight_temp/pre-cli-recovery"
mkdir "$recovery_dir"
chmod 700 "$recovery_dir"
cp "$openclaw_config_path" "$recovery_dir/openclaw.json"
cp "$service_wrapper" "$recovery_dir/service-wrapper.sh"
cp "$service_environment" "$recovery_dir/service-environment.env"
sqlite3 -readonly -cmd '.timeout 5000' "$shared_auth_db" \
    ".backup '$recovery_dir/openclaw.sqlite'"
sqlite3 -readonly -cmd '.timeout 5000' "$agent_auth_db" \
    ".backup '$recovery_dir/openclaw-agent.sqlite'"
chmod 600 "$recovery_dir"/*

cli_invoked=1
openclaw_assert_existing_runtime

openclaw_assert_adopted_service_definition "$node_path"

while IFS= read -r -d '' sensitive_file; do
    openclaw_assert_private_file "$sensitive_file" "sensitive OpenClaw state file"
done < <(
    find \
        "$openclaw_state_dir/state" "$openclaw_state_dir/agents" "$openclaw_state_dir/service-env" \
        -type f \( -name 'openclaw.sqlite*' -o -name 'openclaw-agent.sqlite*' -o -name '*.env' \
        -o -name '*auth*.json' -o -name '*oauth*.json' -o -name '*env-wrapper.sh' \) -print0
)
for sensitive_root in "$openclaw_state_dir/credentials" "$openclaw_state_dir/mcp-oauth"; do
    if [[ -d "$sensitive_root" && ! -L "$sensitive_root" ]]; then
        unsafe_sensitive_path="$(find "$sensitive_root" \( -type f -o -type d \) \
            \( -perm -040 -o -perm -020 -o -perm -010 -o -perm -004 -o -perm -002 -o -perm -001 \) \
            -print -quit)"
        [[ -z "$unsafe_sensitive_path" ]] ||
            openclaw_fail "credential tree is accessible by group or other: $unsafe_sensitive_path"
        unsafe_sensitive_path="$(find "$sensitive_root" \( -type f -o -type d \) ! -user "$(id -un)" -print -quit)"
        [[ -z "$unsafe_sensitive_path" ]] ||
            openclaw_fail "credential tree contains a foreign-owned path: $unsafe_sensitive_path"
    elif [[ -e "$sensitive_root" || -L "$sensitive_root" ]]; then
        openclaw_fail "credential root is not a real directory: $sensitive_root"
    fi
done
[[ ! -e "$openclaw_state_dir/.env" && ! -L "$openclaw_state_dir/.env" ]] ||
    openclaw_fail "legacy profile .env must be migrated to SecretRefs before adoption"

openclaw_assert_restrictive_config "$openclaw_config_path"
jq -e '.gateway.tailscale.mode == "off"' "$openclaw_config_path" >/dev/null ||
    openclaw_fail "adoption requires Tailscale Serve to remain off until separately approved"

openclaw_existing_cli config validate >/dev/null
if ! openclaw_existing_cli secrets audit --check --json \
    > "$preflight_temp/secrets-audit.json" 2> "$preflight_temp/secrets-audit.err"; then
    openclaw_assert_clean_secrets_receipt "$preflight_temp/secrets-audit.json"
    openclaw_fail "secrets audit failed without exposing its private report"
fi
openclaw_assert_clean_secrets_receipt "$preflight_temp/secrets-audit.json"
if ! openclaw_existing_cli plugins list --json \
    > "$preflight_temp/plugins.json" 2> "$preflight_temp/plugins.err"; then
    openclaw_fail "plugin inventory failed without exposing its private report"
fi
openclaw_assert_required_plugins_receipt "$preflight_temp/plugins.json"
openclaw_existing_cli models status --check --json \
    > "$preflight_temp/models.json" 2> "$preflight_temp/models.err"
launchctl print "$gateway_target" >/dev/null || openclaw_fail "Gateway LaunchAgent is not loaded"
openclaw_existing_cli gateway status --require-rpc --json > "$preflight_temp/gateway-status.json"
openclaw_existing_cli gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT" >/dev/null
openclaw_existing_cli health --json > "$preflight_temp/health.json" 2> "$preflight_temp/health.err"
openclaw_assert_live_plugins_receipt "$preflight_temp/health.json"

listener_count=0
while IFS= read -r listener_name; do
    [[ -n "$listener_name" ]] || continue
    listener_count=$((listener_count + 1))
    case "$listener_name" in
        "n127.0.0.1:$PERSONAL_EDGE_OPENCLAW_PORT"|"n[::1]:$PERSONAL_EDGE_OPENCLAW_PORT") ;;
        *) openclaw_fail "Gateway has a non-loopback listener" ;;
    esac
done < <(lsof -nP -iTCP:"$PERSONAL_EDGE_OPENCLAW_PORT" -sTCP:LISTEN -F n 2>/dev/null | awk '/^n/ { print }')
(( listener_count > 0 )) || openclaw_fail "Gateway has no live loopback listener"
snapshot_sensitive_state "$preflight_temp/protected-after-preflight"
cmp -s "$preflight_temp/protected-before" "$preflight_temp/protected-after-preflight" ||
    openclaw_fail "read-only adoption preflight changed protected config/auth/SecretRef state"
preflight_verified=1

echo "OK adopt preflight: exact OpenClaw 2026.8.1 / Node 26 runtime"
echo "OK adopt preflight: restrictive config, clean SecretRefs, required plugins, private auth stores"
echo "OK adopt preflight: ai.openclaw.personaledge is KeepAlive, RPC healthy, loopback-only"
if (( dry_run == 1 )); then
    echo "DRY-RUN no script-authored installed-state write; config/service bytes and credential/auth-profile logical rows unchanged"
    exit 0
fi
adoption_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
adoption_backup_dir="$openclaw_backup_root/adoption-$adoption_stamp"
[[ ! -e "$adoption_backup_dir" && ! -L "$adoption_backup_dir" ]] ||
    openclaw_fail "adoption backup path already exists: $adoption_backup_dir"
openclaw_prepare_private_dir "$openclaw_backup_root" "OpenClaw backup root"
openclaw_prepare_private_dir "$adoption_backup_dir" "adoption backup directory"
openclaw_existing_cli backup create --output "$adoption_backup_dir" --verify --json \
    > "$preflight_temp/backup-receipt.json"
chmod 600 "$preflight_temp/backup-receipt.json"
[[ -n "$(find "$adoption_backup_dir" -type f -print -quit)" ]] ||
    openclaw_fail "verified adoption backup did not create an archive"
snapshot_sensitive_state "$preflight_temp/protected-after-backup"
cmp -s "$preflight_temp/protected-before" "$preflight_temp/protected-after-backup" ||
    openclaw_fail "protected config/auth/SecretRef state changed during backup; management was not installed"
cp "$preflight_temp/backup-receipt.json" "$adoption_backup_dir/backup-receipt.json"
chmod 600 "$adoption_backup_dir/backup-receipt.json"

workspace_staging="$openclaw_state_dir/.workspace-personal-edge-adopt-$adoption_stamp"
quarantine_dir="$openclaw_state_dir/quarantine/adopt-$adoption_stamp"
[[ ! -e "$workspace_staging" && ! -e "$quarantine_dir" ]] ||
    openclaw_fail "adoption workspace staging path already exists"
openclaw_prepare_private_dir "$workspace_staging" "restricted workspace staging"
cp "$script_dir/templates/AGENTS.md" "$workspace_staging/AGENTS.md"
chmod 600 "$workspace_staging/AGENTS.md"
openclaw_prepare_private_dir "$quarantine_dir" "pre-adoption workspace quarantine"

adoption_committed=0
workspace_moved=0
gateway_generation_may_have_changed=0
adoption_failure_cleanup() {
    local exit_status="$?"
    trap - EXIT INT TERM
    if (( adoption_committed == 0 )); then
        launchctl bootout "$watchdog_target" >/dev/null 2>&1 || true
        if [[ -e "$openclaw_watchdog_plist" && ! -L "$openclaw_watchdog_plist" ]]; then
            mv "$openclaw_watchdog_plist" "$quarantine_dir/watchdog-failed.plist" || true
        fi
        if (( workspace_moved == 1 )) && [[ -d "$quarantine_dir/workspace-before-adopt" ]]; then
            if [[ -d "$openclaw_workspace_dir" && ! -L "$openclaw_workspace_dir" ]]; then
                mv "$openclaw_workspace_dir" "$quarantine_dir/restricted-workspace-failed" || true
            fi
            mv "$quarantine_dir/workspace-before-adopt" "$openclaw_workspace_dir" || true
        fi
        if [[ -d "$workspace_staging" && ! -L "$workspace_staging" ]]; then
            mv "$workspace_staging" "$quarantine_dir/restricted-workspace-staging-failed" || true
        fi
        if (( gateway_generation_may_have_changed == 1 )); then
            if ! openclaw_existing_cli gateway restart --preserve-definition >/dev/null 2>&1 ||
               ! openclaw_existing_cli gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT" \
                    >/dev/null 2>&1; then
                openclaw_existing_cli gateway stop --disable >/dev/null 2>&1 || true
                launchctl disable "$gateway_target" >/dev/null 2>&1 || true
                launchctl bootout "$gateway_target" >/dev/null 2>&1 || true
                echo "WARN original-workspace Gateway generation could not be verified; Gateway was stopped best-effort" >&2
            fi
        fi
        if [[ -d "$openclaw_management_root" && ! -L "$openclaw_management_root" ]]; then
            mv "$openclaw_management_root" "$quarantine_dir/management-failed" || true
        fi
        echo "WARN adoption failed; original workspace was restored and partial assets were quarantined" >&2
    fi
    cleanup_preflight
    exit "$exit_status"
}
trap adoption_failure_cleanup EXIT INT TERM

openclaw_prepare_private_dir "$openclaw_management_root" "OpenClaw management root"
openclaw_prepare_private_dir "$openclaw_management_root/bin" "OpenClaw management bin"
openclaw_prepare_private_dir "$openclaw_management_root/libexec" "OpenClaw management libexec"
openclaw_write_managed_wrapper "$node_path"
cp "$script_dir/_common.sh" "$openclaw_management_root/libexec/_common.sh"
cp "$script_dir/watchdog.sh" "$openclaw_management_root/libexec/watchdog.sh"
chmod 600 "$openclaw_management_root/libexec/_common.sh"
chmod 700 "$openclaw_management_root/libexec/watchdog.sh"
workspace_moved=1
mv "$openclaw_workspace_dir" "$quarantine_dir/workspace-before-adopt"
mv "$workspace_staging" "$openclaw_workspace_dir"

# Reload the actual Gateway child only after the verified backup and workspace switch. This makes
# the effective tool/plugin/workspace generation match the already-validated on-disk config.
gateway_generation_may_have_changed=1
openclaw_existing_cli gateway restart --preserve-definition >/dev/null
openclaw_existing_cli gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT" >/dev/null

openclaw_prepare_private_dir "$openclaw_state_dir/logs" "OpenClaw logs directory"
openclaw_prepare_owned_dir "$openclaw_launch_agent_dir" "LaunchAgents directory"
watchdog_temp="$(mktemp "$openclaw_launch_agent_dir/.watchdog.plist.partial.XXXXXX")"
jq -n \
    --arg label "$PERSONAL_EDGE_OPENCLAW_WATCHDOG_LABEL" \
    --arg scriptPath "$openclaw_management_root/libexec/watchdog.sh" \
    --arg allowedRoot "$openclaw_user_root" \
    --arg stateDir "$openclaw_state_dir" \
    --arg configPath "$openclaw_config_path" \
    --arg runtimeRoot "$openclaw_runtime_root" \
    --arg managementRoot "$openclaw_management_root" \
    --arg launchAgentDir "$openclaw_launch_agent_dir" \
    --arg stdoutPath "$openclaw_state_dir/logs/personal-edge-watchdog.log" \
    --arg stderrPath "$openclaw_state_dir/logs/personal-edge-watchdog.err.log" '
    {
        Label: $label,
        ProgramArguments: ["/bin/bash", $scriptPath],
        EnvironmentVariables: {
            PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT: $allowedRoot,
            PERSONAL_EDGE_OPENCLAW_STATE_DIR: $stateDir,
            PERSONAL_EDGE_OPENCLAW_CONFIG_PATH: $configPath,
            PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT: $runtimeRoot,
            PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT: $managementRoot,
            PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR: $launchAgentDir
        },
        RunAtLoad: true,
        StartInterval: 300,
        ProcessType: "Standard",
        StandardOutPath: $stdoutPath,
        StandardErrorPath: $stderrPath
    }
' | plutil -convert xml1 -o "$watchdog_temp" -- -
chmod 600 "$watchdog_temp"
plutil -lint "$watchdog_temp" >/dev/null
mv "$watchdog_temp" "$openclaw_watchdog_plist"
manifest_candidate="$openclaw_management_root/.deployment.candidate.json"
[[ ! -e "$manifest_candidate" && ! -L "$manifest_candidate" ]] ||
    openclaw_fail "stale deployment manifest candidate exists: $manifest_candidate"
openclaw_write_deployment_manifest adopted-homebrew-node "$node_path" "$manifest_candidate"
launchctl bootstrap "gui/$(id -u)" "$openclaw_watchdog_plist"
launchctl enable "$watchdog_target"
launchctl kickstart "$watchdog_target"

snapshot_sensitive_state "$preflight_temp/protected-after-adopt"
cmp -s "$preflight_temp/protected-before" "$preflight_temp/protected-after-adopt" ||
    openclaw_fail "protected config/auth/SecretRef state changed during adoption"

PERSONAL_EDGE_OPENCLAW_DEPLOYMENT_MANIFEST="$manifest_candidate" \
    "$script_dir/verify-gateway.sh" --acknowledge-transient-session-write
snapshot_sensitive_state "$preflight_temp/protected-after-verify"
cmp -s "$preflight_temp/protected-before" "$preflight_temp/protected-after-verify" ||
    openclaw_fail "protected config/auth/SecretRef state changed during final verification"

# The final management marker is an atomic last publication. Until this move succeeds, failure
# cleanup restores the old workspace and quarantines every partial management/watchdog asset.
mv "$manifest_candidate" "$openclaw_deployment_manifest"
adoption_committed=1
trap cleanup_preflight EXIT INT TERM
echo "OK existing personaledge deployment adopted without changing config, auth databases, or SecretRefs"
echo "OK prior workspace is recoverable at $quarantine_dir/workspace-before-adopt"
echo "OK verified pre-adoption backup is private under $adoption_backup_dir"
