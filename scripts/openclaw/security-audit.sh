#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

usage() {
    cat <<'EOF'
Usage: security-audit.sh [--deep]

Runs config, secrets, model-auth, plugin, doctor-lint, and security audit checks. OpenClaw audit
commands may permission-harden owner files, and this script writes private JSON receipts under the
state directory; only non-secret summaries are printed.
EOF
}

deep=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --deep) deep=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) openclaw_fail "unknown argument: $1" ;;
    esac
done

openclaw_load_deployment
openclaw_prepare_private_dir "$openclaw_state_dir/operations" "OpenClaw operations directory"
[[ ! -e "$openclaw_state_dir/.env" && ! -L "$openclaw_state_dir/.env" ]] ||
    openclaw_fail "legacy profile .env must be migrated to the secret store"
openclaw_run config validate >/dev/null
openclaw_assert_restrictive_config "$openclaw_config_path"
audit_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
secrets_path="$openclaw_state_dir/operations/secrets-audit-$audit_stamp.json"
plugins_path="$openclaw_state_dir/operations/plugins-audit-$audit_stamp.json"
models_path="$openclaw_state_dir/operations/models-audit-$audit_stamp.json"
doctor_path="$openclaw_state_dir/operations/doctor-lint-$audit_stamp.json"
health_path="$openclaw_state_dir/operations/live-health-$audit_stamp.json"
openclaw_run secrets audit --check --json > "$secrets_path" 2> "$secrets_path.err"
chmod 600 "$secrets_path" "$secrets_path.err"
openclaw_assert_clean_secrets_receipt "$secrets_path"
openclaw_run plugins list --json > "$plugins_path" 2> "$plugins_path.err"
chmod 600 "$plugins_path" "$plugins_path.err"
openclaw_assert_required_plugins_receipt "$plugins_path"
openclaw_run models status --check --json > "$models_path" 2> "$models_path.err"
chmod 600 "$models_path" "$models_path.err"
doctor_status=0
openclaw_run doctor --lint --all --json > "$doctor_path" 2> "$doctor_path.err" || doctor_status=$?
chmod 600 "$doctor_path" "$doctor_path.err"
# Pinned OpenClaw 2026.8.1 exits 1 for warnings as well as errors at the default warning
# threshold. Retain those findings and continue the independent security audit only when the
# single structured result agrees with that exit status; CLI errors never become warning passes.
[[ "$doctor_status" == "0" || "$doctor_status" == "1" ]] ||
    openclaw_fail "doctor lint command failed (exit=$doctor_status); private receipt: $doctor_path"
jq -se --argjson status "$doctor_status" '
    length == 1 and (.[0] |
        type == "object" and
        (.ok | type == "boolean") and
        (.checksRun | type == "number" and floor == . and . > 0) and
        .checksSkipped == 0 and
        (.findings | type == "array") and
        all(.findings[];
            type == "object" and
            (.checkId | type == "string" and length > 0) and
            (.message | type == "string" and length > 0) and
            (.severity == "warning" or .severity == "error")) and
        .ok == ($status == 0) and
        ($status == (if (.findings | length) > 0 then 1 else 0 end)))
' "$doctor_path" >/dev/null 2>&1 ||
    openclaw_fail "doctor lint returned an unexpected JSON shape or exit status; private receipt: $doctor_path"
doctor_counts="$(jq -c '{
    checksRun, checksSkipped,
    warningCount:([.findings[] | select(.severity == "warning")] | length),
    errorCount:([.findings[] | select(.severity == "error")] | length),
    authProfileWarningCount:([.findings[] | select(
        .severity == "warning" and .checkId == "core/doctor/auth-profiles")] | length)
}' "$doctor_path")"
echo "INFO doctor lint summary: $doctor_counts"
[[ "$(jq -r '.errorCount' <<< "$doctor_counts")" == "0" ]] ||
    openclaw_fail "doctor lint has error findings; private receipt: $doctor_path"
if (( doctor_status == 1 )); then
    echo "WARN doctor lint warnings remain unresolved; continuing independent security audit. Private receipt: $doctor_path"
fi
openclaw_run health --json > "$health_path" 2> "$health_path.err"
chmod 600 "$health_path" "$health_path.err"
openclaw_assert_live_plugins_receipt "$health_path"

audit_path="$openclaw_state_dir/operations/security-audit-$audit_stamp.json"
audit_args=(security audit --json)
if (( deep == 1 )); then
    audit_args=(security audit --deep --json)
fi
openclaw_run "${audit_args[@]}" > "$audit_path"
chmod 600 "$audit_path"
jq -e 'type == "object" and (.findings | type == "array")' "$audit_path" >/dev/null ||
    openclaw_fail "security audit returned an unexpected JSON shape"
critical_count="$(jq '[.findings[] | select(.severity == "critical")] | length' "$audit_path")"
jq '.summary' "$audit_path"
[[ "$critical_count" == "0" ]] || openclaw_fail "security audit found $critical_count critical finding(s)"
echo "OK security audit has no critical findings. Private receipt: $audit_path"
echo "OK secrets audit plaintext=0 unresolved=0; models status --check and bundled plugin checks passed."
if [[ "$(jq -r '.authProfileWarningCount' <<< "$doctor_counts")" != "0" ]]; then
    echo "WARN doctor auth-profile warning remains; model status is not provider-inference acceptance."
fi
