# Architecture review

Reconciled on 2026-08-26 against the current `1.0.0-rc11` working tree.

## Outcome

```text
LLM = bounded proposal and language generation
Kotlin = authority, validation, orchestration, rendering, and persistence
Tool = typed execution behind current interlocks
```

The closed production registry contains seventeen device Tools across calendar, alarms, local
reminders, notification search/reply, KakaoTalk picker sharing, route, public search, weather,
approved memory, and review-only proposals. Model output never invokes Android/provider APIs
directly, and `automaticToolCalling=false` remains mandatory.

This is a source review. The current rc11 working-tree host `releaseGate` passed on 2026-08-26,
and scoped API 37 AVD suites completed without failures. The tree has not been installed or tested
on a physical device. No Fold8, provider-live, migration-preservation, or thermal acceptance is
inferred from older receipts; provenance truthfully records the working tree as dirty.

## Control and execution decisions

1. Tool names, risks, parameters, preparation, confirmation requirements, and result encoders are
   typed Kotlin contracts. Unknown names and malformed arguments fail closed.
2. Every model turn receives `LlmTurnToolScope` derived by `TurnToolScopePolicy`. The scope contains
   only the minimum domain/intent-specific names. Unclassified prose receives none; ambiguous
   multi-domain write intent receives no write schema.
3. A recovery turn receives only its exact ordered read contract, never the broad registry.
4. Read-only Tools require current consent/permission/interlock but not a per-request confirmation
   sheet. State-changing and communication Tools require explicit confirmation plus the durable
   Action Ledger.
5. Confirmation binds immutable canonical input, digest, exact preview, expiry, Tool/risk,
   capabilities, request/action/replay identity, and legacy ledger key. Authorization and the final
   interlock are rechecked immediately before claim.
6. The action ledger is a separate no-backup SQLite database with an `IMMEDIATE` claim transaction
   and `synchronous=FULL`. Live claims are not evicted; failure to claim is an execution veto.
7. Tool and turn IDs are Kotlin-owned. A later user submission is a new action; the app claims
   at-most-once execution for an identity, not semantic deduplication across separate submissions.

## Multi-read AgentPlan path

The typed `AgentPlan` path is active in the production model loop for completed messages containing
multiple Tool calls.

- A batch contains two to four independent, renderable `READ_ONLY` calls.
- The bridge resolves only trusted read bindings from the shipped registry.
- Raw arguments stay turn-ephemeral and are excluded from checkpoints/diagnostics.
- Kotlin strictly parses each call and preflights the entire batch before any dispatch.
- The verified plan binds Tool identity, resolved risk/resources, canonical argument digest,
  objective digest, expiry, and step ordering.
- Execution is limited to two concurrent reads even when the batch contains four.
- The execution boundary prepares every step again and requires the same digests, so changed
  permission, setting, interlock, or Tool behavior fails the whole batch before dispatch.
- Completion callbacks may arrive out of order; receipts and grounded evidence are ordered by the
  original step ordinal before UI/persistence.
- Kotlin renders one bounded grounded answer from typed evidence and closes the pending native
  model turn without a second decode.

Writes, communication Tools, notification-content search, and any read without a Kotlin evidence
renderer cannot bind to this multi-read path.

## Grounded and terminal answers

Weather, web, calendar, alarm, route, and reminder reads produce Kotlin-owned grounded evidence.
Freshness requests cannot finish from model prose when no required trusted read completed.

A trusted completed or definitively refused write receipt is also terminal truth. Kotlin emits the
Tool-specific completion/refusal answer and does not ask the model to restate it. This prevents a
second decode from contradicting whether a calendar/reminder/memory/proposal change happened or
whether KakaoTalk/Clock merely accepted a request. Exceptions that cannot prove the write outcome
remain unknown and require owner verification.

## Room schema 11 recovery and persistence

The current app database is Room schema 11:

- `turn_outcomes` stores the content-free turn state;
- `turn_read_executions` stores up to four exact ordered Tool names;
- `agent_plan_checkpoints` and step rows store only IDs, digests, closed states, ordinals,
  revisions, and timestamps; and
- `unresolved_side_effects` stores owner-verification obligations independently of transcript and
  turn foreign keys.

`turn_outcomes.recovery_source_user_message_ordinal` is a nullable, content-free pointer used only
for bounded contextual public-search follow-ups. It keeps the follow-up's own unique outcome row
while recovery resolves the earlier authenticated USER question; schema 9→10 preserves all existing
rows with a null pointer.

The exact ordered read list, including duplicate Tool names, is copied atomically to a recovery
successor. Reservation order must match exactly; parallel completion is order-independent only after
reservation. Recovery always re-runs current consent, parse, preflight, interlock, Tool, and thermal
checks and never reuses old provider content.

Every side effect is durably armed after authorization/interlock and before ledger claim/execution.
It is never replayed after an uncertain outcome, and a second side effect is refused while the turn
already owns one unresolved target. `unresolved_side_effects` has no expiry/FK, so transcript
deletion or turn TTL cannot erase it. Only a matching definitive refusal or explicit owner check can
clear it.

One Room transaction commits assistant phase + app Tool receipt + typed Tool outcome. Another
atomic/idempotent transaction commits the final assistant phase + terminal turn transition.
Partial transcript/outcome visibility is therefore rejected.

The Tool transaction's durable marker binds turn ID, trusted ordinal, Tool risk, closed outcome,
Tool name, and exact app receipt text. Exact redelivery is a successful no-op; any conflicting reuse
of that ordinal fails closed. The ViewModel also admits only an exact Tool/outcome duplicate after
the durable/UI transition. Derived memory/proposal refresh cannot turn a committed Tool event into
a replay. The Action Ledger, not this receipt marker, remains the side-effect execution authority.

## UI and lifecycle concurrency

`ConversationMutationGate` is a process-local lease that linearizes restore, new conversation,
switch, delete, delete-all, recovery resolution, and synchronous turn start. An active-conversation
mutation cannot race another across UI and Room ownership.

Notification capture uses a separate `NotificationCaptureInterlock`. A disable request closes a
synchronous atomic gate before DataStore persistence begins. Capture, setting mutation, and erasure
share a mutex; enable is visible only after durable persistence, and failed disable stays closed.

Route, web/weather, memory, commitment proposals, proactive route planning, and daily brief share a
general `OwnerConsentInterlock`. Effective consent is always durable state intersected with the
process gate. Every uncommitted toggle closes that feature immediately; app-scope, per-feature
serialization lets only the latest request open it after a successful durable enable. Older,
failed, or cancelled mutations cannot reopen it, and stale coordinator refreshes cannot overwrite
the effective UI state. Route/search/weather recheck at their high-level gateway and before every
GET/POST, so a multi-hop call cannot start its next request after consent closes. This does not
claim cancellation of bytes already sent by an in-flight socket.

WindowManager posture plus usable bounds drive Book/Tabletop/cover/two-pane decisions. Undersized
windows and invalid/edge/oversized hinges fall back to cover. Confirmation actions remain fixed
outside the scrolling body, semantics expose roles/live typing/single switch actions, and streaming
scroll/link work is bounded. Plain-text drop merges with a newline and rejects unsafe or oversized
content without changing prompt/focus.

## Data, privacy, and provider boundaries

- Conversation content, selected memories/reminders/proposals, notification cache, settings, and
  credentials live under `noBackupFilesDir`; credentials use Android Keystore AES-GCM.
- Credential health is fail closed: only a missing encrypted path is `ABSENT`; a present empty,
  oversized, malformed, permission-untrusted, or undecryptable path is `UNREADABLE` and requires
  explicit owner repair/re-entry.
- The Action Ledger remains separate from transcript deletion and encrypted transfer.
- Encrypted transfer excludes credentials, ledger, notification rows, ordered recovery/checkpoint
  state, unresolved side effects, provider IDs, model, diagnostics, permission, and consent.
- Calendar reads use explicitly selected calendars and writes one pinned writable row. An account
  label is not provider identity.
- Route/search/weather use default-off consent, closed hosts/methods, bounded deadlines, normalized
  typed responses, and current execution-time interlocks.
- `LlmToolCall`, `TrustedToolResponse`, `ModelEvent`, `RuntimeTurnInput`, `RuntimeChunk`, and
  `AgentEvent` expose redacted metadata-only string representations; incidental logging cannot
  serialize prompts, streamed deltas, Tool arguments, or provider payloads.
- Notification capture is a default-off allowlisted local cache, not KakaoTalk history. Sharing and
  active-notification reply cannot claim delivery/read.
- Automatic episodic memory, embeddings, autonomous proposal promotion, accessibility automation,
  arbitrary Kakao recipient automation, visual/audio intake, and always-on workflows remain out of
  scope.

## Model, thermal, and release limits

LiteRT-LM is pinned and accepts only `VerifiedInstalledModel`. The production context remains 4K;
8K/16K/32K require memory, thermal, and sustained-decode evidence. Output ceilings are deterministic
128/256/384/1,024 tokens; an explicit long-form request wins over operational wording without
loosening Tool, step, deadline, or argument limits.

Thermal headroom remains sampled, but the current owner-requested foreground and background model
lanes do not shrink a request-derived ceiling or otherwise restrict work through SEVERE. CRITICAL
cancellation, higher-state aborts, and the fail-closed unknown state remain authoritative. No
performance improvement is claimed without the exact Fold8 run.

Release source generates a deterministic CycloneDX SBOM and privacy-safe provenance containing
version, Git/source-state digest, Room schema 11, model/runtime pins, lock digest, SBOM digest, and
public signing certificate identity. Each exported Room schema filename must be a positive integer
equal to that file's JSON-internal database version, and the latest validated export must equal the
`PERSONAL_EDGE_DATABASE_VERSION` compile constant. Missing, malformed, or disagreeing inputs fail
closed instead of trusting an unvalidated maximum value. `releaseGate` is the required
host-eligibility gate; it is not an installation or provider receipt.

## Verification and pending acceptance

The source as reviewed passed doctor, 19/19 host-script checks, 587 JVM cases, all module lint tasks,
signed/minified assembly, and packaged SBOM/provenance/signer verification through root
`releaseGate`. The scoped app AVD receipt has 112 cases, zero failures, and 26 owner/live skips;
the Room/data AVD suite passed 82/82. The two owner-approval Samsung Calendar classes were excluded
from the scoped app run rather than weakening their gates.

Clean-source promotion, owner-approved install/preservation, Fold8 fold/DeX/accessibility/thermal
checks, provider-live tests, and owner-controlled permission acceptance remain pending. No physical
device action was performed during this documentation update.
