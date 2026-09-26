# 텔레그램 OpenClaw 사용 가이드

2026-09-26, Mac의 OpenClaw 2026.9.3 기본 모델을 Claude Opus 5.5(Claude 요금제 사용량)로
바꾸고, 소진 시 GPT-6 Sol → GLM 순서로 넘어가도록 설정한 기준입니다.
기존 봇 대화방에서 아래 명령을 보내면 됩니다.
아래 확인 범위의 GPT-6 Astra 실행·파일 도구·GPT-5.6 Sol 요약 및 Telegram 전송 기록은
2026-09-08~09의 이전 정책에 대한 검증입니다. 이번 Opus 변경의 소유자 Telegram
대화 전송은 별도로 확인해야 합니다.

입력창에 `/`를 입력하면 명령 메뉴가 표시됩니다. `/help`는 짧은 도움말,
`/commands`는 명령 목록입니다. 메뉴에 없는 명령도 직접 입력할 수 있습니다.
설정 명령은 한 메시지에 하나씩 보내고, 확인 응답을 받은 뒤 작업을 요청하면 편합니다.

## 자주 쓰는 명령

| 입력할 명령 | 용도 |
| --- | --- |
| `/help` | 기본 도움말 |
| `/commands` | 명령 목록과 설명 |
| `/status` | 현재 실행·모델·연결 상태와 제공되는 사용량 정보 |
| `운영 상태 보여줘` | 최근 감시 시각, 미전송·장기 작업·야간 작업·복구 검증 백업을 함께 확인 |
| `/tools` | 현재 에이전트가 사용할 수 있는 도구 |
| `/tasks` | 진행 중이거나 최근 실행한 백그라운드 작업 |
| `/stop` | 현재 실행 중인 작업 중단 요청 |
| `/new` | 새 대화 문맥으로 시작 |
| `/compact` | 긴 대화 문맥을 요약·압축 |
| `/model` | 현재 모델 확인과 선택 안내 |
| `/model opus` | 현재 대화에서 Claude Opus 5.5 사용: `anthropic/claude-opus-5-5` |
| `/model codex` | 현재 대화에서 GPT-6 Sol 사용: `openai/gpt-6-sol` |
| `/model astra` | 현재 대화에서 GPT-6 Astra 사용: `openai/gpt-6-astra` |
| `/model sol` | 현재 대화에서 GPT-5.6 Sol 사용: `openai/gpt-5.6-sol` |
| `/model glm` | 현재 대화에서 OpenRouter의 GLM 사용 |
| `/think` | 현재 생각 강도와 가능한 값 확인 |
| `/think high` | 생각 강도를 high로 설정 |
| `/think max` | 현재 모델의 생각 강도를 max로 설정 |
| `/reasoning off` | 추론 과정 표시 끄기 |
| `/subagents list` | 위임한 하위 작업 목록 확인 |

`modelSelectionScope=session`으로 설정했으므로 위 `/model` 명령은 **현재 대화에만 적용**합니다.
`/model codex -s`처럼 `-s`를 붙여 범위를 명시해도 됩니다.
새 대화 시작은 장기 기억 삭제 요청이 아닙니다. 저장한 기억을 지우려면 대상을 지정해
“이 내용을 기억에서 삭제해줘”라고 요청하세요.

현재 전역 기본값과 주 대화 모델은 **Claude Opus 5.5, 생각 강도는 high**입니다.
`/model anthropic/claude-opus-5-5`, `/model openai/gpt-6-sol`, `/model openai/gpt-6-astra`, `/model openai/gpt-5.6-sol`처럼
전체 모델 ID로도 선택할 수 있습니다. 실제 대화의 모델과 지원하는 추론 강도는 `/model`,
`/think`로 확인하세요. `/think low`처럼 설정하며, 강도를 높이면 응답 시간과 사용량이
늘 수 있습니다. `/reasoning off`는 표시 설정이므로 생각 강도와 별개입니다.

| 작업 | 설정한 모델 경로 |
| --- | --- |
| 기본 대화·조사·주 작업 | Claude Opus 5.5, high (소진 시 GPT-6 Sol → GLM) |
| `/summarize`의 별도 요약 실행 | GPT-5.6 Sol, low |
| PDF 도구·코딩 하위 작업 | GPT-5.6 Sol |
| 매일 03:00 기억 정리 | 예약 작업 실행은 전역 기본값 Opus 5.5/high(소진 시 GPT-6 Sol), 내부 기억 정리는 GPT-5.6 Sol |
| 음성 전사 | Mac의 로컬 Whisper |

Opus 경로는 이 Mac에 설치한 Claude Code(`claude`)의 claude.ai 로그인으로 실행되어 Claude
요금제(현재 Pro) 사용량을 씁니다. 이 Mac과 다른 기기에서 쓰는 Claude·Claude Code 사용량과
같은 한도를 공유합니다. Codex 경로는 연결된 ChatGPT 계정의 Codex 할당량을 사용합니다.
어느 쪽도 API 키로 우회하지 않습니다.
주 에이전트의 기본 체인은 Opus 5.5 → GPT-6 Sol → OpenRouter GLM 순서이고, 전역 기본값은
Opus → Sol까지만입니다. fallback은 한도 소진 외의 적격 가용성 오류에도 적용될 수 있습니다.
Claude의 일부 한도 메시지는 한도 오류로 분류되지 않아 대기 시간 없이 다음 모델로 넘어가므로,
한도가 풀릴 때까지 매 턴 Opus를 먼저 시도해 응답이 몇 초 늦어질 수 있습니다. 명시적인
`/model opus`, `/model codex`, `/model astra`, `/model sol` 선택과 요약·PDF·하위 작업·기억
정리에는 이 fallback을 적용하지 않습니다. GLM 경로를 사용하면 OpenRouter 비용이 발생합니다.

## 기능을 지정하는 방법

운영 감시기는 연속 실패와 복구가 있을 때만 알립니다. 문서·미디어 시험에서는
합성 파일 7개의 Telegram 첨부 전달이 확인됐으며, SRT 자막은 원본을 담은 ZIP으로 보냅니다.
백업·별도 경로 복구와 시험 명령은 [운영 가이드](OPENCLAW_OPERATIONS_KO.md)에 있습니다.

기능 이름을 몰라도 한국어로 요청하면 됩니다. 특정 스킬을 지정할 때는
`/skill 이름 요청내용` 형식을 사용합니다.

| 하고 싶은 일 | 보낼 메시지 예시 |
| --- | --- |
| 링크 요약 | `/skill summarize https://example.com 이 페이지를 한국어로 요약해줘` |
| 음성 전사 | 음성 파일을 첨부한 뒤 `/skill openai-whisper 방금 보낸 녹음을 한국어 텍스트와 SRT 자막으로 만들어줘` |
| Word 수정 | DOCX를 첨부하고 “이 문서의 의미와 표를 유지하면서 문장을 다듬고 수정본을 보내줘” |
| Excel 작업 | XLSX를 첨부하고 “이번 달 지출을 항목별로 집계하고 수식과 서식을 유지해줘” |
| PDF 분석 | PDF를 첨부하고 “결론과 근거를 페이지 번호와 함께 정리해줘” |
| 웹 조사 | “이 주제의 최신 자료를 찾아 출처 링크와 함께 비교해줘” |
| 코딩 | “Codex로 지정한 프로젝트의 오류를 수정하고 테스트 결과까지 알려줘” |
| 기억 확인 | “내 프로젝트에 대해 저장한 기억을 보여줘” |

자주 쓰는 스킬은 짧은 명령으로도 지정할 수 있습니다.

| 명령 | 기능 |
| --- | --- |
| `/summarize 요청내용` | 링크·문서 요약 |
| `/openai_whisper 요청내용` | 음성 전사·자막 |
| `/word_docx 요청내용` | Word 문서 작업 |
| `/excel_xlsx 요청내용` | Excel 작업 |

첨부 파일을 먼저 보냈다면 다음 메시지에 “방금 보낸 파일”이라고 지정하세요.
스킬 명령은 해당 기능의 작업 지침을 선택합니다. 문서 파일만으로 작업 목적까지
정해지지는 않으므로 원하는 결과물·언어·범위를 함께 적으면 좋습니다.

`/codex`는 Codex 실행기의 상태·관리 명령입니다. 코딩을 부탁할 때는 자연어로
“Codex로 …”라고 요청하거나 `/model codex`를 보낸 뒤 작업 내용을 적으세요.

## 바로 따라 하는 예시

코딩 작업:

1. `/model codex`
2. `프로젝트 /정확한/경로에서 오류 원인을 찾고 수정한 뒤 테스트 결과를 알려줘.`
3. GPT-6 Astra로 바꾸고 싶으면 `/model astra`, GPT-5.6 Sol로 바꾸고 싶으면 `/model sol`

자료 검토:

1. PDF 또는 링크를 전송합니다.
2. `핵심 주장, 근거, 한계를 구분해서 한국어로 정리해줘.`
3. 추가 검토가 필요하면 `/think`에서 지원하는 강도를 확인하고 구체적인 후속 질문을 합니다.
4. 검토가 끝나면 `/think high`

## 확인 범위와 출처

2026-09-26 설정 변경 후 Gateway가 `anthropic/claude-opus-5-5`를 `claude-cli` 런타임으로
사용 가능하다고 보고했고, 모델을 지정하지 않은 격리 합성 대화 턴이 `provider=claude-cli
model=claude-opus-5-5`로 실행되어 `OPUS55-READY`로 응답했습니다(도구·재라우팅 없음, 임시
세션 삭제). 같은 날 원시 모델 호출(`modelRun`) 시험에서는 OpenClaw가 CLI 런타임을 쓰지 않아
Opus가 인증 오류로 탈락했고, Codex 한도 소진(429)으로 Sol을 건너뛰어 GLM이 응답했습니다.
이는 체인 순서의 실제 동작 증거이지만 Opus 경로 검증은 아닙니다. 이어서 소유자가 보낸 실제
Telegram 메시지가 `claude-cli`/Opus 5.5로 fallback 없이 처리되고 답장이 전송됐습니다
(messageId 426). 실제 Claude 한도 소진 시 전환은 확인하지 않았습니다.

아래는 2026-09-23 Sol 변경 당시의 검증 기록입니다.

2026-09-23 당시 설정에서 전역 및 주 에이전트 기본값 `openai/gpt-6-sol`/`high`와
주 에이전트의 기존 GLM fallback을 확인했습니다. 공식 Codex 0.156.1의 ChatGPT OAuth
경로를 사용한 격리 Gateway `modelRun`은 요청·실효 모델 `openai/gpt-6-sol`, 응답 모델
`gpt-6-sol`, 응답 `SOL-READY`, `rerouted=false`, 도구 호출 0건으로 완료됐고 임시 세션을
정리했습니다. 소유자 Telegram 대화의 전송과 할당량 소진 시 fallback은 이번에 확인하지
않았습니다. 사용량 절감이나 기존 모델과의 품질 동등성도 측정하지 않았습니다.

아래는 가이드 최초 작성 및 2026-09-08 변경 당시의 검증 기록입니다.

최초 가이드 작성 때 설치된 명령 정의와 설정을 대조했습니다. 당시 읽기 전용 점검에서 Gateway ready,
Telegram polling, 도구 40개를 확인했습니다. 요약·Whisper·Word·Excel 스킬은 실행 요건을
충족하는 상태였습니다. 2026-09-23 Sol 실행과는 별개의 당시 점검이며, 각 예시 작업을
새로 실행한 것은 아닙니다.

2026-09-08 변경 검증에서는 기본 Astra의 실제 파일 쓰기·읽기, 설치된 요약 CLI의 Sol/low
한국어 요약, 모델·추론 명령의 현재 대화 적용과 전역 기본값 보존을 확인했습니다.
기억 정리 내부 모델은 같은 실행 코어의 Sol 격리 시험만 했으며, 예약 작업 실행은 기본
Astra/high를 상속하도록 설정되어 있었습니다. 다음 예약은 당시 2026-09-09 03:00 KST였고
해당 시각의 전체 주기는 이 검증에서 실행하지 않았습니다. 변경 안내는 Telegram에 전송됐습니다.
외부 서비스 로그인, 접근 제한 사이트, 스캔 PDF, 복잡한 문서의 처리 가능 여부는
실제 입력에 따라 달라집니다.

- [공식 명령 문서](https://docs.openclaw.ai/tools/slash-commands)
- [현재 Mac/Telegram 운영 문서](OPENCLAW_TELEGRAM.md)
- [문서·요약·음성 기능](OPENCLAW_PRODUCTIVITY.md)
- [OpenClaw Codex 플러그인](OPENCLAW_PLUGINS.md)


## 새 작업 흐름 (2026-09-09)

- “아까 시킨 작업 어디까지 됐어?” — 수행, 검증, 결과 파일, Telegram 접수를 따로 확인합니다.
- “이 녹음을 회의록과 자막으로 정리해서 보내줘.” — 녹음 파일을 지정해 전사, 결정·미확정·후속 업무,
  Word/PDF 및 자막 ZIP을 만듭니다. 담당자·기한이 없으면 미지정으로 남깁니다.
- “AI 브리핑 관심 주제와 출처를 보여줘.” — 등록된 공개 출처를 조회합니다.
- AI·LLM의 중요 변경분 주간 브리핑은 토요일 오전 9시(한국 시간) 예약입니다.
  중요한 변경이 없으면 알림을 생략하고, 수집 실패는 확인된 변경과 구분합니다.

개별 업무 조회가 새 실행이나 재전송을 시작하지 않습니다. Telegram 접수는 휴대폰에서 파일을
열었다는 확인과 다릅니다. 세부 사용법은 [긴 작업](OPENCLAW_TASK_STATUS.md),
[회의록](OPENCLAW_MEETINGS.md), [공개 출처](OPENCLAW_BRIEFING.md)를 참조하세요.
