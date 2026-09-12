# 다른 Mac에서 OpenClaw와 Hermes 이어 쓰기

2026-09-13 KST. 이 저장소는 Mac에서 실행하는 OpenClaw/Telegram과 독립 Hermes 운영
worker의 소스·운영 정책·검증 도구를 보존한다. **Git clone만으로 기존 대화, 장기 기억,
인증과 Hermes의 축적된 경험이 복원되지는 않는다.** 이를 이어 쓰려면 Git 저장소와 별도로
소유자가 보관하는 비공개 이전 자료가 필요하다.

아래는 현재 코드에서 확인한 Mac 이전 절차다. 기존 Mac의 백업과 오프라인 복원 검사는
통과했지만, 다른 컴퓨터에서 서비스까지 활성화하는 전체 이전은 아직 실행하지 않았다.
현재 설치 도구는 Apple Silicon의 `/opt/homebrew` 경로를 사용한다. Intel Mac, Windows,
Linux의 설치·서비스·문서 변환은 별도 이식과 검증이 필요하다.

## 보관해야 할 자료

| 자료 | 현재 위치 또는 출처 | 복원하는 내용 |
| --- | --- | --- |
| Git 저장소 | `https://github.com/jkshin1/jks-openclaw.git` | 소스, 테스트, 정책 템플릿, 변경 이력, 이 가이드 |
| 검증된 OpenClaw 백업 묶음 | `~/Library/Application Support/PersonalEdge/OpenClawBackups/telegram/<backup-id>/` | OpenClaw 상태·대화·기억·설정 DB와 복구용 보충 파일 |
| 독립 Hermes worker 자료 | `~/.local/share/openclaw-hermes-worker/` | 프로필, 절차, 운영 지식, 검증 근거, 실행·배포 이력 |
| Codex 예약과 연결 정보 | Codex 앱의 해당 작업·프로젝트·자동화, 로컬 `~/.codex/automations/` | 토요일 10:00 Asia/Seoul Hermes 검토의 예약과 실행 문맥 |
| 생산성 도구 재구성 자료 | `~/.local/share/openclaw-skill-tools/`, `~/.summarize/`, [도구 문서](OPENCLAW_PRODUCTIVITY.md) | Office·Whisper·요약·추출 환경, lock·설치 영수증과 사용자 설정 |
| 계정 접근과 외부 의존 자료 | 소유자 관리 인증 수단, 필요한 외부 프로젝트·파일 | 재로그인, Telegram 및 공급자 인증, workspace 밖의 참조 자료 |

OpenClaw 백업은 `state.tar.gz` 하나만 옮기지 말고 **묶음 전체**를 옮긴다.
`recovery-manifest.json`, `verification.json`, `recovery/`가 있어야 이 저장소의 복원 검사기가
해시와 복구 파일을 함께 검증한다. `rehearsal/`은 기존 검증의 산출물이며 새 컴퓨터에서도
새 경로에 다시 복원해 검사한다. 백업의 실제 범위는 manifest의 `assets`와 `recovery.files`로
확인한다. 이 묶음은 모든 npm 런타임이나 Homebrew, Hermes, Codex 앱 상태를 포함하는 전체
Mac 백업이 아니다.

Hermes는 최소한 `operations/` 전체, `profile/`의 검토된 스킬·runbook·설정,
`installation.json`, `configuration.json`, `operations-install.json`을 보존한다.
`operations/knowledge.json`만 복사하면 `operations/runs/`에 있는 해시 기반 검증 근거가
빠져 문제 종료·경험·절차 재사용을 검증할 수 없다. 보고서 절차와 이전 결과도 필요하면
worker 루트의 `runs/`를 함께 보존한다. 전체 worker를 비공개로 보관하는 것이 가장 명확하지만,
그 안의 `runtime/.venv`를 다른 Mac에서 그대로 실행할 수 있다는 뜻은 아니다.

이 자료에는 인증과 개인 대화가 들어갈 수 있으므로 Git에 추가하지 않는다. 암호화된 외장
저장 장치 또는 소유자가 선택한 비공개 보관 수단으로 별도 복사하고, 다른 장치에서 파일을
읽을 수 있는지 확인한다. 현재 Mac 내부에만 있는 백업은 Mac 분실·고장에 대비한 외부 사본이
아니다. macOS Keychain·브라우저 로그인·앱 권한이 복원된다고 가정하지 않는다.

## 기존 Mac에서 이전 자료 확정

실행 중인 대화·작업이 끝난 시점을 선택하고, Hermes 실행과 지식 기록이 끝난 뒤 worker를
일관된 시점에 복사한다. SQLite 파일을 쓰는 중에 worker 전체를 단순 복사하지 않는다.
최종 전환 전에는 양쪽 Mac에서 예약이 중복 실행되지 않도록 예약 목록을 확인한다.

프로젝트 루트에서 다음 명령으로 OpenClaw의 공식 온라인 백업과 별도 경로 복원 검사를
함께 수행할 수 있다. 이 명령은 인증을 포함할 수 있는 로컬 백업을 쓰며 서비스는 교체하지 않는다.

```bash
scripts/openclaw/backup-gateway.sh --telegram --apply --rehearse
```

성공 후 `~/.openclaw-personaledge/operations/telegram-backup-latest.json`의
`status=VERIFIED`, `archivePath`, `verifiedAt`을 확인하고 해당 묶음을 보관한다. 백업 원문이나
인증 파일 전체를 채팅·터미널 출력에 붙이지 않는다. 기존 백업이 최신 작업보다 이르면 최종
작업 이후 백업을 사용한다. 2026-09-12의 OpenClaw 백업 검증은 23:47 KST였고, Hermes의
복구 경험·문제 종료 기록은 23:54 KST였으므로 후자는 별도 Hermes 자료로 보존해야 한다.

이전 자료에는 비밀값 없이 다음 목록을 함께 기록한다.

- Git 커밋, 원래 사용자 홈·프로젝트·state·runtime 경로, 플랫폼과 Node/Python 버전.
- OpenClaw/Hermes 버전과 설치 영수증, 런타임 패치 명세 및 실제 적용 해시.
- Gateway·감시기 LaunchAgent와 OpenClaw 내부 cron, Codex 자동화의 목적·시간대·활성 상태.
- 외부 프로젝트, Office 런타임·폰트·Whisper 모델 등 재설치 또는 별도 복사할 의존 자료.
- 이전 직전 실행 중인 작업·대기 전송과 마지막 검증 시점. 종료되지 않은 작업을 완료로 기록하지 않는다.

## 새 Mac에서 소스와 런타임 준비

가능하면 기존 프로젝트 이름을 유지한다. 저장소 이름과 로컬 디렉터리 이름은 달라도 된다.

```bash
mkdir -p "$HOME/projects/python"
git clone https://github.com/jkshin1/jks-openclaw.git "$HOME/projects/python/my-local-agent"
cd "$HOME/projects/python/my-local-agent"
```

먼저 [AGENTS.md](../AGENTS.md), [현재 운영 문서](OPENCLAW_TELEGRAM.md),
[프로젝트 상태](PROJECT_STATUS.md), [Hermes 운영 문서](OPENCLAW_HERMES_OPERATIONS.md)를 읽는다.
Android 소스는 `rc11-final` 보존 대상이다. `install-gateway.sh`, `adopt-existing.sh`,
기존 하드닝·복구 절차와 `--telegram` 없는 레거시 검증기를 현재 호스트 설치에 적용하지 않는다.
이들은 은퇴한 Android용 도구 제한 릴레이의 정책을 담고 있다.

기준 설치는 OpenClaw **2026.9.3**, Hermes **0.21.1**이며, 복구 대상 묶음의 실제 버전을
우선한다. `.local/openclaw-2026.8.1`은 과거부터 유지한 설치 디렉터리 이름이다. 이를 보고
2026.8.1을 새로 설치하거나 버전 문자열을 일괄 치환하지 않는다. 먼저 정확한 공식 패키지와
해당 버전의 플러그인·의존성을 별도 설치하고 저장된 lock·패치 명세와 대조한다. 이 저장소에는
현재 Telegram 구성을 한 번에 새 Mac에 설치·활성화하는 명령이 없다.

OpenClaw 복구 메타데이터는 사용한 Node 버전을 기록한다. 현재 소스의 Node 경로는
`/opt/homebrew/opt/node/bin/node`다. Hermes 설치기는 Git, Python 3.11
(`/opt/homebrew/opt/python@3.11/bin/python3.11`), uv(`/opt/homebrew/bin/uv`)를 요구한다.
감시기 설치기는 실행한 Python의 절대 경로를 LaunchAgent에 저장하므로, 새 Mac에 지속적으로
존재할 Python으로 실행해야 한다. Office·Whisper 런타임과 폰트는
[OPENCLAW_PRODUCTIVITY.md](OPENCLAW_PRODUCTIVITY.md)의 lock·영수증을 기준으로 다시 구성한다.

## OpenClaw를 새 경로에 복원하고 검사

옮겨온 백업 파일은 현재 로그인 사용자 소유, 파일 `0600`, 디렉터리 `0700`을 유지한다.
symlink 경로는 허용하지 않으므로 macOS 임시 경로는 `/tmp` 대신 `/private/tmp`를 사용한다.
아래 경로는 실제 이전 자료와 설치 경로로 지정하고, 대상 디렉터리는 아직 없어야 한다.

```bash
scripts/openclaw/restore-gateway.sh --telegram \
  --archive '/absolute/private/import/<backup-id>/state.tar.gz' \
  --target '/absolute/private/staging/openclaw-restore-new' \
  --state-dir '/Users/jk/.openclaw-personaledge' \
  --cli '/absolute/new/runtime/.personal-edge-management/bin/openclaw' \
  --package '/absolute/new/runtime/lib/node_modules/openclaw' \
  --apply
```

여기서 `--state-dir`은 **아카이브 manifest에 적힌 원래 stateDir**이다. 검사기는 이 문자열이
일치하는지 확인한다. 새 사용자명이 달라졌다면 새 홈의 기본값을 그대로 넘기면 실패한다.
이 인자는 원래 Mac의 파일을 덮어쓰라는 의미가 아니며, 복원 결과는 `--target` 아래에만 생긴다.
`--cli`는 새 Mac에서 동작하도록 경로와 실행 환경을 점검한 관리 wrapper를 가리킨다.
기존 wrapper에는 이전 홈과 Node 경로가 들어 있어 원본 그대로 실행되지 않을 수 있다.

`rehearsal-verification.json`의 `status=VERIFIED`, DB 검사 결과와 해시를 확인한다.
`productionActivated=false`가 정상이다. 추출된 state의 위치는 manifest의
`assets[kind=state].archivePath`로 찾는다. 원본 백업 묶음을 수정하거나 파일을 삭제해
검사 실패를 우회하지 않는다. 2026-09-12의 임시 링크 문제는
[실제 복구 기록](OPENCLAW_HERMES_OPERATIONS.md#2026-09-12-backup-stale-repair)을 참고한다.

이후 활성 state로 배치하기 전에 **복제한 staging 자료에서** 다음 경로들을 검토한다.

| 대상 | 새 Mac에 맞춰 확인할 내용 |
| --- | --- |
| 관리 CLI와 Gateway plist | Node·runtime·state·config·로그 절대 경로, 사용자와 포트 |
| OpenClaw 설정·스킬·workspace | workspace 밖의 참조, 설치 도구 경로, 인증 store 참조 |
| Hermes controller와 등록 스킬 | `DEFAULT_REPO`, worker 경로, 실행 인자의 `--repo`·`--root` |
| 설치 영수증·검증 근거 | 원래 기록은 보존하고, 새 배치의 경로·해시·검증은 새 영수증으로 기록 |
| 예약과 shell wrapper | 이전 프로젝트·홈 경로, 서비스 PATH, 시간대와 실행 사용자 |

특히 현재 `hermes-operations.py`의 기본 repo와 Hermes 스킬 템플릿에는
`/Users/jk/projects/python/my-local-agent`와 `/Users/jk/.local/...` 경로가 있다.
`--repo`를 지원하는 직접 호출은 새 경로를 명시할 수 있지만 감시기의 자동 incident 호출은
그 인자를 전달하지 않는다. 새 홈에서는 해당 호출 체인과 생성되는 설치본까지 수정·검증해야
한다. 전체 파일의 `/Users/jk`를 일괄 치환하면 해시로 연결된 과거 검증 근거까지 손상될 수 있다.

공식 패키지를 새로 설치했다면 저장소의 패치 명세에 해당 버전이 있는지 확인하고 Gateway가
정지한 상태에서 패치를 다시 검증·적용한다. 아래 명령은 검토된 **미패치 원본 바이트**만
받으며 이미 패치된 패키지에는 중복 적용하지 않는다. 출력 경로도 새 경로여야 한다.

```bash
python3 scripts/openclaw/qualify-runtime-patches.py \
  --package '/absolute/new/runtime/lib/node_modules/openclaw' \
  --output '/absolute/private/qualification-new' \
  --state-dir '/absolute/new/active/.openclaw-personaledge' --apply
```

저장된 `recovery/runtime/`는 패치 파일과 복구 메타데이터이며 전체 실행 패키지가 아니다.
적용 전 원본·기존 영수증과 원복 방법을 보존한다. 버전이 다르면 새로운 호환성 검토가 필요하다.

## Hermes 경험과 절차 복원

Hermes 운영 역할은 독립 프로필과 제한된 worker를 유지한다. OpenClaw 인증을 Hermes로
복사하거나 두 번째 Hermes Telegram gateway를 시작하지 않는다. 새 Mac에서는 이전 가상환경을
그대로 재사용하기보다 고정 소스에서 실행 환경을 재구성한다.

새 worker 경로가 아직 없는 경우에만 다음 신규 설치 명령을 사용할 수 있다. 원본 이전 자료는
다른 비공개 경로에 보존한다. `auth`는 Hermes 전용 계정의 독립 장치 로그인을 시작한다.

```bash
git clone https://github.com/NousResearch/hermes-agent.git /private/tmp/hermes-reviewed-source
git -C /private/tmp/hermes-reviewed-source checkout --detach ead7e91dabf1e963796ec834b196984a2fa44ff4
python3 scripts/openclaw/install-hermes-worker.py install --source /private/tmp/hermes-reviewed-source
python3 scripts/openclaw/install-hermes-worker.py auth
python3 scripts/openclaw/install-hermes-worker.py configure
install -m 600 scripts/openclaw/hermes-operations-report.py \
  scripts/openclaw/hermes-report-worker.py scripts/openclaw/install-hermes-worker.py \
  scripts/openclaw/agent-pilot-fixtures.py "$HOME/.local/share/openclaw-hermes-worker/bin/"
python3 scripts/openclaw/install-hermes-operations.py
```

`configure`는 보고서 wrapper를 설치하지 않는다. 위 네 파일 배치가 끝나야 운영 설치기가
보존 의존 파일을 검사할 수 있다. 운영 설치기는 `hermes-operations` 스킬을 등록한다.
보고서용 `hermes-operations-report` 스킬은 복원된 workspace에 있는지 확인하고,
없으면 `templates/HERMES_OPERATIONS_REPORT_SKILL.md`를 새 worker 경로에 맞춰 검토해
등록한다. 이 스킬 파일을 등록했다는 사실만으로 실제 위임이 검증되지는 않는다.

기존 root가 있으면 `install`은 거부하고, 기존 인증이 있으면 `auth`, 기존 설정 영수증이 있으면
`configure`도 거부한다. 정상 자료를 삭제해 이 검사를 통과시키지 않는다. 기존 프로필 복원과
새 프로필 재구성 중 어느 경로인지 먼저 구분한다. OAuth 재인증이 필요한 경우 기존 인증
파일을 비공개로 보존하고 해당 제품이 지원하는 재로그인 절차를 사용한다.

새 설치의 제한 설정·독립 인증을 유지하면서, 보관한 `operations/` 전체와 학습된 스킬·runbook,
필요한 보고서 실행 자료를 대응 위치에 복원한다. 새 설정과 설치 영수증을 이전 것으로 통째로
덮어쓰지 않는다. `operations/knowledge.json`과 참조 파일의 바이트·상대 경로가 유지됐는지
확인하고, 새 runtime·repo·state 경로를 넘겨 다음 명령으로 문제 상태·검증 근거·절차를 읽는다.

```bash
python3 "$HOME/.local/share/openclaw-hermes-worker/bin/hermes-operations.py" knowledge \
  --repo "$HOME/projects/python/my-local-agent" --json
```

현재 버전과 맞지 않거나 근거 해시가 유효하지 않은 절차를 검증 완료로 승격하지 않는다.
2026-09-12 기준으로 `configured-main-routing`과 `refresh-openclaw-verified-backup`의 절차와
경험이 존재한다. 실제 복원 시에는 이 숫자와 이름을 고정 기대값으로 삼기보다 마지막 비공개
지식 영수증과 대조한다. 후속 변경이 생겼다면 최신 기록이 기준이다.

## 서비스 전환과 완료 검증

1. 새 Mac의 설정·복원·패치 검사까지 먼저 마친다. 이전 Mac의 Gateway와 예약이 실행 중이면
   새 Mac에서 Telegram poller·cron·자동화를 시작하지 않는다.
2. 전환 직전 기존 Mac의 활성 작업·대기 전송을 확인하고, 기존 Gateway와 감시기 및 관련
   Codex 예약을 중지한다. LaunchAgent의 `KeepAlive`로 다시 살아나지 않았는지도 확인한다.
   이전 state와 백업은 원복할 수 있게 보존한다.
3. 새 Mac의 Gateway plist를 검토해 현재 사용자 세션에 등록하고 Gateway 하나만 시작한다.
   운영자 로그인, OAuth 재인증, macOS 파일·자동화 권한이 필요하면 이 단계에서 처리한다.
4. 정책과 실제 연결을 확인한 뒤 workflow·Hermes 스킬·감시기를 설치한다. 감시기 설치는
   즉시 첫 검사를 실행하고 5분 LaunchAgent를 활성화하므로 Gateway 준비 후 수행한다.
5. OpenClaw 내부 cron은 복원된 DB에서 기존 정의와 실행 문맥을 확인한다. Codex의 주간
   Hermes heartbeat는 Git이나 OpenClaw 백업만으로 복구되지 않으므로 새 Mac의 해당
   Codex 작업·프로젝트에 연결해 확인한다. 기존 예약이 있으면 중복 생성하지 않는다.

프로젝트 루트에서 다음은 실제 제공하는 검사·설치 명령이다. 설치 명령은 위 전환 순서와
새 경로 검토를 완료한 뒤 사용한다.

```bash
python3 scripts/openclaw/test-telegram-gateway.py
python3 scripts/openclaw/test-telegram-backup.py
scripts/openclaw/verify-gateway.sh --telegram
python3 scripts/openclaw/install-telegram-workflows.py --apply
python3 scripts/openclaw/install-telegram-watchdog.py --apply
python3 "$HOME/.local/share/openclaw-telegram-ops/telegram-watchdog.py" --no-notify --no-hermes --json
```

`verify-gateway.sh --telegram`은 정책·Gateway·Telegram 연결을 확인하지만 사용자의 Telegram에
새 응답을 전달했다는 증거는 아니다. 기존 대화와 지정한 기억을 조회하고, 한 번의 짧은 실제
요청에서 모델·도구 실행과 Telegram 응답을 확인한다. 문서·보고서를 계속 사용할 경우 해당
산출물도 한 종류씩 실제 생성·검증한다. 전송은 그 실제 시험이 요청된 범위에서 수행한다.

마지막으로 새 Mac에서 다시 백업·오프라인 복원 검사를 실행해 이전 Mac 경로를 가리키는
백업 영수증을 현재 경로의 새 영수증으로 갱신한다. 설정 확인, 실제 실행, Telegram 전송,
백업 복원은 각각의 근거로 기록한다. 서비스 전환에 실패하면 새 Mac의 poller·예약을 먼저
중지한 뒤 원래 Mac을 재개하고, 두 Mac의 대화 DB를 임의로 합치지 않는다.

다음 Codex 작업에는 이렇게 요청할 수 있다.

> 이 저장소의 AGENTS.md와 docs/OPENCLAW_NEW_MACHINE.md를 읽고, 내가 준비한 비공개
> OpenClaw/Hermes 이전 자료를 확인해 새 Mac에서 이어 쓸 수 있도록 복원해줘. 기존 대화·기억·
> 인증 경계를 보존하고 현재 경로와 런타임을 검증해줘. 기존 Mac의 poller·예약이 중지됐는지
> 확인한 뒤 새 서비스를 활성화하고, 설정·실행·전송·백업 검증을 구분해서 보고해줘.
