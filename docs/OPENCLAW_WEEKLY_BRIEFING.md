# 토요일 AI·LLM 주간 기술 브리핑

이 workflow는 매주 토요일 오전 9시, `Asia/Seoul` 기준으로 승인된 공개 AI·LLM 출처를
확인하고 중요한 변경을 최대 10개까지 한국어로 Telegram 소유자에게 전달합니다. 실제 예약
등록은 OpenClaw의 명령 실행 예약에서 이 wrapper를 호출하도록 설정합니다.

2026-09-09에 예약 `5d5192d8-c053-4084-bc4e-40a51da74d7e`를 실제 등록하고 활성 상태와
다음 실행 **2026-09-12 09:00 KST**를 확인했습니다. 최초 출처는
[OpenAI 뉴스](https://openai.com/news/rss.xml), [Google DeepMind](https://deepmind.google/blog/rss.xml),
[Anthropic 뉴스](https://www.anthropic.com/news)와 [연구](https://www.anthropic.com/research),
[Hugging Face 블로그](https://huggingface.co/blog/feed.xml), [Mistral 뉴스](https://mistral.ai/news)입니다.
여섯 출처의 첫 기준점 수집은 모두 성공했으며, 실제 예약 실행과 전송은 해당 시각의 기록으로 확인합니다.

`telegram-briefing.py`는 공개 자료 수집과 이전 성공 수집 대비 차이를 담당하고,
`telegram-weekly-briefing.py`는 중요도 요약, 전송 대기 상태, 영수증, 전달 후 기준점 반영을
담당합니다. 최초 기준점과 변경 없는 회차는 모델 호출·Telegram 메시지 없이 끝납니다.
단어 규칙으로 고른 후보에 실제 중요한 변경이 없다고 요약기가 판단한 경우도 조용히
검증 기록과 기준점만 남깁니다.

## 명령

설치 위치는 `~/.local/share/openclaw-telegram-workflows/`이고 기본 주제 ID는 `ai-llm`입니다.
다음 명령은 이미 승인된 소유자 Telegram 전송을 수행합니다.

```bash
/usr/bin/python3 ~/.local/share/openclaw-telegram-workflows/telegram-weekly-briefing.py \
  --run --topic ai-llm
```

정기 예약은 이 명령을 실행합니다. 요약문은 wrapper가 직접 전송하므로 예약의 stdout이나
실행 결과를 별도의 Telegram 알림으로 중복 전달하지 않도록 설정합니다. 최초 실행을
기준점 생성으로 사용할 수 있습니다.

전송 없이 실제 공개 자료 수집·요약까지만 준비하려면 다음 명령을 사용합니다. 새 변경분의
기준점은 전달 전까지 유지되고, 전송 대기 결과의 디렉터리가 반환됩니다.

```bash
/usr/bin/python3 ~/.local/share/openclaw-telegram-workflows/telegram-weekly-briefing.py \
  --prepare-only --topic ai-llm
```

준비된 결과를 보내거나 완료 여부만 재확인하려면 반환된 정확한 실행 디렉터리를 지정합니다.

```bash
/usr/bin/python3 ~/.local/share/openclaw-telegram-workflows/telegram-weekly-briefing.py \
  --deliver-existing /absolute/private/run/directory --topic ai-llm
```

`--state-dir`는 별도 시험 프로필을 선택합니다. 시험에서는 합성 입력, 가짜 요약기·전송기를
쓰는 오프라인 테스트가 기본이며, 실제 공개 자료 요약 시험과 Telegram 전송 시험은 각각
별도 증거입니다.

## 요약과 출처

- 기존 `~/.local/share/openclaw-skill-tools/summarize-openclaw.py`를 통해 새 incognito 세션의
  `openai/gpt-5.6-sol`, 추론 `low` 경로를 사용합니다. 기존 helper는 도구 미사용, 요청·실효
  모델 경로 일치, 종료 영수증을 확인합니다. 추가 API 키 경로를 만들지 않습니다.
- 모델 입력은 설정된 **공개 출처에서 추가된 텍스트와 공개 출처 메타데이터**로만 만듭니다.
  운영 로그, 소유자 ID, 대화 기록, 인증 정보, 로컬 파일 경로를 넣지 않습니다.
- RSS/Atom의 새 항목은 제목·본문·원문 URL·게시 시각으로 분리합니다. 원문 URL은 실제
  관측한 항목에서 추출하고 공개 URL 정책을 통과해야 합니다. 원문 링크를 복원할 수 없는
  HTML 자료는 수집한 목록 URL을 사용하고 “목록 페이지”라고 표시합니다.
- 중요 신호와 게시 시각을 사용해 최대 30개 후보를 선택하고, 모델 입력은 90KB 이하로
  제한합니다. 누락·잘림이 있으면 전달문에도 이를 명시합니다.
- 결과는 중요도순 최대 10개이며 각각 **핵심 내용, 왜 중요한지, 불확실성, 원문 출처**를
  포함합니다. 한국어 필드 길이와 출처 ID를 검증하고, 모델이 만든 임의 URL은 사용하지
  않습니다. 회사 발표와 논문의 주장에 대한 독립 검증 여부도 구분하도록 요청합니다.
- 이 구조·출처 검증이 요약 내용의 모든 사실이나 기술적 영향력을 독립적으로 검증했다는
  뜻은 아닙니다. 원문을 제공해 사용자가 주장과 제한을 확인할 수 있게 합니다.

일부 출처가 실패하면 확인된 자료만 요약하고 실패 개수·출처를 메시지에 표시합니다.
전부 실패하면 모델을 호출하지 않고 수집 실패 사실만 알립니다. 실패한 출처를 “변경 없음”으로
처리하거나 과거 성공 기준점을 덮어쓰지 않습니다. 같은 장애가 계속되면 동일 알림을
반복하지 않으며, 출처가 복구된 뒤 다시 실패하면 새 장애로 알립니다.

## 중단과 전송 중복 방지

실행 상태는 다음 비공개 위치에 보존합니다.

```text
~/.openclaw-personaledge/operations/workflows/weekly-briefing/ai-llm/
  active.json
  delivered.json
  source-failure-episode.json
  runs/weekly-<id>/
    run.json
    summary.json
    briefing.txt
    telegram-stdout.json
    telegram-stderr.log
    telegram-send.json
```

수집 전에 실행 ID와 예정된 공개 자료 관측 경로를 기록합니다. 공개 수집은 `peek=True`로
수행하므로 요약·전송이 실패해도 비교 기준점은 갱신되지 않습니다. 다음 실행은 기존의
완료되지 않은 관측·요약·전송 대기 파일을 먼저 처리하며, 그 작업을 두고 새 자료를 수집하지
않습니다. 검증된 `summary.json`이 있으면 모델을 다시 호출하지 않습니다.

전송 직전에 메시지 해시와 `sending` 의도를 영속화합니다. 성공은 원본 CLI JSON의
`action=send`, `channel=telegram`, `dryRun=false`, `payload.ok=true`, 현재 소유자와 일치하는
`chatId`, 양의 `messageId`를 모두 확인해야 인정합니다. 이 영수증과 전달 기록을 저장한 뒤
성공적으로 수집한 출처의 기준점을 반영합니다.

- 전송 전에 CLI 프로세스가 시작되지 않은 경우는 동일 메시지를 다시 시도할 수 있습니다.
- 시작된 전송의 시간 초과·실패·불확실한 응답은 `delivery-unknown`으로 남기고 자동 재전송하지
  않습니다. 확정된 원본 영수증이 해당 실행의 저장된 stdout에 남아 있으면 재전송 없이 복구합니다.
- 원본 영수증을 별도로 확보한 운영자는 기존 메시지와의 대응을 확인한 후
  `--deliver-existing <run-dir> --accept-receipt <private-original-cli-json>`으로 반영할 수 있습니다.
  이 명령도 다시 보내지 않으며, `sending` 또는 `unknown`인 기존 의도와 일치해야 합니다.
- 전송 성공 후 기준점 저장이 실패하면 다음 실행은 저장된 성공 영수증을 사용해 기준점
  저장만 이어갑니다. 소유자에게 다시 보내지 않습니다.
- 전달한 공개 변경 내용 해시와 실행 ID를 최근 104개까지 보존해 동일 내용 전송을 억제합니다.
  중간 실패의 원본 관측·요약 파일은 별도로 남아 있습니다.

기준점 반영 도중 다른 수집이 이미 새 기준점을 만들었다면 덮어쓰지 않고 충돌을 보고합니다.
명령은 네이티브 수집기의 동일한 `registry.lock`을 사용하므로 동시 수집·전송을 막습니다.
부분 수집 실패는 메시지를 전달했더라도 종료 코드 1로 표시될 수 있습니다.

## 진행 조회와 검증

각 회차는 `telegram-task-status.py` 색인에 수행·검증·파일·전달을 따로 기록합니다.
“이번 주 브리핑은 어디까지 됐어?”라는 요청은 해당 작업 조회로 처리할 수 있습니다.
Telegram 접수와 휴대폰에서 실제로 열기는 별개입니다.

```bash
/usr/bin/python3 scripts/openclaw/test-telegram-weekly-briefing.py
```

오프라인 시험은 최초 기준점·무변경 침묵, 공개 텍스트만 모델에 입력하는 경계, 원문 링크
분리, 후보·길이 제한 고지, 준비 후 재수집 금지, 요약 실패 복구, 전송 의도 선기록,
불확실 전송 재시도 금지, 원본 영수증으로 중단 복구, 부분·전체 출처 실패, 장애 알림 중복
억제와 재발 알림, 성공 후 기준점 저장 실패 복구, 메시지 변조 및 4단계 상태 색인을 다룹니다.

## 2026-09-12 커뮤니티 중심 선정 변경

소유자의 OpenAI 편중 피드백에 따라 GeekNews (`https://news.hada.io/rss/news`)와
Hacker News best (`https://hnrss.org/best`)를 추가했다. 두 공개 피드 수집과 신규 기준점
생성 성공, 기존 6개 기준점 보존을 확인했다. 기존 예약과 전달 경로는 변경하지 않았다.
등록 importance는 all로 바꿔 기업 발표 단어 규칙에 없는 커뮤니티 뉴스를 후보로 허용한다.
요약 단계가 AI 관련성·중요성을 판단한다. 후보는 커뮤니티 최대 15개를 우선 확보한 뒤
출처별 순환으로 총 30개를 채운다. 적격 뉴스가 충분하면 최종 10개 중 커뮤니티 6개 이상,
동일 기업 최대 2개를 요청한다. 이는 모델 선정 지침이며 출력 기업 분류의 강제 검증은 아니다.
커뮤니티의 주장과 공식 발표·독립 검증을 구분하고 관측하지 않은 인기 수치를 만들지 않는다.
RSS 제공 범위가 제한되므로 일주일 전체 기사나 인기 순위의 완전한 수집을 보장하지 않는다.
회귀 29개 통과 및 설치본 바이트 일치 확인. 변경 후 실모델 요약·발송은 아직 시험하지 않았다.

## 2026-09-28 요약 경로 대체와 주간 기간 제한

9/19 실행은 요약 검증 실패, 9/26 실행은 Codex 사용 한도(`API rate limit reached`)로 멈춰 두 주 동안
브리핑이 전달되지 않았다. 소유자 요청으로 다음을 적용했다.

- `summarize-openclaw.py`는 Codex 경로가 사용 한도·속도 제한을 보고하면 이 Mac의 Claude Code 구독
  로그인으로 Opus(`claude-opus-5-5`)를 호출한다. 모든 내장 도구와 MCP를 끄고(`--tools ""`,
  `--strict-mcp-config`, `--safe-mode`) 빈 임시 디렉터리에서 실행하며, API 키 환경변수는 넘기지
  않는다. 한 번의 도구 없는 Opus 응답만 받아들인다. 그 밖의 실패는 대체하지 않는다. OpenClaw의
  원시 모델 실행은 Anthropic API 키를 요구해 이 경로로 쓰지 않았다.
- 결과 봉투의 `route`를 `run.json`의 `summaryRoute`에 기록한다. 모델을 부르지 않은 실행은
  `no-model-call`로 기록한다.
- 후보는 수집 시작 시각 기준 직전 토요일 09:00부터 그 전 토요일 09:00(KST)까지 게시된 항목으로
  제한한다. 날짜가 없는 항목은 판단할 수 없어 포함한다. 메시지에 기간을 표시하고, 같은 목록
  페이지 출처는 한 번만 적는다.

같은 날 00:04 KST 기존 9/19 대기 실행을 `superseded`로 정리하고 새로 수집했다. 기간 밖 98건을
제외한 후보 30건을 요약해(이번에는 Codex 경로가 응답해 대체는 쓰이지 않음) 10건을 00:05 KST에
전달했다(Telegram messageId 492, 기준점 갱신). 설치본 대체 경로는 도구 없는 Opus 호출로 따로
확인했다.

