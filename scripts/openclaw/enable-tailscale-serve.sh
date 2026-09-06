#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

usage() {
    cat <<'EOF'
Usage: enable-tailscale-serve.sh --apply

Enables OpenClaw-managed, tailnet-only Tailscale Serve while preserving loopback binding and token
authentication. It refuses Funnel and never opens a LAN/public listener.
EOF
}

apply=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply) apply=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) openclaw_fail "unknown argument: $1" ;;
    esac
done
(( apply == 1 )) || openclaw_fail "refusing to change network exposure without --apply"

openclaw_load_deployment
for required_command in awk chmod cmp cp date env find id jq launchctl lsof mktemp mv stat; do
    openclaw_require_command "$required_command"
done
openclaw_assert_private_file "$openclaw_config_path" "OpenClaw config"
openclaw_assert_restrictive_config "$openclaw_config_path"
tailscale_path=""
if [[ -n "${PERSONAL_EDGE_OPENCLAW_FIXTURE_TAILSCALE_BIN:-}" ]]; then
    [[ "$(cd "$openclaw_user_root" && pwd -P)" != "$(cd "$HOME" && pwd -P)" ]] ||
        openclaw_fail "fixture CLI is forbidden for the owner root"
    tailscale_path="$PERSONAL_EDGE_OPENCLAW_FIXTURE_TAILSCALE_BIN"
    openclaw_assert_safe_path "$tailscale_path" "fixture Tailscale CLI"
    openclaw_assert_owned_nonwritable_file "$tailscale_path" "fixture Tailscale CLI"
elif [[ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ]]; then
    tailscale_path="/Applications/Tailscale.app/Contents/MacOS/Tailscale"
else
    tailscale_path="$(command -v tailscale || true)"
fi
[[ -x "$tailscale_path" ]] || openclaw_fail "Tailscale CLI is not installed"

transaction_temp="$(mktemp -d "$openclaw_tmp_root/personal-edge-tailscale-enable.XXXXXX")"
chmod 700 "$transaction_temp"
cp "$openclaw_config_path" "$transaction_temp/original-openclaw.json"
chmod 600 "$transaction_temp/original-openclaw.json"
transaction_committed=0
config_may_have_changed=0
tailscale_serve_prestate_captured=0
tailscale_serve_may_have_changed=0
rollback_verified=0
cleanup_tailscale_transaction() {
    local exit_status="$?"
    local rollback_temp gateway_target config_restore_ok serve_restore_ok
    trap - EXIT INT TERM
    if (( transaction_committed == 0 &&
          (config_may_have_changed == 1 || tailscale_serve_may_have_changed == 1) )); then
        gateway_target="gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL"
        rollback_temp=""
        config_restore_ok=1
        serve_restore_ok=1
        if (( config_may_have_changed == 1 )); then
            config_restore_ok=0
            if rollback_temp="$(mktemp "$(dirname "$openclaw_config_path")/.openclaw.tailscale-rollback.XXXXXX")" &&
               cp "$transaction_temp/original-openclaw.json" "$rollback_temp" &&
               chmod 600 "$rollback_temp" &&
               mv -f "$rollback_temp" "$openclaw_config_path" &&
               openclaw_run gateway restart --preserve-definition >/dev/null 2>&1 &&
               openclaw_run gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT" >/dev/null 2>&1 &&
               cmp -s "$transaction_temp/original-openclaw.json" "$openclaw_config_path"; then
                config_restore_ok=1
            fi
        fi
        if (( tailscale_serve_may_have_changed == 1 )); then
            serve_restore_ok=0
            if (( tailscale_serve_prestate_captured == 1 )) &&
               env TAILSCALE_BE_CLI=1 "$tailscale_path" serve set-raw \
                   < "$transaction_temp/tailscale-serve-before-status.json" \
                   > "$transaction_temp/tailscale-serve-restore.out" \
                   2> "$transaction_temp/tailscale-serve-restore.err" &&
               env TAILSCALE_BE_CLI=1 "$tailscale_path" serve status --json \
                   > "$transaction_temp/tailscale-serve-restored-status.json" \
                   2> "$transaction_temp/tailscale-serve-restored-status.err" &&
               chmod 600 "$transaction_temp/tailscale-serve-restored-status.json" &&
               jq -S -c . "$transaction_temp/tailscale-serve-before-status.json" \
                   > "$transaction_temp/tailscale-serve-before-status.canonical.json" &&
               jq -S -c . "$transaction_temp/tailscale-serve-restored-status.json" \
                   > "$transaction_temp/tailscale-serve-restored-status.canonical.json" &&
               cmp -s "$transaction_temp/tailscale-serve-before-status.canonical.json" \
                   "$transaction_temp/tailscale-serve-restored-status.canonical.json"; then
                serve_restore_ok=1
            fi
        fi
        if (( config_restore_ok == 1 && serve_restore_ok == 1 )); then
            rollback_verified=1
            echo "WARN Tailscale Serve enablement failed; original config, healthy Gateway generation, and owner Serve state were restored" >&2
        else
            openclaw_run gateway stop --disable >/dev/null 2>&1 || true
            launchctl disable "$gateway_target" >/dev/null 2>&1 || true
            launchctl bootout "$gateway_target" >/dev/null 2>&1 || true
            echo "WARN Tailscale rollback could not prove the original Gateway and Serve state; Gateway was stopped best-effort" >&2
        fi
    fi
    case "$transaction_temp" in
        /tmp/personal-edge-tailscale-enable.*|/private/tmp/personal-edge-tailscale-enable.*|*/T/personal-edge-tailscale-enable.*)
            if (( transaction_committed == 1 || rollback_verified == 1 || config_may_have_changed == 0 )); then
                rm -rf -- "$transaction_temp"
            else
                echo "WARN private Tailscale recovery snapshot retained: $transaction_temp" >&2
            fi
            ;;
        *) echo "WARN refusing to remove unexpected Tailscale transaction path: $transaction_temp" >&2 ;;
    esac
    exit "$exit_status"
}
trap cleanup_tailscale_transaction EXIT INT TERM

env TAILSCALE_BE_CLI=1 "$tailscale_path" status --json \
    > "$transaction_temp/tailscale-status-before.json" \
    2> "$transaction_temp/tailscale-status-before.err" ||
    openclaw_fail "Tailscale is not logged in or connected; private diagnostics: $transaction_temp/tailscale-status-before.err"
chmod 600 "$transaction_temp/tailscale-status-before.json"
jq -e '.BackendState == "Running" and .Self.Online == true' \
    "$transaction_temp/tailscale-status-before.json" >/dev/null ||
    openclaw_fail "Tailscale backend is not running and online"
# get-config/set-config cover named Services only and omit node-level HTTPS routes.
# Tailscale 1.102.3 supports exact raw status restoration via serve set-raw on stdin.
# Capture the raw node + Services state before any write, then verify byte-equivalent JSON.
env TAILSCALE_BE_CLI=1 "$tailscale_path" serve status --json \
    > "$transaction_temp/tailscale-serve-before-status.json" \
    2> "$transaction_temp/tailscale-serve-before-status.err" ||
    openclaw_fail "Tailscale Serve prestate status could not be captured; private diagnostics: $transaction_temp/tailscale-serve-before-status.err"
chmod 600 "$transaction_temp/tailscale-serve-before-status.json"
jq -e 'type == "object"' "$transaction_temp/tailscale-serve-before-status.json" >/dev/null ||
    openclaw_fail "Tailscale Serve prestate status is not a JSON object"
tailscale_serve_prestate_captured=1

backup_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
tailscale_backup_dir="$openclaw_backup_root/tailscale-enable-$backup_stamp"
[[ ! -e "$tailscale_backup_dir" && ! -L "$tailscale_backup_dir" ]] ||
    openclaw_fail "Tailscale enablement backup path already exists"
openclaw_prepare_private_dir "$openclaw_backup_root" "OpenClaw backup root"
openclaw_prepare_private_dir "$tailscale_backup_dir" "Tailscale enablement backup"
config_may_have_changed=1
openclaw_run backup create --output "$tailscale_backup_dir" --verify --json \
    > "$transaction_temp/backup.json" 2> "$transaction_temp/backup.err"
[[ -n "$(find "$tailscale_backup_dir" -type f -print -quit)" ]] ||
    openclaw_fail "verified Tailscale enablement backup did not create an archive"
cmp -s "$transaction_temp/original-openclaw.json" "$openclaw_config_path" ||
    openclaw_fail "backup preflight changed config bytes"

tailscale_serve_may_have_changed=1
openclaw_run config set gateway.bind loopback
openclaw_run config set gateway.auth.mode token
openclaw_run config set gateway.tailscale.mode serve
openclaw_run config validate >/dev/null
openclaw_assert_restrictive_config "$openclaw_config_path"
openclaw_run secrets audit --check --json \
    > "$transaction_temp/secrets.json" 2> "$transaction_temp/secrets.err"
openclaw_assert_clean_secrets_receipt "$transaction_temp/secrets.json"

openclaw_run gateway restart --preserve-definition >/dev/null
openclaw_run gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT" >/dev/null
openclaw_run health --json > "$transaction_temp/health.json" 2> "$transaction_temp/health.err"
openclaw_assert_live_plugins_receipt "$transaction_temp/health.json"
openclaw_run security audit --deep > "$transaction_temp/security-audit.txt" \
    2> "$transaction_temp/security-audit.err"

listener_count=0
while IFS= read -r listener_name; do
    [[ -n "$listener_name" ]] || continue
    listener_count=$((listener_count + 1))
    case "$listener_name" in
        "n127.0.0.1:$PERSONAL_EDGE_OPENCLAW_PORT"|"n[::1]:$PERSONAL_EDGE_OPENCLAW_PORT") ;;
        *) openclaw_fail "Gateway has a non-loopback listener after Tailscale enablement" ;;
    esac
done < <(lsof -nP -iTCP:"$PERSONAL_EDGE_OPENCLAW_PORT" -sTCP:LISTEN -F n 2>/dev/null |
    awk '/^n/ { print }')
(( listener_count > 0 )) || openclaw_fail "Gateway has no live loopback listener"

env TAILSCALE_BE_CLI=1 "$tailscale_path" serve status --json \
    > "$transaction_temp/tailscale-serve.json" \
    2> "$transaction_temp/tailscale-serve.err" ||
    openclaw_fail "Tailscale Serve status failed; private diagnostics: $transaction_temp/tailscale-serve.err"
chmod 600 "$transaction_temp/tailscale-serve.json"
# Keep the bounded routing receipt in the already-private verified backup, including on failure.
# It contains listener/route metadata only, never Gateway authentication or request content.
cp "$transaction_temp/tailscale-serve.json" "$tailscale_backup_dir/serve-observed.json"
chmod 600 "$tailscale_backup_dir/serve-observed.json"
launchctl print "gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL" |
    awk '/^[[:space:]]*pid = [0-9]+$/ {print $3; exit}' > "$tailscale_backup_dir/gateway-pid.txt"
gateway_observed_pid="$(cat "$tailscale_backup_dir/gateway-pid.txt")"
[[ "$gateway_observed_pid" =~ ^[1-9][0-9]*$ ]] || openclaw_fail "Gateway PID was unavailable"
lsof -a -p "$gateway_observed_pid" -nP -iTCP -sTCP:LISTEN -F n |
    awk '/^n/ {print}' > "$tailscale_backup_dir/gateway-listeners.txt"
chmod 600 "$tailscale_backup_dir/gateway-pid.txt" "$tailscale_backup_dir/gateway-listeners.txt"
echo "Private Serve observation: $tailscale_backup_dir"
openclaw_assert_managed_tailscale_serve_receipt "$transaction_temp/tailscale-serve.json"

transaction_committed=1
trap cleanup_tailscale_transaction EXIT INT TERM
echo "OK tailnet-only Tailscale Serve is active; Gateway remains loopback/token protected."
echo "OK verified pre-enable backup is private under $tailscale_backup_dir"
