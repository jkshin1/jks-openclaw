#!/usr/bin/env python3
"""Offline regression checks for native productivity verification and delivery gates."""

import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import sys
from types import SimpleNamespace
import unittest
import zipfile
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "productivity_acceptance", Path(__file__).with_name("telegram-productivity-acceptance.py"))
acceptance = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(acceptance)


def owner_config():
    return {"commands": {"ownerAllowFrom": ["telegram:12345"]}, "channels": {"telegram": {
        "enabled": True, "dmPolicy": "allowlist", "allowFrom": ["12345"]}}}


def success(message_id="901"):
    return {"action": "send", "channel": "telegram", "dryRun": False,
            "payload": {"ok": True, "messageId": message_id, "chatId": "12345"}}


def distinct_successes():
    return [json.dumps(success(str(901 + index))) for index in range(len(acceptance.ARTIFACTS))]


class ReceiptTests(unittest.TestCase):
    def test_only_real_correct_recipient_receipt_accepted(self):
        result = acceptance.transport_receipt(success(), "12345")
        self.assertEqual(result["payload"], {"ok": True, "messageId": "901"})
        self.assertNotIn("12345", json.dumps(result))
        variants = []
        for key, value in [("action", "poll"), ("channel", "slack"), ("dryRun", True)]:
            data = success()
            data[key] = value
            variants.append(data)
        for key, value in [("ok", False), ("messageId", ""), ("messageId", 0),
                           ("messageId", True), ("chatId", "99999")]:
            data = success()
            data["payload"][key] = value
            variants.append(data)
        for data in variants:
            with self.subTest(data=data), self.assertRaises(ValueError):
                acceptance.transport_receipt(data, "12345")

    def test_unambiguous_owner_required(self):
        self.assertEqual(acceptance.configured_owner(owner_config()), "12345")
        for change in ({"allowFrom": ["12345", "67890"]}, {"allowFrom": ["@user"]},
                       {"allowFrom": [12345]}, {"dmPolicy": "open"},
                       {"accounts": {"other": {}}}, {"direct": {"12345": {}}}):
            config = owner_config()
            config["channels"]["telegram"].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                acceptance.configured_owner(config)
        config = owner_config()
        config["commands"]["ownerAllowFrom"] = ["telegram:99999"]
        with self.assertRaises(ValueError):
            acceptance.configured_owner(config)


class FixtureEnvironment:
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        for name in [*acceptance.INPUTS, *(name for _, name in acceptance.ARTIFACTS),
                     "render/word-page-1.png", "render/word-page-2.png",
                     "render/excel-page-1.png", "render/excel-page-2.png"]:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("synthetic " + name)
        self.manifest = {"schemaVersion": 1, "syntheticOnly": True,
                         "inputs": {name: acceptance.sha(self.root / name) for name in acceptance.INPUTS},
                         "artifacts": acceptance.artifact_hashes(self.root),
                         "renderedPages": acceptance.render_hashes(self.root)}
        acceptance.save(self.root / "manifest.json", self.manifest)
        self.args = SimpleNamespace(run_dir=self.root)


class ArtifactTests(FixtureEnvironment, unittest.TestCase):
    def test_original_mutation_rejected_before_engine_check(self):
        (self.root / "document-input.docx").write_text("changed")
        with self.assertRaisesRegex(ValueError, "original input changed"), \
                patch.object(acceptance, "fixture_module") as engine:
            acceptance.verify(self.args)
        engine.assert_not_called()

    def test_output_mutation_rejected_before_engine_check(self):
        (self.root / "document-output.docx").write_text("changed")
        with self.assertRaisesRegex(ValueError, "output artifact changed"), \
                patch.object(acceptance, "fixture_module") as engine:
            acceptance.verify(self.args)
        engine.assert_not_called()

    def test_render_mutation_rejected_before_engine_check(self):
        (self.root / "render/word-page-1.png").write_text("changed")
        with self.assertRaisesRegex(ValueError, "rendered page changed"):
            acceptance.verify(self.args)

    def test_symlink_and_parent_escape_rejected(self):
        (self.root / "redirect").symlink_to(self.root / "document-input.docx")
        for name in ("redirect", "../outside", "/etc/passwd"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                acceptance.safe_file(self.root, name)

    def test_private_atomic_receipt(self):
        target = self.root / "private.json"
        acceptance.save(target, {"test": True})
        self.assertEqual(target.stat().st_mode & 0o777, 0o600)
        self.assertEqual(json.loads(target.read_text()), {"test": True})

    def test_failure_diagnostics_are_retained_privately(self):
        target = self.root / "diagnostics/failed.json"
        with self.assertRaisesRegex(ValueError, "private diagnostics saved"):
            acceptance.bounded([sys.executable, "-c",
                                "import sys; print('synthetic stdout'); "
                                "print('synthetic stderr', file=sys.stderr); sys.exit(7)"],
                               diagnostic_path=target)
        diagnostic = json.loads(target.read_text())
        self.assertEqual(diagnostic["exitCode"], 7)
        self.assertEqual(diagnostic["stdout"].strip(), "synthetic stdout")
        self.assertEqual(diagnostic["stderr"].strip(), "synthetic stderr")
        self.assertEqual(target.stat().st_mode & 0o777, 0o600)

    def test_verified_media_staged_with_same_hash_under_default_allowlist(self):
        state = self.root / "state"
        source = "document-output.docx"
        digest = self.manifest["artifacts"][source]
        with patch.object(acceptance, "STATE", state):
            staged = acceptance.stage_media(self.root, source, digest)
            self.assertTrue(staged.is_relative_to(state / "media"))
            self.assertEqual(acceptance.sha(staged), digest)
            self.assertEqual(staged.stat().st_mode & 0o777, 0o600)
            staged.write_text("tampered staged output")
            with self.assertRaisesRegex(ValueError, "staged media content differs"):
                acceptance.stage_media(self.root, source, digest)

    def test_media_staging_rejects_redirected_directory(self):
        state = self.root / "state"
        state.mkdir()
        (state / "media").symlink_to(self.root)
        with patch.object(acceptance, "STATE", state), self.assertRaisesRegex(ValueError, "redirected"):
            acceptance.stage_media(self.root, "document-output.docx",
                                   self.manifest["artifacts"]["document-output.docx"])

    def test_subtitle_zip_preserves_only_the_original_srt_bytes(self):
        source = self.root / "transcripts/speech-input.srt"
        before = acceptance.sha(source)
        transport = acceptance.subtitle_archive(self.root)
        self.assertEqual(transport["format"], "zip")
        self.assertEqual(transport["entrySha256"], before)
        self.assertEqual(acceptance.sha(source), before)
        with zipfile.ZipFile(self.root / transport["path"]) as archive:
            self.assertEqual(archive.namelist(), ["speech-input.srt"])
            self.assertEqual(archive.read("speech-input.srt"), source.read_bytes())
        self.assertEqual(acceptance.subtitle_archive(self.root), transport)
        with zipfile.ZipFile(self.root / transport["path"], "a") as archive:
            archive.writestr("unexpected.txt", "extra")
        with self.assertRaisesRegex(ValueError, "archive content changed"):
            acceptance.subtitle_archive(self.root)


class DeliveryTests(FixtureEnvironment, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.state = self.root / "state"
        self.state.mkdir()
        self.config = self.state / "openclaw.json"
        self.config.write_text(json.dumps(owner_config()))
        self.args = SimpleNamespace(run_dir=self.root, config=self.config, cli=Path("fake-cli"))
        self.verification = {"visualReview": "passed", "artifacts": self.manifest["artifacts"]}
        acceptance.save(self.root / "verification.json", self.verification)
        for name, value in (("STATE", self.state), ("verify", lambda args: {})):
            patcher = patch.object(acceptance, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_duplicate_send_skipped_after_confirmed_receipt(self):
        with patch.object(acceptance, "bounded", side_effect=distinct_successes()) as command:
            result = acceptance.send(self.args)
            self.assertEqual(command.call_count, len(acceptance.ARTIFACTS))
            first = copy.deepcopy(result)
            result = acceptance.send(self.args)
            self.assertEqual(command.call_count, len(acceptance.ARTIFACTS))
            self.assertEqual(result, first)
            self.assertTrue(all("--silent" in call.args[0] for call in command.call_args_list))
        ledger = acceptance.read_receipt(self.root, "delivery.json")
        self.assertTrue(all(entry["status"] == "delivered" for entry in ledger["deliveries"].values()))
        self.assertFalse(result["ownerUploadVerified"])
        self.assertFalse(result["phoneOpenVerified"])
        self.assertNotIn("12345", json.dumps(ledger))

    def test_one_message_id_cannot_prove_two_attachment_sends(self):
        with patch.object(acceptance, "bounded", return_value=json.dumps(success())) as command:
            with self.assertRaisesRegex(ValueError, "messageId reused"):
                acceptance.send(self.args)
            self.assertEqual(command.call_count, 2)
        ledger = acceptance.read_receipt(self.root, "delivery.json")
        self.assertEqual([entry["status"] for entry in ledger["deliveries"].values()], ["delivered", "uncertain"])

    def test_uncertain_send_never_automatically_retried(self):
        with patch.object(acceptance, "bounded", side_effect=ValueError("timed out")) as command:
            with self.assertRaisesRegex(ValueError, "timed out"):
                acceptance.send(self.args)
            with self.assertRaisesRegex(ValueError, "outcome uncertain"):
                acceptance.send(self.args)
            self.assertEqual(command.call_count, 1)
        ledger = acceptance.read_receipt(self.root, "delivery.json")
        self.assertEqual(next(iter(ledger["deliveries"].values()))["status"], "uncertain")

    def test_wrong_receipt_stops_remaining_attachments(self):
        data = success()
        data["payload"]["chatId"] = "99999"
        with patch.object(acceptance, "bounded", return_value=json.dumps(data)) as command:
            with self.assertRaisesRegex(ValueError, "recipient mismatch"):
                acceptance.send(self.args)
            self.assertEqual(command.call_count, 1)

    def test_visual_review_required_before_send(self):
        self.verification["visualReview"] = "pending"
        acceptance.save(self.root / "verification.json", self.verification)
        with patch.object(acceptance, "bounded") as command:
            with self.assertRaisesRegex(ValueError, "inspect all four"):
                acceptance.send(self.args)
            command.assert_not_called()

    def test_alternate_config_cannot_select_recipient(self):
        alternate = self.root / "unrelated-config.json"
        alternate.write_text(json.dumps(owner_config()))
        self.args.config = alternate
        with patch.object(acceptance, "bounded") as command:
            with self.assertRaisesRegex(ValueError, "managed CLI pins"):
                acceptance.send(self.args)
            command.assert_not_called()

    def test_subtitle_resume_preserves_six_prior_receipts_and_sends_zip(self):
        owner_hash = acceptance.hashlib.sha256(b"12345").hexdigest()
        ledger = {"recipientHash": owner_hash, "deliveries": {}}
        for kind, name in acceptance.ARTIFACTS[:-1]:
            ledger["deliveries"][name] = {
                "status": "delivered", "sha256": self.manifest["artifacts"][name],
                "transport": acceptance.transport_receipt(success(str(800 + len(ledger["deliveries"]))), "12345")}
        original_receipts = copy.deepcopy(ledger["deliveries"])
        acceptance.save(self.root / "delivery.json", ledger)
        with patch.object(acceptance, "bounded", return_value=json.dumps(success())) as command:
            acceptance.send(self.args)
            command.assert_called_once()
            arguments = command.call_args.args[0]
            self.assertTrue(str(arguments[arguments.index("--media") + 1]).endswith(".zip"))
            self.assertIn("SRT 자막 ZIP", arguments[arguments.index("--message") + 1])
        after = acceptance.read_receipt(self.root, "delivery.json")
        for name, value in original_receipts.items():
            self.assertEqual(after["deliveries"][name], value)
        subtitle = after["deliveries"]["transcripts/speech-input.srt"]
        self.assertEqual(subtitle["sha256"], self.manifest["artifacts"]["transcripts/speech-input.srt"])
        self.assertEqual(subtitle["transportArtifact"]["format"], "zip")
        self.assertEqual(subtitle["transportArtifact"]["entrySha256"], subtitle["sha256"])
        self.assertEqual(subtitle["diagnostics"], "diagnostics/subtitles-zip-send.json")


if __name__ == "__main__":
    unittest.main()
