# OpenClaw 플러그인 운영

2026-09-07 KST에 현재 Mac의 OpenClaw 2026.9.2에서 설치·활성화하고 실제 실행을 확인했다.
이 문서는 Codex 앱 자체에 설치하는 플러그인이 아니라 Telegram 봇의 OpenClaw 플러그인을 다룬다.
2026-09-08에는 아래 모델 정책을 적용하고 기본 Astra 파일 도구 실행, Sol/low 요약 및
변경 안내의 Telegram 전송을 확인했다. 세부 범위는
[모델 기본값 검증](OPENCLAW_TELEGRAM.md#codex-default-acceptance-2026-09-08-kst)에 있다.
아래 2026-09-07 실행 증거는 당시 모델에 대한 기록이다.
2026-09-09에는 코어와 아래 세 플러그인을 2026.9.3으로 맞추고 실제 로딩·추출기 등록,
기본 Astra 응답 및 Sol 요약을 재확인했다. [업데이트 검증 기록](OPENCLAW_UPDATE_20260909.md)에
현재 버전의 검증 범위를 구분했다.

| 플러그인 | 현재 설치 출처 / 버전 | 용도와 9월 7일 실행 검증 |
| --- | --- | --- |
| Codex | 공식 `@openclaw/codex@2026.9.3` (9월 9일 코어와 버전 정렬) | ChatGPT 로그인으로 네이티브 Codex 실행. 합성 Python 코드의 결함을 수정하고 테스트 4개 통과 |
| Web Readability | OpenClaw 번들 2026.9.3 | 웹 본문 추출. 한국어 HTML의 메뉴·푸터 제거와 실제 `web_fetch` 실행 확인 |
| Document Extract | OpenClaw 번들 2026.9.3 | PDF 텍스트·페이지 이미지 추출. 한국어 2쪽 PDF 추출과 실제 `pdf` 분석 확인 |

Codex에 필요한 공식 `openai` 공급자 플러그인도 활성화했다. 두 추출 플러그인은 필요할 때
로딩되는 기능 제공자이므로 `health.plugins.loaded`에 이름이 없어도 고장으로 판단하지 않는다.
운영 검증기는 각 플러그인의 실제 로딩 상태와 추출기 등록을 별도로 확인한다.

## 인증과 사용량

기존 Codex의 **ChatGPT 로그인**을 공식 `migrate codex` 경로의 `auth:openai` 항목만 선택해
OpenClaw 인증 저장소에 연결했다. 계획의 21개 항목 중 인증 1개만 이관하고 나머지 20개는
건너뛰었다. 다른 Codex 대화, 메모리, 스킬, 연결 플러그인은 가져오지 않았다. 인증 값은
문서·스크립트·Git에 넣지 않는다.

- 기본 대화 모델은 `openai/gpt-6-astra`, 별칭은 `codex`, 추론 강도는 `high`로 지정했다.
- `openai/gpt-5.6-sol`의 별칭은 `sol`이다. 두 모델 모두 네이티브 `codex` 실행기를 사용한다.
- PDF와 하위 에이전트의 기본 모델은 `openai/gpt-5.6-sol`로 고정했다. 별도 모델 인자가
  빠진 위임 호출에도 적용한다. Summarize는 Sol/low, 매일 기억 정리의 내부 completion은
  Sol로 지정했다. 기억 정리 예약 작업의 바깥 에이전트 실행은 기본 Astra/high를 상속한다.
- OpenAI 인증은 선택된 OAuth 프로필 1개로 고정했다. API 키 프로필과 모델 대체 경로가 없다.
- Codex 자식 프로세스에서 `OPENAI_API_KEY`, `CODEX_API_KEY`를 제거한다.
- Codex 상태는 OpenClaw 에이전트 단위로 분리한다. 기존 Codex 작업 목록 공유, supervision,
  Codex 플러그인 동기화와 computer use는 활성화하지 않았다.
- 이 Codex 경로는 **계정의 Codex 사용량**을 사용한다. 한도 소진·로그인 실패 시 다른 유료
  공급자나 API 키로 우회하지 않고 알려주도록 지시했다. 사용량 한도와 계정 정책은 적용된다.
- GLM은 `/model glm`으로 수동 선택할 수 있으며 그때 OpenRouter 비용이 발생한다.
  Codex 기본 대화·요약·기억 정리에 GLM 자동 fallback은 없다. 기존 검증은 인증·실행
  경로의 증거이며 청구서 대조는 아니다.

공식 설명: [Codex 실행기](https://docs.openclaw.ai/plugins/codex-harness),
[Codex 인증과 사용 방식](https://learn.chatgpt.com/docs/auth).

## Telegram에서 사용

`/model codex`는 GPT-6 Astra, `/model sol`은 GPT-5.6 Sol, `/model glm`은 GLM을 선택한다.
`modelSelectionScope=session`이므로 옵션을 생략해도 현재 대화에만 적용하며 `-s`도 사용할 수
있다. `/think high`로 기본 추론 강도를 선택하고 `/think`로 현재 모델의 지원 값을 확인한다.
PDF·하위 작업·요약·기억 정리의 별도 모델 지정은 대화 모델 변경과 독립적이다.

예시:

- `Codex로 /원하는/프로젝트의 오류를 수정하고 테스트까지 실행해줘.`
- `이 링크의 본문을 읽고 한국어로 요약해줘.`
- PDF를 첨부한 뒤 `이 문서의 핵심 내용과 확인할 사항을 정리해줘.`

`pdf` 도구는 별도 허용 항목으로 추가했다. 2026.9.2의 `group:media`만으로는 노출되지 않는다.
또한 로컬 PDF는 미디어 허용 경로 안에 있어야 한다. 다른 경로의 소유자 요청 파일은 작업공간의
고유한 임시 디렉터리에 해당 파일만 복사해 분석하고 임시 복사본을 제거하도록 지시했다.
원본과 경로 제한은 보존한다.

## 실행 증거와 재검증

비공개 결과는 `~/.openclaw-personaledge/operations/plugins-codex-20260906T155256Z/`에 있다.
설정과 기존 작업공간 지침을 먼저 백업했고, 인증 이관 전 백업은 원본 상태 디렉터리 밖의
`~/.local/share/openclaw-backups/`에 보관했다. 백업에는 인증이 포함될 수 있으므로 공유하지 않는다.

- Codex: 변경 전 테스트 3개 실패, 1개 통과. 코드 수정 후 4개 통과. 별도 재실행에서도
  4개가 통과했고 테스트 파일이 원본과 같음을 확인했다. 실제 도구는 `exec`, `edit`였다.
- 웹: 동일 Codex 실행에서 `web_fetch https://example.com`으로 HTTP 200과 기대 본문 확인.
  추출기 직접 검증에서는 합성 한국어 본문·제목 보존 및 메뉴·푸터 제거 확인.
- PDF: 실제 `pdf` 도구가 합성 문서의 상태 **검증 완료**와 한국어 문장·표 생성/수정 시험이라는
  목적을 올바르게 답했다. 성공 도구 목록에 `pdf`가 기록됐고 모델 우회는 없었다.
- PDF 추출기 직접 검증: 한국어 2쪽 텍스트, 이미지 대체 경로의 PNG 2개/총 1,723,392 픽셀,
  존재하지 않는 999쪽 요청 거부까지 확인했다. 스캔 문서의 OCR 정확도를 보장하는 시험은 아니다.
- 첫 PDF 시도는 도구 미노출, 다음 시도는 미디어 경로 제한으로 실패했다. 허용 도구 추가 후
  작업공간의 합성 복사본을 사용한 최종 실행이 통과했다. CLI 파서로 대체하지 않았다.
- 코딩·웹 실행은 약 98초, 최종 PDF 실행은 약 18초였다. 일반적인 지연 보장은 아니다.
- GLM → Codex 위임: 처음에는 GLM이 모델 인자를 누락해 GLM 자식이 만들어져 실패로 판정했다.
  기본 하위 에이전트 모델을 보완한 뒤 실제 자식이 `openai/gpt-5.6-sol`과 `codex` 실행기로
  동작했고, 동일 테스트 4개 통과·종료 코드 0을 반환했다. 설정 문구만 확인한 결과가 아니다.

검증 중 모델 실행은 임시 대화와 합성 파일만 사용하고 Telegram 전송을 끈 상태였다.
실제 Telegram 첨부 파일 수신·답장까지 이번에 새로 검증한 것은 아니다.

```bash
python3 scripts/openclaw/test-telegram-gateway.py
python3 scripts/openclaw/test-summarize-openclaw.py
scripts/openclaw/verify-gateway.sh --telegram --json
/opt/homebrew/opt/node/bin/node scripts/openclaw/test-plugin-extractors.mjs \
  "$OPENCLAW_PACKAGE" "$SYNTHETIC_KOREAN_PDF"
```

운영 검증기는 ChatGPT 전용 인증 정책, Codex 실행기 지정, 세 플러그인, 실제 PDF 도구 노출과
기존 소유자 접근 제한·메모리·Telegram·호환성 패치를 함께 확인한다. 설치된 관찰자에도 같은
검증기를 반영했다. 향후 OpenClaw 업데이트 때 Codex 플러그인 호환성 및 추출기 실행도 재검증한다.
