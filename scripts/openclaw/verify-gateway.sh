#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# The archived Android relay verifier remains available with its original strict contract.
# Telegram is a host agent with a positive tool inventory and a separate operating policy.
if [[ "${1:-}" == "--telegram" ]]; then
    shift
    exec python3 "$script_dir/verify-telegram-gateway.py" "$@"
fi
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

usage() {
    cat <<'EOF'
Usage: verify-gateway.sh [--skip-live | --observe-only] [--acknowledge-transient-session-write]
       verify-gateway.sh --telegram [--skip-live] [--json] [--owner-id ID]

Use --telegram for the active Mac/Telegram deployment. The options below apply only to the
archived, tool-free Android relay; its pinned manifest is not the current Telegram receipt.

Verifies pinned runtime hashes, private state, restrictive config/workspace, store SecretRefs,
bundled provider/pairing plugins, LaunchAgent policy, loopback listeners, and RPC health.
--skip-live omits only launchctl/listener/RPC checks.
--observe-only is a non-mutating soak lane: it keeps static policy/hash checks and live
health/listener checks, but omits config validate, secrets audit, plugin/model inventory, and the
transient sessions.create/delete tools.effective probe. Full live verification requires explicit
acknowledgement of that session write.
EOF
}

skip_live=0
observe_only=0
acknowledge_transient_session_write=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-live) skip_live=1; shift ;;
        --observe-only) observe_only=1; shift ;;
        --acknowledge-transient-session-write) acknowledge_transient_session_write=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) openclaw_fail "unknown argument: $1" ;;
    esac
done
(( skip_live + observe_only <= 1 )) ||
    openclaw_fail "--skip-live and --observe-only are mutually exclusive"
if (( skip_live == 0 && observe_only == 0 && acknowledge_transient_session_write == 0 )); then
    openclaw_fail "full live verification requires --acknowledge-transient-session-write because it creates and deletes one incognito session"
fi
for required_command in cmp date find id jq lsof plutil stat; do
    openclaw_require_command "$required_command"
done
openclaw_load_deployment
install_method="$openclaw_loaded_install_method"

node_major="$("$openclaw_node_path" -p 'process.versions.node.split(".")[0]')"
[[ "$node_major" == "$PERSONAL_EDGE_OPENCLAW_NODE_MAJOR" ]] ||
    openclaw_fail "expected Node major 26, found $node_major"
installed_version_output="$(openclaw_run --version)"
[[ "$(openclaw_extract_version "$installed_version_output")" == "$PERSONAL_EDGE_OPENCLAW_VERSION" ]] ||
    openclaw_fail "expected OpenClaw $PERSONAL_EDGE_OPENCLAW_VERSION"

openclaw_assert_private_file "$openclaw_config_path" "OpenClaw config"
[[ ! -e "$openclaw_state_dir/.env" && ! -L "$openclaw_state_dir/.env" ]] ||
    openclaw_fail "legacy profile .env must be migrated to the secret store"
openclaw_assert_restrictive_config "$openclaw_config_path"

receipt_temp="$(mktemp -d "$openclaw_tmp_root/personal-edge-openclaw-verify.XXXXXX")"
verification_complete=0
cleanup_receipts() {
    local inherited_status="$?"
    local exit_status="${1:-$inherited_status}"
    case "$receipt_temp" in
        "$openclaw_tmp_root"/personal-edge-openclaw-verify.*|/tmp/personal-edge-openclaw-verify.*|/private/tmp/personal-edge-openclaw-verify.*|*/T/personal-edge-openclaw-verify.*)
            if (( exit_status != 0 && verification_complete == 0 )); then
                echo "WARN private verification diagnostics retained: $receipt_temp" >&2
            else
                rm -rf -- "$receipt_temp"
            fi
            ;;
        *) echo "WARN refusing to remove unexpected verification path: $receipt_temp" >&2 ;;
    esac
}
trap cleanup_receipts EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
chmod 700 "$receipt_temp"
if (( observe_only == 0 )); then
    if ! openclaw_run config validate \
        > "$receipt_temp/config-validate.out" 2> "$receipt_temp/config-validate.err"; then
        openclaw_fail "config validation failed; private diagnostics: $receipt_temp/config-validate.err"
    fi
fi

[[ -f "$openclaw_workspace_dir/AGENTS.md" && ! -L "$openclaw_workspace_dir/AGENTS.md" ]] ||
    openclaw_fail "restrictive workspace AGENTS.md is missing"
cmp -s "$script_dir/templates/AGENTS.md" "$openclaw_workspace_dir/AGENTS.md" ||
    openclaw_fail "workspace AGENTS.md drifted from the reviewed policy"
unexpected_workspace="$(find "$openclaw_workspace_dir" -mindepth 1 -maxdepth 1 ! -name AGENTS.md -print -quit)"
[[ -z "$unexpected_workspace" ]] ||
    openclaw_fail "restricted workspace contains an unexpected entry: $unexpected_workspace"

if (( observe_only == 0 )); then
    if ! openclaw_run secrets audit --check --json \
        > "$receipt_temp/secrets.json" 2> "$receipt_temp/secrets.err"; then
        openclaw_fail "secrets audit command failed; private diagnostics: $receipt_temp/secrets.err"
    fi
    openclaw_assert_clean_secrets_receipt "$receipt_temp/secrets.json"
    if ! openclaw_run plugins list --json \
        > "$receipt_temp/plugins.json" 2> "$receipt_temp/plugins.err"; then
        openclaw_fail "plugin inventory command failed; private diagnostics: $receipt_temp/plugins.err"
    fi
    openclaw_assert_required_plugins_receipt "$receipt_temp/plugins.json"
    if ! openclaw_run models status --check --json \
        > "$receipt_temp/models.json" 2> "$receipt_temp/models.err"; then
        openclaw_fail "model status command failed; private diagnostics: $receipt_temp/models.err"
    fi
fi

openclaw_assert_owned_nonwritable_file "$openclaw_gateway_plist" "Gateway LaunchAgent"
openclaw_assert_owned_nonwritable_file "$openclaw_watchdog_plist" "watchdog LaunchAgent"
plutil -lint "$openclaw_gateway_plist" >/dev/null
plutil -lint "$openclaw_watchdog_plist" >/dev/null
gateway_plist_json="$(plutil -convert json -o - "$openclaw_gateway_plist")"
openclaw_assert_gateway_plist "$gateway_plist_json"
if [[ "$install_method" == "adopted-homebrew-node" ]]; then
    openclaw_assert_adopted_service_definition "$openclaw_node_path"
else
    openclaw_assert_fresh_service_definition "$openclaw_node_path"
fi

watchdog_plist_json="$(plutil -convert json -o - "$openclaw_watchdog_plist")"
openclaw_assert_watchdog_plist "$watchdog_plist_json"

if (( skip_live == 0 )); then
    launchctl print "gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL" >/dev/null ||
        openclaw_fail "Gateway LaunchAgent is not loaded"
    launchctl print "gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_WATCHDOG_LABEL" >/dev/null ||
        openclaw_fail "watchdog LaunchAgent is not loaded"
    if ! openclaw_run gateway status --require-rpc --json \
        > "$receipt_temp/gateway-status.json" 2> "$receipt_temp/gateway-status.err"; then
        openclaw_fail "Gateway RPC status failed; private diagnostics: $receipt_temp/gateway-status.err"
    fi
    if ! openclaw_run gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT" \
        > "$receipt_temp/gateway-health.out" 2> "$receipt_temp/gateway-health.err"; then
        openclaw_fail "Gateway health command failed; private diagnostics: $receipt_temp/gateway-health.err"
    fi
    if ! openclaw_run health --json \
        > "$receipt_temp/health.json" 2> "$receipt_temp/health.err"; then
        openclaw_fail "live health command failed; private diagnostics: $receipt_temp/health.err"
    fi
    openclaw_assert_live_plugins_receipt "$receipt_temp/health.json"

    if (( observe_only == 0 )); then
    # Prove the running post-policy inventory, rather than inferring zero tools from the authored
    # wildcard deny. The message-free, incognito, read-only session is deleted before verification
    # can succeed. Adoption runs this gate against its unpublished manifest.
    zero_stamp="$(date -u +%Y%m%dt%H%M%Sz)"
    zero_session_requested="agent:main:dashboard:incognito-personal-edge-verify-zero-tools-$zero_stamp-$$"
    zero_session_may_exist=0
    cleanup_zero_tool_session() {
        local exit_status="$?"
        local cleanup_params
        trap - EXIT INT TERM
        if (( zero_session_may_exist == 1 )); then
            cleanup_params="$(jq -cn --arg key "$zero_session_requested" \
                '{key:$key,agentId:"main",deleteTranscript:true,emitLifecycleHooks:false}')"
            if ! openclaw_run gateway call sessions.delete --json --params "$cleanup_params" \
                > "$receipt_temp/zero-tools-delete.json" \
                2> "$receipt_temp/zero-tools-delete.err"; then
                echo "WARN zero-tool verification session needs manual cleanup: $zero_session_requested" >&2
            elif ! jq -e '(.ok // .result.ok) == true' \
                "$receipt_temp/zero-tools-delete.json" >/dev/null; then
                echo "WARN zero-tool verification session cleanup was not acknowledged: $zero_session_requested" >&2
            fi
        fi
        cleanup_receipts "$exit_status"
        exit "$exit_status"
    }
    trap cleanup_zero_tool_session EXIT

    # The local token-auth CLI intentionally omits device identity in 2026.8.1, so its optional
    # principal-scoped idempotencyKey is unsupported. Dispatch this empty probe once; its unique,
    # canonical incognito key binds cleanup even when the transport outcome is unknown.
    create_params="$(jq -cn --arg key "$zero_session_requested" '
        {
            key:$key, agentId:"main", permissionMode:"read-only",
            incognito:true
        }
    ')"
    # A transport timeout can occur after the server created the requested key. Arm cleanup before
    # sending, and only ever delete that exact caller-owned key. A returned untrusted mismatch may
    # name an owner session and must never become an automatic deletion target.
    zero_session_may_exist=1
    if ! openclaw_run gateway call sessions.create --json --params "$create_params" \
        > "$receipt_temp/zero-tools-create.json" 2> "$receipt_temp/zero-tools-create.err"; then
        openclaw_fail "zero-tool session creation failed; private diagnostics: $receipt_temp/zero-tools-create.err"
    fi
    created_session_key="$(jq -r '.key // .result.key // empty' \
        "$receipt_temp/zero-tools-create.json")"
    if [[ "$created_session_key" != "$zero_session_requested" ]]; then
        echo "WARN unbound returned session key was not auto-deleted; inspect the private verification receipt and owner sessions" >&2
        openclaw_fail "Gateway did not create the bound zero-tool verification session"
    fi

    effective_params="$(jq -cn --arg key "$created_session_key" \
        '{agentId:"main",sessionKey:$key}')"
    if ! openclaw_run gateway call tools.effective --json --params "$effective_params" \
        > "$receipt_temp/zero-tools-effective.json" 2> "$receipt_temp/zero-tools-effective.err"; then
        openclaw_fail "tools.effective call failed; private diagnostics: $receipt_temp/zero-tools-effective.err"
    fi
    jq -e '
        (.agentId // .result.agentId) == "main" and
        (((.groups // .result.groups) | type) == "array") and
        ([((.groups // .result.groups)[]?.tools[]?)] | length) == 0
    ' "$receipt_temp/zero-tools-effective.json" >/dev/null ||
        openclaw_fail "running Gateway exposes one or more effective tools"

    delete_params="$(jq -cn --arg key "$created_session_key" \
        '{key:$key,agentId:"main",deleteTranscript:true,emitLifecycleHooks:false}')"
    if ! openclaw_run gateway call sessions.delete --json --params "$delete_params" \
        > "$receipt_temp/zero-tools-delete.json" 2> "$receipt_temp/zero-tools-delete.err"; then
        openclaw_fail "zero-tool session deletion failed; private diagnostics: $receipt_temp/zero-tools-delete.err"
    fi
    jq -e '(.ok // .result.ok) == true' "$receipt_temp/zero-tools-delete.json" >/dev/null ||
        openclaw_fail "Gateway did not acknowledge zero-tool verification session cleanup"
    zero_session_may_exist=0
    trap cleanup_receipts EXIT
    fi

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
    openclaw_check_managed_tailscale_serve "$receipt_temp"
fi

verification_complete=1
echo "OK OpenClaw $PERSONAL_EDGE_OPENCLAW_VERSION / Node 26 is restrictive and healthy."
if (( skip_live == 0 && observe_only == 1 )); then
    echo "OK observation-only static SecretRef/plugin policy plus live health/listener checks passed."
    echo "NOTE secret-store audit/model auth were not re-run because those commands may permission-harden files."
    echo "NOTE transient session probe was omitted."
else
    echo "OK secrets audit plaintext=0 unresolved=0; bundled OpenRouter/device-pair inventory verified."
fi
if (( skip_live == 0 && observe_only == 0 )); then
    echo "OK running Gateway reports tools.effective=0 for a cleaned incognito session."
fi
