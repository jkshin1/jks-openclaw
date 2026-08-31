# Development environment

## Pinned baseline

| Component | Version |
|---|---:|
| Android Studio | Quail 3 / 2026.1.3 Patch 1 or newer |
| Android Gradle Plugin | 9.3.1 |
| Gradle Wrapper | 9.5.0 |
| Kotlin / Compose compiler plugin | 2.3.21 |
| JDK toolchain | 17 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 31 |
| LiteRT-LM Android | 0.16.1 |

AGP 9 uses built-in Kotlin. Do not add `org.jetbrains.kotlin.android`; annotation
processing must use KSP (or AGP's legacy kapt only as a temporary fallback).

## Local setup

1. Install Android Studio and JDK 17.
2. In SDK Manager, install Android SDK Platform 37.0, Platform Tools, Command-line Tools,
   Build Tools 36.0.0 (AGP 9.3 default), and Build Tools 37.0.0 (Android 17 tools).
3. Create untracked `local.properties`:

   ```properties
   sdk.dir=/Users/YOUR_ACCOUNT/Library/Android/sdk
   ```

4. Load the project toolchain and run the checks/build:

   ```bash
   source ./scripts/android-env.sh
   export PERSONAL_EDGE_AVD_NAME=personal_edge_api37_foldable
   ./scripts/create-foldable-avd.sh
   ./scripts/doctor.sh
   ./scripts/test-host-scripts.sh
   ./scripts/download-model.sh
   ./scripts/verify-model.sh
   ./gradlew test lint assembleDebug
   ./gradlew --offline releaseGate
   emulator -avd "$PERSONAL_EDGE_AVD_NAME" -read-only -no-snapshot-load -no-snapshot-save
   # Run this from a second shell; use the exact emulator serial shown by `adb devices -l`.
   ./scripts/run-avd-regression.sh --serial emulator-5554 --confirm-disposable
   ```

`run-avd-regression.sh` refuses physical serials and verifies qemu identity, the exact expected AVD
name, API 37, `arm64-v8a`, and completed boot both before the build and before installation. It
builds and installs six debug APKs with explicit `adb -s`, then runs a reviewed positive allowlist
of 239 app/core methods sequentially. New test files or changed method counts fail closed for
review. Owner-approved Samsung Calendar actions and the dedicated E4B/Qwen/ABI model lanes are not
part of this fast regression. The remaining emulator tests can clear the emulator Clock app and
the debug app's fixture vault, so use only an account-free disposable AVD started with the exact
read-only/no-snapshot flags above; `--confirm-disposable` acknowledges that caller-owned boundary.
The script never starts, stops, uninstalls, or clears an AVD itself.

Android Studio should use JDK 17 for Gradle. Source and bytecode compatibility remain
Java 17 even if the IDE itself runs on a newer bundled runtime.

## Physical Fold8

Enable developer options and USB or wireless debugging, then record device facts rather
than relying only on marketing specifications:

```bash
adb devices -l
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.hardware
```

Build and update the debug APK with an explicit device serial. `-r` preserves the existing
app-private model and diagnostic history when the application ID and signing key are unchanged:

```bash
./gradlew :app:assembleDebug
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

The search-provider tests are deliberately absent from an ordinary device run. They require a
physical device, a fixed public query, and an explicit runner argument for each external call. The
You.com test needs no key; the Tavily test reads an already-saved key without modifying the vault:

```bash
ANDROID_SERIAL=DEVICE_SERIAL ./gradlew :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=com.personaledge.agent.WebSearchProviderLiveAcceptanceTest#keylessYouComReturnsSanitizedHttpsHits" \
  -Pandroid.testInstrumentationRunnerArguments.liveYouSearch=true

ANDROID_SERIAL=DEVICE_SERIAL ./gradlew :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=com.personaledge.agent.WebSearchProviderLiveAcceptanceTest#savedTavilyCredentialReturnsSanitizedHttpsHits" \
  -Pandroid.testInstrumentationRunnerArguments.liveTavilySearch=true
```

These are direct provider/parser receipts only. They do not replace a real-model `web_search`
selection, default-off consent, automatic no-confirmation execution, fallback-routing, or a
content-free diagnostics receipt. Read `AGENTS.md` before either command: keep the exact serial,
preserve installed APKs, and never run a vault-clearing test on the Fold8.

The full live Tool test requires the owner to have enabled web search, uses only the same fixed
public query, launches a fresh conversation without deleting prior history, and fails if a
confirmation sheet appears. Build and replace only the test package; do not reinstall or uninstall
the target app:

```bash
./gradlew :app:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.WebSearchLiveToolAcceptanceTest#publicQueryCompletesThroughRealGemmaWithoutConfirmationUi' \
  -e liveWebSearchTool true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner

adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.WeatherLiveToolAcceptanceTest' \
  -e liveWeatherTool true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner

adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.PublicPersonSearchLiveAcceptanceTest' \
  -e livePublicPersonSearch true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner
```

The settings switch is the external-transmission decision. No `실행`/`거절` action is expected for
these read-only Tools. The rc11 weather class exercises the exact `오늘 동탄 날씨를 알려줘`, a
recent-weather correction `서울이 아니라 동탄`, and a fixed transcript ending at the weather
receipt followed by `왜 답변을 안 해줘`. It requires one app-owned numeric value set, a semantically
Dongtan resolved location, natural wording, and the official Open-Meteo URL. The public-person
class exercises the exact SK hynix query and a pre-Tool failure recovered by `왜 중단했어?`; it
accepts only bounded public results or a truthful no-result answer, and requires the identity
verification notice. Each test deletes only its exact synthetic conversation. Do not treat these
phrases as general geocoder, forecast, search completeness, identity, or discourse accuracy.

Kakao acceptance is also absent from ordinary runs. The first test reports only system-grant,
app-opt-in, total-row-count, and all-rows-allowlisted aggregates. The second asks real Gemma to
search an impossible-match sentinel and asserts the read-only Tool leaves the count unchanged; it
does not inspect a notification field:

```bash
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.KakaoNotificationLiveStateTest#grantedCaptureStateReportsOnlyBooleanAndCount' \
  -e liveKakaoState true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner

adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.KakaoNotificationLiveToolAcceptanceTest#fixedNoMatchQueryCompletesThroughRealGemmaWithoutReadingMessageBodies' \
  -e liveKakaoTool true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner

adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.Fold8KakaoCommunicationSafetyAcceptanceTest#shareRequestStopsAtConfirmationWithoutSending' \
  -e kakaoCommunicationSafety true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner
```

Do not replace the sentinel with a person's name or a conversation phrase for routine acceptance.
The communication safety test resolves the share Intent and denies the model-selected Tool at the
confirmation boundary. It must never be changed to approve execution in automation.

Physical `alarm_set` acceptance is intentionally absent from ordinary runs because Android exposes
no public alarm-delete API. Run it only after the owner chooses an exact `HH:mm` at least fifteen
minutes ahead by default. A shorter time may be used only after an explicit owner choice, but keep
at least three minutes so the resulting `Personal Edge 검증` one-shot alarm cannot ring mid-test and
must still be removed in
Samsung Clock. The test refuses to run if an existing alarm would trigger first, waits for a real
tap on the production `실행` button, verifies `getNextAlarmClock()`, and never clears clock data:

```bash
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.AlarmSetLiveAcceptanceTest#ownerApprovedOneShotAlarmAppearsAsTheNextClockAlarm' \
  -e liveAlarmSet true \
  -e interactiveAlarmSetApproval true \
  -e alarmSetTime HH:mm \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner
```

Wait for `privacy_alarm_set_confirmation_ready=true`, inspect the exact preview, and press `실행`
only if it matches the owner's chosen time. A PASS emits
`privacy_alarm_set_owner_cleanup_required=true`; open Samsung Clock and delete only that labeled
alarm. Never use `pm clear` on the owner's clock app.

The 2026-08-23 Fold8 receipt used the owner-chosen 15:10 time and passed after a real production
`실행` tap. The labeled test alarm was removed, and the public next-alarm value returned to the
owner's Monday 04:55 alarm.

Samsung Calendar acceptance is also opt-in. The selection harness reads calendar metadata only,
prefers the single standard Samsung Account row (`com.osp.app.signin`) over the separate Mobile
Service sharing row, and changes only this app's pinned id. The live harness creates one unique
event, proves scoped query/update, and deletes only the exact id/calendar/title tuple it created:

```bash
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.SamsungCalendarSelectionAcceptanceTest#ownerSelectedSamsungAccountCalendarBecomesTheOnlyToolScope' \
  -e selectSamsungCalendar true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner

adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.SamsungCalendarLiveAcceptanceTest#selectedSamsungCalendarCompletesCreateQueryUpdateAndOwnedCleanup' \
  -e liveSamsungCalendarRoundTrip true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner
```

If a process stops after its insert, do not delete by title alone. Read the exact event id back,
re-establish its selected calendar and `Personal Edge 검증 ` prefix, then use the separately gated
`cleanupOneExplicitlyIdentifiedOwnedAcceptanceEvent` recovery method. Event id 757 from the first
Android-17 cleanup-URI attempt was recovered this way and is no longer present.

The lifecycle test is also opt-in and content-free. It recreates the Activity, backgrounds and
resumes it, and compares only settings, credential-presence flags, and notification row count:

```bash
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.Fold8LifecycleAcceptanceTest#gpuRuntimeSurvivesRecreationAndBackgroundWithoutChangingPrivateState' \
  -e liveFoldLifecycle true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner
```

Android 17 can ignore an app's orientation request on a large display, so the physical rotation
receipt is externally coordinated. Record `wm user-rotation -d DISPLAY_ID` first; start the test,
wait for `privacy_rotation_ready=true`, temporarily lock the opposite rotation, wait for
`privacy_rotation_alternate_observed=true`, restore the initial rotation, then restore `free`:

```bash
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.Fold8LifecycleAcceptanceTest#gpuRuntimeSurvivesExternalSystemRotationWithoutChangingPrivateState' \
  -e liveFoldRotation true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner
```

Never leave a user's display rotation locked after a test. This receipt covers ready-runtime
configuration survival, not a rotation during active decode.

The real summary/recall and active-background receipts are separately opt-in. They use only fixed
synthetic text, compare owner data by aggregate counts/presence, and delete only the isolated test
conversation they create:

```bash
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.Fold8RuntimePrdAcceptanceTest#realSummaryCarriesAnEarlySyntheticFactAfterCompaction' \
  -e liveFoldSummary true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner

adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.Fold8RuntimePrdAcceptanceTest#sustainedGpuTurnCompletesWhileActivityIsStopped' \
  -e liveFoldSustained true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner

adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.Fold8RuntimePrdAcceptanceTest#activeGpuTurnSurvivesAFullPhysicalFoldAndRestore' \
  -e liveFoldActiveTurn true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner
```

Start each only when the production policy reports a runnable thermal state: Android `NONE`,
`LIGHT`, `MODERATE`, or `SEVERE`. The tests do not wait for cooling within that range; they block
only at `CRITICAL` or above, while `UNKNOWN` fails closed. If a natural stop-level state occurs, let
the app's policy cancel or abort the turn; do not immediately retry or force a thermal override.
The background test moves the Activity to `CREATED` after the first displayed thought or answer
token, which means `onStop` has completed while the ViewModel and active turn remain retained. The
AndroidX test harness implements that stop transition with an opaque
`InstrumentationActivityInvoker.EmptyActivity`. The `androidTest` manifest replaces only that
Activity's white window with an opaque black test theme so a Fold8 observer does not mistake the
lifecycle fixture for an app crash. Do not make it transparent or translucent: the target Activity
must really reach `onStop` for the acceptance claim to remain valid. The active-fold test is
interactive:
start fully opened or fully closed, wait for `privacy_active_fold_ready=true`, move all the way to
the opposite physical state, pause briefly, and return all the way to the initial state before the
fixed decode ends. Fold motion arms the fixed decode, so a practical sequence is to hold half-open
for three seconds, hold the opposite endpoint for two seconds, and restore. The test reads only
`cmd device_state print-state` through instrumentation shell identity, records aggregate display
dimensions, and fails unless the same active turn survives both physical endpoints and the Activity
window settles back to its initial display bounds. Do not use a software device-state override as
evidence of a physical fold.

Physical offline acceptance is opt-in and invokes only the production interlock. Record Wi-Fi,
mobile-data, and airplane-mode values first; disable only the transports that were on, run the
probe, and restore every value even if the test fails:

```bash
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.personaledge.agent.NetworkOfflineLiveAcceptanceTest#consentedRouteAndSearchAreBlockedBeforeAnyGatewayWhileOffline' \
  -e liveNetworkOffline true \
  com.personaledge.agent.test/androidx.test.runner.AndroidJUnitRunner
```

The expected receipt is both `privacy_offline_*_blocked=true` and
`privacy_offline_gateway_calls=0`. This is the execution-interlock gate, not a provider timeout
test, and it must not be run without a host-side connectivity restoration plan.

After reproducing the target flow, collect a bounded receipt before uninstalling or clearing data:

```bash
./scripts/collect-fold8-evidence.sh --serial DEVICE_SERIAL
```

The app keeps content-free rotating JSONL diagnostics under `noBackupFilesDir`, and the default
collector combines them with prior exit reasons, memory, thermal, disk, package, and build facts.
Raw app-UID logcat is an explicit `--app-logcat` opt-in because native error text may contain
conversation content.
Debug builds permit private-file extraction through `run-as`; release builds normally do not.
Signed release builds instead export the same content-free JSONL from the app through Android's
create-document picker. See [`DIAGNOSTICS.md`](DIAGNOSTICS.md) for retention, privacy exclusions,
crash relaunch steps, and the explicitly opt-in sensitive `--bugreport` mode.

The emulator is useful for UI and permission flows. LiteRT-LM GPU, memory, thermal,
fold-state, and sustained decode acceptance must run on the physical Fold8.

The Fold8 thermal guard uses the same relaxed boundary in debug and release builds. It allows
Android `NONE`, `LIGHT`, `MODERATE`, and `SEVERE` states. `SEVERE` remains visible and is recorded
through a non-conflating transition queue, but it does not reject or cancel inference.
`CRITICAL` blocks new runtime initialization and turns and requests cooperative cancellation;
`EMERGENCY` or above immediately cancels the owning turn coroutine plus matching native work.
`UNKNOWN` is also blocked because an active listener cannot be proven. This deliberately relaxed
policy still uses Android's OEM-calibrated status rather than a fixed surface-temperature
threshold, and Android/firmware thermal protection remains independent. Validate
the thermal request event -> `turn_cancelled(cause=thermal)` sequence and a later recovery turn with
the default content-free evidence collector.

The 2026-08-23 Fold8 receipt naturally reached `CRITICAL` with overrides disabled and recorded one
cooperative cancel request followed by one thermal cancellation. Treat that gate as accepted; do
not deliberately reproduce it. A separate cooled run completed 67.615 seconds of decode while the
Activity was stopped and reached only `SEVERE`.

The `.litertlm` package advertises a 32K maximum context, but that is not the initial app
default. The pinned runtime budget is 4,096 total input/output tokens: the 32K CPU setting
reached 10GB RSS and Android LMK terminated the first decode on the 12GB API 37 AVD. Treat
8K, 16K, and 32K as benchmark candidates and promote them only with physical-device peak
RSS, latency, thermal, and sustained-decode receipts.

LiteRT-LM 0.16.1 publishes Java 21 class files. Android builds desugar them correctly,
but the policy regression test runs as an Android instrumentation test instead of a
JDK 17 local JVM test.

The creation script defaults to the UI-test AVD name `personal_edge_api37_foldable` and
configures 12GB RAM plus a 20GB data partition. AVD disk sizing is applied when the AVD is
created; it does not enlarge an already-created userdata image. Use a dedicated name for
the real-model import so the 3.66GB source file and verified app-private copy can coexist:

```bash
export PERSONAL_EDGE_AVD_NAME=personal_edge_api37_model
./scripts/create-foldable-avd.sh
./scripts/doctor.sh
emulator -avd "$PERSONAL_EDGE_AVD_NAME"
```

The first API 37 ARM64 real-model receipt is recorded in
[`REAL_MODEL_SMOKE.md`](REAL_MODEL_SMOKE.md). It is emulator evidence, not physical Fold8
acceptance.

The model AVD does not provide the OpenCL sampler libraries that LiteRT-LM 0.16.1's GPU turn
requires. GPU engine initialization can therefore succeed and still fail on the first turn with
`Can not find OpenCL library on this device`. Use the explicit CPU backend for the bounded Korean
Tool-selection probe on this AVD; keep the default GPU path for a capable physical device:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.personaledge.agent.KoreanToolSelectionTest' \
  -Pandroid.testInstrumentationRunnerArguments.inferenceBackend=cpu
```

The test denies any pending write confirmation. It is one fixed-phrase selection receipt, not a
26-case model score or a device-performance qualification.

Do not reuse the production application ID for a large candidate-model experiment. The permanent
`qwen8bLab` build type exists in `app`, `core:agent`, and `core:llm`; an app-only flavor is
insufficient because the runtime and model identity are compiled in `core:llm`. It pins
`models/model-manifest-qwen3-8b.json`, uses `com.personaledge.agent.qwen8blab`, stores the model
under `personal-edge-models-qwen3-8b-v1`, and compiles the public 2K artifact with a 384-token
output ceiling. It also forces explicit Tool scope and disables every side-effecting Tool risk.

This is an instrumentation-only build. MainActivity and WorkManager auto-initialization are
disabled; calendar, network, alarm, and boot permissions plus app reminder receivers are removed.
Do not use it as an interactive app or grant its remaining notification declaration. Build and
lint it explicitly:

```bash
./gradlew --offline -PpersonalEdgeQwen8bLabTest=true \
  :core:llm:testQwen8bLabUnitTest \
  :core:agent:testQwen8bLabUnitTest \
  :app:testQwen8bLabUnitTest \
  :core:llm:lintQwen8bLab \
  :core:agent:lintQwen8bLab \
  :app:lintQwen8bLab \
  :app:assembleQwen8bLab \
  :app:assembleQwen8bLabAndroidTest
```

The two core modules explicitly enable their Qwen JVM test components. Keep runtime tests
manifest-aware: an assertion that hard-codes E4B's 4,096/1,024 limits can pass debug while never
exercising Qwen's 2,048/384 boundary.

Run only named instrumentation after independently placing and verifying the exact pinned model
in the lab store. The CPU emulator receipt uses `qwen8bLabTarget=emulator`; a future Fold8 run must
use `qwen8bLabTarget=fold8`, an explicit physical serial, and the GPU backend. Never substitute a
broad `connectedAndroidTest` run for this boundary. The current public artifact fails the native
Tool-call gate even when the Qwen fence is made explicit, so physical performance testing cannot
promote it without a new artifact/runtime result.

The current APK is intentionally limited to `arm64-v8a` for the Fold8 and Apple Silicon
ARM64 emulator. ChromeOS and x86/x86_64 Android devices are outside this first target.

## Model policy

Do not download the 3.66GB model during ordinary Gradle builds. `scripts/download-model.sh`
downloads the exact revision into the ignored `models/` directory and verifies exact Long
size and SHA-256. In the app, use the system document picker to import that file. The model
store writes and hashes one exclusive file descriptor, publishes without overwrite, fsyncs
the file and directory, and commits only a small current pointer under `noBackupFilesDir`.
The LiteRT runtime accepts only the resulting opaque verified handle. See `models/README.md`.

The first app flow is deliberately manual:

```text
OpenDocument URI → verified app-private artifact → LiteRT Conversation
→ final model Tool call → SDK Map normalization → strict Kotlin validation → risk-based confirmation
→ execution-time interlock → durable ledger claim → Tool execution
→ trusted JSON ToolResponse → same conversation
```

The working-tree device registry contains seventeen Tools: `calendar_query`, `calendar_create_event`,
`calendar_update_event`, `alarm_set`, `alarm_next`, `kakao_notification_search`, `route_estimate`,
`web_search`, `memory_remember`, `commitment_propose`, `reminder_create`, `reminder_update`,
`reminder_cancel`, `reminder_query`, `kakao_share_message`, `kakao_notification_reply`, and
`weather_current`.
Calendar reads require permission and explicit read-calendar
selection; writes remain pinned to one writable provider row. Notification
search requires both the Android listener grant and the default-off capture setting. Each network
Tool requires a default-off persistent opt-in and validated connectivity; after opt-in, read-only
route, web, and weather requests execute without a per-request confirmation sheet. Route additionally
requires the NCP Maps pair. Kakao sharing and notification reply are `COMMUNICATION` Tools and
always require confirmation. Sharing opens KakaoTalk's own target picker; notification reply also
requires the independent default-off reply setting and an exact active notification with one
free-form reply action.
Search uses keyless You.com first and can run without a Tavily key; when a Tavily key is present,
the same canonical query may be sent once more under the closed quality/failure fallback policy.
A candidate Tavily key is checked against the fixed usage endpoint before storage. See
[`CALENDAR.md`](CALENDAR.md),
[`ALARM.md`](ALARM.md), [`NOTIFICATIONS.md`](NOTIFICATIONS.md), and [`NETWORK.md`](NETWORK.md).
`memory_remember` requires the independent default-off memory setting and exact user confirmation;
it never stores credentials or time-based reminders. See [`MEMORY.md`](MEMORY.md).
`commitment_propose` requires a separate default-off proposal setting and creates only an inbox row.
Reminder writes always require confirmation and stay app-owned; see [`REMINDERS.md`](REMINDERS.md).

LiteRT automatic tool calling remains disabled. Thinking is enabled by default; its dedicated raw
thought channel is exposed only through the active `생각 중` UI disclosure and is never parsed as
a Tool proposal, copied into final answer/history, persisted, summarized, or diagnosed. A denied,
expired, invalid, oversized, unknown, or cancelled Tool call is never executed or reinserted.
Duplicate call IDs and duplicate actions remain blocked, but LiteRT-LM exposes arguments as
a parsed Map, so raw duplicate JSON keys are normalized before app validation and cannot be
claimed as rejected by this layer.

The app starts a fresh native Conversation for each top-level user request. The Tool call and its
result share that native context. Short acknowledgements use a 256-token native ceiling, structured
operational prompts use 384, and ambiguous/long-form prompts retain the pinned 1,024 ceiling;
reinjection is refused when the token count leaves insufficient room for the active ceiling and
trusted Tool-response reserve. Cross-turn continuity is application
owned: Room supplies a bounded summary plus the newest recent messages and, when enabled, a small
relevance-ranked set of approved memories. Each source is sanitized, explicitly quoted as data,
and fitted with device state inside a 2 KiB request envelope. Compaction starts after ten pending
messages or 1,536 UTF-8 source bytes, replaces rather than appends one complete 480-byte
latest-state capsule, and preserves the newest twelve rows verbatim. Critical literals cannot
disappear without bounded explicit correction evidence. A new user turn cancels and joins any
background summary before acquiring the single controller. Background work follows the same
owner-selected thermal boundary as foreground inference: NONE through SEVERE continue, CRITICAL
cancels, and higher or UNKNOWN states fail closed. Only a normally completed result still within
that boundary is stored. This is bounded retrieval, not unbounded native KV history.

Validate and score replacement models with the fixed synthetic corpus documented in
[`MODEL_EVALUATION.md`](MODEL_EVALUATION.md). Host scores do not qualify Fold8 PSS, thermal, battery,
fold, or cancellation metrics.

## Release builds

Release APKs are signed with one fixed personal key so `adb install -r` can replace the installed
app without discarding its data or the imported 3.66GB model. Create it once with
`./scripts/create-release-keystore.sh`, then confirm every build with
`./scripts/verify-release-signing.sh`. A partially configured key fails the build instead of
silently producing an unsigned APK. Backup and device-replacement steps are in
[`RELEASE_AND_BACKUP.md`](RELEASE_AND_BACKUP.md).

`releaseGate` generates the exact `releaseRuntimeClasspath` CycloneDX SBOM, binds it and the
privacy-safe source-state digest into packaged provenance, runs host tests and lint, builds the
minified signed release, regenerates both assets byte-for-byte, and verifies the signer. Run it only
from a source freeze; any source edit after generation invalidates the receipt. The schema and
standalone verification commands are in [`RELEASE_PROVENANCE.md`](RELEASE_PROVENANCE.md).

The owner's key already exists and the verifier has passed a non-debug v3-signed APK. Do not run
the creation script again. What remains is owner-confirmed offline key/password backup and a
deliberate debug-to-release migration; the two certificates cannot replace each other.

## Deferred tooling

Bazel, NDK, and Git LFS are not required when consuming the pinned LiteRT-LM Maven AAR.
Install them only if the runtime itself must be rebuilt or an NPU early-access path is
approved. Room and DataStore are wired in `core:data`; Hilt remains reserved in the version
catalog and is intentionally not wired.
