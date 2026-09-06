# Remote agent acceptance

This ledger separates shipped source, host contract tests, provider inference, emulator UI,
physical Fold8 behavior, and an observed 24-hour window. The local LiteRT model stays the default.
Do not enable Mac tools until the tool-free conversation gate has passed; Android writes remain
typed proposals behind Kotlin confirmation, durable idempotency, and execution-time interlocks.

## GLM routing differential, 2026-09-05

The configured SecretRef successfully authenticated to OpenRouter's GET `/api/v1/models/user`;
`z-ai/glm-5.3-flash` was visible. Public metadata reported 24 model endpoints and 20 ZDR endpoints.
All 24 advertised `max_tokens`; none advertised `max_completion_tokens`. Metadata alone was not
treated as proof that the unlisted field caused the error.

A bounded live comparison then used the same fixed Korean prompt, model, reasoning effort `low`,
2,048 output-token ceiling, streaming, strict parameters, data-collection denial, ZDR, latency
sorting, and prompt/completion unit-price ceilings of $1 per million tokens:

| Request | Observed UTC | Result |
| --- | --- | --- |
| `max_tokens: 2048` | 12:53:32.803 | HTTP 200; exact Korean marker, exact model, zero tools; 40 prompt + 158 completion tokens; API-reported cost $0.0000593 |
| Replace only that key with `max_completion_tokens: 2048` | 12:54:26.978 | HTTP 404 |
| Keep `max_tokens`, add `max_completion_tokens: null` | 12:54:53.692 | HTTP 404 |

This differential isolates the incompatible field in the tested request. A successful direct
provider call is not a Gateway or Fold8 acceptance receipt. Failed-response billing was not
independently reconciled. The owner subsequently authorized bounded test calls without repeated
per-call approval.

The pinned OpenClaw 2026.8.1 adapter defaults this route to `max_completion_tokens`. Catalog-owned
compatibility ignores same-route config overrides, and a null field also failed, so the repair is
a narrow local source patch for the exact OpenRouter GLM model. Its original module SHA-256 is
`d76487f151510c8d36cf650803c007b5f350e90171624e72d443ccd21fe2dea5`; the candidate SHA-256 is
`14330dfcfe8f435cd1455070ec160a83fecfd4e08ac198968ff1adc69daf94fd`. This is a locally patched
2026.8.1 runtime, not a byte-identical upstream distribution. Seven network-disabled compatibility
checks cover the original defect, corrected field, other models/routes, and explicit override.

The first application correctly rolled back because strict workspace verification found
`SOUL.md`, `USER.md`, and `IDENTITY.md` created by the earlier 12:17 UTC smoke. Automatic bootstrap
file creation and context injection require explicit disabling; the files must be quarantined
and preserved, not silently removed or exempted from verification.

## Observed integration receipts, 2026-09-05

- The final local patch transaction at 13:33 UTC passed full zero-tool/plugin/SecretRef verification
  and the first Docker-aware launchd watchdog exit 0. A prior rollback exposed a LaunchAgent PATH
  issue in Colima's child `limactl` lookup; the corrected child environment passed live verification.
- At 13:39 UTC, `smoke-model.sh` succeeded through the running Gateway: exact requested/effective
  GLM model and fixed reply, zero successful tools, no reroute, and zero run-owned SQLite rows after
  cleanup. This closes the Gateway 404 gate; physical inference is recorded separately below.
- `test lint assembleDebug assembleRelease` passed. Final AVD UI fixtures: 3 passes and 5 expected
  physical/opt-in skips; the separate production remote surface test passed once.
- Signed Fold8 update used `adb install -r` after certificate preflight. Before/after snapshots
  matched: 23 conversations, 104 messages, 0 existing memory entries, 57 notification rows, all three
  existing credential-presence flags, model size 3,659,530,240 bytes, and the exact settings digest.
  First install time remains 2026-08-23 18:10:37. The production remote surface test passed.
- The context-preview/optional-health revision then passed full host test/lint/debug/release
  assembly, 354 app JVM cases, 95 core OpenClaw cases, and its paired release test assembly.
  The scoped AVD set passed 3 UI cases plus 6 expected guards; production surface passed 1/1.
  It was installed on Fold8 with owner snapshots unchanged and pulled-back app hash
  `9520a912fc15e64c4dbd8adb1a7e033573d12ef5b5a153cff7836cb9a82017dd` matching the
  candidate, plus test APK hash `787153c26687e23ad55ee431a7510cffc3ea0d48b18d3745ee469d826a7afe86`.
  This verifies installation/surface, not actual context export or health RPC execution.
- Tailscale node and phone are online in one tailnet; the owner enabled account-level Serve.
  HTTPS certificate verification passed with ssl_verify_result=0. Managed ingress acceptance
  remains in progress: pinned OpenClaw owns one Foreground route to its own dynamic loopback
  backend, not port 18789. A failed transaction captured this exact ownership shape and restored
  the original config/routes. The validator is being bound to the actual Gateway PID/listener.
  The first attempt failed before mutation because the Service-only get/set-config CLI cannot
  preserve node-level Serve routes. The revised transaction snapshots raw status and restores via
  the pinned CLI's stdin-only `serve set-raw`, verifying the full original JSON after rollback.

The first encrypted physical bootstrap failed before authentication or provider submission.
The release mapping showed Kotlin ArraysKt helpers were renamed while the test invoked a helper
by its original name. The test now uses Java array wiping and fixed phase/type-only failure
metadata, and the matched APKs above contain that correction. The next live retry is pending.

Source, AVD, installed physical APK, active host extension and live inference are separate evidence
boundaries. The later Android proposal source is not included in these APK receipts.

## Latest physical gate and owner-selected stop

The corrected installed release passed one actual Fold8 request after encrypted bootstrap and
explicit device pairing. The response was55UTF-8 bytes, satisfied the fixed Korean capital/marker
claims, and ended normally. Submissions=1; local conversation/message/memory counts unchanged.
The test disconnected and returned to local mode. Broader quality/interruption tests were not run.
The owner asked to stop here to preserve usage. See `REMOTE_AGENT_RESUME.md`; no soak or scheduled
follow-up was started. The managed HTTPS and Standard watchdog deployment have also passed live
verification, superseding earlier failed-and-rolled-back attempts recorded above.

## 2026-09-06 source change since these receipts

At the owner's instruction the per-question external-disclosure dialog was removed and remote
turns are now stored in the ordinary conversation. Every receipt above was taken against the
earlier revision, where each question needed its own confirmation and the remote pane held its
text in process memory. Two things they assert are therefore no longer the current contract: a
send needs no second confirmation, and a method that sends adds its question and answer to the
owner's transcript. `Fold8OpenClawLiveAcceptanceTest` now asserts an exact expected row delta per
method instead of a blanket unchanged message count, and zero still means zero for connect,
pairing, background and recreation. This change is host verified only; it has no emulator or
Fold8 receipt yet.

**Update, same day:** it now has one. On the signed Fold8, after a same-certificate update whose
pulled-back hash matched (`45ee97fc...a548`) and preserved `firstInstallTime`, the provider-free
surface test passed 1/1 and the new opt-in
`Fold8OpenClawLiveAcceptanceTest#oneSendNeedsNoApprovalAndIsStoredInTheSharedConversation` passed
1/1 in 8.767 s. It reused the stored device credential, so it added no pairing, and spent one
bounded provider call after the 2,048-token cap was independently read back from the live Gateway
configuration. Observed: one send with no approval in the path, a 55-byte Korean answer meeting the
fixed claims, both storage flags true, the last two conversation rows read back from Room as the
typed question and the received answer, exactly two new message rows, zero new conversations, and
an unchanged memory count. A force-stop and fresh process then reported 106 messages against the
104 baseline. Thermal status 0, no crash/ANR/LMK exits, Gateway healthy on the same PID.

**Cancellation and its two neighbours, same day.** Three defects found while accepting cancellation
were fixed and re-accepted on the phone: the per-run timeout the Gateway applies is the one this
app sends, so 60 s was why a long answer produced nothing (now 180 s; the 40-item question
completes in 120.757 s); OpenClaw 2026.8.1 deliberately surfaces an externally aborted run as an
error, so a cancelled run read as failed until the client began treating a `FAILED` terminal as
`CANCELLED` when — and only when — the Gateway's own `chat.abort` acknowledgement named this exact
live run; and a cancel tapped before the run id existed sealed the socket instead of being held.
Device receipts: `cancelsOneStartedAnswerAndStoresOnlyWhatArrived` 1/1 in 6.991 s with a confirmed
cancellation 102 ms after the tap, `holdsACancelTappedBeforeTheRunIdExists` 1/1 in 0.842 s with the
connection retained, `completesALongAnswerWithinTheRaisedRunTimeout` 1/1 in 120.757 s, and the send
regression 1/1 in 10.781 s, all against app APK
`fa4776cacfade4d638f6f5af4d2036e3b8242a71db7138f990449a58cd1812e5`. `PROJECT_STATUS.md` holds the
reasoning, including the one residual limit of the abort rule.
`PROJECT_STATUS.md` holds the full receipt.

## Acceptance matrix

| Boundary | Required observation |
| --- | --- |
| Host unit contracts | Explicit enable/disable; no calls before consent; endpoint-bound tokens and identity; handshake ordering; cancellation and unknown outcome; no automatic replay |
| Emulator UI | Local/remote selector; connection consent; masked credential setup; send dispatching the exact visible question; answer/progress/cancel; dismissal at background |
| Signed Fold8 update | Matching release certificates and pulled-back APK hashes; same first-install time; unchanged owner model, credentials, settings fingerprint and aggregate data counts |
| Real pairing | Same tailnet HTTPS endpoint; system TLS verification; exact new device approved on Mac; device token survives reconnection; wrong/unpaired auth rejected |
| Real conversation | Fixed Korean question yields useful Korean response through the production UI/controller and exact Gateway model receipt with zero tools |
| Interruption | Cancel, network loss, background, and process restart report confirmed versus unknown outcomes accurately; no automatic paid replay |
| Runtime recovery | Dedicated Docker/Colima runtime restored from an intentionally stopped, idle state; no owner volume deletion; Gateway and watchdog healthy afterward |
| 24-hour observation | Schema-2 initial/periodic/final journal; Docker/Colima/daemon and Gateway continuity; HTTPS ingress; AC sleep=0, standby=0, autorestart=1; no missed observation interval |

FileVault still requires owner unlock after a cold boot. Sampled observations cannot exclude an
outage shorter than the sampling interval, and a single Mac without independently verified power
backup does not provide unconditional availability.

## Tool expansion order

1. Accept tool-free remote conversation on the real device.
2. Add narrowly named Mac read-only capabilities with bounded output, explicit data scope,
   deterministic authorization, and no shell or arbitrary-path interface. Start with operational
   health metadata; introduce workspace file reads only with an owner-selected directory.
3. Project Android operations into existing typed Tool contracts. Remote prose never invokes a
   platform API. Confirmation, durable idempotency, expiry/replay checks, and execution-time
   permission/lifecycle/thermal interlocks remain authoritative in Kotlin.
4. Add adversarial Korean intent/context and prompt-injection tests before broadening capability.
   Automatic model selection, Hermes, unrelated plugins, and unrestricted remote skills are
   separate decisions; this work does not silently adopt them.

Official references: [OpenRouter user-filtered models](https://openrouter.ai/docs/api/api-reference/models/list-models-filtered-by-user-provider-preferences-privacy-settings-and-guardrails),
[provider routing](https://openrouter.ai/docs/guides/routing/provider-selection),
[request parameters](https://openrouter.ai/docs/api/reference/parameters), and
[GLM model endpoints](https://openrouter.ai/api/v1/models/z-ai/glm-5.3-flash/endpoints).
