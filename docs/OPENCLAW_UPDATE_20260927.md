# OpenClaw 2026.9.6 업그레이드

2026-09-27 KST. 현재 Mac/Telegram 배포에 대한 기록입니다. 이전 업그레이드는
[2026.9.3 기록](OPENCLAW_UPDATE_20260909.md)에 있습니다.

## 결과

- OpenClaw 2026.9.3 → **2026.9.6** (`eb377ac`). 공식 Codex 플러그인도 2026.9.6으로 맞췄습니다.
  Node 26.5.0, 설치 경로(`~/.local/openclaw-2026.8.1`)와 설정·대화 상태는 그대로입니다.
- 모델 경로는 바꾸지 않았습니다: main은 `anthropic/claude-opus-5-5`(claude-cli) → `openai/gpt-6-sol`
  → OpenRouter GLM, 전역 기본은 Opus → Sol.
- Gateway 정지 시간: 업그레이드 7분 19초(04:21:07–04:28:26 UTC), 이후 패치 재적용 27초,
  마지막 정합 재시작 16초. 정지 전에는 매번 활성 세션 0개를 세 번 확인했습니다.

업그레이드 전 9.4·9.5·9.6에 열린 upstream 이슈를 우리 설정과 대조했습니다. 9.6은 GPT-6 Sol과
Claude Opus 5.5를 기본 지원하는 대신 출시 직후 회귀가 여럿 열려 있었습니다. 소유자 요청에 따라
9.6으로 올리되, 우리 경로에 걸리는 문제는 격리 시험·패치·실측으로 따로 막았습니다.

## 로컬 패치 구성

| 항목 | 9.3 | 9.6 | 근거 |
| --- | --- | --- | --- |
| GLM 토큰 필드 | 패치 | 패치(이식) | 9.6도 OpenRouter GLM 기본값이 `max_completion_tokens` |
| 메모리 세션 제외 | 패치 | 패치(이식) | 메타데이터 없는 세션 누락이 그대로 존재 |
| Telegram 전송 복구 | 패치 2개 파일 | 패치 3개 파일(이식) | claim이 SQLite 공유 상태 워커의 커널로 이동 |
| claude-cli `Agent` 차단 | — | **새 패치** | [#158626](https://github.com/openclaw/openclaw/issues/158626) 예방 |
| GLM max 추론 | 패치 | 설정 | `compat.supportedReasoningEfforts`가 `/think` 단계와 요청 effort를 결정 |
| Codex 인증 재확인 | 패치 | 제거 | 9.6이 모든 후보에 WHAM 재확인을 먼저 수행 |
| GPT-6 Sol | 패치 | 제거 | manifest·thinking·route·ChatGPT resolver 기본 포함 |

`runtime-patch-specs.json`의 `2026.9.6` 항목에 검토된 원본/적용 후 해시와 대체 근거(`superseded`)를
고정했습니다. 9.3 항목은 그대로 두어 되돌리기 검증에 씁니다.

- **Telegram 전송 복구:** 9.6은 전송 claim을 `delivery-queue-sqlite-namespace.kernel-*.mjs`에서
  워커 스레드로 실행합니다. storage 모듈은 소유자 재시도 표시만 전달하고, 정책 판단은 커널이
  합니다. 오프라인 시험은 패키지를 APFS로 복제해 워커가 패치된 커널을 불러오게 했습니다.
  커널만 원본으로 두면 시험이 실패하는 음성 대조로 이 경로가 실제로 쓰인다는 점도 확인했습니다.
- **`Agent` 차단:** 9.6은 Claude의 기본 백그라운드 에이전트가 도는 동안 답을 채널로 먼저 보내는
  경로를 새로 넣었고, 그 경로에서 Telegram 답 유실이 보고됐습니다. 기본 `--disallowedTools`에
  `Agent`를 더했습니다. 위임 작업은 원래 정책대로 OpenClaw 하위 에이전트(GPT-5.6 Sol)가 맡습니다.
  cron 같은 제한 실행이 인자를 다시 만들 때도 차단이 유지되는지 오프라인에서 확인했습니다.
- **GLM 설정:** `models.providers.openrouter.models[z-ai/glm-5.3-flash].compat.supportedReasoningEfforts`
  = `["none","low","high","max"]`. 9.3 패치가 노출하던 `minimal`·`medium`은 공급자가 광고하지 않는
  단계라 빠졌습니다. 기본은 계속 `high`입니다. 검증기는 이 값을 고정 확인합니다.
- **철회한 시도:** browser 도구 인벤토리 누락([#156864](https://github.com/openclaw/openclaw/issues/156864))을
  등록 이름 추가 패치로 고쳐 보았으나 인벤토리가 바뀌지 않아, 원본 바이트로 되돌리고 spec에서
  뺐습니다. 아래 "알려진 경계"를 보세요.

## 사전 작업

- **백업 복구:** 9월 26일 Opus 전환 작업이 남긴 `operations/opus-default-*/observer-before`와
  `smoke-native-claude-session` 디렉터리에 실행 비트가 없어, 공식 `backup create`가 상태를 훑다가
  실패하고 있었습니다(감시기의 `backup-stale` 원인). 디렉터리에만 `u+x`를 돌려주고 새 백업을
  만들었습니다. `VERIFIED`, SQLite 42개, 파일 64,306개, 오프라인 복원 검증 완료, 운영 활성화 없음.
- **격리 시험:** 패치한 9.6 복제본을 새 상태 디렉터리와 운영에서 옮긴 설정으로 포트 18799에서
  띄웠습니다. `sandbox-exec`로 운영 상태·설치·LaunchAgent 경로의 쓰기를 커널에서 막았고,
  Telegram·cron·heartbeat·Codex는 껐습니다. 복원본을 그대로 쓰지 않은 이유는 상태 DB에 운영 경로
  절대 참조(plugin 상태 380건 등)가 있기 때문입니다. 결과는 다음과 같습니다.
  - 운영 설정이 9.6 스키마를 통과했습니다.
  - Opus 턴이 claude-cli로 성공했고, 실제 claude 인자에 `Agent` 차단이 들어갔습니다.
  - 재시작 후에도 Opus가 `available`입니다([#158922](https://github.com/openclaw/openclaw/issues/158922) 미재현).
  - GLM 추론 단계는 off/low/high/max로 나옵니다.
  - `sessions.list` CLI+RPC는 0.56초로, 9.3과 같은 수준입니다.

## 업데이트 과정

공식 updater(`update --tag 2026.9.6 --no-restart`)의 복사본 canary, 마이그레이션 리허설, doctor,
설정 검증은 통과했습니다. 첫 시도는 설치 교체 단계에서 `Package rollback launcher backup changed`로
스스로 중단하고 9.3을 복원했습니다. 9월 9일 설치가 umask 077로 만들어져 런처 심볼릭 링크 mode가
0700인데, 셸의 umask 022로 만든 백업 링크는 0755가 되어 지문(mode 포함)이 달라진 것입니다.
**이 설치는 umask 077에서 업데이트해야 합니다.** 두 번째 시도는 111초 만에 성공했습니다.
Codex 플러그인은 `openclaw-codex-*__openclaw-generation__*` 새 디렉터리에 2026.9.6으로 설치됐고,
doctor는 cron 저장소를 정규화하고 설정 메타에 `utilityModelSeparation`을 기록했습니다.

## 실제 확인한 동작

- 설치된 9.6 원본 해시가 검토된 값과 일치한 뒤 패치 4종(파일 7개)이 적용됐습니다. 추론 요청은 0회였습니다.
- live 검증기: Gateway ready, Telegram polling, 필수 플러그인 8개, 추출기 2개, 패치 바이트, loopback.
- 5분 감시기는 재설치 후 `ok=true`, `issues=[]`입니다.
- 운영 Opus 합성 턴이 claude-cli로 성공했습니다. 운영 claude 프로세스 인자에 `Agent` 차단이 있고,
  모델도 `Agent` 도구가 없다고 답했습니다.
- 운영 Opus 턴에서 읽기 전용 `browser status` 호출이 성공했습니다(Gateway 로그 `browser.request` ✓).
- **소유자 Telegram 메시지(13:45 KST):**
  - Opus는 "Claude 세션 사용 한도(16:50 초기화)"로 1.6초 만에 실패했습니다.
  - Sol로 자동 대체되어 답했고, Telegram이 봇 메시지 456·458을 접수했습니다.
  - 오류 배너([#152121](https://github.com/openclaw/openclaw/issues/152121))나 중단된 작업 텍스트 유출([#154970](https://github.com/openclaw/openclaw/issues/154970))은 로그에 없었습니다.
- 한도 초기화 뒤 17:00 heartbeat는 Opus로 대체 없이 성공했습니다.
- GLM 실측: 합성 세션에서 `/think max`가 저장됐고 `GLM-MAX-OK` 응답을 받았습니다. 도구 사용과 우회는 없었고, 세션은 삭제했습니다.
- Codex bootstrap projection: 9.6 플러그인에서 SOUL 응답 계약 반영·갱신 검사 10개가 통과했습니다(모델 호출 없음).
- 추출기: 한국어 기사 본문, 합성 한국어 2쪽 PDF 텍스트, 이미지 대체, 잘못된 쪽 거부를 확인했습니다.
- 회귀 시험: Python 25개 스위트가 Homebrew Python에서 모두 통과했습니다. macOS 기본 Python 3.9에서는
  파일럿·리포트 스위트 5개가 기존의 `X | None` 문법 때문에 실패합니다(이번 변경과 무관).

## 알려진 경계

- **browser 인벤토리 누락:** 이 배포의 `tools.effective`는 browser를 빼고 보여 주지만, 실제 대화
  턴은 browser를 받고 호출합니다. 같은 도구 설정의 격리 시험에서는 목록에 있었으므로 운영 상태에
  따른 차이로 보입니다. 원인은 확정하지 못했습니다. 검증기는 9.6 spec의 `knownInventoryGaps`에
  기록된 경우에만, browser 플러그인이 로드되어 있고 안내가 `browser-filtered-by-profile` 하나일
  때 이를 허용하고 `browserInventory: known-inventory-gap`으로 표시합니다.
- **MEMORY.md 자동 주입 복구(업그레이드 이전부터의 문제):** 운영 MEMORY.md의 출처 기록이 9월 6일 자동
  기억 인수 시험의 합성 대시보드 세션(`auto-memory-cf6c17364a`) 때문에 `untrusted`로 남아 있었습니다.
  9.3과 9.6 모두 이 경우 새 대화 시작 컨텍스트에서 MEMORY.md를 뺍니다. 출처 판정은 한 방향
  래칫이라, 이후 소유자 대화의 쓰기로도 풀리지 않았습니다. 정식 해제 경로는 파일 삭제 시 해시가
  일치할 때뿐인데, 기록 해시가 현재 파일과 달라 쓸 수 없었습니다.
  - 소유자 요청으로 18:04 KST에 Gateway를 멈추고 이 기록 한 행만 지웠습니다. 삭제 조건은 경로,
    `untrusted`, 합성 세션 키, 당시 해시가 모두 일치하는 행입니다. 원래 행은 영수증 디렉터리의
    `memory-provenance-row-before.txt`에 보관했습니다.
  - 기록이 없으면 workspace 기억은 `agent`로 분류되어 주입됩니다. 도구를 금지한 새 합성 세션이 섹션
    5개 이름을 정확히 답했고, 그 턴에는 도구 호출이 없었습니다.
  - 일일 기억 파일 3개(`memory/2026-09-06·07·12.md`)의 `untrusted` 기록은 과거 날짜라 주입 대상이
    아니고, 출처도 소유자 대화가 아니라 그대로 두었습니다.
  - 네트워크 결과가 섞인 턴이나 소유자가 아닌 발신자의 턴이 MEMORY.md를 쓰면 설계상 다시
    `untrusted`가 됩니다. OpenClaw 자체는 경고 없이 주입을 멈추므로, 5분 감시기가 운영 workspace의
    MEMORY.md·USER.md 출처를 OpenClaw와 같은 키로 조회합니다. `untrusted`이면
    `memory-bootstrap-untrusted` 이슈로 소유자에게 알립니다(Hermes 자동 점검 제외). 기억 내용은
    읽지 않습니다. 감시기와 Hermes의 `telegram-ops-status.py` 두 설치본을 모두 갱신했고, 다음 정기
    회차가 새 검사로 실행됐습니다.
  - 재발하면 원인 턴을 확인한 뒤, 복구할 내용이 신뢰할 수 있을 때만 Gateway를 멈추고 해당 행을
    지웁니다. 조회 키는 `sha256(realpath(workspace)) + ":" + sha256("MEMORY.md")`이고, 테이블은
    `state/openclaw.sqlite`의 `plugin_state_entries`(`core:memory-artifact-provenance`)입니다.
- **Claude 대화 연속성:** 업그레이드로 MCP 구성이 바뀌어 기존 Claude 기본 세션이
  초기화됐습니다(`reset reason=mcp`). 기본 로그인에는 계정 식별자가 없어 이전 대화를 새 Claude 세션에
  다시 넣지 않습니다(`auth-unknown`). workspace 파일과 기억 검색은 그대로입니다.
- **heartbeat 간격:** 9.3과 9.6 모두 30분입니다. 9월 26일 기록의 "1시간"은 사실과 달랐습니다.
  heartbeat도 Opus를 먼저 쓰므로 Claude 한도를 함께 소모합니다.
- **Claude 한도 공유:** Opus는 이 Mac의 Claude Code 로그인을 쓰므로 Claude Code 작업과 한도를
  공유합니다. 오늘 한도 소진 시 대체 경로가 정상 동작했습니다.
- Sol 턴마다 Codex의 "parent-local egress workaround unavailable" 경고가 남지만, 전달은 정상입니다.
- [#157324](https://github.com/openclaw/openclaw/issues/157324)(별칭 라우팅)는 기본 모델을 전체 ID로 쓰는 우리 설정에 해당하지 않습니다.
  [#152121](https://github.com/openclaw/openclaw/issues/152121)과 [#154970](https://github.com/openclaw/openclaw/issues/154970)은 원인이 upstream에서도 확정되지 않아 로컬 패치하지 않았습니다.
  오늘 관찰되지 않았다는 것이 없다는 증명은 아닙니다.

## 되돌리기

- **코드:** `~/.local/openclaw-candidates/rollback-2026.9.3-prefix`(패치된 9.3 설치 전체의 APFS 복제)와
  `rollback-codex-plugin-2026.9.3`을 보관했습니다.
- **상태:** 9.6이 상태를 한 방향으로 이전했으므로 9.3으로 돌아가려면 위 백업
  (`OpenClawBackups/telegram/20260927T040527Z-74866824`)을 복원해야 하고, 그 뒤의 대화·기억 변경은 잃습니다.
- **설정:** 업그레이드 직전 설정은 영수증 디렉터리의 `openclaw.json.before`입니다.
- 패치 재적용이나 다음 업그레이드는 `qualify-runtime-patches.py`로 새 패키지를 먼저 자격 검증한 뒤,
  Gateway를 멈춘 상태에서 umask 077로 `--apply`합니다.

비공개 영수증은 `~/.openclaw-personaledge/operations/upgrade-20260927-2026.9.6/`에 있습니다. 여기에는
격리 시험, 업데이트 보고서, 패치 적용 이력, 검증 결과, GLM 실측, 철회한 browser 패치 영수증이 들어 있습니다.
인증 정보와 로컬 상태는 Git에 포함하지 않습니다.
