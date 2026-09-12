#!/usr/bin/env python3
"""Install disposable bundles only; exercise filesystem failure and rollback boundaries."""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("workflow_installer", Path(__file__).with_name("install-telegram-workflows.py"))
installer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(installer)


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.source = self.root / "source"
        self.source.mkdir()
        for name in installer.FILES:
            (self.source / name).write_text("VALUE = 'reviewed new bundle'\n")
        self.destination = self.root / "installed"
        self.state = self.root / "state"
        self.latest = self.state / "operations/telegram-workflows-install-latest.json"

    def old_install(self):
        self.destination.mkdir()
        for name in installer.FILES:
            (self.destination / name).write_text("VALUE = 'previous bundle'\n")
        (self.destination / "retained-owner-note.txt").write_text("retain these original bytes")
        self.latest.parent.mkdir(parents=True)
        installer.atomic_bytes(self.latest, b'{"status":"OLD_RECEIPT"}\n')
        return {path.name: path.read_bytes() for path in self.destination.iterdir()}, self.latest.read_bytes()

    def backup(self):
        return next((self.state / "operations").glob("telegram-workflow-install-*"))

    def assert_old_preserved(self, expected):
        files, latest = expected
        self.assertEqual({path.name: path.read_bytes() for path in self.destination.iterdir()}, files)
        self.assertEqual(self.latest.read_bytes(), latest)

    def test_fresh_install_private_files_and_receipts(self):
        result = installer.install(self.source, self.destination, self.state)
        self.assertEqual(result["status"], "INSTALLED")
        self.assertFalse(result["messagesSent"])
        self.assertFalse(result["schedulesCreated"])
        self.assertEqual(set(result["files"]), set(installer.FILES))
        for name in installer.FILES:
            target = self.destination / name
            self.assertEqual(target.read_bytes(), (self.source / name).read_bytes())
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)
        for path in (self.latest, Path(result["backup"]) / "receipt.json"):
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(json.loads(path.read_text()), result)

    def test_replacement_keeps_previous_bundle_and_extra_owner_file(self):
        original, previous_latest = self.old_install()
        result = installer.install(self.source, self.destination, self.state)
        backup = Path(result["backup"])
        self.assertEqual({path.name: path.read_bytes() for path in (backup / "previous").iterdir()}, original)
        self.assertEqual((backup / "previous-latest.json").read_bytes(), previous_latest)
        self.assertEqual(set(path.name for path in self.destination.iterdir()), set(installer.FILES))

    def test_compile_failure_never_moves_previous_installation(self):
        previous = self.old_install()
        (self.source / installer.FILES[1]).write_text("def invalid(:\n")
        with self.assertRaisesRegex(ValueError, "previous installation preserved"):
            installer.install(self.source, self.destination, self.state)
        self.assert_old_preserved(previous)
        self.assertEqual(json.loads((self.backup() / "receipt.json").read_text())["status"], "NOT_APPLIED")
        self.assertFalse(list(self.root.glob(".workflow-stage-*")))

    def test_missing_source_is_rejected_before_creating_state(self):
        (self.source / installer.FILES[0]).unlink()
        with self.assertRaisesRegex(ValueError, "source file missing"):
            installer.install(self.source, self.destination, self.state)
        self.assertFalse(self.state.exists())
        self.assertFalse(self.destination.exists())

    def test_symlinked_source_file_is_rejected(self):
        path = self.source / installer.FILES[0]
        path.unlink()
        path.symlink_to(self.source / installer.FILES[1])
        with self.assertRaisesRegex(ValueError, "symlinks"):
            installer.install(self.source, self.destination, self.state)
        self.assertFalse(self.destination.exists())

    def test_symlinked_parent_and_operations_are_rejected(self):
        outside = self.root / "outside"
        outside.mkdir()
        alias = self.root / "alias"
        alias.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "symlinks"):
            installer.install(self.source, alias / "installed", self.state)
        self.state.mkdir()
        (self.state / "operations").symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "symlinks"):
            installer.install(self.source, self.destination, self.state)
        self.assertEqual(list(outside.iterdir()), [])

    def test_protected_and_overlapping_paths_are_rejected(self):
        for destination in (self.source, self.root, self.state, self.state / "bundle", self.source / "bundle"):
            with self.subTest(destination=destination), self.assertRaisesRegex(ValueError, "protected|separate"):
                installer.install(self.source, destination, self.state)
        self.assertTrue(all((self.source / name).is_file() for name in installer.FILES))

    def test_concurrent_installer_cannot_swap_target(self):
        previous = self.old_install()
        with installer.install_lock(self.destination):
            with self.assertRaisesRegex(ValueError, "another workflow installation"):
                installer.install(self.source, self.destination, self.state)
        self.assert_old_preserved(previous)
        self.assertFalse(list((self.state / "operations").glob("telegram-workflow-install-*")))

    def fail_after_latest_write(self):
        original = installer.atomic_bytes
        triggered = False

        def write(path, data):
            nonlocal triggered
            original(path, data)
            if path == self.latest and not triggered:
                triggered = True
                raise OSError("synthetic failure after atomic latest publication")

        return patch.object(installer, "atomic_bytes", side_effect=write)

    def test_post_activation_failure_restores_bundle_and_previous_latest(self):
        previous = self.old_install()
        with self.fail_after_latest_write(), self.assertRaisesRegex(ValueError, "previous installation preserved"):
            installer.install(self.source, self.destination, self.state)
        self.assert_old_preserved(previous)
        backup = self.backup()
        self.assertEqual(json.loads((backup / "receipt.json").read_text())["status"], "ROLLED_BACK")
        self.assertTrue((backup / "failed-candidate" / installer.FILES[0]).is_file())
        self.assertFalse(list(self.root.glob(".workflow-stage-*")))

    def test_failed_first_install_removes_only_its_own_latest_receipt(self):
        with self.fail_after_latest_write(), self.assertRaisesRegex(ValueError, "previous installation preserved"):
            installer.install(self.source, self.destination, self.state)
        self.assertFalse(self.destination.exists())
        self.assertFalse(self.latest.exists())
        self.assertTrue((self.backup() / "failed-candidate").is_dir())
        self.assertEqual(json.loads((self.backup() / "receipt.json").read_text())["status"], "ROLLED_BACK")

    def test_failed_rollback_is_reported_and_previous_bundle_is_retained(self):
        original_files, _ = self.old_install()
        original_rename = Path.rename

        def rename(path, target):
            if path == self.destination and Path(target).name == "failed-candidate":
                raise OSError("synthetic quarantine failure")
            return original_rename(path, target)

        with self.fail_after_latest_write(), patch.object(Path, "rename", rename), \
                self.assertRaisesRegex(ValueError, "rollback needs inspection"):
            installer.install(self.source, self.destination, self.state)
        backup = self.backup()
        self.assertEqual(json.loads((backup / "receipt.json").read_text())["status"], "ROLLBACK_FAILED")
        self.assertEqual({path.name: path.read_bytes() for path in (backup / "previous").iterdir()}, original_files)

    def test_plan_does_not_create_installation_or_state(self):
        result = subprocess.run([sys.executable, str(Path(installer.__file__)), "--state-dir", str(self.state),
                                 "--installed-dir", str(self.destination)], capture_output=True, text=True, check=True)
        self.assertEqual(json.loads(result.stdout)["status"], "PLAN")
        self.assertFalse(self.destination.exists())
        self.assertFalse(self.state.exists())


if __name__ == "__main__":
    unittest.main()
