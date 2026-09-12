#!/usr/bin/env python3
"""Offline worker-boundary checks; no model, authentication, or Telegram calls."""

import copy
import importlib.util
import json
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


reporter = load("hermes_operations_report_tested", "hermes-operations-report.py")
fixtures = load("hermes_report_test_fixtures", "agent-pilot-fixtures.py")
installer = load("hermes_report_test_installer", "install-hermes-worker.py")


class WorkerBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.parent = Path(self.tmp.name).resolve()
        self.root = self.parent / "worker"
        (self.root / "profile").mkdir(parents=True)
        (self.root / "installation.json").write_text(json.dumps({"commit": installer.COMMIT}))
        self.input = self.parent / "events.json"
        self.input.write_bytes(fixtures.json_bytes(fixtures.fixture("train")))
        self.output = self.parent / "output"
        self.expected = fixtures.derive_report(fixtures.fixture("train"))
        self.final_response = json.dumps(self.expected)
        self.wait_error = None
        self.spawn_error = None
        self.receipt_overrides = {}
        self.mutate_input = False
        self.commands = []
        self.kills = []
        self.wait_count = 0

    def fake_popen(self, command, **options):
        if self.spawn_error is not None:
            raise self.spawn_error
        self.commands.append((command, options))
        receipt = Path(command[command.index("--receipt-file") + 1])
        phase = command[command.index("--phase") + 1]
        receipt.write_text(json.dumps(self.worker_receipt(phase)))
        boundary = self

        class Process:
            pid = 24681
            returncode = None

            def poll(self):
                return self.returncode

            def wait(self, timeout=None):
                boundary.wait_count += 1
                if boundary.wait_count == 1:
                    if boundary.mutate_input:
                        boundary.input.write_bytes(boundary.input.read_bytes() + b" ")
                    if boundary.wait_error is not None:
                        raise boundary.wait_error
                self.returncode = 0
                return 0

        return Process()

    def worker_receipt(self, phase):
        hashes = {reporter.SKILL + "/SKILL.md": "a" * 64}
        receipt = {"completed": True, "final_response": self.final_response,
                   "skills_before": {} if phase == "train" else dict(hashes),
                   "skills_after": dict(hashes),
                   "tool_calls": [{"name": "skill_manage" if phase == "train" else "skill_view",
                                   "succeeded": True}]}
        receipt.update(self.receipt_overrides)
        return receipt

    def run_report(self, **kwargs):
        modules = {"agent-pilot-fixtures.py": fixtures, "install-hermes-worker.py": installer}
        with patch.object(reporter, "module", side_effect=lambda name, filename: modules[filename]), \
                patch.object(reporter.subprocess, "Popen", side_effect=self.fake_popen), \
                patch.object(reporter.os, "killpg", side_effect=lambda pid, sig: self.kills.append((pid, sig))):
            return reporter.run_report(self.input, self.output, root=self.root, **kwargs)

    def verification(self):
        return json.loads((self.output / "verification.json").read_text())

    def test_success_separates_report_verification_from_telegram_delivery(self):
        result = self.run_report(phase="train")
        self.assertTrue(result["ok"])
        self.assertFalse(result["telegramDelivered"])
        self.assertTrue(self.verification()["reportVerified"])
        self.assertFalse(self.verification()["telegramDelivered"])
        self.assertEqual(json.loads((self.output / "report.json").read_text()), self.expected)
        command, options = self.commands[0]
        self.assertTrue(options["start_new_session"])
        self.assertEqual(options["cwd"], self.root / "profile")
        self.assertEqual(options["env"]["CODEX_HOME"], str(self.root / "profile/codex-disabled-import"))
        self.assertEqual(command[0], str(self.root / "runtime/.venv/bin/python"))
        request = json.loads((self.output / "request.json").read_text())
        self.assertEqual(request["report_contract"], fixtures.CONTRACT)

    def test_repeat_uses_saved_procedure_without_resupplying_contract(self):
        self.run_report(phase="repeat")
        request = json.loads((self.output / "request.json").read_text())
        self.assertEqual(request["report_contract"], "")
        self.assertIn("read the saved", request["prompt"])
        self.assertNotIn("schema_version", request["prompt"])

    def test_malformed_model_json_keeps_failed_verification_receipt(self):
        self.final_response = '{"schema_version": 1, this is malformed'
        with self.assertRaises(ValueError):
            self.run_report()
        self.assertFalse(self.verification()["reportVerified"])
        self.assertEqual(self.verification()["exitCode"], 0)
        self.assertFalse((self.output / "report.json").exists())

    def test_duplicate_model_keys_rejected_and_receipt_survives(self):
        self.final_response = '{"schema_version":1,"schema_version":1}'
        with self.assertRaises(ValueError):
            self.run_report()
        self.assertFalse(self.verification()["reportVerified"])
        self.assertFalse((self.output / "report.json").exists())

    def test_boolean_schema_version_cannot_compare_equal_to_integer(self):
        data = copy.deepcopy(self.expected)
        data["schema_version"] = True
        self.final_response = json.dumps(data)
        with self.assertRaisesRegex(ValueError, "independently computed"):
            self.run_report()
        self.assertFalse(self.verification()["reportVerified"])

    def test_tampered_input_during_worker_execution_cannot_verify(self):
        self.mutate_input = True
        with self.assertRaisesRegex(ValueError, "input changed"):
            self.run_report()
        self.assertFalse(self.verification()["reportVerified"])
        self.assertFalse((self.output / "report.json").exists())

    def test_preexisting_output_is_preserved_and_worker_never_starts(self):
        self.output.mkdir()
        sentinel = self.output / "keep.txt"
        sentinel.write_text("preserve this")
        with self.assertRaisesRegex(ValueError, "fresh output"):
            self.run_report()
        self.assertEqual(sentinel.read_text(), "preserve this")
        self.assertEqual(self.commands, [])

    def test_wrong_installation_commit_is_rejected_before_worker_start(self):
        (self.root / "installation.json").write_text('{"commit":"unreviewed"}')
        with self.assertRaisesRegex(ValueError, "unqualified"):
            self.run_report()
        self.assertEqual(self.commands, [])
        self.assertFalse(self.output.exists())

    def test_worker_spawn_failure_keeps_verification_receipt(self):
        self.spawn_error = FileNotFoundError("synthetic missing executable")
        with self.assertRaises(FileNotFoundError):
            self.run_report()
        self.assertFalse(self.verification()["reportVerified"])
        self.assertEqual(self.verification()["errorType"], "FileNotFoundError")
        self.assertFalse((self.output / "report.json").exists())

    def test_correct_report_without_successful_native_skill_read_is_rejected(self):
        self.receipt_overrides = {"tool_calls": [{"name": "skill_view", "succeeded": False}]}
        with self.assertRaisesRegex(ValueError, "procedure reuse"):
            self.run_report()
        self.assertFalse(self.verification()["reportVerified"])

    def test_repeat_cannot_mutate_skill_even_when_report_is_correct(self):
        self.receipt_overrides = {"skills_after": {reporter.SKILL + "/SKILL.md": "b" * 64}}
        with self.assertRaisesRegex(ValueError, "procedure reuse"):
            self.run_report()
        self.assertFalse(self.verification()["reportVerified"])

    def test_training_requires_a_persisted_skill_change(self):
        self.receipt_overrides = {"skills_before": {reporter.SKILL + "/SKILL.md": "a" * 64}}
        with self.assertRaisesRegex(ValueError, "procedure creation"):
            self.run_report(phase="train")
        self.assertFalse(self.verification()["reportVerified"])

    def test_cli_never_echoes_sensitive_duplicate_json_key(self):
        # A local stand-in executable writes a clearly synthetic unit-test
        # receipt. It never imports or invokes the actual Hermes worker/model.
        secret = "synthetic-sensitive-key-do-not-echo"
        self.final_response = json.dumps({secret: 1})[:-1] + ',' + json.dumps(secret) + ':2}'
        fake_python = self.root / "runtime/.venv/bin/python"
        fake_python.parent.mkdir(parents=True)
        fake_python.write_text(
            "#!" + sys.executable + "\n"
            "import json,pathlib,sys\n"
            "target=pathlib.Path(sys.argv[sys.argv.index('--receipt-file')+1])\n"
            "target.write_text(" + repr(json.dumps(self.worker_receipt("repeat"))) + ")\n"
        )
        fake_python.chmod(0o700)
        result = subprocess.run([sys.executable, reporter.__file__, "--input-file", str(self.input),
                                 "--output-dir", str(self.output), "--root", str(self.root)],
                                capture_output=True, text=True, timeout=10, check=False)
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(secret, result.stderr)
        self.assertNotIn(secret, result.stdout)
        self.assertFalse(json.loads(result.stderr)["ok"])
        self.assertFalse(self.verification()["reportVerified"])

    def test_timeout_kills_worker_process_group_and_records_failure(self):
        self.wait_error = subprocess.TimeoutExpired("synthetic-worker", 280)
        with self.assertRaises((subprocess.TimeoutExpired, ValueError)):
            self.run_report()
        self.assertEqual(self.kills, [(24681, signal.SIGKILL)])
        self.assertGreaterEqual(self.wait_count, 2)
        self.assertTrue(self.verification()["timedOut"])
        self.assertFalse(self.verification()["reportVerified"])

    def test_cancellation_kills_worker_group_and_keeps_failure_receipt(self):
        self.wait_error = KeyboardInterrupt()
        with self.assertRaises((KeyboardInterrupt, ValueError)):
            self.run_report()
        self.assertEqual(self.kills, [(24681, signal.SIGKILL)])
        self.assertGreaterEqual(self.wait_count, 2)
        self.assertFalse(self.verification()["reportVerified"])


class OutputParsingTests(unittest.TestCase):
    def test_only_one_complete_json_document_is_accepted(self):
        for text in ('{} trailing text', '{}\n{}', 'prefix {}', '{"n": NaN}'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                reporter.report_from_text(text, fixtures)
        self.assertEqual(reporter.report_from_text('```json\n{"valid":true}\n```', fixtures),
                         {"valid": True})


if __name__ == "__main__":
    unittest.main()
