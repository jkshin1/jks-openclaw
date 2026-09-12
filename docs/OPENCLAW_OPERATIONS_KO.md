# Telegram 운영 점검·백업·첨부 검증

현재 `personaledge` Mac 배포용 명령입니다. 기본 GPT-6 Astra/high, ChatGPT OAuth,
owner allowlist와 기존 대화는 그대로 유지합니다. 구형 Android relay 명령에는
아래의 `--telegram`을 반드시 붙이세요.

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
네 가지 패치의 실제 파일·해시 영수증, runtime/Node 버전, CLI wrapper, LaunchAgent,
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
