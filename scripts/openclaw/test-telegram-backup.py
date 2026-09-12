#!/usr/bin/env python3
"""Synthetic backups only: extraction safety, corruption and restore evidence."""

import importlib.util
from contextlib import closing
import io
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("telegram_backup", Path(__file__).with_name("telegram-backup.py"))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.archive = self.root / "state.tar.gz"
        self.state = self.root / "live"
        self.addCleanup(self.temporary.cleanup)

    def make_archive(self, extras=(), *, missing_table=False):
        asset_root = "fixture/payload/live"
        manifest = {"schemaVersion": 1, "archiveRoot": "fixture", "paths": {"stateDir": str(self.state)},
                    "assets": [{"kind": "state", "sourcePath": str(self.state), "archivePath": asset_root}]}
        members = [("fixture/manifest.json", json.dumps(manifest).encode())]
        config = {"channels": {"telegram": {"botToken": {"source": "store", "id": "TOKEN_REF"}}}}
        for name, content in (("openclaw.json", json.dumps(config)), ("workspace/AGENTS.md", "policy"),
                              ("workspace/MEMORY.md", "memory"), ("workspace/USER.md", "preferences"),
                              ("workspace/MEMORY_CONTROL.md", "control")):
            members.append((asset_root + "/" + name, content.encode()))
        for role, name, tables in (("global", "state/openclaw.sqlite", backup.GLOBAL_TABLES),
                                   ("agent", "agents/main/agent/openclaw-agent.sqlite", backup.AGENT_TABLES)):
            database_path = self.root / (role + ".sqlite")
            database_path.unlink(missing_ok=True)
            with closing(sqlite3.connect(database_path)) as database:
                for table in sorted(tables - ({"session_nodes"} if missing_table else set())):
                    database.execute('CREATE TABLE "' + table + '" (value TEXT)')
                    database.execute('INSERT INTO "' + table + '" VALUES (?)', ("synthetic",))
                database.commit()
            members.append((asset_root + "/" + name, database_path.read_bytes()))
        with tarfile.open(self.archive, "w:gz") as archive:
            for name, content in members:
                entry = tarfile.TarInfo(name)
                entry.size = len(content)
                archive.addfile(entry, io.BytesIO(content))
            for entry, content in extras:
                archive.addfile(entry, io.BytesIO(content) if content is not None else None)
        os.chmod(self.archive, 0o600)

    def test_complete_restore_verifies_files_and_logical_database_counts(self):
        self.make_archive()
        inventory = backup.archive_inventory(self.archive)
        target = self.root / "restored"
        backup.extract_offline(self.archive, target, inventory)
        observed = backup.validate_restored(target, inventory, self.state)
        self.assertEqual(len(observed), 2)
        self.assertTrue(all(item["integrity"] == "ok" for item in observed.values()))
        self.assertTrue(all(set(item["tableCounts"].values()) == {1} for item in observed.values()))
        self.assertFalse(self.state.exists())

    def test_archive_traversal_absolute_special_and_collision_are_rejected(self):
        for name, kind in (("fixture/../../escape", tarfile.REGTYPE), ("/tmp/escape", tarfile.REGTYPE),
                           ("fixture/device", tarfile.CHRTYPE), ("fixture/manifest.json", tarfile.REGTYPE),
                           ("fixture/MANIFEST.json", tarfile.REGTYPE)):
            with self.subTest(name=name):
                entry = tarfile.TarInfo(name)
                entry.type = kind
                self.make_archive([(entry, b"")])
                with self.assertRaises(ValueError):
                    backup.archive_inventory(self.archive)

    def test_external_escaping_and_missing_symlink_targets_are_rejected(self):
        for target in ("/Users/owner/.openclaw-personaledge", "../../escape", "missing"):
            with self.subTest(target=target):
                entry = tarfile.TarInfo("fixture/link")
                entry.type = tarfile.SYMTYPE
                entry.linkname = target
                self.make_archive([(entry, None)])
                with self.assertRaises(ValueError):
                    backup.archive_inventory(self.archive)

    def test_safe_internal_symlink_is_restored_without_following_it(self):
        entry = tarfile.TarInfo("fixture/link")
        entry.type = tarfile.SYMTYPE
        entry.linkname = "manifest.json"
        self.make_archive([(entry, None)])
        inventory = backup.archive_inventory(self.archive)
        target = self.root / "restored"
        backup.extract_offline(self.archive, target, inventory)
        self.assertEqual(os.readlink(target / "fixture/link"), "manifest.json")

    def test_parent_symlink_cannot_redirect_an_archive_write(self):
        link = tarfile.TarInfo("fixture/alias")
        link.type = tarfile.SYMTYPE
        link.linkname = "manifest.json"
        child = tarfile.TarInfo("fixture/alias/overwrite")
        self.make_archive([(link, None), (child, b"")])
        with self.assertRaisesRegex(ValueError, "parent"):
            backup.archive_inventory(self.archive)

    def test_restore_refuses_existing_target_and_symlink_ancestors(self):
        self.make_archive()
        inventory = backup.archive_inventory(self.archive)
        with self.assertRaises(ValueError):
            backup.extract_offline(self.archive, self.root, inventory)
        linked = self.root / "link"
        linked.symlink_to(self.root)
        with self.assertRaises(ValueError):
            backup.extract_offline(self.archive, linked / "new", inventory)

    def test_restore_refuses_live_state_and_its_parents(self):
        for target in (self.state, self.state / "new", self.root):
            with self.subTest(target=target), self.assertRaises(ValueError):
                backup.check_target(target, self.state, self.root / "runtime")

    def test_valid_sqlite_missing_required_schema_is_rejected(self):
        self.make_archive(missing_table=True)
        inventory = backup.archive_inventory(self.archive)
        target = self.root / "restored"
        backup.extract_offline(self.archive, target, inventory)
        with self.assertRaisesRegex(ValueError, "schema"):
            backup.validate_restored(target, inventory, self.state)

    def test_corrupt_sqlite_is_rejected(self):
        path = self.root / "corrupt.sqlite"
        path.write_bytes(b"SQLite format 3\x00" + b"corrupted" * 200)
        with self.assertRaises(sqlite3.DatabaseError):
            backup.database_observation(path)

    def test_archive_tampering_is_rejected_before_official_command_or_extraction(self):
        self.make_archive()
        manifest = {"schemaVersion": 1, "archiveSha256": backup.digest(self.archive)}
        backup.write_json(self.root / "recovery-manifest.json", manifest)
        backup.write_json(self.root / "verification.json",
                          {"status": "VERIFIED", "manifestSha256": backup.digest(self.root / "recovery-manifest.json"),
                           "archiveSha256": manifest["archiveSha256"]})
        with self.archive.open("ab") as stream:
            stream.write(b"tampered")
        args = type("Args", (), {"archive": self.archive})()
        with patch.object(backup, "run_official") as official:
            with self.assertRaisesRegex(ValueError, "checksum"):
                backup.rehearse(args)
            official.assert_not_called()

    def test_manifest_tampering_is_rejected(self):
        self.make_archive()
        backup.write_json(self.root / "recovery-manifest.json", {"schemaVersion": 1})
        backup.write_json(self.root / "verification.json", {"status": "VERIFIED", "manifestSha256": "0" * 64})
        args = type("Args", (), {"archive": self.archive})()
        with self.assertRaisesRegex(ValueError, "manifest checksum"):
            backup.rehearse(args)

    def test_changed_payload_after_inventory_is_rejected_on_extraction(self):
        self.make_archive()
        inventory = backup.archive_inventory(self.archive)
        inventory["entries"]["fixture/payload/live/workspace/AGENTS.md"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "checksum"):
            backup.extract_offline(self.archive, self.root / "restored", inventory)

    def test_full_create_and_reuse_publish_only_completed_rehearsals(self):
        self.make_archive()
        self.state.mkdir(mode=0o700)
        args = SimpleNamespace(output=self.root / "backups", state_dir=self.state,
                               package=self.root / "runtime", cli=self.root / "cli")

        def official(cli, *arguments, **kwargs):
            if arguments[0] == "create":
                destination = Path(arguments[arguments.index("--output") + 1])
                shutil.copyfile(self.archive, destination)
                os.chmod(destination, 0o600)
                return {"archivePath": str(destination)}
            return {"ok": True}

        with patch.object(backup, "run_official", side_effect=official), patch.object(
                backup, "recovery_supplement", return_value={"runtimeVersion": "fixture", "files": {}}):
            receipt = backup.create(args)
            latest = self.state / "operations/telegram-backup-latest.json"
            self.assertEqual(json.loads(latest.read_text()), receipt)
            self.assertEqual(receipt["databasesVerified"], 2)
            saved = latest.read_bytes()
            args.archive = Path(receipt["archivePath"])
            args.target = self.root / "second-rehearsal"
            reused = backup.rehearse(args)
            self.assertFalse(reused["productionActivated"])
            self.assertTrue((args.target / "rehearsal-verification.json").is_file())
            self.make_archive(missing_table=True)
            with self.assertRaisesRegex(ValueError, "schema"):
                backup.create(args)
            self.assertEqual(latest.read_bytes(), saved, "failed attempts must not publish a success receipt")

    def test_official_failure_retains_private_diagnostics_without_printing_output(self):
        diagnostics = self.root / "diagnostics/create.json"
        completed = subprocess.CompletedProcess([], 1, "synthetic stdout", "synthetic private failure")
        with patch.object(backup.subprocess, "run", return_value=completed):
            with self.assertRaisesRegex(ValueError, "output suppressed") as failure:
                backup.run_official(self.root / "cli", "create", diagnostics_path=diagnostics)
        self.assertNotIn("synthetic", str(failure.exception))
        data = json.loads(diagnostics.read_text())
        self.assertEqual(data["returncode"], 1)
        self.assertEqual(data["stderr"], "synthetic private failure")
        self.assertEqual(diagnostics.stat().st_mode & 0o777, 0o600)
        self.assertEqual(diagnostics.parent.stat().st_mode & 0o777, 0o700)

    def test_timeout_retains_partial_output_and_stops_create_before_further_writes(self):
        args = SimpleNamespace(output=self.root / "backups", state_dir=self.state,
                               package=self.root / "runtime", cli=self.root / "cli")
        timeout = subprocess.TimeoutExpired("synthetic", 900, output=b"partial stdout\xff", stderr=b"partial stderr")
        with patch.object(backup.subprocess, "run", side_effect=timeout), patch.object(
                backup, "recovery_supplement") as supplement, patch.object(backup, "extract_offline") as extract:
            with self.assertRaises(subprocess.TimeoutExpired):
                backup.create(args)
            supplement.assert_not_called()
            extract.assert_not_called()
        diagnostics = list(args.output.glob("*/diagnostics/create.json"))
        self.assertEqual(len(diagnostics), 1)
        data = json.loads(diagnostics[0].read_text())
        self.assertTrue(data["timedOut"])
        self.assertEqual(data["stderr"], "partial stderr")
        self.assertTrue(data["stdout"].startswith("partial stdout"))
        self.assertFalse((self.state / "operations/telegram-backup-latest.json").exists())


class RecoverySupplementTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.bundle = self.root / "bundle"
        self.operator = self.root / "operator"
        self.operator.mkdir(mode=0o700)
        self.args = SimpleNamespace(package=self.root / "package", state_dir=self.root / "state",
                                    cli=self.root / "management/openclaw", launch_agents=self.root / "launchagents")
        self.specs = json.loads(json.dumps(backup.RUNTIME_PATCH_SPECS))
        self.auth = self.specs["2026.9.3"]["authReprobe"]
        self.write(self.args.package / self.auth["path"], b"synthetic patched auth runtime")
        self.auth["after"] = backup.digest(self.args.package / self.auth["path"])
        self.auth["before"] = "a" * 64
        self.write(self.args.package / "package.json", json.dumps({"version": "2026.9.3"}).encode())
        self.write(self.args.cli, b"synthetic CLI")
        for name in ("ai.openclaw.personaledge.plist", "com.personaledge.openclaw-telegram-watchdog.plist"):
            self.write(self.args.launch_agents / name, b"synthetic launch agent")
        for index, name in enumerate(backup.PATCH_RECEIPTS):
            relative = "dist/base-%d.mjs" % index
            self.write(self.args.package / relative, ("synthetic patch %d" % index).encode())
            self.write_receipt(name, {"version": "2026.9.3", "relativePath": relative,
                                     "afterSha256": backup.digest(self.args.package / relative)})
        self.receipt = {"version": "2026.9.3", "relativePath": self.auth["path"],
                        "beforeSha256": self.auth["before"], "afterSha256": self.auth["after"],
                        "inferenceRequests": 0}
        self.write_receipt("auth-reprobe-patch.json", self.receipt)
        for name in ("patch-auth-reprobe.mjs", "test-auth-reprobe.mjs", "runtime-patch-specs.json"):
            self.write(self.operator / name, b"synthetic recovery operator asset")

    def write(self, path, data):
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        path.write_bytes(data)
        path.chmod(0o600)

    def write_receipt(self, name, data):
        self.write(self.args.state_dir / "operations" / name, json.dumps(data).encode())

    def supplement(self):
        with patch.object(backup, "RUNTIME_PATCH_SPECS", self.specs), \
                patch.object(backup, "__file__", str(self.operator / "telegram-backup.py")), \
                patch.object(backup.platform, "platform", return_value="synthetic-platform"), \
                patch.object(backup.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "v26.0.0\n", "")):
            return backup.recovery_supplement(self.bundle, self.args)

    def test_auth_reprobe_receipt_runtime_and_reapplication_assets_survive_offline_restore(self):
        # An extra files field must not redirect the single-file auth receipt.
        self.receipt["files"] = [{"relativePath": "dist/unreviewed.mjs", "afterSha256": "0" * 64}]
        self.write_receipt("auth-reprobe-patch.json", self.receipt)
        recovery = self.supplement()
        expected = {"recovery/receipts/auth-reprobe-patch.json", "recovery/runtime/" + self.auth["path"]}
        expected.update("recovery/operator-scripts/" + name for name in (
            "patch-auth-reprobe.mjs", "test-auth-reprobe.mjs", "runtime-patch-specs.json"))
        self.assertTrue(expected.issubset(recovery["files"]))
        self.assertFalse(recovery["activationTested"])
        restored = self.root / "restored"
        backup.restore_recovery(self.bundle, restored, recovery)
        for relative in expected:
            self.assertEqual(backup.digest(restored / relative), recovery["files"][relative]["sha256"])
            self.assertEqual((restored / relative).stat().st_mode & 0o777, 0o600)

    def test_2026_9_3_requires_auth_receipt(self):
        (self.args.state_dir / "operations/auth-reprobe-patch.json").unlink()
        with self.assertRaises(FileNotFoundError):
            self.supplement()

    def test_auth_receipt_symlink_is_rejected(self):
        path = self.args.state_dir / "operations/auth-reprobe-patch.json"
        path.rename(path.with_suffix(".saved"))
        path.symlink_to(path.with_suffix(".saved"))
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.supplement()

    def test_unreviewed_auth_receipt_is_rejected(self):
        for field, value in (("relativePath", "dist/other.mjs"), ("beforeSha256", "0" * 64),
                             ("afterSha256", "0" * 64), ("inferenceRequests", 1), ("inferenceRequests", False)):
            with self.subTest(field=field, value=value):
                shutil.rmtree(self.bundle, ignore_errors=True)
                self.write_receipt("auth-reprobe-patch.json", dict(self.receipt, **{field: value}))
                with self.assertRaisesRegex(ValueError, "auth reprobe receipt"):
                    self.supplement()

    def test_auth_runtime_drift_is_rejected(self):
        self.write(self.args.package / self.auth["path"], b"unreviewed runtime")
        with self.assertRaisesRegex(ValueError, "hash drifted"):
            self.supplement()

    def test_2026_9_2_keeps_four_patch_receipts(self):
        self.write(self.args.package / "package.json", json.dumps({"version": "2026.9.2"}).encode())
        for name in backup.PATCH_RECEIPTS:
            path = self.args.state_dir / "operations" / name
            receipt = json.loads(path.read_text())
            receipt["version"] = "2026.9.2"
            self.write_receipt(name, receipt)
        # A leftover newer receipt is neither required nor included for the older runtime.
        recovery = self.supplement()
        names = {name for name in recovery["files"] if name.startswith("recovery/receipts/")}
        self.assertEqual(names, {"recovery/receipts/" + name for name in backup.PATCH_RECEIPTS})
        self.assertNotIn("recovery/runtime/" + self.auth["path"], recovery["files"])


if __name__ == "__main__":
    unittest.main()
