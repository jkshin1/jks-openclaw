#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

usage() {
    cat <<'EOF'
Usage: configure-openrouter.sh --key-file FILE --apply

Writes one key from a private mode-600 file directly to OpenClaw's SQLite secret store. The key
is never read into a shell variable, printed, placed in config/.env, or passed on the command line.
This command requires the config to use the reviewed OPENROUTER_API_KEY store SecretRef.
EOF
}

key_file=""
apply=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --key-file)
            [[ $# -ge 2 ]] || openclaw_fail "--key-file requires a value"
            key_file="$2"
            shift 2
            ;;
        --apply) apply=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) openclaw_fail "unknown argument: $1" ;;
    esac
done

[[ -n "$key_file" ]] || openclaw_fail "--key-file is required"
(( apply == 1 )) || openclaw_fail "refusing to update credentials without --apply"
openclaw_assert_private_secret_file "$key_file" "OpenRouter API key file"
openclaw_load_deployment
openclaw_assert_restrictive_config "$openclaw_config_path"
jq -e '.models.providers.openrouter.apiKey == {
    source: "store", provider: "default", id: "OPENROUTER_API_KEY"
}' "$openclaw_config_path" >/dev/null ||
    openclaw_fail "OpenRouter config does not use the reviewed store SecretRef"

openclaw_run secrets store set OPENROUTER_API_KEY --kind secret --value-file "$key_file" >/dev/null
openclaw_run secrets reload >/dev/null
receipt_dir="$openclaw_state_dir/operations"
openclaw_prepare_private_dir "$receipt_dir" "OpenClaw operations directory"
receipt_path="$receipt_dir/secrets-audit-$(date -u +%Y%m%dT%H%M%SZ).json"
if ! openclaw_run secrets audit --check --json > "$receipt_path" 2> "$receipt_path.err"; then
    chmod 600 "$receipt_path" "$receipt_path.err"
    openclaw_assert_clean_secrets_receipt "$receipt_path"
    openclaw_fail "secrets audit failed without exposing its private receipt"
fi
chmod 600 "$receipt_path"
openclaw_assert_clean_secrets_receipt "$receipt_path"
openclaw_run gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT" >/dev/null
echo "OK OpenRouter key stored behind a SecretRef; plaintext=0 and unresolved=0."
echo "OK No model call was made. Private audit receipt: $receipt_path"
