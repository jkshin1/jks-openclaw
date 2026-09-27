#!/usr/bin/env python3
"""Offline checks of the response-contract smoke: explicit checks and fail-closed cleanup."""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("smoke", HERE / "smoke-response-contract.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)

EXPECTED = {'progress_tool': 'message', 'progress_final': False, 'image_wait_tool': None,
            'image_accepted_turn_end': 'NO_REPLY', 'completion_tool': 'message',
            'completion_structured_attachments': True, 'regenerate_on_send_failure': False,
            'terminal_send_final': True, 'handoff_send_final': True, 'tools_after_final_send': False}


class SmokeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        state = Path(self.temp.name)
        (state / "operations").mkdir()
        for name, value in (("STATE", state), ("session_entry", lambda key: None)):
            patcher = patch.object(smoke.rpc_helpers, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.calls = []

    def fake_rpc(self, answer, rerouted=False, fail=()):
        def rpc(method, params):
            self.calls.append(method)
            if method in fail:
                raise RuntimeError(method + " failed")
            if method == "agent":
                return {"runId": "run-1"}
            if method == "agent.wait":
                return {"status": "ok", "endedAt": 1, "terminalReply": {"text": json.dumps(answer)},
                        "terminalReceipt": {"successfulToolNames": [], "rerouted": rerouted,
                                            "effective": {"provider": "anthropic"}}}
            return {}
        return rpc

    def run_smoke(self, rpc, argv=()):
        with patch.object(smoke, "rpc", rpc), patch("sys.stdout"):
            code = smoke.main(list(argv))
        receipt = json.loads(next(Path(self.temp.name, "operations").glob("*/receipt.json")).read_text())
        return code, receipt

    def test_default_model_is_the_owner_main_route(self):
        code, receipt = self.run_smoke(self.fake_rpc(EXPECTED))
        self.assertEqual(code, 0)
        self.assertEqual(receipt["requestedModel"], "anthropic/claude-opus-5-5")
        self.assertTrue(receipt["syntheticSessionDeleted"])

    def test_contract_mismatch_fails_even_under_optimized_python(self):
        # Run the real script body under -O with the checks that used to be assert statements.
        program = ("import importlib.util,sys;"
                   "s=importlib.util.spec_from_file_location('m',sys.argv[1]);m=importlib.util.module_from_spec(s);"
                   "s.loader.exec_module(m);m.require(False,'Response contract mismatch')")
        result = subprocess.run([sys.executable, "-O", "-c", program, str(HERE / "smoke-response-contract.py")],
                                capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Response contract mismatch", result.stderr)

    def test_reroute_is_a_failure(self):
        code, receipt = self.run_smoke(self.fake_rpc(EXPECTED, rerouted=True))
        self.assertEqual(code, 1)
        self.assertFalse(receipt["ok"])

    def test_delete_error_is_recorded_but_confirmed_absence_still_passes(self):
        code, receipt = self.run_smoke(self.fake_rpc(EXPECTED, fail=("sessions.delete",)))
        self.assertEqual(code, 0)
        self.assertEqual(receipt["cleanupErrors"], ["delete:RuntimeError"])
        self.assertTrue(receipt["syntheticSessionDeleted"])

    def test_unconfirmed_session_delete_fails_a_passing_contract(self):
        with patch.object(smoke.rpc_helpers, "session_entry", lambda key: {"key": key}):
            code, receipt = self.run_smoke(self.fake_rpc(EXPECTED))
        self.assertEqual(code, 1)
        self.assertFalse(receipt["syntheticSessionDeleted"])
        self.assertEqual(receipt["errorType"], "CleanupUnconfirmed")

if __name__ == "__main__":
    unittest.main()
