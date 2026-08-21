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
   export PERSONAL_EDGE_AVD_NAME=personal_edge_api37_model
   ./scripts/create-foldable-avd.sh
   ./scripts/doctor.sh
   ./scripts/test-host-scripts.sh
   ./scripts/download-model.sh
   ./scripts/verify-model.sh
   ./gradlew test lint assembleDebug assembleRelease
   emulator -avd "$PERSONAL_EDGE_AVD_NAME"
   # Run this from a second shell; use the serial shown by `adb devices`.
   ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
   ```

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

After reproducing the target flow, collect a bounded receipt before uninstalling or clearing data:

```bash
./scripts/collect-fold8-evidence.sh --serial DEVICE_SERIAL
```

The app keeps content-free rotating JSONL diagnostics under `noBackupFilesDir`, and the default
collector combines them with prior exit reasons, memory, thermal, disk, package, and build facts.
Raw app-UID logcat is an explicit `--app-logcat` opt-in because native error text may contain
conversation content.
Debug builds permit private-file extraction through `run-as`; release builds normally do not.
See [`DIAGNOSTICS.md`](DIAGNOSTICS.md) for retention, privacy exclusions, crash relaunch steps,
and the explicitly opt-in sensitive `--bugreport` mode.

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
→ final model Tool call → SDK Map normalization → strict Kotlin validation → confirmation dialog
→ execution-time interlock → durable ledger claim → Tool execution
→ trusted JSON ToolResponse → same conversation
```

The registered tools are `calendar_query`, `calendar_create_event`, and `calendar_update_event`.
Grant the calendar permission and pin a calendar in the app before using them; see
[`CALENDAR.md`](CALENDAR.md) for the scope boundary and unresolved NAVER transport gate.

LiteRT automatic tool calling and raw thinking output are both disabled. A denied, expired,
invalid, oversized, unknown, or cancelled Tool call is never executed or reinserted.
Duplicate call IDs and duplicate actions remain blocked, but LiteRT-LM exposes arguments as
a parsed Map, so raw duplicate JSON keys are normalized before app validation and cannot be
claimed as rejected by this layer.

The first slice starts a fresh native Conversation for each top-level user request. Only
the Tool call and its result share context, and reinjection is refused when the native
token count leaves insufficient room for the pinned 1,024-token final output. Long-term
memory will be added later through bounded summaries/retrieval instead of unbounded KV
history.

## Release builds

Release APKs are signed with one fixed personal key so `adb install -r` can replace the installed
app without discarding its data or the imported 3.66GB model. Create it once with
`./scripts/create-release-keystore.sh`, then confirm every build with
`./scripts/verify-release-signing.sh`. A partially configured key fails the build instead of
silently producing an unsigned APK. Backup and device-replacement steps are in
[`RELEASE_AND_BACKUP.md`](RELEASE_AND_BACKUP.md).

## Deferred tooling

Bazel, NDK, and Git LFS are not required when consuming the pinned LiteRT-LM Maven AAR.
Install them only if the runtime itself must be rebuilt or an NPU early-access path is
approved. Room and DataStore are wired in `core:data`; Hilt remains reserved in the version
catalog and is intentionally not wired.
