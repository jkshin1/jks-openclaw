#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

usage() {
    cat <<'EOF'
Usage: rollback-gateway.sh --apply [--restart]

Emergency exposure rollback:
  1. stops and disables the Gateway,
  2. attempts a verified forensic backup,
  3. rotates the Gateway token in the SQLite secret store,
  4. replaces config with the reviewed loopback/tool-free baseline,
  5. quarantines unexpected bootstrap/memory files instead of deleting them.

The Gateway remains stopped unless --restart is explicitly supplied.
EOF
}

apply=0
restart=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply) apply=1; shift ;;
        --restart) restart=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) openclaw_fail "unknown argument: $1" ;;
    esac
done
(( apply == 1 )) || openclaw_fail "refusing emergency rollback without --apply"

openclaw_load_deployment
umask 077
openclaw_run gateway stop --disable || openclaw_note "Gateway was already stopped or unavailable"

forensic_dir="$openclaw_backup_root/pre-lockdown"
openclaw_prepare_private_dir "$forensic_dir" "pre-lockdown backup directory"
if ! openclaw_run backup create --output "$forensic_dir" --verify; then
    echo "WARN pre-lockdown backup failed; Gateway remains stopped and lockdown continues" >&2
fi

quarantine_dir="$openclaw_state_dir/quarantine/$(date -u +%Y%m%dT%H%M%SZ)"
quarantined=0
while IFS= read -r -d '' forbidden_path; do
    if (( quarantined == 0 )); then
        openclaw_prepare_private_dir "$quarantine_dir" "workspace quarantine directory"
    fi
    mv -- "$forbidden_path" "$quarantine_dir/$(basename "$forbidden_path")"
    quarantined=1
done < <(find "$openclaw_workspace_dir" -mindepth 1 -maxdepth 1 ! -name AGENTS.md -print0)
if (( quarantined == 1 )); then
    echo "INFO unexpected workspace state moved to private quarantine: $quarantine_dir"
fi

cp "$script_dir/templates/AGENTS.md" "$openclaw_workspace_dir/AGENTS.md"
chmod 600 "$openclaw_workspace_dir/AGENTS.md"
config_temp="$(mktemp "$openclaw_state_dir/.lockdown-config.partial.XXXXXX")"
jq --arg workspace "$openclaw_workspace_dir" \
    '.agents.defaults.workspace = $workspace' \
    "$script_dir/templates/openclaw.json" > "$config_temp"
chmod 600 "$config_temp"
mv -f "$config_temp" "$openclaw_config_path"

token_temp="$(mktemp "$openclaw_tmp_root/personal-edge-gateway-rollback-token.XXXXXX")"
cleanup_token_temp() {
    case "$token_temp" in
        /tmp/personal-edge-gateway-rollback-token.*|/private/tmp/personal-edge-gateway-rollback-token.*|*/T/personal-edge-gateway-rollback-token.*)
            rm -f -- "$token_temp"
            ;;
    esac
}
trap cleanup_token_temp EXIT INT TERM
chmod 600 "$token_temp"
openssl rand -hex 32 > "$token_temp"
openclaw_run secrets store set OPENCLAW_GATEWAY_TOKEN --kind secret --value-file "$token_temp" >/dev/null
cleanup_token_temp
trap - EXIT INT TERM
openclaw_run config validate >/dev/null
audit_temp="$(mktemp "$openclaw_tmp_root/personal-edge-rollback-secrets.XXXXXX")"
chmod 600 "$audit_temp"
openclaw_run secrets audit --check --json > "$audit_temp" 2> "$audit_temp.err"
chmod 600 "$audit_temp.err"
openclaw_assert_clean_secrets_receipt "$audit_temp"
rm -f -- "$audit_temp" "$audit_temp.err"

if (( restart == 1 )); then
    openclaw_run gateway install --runtime node --wrapper "$openclaw_wrapper_path" --force
    openclaw_run gateway start
    # --restart is the operator's explicit request to bring the lockdown back online; like a fresh
    # install it takes full live verification, including the one transient incognito session.
    "$script_dir/verify-gateway.sh" --acknowledge-transient-session-write
    "$script_dir/security-audit.sh" --deep
    echo "OK Gateway restarted in reviewed loopback/tool-free lockdown."
else
    echo "OK Gateway is stopped and its config is in reviewed loopback/tool-free lockdown."
    echo "NOTE Run this script again with --apply --restart only after reviewing the backup and audit."
fi
