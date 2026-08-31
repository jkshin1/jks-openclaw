# Repository Guidelines

## Project Structure & Module Organization

- `app/`: Compose UI, wiring, permissions, and lifecycle policy.
- `core/agent/`: model-turn control, strict Tool parsing, and orchestration.
- `core/llm/`: LiteRT-LM runtime and verified model storage.
- `core/tools/`: Tool contracts, interlocks, ledgers, and provider gateways.
- `core/data/`: Room, DataStore, and secret-vault abstractions.
- `core/diagnostics/`: bounded, content-free JSONL diagnostics.
- `scripts/`, `models/`, `docs/`: host utilities, manifests, and guidance.

Put JVM tests in `src/test` and Android/device tests in `src/androidTest`. Never add generated `build/` output or the 3.66 GB model binary to Git.

## Build, Test, and Development Commands

```bash
source ./scripts/android-env.sh
./scripts/doctor.sh
./scripts/test-host-scripts.sh
./gradlew test lint assembleDebug assembleRelease
ANDROID_SERIAL=DEVICE_SERIAL ./gradlew connectedDebugAndroidTest
```

Use an explicit `ANDROID_SERIAL` with multiple devices. Download and verify models separately with `./scripts/download-model.sh` and `./scripts/verify-model.sh`.

## Coding Style & Naming Conventions

Follow `.editorconfig`: UTF-8, LF, four-space indentation, final newline, and no trailing source whitespace. Use `PascalCase` for types and Compose functions, `camelCase` for functions/properties, and `UPPER_SNAKE_CASE` for constants. Prefer immutable, typed inputs. LiteRT output is untrusted; model proposals must never invoke platform APIs directly.

## Testing Guidelines

Tests use JUnit 4, coroutine test utilities, and AndroidX instrumentation. Name files `*Test.kt` and cover validation, cancellation, replay, expiry, and permission/thermal races. Run affected module tests during development and the full command before review. Android framework behavior requires instrumentation; GPU, memory, thermal, fold, and sustained-decode claims require Fold8 evidence.

## Working on the Physical Fold8

Read this before running anything against the phone. Each item cost real damage or a real scare.

- **`connectedAndroidTest` uninstalls the app**, which deletes its data — including the imported
  3.66 GB model, which then needs a USB push and a re-import. `gradle.properties` sets
  `android.injected.androidTest.leaveApksInstalledAfterRun=true` to prevent that. Do not remove it.
- **Some tests are `assumeTrue(isEmulator)`-guarded and must stay that way.** Alarm creation cleans
  up with `pm clear` on the clock app, and the credential tests clear the real vault, which would
  irrecoverably delete the owner's NAVER keys. On the Fold8 that is 13 intentional skips.
- **Debug and release builds cannot replace each other**; the keys differ. Switching costs an
  uninstall and a 3.66 GB model re-import, so treat it as a deliberate migration.
- **Do not delete calendar events you did not create.** Query first, and leave anything whose
  origin you cannot establish from this app's diagnostics.
- `adb` cannot type Hangul: `input text` refuses non-ASCII, `input keyevent` bypasses the IME's
  Hangul composer, and there is no `cmd clipboard`. Drive the ViewModel from an instrumentation
  test instead — see `KoreanToolSelectionTest`.
- Diagnostics under `no_backup/diagnostics/diagnostics.jsonl` are the authority on what a turn
  actually did. Read them before claiming a gate failed.
- **Owner photo and voice acceptance has not run on the phone.** A 2026-09-01 historical Fold8
  session did run a synthetic-card image through LiteRT-LM and observed the audio front end, but
  its shared-conversation token comparison was invalid. The corrected gate uses a fresh runtime and
  one turn per control/media observation, plus a 32-token decode cap below the required deltas; its
  source compiles but has not run on the Fold8. The fp32 encoders add substantial prefill and force
  CPU fallback there, so watch thermal and PSS. Camera capture necessarily uses one temporary
  app-cache file and makes best-effort deletion after read, with stale-start and next-prepare sweeps;
  audio stays in memory. Raw payload never enters Room, transfer, or diagnostics. Room/transfer keep
  only an app-authored kind/source/whole-second summary, and diagnostics keep content-free counts.

## Project Status & Planning

Read `docs/HANDOFF.md` first if you are picking this up cold, then `docs/PROJECT_STATUS.md`
before changing scope. Keep “implemented,” “emulator verified,” and “physical-device accepted” distinct. Update that document when a milestone or provider assumption changes; never infer NAVER integration from a local test calendar.

## Commit & Pull Request Guidelines

Use concise imperative subjects such as `Add durable action ledger...` and keep commits focused. Pull requests should include a summary, risk/privacy impact, commands run, UI screenshots when relevant, and bounded diagnostics for device/runtime changes.

## Security & Configuration

Do not commit secrets, signing keys, `local.properties`, reports, or model artifacts. Preserve dependency locks and verification metadata when changing dependencies. Keep `automaticToolCalling=false`; real writes require durable idempotency, explicit confirmation, and an execution-time safety interlock. Do not weaken security or thermal gates merely to make a test pass.
