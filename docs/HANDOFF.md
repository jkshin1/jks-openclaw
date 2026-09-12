# Personal Edge handoff

**Active Mac/Telegram handoff:** [OPENCLAW_TELEGRAM.md](OPENCLAW_TELEGRAM.md).
The Android and tool-free Gateway instructions below are historical; their pending gates are not
the active project's work queue.

> **2026-09-06 — retired. Do not pick this up as active work.** The owner stopped development and
> retired the app together with its schedule/reminder/alarm/notification purpose. Assistant work
> moved to the macOS OpenClaw Gateway over Telegram. The final state is tagged `rc11-final`; see the
> stop notice at the top of [PROJECT_STATUS.md](PROJECT_STATUS.md). Everything below describes that
> frozen state and stays accurate for it.

Updated 2026-09-05 for the current rc11 working tree. Read this first, then
[`PROJECT_STATUS.md`](PROJECT_STATUS.md) for the evidence ledger and [`../AGENTS.md`](../AGENTS.md)
before any device work.

## Current truth

**Owner-selected stop:** one real Fold8 Korean answer passed after HTTPS/device pairing. Further
work is paused to conserve usage. Read [REMOTE_AGENT_RESUME.md](REMOTE_AGENT_RESUME.md) before
continuing; it distinguishes the installed APK from later unintegrated proposal source.

- Source identity remains `versionCode=11`, `versionName=1.0.0-rc11`.
- The app Room database is schema 11.
- **OpenClaw/GLM is an optional, default-off remote mode.** The new
  `core:openclaw` protocol-v4 client and app foreground coordinator implement endpoint-bound
  Keystore records, typed nested connect failures, bounded reconnect, one-shot tool-free model
  runs, progress/status/wait/cancel, and close-time best-effort abort queueing. They pass focused
  unit/lint plus app release-Kotlin compilation. The current update wires an explicit local/remote
  selector, consent, masked connection setup, progress, answer and cancellation into the product UI.
  Full host test/lint/debug/release assembly passed, AVD remote UI 3/3 passed, and the signed
  Fold8 update preserved all content-free owner snapshots. The Fold8 remote surface test passed;
  actual pairing/inference remains a separate gate. See `REMOTE_AGENT_ACCEPTANCE.md`.
  The existing local memory/history are retained; see `FEATURE_REUSE_AUDIT.md`.
- **The current Mac Gateway is hardened and managed adoption passed on 2026-09-05.** Exact OpenClaw
  2026.8.1 / Node 26 runs as `personaledge`, KeepAlive and loopback-only on port 18789, with token
  auth, wildcard Tool denial, GLM-5.3 Flash-only model policy, and clean SecretRefs. The approved
  plaintext migration is complete. The owner-approved reapplication published the schema-4 final
  manifest and proved live `tools.effective=0` with its incognito probe deleted. The watchdog's
  first scheduled execution exited 0 and reported healthy policy, secrets, and live plugins.
  The verified backup and previous workspace are retained under `adoption-20260905T120932Z` and
  `quarantine/adopt-20260905T120932Z/workspace-before-adopt`. The prior local-CLI probe incompatibility
  was fixed using the supported canonical incognito key and one-shot exact-key cleanup.
  The owner-approved GLM smoke sent one actual OpenRouter POST, which returned HTTP 404:
  `No endpoints found that can handle the requested parameters`. It did not produce an answer;
  the initial Gateway inference did not succeed. A later owner-authorized direct-provider
  differential succeeded with `max_tokens` and reproduced 404 by changing only that field to
  `max_completion_tokens`; see `REMOTE_AGENT_ACCEPTANCE.md`. The owner now authorizes bounded
  test calls without repeated per-call approval. The 13:39 UTC Gateway smoke now passes exact model/reply, zero tools and zero run-owned residue. Fold8 inference remains separate.
  An earlier attempt stopped before provider dispatch because Docker was absent. That prerequisite
  is now supplied by a dedicated Colima `personaledge` VM and login LaunchAgent, with only the
  sandbox directory mounted. Both attempts' exact run-owned logical SQLite row counts are zero.
  The locally patched 2026.8.1 runtime and Docker-aware watchdog passed live deployment checks.
  Schema-2 soak now checks Docker/VM/daemon continuity, but no 24-hour window has completed.
  A real Colima recovery drill exposed a default-context transition; its correction is in progress.
  The owner has since installed/logged in Tailscale on Mac and Fold8, with both online in one
  tailnet, and enabled `pmset autorestart=1` (read back with sleep=0 and standby=0). HTTPS Serve,
  full pairing and observation are recorded separately below as they complete. FileVault still
  requires owner login after a cold boot.
- **2026-09-06: the remote engine no longer asks per question, and stores its turns.** The owner
  asked for both. The per-question external-disclosure dialog is gone: remote mode plus connection
  consent is the standing decision, and the send control dispatches the visible question. Every
  other send check stays — exact typed question, only the ticked quotes, revalidation against
  current storage and memory consent immediately before dispatch, and a hard stop when the prompt,
  endpoint, consent or foreground changes mid-send. The trade the owner accepted is losing the last
  look at the composed outbound text; the connection-consent dialog says so. Remote turns are now
  written to the same Room conversation as local ones through `OpenClawRemoteTranscript`
  (`PersonalEdgeViewModel.RemoteConversationTranscript`): question before the run starts, answer at
  any terminal including a cancelled or connection-lost partial, only the typed question and never
  the quotes, and a failed write reported rather than claimed. The remote pane renders that shared
  conversation with the local `ChatTranscript`. **Accepted on the Fold8 the same day**: signed
  same-certificate update with the pulled-back hash matching and `firstInstallTime` preserved, the
  provider-free surface test 1/1, and the new opt-in
  `Fold8OpenClawLiveAcceptanceTest#oneSendNeedsNoApprovalAndIsStoredInTheSharedConversation` 1/1
  in 8.767 s on the stored credential — one send, no approval, a useful Korean answer, and exactly
  two rows read back from Room that survived a force-stop (104 to 106 messages). See
  `PROJECT_STATUS.md` for the full receipt. **Cancellation and two related defects were then fixed
  and accepted on the phone.** (1) The Gateway takes its per-run timeout from the value this app
  sends, so the old 60 s was why a long answer produced nothing; it is now 180 s and the 40-item
  question completes in 120.8 s. No Mac configuration was changed. (2) OpenClaw 2026.8.1
  deliberately surfaces an externally aborted run as an *error* (`action: "surface_error"`), so a
  cancelled run was reported as failed; the client now reads a `FAILED` terminal as `CANCELLED`
  only when the Gateway's own `chat.abort` acknowledgement named this exact live run — never from
  the error frame, never for a timeout, and never without that acknowledgement. Cancel to terminal
  measured 102 ms. (3) A cancel tapped before the run id exists is now held and sent when start
  returns, instead of sealing the socket and leaving the run to cost until its timeout. There is
  still no live token streaming in this mode, and that stays deliberate: internal session effects
  are what keep session residue off the Mac. **The last three open remote gates then passed on the
  phone too**: network loss during a live run seals it with the uncertainty terminal and no invented
  answer (radios dropped by the host and verified restored afterwards); an actual process death with
  a run in flight leaves the question as the last row, reconnects nothing and restores no remote
  text; and the context picker was proved by transmission, with a planted nonce appearing in the
  answer to a question that did not contain it. See `PROJECT_STATUS.md`.
- **Newest delta: photo and voice input.** See [`MULTIMODAL_INPUT.md`](MULTIMODAL_INPUT.md) for the
  design and the open acceptance list. The one thing to internalise before touching it: a turn
  carrying media is given no Tool schema at all, enforced in three independent places. Voice
  commands reach a Tool only through dictation, which puts the transcript in the composer for the
  owner to read and send. Audio stays in memory; an external camera briefly writes one app-private
  cache file whose deletion is attempted after read, at stale process start, and before a new
  capture. Payload never enters Room, transfer, or diagnostics. Room and transfer keep only a
  canonical content-free kind/source/seconds code on the USER row.
- **Three stale guards in this tree were corrected, not worked around.** `app/gradle.lockfile` was
  missing `debugAndroidTestRuntimeClasspath` for 14 already-pinned coordinates, which made `lint`
  and every androidTest assembly fail on the untouched tree; the regenerated lock adds that one
  configuration and changes no module or version. `scripts/run-avd-regression.sh` claimed 34 app
  androidTest files when 35 existed, 119 app methods when 126 were selected, and 10 `core:llm`
  methods when 12 were; `scripts/run-avd-release-readiness.sh` carried the same stale 34-file
  inventory. Those are now measured against a real emulator run. If you see a similar
  count mismatch, measure it before editing the number.
- The 2026-08-30 automatic/contextual web-search delta passed the standard host gate
  (`test lint assembleDebug assembleRelease`) plus scoped API 37 AVD regression. The later
  model-evaluation, Kakao notification-scope, and Qwen core-test delta passed the final root
  `releaseGate` plus the separate forced Qwen JVM/lint/APK host gate. The subsequent answer-
  completion and contextual web-synthesis budget delta also passed `releaseGate` and the release
  androidTest assembly before the scoped Fold8 receipt below.
- The current answer-quality delta is implemented. It adds a separate eight-case dialogue screen,
  four-case production-path Fold8 acceptance, bounded E4B thinking, relevant-history priority,
  newest-correction handling, substantive no-Tool answer checks, and a 1,408-byte send/64-KiB
  visible-draft input policy. After the earlier format, metadata, and forecast-budget regressions,
  the pre-thought-UI candidate passed all four fixed quality cases three consecutive times (12/12)
  while the Fold8 naturally progressed from NONE through SEVERE. On 2026-09-01 the final code
  candidate passed the same four cases again in 85.001 seconds with a 1,024-token request ceiling,
  then passed a 97.449-second sustained GPU turn while SEVERE without app-level token reduction or
  cancellation. The sustained turn produced 147 thought updates, cleared the ephemeral thought at
  completion, changed battery temperature from 34.2 to 35.5 C while plugged in, and left reported
  capacity at 94%. These are bounded fixed-case and sustained-turn receipts, not general semantic
  proof or proof that all 1,024 available output tokens were consumed.
- A later intent/search-context audit reproduced the owner's complete bad-answer screenshot chain,
  including the intermediate `내가 뭘 물어봤지? 위 대답이 적절해?` review. The bounded Room
  resolver may now cross exactly one completed, closed-form answer-review turn and a short run of
  search corrections to recover the original owner USER row. Appended private, write, or new-topic
  text fails the full-match review grammar and stops recovery. Current-officeholder evidence no
  longer treats historical `당선`, `취임`, or similar event wording as proof of current status.
  Explicit public software subjects followed by `최신 버전`, `최신 안정 버전`, `최신 릴리스`,
  or `최근 릴리스` now take the immediate grounded-search path; local `내/우리 앱`, subjectless,
  compound, current-installed-version, and install requests remain closed.
- Media turns now receive the same trusted device clock, bounded prior conversation, allowed
  memory, and rolling summary as text turns. Context is built before the current content-free
  attachment USER row is stored, completion stays pinned to the captured conversation even if the
  UI switches threads, and successful media turns trigger normal background compaction. Tight
  context budgets reserve both a bounded summary slice and the newest user/assistant pair.
- The same final code candidate displayed actual LiteRT thought-channel text inside the expandable
  `생각 중` disclosure on the normal dark MainActivity. Expand, live-stream, collapse, and final
  clearing were observed. The earlier all-white screen was isolated to the instrumentation-only
  empty Activity theme; the test manifest now supplies a black test theme. Completed Assistant
  messages use a bounded local Markdown/math renderer, and the Fold8 displayed bold headings and
  centred equations without exposing the raw `**`, dollar delimiters, or `\\text` commands. Active
  answer streaming and thought text remain plain.
- Provenance records the source as dirty, so clean-source promotion is still open work. Its
  `source.stateSha256` covers the whole working tree, so editing any file — including these
  documents — changes the packaged provenance and therefore the APK hash, while an unchanged tree
  rebuilds byte-identically. Compare code by diffing the APK entries before assuming a real drift.
- An earlier rc11 source snapshot was installed on the Fold8 with a same-certificate
  `adb install -r`, and the
  pulled-back `base.apk` hash equals the build under test
  (`90d353f195f36d5fbd3ca133f5a895fd76d80eb55e418fd1e4a70d0a0eb91b1e`). Two scoped acceptance cases
  ran there — `PastedMailScheduleAcceptanceTest` and the alternate-phrasing case of
  `PublicPersonSearchLiveAcceptanceTest` — 1 case and 0 failures each, with the write confirmation
  denied. Those receipts predate the current model-evaluation, Kakao scope, and Qwen test changes;
  nothing in the current tree is physically qualified by them.
- On 2026-08-30 the owner-approved same-certificate release update installed and pulled back the
  exact app/test APKs `978e97c4...0448` / `9853cac3...9d7` on SM-F971N. The two-turn screenshot
  movie scenario passed 1/1 in 23.401 seconds with the title and a stable identity fact in the body,
  complete sentence endings before the source section, and one or two HTTPS sources. Content-free
  before/after snapshots were identical, the 3.66 GB model remained present, `firstInstallTime`
  was unchanged, post-run thermal status was 0, and system exit history showed no crash, ANR, or
  LMK. The detailed contextual web lane is capped at 1,024 output tokens; 4K total context,
  structured 384, CRITICAL cancellation, and the existing input envelope remain. The current
  owner-requested measurement policy keeps each request-derived ceiling unchanged through SEVERE,
  including the 1,024-token long-form ceiling. The owner's latest correction applies the same
  boundary to background summarization: no app-level thermal restriction occurs before CRITICAL.
  Current source routes LiteRT's dedicated thought channel into an expandable `생각 중`
  disclosure while the latest Assistant entry is blank. Expansion shows the live raw thought text
  as plain text; that ephemeral ViewModel field never enters Room, recovery state, summaries,
  restored history, or diagnostics. Per-thread compaction now triggers at ten pending messages or
  1,536 UTF-8 source bytes and replaces one validated 480-byte latest-state capsule while retaining
  recent raw turns. The scoped device policy suite passed 23/23 on the same compaction code; wider
  semantic-recall quality remains a separate acceptance gate.
  This document was updated after the frozen APK receipt and therefore changes only whole-tree
  provenance if rebuilt. Wider provider/fallback, fold/DeX, and model A/B acceptance remain open.

- The 2026-09-01 photo/voice delta passed `doctor.sh`, `test-host-scripts.sh` 32/32, a forced
  `./gradlew --offline test --rerun-tasks` (967 same-run JVM cases, zero failures),
  `./gradlew --offline test lint assembleDebug assembleRelease`, and
  `./gradlew --offline releaseGate` with regenerated SBOM/provenance recording
  `database.schemaVersion=11` and `source.dirty=true`. On the account-free API 37 ARM64 foldable
  AVD started `-read-only -no-snapshot-save`, `scripts/run-avd-regression.sh` selected 267 reviewed
  methods: 237 passed, 30 reached reviewed assumption guards, none failed.
  `scripts/run-avd-release-readiness.sh` also passed on the same disposable AVD against a matched
  owner-signed minified release pair (34 selected, 1 passed, 33 guarded, 0 failed, certificate
  `e0f66d4b...457a`), which is what shows the new instrumentation classes still link against the
  R8-shrunk release app. Both lanes were rerun against the final source, binding the receipt to
  app APK `9a77f9d0...b06ce` and test APK `74530d53...e88a`; the documentation edits that record
  this change the packaged whole-tree provenance and therefore the APK hash. No Fold8 was connected,
  inspected, installed, or changed for those AVD lanes, and those lanes contain no media-inference
  receipt. They also predate the current cache-sweep, transfer/context, and fresh-runtime test
  corrections. After those corrections and the grounded-web journey were added, the current code
  was revalidated on a fresh read-only, no-snapshot API 37 ARM64 foldable AVD. The debug lane
  selected 279 reviewed methods: 248 passed, 31 reached intentional guards, and none failed. The
  matched minified release lane selected 35 methods: its ABI smoke passed and 34 canary/live
  methods reached their reviewed guards, with zero failures under the unchanged owner
  certificate. This latest run covers the cache sweep, attachment transfer/context, mirrored EXIF
  handling, provider-free grounded-web journey, the complete answer-review/re-search chain, and its
  appended-write rejection. A subsequent final-code rerun produced the same 279/248/31/0 debug
  and 35/1/34/0 minified-release counts after the current-evidence, media-context, and software-
  release routing changes. It is emulator evidence only; no Fold8 was connected, inspected,
  installed, or changed.
- **The 2026-09-01 Fold8 session changed the design.** A same-certificate release update installed
  cleanly (pulled-back hash matched, `firstInstallTime` preserved, before/after preservation
  snapshots identical), and three things came out of it. `EngineConfig` needs `visionBackend`,
  `audioBackend`, and `maxNumImages` or a media turn dies with `Vision executor should not be null`
  *after* the image has already been decoded. With those set, a photo genuinely works: the model
  read back the six digits rendered on a synthetic card, with no Tool call. And loading the
  encoders costs the GPU backend for the entire engine — the same text turn went 20,640 ms on GPU
  to 31,534 ms on CPU, with PSS at 6.21 GB — so encoders now load only for the modalities the owner
  enabled, and that setting applies from the next app start, exactly like the backend choice. See
  [`MULTIMODAL_INPUT.md`](MULTIMODAL_INPUT.md) for the native logs and the full table.
- **The corrected media measurement is done, and both documented figures held.** On 2026-09-01
  `Fold8MediaTurnAcceptanceTest` passed on the phone, 1 case in 82.714 s on CPU. With a fresh
  runtime and exactly one turn per observation, and decode capped at 32 tokens, one image cost
  **258 context tokens** (documented 256) and a 15-second clip cost **374** (24.93/s against a
  documented 25/s). The image turn returned exactly the six digits rendered on the synthetic card
  and no turn produced a Tool call. The earlier ~220-token figure came from an invalid comparison
  and is superseded. Preservation snapshots before and after were identical and `firstInstallTime`
  was preserved.

Do not promote rc11 or reuse an older APK hash for this changed source. Host implementation work is
complete, but clean-source, device, provider, and owner-controlled acceptance gates remain below.

## What is implemented in current source

### Minimal Tool exposure and grounded execution

`TurnToolScopePolicy` exposes only the exact Tool names needed for the current domain and read/write
intent. Unclassified prompts receive no Tool schema. Recovery gets only its exact ordered read list.

Line breaks and tabs are ordinary text. Treating them as unsafe silently removed the whole schema
from every pasted mail or multi-line note, which left the model able only to talk about a request
it could not perform. Every other control and format character still clears the scope.

Ambiguous multi-domain write intent still exposes no write Tool. It now ends the turn with one
app-authored question naming the writable domains instead of requiring a Tool the turn was given no
way to call. The owner's answer selects exactly one domain and resumes the original request text,
so dates and details come from the request rather than from the one-word reply. A reply that
carries no domain of its own may also reuse the previous request's scope. That carry-over is
in-process only, is dropped whenever the visible conversation changes, and is never a durable
authorization: after process death the next reply is classified normally.

A request that names a calendar date is scoped to the reminder domain rather than the clock alarm,
because `alarm_set` refuses a named date by contract and would otherwise be the only write Tool a
dated "알람" request could see.

The model loop supports a safe multi-read `AgentPlan` batch:

- two to four trusted, independent, Kotlin-renderable reads;
- strict per-call parsing and whole-batch preflight before any dispatch;
- verified Tool/risk/resource/argument/plan digests;
- at most two reads executing concurrently;
- ordered receipts even when callbacks complete out of order; and
- one Kotlin-grounded terminal answer without a second decode.

This production path is active. Writes, communication, notification-content search, and reads
without a Kotlin renderer cannot bind to it.

### Web answer and follow-up boundary

Explicit public searches reduce the request to its subject before any network call. A closed,
conservative public-knowledge policy now also gives a public movie/book/work/technology/history
question access to `web_search` without requiring the owner to add the word "검색". The first local
answer is buffered: only an explicit recognized knowledge-gap answer triggers one automatic search,
and an ordinary complete answer remains local. This is not a general browser or fact checker;
confidently wrong prose without a recognized knowledge-gap marker is not automatically verified.

A second, stricter grammar handles volatile `current entity + office + who/name` questions. For
example, `현재 대한민국 대통령이 누구야?` is converted from owner text to the bounded query
`대한민국 현직 대통령 이름 공식` and executes `web_search` before any local answer is decoded.
This path is mandatory grounding, not the optional knowledge-gap path. Historical qualifiers,
entityless references, private/sensitive text, writes, and generic `우리나라`/`그 회사` references
do not enter it.

A subjectless follow-up such as `잘 모르겠으면 웹에서 찾아서 알려줘` never becomes the literal
query `잘 모르겠으면`. It ordinarily inherits only the immediately preceding completed USER
request in the same conversation. If a failed search correction leaves a trailing USER row,
`웹 검색 할 수 있잖아` or an explicit `첫 질문` reference may skip at most that bounded run of
closed correction rows and recover the earlier completed owner question. Any unrelated or unsafe
USER row stops the walk. Assistant text, summaries, memory, Tool/provider output, private/sensitive
text, writes, communication, weather, route, and other provider-specific domains cannot supply that
query. The new turn keeps its own durable identity while Room stores only a content-free source-
message ordinal for recovery.

Person and topic reference phrases are trimmed from the tail of an explicit query, including the
`에 대한`/`에 관한` forms alongside `에 대해`; whatever survives is sent to the provider literally.
Bare particles that also end ordinary nouns are deliberately not trimmed. Generic subjectless
queries without a safe preceding owner request fail closed instead of searching conversational
boilerplate.

Public searches are not rendered as a provider dump. The You.com quality gate and the final answer
policy share the same NFKC/spacing-tolerant lexical relevance check, so several unrelated rich hits
no longer count as a successful search. A relevance-poor primary may use the existing one-shot
Tavily fallback only when its optional key is usable. Kotlin then re-scores query relevance,
selects at most two HTTPS records, and removes unsolicited obituary, bereavement, personnel-list,
and dense name-list noise. One optional tool-free local decode may turn only that selected evidence
into short Korean prose; Kotlin rejects empty-evidence output, model links/source sections/lists,
unsupported stable or normalized Korean claim terms, and person prose that loses the requested
name. The identity caveat and source links are always app-owned. Invalid, failed, timed-out, or
Tool-producing synthesis falls back to bounded selected evidence without another web request.

Current-officeholder searches add an answerability gate above lexical relevance. A result must
directly connect a plausible name to the requested role and must either mark that relation as
current or come from a government domain. Constitution, election-method, and term-only pages make
the primary provider insufficient and therefore permit the existing single Tavily attempt; they are
never rendered as a substitute answer. Conflicting names fail closed unless one consistent
government-source name resolves them. Synthesis must put that name and role in the first sentence;
the deterministic fallback can state the same evidence-backed name, but otherwise reports that no
direct current-role evidence was found.

Closed requests that summarize, organize, compare, or extract sources from the previous search
answer receive no Tool schema and cannot execute a hallucinated repeat search. The context builder
preserves both the lead and source tail of a long immediately preceding assistant answer and adds a
trusted no-new-search instruction. Explicit re-search wording still starts a fresh read.

### Recovery and write truth

Room schema 11 owns content-free turn outcomes, up to four ordered read executions, plan checkpoints,
and independent unresolved side effects. Recovery must reserve the predecessor's exact ordered Tool
list; missing, extra, substituted, or reordered calls fail before execution. It performs fresh
consent, parse, whole-batch preflight, interlock, thermal, and provider reads. Old payloads are never
reused.

Schema 10 adds nullable `recovery_source_user_message_ordinal`. A contextual follow-up is uniquely
bound to its own current USER message while recovery resolves the earlier owner question through
that content-free pointer. This preserves the completed predecessor outcome and the one-outcome-per-
user-message uniqueness boundary.

Every side effect is armed after current authorization/interlock but before ledger claim and Tool
call. Unknown writes are not replayed. `unresolved_side_effects` has no transcript/turn foreign key
or expiry, so deleting chat or turn GC cannot erase the owner-verification obligation.

The current assistant phase, app-authored receipt, and typed Tool outcome commit in one Room
transaction. Final assistant text and terminal turn state also commit atomically/idempotently.
Definitively completed/refused writes receive Kotlin-owned terminal answers; unknown writes remain
verification-only.

Tool commit redelivery is exact: turn ID, trusted ordinal, risk, closed outcome, Tool name, and
app-authored receipt must all match. An exact duplicate is a no-op; a conflicting reuse fails
closed. The ViewModel marks an ordinal processed only after durable commit and UI receipt
transition, so a later derived memory/proposal refresh failure cannot duplicate or relabel it. The
Action Ledger remains the independent authority for preventing repeated side-effect execution.

### UI/lifecycle serialization

`ConversationMutationGate` linearizes restore, new/switch/delete/delete-all, recovery resolution,
and synchronous turn start. The active conversation cannot be mutated concurrently across the
UI/Room boundary.

Fold/DeX policy validates minimum height, pane widths, and usable hinge geometry before Book,
Tabletop, or two-pane classification. Confirmation actions stay pinned outside scrolling content.
Chat roles and typing status have semantics, switches expose one action, streaming link/scroll work
is bounded, and safe text drop uses a newline and makes no prompt/focus mutation on refusal.

### Notification capture race

Turning capture off closes an atomic process gate synchronously before asynchronous DataStore
persistence. Listener capture, setting changes, and erase share a mutex. Enabling becomes visible
only after durable persistence; a failed disable remains closed. Capture stays default-off,
allowlisted, bounded, hostile-text sanitized, and excluded from backup/transfer.

### Owner-consent mutation races

Route, web/weather, memory, commitment proposals, proactive route planning, and daily brief use one
process-wide consent contract. Effective state is `durable setting && process gate`. Every pending
enable or disable closes the gate immediately; writes are serialized per feature in an
application-owned scope, and only the latest request may open it after durable enable succeeds.
Older, failed, or cancelled writes remain closed, while coordinators discard stale refresh results.
Route/search/weather recheck at their high-level gateway and before each GET/POST, including
geocode/directions and search fallback hops. The contract does not claim cancellation of an HTTP
request whose bytes were already sent.

### Data and credential boundaries

Encrypted transfer validates versions, declarations, lengths, counts, UTF-8, collection caps,
duplicate IDs, parents, per-conversation ordinal continuity, summary boundaries, and overflow before
transactional import. The current Room schema is 11. Archive envelope v1 uses transfer schema
`6`/payload `2`, which preserves only producer-canonical content-free attachment codes on USER
rows; legacy schema `5`/payload `1` remains importable with the attachment field set to null.

Credentials remain Android-Keystore encrypted and values never enter UI, logs, diagnostics, model
context, transfer, or provenance. Credential health is three-state: a missing path is `ABSENT`; a
present path whose ciphertext is empty, oversized, malformed, permission-untrusted, or otherwise
undecryptable is `UNREADABLE`, never silently downgraded to absent. `UNREADABLE` requires explicit
owner repair/re-entry.

### Release provenance

Release provenance records Room schema 11 only after every exported schema filename is a positive
integer equal to that file's JSON-internal database version and the latest validated export equals
the `PERSONAL_EDGE_DATABASE_VERSION` compile constant. It no longer trusts an unvalidated maximum
JSON filename/value. A missing schema set, malformed file/value, filename-to-JSON mismatch, or
latest-to-source mismatch stops generation or verification. Root `releaseGate` passed with a
signed/minified rc11 APK, regenerated SBOM/provenance, and matching signing certificate. The
provenance correctly records this uncommitted working tree as dirty.

## Non-negotiable safety boundaries

- Keep `automaticToolCalling=false`; LiteRT output is untrusted.
- A turn carrying a photo or a voice clip gets no Tool schema. Do not add "just one read Tool" to a
  media turn: an attachment can contain text that reads like an instruction, and the empty scope is
  what makes that harmless. Voice reaches a Tool only through dictation, after the owner has read
  and sent the transcript themselves.
- Media payload never enters UI state, Room, the transfer archive, or diagnostics. Audio stays in
  memory. External camera capture necessarily uses one private-cache file; deletion is best effort
  after read, for stale files at process start, and before every new capture. Room and transfer keep
  only a canonical content-free USER kind/source/seconds code.
- Never weaken confirmation, Action Ledger, execution interlock, thermal, permission, consent,
  credential, or provider gates to make a test pass.
- Read-only network Tools require explicit persistent consent rechecked at execution. Writes and
  communication retain explicit confirmation.
- Unknown side effects require owner verification and no automatic retry.
- Do not include prompts, answers, arguments, provider bodies, credentials, confirmation text,
  paths, or raw serials in diagnostics.
- Content-bearing LLM/Tool/runtime event objects render only redacted metadata in `toString()`;
  preserve that boundary when adding logging or diagnostics.
- Calendar labels/email-shaped account names are not provider identity. Read/write only the selected
  provider rows and never delete events whose origin is not established.
- Notification capture is a local arrival cache, not KakaoTalk history. Share/reply cannot claim
  delivery or read.
- User-data transfer never includes credentials, ledger, notification content, recovery/checkpoint
  rows, unresolved writes, provider IDs, model, diagnostics, permission, or consent.

## Recorded validation

The 2026-08-30 web-search delta passed the following host checks:

```bash
source ./scripts/android-env.sh
./scripts/doctor.sh
./scripts/test-host-scripts.sh
./gradlew --offline test lint assembleDebug assembleRelease
```

`doctor.sh` passed, host scripts passed 20/20, and Gradle completed 533 actionable tasks with
`BUILD SUCCESSFUL`. On the already running API 37 emulator only, the scoped
`ChatHistoryCoordinatorTest` suite passed 25/25 and the scoped
`PersonalEdgeDatabaseMigrationTest,TurnOutcomeRepositoryTest` suite passed 25/25. These runs cover
the contextual query source, schema 9→10 migration, and durable recovery pointer. They did not
contact You.com/Tavily, run the real LiteRT model, install an APK on the Fold8, or inspect/change
phone data.

The following host gate passed on 2026-08-27:

```bash
source ./scripts/android-env.sh
./scripts/doctor.sh
./scripts/test-host-scripts.sh
./gradlew --offline releaseGate
```

Record failures honestly; do not edit thresholds to obtain a pass. A host pass would still not be
an installation, Fold8, provider, or preservation receipt. The recorded host scope is 596 JVM
cases, 19/19 host-script checks, all module lint tasks, signed/minified assembly, and packaged
SBOM/provenance/signer verification. The fixed model corpus validated 26 cases/23 categories.

That 19/19 receipt describes the suite as it stood on 2026-08-27 and is left unchanged.
`scripts/test-model-eval-harness.sh` was added afterwards and is wired into
`test-host-scripts.sh`, so the suite held 20 checks at that point.

A second host gate ran on 2026-08-28 for the tree that includes that harness. `doctor.sh`
passed, `test-host-scripts.sh` reported 20/20, and `./gradlew --offline releaseGate` succeeded,
producing `source.stateSha256`
`09261695b3c07f64eeda43836bb693b6f1e9f07fb50a38526f89cf120d0c3b5a` and release APK
`3cc2bda3cb3f06a7d19ccc55860d4083dbcd7d18476edc139294217d33a277b7`. Provenance still records
`dirty=true`.

Read that second gate narrowly. Only the host scripts, release supply-chain host tests, lint
vital analysis, packaging/signing, and SBOM/provenance regeneration and verification actually
executed. Every JVM unit test, all compilation, and R8 were reused as up-to-date because the
change touched no compiled source; the newest JVM results remain the 2026-08-27 run. So the
2026-08-28 gate is a fresh artifact and supply-chain receipt, not a fresh 596-case JVM receipt.
Force a full rerun before promoting if a same-run JVM receipt is required.

On the isolated API 37 foldable AVD, the scoped app run completed 112 cases with zero failures and
26 owner/live skips; the two owner-approval Samsung Calendar classes were excluded rather than
weakening their assertions. The Room/data AVD suite passed 82/82. These are emulator receipts only.

The latest ordinary lane supersedes those counts without rewriting that historical receipt.
`scripts/run-avd-regression.sh` now refuses physical serials, rechecks the exact qemu/name/API/ABI
and boot identity before installation, installs only the six required debug APKs, and runs frozen
positive class allowlists. On 2026-09-01 a read-only/no-snapshot
`personal_edge_api37_foldable` AVD selected 269 methods: 238 passed, 31 reached reviewed assumption
guards, and zero failed. The app suite accounted for 142 selected, 112 passed, and 30 guarded;
data was 89/89, diagnostics 2/2, LLM 12 selected with 11 passed and one guarded, and tools 24/24.
The accompanying phone/qemu/name/API/ABI/boot, allowlist, transport-failure, and zero-exit JUnit
fixtures passed 32/32 in `scripts/test-host-scripts.sh`. This runner excludes the dedicated
E4B/Qwen/ABI lanes and all owner-action classes; it does not replace their separate evidence. No
Fold8 was connected for this receipt.

Physical work requires new owner approval and an exact final artifact. If approved:

1. run `preflight-fold8-release-update.sh` to identify the exact physical device and bind the local
   release app/test APKs to the installed package/certificate without changing device state;
2. use same-certificate `adb install -r` rather than uninstall;
3. pull back and hash-match `base.apk` and verify the signing certificate;
4. inspect only content-free preservation metadata for model size/hash, credential presence/health,
   settings fingerprint, and aggregate row counts;
5. run only named non-destructive acceptance cases; and
6. leave notification permission and other owner-controlled state unchanged unless separately
   approved.

## Physical-device hazards

- `connectedAndroidTest` can uninstall the target and delete the 3.66 GB imported model. Keep
  `android.injected.androidTest.leaveApksInstalledAfterRun=true`; never run the broad task against
  the owner's phone.
- Debug and release certificates cannot replace each other without uninstall/model re-import.
- Clock-clearing and vault-clearing tests must remain emulator-guarded.
- `adb input text` cannot drive Hangul reliably; use a narrowly scoped instrumentation path.
- A device test must prove its prompt was accepted, not just that it called `sendPrompt`. The
  conversation mutation gate refuses a turn while a conversation is being created or switched, and
  the refusal is silent: the text stays in the field and the previous conversation stays on screen.
  Waiting for a receipt then matches the *previous* turn, and the run reports that conversation's
  answer as if it were the new one. Assert the request itself appears in the transcript first.
- Query before deleting calendar rows and remove only exact owned fixtures.
- Diagnostics under `no_backup/diagnostics/diagnostics.jsonl` are the authority for Tool phase, but
  reading them still requires an explicitly scoped evidence task.
- **Not every androidTest class can run against the minified release install.** `testBuildType` is
  `release` with `isMinifyEnabled = true`, so the separately packaged test APK links against
  R8-shrunk app output and shares target dependencies rather than embedding them. On 2026-08-29
  `Fold8RuntimePrdAcceptanceTest` and `KoreanToolSelectionTest` exposed this boundary with
  `NoSuchMethodError` for Kotlin collection helpers. The current source preserves the exact
  Kotlin collection/coroutine facade families needed by the small physical suite. A matched
  release app/test pair on an isolated API 37 AVD now passes the one-case, AVD-enforced,
  provider-free `ReleasePhysicalAbiLinkageTest`. It directly resolves every exact post-guard
  class/member/type boundary found by comparing the selected test APK against the target APK;
  the Room count probe was rewritten onto the already-kept SQLite interface rather than broadening
  production Room retention. The same pair also reaches the intended opt-in/model guards:
  the five-case regression canary and all 28 methods in the 16 physical/live classes completed
  with only expected assumption skips and zero failures. This is release-packaging/linkage
  evidence, not physical code execution, provider behavior, or Fold8 acceptance. Keep ordinary
  repository/UI instrumentation on the debug target; a deliberately broad 13-class release run
  still demonstrates expected missing/optimized debug-only APIs. Do not weaken production
  shrinking or reinstall debug over the phone to chase that unsupported scope — doing so would
  cost the 3.66 GB model.
- Running an already-installed instrumentation class with `adb shell am instrument -w -r -e class …`
  needs no Gradle, no rebuild, and no reinstall, so it is the safest way to exercise the phone.
  Live acceptance classes additionally require their own owner opt-in flag (for example
  `-e liveReminderToolSelection true`); without it the case reports as an assumption skip.
- A release package normally makes `run-as` exit nonzero. The evidence collector treats only the
  exact bounded non-debuggable denial as `not_applicable_release`, retains it in `run-as.txt`, and
  keeps any other nonzero output as a failed receipt. Release JSONL still comes from the explicit
  in-app SAF export.

## Source map

| Module | Current responsibility |
|---|---|
| `core:llm` | Verified model boundary, LiteRT lifecycle, per-turn exact Tool scope, declared-modality media contract |
| `core:agent` | Manual controller, Tool scope, exact recovery contract, multi-read plan, grounded/write terminal answers |
| `core:tools` | Typed contracts, confirmation, execution interlocks, Action Ledger, provider gateways |
| `core:data` | Room schema 11, atomic transcript/outcome commits, contextual recovery source, unresolved side effects, content-free attachment summary, transfer, settings, vault abstractions |
| `core:diagnostics` | Bounded content-free typed diagnostics |
| `app` | Compose UI, coordinators, conversation/notification mutation gates, device adapters, permissions/lifecycle |

## Open acceptance work

- **Physical media acceptance.** A historical synthetic-card turn was read correctly and a clip
  reached the audio front end, but the accumulated token comparison was invalid. Run the corrected
  fresh-runtime, one-turn paired controls on Fold8 before accepting image/audio context deltas;
  then measure TTFT, turn time, PSS, and thermal, Korean dictation including silence/too-short
  refusals, the four image intents on owner-approved fixtures, camera/picker flows, and attachment
  UI under fold/DeX. Encoder-enabled GPU requested historically fell back to CPU; there is no
  accepted GPU media result.
- Clean-source review/commit followed by a fresh `releaseGate` and final artifact hash receipt.
- Owner-approved Samsung Calendar live acceptance omitted from the scoped emulator run.
- Wider Fold8 folded/unfolded/DeX/IME/TalkBack/confirmation/DnD matrix beyond the accepted
  answer-quality, thought disclosure, and rich-text scenario.
- Exact multi-read, ordered recovery, write terminal answer, interrupted write verification, and
  transcript deletion behavior on the final artifact.
- Current calendar/route/search/weather provider-live checks without manufacturing outages.
- Wider public-knowledge/provider matrix on the Fold8, including the new current-officeholder
  first-turn route, failed-correction/`첫 질문` recovery, and an answerability-poor primary/fallback
  boundary when naturally observable. The exact screenshot two-turn movie wording is accepted;
  current-officeholder routing and answerability are host verified, while the bounded failed-
  correction/`첫 질문` Room resolver also passed on a disposable API 37 AVD. Live-provider and
  physical behavior remain pending.
- Encrypted SAF transfer on a non-production fixture.
- Same-condition E4B/MTP/E2B model/device evaluation with telemetry and repeatable unplugged battery
  procedure.

Report current rc11 as host verified on a dirty source state with exact artifact-bound Fold8
receipts for contextual search and the bounded intent/context, thought disclosure, rich-text,
sustained-SEVERE, and preservation scenarios. Clean-source promotion and all wider
provider/device/owner-controlled acceptance remain pending.
