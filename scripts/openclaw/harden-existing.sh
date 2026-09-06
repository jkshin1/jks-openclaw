#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

usage() {
    cat <<'EOF'
Usage: harden-existing.sh --dry-run | --apply

Builds an exact restrictive config for the existing `personaledge` profile while retaining its
structured Gateway/OpenRouter SecretRefs and SQLite auth profiles. Dry-run invokes OpenClaw's
validated config patch planner and proves authored bytes/logical credential rows did not change.
Apply creates a verified backup before its config write, uses the official atomic config patch
path, restarts and verifies the Gateway, and restores the original config bytes on any failure.
EOF
}

dry_run=0
apply=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) dry_run=1; shift ;;
        --apply) apply=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) openclaw_fail "unknown argument: $1" ;;
    esac
done
(( dry_run + apply == 1 )) || openclaw_fail "choose exactly one of --dry-run or --apply"
[[ "$(uname -s)" == "Darwin" ]] || openclaw_fail "hardening is macOS-only"
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
shared_auth_db="$openclaw_state_dir/state/openclaw.sqlite"
agent_auth_db="$openclaw_state_dir/agents/main/agent/openclaw-agent.sqlite"

work_temp="$(mktemp -d "$openclaw_tmp_root/personal-edge-openclaw-harden.XXXXXX")"
cli_invoked=0
preflight_verified=0
cleanup_temp() {
    local exit_status="$?"
    case "$work_temp" in
        /tmp/personal-edge-openclaw-harden.*|/private/tmp/personal-edge-openclaw-harden.*|*/T/personal-edge-openclaw-harden.*)
            if (( exit_status != 0 && cli_invoked == 1 && preflight_verified == 0 )); then
                echo "WARN pre-CLI recovery snapshot retained after failed verification: $work_temp" >&2
            else
                rm -rf -- "$work_temp"
            fi
            ;;
        *) echo "WARN refusing to remove unexpected hardening temp path: $work_temp" >&2 ;;
    esac
}
trap cleanup_temp EXIT INT TERM
chmod 700 "$work_temp"

[[ "$openclaw_config_path" == "$openclaw_state_dir/openclaw.json" ]] ||
    openclaw_fail "hardening requires the profile config at state/openclaw.json"
[[ "$openclaw_workspace_dir" == "$openclaw_state_dir/workspace" ]] ||
    openclaw_fail "hardening requires the profile workspace at state/workspace"
openclaw_assert_safe_path "$openclaw_state_dir" "OpenClaw profile state"
openclaw_assert_owned_nonwritable_dir "$openclaw_state_dir" "OpenClaw profile state"
openclaw_assert_private_file "$openclaw_config_path" "OpenClaw profile config"
openclaw_assert_private_file "$service_wrapper" "Gateway service environment wrapper"
openclaw_assert_private_file "$service_environment" "Gateway service environment"
[[ ! -e "$openclaw_state_dir/.env" && ! -L "$openclaw_state_dir/.env" ]] ||
    openclaw_fail "legacy profile .env must be migrated to SecretRefs before hardening"

# The only authored credential fields retained by the baseline must already be structured refs.
# Auth profiles and OAuth values remain in SQLite and are fingerprinted below, never read out.
jq -e '
    .gateway.auth.mode == "token" and
    .gateway.auth.token == {
        source: "store", provider: "default", id: "OPENCLAW_GATEWAY_TOKEN"
    } and
    (
        (.models.providers.openrouter | has("apiKey") | not) or
        .models.providers.openrouter.apiKey == {
            source: "store", provider: "default", id: "OPENROUTER_API_KEY"
        }
    )
' "$openclaw_config_path" >/dev/null ||
    openclaw_fail "current Gateway/OpenRouter credentials are not approved structured SecretRefs"

openclaw_snapshot_security_state \
    "$work_temp/security-before" "$work_temp" "$service_wrapper" "$service_environment"
cp "$openclaw_config_path" "$work_temp/original-openclaw.json"
chmod 600 "$work_temp/original-openclaw.json"
jq '{
    gatewayToken: .gateway.auth.token,
    openrouterApiKey: (.models.providers.openrouter.apiKey // null)
}' "$openclaw_config_path" > "$work_temp/original-secret-refs.json"
chmod 600 "$work_temp/original-secret-refs.json"

# Take consistent, recoverable online snapshots before the first OpenClaw CLI invocation. These
# are temporary on normal success. If a supposedly read-only CLI preflight changes protected
# state or fails before the after-snapshot is verified, the mode-700 directory is retained.
recovery_dir="$work_temp/pre-cli-recovery"
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

# Validate every reachable runtime path before executing the installed Node/OpenClaw binary.
cli_invoked=1
openclaw_assert_existing_runtime
openclaw_assert_adopted_service_definition "$node_path"
launchctl print "$gateway_target" >/dev/null || openclaw_fail "Gateway LaunchAgent is not loaded"
openclaw_existing_cli config validate >/dev/null
openclaw_existing_cli gateway status --require-rpc --json > "$work_temp/gateway-status-before.json"
openclaw_existing_cli gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT" >/dev/null
openclaw_existing_cli secrets audit --check --json \
    > "$work_temp/secrets-before.json" 2> "$work_temp/secrets-before.err"
openclaw_assert_clean_secrets_receipt "$work_temp/secrets-before.json"
openclaw_existing_cli plugins list --json \
    > "$work_temp/plugins-before.json" 2> "$work_temp/plugins-before.err"
openclaw_assert_required_plugins_receipt "$work_temp/plugins-before.json"
openclaw_existing_cli models status --check --json \
    > "$work_temp/models-before.json" 2> "$work_temp/models-before.err"

candidate_config="$work_temp/openclaw.restrictive.json"
jq --arg workspace "$openclaw_workspace_dir" --slurpfile current "$openclaw_config_path" '
    .agents.defaults.workspace = $workspace |
    .gateway.auth.token = $current[0].gateway.auth.token |
    if (($current[0].models.providers.openrouter // {}) | has("apiKey")) then
        .models.providers.openrouter.apiKey = $current[0].models.providers.openrouter.apiKey
    else
        del(.models.providers.openrouter.apiKey)
    end
' "$script_dir/templates/openclaw.json" > "$candidate_config"
chmod 600 "$candidate_config"
openclaw_assert_restrictive_config "$candidate_config"

# Replace every reviewed top-level section and explicitly delete all pre-existing extra sections.
# `meta` is excluded in both directions: exact 2026.8.1 owns and stamps those fields, and rejects
# any patch operation which attempts to write or delete them. This still prevents an old latent
# channel/tool/hook surface from surviving a recursive merge.
patch_file="$work_temp/restrictive.patch.json"
jq -n --slurpfile current "$openclaw_config_path" --slurpfile target "$candidate_config" '
    ($current[0] | keys - ["meta"]) as $currentKeys |
    ($target[0] | keys - ["meta"]) as $targetKeys |
    reduce (($currentKeys - $targetKeys)[]) as $key
        (($target[0] | del(.meta)); .[$key] = null)
' > "$patch_file"
chmod 600 "$patch_file"
replace_arguments=()
while IFS= read -r top_level_key; do
    [[ "$top_level_key" =~ ^[A-Za-z][A-Za-z0-9]*$ ]] ||
        openclaw_fail "candidate contains an unsafe top-level config key"
    replace_arguments+=(--replace-path "$top_level_key")
done < <(jq -r 'keys[] | select(. != "meta")' "$candidate_config")

if ! openclaw_existing_cli config patch --file "$patch_file" \
    "${replace_arguments[@]}" --dry-run --json \
    > "$work_temp/config-patch-dry-run.json" 2> "$work_temp/config-patch-dry-run.err"; then
    openclaw_fail "OpenClaw config patch dry-run failed; private diagnostics: $work_temp/config-patch-dry-run.err"
fi
openclaw_snapshot_security_state \
    "$work_temp/security-after-dry-run" "$work_temp" "$service_wrapper" "$service_environment"
if ! cmp -s "$work_temp/security-before" "$work_temp/security-after-dry-run"; then
    openclaw_fail "config patch dry-run changed authored files or logical credential rows"
fi
preflight_verified=1

echo "OK hardening plan: exact 2026.8.1 restrictive schema and SecretRefs validated"
echo "OK hardening plan: wildcard tool denial, loopback token auth, model/plugin allowlists"
if (( dry_run == 1 )); then
    echo "DRY-RUN no config/service bytes or credential/auth-profile logical rows changed"
    exit 0
fi

harden_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
harden_backup_dir="$openclaw_backup_root/harden-$harden_stamp"
[[ ! -e "$harden_backup_dir" && ! -L "$harden_backup_dir" ]] ||
    openclaw_fail "hardening backup path already exists: $harden_backup_dir"
openclaw_prepare_private_dir "$openclaw_backup_root" "OpenClaw backup root"
openclaw_prepare_private_dir "$harden_backup_dir" "hardening backup directory"
openclaw_existing_cli backup create --output "$harden_backup_dir" --verify --json \
    > "$work_temp/backup-receipt.json"
chmod 600 "$work_temp/backup-receipt.json"
[[ -n "$(find "$harden_backup_dir" -type f -print -quit)" ]] ||
    openclaw_fail "verified hardening backup did not create an archive"
cp "$work_temp/backup-receipt.json" "$harden_backup_dir/backup-receipt.json"
chmod 600 "$harden_backup_dir/backup-receipt.json"

config_write_attempted=0
harden_committed=0
rollback_config() {
    local exit_status="$?"
    local rollback_temp=""
    trap - EXIT INT TERM
    if (( harden_committed == 0 && config_write_attempted == 1 )); then
        rollback_temp="$(mktemp "$openclaw_state_dir/.openclaw.json.rollback.XXXXXX")"
        cp "$work_temp/original-openclaw.json" "$rollback_temp"
        chmod 600 "$rollback_temp"
        mv -f "$rollback_temp" "$openclaw_config_path"
        openclaw_existing_cli gateway restart --preserve-definition >/dev/null 2>&1 || true
        echo "WARN hardening failed; original config bytes were restored from the private snapshot" >&2
    fi
    cleanup_temp
    exit "$exit_status"
}
trap rollback_config EXIT INT TERM

# Mark the attempt before invoking the writer so an interrupted/partial writer is also rolled back.
config_write_attempted=1
if ! openclaw_existing_cli config patch --file "$patch_file" "${replace_arguments[@]}" \
    > "$work_temp/config-patch-apply.log" 2> "$work_temp/config-patch-apply.err"; then
    openclaw_fail "OpenClaw config patch apply failed; private diagnostics: $work_temp/config-patch-apply.err"
fi
openclaw_assert_private_file "$openclaw_config_path" "hardened OpenClaw config"
openclaw_assert_restrictive_config "$openclaw_config_path"
openclaw_existing_cli config validate >/dev/null

jq -S 'del(.meta)' "$candidate_config" > "$work_temp/candidate-normalized.json"
jq -S 'del(.meta)' "$openclaw_config_path" > "$work_temp/applied-normalized.json"
cmp -s "$work_temp/candidate-normalized.json" "$work_temp/applied-normalized.json" ||
    openclaw_fail "official config patch did not produce the exact reviewed non-meta config"
jq -e --arg version "$PERSONAL_EDGE_OPENCLAW_VERSION" '
    (.meta | type) == "object" and
    .meta.lastTouchedVersion == $version and
    (.meta.migrations | type) == "object" and
    .meta.migrations.modelPolicyAllowlist == true
' "$openclaw_config_path" >/dev/null ||
    openclaw_fail "official config patch did not stamp the expected managed metadata"
jq '{
    gatewayToken: .gateway.auth.token,
    openrouterApiKey: (.models.providers.openrouter.apiKey // null)
}' "$openclaw_config_path" > "$work_temp/applied-secret-refs.json"
cmp -s "$work_temp/original-secret-refs.json" "$work_temp/applied-secret-refs.json" ||
    openclaw_fail "official config patch changed a structured credential reference"

openclaw_existing_cli gateway restart --preserve-definition >/dev/null
openclaw_existing_cli gateway status --require-rpc --json > "$work_temp/gateway-status-after.json"
openclaw_existing_cli gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT" >/dev/null
openclaw_existing_cli secrets audit --check --json \
    > "$work_temp/secrets-after.json" 2> "$work_temp/secrets-after.err"
openclaw_assert_clean_secrets_receipt "$work_temp/secrets-after.json"
openclaw_existing_cli plugins list --json \
    > "$work_temp/plugins-after.json" 2> "$work_temp/plugins-after.err"
openclaw_assert_required_plugins_receipt "$work_temp/plugins-after.json"
openclaw_existing_cli models status --check --json \
    > "$work_temp/models-after.json" 2> "$work_temp/models-after.err"
openclaw_existing_cli health --json > "$work_temp/health-after.json" 2> "$work_temp/health-after.err"
openclaw_assert_live_plugins_receipt "$work_temp/health-after.json"

listener_count=0
while IFS= read -r listener_name; do
    [[ -n "$listener_name" ]] || continue
    listener_count=$((listener_count + 1))
    case "$listener_name" in
        "n127.0.0.1:$PERSONAL_EDGE_OPENCLAW_PORT"|"n[::1]:$PERSONAL_EDGE_OPENCLAW_PORT") ;;
        *) openclaw_fail "Gateway has a non-loopback listener after hardening" ;;
    esac
done < <(lsof -nP -iTCP:"$PERSONAL_EDGE_OPENCLAW_PORT" -sTCP:LISTEN -F n 2>/dev/null | awk '/^n/ { print }')
(( listener_count > 0 )) || openclaw_fail "Gateway has no live loopback listener after hardening"

openclaw_snapshot_security_state \
    "$work_temp/security-after-apply" "$work_temp" "$service_wrapper" "$service_environment"
config_relative_path="${openclaw_config_path#"$openclaw_state_dir/"}"
awk -F '\t' -v path="$config_relative_path" '!($1 == "file" && $4 == path)' \
    "$work_temp/security-before" > "$work_temp/security-before-without-config"
awk -F '\t' -v path="$config_relative_path" '!($1 == "file" && $4 == path)' \
    "$work_temp/security-after-apply" > "$work_temp/security-after-without-config"
cmp -s "$work_temp/security-before-without-config" "$work_temp/security-after-without-config" ||
    openclaw_fail "hardening changed service files or logical SQLite credential rows"

harden_committed=1
trap cleanup_temp EXIT INT TERM
echo "OK existing personaledge config hardened; SQLite auth profiles and SecretRefs were preserved"
echo "OK verified pre-hardening backup is private under $harden_backup_dir"
echo "NEXT run adopt-existing.sh --dry-run before installing management/workspace assets"
