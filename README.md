# Personal Edge Agent

Galaxy Z Fold8를 우선 대상으로 하는 on-device personal AI agent Android 프로젝트입니다.

고정된 Gemma 4 E4B 모델을 앱 전용 저장소에 검증 설치하고, LiteRT-LM 텍스트 스트리밍과
확인 게이트를 거친 수동 Tool 루프를 실행합니다. 기기용 닫힌 레지스트리에는 캘린더
조회·등록·수정, 알람 설정·다음 알람 조회, 카카오톡 알림 검색, 이동시간, 웹 검색의
실제 Tool 8개가 등록되어 있습니다.

## Baseline

- Kotlin + Jetpack Compose
- Android 17: `compileSdk 37`, `targetSdk 37`, `minSdk 31`
- LiteRT-LM `0.16.1` + Gemma 4 E4B candidate
- AGP `9.3.1`, Gradle `9.5.0`, Java toolchain 17
- Locked Gradle dependency graphs plus strict SHA-256 artifact verification
- Multi-module boundaries: `app`, `core:agent`, `core:llm`, `core:tools`, `core:diagnostics`
- Bounded, privacy-safe on-device diagnostics in the private no-backup directory, with an
  in-app content-free JSONL export path for debug and signed release builds
- Android thermal guard: debug/release 모두 `NONE`~`SEVERE` 허용, `CRITICAL` 협력 취소, `EMERGENCY+` 즉시 중단
- LiteRT-LM automatic tool calling disabled by policy
- Tool 경로: SDK 정규화 인자 → 엄격한 Kotlin 필드/타입/한계 검증 → 확인 다이얼로그 →
  실행 직전 인터록 재검사 → 영구 ledger 청구 → 실행 → 최소 신뢰 결과 재주입
- `SqliteActionLedger`: `synchronous=FULL` 영구 청구로 프로세스 종료 후에도 중복 실행 차단
- `core:data`: Room 대화/메시지/알림 + DataStore 설정 + Keystore AES-GCM 자격증명 보관소
- 캘린더: 선택한 `CalendarContract` 캘린더 하나로 범위 제한. 설정에서 계정 이름·유형·ID를
  함께 보여 계정 이름만으로 NAVER를 추정하지 않고, 반복 일정 수정은 거부. NAVER Android
  게시·동기화는 여전히 별도 provider qualification gate
- 알람: 표준 `AlarmClock` 인텐트로 1회/반복 알람 생성, `getNextAlarmClock()`로 다음 알람 1건
  조회. 목록·수정·삭제는 공개 API가 없어 제공하지 않음
- 카카오톡 알림 수집: 기본 꺼짐. 시스템 알림 접근 + 앱 설정 두 관문을 모두 통과해야 저장·검색되며,
  카카오톡 메시지 알림만 보관합니다. 보관 기간은 수집 중 정리와 검색/설정 화면 재개 시점에
  다시 적용됩니다. 대화 이력이 아니라 수집된 알림의 로컬 캐시입니다
- 외부 서비스 키: 설정 화면에서 직접 입력받아 Keystore AES-GCM으로 암호화 저장. 저장 후 값을
  되읽어 화면에 보여주는 경로는 없습니다
- 네트워크: 단일 transport가 NAVER 두 호스트로만, HTTPS로만, 리다이렉트 없이 요청합니다.
  이동시간(지오코딩+경로)과 웹 검색은 각각 지속적인 opt-in이 꺼져 있고, 실제 요청마다
  전송할 문자열을 보여 준 뒤 다시 확인합니다. 실제 NAVER 호출은 아직 검증되지 않았습니다
- 대화 연속성: 각 최상위 턴은 새 native conversation을 쓰지만, Room의 제한된 최근 메시지와
  요약을 정화·인용·바이트 제한해 다음 턴에 주입합니다. 사용자 턴은 백그라운드 요약보다 우선합니다

## Start

```bash
source ./scripts/android-env.sh
export PERSONAL_EDGE_AVD_NAME=personal_edge_api37_model
./scripts/create-foldable-avd.sh
./scripts/doctor.sh
./scripts/test-host-scripts.sh
./scripts/download-model.sh
./scripts/verify-model.sh
# New checkout only; this checkout already has an owner key:
# ./scripts/create-release-keystore.sh
./gradlew test lint assembleDebug assembleRelease
./scripts/verify-release-signing.sh
# Start the prepared AVD, then run the device test from a second shell.
emulator -avd "$PERSONAL_EDGE_AVD_NAME"
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
# On a physical device, use the exact serial from `adb devices -l`.
./scripts/collect-fold8-evidence.sh --serial DEVICE_SERIAL
```

앱에서 `모델 가져오기`를 눌러 검증된 `models/gemma-4-E4B-it.litertlm`을 선택한 뒤
`CPU로 로드` 또는 `GPU 시도`를 선택합니다. 캘린더 카드에서 권한을 허용하고 사용할
캘린더를 하나 선택해야 일정 Tool이 동작합니다. 외부 조회는 자격증명과 기능별 opt-in을
별도로 설정해야 하며, 진단 내보내기는 사용자가 선택한 문서에만 씁니다. 모델은 APK에
포함되지 않으며, 선택한 파일은 고정 revision/size/SHA-256을 통과해야만 LiteRT 런타임에
전달됩니다.

다른 도구나 새 세션에서 이어받을 때는 [docs/HANDOFF.md](docs/HANDOFF.md)를 먼저 읽으세요.
현재 완료·검증·남은 범위는 [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md), 캘린더 연동
한계와 안전장치는 [docs/CALENDAR.md](docs/CALENDAR.md), 개인 서명키와 백업
절차는 [docs/RELEASE_AND_BACKUP.md](docs/RELEASE_AND_BACKUP.md),
설계 검토와 보안/MVP 결정은 [docs/ARCHITECTURE_REVIEW.md](docs/ARCHITECTURE_REVIEW.md),
환경 구성과 실기기 확인 절차는 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md), 실제 고정
모델의 첫 실행 근거는 [docs/REAL_MODEL_SMOKE.md](docs/REAL_MODEL_SMOKE.md), 로컬 진단 로그와
수집 절차는 [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md), 알람 Tool의 플랫폼 한계는
[docs/ALARM.md](docs/ALARM.md)를 참고하세요.
LiteRT-LM이 Tool 인자를 raw JSON이 아닌 Map으로 노출하므로, 앱은 정규화 이후의
필드·타입·중첩·크기를 엄격히 검사하지만 원문의 duplicate-key 부재는 증명하지 않습니다.
