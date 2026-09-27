#!/usr/bin/env python3
"""Private, evidence-bound operations knowledge; model observations cannot close issues.

Verification is an operator-attested chain of hashed command receipts and outputs in
operations/runs. It establishes recorded command outcomes, not cryptographic proof of
who ran them. Arbitrary ``verified: true`` flags are never accepted as verification.
"""

from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile

SCHEMA_VERSION = 1
SAFE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}\Z")
SHA256 = re.compile(r"[a-f0-9]{64}\Z")
CLASSIFICATIONS = {"observation", "issue", "improvement", "legacy-unclassified"}
STATES = {"observed", "open", "in_progress", "deferred", "resolved"}
MAX_BYTES = 2 * 1024 * 1024
MAX_ITEMS = 512
SECRET_PATTERN = re.compile(
    r"(?i)(?:bearer\s+\S+|(?:api[_ -]?key|access[_ -]?token|password|secret)\s*[:=]\s*\S+|"
    r"sk-[A-Za-z0-9_-]{12,}|\b\d{6,}:[A-Za-z0-9_-]{20,})"
)


def require(value, message):
    if not value:
        raise ValueError(message)


def identifier(value, label="ID"):
    require(isinstance(value, str) and SAFE_ID.fullmatch(value), label + "_INVALID")
    return value


def sha(value):
    require(isinstance(value, str) and SHA256.fullmatch(value), "SHA256_INVALID")
    return value


def clean_text(value):
    require(isinstance(value, str) and 0 < len(value.strip()) <= 400, "CURATED_TEXT_INVALID")
    require(not any(ord(char) < 32 for char in value) and not SECRET_PATTERN.search(value),
            "CURATED_TEXT_UNSAFE")
    return value.strip()


def text_list(value):
    require(isinstance(value, list) and 1 <= len(value) <= 8, "CURATED_LIST_INVALID")
    return [clean_text(item) for item in value]


def versions(value):
    require(isinstance(value, dict) and 1 <= len(value) <= 8, "VERSIONS_INVALID")
    return {identifier(key, "VERSION_KEY"): identifier(item, "VERSION")
            for key, item in sorted(value.items())}


def safe(path, exists=False):
    path = Path(path)
    require(path.is_absolute() and ".." not in path.parts, "ABSOLUTE_PATH_REQUIRED")
    require(not any(item.is_symlink() for item in (path, *path.parents)), "SYMLINK_REFUSED")
    require(not exists or path.exists(), "PATH_MISSING")
    if path.exists():
        require(path.stat().st_uid == os.getuid(), "OWNER_MISMATCH")
    return path


def private_dir(path):
    path = safe(path)
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(path.is_dir(), "DIRECTORY_REQUIRED")
    path.chmod(0o700)
    return path


def reject_constant(value):
    raise ValueError("NONFINITE_JSON_REFUSED")


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, "DUPLICATE_JSON_KEY_REFUSED")
        value[key] = item
    return value


def decode(data):
    return json.loads(data, parse_constant=reject_constant, object_pairs_hook=unique_object)


def encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, allow_nan=False).encode()


def now():
    return datetime.now(timezone.utc).isoformat()


def read_json(path):
    path = safe(path, True)
    require(path.is_file() and path.stat().st_size <= MAX_BYTES, "JSON_FILE_INVALID")
    return decode(path.read_bytes())


def write_json(path, value):
    path = safe(path)
    data = encode(value) + b"\n"
    require(len(data) <= MAX_BYTES, "KNOWLEDGE_CAPACITY_EXCEEDED")
    fd, temporary = tempfile.mkstemp(prefix=".knowledge-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def empty_state():
    return {"schemaVersion": 1, "revision": 0, "operatorRevision": 0, "findings": {}, "experiences": {},
            "procedures": {}, "reuses": {}, "reviewRuns": []}


def validate_state(state):
    require(isinstance(state, dict) and type(state.get("schemaVersion")) is int and state["schemaVersion"] == 1, "KNOWLEDGE_SCHEMA_INVALID")
    require(type(state.get("revision")) is int and state["revision"] >= 0, "KNOWLEDGE_REVISION_INVALID")
    require(type(state.get("operatorRevision")) is int and state["operatorRevision"] >= 0,
            "KNOWLEDGE_OPERATOR_REVISION_INVALID")
    for group in ("findings", "experiences", "procedures", "reuses"):
        values = state.get(group)
        require(isinstance(values, dict) and len(values) <= MAX_ITEMS, "KNOWLEDGE_COLLECTION_INVALID")
        for key, value in values.items():
            identifier(key)
            require(isinstance(value, dict) and value.get("id") == key, "KNOWLEDGE_RECORD_INVALID")
    for finding in state["findings"].values():
        require(finding.get("classification") in CLASSIFICATIONS and finding.get("state") in STATES,
                "FINDING_STATE_INVALID")
        require((finding["state"] == "observed") != (finding["classification"] in {"issue", "improvement"}),
                "FINDING_CLASSIFICATION_STATE_MISMATCH")
        if finding["state"] in {"in_progress", "deferred", "resolved"}:
            identifier(finding.get("owner"), "OWNER")
        if finding["state"] == "deferred":
            clean_text(finding.get("deferReason"))
        if finding["state"] == "resolved":
            reference_shape(finding.get("closureRef"))
        require(type(finding.get("seenCount")) is int and finding["seenCount"] >= 1,
                "FINDING_COUNT_INVALID")
        identifier(finding.get("lastRunId"), "RUN_ID")
        if finding.get("owner") is not None:
            identifier(finding["owner"], "OWNER")
        if finding.get("deferReason"):
            clean_text(finding["deferReason"])
        if "origin" in finding:
            origin = finding["origin"]
            require(isinstance(origin, dict) and origin.get("kind") in {"operator", "hermes-review"},
                    "FINDING_ORIGIN_INVALID")
            identifier(origin.get("runId"), "RUN_ID")
            if origin["kind"] == "operator":
                identifier(origin.get("operator"), "OPERATOR")
            if "sourceRef" in origin:
                reference_shape(origin["sourceRef"])
        require(isinstance(finding.get("history"), list) and len(finding["history"]) <= 32,
                "FINDING_HISTORY_INVALID")
    for experience in state["experiences"].values():
        validate_experience_fields(experience)
        require(experience["findingId"] in state["findings"], "EXPERIENCE_FINDING_MISSING")
        reference_shape(experience.get("verificationRef"))
        require(experience.get("verificationOutcome") in {"passed", "failed"}, "EXPERIENCE_OUTCOME_INVALID")
    for procedure in state["procedures"].values():
        require(type(procedure.get("version")) is int and procedure["version"] >= 1,
                "PROCEDURE_VERSION_INVALID")
        identifier(procedure.get("experienceId"))
        require(procedure["experienceId"] in state["experiences"], "PROCEDURE_EXPERIENCE_MISSING")
        require(isinstance(procedure.get("procedure"), str) and len(procedure["procedure"]) <= 16000,
                "PROCEDURE_CONTENT_INVALID")
        require(sha(procedure.get("sha256")) == hashlib.sha256(procedure["procedure"].encode()).hexdigest(),
                "PROCEDURE_HASH_INVALID")
        versions(procedure.get("versions"))
        experience = state["experiences"][procedure["experienceId"]]
        require(procedure["procedure"] == render_procedure(experience)
                and procedure["versions"] == experience["versions"]
                and procedure.get("verificationRef") == experience.get("verificationRef"),
                "PROCEDURE_EXPERIENCE_MISMATCH")
        identifier(procedure.get("operator"), "OPERATOR")
        clean_text(procedure.get("title"))
    for reuse in state["reuses"].values():
        require(reuse.get("outcome") in {"verified", "failed"}, "REUSE_OUTCOME_INVALID")
        identifier(reuse.get("procedureId"))
        identifier(reuse.get("runId"))
        require(type(reuse.get("procedureVersion")) is int and reuse["procedureVersion"] >= 1,
                "PROCEDURE_VERSION_INVALID")
        sha(reuse.get("procedureSha256"))
        require(reuse["procedureId"] in state["procedures"], "REUSE_PROCEDURE_MISSING")
        reference_shape(reuse.get("verificationRef"))
        identifier(reuse.get("operator"), "OPERATOR")
    require(isinstance(state.get("reviewRuns"), list) and len(state["reviewRuns"]) <= MAX_ITEMS,
            "REVIEW_RUNS_INVALID")
    for run_id in state["reviewRuns"]:
        identifier(run_id, "RUN_ID")
    # A roundtrip rejects non-finite values anywhere, including unused extra fields.
    encode(state)
    return state


def load_state(operations):
    path = safe(Path(operations) / "knowledge.json")
    return validate_state(read_json(path)) if path.exists() else empty_state()


@contextmanager
def transaction(operations, *, operator=False):
    operations = private_dir(Path(operations))
    lock_path = safe(operations / "knowledge.lock")
    fd = os.open(lock_path, os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        require(os.fstat(fd).st_uid == os.getuid(), "OWNER_MISMATCH")
        require(stat.S_ISREG(os.fstat(fd).st_mode) and os.fstat(fd).st_nlink == 1, "LOCK_FILE_INVALID")
        os.fchmod(fd, 0o600)
        fcntl.flock(fd, fcntl.LOCK_EX)
        state = load_state(operations)
        before = encode(state)
        yield state
        if encode(state) != before:
            state["revision"] += 1
            if operator:
                state["operatorRevision"] += 1
            validate_state(state)
            write_json(operations / "knowledge.json", state)
    finally:
        os.close(fd)


def reference_shape(ref):
    require(isinstance(ref, dict) and set(ref) == {"path", "sha256"}, "ARTIFACT_REFERENCE_INVALID")
    relative = ref.get("path")
    require(isinstance(relative, str) and len(relative) <= 240, "ARTIFACT_PATH_INVALID")
    path = Path(relative)
    require(not path.is_absolute() and len(path.parts) >= 3 and path.parts[0] == "runs"
            and ".." not in path.parts and str(path) == relative, "ARTIFACT_PATH_INVALID")
    identifier(path.parts[1], "RUN_ID")
    sha(ref.get("sha256"))
    return path


def artifact(operations, ref, *, run_id=None, as_json=True):
    path = reference_shape(ref)
    require(run_id is None or path.parts[1] == run_id, "ARTIFACT_RUN_MISMATCH")
    safe(Path(operations), True)
    safe(Path(operations) / "runs", True)
    safe(Path(operations) / "runs" / path.parts[1], True)
    target = safe(Path(operations) / path, True)
    require(target.is_file() and 0 < target.stat().st_size <= MAX_BYTES, "ARTIFACT_FILE_INVALID")
    data = target.read_bytes()
    require(hashlib.sha256(data).hexdigest() == sha(ref.get("sha256")), "ARTIFACT_HASH_MISMATCH")
    if as_json:
        value = decode(data)
        require(isinstance(value, dict), "ARTIFACT_SHAPE_INVALID")
        return value
    return data


def timestamp(value):
    require(isinstance(value, str), "CHECK_TIMESTAMP_INVALID")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise ValueError("CHECK_TIMESTAMP_INVALID") from None
    require(parsed.tzinfo is not None, "CHECK_TIMESTAMP_INVALID")
    return parsed


def verification(operations, ref, *, finding_id=None, run_id=None, expected_versions=None,
                 change_id=None, require_success=True, expected_procedure=None):
    value = artifact(operations, ref, run_id=run_id)
    require(type(value.get("schemaVersion")) is int and value["schemaVersion"] == 1 and value.get("kind") == "hermes-ops-verification",
            "VERIFICATION_SCHEMA_INVALID")
    actual_run = identifier(value.get("runId"), "RUN_ID")
    require(Path(ref["path"]).parts[1] == actual_run and (run_id is None or actual_run == run_id),
            "VERIFICATION_RUN_MISMATCH")
    findings = value.get("findingIds")
    require(isinstance(findings, list) and 1 <= len(findings) <= 32, "VERIFICATION_FINDINGS_INVALID")
    for item in findings:
        identifier(item, "FINDING_ID")
    require(finding_id is None or finding_id in findings, "VERIFICATION_FINDING_MISMATCH")
    actual_change = identifier(value.get("changeId"), "CHANGE_ID")
    require(change_id is None or actual_change == change_id, "VERIFICATION_CHANGE_MISMATCH")
    if expected_procedure is not None:
        require(value.get("procedure") == expected_procedure, "VERIFICATION_PROCEDURE_MISMATCH")
    actual_versions = versions(value.get("versions"))
    require(expected_versions is None or actual_versions == versions(expected_versions),
            "VERIFICATION_VERSION_MISMATCH")
    checks = value.get("checks")
    require(isinstance(checks, list) and 2 <= len(checks) <= 16, "VERIFICATION_CHECKS_INVALID")
    kinds, seen, successful = set(), set(), True
    for reference in checks:
        check = artifact(operations, reference, run_id=actual_run)
        require(type(check.get("schemaVersion")) is int and check["schemaVersion"] == 1 and check.get("kind") == "hermes-ops-command-check",
                "CHECK_SCHEMA_INVALID")
        require(check.get("runId") == actual_run, "CHECK_RUN_MISMATCH")
        check_id = identifier(check.get("checkId"), "CHECK_ID")
        require(check_id not in seen, "CHECK_DUPLICATED")
        seen.add(check_id)
        kind = check.get("checkType")
        require(kind in {"test", "runtime"}, "CHECK_TYPE_INVALID")
        kinds.add(kind)
        if expected_procedure is not None and kind == "runtime":
            require(check.get("procedure") == expected_procedure, "CHECK_PROCEDURE_MISMATCH")
        require(check.get("status") == "completed" and type(check.get("exitCode")) is int,
                "CHECK_NOT_COMPLETED")
        require(timestamp(check.get("finishedAt")) >= timestamp(check.get("startedAt")),
                "CHECK_TIMESTAMP_ORDER_INVALID")
        sha(check.get("commandSha256"))
        artifact(operations, check.get("output"), run_id=actual_run, as_json=False)
        successful = successful and check["exitCode"] == 0
    require(kinds == {"test", "runtime"}, "TEST_AND_RUNTIME_REQUIRED")
    require(not require_success or successful, "VERIFICATION_FAILED")
    return {"runId": actual_run, "changeId": actual_change, "versions": actual_versions,
            "findingIds": findings, "successful": successful, "checkCount": len(checks)}


def observe_review(operations, run_id, result, receipt):
    """Observe fixed-shape model metadata only; absence and prose never close findings."""
    identifier(run_id, "RUN_ID")
    require(isinstance(result, dict) and isinstance(receipt, dict), "REVIEW_SHAPE_INVALID")
    require(receipt.get("status") == "reviewed" and receipt.get("ok") is True
            and receipt.get("runId") == run_id, "REVIEW_NOT_COMPLETED")
    findings = result.get("findings", [])
    require(isinstance(findings, list) and len(findings) <= 64, "REVIEW_FINDINGS_INVALID")
    with transaction(operations) as state:
        if run_id in state["reviewRuns"]:
            return {"status": "already-observed", "runId": run_id}
        seen = set()
        for item in findings:
            require(isinstance(item, dict), "REVIEW_FINDING_INVALID")
            finding_id = identifier(item.get("id"), "FINDING_ID")
            if finding_id in seen:
                continue
            seen.add(finding_id)
            classification = item.get("kind", "legacy-unclassified")
            require(classification in CLASSIFICATIONS, "FINDING_CLASSIFICATION_INVALID")
            # The worker drops citations it cannot verify and labels the finding. The label follows
            # the latest observation, so a later supported sighting clears it.
            insufficient = item.get("evidenceStatus") == "insufficient"
            existing = state["findings"].get(finding_id)
            if existing:
                existing["seenCount"] += 1
                existing["lastRunId"] = run_id
                # A repeated ID is an observation, not automatic reopening or classification.
                if existing["state"] == "resolved":
                    existing["seenAfterResolution"] = True
                if insufficient:
                    existing["evidenceInsufficient"] = True
                else:
                    existing.pop("evidenceInsufficient", None)
            else:
                state["findings"][finding_id] = {
                    "id": finding_id, "classification": classification,
                    "state": "observed" if classification in {"observation", "legacy-unclassified"} else "open",
                    "owner": None, "seenCount": 1, "firstRunId": run_id, "lastRunId": run_id,
                    "origin": {"kind": "hermes-review", "runId": run_id},
                    "history": [{"at": now(), "action": "observed", "runId": run_id}],
                }
                if insufficient:
                    state["findings"][finding_id]["evidenceInsufficient"] = True
        # Reference claims are kept as identifiers only, without promoting or marking reuse.
        claims = result.get("procedure_uses", [])
        require(isinstance(claims, list) and len(claims) <= 16, "PROCEDURE_CLAIMS_INVALID")
        claimed_ids = set()
        for claim in claims:
            require(isinstance(claim, dict), "PROCEDURE_CLAIM_INVALID")
            procedure_id = identifier(claim.get("procedure_id"))
            require(type(claim.get("version")) is int and claim["version"] >= 1, "PROCEDURE_VERSION_INVALID")
            sha(claim.get("sha256"))
            require(claim.get("conclusion") in {"referenced", "applicable", "not_applicable", "reuse_claimed"},
                    "PROCEDURE_CLAIM_INVALID")
            if procedure_id in claimed_ids:
                continue
            claimed_ids.add(procedure_id)
            procedure = state["procedures"].get(procedure_id)
            if procedure and claim.get("version") == procedure["version"] and claim.get("sha256") == procedure["sha256"]:
                procedure["lastReferencedRunId"] = run_id
                procedure["referenceCount"] = procedure.get("referenceCount", 0) + 1
        state["reviewRuns"] = (state["reviewRuns"] + [run_id])[-MAX_ITEMS:]
        return {"status": "observed", "runId": run_id, "findingCount": len(seen)}


def register_finding(operations, finding_id, *, classification, operator, run_id, owner=None,
                     source_ref=None):
    """Register an operator observation without attributing it to a Hermes review.

    An optional captured source proves what the operator inspected, not successful
    recovery. Verification, resolution and promotion remain separate operations.
    """
    identifier(finding_id, "FINDING_ID")
    identifier(operator, "OPERATOR")
    identifier(run_id, "RUN_ID")
    owner = identifier(owner or operator, "OWNER")
    require(classification in {"observation", "issue", "improvement"}, "FINDING_CLASSIFICATION_INVALID")
    origin = {"kind": "operator", "operator": operator, "runId": run_id}
    if source_ref is not None:
        artifact(operations, source_ref, run_id=run_id, as_json=False)
        origin["sourceRef"] = source_ref
    with transaction(operations, operator=True) as state:
        existing = state["findings"].get(finding_id)
        if existing is not None:
            require(existing.get("origin") == origin, "FINDING_ALREADY_EXISTS")
            return dict(existing)
        row = {"id": finding_id, "classification": classification,
               "state": "observed" if classification == "observation" else "open",
               "owner": owner, "seenCount": 1, "firstRunId": run_id, "lastRunId": run_id,
               "origin": origin, "history": [{"at": now(), "action": "operator-registration",
                                              "runId": run_id, "operator": operator}]}
        state["findings"][finding_id] = row
        return dict(row)


def update_finding(operations, finding_id, *, owner=None, state=None, classification=None,
                   defer_reason=None, closure_ref=None):
    identifier(finding_id, "FINDING_ID")
    with transaction(operations, operator=True) as knowledge:
        require(finding_id in knowledge["findings"], "FINDING_UNKNOWN")
        finding = knowledge["findings"][finding_id]
        if owner is not None:
            finding["owner"] = identifier(owner, "OWNER")
        if classification is not None:
            require(classification in CLASSIFICATIONS, "FINDING_CLASSIFICATION_INVALID")
            finding["classification"] = classification
        target = state or finding["state"]
        require(target in STATES, "FINDING_STATE_INVALID")
        issue = finding["classification"] in {"issue", "improvement"}
        require((target == "observed") != issue, "FINDING_CLASSIFICATION_STATE_MISMATCH")
        if target in {"in_progress", "deferred", "resolved"}:
            require(finding.get("owner") is not None, "FINDING_OWNER_REQUIRED")
        if target == "deferred":
            finding["deferReason"] = clean_text(defer_reason or finding.get("deferReason"))
        else:
            finding.pop("deferReason", None)
        if target == "resolved":
            reference = closure_ref or finding.get("closureRef")
            proof = verification(operations, reference, finding_id=finding_id)
            finding["closureRef"] = reference
            finding["closureRunId"] = proof["runId"]
        elif target != finding["state"]:
            finding.pop("closureRef", None)
            finding.pop("closureRunId", None)
            finding.pop("seenAfterResolution", None)
        finding["state"] = target
        finding["history"] = (finding["history"] + [{"at": now(), "action": "operator-update",
            "state": target, "classification": finding["classification"], "owner": finding.get("owner") }])[-32:]
        return dict(finding)


def validate_experience_fields(payload):
    require(isinstance(payload, dict), "EXPERIENCE_INVALID")
    for key in ("id", "findingId", "runId", "changeId", "operator"):
        identifier(payload.get(key), key.upper())
    for key in ("cause", "change"):
        clean_text(payload.get(key))
    for key in ("preconditions", "steps", "validation"):
        text_list(payload.get(key))
    versions(payload.get("versions"))


def record_experience(operations, payload):
    """An operator supplies curated cause/procedure and independently captured receipts."""
    validate_experience_fields(payload)
    proof = verification(operations, payload.get("verificationRef"), finding_id=payload["findingId"],
                         run_id=payload["runId"], expected_versions=payload["versions"],
                         change_id=payload["changeId"], require_success=False)
    with transaction(operations, operator=True) as state:
        require(payload["findingId"] in state["findings"], "FINDING_UNKNOWN")
        require(payload["id"] not in state["experiences"], "EXPERIENCE_ALREADY_EXISTS")
        keys = ("id", "findingId", "runId", "cause", "change", "versions", "changeId",
                "preconditions", "steps", "validation", "verificationRef", "operator")
        entry = {key: payload[key] for key in keys}
        entry.update(recordedAt=now(), verificationOutcome="passed" if proof["successful"] else "failed")
        state["experiences"][entry["id"]] = entry
        return dict(entry)


def render_procedure(experience):
    lines = ["Cause: " + experience["cause"], "Change: " + experience["change"],
             "Validated versions: " + json.dumps(versions(experience["versions"]), sort_keys=True)]
    for key in ("preconditions", "steps", "validation"):
        lines.append(key.capitalize() + ":")
        lines.extend(str(index) + ". " + item for index, item in enumerate(experience[key], 1))
    return "\n".join(lines)


def promote_procedure(operations, experience_id, *, operator, procedure_id=None):
    identifier(experience_id, "EXPERIENCE_ID")
    identifier(operator, "OPERATOR")
    procedure_id = identifier(procedure_id or experience_id, "PROCEDURE_ID")
    with transaction(operations, operator=True) as state:
        require(experience_id in state["experiences"], "EXPERIENCE_UNKNOWN")
        experience = state["experiences"][experience_id]
        proof = verification(operations, experience["verificationRef"], finding_id=experience["findingId"],
                             run_id=experience["runId"], expected_versions=experience["versions"],
                             change_id=experience["changeId"])
        previous = state["procedures"].get(procedure_id)
        if previous and previous["experienceId"] == experience_id:
            return dict(previous)
        body = render_procedure(experience)
        entry = {"id": procedure_id, "version": (previous["version"] + 1) if previous else 1,
                 "sha256": hashlib.sha256(body.encode()).hexdigest(), "title": procedure_id,
                 "procedure": body, "versions": experience["versions"], "experienceId": experience_id,
                 "verificationRef": experience["verificationRef"], "verificationRunId": proof["runId"],
                 "operator": operator, "promotedAt": now()}
        entry["history"] = [] if previous is None else previous.get("history", []) + [
            {key: previous[key] for key in ("version", "sha256", "experienceId", "verificationRef",
                                            "operator", "promotedAt")}]
        require(len(entry["history"]) <= 128, "PROCEDURE_HISTORY_CAPACITY_EXCEEDED")
        state["procedures"][procedure_id] = entry
        return dict(entry)


def record_reuse(operations, payload):
    require(isinstance(payload, dict), "REUSE_INVALID")
    for key in ("id", "procedureId", "runId", "operator"):
        identifier(payload.get(key), key.upper())
    require(type(payload.get("procedureVersion")) is int and payload["procedureVersion"] >= 1,
            "PROCEDURE_VERSION_INVALID")
    sha(payload.get("procedureSha256"))
    with transaction(operations, operator=True) as state:
        require(payload["id"] not in state["reuses"], "REUSE_ALREADY_EXISTS")
        require(not any(row["procedureId"] == payload["procedureId"]
                        and row["procedureVersion"] == payload["procedureVersion"]
                        and row["runId"] == payload["runId"] for row in state["reuses"].values()),
                "REUSE_RUN_ALREADY_RECORDED")
        require(payload["procedureId"] in state["procedures"], "PROCEDURE_UNKNOWN")
        procedure = state["procedures"][payload["procedureId"]]
        require(payload["procedureVersion"] == procedure["version"]
                and payload["procedureSha256"] == procedure["sha256"], "REUSE_PROCEDURE_MISMATCH")
        require(payload["runId"] != procedure["verificationRunId"], "REUSE_REQUIRES_NEW_RUN")
        original = state["experiences"][procedure["experienceId"]]
        # The original promotion evidence must still be intact at reuse time.
        verification(operations, procedure["verificationRef"], finding_id=original["findingId"],
                     run_id=original["runId"], expected_versions=procedure["versions"], change_id=original["changeId"])
        proof = verification(operations, payload.get("verificationRef"), finding_id=original["findingId"],
                             run_id=payload["runId"], expected_versions=procedure["versions"],
                             require_success=False, expected_procedure={
                                 "id": procedure["id"], "version": procedure["version"], "sha256": procedure["sha256"]})
        keys = ("id", "procedureId", "procedureVersion", "procedureSha256", "runId", "verificationRef", "operator")
        entry = {key: payload[key] for key in keys}
        entry.update(recordedAt=now(), outcome="verified" if proof["successful"] else "failed")
        state["reuses"][entry["id"]] = entry
        return dict(entry)


def summary(operations, current_versions=None):
    """Bounded model input; stored prose is included only after operator promotion and proof."""
    state = load_state(operations)
    if current_versions is not None:
        require(isinstance(current_versions, dict), "VERSIONS_INVALID")
        for key, value in current_versions.items():
            identifier(key, "VERSION_KEY")
            identifier(value, "VERSION")
    findings = []
    ordered = sorted(state["findings"].values(), key=lambda row: (row["state"] in {"resolved", "observed"}, row["id"]))
    for finding in ordered[:64]:
        row = {key: finding[key] for key in ("id", "classification", "state", "owner", "seenCount", "lastRunId")}
        origin = finding.get("origin", {"kind": "legacy-unknown"})
        row["origin"] = {key: origin[key] for key in ("kind", "operator", "runId") if key in origin}
        if origin.get("sourceRef"):
            try:
                artifact(operations, origin["sourceRef"], run_id=origin["runId"], as_json=False)
                row["origin"]["sourceEvidenceValid"] = True
            except (ValueError, OSError):
                row["origin"]["sourceEvidenceValid"] = False
        if finding.get("deferReason"):
            row["deferReason"] = finding["deferReason"]
        if finding.get("seenAfterResolution"):
            row["seenAfterResolution"] = True
        if finding.get("evidenceInsufficient"):
            row["evidenceInsufficient"] = True
        if finding["state"] == "resolved":
            try:
                verification(operations, finding.get("closureRef"), finding_id=finding["id"])
                row["closureEvidenceValid"] = True
            except (ValueError, OSError):
                row["closureEvidenceValid"] = False
        findings.append(row)
    available, unavailable, stats = [], [], []
    total_procedure_bytes = 0
    for procedure in sorted(state["procedures"].values(), key=lambda row: row["id"]):
        experience = state["experiences"][procedure["experienceId"]]
        reason = None
        try:
            verification(operations, procedure["verificationRef"], finding_id=experience["findingId"],
                         run_id=experience["runId"], expected_versions=procedure["versions"],
                         change_id=experience["changeId"])
        except (ValueError, OSError):
            reason = "evidence-invalid"
        if not reason and (current_versions is None or any(current_versions.get(key) != value
                                                          for key, value in procedure["versions"].items())):
            reason = "version-mismatch"
        if reason:
            if len(unavailable) < 32:
                unavailable.append({"id": procedure["id"], "version": procedure["version"], "reason": reason})
            continue
        entry = {key: procedure[key] for key in ("id", "version", "sha256", "title", "procedure")}
        entry["evidenceIds"] = ["procedureKnowledge"]
        entry_bytes = len(encode(entry))
        if len(available) >= 8 or total_procedure_bytes + entry_bytes > 48000:
            continue
        available.append(entry)
        total_procedure_bytes += entry_bytes
        successful, failed, invalid = 0, 0, 0
        for reuse in state["reuses"].values():
            if reuse["procedureId"] != procedure["id"] or reuse["procedureVersion"] != procedure["version"]:
                continue
            try:
                proof = verification(operations, reuse["verificationRef"], finding_id=experience["findingId"],
                                     run_id=reuse["runId"], expected_versions=procedure["versions"], require_success=False,
                                     expected_procedure={"id": procedure["id"], "version": procedure["version"],
                                                         "sha256": procedure["sha256"]})
                if proof["successful"]:
                    successful += 1
                else:
                    failed += 1
            except (ValueError, OSError):
                invalid += 1
        stats.append({"id": procedure["id"], "version": procedure["version"], "successfulReuses": successful,
                      "failedReuses": failed, "invalidReuseEvidence": invalid,
                      "referenceCount": procedure.get("referenceCount", 0)})
    return {"schemaVersion": 1, "scope": "operator-verified-operations-knowledge", "revision": state["revision"],
            "operatorRevision": state["operatorRevision"], "procedures": available, "findingLifecycle": findings, "unavailableProcedures": unavailable,
            "procedureStats": stats,
            "counts": {key: len(state[key]) for key in ("findings", "experiences", "procedures", "reuses")}}
