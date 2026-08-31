# Turn recovery

Personal Edge keeps content-free recovery metadata for each persisted user turn. It references the
existing user message by conversation ID and ordinal; it never duplicates the prompt, Tool
arguments/result, assistant text, provider data, or exception text.

The current app database is Room schema 10. Recovery uses `turn_outcomes`, the ordered
`turn_read_executions` relation, and the transcript-independent `unresolved_side_effects` table.

Schema 10 adds nullable `recovery_source_user_message_ordinal`. Most turns leave it null. A bounded
contextual public-search follow-up owns an outcome keyed to its current USER message, while this
content-free ordinal identifies the earlier USER question that must be replayed for recovery. The
repository validates that both rows are USER messages in the same conversation and that the source
precedes the follow-up. Assistant text, summaries, memory, Tool/provider payloads, and prompt copies
are not stored in this field.

## Closed state machine

| Last durable state | Owner option | App behavior |
|---|---|---|
| `STARTED`, no trusted read | None | Prompt wording is not evidence that an external read occurred. |
| One to four trusted reads completed, no terminal answer | Read again | Starts a fresh turn under the exact ordered read contract. Old result content is not reused. |
| Side effect durably armed or observed (`WRITE_PENDING` / `WRITE_UNKNOWN`) | Verify external state | Never replays. The owner is directed to the relevant Calendar, Clock, KakaoTalk, reminder, memory, or proposal surface. |
| Definitive `WRITE_REFUSED` | None | Clears only the matching pending obligation because the typed result proves that write did not occur. |
| Terminal answer committed | None | The transient outcome becomes `ANSWER_COMPLETE`; a separate unresolved side effect, if any, is not hidden by that transcript state. |

## Exact ordered read contract

Every trusted read is recorded with a one-based ordinal. A valid recoverable sequence:

- contains between one and four rows;
- has exactly the contiguous ordinals `1..N`;
- preserves the Tool name at every ordinal, including duplicate names; and
- comes only from registry-resolved `READ_ONLY` descriptors.

Recovery copies that exact ordered list into the new successor inside the same transaction that
persists the successor and deletes the predecessor. The controller exposes only those Tool schemas.
Reservation must match the full ordered list exactly; a substituted, missing, additional, or
reordered Tool is refused before execution. For a parallel batch, reservation order is exact while
completion callbacks may arrive in any order; completion counts must still satisfy the reserved
multiset before the answer can finish.

The successor performs fresh consent, permission, execution-interlock, argument parsing, whole-
batch preflight, thermal, and Tool checks. It cannot finish from old receipts or model prose alone.
Kotlin orders the new receipts and renders the grounded answer. A prose-only successor fails with
`TOOL_NOT_EXECUTED`.

For a contextual public-search turn, recovery resolves the original owner question with
`COALESCE(recovery_source_user_message_ordinal, user_message_ordinal)` while the successor remains
uniquely bound to the current follow-up. This preserves the earlier completed outcome rather than
deleting or reusing it to bypass one-outcome-per-message finalization.

## Side-effect boundary

In a normal turn, authorization and the execution-time interlock run before a side effect is armed.
Immediately before ledger claim and Tool invocation, the exact Tool/risk identity must be stored as
`WRITE_PENDING`. Missing state, policy drift, storage failure, false return, or exception vetoes
execution. Once one write is pending or observed, a second side effect in that unfinished turn is
refused because one recovery obligation cannot safely name two targets.

After Tool invocation begins, a non-definitive failure becomes `WRITE_UNKNOWN`. A later read cannot
erase this obligation. The app never retries it automatically. Definitively completed and refused
writes receive Kotlin-authored terminal answers from the typed receipt; unknown outcomes remain
owner-verification notices and are not converted into success prose.

## Atomic transcript contract

`ConversationRepository.commitToolExecution` performs one Room transaction containing:

1. the current assistant phase, when non-empty;
2. the app-authored Tool receipt; and
3. the typed read/write/refusal outcome transition.

If any insert or transition fails, the whole transaction rolls back. The UI does not advance its
assistant phase until this commit succeeds. Final assistant text and the terminal outcome transition
are likewise committed together and repeated finalization is idempotent. This prevents a restored
receipt without its recovery state, a disappeared capsule without its answer, or duplicate terminal
assistant text after an uncertain caller return.

Each Tool commit also owns an exact idempotency slot identified by turn ID, trusted ordinal, Tool
risk, closed outcome, and Tool name; the stored app-authored receipt text must match too. Redelivery
of that exact marker and receipt succeeds as a no-op without appending assistant/receipt rows or
reapplying the outcome. Reusing the same turn ordinal with a different Tool, risk, outcome, or
receipt fails closed. The ViewModel independently admits only an exact `(toolName, outcome)`
duplicate for a processed ordinal. It marks the event processed only after the durable Room commit
and UI receipt transition succeed; later memory/proposal refresh failure cannot recommit the Tool
or relabel the durable success as failure. This protects commit redelivery only—the separate Action
Ledger remains the authority that prevents execution of a side effect twice.

## Transcript-independent unresolved writes

`unresolved_side_effects` intentionally has no conversation/turn foreign key and no TTL. Deleting a
conversation, deleting all chat history, compacting messages, or garbage-collecting an expired turn
cannot erase an uncertain external action. The UI queries these obligations globally. Only an exact
typed refusal or explicit owner acknowledgement after checking the external state may remove one.
The separate persistent Action Ledger remains the replay-prevention authority; recovery guidance
does not weaken it.

`ConversationMutationGate` also serializes recovery resolution with restore, new/switch/delete
conversation operations, delete-all, and synchronous turn start. A recovery action cannot race a
different active-conversation mutation across the Room/UI boundary.

## Compatibility and privacy

Legacy transcript-only recovery is bounded to closed read-only receipt names. Typed schema-10 state
always wins when present. Recovery metadata is excluded from encrypted user transfer. Diagnostic
and error surfaces use closed codes and counts; they never include the request, arguments, result,
provider body, or exception text.

## Verification boundary

Source and regression coverage exist for ordered multi-read contracts, substitution/reordering,
whole-batch refusal, parallel completion, atomic successor handoff, exact receipt/outcome commit
redelivery, write arming/refusal/unknown states, transcript deletion, migrations 8→9 and 9→10,
contextual-source validation, expiry, and
conversation mutation serialization. An earlier rc11 host `releaseGate` passed, and its scoped API
37 AVD suites completed without failures. The 2026-08-30 schema-10 delta additionally passed the
standard full host test/lint/debug/release assembly gate and the scoped 25-case migration/repository
AVD suite. No physical-device install, process-recreation
acceptance, or provider recovery run was performed for this source tree. Never run an unscoped
`connectedAndroidTest` against the owner's Fold8.
