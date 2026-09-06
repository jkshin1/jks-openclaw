#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

usage() {
    cat <<'EOF'
Usage: smoke-model.sh --acknowledge-model-charge

Creates one message-free incognito session, proves `tools.effective` exposes zero tools, deletes
that session, then makes one paid, one-shot text-only GLM model run. The run uses no reusable or
visible session context, must return the fixed marker and an exact model/tool receipt, and normal
cleanup must leave zero run-owned logical SQLite rows. This is not secure erase. Nothing is
delivered to a channel.
EOF
}

acknowledge=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --acknowledge-model-charge) acknowledge=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) openclaw_fail "unknown argument: $1" ;;
    esac
done
model_run_agent_db="$openclaw_state_dir/agents/main/agent/openclaw-agent.sqlite"
model_run_last_residue_count="unavailable"

# Count only rows bound to this script-generated key/id. No transcript text, event JSON, session
# entry JSON, credential, or other owner data is selected. The hard-coded table/column inventory is
# the exact 2026.8.1 agent SQLite schema and intentionally fails closed on schema drift.
openclaw_model_run_residue_count() {
    local requested_key="$1"
    local explicit_id="$2"
    local internal_key="$3"
    local internal_id="$4"
    local run_id="$5"

    [[ "$requested_key" =~ ^[A-Za-z0-9._:-]+$ &&
       "$explicit_id" =~ ^[A-Za-z0-9._:-]+$ &&
       "$internal_key" =~ ^[A-Za-z0-9._:-]+$ &&
       "$internal_id" =~ ^[A-Za-z0-9._:-]+$ &&
       "$run_id" =~ ^[A-Za-z0-9._:-]+$ ]] ||
        openclaw_fail "unsafe model-run residue identity"
    sqlite3 -readonly -cmd '.timeout 5000' "$model_run_agent_db" "
        PRAGMA query_only=ON;
        WITH residue(count) AS (
            SELECT count(*) FROM acp_parent_stream_events
                WHERE session_id IN ('$explicit_id', '$internal_id') OR run_id = '$run_id'
            UNION ALL SELECT count(*) FROM board_tabs
                WHERE session_key IN ('$requested_key', '$internal_key')
            UNION ALL SELECT count(*) FROM board_widgets
                WHERE session_key IN ('$requested_key', '$internal_key')
            UNION ALL SELECT count(*) FROM context_engine_turn_outbox
                WHERE session_id IN ('$explicit_id', '$internal_id')
            UNION ALL SELECT count(*) FROM conversation_deliveries
                WHERE source_session_key IN ('$requested_key', '$internal_key')
            UNION ALL SELECT count(*) FROM heartbeat_outcomes
                WHERE session_key IN ('$requested_key', '$internal_key')
                   OR run_session_key IN ('$requested_key', '$internal_key')
            UNION ALL SELECT count(*) FROM memory_entry_origins
                WHERE session_id IN ('$explicit_id', '$internal_id')
                   OR session_key IN ('$requested_key', '$internal_key')
            UNION ALL SELECT count(*) FROM memory_session_tombstones
                WHERE session_id IN ('$explicit_id', '$internal_id')
            UNION ALL SELECT count(*) FROM message_tool_run_outcomes
                WHERE session_key IN ('$requested_key', '$internal_key') OR run_id = '$run_id'
            UNION ALL SELECT count(*) FROM session_conversations
                WHERE session_id IN ('$explicit_id', '$internal_id')
            UNION ALL SELECT count(*) FROM session_goal_operations
                WHERE session_id IN ('$explicit_id', '$internal_id')
                   OR session_key IN ('$requested_key', '$internal_key')
            UNION ALL SELECT count(*) FROM session_members
                WHERE session_key IN ('$requested_key', '$internal_key')
            UNION ALL SELECT count(*) FROM session_nodes
                WHERE session_key IN ('$requested_key', '$internal_key')
                   OR current_session_id IN ('$explicit_id', '$internal_id')
                   OR parent_session_key IN ('$requested_key', '$internal_key')
                   OR fork_source_session_key IN ('$requested_key', '$internal_key')
                   OR fork_source_session_id IN ('$explicit_id', '$internal_id')
            UNION ALL SELECT count(*) FROM session_participants
                WHERE session_key IN ('$requested_key', '$internal_key')
            UNION ALL SELECT count(*) FROM session_pending_inputs
                WHERE session_id IN ('$explicit_id', '$internal_id')
                   OR session_key IN ('$requested_key', '$internal_key')
                   OR run_id = '$run_id'
            UNION ALL SELECT count(*) FROM session_progress_cards
                WHERE session_key IN ('$requested_key', '$internal_key')
            UNION ALL SELECT count(*) FROM session_suggestions
                WHERE session_key IN ('$requested_key', '$internal_key')
            UNION ALL SELECT count(*) FROM session_transcript_active_events
                WHERE session_id IN ('$explicit_id', '$internal_id')
            UNION ALL SELECT count(*) FROM session_transcript_archives
                WHERE session_id IN ('$explicit_id', '$internal_id')
                   OR session_key IN ('$requested_key', '$internal_key')
            UNION ALL SELECT count(*) FROM session_transcript_fts
                WHERE session_id IN ('$explicit_id', '$internal_id')
            UNION ALL SELECT count(*) FROM session_transcript_index_state
                WHERE session_id IN ('$explicit_id', '$internal_id')
            UNION ALL SELECT count(*) FROM session_windows
                WHERE session_id IN ('$explicit_id', '$internal_id')
                   OR session_key IN ('$requested_key', '$internal_key')
                   OR previous_session_id IN ('$explicit_id', '$internal_id')
                   OR parent_session_key IN ('$requested_key', '$internal_key')
            UNION ALL SELECT count(*) FROM standing_intents
                WHERE source_session_id IN ('$explicit_id', '$internal_id')
            UNION ALL SELECT count(*) FROM trajectory_runtime_events
                WHERE session_id IN ('$explicit_id', '$internal_id') OR run_id = '$run_id'
            UNION ALL SELECT count(*) FROM transcript_event_identities
                WHERE session_id IN ('$explicit_id', '$internal_id')
            UNION ALL SELECT count(*) FROM transcript_events
                WHERE session_id IN ('$explicit_id', '$internal_id')
            UNION ALL SELECT count(*) FROM transcript_rewrite_watermarks
                WHERE session_id IN ('$explicit_id', '$internal_id')
        )
        SELECT coalesce(sum(count), 0) FROM residue;
    "
}

openclaw_wait_for_model_run_residue_zero() {
    local requested_key="$1"
    local explicit_id="$2"
    local internal_key="$3"
    local internal_id="$4"
    local run_id="$5"
    local observe_full_window="${6:-0}"
    local attempt residue_count

    for attempt in {1..20}; do
        if residue_count="$(openclaw_model_run_residue_count \
            "$requested_key" "$explicit_id" "$internal_key" "$internal_id" "$run_id")" &&
            [[ "$residue_count" =~ ^[0-9]+$ ]]; then
            model_run_last_residue_count="$residue_count"
            if [[ "$residue_count" == "0" && "$observe_full_window" == "0" ]]; then
                return 0
            fi
        else
            model_run_last_residue_count="unavailable"
            return 1
        fi
        (( attempt == 20 )) || sleep 0.25
    done
    [[ "$model_run_last_residue_count" == "0" ]]
}

if [[ "${PERSONAL_EDGE_OPENCLAW_SMOKE_LIBRARY_ONLY:-0}" == "1" ]]; then
    return 0 2>/dev/null || exit 0
fi

(( acknowledge == 1 )) || openclaw_fail "model charge acknowledgement is required"
openclaw_load_deployment
for required_command in jq shasum sleep sqlite3 tr uuidgen; do
    openclaw_require_command "$required_command"
done
openclaw_assert_private_file "$model_run_agent_db" "main-agent SQLite state"
receipt_dir="$openclaw_state_dir/operations"
openclaw_prepare_private_dir "$receipt_dir" "OpenClaw operations directory"
smoke_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
preflight_path="$receipt_dir/glm-smoke-preflight-$smoke_stamp.json"
openclaw_assert_restrictive_config "$openclaw_config_path"
openclaw_run secrets audit --check --json > "$preflight_path" 2> "$preflight_path.err"
chmod 600 "$preflight_path" "$preflight_path.err"
openclaw_assert_clean_secrets_receipt "$preflight_path"
openclaw_run models status --check --json > "$preflight_path.models" 2> "$preflight_path.models.err"
chmod 600 "$preflight_path.models" "$preflight_path.models.err"
openclaw_run health --json > "$preflight_path.health" 2> "$preflight_path.health.err"
chmod 600 "$preflight_path.health" "$preflight_path.health.err"
openclaw_assert_live_plugins_receipt "$preflight_path.health"

# An empty allowlist is unrestricted in 2026.8.1, so the authored wildcard deny is not accepted
# on faith. Ask the running Gateway for the post-policy, model-visible inventory before inference.
zero_session_requested="agent:main:dashboard:incognito-personal-edge-zero-tools-$(date -u +%Y%m%dt%H%M%Sz)-$$"
zero_session_create="$receipt_dir/zero-tools-session-create-$smoke_stamp.json"
zero_inventory="$receipt_dir/zero-tools-effective-$smoke_stamp.json"
zero_session_delete="$receipt_dir/zero-tools-session-delete-$smoke_stamp.json"
zero_session_created=0
cleanup_zero_session() {
    local exit_status="$?"
    local cleanup_params
    trap - EXIT INT TERM
    if (( zero_session_created == 1 )); then
        cleanup_params="$(jq -cn --arg key "$zero_session_requested" \
            '{key:$key,agentId:"main",deleteTranscript:true,emitLifecycleHooks:false}')"
        if ! openclaw_run gateway call sessions.delete --json --params "$cleanup_params" \
            > "$zero_session_delete" 2> "$zero_session_delete.err"; then
            echo "WARN zero-tool verification session cleanup needs manual review: $zero_session_requested" >&2
        fi
        chmod 600 "$zero_session_delete" "$zero_session_delete.err" 2>/dev/null || true
    fi
    exit "$exit_status"
}
trap cleanup_zero_session EXIT INT TERM

# Local token-auth CLI calls omit device identity; the optional principal-scoped sessions.create
# idempotencyKey is unsupported. This empty probe is dispatched once with exact-key cleanup.
# The paid model run below retains its separate required idempotency key.
create_params="$(jq -cn --arg key "$zero_session_requested" '
    {
        key:$key, agentId:"main", permissionMode:"read-only",
        incognito:true
    }
')"
zero_session_created=1
openclaw_run gateway call sessions.create --json --params "$create_params" \
    > "$zero_session_create" 2> "$zero_session_create.err"
chmod 600 "$zero_session_create" "$zero_session_create.err"
created_session_key="$(jq -r '.key // .result.key // empty' "$zero_session_create")"
[[ "$created_session_key" == "$zero_session_requested" ]] ||
    openclaw_fail "Gateway did not create the bound zero-tool verification session"

effective_params="$(jq -cn --arg key "$created_session_key" \
    '{agentId:"main",sessionKey:$key}')"
openclaw_run gateway call tools.effective --json --params "$effective_params" \
    > "$zero_inventory" 2> "$zero_inventory.err"
chmod 600 "$zero_inventory" "$zero_inventory.err"
jq -e '
    (.agentId // .result.agentId) == "main" and
    (((.groups // .result.groups) | type) == "array") and
    ([((.groups // .result.groups)[]?.tools[]?)] | length) == 0
' "$zero_inventory" >/dev/null ||
    openclaw_fail "running Gateway exposes one or more effective tools; paid smoke was not sent"

delete_params="$(jq -cn --arg key "$created_session_key" \
    '{key:$key,agentId:"main",deleteTranscript:true,emitLifecycleHooks:false}')"
openclaw_run gateway call sessions.delete --json --params "$delete_params" \
    > "$zero_session_delete" 2> "$zero_session_delete.err"
chmod 600 "$zero_session_delete" "$zero_session_delete.err"
jq -e '(.ok // .result.ok) == true' "$zero_session_delete" >/dev/null ||
    openclaw_fail "Gateway did not acknowledge zero-tool verification session cleanup"
zero_session_created=0
trap - EXIT INT TERM

marker="PERSONAL_EDGE_GLM_SMOKE_OK"
model_run_uuid="$(uuidgen | tr '[:upper:]' '[:lower:]')"
[[ "$model_run_uuid" =~ ^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$ ]] ||
    openclaw_fail "uuidgen returned an unexpected model-run identity"
model_run_id="personal-edge-glm-smoke-$model_run_uuid"
model_run_session_id="model-run-$model_run_uuid"
model_run_session_key="agent:main:explicit:$model_run_session_id"
internal_run_prefix="${model_run_id//[^a-zA-Z0-9._-]/_}"
internal_run_prefix="${internal_run_prefix:0:48}"
internal_run_hash="$(printf '%s' "$model_run_id" | shasum -a 256 | awk '{print substr($1, 1, 16)}')"
internal_run_suffix="$internal_run_prefix-$internal_run_hash"
internal_session_id="internal-session-effects-$internal_run_suffix"
internal_session_key="agent:main:internal-session-effects:$internal_run_suffix"
expected_provider="${PERSONAL_EDGE_OPENCLAW_MODEL%%/*}"
expected_model="${PERSONAL_EDGE_OPENCLAW_MODEL#*/}"
acceptance_path="$receipt_dir/glm-model-run-acceptance-$smoke_stamp.json"
receipt_path="$receipt_dir/glm-model-run-terminal-$smoke_stamp.json"
residue_path="$receipt_dir/glm-model-run-residue-$smoke_stamp.json"

before_residue="$(openclaw_model_run_residue_count \
    "$model_run_session_key" "$model_run_session_id" \
    "$internal_session_key" "$internal_session_id" "$model_run_id")"
[[ "$before_residue" == "0" ]] ||
    openclaw_fail "generated model-run identity already has session/transcript residue"

model_run_may_exist=0
model_run_residue_verified=0
observe_model_run_cleanup_on_exit() {
    local exit_status="$?"
    trap - EXIT INT TERM
    if (( model_run_may_exist == 1 && model_run_residue_verified == 0 )); then
        if ! openclaw_wait_for_model_run_residue_zero \
            "$model_run_session_key" "$model_run_session_id" \
            "$internal_session_key" "$internal_session_id" "$model_run_id" 1; then
            echo "WARN model-run cleanup could not be verified; run-owned row count: $model_run_last_residue_count" >&2
            (( exit_status != 0 )) || exit_status=1
        fi
    fi
    exit "$exit_status"
}
trap observe_model_run_cleanup_on_exit EXIT INT TERM

agent_params="$(jq -cn \
    --arg marker "$marker" \
    --arg sessionId "$model_run_session_id" \
    --arg sessionKey "$model_run_session_key" \
    --arg idempotencyKey "$model_run_id" '
    {
        message:("Reply with exactly " + $marker),
        agentId:"main",
        sessionId:$sessionId,
        sessionKey:$sessionKey,
        thinking:"low",
        deliver:false,
        timeout:60,
        modelRun:true,
        promptMode:"none",
        disableMessageTool:true,
        cleanupBundleMcpOnRunEnd:true,
        idempotencyKey:$idempotencyKey
    }
')"
model_run_may_exist=1
if ! openclaw_run gateway call agent --json --params "$agent_params" \
    > "$acceptance_path" 2> "$acceptance_path.err"; then
    openclaw_fail "one-shot GLM model run was not accepted; private diagnostics: $acceptance_path.err"
fi
chmod 600 "$acceptance_path" "$acceptance_path.err"
jq -e --arg run "$model_run_id" --arg session "$model_run_session_key" '
    (.result // .) as $accepted |
    $accepted.runId == $run and
    $accepted.sessionKey == $session and
    $accepted.agentId == "main" and
    $accepted.status == "accepted"
' "$acceptance_path" >/dev/null ||
    openclaw_fail "Gateway returned an unexpected one-shot model-run acceptance"

wait_params="$(jq -cn --arg runId "$model_run_id" '{runId:$runId,timeoutMs:90000}')"
if ! openclaw_run gateway call agent.wait --timeout 95000 --json --params "$wait_params" \
    > "$receipt_path" 2> "$receipt_path.err"; then
    openclaw_fail "one-shot GLM model wait failed; private diagnostics: $receipt_path.err"
fi
chmod 600 "$receipt_path" "$receipt_path.err"
jq -e \
    --arg run "$model_run_id" \
    --arg marker "$marker" \
    --arg provider "$expected_provider" \
    --arg model "$expected_model" '
    (.result // .) as $terminal |
    $terminal.runId == $run and
    $terminal.status == "ok" and
    $terminal.terminalReply == {disposition:"visible",text:$marker} and
    $terminal.terminalReceipt.runId == $run and
    $terminal.terminalReceipt.requested == {provider:$provider,model:$model} and
    $terminal.terminalReceipt.effective == {
        provider:$provider,model:$model,responseModel:$model
    } and
    $terminal.terminalReceipt.successfulToolNames == [] and
    $terminal.terminalReceipt.rerouted == false
' "$receipt_path" >/dev/null ||
    openclaw_fail "GLM terminal reply/model/tool receipt did not match the exact smoke contract"

if ! openclaw_wait_for_model_run_residue_zero \
    "$model_run_session_key" "$model_run_session_id" \
    "$internal_session_key" "$internal_session_id" "$model_run_id"; then
    openclaw_fail "one-shot model run left run-owned session/transcript rows: $model_run_last_residue_count"
fi
after_residue="$model_run_last_residue_count"
jq -n \
    --arg runId "$model_run_id" \
    --argjson before "$before_residue" \
    --argjson after "$after_residue" \
    '{runId:$runId,beforeRows:$before,afterRows:$after}' > "$residue_path"
chmod 600 "$residue_path"
model_run_residue_verified=1
model_run_may_exist=0
trap - EXIT INT TERM

echo "OK tools.effective=0 before inference; temporary incognito verification session was deleted."
echo "OK exact GLM terminal receipt reports zero tools; one-shot session/transcript residue is zero."
echo "Private terminal receipt: $receipt_path"
echo "Content-free residue receipt: $residue_path"
