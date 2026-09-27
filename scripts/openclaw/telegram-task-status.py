#!/usr/bin/env python3
"""Inspect individual owner workflows and native tasks with separate evidence for each phase."""

import argparse
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import sys
import tempfile
import uuid


DEFAULT_STATE = Path.home() / '.openclaw-personaledge'
ID_PATTERN = re.compile(r'[a-z0-9][a-z0-9_-]{5,79}\Z')
KINDS = {'coding', 'document', 'media', 'meeting', 'research', 'briefing', 'other'}
EXECUTION_STATES = {'pending', 'running', 'succeeded', 'failed', 'cancelled', 'unknown'}
VERIFICATION_STATES = {'pending', 'passed', 'failed', 'unknown'}
MAX_RECEIPT_BYTES = 262144
MAX_EVIDENCE_BYTES = 16 * 1024 * 1024


def now():
    return datetime.now(timezone.utc).isoformat()


def timestamp(value):
    if value is None:
        return None
    return datetime.fromtimestamp(value / 1000, timezone.utc).isoformat()


def localized(value):
    if not value:
        return '기록 없음'
    return datetime.fromisoformat(value.replace('Z', '+00:00')).astimezone(
        timezone(timedelta(hours=9))).strftime('%m-%d %H:%M KST')


def require(condition, label):
    if not condition:
        raise ValueError(label)


def identifier(value):
    require(isinstance(value, str) and ID_PATTERN.fullmatch(value), 'invalid workflow ID')
    return value


def private_json(path, limit=MAX_RECEIPT_BYTES):
    require(path.is_absolute() and path.is_file() and not path.is_symlink(), 'private evidence unavailable')
    info = path.stat()
    require(info.st_uid == os.getuid() and info.st_mode & 0o077 == 0
            and stat.S_ISREG(info.st_mode) and info.st_size <= limit, 'private evidence permissions or size invalid')
    value = json.loads(path.read_text())
    require(isinstance(value, dict), 'private evidence must be a JSON object')
    return value


def configured_scope(state_dir):
    config = private_json(state_dir / 'openclaw.json')
    owners = config.get('commands', {}).get('ownerAllowFrom', [])
    require(len(owners) == 1 and isinstance(owners[0], str) and owners[0].startswith('telegram:'),
            'exactly one Telegram owner required')
    owner = owners[0][9:]
    require(owner.isascii() and owner.isdigit() and int(owner) > 0, 'invalid owner configuration')
    telegram = config.get('channels', {}).get('telegram', {})
    require(telegram.get('enabled') is True and telegram.get('dmPolicy') == 'allowlist'
            and telegram.get('allowFrom') == [owner] and not telegram.get('accounts'), 'owner ingress policy drifted')
    session = 'agent:main:telegram:direct:' + owner
    return owner, session, hashlib.sha256(session.encode()).hexdigest()


def sha256(path):
    require(path.is_absolute() and path.is_file() and not path.is_symlink(), 'evidence file unavailable')
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def file_reference(path, json_evidence=False):
    path = Path(path).absolute()
    if json_evidence:
        private_json(path, MAX_EVIDENCE_BYTES)
    return {'path': str(path), 'sha256': sha256(path), 'bytes': path.stat().st_size}


def inspect_reference(reference, json_evidence=False):
    try:
        require(isinstance(reference, dict), 'evidence reference missing')
        path = Path(reference['path'])
        require(path.is_absolute(), 'evidence path must be absolute')
        if json_evidence:
            private_json(path, MAX_EVIDENCE_BYTES)
        require(path.stat().st_size == reference['bytes'] and sha256(path) == reference['sha256'], 'evidence changed')
        return True
    except (OSError, ValueError, TypeError, KeyError, OverflowError):
        return False


def receipt_root(state_dir):
    return state_dir / 'operations/workflows/tasks'


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    require(not path.is_symlink(), 'receipt may not be a symlink')
    fd, temporary = tempfile.mkstemp(prefix='.receipt-', dir=str(path.parent))
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, ensure_ascii=False, sort_keys=True, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def phase_reference(value, allowed, completed):
    require(isinstance(value, dict) and value.get('status') in allowed, 'invalid workflow phase status')
    result = {'status': value['status'], 'recordedAt': now(), 'basis': 'workflow-checkpoint'}
    if value.get('evidencePath'):
        result['evidence'] = file_reference(value['evidencePath'], json_evidence=True)
    require(value['status'] != completed or result.get('evidence'), 'completed phase requires private JSON evidence')
    return result


def transport_receipt(path, owner):
    data = private_json(path, MAX_EVIDENCE_BYTES)
    payload = data.get('payload', {})
    require(isinstance(payload, dict) and data.get('action') == 'send'
            and data.get('channel') == 'telegram' and data.get('dryRun') is False,
            'not an actual Telegram send receipt')
    message_id = payload.get('messageId')
    require(payload.get('ok') is True and str(payload.get('chatId')) == owner
            and isinstance(message_id, (str, int)) and not isinstance(message_id, bool)
            and str(message_id).isdigit() and int(message_id) > 0, 'Telegram receipt not confirmed for owner')
    return {'messageId': str(message_id), 'platformAccepted': True, 'phoneOpened': 'unobserved'}


def native_rows(state_dir, session, limit=10, include_internal=False, task_id=None):
    database = state_dir / 'state/openclaw.sqlite'
    connection = sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True, timeout=5)
    connection.row_factory = sqlite3.Row
    try:
        connection.execute('PRAGMA query_only=ON')
        where, parameters = [], []
        if not include_internal:
            where.append('(t.requester_session_key=? OR t.owner_key=?)')
            parameters.extend([session, session])
        else:
            # Internal history is distinct from a different Telegram peer's private tasks.
            where.append('(t.requester_session_key=? OR t.owner_key=? OR '
                         "(COALESCE(t.requester_session_key,'') NOT LIKE '%:telegram:%' AND "
                         "COALESCE(t.owner_key,'') NOT LIKE '%:telegram:%'))")
            parameters.extend([session, session])
        if task_id is not None:
            where.append('t.task_id=?')
            parameters.append(task_id)
        sql = ('SELECT t.task_id,t.runtime,t.task_kind,t.status,t.delivery_status,t.created_at,t.started_at,'
               't.ended_at,t.last_event_at,t.tool_use_count,t.last_tool_name,d.last_notified_event_at '
               'FROM task_runs t LEFT JOIN task_delivery_state d ON d.task_id=t.task_id')
        if where:
            sql += ' WHERE ' + ' AND '.join(where)
        sql += ' ORDER BY COALESCE(t.last_event_at,t.ended_at,t.created_at) DESC LIMIT ?'
        parameters.append(limit)
        return [dict(row) for row in connection.execute(sql, parameters)]
    finally:
        connection.close()


def publish_workflow(state_dir, workflow_id, title, kind, execution, verification, artifacts,
                     deliveries=None, native_task_id=None, require_delivery=False):
    """Publish a small checkpoint index after the owning workflow writes its own evidence.

    Never runs user work, native tasks, inference or transport. Completed phase claims
    require a private JSON evidence file; readers recheck evidence and artifact hashes.
    """
    state_dir = Path(state_dir).absolute()
    owner, session, scope = configured_scope(state_dir)
    identifier(workflow_id)
    require(isinstance(title, str) and 1 <= len(title) <= 120 and all(ord(char) >= 32 for char in title), 'invalid title')
    require(kind in KINDS and isinstance(require_delivery, bool), 'invalid workflow kind or requirements')
    require(isinstance(artifacts, list) and len(artifacts) <= 32, 'too many or invalid artifacts')
    deliveries = [] if deliveries is None else deliveries
    require(isinstance(deliveries, list) and len(deliveries) <= 32, 'too many or invalid deliveries')
    if native_task_id:
        require(isinstance(native_task_id, str) and 1 <= len(native_task_id) <= 200, 'invalid native task ID')
        require(native_rows(state_dir, session, task_id=native_task_id), 'native task is not in owner scope')
    record = {'schemaVersion': 1, 'workflowId': workflow_id, 'ownerScope': scope,
              'title': title, 'kind': kind, 'nativeTaskId': native_task_id,
              'execution': phase_reference(execution, EXECUTION_STATES, 'succeeded'),
              'verification': phase_reference(verification, VERIFICATION_STATES, 'passed'),
              'requirements': {'verification': True, 'artifacts': bool(artifacts), 'delivery': require_delivery},
              'artifacts': [], 'deliveries': [], 'updatedAt': now()}
    artifact_ids = set()
    for item in artifacts:
        artifact_id = item.get('id')
        require(isinstance(artifact_id, str) and re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', artifact_id)
                and artifact_id not in artifact_ids, 'invalid or repeated artifact ID')
        artifact_ids.add(artifact_id)
        record['artifacts'].append({'id': artifact_id, **file_reference(item['path'])})
    delivered_ids = set()
    message_ids = set()
    for item in deliveries:
        artifact_id = item.get('artifactId')
        require(artifact_id in artifact_ids and artifact_id not in delivered_ids, 'unknown or repeated delivered artifact')
        delivered_ids.add(artifact_id)
        path = Path(item['receiptPath']).absolute()
        transport = transport_receipt(path, owner)
        require(transport['messageId'] not in message_ids, 'one send receipt cannot prove multiple artifact sends')
        message_ids.add(transport['messageId'])
        artifact = next(value for value in record['artifacts'] if value['id'] == artifact_id)
        require(item.get('artifactSha256') == artifact['sha256'], 'delivered bytes do not match current artifact')
        record['deliveries'].append({'artifactId': artifact_id, 'artifactSha256': item['artifactSha256'],
                                     'evidence': file_reference(path, json_evidence=True)})
    root = receipt_root(state_dir)
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(not root.is_symlink(), 'workflow index root may not be a symlink')
    directory = root / workflow_id
    require(not directory.is_symlink(), 'workflow directory may not be a symlink')
    directory.mkdir(mode=0o700, exist_ok=True)
    lock_path = directory / '.lock'
    require(not lock_path.is_symlink(), 'workflow lock may not be a symlink')
    descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o600)
    with os.fdopen(descriptor, 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        target = directory / 'receipt.json'
        if target.exists():
            previous = private_json(target)
            require(previous.get('schemaVersion') == 1 and previous.get('ownerScope') == scope,
                    'workflow belongs to a different owner or schema')
            require(previous.get('nativeTaskId') == native_task_id, 'native task binding is immutable')
            record['createdAt'] = previous['createdAt']
            record['revision'] = previous.get('revision', 0) + 1
            for requirement in ('artifacts', 'delivery'):
                record['requirements'][requirement] = bool(record['requirements'][requirement] or
                                                          previous.get('requirements', {}).get(requirement))
        else:
            record['createdAt'] = record['updatedAt']
            record['revision'] = 1
        atomic_json(target, record)
    return {'workflowId': workflow_id, 'revision': record['revision'], 'receiptPath': str(target)}


def phase_status(phase, completed):
    if not isinstance(phase, dict):
        return {'status': 'unknown', 'basis': 'missing-checkpoint'}
    reported = phase.get('status', 'unknown')
    reference = phase.get('evidence')
    evidence_ok = inspect_reference(reference, json_evidence=True) if reference else False
    effective = reported
    if reference and not evidence_ok or reported == completed and not evidence_ok:
        effective = 'unknown'
    return {'status': effective, 'reportedStatus': reported, 'basis': 'workflow-checkpoint',
            'evidenceCurrent': evidence_ok, 'recordedAt': phase.get('recordedAt')}


def inspect_workflow(record, state_dir, owner, session):
    identifier(record['workflowId'])
    require(record.get('schemaVersion') == 1, 'unsupported workflow schema')
    issues = []
    execution = phase_status(record.get('execution'), 'succeeded')
    verification = phase_status(record.get('verification'), 'passed')
    native = None
    native_id = record.get('nativeTaskId')
    if native_id:
        try:
            rows = native_rows(state_dir, session, task_id=native_id)
            native = native_summary(rows[0]) if rows else None
        except (OSError, sqlite3.Error, ValueError):
            native = None
        if not native:
            issues.append('linked-native-task-unavailable')
        elif execution['status'] == 'succeeded' and native['execution']['status'] != 'succeeded':
            issues.append('execution-evidence-conflict')
    artifacts = []
    for artifact in record.get('artifacts', []):
        valid = inspect_reference(artifact)
        artifacts.append({'id': artifact['id'], 'name': Path(artifact['path']).name,
                          'bytes': artifact.get('bytes'), 'status': 'verified' if valid else 'changed-or-missing',
                          'sha256': artifact.get('sha256')})
    deliveries = []
    for delivery in record.get('deliveries', []):
        artifact = next((item for item in artifacts if item['id'] == delivery.get('artifactId')), None)
        valid = (artifact is not None and artifact['status'] == 'verified'
                 and artifact['sha256'] == delivery.get('artifactSha256')
                 and inspect_reference(delivery.get('evidence'), json_evidence=True))
        receipt = None
        if valid:
            try:
                receipt = transport_receipt(Path(delivery['evidence']['path']), owner)
            except (OSError, ValueError, KeyError, TypeError):
                valid = False
        deliveries.append({'artifactId': delivery.get('artifactId'),
                           'status': 'platform-accepted' if valid else 'unverified',
                           'messageId': receipt['messageId'] if valid else None})
    required = record.get('requirements', {})
    confirmed = {item['artifactId'] for item in deliveries if item['status'] == 'platform-accepted'}
    all_delivered = bool(artifacts) and confirmed == {item['id'] for item in artifacts}
    delivery_state = 'platform-accepted' if all_delivered else ('partial' if confirmed else
                     'unverified' if deliveries else 'pending' if required.get('delivery') else 'not-requested')
    artifact_state = ('verified' if all(item['status'] == 'verified' for item in artifacts) else 'changed-or-missing') if artifacts else 'none-recorded'
    complete = (execution['status'] == 'succeeded' and verification['status'] == 'passed'
                and (not required.get('artifacts') or artifact_state == 'verified')
                and (not required.get('delivery') or all_delivered) and not issues)
    if execution['status'] in {'failed', 'cancelled'} or verification['status'] == 'failed':
        overall = 'failed' if execution['status'] != 'cancelled' else 'cancelled'
    elif complete:
        overall = 'complete'
    elif issues or execution['status'] == 'unknown' or verification['status'] == 'unknown':
        overall = 'evidence-incomplete'
    else:
        overall = 'in-progress'
    return {'id': record['workflowId'], 'source': 'workflow', 'title': record['title'], 'kind': record['kind'],
            'createdAt': record['createdAt'], 'updatedAt': record['updatedAt'], 'revision': record.get('revision'),
            'overall': overall, 'execution': execution, 'verification': verification,
            'artifactStatus': artifact_state, 'artifacts': artifacts,
            'delivery': {'status': delivery_state, 'receipts': deliveries, 'phoneOpened': 'unobserved',
                         'required': bool(required.get('delivery'))},
            'nativeTask': native, 'issues': issues, 'resumption': 'status-only-no-automatic-restart'}


def native_summary(row):
    native_status = row.get('status')
    mapped = {'queued': 'pending', 'running': 'running', 'waiting': 'running', 'succeeded': 'succeeded',
              'failed': 'failed', 'lost': 'failed', 'cancelled': 'cancelled', 'canceled': 'cancelled',
              'timed_out': 'failed'}
    execution_state = mapped.get(native_status, 'unknown')
    if native_status == 'succeeded' and row.get('ended_at') is None:
        execution_state = 'unknown'
    delivery = row.get('delivery_status')
    delivery = delivery if delivery in {'pending', 'delivered', 'failed', 'not_applicable', 'suppressed', 'unknown'} else 'unknown'
    return {'id': row['task_id'], 'source': 'native-task', 'title': 'OpenClaw 작업',
            'kind': row.get('task_kind') if row.get('task_kind') in {'automation_run', 'exec'} else 'other',
            'runtime': row['runtime'] if row.get('runtime') in {'cli', 'cron', 'subagent'} else 'other',
            'createdAt': timestamp(row.get('created_at')),
            'updatedAt': timestamp(row.get('last_event_at') or row.get('ended_at') or row.get('created_at')),
            'overall': 'failed' if mapped.get(native_status) == 'failed' else 'phase-evidence-only',
            'execution': {'status': execution_state, 'basis': 'native-task-record',
                          'startedAt': timestamp(row.get('started_at')), 'endedAt': timestamp(row.get('ended_at')),
                          'toolUseCount': row.get('tool_use_count')},
            'verification': {'status': 'unknown', 'basis': 'not-recorded'}, 'artifactStatus': 'unknown', 'artifacts': [],
            'delivery': {'status': 'native-reported-delivered' if delivery == 'delivered' else delivery,
                         'lastNotificationAt': timestamp(row.get('last_notified_event_at')),
                         'platformReceiptVerified': False, 'phoneOpened': 'unobserved'},
            'issues': [], 'resumption': 'status-only-no-automatic-restart'}


def query_status(state_dir=DEFAULT_STATE, task_id=None, limit=10, include_internal=False):
    state_dir = Path(state_dir).absolute()
    owner, session, scope = configured_scope(state_dir)
    require(1 <= limit <= 50, 'limit must be between 1 and 50')
    result = {'schemaVersion': 1, 'observedAt': now(), 'scope': 'including-internal' if include_internal else 'owner-telegram',
              'ok': True, 'tasks': [], 'warnings': []}
    root = receipt_root(state_dir)
    records, native_bound = [], set()
    if root.exists():
        require(not root.is_symlink(), 'workflow index root may not be a symlink')
        for directory in root.iterdir():
            if not directory.is_dir() or directory.is_symlink() or not ID_PATTERN.fullmatch(directory.name):
                continue
            if task_id is not None and task_id != directory.name:
                continue
            try:
                record = private_json(directory / 'receipt.json')
                if record.get('ownerScope') != scope:
                    continue
                if record.get('nativeTaskId'):
                    native_bound.add(record['nativeTaskId'])
                records.append(record)
            except (OSError, ValueError, KeyError, TypeError):
                result['warnings'].append('workflow-receipt-unreadable')
        records.sort(key=lambda item: item.get('updatedAt', ''), reverse=True)
        for record in records[:limit]:
            try:
                result['tasks'].append(inspect_workflow(record, state_dir, owner, session))
            except (OSError, ValueError, KeyError, TypeError, OverflowError):
                result['warnings'].append('workflow-evidence-unreadable')
    try:
        if task_id is None or not result['tasks']:
            rows = native_rows(state_dir, session, limit=limit, include_internal=include_internal, task_id=task_id)
            result['tasks'].extend(native_summary(row) for row in rows if row['task_id'] not in native_bound)
    except (OSError, sqlite3.Error, ValueError, OverflowError):
        result['warnings'].append('native-task-store-unavailable')
    result['tasks'].sort(key=lambda item: item.get('updatedAt') or '', reverse=True)
    result['tasks'] = result['tasks'][:limit]
    result['warnings'] = sorted(set(result['warnings']))
    if task_id is not None and not result['tasks']:
        result['ok'] = False
        result['warnings'].append('task-not-found-in-scope')
    if 'native-task-store-unavailable' in result['warnings'] and not result['tasks']:
        result['ok'] = False
    return result


LABELS = {'pending': '대기', 'running': '진행 중', 'succeeded': '수행 완료', 'failed': '실패',
          'cancelled': '취소', 'unknown': '근거 없음', 'passed': '검증 통과', 'verified': '파일과 해시 확인',
          'changed-or-missing': '파일 누락 또는 변경', 'none-recorded': '생성 파일 기록 없음',
          'platform-accepted': 'Telegram 접수 확인', 'partial': '일부 파일 전송 확인', 'unverified': '전송 근거 불충분',
          'not-requested': '전송 미요청', 'not_applicable': '네이티브 전송 미요청', 'suppressed': '알림 생략',
          'native-reported-delivered': '네이티브 기록상 전달됨 · 전송 영수증 미확인',
          'complete': '요구된 단계 완료', 'in-progress': '미완료 단계 있음', 'evidence-incomplete': '완료 근거 확인 필요',
          'phase-evidence-only': '작업 수행 기록만 있음'}


def human(result):
    lines = ['긴 작업 진행 조회 · ' + localized(result['observedAt'])]
    if not result['tasks']:
        lines.append('이 범위에서 조회할 작업이 없습니다.')
    for task in result['tasks']:
        lines.extend(['', '{} [{}]'.format(task['title'], task['id']),
                      '상태: ' + LABELS.get(task['overall'], '확인 필요'),
                      '수행: {} · 검증: {}'.format(LABELS.get(task['execution']['status'], '확인 필요'),
                                                LABELS.get(task['verification']['status'], '확인 필요')),
                      '파일: {} · 전달: {}'.format(LABELS.get(task['artifactStatus'], '확인 필요'),
                                                LABELS.get(task['delivery']['status'], '확인 필요')),
                      '최근 기록: ' + localized(task['updatedAt'])])
        if task['artifacts']:
            lines.append('결과 파일: ' + ', '.join(item['name'] for item in task['artifacts']))
    if result['warnings']:
        lines.append('\n일부 기록을 읽지 못했거나 요청한 작업이 범위 안에 없습니다. JSON의 warnings를 확인하세요.')
    lines.append('\nTelegram 접수와 휴대폰에서 열기는 별도입니다. 조회는 작업을 재실행하지 않습니다.')
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-dir', type=Path, default=DEFAULT_STATE)
    parser.add_argument('--json', action='store_true')
    sub = parser.add_subparsers(dest='command')
    listing = sub.add_parser('list', help='list recent individual owner tasks')
    listing.add_argument('--limit', type=int, default=10)
    listing.add_argument('--include-internal', action='store_true')
    show = sub.add_parser('show', help='show a workflow or native task ID')
    show.add_argument('id')
    show.add_argument('--include-internal', action='store_true')
    register = sub.add_parser('register', help='record the start of a new owner workflow')
    register.add_argument('--title', required=True)
    register.add_argument('--kind', choices=sorted(KINDS), default='other')
    register.add_argument('--id')
    register.add_argument('--native-task-id')
    register.add_argument('--require-delivery', action='store_true')
    publish = sub.add_parser('checkpoint', help='publish phase evidence from one private JSON input')
    publish.add_argument('--input', type=Path, required=True)
    for command_parser in (listing, show, register, publish):
        command_parser.add_argument('--json', action='store_true', default=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.command == 'register':
        result = publish_workflow(args.state_dir, args.id or 'task-' + uuid.uuid4().hex,
                                  args.title, args.kind, {'status': 'running'}, {'status': 'pending'}, [],
                                  native_task_id=args.native_task_id, require_delivery=args.require_delivery)
        print(json.dumps({'ok': True, **result}, ensure_ascii=False))
        return 0
    if args.command == 'checkpoint':
        data = private_json(args.input.absolute())
        result = publish_workflow(args.state_dir, data['workflowId'], data['title'], data['kind'],
                                  data['execution'], data['verification'], data.get('artifacts', []),
                                  deliveries=data.get('deliveries'), native_task_id=data.get('nativeTaskId'),
                                  require_delivery=data.get('requireDelivery', False))
        print(json.dumps({'ok': True, **result}, ensure_ascii=False))
        return 0
    result = query_status(args.state_dir, task_id=args.id if args.command == 'show' else None,
                          limit=getattr(args, 'limit', 10), include_internal=getattr(args, 'include_internal', False))
    print(json.dumps(result, ensure_ascii=False) if args.json else human(result))
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, TypeError, KeyError, sqlite3.Error, OverflowError):
        print('FAIL task status input or evidence is unavailable; no user task was run', file=sys.stderr)
        raise SystemExit(1)
