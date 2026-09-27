# Mac OpenClaw over Telegram

Updated 2026-09-27 KST. This is the active operating document for this repository.

The September 10 [Heartbeat recovery record](OPENCLAW_HEARTBEAT_RECOVERY_20260910.md) covers stale
Codex subscription blocking, the guarded reprobe repair, and retained synthetic-agent startup
references. Astra response and post-restart Heartbeat passed with existing conversations preserved.

The owner retired the Android app at `rc11-final` and selected Telegram as the remote interface
for the current Mac. Work includes coding, research, operations, media, and documents. The Android
source, installed app data, and historical acceptance receipts remain preserved. Its unfinished
release gates are closed unfinished, not prerequisites for this Mac assistant.

## OpenClaw 2026.9.6 upgrade, 2026-09-27 KST

The runtime is now OpenClaw 2026.9.6 (`eb377ac`) with the official Codex plugin 2026.9.6; the model
chain, owner routing and state were not changed. The reviewed local patch set changed: the GLM
token field, memory admission and owner Telegram delivery repairs were ported (delivery now spans
recovery, storage and the shared-state worker kernel); a new repair adds `Agent` to claude-cli's
default `--disallowedTools` so native background agents cannot strand a Telegram reply
([#158626](https://github.com/openclaw/openclaw/issues/158626)); GLM `/think max` moved to
`compat.supportedReasoningEfforts` configuration; and the Codex auth reprobe and GPT-6 Sol patches
were retired because 2026.9.6 ships both behaviours. The GPT-6 Sol section below is historical.

Verified on the live Gateway: patched bytes, live verifier, reinstalled observer, a synthetic
claude-cli Opus turn without the native `Agent` tool, a real browser tool call, one GLM max-thinking
response, Codex bootstrap projection and extractors. The owner's real Telegram message at 13:45 KST
hit the Claude session limit on Opus, fell back to Sol and was delivered with no error banner.
Update this installation under `umask 077`; the updater fingerprints the launcher mode. The
`tools.effective` inventory omits the browser tool although turns receive it. The owner's MEMORY.md
had been kept out of session bootstrap since a 2026-09-06 synthetic test stamped it `untrusted`; at
the owner's request that single provenance row was removed and startup injection re-verified.
Details, evidence and rollback:
[the 2026.9.6 upgrade record](OPENCLAW_UPDATE_20260927.md).

## Claude Opus 5.5 main-route migration, 2026-09-26 KST

At the owner's request the main agent's chain is now `anthropic/claude-opus-5-5` →
`openai/gpt-6-sol` → `openrouter/z-ai/glm-5.3-flash`, and the global `agents.defaults.model`
is Opus with Sol as its only fallback (no paid fallback). Opus carries alias `opus` and an
explicit `claude-cli` runtime, so it runs through this Mac's own Claude Code login and draws
from the Claude subscription (the account reports `pro`), not an Anthropic API key. Anthropic's
support article (updated 2026-06-16) states that `claude -p` and third-party app usage still
draw from subscription limits. Thinking stays `high`; the summary, PDF, sub-agent and Dreaming
internal routes stay on GPT-5.6 Sol. The bundled `anthropic` plugin was allowed with native
Claude session discovery disabled, so the owner's other Claude Code conversations are not listed.
No runtime file was patched: 2026.9.3 already treats `claude-opus-5-5` as an Opus 5 family model.

Claude Code comes from the Homebrew `claude-code@latest` cask (2.1.282) at
`/opt/homebrew/bin/claude`, the first `claude` on the Gateway service PATH. The stable
`claude-code` cask (2.1.274) was installed first and rejected by the API: Opus 5.5 requires
Claude Code 2.1.280 or newer. The owner signed in interactively with `claude auth login`; the
credential lives in the login keychain and OpenClaw holds no Anthropic credential. Claude Code
resolves that keychain item through the `USER` environment variable, which the Gateway has.
Homebrew upgrades change the binary; the verifier checks the cask location, not a version.

Verification. Live Gateway `models.list` reported all three chain models available and Opus
bound to `claude-cli`. One ordinary synthetic agent turn with no model override logged
`cli exec: provider=claude-cli model=claude-opus-5-5`, answered `OPUS55-READY` in 2.4 s with
no fallback decision, and its session was deleted; the native Claude transcript it created was
moved into the private operation directory. A raw `modelRun` smoke is not valid for this route:
2026.9.3 forces raw model runs onto the embedded runtime, where Opus failed as
`missing-provider-auth`. That same run did show the real chain order: Sol then failed with
Codex's 429 usage limit (allowance exhausted until 2026-09-30) and GLM answered. An offline
run of the installed fallback runner passed Opus → Sol → GLM for four Claude limit messages.
Two of them (`You've hit your … limit · resets …`) classify as `unknown`, not `rate_limit`.
They still fail over while a candidate remains, but without a cooldown, so each turn retries
Opus first until its limit resets. The source verifier, 16 policy tests and the reinstalled
observer's first and next scheduled checks passed. At 10:55 KST the owner sent a real Telegram
message: the existing conversation ran `claude-cli`/`claude-opus-5-5` (15.6 s, no fallback) and
Telegram accepted the reply (`messageId=426`); the session now records provider `claude-cli`.
The next heartbeat turn also ran on Opus. Actual Claude limit exhaustion was not exercised.

Known boundaries:
- A native Claude CLI login exposes no account identity to OpenClaw, so it refuses to replay
  earlier OpenClaw transcript into a fresh Claude session (`reason=auth-unknown`). The owner's
  existing conversation therefore starts its Claude session without the earlier Sol/GLM turns;
  workspace files and memory still load, and later Opus turns resume that Claude session.
- Claude Code keeps this agent's native session files under `~/.claude/projects/`, outside the
  OpenClaw backup. The OpenClaw transcript remains authoritative.
- Opus shares the owner's Claude subscription limits with Claude and Claude Code on any device.
  Heartbeat turns also use Opus first. Logs show a 30-minute cadence on both 2026.9.3 and
  2026.9.6 (corrected 2026-09-27; an earlier note here claimed one hour).

Private backups (configuration, workspace policy, memory note, observer bundle), the applied
patch, smoke scripts and receipts are in
`~/.openclaw-personaledge/operations/opus-default-20260926T014034Z/`. Rollback: restore
`openclaw.json.before` and `AGENTS.md.before`, restart the Gateway, and reinstall the observer
from the matching verifier revision.

## GPT-6 Sol main-route migration, 2026-09-23 KST

The installed global `agents.defaults.model` now selects `openai/gpt-6-sol` without a fallback.
The main agent's explicit `agents.entries.main.model` also selects `openai/gpt-6-sol`, retaining
`openrouter/z-ai/glm-5.3-flash` as its sole configured fallback. Default thinking remains `high`.
The separately configured GPT-5.6 Sol routes for summaries, PDF work, delegated work, and
Dreaming's internal completion were not changed. The scheduled Dreaming agent turn inherits the
new default Sol route when it has no model override.

A separate GPT-6 Sol compatibility patch is installed in four OpenClaw 2026.9.3 files: the
plugin manifest, thinking policy, model route contract, and ChatGPT/Codex resolver. The exact
source and installed hashes are pinned in `scripts/openclaw/runtime-patch-specs.json`; the private
installation receipt is `~/.openclaw-personaledge/operations/gpt6-sol-patch.json`. This is
distinct from the four earlier runtime repairs. The Codex runtime is 0.156.1 and uses the
existing ChatGPT OAuth route; no API-key billing route was added.

An isolated Gateway `modelRun` returned `SOL-READY` with requested and effective model
`openai/gpt-6-sol`, response model `gpt-6-sol`, `rerouted=false`, and zero successful tools.
Its synthetic session and run-owned state were cleaned up. This verifies that bounded model
path, not the owner's Telegram conversation or Telegram delivery. Actual quota exhaustion and
fallback under the new Sol route were not exercised. The historical Astra receipts below remain
evidence for their original dates and models. Comparative usage savings have not been measured.

## Main conversation GLM fallback, 2026-09-11

The owner authorized automatic fallback to the already configured OpenRouter GLM when Codex
allowance is exhausted. This supersedes the earlier no-GLM-fallback policy for the main agent,
not for the separately configured PDF, summary, delegated-model, or Hermes routes.
At that time, `agents.entries.main.model` explicitly selected `openai/gpt-6-astra` with
`openrouter/z-ai/glm-5.3-flash` as its sole fallback. Global defaults remained unchanged.
The then-current Telegram session's model override was cleared through `session_status(model=default)`;
conversation history was preserved and the Gateway required no restart.

The built-in fallback is turn-local and also covers eligible availability errors, not exclusively
quota exhaustion. Explicit `/model codex` selections remain strict; `/model default` returns to
the configured chain. GLM uses the existing OpenRouter billing/credential route. Recovery starts
from Codex on later turns subject to its cooldown. Mid-work failures remain subject to the
runtime's safe-continuation constraints; this is not a guarantee to replay partially executed work.

Verification: the live Gateway returned the new main-model config; a tool-free isolated GLM
provider call returned the expected marker and its synthetic session was deleted; the installed
fallback runner handled a synthetic `rate_limit` failure by choosing GLM. No real quota was
exhausted or changed. The only semantic config change was the main model object; auth and
specialized model settings were unchanged. Receipts and a private rollback copy are in
`~/.openclaw-personaledge/workspace/codex-glm-jtcq420y/`.

## Fallback observer false alarm repaired, 2026-09-11

The 22:59 KST owner alert (Telegram message 230) followed three five-minute verifier
failures after the authorized main-agent GLM fallback was installed. The verifier's
owner-routing check still allowed only empty agent entries, so it rejected the newly
approved main model object with `agent routing overrides require separate review`.
This was a monitoring-policy mismatch, not evidence that Gateway or Telegram was down.

The repaired routing check then accepted exactly the approved Astra-primary/GLM-only-fallback main
entry as well as the prior inherited-default forms. Alternate models, extra fallback
providers, extra agents, per-agent tool overrides and bindings remained rejected. Global,
PDF, delegated and credential policies were unchanged by that repair. Thirteen gateway policy tests and
45 observer tests passed; the repaired source verifier passed live Gateway/Telegram checks.
Private pre-edit backups: `~/.openclaw-personaledge/operations/fallback-observer-fix-izjay679/`.
Installation/live receipts remain in `operations/telegram-observer-install-latest.json`
and `operations/telegram-watchdog-status.json`. Policy changes must be checked against
the installed observer, not only config syntax and model response probes.

## Operating model

The [Hermes operations controller](OPENCLAW_HERMES_OPERATIONS.md) extends this integration to
deployment diagnosis, update-impact review and tested operational improvement candidates through
`/skill hermes-operations`. Weekly review is scheduled through this Codex task for Saturday 10:00
Asia/Seoul; installation, actual execution and first scheduled-run evidence are tracked separately
in that guide. The existing five-minute observer continues without model calls.

The optional [Hermes operations-report worker](OPENCLAW_HERMES_PILOT.md) handles one explicitly
requested event-JSON report through `/skill hermes-operations-report`. It has a separate ChatGPT
OAuth profile, Sol/high model route and procedure skill. OpenClaw retains conversation ownership,
the current Sol/high default, and final Telegram delivery. Native Hermes learning/reuse and a real main
agent-to-worker report call passed; credentials and long-term memories are not synchronized.

- Profile: `personaledge`; state: `~/.openclaw-personaledge`.
- CLI: `~/.local/openclaw-2026.8.1/.personal-edge-management/bin/openclaw`.
- Runtime: OpenClaw **2026.9.6** and Homebrew Node 26.5.0. The official stable updater upgraded
  2026.8.1 in place on 2026-09-06, to 2026.9.3 on 2026-09-09 and to 2026.9.6 on 2026-09-27; the
  historical installation directory name did not change. The official Codex plugin is 2026.9.6.
  Four reviewed repairs (GLM token field, memory admission, Telegram delivery, claude-cli `Agent`
  denial) are pinned in `runtime-patch-specs.json` and installed. This is not a claim of byte
  identity with the upstream package.
- Configured conversation model: `anthropic/claude-opus-5-5` via `claude-cli`, thinking `high`,
  then `openai/gpt-6-sol`, then GLM for the main agent. `openai/gpt-5.6-sol` remains a separate
  specialist route.
  `modelSelectionScope=session` keeps unqualified `/model` changes in the current conversation.
- Telegram bot: `@ForEverything_Gogh_bot`; owner identity is in private configuration, not this repo.
- Gateway: loopback port 18789, token authentication, Tailscale off.
- Credentials: existing write-only store references, including `TELEGRAM_BOT_TOKEN`. Never copy
  token values into scripts, chat, Git, shell history, or reports.

Summarize, local Whisper, Word/DOCX and Excel/XLSX now have installed execution engines and live
acceptance on this Mac. Use `openclaw-office` for Korean headless Office conversion and
`openclaw-extract` for local Office text extraction. See [OPENCLAW_PRODUCTIVITY.md](OPENCLAW_PRODUCTIVITY.md)
for versions, commands and bounded test evidence. GitHub was excluded from this installation.

Codex, Web Readability and Document Extract are also installed and execution-verified. Codex uses
the account's ChatGPT OAuth login, without API-key backup. The September 11 main-agent GLM
fallback exception is documented above. The 2026-09-08 policy then set general chat to GPT-6
Astra/high; Summarize used GPT-5.6 Sol/low, and PDF, delegated work and Dreaming's internal
completion used GPT-5.6 Sol. Its scheduled Dreaming agent turn then inherited Astra/high. The
2026-09-08 checks below verified default Astra execution,
Sol summaries, isolated Dreaming-core execution and Telegram delivery of the updated guidance;
the earlier execution receipts keep their original model scope.
See [OPENCLAW_PLUGINS.md](OPENCLAW_PLUGINS.md) for usage, authentication policy and evidence.

The Telegram owner allowlist authenticates incoming commands. The bot token authenticates the bot
to Telegram; it is not the only access boundary. Local Gateway credentials and the macOS account
also carry authority. `requireMention` controls when a group turn activates; sender authorization
must be checked separately.

## Runtime upgrade and additional workflows, 2026-09-09 KST

The owner authorized the four local patch compatibility updates before upgrading the runtime,
then requested individual task progress, recording-to-minutes/subtitles, and a weekly AI/LLM
change briefing. Current commands and verification are recorded in
[the upgrade guide](OPENCLAW_UPDATE_20260909.md), [task progress](OPENCLAW_TASK_STATUS.md),
[meeting processing](OPENCLAW_MEETINGS.md), and [public-source changes](OPENCLAW_BRIEFING.md).
The weekly briefing is requested for Saturday 09:00 Asia/Seoul, with source citations and
importance filtering; unchanged sources and initial baselines do not produce an old-news digest.

The 2026.9.3 release changes bundled module paths and moves GLM token compatibility into a
separate AI module. `runtime-patch-specs.json` pins the reviewed original and patched bytes for
both qualified releases. `qualify-runtime-patches.py` runs actual request-builder, memory admission
and durable retry checks without inference, then can activate all four repairs while the Gateway
is stopped. The live verifier compares installed bytes to this source manifest, independently of
runtime receipts. These local repairs do not assert byte identity with upstream.

## Operations improvements, 2026-09-09 KST

The owner authorized the four reviewed improvements: observer repair, aggregate status and
failure/recovery alerts, current-deployment backup/restore, and document/media transport acceptance.
See [the Korean operating guide](OPENCLAW_OPERATIONS_KO.md) for commands and evidence boundaries.

- The installed observer's old policy template was the cause of 371 recorded policy-mismatch
  failures. The repo/live policy had already included the image wait guidance. Synchronizing the
  observer template restored the installed check and its next scheduled execution to exit 0.
- The observer now installs as a validated bundle with a private backup and rollback. It records
  its first installed check before enabling the schedule. `telegram-ops-status.py` reads current
  aggregate queue/task/cron/backup data; an absent or older-than-15-minute observer snapshot is
  explicitly unverified. Gateway health and operational issues remain distinct.
- No-inference checks run every five minutes. An issue persisting for three samples produces one
  owner notice; a new persistent issue during an existing incident can escalate once. Recovery
  follows a successfully delivered incident notice. A clearly labeled live notification test was
  accepted by Telegram (`messageId=133`); failure/flapping/deduplication paths used isolated fixtures.
- The Telegram-specific backup uses the official online SQLite archive, preserves recovery files,
  and restores to a fresh private directory before publishing `VERIFIED`. The September 9 archive
  validated 37,947 files and 15 SQLite databases. Restoring does not activate another Gateway.
- Native synthetic DOCX/XLSX/PDF/audio processing and all four rendered pages passed. Telegram
  accepted seven attachments (`messageId=126` through `132`). Reviewed files are staged under the
  existing media root; SRT is delivered inside a validated ZIP because direct SRT is rejected by
  this runtime's file-type check. Owner upload and phone opening were not impersonated or inferred.

Private change and transport receipts are under
`~/.openclaw-personaledge/operations/ops-improvements-20260909T124038Z/`.
The active observer/backup receipts are `operations/telegram-observer-install-latest.json`,
`operations/telegram-watchdog-status.json`, and `operations/telegram-backup-latest.json`.
Seventy-two focused offline tests passed. Configuration bytes, the Gateway process and the four
qualified runtime patches were preserved; no runtime upgrade was applied in these four fixes.

## Codex default acceptance, 2026-09-08 KST

Private backups and receipts are in
`~/.openclaw-personaledge/operations/codex-default-20260908T042837Z/`.

- After the Gateway restart, startup reported `openai/gpt-6-astra (thinking=high, fast=off)`.
  The existing owner's session ID was preserved; old model/provider overrides were removed and
  its thinking remained `high`.
- Synthetic `/model sol`, `/model codex`, `/think max` and `/think high` commands updated their
  session and left the global defaults unchanged. The native model cache advertises `low`,
  `medium`, `high`, `xhigh`, `max` and `ultra` for both Codex models. This is a capability listing;
  every effort level was not individually exercised.
- A fresh session with no model override used Astra for requested, effective and response models,
  with no reroute. Its successful tools were `write` and `read`; the synthetic file's exact contents
  passed verification. The temporary session was deleted after completion.
- The installed `summarize --force-summary` CLI completed on Sol/low and preserved confirmed and
  pending decisions in a Korean summary. The same isolated execution core used by Dreaming's
  internal completion returned the expected marker on Sol with the Codex runtime. Read-only
  inspection found no model override or GLM reference in the three scheduled jobs. Dreaming's next
  schedule is 2026-09-09 03:00 KST; its outer agent turn inherits Astra/high. The nightly scheduler
  and a complete cycle over the owner's memory were not run in this acceptance.
- Seventeen offline tests passed. The live verifier and installed observer passed, with observer
  last exit code 0. Telegram accepted the updated guidance message (`messageId=75`). This is a
  guidance delivery receipt; the model/tool checks above used synthetic sessions.

## Image generation wait correction, 2026-09-08 KST

**2026-09-10 recurrence:** Codex retained an older startup AGENTS snapshot despite refreshed
bootstrap reads. The repair now uses the per-turn SOUL instruction carrier, explicit message-tool
progress/attachment delivery, and observer drift checks. See
[photo response recovery](OPENCLAW_PHOTO_RESPONSE_RECOVERY_20260910.md) for verified scope and receipts.

The owner's Seoul night image request displayed `Yield failed` because the model called
`sessions_yield` after `image_generate` returned `async: true, status: started`. Image generation
is an independent background task; `sessions_yield` accepts announcing child-agent completions
owned by the current turn. Its rejection did not cancel the image task.

The original image completed successfully at 14:50:12 KST. Telegram accepted `sendPhoto` at
14:50:35 (`messageId=90`), the completion run ended normally, and the owner session was `done`.
The local 1024×1536 PNG passed chunk CRC and complete-file checks (3,853,961 bytes). These receipts
prove generation, server-side completion and Telegram API acceptance; they cannot verify the
phone's media download or playback indicator.

The live workspace policy and `templates/TELEGRAM_AGENTS.md` now explicitly require normal turn
completion after image-task acceptance, prohibit using `sessions_yield` or repeated generation to
wait for that image, and retain the separate attachment delivery on the completion event. Existing
subagent wait ownership checks remain intact. Private configuration and policy backups are in
`operations/image-wait-guidance-20260908T060705Z/`; configuration was unchanged. The runtime refreshes
workspace bootstrap files each turn, so this guidance change required no restart or session reset.
All 11 deployment policy tests and the live Telegram verifier passed after application. No new
image-generation request or Telegram test message was sent; recurrence prevention is an instruction
correction, not a newly completed end-to-end generation acceptance.

## Transition findings

The initial Telegram setup paired the owner and polled correctly but was not a working Mac agent:

1. `tools.allow` intersected the coding profile with unrelated groups, removing `exec`, file,
   and memory tools. Use `tools.alsoAllow` to extend the coding profile.
2. The live workspace `AGENTS.md` still prohibited shell, files, browser, memory, and actions;
   the first real Telegram answer repeated those prohibitions. The reviewed replacement is
   `scripts/openclaw/templates/TELEGRAM_AGENTS.md`.
3. `sandbox.mode=non-main` isolated the Telegram direct session. Host file tools and Mac execution
   need an explicit, owner-approved execution policy.
4. The old 5-minute watchdog repeatedly failed `restrictive OpenClaw config drifted`; earlier
   Tailscale checks also failed after Tailscale removal. Its zero-tool/Colima/Android assumptions
   belong to the archived deployment.
5. `plugins.slots.memory=none` left memory tools without a backend.

## Target policy and verification

The owner selected command execution without per-command approvals and separately authorized
Docker isolation removal and direct Mac execution. The applied configuration
uses an explicit owner-only DM allowlist, the same group sender allowlist, mention-required groups,
`tools.exec.host=gateway`, canonical `tools.exec.mode=full`, and synchronized host approvals.
The effective Gateway policy is `security=full`, `ask=off`; `sandbox.mode=off` and file access is
not restricted to the workspace. `computer` remains excluded and elevated execution remains disabled.

Workspace instructions make Korean the default for answers, progress, and error explanations.
`reasoningDefault=off` and the existing owner session's reasoning display is off, so internal
reasoning is not sent as the visible answer. Thinking effort remains `high`.

The memory backend is bundled `memory-core`, with `memory.search.provider=none`, local FTS keyword
search, trigram tokenization for Korean, Markdown-only sources, and no cross-conversation transcript
indexing. No embedding API or model download is needed. The owner approved bounded automatic
capture: confirmed project decisions, persistent preferences, and verified findings may be saved
without a separate remember command. Explicit save, correction, and forget requests take priority.
Questions, speculation, transient chat, secrets, and raw media are excluded; rejected details must
not be retained even in a "not saved" list. Sensitive personal facts need an explicit save request.
This setup does not import other assistants' memory.

The editable source of truth is under `~/.openclaw-personaledge/workspace/`:

| File | Purpose |
| --- | --- |
| `USER.md` | Stable preferences, when there are confirmed preferences to save |
| `MEMORY.md` | Compact durable project decisions |
| `memory/YYYY-MM-DD.md` | Selected working notes, dated in Asia/Seoul |
| `MEMORY_CONTROL.md` | Content-minimal correction/deletion keys; latest owner instructions win |
| `DREAMS.md` | Native Dream Diary, if the nightly cycle produces one |

The agent reads the control file before using or changing memory, removes obsolete source values
on correction/deletion, and rebuilds/searches the local index afterward. The owner can ask to show,
edit, or forget an entry, or edit these Markdown files directly. After manual edits run
`openclaw memory index --agent main --force` through this deployment's wrapper. Memory deletion does
not erase original Telegram messages, conversation storage, or external backups. Automatic selection
and natural-language edits remain model behavior, not an infallible data-loss prevention engine.

`automatic-memory.patch.json` enables pre-compaction memory flush and restores the `Memory policy`
section after compaction. Native Dreaming is configured daily at **03:00 Asia/Seoul** with no
Telegram delivery. The scheduled agent turn inherits the default Astra/high; the internal
memory-core Dreaming completion explicitly uses `openai/gpt-5.6-sol` through Codex.
Light/REM inspect seven days of selected daily notes. Deep consolidation is
bounded to five entries, score at least 0.8, three recalls and three distinct queries, age at most
90 days, and 160 estimated tokens per promoted snippet. These promotion thresholds do not prevent
an explicit owner save or the agent's direct capture of a confirmed decision. The configured nightly
route uses the ChatGPT account's Codex allowance with no paid fallback; the local search index itself
is keyword-only. Completion of a nightly cycle on the new model needs its own runtime receipt.

All raw conversation types are excluded from Dreaming ingestion. OpenClaw 2026.9.2 otherwise admits
interactive sessions whose chat-type metadata is missing, even when every named type is excluded.
`patch-memory-admission.mjs` closes that gap only when all three types are excluded. Twenty offline
checks exercise missing/known metadata, partial-policy compatibility, and forgotten-session behavior.
The version/source-hash-pinned receipt is `operations/memory-admission-patch.json`. The original is
retained in the private automatic-memory backup. This does not change normal message handling.

```bash
python3 scripts/openclaw/test-telegram-gateway.py
scripts/openclaw/verify-gateway.sh --telegram --skip-live
scripts/openclaw/verify-gateway.sh --telegram --json
```

The new verifier checks owner-only ingress, canonical host policy, private configuration and
reviewed workspace instructions, required live plugins, positive `exec`/file/browser/memory tool
inventory for the existing owner session, Telegram polling readiness, and loopback listeners.
It also checks the automatic-memory configuration, qualified runtime version, and hashes of all
four local runtime fixes (delivery recovery, GLM token compatibility, GLM max thinking, and memory admission), plus the
Codex OAuth-only policy, the exact Opus → Sol → GLM main chain and Opus → Sol default at high,
session-scoped model selection, the Codex and Opus aliases and native runtime bindings, the
absence of any OpenClaw-held Anthropic credential, the Homebrew Claude Code executable first on
the Gateway PATH, PDF tool and both lazy extraction providers.
It creates no sessions, sends no messages, and makes no model calls. Live checks require access
to the local RPC socket; a sandbox denial is not a Gateway failure.

`install-telegram-watchdog.py --apply` installs the operations observer as
`com.personaledge.openclaw-telegram-watchdog` every five minutes. It disables the obsolete relay
watchdog while preserving its files. The strict verifier remains read-only; the surrounding
observer sends only the owner-authorized persistent-failure, escalation and recovery notices.
It does not send routine healthy updates or restart the Gateway. Logs are bounded and private.
Back up and synchronize reviewed workspace policy, then re-run the installer after editing the
verifier, observer, status reader or policy template; inspect the first and next scheduled receipts.

The legacy `verify-gateway.sh` behavior is retained behind its original invocation for historical
fixtures and the retired relay. Always pass `--telegram` for this deployment. Do not reapply old
`harden-existing.sh`, `adopt-existing.sh`, `update-watchdog.sh`, or a tool-free backup to the live
Telegram agent without a deliberate rollback plan.

## Telegram delivery recovery

The unanswered photo and follow-up messages were not an intentional silent mode. Local transcripts
contained generated Korean answers, while Telegram `sendMessage` failed with a network error.
The native queue classified the uncertain send as `unknown_after_send` and refused blind replay.
The upstream failure mode also remains in 2026.9.2; see the
[upstream issue](https://github.com/OpenClaw/OpenClaw/issues/125764) and
[delivery lifecycle](https://docs.openclaw.ai/concepts/message-lifecycle-refactor).

The owner requested eventual delivery, so the private
`operations/telegram-delivery-policy.json` opts the owner's default-account DM into at-least-once
recovery of transient send/network failures. `telegram-delivery-retry.mjs` and
`patch-telegram-delivery.py` extend the existing durable SQLite queue and recovery worker.
The queue retries without rerunning the model or repeating its Mac commands. A restart resumes
from the saved queue. Native delays are 5 seconds, 25 seconds, 2 minutes, then 10 minutes, with an
owner recovery limit of seven days / 1,008 attempts. Permission, authentication, invalid-payload,
and receipt-persistence failures retain the upstream terminal handling. Other recipients, groups,
accounts, and threads do not receive this override.

A connection can fail after Telegram accepts a send but before the receipt returns. That case can
produce a duplicate message or chunk when retried; Telegram provides no idempotency key for these
sends. This setup favors eventual delivery and does not promise exactly-once delivery. It requires
the Mac and Gateway to resume running. It does not recover requests that never reached the bot,
model-generation failures, or old messages already removed from the durable queue.

To prepare and test against an unpatched, qualified runtime (these commands do not install):

```bash
python3 scripts/openclaw/patch-telegram-delivery.py --package "$OPENCLAW_PACKAGE" --output "$CANDIDATE"
/opt/homebrew/opt/node/bin/node scripts/openclaw/test-telegram-delivery-retry.mjs "$OPENCLAW_PACKAGE" "$CANDIDATE"
```

The tests exercise recipient/error/age boundaries, durable SQLite state, stale attempt rejection,
concurrent claim exclusion, persisted backoff, recovery in a fresh OS process, and acknowledgement
that prevents another replay. The private installed receipt is `operations/telegram-delivery-patch.json`.

## GLM compatibility and upgrades

### GLM max thinking

OpenRouter's [2026-09-07 model metadata](https://openrouter.ai/api/v1/models) advertises `low`, `high`, and `max` reasoning efforts for
`z-ai/glm-5.3-flash`. OpenClaw 2026.9.2 omitted `max` from this model's command profile, and its
generic AI adapter clamped a requested `max` down to `high` before creating the request.
`patch-glm-thinking.mjs` prepares a source-hash-pinned repair for the exact model's OpenRouter
policy and stream wrapper. It adds `max` to `/think` and restores `reasoning.effort="max"` after
generic request construction, only for the verified OpenRouter completions route. The existing
levels and default selection remain compatible with current callers, including Summarize's
explicit `off` request; this is not a claim that GLM supports disabling its mandatory reasoning.
Conflicting reasoning budgets are removed when explicit max effort is selected, while visibility
and provider routing options are preserved. Other models and endpoints retain their prior behavior.

The configuration and owner conversation remain at `high`; this repair makes `/think max`
available without selecting it for the owner. `/reasoning off` still controls display separately.
The private installation receipt is `operations/glm-thinking-patch.json`; its `backupDirectory`
contains the original runtime files, live configuration, consistent SQLite backup, and prior
observer files. Roll back the two named runtime files from that backup and remove the thinking
receipt only as a coordinated rollback with the matching observer version. Preserve current
configuration and conversation data; their backup copies are recovery material, not rollback inputs.

```bash
OPENCLAW_PACKAGE="$HOME/.local/openclaw-2026.8.1/lib/node_modules/openclaw"
node scripts/openclaw/patch-glm-thinking.mjs "$OPENCLAW_PACKAGE" /private/tmp/glm-thinking-candidate
node scripts/openclaw/test-glm-thinking.mjs "$OPENCLAW_PACKAGE"
python3 scripts/openclaw/test-telegram-gateway.py
```

Candidate preparation passed 33 offline checks against the original runtime with network blocked.
The deployment policy suite passes 11 tests, including receipt tampering and path boundaries.
The installed code passed the same request checks. After restart, two fresh synthetic sessions
accepted `/think max` and persisted it; both subsequent tool-free GLM runs inherited that setting
and returned the exact `GLM-MAX-OK` marker, with no model rerouting or successful tool calls.
The request payload assertion (`reasoning.effort=max`) is offline evidence. The live terminal
receipts prove the GLM route and successful responses; they do not contain an effort field.
Gateway forces `modelRun` into internal session effects and deletes its trajectory on completion.
The initial test harness incorrectly expected a retained trace, failed that assertion after both
successful responses, and was corrected to validate their cached terminal receipts without more
model calls. Keep these evidence boundaries separate.

`smoke-glm-thinking.py --receipt PRIVATE_FRESH_PATH` repeats the command/storage/provider check
with one synthetic model call and exact-key cleanup. Both acceptance sessions and internal run
targets were deleted; no test message was delivered to Telegram. The private acceptance receipt is
`operations/glm-thinking-live-20260907.json`. Live gateway verification and the refreshed scheduled
observer passed; the owner configuration digest and `high` / reasoning-display `off` were preserved.

During deployment, an idle check raced with newly started owner work. Installation initially
stopped, but an incorrectly sequenced restart command still ran. Gateway recovery resumed that work;
its final success and Telegram delivery were confirmed at 01:42 KST before the planned patch restart
ran with three consecutive idle observations. No owner session or configuration was rolled back.

### Output token field

OpenClaw's OpenAI-compatible transport defaults to `max_completion_tokens` on OpenRouter, while
the GLM 5.3 Flash endpoints advertise `max_tokens`. With required-parameter routing enabled this
causes a 404 "No endpoints found that can handle the requested parameters", surfaced misleadingly
as a missing model. The 2026.9.2 catalog owns compatibility for its standard routes and ignores
the model's configured `compat.maxTokensField`; restarting does not change that behavior.

`patch-glm-token-field.mjs` prepares a source-hash-pinned correction for only
`openrouter/z-ai/glm-5.3-flash`. Seven checks verify the corrected default, explicit override
precedence, and unchanged other model/provider behavior. It makes no inference calls during
preparation. The installed receipt is `operations/glm-token-field-patch.json`. Provider privacy
routing (`data_collection=deny`, `zdr=true`, required parameters) is preserved.

Before another upgrade, back up the private configuration, SQLite databases, service definitions,
and installed package. Run the official updater, then requalify all four patches against that exact
release and complete a model/tool smoke test before declaring the bot ready. The verifier deliberately
fails on an unqualified version or overwritten patch. Do not apply the retired Android repair script.

This transition's private backups are under `~/.openclaw-personaledge/operations/`:
`telegram-transition-20260906T130616Z` and `upgrade-2026.9.2-20260906T131746Z`.
They contain sensitive operational state and must not be committed. The two old revoked bot-token
strings in shell history were redacted without printing them or changing other history entries.

## Evidence ledger

- Before transition: owner pairing stored; Telegram polling/connected/ready; 4 live plugins.
- Actual first Telegram answer: text/proposals only, matching the obsolete workspace policy.
- Initial owner-session effective inventory: only 4 core tools; `exec` absent, browser filtered.
- Official update: 2026.8.1 → 2026.9.2, updater exit 0, plugin synchronization without errors.
- Applied configuration: schema/resolvability checks and owner-boundary/effective-tool regressions pass.
- Local memory: synthetic Korean keyword indexing and retrieval passed; fixture removed and index rebuilt.
- Recovery: deterministic network failure and fresh-process recovery tests passed. A synthetic failed
  entry in the live Gateway queue was automatically sent to the owner at 22:29:08 KST and acknowledged
  (entry `c9574550-db39-4a3a-a40b-34d97fc98825`). The actual Telegram send is verified; the failure was
  simulated, not a deliberate outage of the owner's Mac network.
- Model/tool smoke after compatibility repair: terminal `status=ok`; GLM actually called `exec`
  (`uname -s` returned Darwin), `write`, `read`, and `memory_search`. The test file's bytes were
  checked locally and the final visible answer was Korean. Private receipt:
  `operations/host-smoke-32be2b990884471d978fb55c0dd10048/summary.json`.
  The exact temporary session and synthetic memory were removed after terminal completion.
- Existing owner Telegram conversation: a final recovery notice completed with `status=ok`,
  `terminalDelivery.status=sent`, `resultCount=1`, and a Korean visible answer without a language
  override in the prompt. Private receipt: `operations/owner-recovery-check-8309c5dce78f401fa523dbf0ef3031a3/`.
  This verifies the existing conversation's model-to-Telegram output path; inbound polling was
  observed separately, and no owner account was impersonated to manufacture a Telegram update.
- Before the Codex/extractor installation, the live observer verified 38 effective tools, five
  required plugins, Telegram polling/ready, loopback-only
  listener, full/off host approvals, three patch hashes verified; LaunchAgent last exit code 0.
- Final read-only doctor: 30 checks run, one warning about a missing public URL for optional
  remote node onboarding because the Gateway intentionally binds loopback. No error findings.
  Its aggregate `ok=false` includes that warning; do not call this an all-clear doctor result.
  Telegram does not need the optional public node onboarding endpoint.
- Historical deployment script regressions passed. No Android/device code or data was changed.

### Automatic memory acceptance, 2026-09-06

- The current production `thinking=high` configuration saved a synthetic confirmed project rule
  without a remember command, while excluding a same-turn undecided lunch choice and unknown server
  cause. Actual Markdown was checked, not just the assistant's claim. Capture completed in about
  171 seconds, so low latency is not established. Receipt:
  `operations/auto-memory-acceptance-cf6c17364a/capture-check.json`.
- An explicit forget request with the same high configuration removed that rule, rebuilt/searched
  the index, recorded a content-minimal `forgotten` topic key, and preserved a different fixture's
  rule. The agent normalized its ASCII topic key to lower case; the verification matches the key
  case-insensitively. Receipt: the same directory's `forget-check.json`.
- A separate synthetic correction changed the stored title, and a fresh session retrieved the new
  title. That earlier run retained a shortened old title as historical prose. The final policy now
  explicitly forbids that changelog pattern; do not claim every natural-language correction is
  infallible. Receipts: `operations/auto-memory-acceptance-8b13122ae6/`.
- Reduced-effort `thinking=off` capture tests incorrectly retained rejected candidates in a
  "not saved" note, despite the instruction. Those tests did **not** pass the exclusion gate.
  The accepted production high test is narrower than a guarantee for all prompts or effort levels.
  Initial incognito fixtures correctly refused memory capture and are not ordinary capture tests.
- Native admission evaluation reported zero eligible raw transcripts, including the temporary
  operator session with missing chat-type metadata. The admission patch passed 20 offline checks;
  the deployment/access-policy suite passed five tests with configuration mutation cases.
- A manual execution of the managed 03:00 Dreaming job completed `succeeded` in 578 ms, with zero
  ranked/promoted candidates, zero failures/degraded phases, and no chat delivery. This verifies the
  scheduler and no-promotion path, not a model-generated consolidation of qualifying material.
  Job ID: `74107a29-c055-4f30-b2a1-1629aca6a79f`. Its next scheduled run was 2026-09-07 03:00 KST.
  The synthetic-only payload and raw-session exclusion were verified before the authorized test.
- All six fixture sessions plus the one separate recall session were removed after terminal
  completion. Synthetic source notes and control rows were removed, the local index was rebuilt,
  and a `MEMTEST` search returned no results. Private cleanup receipts:
  `operations/auto-memory-cleanup-20260906/`. Private test receipts are retained outside the active
  memory sources; deleting test sessions is not a claim of secure erasure of SQLite free pages.
- Final live verification passed after cleanup: 38 tools, five required plugins, Gateway ready,
  Telegram polling, private memory-file permissions, and watcher last exit code 0.

Skill recommendations, exact publishers/versions, adoption counts, and review boundaries are in
[OPENCLAW_SKILL_RECOMMENDATIONS.md](OPENCLAW_SKILL_RECOMMENDATIONS.md). No new community skill or
supporting executable was installed in this memory-policy change.

Configuration, plugin loading, and a successful health response do not prove model tool use,
research-provider availability, document/media quality, Telegram file delivery, or 24-hour uptime.


## Terminal Telegram reply closure, 2026-09-23

A turn containing only progress sends (`final=false`) reached automatic finalization;
the finalizer was then blocked by a provenance check, producing the generic English
missing-summary notice. The source and active SOUL response contract now require the
last completed reply (including acknowledgement after an accepted scheduled handoff)
to use `final=true`, with bookkeeping performed before it and no tools afterward.
No provenance guard or missing-reply detector was disabled; runtime code was not changed.

Verification: 10 installed-context projection checks and 14 gateway policy tests passed.
The first model check failed before restart (RuntimeError); subsequent checks exposed an
ambiguous handoff scenario. The clarified scenario specifies an already accepted schedule
and no remaining current-turn work. Final isolated Astra verification passed all 10 fields,
with no tools, rerouting, image generation or Telegram delivery; its synthetic session was
removed. Receipt: `operations/response-contract-smoke-pvc96cvk/receipt.json` in the private
OpenClaw state directory. Operational installation passed static/live Gateway verification;
the unrelated `backup-stale` warning remains. Live Telegram delivery is verified separately
by the final message receipt, not by this synthetic test.
