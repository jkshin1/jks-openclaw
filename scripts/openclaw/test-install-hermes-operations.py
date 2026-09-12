#!/usr/bin/env python3
"""Offline transactional installer checks using synthetic state and no live runtime."""

import fcntl
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch


SOURCE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("tested_ops_installer", SOURCE / "install-hermes-operations.py")
installer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(installer)


class InstallTests(unittest.TestCase):
    def setUp(self):
        previous_umask = os.umask(0o077)
        self.addCleanup(os.umask, previous_umask)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.source, self.root, self.state = (self.base / name for name in ("source", "worker", "state"))
        for directory in (self.source / "templates", self.root / "bin", self.root / "profile",
                          self.root / "profile/codex-disabled-import", self.state / "workspace"):
            directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        for name in installer.INSTALL_FILES + installer.PRESERVED_FILES:
            shutil.copyfile(SOURCE / name, self.source / name)
        for name in installer.PRESERVED_FILES:
            self.write(self.root / "bin" / name, (self.source / name).read_bytes())
        self.write(self.source / "templates/HERMES_OPERATIONS_SKILL.md",
                   b"---\nname: hermes-operations\nuser-invocable: true\n---\nSynthetic skill.\n")
        self.write(self.source / "templates/HERMES_OPS_RUNBOOK.md",
                   b"---\nname: openclaw-ops-runbook\n---\nSynthetic operational procedure.\n")
        self.report = installer.load_module(self.source / "hermes-report-worker.py")
        self.original_installer = installer.load_module(self.source / "install-hermes-worker.py")
        self.ops_worker = installer.load_module(self.source / "hermes-ops-worker.py")
        self.auth = {"active_provider": "openai-codex", "providers": {}, "credential_pool": {
            "openai-codex": [{"id": "synthetic-independent-grant", "auth_type": "oauth",
                             "source": "manual:device_code", "access_token": "synthetic-access-only",
                             "refresh_token": "synthetic-refresh-only"}]}}
        self.write(self.root / "profile/auth.json", installer.json_bytes(self.auth))
        self.write(self.root / "profile/config.yaml", installer.json_bytes(self.report.REQUIRED_CONFIG))
        self.write(self.root / "installation.json", installer.json_bytes({"commit": self.original_installer.COMMIT}))
        self.write(self.state / "openclaw.json", b'{"gateway":{"mode":"local"},"synthetic":true}\n')
        self.write(self.state / "workspace/OWNER_SENTINEL.md", b"Keep unrelated workspace.\n")
        self.protected = {path: path.read_bytes() for path in (
            self.root / "profile/auth.json", self.root / "profile/config.yaml",
            self.state / "openclaw.json", self.state / "workspace/OWNER_SENTINEL.md",
            *(self.root / "bin" / name for name in installer.PRESERVED_FILES))}
        self.child = self.root / "profile/profiles/operations"

    @staticmethod
    def write(path, data):
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        path.write_bytes(data)
        path.chmod(0o600)

    def run_install(self):
        return installer.install(self.source, self.root, self.state)

    def assert_protected(self):
        for path, data in self.protected.items():
            self.assertEqual(path.read_bytes(), data, str(path))

    def plan(self):
        paths = sorted((self.root / "operations-install-backups").glob("*/transaction.json"))
        self.assertTrue(paths)
        return json.loads(paths[-1].read_text())

    def test_success_installs_exact_bundle_and_one_skill_without_copying_auth(self):
        result = self.run_install()
        self.assertTrue(result["ok"])
        self.assertFalse(result["modelCalled"] or result["gatewayRestarted"] or result["telegramDelivered"])
        self.assertFalse(result["scheduleCreated"])
        self.assert_protected()
        for name in installer.INSTALL_FILES:
            installed = self.root / "bin" / name
            self.assertEqual(installed.read_bytes(), (self.source / name).read_bytes())
            self.assertEqual(installed.stat().st_mode & 0o777, 0o600)
        self.assertEqual(json.loads((self.child / "config.yaml").read_text()), self.ops_worker.REQUIRED_CONFIG)
        self.assertEqual(list((self.child / "codex-disabled-import").iterdir()), [])
        self.assertFalse((self.child / "auth.json").exists())
        self.assertEqual([p.name for p in (self.state / "workspace/skills").iterdir()], ["hermes-operations"])
        self.assertEqual(self.plan()["status"], "COMMITTED")
        for path in (self.root / "operations-install-backups").rglob("*"):
            if path.is_file():
                self.assertNotIn(b"synthetic-refresh-only", path.read_bytes())
        self.assertEqual(Path(self.plan()["openclawConfigBackup"]).read_bytes(), self.protected[self.state / "openclaw.json"])

    def test_reinstall_preserves_learned_runbook_and_parent_state(self):
        self.run_install()
        learned = self.child / "skills/openclaw-ops-runbook/SKILL.md"
        self.write(learned, b"Verified procedure with a new learned precondition.\n")
        self.run_install()
        self.assertEqual(learned.read_bytes(), b"Verified procedure with a new learned precondition.\n")
        self.assert_protected()

    def test_managed_contract_upgrade_preserves_learned_procedure_and_is_idempotent(self):
        self.run_install()
        learned = self.child / "skills/openclaw-ops-runbook/SKILL.md"
        self.write(learned, b"Verified custom procedure.\n")
        seed = (b"Seed\n" + installer.CONTRACT_START + b"\nEvidence contract revision 2\n"
                + installer.CONTRACT_END + b"\n")
        self.write(self.source / "templates/HERMES_OPS_RUNBOOK.md", seed)
        self.run_install()
        self.assertTrue(learned.read_bytes().startswith(b"Verified custom procedure."))
        self.assertIn(b"Evidence contract revision 2", learned.read_bytes())
        before = learned.read_bytes()
        self.run_install()
        self.assertEqual(learned.read_bytes(), before)
        self.assert_protected()

    def test_malformed_managed_contract_is_refused_before_writing(self):
        self.run_install()
        learned = self.child / "skills/openclaw-ops-runbook/SKILL.md"
        old = learned.read_bytes()
        self.write(self.source / "templates/HERMES_OPS_RUNBOOK.md", installer.CONTRACT_START)
        with self.assertRaisesRegex(installer.InstallError, "RUNBOOK_CONTRACT_INVALID"):
            self.run_install()
        self.assertEqual(learned.read_bytes(), old)
        self.assert_protected()

    def test_existing_dependency_drift_is_rejected_without_installing(self):
        self.write(self.root / "bin/hermes-report-worker.py", b"# unrelated changed worker\n")
        with self.assertRaisesRegex(installer.InstallError, "PRESERVED_DEPENDENCY_MISMATCH"):
            self.run_install()
        self.assertFalse(self.child.exists())
        self.assertFalse((self.root / "operations-install.json").exists())

    def test_an_active_report_worker_blocks_installer(self):
        lock_path = self.root / "profile/.pilot-worker.lock"
        with lock_path.open("a") as lock:
            lock_path.chmod(0o600)
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaisesRegex(installer.InstallError, "WORKER_BUSY"):
                self.run_install()
        self.assertFalse(self.child.exists())
        self.assert_protected()

    def test_child_auth_copy_is_rejected_before_installing(self):
        self.write(self.child / "auth.json", b"{}\n")
        with self.assertRaisesRegex(installer.InstallError, "CHILD_AUTH_REFUSED"):
            self.run_install()
        self.assertEqual((self.child / "auth.json").read_bytes(), b"{}\n")
        self.assertFalse((self.root / "operations-install.json").exists())

    def test_provider_singleton_is_rejected_even_with_matching_pool_pair(self):
        self.auth["providers"]["openai-codex"] = {"auth_mode": "chatgpt", "tokens": {
            "access_token": "synthetic-access-only", "refresh_token": "synthetic-refresh-only"}}
        self.write(self.root / "profile/auth.json", installer.json_bytes(self.auth))
        with self.assertRaisesRegex(installer.InstallError, "SHARED_OAUTH_SINGLETON_REFUSED"):
            self.run_install()
        self.assertFalse(self.child.exists())

    def test_symlinked_target_cannot_modify_unrelated_file(self):
        other = self.base / "untouched.py"
        self.write(other, b"# unrelated\n")
        (self.root / "bin/hermes-operations.py").symlink_to(other)
        with self.assertRaisesRegex(installer.InstallError, "SYMLINK_REFUSED"):
            self.run_install()
        self.assertEqual(other.read_bytes(), b"# unrelated\n")
        self.assert_protected()

    def test_hardlinked_target_is_rejected(self):
        other = self.base / "untouched.py"
        self.write(other, b"# unrelated\n")
        os.link(other, self.root / "bin/hermes-operations.py")
        with self.assertRaisesRegex(installer.InstallError, "REGULAR_SINGLE_LINK_FILE_REQUIRED"):
            self.run_install()
        self.assertEqual(other.read_bytes(), b"# unrelated\n")

    def test_mid_install_failure_restores_prior_file_and_removes_only_new_files(self):
        target = self.root / "bin/hermes-operations.py"
        self.write(target, b"# previous installed controller\n")
        target.chmod(0o400)
        original_write = installer.atomic_write

        def fail_once(path, data, mode=0o600):
            if path == self.root / "bin/hermes-ops-patches.py":
                raise OSError("synthetic disk failure")
            return original_write(path, data, mode)

        with patch.object(installer, "atomic_write", side_effect=fail_once):
            with self.assertRaises(OSError):
                self.run_install()
        self.assertEqual(target.read_bytes(), b"# previous installed controller\n")
        self.assertEqual(target.stat().st_mode & 0o777, 0o400)
        self.assertFalse((self.root / "bin/hermes-ops-worker.py").exists())
        self.assertFalse((self.root / "operations-install.json").exists())
        self.assertFalse(self.child.exists())
        self.assertEqual(self.plan()["status"], "ROLLED_BACK")
        self.assert_protected()

    def test_post_write_corruption_does_not_claim_success_or_overwrite_external_bytes(self):
        target = self.root / "bin/hermes-operations.py"
        original_write = installer.atomic_write

        def corrupt(path, data, mode=0o600):
            original_write(path, data, mode)
            if path == target:
                path.write_bytes(b"# concurrent different bytes\n")

        with patch.object(installer, "atomic_write", side_effect=corrupt):
            with self.assertRaisesRegex(installer.InstallError, "ROLLBACK_CONFLICT"):
                self.run_install()
        self.assertEqual(target.read_bytes(), b"# concurrent different bytes\n")
        self.assertFalse((self.root / "operations-install.json").exists())
        self.assertEqual(self.plan()["status"], "ROLLBACK_CONFLICT")
        self.assertIn(str(target), self.plan()["conflicts"])
        self.assert_protected()

    def test_rollback_restores_mode_when_existing_bytes_already_match_source(self):
        target = self.root / "bin/hermes-operations.py"
        self.write(target, (self.source / "hermes-operations.py").read_bytes())
        target.chmod(0o400)
        original_write = installer.atomic_write

        def fail_once(path, data, mode=0o600):
            if path == self.root / "bin/hermes-ops-patches.py":
                raise OSError("synthetic disk failure")
            return original_write(path, data, mode)

        with patch.object(installer, "atomic_write", side_effect=fail_once):
            with self.assertRaises(OSError):
                self.run_install()
        self.assertEqual(target.read_bytes(), (self.source / "hermes-operations.py").read_bytes())
        self.assertEqual(target.stat().st_mode & 0o777, 0o400)
        self.assertEqual(self.plan()["status"], "ROLLED_BACK")

    def test_final_worker_policy_failure_rolls_back_all_published_files(self):
        self.write(self.child / "skills/unexpected/SKILL.md", b"Unexpected preexisting skill.\n")
        with self.assertRaisesRegex(ValueError, "SKILL_SCOPE_VIOLATION"):
            self.run_install()
        self.assertFalse((self.root / "operations-install.json").exists())
        self.assertFalse((self.root / "bin/hermes-operations.py").exists())
        self.assertEqual((self.child / "skills/unexpected/SKILL.md").read_bytes(), b"Unexpected preexisting skill.\n")
        self.assertEqual(self.plan()["status"], "ROLLED_BACK")
        self.assert_protected()

    def test_external_config_change_is_preserved_and_installed_files_are_rolled_back(self):
        config = self.state / "openclaw.json"
        original_write = installer.atomic_write

        def change_config(path, data, mode=0o600):
            original_write(path, data, mode)
            if path == self.root / "bin/hermes-ops-worker.py":
                self.write(config, b'{"concurrentChange":true}\n')

        with patch.object(installer, "atomic_write", side_effect=change_config):
            with self.assertRaisesRegex(installer.InstallError, "PROTECTED_STATE_CHANGED"):
                self.run_install()
        self.assertEqual(config.read_bytes(), b'{"concurrentChange":true}\n')
        self.assertFalse((self.root / "operations-install.json").exists())
        self.assertFalse((self.root / "bin/hermes-operations.py").exists())
        self.assertEqual(self.plan()["status"], "ROLLED_BACK")


if __name__ == "__main__":
    unittest.main()
