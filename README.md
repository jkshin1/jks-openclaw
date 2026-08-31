# Personal Edge Agent

Galaxy Z Fold8를 우선 대상으로 하는 on-device personal AI agent Android 프로젝트입니다.

고정된 Gemma 4 E4B 모델을 앱 전용 저장소에 검증 설치하고, LiteRT-LM 텍스트 스트리밍과
위험 등급별 확인 게이트를 가진 수동 Tool 루프를 실행합니다. 기기용 닫힌 레지스트리에는 캘린더
조회·등록·수정, 알람 설정·다음 알람 조회, 카카오톡 알림 검색, 이동시간, 웹 검색,
현재/오늘 날씨, 장기 기억, 일정 후보, 앱 소유 리마인더, 확인형 카카오톡 공유·알림 답장의 실제 Tool 17개가
등록되어 있습니다.

## Baseline

- Kotlin + Jetpack Compose
- Android 17: `compileSdk 37`, `targetSdk 37`, `minSdk 31`
- LiteRT-LM `0.16.1` + Gemma 4 E4B candidate
- AGP `9.3.1`, Gradle `9.5.0`, Java toolchain 17
- Locked Gradle dependency graphs plus strict SHA-256 artifact verification
- Multi-module boundaries: `app`, `core:agent`, `core:data`, `core:llm`, `core:tools`,
  `core:diagnostics`
- Bounded, privacy-safe on-device diagnostics in the private no-backup directory, with an
  in-app content-free JSONL export path for debug and signed release builds
- Android thermal guard: debug/release 모두 `NONE`~`SEVERE` 허용, `CRITICAL` 협력 취소, `EMERGENCY+` 즉시 중단
- LiteRT-LM automatic tool calling disabled by policy
- 응답 언어: 현재 사용자 요청이 다른 언어를 명시하지 않으면 입력·인용·Tool 결과의 언어와
  관계없이 한국어로 답합니다
- Tool 경로: SDK 정규화 인자 → 엄격한 Kotlin 필드/타입/한계 검증 → 쓰기 작업 확인 →
  실행 직전 인터록 재검사 → 영구 ledger 청구 → 실행 → 최소 신뢰 결과 재주입
- `SqliteActionLedger`: `synchronous=FULL` 영구 청구로 프로세스 종료 후에도 중복 실행 차단
- `core:data`: Room 대화/메시지/유형·유효기간이 있는 장기 기억/리마인더/일정 후보 +
  DataStore 설정 + Keystore AES-GCM 자격증명 보관소
- 캘린더: 명시적으로 고른 여러 read calendar만 조회하고, 한 개의 pinned writable calendar만
  수정합니다. 설정에서 계정 이름·유형·ID를 함께 보여 계정 이름만으로 NAVER를 추정하지 않고,
  반복 일정 수정은 거부합니다. NAVER Android 게시·동기화는 별도 provider qualification gate입니다
- 알람: 표준 `AlarmClock` 인텐트로 1회/반복 알람 생성, `getNextAlarmClock()`로 다음 알람 1건
  조회. 목록·수정·삭제는 공개 API가 없어 제공하지 않음
- 카카오톡 알림 수집: 기본 꺼짐. 시스템 알림 접근 + 앱 설정 두 관문을 모두 통과해야 저장·검색되며,
  카카오톡 메시지 알림만 보관합니다. 보관 기간은 수집 중 정리와 검색/설정 화면 재개 시점에
  다시 적용됩니다. 대화 이력이 아니라 수집된 알림의 로컬 캐시입니다
- 카카오톡 통신: 일반 메시지는 확인 후 카카오톡의 대화방 선택 화면만 열며 앱이 수신자를
  자동 선택하거나 전송 완료를 주장하지 않습니다. 알림 답장은 별도 기본 꺼짐 설정과 매회 확인,
  현재 활성 알림의 정확한 이름·단일 자유입력 답장 액션을 모두 요구합니다
- 외부 서비스 키: 설정 화면에서 직접 입력받아 Keystore AES-GCM으로 암호화 저장. 저장 후 값을
  되읽어 화면에 보여주는 경로는 없습니다
- 네트워크: 단일 transport가 고정된 NAVER Maps·You.com·Tavily 호스트에만 HTTPS로,
  리다이렉트 없이 요청합니다. 이동시간과 웹 검색은 기능별 opt-in이 기본으로 꺼져 있으며,
  사용자가 허용한 뒤에는 별도 확인 모달 없이 조회합니다. 동의·연결 상태는 실행 직전과 각
  실제 HTTP 요청 직전에 다시 검사합니다. 명시적 날씨 요청과 공개 웹 검색은 Kotlin이 읽기 Tool을 직접 실행합니다.
  웹 결과는 질의 관련성을 다시 검사해 최대 2개의 근거만 선별하고, 부고·인사 명단 같은 저신호 결과를 요청하지 않은
  인물 조사에서는 제외합니다. 답변을 먼저 합성한 뒤 앱이 소유한 HTTPS 출처만 붙이며, 합성이 검증되지 않으면 선별
  근거의 제한된 추출 답변으로 되돌아갑니다. 현재 직책자 이름 질문은 로컬 답변 전에 검색하고, 이름-직책 관계가 직접
  없는 헌법·임기 문서는 답으로 사용하지 않습니다. 일정 생성·수정이나 알람 생성처럼 기기 상태를 바꾸는 작업은 계속 확인합니다
- 소유자 동의 수명주기: 이동시간, 웹/날씨, 기억, 일정 후보, 선제 경로, 데일리 브리프의 유효
  상태는 영구 설정과 프로세스 게이트를 모두 만족해야 합니다. 저장 중인 변경은 즉시 닫히며,
  앱 수명주기 scope에서 직렬화된 최신 요청만 저장 성공 후 다시 열 수 있습니다
- 대화 연속성: 각 최상위 턴은 새 native conversation을 쓰지만, Room의 제한된 최근 메시지와
  요약을 정화·인용·바이트 제한해 다음 턴에 주입합니다. trusted 읽기 Tool이 완료된 뒤 답변이
  중단되면 Room의 content-free capsule에서 `읽기 다시 수행`을 선택할 수 있습니다. 이 후속 턴은
  새 읽기를 실제 완료해야 하며 쓰기 Tool은 실행 전에 거부합니다. 쓰기 상태가 불확실하면 재실행
  대신 외부 상태 확인만 안내합니다. 사용자 턴은 백그라운드 요약보다 우선합니다
- 사진·음성 입력: 기본 꺼짐이며, 사진 1장 또는 20초 이내 녹음 1개를 요청에 첨부합니다. 사진 설명,
  문서·영수증 정리, 글자 그대로 추출, 번역, 사진·음성 질문, 음성 요약, 받아쓰기를 지원합니다.
  첨부가 있는 턴에는 Tool 스키마를 전혀 제공하지 않으므로 사진이나 음성에 담긴 지시는 실행되지
  않고 관찰 대상으로만 다뤄집니다. 음성 명령은 받아쓰기로 입력창에 옮긴 뒤 사용자가 확인하고
  보내는 일반 텍스트 턴에서만 Tool에 도달합니다. 녹음은 메모리에서만 처리하고, 카메라 촬영본은
  외부 카메라 앱과 연동하는 동안 앱 전용 cache에 임시 저장한 뒤 읽기 직후 삭제를 시도합니다.
  중단된 오래된 촬영 파일은 다음 앱 시작에, 모든 잔여 파일은 다음 촬영 준비에 다시 정리합니다.
  미디어 payload는 Room·데이터 이전 파일·진단에 들어가지 않고, 대화와 데이터 이전 파일에는 종류·출처·초 단위만
  담은 표시가 남습니다. 사진은 재인코딩으로 GPS를 포함한 EXIF를 제거하고, CAMERA 권한은 선언하지
  않으며 마이크 권한만 첫 사용 시 요청합니다
- 장기 기억: 기본 꺼짐이며, 안정적인 선호·사실을 직접 추가하거나 모델 제안의 정확한 내용을
  확인한 뒤 저장합니다. 선호·사람·장소·루틴·사실 유형, 유효기간, 재확인, 대체 관계를 보존하며
  lexical overlap이 없는 기억은 주입하지 않습니다. 탐지 가능한 인증·금융정보와 고엔트로피
  후보는 저장 전에 차단합니다
- 리마인더: 모델 없이도 직접 생성·완료·미루기·취소할 수 있고, Room을 원본으로 정확 알람 또는
  WorkManager fallback을 예약합니다. 재부팅·시각·시간대·앱 업데이트 후 재조정하며 모든 쓰기는 확인합니다
- 카카오 일정 후보: 별도 기본-off 동의가 켜진 경우에만 로컬 규칙 또는 확인된 Tool이 검토함에
  후보를 저장합니다. 알림 원문에서 일정이나 리마인더를 자동 생성하지 않습니다
- 사용자 데이터 이전: 선택 항목 미리보기 후 passphrase 기반 AES-GCM 파일로 대화·기억·리마인더·
  후보만 내보냅니다. 자격증명, Action Ledger, 카카오 알림 원문, 캘린더 provider ID는 제외합니다
- Fold 정보구조: 커버 화면은 모델 없는 오늘 요약과 원탭 완료·미루기, 펼친 화면은 오늘 타임라인과
  대화를 나란히 표시합니다. WindowManager posture가 Book/Tabletop hinge를 우선하며, DeX/freeform
  resize, `Ctrl/Cmd+K`, `Ctrl/Cmd+N`, `Esc`, 명시적 전송 전 text-only drop을 지원합니다

## Start

```bash
source ./scripts/android-env.sh
export PERSONAL_EDGE_AVD_NAME=personal_edge_api37_foldable
./scripts/create-foldable-avd.sh
./scripts/doctor.sh
./scripts/test-host-scripts.sh
./scripts/download-model.sh
./scripts/verify-model.sh
# New checkout only; this checkout already has an owner key:
# ./scripts/create-release-keystore.sh
./gradlew test lint assembleDebug
./gradlew --offline releaseGate
# Start the account-free AVD without persisting test mutations, then use its exact serial.
emulator -avd "$PERSONAL_EDGE_AVD_NAME" -read-only -no-snapshot-load -no-snapshot-save
./scripts/run-avd-regression.sh --serial emulator-5554 --confirm-disposable
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
절차는 [docs/RELEASE_AND_BACKUP.md](docs/RELEASE_AND_BACKUP.md), 암호화 사용자 데이터 이전은
[docs/DATA_TRANSFER.md](docs/DATA_TRANSFER.md), 리마인더 계약은
[docs/REMINDERS.md](docs/REMINDERS.md), 모델 비교 절차는
[docs/MODEL_EVALUATION.md](docs/MODEL_EVALUATION.md),
첨부 개선 보고서의 구현/보류 대조표는
[docs/DEEP_RESEARCH_IMPLEMENTATION.md](docs/DEEP_RESEARCH_IMPLEMENTATION.md), 중단 턴의
재실행 금지 계약은 [docs/TURN_RECOVERY.md](docs/TURN_RECOVERY.md), 릴리스 SBOM·provenance 계약은
[docs/RELEASE_PROVENANCE.md](docs/RELEASE_PROVENANCE.md), 기본-off 검토 제안 엔진은
[docs/PROACTIVE_OPPORTUNITY_ENGINE.md](docs/PROACTIVE_OPPORTUNITY_ENGINE.md),
설계 검토와 보안/MVP 결정은 [docs/ARCHITECTURE_REVIEW.md](docs/ARCHITECTURE_REVIEW.md),
환경 구성과 실기기 확인 절차는 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md), 실제 고정
모델의 첫 실행 근거는 [docs/REAL_MODEL_SMOKE.md](docs/REAL_MODEL_SMOKE.md), 로컬 진단 로그와
수집 절차는 [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md), 알람 Tool의 플랫폼 한계는
[docs/ALARM.md](docs/ALARM.md)를 참고하세요.
LiteRT-LM이 Tool 인자를 raw JSON이 아닌 Map으로 노출하므로, 앱은 정규화 이후의
필드·타입·중첩·크기를 엄격히 검사하지만 원문의 duplicate-key 부재는 증명하지 않습니다.
