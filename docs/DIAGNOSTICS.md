# On-device diagnostics

The physical-device diagnostic path is deliberately local, bounded, and content-free. It is
intended to answer questions such as “did model verification fail?”, “was GPU initialization
replaced by CPU?”, “how long was time-to-first-token?”, and “did Android kill the process for
memory pressure?” without retaining the conversation itself.

## What the app records

The app writes typed JSON Lines events under its private no-backup directory:

```text
<noBackupFilesDir>/diagnostics/diagnostics.jsonl
<noBackupFilesDir>/diagnostics/diagnostics.1.jsonl
<noBackupFilesDir>/diagnostics/diagnostics.2.jsonl
<noBackupFilesDir>/diagnostics/diagnostics.3.jsonl
```

Each file is capped at 5 MiB, so the rotating event set is capped at approximately 20 MiB.
Events are serialized on a dedicated worker and each append is flushed to the file descriptor,
keeping file I/O and resource sampling off the UI thread. Logging is best-effort: a full disk,
unsafe file, sudden process death before a queued event is written, or diagnostic implementation
failure must not change model, confirmation, or Tool behavior.

The allowlisted records include:

- process/session start and the previous process exit classification;
- model inspect/import result, duration, expected artifact size, and typed failure code;
- requested/active inference backend and initialization duration;
- prompt byte count, time-to-first-token, turn duration, and output chunk/byte counts;
- known Tool confirmation stage/outcome and risk classification;
- PSS, Java heap usage, and Android thermal status;
- typed thermal status transitions, pre-turn rejection, and cancellation-request timing.

Exceptions are reduced to the exception class and a SHA-256 stack fingerprint. Exception
messages and stack text are not stored.

The following values are intentionally absent from the schema:

- prompt text and model output text;
- Tool arguments, recipient, message, confirmation preview, action ID, or parameter digest;
- model path, document URI, device serial, account data, tokens, or credentials;
- screenshots, microphone/audio, notification contents, calendar contents, or contacts.

There is no diagnostic upload path and the app does not request the `INTERNET` permission.
These files are engineering evidence, not an authorization or tamper-proof security audit log;
code already executing as the app UID may alter app-private files.

## Runtime thermal guard

The ViewModel owns one Android `PowerManager` thermal listener for the lifetime of the runtime.
The relaxed Fold8 policy is identical in debug and release builds and deliberately uses the
OEM-calibrated Android status rather than a hard-coded Celsius value. `NONE`, `LIGHT`, `MODERATE`,
and `SEVERE` permit inference; `SEVERE` remains a visible warning whose in-process transition is
recorded through a non-conflating queue. `CRITICAL`
rejects new work and requests cooperative
cancellation. `EMERGENCY` and `SHUTDOWN` immediately cancel the owning coroutine and also request
matching native cancellation. An unavailable thermal service is `UNKNOWN` and fails closed through
the immediate path. Android and the device firmware retain their independent thermal protection.

The status is re-read immediately before runtime initialization and every turn. While the process
survives, status transitions use a non-conflating diagnostics queue, so a rapid `SEVERE` -> cooled
transition remains ordered for recording. Sudden process death retains the best-effort limit above.
A monotonic,
turn-scoped stop latch preserves even a brief stop-level observation across cooling and across the
small gap before the controller has registered the new turn. A `thermal_guard` JSONL event records
only the typed status and one of `status_observed`, `runtime_initialization_rejected`,
`runtime_initialization_cancel_requested`, `turn_rejected`, `cooperative_cancel_requested`, or
`immediate_abort_requested`; it never contains the prompt, output, turn ID, or device temperature.
The following `turn_cancelled` includes the typed cause and thermal status and is the terminal
proof that cancellation completed.

LiteRT-LM 0.16.1 exposes no interruptible engine-initialization API. If the status rises while
`Engine.initialize()` is inside native code, the owning job is cancelled immediately but native
initialization may return only at its next boundary; the cancelled result is discarded and is
never exposed as `READY`.

## Crash, ANR, and low-memory exits

Android exposes the prior process exit through `ApplicationExitInfo`. On the next app start, the
recorder adds its typed reason (for example, crash, native crash, ANR, or low memory), PSS/RSS,
and the last allowlisted process phase. It does not copy the platform trace into the app log.

After a crash or forceful low-memory exit, launch the app once before collection so the previous
exit can be added to the rotating JSONL files. The host collector also captures the current
package exit history and bounded system diagnostics directly from the device.

## Collect a Fold8 evidence bundle

First load the pinned Android tools and select the exact serial reported by ADB:

```bash
source ./scripts/android-env.sh
adb devices -l
./scripts/collect-fold8-evidence.sh --serial DEVICE_SERIAL
```

The command creates an ignored, timestamped `reports/fold8-*` directory containing a manifest
with status, byte size, and SHA-256 for every captured artifact. The default one-shot includes
device/build/package facts, package exit history, `dumpsys meminfo -d`, thermal state, disk state,
and—when a debug APK is installed—the four private JSONL files through `run-as`. Raw logcat is not
part of the default content-free bundle.

Three potentially content-bearing modes require an explicit choice:

```bash
# Bounded main/system/crash buffers, restricted to the verified app UID.
./scripts/collect-fold8-evidence.sh --serial DEVICE_SERIAL --app-logcat

# Potentially broad and sensitive system report; inspect before sharing.
./scripts/collect-fold8-evidence.sh --serial DEVICE_SERIAL --bugreport

# Continue showing this package's logcat until Ctrl-C after the one-shot receipt is complete.
./scripts/collect-fold8-evidence.sh --serial DEVICE_SERIAL --follow-logcat
```

`--app-logcat` and `--follow-logcat` are restricted to the verified package UID, but native runtime
error lines may still contain rendered prompt, output, or Tool-response content. Treat them as
sensitive and inspect them before sharing. `--bugreport` can additionally contain information from
other apps and the wider device. None is collected by default. System-authored LMK/ANR evidence is
instead taken from exit history and package dumps. Private JSONL extraction with `run-as` is
available for a debuggable build; Android
normally denies it for a release build. A missing diagnostic file is recorded as `not_present`,
while an unsafe, oversized, or inaccessible output makes the collection a partial failure.

The collector never clears logcat, force-stops the package, reinstalls the APK, clears app data,
or deletes device files.

## Reproduction workflow

1. Install or update the debug APK with `adb -s DEVICE_SERIAL install -r ...`.
2. Reproduce model import, initialization, inference, cancellation, and confirmation paths.
3. If the process dies, launch it once and wait for the initial model inspection to finish.
4. Run the one-shot collector before uninstalling or clearing app data.
5. Compare the receipt manifest with the JSONL event sequence, exit reason, memory, and thermal
   artifacts. Add app logcat only when the typed evidence is insufficient, and keep any
   content-bearing bundle local unless it has been reviewed.

`adb install -r` with the same application ID and signing key normally preserves the private model
and diagnostic files. Uninstalling the app or running `pm clear com.personaledge.agent` deletes
both; neither action belongs in the evidence-collection workflow.
