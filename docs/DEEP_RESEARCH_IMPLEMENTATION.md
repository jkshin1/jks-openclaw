# Deep-research implementation reconciliation

Reviewed on 2026-08-26 against the attached improvement report and the current
`1.0.0-rc11` working tree. The report is design input, not an execution instruction; repository
safety policy, owner consent, and evidence boundaries remain authoritative.
Its rc9/rc10 version diagnosis is a historical snapshot; the current authoritative identity is
rc11 with Room schema 10.

## Evidence labels

- **Implemented** means a production path and regression tests exist in source.
- **Host verified** requires a successful gate from the final frozen source state.
- **Android source verified** means Android or instrumentation source compiled; it is not an
  emulator or phone receipt.
- **Emulator verified**, **physical accepted**, and **provider/live accepted** require separately
  recorded runs against the exact artifact and environment named by the receipt.

The current rc11 working tree has not been installed or tested on a physical device during this
iteration. On 2026-08-26 the frozen host source passed `./gradlew --offline releaseGate`; the
scoped API 37 AVD run recorded 112 app cases with zero failures (26 owner/live cases skipped), and
the Room/data AVD suite passed 82/82. Older phone receipts remain historical evidence for their
exact APKs only and do not qualify this source state.

## Implemented P0 work

| Recommendation | Current rc11 source | Evidence boundary |
|---|---|---|
| Release identity, manifest, SBOM, and license inventory | Release builds generate deterministic CycloneDX 1.6 SBOM and privacy-safe provenance assets. Each exported Room schema filename must equal its JSON-internal database version, and the latest validated export must equal `PERSONAL_EDGE_DATABASE_VERSION`; an unvalidated maximum JSON value is not trusted. The root `releaseGate` includes host tests, lint, signed/minified assembly, regeneration, and signer checks. | Implemented fail closed and host verified. The working tree is intentionally recorded as dirty; no APK install or physical acceptance is implied. |
| Typed multi-read `AgentPlan` | Model-produced batches may contain at most four trusted, renderable `READ_ONLY` calls. Kotlin strictly parses every argument, resolves the closed registry, verifies plan/tool/argument digests, and preflights the entire batch before dispatching any read. Execution concurrency is capped at two. Receipts are ordered by requested ordinal and Kotlin renders the single grounded terminal answer without a second model decode. | Active production model-loop path. Final host, Fold8, and provider-live qualification remain pending. |
| Per-turn Tool minimization | `TurnToolScopePolicy` derives an exact domain- and intent-specific schema set. Unclassified prose receives no Tool schema; ambiguous multi-domain write intent exposes no write Tool. Recovery receives only its exact ordered read contract. | Implemented in Kotlin/LiteRT and host verified; model/device acceptance remains external. |
| Durable recovery and process interruption | Room database schema 10 contains content-free turn outcomes, ordered read executions, plan checkpoints, a nullable contextual USER-source ordinal, and transcript-independent unresolved side effects. Interrupted plans reconcile to `CANCELLED`; they are never resumed from stored arguments or provider output. | Implemented. Runtime/process recreation and physical acceptance are pending. |
| Atomic transcript/outcome boundary | One Room transaction commits the current assistant phase, app-authored Tool receipt, and typed Tool outcome. Final assistant text and the terminal turn transition are also atomic and idempotent. A failed commit rolls back the whole group. Exact redelivery must match turn, ordinal, risk, Tool, closed outcome, and receipt text to become a no-op; conflicting ordinal reuse fails closed. | Implemented, host verified, and covered by the scoped AVD regression suite. Process-recreation acceptance remains external. |
| Uncertain side-effect verification | A side effect is armed before ledger claim/execution. `unresolved_side_effects` has no transcript or turn foreign key and no expiry, so deleting history or expiring a turn cannot erase the owner-verification obligation. Writes are never replayed automatically. | Implemented; owner/device verification UX still needs current physical acceptance. |
| Action Challenge binding | Challenge v2 binds trusted Tool/risk/capabilities, canonical-input digest, request/action/replay identity, expiry, legacy ledger identity, and owner-visible preview digest. Identity and expiry are recomputed immediately before claim. | Implemented; biometric/Keystore step-up remains a separate product/device gate. |
| Grounded read and write terminal answers | Trusted weather, web, calendar, alarm, route, and reminder results are rendered by Kotlin. Definitively completed or refused writes also end with an app-authored terminal answer, avoiding a second decode that could contradict the receipt. Unknown write outcomes remain verification-only. | Implemented; current provider/device behavior is not yet accepted. |
| Predictive thermal scheduler | Headroom remains measurable, but the owner-selected foreground and background lanes apply no app-level restriction through SEVERE. CRITICAL cancellation, higher-state aborts, and fail-closed `UNKNOWN` remain authoritative. | Implemented; the current full-budget Fold8 performance/endurance receipt is pending. |
| Adaptive Fold/DeX workspace | Window posture and usable bounds drive Book/Tabletop/cover/two-pane policy. Confirmation actions remain reachable, semantics are explicit, streaming work is bounded, and safe text drop inserts a newline without mutating prompt/focus on refusal. | Implemented with policy tests; current physical pixel/accessibility matrix is pending. |
| Conversation mutation serialization | `ConversationMutationGate` linearizes restore, new/switch/delete/delete-all, recovery resolution, and synchronous turn start so no two active-conversation mutations can cross the persistence boundary together. | Implemented; lifecycle/device acceptance pending. |
| Notification capture disable race | A synchronous process gate closes as soon as disable is requested, before the asynchronous DataStore write. A mutex serializes capture with setting/erase mutations; enable becomes visible only after durable persistence, and failed disable remains closed. | Implemented; no current physical notification capture test was run. |
| Owner-consent lifecycle races | Route, web/weather, memory, proposal, proactive-route, and daily-brief opt-ins combine durable state with a process-wide latest-request gate. Every uncommitted mutation is closed, disable is immediate, persistence is app-scope and per-feature serialized, and enable opens only after the latest durable write succeeds. Network consent is checked at the high-level gateway and before every HTTP hop. | Implemented and host/AVD regression verified. Provider-live and process-death acceptance remain external. |
| Accidental log redaction | Content-bearing `LlmToolCall`, `TrustedToolResponse`, `ModelEvent`, `RuntimeTurnInput`, `RuntimeChunk`, and `AgentEvent` string representations emit redacted metadata only, not prompts, deltas, arguments, or provider payloads. | Relevant module JVM tests and the final host gate passed. |

## Implemented P1 work

| Recommendation | Current rc11 source | Evidence boundary |
|---|---|---|
| Memory provenance | Every injected approved memory has owner-visible category, bounded content, relevance reason, confirmation time, and optional expiry. | Implemented; automatic episodic capture and embeddings remain off. |
| Proactive Opportunity Engine | A default-off deterministic kernel emits review-only proposals from typed content-free events. It cannot schedule, promote, or execute autonomously. | Kernel/adapter implemented; scheduling and UI activation remain off. |
| Cross-app text and DeX controls | Composer accepts one `text/plain` item, rejects URI/Intent/image/multiple items, inserts a newline-delimited merge, and never auto-sends. | Implemented; image drag and URI lifetime handling are excluded. |
| Model/device evaluation gate | The fixed 26-case/23-category corpus has pinned order and content. Scoring rejects malformed telemetry, non-finite/negative values, incomplete coverage, and every unexpected write selection including write-to-different-write substitution. | Host gate verified for corpus/policy. Same-condition verified-artifact Fold8 runs remain pending. |
| Encrypted user transfer | Import validates archive/schema markers, declared lengths/counts, collection caps, duplicate IDs, parent relations, conversation ordinals, and summary boundaries before one transaction writes rows. | Codec tests exist; end-to-end SAF/new-device acceptance is pending. |
| Credential health boundary | Only a missing encrypted-credential path is `ABSENT`. A present path that is empty, oversized, malformed, permission-untrusted, or otherwise unreadable/decryption-failed is `UNREADABLE` and requires owner repair instead of silent absence. | Implemented fail closed; current owner/device recovery acceptance is pending. |

## Report-level recommendation reconciliation

| Report recommendation | rc11 disposition |
|---|---|
| Release manifest, SBOM, license inventory | Implemented and host verified. |
| Typed AgentPlan DAG/checkpoint executor | Partially implemented: up to four independent read-only steps, two concurrent, with durable content-free checkpoints. General dependency DAGs and write-plan execution remain excluded. |
| Small function router plus E4B | Deterministic Kotlin routing is active; a separately resident small-model artifact is deferred. |
| Predictive thermal/model scheduler | Predictive headroom measurement is active. The current owner-requested foreground and background model lanes apply no app-level restriction through SEVERE; CRITICAL and higher-state stops remain active, and UNKNOWN fails closed. Model selection/residency scheduling remains deferred. |
| MTP artifact A/B | Deferred to independently verified artifacts and same-condition Fold8 evaluation. |
| Material adaptive, WindowManager, DeX, DnD | Window posture, bounded custom adaptive layout, freeform/keyboard policy, and text-only drop are implemented. Material3 Adaptive scaffolding and URI/image drop remain deferred. |
| Action Challenge plus biometric step-up | Challenge v2 and execution-time rebinding are implemented; biometric/auth-bound Keystore approval remains a device/product gate. |
| Semantic/episodic Memory 2.0 | Typed provenance and lexical retrieval are implemented; embeddings and automatic episodic retention remain off. |
| Opportunity/proposal engine | Default-off review-only kernel and adapters are implemented; scheduler/UI activation and autonomous promotion are absent. |
| Visual OCR/vision and push-to-talk | Deferred because they add model artifacts, permissions, capture, retention, and accessibility surfaces. |
| Freshness-aware offline cache | Deferred pending explicit retention/privacy/product contracts. |
| Compile-time ToolModule/KSP registry | Optional refactor deferred; the current static closed registry remains typed and fail closed. |
| Health dashboard and benchmark pipeline | Content-free diagnostics and strict evaluation gates exist; dashboard, Baseline Profile, Macrobenchmark, and Perfetto procedures remain deferred. |
| Cloud reasoning/PII gateway, E2EE sync, SmartThings/UWB | P2 scope deferred. |
| NPU backend and S Pen-specific UX | Research or intentionally excluded scope. |

## Explicitly deferred scope

The deterministic direct router covers high-confidence weather, public search, next-alarm, and
reminder-query intents, but a separately shipped small routing model was not introduced. Semantic
embeddings, automatic episodic retention, MTP/E2B model promotion, and biometric/Keystore step-up
remain artifact, quality, or device gates rather than inferred improvements.

Freshness-aware offline caches, optional cloud reasoning/PII gateways, E2EE multi-device sync,
SmartThings/UWB actions, NPU backends, camera/photo/audio intake, always-on voice, and S Pen-specific
UX were not introduced. They require new retention, consent, service, artifact, permission, or
hardware gates. Baseline Profile/Macrobenchmark work also remains deferred until a repeatable
emulator/physical procedure exists; no performance evidence is manufactured from host policy tests.

## Recorded verification and remaining boundary

The frozen rc11 source passed:

```bash
source ./scripts/android-env.sh
./scripts/doctor.sh
./scripts/test-host-scripts.sh
./gradlew --offline releaseGate
```

The host gate includes 587 JVM cases, all module lint tasks, signed/minified release assembly, and
fresh SBOM/provenance/signer verification. `test-host-scripts.sh` passed 19/19 as the suite stood
then, and the fixed model
corpus validated 26 cases across 23 categories. The API 37 AVD receipts above are separate from the
host gate and excluded the two Samsung Calendar owner-approval classes.

APK installation, Fold8 behavior, provider accounts, owner-controlled permissions, thermal
performance, and migration preservation still require separately approved runs.
See `TURN_RECOVERY.md`, `RELEASE_PROVENANCE.md`, `MODEL_EVALUATION.md`,
`PROACTIVE_OPPORTUNITY_ENGINE.md`, and `PROJECT_STATUS.md` for subsystem boundaries.
