#!/usr/bin/env python3
"""Offline tests for bounded operational evidence and idempotent Hermes dispatch."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("ops", Path(__file__).with_name("hermes-operations.py"))
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


class OperationsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name).resolve()
        self.root = self.home / "worker"
        self.repo = self.home / "repo"
        self.state = self.home / "state"
        self.package = self.home / "package"
        for path in (self.root, self.repo / "scripts/openclaw", self.state, self.package):
            path.mkdir(parents=True)
        (self.repo / "scripts/openclaw/telegram-ops-status.py").write_text("COUNT = 1\n")
        self.args = argparse.Namespace(root=self.root, repo=self.repo, state_dir=self.state,
                                       package=self.package, mode="manual", force=False, request="진단",
                                       collect_only=False, offline=False)

    def evidence(self, *_):
        return {"operations": {"ok": True, "issues": [], "warnings": []}}

    def upstream(self):
        return [{"name": "openclaw", "ok": True, "tag": "v1", "body": "release"}]

    def worker(self, root, request, receipt, log):
        request = json.loads(request.read_text())
        return {"completed": True, "model": "gpt-5.6-sol", "usage": {"total_tokens": 1},
                "tool_calls": [{"name": "skill_view", "succeeded": True}],
                "result": {"schemaVersion": 1, "analysis": "이상 없음", "findings": [],
                           "patches": {"schemaVersion": 1, "snapshotSha256": request["snapshotSha256"],
                                       "replacements": []}, "runbook_candidate": ""}}

    def run_review(self, worker=None):
        return ops.run_review(self.args, worker_runner=worker or self.worker,
                              evidence_collector=self.evidence, upstream_collector=self.upstream)

    def test_baseline_binds_exact_evidence_and_upstream_bytes(self):
        first = self.run_review()
        directory = Path(first["receiptPath"]).parent
        operations = self.root / "operations"
        previous = json.loads((operations / "last-review.json").read_text())
        baseline, status = ops.review_baseline(operations, previous)
        self.assertEqual(status, "available")
        self.assertEqual(baseline["evidence"]["operations"]["ok"], True)
        for name, key in (("evidence.json", "evidenceSha256"), ("upstream.json", "upstreamSha256")):
            path = directory / name
            original = path.read_bytes()
            self.assertEqual(first[key], hashlib.sha256(original).hexdigest())
            # Even a parse-preserving edit is no longer the original reviewed artifact.
            path.write_bytes(original + b"\n")
            self.assertEqual(ops.review_baseline(operations, previous), (None, "unavailable"))
            path.write_bytes(original)
        self.assertEqual(ops.review_baseline(operations, previous)[1], "available")

    def test_legacy_baseline_without_artifact_hashes_requests_a_full_review(self):
        first = self.run_review()
        path = Path(first["receiptPath"])
        previous = json.loads(path.read_text())
        previous.pop("evidenceSha256")
        previous.pop("upstreamSha256")
        path.write_text(json.dumps(previous))
        received = []

        def capture(*args):
            received.append(json.loads(args[1].read_text()))
            return self.worker(*args)

        second = self.run_review(capture)
        self.assertEqual(second["status"], "reviewed")
        self.assertEqual(received[0]["changeContext"]["baselineStatus"], "unavailable")
        self.assertTrue(received[0]["changeContext"]["fullReview"])
        self.assertEqual(received[0]["upstream"][0]["body"], "release")
        self.assertNotIn("bodyOmittedUnchanged", received[0]["upstream"][0])

    def test_baseline_rejects_linked_artifacts(self):
        first = self.run_review()
        operations = self.root / "operations"
        previous = json.loads((operations / "last-review.json").read_text())
        artifact = Path(first["receiptPath"]).parent / "upstream.json"
        outside = self.home / "outside.json"
        outside.write_bytes(artifact.read_bytes())
        artifact.unlink()
        artifact.symlink_to(outside)
        self.assertEqual(ops.review_baseline(operations, previous), (None, "unavailable"))
        artifact.unlink()
        artifact.hardlink_to(outside)
        self.assertEqual(ops.review_baseline(operations, previous), (None, "unavailable"))

    def test_failed_dispatch_counts_attempts_separately_from_provider_responses(self):
        def fail(_root, _request, path, _log):
            path.write_text(json.dumps({"completed": False, "model": "gpt-5.6-sol", "response_models": [],
                "model_input_metrics": {"dispatch_attempts": 1, "failed_dispatches": 1, "completed_responses": 0}}))
            raise ValueError("HERMES_WORKER_FAILED:HERMES_RUNTIME_FAILED")

        result = self.run_review(fail)
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["modelInferenceRequests"], 1)
        self.assertEqual(result["modelResponses"], 0)
        self.assertEqual(result["model_input_metrics"]["failed_dispatches"], 1)

    def test_legacy_and_malformed_dispatch_counts_are_unknown(self):
        for metrics in (None, {}, {"dispatch_attempts": True}, {"dispatch_attempts": -1}, {"dispatch_attempts": "1"}):
            outer = {}
            ops.retain_worker_evidence(outer, {"response_models": ["gpt-5.6-sol"], "model_input_metrics": metrics},
                                       self.home / "worker-receipt.json")
            self.assertIsNone(outer["modelInferenceRequests"])
            self.assertEqual(outer["modelResponses"], 1)

    def test_config_and_logs_never_export_credentials_or_messages(self):
        config = {"agents": {"defaults": {"model": {"primary": "test", "fallbacks": []}}},
                  "channels": {"telegram": {"enabled": True, "botToken": "DO_NOT_EXPORT", "allowFrom": ["SECRET_ID"]}},
                  "gateway": {"mode": "local", "auth": {"token": "SECRET_GATEWAY"}}}
        p = self.state / "openclaw.json"
        p.write_text(json.dumps(config)); p.chmod(0o600)
        (self.state / "logs").mkdir()
        (self.state / "logs/gateway.err.log").write_text("private chat SECRET_TEXT subscription_limit\n")
        result = ops.collect_evidence(self.state, self.package, self.root,
                                      collector=lambda _: {"ok": True, "issues": []})
        text = json.dumps(result)
        for secret in ("DO_NOT_EXPORT", "SECRET_ID", "SECRET_GATEWAY", "SECRET_TEXT"):
            self.assertNotIn(secret, text)
        self.assertEqual(result["historicalLogTailCounts"]["files"]["gateway.err.log"]["subscription_limit"], 1)

    def configured_routing(self, agents):
        config = {"agents": agents, "gateway": {"mode": "local"},
                  "channels": {"telegram": {"enabled": True}}}
        path = self.state / "openclaw.json"
        path.write_text(json.dumps(config)); path.chmod(0o600)
        return ops.collect_evidence(self.state, self.package, self.root,
                                    collector=lambda _: {"ok": True, "issues": []})["configuration"]

    def test_main_fallback_is_observed_separately_from_global_defaults(self):
        result = self.configured_routing({
            "defaults": {"model": {"primary": "openai/gpt-6-astra", "fallbacks": []},
                         "thinkingDefault": "high"},
            "entries": {"main": {"model": {"primary": "openai/gpt-6-astra",
                                            "fallbacks": ["openrouter/z-ai/glm-5.3-flash"]}}}})
        self.assertEqual(result["fallbacks"], ["openrouter/z-ai/glm-5.3-flash"])
        self.assertEqual(result["model"], "openai/gpt-6-astra")
        self.assertEqual(result["thinking"], "high")
        self.assertEqual(result["defaults"], {"model": "openai/gpt-6-astra",
                                               "fallbacks": [], "thinking": "high"})
        self.assertEqual(result["scope"], "main-agent-configured-routing-not-session-or-runtime")
        self.assertEqual(result["thinkingScope"], "agent-or-global-thinking-default")

    def test_main_empty_fallback_override_disables_global_ladder(self):
        result = self.configured_routing({
            "defaults": {"model": {"primary": "base", "fallbacks": ["backup"]}},
            "entries": {"main": {"model": {"fallbacks": []}}}})
        self.assertEqual(result["model"], "base")
        self.assertEqual(result["fallbacks"], [])
        self.assertEqual(result["defaults"]["fallbacks"], ["backup"])

    def test_absent_main_model_inherits_global_configuration(self):
        for main in ({}, {"model": {}}, {"model": "  "}):
            with self.subTest(main=main):
                result = self.configured_routing({
                    "defaults": {"model": {"primary": "base", "fallbacks": ["backup"]},
                                 "thinkingDefault": "high"}, "entries": {"main": main}})
                self.assertEqual(result["model"], "base")
                self.assertEqual(result["fallbacks"], ["backup"])
                self.assertEqual(result["thinking"], "high")

    def test_explicit_main_primary_without_fallbacks_is_strict(self):
        for model in (" selected ", {"primary": " selected "}):
            with self.subTest(model=model):
                result = self.configured_routing({
                    "defaults": {"model": {"primary": "base", "fallbacks": ["backup"]}},
                    "entries": {"main": {"model": model}}})
                self.assertEqual(result["model"], "selected")
                self.assertEqual(result["fallbacks"], [])

    def test_main_thinking_override_and_null_inheritance(self):
        for value, expected in (("off", "off"), ("medium", "medium"), (None, "high")):
            with self.subTest(value=value):
                result = self.configured_routing({
                    "defaults": {"model": "base", "thinkingDefault": "high"},
                    "entries": {"main": {"thinkingDefault": value}}})
                self.assertEqual(result["thinking"], expected)
                self.assertEqual(result["defaults"]["thinking"], "high")

    def test_legacy_main_list_and_string_global_model_remain_supported(self):
        result = self.configured_routing({
            "defaults": {"model": "base"},
            "list": [{"id": "main", "model": {"primary": "selected", "fallbacks": ["backup"]}}]})
        self.assertEqual(result["model"], "selected")
        self.assertEqual(result["fallbacks"], ["backup"])
        self.assertEqual(result["defaults"]["model"], "base")

    def test_entries_take_precedence_over_legacy_list(self):
        result = self.configured_routing({
            "defaults": {"model": "base"}, "entries": {"main": {}},
            "list": [{"id": "main", "model": "stale"}]})
        self.assertEqual(result["model"], "base")

    def test_main_configuration_exports_no_unselected_or_secret_fields(self):
        result = self.configured_routing({
            "defaults": {"model": "base", "params": {"apiKey": "DEFAULT_SECRET"}},
            "entries": {"main": {"model": {"primary": "selected", "fallbacks": ["backup"],
                                             "apiKey": "MODEL_SECRET"},
                                  "params": {"token": "MAIN_SECRET"}, "workspace": "PRIVATE_PATH"},
                        "other": {"model": "PRIVATE_OTHER_MODEL"}}})
        encoded = json.dumps(result)
        for secret in ("DEFAULT_SECRET", "MODEL_SECRET", "MAIN_SECRET", "PRIVATE_PATH", "PRIVATE_OTHER_MODEL"):
            self.assertNotIn(secret, encoded)
        self.assertEqual(result["model"], "selected")

    def test_public_release_whitelist_and_truncation(self):
        def fetch(url, **kwargs):
            self.assertEqual(kwargs["max_bytes"], 524288)
            return {"body": json.dumps({"tag_name": "v1", "body": "가" * 33000,
                                         "author": {"login": "not-needed"}}), "fetchedAt": "now"}
        rows = ops.collect_upstream(fetch)
        self.assertEqual(len(rows), 2)
        self.assertTrue(rows[0]["truncated"])
        self.assertLessEqual(len(rows[0]["body"].encode("utf-8")), 24000)
        self.assertLess(len(json.dumps(rows, ensure_ascii=False).encode("utf-8")), 65536)
        self.assertNotIn("author", rows[0])

    def test_installed_launchagent_log_is_counted_without_exporting_text(self):
        log = self.home / "gateway-personaledge.log"
        log.write_text("SECRET_CHAT subscription_limit\n")
        with patch.object(ops, "DEFAULT_STATE", self.state), patch.object(ops, "DEFAULT_GATEWAY_LOG", log):
            result = ops.collect_evidence(self.state, self.package, self.root, collector=self.evidence)
        self.assertEqual(result["historicalLogTailCounts"]["files"][log.name]["subscription_limit"], 1)
        self.assertNotIn("SECRET_CHAT", json.dumps(result))

    def test_public_release_failure_remains_failure(self):
        def fetch(*a, **kw):
            raise OSError("private environment contents")
        rows = ops.collect_upstream(fetch)
        self.assertTrue(all(r["ok"] is False for r in rows))
        self.assertNotIn("private environment", json.dumps(rows))

    def test_symlink_root_refused(self):
        redirect = self.home / "redirect"
        redirect.symlink_to(self.root, target_is_directory=True)
        self.args.root = redirect
        with self.assertRaises(ValueError):
            self.run_review()

    def test_collect_only_never_calls_model(self):
        self.args.collect_only = True
        result = self.run_review(lambda *a: self.fail("model called"))
        self.assertTrue(result["ok"])
        self.assertEqual(result["modelInferenceRequests"], 0)

    def test_healthy_incident_does_not_call_model(self):
        self.args.mode = "incident"
        result = self.run_review(lambda *a: self.fail("model called"))
        self.assertEqual(result["status"], "healthy-no-incident")

    def test_weekly_run_is_not_dispatched_twice(self):
        self.args.mode = "weekly"
        first = self.run_review()
        self.assertEqual(first["status"], "reviewed")
        second = self.run_review(lambda *a: self.fail("duplicate model dispatch"))
        self.assertEqual(second["status"], "already-attempted")
        self.assertEqual(second["previous"]["runId"], first["runId"])

    def test_failed_weekly_attempt_is_not_silently_retried(self):
        self.args.mode = "weekly"
        def fail(*a):
            raise ValueError("HERMES_WORKER_FAILED:LIMIT")
        first = self.run_review(fail)
        self.assertFalse(first["ok"])
        second = self.run_review(lambda *a: self.fail("retry"))
        self.assertEqual(second["status"], "already-attempted")
        self.assertFalse(second["ok"])

    def test_manual_success_retains_evidence_and_no_delivery_claim(self):
        result = self.run_review()
        self.assertTrue(result["ok"])
        self.assertFalse(result["applied"])
        self.assertFalse(result["telegramDelivered"])
        self.assertTrue(result["reusedSkill"])
        self.assertTrue(Path(result["reportPath"]).is_file())
        self.assertFalse(result["attentionRequired"])

    def test_upstream_failure_is_attention_even_when_model_finds_nothing(self):
        result = ops.run_review(self.args, worker_runner=self.worker, evidence_collector=self.evidence,
                                upstream_collector=lambda: [{"name": "openclaw", "ok": False}])
        self.assertTrue(result["attentionRequired"])
        self.assertFalse(result["upstreamComplete"])

    def test_observed_failure_cannot_be_hidden_by_empty_model_findings(self):
        cases = [
            {"operations": {"ok": False, "issues": ["observer-gateway-unhealthy"]}},
            {"operations": {"ok": True, "warnings": ["backup-unavailable"]}},
            {"operations": {"ok": True}, "configuration": {"available": False}},
        ]
        for evidence in cases:
            with self.subTest(evidence=evidence):
                result = ops.run_review(self.args, worker_runner=self.worker,
                                       evidence_collector=lambda *args: evidence,
                                       upstream_collector=self.upstream)
                self.assertTrue(result["attentionRequired"])

    def test_candidate_failure_retains_completed_model_usage(self):
        def invalid_candidate(*args):
            worker = self.worker(*args)
            worker["result"]["patches"]["snapshotSha256"] = "0" * 64
            return worker
        result = self.run_review(invalid_candidate)
        self.assertFalse(result["ok"])
        self.assertEqual(result["usage"], {"total_tokens": 1})
        self.assertEqual(result["model"], "gpt-5.6-sol")
        self.assertIn("workerReceiptPath", result)

    def test_failed_worker_preserves_available_usage_from_its_receipt(self):
        def fail(root, request, path, log):
            path.write_text(json.dumps({"completed": False, "model": "gpt-5.6-sol",
                                       "usage": {"total_tokens": 123}, "response_models": ["gpt-5.6-sol"]}))
            raise ValueError("HERMES_WORKER_FAILED:RESULT_INVALID")
        result = self.run_review(fail)
        self.assertFalse(result["ok"])
        self.assertEqual(result["usage"]["total_tokens"], 123)
        self.assertIsNone(result["modelInferenceRequests"])
        self.assertEqual(result["modelResponses"], 1)

    def test_worker_budgets_are_preserved_in_outer_receipt(self):
        def measured(*args):
            result = self.worker(*args)
            result.update(read_metrics={"delivered_bytes": 100, "reread_bytes": 10},
                          model_input_metrics={"estimated_input_tokens": 300, "billed_tokens": False})
            return result
        result = self.run_review(measured)
        self.assertEqual(result["read_metrics"]["delivered_bytes"], 100)
        self.assertFalse(result["model_input_metrics"]["billed_tokens"])

    def test_review_supplies_delta_and_learning_contract_even_on_first_run(self):
        def inspecting(root, request, receipt, log):
            payload = json.loads(request.read_text())
            self.assertEqual(payload["changeContext"]["baselineStatus"], "none")
            self.assertTrue(payload["changeContext"]["fullReview"])
            self.assertEqual(payload["learningContext"], {"schemaVersion": 1, "procedures": []})
            self.assertIn("findingLifecycle", payload["evidence"])
            return self.worker(root, request, receipt, log)
        self.assertTrue(self.run_review(inspecting)["ok"])


class ReviewMemoryTests(unittest.TestCase):
    """Deduplication, the review ledger, applied-change feedback, and upgrade coverage."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name).resolve()
        self.root = self.home / "worker"
        self.repo = self.home / "repo"
        self.state = self.home / "state"
        self.package = self.home / "package"
        for path in (self.root, self.repo / "scripts/openclaw", self.state, self.package):
            path.mkdir(parents=True)
        (self.repo / "scripts/openclaw/telegram-ops-status.py").write_text("COUNT = 1\n")
        self.calls = []
        self.groups = [{"status": "failed", "errorClass": "command-failed", "exitCode": 1,
                        "taskKind": "exec", "runtime": "cli", "lastToolName": None, "count": 4}]
        self.findings = [{"id": "rg-missing", "severity": "medium", "title": "매우 긴 한국어 서술 PROSE_MARKER"}]
        self.args = argparse.Namespace(root=self.root, repo=self.repo, state_dir=self.state,
                                       package=self.package, mode="incident", force=False,
                                       request="진단", collect_only=False, offline=True)

    def evidence(self, *_):
        return {"operations": {"ok": False, "issues": ["observer-gateway-unhealthy"], "warnings": [],
                               "taskFailureGroups": {"groups": list(self.groups)},
                               "execCapabilities": {"missing": []}, "hermes": {"invocable": True}}}

    def upstream(self):
        return []

    def worker(self, root, request, receipt, log):
        payload = json.loads(request.read_text())
        self.calls.append(payload)
        return {"completed": True, "model": "gpt-5.6-sol", "usage": {"total_tokens": 1},
                "tool_calls": [], "result": {"schemaVersion": 1, "analysis": "분석",
                                             "findings": list(self.findings),
                                             "patches": {"schemaVersion": 1, "replacements": [],
                                                         "snapshotSha256": payload["snapshotSha256"]}}}

    def review(self, **overrides):
        args = argparse.Namespace(**{**vars(self.args), **overrides})
        return ops.run_review(args, worker_runner=self.worker, evidence_collector=self.evidence,
                              upstream_collector=self.upstream)

    def test_identical_evidence_skips_the_model_on_the_next_review(self):
        self.assertEqual(self.review()["status"], "reviewed")
        second = self.review()
        self.assertEqual(second["status"], "unchanged")
        self.assertEqual(second["modelInferenceRequests"], 0)
        self.assertFalse(second["attentionRequired"])
        self.assertEqual(len(self.calls), 1)

    def test_force_overrides_deduplication(self):
        self.review()
        self.assertEqual(self.review(force=True)["status"], "reviewed")
        self.assertEqual(len(self.calls), 2)

    def test_a_new_failure_shape_defeats_deduplication(self):
        self.review()
        self.groups.append({"status": "failed", "errorClass": "auth-or-subscription", "exitCode": None,
                            "taskKind": "automation_run", "runtime": "cron", "lastToolName": None,
                            "count": 1})
        self.assertEqual(self.review()["status"], "reviewed")
        self.assertEqual(len(self.calls), 2)

    def test_repetition_of_the_same_fault_does_not_defeat_deduplication(self):
        self.review()
        self.groups[0]["count"] = 4000
        self.assertEqual(self.review()["status"], "unchanged")
        self.assertEqual(len(self.calls), 1)

    def test_tampered_baseline_forces_full_review_instead_of_hiding_unknown_changes(self):
        first = self.review()
        source = Path(first["receiptPath"]).parent / "snapshot/scripts/openclaw/telegram-ops-status.py"
        source.write_text("COUNT = 999\n")
        second = self.review()
        self.assertEqual(second["status"], "reviewed")
        self.assertEqual(self.calls[-1]["changeContext"]["baselineStatus"], "unavailable")
        self.assertTrue(self.calls[-1]["changeContext"]["fullReview"])

    def test_changed_source_has_a_hash_bound_delta(self):
        self.review()
        (self.repo / "scripts/openclaw/telegram-ops-status.py").write_text("COUNT = 2\n")
        self.review()
        delta = self.calls[-1]["changeContext"]
        self.assertEqual(delta["baselineStatus"], "available")
        self.assertEqual(delta["changedSourcePaths"], ["scripts/openclaw/telegram-ops-status.py"])
        self.assertIn("-COUNT = 1", delta["sourceDiffs"][0]["patch"])
        self.assertIn("+COUNT = 2", delta["sourceDiffs"][0]["patch"])

    def test_explicit_lifecycle_update_breaks_dedup_but_review_observation_does_not(self):
        self.findings[0]["kind"] = "issue"
        self.review()
        self.assertEqual(self.review()["status"], "unchanged")
        ops.module("hermes-ops-knowledge").update_finding(
            self.root / "operations", "rg-missing", owner="codex", state="in_progress")
        self.assertEqual(self.review()["status"], "reviewed")
        lifecycle = self.calls[-1]["evidence"]["findingLifecycle"]
        self.assertEqual(lifecycle[0]["state"], "in_progress")
        self.assertEqual(self.review()["status"], "unchanged")

    def test_normal_observation_is_not_an_open_issue(self):
        self.findings = [{"id": "healthy", "kind": "observation", "severity": "info"}]
        self.review()
        lifecycle = ops.module("hermes-ops-knowledge").summary(self.root / "operations")["findingLifecycle"]
        self.assertEqual(lifecycle[0]["classification"], "observation")
        self.assertEqual(lifecycle[0]["state"], "observed")

    def test_unchanged_release_body_is_not_resent_in_incremental_manual_review(self):
        self.upstream = lambda: [{"name": "openclaw", "ok": True, "tag": "v1", "body": "same-release"}]
        self.review(offline=False, mode="manual")
        self.review(offline=False, mode="manual")
        self.assertEqual(self.calls[0]["upstream"][0]["body"], "same-release")
        self.assertEqual(self.calls[-1]["upstream"][0]["body"], "")
        self.assertTrue(self.calls[-1]["upstream"][0]["bodyOmittedUnchanged"])
        self.assertEqual(self.calls[-1]["changeContext"]["baselineStatus"], "available")

    def test_proof_integrity_change_breaks_deduplication(self):
        knowledge = ops.module("hermes-ops-knowledge")
        original = knowledge.summary
        validity = [True]
        def summary(*args):
            result = original(*args)
            result["findingLifecycle"] = [{"id": "closed", "closureEvidenceValid": validity[0]}]
            return result
        loader = ops.module
        with patch.object(knowledge, "summary", summary), patch.object(
                ops, "module", lambda name: knowledge if name == "hermes-ops-knowledge" else loader(name)):
            self.review()
            self.assertEqual(self.review()["status"], "unchanged")
            validity[0] = False
            self.assertEqual(self.review()["status"], "reviewed")

    def test_large_lifecycle_is_bounded_without_losing_full_evidence(self):
        evidence = {"operations": {"ok": True}, "procedureKnowledge": {"operatorRevision": 1},
                    "findingLifecycle": [{"id": "issue-" + str(i), "deferReason": "가" * 400}
                                         for i in range(64)]}
        upstream = [{"name": name, "ok": True, "body": "a" * 24000}
                    for name in ("openclaw", "hermes")]
        result = ops.bounded_model_evidence(evidence, upstream)
        self.assertLessEqual(len(ops.module("hermes-ops-worker").encoded(
            {"evidence": result, "upstream": upstream})), 65536)
        self.assertEqual(len(evidence["findingLifecycle"]), 64)
        view = result["procedureKnowledge"]["modelView"]
        self.assertTrue(view["truncated"])
        self.assertEqual(view["omittedCounts"]["findingLifecycle"], 64 - len(result["findingLifecycle"]))

    def test_a_failed_review_never_becomes_the_deduplication_baseline(self):
        def broken(*_args):
            raise ValueError("HERMES_WORKER_FAILED:BOOM")

        self.assertEqual(ops.run_review(self.args, worker_runner=broken,
                                        evidence_collector=self.evidence,
                                        upstream_collector=self.upstream)["status"], "failed")
        self.assertEqual(self.review()["status"], "reviewed")

    def test_ledger_records_identifiers_and_outcomes_but_not_model_prose(self):
        self.review()
        text = (self.root / "operations/review-ledger.jsonl").read_text()
        self.assertNotIn("PROSE_MARKER", text)
        row = json.loads(text.splitlines()[-1])
        self.assertEqual(row["findingIds"], ["rg-missing"])
        self.assertEqual(row["severityCounts"], {"medium": 1})
        self.assertEqual(row["status"], "reviewed")

    def test_an_unsafe_finding_identifier_is_dropped_rather_than_carried_forward(self):
        self.findings = [{"id": "'; DROP TABLE --", "severity": "high", "title": "x"},
                         {"id": "ok-id", "severity": "low", "title": "y"}]
        self.review()
        row = json.loads((self.root / "operations/review-ledger.jsonl").read_text().splitlines()[-1])
        self.assertEqual(row["findingIds"], ["ok-id"])

    def test_a_recurring_finding_is_visible_as_a_repeat_count(self):
        self.review()
        # The situation must actually differ, or the second review is deduplicated and reports
        # nothing; a repeat count is only meaningful across two real reviews.
        self.groups.append({"status": "failed", "errorClass": "timeout", "exitCode": None,
                            "taskKind": "exec", "runtime": "cli", "lastToolName": None, "count": 1})
        self.review()
        history = ops.review_history(self.root / "operations")
        self.assertEqual(history["findingRepeatCounts"]["rg-missing"], 2)
        self.assertEqual(len(history["reviews"]), 2)

    def test_evidence_carries_prior_reviews_and_applied_changes(self):
        self.review()
        evidence = ops.collect_evidence(self.state, self.package, self.root,
                                        collector=lambda _s, **_k: {"ok": True, "issues": []})
        self.assertEqual(len(evidence["reviewHistory"]["reviews"]), 1)
        self.assertEqual(evidence["appliedChanges"]["count"], 0)

    def test_recording_an_applied_change_clears_the_baseline_so_the_next_review_looks_again(self):
        self.review()
        self.assertEqual(self.review()["status"], "unchanged")
        recorded = ops.record_applied(argparse.Namespace(
            root=self.root, run_id="ops-20260910T000000Z-abcd1234", paths="scripts/openclaw/x.py",
            note="설치 완료", verified_by="codex"))
        self.assertTrue(recorded["ok"])
        self.assertFalse((self.root / "operations/last-review.json").exists())
        self.assertEqual(self.review()["status"], "reviewed")
        evidence = ops.collect_evidence(self.state, self.package, self.root,
                                        collector=lambda _s, **_k: {"ok": True, "issues": []})
        self.assertEqual(evidence["appliedChanges"]["entries"][0]["verifiedBy"], "codex")

    def test_applied_paths_are_validated(self):
        for bad in ("../etc/passwd", "a; rm -rf /", "x" * 300):
            with self.assertRaises(ValueError):
                ops.record_applied(argparse.Namespace(root=self.root, run_id=None, paths=bad,
                                                      note="", verified_by=""))

    def test_patch_coverage_flags_an_upgrade_its_specs_do_not_cover(self):
        (self.repo / "scripts/openclaw/runtime-patch-specs.json").write_text(
            json.dumps({"2026.9.2": {}, "2026.9.3": {}}))
        evidence = {"openclaw": {"version": "2026.9.3"}}
        result = ops.patch_coverage(self.repo, evidence, [{"name": "openclaw", "ok": True, "tag": "v2026.9.4"}])
        self.assertTrue(result["installedCovered"])
        self.assertFalse(result["latestCovered"])
        self.assertTrue(result["upgradeAvailable"])

    def test_patch_coverage_is_unavailable_rather_than_wrong_without_specs(self):
        result = ops.patch_coverage(self.repo, {"openclaw": {"version": "2026.9.3"}}, [])
        self.assertFalse(result["available"])

    def test_an_older_collector_omits_failure_detail_instead_of_losing_all_evidence(self):
        evidence = ops.collect_evidence(self.state, self.package, self.root,
                                        collector=lambda _s: {"ok": True, "issues": []})
        self.assertFalse(evidence["failureDetailAvailable"])
        self.assertEqual(evidence["operations"], {"ok": True, "issues": []})


if __name__ == "__main__":
    unittest.main()
