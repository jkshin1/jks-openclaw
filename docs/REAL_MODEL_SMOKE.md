# Real-model smoke receipt

Date: 2026-08-21 (Asia/Seoul)

This receipt records the first end-to-end run of the pinned model. It proves the Android
and LiteRT-LM vertical slice on an API 37 ARM64 emulator; it is not a Galaxy Z Fold8
performance or release qualification.

## Fixed inputs

| Item | Value |
|---|---|
| Repository | `litert-community/gemma-4-E4B-it-litert-lm` |
| Revision | `2eee7ac325f20eb8c9ac1d0e972f7c84663062da` |
| File | `gemma-4-E4B-it.litertlm` |
| Size | `3,659,530,240` bytes |
| SHA-256 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |
| Runtime | LiteRT-LM `0.16.1`, CPU backend |
| Context/output budget | `4,096` total tokens / `1,024` output tokens |

The host copy passed `scripts/verify-model.sh`. The Android system document picker then
imported it into the app's `noBackupFilesDir` model store, where the same size and digest
were verified before an opaque model handle could reach the runtime. The model is not in
the APK. After the final run, only the emulator's temporary `/sdcard/Download` source copy
was removed; the read-only app-private verified copy and the host original remain.

## Device under test

- AVD: `personal_edge_api37_model`
- Android API: 37
- ABI: `arm64-v8a`
- Guest RAM: 12,232,284 kB (11.67 GiB)
- `/data` partition: 19.52 GiB; 9.40 GiB free while the source and verified copy
  coexisted, then 13 GiB free after the temporary picker source was removed

## Negative memory receipt

The model package supports a 32K maximum context, but a 32K CPU session is not a safe
default on this device. Engine initialization completed, then the first decode was killed
by Android. `dumpsys activity exit-info com.personaledge.agent` retained:

```text
reason=3 (LOW_MEMORY)
importance=100
rss=10GB
timestamp=2026-08-21 11:43:49.365
```

The app manifest was therefore reduced to a conservative 4,096-token first-slice budget.
Larger 8K, 16K, and 32K profiles remain benchmark candidates, not enabled product
defaults.

## Positive inference receipts

At 4K, the CPU engine initialized and the first decode returned the requested exact text:

```text
User: Reply with exactly: READY
Assistant: READY
```

Observed engine-ready RSS was about 0.79 GiB. During/after decode, RSS was about 4.64 GiB;
the process remained alive. A later `dumpsys meminfo` after the complete Tool and second
turn showed 4,639,968 kB RSS and 4,513,800 kB PSS.

The actual pinned Gemma model then produced a typed Tool call from this request:

```text
Use the fake_arrival_notice tool now with recipient wife and message
Arriving in 30 minutes. You must call the tool.
```

The observed sequence was:

1. The model emitted exactly one `fake_arrival_notice` call with `recipient=wife` and
   `message=Arriving in 30 minutes.`
2. Kotlin validation completed and the confirmation dialog appeared before execution.
3. Approval executed only the local simulation.
4. One minimal trusted Tool response, `{"simulated":true}`, was reinjected under the
   same resolved Tool name and turn without echoing model-controlled arguments.
5. The transcript rendered `User -> Tool -> Assistant`; the assistant said
   `The simulation completed.`

This is a historical confirmation-path fixture. `fake_arrival_notice` is not registered in the
shipped app; the current working-tree registry contains the seventeen real Tools listed in
[`PROJECT_STATUS.md`](PROJECT_STATUS.md).

A second top-level request then proved that a fresh native Conversation could be recreated rather
than accumulating the previous native KV history:

```text
User: Reply with exactly: SECOND
Assistant: SECOND
```

The current app still creates a fresh native Conversation per top-level turn, but it is no longer
application-level stateless: a sanitized, quoted, byte-bounded summary and newest recent messages
are supplied from Room. That later continuity path is not evidence from this historical receipt.

## 2026-08-30 model-AVD follow-up

The same verified E4B file remained in the app-private store on `personal_edge_api37_model`; its
size, digest-named file, and current pointer were preserved. The model and engine initialized on
the GPU path, but the first turn failed before emitting a token. Logcat identified missing
`libLiteRtTopKWebGpuSampler.so` and `libLiteRtTopKOpenClSampler.so`, ending with
`Can not find OpenCL library on this device`. The 622 ms diagnostic failure, zero deltas, and
971,816,960-byte PSS are one actual turn observation. A later package update contaminated the
instrumentation process-crash verdict, but occurred about 112 seconds after the native sampler
failure and did not cause it. This is an AVD GPU/OpenCL capability boundary, not an E4B artifact
failure and not a repeated reproduction.

Two explicit CPU-backend runs initialized in 482 and 410 ms and completed real inference in
17,769 and 13,765 ms, ending at 4,780,795,904 and 4,787,991,552-byte PSS. Neither passed semantic
acceptance: for the Korean request asking to create tomorrow's 15:00--16:00 dentist event, E4B
selected and executed the read-only `calendar_query` instead of proposing
`calendar_create_event`, so no confirmation appeared. No calendar write occurred. The
instrumentation now accepts an explicit `inferenceBackend=cpu` argument and stops waiting when a
failed turn has already terminated, allowing this bounded negative probe without changing the
physical-device GPU default.

These receipts establish only that the current E4B container can execute a CPU turn on this AVD
and that this exact Tool-selection phrase failed. They do not qualify AVD GPU inference, broad
Tool accuracy, Qwen3-8B, Fold8 latency, memory headroom, power, or thermal behavior. No physical
device was connected or inspected during this follow-up.

## 2026-08-30 isolated Qwen3-8B LiteRT candidate

The exact public `litert-community/Qwen3-8B@71ff705588319d52d374977eff3da4eee0c0d26e`
`qwen3_8b_mixed_int4.litertlm` was then exercised in a separate temporary application ID on the
same model AVD. Its 4,887,412,736-byte size and
`cb4e6d0de4bbf6656d177812cf0c6a983967dedd17e7f88e84b901c3a9862a42` SHA-256 were verified
before transfer and in the isolated app-private store. The candidate build pinned the artifact,
used its embedded 2,048-token context limit, kept `automaticToolCalling=false`, and used the CPU
backend in LiteRT-LM 0.16.1. It did not replace or modify the installed E4B app.

Two Tool-free turns prove a narrow but useful fact: this exact container loads and completes text
generation through the current Android runtime. They completed in 22,234 and 22,160 ms, each with
one six-byte delta. The repeated exact-output assertion also exposed an instruction mismatch:
`Reply ... with exactly READY` returned `Ready.`. End-of-turn PSS was 8,860,776,448 and
8,862,967,808 bytes, and the generated XNN CPU cache occupied 4,269,490,248 bytes.

The Korean calendar-write probe did not produce a confirmable native Tool call. It initialized
the CPU runtime in 2,542 ms, ended after 49,054 ms with zero UI deltas and
`tool_not_executed`, and reached 9,456,019,456-byte PSS. No calendar write occurred. Two earlier
`invalid_prompt` results were excluded because the temporary manifest initially advertised a
256-token output limit while the agent requested 1,024; the recorded runs used the corrected
2,048/1,024 profile. This is negative Tool-selection evidence, not a crash or a claim that every
Qwen prompt fails.

The isolated candidate and test packages, their app-private model copy, and their cache were
removed after capture; `/data` free space rose from 2,023,508 to 11,030,984 KiB. The host artifact
was retained, and the original E4B app-private artifact was reverified at its pinned
`0b2a8980...52e0` SHA-256. The full machine-readable receipt is
[`reports/eval/qwen3-8b-litert-emulator-2026-08-30.json`](../reports/eval/qwen3-8b-litert-emulator-2026-08-30.json).
No physical device was connected or inspected.

### Permanent qwen8bLab lifecycle follow-up

The permanent `qwen8bLab` variant repeated the candidate check behind a stricter boundary. Its
application ID and app-private model store are separate from production, MainActivity is disabled,
WorkManager does not auto-initialize, and calendar/network/alarm/boot permissions plus app-owned
reminder receivers are absent. `SIDE_EFFECTING_TOOLS_ENABLED=false` blocks every non-read-only
risk at PREPARE and EXECUTE. The named instrumentation test also verified no lab-UID-owned
scheduled jobs, no notification channels, and no notification runtime grant at test time.

The final app/test APK pair was installed and its two device-side base-APK SHA-256 values matched
the host files. The natural CPU lifecycle then completed model verification (1,680 ms),
initialization (4,110 ms), and a Tool-free turn (10,599 ms). The synthetic 128-token Tool slice did
not crash, but it emitted repeated plain-text `lab_echo` JSON for 17,617 ms and returned zero native
Tool calls. The post-stage PSS sample was 9,239,847,936 bytes. The cancel request was accepted after
6,742 ms including readiness retries; that is not cancellation-completion latency. The runtime
then passed cancellation invariants and completed a clean recovery turn in 10,579 ms.

A second final-pair run supplied the official Qwen `<tool_call>` fence explicitly. It still
returned one ordinary JSON object and zero native calls (7,831 ms; 8,960,824,320-byte post-stage
PSS sample). Its cancel request was accepted after 6,615 ms and its recovery turn completed in
10,236 ms. Every PSS number here is one `Debug.getPss()` sample immediately after the named stage,
not peak RSS/PSS. No Tool executed and no ToolResponse was injected because the runtime never
established a pending structured call. Both runs passed cancellation and post-cancel recovery.

The 4,269,490,248-byte XNN cache, lab model copy, and lab packages were removed after capture. The
production app and pinned E4B model remained present, size/mode/link-count correct, and
hash-correct. This is isolated AVD CPU evidence that the exact public artifact with LiteRT-LM
0.16.1 and this lab configuration failed the replacement gate; it is not a claim about every
Qwen3-8B conversion or physical Fold8 behavior. The local retained machine-readable receipt is
[`reports/eval/qwen3-8b-qwen8blab-native-lifecycle-2026-08-30.json`](../reports/eval/qwen3-8b-qwen8blab-native-lifecycle-2026-08-30.json).

Hermes 3, Qwen2.5, Granite 3.3, and EXAONE were evaluated only as GGUF host screens. None has a
matching LiteRT, Android, AVD, or Fold8 runtime receipt in this repository.

## Physical Fold8 GPU follow-up

The pinned 4K profile was later exercised on the API 37 Samsung SM-F971N Fold8 target. GPU
initialization completed without CPU fallback. Five short exact-response turns completed with a
392 ms median TTFT and 491 ms median total duration. Two bounded sustained decodes ran for about
27.8 and 43.3 seconds after first token; they were manually cancelled while Android reported
`LIGHT` and `MODERATE`, the process survived, and a cooled recovery turn completed in 552 ms. There was no
crash, ANR, or low-memory exit. This supports interactive GPU use and cancel/recovery, not
unbounded-output or long-duration thermal qualification.

The first listener-backed 768-word run naturally reached Android `SEVERE`. The previous policy
requested cooperative cancellation after 22.022 seconds, producing a byte-consistent 419-word
prefix (1,675 bytes), and the same process cooled through `MODERATE` and `LIGHT` to `NONE`. Typed
diagnostics recorded exactly one `cooperative_cancel_requested` followed by
`turn_cancelled(cause=thermal, thermal_status=severe)`; there was no crash, ANR, or low-memory exit.
The user also reported only slight tactile warmth, demonstrating why the platform status and
surface feel must be reported separately.

The policy now permits `NONE` through `SEVERE` in both debug and release builds and keeps `SEVERE`
as a visible warning with in-process non-conflating transition recording. `CRITICAL` rejects new work and requests cooperative
cancellation. `EMERGENCY` or above immediately cancels the owning turn plus matching native work,
while `UNKNOWN` fails closed. Android and firmware thermal protection remain independent. This is
a relaxed runtime policy, not sustained-release thermal qualification evidence.

On 2026-08-23, repeated short real-model acceptance work produced the first unforced natural
`CRITICAL` receipt. The device progressed from `LIGHT` through `MODERATE` and `SEVERE` to
`CRITICAL`; content-free diagnostics recorded exactly one `cooperative_cancel_requested`, followed
by `turn_cancelled(cause=thermal, thermal_status=critical)` after 4.893 seconds with zero emitted
bytes. No thermal override was active. The process later exited at the instrumentation boundary,
not as a crash/ANR/LMK, and the phone cooled through `MODERATE` to `NONE`. This accepts the natural
cooperative-cancel branch without manufacturing heat or weakening the OEM boundary.

The first post-change GPU run started at `NONE`, passed through `LIGHT` and `MODERATE`, and ended
with `turn_completed` after 57.337 seconds without a thermal cancel. It produced 1,024 `the` words
(4,095 bytes) rather than the requested 768, so runtime/thermal completion passed but exact
instruction following failed.

A second run in the same GPU runtime reached natural Android `SEVERE` 28.007 seconds after start.
The UI showed `SEVERE` with inference continuing, no cancel-request event was emitted, and decode
continued for another 24.616 seconds before `turn_completed` at 52.623 seconds. It again produced
1,024 `the` words (4,095 bytes), so the thermal/runtime path passed while the requested 768-word
semantic limit failed. A following 30-byte `POSTSEVERE` probe returned the exact 10-byte response
in 511 ms (TTFT 413 ms). The process survived, final thermal status was `NONE`, and there was no
crash, ANR, or low-memory exit. This is direct physical-device evidence that the common
debug/release policy does not cancel at `SEVERE`; by itself it did not qualify long-duration heat
or the later natural `CRITICAL` branch recorded above.

Separately on 2026-08-22, one English Fold8 request caused real Gemma to select
`calendar_create_event`; approval wrote the canonical event and a provider query confirmed its
times. One Korean instrumentation-driven request selected the same Tool and denial wrote nothing.
Those receipts establish both confirmation directions for that phrasing, not broad Tool-selection
accuracy and not physical acceptance of the other seven then-registered Tools.

The `1.0.0-rc1` candidate later completed a separate read-only `alarm_next` turn through the real
Fold8 UI. Content-free diagnostics recorded GPU initialization in 6,170 ms, the trusted
`alarm_next` execution receipt, 3,064 ms TTFT, 4,197 ms total turn duration, and thermal `none`.
This accepts one alarm-read phrasing only. A later opt-in physical receipt covered `alarm_set`:
the owner chose 15:10, real Gemma selected the Tool, the production confirmation received a real
`실행` tap, and `getNextAlarmClock()` matched. The test alarm was then removed and the next-alarm
value returned to the owner's Monday 04:55 alarm.

On 2026-08-23, the final debug APK also completed an explicitly opted-in `web_search` turn on the
1,248×1,972 Fold8 cover display. Real Gemma selected the Tool for the fixed public query
`대한민국 기상청 공식 홈페이지`; the production sheet showed the exact query plus You.com and
possible Tavily retransmission. A real `실행` tap produced `executed_success`, the trusted Tool
receipt, and an assistant answer in a 57.417-second test / 47.077-second diagnostic turn. A second
real-model turn showed the same preview and a real `거절` tap ended `tool_not_executed` without an
execution stage. The two gateways also passed their own direct live parser tests. This is one
phrase and one primary/full-path approval, not broad selection accuracy or a forced live-fallback
receipt.

On installed owner-signed rc9, a separate non-interactive Fold8 regression sent the exact reported
request `오늘 경기도 이천 날씨 알려줘.`. The automatic read-only path selected `web_search`, rendered
`웹 검색을 완료했습니다.`, then produced a non-empty assistant answer with no STATUS failure in
27.949 seconds. Its isolated conversation was deleted by exact ID. This accepts the repaired
post-Tool answer path for one phrase, not general weather accuracy or fallback behavior.

A second rc9 probe seeded a fixed isolated transcript ending at the web Tool receipt, then sent
`왜 답변을 안 해줘.`. The app recovered and re-searched the original Icheon request, ending with
exact role counts `USER:2 / TOOL:2 / ASSISTANT:1` and no post-Tool failure status in 24.864 seconds.
The synthetic conversation was deleted by exact ID. This is bounded recovery evidence, not broad
discourse-following accuracy.

Rc11 no longer depends on real-Gemma Tool JSON or post-Tool prose for an explicit weather or public
search request. Kotlin performs the enabled read through the existing orchestrator and renders the
validated result. It also recovers a clearly read-only pre-Tool failure after `왜 중단했어?`.
Host tests cover wrong-Seoul model output suppression, Dongtan correction wording, semantic
geocoder selection, exact `SK하이닉스 김재범이란 사람에 대해 찾아서 알려줘` query reduction,
bounded public-result rendering, and write-request replay refusal. On installed rc11, the three
weather cases passed in 30.394 seconds and the two public-search/recovery cases passed in 28.866
seconds. These deterministic explicit-read paths initialize the verified runtime only because the
current UI send gate requires it; they do not ask real Gemma to construct Tool JSON or factual
weather/search prose. The following rc10 evidence remains historical.

Installed owner-signed rc10 replaces weather-through-search with the dedicated structured
`weather_current` path. On Fold8, the exact request `오늘 동탄 날씨를 알려줘` produced one weather
receipt, no web-search receipt or confirmation/status failure, a concrete numeric weather value,
and the explicit `https://open-meteo.com/` source. A second test seeded the same request ending at
only the weather receipt, then sent `왜 답변을 안 해줘`; Kotlin recovered the original request,
performed a fresh weather read, and guaranteed missing concrete values/source from the validated
typed result. Both named tests passed in 53.01 seconds and deleted only their isolated
conversations. This is bounded phrase/schema recovery evidence, not general location or forecast
accuracy.

Rc9 also completed a no-send Kakao communication safety probe. The production-shaped text share
Intent resolved to installed KakaoTalk, notification-listener access remained granted, and the new
reply setting remained default-off. Real Gemma selected `kakao_share_message` for the fixed
`카카오톡으로 안녕이라고 보내줘.` request and reached confirmation; the test denied it before
execution. KakaoTalk was not opened, no `RemoteInput` ran, and no message was sent.

The ready GPU runtime later survived an Activity recreation, a stopped/resumed background cycle,
and a system-driven unfolded-display `landscape → portrait → landscape` configuration change. The
non-interactive lifecycle test completed in 12.138 seconds and the externally coordinated rotation
test in 56.332 seconds. Both compared settings, credential-presence flags, and the three-row Kakao
capture count before and after. A later opt-in turn covered the active background gap: after first
token, the Activity was moved to `CREATED` so `onStop` had completed, remained there for at least
eight seconds, and the decode completed while stopped. Diagnostics reported TTFT 2.076 seconds,
67.615 seconds total, 1,024 deltas / 4,090 bytes, natural `SEVERE`, and 4.442 GB end-of-turn PSS.
The fixed prompt requested 320 repeated words, so semantic length following still failed. The
plugged battery stayed at 94%, its charge counter moved 3,922,800→3,918,600 µAh, and temperature
stayed 33.8°C. This is bounded background and battery characterization, not unplugged endurance.
Debug target `45fef6d4…` then completed a separate physical active-fold receipt. Fold motion
started the fixed decode, the same turn ID remained active across
`OPENED(3) → CLOSED(0) → OPENED(3)`, and the Activity bounds settled from `2,448×1,848` back to the
same `2,448×1,848` inner display. Content-free diagnostics recorded TTFT 2.516 seconds, 68.031
seconds total, 1,024 deltas / 4,090 bytes, and natural `SEVERE`, with no cancellation, runtime
status error, or Tool. Settings, credential-presence flags, notification count, and owner
conversation/message counts returned exactly to baseline. This is one bounded fold cycle, not
unplugged endurance.

The same device exposed and then accepted a real summary-quality repair. A first probe showed that
the second model recap could omit an old synthetic code because the app replaced the prior summary
with untrusted new text. The app now preserves the accepted old prefix and appends a bounded delta;
the model prompt also requires explicitly remembered strings, codes, identifiers, and numbers to
remain verbatim. Eleven deterministic regressions passed, including fail-closed retention when a
complete incremental recap cannot fit. The cooled rerun produced the first and
second summaries in 2.768 and 4.286 seconds, removed the original code from the 12-message recent
window, and recalled it in a final real-model turn using the retained summary. The isolated test
conversation was individually deleted and the owner's aggregate conversation/message counts were
unchanged.

The same `45fef6d4…` target also completed a privacy-bounded Kakao search acceptance. The physical
Fold8 had notification-listener access and app capture enabled, and an aggregate-only probe found
three production rows, all tagged with the sole allowlisted package `com.kakao.talk`; no message
field was read. Real Gemma selected `kakao_notification_search` for the impossible-match sentinel
`PERSONAL_EDGE_ACCEPTANCE_NO_MATCH_20260823`. The Tool recorded `executed_success`, rendered its
trusted receipt, finished in a 15.663-second test / 5.700-second diagnostic turn with TTFT 4,120 ms,
and left the row count unchanged.

## Remaining acceptance gates

- Measure 4K over unplugged long-duration use, then benchmark any 8K and 16K candidates for
  cold/warm load, TTFT, decode rate, peak RSS/PSS, LMKD events, thermal state, and battery. Bounded
  active background, active physical fold/unfold, and natural `CRITICAL` cancellation are now
  accepted; do not manufacture another stop-level event.
- Keep 32K experimental until the physical device shows adequate Android/system headroom;
  advertised model capacity alone is not acceptance evidence.
- Seventeen real Tools are registered, but retain separate acceptance gates: `alarm_next` and one
  owner-chosen `alarm_set` have bounded Fold8 Gemma receipts; Kakao capture and a bounded no-match search are
  Fold8 accepted without inspecting message contents; NAVER Maps
  route later gained its own bounded receipt outside this historical smoke; the keyless You.com and
  saved-key Tavily gateways are separately live-qualified and the full search approval/denial path
  has one cover-display receipt, while forced fallback remains host-only. The owner chose the
  standard Samsung Account calendar instead of NAVER's create-only API; a scoped provider
  create/query/update/cleanup round trip passed. `memory_remember` remains host/data-layer verified
  only and needs a real Gemma confirmation/recall receipt. Rc9 signed-release installation and
  bounded preservation are complete; approved Kakao share/reply execution remains open.
