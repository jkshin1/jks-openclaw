#!/usr/bin/env python3
"""Offline regression tests: aggregate privacy, notification state, and installer rollback."""

import argparse
import copy
import hashlib
import importlib.util
import json
from datetime import datetime, timezone
import os
from pathlib import Path
import plistlib
import sqlite3
import subprocess
import tempfile
import unittest
from unittest.mock import patch


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


status = load('telegram-ops-status')
watchdog = load('telegram-watchdog')
installer = load('install-telegram-watchdog')
NOW = 1788955000000
AT = status.iso(NOW)


class OperationsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.state = self.root / 'state-root'
        (self.state / 'state').mkdir(parents=True)
        (self.state / 'operations').mkdir()
        self.database = self.state / 'state/openclaw.sqlite'
        self.connection = sqlite3.connect(self.database)
        self.connection.executescript('''
            CREATE TABLE delivery_queue_entries(status TEXT,enqueued_at INTEGER);
            CREATE TABLE task_runs(status TEXT,created_at INTEGER,started_at INTEGER,ended_at INTEGER);
            CREATE TABLE cron_run_receipts(store_key TEXT,job_id TEXT,status TEXT,started_at_ms INTEGER,finished_at_ms INTEGER);
            CREATE TABLE cron_jobs(store_key TEXT,job_id TEXT,declaration_key TEXT,state_json TEXT,enabled INTEGER);
            CREATE TABLE backup_runs(created_at INTEGER,archive_path TEXT,status TEXT);
        ''')
        self.connection.execute('INSERT INTO cron_jobs VALUES(?,?,?,?,?)',
                                ('private-store','private-job','memory-core:memory-dreaming-promotion',
                                 json.dumps({'lastRunAtMs': NOW-1000,'lastRunStatus':'ok','consecutiveErrors':0}),1))
        self.connection.execute('INSERT INTO cron_run_receipts VALUES(?,?,?,?,?)',
                                ('private-store','private-job','ok',NOW-1000,NOW-900))
        self.archive = self.root / 'private-owner-archive.tar.gz'
        self.archive.write_bytes(b'archive')
        self.connection.execute('INSERT INTO backup_runs VALUES(?,?,?)',(NOW-1000,str(self.archive),'ok'))
        self.connection.commit()
        (self.state/'operations/telegram-watchdog-status.json').write_text(json.dumps({
            'observedAt':AT,'lastSuccessAt':AT,'healthy':True,'gateway':{'ok':True},'consecutiveFailures':0}))
        config = {'commands':{'ownerAllowFrom':['telegram:123456']},'channels':{'telegram':{
            'enabled':True,'dmPolicy':'allowlist','allowFrom':['123456']}}}
        path = self.state/'openclaw.json'
        path.write_text(json.dumps(config)); path.chmod(0o600)

    def tearDown(self):
        self.connection.close()
        self.temp.cleanup()

    def collect(self, **kwargs):
        self.connection.commit()
        return status.collect(self.state, now=NOW, **kwargs)

    def incident(self):
        saved = {}
        for _ in range(3):
            saved = watchdog.transition(saved,False,['delivery-stale'],AT)
        return saved

    def sent(self, **changes):
        value = {'action':'send','channel':'telegram','dryRun':False,
                 'payload':{'ok':True,'messageId':7,'chatId':'123456'}}
        value.update(changes)
        return subprocess.CompletedProcess([],0,json.dumps(value),'private diagnostic')

    def test_healthy_aggregate_keeps_old_failed_records_separate(self):
        self.connection.execute('INSERT INTO backup_runs VALUES(?,?,?)',(NOW,str(self.archive),'failed'))
        self.connection.execute('INSERT INTO task_runs VALUES(?,?,?,?)',('failed',NOW-5000,NOW-4000,NOW-3000))
        result = self.collect()
        self.assertTrue(result['ok'])
        self.assertEqual(result['tasks']['failed24h'],1)
        self.assertEqual(result['backup']['status'],'UNVERIFIED')
        output = json.dumps(result)
        self.assertNotIn('private-owner',output)
        self.assertNotIn('private-job',output)
        self.assertNotIn('123456',output)

    def provenance(self, relative, origin, workspace=None):
        workspace = workspace or str(self.workspace)
        key = hashlib.sha256(os.path.realpath(workspace).encode()).hexdigest()
        self.connection.execute('INSERT INTO plugin_state_entries VALUES(?,?,?,?,?,?)', (
            'core:memory-artifact-provenance', 'workspace-files',
            key + ':' + hashlib.sha256(relative.encode()).hexdigest(),
            json.dumps({'version': 1, 'workspaceKey': key, 'relativePath': relative, 'fileHash': 'a' * 64,
                        'originClass': origin, 'observedAt': NOW, 'sessionKey': 'agent:main:private-session'}),
            NOW, None))

    def test_untrusted_curated_memory_raises_owner_alert_without_content(self):
        self.workspace = self.root / 'private-workspace'; self.workspace.mkdir()
        path = self.state/'openclaw.json'; config = json.loads(path.read_text())
        config['agents'] = {'defaults': {'workspace': str(self.workspace)}}; path.write_text(json.dumps(config))
        # Without the provenance table (older schema) the check reports itself unavailable, not a fault.
        result = self.collect()
        self.assertTrue(result['ok']); self.assertEqual(result['memoryProvenance']['scope'], 'unavailable')
        self.connection.execute('CREATE TABLE plugin_state_entries(plugin_id TEXT,namespace TEXT,'
                                'entry_key TEXT,value_json TEXT,created_at INTEGER,expires_at INTEGER)')
        self.provenance('MEMORY.md', 'agent')
        self.provenance('memory/2026-09-06.md', 'untrusted')
        self.provenance('USER.md', 'untrusted', workspace=str(self.root / 'other-workspace'))
        result = self.collect()
        self.assertTrue(result['ok'])
        self.assertEqual(result['memoryProvenance'], {'scope': 'curated-roots', 'untrusted': []})
        self.connection.execute('DELETE FROM plugin_state_entries')
        self.provenance('MEMORY.md', 'untrusted')
        result = self.collect()
        self.assertFalse(result['ok'])
        self.assertIn('memory-bootstrap-untrusted', result['issues'])
        self.assertEqual(result['memoryProvenance']['untrusted'], ['MEMORY.md'])
        self.assertIn('기억 파일 자동 주입 제외', status.human(result))
        output = json.dumps(result)
        for private in ('private-workspace', 'private-session', 'a' * 64):
            self.assertNotIn(private, output)
        # Three consecutive observations open one owner alert; it is not an automatic Hermes review.
        saved = {}
        for _ in range(3):
            saved = watchdog.transition(saved, False, result['issues'], AT)
        self.assertEqual(saved['pendingNotification']['kind'], 'failure')
        self.assertNotIn('memory-bootstrap-untrusted', watchdog.HERMES_INCIDENT_ISSUES)

    def test_stale_snapshot_does_not_claim_current_gateway_health(self):
        result = status.collect(self.state,now=NOW+901000)
        self.assertFalse(result['ok']);self.assertTrue(result['operationsOk'])
        self.assertTrue(result['observer']['stale'])
        self.assertTrue(status.collect(self.state,now=NOW+901000,include_observer=False)['ok'])

    def test_saved_gateway_failure_is_explained_in_human_status(self):
        path=self.state/'operations/telegram-watchdog-status.json'
        saved=json.loads(path.read_text());saved['gateway']['ok']=False;path.write_text(json.dumps(saved))
        result=self.collect()
        self.assertFalse(result['ok']);self.assertTrue(result['operationsOk'])
        self.assertIn('observer-gateway-unhealthy',result['issues'])
        self.assertIn('Gateway: 최근 검사 실패',status.human(result))
        self.assertNotIn('확인 필요: 없음',status.human(result))
        self.assertNotIn('observer-gateway-unhealthy',self.collect(include_observer=False)['issues'])

    def test_pending_failed_queue_and_long_task_are_observed(self):
        self.connection.execute('INSERT INTO delivery_queue_entries VALUES(?,?)',('failed',NOW-1000000))
        self.connection.execute('INSERT INTO task_runs VALUES(?,?,?,?)',('running',NOW-4000000,NOW-3900000,None))
        result = self.collect()
        self.assertEqual(result['queue']['pending'],1)
        self.assertEqual(result['queue']['oldestAgeSeconds'],1000)
        self.assertEqual(result['tasks']['longRunning'],1)
        self.assertEqual(set(result['issues']),{'delivery-failed','delivery-stale','task-long-running'})
        self.assertIn('전송 대기 15분 초과',status.human(result))
        self.assertIn('KST',status.human(result))

    def test_dead_letter_delivery_is_an_immediate_failure(self):
        self.connection.execute('INSERT INTO delivery_queue_entries VALUES(?,?)',('dead_letter',NOW-1000))
        result = self.collect()
        self.assertEqual(result['queue']['failed'],1)
        self.assertIn('delivery-failed',result['issues'])

    def test_lost_task_is_terminal_with_or_without_end_time_and_remains_in_failure_history(self):
        for ended_at in (None, NOW-7*3600000):
            with self.subTest(ended_at=ended_at):
                self.connection.execute('DELETE FROM task_runs')
                row = ('lost',NOW-8*3600000,NOW-8*3600000,ended_at)
                self.connection.execute('INSERT INTO task_runs VALUES(?,?,?,?)',row)
                result = self.collect()
                self.assertTrue(result['operationsOk'])
                self.assertEqual(result['tasks']['active'],0)
                self.assertEqual(result['tasks']['longRunning'],0)
                self.assertIsNone(result['tasks']['oldestActiveAgeSeconds'])
                self.assertEqual(result['tasks']['recent24hStatusCounts'],{'lost':1})
                self.assertEqual(result['tasks']['failed24h'],1)
                self.assertNotIn('task-long-running',result['issues'])
                self.assertEqual(self.connection.execute('SELECT * FROM task_runs').fetchall(),[row])

    def test_lost_history_does_not_hide_a_genuinely_long_running_task(self):
        self.connection.executemany('INSERT INTO task_runs VALUES(?,?,?,?)',[
            ('lost',NOW-8*3600000,NOW-8*3600000,None),
            ('running',NOW-2*3600000,NOW-2*3600000,None)])
        result = self.collect()
        self.assertEqual(result['tasks']['active'],1)
        self.assertEqual(result['tasks']['longRunning'],1)
        self.assertEqual(result['tasks']['oldestActiveAgeSeconds'],2*3600)
        self.assertIn('task-long-running',result['issues'])

    def test_unknown_status_does_not_echo_content(self):
        self.connection.execute('INSERT INTO delivery_queue_entries VALUES(?,?)',('secret-owner-token',NOW))
        result=self.collect()
        self.assertEqual(result['queue']['statusCounts'],{'other':1})
        self.assertNotIn('secret-owner-token',json.dumps(result))

    def test_missing_database_never_created(self):
        missing=self.root/'absent'
        result=status.collect(missing,now=NOW)
        self.assertFalse(result['ok']);self.assertEqual(result['issues'],['operations-read-failed'])
        self.assertFalse(missing.exists())

    def test_verified_backup_requires_existing_archive(self):
        path=self.state/'operations/telegram-backup-latest.json'
        path.write_text(json.dumps({'schemaVersion':1,'status':'VERIFIED','verifiedAt':AT,
                                    'archivePath':str(self.archive),'archiveSha256':'a'*64}))
        self.assertEqual(self.collect()['backup']['status'],'VERIFIED')
        self.archive.unlink()
        result=self.collect()
        self.assertEqual(result['backup']['status'],'INVALID')
        self.assertIn('backup-unavailable',result['issues'])

    def test_future_dated_receipts_are_not_fresh(self):
        future = datetime.fromtimestamp((NOW + 3600000) / 1000, timezone.utc).isoformat()
        path=self.state/'operations/telegram-backup-latest.json'
        path.write_text(json.dumps({'schemaVersion':1,'status':'VERIFIED','verifiedAt':future,
                                    'archivePath':str(self.archive),'archiveSha256':'a'*64}))
        (self.state/'operations/telegram-watchdog-status.json').write_text(json.dumps({
            'observedAt':future,'lastSuccessAt':future,'healthy':True,'gateway':{'ok':True},'consecutiveFailures':0}))
        result=self.collect()
        self.assertEqual(result['backup']['status'],'INVALID')
        self.assertTrue(result['observer']['stale'])
        self.assertIn('observer-snapshot-stale',result['warnings'])
        # Ordinary clock skew within five minutes is still accepted.
        near = datetime.fromtimestamp((NOW + 60000) / 1000, timezone.utc).isoformat()
        path.write_text(json.dumps({'schemaVersion':1,'status':'VERIFIED','verifiedAt':near,
                                    'archivePath':str(self.archive),'archiveSha256':'a'*64}))
        self.assertEqual(self.collect()['backup']['status'],'VERIFIED')

    def test_failed_dreaming_receipt_is_not_promotion_proof(self):
        self.connection.execute("UPDATE cron_jobs SET state_json=?",(json.dumps({'lastRunStatus':'error','consecutiveErrors':2}),))
        result=self.collect()
        self.assertIn('dreaming-unhealthy',result['issues'])
        self.assertEqual(result['cron']['dreaming']['scope'],'scheduler-receipt')

    def test_three_failures_create_one_incident(self):
        saved={}
        for index in range(2):
            saved=watchdog.transition(saved,False,['delivery-stale'],AT)
            self.assertNotIn('pendingNotification',saved)
        saved=watchdog.transition(saved,False,['delivery-stale'],AT)
        identifier=saved['incident']['id']
        self.assertEqual(saved['pendingNotification']['kind'],'failure')
        for index in range(8):
            saved=watchdog.transition(saved,False,['delivery-stale'],AT)
        self.assertEqual(saved['incident']['id'],identifier)

    def test_recovery_before_failure_delivery_suppresses_obsolete_alert(self):
        saved=watchdog.transition(self.incident(),True,[],AT)
        self.assertNotIn('pendingNotification',saved)
        self.assertEqual(saved['consecutiveFailures'],0)

    def test_persistent_new_gateway_issue_escalates_once(self):
        saved=self.incident();saved['incident']['failureDelivered']=True;saved.pop('pendingNotification')
        issues=['delivery-stale','gateway-check-timeout']
        for i in range(2):
            saved=watchdog.transition(saved,False,issues,AT)
            self.assertNotIn('pendingNotification',saved)
        saved=watchdog.transition(saved,False,issues,AT)
        self.assertTrue(saved['pendingNotification']['escalation'])
        self.assertEqual(saved['pendingNotification']['kind'],'failure')
        saved.pop('pendingNotification')
        for i in range(5):
            saved=watchdog.transition(saved,False,issues,AT)
            self.assertNotIn('pendingNotification',saved)
        saved=watchdog.transition(saved,False,['gateway-check-timeout'],AT)
        self.assertNotIn('pendingNotification',saved)

    def test_alternating_transient_issues_do_not_trigger_persistent_alert(self):
        saved={}
        for i in range(8):
            saved=watchdog.transition(saved,False,['delivery-stale' if i%2 else 'gateway-check-timeout'],AT)
            self.assertNotIn('pendingNotification',saved)

    def test_verified_receipt_deduplicates_and_recovery_is_once(self):
        saved=self.incident();calls=[];writes=[]
        def run(arguments,**kwargs):
            calls.append(arguments);return self.sent()
        saved=watchdog.send_pending(saved,self.state,Path('/fake-cli'),NOW,lambda x:writes.append(copy.deepcopy(x)),runner=run)
        self.assertEqual(writes[0]['notificationState'],'sending')
        self.assertEqual(saved['notificationState'],'delivered')
        self.assertNotIn('pendingNotification',saved)
        watchdog.send_pending(saved,self.state,Path('/fake-cli'),NOW+1000000,lambda x:None,runner=run)
        self.assertEqual(len(calls),1)
        self.assertEqual(calls[0][:3],['/fake-cli','message','send'])
        saved=watchdog.transition(saved,True,[],AT)
        self.assertEqual(saved['pendingNotification']['kind'],'recovery')
        saved=watchdog.send_pending(saved,self.state,Path('/fake-cli'),NOW+1000000,lambda x:None,runner=run)
        saved=watchdog.transition(saved,True,[],AT)
        self.assertNotIn('pendingNotification',saved)
        self.assertEqual(len(calls),2)

    def test_recovery_is_deferred_on_new_failure(self):
        saved=self.incident();saved['incident']['failureDelivered']=True;saved.pop('pendingNotification')
        saved=watchdog.transition(saved,True,[],AT)
        saved=watchdog.transition(saved,False,['delivery-stale'],AT)
        self.assertNotIn('pendingNotification',saved)
        saved=watchdog.transition(saved,True,[],AT)
        self.assertEqual(saved['pendingNotification']['kind'],'recovery')

    def test_receipt_is_owner_and_transport_bound(self):
        for override in [{'action':'other'},{'channel':'slack'},{'dryRun':True},
                         {'payload':{'ok':True,'messageId':7,'chatId':'999'}},
                         {'payload':{'ok':True,'messageId':True,'chatId':'123456'}},
                         {'payload':{'ok':True,'chatId':'123456'}},{'payload':'unexpected'}]:
            saved=watchdog.send_pending(self.incident(),self.state,Path('/fake'),NOW,lambda x:None,
                                       runner=lambda *a,**k:self.sent(**override))
            self.assertEqual(saved['notificationState'],'pending-retry')
            self.assertIn('pendingNotification',saved)
            self.assertNotIn('private diagnostic',json.dumps(saved))

    def test_transport_retry_is_rate_limited_without_rerunning_work(self):
        calls=[]
        def failure(*a,**k):
            calls.append(a[0]);raise subprocess.TimeoutExpired(a[0],60)
        saved=watchdog.send_pending(self.incident(),self.state,Path('/fake'),NOW,lambda x:None,runner=failure)
        identifier=saved['pendingNotification']['id']
        watchdog.send_pending(saved,self.state,Path('/fake'),NOW+899000,lambda x:None,runner=failure)
        self.assertEqual(len(calls),1)
        watchdog.send_pending(saved,self.state,Path('/fake'),NOW+900000,lambda x:None,runner=failure)
        self.assertEqual(len(calls),2)
        self.assertEqual(saved['pendingNotification']['id'],identifier)
        self.assertTrue(all(call[1:3]==['message','send'] for call in calls))

    def test_owner_configuration_drift_blocks_send(self):
        (self.state/'openclaw.json').chmod(0o644)
        def forbidden(*a,**k):
            self.fail('must not send')
        saved=watchdog.send_pending(self.incident(),self.state,Path('/fake'),NOW,lambda x:None,runner=forbidden)
        self.assertEqual(saved['notificationState'],'owner-policy-unavailable')

    def test_test_notification_text_is_clearly_labeled(self):
        text=watchdog.alert_text({'pendingNotification':{'kind':'test','id':'test-id'}})
        self.assertIn('실제 장애 알림이 아닙니다',text)

    def test_private_atomic_receipt_and_bounded_logs(self):
        path=self.state/'operations/test.json'
        watchdog.atomic_json(path,{'ok':True})
        self.assertEqual(path.stat().st_mode&0o777,0o600)
        log=self.state/'operations/test.jsonl'
        for i in range(30):watchdog.append_bounded_log(log,{'index':i},max_bytes=100)
        self.assertLessEqual(log.stat().st_size,100)
        self.assertLessEqual(log.with_suffix('.jsonl.1').stat().st_size,100)

    def test_verifier_diagnostics_remain_private(self):
        result=watchdog.verify(self.state,Path('/fake'),Path('/fake-package'),
                               runner=lambda *a,**k:subprocess.CompletedProcess([],1,'','owner-secret-token'))
        self.assertFalse(result['ok']);self.assertNotIn('owner-secret-token',json.dumps(result))


class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.args=argparse.Namespace(apply=True,state_dir=self.root/'state',installed_dir=self.root/'installed',
                                    launchagents_dir=self.root/'agents',cli=Path('/fake-cli'),package=Path('/fake-package'),interval=300)
        self.args.installed_dir.mkdir();(self.args.installed_dir/'previous.txt').write_text('keep previous')
        self.args.launchagents_dir.mkdir()
        self.plist=self.args.launchagents_dir/(installer.LABEL+'.plist')
        self.original=plistlib.dumps({'Label':installer.LABEL,'ProgramArguments':['old-observer']})
        self.plist.write_bytes(self.original)
        self.loaded={installer.LABEL:True,installer.OLD_LABEL:False}
        self.calls=[];self.fail_bootstrap=False

    def tearDown(self):self.temp.cleanup()

    def command(self,arguments,required=True):
        self.calls.append(arguments)
        result=subprocess.CompletedProcess(arguments,0,'','')
        if arguments[0]=='launchctl':
            action=arguments[1];label=arguments[2].split('/')[-1]
            if action=='print-disabled':result.stdout='"'+installer.OLD_LABEL+'" => true'
            elif action=='print':result.returncode=0 if self.loaded[label] else 1
            elif action=='bootout':self.loaded[label]=False
            elif action=='bootstrap':
                if self.fail_bootstrap:
                    self.fail_bootstrap=False
                    raise ValueError('injected bootstrap failure')
                label=Path(arguments[3]).stem;self.loaded[label]=True
        else:
            result.stdout=json.dumps({'schemaVersion':1,'ok':True,'gatewayOk':True,'observedAt':AT})
        if required and result.returncode:raise ValueError('injected command error')
        return result

    def test_install_copies_complete_release_and_first_receipt(self):
        with patch.object(installer,'command',self.command),patch.object(installer,'strict_check',return_value={'ok':True,'live':True}):
            installer.install(self.args)
        for name in installer.FILES:self.assertTrue((self.args.installed_dir/name).exists())
        saved=json.loads((self.args.state_dir/'operations/telegram-observer-install-latest.json').read_text())
        self.assertEqual(saved['status'],'INSTALLED');self.assertTrue(saved['firstObserverRun']['gatewayOk'])
        plist=plistlib.loads(self.plist.read_bytes())
        self.assertEqual(plist['StartInterval'],300)
        self.assertNotIn('123456',json.dumps(plist))
        self.assertEqual(plist['StandardOutPath'],'/dev/null')

    def test_bootstrap_failure_restores_files_and_launchagent(self):
        self.fail_bootstrap=True
        with patch.object(installer,'command',self.command),patch.object(installer,'strict_check',return_value={'ok':True,'live':True}):
            with self.assertRaisesRegex(ValueError,'previous installation restored'):installer.install(self.args)
        self.assertEqual((self.args.installed_dir/'previous.txt').read_text(),'keep previous')
        self.assertFalse((self.args.installed_dir/'telegram-watchdog.py').exists())
        self.assertEqual(self.plist.read_bytes(),self.original)
        self.assertTrue(self.loaded[installer.LABEL])
        self.assertFalse(self.loaded[installer.OLD_LABEL])
        receipt=list((self.args.state_dir/'operations').glob('telegram-observer-install-*/install-failure.json'))[0]
        self.assertEqual(json.loads(receipt.read_text())['status'],'ROLLED_BACK')

    def test_strict_preflight_failure_leaves_installation_untouched(self):
        with patch.object(installer,'strict_check',side_effect=ValueError('preflight failed')):
            with self.assertRaises(ValueError):installer.install(self.args)
        self.assertEqual((self.args.installed_dir/'previous.txt').read_text(),'keep previous')
        self.assertFalse(self.args.state_dir.exists())


class FailureGroupingTest(unittest.TestCase):
    """Classified failure shape must be diagnosable without exposing command text."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.database = Path(self.temp.name) / 'tasks.sqlite'
        self.connection = sqlite3.connect(self.database)
        self.connection.executescript(
            'CREATE TABLE task_runs(status TEXT,error TEXT,detail_json TEXT,task_kind TEXT,'
            'runtime TEXT,last_tool_name TEXT,created_at INTEGER,ended_at INTEGER);')
        self.addCleanup(self.connection.close)

    def add(self, status, error, detail, kind, runtime, tool, at=NOW):
        self.connection.execute('INSERT INTO task_runs VALUES(?,?,?,?,?,?,?,?)',
                                (status, error, detail, kind, runtime, tool, at, at))
        self.connection.commit()

    def test_same_cause_collapses_and_distinct_causes_stay_separate(self):
        for _ in range(6):
            self.add('failed', 'heartbeat failed: agent-runner-failure', None, 'automation_run', 'cron', None)
        for _ in range(4):
            self.add('failed', 'Command failed (exit code 1)', '{"exitCode": 1}', 'exec', 'cli', None)
        groups = status.task_failure_groups(self.connection, NOW + 1000)['groups']
        self.assertEqual([(g['errorClass'], g['count'], g['exitCode']) for g in groups],
                         [('heartbeat-agent-runner-failure', 6, None), ('command-failed', 4, 1)])

    def test_command_text_and_raw_error_never_appear_in_the_group(self):
        self.add('failed', 'Command failed: curl -H "Authorization: Bearer SECRET_TOKEN" /etc/passwd',
                 '{"exitCode": 1}', 'exec', 'cli', None)
        text = json.dumps(status.task_failure_groups(self.connection, NOW + 1000), ensure_ascii=False)
        for secret in ('SECRET_TOKEN', 'curl', 'Authorization', '/etc/passwd'):
            self.assertNotIn(secret, text)
        self.assertIn('command-failed', text)

    def test_unknown_error_text_is_reduced_to_a_constant_not_echoed(self):
        self.assertEqual(status.error_class('totally novel PRIVATE detail'), 'other')
        self.assertEqual(status.error_class(None), 'none')

    def test_group_and_row_counts_stay_bounded(self):
        for index in range(status.MAX_FAILURE_GROUPS + 5):
            self.add('failed', 'Command failed', '{"exitCode": %d}' % index, 'exec', 'cli', None)
        result = status.task_failure_groups(self.connection, NOW + 1000)
        self.assertEqual(len(result['groups']), status.MAX_FAILURE_GROUPS)
        self.assertEqual(result['groupsOmitted'], 5)

    def test_unsafe_identifier_is_replaced_rather_than_reported(self):
        self.add('failed', 'Command failed', None, 'exec', 'cli', 'tool name with spaces/and;semicolons')
        self.assertEqual(status.task_failure_groups(self.connection, NOW + 1000)['groups'][0]['lastToolName'],
                         'other')


class ExecCapabilityTest(unittest.TestCase):
    """The Gateway PATH probe must read one line and must never become a health gate."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name) / 'state'
        (self.state / 'service-env').mkdir(parents=True)
        self.env = self.state / 'service-env/ai.openclaw.personaledge.env'

    def write_env(self, text):
        self.env.write_text(text)
        self.env.chmod(0o600)

    def test_only_the_path_assignment_is_read_from_a_secret_bearing_file(self):
        self.write_env("export OPENROUTER_API_KEY='sk-or-SECRET'\n"
                       "export PATH='/usr/bin:/bin'\nexport TELEGRAM_TOKEN='SECRET2'\n")
        self.assertEqual(status.gateway_exec_path(self.state), '/usr/bin:/bin')
        result = status.exec_capabilities(self.state, names=('sh',))
        text = json.dumps(result)
        self.assertNotIn('SECRET', text)
        self.assertEqual(result['pathSource'], 'gateway-service-env')

    def test_missing_tool_is_reported_without_a_resolved_location(self):
        self.write_env("export PATH='/nonexistent-dir'\n")
        result = status.exec_capabilities(self.state, names=('rg',))
        self.assertEqual(result['missing'], ['rg'])
        self.assertEqual(result['tools'], {'rg': False})
        self.assertNotIn('/nonexistent-dir', json.dumps(result['tools']))

    def test_unreadable_environment_reports_unavailable_instead_of_guessing(self):
        self.assertEqual(status.exec_capabilities(self.state, names=('rg',)),
                         {'pathSource': 'unavailable', 'checked': False, 'missing': [], 'tools': {}})

    def test_symlinked_environment_file_is_refused(self):
        real = self.state / 'elsewhere.env'
        real.write_text("export PATH='/usr/bin'\n")
        self.env.symlink_to(real)
        self.assertIsNone(status.gateway_exec_path(self.state))


class HermesAvailabilityTest(unittest.TestCase):
    """Availability is a path check, never a model call."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'worker'
        (self.root / 'bin').mkdir(parents=True)
        (self.root / 'runtime/.venv/bin').mkdir(parents=True)
        (self.root / 'bin/hermes-operations.py').write_text('#\n')
        (self.root / 'installation.json').write_text(json.dumps({'version': '0.21.1'}))

    def link_interpreter(self, target=None):
        (self.root / 'runtime/.venv/bin/python').symlink_to(target or Path(os.sys.executable))

    def test_a_virtualenv_symlink_interpreter_is_accepted(self):
        self.link_interpreter()
        result = status.hermes_availability(self.root)
        self.assertTrue(result['invocable'])
        self.assertEqual(result['version'], '0.21.1')

    def test_missing_interpreter_is_reported_not_silently_healthy(self):
        self.assertEqual(status.hermes_availability(self.root)['reason'], 'interpreter-not-executable')

    def test_absent_installation_is_not_an_error(self):
        self.assertEqual(status.hermes_availability(self.root / 'absent'),
                         {'installed': False, 'invocable': False, 'reason': 'not-installed'})

    def test_unreadable_installation_record_is_refused(self):
        self.link_interpreter()
        (self.root / 'installation.json').write_text('{ broken')
        self.assertEqual(status.hermes_availability(self.root)['reason'], 'installation-record-unreadable')


class HermesDispatchTest(unittest.TestCase):
    """One incident earns at most one automatic review, and intent is durable before the spawn."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'worker'
        (self.root / 'bin').mkdir(parents=True)
        (self.root / 'runtime/.venv/bin').mkdir(parents=True)
        (self.root / 'bin/hermes-operations.py').write_text('#\n')
        (self.root / 'runtime/.venv/bin/python').symlink_to(Path(os.sys.executable))
        self.saved = {'incident': {'id': 'abc123', 'active': True}}
        self.issues = ['observer-gateway-unhealthy']

    def test_an_active_incident_with_a_qualified_issue_is_dispatched_once(self):
        plan = watchdog.hermes_dispatch_allowed(self.saved, self.issues, NOW)
        self.assertEqual(plan['incidentId'], 'abc123')
        saved = watchdog.dispatch_hermes(self.saved, plan, self.root, NOW, lambda v: None,
                                         spawner=lambda *a, **k: None)
        self.assertEqual(saved['hermesDispatch']['state'], 'dispatched')
        self.assertIsNone(watchdog.hermes_dispatch_allowed(saved, self.issues, NOW + 1000))

    def test_transport_and_backup_faults_do_not_spend_a_model_call(self):
        self.assertIsNone(watchdog.hermes_dispatch_allowed(
            self.saved, ['delivery-failed', 'backup-stale'], NOW))

    def test_no_dispatch_without_an_open_incident(self):
        self.assertIsNone(watchdog.hermes_dispatch_allowed({}, self.issues, NOW))
        self.assertIsNone(watchdog.hermes_dispatch_allowed(
            {'incident': {'id': 'abc123', 'active': False}}, self.issues, NOW))

    def test_rate_limit_and_daily_ceiling_are_enforced(self):
        recent = {'hermesDispatch': {'lastIncidentId': 'older', 'lastAtMs': NOW - 1000,
                                     'recentAtMs': [NOW - 1000]}}
        self.assertIsNone(watchdog.hermes_dispatch_allowed(dict(self.saved, **recent), self.issues, NOW))
        full = {'hermesDispatch': {'lastIncidentId': 'older',
                                   'lastAtMs': NOW - watchdog.HERMES_DISPATCH_INTERVAL_SECONDS * 1000 - 1,
                                   'recentAtMs': [NOW - 100, NOW - 200]}}
        self.assertIsNone(watchdog.hermes_dispatch_allowed(dict(self.saved, **full), self.issues, NOW))

    def test_stale_daily_entries_expire_so_the_budget_recovers(self):
        aged = {'hermesDispatch': {'lastIncidentId': 'older', 'lastAtMs': NOW - 90000000,
                                   'recentAtMs': [NOW - 90000000, NOW - 88000000]}}
        self.assertIsNotNone(watchdog.hermes_dispatch_allowed(dict(self.saved, **aged), self.issues, NOW))

    def test_durable_intent_is_written_before_the_spawn(self):
        order = []
        plan = watchdog.hermes_dispatch_allowed(self.saved, self.issues, NOW)
        watchdog.dispatch_hermes(self.saved, plan, self.root, NOW,
                                 lambda v: order.append(('persist', (v.get('hermesDispatch') or {}).get('state'))),
                                 spawner=lambda *a, **k: order.append(('spawn', None)))
        self.assertEqual(order[0], ('persist', 'dispatching'))
        self.assertIn(('spawn', None), order)

    def test_a_missing_controller_is_recorded_and_never_spawned(self):
        (self.root / 'bin/hermes-operations.py').unlink()
        plan = watchdog.hermes_dispatch_allowed(self.saved, self.issues, NOW)

        def refuse(*args, **kwargs):
            raise AssertionError('must not spawn')

        saved = watchdog.dispatch_hermes(self.saved, plan, self.root, NOW, lambda v: None, spawner=refuse)
        self.assertEqual(saved['hermesDispatch']['state'], 'unavailable')


if __name__=='__main__':unittest.main()
