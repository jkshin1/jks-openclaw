#!/usr/bin/env python3
"""Offline checks of the summary adapter's authority and terminal-result boundary."""

import copy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
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
                         {"status": "ok", "result": {"payloads": [{"text": "요약 결과"}]},
                          "route": {"provider": "openai", "model": "gpt-5.6-sol", "fallback": False}})
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


class OpusFallbackTest(unittest.TestCase):
    def claude_result(self, **changes):
        result = {"is_error": False, "num_turns": 1, "permission_denials": [], "result": "오퍼스 요약",
                  "modelUsage": {"claude-opus-5-5": {"inputTokens": 1}}}
        result.update(changes)
        return json.dumps(result)

    def run_main(self, rpc_effects, claude_stdout, claude_code=0):
        output = io.StringIO()
        calls = []

        def runner(command, **kwargs):
            calls.append((command, kwargs))
            return subprocess.CompletedProcess(command, claude_code, claude_stdout, "")
        with patch.object(summary.sys, "argv", ["summary", "agent", "-m", "synthetic text"]), \
                patch.object(summary, "rpc", side_effect=rpc_effects) as rpc, \
                patch.object(summary.sys, "stdout", output), \
                patch.object(summary.subprocess, "run", side_effect=runner):
            summary.main()
        return json.loads(output.getvalue()), rpc, calls

    def test_codex_usage_limit_falls_back_to_tool_free_opus_and_still_deletes_session(self):
        limit = summary.UsageLimit("OpenClaw agent hit a usage limit")
        result, rpc, calls = self.run_main([{}, limit, {}], self.claude_result())
        self.assertEqual(result["result"]["payloads"], [{"text": "오퍼스 요약"}])
        self.assertEqual(result["route"], {"provider": "claude-cli", "model": "claude-opus-5-5",
                                           "fallback": True, "reason": "usage-limit"})
        self.assertEqual([call.args[0] for call in rpc.call_args_list], ["sessions.create", "agent", "sessions.delete"])
        command, kwargs = calls[0]
        self.assertEqual(command[command.index("--tools") + 1], "")
        for flag in ("--strict-mcp-config", "--safe-mode", "--no-session-persistence"):
            self.assertIn(flag, command)
        self.assertEqual(kwargs["input"], "synthetic text")
        self.assertNotIn("synthetic text", command)
        self.assertNotIn("ANTHROPIC_API_KEY", kwargs["env"])

    def test_limit_reported_by_the_run_itself_also_falls_back(self):
        failed = {"status": "error", "endedAt": 10, "error": "⚠️ API rate limit reached. Please try again later."}
        result, rpc, _calls = self.run_main([{}, {}, failed, {}], self.claude_result())
        self.assertTrue(result["route"]["fallback"])
        self.assertEqual(rpc.call_args_list[-1].args[0], "sessions.delete")

    def test_rpc_limit_text_is_classified_but_other_failures_are_not(self):
        for text, expected in (("API rate limit reached", summary.UsageLimit),
                               ("You've hit your usage limit", summary.UsageLimit),
                               ("model not allowed", RuntimeError)):
            with self.subTest(text=text), patch.object(summary.subprocess, "run",
                    return_value=subprocess.CompletedProcess([], 1, text, "")):
                with self.assertRaises(RuntimeError) as caught:
                    summary.rpc("agent", {})
                self.assertIs(type(caught.exception), expected)

    def test_non_limit_failure_never_uses_the_fallback(self):
        with patch.object(summary.sys, "argv", ["summary", "agent", "-m", "synthetic text"]), \
                patch.object(summary, "rpc", side_effect=[{}, RuntimeError("route rejected"), {}, {}]), \
                patch.object(summary.subprocess, "run") as run:
            with self.assertRaises(RuntimeError):
                summary.main()
        run.assert_not_called()

    def test_fallback_rejects_tool_turns_other_models_and_errors(self):
        for changes in ({"num_turns": 2}, {"permission_denials": [{"tool_name": "Bash"}]},
                        {"modelUsage": {"claude-opus-5-5": {}, "claude-haiku-4-5": {}}}, {"is_error": True},
                        {"result": ""}):
            with self.subTest(changes=changes):
                with self.assertRaises(RuntimeError):
                    summary.fallback_text(self.claude_result(**changes))
        with self.assertRaises(RuntimeError):
            self.run_main([{}, summary.UsageLimit("limit"), {}], self.claude_result(), claude_code=1)


if __name__ == "__main__":
    unittest.main()
