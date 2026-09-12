# 공개 출처 변경분 브리핑

`scripts/openclaw/telegram-briefing.py`는 사용자가 선택한 공개 URL의 성공 수집본을 비교한다.
첫 수집은 기준점만 만들며 알림 후보를 만들지 않는다. 변경 없음과 중요도 기준 미달도 조용히
기록한다. 한 출처라도 읽지 못하면 실패를 명시하며, 실패한 출처의 마지막 성공 원문은 보존한다.

이 엔진은 모델, Telegram, 브라우저 로그인, 쿠키, API 키를 사용하지 않는다. 한국어 요약,
Telegram 전송, 예약 실행은 별도 주간 실행기의 책임이다. 사용자는 AI/LLM 핵심 기술을
**매주 토요일 오전 9시, Asia/Seoul**에 전달하도록 요청했다. 이 문서와 엔진 구현만으로
예약 설치나 Telegram 수신이 증명되지는 않는다.

## 명령

설치 대상은 `~/.local/share/openclaw-telegram-workflows/`이며 원본은 이 저장소에 있다.
아래 명령은 저장소 루트 기준이다. 전역 옵션은 하위 명령보다 앞에 둔다.

~~~bash
python3 scripts/openclaw/telegram-briefing.py topic add \
  --id ai-llm --name 'AI/LLM 주요 기술' --importance high-impact \
  --url https://openai.com/news/rss.xml \
  --url https://deepmind.google/blog/rss.xml \
  --url https://www.anthropic.com/news \
  --url https://www.anthropic.com/research \
  --url https://huggingface.co/blog/feed.xml \
  --url https://mistral.ai/news
python3 scripts/openclaw/telegram-briefing.py topic list
python3 scripts/openclaw/telegram-briefing.py --json check --topic ai-llm
python3 scripts/openclaw/telegram-briefing.py --json check --topic ai-llm --peek
python3 scripts/openclaw/telegram-briefing.py topic remove ai-llm
~~~

`topic add`는 등록만 하며 예약을 만들지 않는다. 동일 ID를 덮어쓰지 않는다. `topic remove`는
등록을 비활성화하며 검증 영수증이나 과거 원문을 지우지 않는다. `topic list --include-disabled`로
비활성 항목도 볼 수 있다. `topic check`는 `check`와 같다. 여러 주제는 `check --all`로 점검한다.

일반 `check`는 성공한 출처의 비교 기준점을 갱신한다. 주간 발송 전 수동 점검에는 `--peek`를
사용해야 주간 비교 기간이 줄어들지 않는다. `--peek`도 점검 근거와 작업 영수증을 남기지만
비교 기준점을 갱신하지 않는다. 네트워크 실패 시 종료 코드는 1이며 `silent=false`이다.

## 비교와 중요도

- HTML은 script/style, 메뉴, 헤더·푸터, 숨김 영역을 제외하고 main/article이 있으면 우선 사용한다.
  JavaScript 렌더링이나 OCR을 하지 않으므로 빈 동적 페이지는 실패로 기록한다.
- RSS·Atom·JSON Feed는 항목 내용을 정규화하고 정렬하여 단순 순서 변경과 피드 생성 시각을 무시한다.
  게시 날짜는 개별 항목 내용과 함께 남긴다. 새 항목뿐 아니라 기존 항목 본문 수정도 비교한다.
- `high-impact`는 새 모델, 공개 가중치, 학습 방법, 평가 성능, 추론 비용·속도, 주요 기능의
  명시적 단어 규칙으로 후보를 고른다. 단어 기반 휴리스틱이며 실제 중요성이나 발표 주장의 사실성을
  확정하지 않는다. 일반 마케팅·행사·사소한 문서 수정은 주요 기술 신호가 없으면 후보가 되지 않는다.
  별도 한국어 요약 단계에서도 새로운 핵심 기술을 선별해야 한다.
- 최초 수집 이후 비교 기간은 각 출처의 직전 성공 수집 시각부터다. 실패 후 복구한 출처는
  기간이 일주일보다 길 수 있으므로 보고서에 실제 비교 시작·종료 시각을 남긴다.
- 짧은 보고서는 정확한 수집 URL과 시각, 선별 근거를 담으며 출처별 인용은 제목 포함 25단어 이내다.
  요약 단계에서 사용할 추가 본문은 비공개 근거 JSON에 보존한다.
- 모델 입력용 피드 변경은 전체 추가 항목에서 중요도 신호와 게시 시각을 먼저 비교한 뒤
  32,000자로 제한한다. 긴 피드의 뒤쪽에 있는 주요 발표가 단순 위치 때문에 누락되는 일을 줄인다.
  HTML은 문맥을 보존하기 위해 문단 순서를 유지한다. 제한으로 빠진 내용은 명시적인 플래그로 기록한다.

## 공개 URL과 자원 제한

HTTP 80/HTTPS 443만 허용한다. URL 사용자명·비밀번호, 인증값 형태의 쿼리, localhost,
사설·링크 로컬·멀티캐스트 IP, 내부 호스트명을 거부한다. DNS 답변을 모두 검사하고 검증한
공인 IP에 연결하면서 원래 호스트의 TLS 인증서와 SNI를 유지한다. 리다이렉트마다 URL과 DNS를
다시 검사하며 HTTPS에서 HTTP로 내려가는 이동을 거부한다. 프록시 환경이나 시스템 계정 인증을
가져오지 않는다.

출처별 최대 25초, 본문 2 MiB, 정규화 본문 1,000,000자·5,000행, 주제당 12개 출처로 제한한다.
ETag와 Last-Modified는 같은 최종 URL에만 재사용한다. 인증 필요 응답, 비정상/불완전 응답,
지원하지 않는 MIME·문자 인코딩은 실패다. 압축을 요청하지 않으며 이를 무시한 압축 응답도
명시적으로 거부한다. RSS/XML의 DTD·엔티티 선언을 거부한다.

외부 원문은 신뢰되지 않은 자료다. 그 안의 지시문·명령·추가 링크를 실행하거나 다른 도구의
허가로 해석하지 않는다. 파일에는 `sourceTextUntrusted=true`가 기록되고 보고서의 원문 인용은
닫는 기호를 포함하더라도 코드 블록을 탈출할 수 없게 작성한다.

## 저장과 주간 실행기 연동

비공개 상태 루트는 `~/.openclaw-personaledge/operations/workflows/briefing/`이다.

| 경로 | 내용 |
| --- | --- |
| `topics.json` | 명시적으로 등록한 주제·URL·중요도 모드 |
| `snapshots/<topic>/<source>.json` | 각 출처의 마지막 성공 비교 기준점 |
| `runs/<run>/sources/*.json` | 수집 시각·최종 URL·본문·해시·HTTP 조건 정보 |
| `runs/<run>/result.json` | 출처별 성공/실패·전체 차이 근거·중요도·파일 증거 |
| `runs/<run>/briefing.md` | 선택된 변경이 있을 때만 만드는 짧은 검토 보고서 |
| `runs/<run>/baseline-commit.json` | 기준점 반영 완료 영수증 |
| `latest/<topic>.json` | 가장 최근 점검 결과를 가리키는 작은 인덱스 |

디렉터리는 0700, 파일은 0600이며 symlink 경로를 거부한다. CLI는 비차단 파일 잠금으로
중복 점검을 막는다. 비교 대상은 성공한 출처별로만 갱신하며 과거 실행별 성공 원문도 보존한다.

주간 실행기는 같은 `registry.lock`을 잡고 다음 API를 사용한다.

~~~python
result = check_topic(root, state_dir, topic, peek=True, run_id="briefing-<unique-id>")
run = read_json(Path(result["evidencePath"]))
# 주간 실행기가 한국어 요약·검증·발송 상태를 먼저 영속화한다.
commit_run(root, run)
~~~

실행기는 수집 전에 예정 run ID를 저장하고 중단 후 완성된 `result.json`이 있으면 재사용한다.
이미 완성된 ID로 새 수집을 실행하면 거부한다. `commit_run`은 정확한 실행/출처 경로와 파일·본문
해시, 기존 기준점을 확인하고 성공 출처만 반영한다. 재실행은 완료 영수증을 재사용하며,
중간에 다른 실행이 기준점을 바꿨으면 과거 값으로 덮어쓰지 않는다.

`telegram-task-status.py`의 `publish_workflow`를 사용하여 실행, 검증, 생성 파일, 전송을
분리해서 기록한다. 이 엔진의 전송 상태는 항상 미요청이다. 출처 수집 실패와 생성된 일부 정상
파일을 동시에 표시할 수 있다. 전송 성공은 별도 실행기의 실제 Telegram JSON 영수증으로 증명한다.

## 검증

~~~bash
python3 scripts/openclaw/test-telegram-briefing.py
~~~

로컬 합성 테스트는 공개 URL 경계, DNS·리다이렉트 재검사, 조건부 GET, 크기/MIME/불완전 응답,
HTML 잡음 제거, 피드 재정렬, 최초 기준점·변경 없음·중요도 미달의 무알림, 부분 실패 보존,
프롬프트 삽입 격리, peek, 재개·반영 멱등성, 공유 작업 영수증을 확인한다.
피드 입력을 제한하기 전에 중요 항목을 우선하는 동작과 HTML 중첩 깊이 제한도 포함한다.

2026-09-09에 수집된 위 6개 실제 공개 출처 파일의 오프라인 정규화도 통과했다.
OpenAI 1,180항목/346,034자, DeepMind 100항목/26,927자, Anthropic 뉴스 2,076자,
연구 3,264자, Hugging Face 860항목/121,662자, Mistral 5,360자였다. 모든 항목을 유지했으며
개수 제한으로 잘라낸 내용은 없다. 이는 공개 원문 파싱 증거이며 주간 예약과 실제 발송의 증거는 아니다.
