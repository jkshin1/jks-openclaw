#!/usr/bin/env python3
"""Offline tests for independent task phases, durable checkpoints, and owner-scoped evidence."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('task_status', Path(__file__).with_name('telegram-task-status.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
NOW = 1788960000000
OWNER = '123456'
SESSION = 'agent:main:telegram:direct:' + OWNER


class TaskStatusTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.state = self.root / 'state-root'
        (self.state / 'state').mkdir(parents=True)
        self.write(self.state / 'openclaw.json', {
            'commands': {'ownerAllowFrom': ['telegram:' + OWNER]},
            'channels': {'telegram': {'enabled': True, 'dmPolicy': 'allowlist', 'allowFrom': [OWNER]}}})
        self.database = self.state / 'state/openclaw.sqlite'
        self.connection = sqlite3.connect(self.database)
        self.connection.executescript('''
        CREATE TABLE task_runs(task_id TEXT PRIMARY KEY,runtime TEXT,task_kind TEXT,status TEXT,
            delivery_status TEXT,created_at INTEGER,started_at INTEGER,ended_at INTEGER,last_event_at INTEGER,
            tool_use_count INTEGER,last_tool_name TEXT,requester_session_key TEXT,owner_key TEXT);
        CREATE TABLE task_delivery_state(task_id TEXT PRIMARY KEY,last_notified_event_at INTEGER);
        ''')
        self.native('owner-native-task', 'succeeded', 'delivered')
        self.native('cron-internal-task', 'succeeded', 'not_applicable', session='', runtime='cron')
        self.native('other-owner-task', 'running', 'pending', session='agent:main:telegram:direct:999')
        self.connection.execute('INSERT INTO task_delivery_state VALUES(?,?)', ('owner-native-task', NOW))
        self.connection.commit()
        self.evidence = self.root / 'evidence.json'
        self.write(self.evidence, {'processing': 'ok', 'validation': 'passed'})
        self.artifact = self.root / '회의록.docx'
        self.artifact.write_bytes(b'synthetic document bytes')
        self.transport = self.root / 'transport.json'
        self.write(self.transport, {'action': 'send', 'channel': 'telegram', 'dryRun': False,
                                   'payload': {'ok': True, 'chatId': OWNER, 'messageId': 42}})

    def tearDown(self):
        self.connection.close()
        self.temporary.cleanup()

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value)); path.chmod(0o600)

    def native(self, task_id, status, delivery, session=SESSION, runtime='cli'):
        self.connection.execute('INSERT INTO task_runs VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                                (task_id, runtime, None, status, delivery, NOW, NOW - 1000,
                                 NOW if status == 'succeeded' else None, NOW, 3, 'private tool content', session, session))

    def publish(self, **changes):
        arguments = {'state_dir': self.state, 'workflow_id': 'meeting-test-run', 'title': '회의록 정리', 'kind': 'meeting',
                     'execution': {'status': 'succeeded', 'evidencePath': self.evidence},
                     'verification': {'status': 'passed', 'evidencePath': self.evidence},
                     'artifacts': [{'id': 'minutes', 'path': self.artifact}]}
        arguments.update(changes)
        return module.publish_workflow(**arguments)

    def show(self):
        return module.query_status(self.state, task_id='meeting-test-run')['tasks'][0]

    def deliveries(self):
        return [{'artifactId': 'minutes', 'artifactSha256': module.sha256(self.artifact), 'receiptPath': self.transport}]

    def test_native_success_does_not_imply_validation_files_or_transport(self):
        result = module.query_status(self.state)
        self.assertEqual(len(result['tasks']), 1)
        task = result['tasks'][0]
        self.assertEqual(task['execution']['status'], 'succeeded')
        self.assertEqual(task['verification']['status'], 'unknown')
        self.assertEqual(task['artifactStatus'], 'unknown')
        self.assertEqual(task['delivery']['status'], 'native-reported-delivered')
        self.assertFalse(task['delivery']['platformReceiptVerified'])
        self.assertEqual(task['delivery']['lastNotificationAt'], module.timestamp(NOW))
        self.assertNotEqual(task['overall'], 'complete')
        self.assertNotIn(OWNER, json.dumps(result))
        self.assertNotIn('private tool content', json.dumps(result))

    def test_internal_tasks_require_explicit_flag(self):
        self.assertEqual(len(module.query_status(self.state)['tasks']), 1)
        self.assertEqual(len(module.query_status(self.state, include_internal=True)['tasks']), 2)
        hidden = module.query_status(self.state, task_id='other-owner-task')
        self.assertFalse(hidden['ok'])
        self.assertEqual(hidden['tasks'], [])
        self.assertEqual(module.query_status(self.state, task_id='other-owner-task', include_internal=True)['tasks'], [])

    def test_native_success_without_terminal_timestamp_is_unknown(self):
        self.connection.execute("UPDATE task_runs SET ended_at=NULL WHERE task_id='owner-native-task'")
        self.connection.commit()
        task = module.query_status(self.state)['tasks'][0]
        self.assertEqual(task['execution']['status'], 'unknown')

    def test_new_workflow_has_independent_pending_phases(self):
        self.publish(execution={'status': 'running'}, verification={'status': 'pending'}, artifacts=[])
        task = self.show()
        self.assertEqual(task['execution']['status'], 'running')
        self.assertEqual(task['verification']['status'], 'pending')
        self.assertEqual(task['artifactStatus'], 'none-recorded')
        self.assertEqual(task['delivery']['status'], 'not-requested')
        self.assertEqual(task['overall'], 'in-progress')

    def test_completed_phase_requires_hashed_private_json_evidence(self):
        for changes in [{'execution': {'status': 'succeeded'}}, {'verification': {'status': 'passed'}}]:
            with self.assertRaisesRegex(ValueError, 'private JSON evidence'):
                self.publish(**changes)
        self.evidence.chmod(0o644)
        with self.assertRaises(ValueError):
            self.publish()

    def test_phase_evidence_changed_becomes_unknown(self):
        self.publish()
        self.write(self.evidence, {'processing': 'changed'})
        task = self.show()
        self.assertEqual(task['execution']['status'], 'unknown')
        self.assertEqual(task['verification']['status'], 'unknown')
        self.assertEqual(task['artifactStatus'], 'verified')
        self.assertEqual(task['overall'], 'evidence-incomplete')

    def test_output_changed_invalidates_file_and_delivery(self):
        self.publish(deliveries=self.deliveries(), require_delivery=True)
        self.assertEqual(self.show()['overall'], 'complete')
        self.artifact.write_bytes(b'new bytes')
        task = self.show()
        self.assertEqual(task['artifactStatus'], 'changed-or-missing')
        self.assertEqual(task['delivery']['status'], 'unverified')
        self.assertNotEqual(task['overall'], 'complete')

    def test_delivery_omission_is_not_success(self):
        self.publish(require_delivery=True)
        task = self.show()
        self.assertEqual(task['execution']['status'], 'succeeded')
        self.assertEqual(task['verification']['status'], 'passed')
        self.assertEqual(task['delivery']['status'], 'pending')
        self.assertNotEqual(task['overall'], 'complete')

    def test_old_receipt_cannot_be_rebound_to_new_file_bytes(self):
        deliveries = self.deliveries()
        self.artifact.write_bytes(b'new bytes')
        with self.assertRaisesRegex(ValueError, 'delivered bytes'):
            self.publish(deliveries=deliveries)

    def test_actual_owner_telegram_receipt_is_required(self):
        original = json.loads(self.transport.read_text())
        for edit in ['dryRun', 'channel', 'owner', 'messageId']:
            receipt = copy.deepcopy(original)
            if edit == 'dryRun': receipt['dryRun'] = True
            elif edit == 'channel': receipt['channel'] = 'slack'
            elif edit == 'owner': receipt['payload']['chatId'] = '999'
            else: receipt['payload']['messageId'] = True
            self.write(self.transport, receipt)
            with self.assertRaises(ValueError):
                self.publish(deliveries=self.deliveries())

    def test_receipt_changed_after_publication_is_unverified(self):
        self.publish(deliveries=self.deliveries())
        self.write(self.transport, {'action': 'send', 'channel': 'telegram', 'dryRun': True})
        self.assertEqual(self.show()['delivery']['status'], 'unverified')

    def test_one_receipt_cannot_prove_two_artifact_sends(self):
        other = self.root / 'subtitle.srt'; other.write_bytes(b'content')
        with self.assertRaisesRegex(ValueError, 'multiple artifact sends'):
            self.publish(artifacts=[{'id': 'minutes', 'path': self.artifact}, {'id': 'subtitle', 'path': other}],
                         deliveries=self.deliveries() + [{'artifactId': 'subtitle', 'artifactSha256': module.sha256(other),
                                                         'receiptPath': self.transport}])

    def test_durable_revision_and_delivery_requirement_cannot_be_downgraded(self):
        first = self.publish(require_delivery=True)
        second = self.publish(require_delivery=False)
        self.assertEqual(first['revision'], 1)
        self.assertEqual(second['revision'], 2)
        self.assertTrue(self.show()['delivery']['required'])
        receipt = Path(second['receiptPath'])
        self.assertEqual(receipt.stat().st_mode & 0o777, 0o600)
        self.assertFalse(list(receipt.parent.glob('.receipt-*')))

    def test_no_files_is_valid_for_a_baseline_briefing(self):
        self.publish(artifacts=[], kind='briefing', title='공개 출처 기준점 기록')
        self.assertEqual(self.show()['overall'], 'complete')
        self.assertEqual(self.show()['artifactStatus'], 'none-recorded')
        self.assertEqual(self.show()['delivery']['status'], 'not-requested')

    def test_native_link_is_owner_scoped_and_prevents_duplicate_rows(self):
        self.publish(native_task_id='owner-native-task')
        result = module.query_status(self.state)
        self.assertEqual(len(result['tasks']), 1)
        self.assertEqual(result['tasks'][0]['nativeTask']['id'], 'owner-native-task')
        with self.assertRaisesRegex(ValueError, 'owner scope'):
            self.publish(workflow_id='bad-owner-task', native_task_id='other-owner-task')
        with self.assertRaisesRegex(ValueError, 'immutable'):
            self.publish(native_task_id=None)

    def test_native_link_conflict_prevents_false_completion(self):
        self.connection.execute("UPDATE task_runs SET status='running',ended_at=NULL WHERE task_id='owner-native-task'")
        self.connection.commit()
        self.publish(native_task_id='owner-native-task')
        task = self.show()
        self.assertIn('execution-evidence-conflict', task['issues'])
        self.assertNotEqual(task['overall'], 'complete')

    def test_deleted_native_link_does_not_imply_complete(self):
        self.publish(native_task_id='owner-native-task')
        self.connection.execute('DELETE FROM task_runs'); self.connection.commit()
        task = self.show()
        self.assertIn('linked-native-task-unavailable', task['issues'])
        self.assertNotEqual(task['overall'], 'complete')

    def test_query_does_not_create_missing_database_or_workflow_index(self):
        other = self.root / 'empty-state'; other.mkdir()
        self.write(other / 'openclaw.json', json.loads((self.state / 'openclaw.json').read_text()))
        result = module.query_status(other)
        self.assertFalse(result['ok'])
        self.assertFalse((other / 'state').exists())
        self.assertFalse((other / 'operations').exists())

    def test_symlink_and_traversal_checkpoint_targets_rejected(self):
        with self.assertRaises(ValueError): self.publish(workflow_id='../outside')
        root = module.receipt_root(self.state); root.mkdir(parents=True)
        (root / 'meeting-test-run').symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError): self.publish()
        self.assertFalse((self.root / 'receipt.json').exists())

    def test_new_owner_cannot_see_old_owner_workflow(self):
        self.publish()
        config = json.loads((self.state / 'openclaw.json').read_text())
        config['commands']['ownerAllowFrom'] = ['telegram:888']
        config['channels']['telegram']['allowFrom'] = ['888']
        self.write(self.state / 'openclaw.json', config)
        result = module.query_status(self.state)
        self.assertEqual(result['tasks'], [])

    def test_human_output_uses_separate_korean_phases(self):
        self.publish()
        output = module.human(module.query_status(self.state))
        for label in ['수행:', '검증:', '파일:', '전달:', 'KST', '조회는 작업을 재실행하지 않습니다']:
            self.assertIn(label, output)
        self.assertNotIn(str(self.state), output)


if __name__ == '__main__':
    unittest.main()
