#!/usr/bin/env python3
"""Semantic failure, reuse-integrity and evidence-comparability pilot checks."""

from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("hermes-procedure-pilot.py")
SPEC = importlib.util.spec_from_file_location("hermes_procedure_pilot", SCRIPT)
pilot = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pilot)


class PilotTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.parent = Path(self.tmp.name).resolve()
        self.root = self.parent / "pilot"
        pilot.prepare(self.root)
        self.plan = self.parent / "procedure.json"
        self.plan.write_bytes(pilot.encoded(pilot.procedure()))

    def write_json(self, name, value):
        path = self.parent / name
        path.write_bytes(pilot.encoded(value))
        return path

    def training(self):
        return pilot.verify(self.root, "train", self.plan)

    def report(self, phase="holdout"):
        return self.write_json(phase + "-report.json", pilot.FIXTURES.derive_report(pilot.events(phase)))

    def envelope(self, agent):
        training = self.training()
        value = pilot.run_template()
        usage = dict(noncache_input_tokens=100, cache_read_tokens=200, cache_write_tokens=0,
                     output_tokens=30)
        # These fabricated numbers exist only in an isolated parser unit test;
        # this method is never called by the production prepare/rehearse path.
        source = self.write_json(agent + "-native.json", {"unit_test_only": True, "usage": usage})
        value.update(agent=agent, status="completed", run_id=agent + "-unit-test", model="model-a",
                     provider="provider-a", reasoning_effort="high", runtime_version="test",
                     input_sha256=pilot.digest(pilot.task_files("holdout")["events.json"]),
                     procedure_sha256=pilot.digest(pilot.procedure()), report_path=str(self.report()),
                     procedure_path=str(self.plan), training_receipt_path=training["receipt_path"],
                     source_artifact_path=str(source), source_artifact_sha256=pilot.digest(source.read_bytes()),
                     elapsed_seconds=2.5, usage=usage)
        return self.write_json(agent + "-run.json", value)

    def test_offline_negative_corrected_then_unchanged_reuse(self):
        result = pilot.rehearse(self.root)
        self.assertTrue(result["ok"])
        self.assertEqual(result["correction_origin"], "authored_fixture")
        self.assertEqual(result["model_learning"], "not_measured")
        self.assertEqual(pilot.read_json(result["rejected_candidate"])["status"], "rejected")
        trained = pilot.read_json(result["training"])
        reused = pilot.read_json(result["holdout_reuse"])
        self.assertEqual(reused["status"], "holdout_reused")
        self.assertEqual(trained["procedure_sha256"], reused["procedure_sha256"])
        self.assertEqual(reused["training_receipt_sha256"], pilot.digest(trained))
        self.assertEqual(pilot.rehearse(self.root), result)

    def test_holdout_has_distinct_ids_and_critical_semantics(self):
        train = pilot.events("train")
        holdout = pilot.events("holdout")
        for key in ("task_id", "event_id"):
            self.assertFalse({e[key] for e in train["events"]} & {e[key] for e in holdout["events"]})
        expected = pilot.FIXTURES.derive_report(holdout)
        tasks = {t["task_id"]: t for t in expected["tasks"]}
        self.assertFalse(tasks["iris"]["telegram_delivered"])
        self.assertEqual(tasks["iris"]["delivery_action"], "verify_receipt")
        self.assertEqual(tasks["opal"]["latest_event_id"], "o-new")
        self.assertEqual(tasks["opal"]["execution_action"], "none")
        self.assertEqual(tasks["opal"]["delivery_action"], "retry_delivery_only")
        self.assertEqual(tasks["pine"]["latest_event_id"], "p-z")
        self.assertFalse(tasks["pine"]["telegram_delivered"])
        self.assertEqual(tasks["kestrel"]["execution_status"], "running")
        self.assertTrue(tasks["willow"]["telegram_delivered"])
        self.assertEqual(tasks["willow"]["execution_status"], "failed")
        self.assertEqual(expected["summary"]["duplicate_event_rows"], 3)
        self.assertEqual(pilot.execute_procedure(holdout, pilot.procedure()), expected)

    def test_each_unsafe_rule_is_rejected_behaviorally_on_holdout(self):
        for key, options in pilot.RULES.items():
            for option in options[1:]:
                candidate = pilot.procedure()
                candidate["rules"][key] = option
                with self.subTest(key=key, option=option):
                    _, errors = pilot.evaluate("holdout", candidate)
                    self.assertIn("PROCEDURE_BEHAVIOR_MISMATCH", errors)

    def test_report_errors_do_not_pass_with_good_procedure(self):
        report = pilot.FIXTURES.derive_report(pilot.events("train"))
        report["tasks"][0]["execution_action"] = "rerun_execution"
        result = pilot.verify(self.root, "train", self.plan, self.write_json("bad-report.json", report))
        self.assertFalse(result["ok"])
        self.assertEqual(result["error_codes"], ["REPORT_CONTRACT_MISMATCH"])

    def test_holdout_requires_actual_passing_training_for_same_procedure(self):
        with self.assertRaisesRegex(ValueError, "TRAINING_RECEIPT_REQUIRED"):
            pilot.verify(self.root, "holdout", self.plan)
        rejected = pilot.verify(self.root, "train", self.root / "train/candidate-procedure.json")
        with self.assertRaisesRegex(ValueError, "VALIDATED_PROCEDURE_REQUIRED"):
            pilot.verify(self.root, "holdout", self.plan, training_receipt_path=rejected["receipt_path"])
        training = self.training()
        wrong = pilot.procedure()
        wrong["rules"]["delivery_evidence"] = "accepted_only"
        self.plan.write_bytes(pilot.encoded(wrong))
        with self.assertRaisesRegex(ValueError, "VALIDATED_PROCEDURE_REQUIRED"):
            pilot.verify(self.root, "holdout", self.plan, training_receipt_path=training["receipt_path"])

    def test_changed_training_report_and_receipt_cannot_authorize_holdout(self):
        report_path = self.report("train")
        training = pilot.verify(self.root, "train", self.plan, report_path)
        report_path.write_text("{}")
        with self.assertRaisesRegex(ValueError, "TRAINING_RECEIPT_CHANGED"):
            pilot.verify(self.root, "holdout", self.plan, training_receipt_path=training["receipt_path"])
        self.report("train")
        receipt_path = Path(training["receipt_path"])
        changed = pilot.read_json(receipt_path)
        changed["root"] = str(self.parent)
        receipt_path.write_bytes(pilot.encoded(changed))
        with self.assertRaisesRegex(ValueError, "TRAINING_RECEIPT_CHANGED"):
            pilot.verify(self.root, "holdout", self.plan, training_receipt_path=receipt_path)

    def test_task_tampering_rehash_does_not_pass_and_expected_file_is_not_oracle(self):
        (self.root / "evaluator/train-expected.json").write_text("{}")
        self.assertTrue(self.training()["ok"])
        (self.root / "train/events.json").write_bytes(pilot.encoded(pilot.events("holdout")))
        altered = pilot.manifest()
        altered["phases"]["train"]["events.json"] = pilot.digest((self.root / "train/events.json").read_bytes())
        (self.root / "manifest.json").write_bytes(pilot.encoded(altered))
        with self.assertRaisesRegex(ValueError, "MANIFEST_CHANGED"):
            self.training()

    def test_arbitrary_code_extra_fields_and_duplicate_keys_rejected(self):
        candidates = [dict(pilot.procedure(), command="touch /tmp/no"),
                      {"schema_version": True, "rules": pilot.procedure()["rules"]}]
        for candidate in candidates:
            self.plan.write_bytes(pilot.encoded(candidate))
            with self.assertRaises(ValueError):
                self.training()
        self.plan.write_text('{"schema_version":1,"schema_version":1,"rules":{}}')
        with self.assertRaisesRegex(ValueError, "duplicate JSON key"):
            self.training()

    def test_symlink_root_input_report_and_receipt_rejected(self):
        linked = self.parent / "linked"
        linked.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlink"):
            pilot.verify(linked, "train", self.plan)
        link = self.parent / "plan-link.json"
        link.symlink_to(self.plan)
        with self.assertRaisesRegex(ValueError, "symlink"):
            pilot.verify(self.root, "train", link)

    def test_missing_capacity_and_partial_usage_are_not_zero_measurements(self):
        result = pilot.compare(self.root)
        self.assertEqual(result["status"], "blocked")
        self.assertIsNone(result["hermes_minus_openclaw"])
        openclaw, hermes = self.envelope("openclaw"), self.envelope("hermes")
        changed = pilot.read_json(hermes)
        changed["status"] = "capacity_blocked"
        hermes.write_bytes(pilot.encoded(changed))
        result = pilot.compare(self.root, openclaw, hermes)
        self.assertIn("hermes:capacity_blocked", result["reasons"])
        self.assertIsNone(result["runs"]["hermes"]["metrics"])
        changed["status"] = "completed"
        changed["usage"]["cache_read_tokens"] = None
        hermes.write_bytes(pilot.encoded(changed))
        result = pilot.compare(self.root, openclaw, hermes)
        self.assertIn("hermes:missing_measurements", result["reasons"])
        self.assertIsNone(result["hermes_minus_openclaw"])
        changed["usage"] = {key: 0 for key in changed["usage"]}
        hermes.write_bytes(pilot.encoded(changed))
        result = pilot.compare(self.root, openclaw, hermes)
        self.assertIn("hermes:zero_usage_not_established", result["reasons"])
        self.assertIsNone(result["hermes_minus_openclaw"])

    def test_compare_only_identical_effective_model_effort_task_and_scope(self):
        openclaw, hermes = self.envelope("openclaw"), self.envelope("hermes")
        result = pilot.compare(self.root, openclaw, hermes)
        self.assertTrue(result["ok"])
        self.assertEqual(result["status"], "measured_single_pair")
        self.assertEqual(result["hermes_minus_openclaw"]["elapsed_seconds"], 0)
        original = pilot.read_json(hermes)
        for field, value in (("model", "model-b"), ("provider", "provider-b"),
                             ("reasoning_effort", "low"), ("input_sha256", "bad"),
                             ("contract_sha256", "bad"), ("timing_scope", "inference_only"),
                             ("context_contract", "shared_session")):
            changed = deepcopy(original)
            changed[field] = value
            hermes.write_bytes(pilot.encoded(changed))
            with self.subTest(field=field):
                result = pilot.compare(self.root, openclaw, hermes)
                self.assertFalse(result["ok"])
                self.assertIsNone(result["hermes_minus_openclaw"])

    def test_source_tamper_invalid_output_and_fabricated_origin_rejected(self):
        openclaw, hermes = self.envelope("openclaw"), self.envelope("hermes")
        original = pilot.read_json(hermes)
        source = Path(original["source_artifact_path"])
        source.write_text("changed")
        result = pilot.compare(self.root, openclaw, hermes)
        self.assertIn("hermes:SOURCE_ARTIFACT_CHANGED", result["reasons"])
        original["source_artifact_sha256"] = pilot.digest(source.read_bytes())
        original["measurement_origin"] = "offline_fixture"
        hermes.write_bytes(pilot.encoded(original))
        result = pilot.compare(self.root, openclaw, hermes)
        self.assertIn("hermes:RUNTIME_OBSERVATIONS_REQUIRED", result["reasons"])
        original["measurement_origin"] = "runtime"
        hermes.write_bytes(pilot.encoded(original))
        Path(original["report_path"]).write_text("{}")
        result = pilot.compare(self.root, openclaw, hermes)
        self.assertIn("hermes:RUNTIME_OUTPUT_INVALID", result["reasons"])

    def test_no_overwrite_and_cli_blocked_result_is_machine_readable(self):
        with self.assertRaisesRegex(ValueError, "ROOT_MUST_BE_FRESH"):
            pilot.prepare(self.root)
        result = subprocess.run([sys.executable, str(SCRIPT), "compare", "--root", str(self.root)],
                                capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads(result.stdout)["status"], "blocked")
        self.assertEqual(result.stderr, "")


if __name__ == "__main__":
    unittest.main()
