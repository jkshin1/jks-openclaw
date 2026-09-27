#!/usr/bin/env bash
set -euo pipefail
# Preserve the caller diagnostic stream across redirected fixture-function failures.
exec 8>&2
trap 'deployment_failure_status=$?; if [[ $- == *e* ]]; then echo "FAIL deployment suite unexpected exit $deployment_failure_status at line $LINENO" >&8; fi' ERR

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
config_template="$script_dir/templates/openclaw.json"
workspace_policy="$script_dir/templates/AGENTS.md"

fail() {
    echo "FAIL $*" >&2
    exit 1
}

for required_command in bash find grep jq ln perl plutil realpath rg shasum sort sqlite3 wc; do
    command -v "$required_command" >/dev/null 2>&1 || fail "missing command: $required_command"
done
for script in "$script_dir"/*.sh; do
    bash -n "$script" || fail "shell syntax check failed: $script"
done
for entrypoint in \
    install-gateway.sh harden-existing.sh adopt-existing.sh configure-openrouter.sh enable-tailscale-serve.sh \
    verify-gateway.sh host-readiness.sh status-gateway.sh security-audit.sh \
    export-diagnostics.sh smoke-model.sh backup-gateway.sh restore-gateway.sh \
    rollback-gateway.sh watchdog.sh soak-acceptance.sh test-deployment-assets.sh; do
    [[ -x "$script_dir/$entrypoint" ]] || fail "entry point is not executable: $entrypoint"
done
[[ ! -x "$script_dir/_common.sh" ]] || fail "_common.sh must remain source-only"

jq -e --arg model 'openrouter/z-ai/glm-5.3-flash' '
    .gateway.bind == "loopback" and
    .gateway.auth.token == {source:"store",provider:"default",id:"OPENCLAW_GATEWAY_TOKEN"} and
    .gateway.auth.allowTailscale == false and
    .gateway.auth.rateLimit.exemptLoopback == false and
    .gateway.tailscale.mode == "off" and
    .gateway.controlUi.enabled == false and
    .gateway.controlUi.allowedOrigins == [] and
    .gateway.controlUi.dangerouslyAllowHostHeaderOriginFallback == false and
    .gateway.terminal.enabled == false and
    .gateway.http.endpoints.chatCompletions.enabled == false and
    .gateway.http.endpoints.responses.enabled == false and
    .gateway.nodes.browser.mode == "off" and
    .gateway.nodes.pairing == {autoApproveLocal:false,sshVerify:false} and
    .gateway.nodes.commands.allow == [] and
    .gateway.nodes.pluginTools.enabled == false and .gateway.nodes.allowSkills == false and
    .gateway.tools.allow == [] and .gateway.tools.deny[0] == "*" and
    .session.maintenance == {
      mode:"warn",pruneAfter:"24h",archiveDashboardAfter:false,maxEntries:64,
      preserveRecent:false,resetArchiveRetention:"24h",maxDiskBytes:"100mb",highWaterBytes:"80mb"
    } and
    .agents.defaults.model == {primary:$model,fallbacks:[]} and
    .agents.defaults.modelPolicy.allow == [$model] and .agents.defaults.utilityModel == "" and
    .agents.defaults.thinkingDefault == "low" and .agents.defaults.reasoningDefault == "off" and
    .agents.defaults.skipBootstrap == true and .agents.defaults.contextInjection == "never" and
    .agents.defaults.sandbox == {mode:"non-main",scope:"agent"} and
    .tools.deny[0] == "*" and .tools.exec.security == "deny" and
    .tools.elevated.enabled == false and .browser.enabled == false and .cron.enabled == false and
    .hooks.enabled == false and .hooks.internal.enabled == false and .acp.enabled == false and
    .discovery.mdns.mode == "off" and .telemetry.enabled == false and
    .update.checkOnStart == false and .update.auto.enabled == false and
    .plugins.enabled == true and .plugins.allow == ["openrouter","device-pair"] and
    .plugins.entries == {openrouter:{enabled:true},"device-pair":{enabled:true}} and
    .plugins.slots.memory == "none" and
    .nodeHost.agentRuns.claude.enabled == false and .nodeHost.workerRuns.enabled == false and
    .nodeHost.browserProxy.enabled == false and .nodeHost.skills.enabled == false and
    .models.providers.openrouter.apiKey == {source:"store",provider:"default",id:"OPENROUTER_API_KEY"} and
    .models.providers.openrouter.params.provider == {
      allow_fallbacks:true,require_parameters:true,data_collection:"deny",zdr:true,sort:"latency"
    }
' "$config_template" >/dev/null || fail "config template is not restrictive"

for phrase in "typed action proposals only" "BOOTSTRAP.md" \
    "Personal Edge Android Kotlin controller" "execution-time interlocks" "Action Ledger"; do
    grep -Fq "$phrase" "$workspace_policy" || fail "workspace policy missing: $phrase"
done
grep -Fq 'PERSONAL_EDGE_OPENCLAW_VERSION="2026.8.1"' "$script_dir/_common.sh" ||
    fail "OpenClaw version is not pinned"
grep -Fq 'PERSONAL_EDGE_OPENCLAW_NODE_MAJOR="26"' "$script_dir/_common.sh" ||
    fail "Node major is not pinned"
rg -n 'openclaw@(latest|next)' "$script_dir" >/dev/null && fail "floating OpenClaw version found"
rg -n 'sk-or-[A-Za-z0-9_-]{12,}|ghp_[A-Za-z0-9]{12,}' "$script_dir" >/dev/null &&
    fail "credential-like text found"
rg -n 'openclaw_(env_value|replace_env_secret|read_private_secret_file)' "$script_dir" >/dev/null &&
    fail "plaintext environment-secret helper remains"
grep -Fq 'gateway call tools.effective' "$script_dir/smoke-model.sh" ||
    fail "paid smoke is not gated by the live effective tool inventory"
grep -Fq 'gateway call sessions.delete' "$script_dir/smoke-model.sh" ||
    fail "zero-tool verification session cleanup is missing"
grep -Fq 'gateway call agent --json --params' "$script_dir/smoke-model.sh" ||
    fail "paid smoke does not use the one-shot agent RPC"
grep -Fq 'gateway call agent.wait' "$script_dir/smoke-model.sh" ||
    fail "paid smoke does not wait for the terminal agent RPC receipt"
if rg -n 'openclaw_run[[:space:]]+agent([[:space:]]|$)' "$script_dir/smoke-model.sh" >/dev/null; then
    fail "paid smoke still uses the durable CLI agent command"
fi
for smoke_contract in \
    'modelRun:true' 'promptMode:"none"' 'deliver:false' 'disableMessageTool:true' \
    'thinking:"low"' 'cleanupBundleMcpOnRunEnd:true' 'sessionId:$sessionId' \
    'agent:main:explicit:$model_run_session_id' 'terminalReply' 'terminalReceipt' \
    'openclaw_model_run_residue_count' 'beforeRows:$before,afterRows:$after'; do
    grep -Fq "$smoke_contract" "$script_dir/smoke-model.sh" ||
        fail "one-shot paid smoke contract missing: $smoke_contract"
done
tools_effective_line="$(grep -n 'gateway call tools.effective' "$script_dir/smoke-model.sh" | head -1 | cut -d: -f1)"
model_run_line="$(grep -n 'gateway call agent --json --params' "$script_dir/smoke-model.sh" | head -1 | cut -d: -f1)"
may_exist_line="$(grep -n '^model_run_may_exist=1$' "$script_dir/smoke-model.sh" | head -1 | cut -d: -f1)"
[[ "$tools_effective_line" -lt "$model_run_line" ]] ||
    fail "paid model inference occurs before tools.effective=0 gate"
[[ "$may_exist_line" -lt "$model_run_line" ]] ||
    fail "paid model inference can be sent before outcome-unknown cleanup observation is armed"
for verify_contract in \
    'gateway call sessions.create' 'gateway call tools.effective' 'gateway call sessions.delete' \
    '--acknowledge-transient-session-write' '--observe-only'; do
    grep -Fq -- "$verify_contract" "$script_dir/verify-gateway.sh" ||
        fail "live verifier is missing zero-tool adoption gate: $verify_contract"
done
for acknowledgement_contract in \
    'adoption apply requires --acknowledge-transient-session-write' \
    'fresh install requires --acknowledge-transient-session-write'; do
    rg -F "$acknowledgement_contract" "$script_dir"/*.sh >/dev/null ||
        fail "transient-session acknowledgement contract missing: $acknowledgement_contract"
done
while IFS= read -r verifier_call; do
    [[ "$verifier_call" == *--acknowledge-transient-session-write* || "$verifier_call" == *--observe-only* ||
       "$verifier_call" == *--skip-live* ]] ||
        fail "verify-gateway.sh caller omits the transient-session consent and would always fail: $verifier_call"
done < <(rg -n -F '$script_dir/verify-gateway.sh"' "$script_dir" --glob '*.sh' --glob '!test-*.sh' --glob '!verify-gateway.sh')
for soak_contract in \
    'PERSONAL_EDGE_SOAK_DEFAULT_DURATION_SECONDS=86400' \
    'PERSONAL_EDGE_SOAK_DEFAULT_INTERVAL_SECONDS=300' \
    'verify-gateway.sh" --observe-only' 'lockf -s -t 0 9' \
    'availabilityClaim:"not-established-beyond-observed-window"' \
    'observedCoverageSeconds' 'soak_validate_journal 1'; do
    grep -Fq "$soak_contract" "$script_dir/soak-acceptance.sh" ||
        fail "observation-only soak contract missing: $soak_contract"
done
for soak_doc_contract in \
    'soak-acceptance.sh --start' 'observed-window-pass' \
    'not `F_FULLFSYNC`' 'does **not** prove survival of sudden mains loss' \
    'never invokes a model, creates or deletes a session'; do
    grep -Fq "$soak_doc_contract" "$script_dir/../../docs/OPENCLAW_GATEWAY.md" ||
        fail "observation-only soak runbook contract missing: $soak_doc_contract"
done
if rg -n 'gateway call|sessions\.(create|delete)|sessions[[:space:]]+cleanup|gateway[[:space:]]+(restart|repair)|smoke-model\.sh|^[[:space:]]*"?\$script_dir/watchdog\.sh' \
    "$script_dir/soak-acceptance.sh" >/dev/null; then
    fail "observation-only soak contains a prohibited active operation"
fi
for closed_lock_probe in \
    'gateway_json="$(soak_capture_without_lock soak_collect_service' \
    'listener_json="$(soak_capture_without_lock' \
    'tailscale_json="$(soak_capture_without_lock soak_collect_tailscale' \
    'launchctl print "$target" 9>&-' \
    'TAILSCALE_BE_CLI=1 "$tailscale_path" status --json 9>&-' \
    'verify-gateway.sh" --observe-only 9>&-' \
    'openclaw_run gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT") 9>&-' \
    'sleep "$sleep_seconds" 9>&-'; do
    grep -Fq "$closed_lock_probe" "$script_dir/soak-acceptance.sh" ||
        fail "soak child can inherit the receipt lock: $closed_lock_probe"
done
for bounded_collector in 'launchctl print "$target"' 'lsof -nP -iTCP' '"$tailscale_path" status --json' \
    '"$tailscale_path" serve status --json' 'pmset -g custom' 'verify-gateway.sh" --observe-only' 'fdesetup status'; do
    grep -F -- "$bounded_collector" "$script_dir/soak-acceptance.sh" | grep -Fq 'soak_bounded "$SOAK_' ||
        fail "soak collector can hang without a time limit: $bounded_collector"
done
bounded_helper="$(mktemp "${TMPDIR:-/tmp}/soak-bounded.XXXXXX")"
sed -n '/^soak_bounded() {$/,/^}$/p' "$script_dir/soak-acceptance.sh" > "$bounded_helper"
bounded_started=$SECONDS
bounded_status=0
(source "$bounded_helper"; soak_bounded 1 /bin/sh -c 'sleep 30 & sleep 30') || bounded_status=$?
rm -f -- "$bounded_helper"
[[ "$bounded_status" == 124 && $((SECONDS - bounded_started)) -lt 10 ]] ||
    fail "soak_bounded did not stop a hung collector group (status=$bounded_status)"
sample_fsync_line="$(grep -n 'soak_fsync_path "$sample_temp_file"' \
    "$script_dir/soak-acceptance.sh" | head -1 | cut -d: -f1)"
sample_publish_line="$(grep -n 'mv "$sample_temp_file" "$sample_target"' \
    "$script_dir/soak-acceptance.sh" | head -1 | cut -d: -f1)"
journal_fsync_line="$(grep -n 'soak_fsync_path "$samples_dir"' \
    "$script_dir/soak-acceptance.sh" | tail -1 | cut -d: -f1)"
sample_manifest_line="$(awk -v start="$journal_fsync_line" \
    'NR > start && /soak_update_manifest/ { print NR; exit }' \
    "$script_dir/soak-acceptance.sh")"
[[ "$sample_fsync_line" -lt "$sample_publish_line" &&
   "$sample_publish_line" -lt "$journal_fsync_line" &&
   "$journal_fsync_line" -lt "$sample_manifest_line" ]] ||
    fail "soak journal is not fsynced before its manifest pointer"
manifest_fsync_line="$(grep -n 'soak_fsync_path "$manifest_temp"' \
    "$script_dir/soak-acceptance.sh" | head -1 | cut -d: -f1)"
manifest_publish_line="$(grep -n 'mv -f "$manifest_temp" "$manifest_path"' \
    "$script_dir/soak-acceptance.sh" | head -1 | cut -d: -f1)"
manifest_dir_fsync_line="$(awk -v start="$manifest_publish_line" \
    'NR > start && /soak_fsync_path \"\$receipt_dir\"/ { print NR; exit }' \
    "$script_dir/soak-acceptance.sh")"
[[ "$manifest_fsync_line" -lt "$manifest_publish_line" &&
   "$manifest_publish_line" -lt "$manifest_dir_fsync_line" ]] ||
    fail "soak manifest publication is not durably ordered"
runner_exit_line="$(grep -n '^soak_runner_exit()' "$script_dir/soak-acceptance.sh" | cut -d: -f1)"
runner_temp_cleanup_line="$(awk -v start="$runner_exit_line" \
    'NR > start && /soak_abandon_manifest_temp/ { print NR; exit }' \
    "$script_dir/soak-acceptance.sh")"
runner_interrupt_line="$(awk -v start="$runner_exit_line" \
    'NR > start && /soak_interrupt_manifest/ { print NR; exit }' \
    "$script_dir/soak-acceptance.sh")"
runner_release_line="$(awk -v start="$runner_exit_line" \
    'NR > start && /soak_release_lock/ { print NR; exit }' \
    "$script_dir/soak-acceptance.sh")"
[[ "$runner_temp_cleanup_line" -lt "$runner_interrupt_line" &&
   "$runner_interrupt_line" -lt "$runner_release_line" ]] ||
    fail "soak exit cleanup can lose a manifest partial or lock"
finish_line="$(grep -n '^soak_finish()' "$script_dir/soak-acceptance.sh" | cut -d: -f1)"
finish_clock_guard_line="$(awk -v start="$finish_line" \
    'NR > start && /wall clock moved backward before soak completion/ { print NR; exit }' \
    "$script_dir/soak-acceptance.sh")"
finish_publish_line="$(awk -v start="$finish_line" \
    'NR > start && /soak_update_manifest/ { print NR; exit }' \
    "$script_dir/soak-acceptance.sh")"
[[ "$finish_clock_guard_line" -lt "$finish_publish_line" ]] ||
    fail "soak can publish completion after a backward wall-clock correction"
for verify_diagnostic_contract in \
    'WARN private verification diagnostics retained:' \
    'model status command failed; private diagnostics:'; do
    grep -Fq "$verify_diagnostic_contract" "$script_dir/verify-gateway.sh" ||
        fail "live verifier cannot preserve a content-free failure diagnostic: $verify_diagnostic_contract"
done
for watchdog_candidate_contract in \
    '.deployment.candidate.json' 'watchdog_final_manifest' \
    'export PERSONAL_EDGE_OPENCLAW_DEPLOYMENT_MANIFEST="$watchdog_candidate_manifest"' \
    'recovery is withheld until final manifest publication'; do
    grep -Fq "$watchdog_candidate_contract" "$script_dir/watchdog.sh" ||
        fail "RunAtLoad watchdog cannot verify an unpublished candidate: $watchdog_candidate_contract"
done
zero_session_arm_line="$(grep -n '^    zero_session_may_exist=1$' \
    "$script_dir/verify-gateway.sh" | head -1 | cut -d: -f1)"
zero_session_create_line="$(grep -n 'gateway call sessions.create --json --params' \
    "$script_dir/verify-gateway.sh" | head -1 | cut -d: -f1)"
[[ "$zero_session_arm_line" -lt "$zero_session_create_line" ]] ||
    fail "zero-tool session cleanup is not armed before outcome-unknown creation"
grep -Fq '.plugins.loaded' "$script_dir/_common.sh" ||
    fail "live plugin verification does not use the 2026.8.1 health schema"
if rg -n 'runtimeLoadedPluginIds|contextEngineQuarantines|runtimeToolQuarantines' \
    "$script_dir/_common.sh" >/dev/null; then
    fail "live plugin verification still uses the status-plugin schema"
fi
if rg -n 'sessions[[:space:]]+cleanup|session\.maintenance\.mode[[:space:]]*=[[:space:]]*"enforce"' \
    "$script_dir/watchdog.sh" >/dev/null; then
    fail "watchdog must not enforce or invoke session-history cleanup"
fi

backup_line="$(grep -n 'backup create --output' "$script_dir/adopt-existing.sh" | head -1 | cut -d: -f1)"
management_line="$(grep -n 'openclaw_prepare_private_dir "$openclaw_management_root"' "$script_dir/adopt-existing.sh" | head -1 | cut -d: -f1)"
workspace_line="$(grep -n 'mv "$openclaw_workspace_dir" "$quarantine_dir/workspace-before-adopt"' \
    "$script_dir/adopt-existing.sh" | head -1 | cut -d: -f1)"
workspace_guard_line="$(grep -n '^workspace_moved=1$' "$script_dir/adopt-existing.sh" | head -1 | cut -d: -f1)"
manifest_line="$(grep -n 'openclaw_write_deployment_manifest adopted' "$script_dir/adopt-existing.sh" | head -1 | cut -d: -f1)"
watchdog_bootstrap_line="$(grep -n 'launchctl bootstrap.*"\$openclaw_watchdog_plist"' \
    "$script_dir/adopt-existing.sh" | head -1 | cut -d: -f1)"
verify_line="$(grep -n 'PERSONAL_EDGE_OPENCLAW_DEPLOYMENT_MANIFEST=.*manifest_candidate' "$script_dir/adopt-existing.sh" | head -1 | cut -d: -f1)"
publish_line="$(grep -n 'mv \"\$manifest_candidate\" \"\$openclaw_deployment_manifest\"' "$script_dir/adopt-existing.sh" | head -1 | cut -d: -f1)"
[[ "$backup_line" -lt "$management_line" && "$management_line" -lt "$workspace_guard_line" &&
   "$workspace_guard_line" -lt "$workspace_line" &&
   "$workspace_line" -lt "$manifest_line" && "$manifest_line" -lt "$watchdog_bootstrap_line" &&
   "$watchdog_bootstrap_line" -lt "$verify_line" &&
   "$verify_line" -lt "$publish_line" ]] || fail "unsafe adoption publication order"
harden_backup_line="$(grep -n 'backup create --output' "$script_dir/harden-existing.sh" | head -1 | cut -d: -f1)"
harden_apply_line="$(grep -n 'config patch --file.*patch_file.*replace_arguments' "$script_dir/harden-existing.sh" | tail -1 | cut -d: -f1)"
harden_restore_line="$(grep -n 'mv -f \"\$rollback_temp\" \"\$openclaw_config_path\"' "$script_dir/harden-existing.sh" | head -1 | cut -d: -f1)"
[[ -n "$harden_backup_line" && -n "$harden_apply_line" && -n "$harden_restore_line" &&
   "$harden_backup_line" -lt "$harden_apply_line" ]] || fail "hardening is not backup-first/rollback-capable"
install_failure_trap_line="$(grep -n 'trap cleanup_failed_install EXIT INT TERM' \
    "$script_dir/install-gateway.sh" | head -1 | cut -d: -f1)"
install_manifest_line="$(grep -n 'openclaw_write_deployment_manifest.*manifest_candidate' \
    "$script_dir/install-gateway.sh" | tail -1 | cut -d: -f1)"
install_service_line="$(grep -n 'gateway install --runtime node --wrapper' \
    "$script_dir/install-gateway.sh" | head -1 | cut -d: -f1)"
install_watchdog_bootstrap_line="$(grep -n 'launchctl bootstrap.*"\$openclaw_watchdog_plist"' \
    "$script_dir/install-gateway.sh" | head -1 | cut -d: -f1)"
install_verify_line="$(grep -n 'PERSONAL_EDGE_OPENCLAW_DEPLOYMENT_MANIFEST=.*manifest_candidate' \
    "$script_dir/install-gateway.sh" | head -1 | cut -d: -f1)"
install_publish_line="$(grep -n 'mv "\$manifest_candidate" "\$openclaw_deployment_manifest"' \
    "$script_dir/install-gateway.sh" | head -1 | cut -d: -f1)"
[[ "$install_failure_trap_line" -lt "$install_manifest_line" &&
   "$install_manifest_line" -lt "$install_service_line" &&
   "$install_service_line" -lt "$install_watchdog_bootstrap_line" &&
   "$install_watchdog_bootstrap_line" -lt "$install_verify_line" &&
   "$install_verify_line" -lt "$install_publish_line" ]] ||
    fail "fresh install can mutate service state before failure cleanup is armed"
for install_cleanup_contract in \
    'gateway stop --disable' 'launchctl disable "$gateway_target"' \
    'launchctl bootout "$gateway_target"' 'failed-install Gateway/watchdog stopped state'; do
    grep -Fq "$install_cleanup_contract" "$script_dir/install-gateway.sh" ||
        fail "fresh install failure cleanup is incomplete: $install_cleanup_contract"
done
tailscale_snapshot_line="$(grep -n 'cp "$openclaw_config_path" "$transaction_temp/original-openclaw.json"' \
    "$script_dir/enable-tailscale-serve.sh" | head -1 | cut -d: -f1)"
tailscale_trap_line="$(grep -n 'trap cleanup_tailscale_transaction EXIT INT TERM' \
    "$script_dir/enable-tailscale-serve.sh" | head -1 | cut -d: -f1)"
tailscale_write_line="$(grep -n 'config set gateway.bind loopback' \
    "$script_dir/enable-tailscale-serve.sh" | head -1 | cut -d: -f1)"
[[ "$tailscale_snapshot_line" -lt "$tailscale_trap_line" &&
   "$tailscale_trap_line" -lt "$tailscale_write_line" ]] ||
    fail "Tailscale Serve can change config before rollback is armed"
[[ "$(rg -c 'env TAILSCALE_BE_CLI=1 "\$tailscale_path"' \
    "$script_dir/enable-tailscale-serve.sh")" == "5" ]] ||
    fail "every bundled Tailscale invocation must force CLI mode"
if rg -n '^[[:space:]]*"\$tailscale_path"' "$script_dir/enable-tailscale-serve.sh" >/dev/null; then
    fail "a Tailscale invocation can bypass forced bundled-app CLI mode"
fi
for tailscale_contract in \
    'backup create --output "$tailscale_backup_dir" --verify --json' \
    'serve status --json' 'serve set-raw' '.BackendState == "Running" and .Self.Online == true' \
    'openclaw_assert_managed_tailscale_serve_receipt' 'Gateway has a non-loopback listener' \
    'owner Serve state were restored' 'gateway stop --disable'; do
    grep -Fq "$tailscale_contract" "$script_dir/enable-tailscale-serve.sh" ||
        fail "Tailscale transaction contract is incomplete: $tailscale_contract"
done

fixture_parent="${TMPDIR:-/tmp}"
fixture_parent="${fixture_parent%/}"
fixture_root="$(mktemp -d "$fixture_parent/personal-edge-openclaw-assets.XXXXXX")"
fixture_root="$(cd "$fixture_root" && pwd -P)"
fixture_parent="$(cd "$fixture_parent" && pwd -P)"
cleanup_fixture() {
    local fixture_status=$?
    if [[ "$fixture_status" != 0 || "${PERSONAL_EDGE_OPENCLAW_KEEP_TEST_FIXTURE:-0}" == 1 ]]; then
        echo "INFO retained isolated test fixture $fixture_root (exit=$fixture_status)" >&8
        return "$fixture_status"
    fi
    case "$fixture_root" in
        "$fixture_parent"/personal-edge-openclaw-assets.*) rm -rf -- "$fixture_root" ;;
        *) fail "unsafe fixture cleanup target" ;;
    esac
}
trap cleanup_fixture EXIT INT TERM
chmod 700 "$fixture_root"

fixture_state="$fixture_root/state"
fixture_workspace="$fixture_state/workspace"
fixture_runtime="$fixture_root/runtime"
fixture_launch_agents="$fixture_root/LaunchAgents"
fixture_node_dir="$fixture_root/node"
mkdir -p "$fixture_workspace" "$fixture_state/state" "$fixture_state/agents/main/agent" \
    "$fixture_state/service-env" "$fixture_state/tmp" \
    "$fixture_runtime/lib/node_modules/openclaw/dist/extensions/openrouter" \
    "$fixture_runtime/lib/node_modules/openclaw/dist/extensions/device-pair" \
    "$fixture_launch_agents" "$fixture_node_dir"
find "$fixture_root" -type d -exec chmod 700 {} +
source "$script_dir/_runtime-test-fixture.sh"
fixture_fake_home="$fixture_root/fake-home"
mkdir -p "$fixture_fake_home/personal-edge-openclaw-backref"
for fixture_bypass in "$fixture_fake_home/personal-edge-openclaw-backref/.." "$fixture_fake_home"; do
    if (HOME="$fixture_fake_home" openclaw_runtime_test_fixture "$fixture_bypass" "$fixture_state" \
            "$fixture_launch_agents" "$script_dir"); then
        fail "runtime fixture accepted a root that resolves to HOME: $fixture_bypass"
    fi
done
[[ ! -e "$fixture_fake_home/runtime-stubs" && ! -e "$fixture_fake_home/.colima" ]] ||
    fail "rejected runtime fixture root still wrote into HOME"
rm -rf -- "$fixture_fake_home"
openclaw_runtime_test_fixture "$fixture_root" "$fixture_state" "$fixture_launch_agents" "$script_dir"

fixture_config="$fixture_state/openclaw.json"
jq --arg workspace "$fixture_workspace" '
    .agents.defaults.workspace=$workspace |
    .meta={lastTouchedVersion:"2026.8.1",migrations:{modelPolicyAllowlist:true}}
' \
    "$config_template" > "$fixture_config"
printf '%s\n' '{"version":"2026.8.1"}' > "$fixture_runtime/lib/node_modules/openclaw/package.json"
printf '%s\n' '// fixture CLI' > "$fixture_runtime/lib/node_modules/openclaw/openclaw.mjs"
printf '%s\n' '// fixture Gateway' > "$fixture_runtime/lib/node_modules/openclaw/dist/index.js"
cp "$workspace_policy" "$fixture_workspace/AGENTS.md"
sqlite3 "$fixture_state/state/openclaw.sqlite" '
    CREATE TABLE secret_store_entries (id TEXT, value TEXT);
    CREATE TABLE mcp_oauth_stores (id TEXT, value TEXT);
    INSERT INTO secret_store_entries VALUES ("gateway", "fixture-secret-hash-input");
    INSERT INTO mcp_oauth_stores VALUES ("oauth", "fixture-oauth-hash-input");
'
sqlite3 "$fixture_state/agents/main/agent/openclaw-agent.sqlite" '
    CREATE TABLE auth_profile_store (id TEXT, value TEXT);
    INSERT INTO auth_profile_store VALUES ("openrouter", "fixture-profile-hash-input");
    CREATE TABLE acp_parent_stream_events (session_id TEXT, run_id TEXT);
    CREATE TABLE board_tabs (session_key TEXT);
    CREATE TABLE board_widgets (session_key TEXT);
    CREATE TABLE context_engine_turn_outbox (session_id TEXT);
    CREATE TABLE conversation_deliveries (source_session_key TEXT);
    CREATE TABLE heartbeat_outcomes (session_key TEXT, run_session_key TEXT);
    CREATE TABLE memory_entry_origins (session_id TEXT, session_key TEXT);
    CREATE TABLE memory_session_tombstones (session_id TEXT);
    CREATE TABLE message_tool_run_outcomes (session_key TEXT, run_id TEXT);
    CREATE TABLE session_conversations (session_id TEXT);
    CREATE TABLE session_goal_operations (session_id TEXT, session_key TEXT);
    CREATE TABLE session_members (session_key TEXT);
    CREATE TABLE session_nodes (
        session_key TEXT, current_session_id TEXT, parent_session_key TEXT,
        fork_source_session_key TEXT, fork_source_session_id TEXT
    );
    CREATE TABLE session_participants (session_key TEXT);
    CREATE TABLE session_pending_inputs (session_id TEXT, session_key TEXT, run_id TEXT);
    CREATE TABLE session_progress_cards (session_key TEXT);
    CREATE TABLE session_suggestions (session_key TEXT);
    CREATE TABLE session_transcript_active_events (session_id TEXT);
    CREATE TABLE session_transcript_archives (session_id TEXT, session_key TEXT);
    CREATE TABLE session_transcript_fts (session_id TEXT);
    CREATE TABLE session_transcript_index_state (session_id TEXT);
    CREATE TABLE session_windows (
        session_id TEXT, session_key TEXT, previous_session_id TEXT, parent_session_key TEXT
    );
    CREATE TABLE standing_intents (source_session_id TEXT);
    CREATE TABLE trajectory_runtime_events (session_id TEXT, run_id TEXT);
    CREATE TABLE transcript_event_identities (session_id TEXT);
    CREATE TABLE transcript_events (session_id TEXT);
    CREATE TABLE transcript_rewrite_watermarks (session_id TEXT);
'
find "$fixture_root" -type f -exec chmod 600 {} +
chmod 700 "$PERSONAL_EDGE_COLIMA_BIN" "$PERSONAL_EDGE_DOCKER_BIN"

model_run_residue_fixture() (
    export PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root"
    export PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state"
    export PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config"
    export PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace"
    export PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime"
    export PERSONAL_EDGE_OPENCLAW_SMOKE_LIBRARY_ONLY=1
    source "$script_dir/smoke-model.sh"
    model_run_agent_db="$fixture_state/agents/main/agent/openclaw-agent.sqlite"
    requested_key='agent:main:explicit:model-run-fixture'
    explicit_id='model-run-fixture'
    internal_key='agent:main:internal-session-effects:fixture'
    internal_id='internal-session-effects-fixture'
    run_id='personal-edge-glm-smoke-fixture'
    [[ "$(openclaw_model_run_residue_count "$requested_key" "$explicit_id" \
        "$internal_key" "$internal_id" "$run_id")" == "0" ]] || return 1
    sqlite3 "$model_run_agent_db" "
        INSERT INTO acp_parent_stream_events VALUES ('unrelated-session', '$run_id');
        INSERT INTO message_tool_run_outcomes VALUES ('unrelated-session', '$run_id');
        INSERT INTO session_pending_inputs VALUES ('unrelated-session', 'unrelated-key', '$run_id');
        INSERT INTO trajectory_runtime_events VALUES ('unrelated-session', '$run_id');
    "
    [[ "$(openclaw_model_run_residue_count "$requested_key" "$explicit_id" \
        "$internal_key" "$internal_id" "$run_id")" == "4" ]] || return 1
    sqlite3 "$model_run_agent_db" "
        DELETE FROM acp_parent_stream_events;
        DELETE FROM message_tool_run_outcomes;
        DELETE FROM session_pending_inputs;
        DELETE FROM trajectory_runtime_events;
    "
    [[ "$(openclaw_model_run_residue_count "$requested_key" "$explicit_id" \
        "$internal_key" "$internal_id" "$run_id")" == "0" ]]
)
model_run_residue_fixture || fail "model-run residue gate missed run-id-only rows"

service_wrapper="$fixture_state/service-env/ai.openclaw.personaledge-env-wrapper.sh"
service_environment="$fixture_state/service-env/ai.openclaw.personaledge.env"
cat > "$service_wrapper" <<'EOF'
#!/bin/sh
set -eu
env_file="$1"
shift
if [ -f "$env_file" ]; then
  . "$env_file"
fi
exec "$@"
EOF
cat > "$service_environment" <<EOF
# Generated by OpenClaw. Do not edit while the gateway service is installed.
export HOME='$HOME'
export NODE_EXTRA_CA_CERTS='/etc/ssl/cert.pem'
export NODE_OPTIONS=''
export NODE_USE_SYSTEM_CA='1'
export OPENCLAW_CONFIG_PATH='$fixture_config'
export OPENCLAW_GATEWAY_PORT='18789'
export OPENCLAW_LAUNCHD_LABEL='ai.openclaw.personaledge'
export OPENCLAW_PROFILE='personaledge'
export OPENCLAW_SERVICE_KIND='gateway'
export OPENCLAW_SERVICE_MARKER='openclaw'
export OPENCLAW_STATE_DIR='$fixture_state'
export OPENCLAW_SYSTEMD_UNIT='openclaw-gateway-personaledge.service'
export OPENCLAW_WINDOWS_TASK_HIDDEN_LAUNCHER='1'
export OPENCLAW_WINDOWS_TASK_NAME='OpenClaw Gateway (personaledge)'
export PATH='$fixture_node_dir:$fixture_runtime/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin'
export TMPDIR='$fixture_state/tmp'
EOF
chmod 700 "$service_wrapper"
chmod 600 "$service_environment"

fixture_node="$fixture_node_dir/node"
cat > "$fixture_node" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "--version" ]]; then
    echo 'v26.5.0'
    exit 0
fi
if [[ "${1:-}" == "-p" && "${2:-}" == 'process.versions.node.split(".")[0]' ]]; then
    echo '26'
    exit 0
fi
all_args=" $* "
if [[ -n "${FAKE_OPENCLAW_CALL_LOG:-}" ]]; then
    printf '%s\n' "$*" >> "$FAKE_OPENCLAW_CALL_LOG"
fi
if [[ -n "${FAKE_GATEWAY_RESTART_LOG:-}" && "$all_args" == *" gateway restart "* ]]; then
    printf '%s\n' restart >> "$FAKE_GATEWAY_RESTART_LOG"
fi
if [[ "$all_args" == *" --version "* ]]; then
    echo 'OpenClaw 2026.8.1'
elif [[ "$all_args" == *" backup create "* ]]; then
    previous=""
    for argument in "$@"; do
        if [[ "$previous" == "--output" ]]; then
            mkdir -p "$argument"
            printf '%s\n' fixture-backup > "$argument/fixture.openclaw-backup"
            break
        fi
        previous="$argument"
    done
    printf '%s\n' '{"ok":true}'
elif [[ "$all_args" == *" config patch "* ]]; then
    patch_path=""
    replace_meta=0
    previous=""
    for argument in "$@"; do
        if [[ "$previous" == "--file" ]]; then patch_path="$argument"; fi
        if [[ "$previous" == "--replace-path" && "$argument" == "meta" ]]; then
            replace_meta=1
        fi
        previous="$argument"
    done
    if (( replace_meta == 1 )) || jq -e 'has("meta")' "$patch_path" >/dev/null; then
        printf '%s\n' 'fixture: auto-managed meta edit refused' >&2
        exit 78
    fi
    if [[ "${FAKE_CONFIG_PATCH_DRY_RUN_FAIL:-0}" == "1" && "$all_args" == *" --dry-run "* ]]; then
        printf '%s\n' 'fixture private config patch detail must not reach the console' >&2
        exit 79
    fi
    if [[ "$all_args" != *" --dry-run "* ]]; then
        patch_temp="${FAKE_OPENCLAW_CONFIG_PATH}.partial.$$"
        jq --slurpfile patch "$patch_path" '
            . as $current |
            reduce ($patch[0] | to_entries[]) as $entry (.;
                if $entry.value == null then del(.[$entry.key])
                else .[$entry.key] = $entry.value
                end
            ) |
            .meta = (($current.meta // {}) + {lastTouchedVersion:"2026.8.1"})
        ' "$FAKE_OPENCLAW_CONFIG_PATH" > "$patch_temp"
        mv "$patch_temp" "$FAKE_OPENCLAW_CONFIG_PATH"
        if [[ "${FAKE_CONFIG_PATCH_FAIL_AFTER_WRITE:-0}" == "1" ]]; then exit 77; fi
    fi
    printf '%s\n' '{"valid":true}'
elif [[ "$all_args" == *" config set "* ]]; then
    config_key="${@: -2:1}"
    config_value="${@: -1}"
    config_temp="${FAKE_OPENCLAW_CONFIG_PATH}.set.$$"
    case "$config_key" in
        gateway.bind) jq --arg value "$config_value" '.gateway.bind=$value' \
            "$FAKE_OPENCLAW_CONFIG_PATH" > "$config_temp" ;;
        gateway.auth.mode) jq --arg value "$config_value" '.gateway.auth.mode=$value' \
            "$FAKE_OPENCLAW_CONFIG_PATH" > "$config_temp" ;;
        gateway.tailscale.mode) jq --arg value "$config_value" '.gateway.tailscale.mode=$value' \
            "$FAKE_OPENCLAW_CONFIG_PATH" > "$config_temp" ;;
        *) exit 80 ;;
    esac
    mv "$config_temp" "$FAKE_OPENCLAW_CONFIG_PATH"
    chmod 600 "$FAKE_OPENCLAW_CONFIG_PATH"
    printf '%s\n' '{"ok":true}'
elif [[ "$all_args" == *" secrets audit "* ]]; then
    printf '%s\n' '{"status":"clean","summary":{"plaintextCount":0,"unresolvedRefCount":0,"shadowedRefCount":0,"storeResidueCount":0,"legacyResidueCount":0}}'
elif [[ "$all_args" == *" plugins list "* ]]; then
    printf '{"plugins":[{"id":"openrouter","enabled":true,"status":"loaded","origin":"bundled","rootDir":"%s/lib/node_modules/openclaw/dist/extensions/openrouter","providerIds":["openrouter"],"commands":[]},{"id":"device-pair","enabled":true,"status":"loaded","origin":"bundled","rootDir":"%s/lib/node_modules/openclaw/dist/extensions/device-pair","providerIds":[],"commands":["pair"]}]}\n' "$FAKE_OPENCLAW_RUNTIME_ROOT" "$FAKE_OPENCLAW_RUNTIME_ROOT"
elif [[ "$all_args" == *" models status "* ]]; then
    if [[ "${FAKE_MODELS_STATUS_FAIL:-0}" == "1" ]]; then
        printf '%s\n' 'fixture private model-status diagnostic' >&2
        exit 84
    fi
    printf '%s\n' '{"ok":true}'
elif [[ "$all_args" == *" doctor --lint --all --json "* ]]; then
    printf '%s\n' 'fixture private doctor diagnostic' >&2
    case "${FAKE_DOCTOR_RESULT:-clean}" in
        clean) printf '%s\n' '{"ok":true,"checksRun":59,"checksSkipped":0,"findings":[]}' ;;
        warning|fatal)
            printf '%s\n' '{"ok":false,"checksRun":59,"checksSkipped":0,"findings":[{"checkId":"core/doctor/auth-profiles","severity":"warning","message":"fixture private auth warning","requirement":"missing_credential"}]}'
            if [[ "$FAKE_DOCTOR_RESULT" == fatal ]]; then exit 78; fi
            exit 1 ;;
        error)
            printf '%s\n' '{"ok":false,"checksRun":59,"checksSkipped":0,"findings":[{"checkId":"core/doctor/auth-profiles","severity":"error","message":"fixture private auth error"}]}'
            exit 1 ;;
        malformed) printf '%s\n' 'invalid-json'; exit 1 ;;
        mismatch) printf '%s\n' '{"ok":true,"checksRun":59,"checksSkipped":0,"findings":[]}'; exit 1 ;;
        multiple)
            printf '%s\n' '{"ok":true,"checksRun":59,"checksSkipped":0,"findings":[]}' '{}'
            ;;
        unknown-severity)
            printf '%s\n' '{"ok":false,"checksRun":59,"checksSkipped":0,"findings":[{"checkId":"core/doctor/auth-profiles","severity":"fatal","message":"fixture private auth error"}]}'
            exit 1 ;;
        *) exit 89 ;;
    esac
elif [[ "$all_args" == *" security audit "* ]]; then
    printf '%s\n' '{"summary":{"critical":0,"warn":0,"info":0},"findings":[]}'
elif [[ "$all_args" == *" gateway call sessions.create "* ]]; then
    params=''
    previous=''
    for argument in "$@"; do
        if [[ "$previous" == "--params" ]]; then params="$argument"; break; fi
        previous="$argument"
    done
    requested_key="$(jq -r '.key' <<< "$params")"
    # Match the installed 2026.8.1 local token-auth CLI constraints, not just a permissive echo.
    # Principal-scoped replay requires a device identity omitted by that CLI, and incognito
    # creation accepts only a new canonical dashboard:incognito-* key.
    jq -e '
        (has("idempotencyKey") | not) and .incognito == true and
        .permissionMode == "read-only" and .agentId == "main" and
        (.key | test("^agent:main:dashboard:incognito-personal-edge-(verify-)?zero-tools-[0-9]{8}t[0-9]{6}z-[0-9]+$")) and
        (has("message") | not) and (has("task") | not) and (has("attachments") | not)
    ' <<< "$params" >/dev/null || exit 86
    if [[ -n "${FAKE_SESSION_CREATE_LOG:-}" ]]; then
        printf '%s\n' "$requested_key" >> "$FAKE_SESSION_CREATE_LOG"
    fi
    if [[ "${FAKE_ZERO_SESSION_RETURN_OWNER:-0}" == "1" ]]; then
        printf '%s\n' '{"key":"agent:main:owner-existing-session"}'
    else
        jq -cn --arg key "$requested_key" '{key:$key}'
    fi
elif [[ "$all_args" == *" gateway call sessions.delete "* ]]; then
    params=''
    previous=''
    for argument in "$@"; do
        if [[ "$previous" == "--params" ]]; then params="$argument"; break; fi
        previous="$argument"
    done
    deleted_key="$(jq -r '.key' <<< "$params")"
    if [[ -n "${FAKE_SESSION_DELETE_LOG:-}" ]]; then
        printf '%s\n' "$deleted_key" >> "$FAKE_SESSION_DELETE_LOG"
    fi
    printf '%s\n' '{"ok":true}'
elif [[ "$all_args" == *" gateway health "* ]]; then
    if [[ "${FAKE_GATEWAY_HEALTH_FAIL:-0}" == "1" ]]; then exit 85; fi
    printf '%s\n' '{"ok":true}'
elif [[ "$all_args" == *" health --json "* ]]; then
    printf '%s\n' '{"plugins":{"loaded":["openrouter","device-pair"],"errors":[],"unavailable":[]}}'
else
    printf '%s\n' '{}'
fi
EOF
chmod 700 "$fixture_node"

legacy_runtime_tree_sha256() {
    {
        while IFS= read -r runtime_path; do
            relative_path="${runtime_path#"$fixture_runtime/"}"
            printf 'F %s %s\n' "$(shasum -a 256 "$runtime_path" | awk '{print $1}')" \
                "$relative_path"
        done < <(find "$fixture_runtime" \
            -path "$fixture_runtime/.personal-edge-management" -prune -o \
            -type f -print | LC_ALL=C sort)
        while IFS= read -r runtime_path; do
            relative_path="${runtime_path#"$fixture_runtime/"}"
            link_target="$(realpath "$runtime_path")"
            printf 'L %s %s\n' "${link_target#"$fixture_runtime/"}" "$relative_path"
        done < <(find "$fixture_runtime" \
            -path "$fixture_runtime/.personal-edge-management" -prune -o \
            -type l -print | LC_ALL=C sort)
    } | shasum -a 256 | awk '{print $1}'
}

optimized_runtime_tree_sha256() (
    export PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root"
    export PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state"
    export PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config"
    export PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace"
    export PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime"
    export PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management"
    source "$script_dir/_common.sh"
    openclaw_runtime_tree_sha256
)

runtime_link="$fixture_runtime/lib/node_modules/openclaw/openclaw-link.mjs"
ln -s openclaw.mjs "$runtime_link"
external_runtime_target="$fixture_root/external-runtime-target.mjs"
external_runtime_link="$fixture_runtime/lib/node_modules/openclaw/external-link.mjs"
printf '%s\n' '// external fixture target' > "$external_runtime_target"
ln -s "$external_runtime_target" "$external_runtime_link"
legacy_runtime_hash="$(legacy_runtime_tree_sha256)"
optimized_runtime_hash="$(optimized_runtime_tree_sha256)"
[[ "$optimized_runtime_hash" == "$legacy_runtime_hash" ]] ||
    fail "single-process runtime hash differs from the legacy F/L stream"
rm -f -- "$external_runtime_link" "$external_runtime_target"

unsafe_runtime_name="$fixture_runtime/lib/node_modules/openclaw/"$'unsafe\nname'
printf '%s\n' 'unsafe fixture' > "$unsafe_runtime_name"
if optimized_runtime_tree_sha256 > "$fixture_root/unsafe-runtime-hash.log" 2>&1; then
    fail "single-process runtime hash accepted a line break in a path"
fi
rm -f -- "$unsafe_runtime_name"

broken_runtime_link="$fixture_runtime/lib/node_modules/openclaw/broken-link.mjs"
ln -s missing-target.mjs "$broken_runtime_link"
if optimized_runtime_tree_sha256 > "$fixture_root/broken-runtime-hash.log" 2>&1; then
    fail "single-process runtime hash accepted a broken symbolic link"
fi
rm -f -- "$broken_runtime_link"

unsafe_link_target="$fixture_root/"$'unsafe\rtarget.mjs'
unsafe_target_link="$fixture_runtime/lib/node_modules/openclaw/unsafe-target-link.mjs"
printf '%s\n' '// unsafe link target fixture' > "$unsafe_link_target"
ln -s "$unsafe_link_target" "$unsafe_target_link"
if optimized_runtime_tree_sha256 > "$fixture_root/unsafe-link-target-hash.log" 2>&1; then
    fail "single-process runtime hash accepted a line break in a symbolic-link target"
fi
rm -f -- "$unsafe_target_link" "$unsafe_link_target"

gateway_plist="$fixture_launch_agents/ai.openclaw.personaledge.plist"
jq -n \
    --arg wrapper "$service_wrapper" --arg environment "$service_environment" \
    --arg node "$fixture_node" --arg entry "$fixture_runtime/lib/node_modules/openclaw/dist/index.js" \
    --arg state "$fixture_state" '
    {
      Label:"ai.openclaw.personaledge",RunAtLoad:true,KeepAlive:true,WorkingDirectory:$state,
      ProgramArguments:["/bin/sh",$wrapper,$environment,$node,"--max-old-space-size=16384",
        $entry,"gateway","--port","18789"]
    }
' | plutil -convert xml1 -o "$gateway_plist" -- -
chmod 600 "$gateway_plist"

fresh_launch_agents="$fixture_root/FreshLaunchAgents"
mkdir -p "$fresh_launch_agents"
chmod 700 "$fresh_launch_agents"
fresh_gateway_plist="$fresh_launch_agents/ai.openclaw.personaledge.plist"
jq -n \
    --arg wrapper "$service_wrapper" --arg environment "$service_environment" \
    --arg managed "$fixture_runtime/.personal-edge-management/bin/openclaw" \
    --arg state "$fixture_state" '
    {
      Label:"ai.openclaw.personaledge",RunAtLoad:true,KeepAlive:true,WorkingDirectory:$state,
      ProgramArguments:["/bin/sh",$wrapper,$environment,$managed,"gateway","--port","18789"],
      EnvironmentVariables:{}
    }
' | plutil -convert xml1 -o "$fresh_gateway_plist" -- -
chmod 600 "$fresh_gateway_plist"

fresh_service_definition_fixture() (
    export PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root"
    export PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state"
    export PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config"
    export PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace"
    export PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime"
    export PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management"
    export PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fresh_launch_agents"
    export PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node"
    source "$script_dir/_common.sh"
    openclaw_assert_fresh_service_definition "$fixture_node"
)
fresh_service_definition_fixture
cp "$fresh_gateway_plist" "$fixture_root/fresh-gateway-canonical.plist"
plutil -replace ProgramArguments -json \
    "$(plutil -convert json -o - "$fresh_gateway_plist" | jq -c '.ProgramArguments + ["--bind=lan"]')" \
    "$fresh_gateway_plist"
if fresh_service_definition_fixture > "$fixture_root/fresh-gateway-injection.log" 2>&1; then
    fail "fresh Gateway service definition accepted an extra launch argument"
fi
/bin/mv "$fixture_root/fresh-gateway-canonical.plist" "$fresh_gateway_plist"
chmod 600 "$fresh_gateway_plist"

watchdog_fixture_json="$(jq -cn \
    --arg scriptPath "$fixture_runtime/.personal-edge-management/libexec/watchdog.sh" \
    --arg allowedRoot "$fixture_root" --arg stateDir "$fixture_state" \
    --arg configPath "$fixture_config" --arg runtimeRoot "$fixture_runtime" \
    --arg managementRoot "$fixture_runtime/.personal-edge-management" \
    --arg launchAgentDir "$fresh_launch_agents" \
    --arg stdoutPath "$fixture_state/logs/personal-edge-watchdog.log" \
    --arg stderrPath "$fixture_state/logs/personal-edge-watchdog.err.log" '
    {
      Label:"com.personaledge.openclaw-watchdog",ProgramArguments:["/bin/bash",$scriptPath],
      EnvironmentVariables:{
        PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT:$allowedRoot,
        PERSONAL_EDGE_OPENCLAW_STATE_DIR:$stateDir,
        PERSONAL_EDGE_OPENCLAW_CONFIG_PATH:$configPath,
        PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT:$runtimeRoot,
        PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT:$managementRoot,
        PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR:$launchAgentDir
      },RunAtLoad:true,StartInterval:300,ProcessType:"Standard",
      StandardOutPath:$stdoutPath,StandardErrorPath:$stderrPath
    }
')"
watchdog_definition_fixture() (
    export PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root"
    export PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state"
    export PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config"
    export PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime"
    export PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management"
    export PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fresh_launch_agents"
    source "$script_dir/_common.sh"
    openclaw_assert_watchdog_plist "$1"
)
watchdog_definition_fixture "$watchdog_fixture_json"
if watchdog_definition_fixture \
    "$(jq -c '.EnvironmentVariables.BASH_ENV="/tmp/injected.sh"' <<< "$watchdog_fixture_json")" \
    > "$fixture_root/watchdog-env-injection.log" 2>&1; then
    fail "watchdog service definition accepted an extra BASH_ENV"
fi

stub_dir="$fixture_root/stubs"
mkdir -p "$stub_dir"
cat > "$stub_dir/launchctl" <<'EOF'
#!/usr/bin/env bash
if [[ -n "${FAKE_LAUNCHCTL_CALL_LOG:-}" ]]; then printf '%s\n' "$*" >> "$FAKE_LAUNCHCTL_CALL_LOG"; fi
if [[ "${FAKE_LAUNCHCTL_FAIL_BOOTSTRAP:-0}" == "1" && "${1:-}" == "bootstrap" ]]; then exit 65; fi
if [[ "${1:-}" == "print" && "${2:-}" == *"openclaw-watchdog"* &&
      "${FAKE_WATCHDOG_LOADED:-0}" != "1" ]]; then exit 1; fi
if [[ "${1:-}" == print && "${2:-}" == *ai.openclaw.personaledge ]]; then
    printf 'state = running\npid = 41001\n'
fi
if [[ "${1:-}" == print && "${2:-}" == *colima-runtime ]]; then
    printf 'state = running\npid = 41003\nruns = 1\n'
fi
exit 0
EOF
cat > "$stub_dir/lsof" <<'EOF'
#!/usr/bin/env bash
if [[ "$*" == *"-F pn"* ]]; then printf '%s\n' p41001; fi
printf '%s\n' 'n127.0.0.1:18789'
if [[ "$*" == *"-a -p 41001"* ]]; then printf '%s\n' 'n127.0.0.1:43210'; fi
EOF
cat > "$stub_dir/ps" <<'EOF'
#!/usr/bin/env bash
id -u
EOF
chmod 700 "$stub_dir/ps"
cat > "$stub_dir/mv" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${FAKE_MV_FAIL_RESTRICTED_WORKSPACE:-0}" == "1" && $# -eq 2 &&
      "$1" == */.workspace-personal-edge-adopt-* &&
      "$2" == "${PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR:-}" ]]; then
    exit 66
fi
exec /bin/mv "$@"
EOF
cat > "$stub_dir/tailscale" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${TAILSCALE_BE_CLI:-}" == "1" ]] || exit 81
serve_state="${FAKE_TAILSCALE_SERVE_STATE_FILE:?}"
serve_status_count_file="${serve_state}.status-count"
if [[ $# -eq 2 && "$1" == "serve" && "$2" == "set-raw" ]]; then
    cat > "$serve_state"
elif [[ $# -eq 3 && "$1" == "serve" && "$2" == "status" && "$3" == "--json" ]]; then
    status_count=0
    if [[ -f "$serve_status_count_file" ]]; then
        status_count="$(< "$serve_status_count_file")"
    fi
    status_count=$((status_count + 1))
    printf '%s\n' "$status_count" > "$serve_status_count_file"
    if [[ "${FAKE_TAILSCALE_SERVE_STATUS_INVALID:-0}" == "1" && "$status_count" == "2" ]]; then
        printf '%s\n' '{"TCP":{"444":{"HTTPS":true}},"Web":{},"AllowFunnel":{}}' > "$serve_state"
    fi
    cat "$serve_state"
elif [[ $# -eq 2 && "$1" == "status" && "$2" == "--json" ]]; then
    printf '%s\n' '{"BackendState":"Running","Self":{"DNSName":"fixture.tailnet.ts.net.","Online":true}}'
else
    exit 82
fi
EOF
chmod 700 "$stub_dir/launchctl" "$stub_dir/lsof" "$stub_dir/mv" "$stub_dir/tailscale"

soak_stub_dir="$fixture_root/soak-stubs"
mkdir "$soak_stub_dir"
chmod 700 "$soak_stub_dir"
cat > "$soak_stub_dir/launchctl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${FAKE_SOAK_HOST_CALL_LOG:-}" ]]; then printf 'launchctl %s\n' "$*" >> "$FAKE_SOAK_HOST_CALL_LOG"; fi
[[ "${1:-}" == "print" ]] || exit 83
target="${2:-}"
if [[ "$target" == *"ai.openclaw.personaledge"* ]]; then
    [[ "${FAKE_SOAK_GATEWAY_MISSING:-0}" != "1" ]] || exit 1
    printf 'state = running\npid = 41001\nruns = %s\ngeneration = %s\nlast exit code = %s\nlast terminating signal = %s\ncrash count = %s\n' \
        "${FAKE_SOAK_GATEWAY_RUNS:-9}" "${FAKE_SOAK_GATEWAY_GENERATION:-3}" \
        "${FAKE_SOAK_GATEWAY_EXIT:-0}" "${FAKE_SOAK_GATEWAY_SIGNAL:-0}" \
        "${FAKE_SOAK_GATEWAY_CRASHES:-0}"
elif [[ "$target" == *"colima-runtime"* ]]; then
    [[ "${FAKE_RUNTIME_SERVICE_MISSING:-0}" != 1 ]] || exit 1
    printf 'state = running\npid = %s\nruns = %s\n' \
        "${FAKE_RUNTIME_SERVICE_PID:-41003}" "${FAKE_RUNTIME_SERVICE_RUNS:-1}"
elif [[ "$target" == *"openclaw-watchdog"* ]]; then
    [[ "${FAKE_SOAK_WATCHDOG_MISSING:-0}" != "1" ]] || exit 1
    printf 'state = running\npid = 41002\nruns = %s\ngeneration = %s\nlast exit code = %s\nlast terminating signal = %s\ncrash count = %s\n' \
        "${FAKE_SOAK_WATCHDOG_RUNS:-4}" "${FAKE_SOAK_WATCHDOG_GENERATION:-2}" \
        "${FAKE_SOAK_WATCHDOG_EXIT:-0}" "${FAKE_SOAK_WATCHDOG_SIGNAL:-0}" \
        "${FAKE_SOAK_WATCHDOG_CRASHES:-0}"
else
    exit 1
fi
EOF
cat > "$soak_stub_dir/lsof" <<'EOF'
#!/usr/bin/env bash
if [[ -n "${FAKE_SOAK_HOST_CALL_LOG:-}" ]]; then printf 'lsof %s\n' "$*" >> "$FAKE_SOAK_HOST_CALL_LOG"; fi
[[ "${FAKE_SOAK_NO_LISTENER:-0}" == "1" ]] || printf '%s\n' 'n127.0.0.1:18789'
EOF
cat > "$soak_stub_dir/pmset" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${FAKE_SOAK_HOST_CALL_LOG:-}" ]]; then printf 'pmset %s\n' "$*" >> "$FAKE_SOAK_HOST_CALL_LOG"; fi
[[ "$*" == "-g custom" ]] || exit 84
cat <<SETTINGS
AC Power:
 sleep              0
 standby            0
 autorestart        ${FAKE_SOAK_AUTORESTART:-1}
 autorestartatconnect 0
 powernap           ${FAKE_SOAK_POWERNAP:-0}
 lowpowermode       0
 womp               1
 tcpkeepalive       1
SETTINGS
if [[ "${FAKE_SOAK_UPS_PRESENT:-0}" == "1" ]]; then
    printf '%s\n' 'UPS Power:' ' sleep 0'
fi
EOF
cat > "$soak_stub_dir/fdesetup" <<'EOF'
#!/usr/bin/env bash
if [[ -n "${FAKE_SOAK_HOST_CALL_LOG:-}" ]]; then printf 'fdesetup %s\n' "$*" >> "$FAKE_SOAK_HOST_CALL_LOG"; fi
[[ "${1:-}" == "status" ]] || exit 85
printf '%s\n' "FileVault is ${FAKE_SOAK_FILEVAULT:-On}."
EOF
cat > "$soak_stub_dir/stat" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == "-f %Su /dev/console" ]]; then
    if [[ -n "${FAKE_SOAK_HOST_CALL_LOG:-}" ]]; then printf 'stat-console %s\n' "$*" >> "$FAKE_SOAK_HOST_CALL_LOG"; fi
    printf '%s\n' "${FAKE_SOAK_CONSOLE_USER:-$(id -un)}"
else
    exec /usr/bin/stat "$@"
fi
EOF
cat > "$soak_stub_dir/sysctl" <<'EOF'
#!/usr/bin/env bash
if [[ -n "${FAKE_SOAK_HOST_CALL_LOG:-}" ]]; then printf 'sysctl %s\n' "$*" >> "$FAKE_SOAK_HOST_CALL_LOG"; fi
[[ "$*" == "-n kern.boottime" ]] || exit 86
printf '{ sec = %s, usec = 0 } Thu Jan  1 00:00:00 1970\n' \
    "${FAKE_SOAK_BOOT_EPOCH:-1700000000}"
EOF
cat > "$soak_stub_dir/sw_vers" <<'EOF'
#!/usr/bin/env bash
if [[ -n "${FAKE_SOAK_HOST_CALL_LOG:-}" ]]; then printf 'sw_vers %s\n' "$*" >> "$FAKE_SOAK_HOST_CALL_LOG"; fi
[[ "${1:-}" == "-productVersion" ]] || exit 87
printf '%s\n' '26.6.2'
EOF
cat > "$soak_stub_dir/uname" <<'EOF'
#!/usr/bin/env bash
if [[ -n "${FAKE_SOAK_HOST_CALL_LOG:-}" ]]; then printf 'uname %s\n' "$*" >> "$FAKE_SOAK_HOST_CALL_LOG"; fi
case "${1:-}" in
    -s) printf '%s\n' Darwin ;;
    -m) printf '%s\n' arm64 ;;
    *) exec /usr/bin/uname "$@" ;;
esac
EOF
cat > "$soak_stub_dir/sleep" <<'EOF'
#!/usr/bin/env bash
exec /bin/sleep "$@"
EOF
chmod 700 "$soak_stub_dir"/*

tailscale_serve_state="$fixture_root/tailscale-serve-state.json"
printf '%s\n' \
    '{"TCP":{"443":{"HTTPS":true}},"Web":{"owner.tailnet.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:9999"}}}},"AllowFunnel":{"owner.tailnet.ts.net:443":false}}' \
    > "$tailscale_serve_state"
chmod 600 "$tailscale_serve_state"

tailscale_receipt_fixture() (
    export PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root"
    export PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state"
    export PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config"
    export PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace"
    export PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime"
    source "$script_dir/_common.sh"
    openclaw_assert_tailscale_serve_receipt "$1" 18789
)
tailscale_valid_receipt="$fixture_root/tailscale-valid-receipt.json"
tailscale_wrong_port_receipt="$fixture_root/tailscale-wrong-port-receipt.json"
tailscale_wrong_path_receipt="$fixture_root/tailscale-wrong-path-receipt.json"
printf '%s\n' \
    '{"TCP":{"443":{"HTTPS":true}},"Web":{"fixture.tailnet.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:18789"}}}},"AllowFunnel":{"fixture.tailnet.ts.net:443":false}}' \
    > "$tailscale_valid_receipt"
printf '%s\n' \
    '{"TCP":{"443":{"HTTPS":true}},"Web":{"fixture.tailnet.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:43210"}}}},"AllowFunnel":{"fixture.tailnet.ts.net:443":false}}' \
    > "$tailscale_wrong_port_receipt"
printf '%s\n' \
    '{"TCP":{"443":{"HTTPS":true}},"Web":{"fixture.tailnet.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:18789/private"}}}},"AllowFunnel":{"fixture.tailnet.ts.net:443":false}}' \
    > "$tailscale_wrong_path_receipt"
chmod 600 "$tailscale_valid_receipt" "$tailscale_wrong_port_receipt" \
    "$tailscale_wrong_path_receipt"
tailscale_receipt_fixture "$tailscale_valid_receipt"
if tailscale_receipt_fixture "$tailscale_wrong_port_receipt" \
    > "$fixture_root/tailscale-wrong-port.log" 2>&1; then
    fail "Tailscale Serve receipt accepted the wrong loopback backend port"
fi
if tailscale_receipt_fixture "$tailscale_wrong_path_receipt" \
    > "$fixture_root/tailscale-wrong-path.log" 2>&1; then
    fail "Tailscale Serve receipt accepted a backend URL path"
fi

fixture_snapshot() {
    find "$fixture_state" "$fixture_runtime" "$fixture_launch_agents" "$fixture_node_dir" \
        -type f -print | LC_ALL=C sort | while IFS= read -r file_path; do
            printf '%s %s\n' "$(shasum -a 256 "$file_path" | awk '{print $1}')" "$file_path"
        done | shasum -a 256 | awk '{print $1}'
}
before_dry_run="$(fixture_snapshot)"

mkdir -p "$fixture_root/diagnostic-tmp"
chmod 700 "$fixture_root/diagnostic-tmp"
cp "$service_environment" "$fixture_root/canonical-service-environment.env"
awk '
    /^export NODE_OPTIONS=/ { print "export NODE_OPTIONS=\047--require /tmp/injected.js\047"; next }
    { print }
' "$service_environment" > "$fixture_root/unsafe-service-environment.env"
/bin/mv "$fixture_root/unsafe-service-environment.env" "$service_environment"
chmod 600 "$service_environment"
if env \
    TMPDIR="$fixture_root/diagnostic-tmp" \
    PATH="$stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$script_dir/adopt-existing.sh" --dry-run \
        > "$fixture_root/unsafe-service-environment.log" 2>&1; then
    fail "adoption accepted an executable NODE_OPTIONS service injection"
fi
/bin/mv "$fixture_root/canonical-service-environment.env" "$service_environment"
chmod 600 "$service_environment"

env \
    PATH="$stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    FAKE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$script_dir/harden-existing.sh" --dry-run > "$fixture_root/harden-dry-run.log"
grep -Fq 'DRY-RUN no config' "$fixture_root/harden-dry-run.log" ||
    fail "hardening fixture did not complete dry-run"
[[ "$before_dry_run" == "$(fixture_snapshot)" ]] ||
    fail "hardening dry-run changed fixture protected bytes"

# A planner rejection must remain content-free on the console while pointing the owner to the
# private stderr receipt. Keep its deliberately retained recovery snapshot inside the fixture.
if env \
    TMPDIR="$fixture_root/diagnostic-tmp" \
    PATH="$stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    FAKE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    FAKE_CONFIG_PATCH_DRY_RUN_FAIL=1 \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$script_dir/harden-existing.sh" --dry-run \
        > "$fixture_root/harden-dry-run-rejection.log" 2>&1; then
    fail "hardening planner rejection fixture unexpectedly succeeded"
fi
grep -Fq 'FAIL OpenClaw config patch dry-run failed; private diagnostics:' \
    "$fixture_root/harden-dry-run-rejection.log" ||
    fail "hardening planner rejection did not emit a content-free diagnostic pointer"
if grep -Fq 'fixture private config patch detail' "$fixture_root/harden-dry-run-rejection.log"; then
    fail "hardening planner rejection leaked private stderr to the console"
fi

cp "$fixture_config" "$fixture_root/pre-failed-harden-openclaw.json"
if env \
    PATH="$stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    FAKE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    FAKE_CONFIG_PATCH_FAIL_AFTER_WRITE=1 \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$script_dir/harden-existing.sh" --apply > "$fixture_root/harden-failure.log" 2>&1; then
    fail "hardening failure fixture unexpectedly succeeded"
fi
cmp -s "$fixture_root/pre-failed-harden-openclaw.json" "$fixture_config" ||
    fail "failed hardening did not restore original config bytes"

env \
    PATH="$stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$script_dir/adopt-existing.sh" --dry-run > "$fixture_root/adopt-dry-run.log"
grep -Fq 'DRY-RUN no script-authored installed-state write' "$fixture_root/adopt-dry-run.log" ||
    fail "adopt fixture did not complete dry-run"
[[ "$before_dry_run" == "$(fixture_snapshot)" ]] ||
    fail "adopt dry-run changed fixture protected bytes"
[[ ! -e "$fixture_runtime/.personal-edge-management" ]] || fail "dry-run installed management state"

printf '%s\n' 'owner workspace fixture' > "$fixture_workspace/BOOTSTRAP.md"
cp "$fixture_config" "$fixture_root/pre-failed-adopt-openclaw.json"
cp "$fixture_state/state/openclaw.sqlite" "$fixture_root/pre-failed-adopt-global.sqlite"
cp "$fixture_state/agents/main/agent/openclaw-agent.sqlite" \
    "$fixture_root/pre-failed-adopt-agent.sqlite"
if env \
    PATH="$stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    FAKE_MV_FAIL_RESTRICTED_WORKSPACE=1 \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$script_dir/adopt-existing.sh" --apply --acknowledge-transient-session-write \
        > "$fixture_root/adopt-failure.log" 2>&1; then
    fail "adoption failure fixture unexpectedly succeeded"
fi
cmp -s "$fixture_root/pre-failed-adopt-openclaw.json" "$fixture_config" ||
    fail "failed adoption changed config bytes"
cmp -s "$fixture_root/pre-failed-adopt-global.sqlite" "$fixture_state/state/openclaw.sqlite" ||
    fail "failed adoption changed shared credential database bytes"
cmp -s "$fixture_root/pre-failed-adopt-agent.sqlite" \
    "$fixture_state/agents/main/agent/openclaw-agent.sqlite" ||
    fail "failed adoption changed auth-profile database bytes"
grep -Fxq 'owner workspace fixture' "$fixture_workspace/BOOTSTRAP.md" ||
    fail "failed adoption did not restore the original workspace"
[[ -z "$(find "$fixture_state" -maxdepth 1 -name '.workspace-personal-edge-adopt-*' -print -quit)" ]] ||
    fail "failed adoption left a live restricted-workspace staging directory"
[[ -n "$(find "$fixture_state/quarantine" -type f \
    -path '*/restricted-workspace-staging-failed/AGENTS.md' -print -quit)" ]] ||
    fail "failed adoption did not quarantine the interrupted restricted-workspace staging tree"
[[ ! -e "$fixture_runtime/.personal-edge-management" ]] ||
    fail "failed adoption left a live management directory"
[[ ! -e "$fixture_launch_agents/com.personaledge.openclaw-watchdog.plist" ]] ||
    fail "failed adoption left a live watchdog plist"
[[ ! -e "$fixture_runtime/.personal-edge-management/deployment.json" ]] ||
    fail "failed adoption published a deployment manifest"

# A later failure happens after the restricted workspace generation was loaded. Cleanup must
# restore the owner workspace and explicitly reload that generation before it can return success.
sleep 1
gateway_restart_log="$fixture_root/gateway-restart.log"
if env \
    PATH="$stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    FAKE_GATEWAY_RESTART_LOG="$gateway_restart_log" \
    FAKE_LAUNCHCTL_FAIL_BOOTSTRAP=1 \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$script_dir/adopt-existing.sh" --apply --acknowledge-transient-session-write \
        > "$fixture_root/adopt-post-restart-failure.log" 2>&1; then
    fail "post-restart adoption failure fixture unexpectedly succeeded"
fi
[[ "$(wc -l < "$gateway_restart_log" | tr -d ' ')" == "2" ]] ||
    fail "failed adoption did not reload the restored owner-workspace Gateway generation"
grep -Fxq 'owner workspace fixture' "$fixture_workspace/BOOTSTRAP.md" ||
    fail "post-restart adoption failure did not restore the original workspace"
[[ ! -e "$fixture_runtime/.personal-edge-management" ]] ||
    fail "post-restart adoption failure left a live management directory"

# Exercise the real loader, including its exact `jq keys` gate. Start from the unpublished
# candidate because launchd RunAtLoad must be able to verify that authority without weakening the
# manifest-last publication contract.
manifest_roundtrip_fixture() (
    export PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root"
    export PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state"
    export PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config"
    export PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace"
    export PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime"
    export PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management"
    export PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups"
    export PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents"
    export PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node"
    # shellcheck source=_common.sh
    source "$script_dir/_common.sh"
    openclaw_prepare_private_dir "$openclaw_management_root" "fixture management root"
    openclaw_prepare_private_dir "$openclaw_management_root/bin" "fixture management bin"
    openclaw_prepare_private_dir "$openclaw_management_root/libexec" "fixture management libexec"
    openclaw_write_managed_wrapper "$fixture_node"
    cp "$script_dir/_common.sh" "$openclaw_management_common"
    cp "$script_dir/watchdog.sh" "$openclaw_management_watchdog"
    chmod 600 "$openclaw_management_common"
    chmod 700 "$openclaw_management_watchdog"
    openclaw_write_deployment_manifest adopted-homebrew-node "$fixture_node" \
        "$openclaw_management_root/.deployment.candidate.json"
    openclaw_deployment_manifest="$openclaw_management_root/.deployment.candidate.json"
    openclaw_load_deployment
    printf '%s\n' 'manifest-loader-round-trip-pass'
)
manifest_roundtrip_fixture > "$fixture_root/manifest-round-trip.log"
grep -Fxq 'manifest-loader-round-trip-pass' "$fixture_root/manifest-round-trip.log" ||
    fail "deployment manifest did not pass the real loader round trip"
[[ ! -e "$fixture_runtime/.personal-edge-management/deployment.json" ]] ||
    fail "candidate manifest fixture published the final marker early"

# Reproduce launchd RunAtLoad before final publication. The managed watchdog must select and fully
# validate the candidate rather than emitting a misleading final-manifest-missing failure.
watchdog_candidate_fixture_json="$(jq -c --arg launchAgentDir "$fixture_launch_agents" \
    '.EnvironmentVariables.PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR=$launchAgentDir' \
    <<< "$watchdog_fixture_json")"
printf '%s\n' "$watchdog_candidate_fixture_json" |
    plutil -convert xml1 -o "$fixture_launch_agents/com.personaledge.openclaw-watchdog.plist" -- -
chmod 600 "$fixture_launch_agents/com.personaledge.openclaw-watchdog.plist"
watchdog_tmp="$fixture_root/watchdog-tmp"
mkdir "$watchdog_tmp"
chmod 700 "$watchdog_tmp"

# A candidate is never enough merely because it occupies the expected path. Corrupt one pinned
# management hash and prove the watchdog exits before reaching any launchctl recovery command.
candidate_manifest_path="$fixture_runtime/.personal-edge-management/.deployment.candidate.json"
cp "$candidate_manifest_path" "$fixture_root/valid-deployment-candidate.json"
jq '.watchdogSha256=("0" * 64)' "$candidate_manifest_path" \
    > "$fixture_root/invalid-deployment-candidate.json"
/bin/mv "$fixture_root/invalid-deployment-candidate.json" "$candidate_manifest_path"
chmod 600 "$candidate_manifest_path"
candidate_launchctl_log="$fixture_root/invalid-candidate-launchctl.log"
if env \
    TMPDIR="$watchdog_tmp" \
    PATH="$stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    FAKE_LAUNCHCTL_CALL_LOG="$candidate_launchctl_log" \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$fixture_runtime/.personal-edge-management/libexec/watchdog.sh" \
        > "$fixture_root/watchdog-invalid-candidate.log" 2>&1; then
    fail "watchdog accepted a candidate with a drifted management hash"
fi
[[ ! -s "$candidate_launchctl_log" ]] ||
    fail "watchdog attempted launchd recovery before fully validating its candidate"
/bin/mv "$fixture_root/valid-deployment-candidate.json" "$candidate_manifest_path"
chmod 600 "$candidate_manifest_path"

# Even a fully valid candidate cannot authorize recovery before final publication. Adoption has
# its own bounded Gateway restart; the RunAtLoad watchdog remains observation-only in this window.
candidate_health_launchctl_log="$fixture_root/candidate-health-launchctl.log"
if env \
    TMPDIR="$watchdog_tmp" \
    PATH="$stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    FAKE_GATEWAY_HEALTH_FAIL=1 \
    FAKE_LAUNCHCTL_CALL_LOG="$candidate_health_launchctl_log" \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$fixture_runtime/.personal-edge-management/libexec/watchdog.sh" \
        > "$fixture_root/watchdog-candidate-unhealthy.log" 2>&1; then
    fail "candidate watchdog reported success for an unhealthy Gateway"
fi
grep -Fq 'recovery is withheld until final manifest publication' \
    "$fixture_root/watchdog-candidate-unhealthy.log" ||
    fail "candidate watchdog did not report its pre-publication recovery boundary"
if [[ -s "$candidate_health_launchctl_log" ]] && rg -v '^print ' "$candidate_health_launchctl_log" >/dev/null; then
    fail "candidate watchdog re-armed launchd before final manifest publication"
fi

env \
    TMPDIR="$watchdog_tmp" \
    PATH="$stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$fixture_runtime/.personal-edge-management/libexec/watchdog.sh" \
        > "$fixture_root/watchdog-candidate.log" 2>&1
grep -Fq 'OK gateway and Docker healthy with restrictive config' "$fixture_root/watchdog-candidate.log" ||
    fail "RunAtLoad watchdog could not verify the unpublished candidate manifest"

/bin/mv "$fixture_runtime/.personal-edge-management/.deployment.candidate.json" \
    "$fixture_runtime/.personal-edge-management/deployment.json"
jq -e '
    .schemaVersion == 4 and
    (.wrapperSha256 | test("^[a-f0-9]{64}$")) and
    (.commonSha256 | test("^[a-f0-9]{64}$")) and
    (.watchdogSha256 | test("^[a-f0-9]{64}$"))
' "$fixture_runtime/.personal-edge-management/deployment.json" >/dev/null ||
    fail "deployment manifest does not pin every management executable"

manifest_load_fixture() (
    export PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root"
    export PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state"
    export PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config"
    export PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace"
    export PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime"
    export PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management"
    export PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node"
    source "$script_dir/_common.sh"
    openclaw_load_deployment
)
cp "$fixture_runtime/.personal-edge-management/deployment.json" \
    "$fixture_root/single-object-deployment.json"
printf '%s\n' '{}' >> "$fixture_runtime/.personal-edge-management/deployment.json"
if manifest_load_fixture > "$fixture_root/multiple-manifest-objects.log" 2>&1; then
    fail "deployment loader accepted multiple JSON values in one manifest"
fi
/bin/mv "$fixture_root/single-object-deployment.json" \
    "$fixture_runtime/.personal-edge-management/deployment.json"
chmod 600 "$fixture_runtime/.personal-edge-management/deployment.json"

printf '%s\n' '# fixture management drift' >> \
    "$fixture_runtime/.personal-edge-management/libexec/_common.sh"
if manifest_load_fixture > "$fixture_root/management-drift.log" 2>&1; then
    fail "deployment loader accepted a drifted management common library"
fi
cp "$script_dir/_common.sh" "$fixture_runtime/.personal-edge-management/libexec/_common.sh"
chmod 600 "$fixture_runtime/.personal-edge-management/libexec/_common.sh"
manifest_load_fixture

# Doctor lint exits 1 on warnings in OpenClaw 2026.8.1. Preserve the warning receipt and still
# reach the deep security audit, but never accept malformed output, error findings, or CLI failure.
run_security_audit_fixture() {
    env \
        FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
        FAKE_OPENCLAW_CALL_LOG="$fixture_root/doctor-$1.calls" \
        FAKE_DOCTOR_RESULT="$1" \
        PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
        PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
        PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
        PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
        PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
        PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
        PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
        PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
        PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
        "$script_dir/security-audit.sh" --deep
}
for doctor_case in clean warning error malformed mismatch multiple fatal unknown-severity; do
    doctor_result=0
    run_security_audit_fixture "$doctor_case" > "$fixture_root/doctor-$doctor_case.log" 2>&1 ||
        doctor_result=$?
    case "$doctor_case" in
        clean|warning)
            [[ "$doctor_result" == 0 ]] || fail "doctor $doctor_case blocked security audit"
            grep -Fq 'security audit --deep --json' "$fixture_root/doctor-$doctor_case.calls" ||
                fail "doctor $doctor_case did not reach deep security audit"
            ;;
        *)
            [[ "$doctor_result" != 0 ]] || fail "doctor $doctor_case unexpectedly passed"
            if grep -Fq 'security audit' "$fixture_root/doctor-$doctor_case.calls"; then
                fail "doctor $doctor_case continued despite invalid/fatal doctor result"
            fi
            ;;
    esac
    if grep -Fq 'fixture private' "$fixture_root/doctor-$doctor_case.log"; then
        fail "doctor $doctor_case leaked private findings or stderr"
    fi
    if [[ "$doctor_case" == warning ]]; then
        grep -Fq '"warningCount":1,"errorCount":0,"authProfileWarningCount":1' \
            "$fixture_root/doctor-warning.log" || fail "doctor warning counts were not reported"
        grep -Fq 'WARN doctor auth-profile warning remains' "$fixture_root/doctor-warning.log" ||
            fail "doctor auth warning was silently waived"
        doctor_warning_receipt="$(sed -n 's/^WARN doctor lint warnings.*Private receipt: //p' \
            "$fixture_root/doctor-warning.log")"
        jq -e '.findings[0].requirement == "missing_credential"' "$doctor_warning_receipt" >/dev/null ||
            fail "doctor warning receipt lost the credential finding"
        [[ "$(stat -f '%Lp' "$doctor_warning_receipt")" == 600 &&
           "$(stat -f '%Lp' "$doctor_warning_receipt.err")" == 600 ]] ||
            fail "doctor warning receipts are not private"
    fi
done

# If final publication races the watchdog between candidate selection and manifest read, the
# loader may fall back only from that exact candidate path to the now-present final manifest.
candidate_transition_load_fixture() (
    export PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root"
    export PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state"
    export PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config"
    export PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace"
    export PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime"
    export PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management"
    export PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node"
    export PERSONAL_EDGE_OPENCLAW_DEPLOYMENT_MANIFEST="$fixture_runtime/.personal-edge-management/.deployment.candidate.json"
    source "$script_dir/_common.sh"
    openclaw_load_deployment
)
candidate_transition_load_fixture

# A private command failure must identify its stage without printing private stderr, and retain
# the mode-700 receipt directory so the owner can diagnose a late adoption failure.
owner_bootstrap_saved="$fixture_root/owner-bootstrap.saved"
/bin/mv "$fixture_workspace/BOOTSTRAP.md" "$owner_bootstrap_saved"
verify_diagnostics_root="$fixture_root/verify-diagnostics"
mkdir "$verify_diagnostics_root"
chmod 700 "$verify_diagnostics_root"
if env \
    TMPDIR="$verify_diagnostics_root" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    FAKE_MODELS_STATUS_FAIL=1 \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$script_dir/verify-gateway.sh" --skip-live \
        > "$fixture_root/verify-private-failure.log" 2>&1; then
    fail "verification private-command failure unexpectedly passed"
fi
grep -Fq 'FAIL model status command failed; private diagnostics:' \
    "$fixture_root/verify-private-failure.log" ||
    fail "verification failure did not identify its content-free stage"
grep -Fq 'WARN private verification diagnostics retained:' \
    "$fixture_root/verify-private-failure.log" ||
    fail "verification failure did not retain its private receipt directory"
if grep -Fq 'fixture private model-status diagnostic' "$fixture_root/verify-private-failure.log"; then
    fail "verification failure leaked private stderr to the console"
fi
retained_verify_dir="$(find "$verify_diagnostics_root" -mindepth 1 -maxdepth 1 -type d \
    -name 'personal-edge-openclaw-verify.*' -print -quit)"
[[ -n "$retained_verify_dir" && -f "$retained_verify_dir/models.err" ]] ||
    fail "verification failure removed its private model-status receipt"
grep -Fq 'fixture private model-status diagnostic' "$retained_verify_dir/models.err" ||
    fail "retained verification receipt did not preserve the command diagnostic"

# A successful sessions.create transport can still return an arbitrary untrusted key. Verification
# must fail and clean only its exact requested key; deleting the returned key could destroy an
# unrelated owner session.
verify_mismatch_root="$fixture_root/verify-mismatch"
mkdir "$verify_mismatch_root"
chmod 700 "$verify_mismatch_root"
zero_session_delete_log="$fixture_root/zero-session-delete.log"
if env \
    TMPDIR="$verify_mismatch_root" \
    PATH="$stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    FAKE_WATCHDOG_LOADED=1 \
    FAKE_ZERO_SESSION_RETURN_OWNER=1 \
    FAKE_SESSION_DELETE_LOG="$zero_session_delete_log" \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$script_dir/verify-gateway.sh" --acknowledge-transient-session-write \
        > "$fixture_root/verify-session-mismatch.log" 2>&1; then
    fail "verification accepted an unbound returned session key"
fi
grep -Fq 'unbound returned session key was not auto-deleted' \
    "$fixture_root/verify-session-mismatch.log" ||
    fail "verification did not report its safe unbound-session cleanup boundary"
[[ "$(wc -l < "$zero_session_delete_log" | tr -d ' ')" == "1" ]] ||
    fail "unbound-session cleanup did not issue exactly one bounded deletion"
grep -Eq '^agent:main:dashboard:incognito-personal-edge-verify-zero-tools-[0-9]{8}t[0-9]{6}z-[0-9]+$' \
    "$zero_session_delete_log" ||
    fail "unbound-session cleanup targeted something other than its requested synthetic key"
if grep -Fq 'agent:main:owner-existing-session' "$zero_session_delete_log"; then
    fail "unbound-session cleanup attempted to delete an owner session returned by the server"
fi

verify_success_root="$fixture_root/verify-success"
mkdir "$verify_success_root"
chmod 700 "$verify_success_root"
env \
    TMPDIR="$verify_success_root" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$script_dir/verify-gateway.sh" --skip-live \
        > "$fixture_root/verify-success.log" 2>&1
[[ -z "$(find "$verify_success_root" -mindepth 1 -maxdepth 1 -type d \
    -name 'personal-edge-openclaw-verify.*' -print -quit)" ]] ||
    fail "successful verification retained a private receipt directory"

# Full verification and adoption apply must stop before touching the profile unless the owner
# separately acknowledges their transient incognito session write.
if "$script_dir/verify-gateway.sh" > "$fixture_root/verify-no-session-ack.log" 2>&1; then
    fail "full verification ran without transient-session acknowledgement"
fi
grep -Fq -- '--acknowledge-transient-session-write' "$fixture_root/verify-no-session-ack.log" ||
    fail "full verification refusal did not name the acknowledgement flag"
if "$script_dir/install-gateway.sh" --adopt-existing --apply \
    > "$fixture_root/adopt-no-session-ack.log" 2>&1; then
    fail "adoption apply ran without transient-session acknowledgement"
fi
grep -Fq -- '--acknowledge-transient-session-write' "$fixture_root/adopt-no-session-ack.log" ||
    fail "adoption acknowledgement refusal was not explicit"

# The soak lane is intentionally narrower than full verification. It may read static policy and
# live health, but it must not run the secret audit (which may permission-harden), create a
# session, invoke a model, restart/repair anything, or call Tailscale when it is absent.
soak_tmp="$fixture_root/soak-tmp"
mkdir "$soak_tmp"
chmod 700 "$soak_tmp"
soak_session_log="$fixture_root/soak-session-create.log"
soak_restart_log="$fixture_root/soak-restart.log"
soak_openclaw_log="$fixture_root/soak-openclaw-calls.log"
soak_host_log="$fixture_root/soak-host-calls.log"
run_soak_fixture() {
    env \
        TMPDIR="$soak_tmp" \
        PATH="${FAKE_SOAK_PATH:-$soak_stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin}" \
        FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
        FAKE_OPENCLAW_CONFIG_PATH="$fixture_config" \
        FAKE_SESSION_CREATE_LOG="$soak_session_log" \
        FAKE_GATEWAY_RESTART_LOG="$soak_restart_log" \
        FAKE_OPENCLAW_CALL_LOG="${FAKE_OPENCLAW_CALL_LOG:-$soak_openclaw_log}" \
        FAKE_SOAK_HOST_CALL_LOG="${FAKE_SOAK_HOST_CALL_LOG:-$soak_host_log}" \
        FAKE_SOAK_BOOT_EPOCH="${FAKE_SOAK_BOOT_EPOCH:-1700000000}" \
        FAKE_SOAK_GATEWAY_MISSING="${FAKE_SOAK_GATEWAY_MISSING:-0}" \
        FAKE_SOAK_WATCHDOG_MISSING="${FAKE_SOAK_WATCHDOG_MISSING:-0}" \
        PERSONAL_EDGE_OPENCLAW_SOAK_FIXTURE_MODE=1 \
        PERSONAL_EDGE_OPENCLAW_SOAK_FIXTURE_TAILSCALE_ABSENT=1 \
        PERSONAL_EDGE_OPENCLAW_SOAK_FIXTURE_STOP_AFTER_SAMPLES="${PERSONAL_EDGE_OPENCLAW_SOAK_FIXTURE_STOP_AFTER_SAMPLES:-0}" \
        PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
        PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
        PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
        PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
        PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
        PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
        PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
        PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
        PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
        "$@"
}

stale_start_raw="$soak_tmp/personal-edge-openclaw-soak-sample.soak-20200101T000000Z-999999.runner-999999.ABC123"
mkdir "$stale_start_raw"
chmod 700 "$stale_start_raw"
printf '%s\n' 'fixture raw host metadata' > "$stale_start_raw/gateway.launchctl"
chmod 600 "$stale_start_raw/gateway.launchctl"
# Real CLI/SQLite/Docker fixture observations can take several seconds under host load.
# The target remains short; a ten-second interval avoids testing machine speed against a
# three-second continuity allowance. Production five-minute continuity limits are unchanged.
soak_receipt="$fixture_root/backups/soak-explicit-fixture"
run_soak_fixture "$script_dir/soak-acceptance.sh" --start \
    --receipt-dir "$soak_receipt" --duration-seconds 1 --interval-seconds 10 \
    > "$fixture_root/soak-short.log" 2>&1
run_soak_fixture "$script_dir/soak-acceptance.sh" --validate --receipt-dir "$soak_receipt" \
    > "$fixture_root/soak-validate.log" 2>&1
[[ ! -e "$stale_start_raw" ]] || fail "soak start did not sweep an owned stale raw temporary directory"
grep -Fq "START observation-only soak receipt=$soak_receipt" "$fixture_root/soak-short.log" ||
    fail "soak start did not publish its exact resumable receipt path"
jq -e '
    .acceptanceScope == "observed-window-only" and
    .availabilityClaim == "not-established-beyond-observed-window" and
    .result == "diagnostic" and .status == "complete" and
    (.receiptId | test("^soak-[0-9]{8}T[0-9]{6}Z-[0-9]+$")) and
    .summary.sampleCount == 2 and .summary.initialGateSampleCount == 1 and
    .summary.finalGateSampleCount == 1 and .summary.phaseOrderValid and
    .summary.targetReached and .summary.observedCoverageSeconds >= 1 and
    .limitations == [
      "filevault-owner-unlock-boundary", "login-session-launchagent-boundary",
      "power-settings-do-not-prove-power-continuity", "single-mac-no-redundancy",
      "ups-not-required-by-this-acceptance"
    ]
' "$soak_receipt/manifest.json" >/dev/null || fail "short soak receipt contract is invalid"
jq -s -e '
    length == 2 and .[0].index == 0 and .[0].phase == "initial" and
    .[1].index == 1 and .[1].phase == "final" and
    all(.[]; .tailscale == {
      availability:"not-installed",acceptance:"pending",mode:"off",
      backendRunning:null,selfOnline:null,serveRouteValid:null
    }) and
    .[0].power.upsProfilePresent == false
' "$soak_receipt/samples"/*.json >/dev/null || fail "short soak sample journal is invalid"
[[ "$(stat -f '%Lp' "$soak_receipt")" == "700" &&
   "$(stat -f '%Lp' "$soak_receipt/samples")" == "700" &&
   "$(stat -f '%Lp' "$soak_receipt/manifest.json")" == "600" &&
   "$(stat -f '%Lp' "$soak_receipt/.active.lock")" == "600" ]] ||
    fail "soak receipt permissions are not private"
[[ ! -s "$soak_session_log" && ! -s "$soak_restart_log" ]] ||
    fail "observation-only soak created a session or restarted the Gateway"
if rg -n 'config validate|secrets audit|plugins list|models status|gateway call| agent |gateway restart|gateway repair|backup create|config set' \
    "$soak_openclaw_log" >/dev/null; then
    fail "observation-only soak reached a mutating or inference-capable CLI lane"
fi

# Validation is receipt-only: no profile CLI, launchd, power, login, FileVault, or Tailscale probe.
validate_host_log="$fixture_root/soak-validate-host.log"
validate_openclaw_log="$fixture_root/soak-validate-openclaw.log"
FAKE_SOAK_HOST_CALL_LOG="$validate_host_log" FAKE_OPENCLAW_CALL_LOG="$validate_openclaw_log" \
    run_soak_fixture "$script_dir/soak-acceptance.sh" --validate --receipt-dir "$soak_receipt" \
        > "$fixture_root/soak-revalidate.log" 2>&1
[[ ! -s "$validate_host_log" && ! -s "$validate_openclaw_log" ]] ||
    fail "receipt validation accessed live host/profile state"

copy_soak_receipt() {
    local source_receipt="$1"
    local target_receipt="$2"
    cp -R "$source_receipt" "$target_receipt"
    find "$target_receipt" -type d -exec chmod 700 {} +
    find "$target_receipt" -type f -exec chmod 600 {} +
}
mutate_soak_json() {
    local target="$1"
    local filter="$2"
    local temporary="${target}.fixture-partial"
    jq "$filter" "$target" > "$temporary"
    /bin/mv "$temporary" "$target"
    chmod 600 "$target"
}
expect_invalid_soak_receipt() {
    local target_receipt="$1"
    local label="$2"
    if run_soak_fixture "$script_dir/soak-acceptance.sh" --validate \
        --receipt-dir "$target_receipt" > "$fixture_root/$label.log" 2>&1; then
        fail "soak validator accepted $label"
    fi
}

# Schema 2 binds the daemon and VM generations to the same validated sample journal.
for runtime_tamper in readiness generation missing-runtime legacy; do
    runtime_receipt="$fixture_root/backups/soak-runtime-$runtime_tamper"
    copy_soak_receipt "$soak_receipt" "$runtime_receipt"
    case "$runtime_tamper" in
        readiness) mutate_soak_json "$runtime_receipt/samples/000000001.json" '.runtime.dockerResponsive=false' ;;
        generation) mutate_soak_json "$runtime_receipt/samples/000000001.json" '.runtime.daemonGeneration=("a" * 64)' ;;
        missing-runtime) mutate_soak_json "$runtime_receipt/samples/000000001.json" 'del(.runtime)' ;;
        legacy) mutate_soak_json "$runtime_receipt/manifest.json" '.schemaVersion=1' ;;
    esac
    expect_invalid_soak_receipt "$runtime_receipt" "runtime-$runtime_tamper"
done

summary_tamper="$fixture_root/backups/soak-summary-tamper"
copy_soak_receipt "$soak_receipt" "$summary_tamper"
mutate_soak_json "$summary_tamper/manifest.json" '.summary.unreviewed=true'
expect_invalid_soak_receipt "$summary_tamper" summary-tamper

result_tamper="$fixture_root/backups/soak-result-tamper"
copy_soak_receipt "$soak_receipt" "$result_tamper"
mutate_soak_json "$result_tamper/manifest.json" '.result="observed-window-pass"'
expect_invalid_soak_receipt "$result_tamper" result-tamper

pointer_tamper="$fixture_root/backups/soak-pointer-tamper"
copy_soak_receipt "$soak_receipt" "$pointer_tamper"
mutate_soak_json "$pointer_tamper/manifest.json" '.lastBootEpoch += 1'
expect_invalid_soak_receipt "$pointer_tamper" pointer-tamper

gap_tamper="$fixture_root/backups/soak-gap-tamper"
copy_soak_receipt "$soak_receipt" "$gap_tamper"
mutate_soak_json "$gap_tamper/samples/000000001.json" '.gapSeconds += 1'
expect_invalid_soak_receipt "$gap_tamper" gap-tamper

delta_tamper="$fixture_root/backups/soak-delta-tamper"
copy_soak_receipt "$soak_receipt" "$delta_tamper"
mutate_soak_json "$delta_tamper/samples/000000001.json" '.delta.bootChanged=true'
expect_invalid_soak_receipt "$delta_tamper" delta-tamper

boundary_tamper="$fixture_root/backups/soak-boundary-tamper"
copy_soak_receipt "$soak_receipt" "$boundary_tamper"
mutate_soak_json "$boundary_tamper/samples/000000001.json" '.gates.executed=false'
expect_invalid_soak_receipt "$boundary_tamper" boundary-tamper

power_tamper="$fixture_root/backups/soak-power-tamper"
copy_soak_receipt "$soak_receipt" "$power_tamper"
mutate_soak_json "$power_tamper/samples/000000001.json" '.power.ac.autorestart=0'
expect_invalid_soak_receipt "$power_tamper" power-tamper

tailscale_tamper="$fixture_root/backups/soak-tailscale-tamper"
copy_soak_receipt "$soak_receipt" "$tailscale_tamper"
mutate_soak_json "$tailscale_tamper/samples/000000001.json" '.tailscale.acceptance="pass"'
expect_invalid_soak_receipt "$tailscale_tamper" tailscale-tamper

created_time_tamper="$fixture_root/backups/soak-created-time-tamper"
copy_soak_receipt "$soak_receipt" "$created_time_tamper"
mutate_soak_json "$created_time_tamper/manifest.json" '.createdAt="1970-01-01T00:00:00Z"'
expect_invalid_soak_receipt "$created_time_tamper" created-time-tamper

observed_time_tamper="$fixture_root/backups/soak-observed-time-tamper"
copy_soak_receipt "$soak_receipt" "$observed_time_tamper"
mutate_soak_json "$observed_time_tamper/samples/000000001.json" \
    '.observedAt="1970-01-01T00:00:00Z"'
expect_invalid_soak_receipt "$observed_time_tamper" observed-time-tamper

completion_order_tamper="$fixture_root/backups/soak-completion-order-tamper"
copy_soak_receipt "$soak_receipt" "$completion_order_tamper"
mutate_soak_json "$completion_order_tamper/manifest.json" \
    '.completedEpoch=0 | .completedAt="1970-01-01T00:00:00Z"'
expect_invalid_soak_receipt "$completion_order_tamper" completion-order-tamper

# Validation may remove only an exact receipt-owned raw temp left by a killed runner. It does not
# touch live host/profile state or a raw directory belonging to another receipt.
soak_receipt_id="$(jq -r '.receiptId' "$soak_receipt/manifest.json")"
stale_validate_raw="$soak_tmp/personal-edge-openclaw-soak-sample.$soak_receipt_id.runner-999999.DEF456"
unrelated_raw="$soak_tmp/personal-edge-openclaw-soak-sample.soak-20200101T000000Z-888888.runner-999999.GHI789"
mkdir "$stale_validate_raw" "$unrelated_raw"
chmod 700 "$stale_validate_raw" "$unrelated_raw"
run_soak_fixture "$script_dir/soak-acceptance.sh" --validate --receipt-dir "$soak_receipt" \
    > "$fixture_root/soak-stale-raw-validate.log" 2>&1
[[ ! -e "$stale_validate_raw" && -d "$unrelated_raw" ]] ||
    fail "soak validation stale-raw sweep escaped its receipt"

# Host-readiness, login, FileVault, and UPS observations are explicit advisory/limitations data.
# They do not become an unconditional availability claim or silently change the sample outcome.
advisory_receipt="$fixture_root/backups/soak-advisory-boundary"
copy_soak_receipt "$soak_receipt" "$advisory_receipt"
for advisory_sample in "$advisory_receipt/samples/000000000.json" \
    "$advisory_receipt/samples/000000001.json"; do
    mutate_soak_json "$advisory_sample" \
        '.gates.advisory.hostReadiness=false | .fileVault="off" | .userSessionMatches=false'
done
run_soak_fixture "$script_dir/soak-acceptance.sh" --validate --receipt-dir "$advisory_receipt" \
    > "$fixture_root/soak-advisory-boundary.log" 2>&1

# Missing launchd jobs must become bounded `missing` service objects and a failed receipt, not an
# aborted collector with raw launchctl output.
missing_job_receipt="$fixture_root/backups/soak-missing-jobs"
if FAKE_SOAK_GATEWAY_MISSING=1 FAKE_SOAK_WATCHDOG_MISSING=1 run_soak_fixture \
    "$script_dir/soak-acceptance.sh" --start --receipt-dir "$missing_job_receipt" \
    --duration-seconds 1 --interval-seconds 10 > "$fixture_root/soak-missing-jobs.log" 2>&1; then
    fail "soak unexpectedly passed with missing launchd jobs"
fi
jq -e '.gateway == {loaded:false,state:"missing",pid:null,runs:null,generation:null,
    lastExitCode:null,lastTerminatingSignal:null,crashCount:null} and
    .watchdog == {loaded:false,state:"missing",pid:null,runs:null,generation:null,
    lastExitCode:null,lastTerminatingSignal:null,crashCount:null}' \
    "$missing_job_receipt/samples/000000000.json" >/dev/null ||
    fail "missing launchd jobs were not serialized safely"
run_soak_fixture "$script_dir/soak-acceptance.sh" --validate --receipt-dir "$missing_job_receipt" \
    > "$fixture_root/soak-missing-jobs-validate.log" 2>&1

# A zero-sample interruption restarts the observation window at a new initial gate. It cannot use
# receipt creation time as fake coverage.
zero_sample_receipt="$fixture_root/backups/soak-zero-sample-resume"
copy_soak_receipt "$soak_receipt" "$zero_sample_receipt"
rm -f -- "$zero_sample_receipt/samples"/*.json
mutate_soak_json "$zero_sample_receipt/manifest.json" '
    .nextSampleIndex=0 | .lastSampleIndex=null | .lastSampleEpoch=null | .lastBootEpoch=null |
    .resumeCount=0 | .journalRecoveryCount=0 | .status="interrupted" | .result="not-complete" |
    .completedAt=null | .completedEpoch=null | .summary=null
'
run_soak_fixture "$script_dir/soak-acceptance.sh" --resume --receipt-dir "$zero_sample_receipt" \
    > "$fixture_root/soak-zero-sample-resume.log" 2>&1
jq -s -e 'length == 2 and .[0].phase == "initial" and .[1].phase == "final"' \
    "$zero_sample_receipt/samples"/*.json >/dev/null ||
    fail "zero-sample resume skipped or shortened its new initial observation window"

# Reconcile exactly one fsynced sample that reached the journal before its manifest pointer, then
# resume across a boot-epoch change. The reboot remains a failed continuity receipt.
orphan_receipt="$fixture_root/backups/soak-orphan-reboot"
set +e
PERSONAL_EDGE_OPENCLAW_SOAK_FIXTURE_STOP_AFTER_SAMPLES=1 run_soak_fixture \
    "$script_dir/soak-acceptance.sh" --start --receipt-dir "$orphan_receipt" \
    --duration-seconds 1 --interval-seconds 10 > "$fixture_root/soak-orphan-start.log" 2>&1
orphan_start_status=$?
set -e
[[ "$orphan_start_status" == "75" ]] || fail "fixture interruption did not stop after one sample"
orphan_epoch="$(jq -r '.observedEpoch' "$orphan_receipt/samples/000000000.json")"
orphan_boot="$(jq -r '.bootEpoch' "$orphan_receipt/samples/000000000.json")"
orphan_created="$(jq -r '.createdEpoch' "$orphan_receipt/manifest.json")"
jq --argjson observedEpoch "$orphan_epoch" --argjson uptime "$((orphan_epoch - orphan_boot))" \
    --argjson elapsed "$((orphan_epoch - orphan_created))" '
    .index=1 | .phase="periodic" | .observedEpoch=$observedEpoch |
    .uptimeSeconds=$uptime | .elapsedSeconds=$elapsed | .gapSeconds=0 |
    .gates={executed:false,required:{verifyObserveOnly:null,statusProbe:null},
            advisory:{hostReadiness:null}} |
    .power=null | .fileVault=null | .userSessionMatches=null |
    .delta={bootChanged:false,
      runtime:{vmChanged:false,daemonChanged:false,
        service:{generationChanged:false,restartDelta:0,exitObserved:false,crashObserved:false}},
      gateway:{generationChanged:false,restartDelta:0,exitObserved:false,crashObserved:false},
      watchdog:{generationChanged:false,restartDelta:0,exitObserved:false,crashObserved:false}}
' "$orphan_receipt/samples/000000000.json" > "$orphan_receipt/samples/000000001.json"
chmod 600 "$orphan_receipt/samples/000000001.json"
/bin/sleep 1
if FAKE_SOAK_BOOT_EPOCH=1700000100 run_soak_fixture "$script_dir/soak-acceptance.sh" \
    --resume --receipt-dir "$orphan_receipt" > "$fixture_root/soak-orphan-resume.log" 2>&1; then
    fail "rebooted soak incorrectly passed continuity"
fi
jq -e '.journalRecoveryCount == 1 and .resumeCount == 1 and .status == "failed" and
    .result == "failed" and .summary.rebootCount >= 1 and .summary.sampleCount == 3' \
    "$orphan_receipt/manifest.json" >/dev/null ||
    fail "orphan/reboot resume receipt is incomplete"
run_soak_fixture "$script_dir/soak-acceptance.sh" --validate --receipt-dir "$orphan_receipt" \
    > "$fixture_root/soak-orphan-validate.log" 2>&1

# A final sample committed before completion metadata is sufficient to finish on resume; no second
# final may be appended. Likewise an initial hard failure cannot be resumed into a 24-hour run.
final_tail_receipt="$fixture_root/backups/soak-final-tail"
copy_soak_receipt "$soak_receipt" "$final_tail_receipt"
mutate_soak_json "$final_tail_receipt/manifest.json" '
    .status="interrupted" | .result="not-complete" |
    .completedAt=null | .completedEpoch=null | .summary=null
'
run_soak_fixture "$script_dir/soak-acceptance.sh" --resume --receipt-dir "$final_tail_receipt" \
    > "$fixture_root/soak-final-tail.log" 2>&1
[[ "$(find "$final_tail_receipt/samples" -type f -name '*.json' | wc -l | tr -d ' ')" == "2" ]] ||
    fail "final-tail resume appended a duplicate final sample"

initial_fail_tail="$fixture_root/backups/soak-initial-fail-tail"
copy_soak_receipt "$missing_job_receipt" "$initial_fail_tail"
mutate_soak_json "$initial_fail_tail/manifest.json" '
    .status="interrupted" | .result="not-complete" |
    .completedAt=null | .completedEpoch=null | .summary=null
'
if run_soak_fixture "$script_dir/soak-acceptance.sh" --resume --receipt-dir "$initial_fail_tail" \
    > "$fixture_root/soak-initial-fail-tail.log" 2>&1; then
    fail "initial failure tail resumed instead of finalizing failed"
fi
[[ "$(find "$initial_fail_tail/samples" -type f -name '*.json' | wc -l | tr -d ' ')" == "1" ]] ||
    fail "initial-failure resume appended another observation"

# The kernel-held file-descriptor lock must reject a concurrent resume before it can alter either
# the manifest or journal.
lock_receipt="$fixture_root/backups/soak-lock-contention"
run_soak_fixture "$script_dir/soak-acceptance.sh" --start --receipt-dir "$lock_receipt" \
    --duration-seconds 3 --interval-seconds 10 > "$fixture_root/soak-lock-owner.log" 2>&1 &
lock_owner_pid=$!
lock_wait=0
while [[ ! -f "$lock_receipt/manifest.json" ||
         "$(jq -r '.nextSampleIndex // 0' "$lock_receipt/manifest.json" 2>/dev/null || true)" != "1" ]]; do
    /bin/sleep 0.05
    lock_wait=$((lock_wait + 1))
    (( lock_wait < 100 )) || fail "timed out waiting for soak lock owner"
done
lock_manifest_before="$(shasum -a 256 "$lock_receipt/manifest.json" | awk '{print $1}')"
lock_samples_before="$(find "$lock_receipt/samples" -type f -name '*.json' | wc -l | tr -d ' ')"
if run_soak_fixture "$script_dir/soak-acceptance.sh" --resume --receipt-dir "$lock_receipt" \
    > "$fixture_root/soak-lock-contender.log" 2>&1; then
    fail "concurrent soak runner acquired an already-held receipt"
fi
[[ "$lock_manifest_before" == "$(shasum -a 256 "$lock_receipt/manifest.json" | awk '{print $1}')" &&
   "$lock_samples_before" == "$(find "$lock_receipt/samples" -type f -name '*.json' | wc -l | tr -d ' ')" ]] ||
    fail "rejected soak lock contender changed the receipt"
wait "$lock_owner_pid" || fail "soak lock owner did not finish its short diagnostic"

# A killed runner must not leave its command-substitution shell or still-running collector child
# holding fd 9. Resume acquires the kernel lock, sweeps that receipt's abandoned raw directory, and
# completes while the orphan listener probe is deliberately still alive.
hung_stub_dir="$fixture_root/soak-hung-stubs"
mkdir "$hung_stub_dir"
chmod 700 "$hung_stub_dir"
cat > "$hung_stub_dir/lsof" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$$" > "${FAKE_SOAK_HUNG_CHILD_PID:?}"
while true; do
    /bin/sleep 1
done
EOF
chmod 700 "$hung_stub_dir/lsof"
hung_receipt="$fixture_root/backups/soak-hung-child"
hung_pid_file="$fixture_root/soak-hung-child.pid"
env \
    TMPDIR="$soak_tmp" \
    PATH="$hung_stub_dir:$soak_stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    FAKE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    FAKE_SESSION_CREATE_LOG="$soak_session_log" \
    FAKE_GATEWAY_RESTART_LOG="$soak_restart_log" \
    FAKE_OPENCLAW_CALL_LOG="$soak_openclaw_log" \
    FAKE_SOAK_HOST_CALL_LOG="$soak_host_log" \
    FAKE_SOAK_BOOT_EPOCH=1700000000 \
    FAKE_SOAK_GATEWAY_MISSING=0 \
    FAKE_SOAK_WATCHDOG_MISSING=0 \
    FAKE_SOAK_HUNG_CHILD_PID="$hung_pid_file" \
    PERSONAL_EDGE_OPENCLAW_SOAK_FIXTURE_MODE=1 \
    PERSONAL_EDGE_OPENCLAW_SOAK_FIXTURE_TAILSCALE_ABSENT=1 \
    PERSONAL_EDGE_OPENCLAW_SOAK_FIXTURE_STOP_AFTER_SAMPLES=0 \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$script_dir/soak-acceptance.sh" --start --receipt-dir "$hung_receipt" \
        --duration-seconds 1 --interval-seconds 60 \
        > "$fixture_root/soak-hung-owner.log" 2>&1 &
hung_runner_pid=$!
hung_wait=0
while [[ ! -s "$hung_pid_file" ]]; do
    /bin/sleep 0.05
    hung_wait=$((hung_wait + 1))
    (( hung_wait < 100 )) || fail "timed out waiting for the hung soak probe"
done
hung_child_pid="$(tr -d '[:space:]' < "$hung_pid_file")"
[[ "$hung_child_pid" =~ ^[0-9]+$ ]] || fail "hung soak probe PID is invalid"
[[ "$hung_runner_pid" == "$(jq -r '.receiptId | capture("-(?<pid>[0-9]+)$").pid' \
    "$hung_receipt/manifest.json")" ]] ||
    fail "receiptId did not identify the running soak script"
/bin/kill -9 "$hung_runner_pid"
set +e
wait "$hung_runner_pid" 2>/dev/null
set -e
[[ -d "$hung_receipt" ]] || fail "killed soak runner left no resumable receipt"
set +e
run_soak_fixture "$script_dir/soak-acceptance.sh" --resume --receipt-dir "$hung_receipt" \
    > "$fixture_root/soak-hung-resume.log" 2>&1
hung_resume_status=$?
set -e
/bin/kill "$hung_child_pid" 2>/dev/null || true
if (( hung_resume_status != 0 )); then
    sed -n '1,20p' "$fixture_root/soak-hung-resume.log" >&2
    fail "surviving soak probe retained the receipt lock"
fi
hung_receipt_id="$(jq -r '.receiptId' "$hung_receipt/manifest.json")"
[[ -z "$(find "$soak_tmp" -maxdepth 1 -type d \
    -name "personal-edge-openclaw-soak-sample.$hung_receipt_id.runner-*" -print -quit)" ]] ||
    fail "resume left its killed runner's raw temporary directory"

# A contender can begin path preflight while the owner is about to finish. Delay only its lockf
# call until the owner has published completion. Authoritative status must be read after acquiring
# the lock, so the contender refuses without rewriting the completed manifest or leaving a partial.
race_stub_dir="$fixture_root/soak-race-stubs"
mkdir "$race_stub_dir"
chmod 700 "$race_stub_dir"
cat > "$race_stub_dir/lockf" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
: > "${FAKE_SOAK_RACE_READY:?}"
while [[ ! -e "${FAKE_SOAK_RACE_RELEASE:?}" ]]; do
    /bin/sleep 0.01
done
exec /usr/bin/lockf "$@"
EOF
chmod 700 "$race_stub_dir/lockf"
release_race_receipt="$fixture_root/backups/soak-release-race"
run_soak_fixture "$script_dir/soak-acceptance.sh" --start \
    --receipt-dir "$release_race_receipt" --duration-seconds 2 --interval-seconds 10 \
    > "$fixture_root/soak-release-race-owner.log" 2>&1 &
release_race_owner=$!
release_race_wait=0
while [[ ! -f "$release_race_receipt/manifest.json" ||
         "$(jq -r '.nextSampleIndex // 0' "$release_race_receipt/manifest.json" 2>/dev/null || true)" != "1" ]]; do
    /bin/sleep 0.05
    release_race_wait=$((release_race_wait + 1))
    (( release_race_wait < 100 )) || fail "timed out waiting for release-race owner"
done
race_ready="$fixture_root/soak-release-race.ready"
race_release="$fixture_root/soak-release-race.release"
set +e
FAKE_SOAK_PATH="$race_stub_dir:$soak_stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
FAKE_SOAK_RACE_READY="$race_ready" FAKE_SOAK_RACE_RELEASE="$race_release" \
    run_soak_fixture "$script_dir/soak-acceptance.sh" --resume \
        --receipt-dir "$release_race_receipt" \
        > "$fixture_root/soak-release-race-contender.log" 2>&1 &
release_race_contender=$!
set -e
release_race_wait=0
while [[ ! -f "$race_ready" ]]; do
    /bin/sleep 0.05
    release_race_wait=$((release_race_wait + 1))
    (( release_race_wait < 100 )) || fail "timed out waiting for release-race contender"
done
wait "$release_race_owner" || fail "release-race owner did not complete"
release_race_manifest_hash="$(shasum -a 256 "$release_race_receipt/manifest.json" | awk '{print $1}')"
: > "$race_release"
if wait "$release_race_contender"; then
    fail "release-race contender resumed a completed soak"
fi
[[ "$release_race_manifest_hash" == \
   "$(shasum -a 256 "$release_race_receipt/manifest.json" | awk '{print $1}')" ]] ||
    fail "release-race contender rewrote the completed manifest"
[[ -z "$(find "$release_race_receipt" -maxdepth 1 -name '.manifest.partial.*' -print -quit)" ]] ||
    fail "release-race contender left a manifest partial"
run_soak_fixture "$script_dir/soak-acceptance.sh" --validate \
    --receipt-dir "$release_race_receipt" > "$fixture_root/soak-release-race-validate.log" 2>&1

/bin/mv "$owner_bootstrap_saved" "$fixture_workspace/BOOTSTRAP.md"

cp "$fixture_config" "$fixture_root/pre-tailscale-openclaw.json"
cp "$tailscale_serve_state" "$fixture_root/pre-tailscale-serve-state.json"
rm -f -- "${tailscale_serve_state}.status-count"
if env \
    PATH="$stub_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    FAKE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    FAKE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_FIXTURE_TAILSCALE_BIN="$stub_dir/tailscale" \
    FAKE_TAILSCALE_SERVE_STATUS_INVALID=1 \
    FAKE_TAILSCALE_SERVE_STATE_FILE="$tailscale_serve_state" \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_runtime" \
    PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT="$fixture_runtime/.personal-edge-management" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_launch_agents" \
    PERSONAL_EDGE_OPENCLAW_NODE_BIN="$fixture_node" \
    "$script_dir/enable-tailscale-serve.sh" --apply \
        > "$fixture_root/tailscale-rollback.log" 2>&1; then
    fail "invalid Tailscale Serve status unexpectedly passed"
fi
cmp -s "$fixture_root/pre-tailscale-openclaw.json" "$fixture_config" ||
    fail "failed Tailscale Serve enablement did not restore exact config bytes"
cmp -s "$fixture_root/pre-tailscale-serve-state.json" "$tailscale_serve_state" ||
    fail "failed Tailscale Serve enablement did not restore existing owner Serve routes"
grep -Fq 'original config, healthy Gateway generation, and owner Serve state were restored' \
    "$fixture_root/tailscale-rollback.log" ||
    fail "failed Tailscale Serve enablement did not prove Gateway and Serve rollback"

private_key="$fixture_root/openrouter-key.txt"
printf '%s\n' 'fixture-key-material-not-a-real-key' > "$private_key"
chmod 600 "$private_key"
cp "$fixture_config" "$fixture_root/original-openclaw.json"
cp "$fixture_state/state/openclaw.sqlite" "$fixture_root/original-global.sqlite"
if env \
    PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture_root" \
    PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture_state" \
    PERSONAL_EDGE_OPENCLAW_CONFIG_PATH="$fixture_config" \
    PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture_workspace" \
    PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT="$fixture_root/unmanaged-runtime" \
    PERSONAL_EDGE_OPENCLAW_BACKUP_DIR="$fixture_root/backups" \
    PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture_root/unmanaged-LaunchAgents" \
    "$script_dir/install-gateway.sh" --method homebrew-node \
        --acknowledge-transient-session-write \
        --openrouter-key-file "$private_key" --apply > "$fixture_root/install-refusal.log" 2>&1; then
    fail "fresh installation accepted an existing unmanaged profile config"
fi
grep -Fq 'existing OpenClaw config is not owned by this deployment' "$fixture_root/install-refusal.log" ||
    fail "fresh install did not stop at existing config"
cmp -s "$fixture_root/original-openclaw.json" "$fixture_config" ||
    fail "fresh install changed existing config or SecretRefs"
cmp -s "$fixture_root/original-global.sqlite" "$fixture_state/state/openclaw.sqlite" ||
    fail "fresh install changed existing SQLite auth store"
[[ ! -e "$fixture_root/unmanaged-runtime" && ! -e "$fixture_root/unmanaged-LaunchAgents" ]] ||
    fail "fresh refusal mutated runtime or LaunchAgent state"

if "$script_dir/install-gateway.sh" --repair --apply \
    > "$fixture_root/in-place-repair-refusal.log" 2>&1; then
    fail "unsafe in-place runtime repair remains enabled"
fi
grep -Fq 'in-place runtime repair is disabled' "$fixture_root/in-place-repair-refusal.log" ||
    fail "in-place repair refusal is not explicit"

for help_script in \
    install-gateway.sh harden-existing.sh adopt-existing.sh configure-openrouter.sh enable-tailscale-serve.sh \
    verify-gateway.sh security-audit.sh export-diagnostics.sh smoke-model.sh \
    backup-gateway.sh restore-gateway.sh rollback-gateway.sh soak-acceptance.sh; do
    "$script_dir/$help_script" --help >/dev/null || fail "--help failed: $help_script"
done

"$script_dir/test-runtime-continuity.sh"
"$script_dir/test-bootstrap-repair.sh"
"$script_dir/test-remote-health-publisher.sh"
"$script_dir/test-managed-tailscale-ingress.sh"
cleanup_fixture
trap - EXIT INT TERM
echo "All OpenClaw deployment asset tests passed."
