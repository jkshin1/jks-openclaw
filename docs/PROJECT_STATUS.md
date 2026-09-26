# Project status

**Current Mac/Telegram operations and evidence:** [OPENCLAW_TELEGRAM.md](OPENCLAW_TELEGRAM.md).
On 2026-09-26 KST, the main agent's chain became `anthropic/claude-opus-5-5` →
`openai/gpt-6-sol` → OpenRouter GLM, and the global default became Opus → Sol. Opus runs through
the `claude-cli` runtime on this Mac's own Claude Code login (Homebrew `claude-code@latest`
2.1.282; Opus 5.5 needs 2.1.280+), drawing from the Claude subscription, with no Anthropic API
key and native Claude session discovery disabled. No runtime file was patched. One synthetic
agent turn with no model override ran `claude-cli`/`claude-opus-5-5` and returned `OPUS55-READY`;
a raw `modelRun` smoke cannot test this route because 2026.9.3 forces raw runs onto the embedded
runtime. Its failover showed the real chain order (Opus auth failure, Sol 429 with Codex
exhausted until 2026-09-30, GLM answered). The verifier, 16 policy tests and the reinstalled
observer's scheduled check passed. A real owner Telegram message then ran on `claude-cli`/Opus 5.5
with no fallback and Telegram accepted the reply (message 426). Actual Claude limit exhaustion
remains unverified; see the migration record's known boundaries.
The paragraph below describes the superseded 2026-09-23 Sol state.
On 2026-09-23 KST, the configured global and main-agent default routes moved to
`openai/gpt-6-sol` with `high` thinking; main retains its sole configured OpenRouter GLM
fallback. A separate, scoped Sol compatibility patch is installed in four OpenClaw 2026.9.3
files (plugin manifest, thinking policy, model route contract, and ChatGPT/Codex resolver).
Its reviewed hashes are in `scripts/openclaw/runtime-patch-specs.json` and its private installed
receipt is `operations/gpt6-sol-patch.json`. This does not replace the four earlier local
runtime repairs. Codex 0.156.1 still uses the ChatGPT OAuth credential without an API-key
billing route. An isolated Gateway `modelRun` returned `SOL-READY`: requested/effective
`openai/gpt-6-sol`, response model `gpt-6-sol`, no reroute or successful tools, and cleaned
synthetic state. The owner's Telegram conversation and Telegram delivery have not yet been
tested on this new route; quota exhaustion, fallback under Sol, and comparative usage savings
also remain unverified. Historical Astra receipts below keep their original scope.
The same day, a fresh official backup and offline restore rehearsal reached `VERIFIED` at
07:02:48 UTC with 42 SQLite databases and 64,016 files. Its recovery supplement contains
the Sol installation receipt and all four patched runtime files; the restored copy was
checked separately and was not activated. The operating status returned `ok=true` and
`issues=[]`. This is a recovery copy of the current Sol state, not an Astra rollback.

On 2026-09-19 KST, the natural weekly Hermes review ran once and stopped at the worker's
model-input estimate budget after four responses; the failed attempt and supplied usage were retained
without retry. Independent follow-up refreshed the stale backup and verified its offline restoration
(42 SQLite databases, 57,678 files), recording actual reuse of the existing backup procedure and
valid finding closure. Current observer issues and consecutive failures returned to zero.
A bounded final-response transition was installed without changing model/input limits or credentials:
117 source regressions, four installed-worker offline mock checks, and live Gateway/Telegram checks
passed. Actual model acceptance remains deferred to the next natural review. The separately failed
09:00 weekly briefing remains preserved and unsent; it was not restarted by this operations review.
See [the September 19 record](OPENCLAW_HERMES_OPERATIONS.md#2026-09-19-주간-점검과-후속-복구).

On 2026-09-12 KST, the owner-requested Hermes priorities were implemented and installed with a
transactional backup: independent test/runtime receipts now connect recovery episodes, versioned
procedures, verified reuse and finding closure; reviews receive lifecycle and verified procedure
context plus hashed source/release deltas, with cumulative reading and input-estimate budgets.
The earlier configured-main collector repair was recorded as an operator-origin episode, closed
with real installed-collector checks, and reused successfully in a separate validation run.
Five original weekly findings were imported: two observations and three deferred follow-ups with
owners/reasons, separate from that resolved repair. The existing weekly heartbeat now records the
same post-repair evidence chain without changing its schedule or adding inference. Gateway/Telegram
verification passed. Synthetic report procedure correction and fresh-input reuse passed in both
OpenClaw and Hermes; OpenClaw raw-mode token telemetry is missing, so the complete efficiency
comparison remains blocked and ordinary work routing is unchanged. After explicit owner approval,
the actual private operations-data acceptance completed once on Sol/high at 23:29 KST: six
responses in 162.665 seconds, exact procedure reference, correct main/defaults distinction and
preserved resolved state, with 10,340 bytes read and zero reread. The 23:30 local follow-up reported
backup-stale as an open issue owned by codex. Following the owner's repair request, a fresh backup
and offline restore rehearsal reached VERIFIED at 23:47 KST: 42 SQLite databases and 57,335 files.
The first attempt exposed six temporary aliases in inactive retained pilot data whose existing
runtime targets are excluded from the official archive. Those aliases were moved to a separate
private quarantine with a reversible manifest included in the new backup; the target binary and
archive safety checks were preserved. At 23:48 KST, operationsOk=true, issues=[] and observer
consecutiveFailures=0. This repair made no model call, Gateway restart or Telegram send, and added
no recurring backup automation. See the [backup repair record](OPENCLAW_HERMES_OPERATIONS.md#2026-09-12-backup-stale-repair).
At 23:54 KST, independent test/runtime receipts closed backup-stale with valid closure evidence.
Both the failed attempt and successful repair were retained as experiences; only the successful
repair became `refresh-openclaw-verified-backup` v1. Finding states are now observed 3, deferred 3
and resolved 2. The new procedure has no verified reuse yet; the existing procedure's reuse count
remains one.
The real acceptance reference was recorded without increasing verified reuse counts.
The installed operations worker also passed one
synthetic-only real model run: exact procedure reference, observation classification, one 386-byte
source read and no reread, with four responses. See
[implementation and acceptance](OPENCLAW_HERMES_OPERATIONS.md#2026-09-12-verified-operations-knowledge).

On 2026-09-12 KST, the first natural Saturday Hermes review completed on Sol/high, reading the
installed 1.1.0 procedure and the supplied sources. Gateway/Telegram were healthy; two terminal
exec failures and earlier auth-selection log entries were kept separate from current service
status. OpenClaw 2026.9.4 and Hermes 0.21.2 were identified for compatibility review; neither
runtime was upgraded. Independent follow-up found that diagnostic configuration showed only global
defaults and omitted the approved main-agent GLM fallback. The collector now reports the configured
main route separately from global defaults and explicitly excludes session overrides and actual
provider selection from that claim. Regression and installed collection evidence are recorded in
[the weekly review record](OPENCLAW_HERMES_OPERATIONS.md#2026-09-12-first-weekly-review).

On 2026-09-10 KST, eight owner-requested operations improvements were installed. The five-minute
tool-free observer now starts one detached `--mode incident` review for a qualified open Gateway,
task or dreaming fault, budgeted to one per incident, a six-hour interval and two per day, with
durable intent written before the spawn; transport and backup faults stay on their deterministic
procedures. Evidence gained classified per-failure grouping, a Gateway exec-PATH tool inventory and
a model-free Hermes invocability check. Reviews gained fingerprint deduplication that short-circuits
identical evidence with zero model calls, an append-only review ledger exposing repeat findings, a
`record-applied` feedback path that invalidates the deduplication baseline, and runtime-patch
coverage against the latest upstream release. Boundaries were preserved: failure groups carry fixed
classification labels rather than command text or raw error strings, classification anchors on the
app-authored prefix so an argument containing a word like `authorization` is not filed as an auth
fault, only the `PATH=` line of the secret-bearing service environment is read, the ledger carries
finding identifiers rather than model prose, and the inventory checks raise warnings only, never
issues, so they cannot change a health verdict or raise an owner alert. A task-schema change now
voids only the failure grouping, and an older installed collector omits only that section. Verified:
130 offline tests across five suites (28 controller, 45 observer/status, 12, 27, 18), a real
collect-only run that reproduced the day's two failure causes with zero model calls, live upstream
patch-coverage against tag v2026.9.3, backup-first installation of both installed copies with the
scheduled observer run confirmed executing the new code, a live `record-applied` round trip, and
`test-host-scripts.sh` 34/34. The Gateway process, Telegram transport, configuration and model
defaults were unchanged. Hermes procedure memory moved to `HERMES_OPS_RUNBOOK.md` 1.1.0 in both the
repository seed and the preserved installed copy; whether a real model run reads it is not yet
observed, and the first natural weekly run remains 2026-09-12 10:00 KST.
On 2026-09-10 KST, the archived-relay management scripts were made to say which deployment the
caller reached. Their `PERSONAL_EDGE_OPENCLAW_VERSION="2026.8.1"` pin is unchanged and deliberate:
it also derives the install path `~/.local/openclaw-2026.8.1`, which the in-place upgrade to
2026.9.3 kept, so bumping the constant alone would point the runtime root at a directory that does
not exist. The pin is also only the first of several gates that path fails — the live deployment
manifest still records the pre-upgrade `version` and four drifted hashes
(`packageJsonSha256`, `cliEntrySha256`, `gatewayEntrySha256`, `wrapperSha256`; `commonSha256` and
`watchdogSha256` still match), and `_common.sh` pins the relay's GLM-only model policy against a
host agent now configured for `openai/gpt-5.6-sol`. Entering `openclaw_assert_existing_runtime` or
`openclaw_load_deployment` with a newer installed runtime therefore reports the archived-relay
boundary and the `--telegram` entry points instead of bare drift, still exiting 1. No live
deployment state, manifest, hash baseline or configuration was changed. Verified: the legacy
`status-gateway.sh` and `security-audit.sh` paths print the boundary and exit 1, the active
`status-gateway.sh --telegram` and `verify-gateway.sh --telegram` are unaffected, and
`test-deployment-assets.sh` plus `test-host-scripts.sh` (34) pass.
On 2026-09-10 KST, Hermes was expanded from event reports to requested OpenClaw diagnosis,
update-impact review and operational improvement candidates. The installed Sol/high worker read
its operations procedure and source tools in a real successful review; current operations were
healthy, historical failures were kept separate, and no code replacement was justified. A verified
archived-relay versus active-Telegram distinction was added to the procedure after independent review.
The existing Gateway and Telegram transport remained ready. A Codex heartbeat named
`Hermes OpenClaw 주간 운영 점검` is ACTIVE for Saturday 10:00 Asia/Seoul; its first natural run
is September 12 and has not yet occurred. Proven fixes continue through scoped backup, tests,
installation and runtime checks. [Implementation and evidence](OPENCLAW_HERMES_OPERATIONS.md).

On 2026-09-10 KST, stale Codex subscription blocking was cleared through the official usage
reprobe, and explicit Codex runtimes were connected to that same guarded reprobe path.
Astra response and post-restart Heartbeat succeeded. Four deleted synthetic pilot agents' retained
database registrations and directories had prevented startup; their registrations were removed
and files preserved outside agent discovery. Configuration, credentials and existing conversation
events were preserved. See [the recovery record](OPENCLAW_HEARTBEAT_RECOVERY_20260910.md).

On 2026-09-09 KST, an isolated Hermes 0.21.1 worker was installed for explicitly requested
operations-event reports. Its separate ChatGPT OAuth, Sol/high procedure learning and fresh-session
reuse passed; the existing Astra main agent also invoked the installed wrapper and returned an
independently verified report. Main configuration and conversation identity were preserved.
OpenClaw native create/apply and fresh-session reuse also passed on the existing main route.
The optional Telegram usage guide was sent once on 2026-09-10 KST after the owner's explicit approval
resolved the earlier automatic-review rejection; Telegram accepted it as message 155 for the verified owner.
Phone opening remains unobserved. The original acceptance snapshot and a separate closure receipt are preserved.
Detailed integration command-argument auditing remains unobserved because the smoke's post-run history request
was invalid. Scope, failed attempts, receipts and usage: [OpenClaw + Hermes pilot](OPENCLAW_HERMES_PILOT.md).

On 2026-09-09 KST, OpenClaw and the official Codex plugin were subsequently upgraded to
**2026.9.3** after all four local runtime patches were requalified against exact source hashes.
Default Astra/high and the owner's Telegram session identity were preserved. Individual task
progress, recording-to-minutes/subtitles, and an AI/LLM weekly briefing were installed. The weekly
briefing uses official public sources, Sol/low isolated summaries and owner Telegram receipts,
scheduled for Saturday 09:00 Asia/Seoul. Actual synthetic meeting processing and four Telegram
attachments passed; the future scheduled run remains distinct from registration. See
[the upgrade and workflow record](OPENCLAW_UPDATE_20260909.md).

On 2026-09-09 KST, the owner-authorized operations improvements repaired the stale observer
template, deployed aggregate status and bounded failure/recovery alerts, and added current-runtime
backup with isolated restore verification. Synthetic document/media processing, four-page visual
review and seven Telegram attachments passed; direct SRT uses a validated ZIP without expanding
media permissions. Seventy-two focused offline tests passed. The Gateway process, configuration,
GPT-6 Astra/high default and four runtime patches were preserved. Owner upload and phone opening
remain separate. Commands and verification boundaries: [OPENCLAW_OPERATIONS_KO.md](OPENCLAW_OPERATIONS_KO.md).
The old zero-tool relay manifest does not certify the current Telegram host agent.
The Mac deployment now uses owner-approved automatic memory with explicit correction/forget priority;
current policy and acceptance evidence are recorded in that operations document. Community skill
recommendations are in [OPENCLAW_SKILL_RECOMMENDATIONS.md](OPENCLAW_SKILL_RECOMMENDATIONS.md).
On 2026-09-07 KST, Summarize, local Whisper, Word and Excel were installed and verified on this Mac;
GitHub was excluded. [OPENCLAW_PRODUCTIVITY.md](OPENCLAW_PRODUCTIVITY.md) records actual execution,
Korean output, PDF rendering and spreadsheet recalculation evidence and its limits.
Codex, Web Readability and Document Extract were subsequently installed and execution-verified.
Codex uses ChatGPT account authentication with API-key fallback disabled. On 2026-09-08 KST the
owner selected and saved GPT-6 Astra/high as the default conversation model: `/model codex` selects
`openai/gpt-6-astra`, and `/model sol` selects `openai/gpt-5.6-sol`. Model commands default to the
current session. Summarize is explicitly Sol/low; PDF, delegated work and Dreaming's internal
completion use Sol. The scheduled Dreaming agent turn inherits the default Astra/high.
At that time, GLM was available for manual selection, with no automatic fallback from Codex. The
September 11 main-agent fallback policy superseded that historical boundary. Default Astra
execution passed with actual `write`/`read` tools and no reroute; the installed Sol/low summary CLI
passed, session model/effort commands left global defaults unchanged, and the updated guidance was
delivered to Telegram. Seventeen offline tests, the live verifier and installed observer passed.
Dreaming's internal Sol route passed an isolated core check; the scheduler and a complete owner-memory
cycle were not exercised. Prior execution evidence below retains its original model scope.
See [OPENCLAW_PLUGINS.md](OPENCLAW_PLUGINS.md) for configuration, usage and bounded evidence.
On 2026-09-08, the image-request `Yield failed` report was traced to a mistaken `sessions_yield`
call for independent image generation. The original image and Telegram photo delivery had completed;
the live workspace guidance now specifies the correct wait/finish behavior. Eleven policy tests
and live gateway verification passed. No new image request or phone-download acceptance was run;
the bounded receipt is in [OPENCLAW_TELEGRAM.md](OPENCLAW_TELEGRAM.md#image-generation-wait-correction-2026-09-08-kst).
GLM-5.3 Flash `/think max` support was added on 2026-09-07 KST with pinned runtime patches,
offline request validation, live command persistence and two successful isolated GLM responses.
That acceptance retained the owner session's `high`; [OPENCLAW_TELEGRAM.md](OPENCLAW_TELEGRAM.md#glm-max-thinking)
records the separate wire, provider-response and Telegram-delivery evidence boundaries.

> **2026-09-06 — development stopped. This Android app is retired.**
>
> The owner retired the app and the product problem it solved. Personal schedule, reminder, alarm,
> and Kakao notification tooling is retired with it and is not being rebuilt elsewhere. Assistant
> work continues on the already-hardened macOS OpenClaw Gateway, reached through Telegram instead
> of through this app, and aimed at different work (coding, research, operations monitoring, media
> and document processing) that suits Mac hardware.
>
> The final source state is tagged `rc11-final`. Nothing below is retracted: the evidence ledger and
> its Fold8 receipts remain accurate for the exact artifacts they name. It is kept as a record, not
> as a plan. The open acceptance work listed here and in [HANDOFF.md](HANDOFF.md) will not be
> completed, and the promotion gate below is closed unfinished.
>
> Two `core:openclaw` corrections landed on 2026-09-06 immediately before the stop, so the tag
> includes them. Both came from diffing the installed OpenClaw 2026.8.1 against the then-current
> 2026.9.2 release. The hello check now accepts an explicit set of qualified releases
> (`2026.8.1`, `2026.9.2`) rather than one hard-pinned string, and the chat error envelope accepts
> 2026.9.x's added optional `errorDetail` object without reading its contents, so a real remote
> failure is no longer misreported as a protocol violation. `core:openclaw` passed 104 JVM cases in
> both variants with zero failures after the change. Wire protocol v4 itself is unchanged between
> those releases; the protocol constants file is byte-identical.

**2026-09-05 stopping point:** the owner chose to stop after one successful actual Fold8 Korean
answer. HTTPS/pairing and the one request passed with local counts preserved. Cancel/restart,
health extension activation, Android proposal integration, recovery retry and24-hour observation
remain. See [REMOTE_AGENT_RESUME.md](REMOTE_AGENT_RESUME.md) for current evidence and exact restart scope.

Last reviewed: 2026-09-05 against the current `versionCode=11`,
`versionName=1.0.0-rc11` working tree.

## 2026-09-05 optional OpenClaw / GLM remote-agent delta

The local LiteRT model remains the shipped path. A new, default-off OpenClaw client scaffold adds a
separate remote-agent boundary rather than silently replacing or automatically routing around the
local model. Durable enablement, an HTTPS or loopback endpoint, system or pinned-certificate trust,
and both a process-local owner-consent gate and crash-durable revocation barrier are all required
before it can connect. The OpenClaw-only barrier starts blocked and eagerly reconciles a fixed,
content-free `noBackupFilesDir` marker before any future coordinator can publish. Disable publishes
and fsyncs the marker before DataStore, removes it only after a durable false commit, and leaves it
blocking on failure; if publication itself cannot be proved, disable still best-effort commits
false but reports failure and remains blocked. Startup uncertainty also repairs toward false.
Unique-token CAS, serialization, and epochs prevent stale enable/disable completion from clearing a
newer intent. Credentials and the device identity are endpoint-bound records in the Android
Keystore-backed vault;
compare-and-set storage and rollback prevent a stale connection epoch from overwriting a newer
owner choice.

`core:agent` now defines bounded start/status/event/wait/cancel contracts, and `core:openclaw`
implements the pinned OpenClaw 2026.8.1 protocol-v4 projection. Each proposed remote turn uses a
fresh one-shot model-run session, no model tools, no delivery side effect, and bounded output,
event, and timeout limits. The wire parser preserves only bounded structured connect codes and
retry policy, including the actual nested `error.details.code` used by 2026.8.1; remote prose,
endpoint text, tokens, prompts, outputs, and device metadata do not enter public errors or object
rendering. Foreground loss or disable seals new requests, enqueues at most 64 best-effort
`chat.abort` frames for nonterminal reservations, then closes the socket. Queueing is not reported
as remote cancellation: abrupt loss can still leave provider cost and OpenClaw SQLite/WAL residue
until the run's maximum 30-minute timeout.

The app coordinator is now wired into the Activity/ViewModel/Compose flow with explicit local/remote
selection, masked endpoint/token setup, connection consent, progress/answer/cancel, and foreground
revocation. Existing local history, summary, long-term memory, credential vault, and Tool execution
remain the authoritative implementations; the remote route currently sends only its current
question plus any quotes the owner ticked. See [`FEATURE_REUSE_AUDIT.md`](FEATURE_REUSE_AUDIT.md)
for the owner's overlap audit.

**2026-09-06 owner change: no per-question send approval, and one shared transcript.** At the
owner's instruction the per-question external-disclosure dialog is removed: selecting the remote
engine and consenting to the connection is the standing decision, so the send control dispatches
the visible question directly. Everything else about a send is unchanged — the question and the
ticked quotes are still exactly what the owner typed and selected, the selection is still
revalidated against current storage and memory consent immediately before dispatch, an edited
prompt, expired memory, withdrawn consent, endpoint change or backgrounding still stops a send
already in flight, and a connection without durable consent still cannot carry a question. What
the owner loses is the last look at the exact composed outbound text before it leaves the device;
the connection-consent dialog now states that plainly. The remote turn is also no longer
process-memory only: its typed question and received answer are written to the same Room
conversation the local model appends to, through a narrow `OpenClawRemoteTranscript` seam, so
restart, history, transfer, later context, and summarization treat both engines identically. The
question is stored before the run starts (an interrupted run keeps it) and whatever answer text
arrived is stored at any terminal outcome, including a cancelled or connection-lost partial. Only
the typed question is stored, never the composed quotes, because each quote is already a row of
its own. A failed write is reported and never claimed as stored: the screen keeps showing the
unstored rows. The remote pane now renders that shared conversation with the same `ChatTranscript`
composable as the local screen.

**2026-09-06 Fold8 receipt for this change.** The owner approved the physical update. The
read-only `preflight-fold8-release-update.sh` passed first on SM-F971N (`R5KL801YXWE`, API 37,
arm64-v8a) with the unchanged release certificate
`e0f66d4b4c8064db6a9d46097d77903cf13fbccacbdfc6e49e9f7c380b8e457a`. Same-certificate
`adb install -r` of the owner-signed release pair succeeded; the pulled-back `base.apk` hash equals
the local build exactly (`45ee97fc289a3805a7a245b5941c455b093b4fe6cebcd5f333d5bc87f241a548`, test
APK `f2b3b3dff2eb549bb3cdbe653760415404fdb8a47e137307227c449a975dd475`) and `firstInstallTime`
stayed `2026-08-23 18:10:37`. Content-free snapshots before and after installation were identical:
23 conversations, 104 messages, 0 memories, 57 notifications, three credentials present, the
3,659,530,240-byte model, Kakao reply disabled, settings digest
`e5d5d2c4353bff258d49ed120e1e569f097af8892146e262a9133696823787cd`.

`Fold8OpenClawSurfaceAcceptanceTest` passed 1/1 in 0.967 s without contacting a provider, so the
new pane renders and returns to local on the real device. The new opt-in
`Fold8OpenClawLiveAcceptanceTest#oneSendNeedsNoApprovalAndIsStoredInTheSharedConversation` then
passed 1/1 in 8.767 s over Tailscale HTTPS using the stored device credential, so it added no
pairing. Before authorizing it, the Gateway's own configuration was read and independently
confirmed to cap `openrouter/z-ai/glm-5.3-flash` at 2,048 output tokens. It spent exactly one
provider call: one send with no approval step in the path, a 55-byte Korean answer meeting the
fixed 대한민국/수도/서울/REMOTE-KO-OK claims, `questionStored` and `answerStored` both true, and a
Room read-back showing the last two rows of the active conversation are the owner's typed question
followed by exactly the received answer. Exactly two message rows and zero new conversations were
added, and the memory count was unchanged.

The durability the old design could not provide was then verified directly: after `am force-stop`,
a fresh process reported 106 messages against the 104 baseline, with conversations, memories,
notifications, credentials, model size and settings digest all unchanged. Post-run platform
thermal status was 0, the battery was 69% at 32.0 C while charging, and the app's exit history
contained only instrumentation force-stops with no crash, ANR, or low-memory kill. The Mac Gateway
stayed healthy on the same PID and is still listening only on loopback. This receipt covers one
fixed short question on the stored-credential path; cancellation, network loss, process restart
during a run, long answers, and the context-picker path on the phone remain untested. As always on
a dirty tree, this ledger entry was written after the tested artifact was built, so rebuilding now
changes the packaged whole-tree provenance field and therefore the APK hash; the receipt is bound
to the named pulled-back bytes, and code equivalence must be established by comparing APK entries.

**2026-09-06 Fold8 cancellation receipt, and two findings that changed the test.** Run on the same
device and certificate after a further same-certificate update whose pulled-back hash matched
(`f57e2f8c7890ce361a51d0ebcc481c25ef30a0b4f668a5b6d5509dba7ffc288c`).
`Fold8OpenClawLiveAcceptanceTest#cancelsOneStartedAnswerAndStoresOnlyWhatArrived` passed 1/1 in
6.972 s on the stored-credential path. The owner's cancel stopped the started run in **102 ms**,
against the 60-second timeout it would otherwise have run to, and the Gateway's own log records
`[agent] run 91687052-… ended with stopReason=aborted`. The turn stored exactly one row, its
question, with `answerStored` false and zero answer bytes received; conversations and memory were
unchanged.

The first shape of that test could not run, and the reason is worth keeping. **This Gateway hands
the app its answer when the run completes rather than as deltas.** The 40-item cancellation
question therefore produced no visible text at all and simply reached its own timeout
(`embedded run timeout … Request timed out before a response was generated`), so waiting for a
partial answer in order to cancel it is not possible on this configuration. Nothing in the app
buffers that text; there was none to buffer.

**OpenClaw 2026.8.1 also surfaces an aborted run to the client as an error rather than an aborted
terminal.** Reading the pinned runtime shows this is deliberate: its failover decision returns
`action: "surface_error"` when a run's terminal is `aborted` or `timeout` with
`source === "external"`, which is exactly our `chat.abort`. Its own log says `stopReason=aborted`
while the `agent.wait` result the client sees reports `status: "error"`, so the app reported
`원격 모델 실행에 실패했습니다` for a run the owner had successfully cancelled.

A third observation came out of the same work. Cancelling in the first moment after a send, before
`RemoteAgentStartResult.Started` has produced a run id, took the sealed-connection path and
reported an unknown outcome rather than cancelling; the UI enables cancel as soon as the turn is
running, so an owner tapping immediately reached it — and the remote run then kept costing until
its own timeout.

## 2026-09-06 resolution of all three remote findings

All three were fixed and each was verified on the Fold8 against the signed release.

**Long answers complete.** The Gateway takes its per-run timeout from the value this app sends in
its start params, so the old 60 seconds was itself the cause; no Mac configuration was changed. The
run limit is now 180 s with a 200 s observation bound, and `RemoteAgentContractLimits` already
allowed both. Measured: the 40-item question that previously produced nothing and expired at 60 s
completed in **120.757 s** with a 666-byte answer, stored as two rows. The absence of incremental
deltas is unchanged and remains deliberate: this one-shot mode runs with internal session effects
so it leaves no session residue on the Mac, and that is what suppresses live streaming. Text still
arrives when the run completes.

**A cancelled run is reported as cancelled.** The evidence used is not the error frame and not
timing: it is the Gateway's own `chat.abort` acknowledgement, which reports `aborted: true` against
this exact run id and is only sent for a run that was still live. `RunRecord.abortRequested` is set
from nothing else, so a refusal, a protocol mismatch, or an `already terminal` answer all leave it
false. When it is set, a `FAILED` terminal is read as `CANCELLED` on both paths a terminal can
arrive by — the chat event and the `agent.wait` result. A `TIMED_OUT` terminal is deliberately not
reinterpreted, because expiring is not being stopped. The residual limit is narrow and worth
stating: if a run failed independently in the window between our accepted abort and its terminal,
this labels it cancelled. Measured: cancel to terminal in **102 ms** against a 180-second timeout,
terminal `원격 실행 취소가 확인되었습니다`, one row stored and no invented answer. This supersedes
the paragraph above, which recorded the behaviour before the fix.

**A cancel tapped before the run id exists is held.** It no longer closes the socket and reports an
unknown outcome; the request is remembered and sent the instant start returns a run id. Measured:
**0.842 s**, the connection stayed `CONNECTED`, the terminal was a confirmed cancellation, and one
row was stored.

Receipts, all on SM-F971N against same-certificate release updates whose pulled-back hashes matched
(final app APK `fa4776cacfade4d638f6f5af4d2036e3b8242a71db7138f990449a58cd1812e5`) with
`firstInstallTime` preserved at `2026-08-23 18:10:37`:
`cancelsOneStartedAnswerAndStoresOnlyWhatArrived` 1/1 in 6.991 s,
`holdsACancelTappedBeforeTheRunIdExists` 1/1 in 0.842 s,
`completesALongAnswerWithinTheRaisedRunTimeout` 1/1 in 120.757 s,
`oneSendNeedsNoApprovalAndIsStoredInTheSharedConversation` 1/1 in 10.781 s, and the provider-free
`Fold8OpenClawSurfaceAcceptanceTest` 1/1 in 0.894 s. Host coverage adds four new JVM cases in
`core:openclaw` for the abort rule — including that a refused abort, an unrequested abort, and a
timeout are all left alone — and two in `app` for the held cancel. Final device state: 24
conversations, 122 messages, 0 memories, 57 notifications, three credentials present, the
3,659,530,240-byte model, settings digest
`e5d5d2c4353bff258d49ed120e1e569f097af8892146e262a9133696823787cd`, thermal status 0, battery 83%
at 32.5 C, and no crash, ANR, or low-memory exit. The conversation and message baseline moved from
23/112 to 24/114 between sessions through ordinary owner use of the phone, not through these runs.

Code equivalence for this receipt was established the way this document asks for, by comparing APK
entries rather than reusing a whole-file hash. The APK still installed on the phone hashes exactly
to the tested `fa4776ca…12e5` with `firstInstallTime` preserved, and rebuilding the tree after the
documentation edits above produces `883a8fbb…dba4`, which differs from it in exactly one of 174
entries: `assets/release-provenance.json`. Every code, resource and native entry is byte-identical,
so the device ran precisely this source. A later snapshot confirmed the device unchanged at 24
conversations and 122 messages, with the notification cache at 58 after one ordinary arrival.

## 2026-09-06 remaining remote gates: network loss, process restart, context picker

The three items that were still open after the cancellation work were all run on the Fold8 against
app APK `be324da54e3dab32fbf2b90d0e7b77d5a58585b50a4fbfadb1b652a82bec5303`, installed by
same-certificate update with the pulled-back hash matching and `firstInstallTime` preserved.

**Network loss during a live run.** `networkLossDuringARunSealsItWithoutInventingAnAnswer` passed
1/1 in 33.121 s. With a run in flight and its question already stored, the host disabled the
phone's Wi-Fi and mobile data. The app sealed the run, reported `DISCONNECTED`, and used the
uncertainty terminal — `원격 상태를 확인할 수 없습니다. 취소 완료와 과금 여부는 확인되지 않았습니다.`
— rather than claiming a completed answer or a confirmed cancellation. One row was stored, the
question, and no answer was invented for text that never arrived. This is the one test in this
group that mutates device settings; both radios were restored immediately and verified back at
`wifi=1 data=1 airplane_mode=0`, with the phone pinging the Mac's tailnet address again, after
which `reconnectsUsingStoredDeviceCredentialWithoutBootstrap` passed 1/1 with zero submissions and
unchanged local counts.

**Process restart during a live run.** Split across two invocations so the kill is real rather than
simulated: `startsARunLeftInFlightForTheProcessRestartCheck` passed 1/1 in 1.053 s and deliberately
returned with the run still live, and instrumentation completion then killed the app process — the
phone reported no pid for the package immediately afterwards. `aRunKilledWithItsProcessLeavesThe
QuestionAndNoInventedAnswer` then passed 1/1 in 1.578 s in the fresh process: nothing reconnected on
its own, nothing was running, no remote question or answer text was restored, the killed run's
question was still the last transcript row, and the restart added and removed no rows. Activity
recreation was already covered separately; this is the first receipt for an actual process death
with a run in flight.

**The context picker, proved by what reached the model.** `selectedContextIsWhatReachesTheModel`
passed 1/1 in 29.436 s across two provider calls. The picker offered nothing until it was opened
and preselected nothing. A first turn planted the fixed nonce `PE-CTX-7Q4M9` in the transcript; the
second turn asked a question that does not contain that nonce and selected only the planted row as
its reference. The 12-byte answer was exactly the nonce, so the selected quote demonstrably left
the device — this is transmission evidence, not an assertion about the composed text. The selection
and offered items were cleared after sending, four rows were stored across the two turns with no
new conversation and an unchanged memory count, and each stored USER row is the owner's typed
question rather than the quotes composed around it. Reading one offered item back across the split
APK needed `OpenClawRemoteContextItem` public members added to the existing R8 keep allowlist; no
production capability was broadened.

Final device state: 24 conversations, 128 messages, 0 memories, 58 notifications, three credentials
present, the 3,659,530,240-byte model, settings digest
`e5d5d2c4353bff258d49ed120e1e569f097af8892146e262a9133696823787cd`, thermal status 0, battery 47%
at 30.4 C, no crash, ANR, or low-memory exit, and `firstInstallTime` still `2026-08-23 18:10:37`.

Storage integrity held across every failure shape observed that day — a Gateway run timeout, a
connection sealed before the run id existed, and two abort-as-error terminals. Each stored its
question and none stored an answer it never received, so the transcript went 106 to 112 rows across
six provider calls with no invented content. Final state after the last run: 23 conversations,
112 messages, 0 memories, 57 notifications, three credentials present, the 3,659,530,240-byte model,
settings digest `e5d5d2c4353bff258d49ed120e1e569f097af8892146e262a9133696823787cd`, thermal status
0, battery 74% at 32.3 C, and no crash, ANR, or low-memory exit.



The source passed focused app unit/lint, debug/release Kotlin and Android-test compilation;
337 app JVM cases include 17 new remote-controller cases. A further 88 core OpenClaw JVM cases
passed, including a fix for the pinned Gateway sending presence metadata before hello and the
immediate post-hello scheduling race. These counts are host contracts, not physical pairing or
provider acceptance. Scoped emulator and physical evidence is recorded in
[`REMOTE_AGENT_ACCEPTANCE.md`](REMOTE_AGENT_ACCEPTANCE.md) as each gate completes.

The current Mac has an exact OpenClaw 2026.8.1 / Node 26 `personaledge` Gateway running under a
KeepAlive LaunchAgent on `127.0.0.1` and `[::1]` port 18789. Gateway and OpenRouter credentials were
migrated into OpenClaw's private SQLite secret store as SecretRefs; the legacy plaintext profile
file and temporary key-transfer material were removed, and the official secret audit reported no
plaintext, unresolved, shadowed, or legacy residue. Wildcard Tool denial, token authentication,
model/plugin allowlists, and the GLM-5.3 Flash-only model policy are active.

The repository now contains backup-first install/adopt/harden/verify/audit/watchdog/rollback,
one-shot paid-smoke, and Tailscale Serve assets under `scripts/openclaw`; their isolated deployment
suite and all 33 host-script tests passed. On 2026-09-05 the owner-approved managed adoption
completed after correcting two installed-CLI probe contract mismatches: local token-auth CLI calls
omit device identity and cannot use the optional principal-scoped create idempotency key, while
incognito creation requires a canonical lower-case dashboard incognito key. The empty probe now
dispatches once with deletion armed before sending; model-run idempotency is unchanged. The updated
deployment asset regression passed, and the live apply proved `tools.effective=0`, deleted its
probe, and atomically published the schema-4 final deployment manifest with no candidate remaining.
The Gateway was healthy at PID 9290; the five-minute watchdog completed its first run with exit 0
and a healthy policy/secrets/plugins receipt. The verified pre-adoption backup is under
`OpenClawBackups/adoption-20260905T120932Z`, and the old workspace is retained at
`quarantine/adopt-20260905T120932Z/workspace-before-adopt`. Three earlier temporary hardening
recovery copies were removed under the owner's prior secret-cleanup approval after this success;
persistent backups and quarantines remain.

The deep security audit completed with zero critical findings, one warning, and one informational
finding (`operations/security-audit-20260905T121632Z.json`). Its warning is a deep Gateway probe
failure due to missing `operator.read` scope; independent token-auth health checks pass, but the
deep probe itself did not pass. Doctor returned four warnings and no errors across 59 checks,
including one auth-profile metadata warning despite the separate configured OpenRouter SecretRef.
The audit wrapper now distinguishes validated warning-only doctor exit 1 from malformed/fatal or
error-bearing results. Eight new isolated regressions cover that boundary. The first suite run
after those changes exited 1 without a retained diagnostic; subsequent traced and fresh untraced
runs passed, so the unexplained first result is not counted as a pass.

The owner approved one fixed, tool-free OpenRouter connection test (`thinking=low`, 2,048 output
tokens, 60-second run timeout). The first local attempt,
`personal-edge-glm-smoke-f9a980cc-4da9-4fd8-8b0b-63edfe7fe649`, failed in sandbox provisioning with
Docker `ENOENT`, before the pinned runner reaches provider inference. Colima 0.10.3 and Docker CLI
29.8.0 were then installed without changing Node/OpenClaw. A new `personaledge` VM uses VZ,
2 CPUs, 2 GiB memory, 10 GiB data/root disks, and only the writable profile `sandboxes` host mount.
The pinned documentation's sandbox Dockerfile was built without a repository build context;
the ARM64 image is `sha256:b073bd6e1ed8d897e79b5268a40abe24de29794ec5dc9a62d0f039dc6d8736e2`.
A non-network, read-only-root, non-root container preflight passed. The owner-login LaunchAgent
`com.personaledge.colima-runtime` is running (first run, PID 61551), and Docker server 29.5.2
responds. This does not yet prove reboot, Docker-crash recovery, or sustained availability.

The resumed smoke, `personal-edge-glm-smoke-746b967c-7855-4a56-a8d6-eefa13aa283e`, dispatched exactly
one logged POST to OpenRouter `/api/v1/chat/completions` at 2026-09-05 12:26:43 UTC. It returned HTTP
404 in 252 ms: `No endpoints found that can handle the requested parameters`. The Gateway's generic
`model_not_found` wrapper is not sufficient to conclude that the model id is invalid: public model
and ZDR endpoint lists include GLM-5.3 Flash. At that historical point, precise causation was unresolved and no successful answer existed.
The later controlled differential isolated `max_completion_tokens`; the narrow GLM route patch
then passed the 13:39 UTC Gateway smoke with an exact-model reply, zero tools, and zero run-owned
SQLite rows after cleanup. See `REMOTE_AGENT_ACCEPTANCE.md`. Failed-call billing was not independently checked. The initial approval's one actual provider request was used. The owner subsequently authorized
bounded test calls without repeated approval; the live differential is recorded below. Strict parameter checking, ZDR, and data-collection denial remain
unchanged. Read-only exact-id checks found zero run-owned logical SQLite rows for both attempts;
this is not secure erase. Terminal receipts are under
`operations/glm-model-run-terminal-20260905T121719Z.json` and
`operations/glm-model-run-terminal-20260905T122638Z.json`. A fresh final health check returned
`ok=true` with both required plugins loaded and no errors, and the Gateway still listens only on
IPv4/IPv6 loopback.

A subsequent offline reconstruction used the pinned host capability resolver, OpenRouter stream
wrapper, and completion adapter with a dummy key and the fixed marker. Node denied network access;
one intercepted request contained only `max_completion_tokens`, `messages`, `model`, `provider`,
`reasoning`, and `stream`, with the expected 2,048-token cap, `low` effort, and unchanged routing
policy. No `store`, `parallel_tool_calls`, or tools were serialized. This is reconstructed
provider-wrapper evidence, not a capture of the original Gateway POST or a replay of its complete
pipeline. This reconstruction alone did not prove the unsupported field; the later paid differential did. The real key remains a configured, resolved SecretRef; re-entering it is not indicated
by the observed 404.

The owner installed and logged in Tailscale on Mac and Fold8; both now report online in the same
tailnet. `pmset autorestart=1`, `sleep=0`, and `standby=0` were read back after owner administrator
authentication. HTTPS Serve and actual pairing are separate gates; no 24-hour soak has completed. FileVault also means an unattended cold boot cannot make this user LaunchAgent available
before owner login. The revised schema-2 observation soak also checks dedicated Docker/Colima/daemon generations.
A real idle Colima recovery drill found the normal stopped-VM Docker context transition was not
accepted; fallback restored the runtime and a bounded correction is in progress. No complete
24-hour observation exists yet. Gateway provider inference has passed; real Fold8 inference and
the observed availability window remain distinct acceptance gates.

## 2026-09-01 photo and voice input delta

The pinned artifact is multimodal, and this delta uses it. One photo or one short voice clip may
be attached to a turn, and a dictation path turns speech into an editable composer draft. The
design, the benchmarked task set, the bounds, and the open acceptance work are in
[`MULTIMODAL_INPUT.md`](MULTIMODAL_INPUT.md).

The governing rule is that **a turn carrying media is given no Tool schema at all**, enforced
independently in `TurnMediaPlan`, in `ManualToolAgentController.runTurn`, and by the ViewModel's
`maxSteps = 1` media budget. Voice commands therefore reach a Tool only through dictation: the
transcript lands in the composer, the owner reads and edits it, and the send that follows is an
ordinary text turn that earns its scope the ordinary way.

Media payloads never enter Room or the transfer archive. Audio stays in memory; an external camera
necessarily writes one temporary file in the app's private cache, where deletion is attempted after
read, for stale leftovers at process start, and for every leftover before a new capture. Room schema
11 adds `messages.attachment_summary`, a short app-written kind/source/whole-second code. Transfer
schema `6`/payload `2` preserves that content-free USER-row shape (legacy `5`/`1` imports it as
null), and attachment-only rows reach later context and summarization through an app-authored label.
`DiagnosticEvent.MediaAttachment` records kind, stage, byte count, and audio seconds only.

`models/model-manifest.json` now declares `supportsImageInput` and `supportsAudioInput`, and the
runtime refuses a modality the verified manifest does not declare. That declaration is evidence
from the artifact itself: the pinned `gemma-4-E4B-it.litertlm` header contains
`tf_lite_vision_encoder`, `tf_lite_vision_adapter`, `tf_lite_end_of_vision`,
`tf_lite_audio_encoder_hw`, `tf_lite_audio_adapter`, and `tf_lite_end_of_audio`, and its embedded
jinja template renders an `image` item as `<|image|>` and an `audio` item as `<|audio|>`. The
`qwen8bLab` manifest declares both false.

Historical verification for the initial delta. `doctor.sh` passed; `test-host-scripts.sh` passed 32/32; a forced
`./gradlew --offline test --rerun-tasks` produced 967 same-run JVM cases with zero failures
(app 263, core:agent 185 debug + 185 qwen8bLab, core:tools 201, core:llm 35 debug + 35 qwen8bLab,
core:diagnostics 40, core:data 23); `./gradlew --offline test lint assembleDebug assembleRelease`
and `./gradlew --offline releaseGate` both passed, the latter regenerating and verifying
SBOM/provenance with `database.schemaVersion=11`, `source.dirty=true`, and the unchanged model
hash `0b2a8980...52e0`. On the account-free API 37 ARM64 foldable AVD started `-read-only
-no-snapshot-save`, `scripts/run-avd-regression.sh` selected 267 reviewed methods: 237 passed, 30
stopped at reviewed assumption guards, none failed. That includes the two new account-free classes
`ImageAttachmentLoaderTest` and `MediaCaptureStagingTest` (14 methods, all passing) and five new
`core:data` cases covering the 10→11 migration and the attachment column.

`scripts/run-avd-release-readiness.sh` then passed on the same disposable AVD against a matched
owner-signed minified release pair: the provider-free ABI linkage smoke passed 1/1 and the five
canaries plus 28 physical/live methods all stopped at their reviewed opt-in guards, for 34
selected, 1 passed, 33 guarded, 0 failed, under certificate
`e0f66d4b4c8064db6a9d46097d77903cf13fbccacbdfc6e49e9f7c380b8e457a`. That matters for this delta
specifically: the new instrumentation classes compile into the release test APK, and this lane is
what shows the minified release class surface still links. It is packaging and linkage evidence,
not physical execution.

Those AVD lanes bound the then-final initial media source, not the later cache-sweep, transfer,
attachment-only context, or fresh-runtime acceptance corrections. Their tested app APK was
`9a77f9d050d02171e08db5869b211802644843c2ee3ff5787c8ad54d852b06ce`, its release androidTest APK
`74530d53e44ac7cfab79c7b4409a3c4a8802f8da13d5057cb71f585d88b2e88a`, and the same hash came out of
the final root `releaseGate`. As always on a dirty tree, provenance packages a whole-tree
`source.stateSha256`, so the documentation edits that record this receipt change the packaged
provenance field and therefore the APK hash; compare APK code and resource entries rather than
reusing this whole-file hash to establish code equivalence.

Current-source revalidation after those later corrections completed on 2026-09-01. A fresh
read-only, no-snapshot API 37 ARM64 foldable AVD ran the reviewed debug lane with 279 selected
methods: 248 passed, 31 stopped at intentional assumption guards, and none failed. This includes
the provider-free `GroundedWebSearchJourneyTest`, the expanded mirrored-EXIF bitmap checks, camera
cache cleanup, schema-11 attachment transfer/context coverage, and the existing owner/live guard
matrix. The matched owner-signed minified release lane then selected 35 methods: the ABI linkage
smoke passed, five canaries and 29 physical/live cases reached their reviewed opt-in guards, and
none failed. `./gradlew --offline releaseGate` and the 32-case host-script suite also passed. No
Fold8 was connected or changed, so corrected per-turn media-token, physical UI, thermal, and live
provider acceptance remain open.

Three stale guards in the inherited working tree were corrected rather than worked around, and
each is a counting change only. `app/gradle.lockfile` was missing `debugAndroidTestRuntimeClasspath`
for 14 already-pinned coordinates, which made `lint` and every androidTest assembly fail before
this delta; the regenerated lock adds that one configuration and changes no module or version. The
AVD runner's reviewed inventory said 34 app androidTest files when 35 existed, and its app suite
expected 119 methods when the allowlisted classes already held 126 and the `core:llm` suite already
held 12 rather than 10; `scripts/run-avd-release-readiness.sh` carried the same stale 34-file
inventory. Those baselines are now measured against a real emulator run, not assumed.

### Historical 2026-09-01 Fold8 receipt for this delta

The owner connected SM-F971N (`R5KL801YXWE`) and approved a same-certificate release update. The
read-only `preflight-fold8-release-update.sh` passed first without device mutation. `adb install -r`
of the gated release app/test pair succeeded under certificate
`e0f66d4b4c8064db6a9d46097d77903cf13fbccacbdfc6e49e9f7c380b8e457a`; the pulled-back `base.apk`
hash equalled the local build exactly and `firstInstallTime` stayed `2026-08-23 18:10:37`.
Content-free preservation snapshots taken before and after installation were identical:
20 conversations, 95 messages, 45 notifications, 0 memories, three credentials present, the
3,659,530,240-byte model, Kakao reply disabled, settings digest
`af13c42b582270e1e2fea4bdcdfc0224f68d6fddc4d6b6a5ef087f1adab3a5fc`. No conversation, reminder,
calendar, or alarm row was created.

Three findings came out of it, and all three changed the code.

1. **The engine was never loading its encoders.** `EngineConfig` carries `visionBackend`,
   `audioBackend`, and `maxNumImages`, and this app set none of them, so a media turn failed
   `NATIVE_FAILURE` with `INVALID_ARGUMENT: Vision executor should not be null` — *after*
   `stb_image_preprocessor` had already decoded and patched the image (768x512 to 960x624,
   2,340 patches against a 2,520 limit). Every signal short of the native log pointed at a bad
   attachment rather than an unconfigured engine.
2. **A synthetic photo was observed to work.** With the executors configured, the `IMAGE_TEXT_EXTRACT` turn on a
   synthetic high-contrast card returned exactly the six rendered digits and nothing else, with no
   Tool call. A model can invent a description of a photo it never received; it cannot read back a
   number it was never shown.
3. **Loading the encoders costs the GPU backend for the whole engine.** Same session, same prompt:
   without them GPU stayed active and a text turn took 20,640 ms; with them the engine fell back to
   CPU, the same text turn took 31,534 ms, initialization went from 8,183 ms to 10,755 ms, and PSS
   reached 6,208,525,312 bytes against roughly 4.79 GB previously recorded for text-only CPU runs.
   Enabling encoders because the manifest declares them would therefore have made every text turn
   about 53% slower for a default-off feature. Initialization now takes the modalities the owner
   actually enabled, the runtime fails a turn closed when it carries a modality the engine did not
   load, and the setting applies from the next app start exactly as the backend choice does.

**Measured on the Fold8, 2026-09-01.** The corrected gate ran and passed, 1 case in 82.714 s on
the CPU backend. Every control and media observation got a newly initialized runtime and exactly
one turn, `getTokenCount()` was sampled immediately before and after that turn, and both answers
were capped at 32 decode tokens so answer-length variation could not reach either threshold. All
four runtimes loaded the same `vision_encoder`, `vision_adapter`, `static_audio_encoder`, and
`audio_adapter` caches, so configuration cannot explain the difference between a control and its
paired media turn.

| observation | init | turn | TTFT | context tokens | answer |
|---|---|---|---|---|---|
| image control | 1,158 ms | 14,056 ms | 12,989 ms | 648 | 23 code points |
| image | 1,583 ms | 23,691 ms | 23,114 ms | 906 | 6 code points |
| audio control | 1,148 ms | 14,137 ms | 13,065 ms | 610 | 21 code points |
| audio, 15 s clip | 1,052 ms | 20,587 ms | 19,822 ms | 984 | 19 code points |

One image cost **258 context tokens** against the documented 256, and a 15-second clip cost
**374 tokens**, or 24.93 per second against the documented 25. Both published figures are therefore
confirmed on this artifact rather than carried as documentation. The image turn again returned
exactly the six digits rendered on the synthetic card, and no turn produced a Tool call. Battery
moved 64% to 65% while charging and temperature 31.0 C to 32.2 C across the run.

Owner state was untouched: content-free preservation snapshots before and after were identical at
20 conversations, 95 messages, 48 notifications, 0 memories, three credentials present, the
3,659,530,240-byte model, and settings digest
`af13c42b582270e1e2fea4bdcdfc0224f68d6fddc4d6b6a5ef087f1adab3a5fc`. The pulled-back `base.apk`
hash equalled the local build (`e20d4b42...0396`) and `firstInstallTime` stayed
`2026-08-23 18:10:37`.

The earlier roughly 220-token image figure came from an invalid comparison and is superseded by the
258 measured here. Korean transcription accuracy, the camera and picker flows through the
production ViewModel path, sustained-media thermal behaviour, and the fold/DeX layouts for the new
composer controls all remain untouched. There is no GPU media path on this device to measure.

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
uses one shared NFKC/spacing-tolerant relevance rule at both provider quality and final selection, selects at most two sources, excludes unsolicited obituary/personnel-list noise, synthesizes answer-first prose under a tool-free and grounded validator, and appends only Kotlin-owned HTTPS sources. Current-officeholder queries additionally require a direct current role-to-name assertion with an explicit current-time marker; generic constitution, election, term, and historical appointment/event pages fail answerability and may trigger the existing one-shot Tavily fallback. Conflicting names fail closed unless one consistent government-source name resolves them. Invalid synthesis falls back without a second search. A freshness/recovery turn cannot finish from model prose without satisfying its trusted read contract. |
| Automatic/contextual public search | Implemented; officeholder path host verified, correction-chain storage scoped-AVD verified, live device/provider pending | A closed public-knowledge grammar may expose `web_search` without the literal word “검색”. A complete local answer remains local; one recognized explicit knowledge-gap answer may trigger one search. Separately, `current entity + office + who/name` is mandatory grounding and runs a canonical owner-derived search before local decode; `현재 대한민국 대통령이 누구야?` therefore cannot terminate with the model's no-live-information prose. Explicit public subjects followed by `최신 버전`, `최신 안정 버전`, `최신 릴리스`, or `최근 릴리스` also run immediate grounding; subjectless/current-installed-version, private `내/우리` software, compound-subject, and install requests do not. The exact movie scenario passed on the owner-signed Fold8 with a complete body and app-owned HTTPS sources. The correction/review chain Room resolver passed in the 2026-09-01 disposable API 37 AVD suite; current-officeholder and software-release routing use provider-free fixtures only. It never searches assistant text, summary, memory, Tool/provider output, private/sensitive content, writes, communication, weather, or route text. General confidently wrong prose without a recognized gap marker and the wider live-provider matrix remain unqualified. |
| Search follow-up context | Implemented and scoped-AVD verified; physical model pending | Closed previous-result summary/organization/source requests expose no Tool, reject a hallucinated repeat search before the gateway, and preserve the prior answer lead plus source tail. A normal subjectless search inherits the immediately preceding completed safe USER question. A bounded run of closed search corrections may be skipped. The exact screenshot chain may additionally cross one completed answer-review sentence from a full-match Korean grammar before resolving the original durable USER ordinal; appended private/write/new-topic text, an incomplete turn, or any unrelated USER row stops the walk. Explicit standalone re-search wording remains a fresh read, and generic subjectless wording without a safe owner source fails closed. |
| Dialogue intent and context quality | Implemented; bounded Fold8 acceptance passed | A separate fixed eight-case lexical regression lane covers long-prompt salience, compound constraints, relevant recent context, newest correction, stale-summary override, irrelevant history, clarification, and conversation isolation. Its reviewed-set pass additionally requires a six-dimension human rubric for intent, context, constraints, unsupported claims, format, and direct usefulness on all eight answers; even that is bounded synthetic judgment, not general semantic proof. Production-path Fold8 acceptance adds four exact synthetic no-Tool cases through ViewModel/Room/context/LiteRT. The clock-prioritized full-budget candidate first passed those cases three consecutive times (12/12) across NONE, MODERATE, and SEVERE. The final thought-streaming code candidate passed 4/4 again in 85.001 seconds with a reported 1,024-token request ceiling in every case. Media turns now use the same trusted clock, prior conversation, allowed-memory, and summary context; tight budgets reserve both a bounded rolling capsule and the newest complete pair. |
| Rolling conversation compaction | Implemented; policy device tests passed, semantic breadth pending | After a completed turn, compaction triggers at ten pending messages or 1,536 UTF-8 source bytes. The model receives a head-and-tail bounded transcript plus the prior capsule and must return one complete 480-byte latest-state capsule covering goals, decisions/constraints, and unresolved references. Kotlin rejects over-limit, incomplete, Tool-producing, invented-literal, and unjustified prior-anchor-loss results; explicit corrections may replace only a repeated old anchor with a retained same-kind new anchor. The newest twelve messages and live recovery rows remain verbatim. User work preempts compaction; the owner's latest thermal policy allows it through SEVERE, cancels at CRITICAL or higher, fails closed at UNKNOWN, and rechecks that boundary before storage. The scoped device policy suite passed 23/23 on the same compaction code; broad semantic recall over arbitrary long conversations remains unqualified. |
| E4B thinking, answer completion, and rich response text | Implemented; bounded Fold8 UI/sustained acceptance passed | Thinking is enabled by default with `min(384, maxOutputTokens / 2)` reasoning tokens. Multi-constraint short-output requests retain the normal full turn budget so reasoning does not collapse the visible answer allowance. While the active latest Assistant entry is blank, the transcript shows a tappable `생각 중` disclosure; expansion renders the matching LiteRT thought-channel deltas verbatim as bounded, tail-following plain text. The Fold8 showed actual thought text streaming, then collapsed it without leaving the dark MainActivity; the sustained turn recorded 147 updates and cleared the field at completion. The instrumentation-only empty Activity now has a black test theme after it was identified as the white-screen source. Completed answers use a bounded local Markdown/math renderer; an actual Fold8 answer displayed bold headings and centred equations without raw `**`, dollar delimiters, or `\\text`. Thought and active answer streams stay plain, and unsupported or malformed markup falls back to literal text. The raw thought stream remains redacted and ephemeral: it is never copied into final answers, runtime history, Room, recovery capsules, summaries, diagnostics, or restored conversations. |
| Write terminal answers | Implemented; device/provider pending | Typed completed/refused write receipts produce app-authored terminal answers. Unknown side effects are never presented as success and remain owner-verification obligations. |
| Turn recovery | Implemented and host/scoped-AVD verified; process/device gates pending | The exact ordered list of up to four completed read Tool names is durable. Recovery copies and reserves that list exactly, re-runs fresh checks/Tools, and accepts order-independent parallel completion only after exact reservation. Schema 10 can point a contextual follow-up at the earlier USER request through a content-free source ordinal while keeping the current follow-up's unique outcome row. |
| Atomic transcript/outcome storage | Host and scoped-AVD verified | Assistant phase, Tool receipt, and typed Tool outcome commit in one Room transaction. An exact turn/ordinal/risk/outcome/Tool/receipt redelivery is a no-op; conflicting ordinal reuse fails closed. Final assistant phase and terminal outcome commit atomically and idempotently. |
| Unresolved side effects | Implemented; owner UX/device pending | Room schema 11 retains unresolved writes in a no-expiry table without transcript/turn foreign keys. Chat deletion and turn expiry cannot hide an uncertain write; only matching refusal or explicit owner verification clears it. |
| Conversation mutation concurrency | Implemented; lifecycle/device pending | `ConversationMutationGate` serializes restore, new/switch/delete/delete-all, recovery resolution, and turn start across UI/Room ownership. |
| Notification capture | Implemented; physical permission/lifecycle pending | Capture remains default-off and allowlisted. Disable closes a synchronous process gate before asynchronous persistence; capture, setting changes, and erasure share a mutex. A failed disable remains closed. The current turn scope now uses the registered `kakao_notification_search` name, does not add `web_search` merely because a Kakao-notification query says `찾아`, and requires an actual notification read before the turn can complete. |
| General owner consent | Implemented and host/AVD verified; live acceptance pending | Route, web/weather, memory, proposal, proactive-route, and daily-brief features combine durable state with a latest-request process gate. Every pending mutation closes the gate; only the latest successful durable enable opens it. Network gateways recheck before each provider hop. |
| Encrypted transfer | Implemented; end-to-end migration pending | Envelope v1 transfer schema 6/payload 2 preserves only canonical content-free USER attachment summaries; legacy schema 5/payload 1 imports them as null. Version/length/count/UTF-8/collection/ID/relationship/ordinal/summary checks run before transactional import. Credentials, ledger, notification rows, recovery/checkpoint state, unresolved writes, provider IDs, media payload, model, diagnostics, permission, and consent are absent. |
| Fold/DeX/accessibility/input | Implemented policy; physical matrix pending | Usable size and hinge validation prevent invalid Book/Tabletop/two-pane classification. Confirmation actions remain reachable, semantics are explicit, streaming work is bounded, and text drop merges with a newline without mutation on refusal. |
| Output budget and model evaluation | Sustained 1,024-ceiling Fold8 measurement accepted; model comparison pending | Explicit concise intent stays bounded even when generic explanation wording is present, subjectless Korean `찾아` requests normally use the structured budget, and an inherited detailed public-search request receives the full 1,024-token ceiling while Tool/step/deadline/argument caps remain. Predicted or observed heat through SEVERE does not shorten the request-derived ceiling; CRITICAL still cancels. A current-code sustained GPU turn completed in 97.449 seconds at SEVERE with the 1,024-token ceiling unchanged; this does not prove all available tokens were consumed. The fixed 26-case/23-category screen uses a fourteen-Tool subset of the shipped seventeen. It is an all-Tool stress screen, not a production accuracy estimate. Schema v3 strictly rejects duplicate JSON keys, non-finite values, hidden later/malformed calls, invalid Tool arguments, contradictory terminal states, mixed run/model/artifact audit rows, cleared review obligations, missing visible-language evidence, and incomplete Android binding. The focused Qwen3.5-9B host score and current E4B AVD write-selection failure qualify neither replacement nor same-condition comparison. No replacement model is qualified. |
| Thermal policy | Implemented; SEVERE full-ceiling Fold8 measurement passed | At the owner's request, predictive headroom remains measured but does not shorten or cancel app model work before CRITICAL. Each deterministic request class keeps its normal 128/256/384/1,024 ceiling and background summarization remains eligible through an observed SEVERE state. `ThermalTurnPolicy` retains the hard boundary: NONE through SEVERE may start, CRITICAL cooperatively cancels, and EMERGENCY or above aborts; UNKNOWN remains fail closed. The sustained Fold8 turn completed at SEVERE with no app-level reduction or cancellation: battery temperature changed from 34.2 to 35.5 C while plugged in, capacity remained 94%, PSS changed from 18,761,728 to 145,807,360 bytes, and app heap changed from 8,325,328 to 22,021,840 bytes. Android and firmware protections remain independent. |
| Memory and proactive proposals | Implemented bounded source; activation acceptance pending | Memory provenance is owner-visible. Opportunity detection is default-off and review-only; it cannot autonomously promote or execute. |
| Photo and voice input | Implemented; focused host/source checks passed; corrected physical gate pending | Default-off and gated by a declared manifest modality. One attachment per turn: an image bounded to a 768 px long edge and 1.5 MB, or 0.4–20 s of 16 kHz mono WAV. A media turn is given no Tool schema, runs with `maxSteps = 1`, and cannot combine with a recovery contract, a contextual search request, or a caller-supplied scope; deterministic read routing and follow-up carry-over are skipped. It does receive the trusted device clock and bounded prior conversation/memory/summary, builds that context before storing the current attachment row, pins completion to the captured conversation, and schedules ordinary compaction afterward. Photos are re-encoded to strip EXIF; camera capture uses one temporary private-cache file with best-effort cleanup, while audio stays in memory. Payloads never enter UI state, Room, transfer, or diagnostics; a canonical content-free USER summary does enter Room, transfer, later context, and summaries. The historical Fold8 session read a synthetic-card number and reached the audio front end, but its token comparison was invalid. The corrected fresh-runtime gate has not run, and owner-media quality, Korean transcription, production camera/picker flow, sustained latency/PSS/thermal, and fold/DeX remain open. |

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

## Room schema 11

The no-backup application database contains conversations/messages, memories, notification cache,
reminders/deliveries, proposals, turn outcomes, ordered read executions, AgentPlan checkpoints, and
unresolved side-effect obligations. Migration 8→9 adds the independent unresolved table and
preserves closed side-effect identities from earlier rows without inventing content. Migration
9→10 adds nullable `recovery_source_user_message_ordinal`; it stores no prompt or result text. A
contextual follow-up owns its own unique outcome row, while recovery resolves the preceding owner
question through that ordinal. Migration 10→11 adds nullable `messages.attachment_summary`, a
bounded app-written kind/source/whole-second code recording that a message carried a photo or a
voice clip; existing rows stay NULL and render as ordinary messages. Transfer schema 6/payload 2
preserves only canonical codes on USER rows; legacy schema 5/payload 1 imports the field as NULL.
Attachment-only USER rows contribute an app-authored content-free label to subsequent context and
long-term summarization. No media payload enters Room or transfer.

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
- Attached photos and voice clips are untrusted observation, never instruction. A media turn is
  given no Tool schema, so an instruction inside an attachment has nowhere to execute. Payloads are
  signature-checked and bounded before the JNI bridge and re-encoded to strip EXIF including GPS.
  Camera capture is temporarily staged in private cache with best-effort deletion; payload never
  enters UI state, Room, transfer, or diagnostics, while only its canonical content-free shape does.
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
read-only API 37 AVD runner selected 279 reviewed methods with 248 passes, 31 intended guards, and
zero failures on 2026-09-01. It excluded every owner-action and dedicated model/ABI class rather than weakening
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
