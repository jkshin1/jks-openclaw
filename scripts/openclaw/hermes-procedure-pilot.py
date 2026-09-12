#!/usr/bin/env python3
"""Offline synthetic report-procedure pilot; never calls a model or a live service.

Prepare exposes each phase's task separately from evaluator answers. Training
feedback may improve a declarative procedure; holdout verification requires that
same procedure and a passing training receipt. Runtime comparison accepts the
explicit observed-run-v1 import contract emitted in the prepared directory. It
cannot authenticate a runner's observations and never manufactures measurements.
"""

import argparse
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import sys


SPEC = importlib.util.spec_from_file_location(
    "procedure_pilot_fixtures", Path(__file__).with_name("agent-pilot-fixtures.py"))
FIXTURES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURES)
SCHEMA_VERSION = 1
PHASES = ("train", "holdout")
FAMILY = "synthetic-operations-report-procedure-v1"
TIMING_SCOPE = "task_input_available_to_verified_output"
RULES = {
    "selection": ("utc_then_event_id", "file_order", "timestamp_string"),
    "delivery_evidence": ("accepted_and_receipt", "accepted_only", "receipt_only"),
    "delivery_failure": ("retry_delivery_only", "rerun_execution"),
    "task_order": ("task_id", "input_order"),
    "duplicates": ("deduplicate_and_reject_conflicts", "count_all"),
}
PROCEDURE_CONTRACT = """\n# Procedure improvement task\n\nThis pilot uses public synthetic events only. Do not access personal/company data\nor any live profile, contact Telegram, or execute code from input. Produce both\n`report.json` (the exact report above) and `procedure.json` (a reusable rule plan).\nThe procedure root has exactly `schema_version: 1` and `rules`. Rules must contain\nexactly these keys, with one of the listed string values:\n""" + "\n".join("- " + key + ": " + ", ".join(values) for key, values in RULES.items()) + """\n\nTraining: inspect the supplied faulty candidate and correct general rules using\ntraining feedback. Do not hard-code task IDs, event IDs, dates, or expected counts.\nHoldout: use the unchanged training-validated procedure on these new events;\nchanging a rule creates a new candidate and must restart training. Never read\nother phase directories or evaluator answers. A passing result proves this\nbounded report procedure only; it is not a general learning or speed claim.\n"""
CONTRACT = FIXTURES.CONTRACT + PROCEDURE_CONTRACT


def require(value, code):
    if not value:
        raise ValueError(code)


def encoded(value):
    return FIXTURES.json_bytes(value)


def digest(value):
    if not isinstance(value, bytes):
        value = FIXTURES.canonical(value).encode()
    return hashlib.sha256(value).hexdigest()


def path_safe(path):
    path = Path(path).absolute()
    require(".." not in path.parts, "PARENT_PATH_REFUSED")
    return FIXTURES.safe_path(path)


def write_new(path, value):
    path = path_safe(path)
    payload = value if isinstance(value, bytes) else encoded(value)
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())


def read_json(path):
    return FIXTURES.read_json(path_safe(path))


def procedure(correct=True):
    rules = {key: options[0] for key, options in RULES.items()}
    if not correct:
        rules.update(selection="file_order", delivery_evidence="accepted_only",
                     delivery_failure="rerun_execution")
    return {"schema_version": 1, "rules": rules}


def events(phase):
    require(phase in PHASES, "UNKNOWN_PHASE")
    if phase == "train":
        return FIXTURES.fixture("train")
    row = FIXTURES.row
    records = [
        row("k-old", "kestrel", "2026-09-20T18:59:00+09:00", "completed", "accepted", "synthetic-901"),
        row("k-new", "kestrel", "2026-09-20T10:02:00Z", "running", "unknown"),
        row("i-only", "iris", "2026-09-20T12:00:00+02:00", "completed", "accepted"),
        row("o-old", "opal", "2026-09-20T19:00:00+09:00", "running", "unknown"),
        row("o-new", "opal", "2026-09-20T01:01:00-09:00", "completed", "failed"),
        row("p-a", "pine", "2026-09-20T21:05:00+11:00", "completed", "accepted", "synthetic-902"),
        row("p-z", "pine", "2026-09-20T10:05:00Z", "failed", "unknown", "synthetic-903"),
        row("w-only", "willow", "2026-09-20T10:04:00Z", "failed", "accepted", "synthetic-904"),
    ]
    order = (4, 6, 2, 7, 1, 5, 3, 0, 6, 2, 4)
    return {"schema_version": 1, "events": [deepcopy(records[i]) for i in order]}


def execute_procedure(payload, plan):
    """Interpret a small inert rule plan, independent of the reference oracle.

    Deliberately incorrect choices are executable for negative controls. No
    Python/shell code, source text, or arbitrary expressions are accepted.
    """
    require(type(plan) is dict and set(plan) == {"schema_version", "rules"}
            and type(plan["schema_version"]) is int and plan["schema_version"] == 1,
            "INVALID_PROCEDURE_SCHEMA")
    rules = plan["rules"]
    require(type(rules) is dict and set(rules) == set(RULES), "INVALID_PROCEDURE_RULES")
    for key, options in RULES.items():
        require(type(rules[key]) is str and rules[key] in options, "INVALID_RULE_VALUE")
    # Validate input even for intentionally wrong candidate choices.
    FIXTURES.derive_report(payload)
    rows = payload["events"]
    unique = {event["event_id"]: event for event in rows}
    selected = {}
    for event in unique.values():
        old = selected.get(event["task_id"])
        if rules["selection"] == "utc_then_event_id":
            key = lambda e: (datetime.fromisoformat(e["observed_at"].replace("Z", "+00:00")), e["event_id"])
        else:
            key = lambda e: e["observed_at"]
        if old is None or rules["selection"] == "file_order" or key(event) > key(old):
            selected[event["task_id"]] = event
    task_ids = sorted(selected) if rules["task_order"] == "task_id" else list(selected)
    tasks = []
    for task_id in task_ids:
        event = selected[task_id]
        accepted = event["delivery_status"] == "accepted"
        receipt = isinstance(event.get("message_id"), str) and bool(event["message_id"])
        delivered = {"accepted_and_receipt": accepted and receipt,
                     "accepted_only": accepted, "receipt_only": receipt}[rules["delivery_evidence"]]
        action = {"completed": "none", "running": "wait_for_execution",
                  "failed": "review_execution_failure"}[event["execution_status"]]
        if event["delivery_status"] == "failed" and rules["delivery_failure"] == "rerun_execution":
            action = "rerun_execution"
        tasks.append({"task_id": task_id, "latest_event_id": event["event_id"],
                      "observed_at_utc": datetime.fromisoformat(event["observed_at"].replace("Z", "+00:00"))
                          .astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                      "execution_status": event["execution_status"], "delivery_status": event["delivery_status"],
                      "message_id": event.get("message_id"), "telegram_delivered": delivered,
                      "execution_action": action,
                      "delivery_action": "none" if delivered else "retry_delivery_only"
                          if event["delivery_status"] == "failed" else "verify_receipt"})
    unique_count = len(unique) if rules["duplicates"] == "deduplicate_and_reject_conflicts" else len(rows)
    summary = {"input_event_rows": len(rows), "unique_events": unique_count,
               "duplicate_event_rows": len(rows) - unique_count, "tasks": len(tasks)}
    for status in ("completed", "running", "failed"):
        summary["execution_" + status] = sum(t["execution_status"] == status for t in tasks)
    summary["telegram_delivered"] = sum(t["telegram_delivered"] for t in tasks)
    summary["telegram_failed"] = sum(t["delivery_status"] == "failed" for t in tasks)
    summary["telegram_unconfirmed"] = sum(not t["telegram_delivered"] and t["delivery_status"] != "failed"
                                           for t in tasks)
    return {"schema_version": 1, "summary": summary, "tasks": tasks}


def task_files(phase):
    return {"events.json": encoded(events(phase)), "contract.md": CONTRACT.encode(),
            "candidate-procedure.json": encoded(procedure(False)) if phase == "train" else
                encoded({"instruction": "Supply the unchanged training-validated procedure separately."})}


def manifest():
    return {"schema_version": 1, "family": FAMILY, "synthetic_only": True,
            "phases": {phase: {name: digest(content) for name, content in task_files(phase).items()}
                       for phase in PHASES}}


def run_template():
    return {"schema_version": 1, "format": "observed-run-v1", "agent": None,
            "measurement_origin": "runtime", "status": None, "run_id": None,
            "phase": "holdout", "model": None, "provider": None, "reasoning_effort": None,
            "runtime_version": None, "input_sha256": None, "contract_sha256": digest(CONTRACT.encode()),
            "procedure_sha256": None, "report_path": None, "procedure_path": None,
            "training_receipt_path": None, "source_artifact_path": None, "source_artifact_sha256": None,
            "elapsed_seconds": None, "timing_scope": TIMING_SCOPE,
            "usage": {"noncache_input_tokens": None, "cache_read_tokens": None,
                      "cache_write_tokens": None, "output_tokens": None},
            "context_contract": "fresh_session_same_task_and_procedure_no_prior_events",
            "usage_scope": "all_model_calls_in_timing_scope", "error_code": None}


def prepare(root):
    root = path_safe(root)
    require(not root.exists(), "ROOT_MUST_BE_FRESH")
    require(root.parent.is_dir(), "ROOT_PARENT_MISSING")
    root.mkdir(mode=0o700)
    for phase in PHASES:
        task = root / phase
        task.mkdir(mode=0o700)
        for name, content in task_files(phase).items():
            write_new(task / name, content)
    (root / "evaluator").mkdir(mode=0o700)
    (root / "receipts").mkdir(mode=0o700)
    for phase in PHASES:
        write_new(root / "evaluator" / (phase + "-expected.json"), FIXTURES.derive_report(events(phase)))
    write_new(root / "manifest.json", manifest())
    write_new(root / "observed-run-template.json", run_template())
    write_new(root / "README.md", (
        "# Bounded report procedure pilot\n\nOnly provide the selected phase directory and candidate procedure to a model. "
        "Keep evaluator answers and holdout events out of training context. Use a fresh holdout session "
        "and unchanged training-validated procedure. The verifier recomputes answers independently.\n\n"
        "Runtime import: copy observed-run-template.json outside task directories. Fill it from a retained "
        "native runtime artifact and retain its SHA-256. Resolve paths relative to the envelope. "
        "Record the effective model/provider/effort, fresh-session context contract, and all model calls "
        "from task input availability through independently verified output, including retries and delegation. "
        "noncache_input_tokens excludes cache-read tokens; if source input semantics are unclear, leave it null. "
        "Cache-read, cache-write and output must be individually observed; absent counters stay null. "
        "Set status completed, capacity_blocked, failed, or not_measured. Never derive a USD price from total tokens. "
        "A source hash protects against later mutation, not a dishonest producer. Imported claims are trusted "
        "runner observations, not cryptographic proof. Existing legacy receipts need an audited adapter and "
        "cannot by themselves establish this new task/input/timing contract.\n\n"
        "`rehearse` demonstrates authored negative/corrected fixtures without any model. Its receipts "
        "cannot establish model learning or a runtime performance improvement.\n").encode())
    return {"ok": True, "root": str(root), "family": FAMILY, "synthetic_only": True,
            "runtime_status": "not_measured", "comparison_status": "blocked_missing_runtime_runs"}


def validate_root(root):
    root = path_safe(root)
    require(FIXTURES.canonical(read_json(root / "manifest.json")) == FIXTURES.canonical(manifest()),
            "MANIFEST_CHANGED")
    for phase, files in manifest()["phases"].items():
        for name, expected in files.items():
            require(digest(FIXTURES.read_file(root / phase / name)) == expected, "TASK_CHANGED")
    return root


def evaluate(phase, plan, report=None):
    expected = FIXTURES.derive_report(events(phase))
    actual = execute_procedure(events(phase), plan)
    reasons = []
    if FIXTURES.canonical(actual) != FIXTURES.canonical(expected):
        reasons.append("PROCEDURE_BEHAVIOR_MISMATCH")
    if report is not None and FIXTURES.canonical(report) != FIXTURES.canonical(expected):
        reasons.append("REPORT_CONTRACT_MISMATCH")
    return actual, reasons


def verify(root, phase, procedure_path, report_path=None, training_receipt_path=None, persist=True):
    root = validate_root(root)
    require(phase in PHASES, "UNKNOWN_PHASE")
    plan = read_json(procedure_path)
    report = read_json(report_path) if report_path else None
    actual, reasons = evaluate(phase, plan, report)
    training_digest = None
    if phase == "holdout":
        require(training_receipt_path is not None, "TRAINING_RECEIPT_REQUIRED")
        training = read_json(training_receipt_path)
        require(training.get("phase") == "train" and training.get("ok") is True
                and training.get("procedure_sha256") == digest(plan), "VALIDATED_PROCEDURE_REQUIRED")
        # Recompute the receipt from retained artifacts; edited or cross-root
        # approval files and changed training reports cannot authorize reuse.
        reconstructed = verify(root, "train", training["procedure_path"], training.get("report_path"), persist=False)
        reconstructed.pop("receipt_path", None)
        require(FIXTURES.canonical(reconstructed) == FIXTURES.canonical(training), "TRAINING_RECEIPT_CHANGED")
        training_digest = digest(training)
    result = {"schema_version": 1, "family": FAMILY, "synthetic_only": True,
              "scope": "deterministic_procedure_verification_not_model_learning", "root": str(root),
              "phase": phase, "ok": not reasons,
              "status": "rejected" if reasons else "training_validated" if phase == "train" else "holdout_reused",
              "error_codes": reasons, "input_sha256": digest(task_files(phase)["events.json"]),
              "contract_sha256": digest(CONTRACT.encode()), "procedure_sha256": digest(plan),
              "procedure_path": str(path_safe(procedure_path)),
              "report_sha256": digest(report) if report is not None else None,
              "report_path": str(path_safe(report_path)) if report_path else None,
              "evaluated_report_sha256": digest(actual), "training_receipt_sha256": training_digest}
    if persist:
        path = root / "receipts" / (phase + "-" + digest(result) + ".json")
        if path.exists():
            require(FIXTURES.canonical(read_json(path)) == FIXTURES.canonical(result), "RECEIPT_CHANGED")
        else:
            write_new(path, result)
        result["receipt_path"] = str(path)
    return result


def rehearse(root):
    """Exercise failure -> authored correction -> unchanged holdout reuse."""
    root = validate_root(root)
    rejected = verify(root, "train", root / "train/candidate-procedure.json")
    require(rejected["status"] == "rejected", "NEGATIVE_CONTROL_UNEXPECTEDLY_PASSED")
    corrected = root / "evaluator/authored-corrected-procedure.json"
    if not corrected.exists():
        write_new(corrected, procedure())
    trained = verify(root, "train", corrected)
    reused = verify(root, "holdout", corrected, training_receipt_path=trained["receipt_path"])
    require(trained["ok"] and reused["ok"], "REHEARSAL_FAILED")
    result = {"ok": True, "synthetic_only": True, "correction_origin": "authored_fixture",
              "model_learning": "not_measured", "runtime_comparison": "blocked_missing_runtime_runs",
              "rejected_candidate": rejected["receipt_path"], "training": trained["receipt_path"],
              "holdout_reuse": reused["receipt_path"]}
    path = root / "evaluator/rehearsal.json"
    if not path.exists():
        write_new(path, result)
    return result


def relative_artifact(envelope_path, value):
    require(type(value) is str and bool(value), "ARTIFACT_PATH_MISSING")
    path = Path(value)
    return path_safe(path if path.is_absolute() else Path(envelope_path).absolute().parent / path)


def validate_run(root, envelope_path, agent):
    envelope = read_json(envelope_path)
    require(type(envelope) is dict and set(envelope) == set(run_template()), "RUN_ENVELOPE_SCHEMA_MISMATCH")
    require(envelope["schema_version"] == 1 and type(envelope["schema_version"]) is int
            and envelope["format"] == "observed-run-v1" and envelope["agent"] == agent,
            "RUN_IDENTITY_MISMATCH")
    require(envelope["measurement_origin"] == "runtime", "RUNTIME_OBSERVATIONS_REQUIRED")
    require(envelope["status"] in {"completed", "capacity_blocked", "failed", "not_measured"},
            "RUN_STATUS_INVALID")
    if envelope["status"] != "completed":
        return {"agent": agent, "status": envelope["status"], "metrics": None}, [agent + ":" + envelope["status"]]
    for field in ("run_id", "model", "provider", "reasoning_effort", "runtime_version"):
        require(type(envelope[field]) is str and bool(envelope[field].strip()), "RUNTIME_METADATA_MISSING")
    require(envelope["phase"] == "holdout" and envelope["input_sha256"] == digest(task_files("holdout")["events.json"])
            and envelope["contract_sha256"] == digest(CONTRACT.encode()), "RUN_TASK_MISMATCH")
    require(envelope["context_contract"] == run_template()["context_contract"]
            and envelope["timing_scope"] == TIMING_SCOPE
            and envelope["usage_scope"] == "all_model_calls_in_timing_scope", "MEASUREMENT_SCOPE_MISMATCH")
    source = relative_artifact(envelope_path, envelope["source_artifact_path"])
    require(source != path_safe(envelope_path), "SELF_REFERENCING_SOURCE_REFUSED")
    require(digest(FIXTURES.read_file(source)) == envelope["source_artifact_sha256"], "SOURCE_ARTIFACT_CHANGED")
    plan_path = relative_artifact(envelope_path, envelope["procedure_path"])
    require(digest(read_json(plan_path)) == envelope["procedure_sha256"], "PROCEDURE_CHANGED")
    verification = verify(root, "holdout", plan_path,
                          relative_artifact(envelope_path, envelope["report_path"]),
                          relative_artifact(envelope_path, envelope["training_receipt_path"]), persist=False)
    require(verification["ok"], "RUNTIME_OUTPUT_INVALID")
    elapsed = envelope["elapsed_seconds"]
    require(elapsed is None or type(elapsed) in (int, float) and math.isfinite(elapsed) and elapsed > 0,
            "INVALID_ELAPSED_SECONDS")
    usage = envelope["usage"]
    require(type(usage) is dict and set(usage) == set(run_template()["usage"]), "USAGE_SCHEMA_MISMATCH")
    for value in usage.values():
        require(value is None or type(value) is int and value >= 0, "INVALID_USAGE_COUNTER")
    reasons = []
    if elapsed is None or any(value is None for value in usage.values()):
        reasons.append(agent + ":missing_measurements")
    elif not any(usage.values()):
        # Several runtimes initialize absent provider counters to zero. A
        # completed model turn cannot establish a zero-token measurement.
        reasons.append(agent + ":zero_usage_not_established")
    result = {"agent": agent, "status": "verified", "model": envelope["model"],
              "provider": envelope["provider"], "reasoning_effort": envelope["reasoning_effort"],
              "procedure_sha256": envelope["procedure_sha256"], "run_id": envelope["run_id"],
              "runtime_version": envelope["runtime_version"],
              "source_artifact_sha256": envelope["source_artifact_sha256"],
              "metrics": {"elapsed_seconds": elapsed, **usage}}
    return result, reasons


def compare(root, openclaw_run=None, hermes_run=None):
    root = validate_root(root)
    runs, reasons = {}, []
    for agent, path in (("openclaw", openclaw_run), ("hermes", hermes_run)):
        if path is None or not Path(path).exists():
            runs[agent] = {"agent": agent, "status": "not_measured", "metrics": None}
            reasons.append(agent + ":missing_runtime_run")
            continue
        try:
            runs[agent], errors = validate_run(root, path, agent)
            reasons.extend(errors)
        except (ValueError, OSError, KeyError, TypeError) as exc:
            runs[agent] = {"agent": agent, "status": "invalid_evidence", "metrics": None}
            reasons.append(agent + ":" + str(exc))
    if all(run["status"] == "verified" for run in runs.values()):
        for field in ("model", "provider", "reasoning_effort", "procedure_sha256"):
            if runs["openclaw"][field] != runs["hermes"][field]:
                reasons.append("incomparable:" + field)
    comparable = not reasons
    return {"ok": comparable, "family": FAMILY, "synthetic_only": True,
            "status": "measured_single_pair" if comparable else "blocked",
            "reasons": reasons, "runs": runs,
            "hermes_minus_openclaw": {key: runs["hermes"]["metrics"][key] - runs["openclaw"]["metrics"][key]
                                    for key in runs["hermes"]["metrics"]} if comparable else None,
            "claim_scope": "imported_runtime_observations_one_pair_no_general_speed_or_billing_claim",
            "provenance": "source_hash_verified_producer_observations_not_independently_authenticated"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("prepare", "verify", "rehearse", "compare"):
        child = commands.add_parser(name)
        child.add_argument("--root", type=Path, required=True)
        if name == "verify":
            child.add_argument("--phase", choices=PHASES, required=True)
            child.add_argument("--procedure", type=Path, required=True)
            child.add_argument("--report", type=Path)
            child.add_argument("--training-receipt", type=Path)
        if name == "compare":
            child.add_argument("--openclaw-run", type=Path)
            child.add_argument("--hermes-run", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args.root)
        elif args.command == "verify":
            result = verify(args.root, args.phase, args.procedure, args.report, args.training_receipt)
        elif args.command == "rehearse":
            result = rehearse(args.root)
        else:
            result = compare(args.root, args.openclaw_run, args.hermes_run)
    except (ValueError, OSError, UnicodeError, KeyError, TypeError) as exc:
        result = {"ok": False, "status": "blocked", "error": str(exc)}
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, allow_nan=False))
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
