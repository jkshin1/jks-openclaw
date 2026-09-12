# 사진 요청 응답·Yield 및 Hermes 실행 오류 복구

2026-09-10 KST. 소유자가 사진 개선 요청의 무응답과 `Yield failed`, 조사 중 표시된
`Exec failed`를 함께 해결하도록 요청했다. 사진 재생성, 대화 초기화, 인증 변경은 하지 않았다.

## 확인한 원인

- 09:43:36 최초 요청의 로그는 `outcome=mute`와 `no queued reply payloads`였다.
  안내문 작성과 Telegram 전송은 다르며, 해당 요청은 message-tool-only 전송을 실행하지 않았다.
- 이미지 생성은 독립된 비동기 작업이다. `sessions_yield`는 announcing child-agent 대기용이며
  이 이미지 작업의 대기 수단이 아니다. 잘못된 대기 호출이 생성 실패를 뜻하지 않는다.
- 09:45:57 원래 사진의 `sendPhoto`가 성공했다(messageId 162). 원래 작업을 다시 실행하지 않았다.
- 설치된 Codex 2026.9.3 `run-attempt-DwKds3DN.js`는
  `startupBinding.agentWorkspaceDeveloperInstructions`를 최신 thread AGENTS보다 우선한다.
  현재 디스크 AGENTS에는 이미지 대기 지침이 있지만 이 대화의 시작 스냅샷에는 없었다.
  부트스트랩 로더 자체는 파일을 다시 읽으므로 단순 파일 캐시 문제와 구별된다.
- 첫 Hermes 진단 `ops-20260910T004819Z-5210cf66`은 `BUNDLE_PAGE_INVALID`로 종료했다.
  페이지 인자 실수까지 batch 사전검증이 전체 agent 실행을 중단시키고 있었다.
- 조사 중 일부 shell 탐색도 실패했다(`rg` 미설치, zsh의 미일치 glob 및 없는 경로).
  이런 실패는 실제 도구 실패이며 사용자 메시지 전송 실패와 같은 뜻이 아니다. 오류 표시는
  숨기지 않고 지원되는 탐색 방식으로 전환했다.

## 적용

1. 매 턴 주입되는 `SOUL.md`에 제한된 Telegram 응답 계약을 설치했다.
   최초 진행은 `message(action=send, final=false)`, 이미지 accepted 이후 일반 종료,
   완료 이벤트는 현재 전달 계약에 따라 구조화된 첨부 전송을 명시한다.
   기존 AGENTS 템플릿도 같은 의미로 명확히 했지만 AGENTS 갱신만으로 복구를 주장하지 않는다.
2. Gateway 검증기와 기존 감시기에 SOUL의 관리 블록 누락·변조·중복 검사를 추가했다.
   다른 SOUL 내용은 허용하고, 최초 설치 당시 SOUL은 없었다. 새 예약을 만들지 않았다.
3. Hermes는 페이지 인자 오류만 제한된 실패 도구 응답으로 반환하여 같은 실행에서
   정정할 수 있게 했다. 허용되지 않은 소스·도구·스킬 접근, 호출 예산, 페이지 크기는
   기존대로 거부한다. 범위를 벗어난 인자를 무조건 보정하거나 권한을 넓히지 않았다.
4. 원래 대화·설정·이미지는 유지했다. Gateway 및 Codex 연결 재시작은 하지 않았다.

## 검증

- 실제 Hermes 환경의 worker 회귀 27개, controller 14개, installer 13개 통과.
- Telegram 정책 12개 및 운영/감시기 회귀 25개 통과.
- `test-codex-response-context.mjs`: 설치된 Codex의 실제 bootstrap projection으로
  같은 합성 세션에 SOUL 추가·갱신이 전달되는 검사 7개 통과(모델 호출 없음).
- `smoke-response-contract.py`: 기존 main의 별도 합성 세션에서 실제 Astra 응답으로
  최초 전송/비동기 대기/완료 첨부/재생성 금지의 규칙 7개 확인. 도구 사용과 모델 reroute가
  없었고 합성 세션은 정확히 삭제했다. 실제 새 사진 생성·전체 휴대폰 상호작용 검사는 아니다.
- live Gateway 검증은 static/live true, Gateway ready, Telegram polling.
  기존 감시기 재설치의 첫 실행도 정상 완료했다.
- Hermes 두 번째 실행 `ops-20260910T005110Z-97b53c5d`은 소스 읽기는 성공했지만
  240초 시간초과로 종료했다. 완성된 진단이나 검증된 후보로 집계하지 않는다.
  뒤이은 검토의 별도 종료 상태를 아래에 구분했다.

- 한정 검토 `ops-20260910T005558Z-01d6ac59`는 2회 소스 읽기에 성공했지만
  `FINDING_EVIDENCE_INVALID`로 최종 형식 검증이 실패했다. 검증기는 완화하지 않고,
  worker 입력에 허용되는 근거 ID 목록과 정확 일치 규칙을 추가해 검증 계약과 정렬했다.
  실제 Hermes worker 27개, controller 14개, installer 13개를 다시 통과하고 설치했다.
- 최종 한정 Hermes 검토 `ops-20260910T005827Z-389e5b86`는 **reviewed / completed:true**.
  Sol/high, 62.206초, API 5회, skills_list/skill_view/ops_read_source/ops_check_candidate 성공.
  허용된 근거 ID 검증을 통과했다. 추가 후보는 없어 candidateVerification=no-changes.
  이 검토는 응답 규칙을 확인한 것으로 전체 신규 이미지 전달 시험이나 배포 영수증을
  대신하지 않는다. 설치와 실제 Astra 규칙응답 검사는 위 별도 영수증으로 확인했다.
  `--offline` 검토이므로 최신 공개 upstream 수집은 하지 않았다.

## 비공개 영수증과 원복

- 운영 정책/설정 백업: `~/.openclaw-personaledge/operations/photo-response-fix-o5qswluu/`
- 실제 모델 확인: `~/.openclaw-personaledge/operations/response-contract-smoke-i6srhk3n/`
- Hermes 설치: `~/.local/share/openclaw-hermes-worker/operations-install.json`
  및 `operations-install-backups/20260910T005052Z-74896d10c6/`(페이지 처리) 및
  `operations-install-backups/20260910T005808Z-172f289397/`(근거 표기 계약).
- 감시기 설치: `~/.openclaw-personaledge/operations/telegram-observer-install-latest.json`.

원복은 각 receipt의 현재 after hash가 일치할 때 해당 변경 파일만 수행한다. AGENTS는
그 백업으로 되돌리고 이번에 새로 만든 SOUL은 변경되지 않았을 때만 제거한다. 감시기 코드와
검증 템플릿은 같은 배포 단위로 되돌린다. 전체 설정이나 대화 DB를 과거 복사본으로 덮지 않는다.

로그의 `native prompt annotation would restore redacted evidence` / finalization context 경고는
별도로 존재한다. 이 작업은 redaction 보호장치를 해제하지 않았으며 해당 내부 경고 전체를
해결했다고 주장하지 않는다. 이미지 대기에 잘못된 yield를 호출하지 않는 경로를 복구했다.
