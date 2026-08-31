# Project status

Last reviewed: 2026-08-31 against the current `versionCode=11`,
`versionName=1.0.0-rc11` working tree.

This is an evidence ledger, not a feature checklist:

- **Implemented**: production source and regression coverage exist.
- **Host verified**: the relevant gate passed from the final frozen source state.
- **Android source verified**: Android/instrumentation source compiled; no runtime is implied.
- **Emulator verified**, **physical accepted**, and **provider/live accepted** require an exact
  artifact/environment receipt.

The latest focused model check is limited to the current E4B and Qwen3.5-9B Q4_K_M. Current host
tests passed (`core:llm` 17, `core:agent` 154, `app` 185), and the current debug app/test APKs
assembled. On the API 37 ARM64 CPU AVD, E4B verified and initialized but selected/executed
read-only `calendar_query` for a Korean calendar-create request; diagnostics recorded a 19.030 s
turn, 4,794,672,128-byte post-turn PSS sample, and no write. A separate ambiguity flow correctly
proposed `reminder_create`, reached confirmation, and was denied without a write. The exact E4B
also passed a provider-free real-decode cancellation and fresh-turn recovery test in 8.291 seconds.
Qwen3.5-9B then completed the corrected 26-case host screen with the production-format fixed
clock/zone context and exact artifact/run binding. It failed nine schema-v3 quality checks: Tool
selection 0.846, argument exactness 0.471, date/time exactness 0.625, final state 0.769,
response-language accuracy 0.333 with evidence in 8/9 eligible cases, extra-Tool rate 0.222, and
two rejected Tool calls; wrong-write rate was 0.000. The older focused numbers are superseded
because the old harness did not put the corpus clock/zone into the submitted model context. The
tested Qwen artifact is GGUF. A separate 9.52 GB community `.litertlm` exists but lacks a
reproducible quantization/cache/Android 0.16.1 qualification and was not downloaded. These are
asymmetric rejection lanes, not a same-condition speed or quality ranking. E4B remains the
deployable baseline; neither model passed promotion. No Fold8 was connected or inspected for this
focused check. The emulator follow-up now covers 241 deduplicated Android methods: 209 passed, 32
stopped at intended assumption guards, and none failed. The strict model-AVD lane includes all 10
`core:llm` tests, all 84 `core:data` tests, private action-ledger/diagnostics, conversation recovery,
summary, notification, settings, and thermal coverage. An account-free foldable AVD was separately
run with `-read-only -no-snapshot-save`; Calendar/Alarm passed 13/13, the explicit non-live app
suite produced 90 passes plus three expected skips, and 28 physical/live methods all stopped at
their opt-in guards. A restart proved the rc11 app update, permissions, placeholder vault values,
settings, calendars, and alarms were discarded: the base rc1 app returned with no accounts,
calendar rows, or next alarm. Samsung Calendar assertion-gated classes and the out-of-scope
Qwen3-8B lab smoke were not run. The production E4B file remained the expected 3,659,530,240 bytes
with mode `0400`, and recent diagnostics contained no Tool execution or write event. These tests
strengthen storage, recovery, replay-prevention, provider scoping, and opt-in policy evidence, not
model quality or physical performance. The latest
`./gradlew --offline releaseGate` passed against these
evaluator, test-source, and evidence changes, including JVM tests, lint, minified release assembly,
deterministic SBOM/provenance verification, and the host script suite. It did not run connected
tests and adds no physical-device evidence.

The ordinary account-free AVD lane is now reproducible through
`scripts/run-avd-regression.sh`, rather than an unscoped Gradle connected task. The runner requires
an explicit `emulator-*` serial plus disposable-state confirmation, verifies the expected AVD name,
qemu identity, API 37, ARM64 ABI, and completed boot before any install, and uses reviewed positive
class allowlists with frozen method counts. Its 2026-08-30 end-to-end run on
`personal_edge_api37_foldable`, started read-only with snapshots disabled, selected exactly 239
methods: 209 passed, 30 stopped at reviewed assumption guards, and none failed. This consists of
app 90/29, data 84/0, diagnostics 2/0, LLM 9/1, and tools 24/0 pass/skip counts. The separate host
fixtures now reject physical serials, wrong qemu/name/API/ABI/boot state, future-open discovery,
transport failure, and zero-exit JUnit failure; the full host script suite passed 28/28. No Fold8
was connected, inspected, installed, or changed for this receipt.

The 2026-08-30 delta adds bounded automatic and contextual public-knowledge web search plus Room
schema 10. `doctor.sh`, `test-host-scripts.sh` (20/20), and
`./gradlew --offline test lint assembleDebug assembleRelease` passed. The final
`./gradlew --offline releaseGate` also passed after the model documentation was frozen. On the
API 37 emulator, the scoped app context suite passed 25/25 and the scoped
schema-migration/recovery suite passed 25/25. The later owner-approved Fold8 receipt for the exact
contextual movie-search path is recorded below. It qualifies that one provider-grounded path, not
the wider search/provider/fallback matrix; the isolated Qwen LiteRT observations are described
below. That release gate predates the latest focused evaluator-binding and E4B lifecycle-test
changes above; it remains historical evidence and does not cover them.

Before the scope was narrowed, the same date's historical model screen covered ten around-8B GGUF
candidates on the 26-case host corpus. All failed promotion. Those legacy runs did not submit the
fixed clock/zone in the model's user context, so their date/time and date-dependent argument
numbers are superseded and are not comparable to the corrected focused run. Qwen2.5-7B led the
at-or-below-8B Tool-capable rows on Tool selection
and tied the best argument accuracy but proposed two forbidden memory writes; Qwen3-8B retained
better clarification and final-state scores plus a higher same-procedure visible-language score
while producing four wrong writes. Schema v2 now scores language only on nine eligible no-Tool
cases and treats missing text as missing evidence; Qwen3 and Qwen2.5 each exposed visible language
for only 6/9, so neither is a standalone Korean-generation-quality result.
Qwen3.5-9B led Tool selection in that historical procedure but still failed the gate.
Hermes also failed safety, while Granite 3.3 and EXAONE did not produce a valid structured call in
their current default paths. Therefore Qwen3-8B is not an unambiguous class winner and no GGUF
candidate is qualified. The schema-v2 scorer preserves or loads every returned Tool call for
safety metrics; the gate rejects first-call-only coverage, unknown/malformed calls, duplicate
writes, unreviewed heuristic rows, missing visible-language evidence, disjoint core-turn telemetry,
and device telemetry without one exact run binding. The public Qwen3-8B `.litertlm`
candidate is 4,887,412,736 bytes and has
an embedded 2,048-token input-plus-output limit, so it cannot be substituted for the pinned 4K E4B
profile without a separate provenance/runtime migration. On the isolated model AVD, E4B's GPU
engine reached the first turn but failed at the missing OpenCL TopK sampler; the explicit CPU path
completed inference but chose `calendar_query` instead of the requested calendar write. No write
occurred. An isolated application ID then proved that the exact public Qwen3-8B LiteRT blob can
load and complete simple CPU text generation in about 22.2 seconds, but the Korean write probe
ended after 49.1 seconds without a confirmable Tool call. Its end PSS was 8.86--9.46 GB and its
XNN cache was 4.27 GB, versus about 4.79 GB end PSS for the two E4B CPU probes. The Qwen candidate
packages, copied model, and cache were removed after capture; the host artifact and emulator's
verified E4B remained intact. No physical phone was connected or inspected.

A permanent, instrumentation-only `qwen8bLab` build path now makes that comparison reproducible
without replacing the production app. It pins the exact Qwen artifact in `core:llm`, uses
application ID `com.personaledge.agent.qwen8blab`, a separate model store, the artifact's 2,048
input-plus-output metadata limit, and a separately configured 384-token lab output ceiling. The
lab has no calendar, network, alarm, or boot
permissions; its Activity and WorkManager initializer are disabled; app reminder receivers are
absent; and both PREPARE and EXECUTE interlocks reject every non-read-only Tool risk. The named
instrumentation check verified no lab-UID-owned jobs, no notification channels, and no
notification runtime grant at test time.

The final no-ADB Qwen host gate now also runs the candidate BuildConfig through core JVM tests,
not only app tests and AAR compilation. A forced run passed `core:llm` 17/17, `core:agent`
153/153, and `app` 185/185, followed by Qwen lint and app/test-APK assembly. Static inspection
confirmed arm64-v8a only, the isolated application ID, the reduced permission set, disabled
MainActivity, removed WorkManager initializer, and matching v2 debug-signing certificates. The
current app/test APK hashes are `d0b51994...6d75` and `18bb20ff...c1`; neither artifact was
installed or executed. The retained AVD lifecycle receipt binds the earlier exact pair
`780c4e02...80f` and `32eab974...44e`, so it does not promote this newer source tree to emulator
verified.

The deeper API 37 ARM64 CPU lifecycle rejects this exact public Qwen3-8B LiteRT artifact with
LiteRT-LM 0.16.1 and the tested lab configuration as an agent replacement. A Tool-free turn,
native cancellation, and a new recovery turn completed, but the synthetic `lab_echo` request
returned repeated plain-text JSON rather than a `Message.toolCalls` entry. Even an explicit Qwen
`<tool_call>` fence produced one plain-text JSON object and zero native Tool calls. Therefore no
trusted ToolResponse could be reinjected. Against the same final app/test APK pair, the natural
Tool slice took 17.617 seconds and its post-stage PSS sample was 9,239,847,936 bytes; the
explicit-fence slice took 7.831 seconds and its post-stage sample was 8,960,824,320 bytes. These
single `Debug.getPss()` samples are not peak RSS/PSS. This is a promotion-blocking result for the
tested pairing, not every Qwen3-8B conversion. The lab packages, copied model, and
4,269,490,248-byte XNN cache were removed after evidence capture; `/data` free space increased
from 2,021,620 to 11,028,504 KiB and the production E4B hash remained
`0b2a8980...52e0`.

The final gate reused unchanged compiled/test outputs where Gradle reported them up-to-date and
regenerated, packaged, and verified the current provenance/SBOM. This is host release evidence,
not a physical-install receipt.

The 2026-08-28 snapshot carried host-only model-evaluation tooling
(`scripts/run-model-eval-harness.py`, `scripts/test-model-eval-harness.sh`) that did not exist at
the last host gate, and `test-host-scripts.sh` now runs 20 checks rather than 19. No production
module, schema, or gate was touched. Because provenance packages a whole-tree
`source.stateSha256`, both artifact hashes named below describe an earlier tree.

That tree was regated on 2026-08-28: `doctor.sh`, `test-host-scripts.sh` at 20/20, and
`./gradlew --offline releaseGate` all passed, yielding `source.stateSha256`
`09261695b3c07f64eeda43836bb693b6f1e9f07fb50a38526f89cf120d0c3b5a` and release APK
`3cc2bda3cb3f06a7d19ccc55860d4083dbcd7d18476edc139294217d33a277b7`, still `dirty=true`. Only the
host scripts, supply-chain host tests, lint vital analysis, packaging/signing, and
SBOM/provenance regeneration and verification executed; JVM unit tests, compilation, and R8 were
reused as up-to-date against unchanged compiled source, so no same-run JVM case count is claimed.
No Fold8, install, or preservation receipt exists for this newer artifact.

On 2026-08-29 the Fold8 (SM-F971N, `R5KL801YXWE`) ran scoped acceptance against the **already
installed** 2026-08-27 release build via `adb shell am instrument`; nothing was rebuilt, installed,
or uninstalled, and the newer 2026-08-28 artifact was not put on the device. Three classes passed:
the content-free preservation snapshot, `Fold8ReminderToolSelectionAcceptanceTest`
(reminder_create/reminder_update/reminder_cancel selected correctly, all three reaching confirmation
and all three denied, 98.81 s), and `Fold8ResponseLanguageAcceptanceTest` (default reply 36 code
points / 28 Hangul / 0 Latin; explicit-English reply 97 code points / 0 Hangul / 81 Latin,
99.04 s). A preservation snapshot taken afterwards was identical to the one taken first —
18 conversations, 79 messages, 41 notifications, 0 memories, three credentials present, model
`present_size_matched` at 3,659,530,240 bytes, settings digest
`af13c42b582270e1e2fea4bdcdfc0224f68d6fddc4d6b6a5ef087f1adab3a5fc` — so the run created no
reminder, calendar, or alarm row and left owner state untouched.

Two classes could not run at all. `Fold8RuntimePrdAcceptanceTest` (3 cases) and
`KoreanToolSelectionTest` (1 case) fail immediately on the minified release install with
`NoSuchMethodError` for Kotlin stdlib helpers (`listOf`, `collectionSizeOrDefault`) in the app's R8
output. `testBuildType = "release"` with `isMinifyEnabled = true` means these androidTest classes
compile against shrunk app code that no longer exposes what they call. This is a release
test-configuration defect, not a model result, and it is why no device TTFT, turn-time, PSS, or
thermal telemetry exists for this artifact. Idle process PSS measured 21,629 KB with the model
unloaded and platform thermal status 1 (LIGHT) after the runs; neither is a loaded-model
measurement, so the device profile stays unqualified.

The current source fixes that release split-APK defect for the owner-reviewed physical suite by
retaining the required Kotlin collection/coroutine entry points. On an isolated API 37 ARM64 AVD,
the matched owner-signed app/test pair now passes a one-case, AVD-enforced, provider-free post-guard
ABI smoke. The smoke directly exercises every class/member/type boundary identified by an exact
selected-test-APK versus target-APK reference comparison, then the pair completes a five-case
regression canary and all 28 methods in the 16 physical/live classes with only their intended
opt-in/model assumption skips and zero failures. The production keep surface is a closed allowlist
for those split-APK entry points; the Room count probe instead uses the existing SQLite interface,
and the ordinary suite was not made a production R8 root. That 13-class repository/UI suite remains
a debug target, because forcing its 93 methods against the minified release exposes missing/
optimized debug-only APIs and is not a model or physical result. The ABI smoke performs no provider
request or state change and is still only AVD linkage evidence. No Fold8 was connected for this
repair.

The focused Qwen3.5-9B result is now available only on the host, as recorded at the top of this
ledger. The tested 5.68 GB Q4_K_M GGUF cannot enter the Android LiteRT-LM path. The separate
9.52 GB community Qwen3.5-9B `.litertlm` was not downloaded because its conversion, cache/context,
and LiteRT-LM 0.16.1 compatibility are not reproducibly qualified. `ModelArtifactStore` still
accepts only the pinned E4B artifact. A Fold8 comparison therefore remains unavailable until one
exact Qwen Android artifact is independently qualified; the asymmetric host/AVD rejection lanes
must not be presented as a same-condition ranking.

The working tree described in the rest of this paragraph passed its rc11 host gate and scoped
API 37 AVD regression suites. Two
scoped Fold8 receipts exist for this source state and nothing wider:
`PastedMailScheduleAcceptanceTest` and the alternate-phrasing case of
`PublicPersonSearchLiveAcceptanceTest` each ran on SM-F971N against a same-certificate
`adb install -r` of the gated release APK, 1 case and 0 failures each, with `firstInstallTime`
unchanged and the pulled-back `base.apk` hash equal to the build under test
(`90d353f195f36d5fbd3ca133f5a895fd76d80eb55e418fd1e4a70d0a0eb91b1e`). On a dirty tree that hash
tracks the working tree, not just the code: provenance packages a `source.stateSha256`, so editing
any file — this document included — produces a new APK hash. The current gated artifact
(`3725e6ef5dacda567112e1bfdd88be1dcbce0ec24d412130a26c10f9d3afb094`) differs from the tested build
in exactly that one provenance field and was otherwise byte-identical, and rebuilding that
unchanged tree reproduced the same APK. The additions noted above have since superseded it. Both write confirmations were
denied, so no reminder, alarm, or calendar row was created. Every
other Fold8, provider, preservation, thermal, and model claim remains unqualified for this source,
and historical receipts from an older rc11 APK qualify only their own artifact. The host provenance
records `dirty=true`, so clean-source promotion remains closed.

On 2026-08-30 the owner-approved Fold8 (SM-F971N, `R5KL801YXWE`) received the gated owner-signed
release and release androidTest APKs through same-certificate `adb install -r`. Their local and
pulled-back hashes matched exactly: app
`978e97c49e8b1ab16361dfe05e3af61495d735ed67a7374181a7b4cb1f060448` and test
`9853cac396a9c5eb5a1a07170b5b076bd8f961fa5c23811ec36903adc4aac9d7`; both used certificate
`e0f66d4b4c8064db6a9d46097d77903cf13fbccacbdfc6e49e9f7c380b8e457a`, and `firstInstallTime`
remained `2026-08-23 18:10:37`. The exact two-turn movie scenario ending with
`잘 모르겠으면 웹에서 찾아서 알려줘` passed 1/1 in 23.401 seconds. Its assertion separates the
answer body from the Kotlin-owned source section, requires the original title plus a stable movie
identity fact in the body itself, requires every non-empty body line to end as a complete sentence,
and requires one or two HTTPS sources. This is the detailed contextual synthesis lane configured
for at most 1,024 output tokens; it does not prove that 1,024 tokens were consumed or qualify a
sustained maximum-length decode. In that frozen artifact, structured Tool turns were capped at
384 and SEVERE reduced a foreground turn to 256; the current source instead keeps the selected
ceiling through SEVERE and still cancels at CRITICAL. The total model context remains 4,096 with
the existing 2,048-byte runtime input envelope. System exit history after installation contained only
package-update and instrumentation start/finish stops, with no crash, ANR, or low-memory exit; the
search process's instrumentation-finish record reported 4.4 GB RSS and the post-run platform
thermal status was 0. Content-free snapshots before installation, after installation, and after the
test were identical: 19 conversations, 84 messages, 43 notifications, 0 memories, three credential
presence flags, the 3,659,530,240-byte model, Kakao reply disabled, and settings digest
`af13c42b582270e1e2fea4bdcdfc0224f68d6fddc4d6b6a5ef087f1adab3a5fc`. This ledger edit happened
after that frozen artifact was tested, so it supersedes only the APK's whole-tree provenance field;
the receipt above remains bound to the named pulled-back APK bytes.

On 2026-09-01 the owner-approved Fold8 received the final answer-quality code candidate and its
release androidTest APK through same-certificate `adb install -r`. The exact locally built and
pulled-back app hash was
`90963e14f89a8199ce882ae17191f25bff83d1e144a190391d9abe102f7b3144`; the test APK hash was
`cff79c80fe6e8461ffb6574c537fd706bfe27a32f9fbf85ffb5072d05e0d9cc4`. The four production-path
intent/context cases passed 4/4 in 85.001 seconds with the reported request ceiling unchanged at
1,024. A separate sustained GPU turn completed in 97.449 seconds at SEVERE, produced 147 ephemeral
thought updates, and cleared them at completion. The normal MainActivity showed the actual thought
stream expanding and collapsing without a white transition, then rendered bold Markdown and dollar
math without raw markup. The test-only empty Activity received a black theme after it was isolated
as the earlier white-screen source. Final content-free cleanup returned to 19 conversations,
84 messages, 45 notifications, and 0 memories; the 3,659,530,240-byte model, credential-presence
flags, settings digest, disabled Kakao reply, and `2026-08-23 18:10:37` first-install time were
preserved. The exact renderer-only numeric-math correction was included in the named app hash.
Subsequent documentation edits change packaged whole-tree provenance; code equivalence must be
established by comparing APK code/resource entries rather than reusing this whole-file hash.

## Current rc11 snapshot

| Area | State | Current boundary |
|---|---|---|
| Release identity and provenance | Dirty-state `releaseGate` and one exact Fold8 install receipt passed; clean-source promotion pending | The schema-10 dirty source passed the named root `releaseGate`, including tests, lint, minified release assembly, deterministic SBOM/provenance generation, and signer verification. The frozen pre-ledger-update APKs were installed with preserved first-install time and pulled back byte-identically as recorded above. Every Room schema filename equals its JSON-internal version and the latest validated export equals `PERSONAL_EDGE_DATABASE_VERSION`. Clean-source promotion remains pending. |
| Tool exposure | Implemented and host verified | `automaticToolCalling=false`. `TurnToolScopePolicy` gives each turn only its minimum exact domain/intent Tool set; unclassified prose exposes none. Line breaks and tabs are ordinary text, so a pasted mail keeps its schema. Ambiguous multi-domain write intent still exposes no write schema and now produces one app-authored question instead of a turn that requires a Tool it cannot offer; the owner's answer resumes the original request under a single-domain scope. A request that names a calendar date is scoped to reminders rather than the clock alarm, matching `alarm_set`'s own contract. A reply carrying no domain of its own may reuse the previous request's scope from in-process state only, which is never a durable authorization. |
| Multi-read AgentPlan | Implemented and active in source; external acceptance pending | Two to four independent renderable reads are converted into a verified plan. Kotlin strictly parses and whole-batch preflights every call before dispatch; at most two execute concurrently. Ordered receipts and Kotlin-grounded rendering end the turn without a second decode. |
| Grounded reads | Implemented and host/emulator verified; current provider/device pending | Weather, public search, calendar, alarm, route, and reminder results come from typed evidence. Explicit public search reduces the request to its subject before the query leaves the device:
person-reference and topic phrases such as `…이란 사람에 대한 정보를` are trimmed, since a leftover
sentence fragment is sent to the provider literally and can return nothing for a widely covered
subject. Trimming never removes a bare particle that also ends ordinary nouns. Public search
uses one shared NFKC/spacing-tolerant relevance rule at both provider quality and final selection, selects at most two sources, excludes unsolicited obituary/personnel-list noise, synthesizes answer-first prose under a tool-free and grounded validator, and appends only Kotlin-owned HTTPS sources. Relevance-poor You.com hits may reach Tavily once only when the optional key is usable. Invalid synthesis falls back without a second search. A freshness/recovery turn cannot finish from model prose without satisfying its trusted read contract. |
| Automatic/contextual public search | Implemented and host/scoped-AVD verified; exact contextual movie path accepted on Fold8 | A closed public-knowledge grammar may expose `web_search` without the literal word “검색”. A complete local answer remains local; one recognized explicit knowledge-gap answer may trigger one search. A subjectless follow-up such as `잘 모르겠으면 웹에서 찾아서 알려줘` can inherit only the immediately preceding USER public-knowledge request in the same conversation. The exact movie scenario passed on the owner-signed Fold8 with a complete body and app-owned HTTPS sources. It never searches assistant text, summary, memory, Tool/provider output, private/sensitive content, writes, communication, weather, or route text. Confidently wrong prose without a recognized gap marker and the wider provider/fallback matrix remain unqualified. |
| Search follow-up context | Implemented and host verified; physical model pending | Closed previous-result summary/organization/source requests expose no Tool, reject a hallucinated repeat search before the gateway, and preserve the prior answer lead plus source tail. Explicit re-search wording remains a fresh read. Generic subjectless search wording without a safe owner source fails closed. |
| Dialogue intent and context quality | Implemented; bounded Fold8 acceptance passed | A separate fixed eight-case lexical regression lane covers long-prompt salience, compound constraints, relevant recent context, newest correction, stale-summary override, irrelevant history, clarification, and conversation isolation. Its reviewed-set pass additionally requires a six-dimension human rubric for intent, context, constraints, unsupported claims, format, and direct usefulness on all eight answers; even that is bounded synthetic judgment, not general semantic proof. Production-path Fold8 acceptance adds four exact synthetic no-Tool cases through ViewModel/Room/context/LiteRT. The clock-prioritized full-budget candidate first passed those cases three consecutive times (12/12) across NONE, MODERATE, and SEVERE. The final thought-streaming code candidate passed 4/4 again in 85.001 seconds with a reported 1,024-token request ceiling in every case. |
| Rolling conversation compaction | Implemented; policy device tests passed, semantic breadth pending | After a completed turn, compaction triggers at ten pending messages or 1,536 UTF-8 source bytes. The model receives a head-and-tail bounded transcript plus the prior capsule and must return one complete 480-byte latest-state capsule covering goals, decisions/constraints, and unresolved references. Kotlin rejects over-limit, incomplete, Tool-producing, invented-literal, and unjustified prior-anchor-loss results; explicit corrections may replace only a repeated old anchor with a retained same-kind new anchor. The newest twelve messages and live recovery rows remain verbatim. User work preempts compaction; the owner's latest thermal policy allows it through SEVERE, cancels at CRITICAL or higher, fails closed at UNKNOWN, and rechecks that boundary before storage. The scoped device policy suite passed 23/23 on the same compaction code; broad semantic recall over arbitrary long conversations remains unqualified. |
| E4B thinking, answer completion, and rich response text | Implemented; bounded Fold8 UI/sustained acceptance passed | Thinking is enabled by default with `min(384, maxOutputTokens / 2)` reasoning tokens. Multi-constraint short-output requests retain the normal full turn budget so reasoning does not collapse the visible answer allowance. While the active latest Assistant entry is blank, the transcript shows a tappable `생각 중` disclosure; expansion renders the matching LiteRT thought-channel deltas verbatim as bounded, tail-following plain text. The Fold8 showed actual thought text streaming, then collapsed it without leaving the dark MainActivity; the sustained turn recorded 147 updates and cleared the field at completion. The instrumentation-only empty Activity now has a black test theme after it was identified as the white-screen source. Completed answers use a bounded local Markdown/math renderer; an actual Fold8 answer displayed bold headings and centred equations without raw `**`, dollar delimiters, or `\\text`. Thought and active answer streams stay plain, and unsupported or malformed markup falls back to literal text. The raw thought stream remains redacted and ephemeral: it is never copied into final answers, runtime history, Room, recovery capsules, summaries, diagnostics, or restored conversations. |
| Write terminal answers | Implemented; device/provider pending | Typed completed/refused write receipts produce app-authored terminal answers. Unknown side effects are never presented as success and remain owner-verification obligations. |
| Turn recovery | Implemented and host/scoped-AVD verified; process/device gates pending | The exact ordered list of up to four completed read Tool names is durable. Recovery copies and reserves that list exactly, re-runs fresh checks/Tools, and accepts order-independent parallel completion only after exact reservation. Schema 10 can point a contextual follow-up at the earlier USER request through a content-free source ordinal while keeping the current follow-up's unique outcome row. |
| Atomic transcript/outcome storage | Host and scoped-AVD verified | Assistant phase, Tool receipt, and typed Tool outcome commit in one Room transaction. An exact turn/ordinal/risk/outcome/Tool/receipt redelivery is a no-op; conflicting ordinal reuse fails closed. Final assistant phase and terminal outcome commit atomically and idempotently. |
| Unresolved side effects | Implemented; owner UX/device pending | Room schema 10 retains unresolved writes in a no-expiry table without transcript/turn foreign keys. Chat deletion and turn expiry cannot hide an uncertain write; only matching refusal or explicit owner verification clears it. |
| Conversation mutation concurrency | Implemented; lifecycle/device pending | `ConversationMutationGate` serializes restore, new/switch/delete/delete-all, recovery resolution, and turn start across UI/Room ownership. |
| Notification capture | Implemented; physical permission/lifecycle pending | Capture remains default-off and allowlisted. Disable closes a synchronous process gate before asynchronous persistence; capture, setting changes, and erasure share a mutex. A failed disable remains closed. The current turn scope now uses the registered `kakao_notification_search` name, does not add `web_search` merely because a Kakao-notification query says `찾아`, and requires an actual notification read before the turn can complete. |
| General owner consent | Implemented and host/AVD verified; live acceptance pending | Route, web/weather, memory, proposal, proactive-route, and daily-brief features combine durable state with a latest-request process gate. Every pending mutation closes the gate; only the latest successful durable enable opens it. Network gateways recheck before each provider hop. |
| Encrypted transfer | Implemented; end-to-end migration pending | Version/length/count/UTF-8/collection/ID/relationship/ordinal/summary checks run before transactional import. Credentials, ledger, notification rows, recovery/checkpoint state, unresolved writes, provider IDs, model, diagnostics, permission, and consent are absent. |
| Fold/DeX/accessibility/input | Implemented policy; physical matrix pending | Usable size and hinge validation prevent invalid Book/Tabletop/two-pane classification. Confirmation actions remain reachable, semantics are explicit, streaming work is bounded, and text drop merges with a newline without mutation on refusal. |
| Output budget and model evaluation | Sustained 1,024-ceiling Fold8 measurement accepted; model comparison pending | Explicit concise intent stays bounded even when generic explanation wording is present, subjectless Korean `찾아` requests normally use the structured budget, and an inherited detailed public-search request receives the full 1,024-token ceiling while Tool/step/deadline/argument caps remain. Predicted or observed heat through SEVERE does not shorten the request-derived ceiling; CRITICAL still cancels. A current-code sustained GPU turn completed in 97.449 seconds at SEVERE with the 1,024-token ceiling unchanged; this does not prove all available tokens were consumed. The fixed 26-case/23-category screen uses a fourteen-Tool subset of the shipped seventeen. It is an all-Tool stress screen, not a production accuracy estimate. Schema v3 strictly rejects duplicate JSON keys, non-finite values, hidden later/malformed calls, invalid Tool arguments, contradictory terminal states, mixed run/model/artifact audit rows, cleared review obligations, missing visible-language evidence, and incomplete Android binding. The focused Qwen3.5-9B host score and current E4B AVD write-selection failure qualify neither replacement nor same-condition comparison. No replacement model is qualified. |
| Thermal policy | Implemented; SEVERE full-ceiling Fold8 measurement passed | At the owner's request, predictive headroom remains measured but does not shorten or cancel app model work before CRITICAL. Each deterministic request class keeps its normal 128/256/384/1,024 ceiling and background summarization remains eligible through an observed SEVERE state. `ThermalTurnPolicy` retains the hard boundary: NONE through SEVERE may start, CRITICAL cooperatively cancels, and EMERGENCY or above aborts; UNKNOWN remains fail closed. The sustained Fold8 turn completed at SEVERE with no app-level reduction or cancellation: battery temperature changed from 34.2 to 35.5 C while plugged in, capacity remained 94%, PSS changed from 18,761,728 to 145,807,360 bytes, and app heap changed from 8,325,328 to 22,021,840 bytes. Android and firmware protections remain independent. |
| Memory and proactive proposals | Implemented bounded source; activation acceptance pending | Memory provenance is owner-visible. Opportunity detection is default-off and review-only; it cannot autonomously promote or execute. |

## AgentPlan and recovery invariants

- Maximum batch: four trusted reads; maximum concurrent execution: two.
- Every call is parsed/prepared before any batch member dispatches.
- Writes, communication, unrenderable reads, and notification-content reads cannot enter the
  grounded batch bridge.
- Recovery stores exact ordered Tool names with contiguous ordinals; duplicate names remain
  significant.
- Reservation must equal the predecessor list exactly. Missing, extra, reordered, or substituted
  reads fail before execution.
- Old provider payloads are never stored or reused. Kotlin renders only fresh typed evidence.
- A side effect is armed after current authorization/interlock and before ledger claim/Tool call.
  It is never automatically replayed after uncertainty.

## Room schema 10

The no-backup application database contains conversations/messages, memories, notification cache,
reminders/deliveries, proposals, turn outcomes, ordered read executions, AgentPlan checkpoints, and
unresolved side-effect obligations. Migration 8→9 adds the independent unresolved table and
preserves closed side-effect identities from earlier rows without inventing content. Migration
9→10 adds nullable `recovery_source_user_message_ordinal`; it stores no prompt or result text. A
contextual follow-up owns its own unique outcome row, while recovery resolves the preceding owner
question through that ordinal.

The Action Ledger remains a separate database. Therefore:

- deleting chat cannot reopen a claimed action;
- deleting chat or expiring a turn cannot erase unresolved external-state verification;
- conversation compaction preserves live recovery requests; and
- transient status/recovery UI is derived rather than persisted as assistant content.

## Security and privacy boundaries

- Tool output and model output are untrusted until Kotlin validation.
- State-changing and communication Tools retain confirmation, durable idempotency, and an
  execution-time interlock. Read-only network Tools instead require explicit persistent consent.
- Diagnostics remain content-free and bounded; prompts, outputs, arguments, provider bodies,
  confirmation text, credentials, paths, and raw identifiers are excluded.
- Content-bearing model/Tool/runtime event types override string rendering with redacted metadata
  only, so incidental object logging cannot expose prompt, delta, argument, or provider payload
  content.
- Notification text is hostile, sanitized, allowlisted, bounded, and excluded from transfer.
- Credentials remain Keystore-encrypted; UI exposes health state but never the credential value.
- Credential health distinguishes a truly missing path (`ABSENT`) from a present empty, oversized,
  malformed, permission-untrusted, or undecryptable path (`UNREADABLE`). `UNREADABLE` requires owner
  repair/re-entry and is never silently treated as absence.
- Calendar labels are not provider identity; reads/writes remain pinned to explicit rows.
- Android automatic backup remains disabled.

## Promotion gate for rc11 to 1.0.0

The standard host build/test gate is complete, but promotion remains closed until the remaining
applicable items
have exact receipts:

1. Commit/review a clean source state and rerun `doctor.sh`, all host-script tests, and
   `./gradlew --offline releaseGate`; the current dirty-state gate already passed.
2. Record final clean-state SBOM/provenance regeneration, minified release assembly, APK signature,
   certificate identity, and artifact hashes.
3. Compile and then run only scoped Android tests on an isolated emulator; do not treat source
   compilation as runtime evidence.
4. With owner approval, perform same-certificate Fold8 `adb install -r`, pulled-APK hash/certificate
   verification, model/credential/settings/count preservation, and bounded rollback planning. Run
   the read-only `preflight-fold8-release-update.sh` first; its compatibility PASS is not permission
   to install and not a device acceptance receipt.
5. Exercise exact ordered multi-read/recovery, write terminal answers, process interruption,
   transcript-independent unresolved verification, and conversation-mutation races.
6. Exercise notification disable-versus-arrival races, listener lifecycle, retention/erase, and
   default-off communication behavior without reading unrelated content.
7. Run folded/unfolded, short DeX/freeform, hinge, IME, TalkBack, confirmation, keyboard, and DnD
   matrices on the exact release artifact.
8. Run same-condition model/device evaluation for the pinned artifact, including TTFT, turn time,
   PSS, thermal, fold/cancel, and a repeatable unplugged battery procedure.
9. Qualify provider-live calendar, route, search, and weather behavior under current contracts and
   privacy consent. Do not manufacture outages or weaken gates to obtain fallback evidence.
10. Complete encrypted SAF export/import on a non-production fixture, including wrong passphrase,
    tamper, category selection, clean merge, calendar remap, reminder reconciliation, and proof that
    excluded stores did not transfer.

## Device-test safety

The exact owner-approved 2026-08-30 contextual-search receipt and 2026-09-01 answer-quality,
thought-UI, renderer, sustained-turn, and preservation receipts are recorded above and belong only
to their named pulled-back artifacts. Other
physical receipts remain historical and artifact-specific. The latest ordinary account-free,
read-only API 37 AVD runner selected 239 reviewed methods with 209 passes, 30 intended guards, and
zero failures. It excluded every owner-action and dedicated model/ABI class rather than weakening
their gates. Before any later phone work, read `AGENTS.md`. Always use an explicit serial. Do not
run unscoped `connectedAndroidTest`:
it can uninstall the app and destroy the imported 3.66 GB model. Keep clock-clearing and vault-
clearing tests emulator-only, query before deleting calendar rows, preserve the installed
certificate variant, and use content-free diagnostics for turn evidence.

The next Fold8 connection now has a fail-closed host preflight: it requires the exact physical API
37 SM-F971N, verifies the owner-signed release app/test package relationship, and pulls the
installed `base.apk` only for local hash/certificate/version comparison. The evidence collector
also preserves the normal nonzero release `run-as` denial and classifies only its exact message as
`not_applicable_release`; unexpected failures remain failures. Both changes are host-side safety
work and do not constitute a new Fold8 receipt.

## Deferred product scope

Arbitrary-recipient/silent Kakao automation, Kakao history, full Samsung Clock editing,
Accessibility-driven automation, Polestar control, always-on voice, multimodal intake, autonomous
background actions, cloud reasoning/PII relay, E2EE sync, dynamic downloaded Tool code, and
8K/16K/32K production contexts remain outside this rc11 acceptance boundary.
