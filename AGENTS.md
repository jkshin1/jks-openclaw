# Repository Guidelines

## Project Structure & Module Organization

This Kotlin/Gradle project targets Android 17/API 37 on arm64 devices.

- `app/`: Compose UI, application wiring, permissions, and lifecycle policy.
- `core/agent/`: model-turn controller, strict Tool-call parsing, and orchestration.
- `core/llm/`: LiteRT-LM runtime plus verified model import and storage.
- `core/tools/`: Tool contracts, confirmation/interlock policy, ledgers, and provider gateways.
- `core/data/`: Room, DataStore, conversation persistence, and secret-vault abstractions.
- `core/diagnostics/`: bounded, content-free JSONL diagnostics.
- `scripts/`, `models/`, `docs/`: host utilities, model manifests, and operational guidance.

Put JVM tests in `src/test` and Android/device tests in `src/androidTest`. Never add generated `build/` output or the 3.66 GB model binary to Git.

## Build, Test, and Development Commands

```bash
source ./scripts/android-env.sh             # Configure JDK 17 and Android SDK paths
./scripts/doctor.sh                         # Validate SDK, build tools, and AVD setup
./scripts/test-host-scripts.sh              # Exercise host-side model/device scripts
./gradlew test lint assembleDebug assembleRelease
ANDROID_SERIAL=DEVICE_SERIAL ./gradlew :core:llm:connectedDebugAndroidTest
```

Use an explicit `ANDROID_SERIAL` with multiple devices. Download and verify models separately with `./scripts/download-model.sh` and `./scripts/verify-model.sh`.

## Coding Style & Naming Conventions

Follow `.editorconfig`: UTF-8, LF, four-space indentation, final newline, and no trailing source whitespace. Use `PascalCase` for types and Compose functions, `camelCase` for functions/properties, and `UPPER_SNAKE_CASE` for constants. Prefer immutable, typed inputs over maps or `Any`. LiteRT output is untrusted; model proposals must never invoke platform APIs directly.

## Testing Guidelines

Tests use JUnit 4, Kotlin coroutines test utilities, and AndroidX instrumentation. Name files `*Test.kt` and add regression tests for validation, cancellation, replay/idempotency, expiry, and permission/thermal races. Run the affected module tests during development and the full command above before review. Native LiteRT, Room, file-permission, and Android framework behavior require instrumentation; GPU, memory, thermal, fold, and sustained-decode claims require Fold8 evidence.

## Commit & Pull Request Guidelines

Existing commits use concise imperative subjects such as `Add durable action ledger...`; follow that form and keep each commit to one logical change. Pull requests should include a summary, risk/privacy impact, commands run, and linked issue when available. Add Compose screenshots for UI changes and a bounded diagnostics receipt for device/runtime changes.

## Security & Configuration

Do not commit secrets, signing keys, `local.properties`, reports, or model artifacts. Preserve dependency locks and verification metadata when changing dependencies. Keep `automaticToolCalling=false`; real writes require durable idempotency, explicit confirmation, and an execution-time safety interlock. Do not weaken security or thermal gates merely to make a test pass.
