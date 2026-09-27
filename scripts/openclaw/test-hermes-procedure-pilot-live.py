#!/usr/bin/env python3
"""Offline checks for real-adapter evidence, boundaries and owned cleanup."""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("hermes-procedure-pilot-live.py")
SPEC = importlib.util.spec_from_file_location("hermes_procedure_live_test", SCRIPT)
live = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(live)


class FakeState:
    def __init__(self, root):
        self.root = root
        self.data = {"auth": {"order": {"openai": ["test-oauth"]},
                              "profiles": {"test-oauth": {"provider": "openai", "mode": "oauth"}}}}
        live.PILOT.write_new(root / "openclaw.json", self.data)

    def config(self):
        return self.data

    def runtime_snapshot(self):
        return {"version": "unit-test-runtime"}


class FakeGateway:
    def __init__(self, phase="train", fail=False):
        self.calls = []
        self.phase = phase
        self.fail = fail
        self.key = None
        self.session_id = "unit-test-session"

    def rpc(self, method, params):
        self.calls.append((method, params))
        if method == "models.authStatus":
            return {"providers": [{"provider": "openai", "profiles": [
                {"profileId": "test-oauth", "type": "oauth", "status": "ok"}]}]}
        if method == "sessions.create":
            self.key = params["key"]
            return {"key": self.key, "sessionId": self.session_id, "entry": {
                "incognito": True, "thinkingLevel": "high", "authProfileOverride": "test-oauth"}}
        if method == "agent":
            return {"runId": "unit-test-run"}
        if method == "agent.wait":
            if self.fail:
                raise ValueError("UNIT_TEST_TIMEOUT")
            return {"status": "ok", "endedAt": 100, "terminalReceipt": {
                "rerouted": False, "requested": {"provider": "openai", "model": live.MODEL},
                "effective": {"provider": "openai", "model": live.MODEL}, "successfulToolNames": []},
                "terminalReply": {"disposition": "visible", "text": json.dumps({
                    "procedure": live.PILOT.procedure(),
                    "report": live.PILOT.FIXTURES.derive_report(live.PILOT.events(self.phase))})}}
        if method == "chat.history":
            return {"sessionId": self.session_id, "hasMore": False, "messages": [{"role": "assistant",
                "content": [{"type": "text", "text": "unit test output"}],
                "usage": {"input": 100, "output": 30, "cacheRead": 200, "cacheWrite": 0, "totalTokens": 330}}]}
        if method == "chat.abort":
            return {"ok": True}
        if method == "sessions.delete":
            return {"ok": True, "deleted": True, "archived": []}
        raise AssertionError("unexpected method: " + method)


class LiveTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.parent = Path(self.tmp.name).resolve()
        self.root = self.parent / "pilot"
        live.PILOT.prepare(self.root)
        self.out = self.parent / "output"
        self.out.mkdir(mode=0o700)
        self.state = FakeState(self.parent)

    def test_common_task_is_identical_across_agents_and_holdout_reuses_procedure(self):
        request = live.common_request("train", live.PILOT.procedure(False))
        self.assertEqual(request, live.common_request("train", live.PILOT.procedure(False)))
        self.assertEqual(set(request), {"request_id", "prompt", "report_contract", "events"})
        self.assertNotIn("holdout", json.dumps(request["events"]))
        holdout = live.common_request("holdout", live.PILOT.procedure())
        self.assertIn("unchanged", holdout["prompt"])
        self.assertEqual(holdout["events"], live.PILOT.events("holdout"))

    def test_openclaw_incognito_tool_free_raw_route_and_exact_cleanup(self):
        gateway = FakeGateway()
        output, observation = live.run_openclaw(
            live.common_request("train", live.PILOT.procedure(False)), self.out, gateway, self.state)
        self.assertEqual(output["procedure"], live.PILOT.procedure())
        self.assertEqual(observation["usage"]["noncache_input_tokens"], 100)
        methods = [method for method, _ in gateway.calls]
        self.assertNotIn("config.patch", methods)
        created = next(params for method, params in gateway.calls if method == "sessions.create")
        self.assertTrue(created["incognito"])
        self.assertEqual(created["permissionMode"], "read-only")
        self.assertTrue(created["key"].startswith("agent:main:dashboard:incognito-procedure-pilot-"))
        dispatched = next(params for method, params in gateway.calls if method == "agent")
        self.assertFalse(dispatched["deliver"])
        self.assertTrue(dispatched["modelRun"])
        self.assertEqual(dispatched["promptMode"], "none")
        self.assertNotIn("expectedExistingSessionId", dispatched)
        deleted = next(params for method, params in gateway.calls if method == "sessions.delete")
        self.assertEqual(deleted["key"], created["key"])
        self.assertEqual(deleted["expectedSessionId"], gateway.session_id)
        self.assertTrue(live.PILOT.read_json(self.out / "cleanup.json")["ok"])

    def test_active_failed_run_aborted_and_owned_session_deleted(self):
        gateway = FakeGateway(fail=True)
        with self.assertRaisesRegex(ValueError, "UNIT_TEST_TIMEOUT"):
            live.run_openclaw(live.common_request("train", live.PILOT.procedure(False)),
                             self.out, gateway, self.state)
        methods = [method for method, _ in gateway.calls]
        self.assertLess(methods.index("chat.abort"), methods.index("sessions.delete"))
        self.assertTrue(live.PILOT.read_json(self.out / "cleanup.json")["ok"])

    def test_model_proposal_train_and_holdout_get_real_artifact_lineage_without_native_write(self):
        real_runner = live.run_openclaw
        def observed(request, out):
            phase = "train" if request["events"] == live.PILOT.events("train") else "holdout"
            return real_runner(request, out, FakeGateway(phase), self.state)
        with patch.object(live, "run_openclaw", side_effect=observed):
            training = live.run(self.root, "openclaw", "train")
            self.assertTrue(training["ok"], training)
            result = live.run(self.root, "openclaw", "holdout", training["verification"]["receipt_path"])
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["correction_origin"], "unchanged_model_output_reuse")
        imported, reasons = live.PILOT.validate_run(self.root, result["comparison_envelope"], "openclaw")
        self.assertEqual(imported["status"], "verified")
        self.assertEqual(reasons, [])
        self.assertFalse(result["native_skills_mutated"])

    def test_missing_training_or_invalid_output_does_not_claim_runtime_acceptance(self):
        with patch.object(live, "run_openclaw") as runner:
            result = live.run(self.root, "openclaw", "holdout")
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "TRAINING_RECEIPT_REQUIRED")
        runner.assert_not_called()
        with self.assertRaises(ValueError):
            live.parse_output('{"report":{}}')

    def test_missing_usage_remains_unknown_and_baseline_is_disclosed(self):
        self.assertTrue(all(value is None for value in live.normalize_usage({}, False).values()))
        gateway = FakeGateway()
        _, observation = live.run_openclaw(live.common_request("train", live.PILOT.procedure()),
                                          self.out, gateway, self.state)
        self.assertIn("raw model baseline", observation["context_difference"])

    def test_unavailable_raw_history_preserves_output_with_unknown_metrics(self):
        class MissingHistoryGateway(FakeGateway):
            def rpc(self, method, params):
                if method == "chat.history":
                    raise live.GATEWAY.PilotError("history-unavailable")
                return super().rpc(method, params)
        output, observation = live.run_openclaw(live.common_request("train", live.PILOT.procedure()),
                                                self.out, MissingHistoryGateway(), self.state)
        self.assertEqual(output["procedure"], live.PILOT.procedure())
        self.assertTrue(all(value is None for value in observation["usage"].values()))
        self.assertTrue(live.PILOT.read_json(self.out / "cleanup.json")["ok"])

    def failed_training_attempt(self, error="SYNTHETIC_HISTORY_INVALID"):
        out = self.root / "live/openclaw/train"
        out.mkdir(mode=0o700, parents=True)
        request = live.common_request("train", live.PILOT.procedure(False), {"candidate_rejected": True})
        live.PILOT.write_new(out / "common-request.json", request)
        live.run_openclaw(request, out, FakeGateway(), self.state)
        live.PILOT.write_new(out / "acceptance.json", {"ok": False, "phase": "train", "elapsed_seconds": 10,
                            "comparison_limit": "raw model baseline", "error": error, "error_type": "ValueError",
                            "common_request_sha256": live.PILOT.digest(request)})
        return out

    def test_training_recovery_uses_retained_terminal_without_inference(self):
        self.failed_training_attempt()
        with patch.object(live, "run_openclaw") as inference:
            result = live.recover_training_output(self.root)
        self.assertTrue(result["ok"])
        self.assertEqual(result["new_model_calls"], 0)
        self.assertEqual(result["verification"]["status"], "training_validated")
        self.assertTrue(all(value is None for value in result["usage"].values()))
        inference.assert_not_called()

    def test_recovery_refuses_non_telemetry_failures(self):
        self.failed_training_attempt("OPENCLAW_TOOL_CALL_REFUSED")
        with self.assertRaisesRegex(ValueError, "NON_TELEMETRY_FAILURE_NOT_RECOVERABLE"):
            live.recover_training_output(self.root)

    def test_recovery_refuses_a_request_that_is_not_the_recorded_train_request(self):
        out = self.failed_training_attempt()
        tampered = live.PILOT.read_json(out / "common-request.json")
        tampered["prompt"] += " extra"
        (out / "common-request.json").write_text(json.dumps(tampered))
        with self.assertRaisesRegex(ValueError, "TRAIN_REQUEST_PROVENANCE_INVALID"):
            live.recover_training_output(self.root)

    def test_hermes_calls_must_be_successful_skill_reads_only(self):
        live.verify_read_only_calls([{"name": "skills_list", "succeeded": True},
                                     {"name": "skill_view", "succeeded": True}])
        for calls in ([], None, [{"name": "skill_view", "succeeded": False}],
                      [{"name": "skill_view", "succeeded": True}, {"name": "terminal", "succeeded": True}],
                      [{"name": "skill_view", "succeeded": True}, {"name": "web_fetch", "succeeded": False}],
                      [{"name": "skill_view", "succeeded": True}, "malformed"]):
            with self.subTest(calls=calls), self.assertRaisesRegex(ValueError, "HERMES_READ_ONLY_REUSE_UNVERIFIED"):
                live.verify_read_only_calls(calls)

    def test_hermes_public_skill_gate_rejects_added_content_and_support_files(self):
        profile = self.parent / "profile"
        skill = profile / "skills/operations-event-report/SKILL.md"
        skill.parent.mkdir(mode=0o700, parents=True)
        skill.write_text(live.PUBLIC_SKILL_HEADER + live.PILOT.FIXTURES.CONTRACT)
        proof = live.public_skill_proof(profile)
        self.assertTrue(proof["public_fixture_exact_match"])
        original = skill.read_text()
        skill.write_text(original + "Internal operational content must never be transmitted.\n")
        with self.assertRaisesRegex(ValueError, "NOT_EXACT_PUBLIC_FIXTURE"):
            live.public_skill_proof(profile)
        skill.write_text(original)
        (skill.parent / "private.txt").write_text("test only")
        with self.assertRaisesRegex(ValueError, "PUBLIC_SYNTHETIC_SKILL_ONLY_REQUIRED"):
            live.public_skill_proof(profile)


if __name__ == "__main__":
    unittest.main()
