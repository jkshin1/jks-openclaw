#!/usr/bin/env python3
"""Offline checks of the summary adapter's authority and terminal-result boundary."""

import copy
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("summary", Path(__file__).with_name("summarize-openclaw.py"))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class SummaryBoundaryTest(unittest.TestCase):
    def good_result(self):
        route = {"provider": "openai", "model": "gpt-5.6-sol"}
        return {"status": "ok", "endedAt": 10,
                "terminalReceipt": {"requested": route,
                                    "effective": {**route, "responseModel": "gpt-5.6-sol"},
                                    "rerouted": False, "successfulToolNames": []},
                "terminalReply": {"disposition": "visible", "text": "요약 결과"}}

    def test_request_is_tool_free_and_has_no_delivery(self):
        request = summary.model_request("synthetic text", "temporary-key", "unique-run", 90)
        self.assertNotIn("model", request)
        self.assertNotIn("provider", request)
        self.assertEqual(request["thinking"], "low")
        self.assertIs(request["modelRun"], True)
        self.assertEqual(request["promptMode"], "none")
        self.assertIs(request["deliver"], False)
        self.assertEqual(request["sessionKey"], "temporary-key")
        self.assertEqual(request["idempotencyKey"], "unique-run")

    def test_only_successful_visible_tool_free_answer_is_accepted(self):
        good = self.good_result()
        self.assertEqual(summary.completed_text(good), "요약 결과")
        for path, value in [("status", "timeout"), ("endedAt", None),
                            ("terminalReceipt.successfulToolNames", ["exec"]),
                            ("terminalReceipt.successfulToolNames", None),
                            ("terminalReceipt.rerouted", True),
                            ("terminalReceipt.rerouted", None),
                            ("terminalReceipt.requested.provider", "openrouter"),
                            ("terminalReceipt.effective.provider", "openrouter"),
                            ("terminalReceipt.requested.model", "gpt-6-astra"),
                            ("terminalReceipt.effective.model", "z-ai/glm-5.3-flash"),
                            ("terminalReceipt.effective.responseModel", "other-model"),
                            ("terminalReply.disposition", "hidden"), ("terminalReply.text", "")]:
            with self.subTest(path=path):
                bad = copy.deepcopy(good)
                parts = path.split(".")
                parent = bad
                for part in parts[:-1]:
                    parent = parent[part]
                parent[parts[-1]] = value
                with self.assertRaises(RuntimeError):
                    summary.completed_text(bad)

    def test_missing_route_evidence_is_rejected(self):
        for field in ("terminalReceipt", "terminalReceipt.requested", "terminalReceipt.effective",
                      "terminalReceipt.effective.responseModel", "terminalReceipt.rerouted",
                      "terminalReceipt.successfulToolNames"):
            with self.subTest(field=field):
                bad = self.good_result()
                parts = field.split(".")
                parent = bad
                for part in parts[:-1]:
                    parent = parent[part]
                del parent[parts[-1]]
                with self.assertRaises(RuntimeError):
                    summary.completed_text(bad)

    def test_success_deletes_incognito_session_before_returning_answer(self):
        output = io.StringIO()
        with patch.object(summary.sys, "argv", ["summary", "agent", "-m", "synthetic text"]), \
                patch.object(summary, "rpc", side_effect=[{}, {}, self.good_result(), {}]) as rpc, \
                patch.object(summary.sys, "stdout", output):
            summary.main()
        self.assertEqual(json.loads(output.getvalue()),
                         {"status": "ok", "result": {"payloads": [{"text": "요약 결과"}]}})
        self.assertEqual([call.args[0] for call in rpc.call_args_list],
                         ["sessions.create", "agent", "agent.wait", "sessions.delete"])
        created = rpc.call_args_list[0].args[1]
        request = rpc.call_args_list[1].args[1]
        deleted = rpc.call_args_list[-1].args[1]
        self.assertIs(created["incognito"], True)
        self.assertEqual(created["model"], "openai/gpt-5.6-sol")
        self.assertEqual(created["thinkingLevel"], "low")
        self.assertTrue(created["key"].startswith("agent:main:dashboard:incognito-summarize-"))
        self.assertEqual(request["sessionKey"], created["key"])
        self.assertEqual(deleted, {"key": created["key"], "agentId": "main",
                                  "deleteTranscript": True, "emitLifecycleHooks": False})

    def test_route_rejection_still_deletes_session_without_output(self):
        rejected = self.good_result()
        rejected["terminalReceipt"]["effective"]["provider"] = "openrouter"
        output = io.StringIO()
        with patch.object(summary.sys, "argv", ["summary", "agent", "-m", "synthetic text"]), \
                patch.object(summary, "rpc", side_effect=[{}, {}, rejected, {}]) as rpc, \
                patch.object(summary.sys, "stdout", output):
            with self.assertRaises(RuntimeError):
                summary.main()
        self.assertEqual(output.getvalue(), "")
        self.assertEqual([call.args[0] for call in rpc.call_args_list],
                         ["sessions.create", "agent", "agent.wait", "sessions.delete"])

    def test_wait_failure_aborts_and_deletes_the_same_session(self):
        with patch.object(summary.sys, "argv", ["summary", "agent", "-m", "synthetic text"]), \
                patch.object(summary, "rpc", side_effect=[{}, {}, RuntimeError("wait failed"), {}, {}]) as rpc:
            with self.assertRaises(RuntimeError):
                summary.main()
        self.assertEqual([call.args[0] for call in rpc.call_args_list],
                         ["sessions.create", "agent", "agent.wait", "chat.abort", "sessions.delete"])
        request = rpc.call_args_list[1].args[1]
        aborted = rpc.call_args_list[-2].args[1]
        deleted = rpc.call_args_list[-1].args[1]
        self.assertEqual(aborted, {"sessionKey": request["sessionKey"],
                                   "runId": request["idempotencyKey"]})
        self.assertEqual(deleted["key"], request["sessionKey"])


if __name__ == "__main__":
    unittest.main()
