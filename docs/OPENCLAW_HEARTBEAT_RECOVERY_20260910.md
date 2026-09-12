# Codex 제한 상태 이후 Heartbeat 복구

2026-09-10 KST. 현재 Mac/Telegram 배포의 사용 제한 복구 기록입니다.

## 확인한 문제

02:29에 `heartbeat-main`이 Codex 구독 한도 오류를 기록했습니다. 이후 요청은
OpenClaw에 남은 `subscription_limit` / `wham` 차단 때문에 모델 실행 전 실패했습니다.
05:38의 해당 OAuth 사용량 직접 조회는 `allowed=true`, `limit_reached=false`,
주간 사용량 74%였습니다. 현재 사용 가능 여부와 오래된 로컬 차단 상태가 달랐습니다.

사용자는 다른 Codex 프로젝트도 한도 오류로 중단됐다고 보고했습니다. 최초 오류가
Codex 공통 서비스에서 발생했을 가능성은 있지만, 최초 서버 판정의 원인은 확정하지
않았습니다. 이번 수정은 OpenClaw가 남은 차단에서 회복하지 못하는 문제를 다룹니다.

현재 Astra와 Sol은 모델별 `agentRuntime.id=codex`를 명시합니다. 이 경로는
`skipsProviderAuthCooldown`으로 WHAM 재검사 호출을 건너뛰지만, 실제 인증 준비 단계는
같은 차단 상태를 검사합니다. 로그인 자체와 Telegram polling은 정상이어도 요청은
계속 실패할 수 있었습니다. 반복 Heartbeat 실패 안내는 실제 메인 채팅 응답 가능성을
검증한 문구가 아닙니다.

## 복구 방법과 보존 범위

- 설정과 main SQLite를 비공개 운영 디렉터리에 백업하고 SQLite 무결성을 확인했습니다.
- 설치된 공식 `maybeReprobeWhamBlockedProfiles`에 정확한 main OAuth 프로필만 전달했습니다.
  서버의 사용 가능 응답 후 `blocked*` 제거와 `lastProbeAt` 갱신을 확인했습니다.
  토큰·오류 이력·다른 인증 상태를 일괄 초기화하지 않았습니다.
- 명시적 Codex 경로에서도 기존 공식 재검사를 실행하도록 단일 런타임 파일을 수정했습니다.
  기존 인증 선택, 모델·프로필 범위, 실제 한도 차단과 45분 간격, 동시 변경 보호는 유지합니다.
- 재검사는 백그라운드로 실행되므로, 향후 최초 재시도는 실패하고 다음 요청부터 회복될
  수 있습니다. 실제 한도 소진을 우회하거나 Codex 공통 서비스 오류를 해결하는 패치는 아닙니다.
- 설정의 Astra/high 기본값, Codex 실행 필수 정책, 자동 유료 대체 금지와 기존 대화를 보존합니다.
  Heartbeat 주기나 알림 설정은 변경하지 않습니다.

## 검증

- 패치의 핵심 회귀 12개와 구문 검사 통과.
- 통합 검사 57개 통과: runtime 25, backup 21, Telegram gateway 정책 11.
- 운영 감시 회귀 25개 통과. 이번 복구의 관련 검사 합계는 94개입니다.
- 짧은 합성 모델 실행에서 요청·실제 응답 모델 모두 `openai/gpt-6-astra`,
  응답 `RECOVERY_OK`, reroute 없음, 도구 실행 없음 확인. high를 요청했습니다.
  합성 대화는 삭제했고 Telegram으로 보내지 않았습니다.
- 최초 합성 검사는 `promptMode=none`에 필요한 `modelRun=true`가 없어 모델 호출 전
  거부됐습니다. 해당 임시 대화도 삭제했고, 원래 실패 영수증과 정정 후 성공 영수증을
  각각 보존했습니다.
- 유휴 상태 세 표본 확인 후 재시작했습니다. 재시작 이후 Gateway ready와 Telegram polling,
  기존 `heartbeat-main` 수동 실행 성공(8.310초), 연속 오류 0을 확인했습니다.
  Heartbeat는 별도 알림을 요청하지 않았습니다.
- 설정·인증 내용·기존 세션 식별자가 동일합니다. 기존 transcript 1,211개가 모두 그대로이며,
  정상 Heartbeat 실행으로 3개가 추가됐습니다. 대화를 되돌리거나 삭제하지 않았습니다.

## 재시작에서 발견한 이전 검증 잔여물

첫 재시작은 이전 Workshop 합성 검증 agent의 보존된 DB 때문에 실패했습니다.
`agents.delete(deleteFiles:false)`가 삭제 기록을 남기면서 디렉터리와 DB 등록은 보존했고,
시작 시 디렉터리 검색은 이를 다시 발견해 삭제된 agent에 기록을 시도했습니다.
이번 인증 패치를 원복해도 동일하게 실패해 두 원인이 별개임을 확인했습니다.

정확한 검증 영수증으로 소유권을 확인한 임시 agent 네 개에 한해, 설정에 없음·삭제 완료·
세션/인증/transcript 테이블이 비어 있음을 검사했습니다. 공유 DB를 추가 백업하고 공식
`unregisterOpenClawAgentDatabase`로 해당 등록만 해제한 뒤, 디렉터리는 `agents` 밖의
이번 복구 폴더 `retained-pilots/`로 보존 이동했습니다. 삭제 기록과 실제 파일은 유지했습니다.
그 뒤 동일한 인증 패치를 다시 적용하고 Gateway가 정상 시작했습니다.

기존 파일럿의 `cleanup.ok=true`는 당시 세션·설정·예약 작업 정리 결과였으며,
재시작 가능성이나 보존 DB의 검색 대상 제외까지 검증한 결과는 아니었습니다.
같은 파일럿을 다시 실행할 때는 `deleteFiles:false` 뒤 보존 DB 등록과 디렉터리 검색 제외도
검증해야 합니다. 실제 파일을 지우거나 공유 workspace를 삭제하는 방식으로 대체하지 않습니다.

## 운영 감시의 종료 상태 오탐

최종 감시에서는 `task-long-running`이 남아 있었지만, 실제 DB에는 실행 중인 작업이 없고
이미 종료된 `lost` 작업 한 개가 있었습니다. OpenClaw 2026.9.3은 `lost`를 종료 상태로
정의하지만 기존 감시기는 이를 포함하지 않아 계속 실행 중으로 집계했습니다.
종료 상태에 `lost`를 포함하되 실패 이력에는 유지하도록 수정했습니다. DB 상태를 성공으로
바꾸거나 과거 실패를 지우지 않았습니다. 재배포 후 `operationsOk=true`, 현재 문제 없음,
활성·장기 실행 작업 0, 감시기 연속 실패 0을 확인했습니다.

개인정보가 포함될 수 있는 백업·실행 영수증은
`~/.openclaw-personaledge/operations/heartbeat-recovery-20260910T0548/`에 보관합니다.

## 재적용

`runtime-patch-specs.json`의 2026.9.3 `authReprobe` 항목이 원본과 후보 SHA-256을 고정합니다.
`qualify-runtime-patches.py`는 기존 네 패치와 함께 검증·설치·원복하며, 운영 검증기와
백업도 새 런타임 파일과 `auth-reprobe-patch.json`을 확인합니다. 다른 릴리스에는
이 바이트를 그대로 적용하지 말고 후보를 다시 검증해야 합니다.
