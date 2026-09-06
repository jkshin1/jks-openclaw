#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

usage() {
    cat <<'EOF'
Usage:
  install-gateway.sh --method homebrew-node|official-user --openrouter-key-file FILE --apply \
    --acknowledge-transient-session-write
  install-gateway.sh --adopt-existing --dry-run
  install-gateway.sh --adopt-existing --apply --acknowledge-transient-session-write

Installs exact OpenClaw 2026.8.1 with a restrictive workspace, SQLite-backed SecretRefs,
the per-user Gateway LaunchAgent, and a five-minute watchdog. It never prints a secret.
Adoption delegates to adopt-existing.sh and never reinstalls the existing runtime.
Apply's final zero-tool gate creates and deletes one incognito session and needs explicit consent.
EOF
}

install_method=""
openrouter_key_file=""
repair=0
apply=0
adopt_existing=0
dry_run=0
acknowledge_transient_session_write=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --method)
            [[ $# -ge 2 ]] || openclaw_fail "--method requires a value"
            install_method="$2"
            shift 2
            ;;
        --openrouter-key-file)
            [[ $# -ge 2 ]] || openclaw_fail "--openrouter-key-file requires a value"
            openrouter_key_file="$2"
            shift 2
            ;;
        --repair) repair=1; shift ;;
        --adopt-existing) adopt_existing=1; shift ;;
        --dry-run) dry_run=1; shift ;;
        --apply) apply=1; shift ;;
        --acknowledge-transient-session-write) acknowledge_transient_session_write=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) openclaw_fail "unknown argument: $1" ;;
    esac
done

if (( adopt_existing == 1 )); then
    [[ -z "$install_method" && -z "$openrouter_key_file" && $repair -eq 0 ]] ||
        openclaw_fail "--adopt-existing cannot be combined with install, key, or repair options"
    (( dry_run + apply == 1 )) ||
        openclaw_fail "adoption requires exactly one of --dry-run or --apply"
    if (( dry_run == 1 )); then
        exec "$script_dir/adopt-existing.sh" --dry-run
    fi
    (( acknowledge_transient_session_write == 1 )) ||
        openclaw_fail "adoption apply requires --acknowledge-transient-session-write"
    exec "$script_dir/adopt-existing.sh" --apply --acknowledge-transient-session-write
fi

(( repair == 0 )) ||
    openclaw_fail "in-place runtime repair is disabled; stage and review a fresh pinned runtime instead"
(( dry_run == 0 )) || openclaw_fail "--dry-run is only valid with --adopt-existing"
[[ "$install_method" == "homebrew-node" || "$install_method" == "official-user" ]] ||
    openclaw_fail "--method must be homebrew-node or official-user"
(( apply == 1 )) || openclaw_fail "refusing to install without --apply"
(( acknowledge_transient_session_write == 1 )) ||
    openclaw_fail "fresh install requires --acknowledge-transient-session-write for its final incognito zero-tool session"
[[ "$(uname -s)" == "Darwin" ]] || openclaw_fail "this deployment is macOS-only"
[[ "$(id -u)" != "0" ]] || openclaw_fail "run as the dedicated login user, not root"
for required_command in awk chmod cmp cp curl env find id jq launchctl lsof mkdir mktemp mv openssl plutil realpath stat uname; do
    openclaw_require_command "$required_command"
done

known_deployment=0
if [[ -f "$openclaw_deployment_manifest" ]]; then
    (( repair == 1 )) ||
        openclaw_fail "deployment already exists; in-place runtime repair is intentionally disabled"
    openclaw_load_deployment
    [[ "$openclaw_loaded_install_method" == "$install_method" ]] ||
        openclaw_fail "--repair cannot change the install method or repair an adopted runtime"
    known_deployment=1
elif (( repair == 1 )); then
    openclaw_fail "--repair requires an existing deployment manifest"
fi

if (( known_deployment == 0 )); then
    [[ -n "$openrouter_key_file" ]] ||
        openclaw_fail "fresh installation requires --openrouter-key-file"
    openclaw_assert_private_secret_file "$openrouter_key_file" "OpenRouter API key file"
    [[ ! -e "$openclaw_config_path" ]] ||
        openclaw_fail "existing OpenClaw config is not owned by this deployment: $openclaw_config_path"
    for unmanaged_dir in "$openclaw_state_dir" "$openclaw_workspace_dir" "$openclaw_runtime_root"; do
        if [[ -d "$unmanaged_dir" ]]; then
            unmanaged_entry="$(find "$unmanaged_dir" -mindepth 1 -maxdepth 1 -print -quit)"
            [[ -z "$unmanaged_entry" ]] ||
                openclaw_fail "refusing to reuse non-empty unmanaged path: $unmanaged_dir"
        elif [[ -e "$unmanaged_dir" || -L "$unmanaged_dir" ]]; then
            openclaw_fail "unmanaged deployment path is not a real directory: $unmanaged_dir"
        fi
    done
else
    [[ -z "$openrouter_key_file" ]] ||
        openclaw_fail "--repair preserves the secret store; omit --openrouter-key-file"
    openclaw_assert_private_file "$openclaw_config_path" "OpenClaw config"
    openclaw_assert_restrictive_config "$openclaw_config_path"
    [[ -f "$openclaw_workspace_dir/AGENTS.md" && ! -L "$openclaw_workspace_dir/AGENTS.md" ]] ||
        openclaw_fail "restrictive workspace AGENTS.md is missing"
    cmp -s "$script_dir/templates/AGENTS.md" "$openclaw_workspace_dir/AGENTS.md" ||
        openclaw_fail "existing workspace AGENTS.md differs from the restrictive policy"
fi

umask 077
openclaw_prepare_private_dir "$openclaw_state_dir" "OpenClaw state directory"
openclaw_prepare_private_dir "$openclaw_runtime_root" "OpenClaw runtime directory"
openclaw_prepare_private_dir "$openclaw_management_root" "OpenClaw management directory"
openclaw_prepare_private_dir "$openclaw_management_root/bin" "OpenClaw management bin directory"
openclaw_prepare_private_dir "$openclaw_management_root/libexec" "OpenClaw management libexec directory"
openclaw_prepare_owned_dir "$openclaw_launch_agent_dir" "LaunchAgents directory"

node_path=""
if [[ "$install_method" == "homebrew-node" ]]; then
    node_path="${PERSONAL_EDGE_OPENCLAW_NODE_BIN:-/opt/homebrew/opt/node/bin/node}"
    npm_path="$(dirname "$node_path")/npm"
    [[ -x "$node_path" && ! -L "$node_path" ]] || openclaw_fail "Homebrew Node is missing: $node_path"
    [[ -x "$npm_path" ]] || openclaw_fail "Homebrew npm is missing: $npm_path"
    node_major="$("$node_path" -p 'process.versions.node.split(".")[0]')"
    [[ "$node_major" == "$PERSONAL_EDGE_OPENCLAW_NODE_MAJOR" ]] ||
        openclaw_fail "expected Homebrew Node major 26, found $node_major"
    env PATH="$(dirname "$node_path"):/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
        "$npm_path" install --prefix "$openclaw_runtime_root" --no-package-lock \
        --allow-scripts=openclaw "openclaw@$PERSONAL_EDGE_OPENCLAW_VERSION"
else
    installer_path="$(mktemp "$openclaw_tmp_root/personal-edge-openclaw-installer.XXXXXX")"
    cleanup_installer() {
        case "$installer_path" in
            /tmp/personal-edge-openclaw-installer.*|/private/tmp/personal-edge-openclaw-installer.*|*/T/personal-edge-openclaw-installer.*)
                rm -f -- "$installer_path"
                ;;
        esac
    }
    trap cleanup_installer EXIT INT TERM
    curl -fsSL --proto '=https' --tlsv1.2 https://openclaw.ai/install-cli.sh -o "$installer_path"
    chmod 700 "$installer_path"
    bash "$installer_path" --prefix "$openclaw_runtime_root" --install-method npm \
        --version "$PERSONAL_EDGE_OPENCLAW_VERSION" \
        --node-version "$PERSONAL_EDGE_OPENCLAW_OFFICIAL_NODE_VERSION" --no-onboard
    node_path="$openclaw_runtime_root/tools/node-v$PERSONAL_EDGE_OPENCLAW_OFFICIAL_NODE_VERSION/bin/node"
    cleanup_installer
    trap - EXIT INT TERM
fi

[[ -x "$node_path" && ! -L "$node_path" ]] || openclaw_fail "installed Node is missing: $node_path"
openclaw_assert_owned_nonwritable_file "$openclaw_package_json" "OpenClaw package metadata"
openclaw_assert_owned_nonwritable_file "$openclaw_cli_entry" "OpenClaw CLI entry"
openclaw_assert_owned_nonwritable_file "$openclaw_gateway_entry" "OpenClaw Gateway entry"
[[ "$(jq -r '.version' "$openclaw_package_json")" == "$PERSONAL_EDGE_OPENCLAW_VERSION" ]] ||
    openclaw_fail "installed OpenClaw package version is not $PERSONAL_EDGE_OPENCLAW_VERSION"
installed_version_output="$(env PATH="$(dirname "$node_path"):/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    "$node_path" "$openclaw_cli_entry" --profile "$PERSONAL_EDGE_OPENCLAW_PROFILE" --version)"
[[ "$(openclaw_extract_version "$installed_version_output")" == "$PERSONAL_EDGE_OPENCLAW_VERSION" ]] ||
    openclaw_fail "installed OpenClaw CLI version is not $PERSONAL_EDGE_OPENCLAW_VERSION"

openclaw_prepare_private_dir "$openclaw_workspace_dir" "OpenClaw restricted workspace"
if [[ -e "$openclaw_workspace_dir/AGENTS.md" ]] &&
   ! cmp -s "$script_dir/templates/AGENTS.md" "$openclaw_workspace_dir/AGENTS.md"; then
    openclaw_fail "existing workspace AGENTS.md differs from the restrictive policy"
fi
cp "$script_dir/templates/AGENTS.md" "$openclaw_workspace_dir/AGENTS.md"
chmod 600 "$openclaw_workspace_dir/AGENTS.md"
if (( known_deployment == 0 )); then
    config_temp="$(mktemp "$openclaw_state_dir/.openclaw.json.partial.XXXXXX")"
    jq --arg workspace "$openclaw_workspace_dir" '.agents.defaults.workspace = $workspace' \
        "$script_dir/templates/openclaw.json" > "$config_temp"
    chmod 600 "$config_temp"
    mv "$config_temp" "$openclaw_config_path"
fi
openclaw_assert_private_file "$openclaw_config_path" "OpenClaw config"
openclaw_assert_restrictive_config "$openclaw_config_path"

openclaw_write_managed_wrapper "$node_path"
openclaw_node_path="$node_path"
openclaw_wrapper_path="$openclaw_cli_path"
secret_temp=""
cleanup_secret_temp() {
    case "$secret_temp" in
        /tmp/personal-edge-gateway-token.*|/private/tmp/personal-edge-gateway-token.*|*/T/personal-edge-gateway-token.*)
            rm -f -- "$secret_temp"
            ;;
    esac
}
if (( known_deployment == 0 )); then
    secret_temp="$(mktemp "$openclaw_tmp_root/personal-edge-gateway-token.XXXXXX")"
    chmod 600 "$secret_temp"
    trap cleanup_secret_temp EXIT INT TERM
    openssl rand -hex 32 > "$secret_temp"
    openclaw_run secrets store set OPENCLAW_GATEWAY_TOKEN --kind secret --value-file "$secret_temp" >/dev/null
    cleanup_secret_temp
    secret_temp=""
    openclaw_run secrets store set OPENROUTER_API_KEY --kind secret --value-file "$openrouter_key_file" >/dev/null
    trap - EXIT INT TERM
fi

openclaw_run config validate >/dev/null
receipt_temp="$(mktemp -d "$openclaw_tmp_root/personal-edge-openclaw-install.XXXXXX")"
cleanup_receipts() {
    case "$receipt_temp" in
        /tmp/personal-edge-openclaw-install.*|/private/tmp/personal-edge-openclaw-install.*|*/T/personal-edge-openclaw-install.*)
            rm -rf -- "$receipt_temp"
            ;;
    esac
}
trap cleanup_receipts EXIT INT TERM
chmod 700 "$receipt_temp"
openclaw_run secrets audit --check --json > "$receipt_temp/secrets.json" 2> "$receipt_temp/secrets.err"
openclaw_assert_clean_secrets_receipt "$receipt_temp/secrets.json"
openclaw_run plugins list --json > "$receipt_temp/plugins.json" 2> "$receipt_temp/plugins.err"
openclaw_assert_required_plugins_receipt "$receipt_temp/plugins.json"

cp "$script_dir/_common.sh" "$openclaw_management_root/libexec/_common.sh"
cp "$script_dir/watchdog.sh" "$openclaw_management_root/libexec/watchdog.sh"
chmod 600 "$openclaw_management_root/libexec/_common.sh"
chmod 700 "$openclaw_management_root/libexec/watchdog.sh"
manifest_candidate="$openclaw_management_root/.deployment.candidate.json"
[[ ! -e "$manifest_candidate" && ! -L "$manifest_candidate" ]] ||
    openclaw_fail "stale deployment manifest candidate exists: $manifest_candidate"
install_committed=0
cleanup_failed_install() {
    local exit_status="$?"
    local failure_stamp failed_manifest failed_watchdog gateway_listener_check_failed
    local gateway_listener_present watchdog_loaded
    local gateway_target watchdog_target
    trap - EXIT INT TERM
    if (( install_committed == 0 )); then
        failure_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
        gateway_target="gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL"
        watchdog_target="gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_WATCHDOG_LABEL"

        launchctl bootout "$watchdog_target" >/dev/null 2>&1 || true
        watchdog_loaded=0
        if launchctl print "$watchdog_target" >/dev/null 2>&1; then
            watchdog_loaded=1
        fi
        if [[ -e "$openclaw_watchdog_plist" || -L "$openclaw_watchdog_plist" ]]; then
            failed_watchdog="$openclaw_management_root/.watchdog.failed.$failure_stamp.$$.plist"
            if [[ ! -e "$failed_watchdog" && ! -L "$failed_watchdog" ]] &&
                mv -- "$openclaw_watchdog_plist" "$failed_watchdog"; then
                echo "WARN failed-install watchdog plist quarantined at $failed_watchdog" >&2
            else
                echo "WARN failed-install watchdog plist could not be quarantined: $openclaw_watchdog_plist" >&2
            fi
        fi

        if ! openclaw_run gateway stop --disable >/dev/null 2>&1; then
            echo "WARN managed OpenClaw CLI could not stop/disable the failed Gateway" >&2
        fi
        launchctl disable "$gateway_target" >/dev/null 2>&1 || true
        launchctl bootout "$gateway_target" >/dev/null 2>&1 || true

        gateway_listener_present=0
        gateway_listener_check_failed=0
        if lsof -nP -iTCP:"$PERSONAL_EDGE_OPENCLAW_PORT" -sTCP:LISTEN -F n \
            > "$receipt_temp/failed-install-listeners" \
            2> "$receipt_temp/failed-install-listeners.err"; then
            if awk '/^n/ { found = 1 } END { exit(found ? 0 : 1) }' \
                "$receipt_temp/failed-install-listeners"; then
                gateway_listener_present=1
            fi
        elif [[ -s "$receipt_temp/failed-install-listeners.err" ]]; then
            gateway_listener_check_failed=1
        fi
        if (( watchdog_loaded == 1 )) ||
            (( gateway_listener_check_failed == 1 )) ||
            launchctl print "$gateway_target" >/dev/null 2>&1 ||
            (( gateway_listener_present == 1 )); then
            echo "WARN failed-install Gateway/watchdog stopped state could not be proven; review launchd and TCP port $PERSONAL_EDGE_OPENCLAW_PORT immediately" >&2
        else
            echo "WARN failed install left the Gateway stopped/disabled and the watchdog unloaded" >&2
        fi

        if [[ -f "$manifest_candidate" && ! -L "$manifest_candidate" ]]; then
            failed_manifest="$openclaw_management_root/.deployment.failed.$failure_stamp.json"
            if [[ ! -e "$failed_manifest" && ! -L "$failed_manifest" ]] &&
                mv "$manifest_candidate" "$failed_manifest"; then
                echo "WARN unpublished deployment manifest quarantined at $failed_manifest" >&2
            else
                echo "WARN unpublished deployment manifest could not be quarantined: $manifest_candidate" >&2
            fi
        fi
    fi
    cleanup_receipts
    exit "$exit_status"
}
trap cleanup_failed_install EXIT INT TERM
openclaw_write_deployment_manifest "$install_method" "$node_path" "$manifest_candidate"

openclaw_run gateway install --runtime node --wrapper "$openclaw_cli_path" --force
openclaw_run gateway start
openclaw_prepare_private_dir "$openclaw_state_dir/logs" "OpenClaw logs directory"
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
launchctl bootout "gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_WATCHDOG_LABEL" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$openclaw_watchdog_plist"
launchctl enable "gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_WATCHDOG_LABEL"
launchctl kickstart "gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_WATCHDOG_LABEL"

PERSONAL_EDGE_OPENCLAW_DEPLOYMENT_MANIFEST="$manifest_candidate" \
    "$script_dir/verify-gateway.sh" --acknowledge-transient-session-write

# Publish the management manifest only after the exact runtime, config, workspace, plugins,
# secrets, service definitions, listeners, and live RPC checks all succeeded.
mv "$manifest_candidate" "$openclaw_deployment_manifest"
install_committed=1
trap cleanup_receipts EXIT INT TERM
cleanup_receipts
trap - EXIT INT TERM
openclaw_note "OpenClaw $PERSONAL_EDGE_OPENCLAW_VERSION is installed with restrictive tools and SecretRefs."
openclaw_note "Tailscale Serve remains off until enable-tailscale-serve.sh --apply succeeds."
