# Personal Edge Agent

Galaxy Z Fold8를 우선 대상으로 하는 on-device personal AI agent Android 프로젝트입니다.

현재 단계는 **첫 보안 수직 슬라이스**입니다. 고정된 Gemma 4 E4B 모델을 앱 전용
저장소에 검증 설치하고, 실제 LiteRT-LM 텍스트 스트리밍과 수동 Tool 확인 흐름을
실행할 수 있습니다. 외부 서비스에 실제 메시지를 보내는 Tool은 아직 포함하지 않습니다.

## Baseline

- Kotlin + Jetpack Compose
- Android 17: `compileSdk 37`, `targetSdk 37`, `minSdk 31`
- LiteRT-LM `0.16.1` + Gemma 4 E4B candidate
- AGP `9.3.1`, Gradle `9.5.0`, Java toolchain 17
- Locked Gradle dependency graphs plus strict SHA-256 artifact verification
- Multi-module boundaries: `app`, `core:agent`, `core:llm`, `core:tools`, `core:diagnostics`
- Bounded, privacy-safe on-device diagnostics in the private no-backup directory
- Android thermal guard: debug/release 모두 `NONE`~`SEVERE` 허용, `CRITICAL` 협력 취소, `EMERGENCY+` 즉시 중단
- LiteRT-LM automatic tool calling disabled by policy
- Fake arrival-notice Tool: SDK-normalized arguments → exact Kotlin field/type/limit
  validation → confirmation → simulated execution → minimal trusted result reinjection

## Start

```bash
source ./scripts/android-env.sh
export PERSONAL_EDGE_AVD_NAME=personal_edge_api37_model
./scripts/create-foldable-avd.sh
./scripts/doctor.sh
./scripts/test-host-scripts.sh
./scripts/download-model.sh
./scripts/verify-model.sh
./gradlew test lint assembleDebug assembleRelease
# Start the prepared AVD, then run the device test from a second shell.
emulator -avd "$PERSONAL_EDGE_AVD_NAME"
ANDROID_SERIAL=emulator-5554 ./gradlew :core:llm:connectedDebugAndroidTest
# On a physical device, use the exact serial from `adb devices -l`.
./scripts/collect-fold8-evidence.sh --serial DEVICE_SERIAL
```

앱에서 `모델 가져오기`를 눌러 검증된 `models/gemma-4-E4B-it.litertlm`을 선택한 뒤
`CPU로 로드` 또는 `GPU 시도`를 선택합니다. 모델은 APK에 포함되지 않으며, 선택한
파일은 고정 revision/size/SHA-256을 통과해야만 LiteRT 런타임에 전달됩니다.

설계 검토와 보안/MVP 결정은 [docs/ARCHITECTURE_REVIEW.md](docs/ARCHITECTURE_REVIEW.md),
환경 구성과 실기기 확인 절차는 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md), 실제 고정
모델의 첫 실행 근거는 [docs/REAL_MODEL_SMOKE.md](docs/REAL_MODEL_SMOKE.md), 로컬 진단 로그와
수집 절차는 [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md)를 참고하세요.
LiteRT-LM이 Tool 인자를 raw JSON이 아닌 Map으로 노출하므로, 앱은 정규화 이후의
필드·타입·중첩·크기를 엄격히 검사하지만 원문의 duplicate-key 부재는 증명하지 않습니다.
