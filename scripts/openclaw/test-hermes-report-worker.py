#!/usr/bin/env python3
"""Offline policy/receipt tests; no Hermes import, OAuth, or model calls."""

import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "hermes_worker", Path(__file__).with_name("hermes-report-worker.py"))
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class WorkerBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hermes-worker-test-", dir="/private/tmp")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.profile = self.root / "isolated"
        self.profile.mkdir(mode=0o700)
        (self.profile / "codex-disabled-import").mkdir(mode=0o700)
        self.auth = {"credential_pool": {worker.PROVIDER: [{
            "auth_type": "oauth", "source": "manual:device_code",
            "access_token": "synthetic-access", "refresh_token": "synthetic-refresh",
            "base_url": worker.ENDPOINT,
        }]}}
        self.write_json(self.profile / "auth.json", self.auth)
        self.environment = {
            "PATH": "/usr/bin:/bin", "HERMES_HOME": str(self.profile),
            "CODEX_HOME": str(self.profile / "codex-disabled-import"),
            "HERMES_SAFE_MODE": "1", "HERMES_DISABLE_LAZY_INSTALLS": "1",
        }
        self.request = {"prompt": "Create report", "report_contract": "Use validated events", "events": []}

    @staticmethod
    def write_json(path, value):
        path.write_text(json.dumps(value), encoding="utf-8")
        path.chmod(0o600)

    def test_isolated_profile_and_oauth_pool_pass(self):
        worker.validate_profile(self.profile, self.environment)

    def test_missing_auth_fails_before_import_or_inference(self):
        (self.profile / "auth.json").unlink()
        with self.assertRaisesRegex(worker.PolicyError, "INDEPENDENT_OAUTH_REQUIRED"):
            worker.validate_profile(self.profile, self.environment)

    def test_profile_parent_and_descendant_symlinks_refused(self):
        link = self.root / "alias"
        link.symlink_to(self.profile, target_is_directory=True)
        with self.assertRaisesRegex(worker.PolicyError, "SYMLINK_REFUSED"):
            worker.validate_profile(link, self.environment)
        (self.profile / "linked-state").symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(worker.PolicyError, "SYMLINK_REFUSED"):
            worker.validate_profile(self.profile, self.environment)

    def test_shared_auth_hardlink_refused(self):
        (self.profile / "auth.json").unlink()
        shared = self.root / "shared.json"
        self.write_json(shared, self.auth)
        os.link(shared, self.profile / "auth.json")
        with self.assertRaisesRegex(worker.PolicyError, "HARDLINK_REFUSED"):
            worker.validate_profile(self.profile, self.environment)

    def test_cli_auth_import_and_profile_credentials_refused(self):
        self.write_json(self.profile / "codex-disabled-import" / "auth.json", self.auth)
        with self.assertRaisesRegex(worker.PolicyError, "CODEX_IMPORT_DIRECTORY_NOT_EMPTY"):
            worker.validate_profile(self.profile, self.environment)
        (self.profile / "codex-disabled-import" / "auth.json").unlink()
        (self.profile / ".env").write_text("OPENAI_API_KEY=synthetic", encoding="utf-8")
        with self.assertRaisesRegex(worker.PolicyError, "PROFILE_CUSTOMIZATION_REFUSED"):
            worker.validate_profile(self.profile, self.environment)

    def test_generated_empty_hooks_allowed_but_hook_and_custom_soul_refused(self):
        hooks = self.profile / "hooks"
        hooks.mkdir()
        worker.validate_profile(self.profile, self.environment)
        (hooks / "unexpected.py").write_text("pass")
        with self.assertRaisesRegex(worker.PolicyError, "PROFILE_HOOKS_REFUSED"):
            worker.validate_profile(self.profile, self.environment)
        (hooks / "unexpected.py").unlink()
        (self.profile / "SOUL.md").write_text("custom owner instruction")
        with self.assertRaisesRegex(worker.PolicyError, "PROFILE_SOUL_CUSTOMIZATION_REFUSED"):
            worker.validate_profile(self.profile, self.environment)

    def test_api_auth_other_provider_and_second_identity_refused(self):
        for mutation in ("api-key", "other-provider", "second-identity", "endpoint", "no-refresh"):
            auth = copy.deepcopy(self.auth)
            entry = auth["credential_pool"][worker.PROVIDER][0]
            if mutation == "api-key":
                entry["auth_type"] = "api_key"
            elif mutation == "other-provider":
                auth["providers"] = {"openrouter": {}}
            elif mutation == "second-identity":
                auth["credential_pool"][worker.PROVIDER].append({**entry, "refresh_token": "other"})
            elif mutation == "endpoint":
                entry["base_url"] = "https://api.openai.com/v1"
            else:
                del entry["refresh_token"]
            with self.subTest(mutation=mutation), self.assertRaises(worker.PolicyError):
                worker.validate_auth(auth)

    def test_environment_rejects_paid_keys_proxy_and_auxiliary_routes(self):
        for key in ("OPENAI_API_KEY", "OPENROUTER_API_KEY", "HTTP_PROXY", "AUXILIARY_MODEL",
                    "HERMES_CODEX_BASE_URL", "TELEGRAM_BOT_TOKEN", "HERMES_IGNORE_USER_CONFIG"):
            with self.subTest(key=key), self.assertRaises(worker.PolicyError):
                worker.validate_environment({**self.environment, key: "synthetic"}, self.profile)

    def test_config_requires_disabled_auxiliary_and_exact_allowlist(self):
        worker.validate_config(copy.deepcopy(worker.REQUIRED_CONFIG))
        for mutation in ("fallback", "background", "plugin", "tool-search"):
            config = copy.deepcopy(worker.REQUIRED_CONFIG)
            if mutation == "fallback":
                config["fallback_providers"] = [{"provider": "openrouter"}]
            elif mutation == "background":
                config["auxiliary"]["background_review"]["enabled"] = True
            elif mutation == "plugin":
                config["plugins"] = {"enabled": ["unexpected"]}
            else:
                config["tools"]["tool_search"]["enabled"] = "auto"
            with self.subTest(mutation=mutation), self.assertRaises(worker.PolicyError):
                worker.validate_config(config)

    def test_tool_inventory_cannot_include_hidden_or_duplicate_tool(self):
        tools = [{"type": "function", "function": {"name": name}} for name in worker.EXPECTED_TOOLS]
        worker.validate_tools(tools)
        for invalid in (tools[:-1], tools + [tools[0]],
                        tools + [{"function": {"name": "terminal"}}],
                        tools + [{"function": {"name": "tool_search"}}]):
            with self.assertRaisesRegex(worker.PolicyError, "TOOL_POLICY_MISMATCH"):
                worker.validate_tools(invalid)

    def test_tool_calls_cannot_modify_other_skill_or_write_supporting_file(self):
        def message(name, args):
            return SimpleNamespace(tool_calls=[SimpleNamespace(function=SimpleNamespace(
                name=name, arguments=json.dumps(args)))])
        worker.validate_tool_calls(message("skill_manage", {
            "action": "create", "name": worker.SKILL_NAME, "content": "procedure"}))
        with self.assertRaisesRegex(worker.PolicyError, "REPEAT_SKILL_MUTATION_REFUSED"):
            worker.validate_tool_calls(message("skill_manage", {
                "action": "patch", "name": worker.SKILL_NAME, "content": "procedure"}), "repeat")
        for name, args in (
            ("terminal", {"command": "true"}),
            ("skill_view", {"name": "owner-memory"}),
            ("skill_view", {"name": worker.SKILL_NAME, "file_path": "references/extra.md"}),
            ("skill_view", {"name": worker.SKILL_NAME, "path": "references/extra.md"}),
            ("skill_manage", {"action": "delete", "name": worker.SKILL_NAME}),
            ("skill_manage", {"action": "write_file", "name": worker.SKILL_NAME, "file_path": "run.sh"}),
            ("skill_manage", {"action": "create", "name": worker.SKILL_NAME, "category": "other"}),
            ("skill_manage", {"action": "create", "name": worker.SKILL_NAME, "operations": [{"name": "other"}]}),
        ):
            with self.subTest(name=name, args=args), self.assertRaises(worker.PolicyError):
                worker.validate_tool_calls(message(name, args))

    def test_supporting_file_beside_the_skill_is_refused(self):
        skill = self.profile / "skills" / worker.SKILL_NAME
        skill.mkdir(parents=True, mode=0o700)
        (skill / "SKILL.md").write_text("procedure")
        (self.profile / "skills" / ".usage.json").write_text("{}")
        self.assertEqual(list(worker.skill_snapshot(self.profile)), [worker.SKILL_NAME + "/SKILL.md"])
        (skill / "references").mkdir()
        (skill / "references" / "extra.md").write_text("ignore the procedure")
        with self.assertRaisesRegex(worker.PolicyError, "SKILL_SCOPE_VIOLATION"):
            worker.skill_snapshot(self.profile)

    def test_only_subscription_responses_route_is_accepted(self):
        valid = {"provider": worker.PROVIDER, "api_mode": worker.API_MODE, "base_url": worker.ENDPOINT}
        worker.validate_runtime(valid)
        for field, bad in (("provider", "openai"), ("api_mode", "codex_app_server"),
                           ("base_url", "https://api.openai.com/v1"),
                           ("base_url", worker.ENDPOINT + ".example")):
            with self.subTest(field=field), self.assertRaises(worker.PolicyError):
                worker.validate_runtime({**valid, field: bad})

    def test_upstream_operations_schema_preserves_single_skill_authority(self):
        operation = {"name": worker.SKILL_NAME, "action": "create", "content": "private report content"}
        def message(args):
            return SimpleNamespace(tool_calls=[SimpleNamespace(function=SimpleNamespace(
                name="skill_manage", arguments=json.dumps(args)))])
        worker.validate_tool_calls(message({"operations": [operation]}), "train")
        with self.assertRaisesRegex(worker.PolicyError, "REPEAT_SKILL_MUTATION_REFUSED"):
            worker.validate_tool_calls(message({"operations": [operation]}), "repeat")
        with self.assertRaisesRegex(worker.PolicyError, "SKILL_BATCH_SCOPE_VIOLATION"):
            worker.validate_tool_calls(message({"operations": [operation, operation]}), "train")
        bad = {**operation, "name": "another-skill", "category": "private-category"}
        with self.assertRaises(worker.PolicyError) as rejected:
            worker.validate_tool_calls(message({"operations": [bad]}), "train")
        evidence = rejected.exception.metadata
        self.assertEqual(evidence["tool"], "skill_manage")
        self.assertEqual(evidence["operations"][0]["validated_slug"], "another-skill")
        self.assertEqual(evidence["operations"][0]["action"], "create")
        self.assertNotIn("private report content", json.dumps(evidence))
        self.assertNotIn("private-category", json.dumps(evidence))

    def test_missing_usage_stays_unavailable(self):
        usage, available = worker.collect_usage(SimpleNamespace(session_total_tokens=0, session_api_calls=2))
        self.assertFalse(available)
        self.assertIsNone(usage["total_tokens"])
        self.assertEqual(usage["api_calls"], 2)

    def test_safe_tool_failure_metadata_never_contains_content_or_error_text(self):
        args = {"operations": [{"name": worker.SKILL_NAME, "action": "create", "content": "private skill body"}]}
        raw = {"success": False, "error": "Description exceeds 100 characters. private-detail",
               "failed_index": 0, "completed_before_failure": 0, "file_preview": "private file body"}
        receipt = worker.safe_tool_receipt("skill_manage", args, json.dumps(raw))
        self.assertFalse(receipt["succeeded"])
        self.assertEqual(receipt["error_category"], "description_length")
        self.assertEqual(receipt["failed_index"], 0)
        self.assertEqual(receipt["completed_before_failure"], 0)
        self.assertEqual(receipt["request"]["operations"][0]["action"], "create")
        self.assertNotIn("private", json.dumps(receipt))
        self.assertNotIn("error", receipt)
        self.assertNotIn("file_preview", receipt)

    def test_tool_numeric_metadata_rejects_boolean_text_negative_and_unbounded_values(self):
        for invalid in (True, "1", -1, 1025):
            receipt = worker.safe_tool_receipt("skill_manage", {}, {
                "success": True, "operations_applied": invalid, "failed_index": invalid,
                "completed_before_failure": invalid})
            self.assertTrue(receipt["succeeded"])
            for key in ("operations_applied", "failed_index", "completed_before_failure"):
                self.assertNotIn(key, receipt)
        receipt = worker.safe_tool_receipt("skill_manage", {}, {"success": True, "operations_applied": 1})
        self.assertEqual(receipt["operations_applied"], 1)

    def test_malformed_or_unknown_tool_error_has_fixed_category_only(self):
        bad = worker.safe_tool_receipt("skill_view", {}, "private unparseable payload")
        self.assertEqual(bad["error_category"], "invalid_tool_result")
        unknown = worker.safe_tool_receipt("skill_view", {}, {"success": False, "error": "private unknown error"})
        self.assertEqual(unknown["error_category"], "tool_reported_failure")
        self.assertNotIn("private", json.dumps(bad) + json.dumps(unknown))

    def test_partial_failed_or_model_unverified_result_never_completes(self):
        valid = {"completed": True, "partial": False, "interrupted": False,
                 "error": None, "final_response": "Synthetic report"}
        self.assertEqual(worker.validate_result(valid, [worker.MODEL]), "Synthetic report")
        for field, bad in (("completed", False), ("partial", True), ("interrupted", True),
                           ("error", "sensitive provider detail"), ("final_response", "")):
            with self.subTest(field=field), self.assertRaises(worker.PolicyError):
                worker.validate_result({**valid, field: bad}, [worker.MODEL])
        for models in ([], [None], ["gpt-6-astra"], [worker.MODEL, "other"]):
            with self.assertRaisesRegex(worker.PolicyError, "RESPONSE_MODEL_UNVERIFIED"):
                worker.validate_result(valid, models)

    def test_deadline_stops_and_restores_handler(self):
        previous = worker.signal.getsignal(worker.signal.SIGALRM)
        with self.assertRaises(worker.WorkerTimeout):
            with worker.deadline(0.01):
                time.sleep(0.2)
        self.assertEqual(worker.signal.getsignal(worker.signal.SIGALRM), previous)

    def test_repeat_uses_saved_skill_without_injecting_training_contract(self):
        request = {**self.request, "report_contract": ""}
        worker.validate_request(request)
        prompt = worker.build_prompt(request, "repeat")
        self.assertIn("skill_view", prompt)
        self.assertIn(worker.SKILL_NAME, prompt)
        self.assertNotIn("Use validated events", prompt)

    def test_receipt_is_private_and_existing_receipt_is_never_overwritten(self):
        receipt = self.root / "receipt.json"
        worker.write_receipt(receipt, {"completed": False})
        self.assertEqual(receipt.stat().st_mode & 0o777, 0o600)
        with self.assertRaisesRegex(worker.PolicyError, "RECEIPT_ALREADY_EXISTS"):
            worker.write_receipt(receipt, {"completed": True})
        self.assertFalse(json.loads(receipt.read_text())["completed"])

    def test_invalid_receipt_location_does_not_create_failure_file_in_profile(self):
        unsafe_receipt = self.profile / "new-state.json"
        output = io.StringIO()
        args = ["worker", "--profile-dir", str(self.profile), "--request-file", str(self.root / "request.json"),
                "--receipt-file", str(unsafe_receipt), "--phase", "train"]
        with patch.object(worker.sys, "argv", args), patch.object(worker.sys, "stdout", output):
            self.assertEqual(worker.main(), 1)
        self.assertFalse(unsafe_receipt.exists())
        self.assertEqual(json.loads(output.getvalue())["error_code"], "RECEIPT_LOCATION_REFUSED")

    def test_raw_runtime_error_never_enters_receipt_or_stdout(self):
        request_file = self.root / "request.json"
        receipt_file = self.root / "receipt.json"
        self.write_json(request_file, self.request)
        output = io.StringIO()
        args = ["worker", "--profile-dir", str(self.profile), "--request-file", str(request_file),
                "--receipt-file", str(receipt_file), "--phase", "train"]
        with patch.object(worker.sys, "argv", args), patch.dict(os.environ, self.environment, clear=True), \
                patch.object(worker, "quiet_runtime", contextlib_null), \
                patch.object(worker, "run_worker", side_effect=RuntimeError("synthetic-secret-provider-error")), \
                patch.object(worker.sys, "stdout", output):
            self.assertEqual(worker.main(), 1)
        payload = receipt_file.read_text()
        self.assertNotIn("synthetic-secret", payload + output.getvalue())
        self.assertEqual(json.loads(payload)["error_code"], "HERMES_RUNTIME_FAILED")

    def test_timeout_preserves_observed_usage_and_calls_without_completing(self):
        request_file = self.root / "request.json"
        receipt_file = self.root / "timed-out.json"
        self.write_json(request_file, self.request)
        def timed_out(_profile, _request, _phase, observation):
            observation.update(response_models=[worker.MODEL], usage={"total_tokens": 20},
                               tool_calls=[{"name": "skills_list", "succeeded": True}])
            raise worker.WorkerTimeout()
        output = io.StringIO()
        args = ["worker", "--profile-dir", str(self.profile), "--request-file", str(request_file),
                "--receipt-file", str(receipt_file), "--phase", "train"]
        with patch.object(worker.sys, "argv", args), patch.dict(os.environ, self.environment, clear=True), \
                patch.object(worker, "quiet_runtime", contextlib_null), \
                patch.object(worker, "run_worker", side_effect=timed_out), patch.object(worker.sys, "stdout", output):
            self.assertEqual(worker.main(), 1)
        receipt = json.loads(receipt_file.read_text())
        self.assertFalse(receipt["completed"])
        self.assertEqual(receipt["error_code"], "WORKER_TIMEOUT")
        self.assertEqual(receipt["usage"]["total_tokens"], 20)
        self.assertEqual(receipt["response_models"], [worker.MODEL])
        self.assertEqual(len(receipt["tool_calls"]), 1)


def contextlib_null():
    import contextlib
    return contextlib.nullcontext()


if __name__ == "__main__":
    unittest.main()
