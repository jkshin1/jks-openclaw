# 긴 작업의 진행 및 결과 조회

`telegram-task-status.py`는 소유자가 요청한 개별 업무의 **수행, 검증, 파일 생성,
Telegram 전달**을 따로 조회합니다. 전체 Gateway·전송 큐·야간 작업의 건전성은 기존
`telegram-ops-status.py`가 담당합니다.

이 도구는 상태 조회 중에 모델을 호출하거나, 업무를 재실행하거나, 메시지를 전송하지 않습니다.
저장된 단계는 자동 재개를 보장하지 않습니다. 작업을 이어야 한다면 담당 workflow의 명시적인
재개 명령과 현재 근거를 확인해야 합니다.

## Telegram에서 요청하기

- “아까 시킨 작업 어디까지 됐어?”
- “회의록 작업은 파일 생성까지 끝났어? Telegram 전송도 확인해줘.”
- “검증이 안 끝난 최근 작업만 알려줘.”
- “작업 `meeting-…`의 수행, 검증, 파일, 전달 상태를 나눠서 보여줘.”

이 요청을 받으면 기존 작업을 조회합니다. 조회를 위해 새 작업을 등록하거나 동일 업무를
다시 실행하지 않습니다. 목록에서 대상이 분명하지 않으면 제목과 최근 기록 시각을 제시하고
구체적인 작업을 좁힙니다. 네이티브 기록에 작업 설명만 있고 단계별 근거가 없으면,
확인되지 않은 검증·파일·전송을 완료로 추정하지 않습니다.

설치 위치는 `~/.local/share/openclaw-telegram-workflows/`입니다.

```bash
/usr/bin/python3 ~/.local/share/openclaw-telegram-workflows/telegram-task-status.py list
/usr/bin/python3 ~/.local/share/openclaw-telegram-workflows/telegram-task-status.py list --limit 5 --json
/usr/bin/python3 ~/.local/share/openclaw-telegram-workflows/telegram-task-status.py show meeting-EXAMPLE --json
```

`--state-dir`를 하위 명령 앞에 지정하면 별도 시험 프로필을 조회할 수 있습니다.
`list --include-internal`은 예약 작업과 내부 시험을 포함합니다. 기본 목록은 현재 설정의
단일 Telegram 소유자 세션에 속하는 작업과 그 소유자의 workflow 기록으로 제한됩니다.

## 각 상태가 뜻하는 것

| 단계 | 근거 | 확인하지 못하는 내용 |
| --- | --- | --- |
| 수행 | 네이티브 `task_runs` 또는 담당 workflow의 해시가 일치하는 JSON 근거 | 네이티브 `succeeded`만으로 문서 정확성이나 파일 전송을 보장하지 않음 |
| 검증 | 별도로 기록한 검증 결과 JSON과 현재 SHA-256 일치 | 검증기의 적용 범위를 넘어선 문서 품질·정확성을 보장하지 않음 |
| 파일 | 등록된 결과 파일의 존재, 크기, 현재 SHA-256 | 파일이 있다고 내용 검증까지 완료된 것은 아님 |
| 전달 | 전송 당시 파일 해시와 소유자 대상 Telegram CLI 원본 영수증 | Telegram 접수는 휴대폰 다운로드·열기 확인과 다름 |

`task_delivery_state.last_notified_event_at`는 네이티브 알림 시각으로만 표시합니다.
`task_runs.delivery_status=delivered`는 “네이티브 기록상 전달됨”으로 표시하며,
소유자와 메시지 번호가 확인된 실제 전송 영수증으로 승격하지 않습니다.

등록된 JSON 근거나 결과 파일이 바뀌거나 사라지면 해당 단계의 근거를 미확인으로 표시합니다.
연결된 네이티브 작업이 누락되거나 workflow의 수행 완료 주장과 충돌해도 전체 완료로
표시하지 않습니다. 네이티브 DB를 읽지 못했을 때는 이를 경고로 표시하며 workflow 근거와
구분합니다. 조회 성공의 `ok=true`는 조회 요청이 처리됐다는 뜻으로, 모든 업무의 완료를
뜻하지 않습니다.

## 담당 workflow에서 진행 기록하기

개별 workflow의 기존 manifest를 유지하고 작은 근거 색인만 다음 위치에 저장합니다.

```text
~/.openclaw-personaledge/operations/workflows/tasks/<workflow-id>/receipt.json
```

색인은 0600 파일로 원자 교체하며, 업무별 잠금과 증가하는 `revision`을 사용합니다.
소유자 ID 대신 범위 해시를 저장합니다. 원문 대화, 작업 프롬프트, 인증 정보, 실제 전송의
원본 stdout은 이 색인에 복제하지 않습니다. 개별 workflow의 JSON 근거 및 전송 영수증도
0600 권한이어야 합니다.

새 업무가 승인되어 시작할 때 한 번 등록할 수 있습니다.

```bash
/usr/bin/python3 ~/.local/share/openclaw-telegram-workflows/telegram-task-status.py register \
  --title '회의록 정리' --kind meeting --require-delivery
```

반환된 `workflowId`를 사용합니다. 이미 실행 중인 소유자 네이티브 작업이 있다면
`--native-task-id`로 연결할 수 있습니다. 연결은 변경할 수 없으며 다른 소유자나 내부 작업을
기본 소유자 workflow에 연결하지 않습니다.

담당 Python workflow는 같은 설치 디렉터리의 모듈을 `importlib.util`로 읽어 아래 함수를
호출할 수 있습니다. 실행·검증의 **자체 manifest를 먼저 저장한 후** 이 함수를 호출합니다.

```python
publish_workflow(
    state_dir=state_dir,
    workflow_id="meeting-<unique-id>",
    title="회의록 정리",
    kind="meeting",
    execution={"status": "succeeded", "evidencePath": execution_receipt},
    verification={"status": "passed", "evidencePath": verification_receipt},
    artifacts=[{"id": "minutes", "path": minutes_docx}],
    deliveries=[{
        "artifactId": "minutes",
        "artifactSha256": hash_of_bytes_at_send_time,
        "receiptPath": original_cli_send_receipt,
    }],
    native_task_id=None,
    require_delivery=True,
)
```

- `execution.status`: `pending`, `running`, `succeeded`, `failed`, `cancelled`, `unknown`.
- `verification.status`: `pending`, `passed`, `failed`, `unknown`.
- `succeeded`와 `passed`에는 현재 존재하는 비공개 JSON `evidencePath`가 필수입니다.
- `artifacts`는 최대 32개이며 ID, 파일 경로를 넘기면 크기와 해시를 기록합니다.
- 전송하지 않은 업무의 `deliveries`는 빈 목록입니다. `require_delivery=False`이면
  “전송 미요청”으로 표시합니다.
- 전송 영수증은 원본 CLI JSON의 `action=send`, `channel=telegram`, `dryRun=false`,
  `payload.ok=true`, 현재 소유자와 일치하는 `payload.chatId`, 양의 `messageId`가 필요합니다.
- `artifactSha256`는 전송 당시 파일의 해시입니다. 과거 영수증을 변경된 파일에 붙이는 것을
  거부하며 한 메시지 영수증을 여러 파일의 개별 전송 근거로 재사용하지 않습니다.
- 이미 요구된 전달·파일 단계는 이후 checkpoint에서 생략한다고 요구가 해제되지 않습니다.
- 기준점만 저장한 공개 출처 브리핑처럼 결과 파일이 필요 없는 작업은 `artifacts=[]`로
  기록할 수 있습니다. 파일이 필요한 업무의 담당 workflow는 파일 생성 및 검증을 끝내기
  전에는 수행·검증을 모두 완료로 기록하지 않습니다.

동일 입력을 CLI로 기록하려면 `checkpoint --input <private-json>`을 사용합니다.
JSON 최상위 필드는 `workflowId`, `title`, `kind`, `execution`, `verification`, `artifacts`,
`deliveries`, `nativeTaskId`, `requireDelivery`입니다. Python API 인수명과 달리 마지막 두
필드는 JSON에서 camelCase를 사용합니다. 이 명령도 업무나 전송을 실행하지 않고 근거를
등록합니다.

## 검증

```bash
/usr/bin/python3 scripts/openclaw/test-telegram-task-status.py
```

오프라인 회귀 시험은 소유자 범위, 내부 작업 제외, 네이티브 수행 성공의 과대 해석 방지,
단계별 근거 요구, 파일·영수증 변경, 전송 대상·dry-run 검증, 전송 당시 해시 바인딩,
부분 완료, 네이티브 기록 충돌·누락, 비공개 원자 저장, 경로 이탈과 symlink 거부를 다룹니다.
이 시험을 실제 Telegram 수신이나 휴대폰 열기 확인으로 해석하지 않습니다.
