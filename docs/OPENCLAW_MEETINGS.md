# 녹음에서 회의록과 자막 만들기

`telegram-meeting.py`는 사용자가 명시적으로 지정한 녹음 한 개를 처리합니다. 원본은 수정하지 않고,
비공개 작업 폴더에 같은 해시의 사본을 만든 뒤 한국어 전사, 근거가 있는 회의록 초안, 자막을 생성합니다.
사용자 폴더를 탐색해서 녹음을 선택하거나 Telegram 메시지를 임의로 읽지 않습니다.

## Telegram에서 요청하기

녹음을 첨부하고 다음처럼 요청합니다.

> 이 녹음을 회의록과 자막으로 만들어줘. 결정 사항, 미확정 사항, 후속 업무를 나누고 담당자와 기한은
> 녹음에 나온 내용만 적어줘. 원본은 보존하고 Word, PDF, 전사, 자막 ZIP을 여기로 보내줘.

전사와 요약은 분리됩니다. Whisper `small`은 Mac에서 로컬 실행하고, 기본 요약은 기존
`summarize-openclaw.py`의 incognito Sol/low ChatGPT OAuth 경로를 사용합니다. 운영 로그나 다른 대화는
요약 입력에 포함하지 않습니다. API 키 경로나 다른 유료 공급자로 자동 전환하지 않습니다.

## 생성되는 파일과 근거

| 파일 | 내용 |
| --- | --- |
| `meeting-minutes.docx` | 결정 사항, 미확정 사항, 후속 업무, 원문 인용과 시간 근거 |
| `meeting-minutes.pdf` | Word 문서를 설치된 LibreOffice로 렌더한 읽기용 문서 |
| `transcript.txt` | 전체 한국어 전사, 시작·종료 시간과 세그먼트 번호 |
| `transcript.json` | 기계 검증용 전사와 타임스탬프 |
| `subtitles.srt` | 원본 자막 |
| `subtitles.zip` | 원본 SRT 한 개만 담은 Telegram 전송용 ZIP |

회의록은 근거를 추적할 수 있는 **초안**입니다. 요약 항목의 문장은 전사 세그먼트에 실제 존재해야 합니다.
결정 사항에는 명시적인 확정 근거가 있어야 하며, 미정·보류 표현을 결정으로 바꿀 수 없습니다.
담당자와 기한은 원문에 붙어 있는 표현만 허용합니다. 확인하지 못하면 그 상태를 표시합니다.
화자 식별을 수행하지 않으며, 금요일·다음 주 같은 상대 날짜를 추정한 달력 날짜로 바꾸지 않습니다.
자동 전사 자체의 인식 오류, 소음, 겹치는 발언은 원본 녹음과 대조해야 합니다.

## 운영 명령

설치 위치는 `~/.local/share/openclaw-telegram-workflows/`입니다. 아래 예시는 사용자가 지정한 녹음 경로를
사용합니다. `prepare`는 기존 폴더를 덮어쓰지 않으며, `--run-dir`을 생략하면
`~/.openclaw-personaledge/operations/workflows/meetings/meeting-<id>/`를 생성합니다.

```bash
openclaw-python ~/.local/share/openclaw-telegram-workflows/telegram-meeting.py prepare \
  --audio /absolute/path/to/recording.m4a --title '프로젝트 회의록 초안'

openclaw-python ~/.local/share/openclaw-telegram-workflows/telegram-meeting.py resume \
  --run-dir /absolute/path/to/meeting-run

openclaw-python ~/.local/share/openclaw-telegram-workflows/telegram-meeting.py verify \
  --run-dir /absolute/path/to/meeting-run
```

외부 요약 모델 없이 제한적인 원문 분류만 원할 때는 시작 시 `--summary-mode extractive`를 명시합니다.
이 모드는 규칙으로 확정·미정·업무 표현을 찾고, 분명한 문장 구조에서만 담당자와 기한을 추출합니다.
자동 장애 대체 경로가 아니며, 영수증에 `extractive`라고 구분합니다. 복잡한 대화를 이해하는 모델 요약과
같은 품질을 보장하지 않습니다.

기본 한도는 파일 256 MiB, 녹음 1시간입니다. `--max-duration`으로 최대 4시간까지 명시할 수 있습니다.
긴 녹음은 10분 단위로 로컬 전사하고 완료한 청크의 해시를 남깁니다. 요약도 UTF-8 바이트 기준으로
나누어 처리하므로 한 번의 거대한 모델 요청을 만들지 않습니다. 전사 청크는 각각 최대 10분, 요약 요청은
한 번에 최대 약 4분의 실행 제한을 갖습니다. 시간 초과나 모델 실패는 실패 상태로 남으며, `resume`은
해시가 일치하는 완료 단계를 재실행하지 않습니다. 원본이나 완료 산출물이 변경되면 중단합니다.

## 시각 검증과 전달

Word/PDF 생성 후 `render/page-*.png` 전부를 열어 한국어, 원문 인용, 담당자·기한, 타임스탬프,
페이지 잘림을 확인합니다. 점검을 완료한 실행에만 다음과 같이 시각 검증을 기록합니다.

```bash
openclaw-python ~/.local/share/openclaw-telegram-workflows/telegram-meeting.py record-visual-review \
  --run-dir /absolute/path/to/meeting-run --note '모든 페이지의 한국어와 시간 근거, 업무 배정, 잘림 여부 확인'
```

사용자가 결과를 Telegram으로 보내 달라고 명시한 경우에만 `send`를 실행합니다.

```bash
openclaw-python ~/.local/share/openclaw-telegram-workflows/telegram-meeting.py send \
  --run-dir /absolute/path/to/meeting-run
```

전달 대상은 현재 배포 설정의 단일 owner입니다. 검증한 산출물만 기존 media 허용 경로에 복사한 뒤
Word, PDF, 전사 TXT, 자막 ZIP 네 개를 조용히 보냅니다. SRT는 설치된 미디어 형식 검사에서 직접
거부될 수 있어 원본 바이트를 보존하는 ZIP으로 전달하며, 미디어 안전 검사를 완화하지 않습니다.

전송 완료는 종료 코드만으로 판단하지 않습니다. 실제 CLI 응답의 `action=send`, `channel=telegram`,
`dryRun=false`, `payload.ok=true`, `messageId`, owner 수신자 일치를 확인합니다. 전달 당시의 파일 해시와
원본 JSON 영수증을 보관합니다. 이미 전달된 같은 해시는 생략합니다. 전송 중 실패나 시간 초과는
`uncertain`으로 남고 자동 재전송하지 않습니다. 진단을 확인하고 실제 사전 거부인지, 전송됐지만 응답만
유실됐는지를 운영자가 구분해야 합니다.

## 작업 조회와 검증 범위

작업은 `telegram-task-status.py`의 `publish_workflow`로 등록됩니다. 작업 실행, 검증, 산출물, 전달은
각각 다른 근거를 사용합니다. 문서를 생성해도 시각 검증 전에는 검증 완료로 표시하지 않고,
전달을 요청하지 않았거나 실제 영수증이 없으면 Telegram 전달 완료로 표시하지 않습니다.
주 영수증은 `receipt.json`, 단계별 진단은 비공개 `diagnostics/`, 전달 근거는 `delivery/`에 있습니다.

`ownerUploadVerified=false`는 이 호스트 작업이 Telegram의 실제 owner 업로드 이벤트를 별도로
검증하지 않았다는 뜻입니다. `phoneOpenVerified=false`는 사용자의 휴대폰에서 파일을 열어 본
증거가 없다는 뜻입니다. 처리 및 서버 전송 성공을 이 두 결과와 혼동하지 않습니다.

개발 시험은 다음 명령으로 실행합니다.

```bash
python3 scripts/openclaw/test-telegram-meeting.py
```

합성 녹음 통합 시험에서는 `--no-status-index`를 사용해 실제 운영 작업 목록을 오염시키지 않습니다.
이 옵션은 모델 선택이나 전달 승인 조건을 바꾸지 않습니다. 설치 시 같은 폴더에
`telegram-productivity-acceptance.py`(공통 비공개 영수증·타임아웃·전달 보조 코드)와
`telegram-task-status.py`를 포함해야 합니다. `productivity-fixtures.py`는 회의록 작업의 실행 의존성이 아닙니다.

## 검증 기록 2026-09-09

- 오프라인 회귀 시험 21개가 통과했습니다. 출처에 없는 인용·화자·담당자·기한, 미확정 사항의 결정 분류,
  완료·취소한 업무의 후속 업무 분류, 타임스탬프 오류, 원본·완료 산출물 변경, 불확실 전송 자동 재시도를
  거부하는 경우를 포함합니다. 실제 task-status 모듈과 연결해 실행·검증·전달 상태 분리도 확인했습니다.
- 직접 만든 도서관 회의 합성 녹음 17.36초를 설치된 Whisper `small`로 전사하고 로컬 분류 및
  기존 Sol/low ChatGPT OAuth 요약 경로를 각각 실행했습니다. 실제 사용자 녹음이나 운영 로그는
  이 시험의 모델 입력에 포함하지 않았습니다.
- OAuth 결과는 확정 1건, 미확정 1건, 후속 업무 2건입니다. 민수·금요일은 원문 근거와 일치하며,
  다음 회의에서 검토한다는 항목은 담당자·마감기한을 추정하지 않았습니다.
- 근거 조건을 만족하지 못한 첫 모델 응답은 검증 단계에서 거부됐습니다. 조건을 명확히 한 프롬프트로
  `resume`한 뒤 통과했으며, probe·전사 실행 횟수는 각 1회, 요약은 2회로 체크포인트 재사용을 확인했습니다.
- 원본 해시, 인용 및 시간 근거, DOCX와 PDF 텍스트, SRT ZIP 원본 바이트가 통과했고, 생성된 PDF
  1페이지를 직접 열어 한국어와 업무 배정, 잘림·겹침 여부를 확인했습니다.
- 최초 생성 시험은 전송 없이 수행했습니다. 이후 동일한 검증 산출물의 Word·PDF·전사·자막 ZIP을
  운영 설치본으로 전송했고 Telegram 접수 영수증 `143`–`146`을 확인했습니다. 최종 진행 조회도
  수행·검증·파일·전송의 네 단계를 완료로 표시했습니다. 실제 owner 업로드 이벤트와 휴대폰 열람은
  이 시험의 검증 범위가 아닙니다.
