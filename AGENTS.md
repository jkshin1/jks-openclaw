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

## Project Status & Planning

Read `docs/PROJECT_STATUS.md` before changing scope. Keep “implemented,” “emulator verified,” and “physical-device accepted” distinct. Update that document when a milestone or provider assumption changes; never infer NAVER integration from a local test calendar.

## Commit & Pull Request Guidelines

Use concise imperative subjects such as `Add durable action ledger...` and keep commits focused. Pull requests should include a summary, risk/privacy impact, commands run, UI screenshots when relevant, and bounded diagnostics for device/runtime changes.

## Security & Configuration

Do not commit secrets, signing keys, `local.properties`, reports, or model artifacts. Preserve dependency locks and verification metadata when changing dependencies. Keep `automaticToolCalling=false`; real writes require durable idempotency, explicit confirmation, and an execution-time safety interlock. Do not weaken security or thermal gates merely to make a test pass.
