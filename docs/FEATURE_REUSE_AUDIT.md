# Existing app and remote feature reuse audit

Reviewed 2026-09-05 after the owner asked specifically about overlap with existing memory.

| Capability | Existing Personal Edge owner | Remote update and decision |
| --- | --- | --- |
| Long-term memory | `MemoryRepository`, `MemoryCoordinator`, settings memory section, `memory_remember` | Keep one local Room store and the existing save/replace/reconfirm/delete UI. No second OpenClaw memory store or Mac memory plugin. Remote sharing is not implied by local memory consent. |
| Conversation history | `ConversationRepository`, existing history UI and encrypted transfer | Preserve existing records and use them. The initial remote pane held its question/answer in process memory; since 2026-09-06 a remote turn is appended to the same conversation as a local one through a narrow sink, still with no competing table, schema change or second history UI. |
| Rolling summary and recent context | `ConversationSummarizer`, `TurnContextBuilder` | Reuse these bounded policies when remote context is introduced. The installed baseline sends only the current question. The latest installed revision adds individually selected local items and an exact one-request external preview; real context transmission acceptance remains pending. |
| Answer rendering | Existing `AssistantRichText` | Reuse the same renderer for remote completed answers; do not build another Markdown/math engine. |
| Secrets | Existing Android Keystore `SecretVault` | Add only endpoint-bound Gateway credential/device-identity records. Existing NAVER/Tavily credentials remain separate and are never copied to Mac. |
| Consent | Existing `OwnerConsentInterlock` and durable settings | Remote disclosure needs its own feature boundary because data leaves the phone. Reuse the consent infrastructure; the extra crash-durable revocation barrier addresses remote transmission uncertainty, not a second memory preference. |
| Tools and writes | Existing typed Tool contracts, confirmation sheet, action ledger and execution interlocks | Remote tools must project into these contracts. Do not add an independent Android executor or let remote prose invoke platform APIs. |
| Web, calendar, routes, alarms and reminders | Existing Kotlin-owned providers and permissions | Retain these functions. A future remote model can propose an existing permitted action; duplicating provider credentials or scheduling stores on Mac is outside this acceptance. |
| Progress and cancellation | Local runtime controller and foreground policy | A separate remote run controller is needed to represent network loss and uncertain provider termination/cost. Share presentation conventions while preserving distinct execution semantics. |
| Media | Existing opt-in image/audio front ends and media no-tool rule | Initial remote route is text-only. Do not silently copy owner photos/audio or reuse media consent as external-upload approval. |

The important gap was reuse of existing context, not missing memory storage. Current source reads
the current local conversation and query-relevant memory only when the owner opens the picker,
with no selected defaults, and sends at most 8 KiB. The installed baseline additionally required a
one-request external-disclosure confirmation showing the exact combined payload; at the owner's
2026-09-06 instruction that per-question confirmation is removed, and remote mode plus connection
consent is the standing decision instead. The selection itself is unchanged and is still
revalidated immediately before dispatch: source changes, deletion, expiry, consent withdrawal,
endpoint changes and leaving the app all stop the send. It must not automatically synchronize
local memory, summaries, history, credentials, or notifications into OpenClaw. Memory deletion and
disable remain controlled by the existing local UI.

Since 2026-09-06 the remote pane's own wording must say that its question and answer are stored in
the same conversation as the local model's, because they are. It must not suggest that they expire
on process restart, and it must not suggest that saved local history or long-term memory will be
deleted.

## Evidence

- The accepted baseline APK request contains only the current question. Subsequent source adds
  `OpenClawRemoteContext.kt` as a read-only seam over the existing repositories, without new Room
  entities, database migrations, automatic uploads or a competing memory preference. Its physical
  transmission acceptance is pending. The final app gate passed 354 JVM tests, including context
  and health cases, and the latest scoped AVD UI/surface plus physical preservation/surface checks
  passed. None of these fixtures sent existing owner memory to a provider.
- Before updating the physical Fold8, the existing scoped `Fold8PreservationSnapshotTest` passed
  1/1 and reported the pinned 3,659,530,240-byte model present, credential presence intact,
  23 conversations, 104 message rows, and zero long-term-memory rows. Zero rows is a current count,
  not absence of the memory feature and not a statement about the conversation summaries.
- No memory content, conversation text, credential value, or notification body was inspected or
  exported for this audit. Current source capability is distinct from a new full device acceptance
  of every existing memory behavior.
