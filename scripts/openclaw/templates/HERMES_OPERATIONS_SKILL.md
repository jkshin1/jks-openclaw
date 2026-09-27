---
name: hermes-operations
description: Use for an explicit owner request to diagnose this OpenClaw deployment, review an update's local impact, or prepare an operational improvement with Hermes.
user-invocable: true
---

# Hermes operations

Use the installed operations controller when the owner explicitly requests an
OpenClaw operational diagnosis, update-impact review, or operational improvement.
Examples include "OpenClaw 장애 원인을 Hermes로 확인해줘", "이번 업데이트가 우리
설정에 미치는 영향을 검토해줘", and "반복되는 운영 오류를 개선해줘".
An ordinary task, its delayed reply, or a routine status question is not a request
to launch a second operations investigation.

OpenClaw owns the conversation, current task, deployment decisions within the
owner's authorization, and final delivery. Hermes uses its separate OAuth profile,
Sol/high route, selected operational evidence, bounded source-reading tools, and
operational procedure skills. The controller validates candidate changes in a
separate snapshot. A tested candidate is not an installed fix.

1. Preserve the owner's request as one safely quoted argument. Include the
   operational symptom and requested outcome; do not append conversation history,
   authentication files, credentials, or unrelated personal information. Invoke:

   ```text
   python3 /Users/jk/.local/share/openclaw-hermes-worker/bin/hermes-operations.py run --mode manual --request <owner-request-as-one-argument> --json
   ```

   For an explicit request to review the whole source tree without gaps, add
   `--area all`: the controller runs one worker per source area in sequence
   (about 30 minutes) and returns an `area-audit` summary with each area's
   `runId`, `status`, `reportPath` and `failedAreas`. A single area uses
   `--area <name>`. Run it detached and wait for its summary, not a second run.

   Shell substitution inside user text must remain literal. Prefer an argument
   array where supported; otherwise use correct shell quoting. Do not build a
   command by concatenating unescaped request text.
2. Track and wait for this exact process. If it is still running, use the returned
   process handle. A delayed Telegram answer, timeout while waiting, or incomplete
   display is not permission to start another controller run. Inspect existing
   status and receipts first. If the controller reports a duplicate or busy run,
   preserve that result and follow the existing run instead of bypassing its lock.
3. Read the controller's JSON result and its referenced local receipt. Use `runId`,
   `status`, `evidencePath`, `reportPath`, `receiptPath`, and
   `candidateVerification` to associate the evidence with this execution. Check the
   actual terminal state, evidence collection time, model execution and validation
   outcomes. A process exit code alone is insufficient. Explain failed, blocked,
   unchanged, or incomplete work as such; do not silently change the model,
   provider, authentication source, or tool boundary to obtain success.
4. Explain the useful result in Korean: observed condition, supporting evidence,
   cause or remaining uncertainty, and action taken or proposed. Keep a model's
   diagnosis distinct from independently reproduced evidence. Mention material
   missing or stale evidence. Read only receipt-referenced artifacts needed for
   this request; do not dump private diagnostics into the conversation.
5. When a receipt includes a successfully tested candidate, review its exact diff,
   target scope, source hashes, test results, and rollback information. Continue
   the requested and already authorized repair through OpenClaw's existing
   deployment procedure: current-state checks, scoped backup, relevant regression
   checks, installation, and runtime verification. If a Gateway restart is needed,
   use the established three-sample idle gate and official service commands;
   preserve existing conversations and active work. Changed baseline hashes or
   unresolved validation failures require renewed preparation and testing of the
   affected candidate before installation. Do not automatically apply a candidate
   whose tests failed. Keep follow-up edits within this project's operational code
   and the owner's requested scope; do not infer authorization for changing
   authentication, deleting conversations, adding paid routes, or replacing major
   runtime versions. Complete diagnosis and candidate preparation without adding
   an intermediate approval step. A diagnosis-only request ends with the
   diagnosis and concrete proposal, unless earlier authorization covers applying it.
6. The controller reports `applied:false` and `telegramDelivered:false`. Report
   candidate tests, installation, runtime behavior, and Telegram delivery as
   separate evidence. A controller's no-send indicator means Hermes did not send
   the result. OpenClaw's final reply or attachment uses its normal delivery path
   and real transport receipt. Retry only a confirmed failed transport; never
   rerun the original user task or the completed diagnosis to recover a message.
   Do not send a second notice if the same incident/result already has a delivery
   receipt. Telegram API acceptance does not prove the phone opened the result.

If independent authentication is unavailable, report that specific blocker. Keep
the existing OpenClaw/Codex credentials and Hermes login separate; do not copy a
token, read an auth file into the conversation, request an API key, or launch an
installer/login as an implicit fallback. Do not claim a separate allowance or
protection from a shared provider outage merely because the login is separate.

This skill does not create schedules, install another Telegram poller, rewrite
general owner memory, or grant Hermes authority to apply its own candidates.
The weekly operations review uses the separately registered Codex schedule.
For a supplied operations-event JSON report without a deployment investigation,
use `hermes-operations-report` and its established input contract instead.

## Operational experience and issue follow-up

The installed controller automatically supplies prior finding lifecycle, verified procedures and
changes since the last valid review. Read `knowledge` for the current recorded state. The controller
supports `update-finding`, `record-experience`, `promote-procedure` and `record-reuse`, each taking
`--payload-file <private-json-path>`; exact payload and verification schemas are documented in
`docs/OPENCLAW_HERMES_OPERATIONS.md` in the active repository. Pass JSON via files, not shell text.

Record an owner and processing state for an accepted issue, a reason for deferral, and independent
test plus runtime verification before resolution. Keep observation, issue and improvement distinct;
normal observations and unclassified historical findings are not automatically open problems.
`record-applied` alone is a deployment note and cannot close an issue or certify a learned procedure.

After an authorized repair, connect its cause/change/version/verification to an experience and
promote only the verified general procedure. Preserve failed attempts. Subsequent procedure reading
or a model's reuse claim is reference evidence only; verified reuse requires a new procedure-bound
test/runtime receipt. Complete these records using the actual outcomes of the current work rather
than requesting new approval for already authorized routine steps.
