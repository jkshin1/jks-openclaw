#!/usr/bin/env python3
"""Prepare and independently verify public synthetic agent repeat-work fixtures.

This module never calls a model, Telegram, or owner data. The evaluator computes
the answer from validated events; no reference answer is written into a run.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import NamedTuple


SCHEMA_VERSION = 1
MAX_FILE_BYTES = 1024 * 1024
VARIANTS = ("train", "repeat")
CONTRACT = """# Synthetic operations report contract

Read only the supplied synthetic `events.json`; do not use owner data or contact
Telegram. Produce `report.json` using the exact JSON schema and rules below.
An optional Korean `report.md` may explain the result; only JSON is graded.

## Input and selection

The input object has `schema_version: 1` and an `events` array. Each event has
`event_id`, `task_id`, `observed_at`, `execution_status`, `delivery_status`, and
an optional `message_id`. Missing or null message IDs mean there is no receipt.
Times are ISO 8601 seconds with an explicit Z or numeric UTC offset.
Execution states are `completed`, `running`, `failed`; delivery attempt states
are `accepted`, `unknown`, `failed`. These are independent observations.

1. Deduplicate identical rows by `event_id`; conflicting duplicates are errors.
   Object key order does not matter, but all fields and values must be identical.
2. Select one latest event per task by actual UTC time, NOT file order or the
   timestamp string. For equal UTC times, the lexicographically greatest
   `event_id` wins. Report only that selected snapshot, not historical success.
3. Sort the output `tasks` array lexicographically by `task_id`.
4. `telegram_delivered` is true ONLY when the selected delivery status is
   `accepted` AND its `message_id` is a nonempty string. An `unknown` status
   remains unconfirmed even if it has a message ID. Accepted without a receipt
   is also unconfirmed. Acceptance does not establish that a person read it.
5. A failed delivery means retry transport only; never recommend executing the
   underlying task again merely because transport failed.

## Exact output schema

The root has exactly `schema_version` (integer 1), `summary`, and `tasks`.
`summary` has exactly these integer fields:
- `input_event_rows`: original array length, including duplicates.
- `unique_events`: number of distinct event IDs.
- `duplicate_event_rows`: input_event_rows minus unique_events.
- `tasks`: number of distinct task IDs.
- `execution_completed`, `execution_running`, `execution_failed`: selected
  snapshots with each execution state; these three sum to tasks.
- `telegram_delivered`: selected snapshots meeting rule 4.
- `telegram_unconfirmed`: selected snapshots with unknown status or accepted
  status without a receipt.
- `telegram_failed`: selected snapshots with failed delivery status.
  These last three fields also sum to tasks.

Every element of `tasks` has exactly these fields:
- `task_id`, `latest_event_id`: strings from the selected event.
- `observed_at_utc`: selected time formatted as `YYYY-MM-DDTHH:MM:SSZ`.
- `execution_status`, `delivery_status`: unchanged selected strings.
- `message_id`: selected string or JSON null if missing/null.
- `telegram_delivered`: JSON boolean using rule 4.
- `execution_action`: `none` for completed, `wait_for_execution` for running,
  or `review_execution_failure` for failed.
- `delivery_action`: `none` if telegram_delivered is true,
  `retry_delivery_only` for failed delivery status, otherwise `verify_receipt`.

JSON object key order and whitespace are ignored. Extra fields, wrong types,
wrong array ordering, missing tasks, or duplicate JSON keys fail verification.
Leave the input, this contract, and the fixture manifest unchanged.
"""


class Event(NamedTuple):
    event_id: str
    task_id: str
    observed_at: datetime
    execution_status: str
    delivery_status: str
    message_id: str | None


def require(condition, message):
    if not condition:
        raise ValueError(message)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                      allow_nan=False)


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key: " + key)
        result[key] = value
    return result


def no_constant(value):
    raise ValueError("non-finite JSON number: " + value)


def read_json(path):
    return json.loads(read_file(path).decode("utf-8"), object_pairs_hook=unique_object,
                      parse_constant=no_constant)


def safe_path(path):
    path = Path(path).absolute()
    require(not any(part.is_symlink() for part in (path, *path.parents)),
            "symlink path rejected")
    return path


def read_file(path):
    path = safe_path(path)
    require(path.is_file(), "missing regular file: " + path.name)
    require(path.stat().st_size <= MAX_FILE_BYTES, "file exceeds fixture size limit")
    return path.read_bytes()


def row(event_id, task_id, observed_at, execution_status, delivery_status,
        message_id=None):
    event = dict(event_id=event_id, task_id=task_id, observed_at=observed_at,
                 execution_status=execution_status, delivery_status=delivery_status)
    if message_id is not None:
        event["message_id"] = message_id
    return event


def fixture(variant):
    require(variant in VARIANTS, "unknown fixture variant")
    if variant == "train":
        records = [
            row("a-old", "alpha", "2026-09-01T09:20:00Z", "running", "unknown"),
            row("a-new", "alpha", "2026-09-01T18:30:00+09:00", "completed", "failed"),
            row("b-old", "beta", "2026-09-01T18:00:00+09:00", "completed", "failed"),
            row("b-new", "beta", "2026-09-01T09:10:00Z", "completed", "accepted", "synthetic-101"),
            row("c-old", "gamma", "2026-09-01T19:15:00+10:00", "running", "unknown"),
            row("c-new", "gamma", "2026-09-01T00:35:00-09:00", "completed", "unknown", "synthetic-103"),
            row("d-only", "delta", "2026-09-01T09:40:00Z", "running", "unknown"),
            row("e-only", "epsilon", "2026-09-01T10:45:00+01:00", "failed", "accepted", "synthetic-104"),
            row("f-only", "zeta", "2026-09-01T09:50:00Z", "completed", "accepted"),
        ]
        order = (5, 0, 7, 3, 8, 1, 6, 4, 3, 2, 5)
    else:
        records = [
            row("q-old", "quartz", "2026-09-08T18:30:00+09:00", "running", "failed"),
            row("q-new", "quartz", "2026-09-08T09:50:00Z", "completed", "accepted", "synthetic-211"),
            row("m-old", "maple", "2026-09-08T18:40:00+09:00", "completed", "failed"),
            row("m-new", "maple", "2026-09-08T01:10:00-09:00", "completed", "unknown", "synthetic-212"),
            row("r-only", "river", "2026-09-08T10:15:00Z", "failed", "accepted", "synthetic-213"),
            row("c-old", "cedar", "2026-09-08T10:00:00Z", "running", "unknown"),
            row("c-new", "cedar", "2026-09-08T19:20:00+09:00", "failed", "failed"),
            row("l-only", "lunar", "2026-09-08T11:25:00+01:00", "running", "unknown"),
            row("e-a", "ember", "2026-09-08T12:30:00+02:00", "running", "failed"),
            row("e-z", "ember", "2026-09-08T10:30:00Z", "running", "unknown"),
            row("s-only", "stone", "2026-09-08T19:35:00+09:00", "completed", "accepted", "synthetic-214"),
        ]
        order = (10, 2, 8, 6, 0, 9, 3, 1, 5, 7, 4, 9, 10)
    return {"schema_version": SCHEMA_VERSION, "events": [dict(records[i]) for i in order]}


def parse_event(raw):
    required = {"event_id", "task_id", "observed_at", "execution_status", "delivery_status"}
    require(type(raw) is dict and required <= raw.keys() <= required | {"message_id"},
            "invalid event fields")
    for key in ("event_id", "task_id"):
        require(type(raw[key]) is str and re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", raw[key]),
                "invalid " + key)
    timestamp = raw["observed_at"]
    require(type(timestamp) is str and re.fullmatch(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})", timestamp),
        "observed_at requires ISO 8601 seconds and explicit UTC offset")
    instant = datetime.fromisoformat(timestamp.replace("Z", "+00:00")).astimezone(timezone.utc)
    require(raw["execution_status"] in ("completed", "running", "failed"),
            "invalid execution status")
    require(raw["delivery_status"] in ("accepted", "unknown", "failed"),
            "invalid delivery status")
    receipt = raw.get("message_id")
    require(receipt is None or (type(receipt) is str and 0 < len(receipt) <= 128),
            "message_id must be a nonempty string or null")
    return Event(raw["event_id"], raw["task_id"], instant, raw["execution_status"],
                 raw["delivery_status"], receipt)


def derive_report(payload):
    """Compute the reference using typed data and explicit independent counters."""
    require(type(payload) is dict and set(payload) == {"schema_version", "events"},
            "invalid input fields")
    require(type(payload["schema_version"]) is int and payload["schema_version"] == SCHEMA_VERSION,
            "invalid input schema version")
    records = payload["events"]
    require(type(records) is list and 0 < len(records) <= 10000, "invalid event array")
    unique = {}
    raw_by_id = {}
    for raw in records:
        event = parse_event(raw)
        encoded = canonical(raw)
        if event.event_id in unique:
            require(raw_by_id[event.event_id] == encoded, "conflicting duplicate event_id")
        else:
            unique[event.event_id] = event
            raw_by_id[event.event_id] = encoded
    latest = {}
    for event in unique.values():
        previous = latest.get(event.task_id)
        if previous is None or (event.observed_at, event.event_id) > (previous.observed_at, previous.event_id):
            latest[event.task_id] = event
    tasks = []
    for task_id in sorted(latest):
        event = latest[task_id]
        delivered = event.delivery_status == "accepted" and event.message_id is not None
        tasks.append({
            "task_id": task_id,
            "latest_event_id": event.event_id,
            "observed_at_utc": event.observed_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "execution_status": event.execution_status,
            "delivery_status": event.delivery_status,
            "message_id": event.message_id,
            "telegram_delivered": delivered,
            "execution_action": {"completed": "none", "running": "wait_for_execution",
                                 "failed": "review_execution_failure"}[event.execution_status],
            "delivery_action": ("none" if delivered else "retry_delivery_only"
                                if event.delivery_status == "failed" else "verify_receipt"),
        })
    summary = {
        "input_event_rows": len(records), "unique_events": len(unique),
        "duplicate_event_rows": len(records) - len(unique), "tasks": len(tasks),
        "execution_completed": sum(e.execution_status == "completed" for e in latest.values()),
        "execution_running": sum(e.execution_status == "running" for e in latest.values()),
        "execution_failed": sum(e.execution_status == "failed" for e in latest.values()),
        "telegram_delivered": sum(t["telegram_delivered"] for t in tasks),
        "telegram_unconfirmed": sum(not t["telegram_delivered"] and t["delivery_status"] != "failed"
                                    for t in tasks),
        "telegram_failed": sum(e.delivery_status == "failed" for e in latest.values()),
    }
    return {"schema_version": SCHEMA_VERSION, "summary": summary, "tasks": tasks}


def fixture_files(variant):
    return {"events.json": json_bytes(fixture(variant)), "contract.md": CONTRACT.encode("utf-8")}


def manifest_for(variant):
    return {"schema_version": SCHEMA_VERSION, "synthetic_only": True, "variant": variant,
            "sha256": {name: hashlib.sha256(content).hexdigest()
                       for name, content in fixture_files(variant).items()}}


def prepare(output_dir, variant):
    root = safe_path(output_dir)
    require(not root.exists(), "output directory must be fresh")
    require(root.parent.is_dir(), "output parent directory must exist")
    root.mkdir(mode=0o700)
    for name, content in fixture_files(variant).items():
        with (root / name).open("xb") as stream:
            stream.write(content)
    with (root / "fixture-manifest.json").open("xb") as stream:
        stream.write(json_bytes(manifest_for(variant)))
    return {"ok": True, "operation": "prepare", "variant": variant,
            "run_dir": str(root), "inputs": ["events.json", "contract.md", "fixture-manifest.json"]}


def verify(run_dir, report_path):
    root = safe_path(run_dir)
    require(root.is_dir(), "run directory is missing")
    manifest = read_json(root / "fixture-manifest.json")
    require(type(manifest) is dict and manifest.get("variant") in VARIANTS,
            "invalid fixture manifest")
    variant = manifest["variant"]
    expected_manifest = manifest_for(variant)
    require(canonical(manifest) == canonical(expected_manifest), "fixture manifest changed")
    for name, digest in expected_manifest["sha256"].items():
        require(hashlib.sha256(read_file(root / name)).hexdigest() == digest,
                "fixture changed: " + name)
    expected = derive_report(read_json(root / "events.json"))
    actual = read_json(report_path)
    require(canonical(actual) == canonical(expected), "report does not match fixture contract")
    return {"ok": True, "operation": "verify", "variant": variant,
            "synthetic_only": True, "summary": expected["summary"],
            "report_sha256": hashlib.sha256(read_file(report_path)).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    preparation = commands.add_parser("prepare", help="write synthetic inputs into a fresh directory")
    preparation.add_argument("--output-dir", type=Path, required=True)
    preparation.add_argument("--variant", choices=VARIANTS, required=True)
    verification = commands.add_parser("verify", help="check immutable inputs and exact report")
    verification.add_argument("--run-dir", type=Path, required=True)
    verification.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = (prepare(args.output_dir, args.variant) if args.command == "prepare"
                  else verify(args.run_dir, args.report))
    except (ValueError, OSError, UnicodeError) as exc:
        print(json.dumps({"ok": False, "operation": args.command, "error": str(exc)}))
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
