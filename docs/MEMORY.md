# Long-term Memory

Long-term memory is a user-controlled cross-thread store. It is separate from both chat history
and the per-thread model summary.

## Capture and consent

- The feature defaults off. While off, recall and `memory_remember` execution both fail closed;
  existing rows remain visible and deletable in settings.
- Effective consent is the durable setting intersected with the process-wide owner-consent gate.
  Any pending toggle closes recall/store immediately; only the latest enable may reopen after its
  durable write succeeds. ViewModel recreation, stale refresh, failed persistence, or cancellation
  cannot reopen an uncommitted feature.
- The settings screen can save one memory directly. This is an explicit local user action.
- Gemma can propose `memory_remember` for one durable preference, person, place, routine, or fact. The exact
  canonical sentence is shown in the normal confirmation sheet and is not stored unless approved.
- `automaticToolCalling=false` remains unchanged. Kotlin validates the flat `content` argument,
  checks the independent memory opt-in again immediately before the durable ledger claim, and owns
  the Room write.
- Passwords, API keys, tokens, JWTs, long hex/Base64-like values, unlabelled high-entropy ASCII
  candidates, Korean resident numbers, payment-card candidates that pass Luhn, financial
  identifiers, model-control delimiters, format controls, and over-240-code-point values are
  rejected. The UI states the bounded claim precisely: detectable authentication and financial
  information is blocked. Memory is not a credential store.

## Recall

At turn start, Kotlin scores at most 50 stored rows against the current request using normalized
words and bounded character bigrams. Up to four relevant rows enter the existing 2 KiB request
envelope. There is no zero-overlap fallback: an unrelated recent memory is not injected merely
because it is newest. Route-shaped prompts request only `PLACE` memories.

Expired rows, rows older than the reconfirmation horizon, and rows superseded by a newer memory are
excluded from recall. Settings keeps them visible with their state and supports explicit
reconfirmation or replacement. Replacement writes a new row and binds `supersedesId`; it does not
silently rewrite the original approved statement.

Memory appears inside an explicit quoted-data block. It is sanitized again by
`TurnContextBuilder`, cannot replace the current request, and competes with summary/recent-message
context under the byte cap. It is reference data, not a system instruction or proof that a fact is
still current.

## Per-thread context compaction

Per-thread compaction is separate from opt-in cross-thread Memory. After a completed user turn, the
app schedules a Tool-free rolling capsule when either ten unsummarized messages accumulate or their
UTF-8 source size reaches 75% of the 2 KiB request boundary. Long rows preserve both their opening
and trailing request before summarization. Each accepted capsule is a complete latest-state
replacement organized around goal, decisions and constraints, and unresolved references; it is
limited to 480 UTF-8 bytes rather than an open-ended append.

Kotlin rejects an incomplete, over-limit, Tool-producing, or literal-inventing summary. A prior
date, number, URL, email, identifier, or quoted value may disappear only when the new source
explicitly repeats the old value, marks a correction, and supplies a same-kind replacement retained
by the capsule. The newest twelve messages and any live recovery request remain verbatim. A user
turn cancels and joins background compaction. Under the owner's latest policy, background work is
not reduced or cancelled through SEVERE; CRITICAL and higher states cancel it, UNKNOWN fails closed,
and the same boundary is rechecked before storage. Failure leaves the previous capsule, boundary,
and source rows unchanged.

## Storage and deletion

The `memories` Room table lives in `noBackupFilesDir` and is excluded from Android automatic
backup/device transfer. It stores category, optional validity, last confirmation, and supersession.
Duplicate normalized content updates one row; capacity is 50 and a full store refuses rather than
silently evicting a user-approved record. A user-selected encrypted archive may include memory rows;
credentials remain excluded. See [`DATA_TRANSFER.md`](DATA_TRANSFER.md).

Deleting chat history does not delete memories. Settings provides individual and memory-only
delete-all actions. Turning the feature off stops capture and recall but does not erase rows.

Memory does not schedule proactive notifications. Use the calendar or alarm Tools when a fact must
surface at a particular time.

## Evidence boundary

The current rc11 host `releaseGate` covers Tool validation/encoding, confirmation classification,
category filtering, expiry/reconfirmation/supersession, no-overlap selection, consent races, prompt
quoting/budgeting, receipts, and the diagnostic allowlist. On 2026-08-23 five earlier targeted
`core:data` tests passed on the Fold8 in a package separate from the installed Personal Edge app,
including Room v1-to-v2 migration. The same-certificate release was then installed with
`adb install -r`, pulled back with an exact host-APK hash match, and launched with the prior
transcript plus verified-model metadata intact. Settings showed the new default-off memory row with
zero records. The typed-memory schema and UI are a later working-tree implementation and do not
inherit that device receipt. Direct capture, real Gemma selection/approval, expiry/reconfirmation,
replacement, process-restart recall, deletion/disable behavior, both-display UI stability, and
content-free SAF diagnostics remain unqualified.
