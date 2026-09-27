#!/usr/bin/env python3
"""Offline operations worker boundaries; synthetic auth, bundle, and runtime only."""

import contextlib
from concurrent.futures import ThreadPoolExecutor
import copy
import fcntl
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("hermes_ops_worker", Path(__file__).with_name("hermes-ops-worker.py"))
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


def message(name, args):
    return SimpleNamespace(tool_calls=[SimpleNamespace(function=SimpleNamespace(name=name, arguments=json.dumps(args)))])


class OpsWorkerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="ops-worker-test-", dir="/private/tmp")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.profile = self.root / "profile"
        self.home = worker.profile_home(self.profile)
        for directory in (self.profile, self.profile / "codex-disabled-import", self.home,
                          self.home / "codex-disabled-import", self.home / "skills" / worker.SKILL_NAME):
            directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.auth = {"credential_pool": {worker.PROVIDER: [{"id": "synthetic-row", "auth_type": "oauth",
            "source": "manual:device_code", "access_token": "synthetic-access", "refresh_token": "synthetic-refresh",
            "base_url": worker.ENDPOINT}]}}
        self.write_json(self.profile / "auth.json", self.auth)
        self.write_json(self.home / "config.yaml", worker.REQUIRED_CONFIG)
        (self.home / ".no-bundled-skills").write_text("synthetic profile\n")
        self.skill = self.home / "skills" / worker.SKILL_NAME / "SKILL.md"
        self.skill.write_text("---\nname: openclaw-ops-runbook\ndescription: Operations diagnosis\n---\nReview evidence first.\n")
        self.skill.chmod(0o600)
        self.env = {"HERMES_HOME": str(self.profile), "CODEX_HOME": str(self.profile / "codex-disabled-import"),
                    "HERMES_SAFE_MODE": "1", "HERMES_DISABLE_LAZY_INSTALLS": "1", "PATH": "/usr/bin:/bin"}
        self.source = "scripts/openclaw/telegram-ops-status.py"
        content = "# private-source-marker\nvalue = 1\n"
        self.request = {"schemaVersion": 1, "mode": "incident", "request": "가려진 상태를 진단해주세요.",
            "evidence": {"status": {"active": 0, "issues": []}}, "upstream": [], "snapshotSha256": "a" * 64,
            "sources": {self.source: {"sha256": hashlib.sha256(content.encode()).hexdigest(), "content": content}}}
        self.candidate = {"schemaVersion": 1, "snapshotSha256": "a" * 64, "replacements": [{"path": self.source,
            "beforeSha256": self.request["sources"][self.source]["sha256"], "content": "value = 2\n"}]}
        self.result = {"schemaVersion": 1, "analysis": "실제 실행 중인 작업은 없습니다.",
            "findings": [{"id": "finding-1", "severity": "info", "title": "상태 확인", "evidence": ["status"],
                          "recommendation": "기존 감시를 유지하세요."}],
            "patches": {"schemaVersion": 1, "snapshotSha256": "a" * 64, "replacements": []}, "runbook_candidate": ""}
        self.request_file = self.root / "request.json"
        self.receipt_file = self.root / "receipt.json"
        self.write_json(self.request_file, self.request)

    def write_json(self, path, value):
        path.write_text(json.dumps(value, ensure_ascii=False))
        path.chmod(0o600)

    def add_contexts(self):
        content = "원인: 전역 설정과 실제 main 설정이 다를 수 있음. 실제 main 라우팅을 비교한다."
        self.request["learningContext"] = {"schemaVersion": 1, "procedures": [{"id": "routing-main", "version": 2,
            "sha256": hashlib.sha256(content.encode()).hexdigest(), "title": "실제 main 설정 확인",
            "procedure": content, "evidenceIds": ["status"]}]}
        self.request["changeContext"] = {"schemaVersion": 1, "baselineRunId": "synthetic-prior",
            "baselineSnapshotSha256": "b" * 64, "baselineStatus": "available", "changedEvidenceIds": ["status"],
            "changedSourcePaths": [self.source], "removedSourcePaths": [], "sourceDiffs": [{"path": self.source,
                "beforeSha256": "b" * 64, "afterSha256": self.request["sources"][self.source]["sha256"],
                "patch": "@@ synthetic change @@", "truncated": False}],
            "upstreamChanges": [{"name": "hermes", "beforeTag": "v1", "afterTag": "v2", "bodyChanged": True,
                                 "patch": "synthetic release delta", "truncated": False}],
            "fullReview": False, "truncated": False}

    def main(self, run_worker=None):
        output = io.StringIO()
        argv = ["worker", "--profile-dir", str(self.profile), "--request-file", str(self.request_file),
                "--receipt-file", str(self.receipt_file)]
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(worker.sys, "argv", argv))
            stack.enter_context(patch.object(worker.sys, "stdout", output))
            stack.enter_context(patch.dict(os.environ, self.env, clear=True))
            stack.enter_context(patch.object(worker.base, "quiet_runtime", contextlib.nullcontext))
            if run_worker is not None:
                stack.enter_context(patch.object(worker, "run_worker", side_effect=run_worker))
            result = worker.main()
        return result, output.getvalue()

    def test_every_accepted_finding_id_can_enter_the_knowledge_ledger(self):
        knowledge_spec = importlib.util.spec_from_file_location(
            "worker_knowledge_contract", Path(__file__).with_name("hermes-ops-knowledge.py"))
        knowledge = importlib.util.module_from_spec(knowledge_spec)
        knowledge_spec.loader.exec_module(knowledge)
        for identifier in ("a", "A-1_ok", "a" * 80):
            self.result["findings"][0]["id"] = identifier
            parsed = worker.validate_result(json.dumps(self.result), self.request)
            self.assertEqual(knowledge.identifier(parsed["findings"][0]["id"]), identifier)
        for identifier in ("_leading", "-leading", "a" * 81):
            self.result["findings"][0]["id"] = identifier
            with self.assertRaisesRegex(worker.PolicyError, "FINDING_ID_INVALID"):
                worker.validate_result(json.dumps(self.result), self.request)

    def test_nested_profile_borrows_auth_without_copying_parent_state(self):
        before = (self.profile / "auth.json").read_bytes()
        self.assertEqual(worker.validate_profile(self.profile, self.env), self.home)
        self.assertFalse((self.home / "auth.json").exists())
        self.assertEqual((self.profile / "auth.json").read_bytes(), before)
        with patch.dict(os.environ, self.env, clear=True):
            with worker.profile_environment(self.home):
                worker.base.validate_environment(dict(os.environ), self.home)
            self.assertEqual(dict(os.environ), self.env)

    def test_profile_auth_copy_links_and_missing_seed_are_rejected(self):
        self.write_json(self.home / "auth.json", self.auth)
        with self.assertRaisesRegex(worker.PolicyError, "OPS_AUTH_COPY_REFUSED"):
            worker.validate_profile(self.profile, self.env)
        (self.home / "auth.json").unlink()
        (self.home / "outside").symlink_to(self.root)
        with self.assertRaisesRegex(worker.PolicyError, "SYMLINK_REFUSED"):
            worker.validate_profile(self.profile, self.env)
        (self.home / "outside").unlink()
        self.skill.unlink()
        with self.assertRaisesRegex(worker.PolicyError, "OPS_SKILL_REQUIRED"):
            worker.validate_profile(self.profile, self.env)

    def test_shared_singleton_cannot_fork_refresh_grant(self):
        auth = copy.deepcopy(self.auth)
        auth["providers"] = {worker.PROVIDER: {"auth_mode": "chatgpt", "tokens": {
            "access_token": "synthetic-access", "refresh_token": "synthetic-refresh"}}}
        self.write_json(self.profile / "auth.json", auth)
        with self.assertRaisesRegex(worker.PolicyError, "SHARED_OAUTH_SINGLETON_REFUSED"):
            worker.validate_profile(self.profile, self.env)

    def test_keys_proxies_cli_auth_and_custom_hooks_are_refused(self):
        for key in ("OPENAI_API_KEY", "HTTPS_PROXY", "HERMES_CODEX_BASE_URL", "TELEGRAM_BOT_TOKEN"):
            with self.subTest(key=key), self.assertRaises(worker.PolicyError):
                worker.validate_profile(self.profile, {**self.env, key: "synthetic"})
        self.write_json(self.home / "codex-disabled-import/auth.json", self.auth)
        with self.assertRaisesRegex(worker.PolicyError, "CODEX_IMPORT_DIRECTORY_NOT_EMPTY"):
            worker.validate_profile(self.profile, self.env)
        (self.home / "codex-disabled-import/auth.json").unlink()
        (self.home / "hooks").mkdir()
        (self.home / "hooks/code.py").write_text("pass")
        with self.assertRaisesRegex(worker.PolicyError, "PROFILE_HOOKS_REFUSED"):
            worker.validate_profile(self.profile, self.env)

    def test_config_cannot_enable_automatic_learning_fallback_or_more_tools(self):
        worker.validate_config(copy.deepcopy(worker.REQUIRED_CONFIG))
        for key, value in (("skills", {**worker.REQUIRED_CONFIG["skills"], "write_approval": False}),
                           ("toolsets", ["skills", "terminal"]), ("fallback_providers", [{"provider": "openrouter"}])):
            with self.subTest(key=key), self.assertRaisesRegex(worker.PolicyError, "PROFILE_CONFIG_MISMATCH"):
                worker.validate_config({**worker.REQUIRED_CONFIG, key: value})

    def test_request_hash_paths_size_and_unknown_fields_are_validated(self):
        worker.validate_request(self.request)
        for mutation in ("hash", "path", "extra", "size", "nan"):
            request = copy.deepcopy(self.request)
            if mutation == "hash":
                request["sources"][self.source]["sha256"] = "b" * 64
            elif mutation == "path":
                request["sources"]["../private"] = request["sources"].pop(self.source)
            elif mutation == "extra":
                request["shell"] = "true"
            elif mutation == "size":
                request["evidence"]["large"] = "x" * 65537
            else:
                request["evidence"]["nan"] = float("nan")
            with self.subTest(mutation=mutation), self.assertRaises(worker.PolicyError):
                worker.validate_request(request)

    def test_prompt_only_contains_source_index_not_source_contents(self):
        prompt = worker.build_prompt(self.request)
        self.assertIn(self.source, prompt)
        self.assertNotIn("private-source-marker", prompt)
        self.assertIn("ops_read_source", prompt)
        data = json.loads(prompt.split("BUNDLE_INDEX_AND_EVIDENCE:\n", 1)[1])
        self.assertEqual(data["allowed_evidence_ids"], sorted([self.source, "status"]))
        self.assertIn("MUST exactly match", prompt)

    def test_change_and_learning_context_are_optional_bounded_and_hash_verified(self):
        worker.validate_request(self.request)
        self.add_contexts()
        worker.validate_request(self.request)
        prompt = worker.build_prompt(self.request)
        self.assertIn("changed evidence, source diffs", prompt)
        self.assertIn("not verified successful reuse", prompt)
        for mutation in ("unknown", "path", "evidence", "hash", "boolean_version", "unsafe_id", "large_diff", "instruction_key"):
            request = copy.deepcopy(self.request)
            if mutation == "unknown":
                request["changeContext"]["applyChanges"] = True
            elif mutation == "path":
                request["changeContext"]["removedSourcePaths"] = ["../auth.json"]
            elif mutation == "evidence":
                request["changeContext"]["changedEvidenceIds"] = ["invented"]
            elif mutation == "hash":
                request["learningContext"]["procedures"][0]["sha256"] = "b" * 64
            elif mutation == "boolean_version":
                request["learningContext"]["procedures"][0]["version"] = True
            elif mutation == "unsafe_id":
                request["learningContext"]["procedures"][0]["id"] = "../../memory"
            elif mutation == "large_diff":
                request["changeContext"]["sourceDiffs"][0]["patch"] = "x" * 32768
            else:
                request["learningContext"]["procedures"][0]["shell"] = "do something"
            with self.subTest(mutation=mutation), self.assertRaises(worker.PolicyError):
                worker.validate_request(request)

    def test_procedure_uses_are_references_with_exact_version_hash_and_known_evidence(self):
        self.add_contexts()
        procedure = self.request["learningContext"]["procedures"][0]
        self.result["findings"][0]["kind"] = "observation"
        self.result["procedure_uses"] = [{"procedure_id": procedure["id"], "version": procedure["version"],
            "sha256": procedure["sha256"], "conclusion": "reuse_claimed", "evidence": ["status"]}]
        parsed = worker.validate_result(json.dumps(self.result), self.request)
        self.assertEqual(parsed, self.result)
        self.assertNotIn("verified", parsed["procedure_uses"][0])
        for key, value in (("procedure_id", "invented"), ("version", 1), ("version", True), ("sha256", "b" * 64),
                           ("conclusion", "verified_success"), ("evidence", ["invented"]), ("executed", True)):
            result = copy.deepcopy(self.result)
            result["procedure_uses"][0][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(worker.PolicyError):
                worker.validate_result(json.dumps(result), self.request)
        for kind in ("open", [], True):
            result = copy.deepcopy(self.result)
            result["findings"][0]["kind"] = kind
            with self.subTest(kind=kind), self.assertRaises(worker.PolicyError):
                worker.validate_result(json.dumps(result), self.request)
        legacy = copy.deepcopy(self.result)
        legacy.pop("procedure_uses")
        legacy["findings"][0].pop("kind")
        self.assertNotIn("kind", worker.validate_result(json.dumps(legacy), self.request)["findings"][0])

    def test_operator_knowledge_ids_keep_exact_metadata_scope_without_relaxing_run_ids(self):
        self.add_contexts()
        procedure = self.request["learningContext"]["procedures"][0]
        for identifier in ("routing.main:v2_fix", "p" * 96):
            procedure["id"] = identifier
            worker.validate_request(self.request)
            self.result["procedure_uses"] = [{"procedure_id": identifier, "version": procedure["version"],
                "sha256": procedure["sha256"], "conclusion": "referenced", "evidence": ["status"]}]
            worker.validate_result(json.dumps(self.result), self.request)
        for identifier in ("../outside", ".hidden", "slash/path", "p" * 97):
            procedure["id"] = identifier
            with self.subTest(identifier=identifier), self.assertRaisesRegex(worker.PolicyError, "PROCEDURE_ID_INVALID"):
                worker.validate_request(self.request)
        procedure["id"] = "routing-main"
        self.request["changeContext"]["baselineRunId"] = "routing.main"
        with self.assertRaisesRegex(worker.PolicyError, "CHANGE_CONTEXT_INVALID"):
            worker.validate_request(self.request)

    def test_read_receipt_counts_partial_overlap_and_identical_rereads_without_source_content(self):
        content = "첫째\nsecond\nthird\nfourth\n"
        self.request["sources"][self.source] = {"sha256": hashlib.sha256(content.encode()).hexdigest(), "content": content}
        observation = {}
        tools = worker.BundleTools(self.request, observation)
        tools.read_source({"path": self.source, "start_line": 1, "line_count": 2})
        tools.read_source({"path": self.source, "start_line": 2, "line_count": 2})
        tools.read_source({"path": self.source, "start_line": 2, "line_count": 2})
        metrics = observation["read_metrics"]
        self.assertEqual(metrics["unique_bytes"], len("첫째\nsecond\nthird\n".encode()))
        self.assertEqual(metrics["reread_bytes"], len("second\nsecond\nthird\n".encode()))
        self.assertEqual(metrics["delivered_bytes"], metrics["unique_bytes"] + metrics["reread_bytes"])
        self.assertEqual(metrics["sources"][self.source]["read_ranges"], [[1, 3]])
        self.assertEqual(metrics["sources"][self.source]["unique_lines"], 3)
        self.assertEqual(metrics["sources"][self.source]["sha256"], self.request["sources"][self.source]["sha256"])
        self.assertNotIn("second", json.dumps(metrics))

    def test_read_budget_rejection_is_recoverable_atomic_and_never_delivers_partial_data(self):
        args = {"path": self.source}
        size = len(self.request["sources"][self.source]["content"].encode())
        tools = worker.BundleTools(self.request)
        with patch.object(worker, "MAX_READ_BYTES", size):
            with ThreadPoolExecutor(max_workers=2) as pool:
                replies = list(pool.map(lambda _i: json.loads(tools.dispatch("ops_read_source", args)), range(2)))
        self.assertEqual(sum(reply["success"] for reply in replies), 1)
        rejected = next(reply for reply in replies if not reply["success"])
        self.assertEqual(rejected["error_code"], "SOURCE_READ_BUDGET_EXHAUSTED")
        self.assertTrue(rejected["recoverable"])
        self.assertNotIn("private-source-marker", json.dumps(rejected))
        self.assertEqual(tools.read_metrics["delivered_bytes"], size)
        with patch.object(worker, "MAX_REREAD_BYTES", size - 1):
            rejected = json.loads(tools.dispatch("ops_read_source", args))
        self.assertEqual(rejected["error_code"], "SOURCE_REREAD_BUDGET_EXHAUSTED")
        self.assertEqual(tools.read_metrics["reread_bytes"], 0)
        self.assertEqual(tools.read_metrics["budget_rejections"], 2)

    def test_model_input_estimate_budget_reserves_before_dispatch_including_failures(self):
        observation = {}
        budget = worker.ModelInputBudget(observation)
        payload = {"messages": [{"role": "user", "content": "synthetic input"}], "tools": []}
        expected = (len(worker.encoded(payload)) + 3) // 4
        with patch.object(worker, "MAX_CUMULATIVE_MODEL_INPUT_ESTIMATE", expected * 2):
            budget.reserve(payload)
            budget.metrics["failed_dispatches"] += 1
            budget.reserve(payload)
            with self.assertRaisesRegex(worker.PolicyError, "MODEL_INPUT_ESTIMATE_BUDGET_EXHAUSTED"):
                budget.reserve(payload)
        self.assertEqual(budget.metrics["dispatch_attempts"], 2)
        self.assertEqual(budget.metrics["estimated_input_tokens"], expected * 2)
        with patch.object(worker, "MAX_MODEL_INPUT_ESTIMATE", expected - 1):
            with self.assertRaisesRegex(worker.PolicyError, "MODEL_INPUT_ESTIMATE_BUDGET_EXHAUSTED"):
                budget.reserve(payload)
        self.assertEqual(budget.metrics["dispatch_attempts"], 2)
        self.assertFalse(budget.metrics["billed_tokens"])
        self.assertNotIn("synthetic input", json.dumps(observation))

    def test_large_review_finishes_existing_dispatch_without_discarding_evidence(self):
        budget = worker.ModelInputBudget({})
        # The failing weekly run had spent 72,278 before its 47,027-token
        # fourth dispatch. That response requested another large source batch.
        budget.metrics.update(dispatch_attempts=3, completed_responses=3, estimated_input_tokens=72278)
        # 187,800 bytes against the original 60,000 limit; keep the same share of the current limit.
        payload = {"model": worker.MODEL, "input": [{"role": "user", "content": "x" * (worker.MAX_MODEL_INPUT_ESTIMATE * 3 + 7800)}],
                   "instructions": "Original contract", "tools": [{"type": "function", "name": "ops_read_source"}]}
        original = copy.deepcopy(payload)
        prepared = budget.reserve_dispatch(payload)
        self.assertEqual(payload, original)
        self.assertEqual(prepared["input"], original["input"])
        self.assertEqual(prepared["tools"], original["tools"])
        self.assertEqual(prepared["tool_choice"], "none")
        self.assertIn("incomplete", worker.build_prompt(self.request))
        self.assertIn("evidence gaps", prepared["instructions"])
        self.assertTrue(prepared["instructions"].startswith("Original contract"))
        self.assertEqual(budget.metrics["dispatch_attempts"], 4)
        self.assertEqual(budget.metrics["finalization_dispatches"], 1)
        self.assertEqual(budget.metrics["finalization_reason"], "per_dispatch_headroom")
        self.assertLessEqual(budget.metrics["max_dispatch_estimate"], worker.MAX_MODEL_INPUT_ESTIMATE)
        self.assertLessEqual(budget.metrics["estimated_input_tokens"], worker.MAX_CUMULATIVE_MODEL_INPUT_ESTIMATE)
        with self.assertRaisesRegex(worker.PolicyError, "MODEL_FINALIZATION_NOT_COMPLETED"):
            budget.reserve_dispatch(payload)
        self.assertEqual(budget.metrics["dispatch_attempts"], 4)

    def test_finalization_counts_instruction_overhead_and_cumulative_headroom(self):
        payload = {"model": worker.MODEL, "messages": [{"role": "user", "content": "x" * 1000}]}
        estimate = (len(worker.encoded(payload)) + 3) // 4
        budget = worker.ModelInputBudget({})
        budget.metrics["estimated_input_tokens"] = worker.MAX_CUMULATIVE_MODEL_INPUT_ESTIMATE - 2 * estimate
        prepared = budget.reserve_dispatch(payload)
        self.assertEqual(prepared["tool_choice"], "none")
        self.assertEqual(prepared["messages"][0], payload["messages"][0])
        self.assertEqual(budget.metrics["finalization_reason"], "cumulative_headroom")
        budget = worker.ModelInputBudget({})
        with patch.object(worker, "MAX_MODEL_INPUT_ESTIMATE", estimate):
            with self.assertRaisesRegex(worker.PolicyError, "MODEL_INPUT_ESTIMATE_BUDGET_EXHAUSTED"):
                budget.reserve_dispatch(payload)
        self.assertEqual(budget.metrics["dispatch_attempts"], 0)
        self.assertEqual(budget.metrics["finalization_dispatches"], 0)
        self.assertEqual(budget.metrics["budget_rejections"], 1)
        budget = worker.ModelInputBudget({})
        budget.metrics["estimated_input_tokens"] = worker.MAX_CUMULATIVE_MODEL_INPUT_ESTIMATE - estimate
        with self.assertRaisesRegex(worker.PolicyError, "MODEL_INPUT_ESTIMATE_BUDGET_EXHAUSTED"):
            budget.reserve_dispatch(payload)
        self.assertEqual(budget.metrics["dispatch_attempts"], 0)
        self.assertEqual(budget.metrics["finalization_dispatches"], 0)

    def test_ordinary_dispatch_is_unchanged_and_last_dispatch_is_final(self):
        payload = {"model": worker.MODEL, "input": [{"role": "user", "content": "Small evidence"}],
                   "instructions": "Contract", "tools": []}
        budget = worker.ModelInputBudget({})
        self.assertIs(budget.reserve_dispatch(payload), payload)
        self.assertEqual(budget.metrics["finalization_dispatches"], 0)
        self.assertNotIn("tool_choice", payload)
        budget.metrics["dispatch_attempts"] = worker.MAX_ITERATIONS - 1
        self.assertEqual(budget.reserve_dispatch(payload)["tool_choice"], "none")
        self.assertEqual(budget.metrics["finalization_reason"], "last_dispatch")
        self.assertEqual(budget.metrics["dispatch_attempts"], worker.MAX_ITERATIONS)

    def test_bounded_source_read_returns_only_bundle_lines_and_continuation(self):
        content = ("x" * 1000 + "\n") * 40
        self.request["sources"][self.source]["content"] = content
        tools = worker.BundleTools(self.request)
        page = tools.read_source({"path": self.source, "start_line": 2, "line_count": 40})
        self.assertLessEqual(len(page["content"].encode()), worker.MAX_PAGE_BYTES)
        self.assertEqual(page["start_line"], 2)
        self.assertEqual(page["end_line"], 17)
        self.assertEqual(page["next_line"], 18)
        self.assertEqual(page["total_lines"], 40)
        for args in ({"path": "/etc/passwd"}, {"path": self.source, "line_count": True},
                     {"path": self.source, "start_line": 0}, {"path": self.source, "line_count": 201},
                     {"path": self.source, "start_line": 999}):
            with self.assertRaises(worker.PolicyError):
                tools.read_source(args)

    def test_invalid_page_is_recoverable_in_native_batch_and_scope_stays_fatal(self):
        tools = worker.BundleTools(self.request)
        for bad in (201, 0, True, "200"):
            args = {"path": self.source, "line_count": bad}
            tools.validate_batch(message("ops_read_source", args))
            result = json.loads(tools.dispatch("ops_read_source", args))
            self.assertFalse(result["success"])
            self.assertEqual(result["error_code"], "BUNDLE_PAGE_INVALID")
            self.assertNotIn("private-source-marker", json.dumps(result))
        good = {"path": self.source, "line_count": 120}
        tools.validate_batch(message("ops_read_source", good))
        self.assertTrue(json.loads(tools.dispatch("ops_read_source", good))["success"])
        for args in ({"path": "/etc/passwd", "line_count": 999},
                     {"path": self.source, "command": "invalid"}):
            with self.assertRaisesRegex(worker.PolicyError, "BUNDLE_SOURCE_REFUSED"):
                tools.validate_batch(message("ops_read_source", args))
        self.assertEqual(tools.calls, 5)

    def test_oversized_single_line_fails_without_leaking_partial_or_other_files(self):
        self.request["sources"][self.source]["content"] = "private-source-marker" * worker.MAX_PAGE_BYTES
        result = json.loads(worker.BundleTools(self.request).dispatch("ops_read_source", {"path": self.source}))
        self.assertFalse(result["success"])
        self.assertEqual(result["error_code"], "BUNDLE_LINE_TOO_LARGE")
        self.assertNotIn("private-source-marker", json.dumps(result))

    def test_candidate_check_has_no_execution_or_filesystem_writes(self):
        # Python that would write a marker if executed is parsed as data only.
        candidate = copy.deepcopy(self.candidate)
        marker = self.root / "should-not-exist"
        candidate["replacements"][0]["content"] = f"open({str(marker)!r}, 'w').write('executed')\n"
        result = worker.candidate_check(candidate, self.request)
        self.assertTrue(result["success"])
        self.assertFalse(result["tests_executed"])
        self.assertFalse(result["applied"])
        self.assertFalse(marker.exists())

    def test_candidate_rejects_protected_paths_drift_duplicate_additions_and_syntax(self):
        for mutation in ("protected", "base", "duplicate", "addition", "syntax", "extra"):
            request, candidate = copy.deepcopy(self.request), copy.deepcopy(self.candidate)
            entry = candidate["replacements"][0]
            if mutation == "protected":
                path = "scripts/openclaw/test-telegram-operations.py"
                request["sources"][path] = request["sources"][self.source]
                entry["path"] = path
            elif mutation == "base":
                entry["beforeSha256"] = "b" * 64
            elif mutation == "duplicate":
                candidate["replacements"].append(copy.deepcopy(entry))
            elif mutation == "addition":
                entry["path"] = "docs/OPENCLAW_NEW.md"
            elif mutation == "syntax":
                entry["content"] = "def broken(\n"
            else:
                entry["command"] = "true"
            with self.subTest(mutation=mutation), self.assertRaises(worker.PolicyError):
                worker.candidate_check(candidate, request)

    def test_inventory_and_tool_count_enforce_real_dispatch_boundary(self):
        inventory = [{"function": {"name": name}} for name in worker.EXPECTED_TOOLS]
        worker.validate_tools(inventory)
        for invalid in (inventory[:-1], inventory + [{"function": {"name": "terminal"}}], inventory + [inventory[0]]):
            with self.assertRaisesRegex(worker.PolicyError, "TOOL_POLICY_MISMATCH"):
                worker.validate_tools(invalid)
        tools = worker.BundleTools(self.request)
        for _ in range(worker.MAX_TOOL_CALLS):
            tools.validate_batch(message("skills_list", {}))
        with self.assertRaisesRegex(worker.PolicyError, "TOOL_BUDGET_EXHAUSTED"):
            tools.validate_batch(message("skills_list", {}))

    def test_skill_dispatch_cannot_read_other_profiles_or_approve_its_own_changes(self):
        tools = worker.BundleTools(self.request)
        tools.validate_batch(message("skill_view", {"name": worker.SKILL_NAME}))
        tools.validate_batch(message("skill_manage", {"operations": [{"name": worker.SKILL_NAME,
            "action": "patch", "old_string": "old", "new_string": "new"}]}))
        for name, args in (("terminal", {"command": "true"}), ("skill_view", {"name": "operations-event-report"}),
                           ("skill_view", {"name": worker.SKILL_NAME, "path": "../../auth.json"}),
                           ("skill_manage", {"operations": [{"name": worker.SKILL_NAME, "action": "approve"}]}),
                           ("skill_manage", {"operations": [{"name": worker.SKILL_NAME, "action": "write_file"}]})):
            with self.subTest(name=name), self.assertRaises(worker.PolicyError):
                tools.validate_batch(message(name, args))

    def test_scope_snapshot_rejects_other_skills_and_executable_support_files(self):
        for path in (self.home / "skills/other/SKILL.md", self.skill.parent / "helper.py"):
            path.parent.mkdir(exist_ok=True)
            path.write_text("private data")
            with self.assertRaisesRegex(worker.PolicyError, "SKILL_SCOPE_VIOLATION"):
                worker.skill_snapshot(self.home)
            path.unlink()

    def test_output_requires_known_evidence_and_never_accepts_malformed_json(self):
        self.assertEqual(worker.validate_result(json.dumps(self.result), self.request), self.result)
        for mutation in ("extra", "evidence", "self-labelled", "duplicate", "snapshot", "nan"):
            result = copy.deepcopy(self.result)
            if mutation == "extra":
                result["applied"] = True
            elif mutation == "evidence":
                result["findings"][0]["evidence"] = "status"
            elif mutation == "self-labelled":
                result["findings"][0]["evidenceStatus"] = "insufficient"
            elif mutation == "duplicate":
                result["findings"].append(copy.deepcopy(result["findings"][0]))
            elif mutation == "snapshot":
                result["patches"]["snapshotSha256"] = "b" * 64
            else:
                result["schemaVersion"] = float("nan")
            with self.subTest(mutation=mutation), self.assertRaises(worker.PolicyError):
                worker.validate_result(json.dumps(result), self.request)
        with self.assertRaisesRegex(worker.PolicyError, "RESULT_JSON_INVALID"):
            worker.validate_result("```json\n{}\n```", self.request)

    def test_mis_cited_evidence_keeps_the_finding_but_marks_it_insufficient(self):
        # 2026-09-23: a 170-second incident review was discarded because one finding cited a key
        # that was not in the allowed list. The analysis and the other findings must survive.
        self.result["findings"].append({"id": "finding-2", "kind": "issue", "severity": "medium",
            "title": "감시기 실패", "evidence": ["status", "status.issues", 7, "invented evidence"],
            "recommendation": "실패 단계를 확인하세요."})
        parsed = worker.validate_result(json.dumps(self.result), self.request)
        self.assertEqual(parsed["findings"][0], self.result["findings"][0])
        kept = parsed["findings"][1]
        self.assertEqual(kept["evidence"], ["status"])
        self.assertEqual(kept["evidenceStatus"], "insufficient")
        self.assertEqual(kept["unverifiedCitationCount"], 3)
        self.assertEqual(kept["title"], "감시기 실패")
        self.assertEqual(parsed["analysis"], self.result["analysis"])
        self.assertNotIn("invented evidence", json.dumps(parsed))
        self.result["findings"][1]["evidence"] = ["status"] * 13
        with self.assertRaisesRegex(worker.PolicyError, "FINDING_EVIDENCE_INVALID"):
            worker.validate_result(json.dumps(self.result), self.request)

    def test_one_tool_round_cannot_push_the_next_dispatch_past_its_limit(self):
        # 2026-09-19 incident: after a 27,702 dispatch (76,371 cumulative) one batch of parallel reads
        # made the next request exceed 60,000 and the whole run was rejected.
        content = ("y" * 999 + "\n") * 400
        self.request["sources"][self.source] = {"sha256": hashlib.sha256(content.encode()).hexdigest(),
                                                "content": content}
        budget = worker.ModelInputBudget({})
        # Keep that incident's remaining per-dispatch headroom (60,000 - 27,702) at the current limit.
        last = worker.MAX_MODEL_INPUT_ESTIMATE - 60000 + 27702
        budget.metrics["estimated_input_tokens"] = 76371 - 27702
        budget.reserve({"input": "z" * (last * 4 - 16)})
        allowance = budget.round_allowance_bytes()
        tools = worker.BundleTools(self.request)
        tools.start_round(allowance)
        replies, start = [], 1
        for _ in range(12):
            reply = json.loads(tools.dispatch("ops_read_source", {"path": self.source, "start_line": start,
                                                                   "line_count": 200}))
            replies.append(reply)
            if reply["success"]:
                start = reply["next_line"]
        delivered = tools.read_metrics["delivered_bytes"]
        self.assertLessEqual(delivered, allowance)
        next_dispatch = budget.last_estimate + delivered * worker.TOOL_RESULT_EXPANSION / 4
        self.assertLessEqual(next_dispatch + worker.ROUND_RESERVE_ESTIMATE, worker.MAX_MODEL_INPUT_ESTIMATE)
        rejected = replies[-1]
        self.assertFalse(rejected["success"])
        self.assertEqual(rejected["error_code"], "SOURCE_ROUND_BUDGET_EXHAUSTED")
        self.assertTrue(rejected["recoverable"])
        self.assertNotIn("yyyy", json.dumps(rejected))
        # The next measured dispatch opens a new round.
        tools.start_round(4000)
        page = tools.read_source({"path": self.source, "start_line": start, "line_count": 200})
        self.assertEqual(page["end_line"] - page["start_line"] + 1, 4)
        self.assertEqual(page["next_line"], start + 4)
        self.assertGreaterEqual(tools.read_metrics["round_limited_reads"], 1)

    def test_round_allowance_also_respects_the_cumulative_limit(self):
        budget = worker.ModelInputBudget({})
        # 10,004 reserved leaves 9,992 cumulative, less than a repeat of this request.
        budget.metrics["estimated_input_tokens"] = worker.MAX_CUMULATIVE_MODEL_INPUT_ESTIMATE - 20000
        budget.reserve({"input": "z" * 40000})
        self.assertEqual(budget.round_allowance_bytes(), 0)
        self.assertEqual(worker.BundleTools(self.request).round_allowance, None)

    def test_incident_prompt_is_scoped_only_when_the_bundle_was_narrowed(self):
        # An incident run with an explicit --full-review carries no evidence.incident.
        self.assertNotIn("incident-scoped review", worker.build_prompt(self.request))
        self.request["evidence"]["incident"] = {"available": True, "reviewScope": {"issues": []}}
        self.assertIn("incident-scoped review", worker.build_prompt(self.request))
        self.request["mode"] = "manual"
        self.assertNotIn("incident-scoped review", worker.build_prompt(self.request))

    def test_completion_requires_successful_native_skill_list_and_read(self):
        worker.assert_skill_reads([{"name": "skills_list", "succeeded": True}, {"name": "skill_view", "succeeded": True}])
        with self.assertRaisesRegex(worker.PolicyError, "REQUIRED_SKILL_READ_MISSING"):
            worker.assert_skill_reads([{"name": "skills_list", "succeeded": True}, {"name": "skill_view", "succeeded": False}])
        receipt = worker.tool_receipt("skill_manage", {"success": True, "staged": True, "content": "private body"})
        self.assertTrue(receipt["staged"])
        self.assertNotIn("private", json.dumps(receipt))

    def test_upstream_fail_open_or_bypass_cannot_disable_skill_staging(self):
        gate = SimpleNamespace(SKILLS="skills", evaluate_gate=lambda _kind: SimpleNamespace(allow=False))
        manager = SimpleNamespace(_skill_gate_bypass=SimpleNamespace(get=lambda: False))
        worker.assert_staged_skill_gate(gate, manager)
        gate.evaluate_gate = lambda _kind: SimpleNamespace(allow=True)
        with self.assertRaisesRegex(worker.PolicyError, "SKILL_APPROVAL_GATE_UNAVAILABLE"):
            worker.assert_staged_skill_gate(gate, manager)
        gate.evaluate_gate = lambda _kind: SimpleNamespace(allow=False)
        manager._skill_gate_bypass.get = lambda: True
        with self.assertRaisesRegex(worker.PolicyError, "SKILL_APPROVAL_GATE_UNAVAILABLE"):
            worker.assert_staged_skill_gate(gate, manager)

    def test_runtime_environment_accepts_only_its_exact_generated_session_id(self):
        with patch.dict(os.environ, self.env, clear=True), worker.profile_environment(self.home):
            env = dict(os.environ, HERMES_SESSION_ID="hermes-ops-synthetic")
            worker.validate_runtime_environment(env, self.home, "hermes-ops-synthetic")
            with self.assertRaisesRegex(worker.PolicyError, "RUNTIME_SESSION_ENV_MISMATCH"):
                worker.validate_runtime_environment(env, self.home, "different-session")
            with self.assertRaisesRegex(worker.PolicyError, "INHERITED_CREDENTIAL_OR_ROUTE_REFUSED"):
                worker.validate_runtime_environment({**env, "OPENAI_API_KEY": "synthetic"}, self.home, "hermes-ops-synthetic")

    def test_native_config_bridge_is_exact_and_never_allowed_as_inherited_environment(self):
        with patch.dict(os.environ, self.env, clear=True), worker.profile_environment(self.home):
            env = {**os.environ, **worker.RUNTIME_CONFIG_ENV}
            worker.validate_runtime_environment(env, self.home, "hermes-ops-synthetic")
            for key in worker.RUNTIME_CONFIG_ENV:
                with self.subTest(key=key), self.assertRaisesRegex(worker.PolicyError, "RUNTIME_CONFIG_ENV_MISMATCH"):
                    worker.validate_runtime_environment({**env, key: "999"}, self.home, "hermes-ops-synthetic")
                with self.subTest(inherited=key), self.assertRaisesRegex(worker.PolicyError, "INHERITED_CREDENTIAL_OR_ROUTE_REFUSED"):
                    worker.validate_profile(self.profile, {**self.env, key: worker.RUNTIME_CONFIG_ENV[key]})
            for key in ("HERMES_AGENT_TIMEOUT", "HERMES_CODEX_BASE_URL", "OPENAI_API_KEY", "HTTPS_PROXY"):
                with self.subTest(unexpected=key), self.assertRaisesRegex(worker.PolicyError, "INHERITED_CREDENTIAL_OR_ROUTE_REFUSED"):
                    worker.validate_runtime_environment({**env, key: "synthetic"}, self.home, "hermes-ops-synthetic")

    def test_shared_report_worker_lock_blocks_before_runtime(self):
        lock_path = self.profile / ".pilot-worker.lock"
        lock_path.touch(mode=0o600)
        with lock_path.open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with patch.object(worker, "run_worker") as runtime:
                code, _output = self.main()
                runtime.assert_not_called()
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(self.receipt_file.read_text())["error_code"], "PROFILE_ALREADY_RUNNING")

    def test_timeout_keeps_safe_observations_but_not_completed(self):
        def timeout(_home, _request, observation):
            observation.update(response_models=[worker.MODEL], usage={"total_tokens": 12},
                               tool_calls=[{"name": "skill_view", "succeeded": True}])
            raise worker.WorkerTimeout()
        code, output = self.main(timeout)
        receipt = json.loads(self.receipt_file.read_text())
        self.assertEqual(code, 1)
        self.assertFalse(receipt["completed"])
        self.assertEqual(receipt["error_code"], "WORKER_TIMEOUT")
        self.assertEqual(receipt["usage"]["total_tokens"], 12)
        self.assertEqual(receipt["response_models"], [worker.MODEL])
        self.assertNotIn("synthetic-access", self.receipt_file.read_text() + output)

    def test_raw_provider_error_is_suppressed_and_receipt_private(self):
        def fail(*_args):
            raise RuntimeError("private-provider-error synthetic-access")
        code, output = self.main(fail)
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(self.receipt_file.read_text())["error_code"], "HERMES_RUNTIME_FAILED")
        self.assertNotIn("private-provider-error", self.receipt_file.read_text() + output)
        self.assertEqual(self.receipt_file.stat().st_mode & 0o777, 0o600)

    def test_existing_receipt_and_profile_destination_are_never_written(self):
        self.receipt_file = self.home / "forbidden.json"
        with patch.object(worker, "run_worker") as runtime:
            code, output = self.main()
            runtime.assert_not_called()
        self.assertEqual(code, 1)
        self.assertFalse(self.receipt_file.exists())
        self.assertEqual(json.loads(output)["error_code"], "RECEIPT_LOCATION_REFUSED")

    def test_deadline_interrupts_bounded_worker_and_restores_handler(self):
        previous = worker.base.signal.getsignal(worker.base.signal.SIGALRM)
        with self.assertRaises(worker.WorkerTimeout):
            with worker.base.deadline(0.01):
                time.sleep(0.1)
        self.assertEqual(worker.base.signal.getsignal(worker.base.signal.SIGALRM), previous)

    @unittest.skipUnless(importlib.util.find_spec("run_agent") is not None, "optional pinned Hermes runtime is not installed")
    def test_native_conversation_startup_reaches_transport_without_network_or_inference(self):
        # Exercise the real conversation startup. Mocking run_conversation hides
        # its gateway config bridge and previously missed a first-call rejection.
        class StopBeforeInference(BaseException):
            pass

        reached = []
        auth_before = (self.profile / "auth.json").read_bytes()
        with patch.dict(os.environ, self.env, clear=True), worker.profile_environment(self.home), \
                patch.object(socket.socket, "connect", side_effect=AssertionError("offline network blocked")), \
                worker.base.quiet_runtime():
            from run_agent import AIAgent
            from hermes_cli.config_defaults import DEFAULT_CONFIG
            self.assertEqual(worker.RUNTIME_CONFIG_ENV["HERMES_TURN_LEASE_TIMEOUT"],
                             str(DEFAULT_CONFIG["agent"]["gateway_turn_lease_timeout"]))

            def stop_before_transport(agent, api_kwargs):
                self.assertEqual(api_kwargs["model"], worker.MODEL)
                self.assertEqual({key: os.environ.get(key) for key in worker.RUNTIME_CONFIG_ENV}, worker.RUNTIME_CONFIG_ENV)
                self.assertEqual(os.environ["HERMES_SESSION_ID"], agent.session_id)
                reached.append(True)
                raise StopBeforeInference()

            observation = {}
            with patch.object(AIAgent, "_interruptible_api_call", stop_before_transport):
                with self.assertRaises(StopBeforeInference):
                    worker.run_worker(self.home, self.request, observation)
            self.assertEqual(reached, [True])
            self.assertEqual(observation["response_models"], [])
            self.assertEqual(observation["usage"]["api_calls"], 0)
            self.assertEqual(observation["model_input_metrics"]["dispatch_attempts"], 1)
            self.assertEqual(observation["model_input_metrics"]["failed_dispatches"], 1)
            self.assertGreater(observation["model_input_metrics"]["estimated_input_tokens"], 0)
            self.assertEqual((self.profile / "auth.json").read_bytes(), auth_before)
            self.assertFalse((self.home / "auth.json").exists())

    @unittest.skipUnless(importlib.util.find_spec("run_agent") is not None, "optional pinned Hermes runtime is not installed")
    def test_native_failed_dispatch_and_budget_stop_preserve_private_metrics_in_receipt(self):
        with patch.dict(os.environ, self.env, clear=True), worker.profile_environment(self.home), \
                patch.object(socket.socket, "connect", side_effect=AssertionError("offline network blocked")), \
                worker.base.quiet_runtime():
            from run_agent import AIAgent

            def conversation(agent, **_kwargs):
                agent._interruptible_api_call({"model": agent.model, "messages": [
                    {"role": "user", "content": "private-input-marker"}]})

            with patch.object(AIAgent, "run_conversation", conversation), \
                    patch.object(AIAgent, "_interruptible_api_call", side_effect=RuntimeError("private-provider-marker")) as dispatch:
                code, output = self.main()
                self.assertEqual(dispatch.call_count, 1)
            receipt = json.loads(self.receipt_file.read_text())
            self.assertEqual(code, 1)
            self.assertEqual(receipt["model_input_metrics"]["dispatch_attempts"], 1)
            self.assertEqual(receipt["model_input_metrics"]["failed_dispatches"], 1)
            self.assertGreater(receipt["model_input_metrics"]["estimated_input_tokens"], 0)
            self.assertEqual(receipt["read_metrics"]["delivered_bytes"], 0)
            self.assertNotIn("private-input-marker", self.receipt_file.read_text() + output)
            self.assertNotIn("private-provider-marker", self.receipt_file.read_text() + output)
            self.receipt_file.unlink()
            with patch.object(AIAgent, "run_conversation", conversation), \
                    patch.object(AIAgent, "_interruptible_api_call") as dispatch, \
                    patch.object(worker, "MAX_MODEL_INPUT_ESTIMATE", 1):
                code, _output = self.main()
                dispatch.assert_not_called()
            receipt = json.loads(self.receipt_file.read_text())
            self.assertEqual(code, 1)
            self.assertEqual(receipt["error_code"], "MODEL_INPUT_ESTIMATE_BUDGET_EXHAUSTED")
            self.assertEqual(receipt["model_input_metrics"]["dispatch_attempts"], 0)
            self.assertEqual(receipt["model_input_metrics"]["budget_rejections"], 1)

    @unittest.skipUnless(importlib.util.find_spec("run_agent") is not None, "optional pinned Hermes runtime is not installed")
    def test_native_finalization_keeps_evidence_and_refuses_any_more_tools_or_dispatches(self):
        with patch.dict(os.environ, self.env, clear=True), worker.profile_environment(self.home), \
                patch.object(socket.socket, "connect", side_effect=AssertionError("offline network blocked")), \
                worker.base.quiet_runtime():
            import model_tools
            from run_agent import AIAgent
            from agent.codex_responses_adapter import _preflight_codex_api_kwargs
            reply = copy.deepcopy(self.result)
            reply["analysis"] = "입력 예산 때문에 소스 검토를 조기 종료했습니다. 읽지 않은 소스는 검증되지 않았습니다."
            original_input = [{"role": "user", "content": "synthetic-evidence " * (9000 * worker.MAX_MODEL_INPUT_ESTIMATE // 60000)}]

            def transport(agent, api_kwargs):
                normalized = _preflight_codex_api_kwargs(api_kwargs)
                self.assertEqual(normalized["tool_choice"], "none")
                self.assertEqual(api_kwargs["input"], original_input)
                self.assertIn("evidence gaps", normalized["instructions"])
                return SimpleNamespace(model=worker.MODEL)

            def conversation(agent, **_kwargs):
                for name, args in (("skills_list", {}), ("skill_view", {"name": worker.SKILL_NAME})):
                    result = model_tools.handle_function_call(name, args, enabled_tools=sorted(worker.EXPECTED_TOOLS),
                                                              enabled_toolsets=["skills", "ops_bundle"])
                    agent.tool_complete_callback("synthetic", name, args, result)
                kwargs = {"model": agent.model, "input": original_input, "instructions": "Contract", "tools": []}
                agent._interruptible_api_call(kwargs)
                with self.assertRaisesRegex(worker.RuntimePolicyStop, "MODEL_FINALIZATION_NOT_COMPLETED"):
                    agent._execute_tool_calls(message("ops_read_source", {"path": self.source}), [], "synthetic")
                with self.assertRaisesRegex(worker.RuntimePolicyStop, "MODEL_FINALIZATION_NOT_COMPLETED"):
                    agent._interruptible_api_call(kwargs)
                return {"completed": True, "partial": False, "interrupted": False, "error": None,
                        "final_response": json.dumps(reply, ensure_ascii=False)}

            with patch.object(AIAgent, "run_conversation", conversation), \
                    patch.object(AIAgent, "_interruptible_api_call", transport):
                observed = worker.run_worker(self.home, self.request, {})
            self.assertTrue(observed["completed"])
            self.assertEqual(observed["model_input_metrics"]["dispatch_attempts"], 1)
            self.assertEqual(observed["model_input_metrics"]["completed_responses"], 1)
            self.assertEqual(observed["model_input_metrics"]["finalization_dispatches"], 1)
            self.assertEqual(observed["read_metrics"]["successful_reads"], 0)
            self.assertEqual(observed["result"], reply)

    @unittest.skipUnless(importlib.util.find_spec("run_agent") is not None, "optional pinned Hermes runtime is not installed")
    def test_native_registry_agent_and_borrowed_auth_without_network_or_inference(self):
        seed = self.skill.read_bytes()
        native_result = json.dumps(self.result, ensure_ascii=False)
        with patch.dict(os.environ, self.env, clear=True), worker.profile_environment(self.home), \
                patch.object(socket.socket, "connect", side_effect=AssertionError("offline network blocked")), \
                worker.base.quiet_runtime():
            import model_tools
            from run_agent import AIAgent
            from agent.credential_pool import persist_pool_entries

            def conversation(agent, **_kwargs):
                # Only the provider response is synthetic. Constructor, tool
                # registry, dispatch, approval staging, and auth borrowing are native.
                agent._interruptible_api_call({"model": agent.model})
                for name, args in (("skills_list", {}), ("skill_view", {"name": worker.SKILL_NAME}),
                                   ("ops_read_source", {"path": self.source}),
                                   ("skill_manage", {"operations": [{"action": "patch", "name": worker.SKILL_NAME,
                                       "old_string": "Review evidence first.", "new_string": "Review supplied evidence first."}]})):
                    worker.BundleTools(self.request).validate_batch(message(name, args))
                    result = model_tools.handle_function_call(name, args, enabled_tools=sorted(worker.EXPECTED_TOOLS),
                                                              enabled_toolsets=["skills", "ops_bundle"])
                    agent.tool_complete_callback("synthetic", name, args, result)
                return {"completed": True, "partial": False, "interrupted": False, "error": None,
                        "final_response": native_result}

            with patch.object(AIAgent, "_interruptible_api_call", return_value=SimpleNamespace(model=worker.MODEL)), \
                    patch.object(AIAgent, "run_conversation", conversation):
                observation = {"completed": False}
                result = worker.run_worker(self.home, self.request, observation)
            self.assertTrue(result["completed"])
            self.assertEqual(result["effective_model"], worker.MODEL)
            self.assertEqual(result["effective_api_mode"], worker.API_MODE)
            self.assertEqual(result["result"], self.result)
            self.assertTrue(result["tool_calls"][-1]["staged"])
            self.assertEqual(self.skill.read_bytes(), seed)
            updated = {**self.auth["credential_pool"][worker.PROVIDER][0],
                       "access_token": "synthetic-new-access", "refresh_token": "synthetic-new-refresh"}
            persist_pool_entries(worker.PROVIDER, [updated])
            after = json.loads((self.profile / "auth.json").read_text())
            self.assertEqual(after["credential_pool"][worker.PROVIDER][0]["refresh_token"], "synthetic-new-refresh")
            self.assertFalse((self.home / "auth.json").exists())


if __name__ == "__main__":
    unittest.main()
