#!/usr/bin/env python3
"""Evidence and lifecycle regression tests; no runtime or model calls."""

from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("hermes_ops_knowledge", Path(__file__).with_name("hermes-ops-knowledge.py"))
knowledge = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(knowledge)
VERSIONS = {"openclaw": "2026.9.3", "hermes": "0.21.1"}


class KnowledgeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.operations = Path(self.temporary.name).resolve() / "operations"
        self.operations.mkdir()
        self.observe()

    def tearDown(self):
        self.temporary.cleanup()

    def observe(self, run_id="review-1", findings=None, claims=None):
        return knowledge.observe_review(self.operations, run_id,
            {"findings": findings if findings is not None else [{"id": "routing-main", "kind": "issue"}],
             "procedure_uses": claims or []}, {"status": "reviewed", "ok": True, "runId": run_id})

    def artifact(self, run_id, name, value):
        target = self.operations / "runs" / run_id / name
        target.parent.mkdir(parents=True, exist_ok=True)
        data = value.encode() if isinstance(value, str) else json.dumps(value).encode()
        target.write_bytes(data)
        return {"path": str(target.relative_to(self.operations)), "sha256": hashlib.sha256(data).hexdigest()}

    def proof(self, run_id="validation-1", *, failed=False, versions=None, finding="routing-main", procedure=None):
        checks = []
        for kind in ("test", "runtime"):
            check = {"schemaVersion": 1, "kind": "hermes-ops-command-check", "runId": run_id,
                     "checkId": kind + "-routing", "checkType": kind, "status": "completed",
                     "exitCode": 1 if failed and kind == "runtime" else 0,
                     "startedAt": "2026-09-12T01:00:00+00:00", "finishedAt": "2026-09-12T01:00:02+00:00",
                     "commandSha256": hashlib.sha256((kind + "-command").encode()).hexdigest(),
                     "output": self.artifact(run_id, kind + ".txt", "actual captured " + kind + " result\n")}
            if procedure and kind == "runtime":
                check["procedure"] = procedure
            checks.append(self.artifact(run_id, kind + ".json", check))
        envelope = {"schemaVersion": 1, "kind": "hermes-ops-verification", "runId": run_id,
                    "findingIds": [finding], "changeId": "commit-abc", "versions": versions or VERSIONS,
                    "checks": checks}
        if procedure:
            envelope["procedure"] = procedure
        return self.artifact(run_id, "verification.json", envelope)

    def experience(self, *, run_id="validation-1", experience_id="routing-fix", failed=False):
        return knowledge.record_experience(self.operations, {
            "id": experience_id, "findingId": "routing-main", "runId": run_id,
            "cause": "Global defaults omitted the effective main-agent override.",
            "change": "Collect the selected main-agent route and explicit fallback policy.",
            "versions": VERSIONS, "changeId": "commit-abc", "operator": "codex",
            "preconditions": ["Configuration uses an explicit main-agent model."],
            "steps": ["Inspect main-agent configuration.", "Resolve explicit primary and fallback overrides."],
            "validation": ["Run routing precedence regression tests.", "Compare the bounded runtime routing projection."],
            "verificationRef": self.proof(run_id, failed=failed)})

    def promote(self):
        self.experience()
        return knowledge.promote_procedure(self.operations, "routing-fix", operator="codex", procedure_id="main-routing")

    def reuse(self, procedure, *, failed=False, reuse_id="reuse-1", run_id="validation-2", binding=True):
        identity = {key: procedure[key] for key in ("id", "version", "sha256")}
        return knowledge.record_reuse(self.operations, {
            "id": reuse_id, "procedureId": procedure["id"], "procedureVersion": procedure["version"],
            "procedureSha256": procedure["sha256"], "runId": run_id, "operator": "codex",
            "verificationRef": self.proof(run_id, failed=failed, procedure=identity if binding else None)})

    def test_legacy_and_normal_observations_are_not_issues(self):
        self.observe("review-2", [{"id": "normal-health", "kind": "observation"}, {"id": "legacy-health"}])
        findings = {row["id"]: row for row in knowledge.summary(self.operations)["findingLifecycle"]}
        self.assertEqual(findings["normal-health"]["state"], "observed")
        self.assertEqual(findings["legacy-health"]["classification"], "legacy-unclassified")
        self.assertEqual(findings["legacy-health"]["state"], "observed")

    def test_repeated_review_is_idempotent_and_does_not_change_operator_revision(self):
        before = knowledge.summary(self.operations)
        self.assertEqual(self.observe()["status"], "already-observed")
        self.observe("review-2")
        after = knowledge.summary(self.operations)
        self.assertEqual(after["operatorRevision"], before["operatorRevision"])
        self.assertEqual(after["findingLifecycle"][0]["seenCount"], 2)

    def test_disappearing_findings_stay_open(self):
        self.observe("review-empty", [])
        self.assertEqual(knowledge.summary(self.operations)["findingLifecycle"][0]["state"], "open")

    def test_model_text_never_enters_summary(self):
        self.observe("review-secret", [{"id": "obs", "kind": "observation", "detail": "api_key=DO-NOT-STORE"}])
        self.assertNotIn("DO-NOT-STORE", (self.operations / "knowledge.json").read_text())

    def test_lifecycle_requires_owner_and_deferral_reason(self):
        with self.assertRaisesRegex(ValueError, "OWNER_REQUIRED"):
            knowledge.update_finding(self.operations, "routing-main", state="in_progress")
        knowledge.update_finding(self.operations, "routing-main", owner="codex", state="in_progress")
        with self.assertRaisesRegex(ValueError, "CURATED_TEXT_INVALID"):
            knowledge.update_finding(self.operations, "routing-main", state="deferred")
        row = knowledge.update_finding(self.operations, "routing-main", state="deferred", defer_reason="Waiting for a pinned runtime release.")
        self.assertEqual(row["state"], "deferred")
        self.assertEqual(knowledge.summary(self.operations)["operatorRevision"], 2)

    def test_resolution_requires_real_receipt_chain(self):
        with self.assertRaisesRegex(ValueError, "ARTIFACT_REFERENCE_INVALID"):
            knowledge.update_finding(self.operations, "routing-main", owner="codex", state="resolved",
                                     closure_ref={"verified": True})
        self.assertEqual(knowledge.summary(self.operations)["findingLifecycle"][0]["state"], "open")
        knowledge.update_finding(self.operations, "routing-main", owner="codex", state="resolved", closure_ref=self.proof())
        self.assertTrue(knowledge.summary(self.operations)["findingLifecycle"][0]["closureEvidenceValid"])

    def test_recurrence_keeps_closure_and_requires_operator_reopening(self):
        knowledge.update_finding(self.operations, "routing-main", owner="codex", state="resolved", closure_ref=self.proof())
        self.observe("review-recurrence")
        row = knowledge.summary(self.operations)["findingLifecycle"][0]
        self.assertTrue(row["seenAfterResolution"])
        self.assertEqual(row["state"], "resolved")
        row = knowledge.update_finding(self.operations, "routing-main", state="open")
        self.assertNotIn("closureRef", row)

    def test_failure_cannot_close_or_promote_but_experience_is_preserved(self):
        experience = self.experience(failed=True)
        self.assertEqual(experience["verificationOutcome"], "failed")
        with self.assertRaisesRegex(ValueError, "VERIFICATION_FAILED"):
            knowledge.promote_procedure(self.operations, "routing-fix", operator="codex")
        with self.assertRaisesRegex(ValueError, "VERIFICATION_FAILED"):
            knowledge.update_finding(self.operations, "routing-main", owner="codex", state="resolved",
                                     closure_ref=experience["verificationRef"])

    def test_wrong_finding_cannot_close(self):
        with self.assertRaisesRegex(ValueError, "FINDING_MISMATCH"):
            knowledge.update_finding(self.operations, "routing-main", owner="codex", state="resolved",
                                     closure_ref=self.proof(finding="unrelated"))

    def test_promotion_is_explicit_and_idempotent(self):
        self.experience()
        self.assertEqual(knowledge.summary(self.operations, VERSIONS)["procedures"], [])
        procedure = knowledge.promote_procedure(self.operations, "routing-fix", operator="codex")
        before = knowledge.summary(self.operations, VERSIONS)
        knowledge.promote_procedure(self.operations, "routing-fix", operator="codex")
        self.assertEqual(knowledge.summary(self.operations, VERSIONS)["operatorRevision"], before["operatorRevision"])
        self.assertEqual(procedure["sha256"], hashlib.sha256(procedure["procedure"].encode()).hexdigest())

    def test_version_mismatch_and_unknown_versions_disable_procedure(self):
        self.promote()
        self.assertEqual(len(knowledge.summary(self.operations, VERSIONS)["procedures"]), 1)
        for versions in (None, {}, {"openclaw": "2026.9.4", "hermes": "0.21.1"}):
            summary = knowledge.summary(self.operations, versions)
            self.assertEqual(summary["procedures"], [])
            self.assertEqual(summary["unavailableProcedures"][0]["reason"], "version-mismatch")

    def test_stale_output_hash_disables_procedure_and_closure(self):
        procedure = self.promote()
        knowledge.update_finding(self.operations, "routing-main", owner="codex", state="resolved",
                                 closure_ref=procedure["verificationRef"])
        (self.operations / "runs/validation-1/runtime.txt").write_text("changed")
        summary = knowledge.summary(self.operations, VERSIONS)
        self.assertEqual(summary["procedures"], [])
        self.assertFalse(summary["findingLifecycle"][0]["closureEvidenceValid"])
        self.assertEqual(summary["unavailableProcedures"][0]["reason"], "evidence-invalid")

    def test_model_reference_claim_never_counts_as_successful_reuse(self):
        procedure = self.promote()
        self.observe("review-2", claims=[{"procedure_id": procedure["id"], "version": procedure["version"],
            "sha256": procedure["sha256"], "conclusion": "reuse_claimed", "evidence": ["procedureKnowledge"]}])
        stats = knowledge.summary(self.operations, VERSIONS)["procedureStats"][0]
        self.assertEqual(stats["referenceCount"], 1)
        self.assertEqual(stats["successfulReuses"], 0)

    def test_reuse_requires_explicit_procedure_binding(self):
        procedure = self.promote()
        with self.assertRaisesRegex(ValueError, "VERIFICATION_PROCEDURE_MISMATCH"):
            self.reuse(procedure, binding=False)

    def test_successful_and_failed_reuse_are_distinct(self):
        procedure = self.promote()
        self.assertEqual(self.reuse(procedure)["outcome"], "verified")
        self.assertEqual(self.reuse(procedure, failed=True, reuse_id="reuse-2", run_id="validation-3")["outcome"], "failed")
        stats = knowledge.summary(self.operations, VERSIONS)["procedureStats"][0]
        self.assertEqual((stats["successfulReuses"], stats["failedReuses"]), (1, 1))

    def test_duplicate_reuse_run_does_not_inflate_success(self):
        procedure = self.promote()
        self.reuse(procedure)
        with self.assertRaisesRegex(ValueError, "REUSE_RUN_ALREADY_RECORDED"):
            self.reuse(procedure, reuse_id="duplicate")

    def test_reuse_cannot_relabel_promotion_run(self):
        procedure = self.promote()
        with self.assertRaisesRegex(ValueError, "REUSE_REQUIRES_NEW_RUN"):
            self.reuse(procedure, run_id="validation-1")

    def test_stale_reuse_evidence_not_counted(self):
        procedure = self.promote()
        self.reuse(procedure)
        (self.operations / "runs/validation-2/runtime.txt").write_text("changed")
        stats = knowledge.summary(self.operations, VERSIONS)["procedureStats"][0]
        self.assertEqual((stats["successfulReuses"], stats["invalidReuseEvidence"]), (0, 1))

    def test_corrupt_reuse_of_an_unavailable_procedure_is_still_counted(self):
        procedure = self.promote()
        self.reuse(procedure)
        (self.operations / "runs/validation-2/runtime.txt").write_text("changed")
        summary = knowledge.summary(self.operations, {"openclaw": "2026.9.9", "hermes": "0.21.1"})
        self.assertEqual(summary["procedures"], [])
        self.assertEqual(summary["unavailableProcedures"][0]["reason"], "version-mismatch")
        stats = summary["procedureStats"][0]
        self.assertEqual(stats["invalidReuseEvidence"], 1)
        self.assertFalse(stats["offered"])

    def test_new_procedure_version_retains_prior_evidence(self):
        self.promote()
        self.experience(run_id="validation-2", experience_id="routing-fix-2")
        procedure = knowledge.promote_procedure(self.operations, "routing-fix-2", operator="codex", procedure_id="main-routing")
        self.assertEqual(procedure["version"], 2)
        self.assertEqual(procedure["history"][0]["experienceId"], "routing-fix")

    def test_symlink_and_external_artifacts_are_rejected(self):
        proof = self.proof()
        target = self.operations / proof["path"]
        moved = target.with_name("other.json")
        target.rename(moved)
        target.symlink_to(moved)
        with self.assertRaisesRegex(ValueError, "SYMLINK_REFUSED"):
            knowledge.verification(self.operations, proof)
        for path in ("../outside.json", "/tmp/outside.json", "runs/../outside.json", "knowledge.json"):
            with self.assertRaisesRegex(ValueError, "ARTIFACT_PATH_INVALID"):
                knowledge.verification(self.operations, {"path": path, "sha256": "0" * 64})

    def test_nonfinite_and_duplicate_json_keys_rejected(self):
        for data in ('{"x": NaN}', '{"x": Infinity}', '{"x": 1, "x": 2}'):
            reference = self.artifact("bad", "invalid.json", data)
            with self.assertRaises(ValueError):
                knowledge.artifact(self.operations, reference)

    def test_secret_like_curated_text_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "CURATED_TEXT_UNSAFE"):
            knowledge.update_finding(self.operations, "routing-main", owner="codex", state="deferred",
                                     defer_reason="api_key=do-not-persist")

    def test_atomic_persistence_is_private_and_reloadable(self):
        self.promote()
        self.assertEqual(os.stat(self.operations / "knowledge.json").st_mode & 0o777, 0o600)
        self.assertEqual(os.stat(self.operations / "knowledge.lock").st_mode & 0o777, 0o600)
        self.assertEqual(os.stat(self.operations).st_mode & 0o777, 0o700)
        state = knowledge.load_state(self.operations)
        self.assertEqual(state["procedures"]["main-routing"]["version"], 1)
        before = (self.operations / "knowledge.json").read_bytes()
        with self.assertRaises(ValueError):
            knowledge.update_finding(self.operations, "routing-main", owner="new-owner", state="resolved")
        self.assertEqual((self.operations / "knowledge.json").read_bytes(), before)

    def test_operator_registration_preserves_provenance_and_invalidation(self):
        source = self.artifact("operator-1", "observation.txt", "bounded collector projection differs from actual main override")
        row = knowledge.register_finding(self.operations, "operator-routing", classification="issue", operator="codex",
                                         run_id="operator-1", source_ref=source)
        self.assertEqual(row["origin"]["kind"], "operator")
        self.assertEqual(row["state"], "open")
        before = knowledge.summary(self.operations)["operatorRevision"]
        knowledge.register_finding(self.operations, "operator-routing", classification="issue", operator="codex",
                                   run_id="operator-1", source_ref=source)
        self.assertEqual(knowledge.summary(self.operations)["operatorRevision"], before)
        self.observe("review-later", [{"id": "operator-routing", "kind": "issue"}])
        row = next(row for row in knowledge.summary(self.operations)["findingLifecycle"] if row["id"] == "operator-routing")
        self.assertEqual(row["origin"]["kind"], "operator")
        self.assertTrue(row["origin"]["sourceEvidenceValid"])
        (self.operations / source["path"]).write_text("changed")
        row = next(row for row in knowledge.summary(self.operations)["findingLifecycle"] if row["id"] == "operator-routing")
        self.assertFalse(row["origin"]["sourceEvidenceValid"])

    def test_operator_registration_cannot_relabel_hermes_finding(self):
        with self.assertRaisesRegex(ValueError, "FINDING_ALREADY_EXISTS"):
            knowledge.register_finding(self.operations, "routing-main", classification="issue", operator="codex", run_id="operator-1")
        row = knowledge.summary(self.operations)["findingLifecycle"][0]
        self.assertEqual(row["origin"]["kind"], "hermes-review")

    def test_operator_observation_does_not_count_as_verified_recovery(self):
        row = knowledge.register_finding(self.operations, "operator-normal", classification="observation",
                                         operator="codex", run_id="operator-1")
        self.assertEqual(row["state"], "observed")
        self.assertEqual(knowledge.summary(self.operations)["counts"]["experiences"], 0)
        self.assertEqual(knowledge.summary(self.operations)["counts"]["reuses"], 0)

    def test_registration_rejects_source_from_another_run(self):
        source = self.artifact("another-run", "source.txt", "bounded source")
        with self.assertRaisesRegex(ValueError, "ARTIFACT_RUN_MISMATCH"):
            knowledge.register_finding(self.operations, "operator-routing", classification="issue", operator="codex",
                                       run_id="operator-1", source_ref=source)


    def test_concurrent_observations_do_not_lose_updates(self):
        with ThreadPoolExecutor(max_workers=4) as executor:
            list(executor.map(lambda index: self.observe("concurrent-" + str(index)), range(12)))
        summary = knowledge.summary(self.operations)
        self.assertEqual(summary["findingLifecycle"][0]["seenCount"], 13)
        self.assertEqual(summary["operatorRevision"], 0)

    def test_verification_version_and_run_binding(self):
        reference = self.proof()
        with self.assertRaisesRegex(ValueError, "VERSION_MISMATCH"):
            knowledge.verification(self.operations, reference, expected_versions={"openclaw": "wrong"})
        with self.assertRaisesRegex(ValueError, "RUN_MISMATCH"):
            knowledge.verification(self.operations, reference, run_id="another-run")

    def test_malformed_state_is_rejected_without_overwriting(self):
        path = self.operations / "knowledge.json"
        state = json.loads(path.read_text())
        state["schemaVersion"] = True
        path.write_text(json.dumps(state))
        before = path.read_bytes()
        with self.assertRaisesRegex(ValueError, "KNOWLEDGE_SCHEMA_INVALID"):
            self.observe("another-review")
        self.assertEqual(path.read_bytes(), before)


    def test_missing_test_or_runtime_and_boolean_exit_are_rejected(self):
        reference = self.proof()
        envelope = knowledge.artifact(self.operations, reference)
        envelope["checks"] = envelope["checks"][:1]
        with self.assertRaisesRegex(ValueError, "CHECKS_INVALID"):
            knowledge.verification(self.operations, self.artifact("validation-1", "one.json", envelope))
        reference = self.proof()
        envelope = knowledge.artifact(self.operations, reference)
        check = knowledge.artifact(self.operations, envelope["checks"][0])
        check["exitCode"] = False
        envelope["checks"][0] = self.artifact("validation-1", "test.json", check)
        with self.assertRaisesRegex(ValueError, "CHECK_NOT_COMPLETED"):
            knowledge.verification(self.operations, self.artifact("validation-1", "verification.json", envelope))


if __name__ == "__main__":
    unittest.main()
