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
- prompt byte count, time-to-first-displayed-token, turn duration, and final-answer chunk/byte
  counts; a displayed thought token may establish TTFT, but thought text and its byte count remain
  outside diagnostics;
- closed-allowlist Tool confirmation stage/outcome and risk classification for all seventeen working-tree
  device Tools;
- closed, content-free provider/transport failure codes for network Tools, such as authentication,
  permission, quota/API-selection, rate, no-result, timeout, and malformed-response categories;
- PSS, Java heap usage, and Android thermal status;
- typed thermal status transitions, pre-turn rejection, and cancellation-request timing.

Exceptions are reduced to the exception class and a SHA-256 stack fingerprint. Exception
messages and stack text are not stored. Network failure codes likewise never retain the provider
body/message, request URL, address, search query, or credential value.

`web_search` carries a closed trusted `YOU_COM` or `TAVILY` provider tag in its Tool result so the
model can attribute the returned hits. That Tool result is not a diagnostics event. Diagnostics
retain only the registered `web_search` name, confirmation/execution outcome, and any closed
failure code; they do not store provider-returned titles, snippets, URLs, query text, Tavily key,
raw response, or the in-memory You.com circuit state. A provider-specific live receipt therefore
also needs the explicitly opted-in acceptance assertion described in `NETWORK.md`; a generic
`executed_success` alone does not identify which provider returned the result.

`weather_current` follows the same content-free boundary. Diagnostics record only the closed Tool
name, READ_ONLY risk, stage/outcome, timing, and a closed failure code. The requested place,
geocoded label or coordinates, current/daily values, Open-Meteo response, and source URL are never
diagnostic fields. The complete app-owned weather answer is built from the validated typed result;
model-authored weather prose is suppressed, and the rendered answer is not logged.

The 2026-08-23 cover-display acceptance demonstrates the intended pairing. Separate direct tests
asserted the closed `YOU_COM` and `TAVILY` identities against their live gateways. The full
real-model approval then recorded only `web_search` requested → approved → `executed_success` →
completed; the real UI denial recorded requested → denied → `tool_not_executed` and no execution
stage. Neither sequence stored the fixed public query, returned hits, provider body, URL, or key.

The following values are intentionally absent from the schema:

- prompt text and model output text;
- Tool arguments, recipient, message, confirmation preview, action ID, or parameter digest;
- model path, document URI, device serial, account data, tokens, or credentials;
- screenshots, microphone/audio, notification contents, calendar contents, or contacts.

Live Kakao acceptance follows the same boundary: the state probe emits only access/capture
booleans, a total row count, and an all-rows-allowlisted boolean. The real-model search uses an
impossible-match sentinel, and diagnostics retain only the Tool name, stage, risk, outcome, timing,
resource, and thermal fields—not the query or any notification field.

There is no diagnostic upload path. The app does request `INTERNET` for the separately gated NAVER
Maps route and You.com/Tavily search Tools, but the recorder and exporter never use it. Diagnostics
leave app-private storage only when the user explicitly chooses a document through Android's
Storage Access Framework. These files are engineering evidence, not an authorization or
tamper-proof security audit log; code already executing as the app UID may alter app-private files.

## In-app export, including signed release

The settings area exposes `진단 JSONL 저장`. It opens Android's create-document picker and writes
one chronological JSONL stream: oldest rotated archive first, active log last. Before copying, the
recorder takes a bounded snapshot under the same process lock used for writes and rejects an
unexpected path, unsafe file node, oversized source, malformed UTF-8, incomplete line, or unknown
JSONL envelope. Prompts, model output, Tool arguments/results, credentials, and the exit checkpoint
are outside the export schema.

This path works without `run-as`, so it is the private-log collection path for a signed release.
It does not itself prove release acceptance: run it on the installed signed APK and verify the
selected document before claiming that gate. Once saved to a user-selected provider, the exported
copy is no longer protected by the app's `noBackupFilesDir`; the owner controls its retention and
sharing. A destination failure may leave a partial document and is reported as a failed export.

On 2026-08-23 the Fold8 signed release exported 74 records / 10,499 bytes after all eight shipped
Tools completed. Every line parsed, all fields stayed in the closed flat schema, and fixed prompts,
write labels, addresses, the notification sentinel, and URLs were absent. The retained copy
SHA-256 is `75097be848a92df4ca2a045e9fb60aeeb81345cf7e06854ac371c7bd7fd9e741`
beside system receipt `reports/fold8-20260823T100002Z-8daac8ade96e.eT5iBP/manifest.json`.

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

The 2026-08-23 Fold8 acceptance reached natural `CRITICAL` with `IsStatusOverride: false`.
Diagnostics recorded one `cooperative_cancel_requested` followed by one
`turn_cancelled(cause=thermal, thermal_status=critical)`; no prompt/output content was needed to
establish the branch. A later cooled active-background run completed at `SEVERE` in 67.615 seconds
with TTFT 2.076 seconds and a 4.442 GB resource snapshot. Do not force another `CRITICAL` event just
to reproduce this receipt.

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
part of the default content-free bundle. For a signed release, first use the in-app JSONL export,
then run the host collector for package/exit/memory/thermal facts; keep the exported JSONL beside
the resulting manifest as a separately user-authorized artifact.

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
available for a debuggable build; Android normally denies it for a release build. That denial is
not evidence that release diagnostics are absent; use the in-app export for their private JSONL.
The collector retains that `run-as` denial as `run-as.txt` and marks the four app-private JSONL
commands `not_applicable_release` instead of turning the otherwise valid release receipt into a
false failure.
A missing diagnostic file is recorded as `not_present`, while an unsafe, oversized, or
inaccessible output makes the host collection a partial failure.

The collector never clears logcat, force-stops the package, reinstalls the APK, clears app data,
or deletes device files.

## Reproduction workflow

1. Install or update the intended APK with the same signing key. For the existing debug install,
   use `adb -s DEVICE_SERIAL install -r ...`; never try to replace debug with release.
2. Reproduce model import, initialization, inference, cancellation, and confirmation paths.
3. If the process dies, launch it once and wait for the initial model inspection to finish.
4. In a release build, save the in-app content-free JSONL. Run the one-shot collector before
   uninstalling or clearing app data in either build.
5. Compare the receipt manifest with the JSONL event sequence, exit reason, memory, and thermal
   artifacts. Add app logcat only when the typed evidence is insufficient, and keep any
   content-bearing bundle local unless it has been reviewed.

`adb install -r` with the same application ID and signing key normally preserves the private model
and diagnostic files. Uninstalling the app or running `pm clear com.personaledge.agent` deletes
both; neither action belongs in the evidence-collection workflow.

Before a physical release update, run the separate read-only
`./scripts/preflight-fold8-release-update.sh --serial DEVICE_SERIAL`. It binds the exact local
release app/test APK identities and signing certificate to the installed `base.apk` before any
install command is allowed into the procedure. Its PASS is a compatibility precondition, not an
acceptance result; content-free snapshots are still required before and after the update.
