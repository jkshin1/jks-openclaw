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
shipped app; the current device registry contains the eight real Tools listed in
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
debug/release policy does not cancel at `SEVERE`; it does not qualify long-duration heat or the
natural `CRITICAL` branch.

Separately on 2026-08-22, one English Fold8 request caused real Gemma to select
`calendar_create_event`; approval wrote the canonical event and a provider query confirmed its
times. One Korean instrumentation-driven request selected the same Tool and denial wrote nothing.
Those receipts establish both confirmation directions for that phrasing, not broad Tool-selection
accuracy and not physical acceptance of the other seven registered Tools.

The `1.0.0-rc1` candidate later completed a separate read-only `alarm_next` turn through the real
Fold8 UI. Content-free diagnostics recorded GPU initialization in 6,170 ms, the trusted
`alarm_next` execution receipt, 3,064 ms TTFT, 4,197 ms total turn duration, and thermal `none`.
This accepts one alarm-read phrasing only; it does not accept physical `alarm_set`.

## Remaining acceptance gates

- Measure 4K, then 8K and 16K candidates for cold/warm load, TTFT, decode rate, peak
  RSS/PSS, LMKD events, thermal state, battery, folded/unfolded, and background transitions.
- Observe a natural `CRITICAL` Fold8 transition and confirm one thermal cancel-request event followed
  by one `turn_cancelled(cause=thermal)`; do not force a system thermal override on the personal
  device. Natural `SEVERE` continuation is confirmed above.
- Keep 32K experimental until the physical device shows adequate Android/system headroom;
  advertised model capacity alone is not acceptance evidence.
- The eight real Tools are registered, but retain separate acceptance gates: `alarm_next` has one
  Fold8 Gemma turn while `alarm_set` does not; Kakao capture lacks a real-package post; NAVER
  route/search lack live credentialed calls; NAVER Calendar publication is unqualified; and
  signed-release migration remains an owner decision after offline key backup.
