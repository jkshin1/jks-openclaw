#!/usr/bin/env python3
"""Offline candidate boundaries; opt-in macOS checks exercise the actual sandbox."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("hermes_ops_patches", Path(__file__).with_name("hermes-ops-patches.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
TARGET = "scripts/openclaw/telegram-ops-status.py"


class CandidateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.source = self.root / "source"
        self.source.mkdir()
        files = {TARGET: "def collect():\n    return {'ok': False}\n",
                 "docs/OPENCLAW_TEST.md": "# Synthetic operations\n",
                 "scripts/openclaw/verify-telegram-gateway.py": "# fixed verifier\n",
                 "scripts/openclaw/install-test.py": "# fixed installer\n"}
        files.update({name: "# fixed synthetic test\n" for name in module.FIXED_TESTS})
        for name, text in files.items():
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        self.snapshot = self.root / "snapshot"
        self.manifest = module.create_snapshot(self.source, self.snapshot)
        self.candidate = {"schemaVersion": 1, "snapshotSha256": self.manifest["snapshotSha256"],
                          "replacements": [{"path": TARGET,
                            "beforeSha256": module.digest((self.snapshot / TARGET).read_bytes()),
                            "content": "def collect():\n    return {'ok': True}\n"}]}

    def tearDown(self):
        self.temporary.cleanup()

    def validate(self, candidate=None):
        return module.validate_candidate(self.snapshot, candidate or self.candidate,
                                         expected_snapshot_sha256=self.manifest["snapshotSha256"])

    def mock_check(self, command, candidate_dir, log_path):
        self.assertEqual(command[0], module.PYTHON)
        self.assertEqual(command[1:3], ["-I", "-B"])
        self.assertIn(command[3], module.FIXED_TESTS)
        self.assertNotIn(str(self.source), list(map(str, command)))
        self.assertEqual((candidate_dir / TARGET).read_text(), self.candidate["replacements"][0]["content"])
        self.assertEqual((candidate_dir / command[3]).read_text(), "# fixed synthetic test\n")
        return {"ok": True, "exitCode": 0, "timedOut": False}

    def test_snapshot_contains_only_selected_public_source(self):
        (self.source / "scripts/openclaw/.env").write_text("synthetic-secret")
        (self.source / "scripts/openclaw/auth.json").write_text('{"token":"synthetic-secret"}')
        reports = self.source / "scripts/openclaw/reports"
        reports.mkdir()
        (reports / "private.json").write_text('{"owner":"synthetic"}')
        (self.source / "scripts/openclaw/private.sqlite").write_bytes(b"private-state")
        new = self.root / "second"
        manifest = module.create_snapshot(self.source, new)
        names = {entry["path"] for entry in manifest["files"]}
        self.assertNotIn("scripts/openclaw/.env", names)
        self.assertNotIn("scripts/openclaw/auth.json", names)
        self.assertNotIn("scripts/openclaw/reports/private.json", names)
        self.assertNotIn("scripts/openclaw/private.sqlite", names)
        self.assertEqual(manifest["snapshotSha256"], self.manifest["snapshotSha256"])
        self.assertEqual(new.stat().st_mode & 0o077, 0)

    def test_apply_and_fixed_checks_touch_only_fresh_copy(self):
        before = {name: (self.source / name).read_bytes() for name in [TARGET, *module.FIXED_TESTS]}
        with patch.object(module, "run_check", side_effect=self.mock_check) as runner:
            result = module.evaluate(self.snapshot, self.candidate, self.root / "evaluation")
        self.assertTrue(result["ok"])
        self.assertFalse(result["activated"])
        self.assertEqual(runner.call_count, 5)
        self.assertEqual([call.args[0][-1] for call in runner.call_args_list], list(module.FIXED_TESTS))
        self.assertEqual(before, {name: (self.source / name).read_bytes() for name in before})
        module.verify_snapshot(self.snapshot, self.manifest["snapshotSha256"])

    def test_mutated_original_snapshot_is_rejected_before_output(self):
        (self.snapshot / TARGET).write_text("changed\n")
        output = self.root / "evaluation"
        with self.assertRaisesRegex(ValueError, "SNAPSHOT_SOURCE_CHANGED"):
            module.evaluate(self.snapshot, self.candidate, output)
        self.assertFalse(output.exists())

    def test_recomputed_manifest_cannot_replace_controller_identity(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["files"][0]["size"] += 1
        core = {key: manifest[key] for key in ("schemaVersion", "files")}
        manifest["snapshotSha256"] = module.digest(module.canonical(core))
        (self.snapshot / module.MANIFEST).write_bytes(module.canonical(manifest))
        with self.assertRaisesRegex(ValueError, "SNAPSHOT_IDENTITY_MISMATCH"):
            self.validate()

    def test_bad_base_hash_and_snapshot_id_are_rejected(self):
        for field in ("base", "snapshot"):
            value = copy.deepcopy(self.candidate)
            if field == "base":
                value["replacements"][0]["beforeSha256"] = "0" * 64
            else:
                value["snapshotSha256"] = "0" * 64
            with self.assertRaises(ValueError):
                self.validate(value)

    def test_paths_cannot_escape_or_add_new_files(self):
        for name in ("../owner", "/tmp/owner", "scripts/openclaw/../../owner", "scripts/openclaw\\owner.py",
                     "scripts//openclaw/telegram-ops-status.py", "scripts/openclaw/new.py"):
            value = copy.deepcopy(self.candidate)
            value["replacements"][0]["path"] = name
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.validate(value)

    def test_tests_verifiers_installers_and_unapproved_code_are_immutable(self):
        for name in [*module.FIXED_TESTS, "scripts/openclaw/verify-telegram-gateway.py", "scripts/openclaw/install-test.py"]:
            value = copy.deepcopy(self.candidate)
            value["replacements"][0].update(path=name, beforeSha256=module.digest((self.snapshot / name).read_bytes()))
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "PROTECTED_SOURCE_REFUSED"):
                self.validate(value)

    def test_empty_deleted_malformed_python_or_duplicate_replacement_refused(self):
        for text in ("", "  ", "def broken(:\n", "return 1\n"):
            value = copy.deepcopy(self.candidate)
            value["replacements"][0]["content"] = text
            with self.subTest(text=text), self.assertRaises(ValueError):
                self.validate(value)
        value = copy.deepcopy(self.candidate)
        value["replacements"].append(copy.deepcopy(value["replacements"][0]))
        with self.assertRaisesRegex(ValueError, "DUPLICATE_PATH"):
            self.validate(value)

    def test_docs_and_empty_review_only_candidate_are_valid(self):
        value = copy.deepcopy(self.candidate)
        value["replacements"] = []
        self.assertEqual(self.validate(value), [])
        name = "docs/OPENCLAW_TEST.md"
        value["replacements"] = [{"path": name, "beforeSha256": module.digest((self.snapshot / name).read_bytes()),
                                  "content": "# Updated synthetic runbook\n"}]
        self.assertEqual(self.validate(value)[0]["path"], name)

    def test_strict_json_and_schema_reject_duplicate_keys_bool_and_unknown_fields(self):
        path = self.root / "duplicate.json"
        path.write_text('{"content":"first","content":"hidden"}')
        with self.assertRaisesRegex(ValueError, "DUPLICATE_JSON_KEY"):
            module.read_json(path)
        for update in ({"schemaVersion": True}, {"shellCommand": "touch owner"}):
            value = {**self.candidate, **update}
            with self.assertRaisesRegex(ValueError, "INVALID_CANDIDATE_SCHEMA"):
                self.validate(value)

    def test_symlink_source_snapshot_or_output_refused(self):
        original = self.snapshot / TARGET
        original.unlink()
        original.symlink_to(self.source / TARGET)
        with self.assertRaisesRegex(ValueError, "SYMLINK_REFUSED"):
            self.validate()
        output = self.root / "redirected"
        output.symlink_to(self.source, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "SYMLINK_REFUSED"):
            module.create_snapshot(self.source, output)

    def test_existing_or_nested_output_refused(self):
        for output in (self.snapshot, self.snapshot / "nested", self.root, self.source / "unsafe-evaluation"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                module.evaluate(self.snapshot, self.candidate, output)

    def test_failing_check_produces_reviewable_failure_receipt(self):
        with patch.object(module, "run_check", return_value={"ok": False, "exitCode": 1, "timedOut": False}):
            result = module.evaluate(self.snapshot, self.candidate, self.root / "evaluation")
        self.assertFalse(result["ok"])
        self.assertEqual(len(result["checks"]), 5)
        self.assertFalse(result["activated"])
        self.assertTrue((self.root / "evaluation/patch-verification.json").is_file())

    def test_check_cannot_silently_rewrite_candidate_or_original(self):
        def mutate(command, candidate_dir, log_path):
            (candidate_dir / TARGET).write_text("# tampered\n")
            return {"ok": True, "exitCode": 0, "timedOut": False}
        with patch.object(module, "run_check", side_effect=mutate):
            result = module.evaluate(self.snapshot, self.candidate, self.root / "evaluation")
        self.assertFalse(result["ok"])
        self.assertEqual(result["errorType"], "ValueError")
        module.verify_snapshot(self.snapshot, self.manifest["snapshotSha256"])

    def test_runtime_missing_is_fail_closed(self):
        with patch.object(module, "SANDBOX", self.root / "missing"):
            with self.assertRaisesRegex(ValueError, "SANDBOX_RUNTIME_UNAVAILABLE"):
                module.evaluate(self.snapshot, self.candidate, self.root / "evaluation")
        self.assertFalse((self.root / "evaluation").exists())


@unittest.skipUnless(os.environ.get("HERMES_OPS_SANDBOX_TESTS") == "1", "set HERMES_OPS_SANDBOX_TESTS=1 on macOS")
class MacSandboxTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.candidate = self.root / "candidate"
        self.candidate.mkdir()
        for name in ("home", "tmp"):
            (self.candidate / ".check-state" / name).mkdir(parents=True)

    def tearDown(self):
        self.temporary.cleanup()

    def execute(self, code, timeout=5):
        return module.run_check([module.PYTHON, "-I", "-B", "-c", code], self.candidate,
                                self.root / "check.log", timeout=timeout)

    def test_real_sandbox_blocks_external_read_write_and_network(self):
        secret = self.root / "synthetic-private.txt"
        secret.write_text("synthetic private value")
        escaped = self.root / "outside-write"
        volume_alias = Path("/System/Volumes/Data" + str(secret))
        self.assertTrue(volume_alias.is_file(), "macOS Data-volume alias fixture unavailable")
        code = """import errno, pathlib, socket
for operation in [lambda: pathlib.Path(%r).read_text(), lambda: pathlib.Path(%r).write_text('bad'),
                  lambda: pathlib.Path(%r).read_text(),
                  lambda: socket.create_connection(('127.0.0.1', 9), timeout=1)]:
    try:
        operation()
    except OSError as error:
        assert error.errno in (errno.EPERM, errno.EACCES), repr(error)
    else:
        raise AssertionError('sandbox boundary failed')
pathlib.Path('allowed.txt').write_text('synthetic')
""" % (str(secret), str(escaped), str(volume_alias))
        result = self.execute(code)
        self.assertTrue(result["ok"], (self.root / "check.log").read_text())
        self.assertFalse(escaped.exists())
        self.assertEqual((self.candidate / "allowed.txt").read_text(), "synthetic")

    def test_output_is_bounded(self):
        result = self.execute("print('x' * 300000)")
        self.assertTrue(result["ok"])
        self.assertTrue(result["outputTruncated"])
        self.assertLessEqual((self.root / "check.log").stat().st_size, module.MAX_LOG)

    def test_timeout_kills_descendant_before_it_can_write(self):
        child = "import time,pathlib;time.sleep(1);pathlib.Path('escaped-child').write_text('bad')"
        result = self.execute("import subprocess,sys,time;subprocess.Popen([sys.executable,'-I','-B','-c',%r]);time.sleep(5)" % child,
                              timeout=0.2)
        self.assertTrue(result["timedOut"])
        time.sleep(1.1)
        self.assertFalse((self.candidate / "escaped-child").exists())


if __name__ == "__main__":
    unittest.main()
