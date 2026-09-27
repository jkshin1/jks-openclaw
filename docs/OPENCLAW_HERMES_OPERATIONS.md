# Hermes를 통한 OpenClaw 운영 진단·개선

2026-09-10 KST. 운영 확장과 설치 검증 안내다. 사용자는 장애 진단,
업데이트 영향 검토, 운영 개선과 **매주 토요일 10:00 Asia/Seoul** 검토를 요청했다.
이 문서나 스킬 파일이 있다는 사실은 설치·예약 등록·실제 실행 완료의 증거가 아니다.
각 단계는 마지막의 검증 항목과 실제 영수증으로 판정한다.

기존 [이벤트 JSON 보고서](OPENCLAW_HERMES_PILOT.md)는 유지한다. 새 `hermes-operations`는
이 Mac의 OpenClaw 운영 상태와 관련 코드를 대조해 원인을 진단하고 개선 후보를 준비하는
호출 경로다. 공개 사례에 대한 조사와 근거 한계는
[운영 역할 확장 조사](OPENCLAW_HERMES_OPERATIONS_RESEARCH_20260910.md)에 있다.

## 역할과 결과

| 담당 | 역할 | 결과의 의미 |
| --- | --- | --- |
| 기존 무추론 감시기 | Gateway·작업·전송·예약 상태를 정해진 규칙으로 확인 | 현재 상태와 장애 사건의 근거 |
| 운영 controller | 필요한 근거 수집·가림 처리, 중복 실행 방지, Hermes 호출, 후보 검증 | 실행 ID와 연결된 진단·검증 영수증 |
| Hermes | 운영 근거와 제한된 소스를 읽고 원인·업데이트 영향·개선안을 작성, 관련 운영 절차 재사용 | 모델의 분석과 수정 후보 |
| 독립 검사 | 별도 소스 스냅샷에 후보를 적용하고 정해진 검사를 실행 | 그 스냅샷에서의 검사 결과 |
| OpenClaw 또는 예약을 실행한 Codex 작업 | 요청·기존 승인 범위에서 후보 검토, 설치, 원복 준비와 실제 동작 검증 | 배포·실행 검증 결과 |
| OpenClaw의 기존 전송 경로 | 사용자 응답과 필요한 첨부 전송 | Telegram API 영수증 |

Hermes의 기본 경로는 전용 ChatGPT OAuth와 `gpt-5.6-sol` / `high`다. 기존 OpenClaw의
Astra/high 기본값, 주 대화 소유권, Telegram polling, 기억 저장소를 유지한다. 인증을
복사하거나 장기 기억을 자동 동기화하지 않는다. 같은 계정·공급자를 쓰면 사용 한도와
공급자 장애는 여전히 공유될 수 있다.

운영 입력은 필요한 집계 상태, 가린 진단 근거, 선택된 코드·문서다. 일반 대화와
인증 저장소 전체를 모델에 넘기지 않는다. Hermes의 도구는 구현에서 정한 운영 자료
읽기·운영 스킬 범위로 제한하며, 임의 shell·Telegram·운영 설정 변경 권한을 추가하는
지침으로 이 문서를 사용하지 않는다. 프로필 분리 자체는 운영체제 수준 격리가 아니다.
후보 검사 프로세스는 macOS sandbox-exec로 별도 사본과 실행 라이브러리만 읽을 수 있으며,
실제 운영 디렉터리 접근과 네트워크를 차단한다. 수정 경로와 검사 목록도 고정한다.

## 요청해서 사용하기

Telegram의 예시는 다음과 같다.

```text
/skill hermes-operations 최근 OpenClaw 장애 원인과 복구 방안을 확인해줘
/skill hermes-operations 이번 업데이트가 현재 설정과 로컬 패치에 미치는 영향을 검토해줘
/skill hermes-operations 반복되는 운영 오류를 재현하고 수정·검증까지 진행해줘
```

운영 진단·업데이트 영향 검토·개선 요청이 명시되어 있으면 OpenClaw는
[`HERMES_OPERATIONS_SKILL.md`](../scripts/openclaw/templates/HERMES_OPERATIONS_SKILL.md)의
설치본을 사용한다. 일반 업무나 늦게 도착한 답변만으로 추가 진단을 시작하지 않는다.

설치 후 Mac에서 직접 실행하는 경로는 다음과 같다. 요청 전체를 하나의 인자로 전달한다.

```bash
python3 /Users/jk/.local/share/openclaw-hermes-worker/bin/hermes-operations.py run --mode manual --request '최근 OpenClaw 장애 원인과 복구 방안을 확인해줘' --json
```

사용자 문자열을 shell 명령에 단순 연결하지 않는다. 인자 배열 또는 올바른 shell quoting을
사용하여 백틱·달러 치환이 실행되지 않도록 한다. 실행이 오래 걸리면 같은 프로세스와
영수증을 확인한다. 중복·실행 중 응답은 새 실행을 우회해서 시작할 이유가 아니다.

실행 실패, 인증 불가, 자료 부족은 각각 해당 결과로 보존한다. 다른 모델·공급자·API 키로
몰래 전환하지 않는다. 독립 인증이 필요해도 기존 OpenClaw/Codex 토큰을 복사하지 않는다.
현재 설치와 로그인이 정상이라면 [신규 설치 절차](OPENCLAW_HERMES_PILOT.md)를 다시 실행할
필요가 없다.

JSON 결과의 `runId`, `status`, `evidencePath`, `reportPath`, `receiptPath`,
`candidateVerification`으로 실행과 근거를 연결한다. `applied:false`와
`telegramDelivered:false`는 controller가 운영 반영이나 전송을 하지 않았다는 뜻이다.
이후 실행 주체가 설치하거나 전송했다면 같은 실행 ID에 별도 영수증을 연결한다.

## 검사된 수정 후보 처리

진단, 후보 작성, 후보 검사, 설치, 실제 동작, 최종 전송은 별개의 단계다. 모델이 수정
코드를 만들었거나 스냅샷 검사가 통과한 것만으로 운영 복구를 완료했다고 표시하지 않는다.

1. 영수증이 가리키는 후보 diff, 변경 대상, 기준 소스 해시, 검사 결과를 읽는다.
   실패·미완료 검사와 오래된 근거가 있으면 이를 먼저 해소한다.
2. 현재 운영 소스와 기준 해시가 맞는지 확인한다. 다른 작업이 소스나 설정을 변경했다면
   그 변경을 보존하고 후보를 새 기준으로 준비·검증한다. 전체 설정 복원으로 다른
   작업의 변경을 덮지 않는다.
3. 수정·배포까지 요청됐거나 기존 승인 범위에 포함되면 기존
   [운영 절차](OPENCLAW_OPERATIONS_KO.md)와 해당 변경의 검증 절차를 따라 진행한다.
   진단만 요청된 경우에는 구체적인 근거와 제안까지 보고한다.
4. 운영 파일을 바꾸기 전 대상과 설정을 비공개 경로에 백업한다. 재시작이 필요하면
   세 번의 유휴 표본에서 활성 실행과 실행 ID를 확인하고 공식 서비스 명령을 사용한다.
   소유자의 진행 중 작업을 중단하거나 기존 대화·등록 정보를 폭넓게 삭제하지 않는다.
5. 영향에 맞는 회귀 검사와 설치본 검증을 수행하고 실제 모델·도구·서비스 동작이 필요한
   변경은 그 실행 영수증까지 확인한다. 인증·사용 한도가 막혔다면 검사 성공을 실제
   추론 성공으로 대신하지 않는다. 실패 원복은 해당 변경에서 소유한 대상에 한정한다.
6. 최종 응답에는 바뀐 점, 검증 결과, 남은 제한을 설명한다. 원래 작업이나 이미 완료한
   진단을 전송 재시도 때문에 실행하지 않는다. 전달 실패는 확인된 전송만 재시도한다.

분석과 후보 작성·검사까지 중간 승인 단계를 새로 만들지 않는다. 후속 수정은 같은
프로젝트의 운영 코드와 기존 요청 범위에서 진행한다. 인증 변경, 대화 삭제, 유료 경로
추가, 대규모 버전 교체를 이 권한에 포함한다고 추정하지 않는다. 후보 검사 실패 시
자동 적용하지 않는다.

운영 스킬에 남길 내용은 적용 버전, 전제 조건, 재현·진단·검증·원복 절차다. 실제 성공과
실패를 구분하며, 토큰·개인 대화·사건의 원문을 학습 자료로 저장하지 않는다. 저장된 스킬의
존재와 성공적인 도구 읽기·정확한 후속 결과는 서로 다른 재사용 증거다.

## 주간 검토와 장애 시 실행

주간 검토는 **토요일 10:00 Asia/Seoul**에 이 Codex 작업의 heartbeat 자동화로 등록한다.
OpenClaw 내부 cron이나 두 번째 Hermes Telegram gateway를 중복 설치하지 않는다.
예약 실행은 controller를 호출하고, 결과에 실제 조치가 있으면 요청된 범위의 수정·설치·
검증을 이 작업에서 이어간다. 예약 등록 자체와 첫 예약 실행 성공은 별도로 확인한다.

```bash
python3 /Users/jk/.local/share/openclaw-hermes-worker/bin/hermes-operations.py run --mode weekly --json
```

예약 프롬프트에 보존할 계약은 다음과 같다.

> 현재 OpenClaw 운영 상태, 마지막 검토 이후의 관련 변경과 운영 근거를 확인하고 설치된
> Hermes 운영 controller로 주간 검토를 실행한다. 영수증에서 변경 없음·중복·자료 부족을
> 구분한다. Hermes 주간 검토는 주마다 한 번만 호출하며, 같은 주의 실패·재실행에
> 자동 재시도나 --force를 사용하지 않는다. 반복 알림을 만들지 않는다.
> 재현 가능한 문제와 검증된 수정 후보가 있으면 기존 승인 범위와
> 백업·유휴 확인·검사·원복 절차 안에서 수정·배포·실제 동작 검증을 이어간다. 변화가 없거나
> 조치할 내용이 없으면 조용히 종료하고, 중요한 변화·완료·실패·사용자 조치가 필요할 때만
> 알린다. 원래 사용자 업무나 이미 전달된 결과를 다시 실행·전송하지 않는다.

Codex에서 시작하는 로컬 controller는 OpenClaw Gateway가 꺼져 있어도 실행할 수 있도록
분리한다. Gateway가 응답하지 않는 항목은 미확인으로 표시하고 가능한 로컬 근거와 결과를
보존한다. 다만 같은 Mac, Codex 앱의 자동화 실행 환경, 네트워크와 모델 인증에 의존한다.
Mac 전원 꺼짐이나 Codex 실행 불가까지 복구하는 독립 서버 감시를 뜻하지 않는다.

주간 검토는 장애를 즉시 분석하는 예약이 아니다. 자동 장애 대응을 추가할 때의 연결은
기존 **무추론 감시 → 사건 대기열 → 제한된 controller 실행**이다. 감시와 기존 알림을
정상 유지하면서 사건 ID·근거 해시·최종 상태로 중복 호출을 막고, 모델 실패가 감시기를
막지 않도록 해야 한다. 해당 연결은 설치·통합 영수증이 생기기 전까지 활성화됐다고
표시하지 않는다. 단순 알림 지연 때문에 원래 사용자 작업을 재시도해서는 안 된다.
2026-09-10 KST에 이 연결이 실제로 설치됐다. 5분 감시기는 이미 열린 사건에 대해
`run --mode incident --json`을 한 번, 분리된 프로세스로 시작한다. 감시기는 결과를 기다리지
않으며 모델을 직접 호출하지 않는다. 예산은 사건 1건당 1회, 6시간 간격, 하루 2회이고,
대상은 Gateway·작업·야간 작업 계열 항목으로 제한한다. 전송·백업 장애는 정해진 운영 절차가
있으므로 모델 호출로 승격하지 않는다. spawn 전에 의도를 먼저 durable하게 기록하므로
중간 실패가 예산 없는 재호출로 이어지지 않고, controller 자신의 lock과 주간 예산이 여전히
중복을 막는다. 수집기가 정상으로 판정하면 incident 모드는 모델 호출 없이
`healthy-no-incident`로 즉시 종료하므로 오탐 비용은 0이다. 설치 스모크는 `--no-hermes`로
실행하여 설치 자체가 모델 호출을 시작하지 않는다.

## 설치·원복과 검증 기록

운영 확장의 배포 단위는 controller, 의존 helper, 검토된 실행 계약과 스킬 템플릿이다.
설치기는 실제 import/실행 의존 파일을 함께 배치하고 각각의 소스·설치본 해시를 남겨야
한다. 기존 이벤트 보고서 worker와 인증·학습 자료를 덮어쓰지 않는다.

- 실행 파일 기준 경로: `/Users/jk/.local/share/openclaw-hermes-worker/bin/`.
- OpenClaw 스킬 설치 대상:
  `/Users/jk/.openclaw-personaledge/workspace/skills/hermes-operations/SKILL.md`.
- 변경 전 기존 설치 파일과 설정을 비공개 경로에 백업하고 파일별 원복 대상을 기록한다.
  존재하지 않던 파일과 교체한 파일을 구분한다.
- 실행 근거, 모델 출력, 후보, 검사 로그, 인증 상태는 로컬 비공개 산출물로 보존한다.
  문서·Git·대화에는 필요한 내용과 가린 요약만 남긴다.
- 중지할 때는 해당 예약과 `hermes-operations` 호출을 비활성화한다. 현재 실행은 정확한
  소유 프로세스 상태를 확인하여 처리하고 기존 대화·다른 예약·인증·기록은 보존한다.

수용 검증에서는 다음 항목을 별도로 기록한다. 실패한 시도도 비용·오류 근거로 보존한다.

| 검증 | 필요한 근거 |
| --- | --- |
| 소스 구현 | 중복·실패·자료 가림·후보 변조·범위 이탈 등 관련 회귀 검사 |
| 설치 | 파일별 소스/설치 해시, 의존 파일, 기존 인증·설정 보존, 원복 대상 |
| 도구 등록 | OpenClaw main의 실제 스킬 등록·사용 가능 상태 |
| Hermes 실행 | 실제 모델·추론 수준·사용량의 제공 여부, 성공 도구, 종료 상태 |
| 진단 품질 | 정답을 확인할 수 있는 운영 사례의 원인 구분과 근거 연결 |
| 후보 검사 | 기준 해시, 정확한 diff, 정해진 검사 결과와 실패 검출 |
| 운영 반영 | 설치·서비스 상태·필요한 실제 모델/도구 실행 결과 |
| 재사용 | 새 입력에서 운영 스킬을 성공적으로 읽고 검증된 결과를 낸 기록 |
| 예약 | 토요일 10:00 KST 등록과 첫 자연 예약 실행 결과 |
| 전송 | 실제 전송을 수행한 경우의 별도 Telegram 수락 영수증 |

`telegramDelivered:false`는 Hermes가 전송하지 않았다는 뜻이다. OpenClaw 또는 승인된
기존 전송 경로의 뒤이은 전송은 자체 영수증으로 확인하며, API 수락을 휴대폰 열람으로
해석하지 않는다. 스케줄 설정·로컬 검사 통과·실제 운영 회복을 한 개의 성공 표시로
합치지 않는다.

### 2026-09-10 설치 및 수용 검증

- 새 controller·운영 worker·후보 검증기·전용 프로필을 설치했다. 기존 보고서 worker,
  Hermes 인증 원본과 OpenClaw 설정을 보존했고 Gateway 재시작 없이 설치했다.
  설치 영수증은 `~/.local/share/openclaw-hermes-worker/operations-install.json`이다.
- main의 실제 `skills.status`에서 `hermes-operations`와 `hermes-operations-report` 모두
  `eligible:true`, `disabled:false`였다. Gateway 검증은 2026.9.3, `gateway:ready`,
  `telegram:polling`으로 통과했다. 새 명령의 Telegram 발송·휴대폰 열람 검증은 별도다.
- 후보 검증기의 실제 macOS sandbox 경계 검사 18개와 고정 운영 회귀 109개가 통과했다.
  고정 회귀는 운영 집계 25, 작업 상태 21, 공개 조회 24, 주간 브리핑 28, Gateway 11개다.
- 실제 GitHub releases 조회가 JSON Accept 누락으로 `HTTP_415`를 반환하는 문제를 재현했다.
  JSON을 허용하는 응답 규칙에 맞춰 요청 헤더도 수정하고 두 공식 릴리스 재조회에 성공했다.
  브리핑 설치본도 백업 후 동기화했다. 첫 실패 수집 영수증은
  `ops-20260909T225440Z-16e680b7`, 성공 재검증은 `ops-20260909T225554Z-060b49fb`다.
- 첫 실제 worker 시도 `ops-20260909T225758Z-997cc4e9`는 환경 검증에서 종료했다.
  `INHERITED_CREDENTIAL_OR_ROUTE_REFUSED`, `api_calls:0`, `response_models:[]`였으며
  모델 추론 성공이나 사용 한도 오류로 해석하지 않는다. 해당 실패 영수증을 보존했다.
- native 대화 시작 시 Hermes가 추가하는 두 실행 설정을 외부 환경 주입으로 오인한 것이
  원인이었다. 기존 상속 거부를 유지하면서 고정된 실행 설정과 일치하는 값만 허용했다.
  native 경로 26개, controller 14개, 설치기 13개, 후보 검증기 18개가 통과했다.
  기존 보고서 wrapper 16개와 worker 24개도 통과했다.
- 실제 재검증 `ops-20260909T230405Z-edff402a`는 `reviewed`, `completed:true`로 종료했다.
  **Sol/high**, 219.461초, API 응답 7회였고 `skills_list`·`skill_view`·소스 읽기·후보
  구조 검사가 성공했다. 재사용한 운영 스킬의 해시는 실행 전후 같았다. 모델 제공 사용량은
  input 66,939, output 6,202, cache read 210,944, total 284,085 토큰이다.
- 해당 진단은 현재 정상 상태와 종료된 과거 실패를 구분했으며, 수정 후보는 비어 있었다.
  고정 회귀 109개는 별도 후보 검증기 수용 시험이며, 이 빈 후보에 수행한 검사라고
  합쳐 표시하지 않는다. `applied:false`, `telegramDelivered:false`다.
- 독립 검토에서는 구형 관리 스크립트의 버전 상수를 현재 설정 이탈로 분류하지 않았다.
  저장소 AGENTS.md에 명시된 보존 Android 경로와 활성 Telegram 관리 경로의 차이다.
  이 확인된 구분을 운영 절차에 반영하고 이전 파일과 근거를
  `~/.local/share/openclaw-hermes-worker/operations/acceptance-20260910/`에 보존했다.
  새로 보강한 문단을 이후 모델 실행이 읽었는지는 아직 관찰하지 않았다.
- 자동화 ID `hermes-openclaw`는 이 Codex 작업에 연결된 heartbeat로 **ACTIVE**다.
  매주 토요일 10:00 Asia/Seoul이며 첫 자연 예약은 **2026-09-12 10:00 KST**다.
  예약 파일과 연결 작업을 읽어 확인했으나 첫 자연 실행은 아직 발생하지 않았다.
  결과 알림은 의미 있는 변화·완료·실패·필요한 조치가 있을 때 이 Codex 작업에서 한다.

### 2026-09-10 운영 아키텍처 보강

요청에 따라 다음 여덟 가지를 반영했다. 근거 수집·검증은 모두 코드이고, 모델은 여전히
분석만 하며 적용 권한은 갖지 않는다.

| 항목 | 내용 | 관측된 검증 |
| --- | --- | --- |
| 장애 자동 분석 | 5분 감시기가 자격 있는 사건에 incident 검토 1회를 분리 실행 | 고정 회귀 7건, 실제 예약 실행이 새 코드로 동작 |
| 실패 건별 분류 | `taskFailureGroups`가 원인 형태별로 묶어 제공 | 실데이터에서 오늘 장애 2종을 자동 분리 |
| 중복 억제 | 동일 fingerprint·소스·릴리스면 모델 호출 0으로 단락 | 고정 회귀 5건 |
| 실행 도구 목록 | Gateway 자신의 exec PATH에서 선언 도구 확인 | 실데이터 `rg`/`jq`/`ffmpeg` 확인 |
| 적용 되먹임 | `record-applied`가 배포를 기록하고 중복 기준선을 무효화 | 실제 설치본에서 왕복 확인 |
| 업그레이드 사전 점검 | 최신 릴리스가 로컬 패치 사양에 포함되는지 비교 | 실제 upstream 태그로 확인 |
| 검토 원장 | 실행 결과와 지적 식별자를 누적해 반복을 노출 | 고정 회귀 4건 |
| Hermes 가용성 | 모델 호출 없이 controller 호출 가능 여부 점검 | 실데이터에서 0.21.1 확인 |

근거 경계는 그대로 유지한다. 실패 그룹은 원문 명령과 원문 오류 문자열을 담지 않고 고정
분류 라벨만 남긴다. 분류는 앱이 작성한 접두사에 anchor하므로, 명령 인자에 우연히 포함된
`authorization` 같은 단어가 인증 장애로 오분류되지 않는다. PATH는 Gateway service-env의
`PATH=` 한 줄만 읽고 그 파일의 다른 줄은 파싱·보관하지 않는다. 검토 원장은 지적 식별자와
결과만 남기고 모델 산문은 다음 요청으로 되돌리지 않는다. 실행 도구 목록과 Hermes 가용성은
warning으로만 보고하며 `issues`에 넣지 않으므로 정상 판정과 소유자 알림을 바꾸지 않는다.

작업 스키마가 바뀌면 실패 그룹만 `scope: "unavailable"`로 비고, 나머지 집계 근거는 그대로
유지한다. 설치본 collector가 구버전이면 실패 그룹만 생략하고 `failureDetailAvailable=false`로
보고한다. 두 경우 모두 전체 근거가 조회 실패로 무너지지 않는다.

운영 절차 기억은 `HERMES_OPS_RUNBOOK.md` 1.1.0에 반영했다. 설치기는 기존 runbook을 보존하므로
저장소 seed와 설치본을 함께 갱신했고 재설치 후 보존을 확인했다. 실제 모델 실행이 갱신된
절차를 읽었는지는 아직 관측하지 않았다. 첫 자연 주간 실행은 2026-09-12 10:00 KST다.

### 사진 응답 복구 후 controller 보완, 2026-09-10

페이지 인자 오류가 전체 worker를 종료하던 문제를 복구 가능한 도구 응답으로 바꾸고,
최종 findings의 허용 근거 ID를 입력 계약에 명시했다. 범위·크기·호출 예산과 근거 검증은
유지한다. 초기 실패, 시간초과, 보고서 형식 실패와 마지막 Sol/high 검토 성공은
[사진 응답 복구 기록](OPENCLAW_PHOTO_RESPONSE_RECOVERY_20260910.md)에 구분했다.

### 2026-09-12 first weekly review

첫 자연 주간 실행 `ops-20260912T010131Z-6b648126`은 한국 시간 10:01:31에 시작해
10:03:40에 `reviewed`, `completed:true`로 종료했다. Sol/high API 응답 6회,
worker 시간 127.967초였으며 `skills_list`, `skill_view`, `ops_read_source`,
`ops_check_candidate`가 성공했다. 설치된 1.1.0 운영 절차의 실제 재사용이 확인됐고
실행 전후 SHA는 같았다. 제공된 사용량은 input 63,050, output 3,749,
cache read 182,016, total 248,815 토큰이다. 이번 주 worker는 한 번만 실행했다.

현재 Gateway·Telegram polling·전송 큐·감시기는 정상이다. 최근 24시간의 종료된
exec 실패 2건과 과거 인증 선택 오류 문자열은 현재 서비스 장애로 판정하지 않았다.
9월 11일 main GLM fallback 승인 후 발생했던 감시 정책 오탐은 이미 복구·알림된
사건이므로 재시작이나 원래 작업의 재실행·재전송을 하지 않았다.

새 [OpenClaw 2026.9.4](https://github.com/openclaw/openclaw/releases/tag/v2026.9.4)는
업데이트 복원, 응답 전달과 인증 관련 변경을 포함한다. 현재 패치 명세의 지원 버전은
2026.9.2/2026.9.3이므로 새 패키지의 원본 해시와 각 패치 필요성을 별도로 검증해야 한다.
[Hermes 0.21.2](https://github.com/NousResearch/hermes-agent/releases/tag/v2026.9.11)는
state.db와 프로필 격리 보완을 포함하지만 현재 제한 worker에서 관련 장애는 관측하지 않았다.
설치 커밋·lock·도구 및 인증 계약을 검증하기 전에는 어느 런타임도 교체하지 않는다.
OpenClaw 릴리스 본문은 수집 시 일부가 잘렸으므로 추가 공식 페이지를 독립 검토했으며,
이를 새 버전 전체의 호환성 시험 완료로 해석하지 않는다.

Hermes 코드 후보는 비어 있었고 후보별 테스트·운영 반영은 없었다. 별도 독립 검토에서는
`collect_evidence`가 전역 기본 모델만 표시해 `agents.entries.main.model`에 설정된
GLM fallback을 누락하는 문제를 재현했다. 이를 수집 코드에서 수정해 main의 설정된
경로와 전역 기본값을 분리하고, 세션 override나 실제 선택된 provider를 관측한 값이
아님을 명시했다. 모델·인증·전송 설정은 바꾸지 않았다. 수정 전후 코드, 원복 자료,
회귀 검사, 설치본의 수집 결과와 Gateway 검증은 이 실행의 `operator-followup/`에
보존한다. 후속 검증에는 추가 Hermes 추론을 사용하지 않는다.

수정 후 controller 36개·설치기 13개·Gateway 정책 13개 검사가 통과했고, main 선택 규칙은
설치된 OpenClaw resolver와 합성 9종에서 일치했다. 설치본이 main GLM fallback을 내보내는
것과 전역 fallback이 비어 있는 것을 각각 확인했다. Gateway는 `ready`, Telegram은
`polling`이며 설정은 수정 전과 바이트 단위로 같았다. 설정의 main/global/session 구분과
실패 그룹이 원인 확정을 뜻하지 않는다는 절차를 근거·버전 조건과 함께 runbook 1.1.1에
반영했다. 1.1.1을 다음 모델 실행이 읽는 것은 아직 관측하지 않았다.

### 2026-09-12 verified operations knowledge

복구 경험을 다음 검토에서 사용할 수 있도록 지식 원장, 문제의 처리 상태, 변경분 중심
입력과 읽기 예산을 구현했다. 이 절은 구현 계약과 오프라인 검증을 설명한다.
이 변경의 설치, 실제 모델 실행과 후속 재사용 수용 결과는 별도 실행 영수증으로 확인한다.

지식 원장은 `/Users/jk/.local/share/openclaw-hermes-worker/operations/knowledge.json`이며
개인 대화 기억과 분리한다. Hermes의 일반 기억·백그라운드 자동 학습을 켜지 않는다.
OpenClaw가 Telegram 대화·주제 기억·최종 전달을 계속 담당하고, 운영 검토는 이 원장의
검증된 절차만 선택해 입력에 붙인다. 원장과 근거는 비공개 운영 경로에 두며 Git에 넣지 않는다.

#### 경험의 출처와 절차 승격

흐름은 `문제 등록 → 실제 수정·검사 → 경험 기록 → 절차 승격 → 새 실행에서 사용·검증`이다.
완료한 Hermes 검토가 발견한 문제는 `origin.kind: "hermes-review"`와 실행 ID로 기록한다.
OpenClaw/Codex가 독립적으로 발견한 문제는 `register-finding`으로
`origin.kind: "operator"`, 담당 실행 주체와 근거를 기록한다. 후자를 Hermes가 발견하거나
학습한 결과로 바꾸어 적지 않는다. 과거 기록에 출처가 없으면 `legacy-unknown`이다.

경험에는 `findingId`, `runId`, `cause`, `change`, `changeId`, `versions`,
`preconditions`, `steps`, `validation`, `verificationRef`, `operator`를 묶는다.
원인과 수정 설명은 검토한 실행 주체가 작성한 요약이다. 프로그램이 검사하는 것은 연결된
명령 영수증·출력 해시·실행 ID·버전·완료 상태이며, 원인 문장의 진실이나 작성자 신원을
암호학적으로 증명하는 기능은 아니다. 원문 대화, 인증 자료와 가공하지 않은 사건 로그를
재사용 절차에 저장하지 않는다.

`verificationRef`는 `{"path": "runs/<run-id>/verification.json", "sha256": "<실제 파일의 SHA-256>"}`
형태다. `<...>`는 설명용 자리이며 실제 기록에는 존재하는 파일과 64자리 소문자 해시가 필요하다.
검증 연결은 다음을 요구한다.

- 검증 묶음: `schemaVersion: 1`, `kind: "hermes-ops-verification"`, `runId`,
  `findingIds`, `changeId`, `versions`, `checks`.
- `checks`가 가리키는 각 명령 영수증: `kind: "hermes-ops-command-check"`, 같은 `runId`,
  고유 `checkId`, `checkType: "test"` 또는 `"runtime"`, `status: "completed"`,
  정수 `exitCode`, 순서가 맞는 `startedAt`·`finishedAt`, `commandSha256`, 출력의 `output` 참조.
- 최소 하나의 회귀 검사와 하나의 실제 동작 검사. 출력 파일도 해당 실행 디렉터리 안에
  있어야 하고 해시가 일치해야 한다. 단독 `verified: true`나 모델의 성공 문장은 근거가 아니다.

실패한 검증도 경험으로 보존할 수 있지만 절차 승격과 문제 종료에는 성공한 검증이 필요하다.
`promote-procedure`는 경험의 검증 연결을 다시 확인하고 절차의 ID, 정수 버전과 본문
SHA-256을 만든다. 같은 경험의 재승격은 중복 버전을 만들지 않는다. 새 경험으로 개정하면
이전 버전·경험·근거 참조를 보존한다. 예를 들어 main 설정과 전역 설정의 구분은 검증한
OpenClaw/Hermes 버전, 선택 규칙, 회귀 검사와 설치본 확인 절차를 함께 기록하는 대상이다.
이 예시만으로 해당 경험의 등록·승격·재사용이 실제 끝났다고 표시하지 않는다.

다음 검토에서는 현재 버전이 맞고 원래 검증 파일이 여전히 유효한 절차만
`learningContext.procedures`로 제공한다. 각 항목은 정확히
`{id, version, sha256, title, procedure, evidenceIds}`이며 worker가 본문 해시를 재검증한다.
버전이 미확인이거나 다르면 `version-mismatch`, 검증 연결이 손상되면 `evidence-invalid`로
분리한다. 설치기는 runbook의 관리 계약 구간을 갱신하고 그 밖의 검토된 기존 절차 문장을 보존한다.

#### 문제의 처리 상태와 재사용 결과

모델 findings의 `kind`는 `observation`, `issue`, `improvement`로 구분한다. 분류가 없는
과거 출력은 `legacy-unclassified`와 `observed`로 시작한다. 정상 관측을 모두 미해결 문제로
등록하지 않는다. 문제·개선 제안의 상태는 `open`, `in_progress`, `deferred`, `resolved`이며
처리 중·보류·종료에는 `owner`가, 보류에는 `defer_reason`이 필요하다.

`resolved`로 바꾸려면 해당 문제를 검증한 `closure_ref`가 필요하고, 이후 요약에서도
그 연결을 다시 검사해 `closureEvidenceValid`를 제공한다. 다음 보고서에서 빠졌다는 이유로
문제를 닫지 않는다. 종료 후 같은 ID가 다시 보이면 `seenAfterResolution`을 표시하며,
재개 여부는 현재 근거를 확인한 실행 주체가 결정한다. 원장에는 상태 변경 이력, 마지막
관측 실행과 반복 횟수가 남는다. 모델의 이전 분석 산문 전체는 다음 입력에 되돌리지 않는다.

Hermes는 선택적인 `procedure_uses`에
`{procedure_id, version, sha256, conclusion, evidence}`를 반환할 수 있다.
`conclusion`은 `referenced`, `applicable`, `not_applicable`, `reuse_claimed` 중 하나이며,
제공된 절차 ID·버전·해시와 허용 근거 ID만 사용할 수 있다. 이 값과 `skill_view` 성공은
읽거나 참조했다는 증거다. 실제 복구 성공이나 성공한 재사용 횟수로 세지 않는다.

`record-reuse`는 원래 승격 실행과 다른 새 실행, 정확한 절차 ID·버전·해시, 그 절차가
명시된 검증 묶음과 runtime 검사 영수증을 요구한다. 동일 절차 버전·실행의 중복 기록을
거부하고, 성공·실패 재사용을 분리한다. 원래 승격 근거와 재사용 근거를 다시 검사하므로
삭제되거나 바뀐 출력은 성공 횟수에서 제외하고 무효 근거로 표시한다.

설치 후 CLI의 쓰기 명령은 권한 `0600`의 JSON 파일을 `--payload-file`로 받는다.
아래 표의 이름은 실제 payload 키이며 snake_case와 camelCase를 임의로 바꾸지 않는다.

| 명령 | payload 키 |
| --- | --- |
| `register-finding` | `finding_id`, `classification`, `operator`, `run_id`; 선택 `owner`, `source_ref` |
| `update-finding` | `finding_id`; 변경할 `owner`, `state`, `classification`, `defer_reason`, `closure_ref` |
| `record-experience` | `id`, `findingId`, `runId`, `cause`, `change`, `changeId`, `versions`, `preconditions`, `steps`, `validation`, `verificationRef`, `operator` |
| `promote-procedure` | `experience_id`, `operator`; 선택 `procedure_id` |
| `record-reuse` | `id`, `procedureId`, `procedureVersion`, `procedureSha256`, `runId`, `verificationRef`, `operator` |

예를 들어 이미 등록한 문제를 담당자에게 배정하는 payload는
`{"finding_id":"main-routing","owner":"codex","state":"in_progress"}`다.
검증을 수행한 실행 주체가 실제 경험 payload를 준비한 뒤 사용하는 명령 예시는 다음과 같다.

```bash
python3 /Users/jk/.local/share/openclaw-hermes-worker/bin/hermes-operations.py record-experience --payload-file /private/tmp/hermes-ops-experience.json --json
python3 /Users/jk/.local/share/openclaw-hermes-worker/bin/hermes-operations.py knowledge --json
```

`record-applied`는 별도 배포 기록으로 유지한다. 배포를 기록한 것만으로 문제를 닫거나
절차를 승격하지 않는다. `knowledge` 조회는 확인할 수 있는 설치 버전으로 절차를 필터링하며,
모델 실행이나 Telegram 전송을 시작하지 않는다.

#### 변경분 우선 검토와 읽기 예산

요청의 `schemaVersion: 1`은 유지하고 `changeContext`, `learningContext`를 선택 필드로
추가했다. 변경분에는 이전 실행·스냅샷 ID, 달라진 근거 ID, 수정·삭제된 소스 경로,
소스 diff와 릴리스 diff를 담는다. diff는 파일당 최대 8KiB, 합계 최대 32KiB이며 잘림을
표시한다. 원문과 diff는 모두 신뢰하지 않는 자료이고 도구 권한을 확장하는 지시가 아니다.

완료한 기준 실행의 source snapshot뿐 아니라 영수증의 `evidenceSha256`, `upstreamSha256`과
실제 파일 바이트가 일치해야 baseline을 사용할 수 있다. 과거 영수증에 해시가 없거나 파일이
변조·누락된 경우에는 `baselineStatus: "unavailable"`, `fullReview: true`로 검토한다.
검증된 baseline과 같은 릴리스 본문만 축약하고 `bodyOmittedUnchanged`, `bodySha256`을 남긴다.
처음 검토, 실제로 실행하는 주간 검토, 명시적 `--full-review`는 전체 상태와 릴리스 본문을
포함한다. 주간 중복 억제와 변경 없는 사건의 모델 호출 생략은 유지되며 `--full-review`도
같은 주의 자동 재호출 금지 규칙을 우회하지 않는다.

| 예산·영수증 | 계약 |
| --- | --- |
| 소스 한 페이지 | 최대 200줄·16KiB |
| 누적 소스 전달 | 실행당 384KiB UTF-8 바이트 |
| 누적 재독 | 실행당 64KiB; 같은 줄·겹친 줄을 다시 전달한 바이트를 매번 합산 |
| 모델 입력 추정 | 호출당 180,000, 실행 누적 1,200,000; 직렬화한 요청 JSON의 UTF-8 바이트를 4로 나누어 올림 |
| `read_metrics` | 경로별 원본 해시, 읽은 줄 범위·수, 전체·고유·재독 바이트, 예산 거절 수; 소스 본문 없음 |
| `model_input_metrics` | 추정 방식·한도·호출 시도·응답·실패·예산 거절 수; 청구 토큰으로 표시하지 않음 |

소스 예산은 병렬 도구 호출에도 전달 전에 적용한다. 초과는 복구 가능한 도구 오류로
반환해 이미 읽은 근거로 결론을 정리하거나 확인하지 못한 범위를 알리게 한다. 모델 입력
추정 예산은 공급자 전송 전에 적용한다. 실패한 전송 시도도 누적하지만 SDK 내부의 재시도는
별도 관측하지 못한다. 전체 검토도 이 예산 안에서 수행하며 파일 전체를 읽었다고 보장하지 않는다.

controller의 `modelInferenceRequests`는 관측된 worker 호출 시도이고 `modelResponses`는
응답 수다. 시도 지표가 없는 과거 영수증에서는 요청 수를 `null`로 두며 응답 0회를
요청 0회로 해석하지 않는다. 실패·부분 실행에도 가용한 읽기·입력 추정 지표와 모델이
제공한 사용량을 보존한다. 추정치, 공급자 사용량, 캐시 포함 총량과 청구 비용은 별개다.

디스크의 근거 파일은 수집한 전체 집계 상태를 보존한다. 모델 입력에서만 늘어난 문제·절차
통계를 바이트 예산에 맞춰 줄이며 `procedureKnowledge.modelView.truncated`와
`omittedCounts`를 제공한다. 줄어든 입력에서 빠졌다는 이유로 문제가 없어졌다고 판단하지
않는다. 운영자의 상태 변경과 근거 유효성 변화는 중복 판단에 반영하지만, 검토 자체가 늘린
참조·관측 횟수만으로 다음 검토를 반복 실행하지 않는다.

#### 일반 업무의 절차 개선 파일럿과 오프라인 검증

일반 업무 확장의 첫 대상으로 합성 이벤트 보고서의 반복 오류를 교정하는 절차 파일럿을
준비했다. `hermes-procedure-pilot.py`는 학습 자료와 holdout을 분리하고 학습 단계에서
검증한 같은 규칙·해시로 새 입력을 처리하게 한다. 별도 실행 어댑터와 비교 계약은 실제
실행의 출처·출력·시간·사용량을 받아 확인한다. 측정값이 없거나 모델·provider·추론 수준·
절차·측정 범위가 다르면 비교 불가로 남긴다. 합성 검사 통과를 Hermes의 일반 업무 성능이나
OpenClaw 대비 속도 우위로 표시하지 않는다. 기존 일반 대화·문서 제작 경로를 자동 전환하지 않는다.

이 변경의 오프라인 검사는 controller 50개, 지식 원장 31개, 설치된 Hermes 런타임을 이용한
worker 경계 35개, 변경분 6개, 설치기 15개, 절차 파일럿 13개, 실행 어댑터 9개다.
명령 실행 성공·설치 해시·실제 모델의 절차 참조·독립 검증된 재사용은 후속 수용 기록에서
각각 확인하며, 이 검사 개수를 실제 배포나 새 모델 실행의 완료 증거로 사용하지 않는다.

#### 이번 설치와 수용 기록

2026-09-12 23:06 KST에 기존 설치기로 운영 파일 15개를 검증해 설치했다. 백업은
`/Users/jk/.local/share/openclaw-hermes-worker/operations-install-backups/20260912T140605Z-d44e3ec51e`,
설치 영수증은 같은 worker 루트의 `operations-install.json`이다. 설치 파일 해시 일치를
다시 확인했고 부모 인증·부모 설정·OpenClaw 설정 보존이 설치 영수증에 기록됐다.
23:20 KST에는 모델 결과의 문제 ID가 지식 원장 규칙과 일치하도록 최종 검사를 보완해
재설치했다. 최종 백업은 같은 `operations-install-backups/20260912T142030Z-006d624e88`이다.
새 모델 실행 없이 앞서 받은 합성 결과를 최종 설치본의 검증기로 재검증했다.
Gateway 재시작, 런타임 업그레이드, 새 스케줄, Telegram 전송은 수행하지 않았다.
설치본 runbook은 기존 frontmatter 1.1.1과 기존 절차 문장을 보존하면서 관리 계약 rev2를 반영했다.

실제 설정 점검 사례의 운영 지식 연결은 다음 영수증으로 확인했다.

- 최초 경험·종료 검증 실행: `routing-seed-20260912T140618Z-59f2ac`.
  이전 main/global 수집기 오류의 출처는 `operator: codex`다. 현재 회귀 검사와
  설치된 수집기의 실제 Mac 설정 읽기 및 합성 경계 사례 4개를 각각 실행했다.
- 승격 절차: `configured-main-routing`, version 1,
  SHA-256 `48d50613cab32bb2808aa5c0e23a26735d107949170443444aee1af4a2075525`.
  적용 버전은 OpenClaw 2026.9.3 / Hermes 0.21.1이다.
- 별도 재사용 검증 실행: `routing-reuse-20260912T140630Z-9cbab8`.
  같은 절차 ID·버전·해시를 검증 묶음과 실제 설치본 검사에 연결했고 결과는 `verified`다.
  이 검사는 설치된 **설정 수집기**의 동작을 확인한 것이며 실제 세션의 모델 선택 증거는 아니다.
- 기존 주간 검토 `ops-20260912T010131Z-6b648126`의 원래 분석과 완료 영수증을 확인해
  발견사항 5개를 이관했다. 현재 운영 정상·과거 인증 흔적은 `observed`, 과거 종료 작업의
  처리 확인 및 OpenClaw/Hermes 업그레이드 사전 검증은 담당 `codex`와 이유가 있는
  `deferred`다. 원장 합계는 문제/관측 6개, 경험 1개, 절차 1개, 검증된 재사용 1개다.
- 실제 상태의 모델 없는 수집은 `ops-20260912T140804Z-e5eac3f7`로 완료했다.
  소스 124개, 실제 절차 1개와 상태 6개로 구성한 다음 worker 입력은 로컬 스키마·해시
  검사를 통과했다. 모델용 evidence 9,384 bytes이며 외부 전송하지 않았다.

기존 `hermes-openclaw` heartbeat는 토요일 10:00 KST 일정과 대상 작업을 유지한다.
프롬프트에 수정 후 로컬 상태·경험·승격·종료·재사용 기록 절차를 추가했고, 동일 예약에서
추가 추론이나 강제 재실행을 만들지 않도록 명시했다. 이 갱신은 다음 자연 실행의
기록 지침이며 다음 예약 실행 자체가 이미 성공했다는 증거는 아니다.

합성 보고서 파일럿은 두 실행기 모두 모델이 수정 절차를 제안했고, 별도 새 사건 입력에서
그 절차를 변경 없이 재사용해 보고서의 정확성 검사를 통과했다. 동일 Sol/high, 동일 입력·
계약·절차 해시를 사용했지만 OpenClaw는 bootstrap·도구가 없는 incognito raw-model 기준이고
Hermes는 기존 합성 보고서 스킬을 읽는 worker다. 전체 Telegram 제작·전달 경로 비교가 아니다.

| Holdout 관측 | OpenClaw | Hermes |
| --- | --- | --- |
| 보고서·절차 검증 | 통과 | 통과 |
| 경과 시간 | 19.729초 | 22.753초 |
| 비캐시 입력 / 캐시 읽기 / 출력 토큰 | 관측 불가 | 6,189 / 4,864 / 913 |
| 스킬·설정 보존 | 확인 | 확인 |

OpenClaw raw-mode의 일시적 transcript에서 토큰 계수를 얻을 수 없어 비교 결과는
`blocked: openclaw:missing_measurements`다. 누락을 0으로 채우거나 계정 사용량 차이로
추정하지 않았다. 한 쌍의 결과로 속도·비용 우위나 일반 업무 확대를 결정하지 않는다.
실제 실행 어댑터는 소유한 incognito만 정리했고 원래 세션·스킬을 변경하지 않았다.

첫 OpenClaw 어댑터 시도는 backend 전용 인자를 CLI가 거절해 추론 전에 끝났다.
해당 인자를 제거한 실제 완료 출력은 보존했고, raw-mode history 부재를 처리하기 위해
추론을 반복하지 않고 완료 출력을 검증에 사용했다. Hermes 합성 실행은 처음에 기존
보고서 스킬의 민감 자료 가능성 때문에 자동 승인이 거절됐다. 스킬 3,491 bytes가
저장소의 공개 합성 계약과 정확히 일치함을 확인하고, 내용이 달라지면 호출을 차단하는
검사를 추가한 뒤 같은 합성 실행이 승인됐다.

실제 운영 설정·문제 상태·검증 절차를 넣는 추가 Hermes 모델 실행은 자동 승인 검토가
처음에는 외부 모델 전송 권한을 이유로 거절했다. 이후 소유자가 최종 검증을 명시 승인해
아래의 실제 운영 자료 수용 검증을 완료했다. 최초 거절·합성 실행·승인 후 실제 실행은
각각 별도 영수증으로 보존한다.

별도로 설치된 운영 worker에 **합성 자료만** 넣은 1회 모델 검증은 승인되어 완료했다.
기존 runbook 전체가 저장소의 일반 절차 문장과 일치하고 소유자 개인정보·실제 운영 자료가
없음을 사전 검사했다. 요청에는 386-byte 장난감 소스, 합성 설정·상태 2개·절차 1개만 넣었다.
36.482초, 모델 응답 4회에 정확한 절차 ID·버전·해시를 `applicable`로 반환했고,
종료된 합성 문제와 정상 상태 모두 `observation`으로 분류했다. 실제 복구 성공이라고
주장하지 않았으며 코드 후보·runbook 후보는 비어 있었다. source 전달 386 bytes,
재독 0 bytes, 모델 입력 추정 합계 34,441로 예산 거절은 없었다. 공급자 계수는 비캐시
입력 16,872 / 캐시 읽기 10,624 / 출력 1,104 / 보고 총량 28,600이다.
이 작은 합성 과제를 과거 전체 운영 검토 128초와 비교해 효율 개선율을 계산하지 않는다.

상세 자료는 저장소의 비공개·Git 제외 경로
`reports/hermes-priorities-20260912T134826Z/`에 있다. `local-contract-verification.json`,
`seed-acceptance.json`, `reuse-acceptance.json`, `lifecycle-import.json`,
`automation-update-verification.json`과 `procedure-pilot/evaluator/runtime-comparison.json`을
각각 대조할 수 있다. 운영 모델의 합성 검증은 `synthetic-ops/acceptance.json`과
`synthetic-ops/worker-receipt.json`에 있다. 12개 회귀 묶음과 보완한 실행 어댑터 검사가 통과했으며,
기존 patch sandbox 선택 검사 3개는 환경 opt-in 미설정으로 건너뛴 사실을 보존했다.
host scripts 34개와 `verify-gateway.sh --telegram --json`의 실제 Gateway ready / Telegram
polling 검사도 통과했다. 모델 호출 없는 기본 sandbox에서 인증 상태 읽기가 제한된 최초
검사는 실패했고, 허용된 로컬 상태 조회로 재검사해 통과했다.

#### 2026-09-12 승인 후 실제 운영 자료 최종 검증

소유자의 명시 승인 후 23:26:21~23:29:04 KST에 준비된 입력을 기존 독립 인증의
Hermes Sol/high로 한 번 실행했다. 요청 SHA-256은
`8930ea73c01443fed246642c0057438ff66b0f794123feb26092a1aaf72eaff7`이다.
실행 전에 준비된 설정·설치 버전·검증 절차·문제 상태가 현재 값과 일치함을 확인했다.
입력 운영 스냅샷은 23:08 KST의 자료이며 source bundle은 고정된 시점의 소스다.
전체 124개 소스 중 5개는 후속 문서·worker·시험 수정 전 내용이지만 이번 점검 대상인
`configured_main_routing` 수집기 소스는 현재 설치본과 정확히 일치했다. 전체 최신 소스
재검토나 변경분 효율 비교로 설명하지 않는다.

worker 계약 검사와 별도 의미 검토가 모두 통과했다. main의 Astra/high·GLM fallback과
전역 defaults의 빈 fallback을 구별했고, 이를 실제 세션/공급자 실행 증거로 확대하지
않았다. `configured-main-routing-projection`은 유효한 종료 근거가 있는 `resolved`로
유지했으며, `configured-main-routing` v1의 정확한 해시를 `applicable`로 참조했다.
코드·runbook 변경 후보는 비어 있었고 설정·스킬·지식 파일은 모델 실행 중 동일했다.
Telegram 전송, 인증 복제, Gateway 재시작은 수행하지 않았다.

| 실제 운영 자료 검증 지표 | 결과 |
| --- | --- |
| worker 경과 시간 / 전체 wrapper 시간 | 162.665초 / 162.929초 |
| 응답 / 실행 시도 | 6회 / 1회 |
| 소스 읽기 | 수집기 150~349줄 1회, 10,340 bytes |
| 재독 / 예산 거절 | 0 bytes / 0회 |
| 누적 모델 입력 추정 / 단일 호출 최대 | 137,077 / 30,355 |
| 공급자 비캐시 입력 / 캐시 읽기 / 출력 | 28,168 / 111,104 / 4,215 tokens |
| 공급자 보고 총량 | 143,487 tokens; 청구 비용과 별도 |

`ops_check_candidate`는 한 차례 실패한 뒤 성공했다. 최종 빈 후보의 계약 검사와
검증 결과는 유효하지만 모든 도구 호출이 처음부터 성공한 것은 아니다. 첫 로컬 결과
검사기는 모든 발견사항이 observation일 것으로 가정해 실제 `backup-stale` 이슈를
거절했다. 입력의 명시된 이슈 목록과 독립 검토를 근거로, 목표 항목은 observation이어야
하고 실제로 제공된 이슈만 허용하도록 검사 기준을 바로잡았다. 모델 출력을 수정하거나
모델을 재실행하지 않았다.

Hermes는 입력에 있던 `backup-stale`을 별도 이슈로 정확히 구분했다. 23:30 KST 로컬
재확인에서도 마지막 검증 백업은 9월 9일 22:57 KST, age 261,167초로 남았으며
운영 종합 값은 `ok:false`, 이슈는 `backup-stale`이었다. observer의 Gateway 상태는
healthy지만 consecutiveFailures는 5였고 원인 추정으로 Gateway 장애를 선언하지 않았다.
최종 모델 검증의 성공은 백업 갱신이나 전체 운영 정상화를 뜻하지 않는다.

실제 반환 결과와 검증 영수증을
`operations/runs/prepared-private-ops-acceptance-20260912`에 보존하고 원장의 참조를
기록했다. 기존 종료 상태와 검증된 재사용 1회는 유지되며 절차 referenceCount가 1이 됐다.
발견사항은 8개로, observation 3개·deferred 3개·resolved 1개·open 1개다.
새 `backup-stale`은 담당 `codex`, 상태 `open`으로 기록했다.

최종 근거는 기존 보고서 폴더 아래 `prepared-private-ops-acceptance/acceptance.json`,
`worker-receipt.json`, `current-local-status.json`, `knowledge-reference-record.json`에 있다.
현재 `completion.json`과 `RESULT.md`는 최초 승인 대기 상태를 이 최종 결과로 갱신했다.

<a id="2026-09-12-backup-stale-repair"></a>

#### 2026-09-12 백업 기한 경과 후속 복구

위 23:30 KST의 `backup-stale` 관측 이후 소유자가 해결을 요청했다. 새 백업과 별도
경로 복원 검증은 23:47:43 KST에 `VERIFIED`로 완료됐다. SQLite 42개가 모두 integrity
검사를 통과했고 파일 57,335개와 runtime 복구 자료 128개를 보존했다. archive와 manifest의
SHA-256을 독립적으로 다시 계산해 영수증과 대조했고 최신 성공 영수증의 일치도 확인했다.

첫 시도에서는 공식 archive 생성·검증 후 로컬 복원 사전 검사가 실패했다. 과거 복구 때
비활성 상태로 보존한 두 파일럿의 `codex-home/tmp/arg0`에 임시 실행 링크 6개가 있었고,
그 대상인 npm runtime은 공식 archive 범위에서 제외됐다. 대상 실행 파일은 실제 Mac에는
존재했으므로 라이브에서 끊어진 링크였다는 뜻은 아니다. 해당 링크 6개만 원래 문자열·경로·
inode·권한·mtime을 기록한 뒤 별도 비공개 격리 폴더로 이동했다. 링크와 대상 실행 파일을
삭제하지 않았으며 원복 manifest를 state와 새 백업·복구본에 함께 보존했다. 누락 대상·경로
탈출·링크를 통한 덮어쓰기 거부 규칙은 변경하지 않았다.

23:48:40 KST 후속 검사는 `operationsOk=true`, `issues=[]`, `warnings=[]`,
observer `consecutiveFailures=0`을 확인했다. Gateway 상태도 정상이었고 백업 경과 시간은
57초였다. 복구본은 활성화하지 않았으며 이번 복구에서 Gateway 재시작, 모델 호출,
Telegram 전송은 없었다. 반복 백업 자동화도 추가하지 않았다. 이 검증 범위는 OpenClaw
state와 runtime 복구 자료이며 별도 Hermes 저장소 전체의 백업을 뜻하지 않는다.

23:54 KST에는 실제 시험·실행 영수증을 Hermes 운영 지식 저장소에 연결해 `backup-stale`을
`resolved`로 종료했으며 `closureEvidenceValid=true`를 확인했다. 최초 실패 경험과 성공한
수정 경험을 각각 추가했고, 실패는 승격하지 않았다. 성공 경험만
`refresh-openclaw-verified-backup` v1로 승격했으며 절차 SHA-256은
`63b53b06fab56e694ffb90734aab04f321cfb57853bb35c3d798a96c363951ef`다.
발견사항 8개의 현재 상태는 `observed` 3개·`deferred` 3개·`resolved` 2개이며 열린 문제는
0개다. 전체 경험 3개·절차 2개·검증된 재사용 1회가 보존됐다. 새 백업 절차의 재사용은
아직 0회이며 이번 승격을 재사용 성공으로 세지 않았다. 기존 설정 점검 절차의 재사용
1회는 그대로 유지했다.

근거는 [복구 결과](../reports/backup-stale-repair-20260912T143847Z/RESULT.md)의
`retry-1/backup-verification.json`, `restored-health-verification.json`,
`alias-relocation.json`, `knowledge-closure-receipt.json`에 있다. 최초 실패와 성공한 재시도는
별도 영수증으로 보존했다.

### 2026-09-19 주간 점검과 후속 복구

자연 주간 실행 `ops-20260919T010404Z-8db23c12`는 한 번 실행했고 실제 종료 코드는 1이었다.
Hermes Sol/high가 네 번 응답한 뒤 누적된 소스 결과 때문에 다음 입력이 worker의 추정 예산에
걸려 `MODEL_INPUT_ESTIMATE_BUDGET_EXHAUSTED`로 종료됐다. 제공된 사용량은 비캐시 입력
49,766·캐시 읽기 82,048·출력 1,848, 총 133,662 tokens다. 이는 구독 한도 오류가 아니다.
최종 분석과 후보 검사 결과는 없으며 원본 실패 영수증과 사용량을 보존했다. 재시도나 다른
모델·인증 경로 전환은 하지 않았다.

수집기가 별도로 확인한 현재 문제는 `backup-stale`이었다. Gateway/Telegram은 정상이고
9월 12일 검증 백업의 나이가 555,381초로 72시간을 초과했다. 기존
`refresh-openclaw-verified-backup` v1을 적용해 10:08 KST 새 백업과 격리 복원을 완료했다.
SQLite 42개와 파일 57,678개, archive·manifest SHA-256과 최신 영수증의 일치를 확인했다.
21개 백업 회귀 검사와 설치된 observer의 무전송·무추론 재확인이 통과했으며 `issues=[]`,
`consecutiveFailures=0`으로 회복됐다. 설정·기존 백업을 보존했고 링크 이동이나 Gateway
재시작은 필요하지 않았다. 새 경험과 기존 절차의 실제 재사용 1회를 기록하고 같은 검증
묶음으로 `backup-stale`을 다시 종료했다. 주간 점검 간격보다 백업의 유효 기간이 짧으므로
향후 갱신 주기 개선은 별도 보류 항목으로 남기며 이번 점검이 새 예약을 만들지는 않는다.

독립 코드 검토로 worker의 조기 최종 작성 전환을 추가했다. 입력 추정이 호출당 한도의
절반에 도달하거나 누적 여유가 현재 입력 두 번보다 작거나 마지막 회차이면, 기존 회차를
`tool_choice=none`으로 전환해 확보한 근거와 미검토 범위를 보고하도록 한다. 원래 근거와
도구 결과를 삭제하지 않으며, 추가 지시까지 포함해 기존 60,000/180,000 한도를 다시
검사한다. 최종 전환 뒤 도구 실행과 추가 호출은 거절한다. 임의로 큰 요청이 언제나
완료된다는 보장은 없으며 기존 제한을 넘는 요청은 계속 거절한다.

소스 회귀 검사 117개(worker 39·controller 50·installer 15·Telegram 정책 13)가 통과했다.
공식 설치기의 비공개 백업 후 설치했고, 설치본을 pinned Hermes runtime에 로드해
네트워크를 차단한 mock 검사 4개와 실제 Gateway/Telegram 검사를 통과했다. 이 검사는
실제 모델의 후속 분석 성공과 다르다. 해당 문제는 다음 자연 검토의 최종 분석 완료 확인까지
담당 `codex`의 `deferred`로 유지하며 수정 경험을 새 검증 절차로 승격하지 않는다.

이번 토요일 09:00 AI·LLM 브리핑도 별도로 `SUMMARY_NOT_VERIFIED` 상태였고 전달이
시작되지 않았음을 내용 없는 상태 자료로 확인했다. 공개 출처 수집 실패는 없었으며 원래
기준점·미완료 실행을 보존했다. 이 운영 점검에서 이전 사용자 업무를 재시작하지 않는
계약에 따라 브리핑을 재실행·재전송하지 않고 담당과 보류 이유를 기록했다.

설치 버전은 OpenClaw 2026.9.3/Hermes 0.21.1을 유지한다. 공식
[OpenClaw v2026.9.4](https://github.com/openclaw/openclaw/releases/tag/v2026.9.4)는 기존 검토
대상이며, [Hermes v0.21.3](https://github.com/NousResearch/hermes-agent/releases/tag/v2026.9.14)는
새 검토 대상이다. 이번 실패만으로 런타임 교체 필요성을 판정하지 않았다.

비공개 근거는 해당 실행의 `operator-followup/` 아래에 있다. `backup-verification.json`은
실제 회귀·백업·해시 대조·설치된 observer 명령을 연결하고, `worker-verification.json`은
코드 검사·설치·설치본의 mock 동작·Gateway 확인을 연결한다. 두 파일은 위
`verificationRef` 규격의 경로와 SHA-256으로 경험에 등록하며, `completion.json`에서
원본 주간 실패, 백업 복구, 코드 설치, 실제 모델 수용 대기를 구분한다.

### 2026-09-27 장애 분석 범위 한정과 근거 불충분 보존

9월 11~27일 자동 장애 분석 8회 중 완료는 1회였다. 실패 7회 중 5회는 입력 추정 예산 초과,
1회는 시간초과, 1회(9/23)는 발견 하나의 근거 ID가 허용 목록에 없어 170초 분석 전체가 버려진
경우였다. 영수증을 보면 장애 분석도 소스 124~128개(약 1.8MB)와 릴리스 노트를 받았고, baseline이
없으면 `fullReview`가 켜져 사건과 무관한 `hermes-operations.py`, 테스트, 문서를 읽었다. 9/19·9/22
실패는 한 번의 병렬 읽기 묶음이 다음 요청을 호출당 60,000 한도 위로 밀어 올린 경우였고, 이때
조기 최종 작성 전환은 발동하지 않았다(`finalization_dispatches=0`).
소유자 요청으로 다음 세 가지를 바꿨다. 모델 호출은 추가하지 않았다.

- **장애 분석 범위 한정.** `--mode incident`는 감시기 상태 파일
  (`telegram-watchdog-status.json`)에서 사건 ID, 시작·회복 시각, 사건 이슈와 Hermes에 넘긴 이슈,
  마지막 Gateway 실패 코드를 고정 코드로만 읽어 `evidence.incident`에 넣는다. 알림 문구, 검사 출력,
  요약 본문은 읽지 않는다. 모델이 읽을 수 있는 소스는 해당 사건의 검사로 제한한다. 항상
  `telegram-ops-status.py`와 `OPENCLAW_OPERATIONS_KO.md`를 넣고, Gateway 계열이면
  `verify-telegram-gateway.py`·`telegram-watchdog.py`·`runtime-patch-specs.json`을,
  `task-long-running`이면 `telegram-task-status.py`·`OPENCLAW_TASK_STATUS.md`를 더한다.
  릴리스 노트는 수집하지 않고(`upstreamSkipped: incident-scope`, `upstreamComplete: null`),
  검토 이력·적용 기록·패치 범위는 모델 입력에서 뺀다. baseline이 없어도 `fullReview`는 켜지지
  않는다. 후보 검사용 snapshot과 디스크의 `evidence.json`은 전체를 유지한다. 명시적
  `--full-review`는 이전의 넓은 동작을 그대로 쓴다. 사건 이슈 목록은 감시기의
  `HERMES_INCIDENT_ISSUES`와 같아야 하며 테스트가 이를 확인한다.
- **도구 한 묶음의 소스 상한.** worker는 매 요청을 예약한 뒤 다음 요청이 호출당 한도와 남은 누적
  한도 안에 들도록, 이번 도구 묶음이 전달할 수 있는 소스 바이트를 계산한다(확장 계수 1.25,
  예비 6,000). 한도를 넘는 페이지는 잘라서 `next_line`을 주고, 한 줄도 들어가지 않으면 복구 가능한
  `SOURCE_ROUND_BUDGET_EXHAUSTED`를 돌려준다. 9/19 조건(직전 27,702, 누적 76,371)을 재현한
  시험에서 다음 요청은 60,000 이하로 유지됐다. 이 상한은 주간·수동 검토에도 적용된다.
- **근거 ID 오류 보존.** 발견의 근거 목록에 허용되지 않은 항목이 있으면 그 항목만 지우고,
  발견에 `evidenceStatus: "insufficient"`와 `unverifiedCitationCount`를 붙인 뒤 나머지 분석과
  발견을 유지한다. 목록 형식 오류, 13개 이상, 모델이 직접 붙인 `evidenceStatus`는 여전히 거부한다.
  controller 영수증은 `insufficientEvidenceFindingIds`를, 보고서는 제목 뒤 `(근거 불충분)`을,
  지식 원장은 `evidenceInsufficient`를 기록한다. 같은 발견이 이후 근거와 함께 다시 보이면
  표시가 지워진다. `procedure_uses`의 근거 검사는 바꾸지 않았다.

검증: worker 43개(설치된 Hermes 0.21.1 runtime), controller 58개, 지식 원장 31개, 변경분 6개,
설치기 15개, 패치 18개(환경 opt-in 3개 건너뜀), 절차 파일럿 13개, 감시기 운영 시험이 통과했다.
공식 설치기로 설치했고, 한정 지시문 조건을 고친 두 번째 설치의 백업은
`operations-install-backups/20260927T093433Z-d75815d806`이다(첫 설치 `20260927T093219Z-6b6f9b21a9`). 설치본 해시가 저장소와 같고 두
`telegram-ops-status.py` 설치본은 바뀌지 않았다. 모델 없이 실제 운영 상태와 9/27 사건
`33cc4c082a72` 기록으로 장애 분석 요청을 만들어 보니, 소스는 5개 93,012 bytes, 첫 요청 추정은
4,258로 9/27 실제 실행의 18,195보다 작았다. `record-applied`로 적용을 기록했다. Gateway 재시작,
Telegram 전송, 모델 호출은 없었다. 새 장애 분석의 실제 모델 실행은 다음 자연 사건에서 처음
관찰한다. 현재 운영이 정상이면 `--mode incident`는 모델 없이 `healthy-no-incident`로 끝난다.

주간 점검 heartbeat `hermes-openclaw`는 2026-09-23 21:51:42 KST부터 `PAUSED`다. 같은
21:51:41~43 KST 2초 사이에 Codex 자동화 6개(autobot 4개, `daily-bug-scan`, `hermes-openclaw`)가
모두 멈췄다. 그 시각 Codex Desktop 창이 활성 상태였고 Desktop 로그에 에이전트의
`automation_update` 호출은 없었다. 따라서 앱 화면에서 소유자가 직접 멈춘 것으로 보며, 장애나
에이전트가 멈춘 것은 아니다. 그래서 9월 26일 주간 점검은 실행되지 않았다. 소유자 요청으로
2026-09-27 22:11 KST에 ChatGPT(Codex) 앱의 예약 화면에서 `hermes-openclaw`만 재개했다. 앱에
`활성`, 다음 실행 10월 3일 토요일 10:00이 표시됐고, 파일에도 `status = "ACTIVE"`가 기록됐다.
자동화 상태는 앱의 SQLite `automations` 테이블이 관리하므로 `automation.toml`을 직접 고치지
않았다. 나머지 5개는 `PAUSED`로 두었다. 재개 후 첫 자연 실행은 아직 일어나지 않았다.

### 2026-09-27 운영 검토 입력 한도 상향

소유자 요청으로 운영 검토의 입력 한도를 올렸다. 같은 날 22:26 KST 수동 검토
`ops-20260927T132651Z-6ae8174c`는 네 번째 호출 추정이 52,521로 호출당 한도 60,000의 절반을
넘어 `per_dispatch_headroom` 조기 최종 작성으로 끝났다. 소스 9개 87,180 bytes만 읽었다.
실패는 아니지만 전체 소스 감사가 되지 못했다.

| 한도 | 이전 | 변경 |
| --- | --- | --- |
| 호출당 입력 추정 (조기 최종 작성은 절반부터) | 60,000 | 180,000 |
| 실행 누적 입력 추정 | 180,000 | 1,200,000 |
| 누적 소스 전달 / 재독 | 128KiB / 24KiB | 384KiB / 64KiB |
| 모델 호출 횟수 / 도구 호출 수 | 16 / 40 | 24 / 96 |
| worker 실행 시간 / controller 대기 | 240초 / 300초 | 900초 / 960초 |

호출당 한도는 Codex OAuth의 `gpt-5.6-sol` 272K 창(설치된 Hermes 0.21.1 `agent/model_metadata.py`)에서
추론·출력과 한글에서 bytes/4 추정이 적게 나오는 오차를 남기도록 정했다. 조기 최종 작성, 소스
round 한도와 예산 거절 규칙은 그대로다. 사용량은 기존 Hermes Sol OAuth 경로의 구독 한도에서
나가며, 실행당 사용량이 늘 수 있다.

검증: 과거 사건 재현 시험 3개는 60,000 기준에서 쓰던 크기의 비율을 새 한도에서도 유지하도록
바꿨다(60,000에서는 원래 값과 같다). worker 43개, controller 58개, 지식 원장 31개, 변경분 6개,
패치 18개(3개 건너뜀), 설치기 15개, 보고 worker 24개, 절차 파일럿 13개가 통과했다. 공식
설치기로 설치했다. 백업은 `operations-install-backups/20260927T133417Z-edbb047646`이며,
설치본 두 파일이 저장소와 같고 운영 프로필 설정이 `max_turns 24`, `run_budget_seconds 900`인 것을
확인했다. Gateway 재시작과 Telegram 전송은 없었다.


실제 실행 확인: 설치 직후 22:34 KST 수동 검토 `ops-20260927T133422Z-120fc7be`가 140초 만에
`reviewed`로 끝났다. 모델 호출 12회(이전 4회), 소스 읽기 24회·11개 228,095 bytes(이전 9회·87,180),
단일 호출 최대 추정 96,189, 누적 추정 604,330이었고 예산 거절과 실패 호출은 없었다. 공급자
사용량은 비캐시 입력 170,053·캐시 읽기 424,064·출력 7,180, 총 601,297 tokens다. 그래도
`per_dispatch_headroom`(추정 90,000 이상)으로 조기 최종 작성에 들어갔다. 전체 소스 묶음은
127개 1,896,001 bytes(추정 약 474,000)로 모델 창 272K보다 크다. 따라서 한 번의 실행으로 전체
소스를 감사하는 것은 한도 상향만으로는 불가능하며, 영역별로 나눈 검토가 필요하다.

### 2026-09-27 영역별 소스 검토 (`--area`)

한도 상향 뒤에도 전체 소스 묶음(127개, 약 1.9MB)은 모델 창보다 크므로, 소유자 요청으로
controller에 영역별 검토를 추가했다. `run --mode manual --area <영역>`은 한 영역의 소스만 모델에
제공하고, `--area all`은 비어 있지 않은 영역마다 worker를 하나씩 순서대로 실행한 뒤
`area-audit` 요약(영역별 runId·상태·보고서·조기 종료 이유, `failedAreas`)을 반환하고
`operations/latest-area-audit.json`에 남긴다. 한 영역이 실패해도 나머지 영역은 계속 실행한다.

| 영역 | 소스 수 | 크기(bytes) |
| --- | --- | --- |
| telegram-workflows | 8 | 181,299 |
| telegram-tasks-productivity | 13 | 127,489 |
| telegram-operations | 10 | 156,357 |
| telegram-gateway-delivery | 17 | 186,798 |
| gateway-install | 24 | 208,811 |
| gateway-acceptance | 4 | 204,494 |
| runtime-patches-models | 24 | 179,654 |
| hermes-controller | 7 | 174,721 |
| hermes-worker | 6 | 174,102 |
| hermes-knowledge-install | 6 | 131,234 |
| learning-pilots | 8 | 183,811 |

코드는 테스트와 같은 영역에 두고, 각 영역이 조기 최종 작성 전에 모두 읽힐 수 있도록 약 200KB로
맞췄다. 경로는 선언 순서상 처음 맞는 영역에 속하며, 어느 영역에도 맞지 않는 새 파일은
`unassigned` 영역으로 검토되어 빠지지 않는다. 시험은 실제 저장소의 모든 소스가 정확히 한 영역에
속하고, `unassigned`가 비어 있으며, 각 영역이 240KiB 이하인지 확인한다.

영역 검토는 수동 모드에서만 허용한다(주간·사건 모드와 결합하면 `AREA_REQUIRES_MANUAL_MODE`로
모델 호출 없이 거절). 업데이트 영향과 릴리스 노트는 받지 않으며(`upstreamSkipped: area-scope`),
스냅샷은 전체를 유지해 코드 후보는 전체 트리에서 검사한다. 영역 기준선은
`operations/area-reviews.json`에 따로 두므로 주간 검토의 중복 억제 기준(`last-review.json`)을
바꾸지 않는다. 운영 스킬 템플릿에 전체 검토 요청 시 `--area all` 사용법을 추가했다.

검증: controller 65개(신규 영역 검토 7개 포함), worker 43개, 지식 원장 31개, 변경분 6개, 패치
18개(3개 건너뜀), 설치기 15개, 보고 worker 24개, 보고서 16개, 절차 파일럿 13개, 텔레그램 운영
시험이 통과했다. 첫 설치 후 설치본 CLI로 `--area all --mode weekly` 거절을 확인하다가 이 조합이
예외로 끝나는 결함을 발견했다(모델 호출·주간 기록 변경 없음). 거절 처리와 시험을 추가해 다시
설치했고(백업 `operations-install-backups/20260927T134401Z-9468efe141`), 설치본이 저장소와 같고
같은 명령이 모델 호출 없이 `AREA_REQUIRES_MANUAL_MODE`를 반환하는 것을 확인했다.
