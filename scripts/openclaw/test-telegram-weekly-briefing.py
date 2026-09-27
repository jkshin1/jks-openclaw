#!/usr/bin/env python3
"""Offline weekly briefing tests: public-only summaries and durable no-replay delivery."""

import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


weekly = load('telegram-weekly-briefing')


class WeeklyBriefingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.state = self.root / 'state'; self.state.mkdir(mode=0o700)
        self.engine = load('telegram-briefing')
        self.engine.atomic_json(self.state / 'openclaw.json', {
            'commands': {'ownerAllowFrom': ['telegram:12345']},
            'channels': {'telegram': {'enabled': True, 'dmPolicy': 'allowlist', 'allowFrom': ['12345']}},
            'privateOwnerHistory': 'OWNER_HISTORY_MUST_NEVER_ENTER_MODEL'})
        self.briefing_root = self.state / 'operations/workflows/briefing'
        self.weekly_root = self.state / 'operations/workflows/weekly-briefing/ai-llm'
        self.urls = ['https://example.com/ai-feed', 'https://example.org/research']
        self.topic = {'id': 'ai-llm', 'name': 'AI·LLM', 'enabled': True, 'importance': 'all', 'urls': self.urls}
        self.engine.atomic_json(self.briefing_root / 'topics.json', {'schemaVersion': 1, 'topics': {'ai-llm': self.topic}})
        self.bodies = {url: 'Original public baseline.' for url in self.urls}
        self.fetch_calls = []; self.summary_calls = []; self.send_calls = []
        original = self.engine.check_topic
        self.engine.check_topic = lambda root, state, topic, **kwargs: original(
            root, state, topic, fetcher=self.fetch, **kwargs)
        self.send_failure = None
        self.summary_failure = False
        self.summary_result = {'items': [{'title': '새 추론 모델 공개', 'summary': '공개 출처가 새로운 추론 모델을 발표했습니다.',
            'whyImportant': '추론 기술의 선택 범위가 넓어질 수 있습니다.',
            'uncertainty': '발표자의 주장으로 독립 재현은 확인되지 않았습니다.', 'sourceIds': ['S1']}]}

    def tearDown(self):
        self.temporary.cleanup()

    def fetch(self, url, previous):
        self.fetch_calls.append(url)
        value = self.bodies[url]
        if isinstance(value, Exception): raise value
        return {'body': value, 'mime': 'text/plain', 'resolvedUrl': url, 'fetchedAt': self.engine.now_iso()}

    def summarize(self, prompt):
        self.summary_calls.append(prompt)
        if self.summary_failure: raise ValueError('simulated model failure')
        return json.dumps(self.summary_result, ensure_ascii=False)

    def send(self, arguments, **kwargs):
        self.send_calls.append(arguments)
        state = self.active()
        self.assertEqual(state['delivery']['status'], 'sending')
        self.assertEqual(state['delivery']['messageSha256'], state['message']['sha256'])
        self.assertEqual(arguments[1:3], ['message', 'send'])
        if self.send_failure: raise self.send_failure
        return subprocess.CompletedProcess(arguments, 0, json.dumps({
            'action': 'send', 'channel': 'telegram', 'dryRun': False,
            'payload': {'ok': True, 'chatId': '12345', 'messageId': 101}}), '')

    def perform(self, **kwargs):
        return weekly.perform(self.state, engine=self.engine, summarizer=self.summarize, runner=self.send, **kwargs)

    def active_path(self):
        active = self.engine.read_json(self.weekly_root / 'active.json')
        return self.weekly_root / 'runs' / active['id'] / 'run.json'

    def active(self):
        return self.engine.read_json(self.active_path())

    def baseline(self):
        result = self.perform(send=True)
        self.assertEqual(result['status'], 'silent')
        self.assertFalse(self.send_calls); self.assertFalse(self.summary_calls)
        return result

    def changed(self):
        self.bodies[self.urls[0]] += '\nReleased new reasoning model with a benchmark claim.'

    def snapshots(self):
        return {path.name: path.read_bytes() for path in (self.briefing_root / 'snapshots/ai-llm').glob('*.json')}

    def test_baseline_and_unchanged_are_silent_without_model_or_send(self):
        self.baseline()
        self.perform(send=True)
        self.assertFalse(self.summary_calls); self.assertFalse(self.send_calls)
        self.assertTrue(self.active()['baselineCommitted'])

    def test_verified_important_changes_sent_with_sources_and_limits(self):
        self.baseline(); self.changed()
        result = self.perform(send=True)
        self.assertEqual(result['status'], 'delivered')
        self.assertEqual(len(self.summary_calls), 1); self.assertEqual(len(self.send_calls), 1)
        message = self.send_calls[0][self.send_calls[0].index('--message') + 1]
        for value in ['왜 중요한가', '불확실성', self.urls[0], '새 추론 모델']:
            self.assertIn(value, message)
        self.assertLessEqual(len(message), weekly.MAX_MESSAGE_CHARS)
        run = self.active()
        self.assertEqual(run['delivery']['messageId'], '101')
        self.assertTrue(run['baselineCommitted'])
        self.assertEqual(Path(run['delivery']['receiptPath']).stat().st_mode & 0o777, 0o600)
        task = load('telegram-task-status').query_status(self.state, task_id=run['id'])['tasks'][0]
        self.assertEqual(task['execution']['status'], 'succeeded')
        self.assertEqual(task['verification']['status'], 'passed')
        self.assertEqual(task['artifactStatus'], 'verified')
        self.assertEqual(task['delivery']['status'], 'platform-accepted')

    def test_model_input_contains_only_selected_public_changes(self):
        self.baseline(); self.changed(); self.perform(send=False)
        prompt = self.summary_calls[0]
        self.assertIn('Released new reasoning model', prompt)
        self.assertIn(self.urls[0], prompt)
        for value in ['OWNER_HISTORY_MUST_NEVER_ENTER_MODEL', str(self.state), 'ownerAllowFrom', '12345']:
            self.assertNotIn(value, prompt)
        self.assertIn('신뢰되지 않은 인용 데이터', prompt)

    def test_prepare_preserves_baseline_and_existing_delivery_does_not_fetch_or_summarize(self):
        self.baseline(); before = self.snapshots(); self.changed()
        prepared = self.perform(send=False)
        self.assertEqual(prepared['status'], 'prepared')
        self.assertEqual(self.snapshots(), before)
        fetches, summaries = len(self.fetch_calls), len(self.summary_calls)
        result = self.perform(send=True, existing=Path(prepared['runDirectory']))
        self.assertEqual(result['status'], 'delivered')
        self.assertEqual(len(self.fetch_calls), fetches); self.assertEqual(len(self.summary_calls), summaries)

    def test_summary_failure_retries_persisted_changes_before_new_fetch(self):
        self.baseline(); before = self.snapshots(); self.changed(); self.summary_failure = True
        with self.assertRaisesRegex(ValueError, 'PENDING_CHANGES_PRESERVED'):
            self.perform(send=True)
        failed_run = self.active()['id']; fetches = len(self.fetch_calls)
        self.assertEqual(self.snapshots(), before)
        self.bodies[self.urls[0]] = 'Unrelated new content after model failure.'
        self.summary_failure = False
        result = self.perform(send=True)
        self.assertEqual(self.active()['id'], failed_run)
        self.assertEqual(len(self.fetch_calls), fetches)
        self.assertEqual(result['status'], 'delivered')
        self.assertIn('Released new reasoning model', self.summary_calls[-1])
        self.assertNotIn('Unrelated new content', self.summary_calls[-1])

    def test_unknown_send_never_automatically_replayed_or_refetched(self):
        self.baseline(); self.changed()
        self.send_failure = subprocess.TimeoutExpired(['fake'], 60)
        with self.assertRaisesRegex(ValueError, 'NO_AUTOMATIC_RESEND'):
            self.perform(send=True)
        counts = (len(self.fetch_calls), len(self.summary_calls), len(self.send_calls))
        self.send_failure = None
        with self.assertRaisesRegex(ValueError, 'NO_AUTOMATIC_RESEND'):
            self.perform(send=True)
        self.assertEqual((len(self.fetch_calls), len(self.summary_calls), len(self.send_calls)), counts)
        self.assertFalse(self.active()['baselineCommitted'])

    def test_missing_cli_process_can_retry_same_pending_message(self):
        self.baseline(); self.changed(); self.send_failure = FileNotFoundError('fake CLI absent')
        with self.assertRaisesRegex(ValueError, 'PROCESS_NOT_STARTED'):
            self.perform(send=True)
        counts = (len(self.fetch_calls), len(self.summary_calls))
        self.send_failure = None
        self.assertEqual(self.perform(send=True)['status'], 'delivered')
        self.assertEqual((len(self.fetch_calls), len(self.summary_calls)), counts)

    def test_saved_original_receipt_finishes_unknown_send_without_replay(self):
        self.baseline(); self.changed()
        original = {'action': 'send', 'channel': 'telegram', 'dryRun': False,
                    'payload': {'ok': True, 'chatId': '12345', 'messageId': 999}}
        self.send_failure = subprocess.TimeoutExpired(['fake'], 60, output=json.dumps(original))
        with self.assertRaisesRegex(ValueError, 'NO_AUTOMATIC_RESEND'):
            self.perform(send=True)
        sends = len(self.send_calls)
        self.send_failure = None
        result = self.perform(send=True)
        self.assertTrue(result['recoveredReceipt'])
        self.assertEqual(result['messageId'], '999'); self.assertEqual(len(self.send_calls), sends)

    def test_invalid_transport_receipt_stays_unknown(self):
        self.baseline(); self.changed()
        def false_success(*a, **k):
            return subprocess.CompletedProcess([], 0, json.dumps({'action': 'send', 'channel': 'telegram', 'dryRun': False,
                'payload': {'ok': True, 'chatId': '99999', 'messageId': 123}}), '')
        with self.assertRaisesRegex(ValueError, 'NO_AUTOMATIC_RESEND'):
            weekly.perform(self.state, send=True, engine=self.engine, summarizer=self.summarize, runner=false_success)
        self.assertEqual(self.active()['delivery']['status'], 'unknown')

    def test_partial_source_failure_is_reported_and_failed_baseline_preserved(self):
        self.baseline(); before = self.snapshots(); self.changed()
        self.bodies[self.urls[1]] = self.engine.BriefingError('HTTP_503')
        result = self.perform(send=True)
        self.assertEqual(result['status'], 'delivered'); self.assertFalse(result['ok'])
        message = self.send_calls[0][self.send_calls[0].index('--message') + 1]
        self.assertIn('일부 출처 수집 실패', message)
        self.assertIn(self.urls[1], message)
        failed_id = self.engine.sha256_text(self.urls[1])[:24] + '.json'
        self.assertEqual(self.snapshots()[failed_id], before[failed_id])

    def test_all_failed_sources_send_only_failure_notice_without_model(self):
        self.baseline()
        for url in self.urls: self.bodies[url] = self.engine.BriefingError('HTTP_503')
        result = self.perform(send=True)
        self.assertFalse(result['ok']); self.assertEqual(result['status'], 'delivered')
        self.assertFalse(self.summary_calls)
        message = self.send_calls[0][self.send_calls[0].index('--message') + 1]
        self.assertIn('변경 없음으로 판단하지 않았습니다', message)

    def test_repeating_same_failure_notice_is_hash_deduplicated(self):
        self.baseline()
        for url in self.urls: self.bodies[url] = self.engine.BriefingError('HTTP_503')
        self.perform(send=True)
        result = self.perform(send=True)
        self.assertEqual(result['status'], 'deduplicated')
        self.assertEqual(len(self.send_calls), 1)

    def test_recovered_then_recurrent_source_failure_is_not_suppressed_forever(self):
        self.baseline()
        for url in self.urls: self.bodies[url] = self.engine.BriefingError('HTTP_503')
        self.perform(send=True)
        for url in self.urls: self.bodies[url] = 'Original public baseline.'
        self.assertEqual(self.perform(send=True)['status'], 'silent')
        for url in self.urls: self.bodies[url] = self.engine.BriefingError('HTTP_503')
        self.assertEqual(self.perform(send=True)['status'], 'delivered')
        self.assertEqual(len(self.send_calls), 2)

    def test_completed_run_reinvocation_does_not_send(self):
        self.baseline(); self.changed(); sent = self.perform(send=True)
        result = self.perform(send=True, existing=Path(sent['runDirectory']))
        self.assertTrue(result['alreadyCompleted'])
        self.assertEqual(len(self.send_calls), 1)

    def test_successful_send_commit_failure_retries_only_commit(self):
        self.baseline(); self.changed()
        original = self.engine.commit_run
        with patch.object(self.engine, 'commit_run', side_effect=OSError('injected baseline commit failure')):
            with self.assertRaises(OSError): self.perform(send=True)
        counts = (len(self.fetch_calls), len(self.summary_calls), len(self.send_calls))
        self.assertEqual(self.active()['delivery']['status'], 'delivered')
        result = self.perform(send=True)
        self.assertTrue(result['alreadyCompleted'])
        self.assertEqual((len(self.fetch_calls), len(self.summary_calls), len(self.send_calls)), counts)

    def test_changed_prepared_message_is_not_sent(self):
        self.baseline(); self.changed(); prepared = self.perform(send=False)
        (Path(prepared['runDirectory']) / 'briefing.txt').write_text('modified')
        with self.assertRaisesRegex(ValueError, 'PERSISTED_MESSAGE_CHANGED'):
            self.perform(send=True)
        self.assertFalse(self.send_calls)

    def test_model_items_require_korean_known_sources_and_max_ten(self):
        candidates = [{'id': 'S1'}]
        for changed in [dict(self.summary_result, items=self.summary_result['items'] * 11),
                        {'items': [{**self.summary_result['items'][0], 'sourceIds': ['invented']}]},
                        {'items': [{**self.summary_result['items'][0], 'summary': 'English only'}]}]:
            with self.assertRaises(ValueError):
                weekly.validate_summary(json.dumps(changed), candidates)

    def test_ten_items_validate_and_render_without_dropping_news(self):
        summary = {'items': [{**self.summary_result['items'][0], 'title': '주요 뉴스 ' + str(i)} for i in range(1, 11)]}
        candidates = [{'id': 'S1', 'url': 'https://example.com/news'}]
        parsed = weekly.validate_summary(json.dumps(summary), candidates)
        text = weekly.render_message(parsed, candidates, {'finishedAt': '2026-09-12T00:00:00Z', 'sources': []})
        self.assertIn('10. 주요 뉴스 10', text)
        self.assertEqual(text.count('출처:'), 10)
        self.assertLessEqual(len(text.encode('utf-16-le')) // 2, weekly.MAX_MESSAGE_CHARS)

    def test_rss_article_candidates_use_only_observed_article_links(self):
        observed = {'sources': [{'status': 'CHANGED', 'importance': {'selected': True}, 'url': self.urls[0],
            'resolvedUrl': self.urls[0], 'fetchedAt': '2026-09-12T00:00:00Z', 'diff': {'addedText':
            'New model | https://example.com/article/model | 2026-09-11T12:00:00Z | Released a new reasoning model.\n'
            'Older result | https://example.com/article/result | 2026-09-10T12:00:00Z | Released a new reasoning model.'}}],
            'finishedAt': '2026-09-12T00:00:00Z'}
        candidates, coverage = weekly.public_candidates(self.engine, observed)
        self.assertEqual(len(candidates), 2)
        self.assertEqual(candidates[0]['url'], 'https://example.com/article/model')
        self.assertEqual(candidates[0]['sourceFeedUrl'], self.urls[0])
        self.assertEqual(candidates[0]['citationScope'], 'article')
        rendered = weekly.render_message(self.summary_result, candidates, observed, coverage)
        self.assertIn('https://example.com/article/model', rendered)
        self.assertNotIn(self.urls[0], rendered)
        self.assertIn('확인: 2026-09-12 09:00 KST', rendered)

    def test_window_is_previous_to_latest_saturday_nine_kst(self):
        for started, start, end in (
                ('2026-09-26T00:00:00.462358Z', '2026-09-19T09:00:00+09:00', '2026-09-26T09:00:00+09:00'),
                ('2026-09-27T15:05:00Z', '2026-09-19T09:00:00+09:00', '2026-09-26T09:00:00+09:00'),
                ('2026-09-25T23:59:59Z', '2026-09-12T09:00:00+09:00', '2026-09-19T09:00:00+09:00')):
            with self.subTest(started=started):
                window = weekly.briefing_window({'startedAt': started})
                self.assertEqual([value.isoformat() for value in window], [start, end])
        with self.assertRaisesRegex(ValueError, 'COLLECTION_TIMESTAMP_INVALID'):
            weekly.briefing_window({'startedAt': 'yesterday'})

    def test_missed_week_changes_are_limited_to_the_briefing_week(self):
        observed = {'startedAt': '2026-09-27T15:05:00Z', 'finishedAt': '2026-09-27T15:05:10Z',
            'sources': [{'status': 'CHANGED', 'importance': {'selected': True}, 'url': self.urls[0],
            'resolvedUrl': self.urls[0], 'fetchedAt': '2026-09-27T15:05:00Z', 'diff': {'addedText':
            'In week | https://example.com/a/in | 2026-09-22T12:00:00Z | Released a new reasoning model.\n'
            'Stale | https://example.com/a/old | 2026-09-15T12:00:00Z | Released a new reasoning model.\n'
            'Too new | https://example.com/a/new | 2026-09-27T01:00:00Z | Released a new reasoning model.\n'
            'Undated | https://example.com/a/undated |  | Released a new reasoning model.'}}]}
        candidates, coverage = weekly.public_candidates(self.engine, observed)
        self.assertEqual(sorted(item['url'] for item in candidates),
                         ['https://example.com/a/in', 'https://example.com/a/undated'])
        self.assertEqual(coverage['outsideWindow'], 2)
        rendered = weekly.render_message(self.summary_result, candidates, observed, coverage)
        self.assertIn('기간: 09/19 09:00 ~ 09/26 09:00 KST', rendered)

    def test_invalid_or_unzoned_collection_timestamp_fails_explicitly(self):
        for value in ['yesterday', '2026-09-12T09:00:00', None]:
            with self.assertRaisesRegex(ValueError, 'COLLECTION_TIMESTAMP_INVALID'):
                weekly.render_message({'items': []}, [], {'sources': [], 'finishedAt': value})

    def test_summary_cannot_insert_unverified_links_in_free_text(self):
        summary = copy.deepcopy(self.summary_result)
        summary['items'][0]['summary'] = '새 기술 링크 https://invented.example/paper'
        with self.assertRaisesRegex(ValueError, 'SUMMARY_KOREAN_FIELD_INVALID'):
            weekly.validate_summary(json.dumps(summary), [{'id': 'S1'}])

    def test_candidate_limit_and_public_input_truncation_are_visible(self):
        observed = {'sources': [{'status': 'CHANGED', 'importance': {'selected': True}, 'url': self.urls[0],
            'fetchedAt': '2026-09-12T00:00:00Z', 'diff': {'addedText': '\n'.join(
            'Model {} | https://example.com/article/{} | 2026-09-11 | Released reasoning model.'.format(i, i)
            for i in range(35)), 'addedTextTruncated': True}}], 'finishedAt': '2026-09-12T00:00:00Z'}
        candidates, coverage = weekly.public_candidates(self.engine, observed)
        self.assertEqual(len(candidates), 30)
        self.assertEqual(coverage['omittedCandidates'], 5)
        rendered = weekly.render_message(self.summary_result, candidates, observed, coverage)
        self.assertIn('길이·후보 수 제한', rendered)

    def test_community_candidates_survive_vendor_flood(self):
        sources = []
        for url, count, text in [('https://openai.com/news/rss.xml', 80, 'Released new reasoning model benchmark'),
                                 ('https://news.hada.io/rss/news', 12, '로컬 개발 도구'),
                                 ('https://hnrss.org/best', 12, 'Open source AI tool')]:
            sources.append({'status': 'CHANGED', 'importance': {'selected': True}, 'url': url,
                            'diff': {'addedText': '\n'.join(
                                f'{text} {i} | https://example.com/{len(sources)}/{i} | 2026-09-12 | {text}'
                                for i in range(count))}})
        candidates, coverage = weekly.public_candidates(self.engine, {'sources': sources})
        self.assertEqual(len(candidates), 30)
        self.assertGreaterEqual(sum(c['sourceFeedUrl'] != sources[0]['url'] for c in candidates), 15)
        self.assertEqual(len({c['sourceFeedUrl'] for c in candidates}), 3)
        self.assertEqual(coverage['omittedCandidates'], 74)

    def test_public_prompt_byte_budget_marks_omitted_text(self):
        candidates = [{'id': 'S' + str(index), 'url': self.urls[0], 'changes': '공개기술' * 1600,
                       'truncated': False} for index in range(12)]
        prompt = weekly.build_prompt(candidates)
        self.assertLessEqual(len(prompt.encode()), weekly.MAX_PROMPT_BYTES)
        selected = json.loads(prompt.split('PUBLIC_CHANGE_DATA_JSON:\n', 1)[1])
        self.assertTrue(any(item['truncated'] for item in selected))

    def test_invalid_article_url_falls_back_to_explicit_listing_citation(self):
        observed = {'sources': [{'status': 'CHANGED', 'importance': {'selected': True}, 'url': self.urls[0],
            'diff': {'addedText': 'Model | http://127.0.0.1/private | 2026-09-11 | Released model.'}}],
            'finishedAt': '2026-09-12T00:00:00Z'}
        candidates, coverage = weekly.public_candidates(self.engine, observed)
        self.assertEqual(candidates[0]['url'], self.urls[0])
        self.assertEqual(candidates[0]['citationScope'], 'listing')
        rendered = weekly.render_message(self.summary_result, candidates, observed, coverage)
        self.assertNotIn('127.0.0.1', rendered)
        self.assertIn('목록 페이지', rendered)

    def test_empty_importance_selection_is_silent_after_verification(self):
        self.baseline(); self.changed(); self.summary_result = {'items': []}
        result = self.perform(send=True)
        self.assertEqual(result['status'], 'silent')
        self.assertFalse(self.send_calls)
        self.assertTrue(self.active()['baselineCommitted'])

    def test_summary_adapter_is_isolated_helper_and_not_direct_provider_call(self):
        directory = self.root / 'adapter'; directory.mkdir(mode=0o700)
        arguments = []
        def runner(argv, **kwargs):
            arguments.append(argv)
            return subprocess.CompletedProcess(argv, 0, json.dumps({'status': 'ok', 'result': {
                'payloads': [{'text': json.dumps(self.summary_result, ensure_ascii=False)}]}}), '')
        text = weekly.default_summary('public input only', directory, Path('/reviewed/summarize-openclaw.py'), self.engine, runner)
        self.assertEqual(json.loads(text), self.summary_result)
        self.assertIn('/reviewed/summarize-openclaw.py', arguments[0])
        self.assertIn('agent', arguments[0]); self.assertIn('--json', arguments[0])
        self.assertNotIn('OPENAI_API_KEY', str(arguments))

    def test_recorded_route_is_the_one_the_adapter_reports(self):
        directory = self.root / 'route'; directory.mkdir(mode=0o700)
        for route, expected in (
                ({'provider': 'claude-cli', 'model': 'claude-opus-5-5', 'fallback': True, 'reason': 'usage-limit'},
                 {'provider': 'claude-cli', 'model': 'claude-opus-5-5', 'fallback': True,
                  'scope': 'existing-isolated-summary-adapter'}),
                (None, {'scope': 'unrecorded'})):
            with self.subTest(route=route):
                envelope = {'status': 'ok', 'result': {'payloads': [{'text': '{}'}]}}
                if route:
                    envelope['route'] = route
                (directory / 'model-stdout.json').write_text(json.dumps(envelope))
                self.assertEqual(weekly.adapter_route(directory), expected)

    def test_same_listing_citation_is_printed_once(self):
        candidates = [{'id': 'S1', 'url': 'https://example.com/news', 'citationScope': 'listing'},
                      {'id': 'S2', 'url': 'https://example.com/news', 'citationScope': 'listing'}]
        summary = copy.deepcopy(self.summary_result)
        summary['items'] = summary['items'][:1]
        summary['items'][0]['sourceIds'] = ['S1', 'S2']
        parsed = weekly.validate_summary(json.dumps(summary), candidates)
        text = weekly.render_message(parsed, candidates, {'finishedAt': '2026-09-12T00:00:00Z', 'sources': []})
        self.assertEqual(text.count('https://example.com/news'), 1)

    def test_no_candidate_run_does_not_claim_a_model_route(self):
        self.baseline(); self.changed(); self.summary_result = {'items': []}
        with patch.object(weekly, 'public_candidates', return_value=([], {'candidateCount': 0})):
            self.perform(send=True)
        self.assertEqual(self.active()['summaryRoute'], {'scope': 'no-model-call'})

    def test_existing_path_outside_weekly_run_root_is_rejected(self):
        self.baseline()
        with self.assertRaisesRegex(ValueError, 'UNSAFE_EXISTING_RUN'):
            self.perform(existing=self.root, send=True)

    def test_real_partial_baseline_commit_resumes_without_refetch_model_or_send(self):
        self.baseline()
        for url in self.urls:
            self.bodies[url] += '\nReleased a new open-weight reasoning model.'
        original = self.engine.atomic_json
        written = []

        def interrupted_commit(path, value):
            if path.parent == self.briefing_root / 'snapshots/ai-llm':
                written.append(path)
                if len(written) == 2:
                    raise OSError('synthetic second-source publication failure')
            return original(path, value)

        with patch.object(self.engine, 'atomic_json', side_effect=interrupted_commit):
            with self.assertRaises(OSError):
                self.perform(send=True)
        self.assertEqual(len(written), 2)
        self.assertEqual(self.active()['delivery']['status'], 'delivered')
        self.assertFalse(self.active()['baselineCommitted'])
        counts = (len(self.fetch_calls), len(self.summary_calls), len(self.send_calls))
        result = self.perform(send=True)
        self.assertTrue(result['alreadyCompleted'])
        self.assertTrue(self.active()['baselineCommitted'])
        self.assertEqual((len(self.fetch_calls), len(self.summary_calls), len(self.send_calls)), counts)
        for snapshot in self.snapshots().values():
            self.assertIn('Released a new open-weight', snapshot.decode())

    def test_prepared_outbox_cannot_move_to_a_different_configured_owner(self):
        self.baseline(); self.changed(); self.perform(send=False)
        config = self.engine.read_json(self.state / 'openclaw.json')
        config['commands']['ownerAllowFrom'] = ['telegram:54321']
        config['channels']['telegram']['allowFrom'] = ['54321']
        self.engine.atomic_json(self.state / 'openclaw.json', config)
        with self.assertRaises(ValueError):
            self.perform(send=True)
        self.assertFalse(self.send_calls)


if __name__ == '__main__':
    unittest.main()
