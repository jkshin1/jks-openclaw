# Telegram 운영 점검·백업·첨부 검증

현재 `personaledge` Mac 배포용 명령입니다. owner allowlist와 기존 대화는 유지합니다.
구형 Android relay 명령과 구분하려면 아래의 `--telegram`을 반드시 붙이세요.

**현재 모델 경로 (2026-09-28 기준, OpenClaw 2026.9.6)**

- main 대화: Claude Opus 5.5(`claude-cli`, Claude Code 구독 로그인) → GPT-6 Sol(Codex ChatGPT
  OAuth) → OpenRouter GLM. 전역 기본값은 Opus → Sol.
- 요약기(`summarize-openclaw.py`, 주간 브리핑·회의록): GPT-5.6 Sol → 사용 한도 시 도구 없는
  Opus → 그것도 한도면 도구 없는 GLM. 자세한 조건은 [주간 브리핑 문서](OPENCLAW_WEEKLY_BRIEFING.md).
- Hermes 운영 검토: 별도 OAuth의 GPT-5.6 Sol/high.

아래의 날짜별 절은 당시 기록이며 현재 기본값이 아닙니다.

## Claude Opus 5.5 전환 (2026-09-26)

main 에이전트 기본 체인은 `anthropic/claude-opus-5-5` → `openai/gpt-6-sol` → OpenRouter GLM,
전역 기본값은 Opus → Sol입니다. Opus는 이 Mac의 Claude Code 로그인(`claude-cli` 실행기,
Homebrew `claude-code@latest`)으로 Claude 요금제 사용량을 씁니다. Anthropic API 키는 없습니다.
검증 범위와 한계는 [운영 문서](OPENCLAW_TELEGRAM.md#claude-opus-55-main-route-migration-2026-09-26-kst)에
있습니다. 아래 Sol 절은 2026-09-23 당시 기록입니다.

## GPT-6 Sol 전환 확인 범위 (2026-09-23)

당시 전역 기본 경로는 `openai/gpt-6-sol`이고, main 에이전트도 같은 모델을 우선 사용하도록
설정했습니다. main 에이전트의 기존 GLM 단일 fallback은 유지합니다. OpenClaw 2026.9.3의
plugin manifest, thinking policy, model route contract, ChatGPT/Codex resolver 네 파일에
한정된 Sol 호환성 패치를 설치했습니다. 검토된 해시는
`scripts/openclaw/runtime-patch-specs.json`, 설치 영수증은 비공개
`~/.openclaw-personaledge/operations/gpt6-sol-patch.json`에 있습니다. 기존 네 가지
런타임 복구 패치와 별개이며, API 키 과금 경로는 추가하지 않았습니다.
2026-09-27 OpenClaw 2026.9.6은 Sol을 기본 지원하므로 이 패치는 더 이상 설치하지 않습니다.
아래 원복 절차는 2026.9.3 설치에만 해당하며, 현재 기준은 [2026.9.6 기록](OPENCLAW_UPDATE_20260927.md)입니다.

격리된 Gateway `modelRun`은 요청·적용 모델 `openai/gpt-6-sol`, 응답 모델 `gpt-6-sol`,
`rerouted=false`, 성공 도구 0개와 `SOL-READY` 응답을 반환했고 임시 실행 상태를
정리했습니다. 실제 Telegram 대화의 새 모델 응답과 전달은 아직 확인하지 않았습니다.
Codex 할당량 소진이나 새 Sol 경로의 GLM fallback도 실제로 재현하지 않았습니다.
사용량 절감 효과도 비교 측정하지 않았습니다.

2026-09-23 새 백업은 공식 online archive와 별도 경로 복원 검증을 마쳐 `VERIFIED`입니다
(`2026-09-23T07:02:48Z`, SQLite 42개, 파일 64,016개). 복구 자료에 Sol 설치 영수증과
패치된 네 런타임 파일이 포함되고 별도 복원 폴더에서도 확인됐습니다. 운영 상태 조회는
`ok=true`, `issues=[]`를 반환했습니다. 이 백업은 현재 **Sol 설정의 복구본**이며
이전 Astra 상태로의 원복을 뜻하지 않습니다. 백업 영수증의 `productionActivated=false`는
복원본에서 운영 Gateway를 시작하지 않았다는 뜻입니다.

### Astra로 원복할 때

활성 실행이 없음을 확인한 뒤 비공개 상태 백업을 보존하고 Gateway와 감시기를 멈춥니다.
준비된 원본 파일을 사용하는 `patch-gpt6-sol.py --rollback`으로 OpenClaw 2026.9.3의
네 파일을 검토된 이전 해시로 되돌리고, 공식 설정 패치로 전역·main 모델과 별칭·허용
목록을 Astra 정책에 맞춰 함께 복원합니다. main의 기존 GLM fallback과 OAuth 자격 증명은
별도로 검토해 유지합니다. 이에 맞는 `runtime-patch-specs.json`과 엄격 검증기 소스도
같은 버전으로 복원한 뒤 Gateway를 시작해 정적·실시간 검증을 통과시키고, 감시기를
재설치해 첫 검사와 다음 예약 검사를 확인합니다. 마지막으로 격리 Astra 실행과 실제
Telegram 전달을 각각 확인합니다. 이전 설정 파일만 되돌리면 Sol 패치 해시 및 현재
검증기·설치된 감시기의 모델 정책과 맞지 않아 정상 복구로 볼 수 없습니다. 새 Sol
백업의 복원 폴더를 운영 환경에 곧바로 활성화하지 않습니다.

## 운영 상태

Telegram에 **“운영 상태 보여줘”**라고 요청하면 설치된 상태 조회기를 사용합니다.
Mac에서는 다음 명령으로 같은 집계 정보를 볼 수 있습니다.

```bash
scripts/openclaw/status-gateway.sh --telegram
scripts/openclaw/status-gateway.sh --telegram --json
```

전송 대기·실패, 장시간 작업, 야간 예약 실행, 최근 복구 검증 백업과 감시기 검사 시각을
구분합니다. 개인 대화 본문이나 계정 식별자는 출력하지 않습니다. 감시 기록이 오래됐으면
이전 정상 결과를 현재 정상으로 표시하지 않습니다. 야간 작업의 scheduler 성공은
모델이 기억을 실제 승격했다는 뜻과 다릅니다.

2026-09-27부터는 대화 시작 시 주입되는 기억 파일(`MEMORY.md`, `USER.md`)의 출처 기록도
확인합니다. OpenClaw가 이 파일을 `untrusted`로 기록하면 새 대화에서 경고 없이 빼고, 이후
쓰기로도 풀리지 않습니다. 이 경우 `memory-bootstrap-untrusted`("기억 파일 자동 주입 제외")가
이슈로 잡혀 아래 규칙대로 알림이 갑니다. Hermes 자동 점검은 호출하지 않습니다. 검사는 파일 이름과
판정만 읽고 기억 내용이나 세션 정보는 출력하지 않습니다. 해제 절차는
[2026.9.6 기록](OPENCLAW_UPDATE_20260927.md)에 있습니다.

감시기는 5분마다 모델 호출 없이 검사합니다. 연속 3회 실패하면 한 번 알리고,
실패 알림이 전달된 사건이 정상화되면 복구를 한 번 알립니다. 기존 장애가 지속되는 중에
새 장애 항목이 3회 연속 확인되면 추가 알림을 보냅니다. 일시적 전송 실패에는
같은 알림만 재시도하며 원래 사용자 작업을 다시 실행하지 않습니다. Telegram 응답이
유실되면 같은 번호의 알림이 중복될 수 있습니다. 로컬 상태와 전달 결과는
`~/.openclaw-personaledge/operations/telegram-watchdog-status.json`에 따로 남습니다.

감시기 또는 workspace 지침을 배포할 때는 검토된 live `AGENTS.md`를 백업·동기화한 뒤
아래 설치기를 실행합니다. 소스·준비본·설치본의 엄격 검증, 기존 설치 백업, 실패 원복,
설치본 첫 검사와 다음 예약 실행을 확인하세요. Gateway 재시작은 하지 않습니다.

```bash
python3 scripts/openclaw/install-telegram-watchdog.py --apply
```

## 백업과 별도 경로 복구

```bash
scripts/openclaw/backup-gateway.sh --telegram --apply --rehearse
scripts/openclaw/restore-gateway.sh --telegram --archive /절대경로/state.tar.gz --target /새로운/비공개/복구폴더 --apply
```

기본 백업 위치는 `~/Library/Application Support/PersonalEdge/OpenClawBackups/telegram/`입니다.
공식 online backup으로 활성 SQLite의 일관성을 보존하고, full state archive와 함께
기존 런타임 패치와 GPT-6 Sol 호환성 패치의 실제 파일·해시 영수증, runtime/Node 버전,
CLI wrapper, LaunchAgent,
복구 스크립트·정책을 보관합니다. archive 검증과 별도 폴더 복원 후 파일 해시,
SQLite integrity·필수 schema·논리 행 수를 확인해야 `VERIFIED` 영수증이 발행됩니다.

최신 성공 영수증은 `operations/telegram-backup-latest.json`입니다. 이후 백업이 실패해도
이전 검증 성공분을 덮지 않습니다. 복구 명령은 기존 폴더나 운영 state/runtime 경로를
대상으로 받을 수 없고, 복구한 Gateway나 Telegram polling을 시작하지 않습니다.
새 Mac에서의 설치·수동 활성화와 전원 복귀 후 자동 가동은 별도 검증입니다.
백업에는 인증 자료가 포함되므로 생성된 private 권한을 유지하고 Git에 넣지 마세요.

## 문서·미디어 첨부 검증

```bash
openclaw-python scripts/openclaw/telegram-productivity-acceptance.py prepare --run-dir /새로운/비공개/시험폴더
openclaw-python scripts/openclaw/telegram-productivity-acceptance.py verify --run-dir /시험폴더
openclaw-python scripts/openclaw/telegram-productivity-acceptance.py record-visual-review --run-dir /시험폴더 --note '네 페이지의 한글, 표, 머리글, 페이지 번호와 합계를 직접 확인한 내용'
openclaw-python scripts/openclaw/telegram-productivity-acceptance.py send --run-dir /시험폴더
```

시험은 사용자 파일을 검색하지 않고 합성 DOCX·XLSX·음성을 만듭니다. 원본 해시 보존,
지정 문구 수정, Excel 구조·수식·재계산 합계, PDF 한글 추출, Whisper 전사·자막을
검사합니다. `render/`의 Word·Excel 네 페이지를 직접 본 후 시각 검증을 기록해야
전송할 수 있습니다. `send`는 현재 설정의 owner만 대상으로 하며 DOCX·XLSX·PDF 2개·
WAV·TXT·SRT 자막 ZIP 총 7개를 무음 전송합니다. 현재 OpenClaw의 파일 형식 검증은
직접 SRT 첨부를 거부하므로, 원본 `.srt` 바이트를 그대로 담은 ZIP으로 전달합니다.
첨부 경로 제한을 유지하면서 검증 파일만 `STATE/media/productivity-acceptance/`에 복사합니다.

출력 해시와 실제 Telegram API 영수증을 연결하고, 이미 전달된 같은 산출물은 다시 보내지
않습니다. 결과가 불확실한 전송은 실패 원인을 확인하기 전 자동 재시도하지 않습니다.
실패 stdout/stderr는 시험 폴더 `diagnostics/`의 private 파일에 남기고 대화에는 출력하지 않습니다.
로컬 처리·렌더링, Telegram API 수락, 실제 owner 업로드 수신, 휴대폰 다운로드·열람은
서로 다른 확인 항목입니다. 봇이 보낸 시험 파일로 owner 업로드를 증명하지 않으며,
운영 polling과 동시에 `getUpdates`를 호출하지 않습니다.

## 회귀 검사

```bash
python3 scripts/openclaw/test-telegram-gateway.py
python3 scripts/openclaw/test-telegram-operations.py
python3 scripts/openclaw/test-telegram-backup.py
python3 scripts/openclaw/test-telegram-productivity-acceptance.py
scripts/openclaw/verify-gateway.sh --telegram --json
```

격리된 실패·복구·수신자·변조·원복 시험을 먼저 수행하고, 운영 환경에서는 실제 설치본,
자연스러운 예약 실행, 별도 복원과 합성 첨부 전송 영수증을 확인합니다. 운영 장애를
일으키거나 실제 사용자 파일을 변경해서 시험하지 않습니다.
