# Architecture review

Reviewed on 2026-08-22 against the current implementation and official Android/LiteRT-LM
documentation.

## Outcome

The core boundary is sound:

```text
LLM = judgment
Kotlin runtime = control
Tool = execution
```

The project began with one secure vertical slice: Compose text input, on-device inference, a fake
typed tool, Kotlin validation, confirmation, execution, and result reinjection. The shipped closed
registry now carries eight device Tools across calendar, alarm, notification search, route, and web
search behind the same durable ledger and execution-time interlock.

## Decisions encoded in this scaffold

1. LiteRT-LM is pinned to `0.16.1`; dynamic dependency versions are not allowed.
2. `ConversationConfig.automaticToolCalling` is always `false`. Model output is
   untrusted input and cannot execute tools directly.
3. Tool contracts are typed and separate validation from execution.
4. Communication, vehicle control, high-risk actions, and data writes require a
   confirmation policy. Read-only network Tools also require confirmation because their canonical
   inputs leave the phone. Confirmation is requested inside the orchestrator and is bound
   to an immutable canonical input, its digest, preview, and expiry. Execution uses that
   same snapshot, and expiry is checked again after the confirmation UI returns.
5. Model files are not committed or bundled in the base APK. They require revision,
   size, and SHA-256 verification plus resumable/atomic installation.
6. Accessibility automation is outside the Play-safe MVP. Kakao new-message and Samsung
   Clock UI automation belong in an explicit sideload-only experimental variant.
7. The maximum output is 1,024 tokens, not 4,000. The fake-only loop started with two model
   steps, one Tool call, and a 60-second budget; real calendar work needs a read followed by a
   write, so the loop now allows two Tool calls across four steps within 120 seconds. Revisit
   with physical-device latency receipts, not by feel.
8. Raw model thinking is neither displayed nor persisted.
9. Side-effecting tools are rejected unless the orchestrator holds the module-owned
   `PersistentActionLedger` capability. `SqliteActionLedger` now provides it: a dedicated
   no-backup database, one IMMEDIATE transaction per claim, `synchronous=FULL` so a claim is
   durable before the side effect, and fail-closed behavior on every fault. Retention and
   capacity are bounded, and a full ledger refuses rather than evicting a live key. The
   resulting guarantee is at-most-once.
10. The trusted Kotlin workflow, never model output, owns the per-turn request ID used to derive
    idempotency keys. The durable ledger blocks replay of that key across cancellation and process
    restart. A later user turn has a new request ID and is a new action; the app does not claim
    semantic deduplication across separately submitted requests.
11. `LlmRuntime` accepts an opaque `VerifiedInstalledModel`, not a raw path. Model events,
    Tool calls, cancellation, and Tool responses are typed and bound to one turn ID.
12. The demonstration Tool is truthfully `READ_ONLY` because it performs no side effect, but its
    `minimumConfirmation` raises the effective policy to explicit user confirmation. It remains a
    test fixture and is not registered in the shipped device registry. The latter explicitly maps
    exactly eight Tools; a ninth model-invented name cannot resolve.
13. The model package can support up to 32K context, but the first Android runtime budget
    is 4,096 total input/output tokens. A 32K CPU session reached 10GB RSS and was killed
    by LMK on the 12GB API 37 test AVD during first decode. Larger 8K/16K/32K budgets stay
    disabled until peak-memory, latency, and sustained-decode checks pass on the Fold8.
14. Each top-level request gets a fresh native Conversation. The Tool call and its response remain
    in that Conversation, with a native token-count guard reserving room for final output.
    Cross-turn continuity comes from bounded Room state: a sanitized, explicitly quoted summary
    and newest recent messages share a 2 KiB request envelope with trusted device context.
15. LiteRT-LM exposes native Tool arguments as a parsed Map, not raw JSON. Exact field,
    type, nesting, and post-serialization size checks remain enforced, but duplicate-key
    evidence is already lost at that SDK boundary. Raw duplicate rejection therefore
    remains an upstream API/qualification gate rather than a production claim.
16. The fake Tool's trusted result is the minimal `{"simulated":true}` receipt. It never
    echoes model-controlled arguments into the Gemma Tool-response template, and pinned
    model control-token delimiters are rejected before confirmation or execution.
17. Gradle dependencies are locked per module and checksum verified. The LiteRT-LM group
    resolves only from the dedicated Google repository declaration.
18. Physical-device diagnostics use a fixed typed schema and bounded private rotation. Prompts,
    outputs, Tool arguments, confirmation material, paths, URIs, and raw serials cannot enter that
    schema. A validated chronological SAF export gives signed releases a content-free path without
    `run-as`. Raw app logcat and bugreports remain explicit sensitive opt-ins rather than default
    evidence.
19. An `ExecutionInterlock` is evaluated twice: before the confirmation dialog, so the user is
    never asked for an impossible action, and again immediately before the durable claim. It
    re-checks runtime permissions, thermal state, and the pinned calendar, because all three can
    change while a dialog is on screen. Blocking happens before the claim, so a blocked action
    stays retryable instead of spending its idempotency key.
20. Conversations, messages, captured notifications, settings, and third-party credentials live in
    `core:data` under `noBackupFilesDir` — Room with exported schemas, a Preferences DataStore,
    and an AndroidKeyStore AES-GCM vault. The action ledger is a separate database, so clearing
    history can never reopen a replay window.
21. Calendar tools reach only the one calendar pinned in settings. NAVER Calendar's Open API is
    create-only, while NAVER officially marks Android CalDAV unsupported. The generic
    `CalendarContract` adapter is implemented and scoped, but actual NAVER publication and sync
    are not qualified. Settings expose provider account type and row ID so an email-shaped account
    name cannot masquerade as provider identity. Recurring-series updates are refused until the
    confirmation contract can express series-versus-occurrence scope.
22. Route and web search each have two independent privacy gates: a default-off persistent opt-in
    re-checked by the interlock, and a per-request confirmation bound to the canonical outbound
    strings. Credentials are masked, device-bound secrets; the optional default origin is returned
    to UI as presence only.
23. Notification retention is a read boundary, not just maintenance. Expired rows are pruned during
    capture and again before search/settings counts, so an idle listener cannot leave stale content
    readable beyond the selected window.

## Resolved product decisions

- Distribution: private sideload only, signed with one fixed personal key
  (see [`RELEASE_AND_BACKUP.md`](RELEASE_AND_BACKUP.md)).
- Calendar adapter: device `CalendarContract`; whether the official NAVER Calendar is exposed there
  was checked on the Fold8 and no NAVER-published row was found (see
  [`CALENDAR.md`](CALENDAR.md)).
- External providers: NAVER Cloud Maps for route estimates and NAVER Developers for web search,
  with user-entered Keystore credentials, persistent opt-in, and per-request confirmation.
- Sensitive stores: no Android backup; explicit local erasure; content-free diagnostics export
  only. Conversation and notification content has no general export path.

## Remaining acceptance decisions

- Whether a compatible non-NAVER `CalendarContract` calendar is acceptable, or a new documented
  NAVER-specific transport should be scoped.
- Whether and when the owner will enter live NAVER credentials and accept the providers' current
  handling/retention terms.
- Offline backup of the existing release key and timing of the destructive debug-to-release
  migration.
- Promotion of any larger model context only after CPU/GPU memory, thermal, and latency evidence.

## MVP boundary

Implemented after the secure vertical slice: web search, route estimate, generic
`CalendarContract`, standard Android alarm intents, and Kakao notification capture/search. Their
provider and physical evidence remains separately graded in `PROJECT_STATUS.md`.

Excluded: Kakao reply/send, Accessibility-driven Kakao send, full Samsung Clock editing,
Polestar control, always-on voice, multimodal input, and autonomous background workflows.

## Acceptance metrics to add

- cold/warm model load, time to first token, decode tokens/sec;
- peak resident/GPU memory, thermal state, and battery drain;
- tool-selection accuracy and valid-argument rate in Korean;
- zero confirmation bypasses and zero duplicate side effects;
- the execution-time thermal/permission/account interlock holds on the physical device;
- cancellation, timeout, process-death, and interrupted-download recovery;
- folded/unfolded, rotation, multi-window, and background/foreground behavior.
