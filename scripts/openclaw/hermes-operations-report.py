#!/usr/bin/env python3
"""Run the isolated Hermes operations-report worker and verify its structured result."""

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid


ROOT = Path.home() / ".local/share/openclaw-hermes-worker"
SKILL = "operations-event-report"


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


def save(path, data):
    with path.open("x", encoding="utf-8") as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")
    path.chmod(0o600)


def report_from_text(text, fixtures):
    text = text.strip()
    if text.startswith("```json\n") and text.endswith("\n```"):
        text = text[8:-4]
    elif text.startswith("```\n") and text.endswith("\n```"):
        text = text[4:-4]
    return json.loads(text, object_pairs_hook=fixtures.unique_object,
                      parse_constant=fixtures.no_constant)


def run_report(input_file, output_dir, *, root=ROOT, phase="repeat", contract=None):
    fixtures = module("agent_pilot_fixtures", "agent-pilot-fixtures.py")
    installer = module("hermes_installer", "install-hermes-worker.py")
    input_file = fixtures.safe_path(input_file)
    output_dir = fixtures.safe_path(output_dir)
    root = fixtures.safe_path(root)
    fixtures.require(output_dir.is_absolute() and not output_dir.exists(), "choose a fresh output directory")
    events = fixtures.read_json(input_file)
    # Validation never supplies this reference answer to the agent.
    expected = fixtures.derive_report(events)
    installation = fixtures.read_json(root / "installation.json")
    fixtures.require(installation.get("commit") == installer.COMMIT, "unqualified Hermes runtime")
    worker = Path(__file__).with_name("hermes-report-worker.py")
    fixtures.require(worker.is_file(), "Hermes worker missing")
    input_sha = hashlib.sha256(input_file.read_bytes()).hexdigest()
    output_dir.mkdir(mode=0o700, parents=True)
    request_id = "operations-report-" + uuid.uuid4().hex
    if phase == "train":
        prompt = (
            "This is an isolated, explicitly authorized procedure-learning test. Process the supplied "
            "operations events under the supplied contract. Use the native skill tools to create a "
            f"reusable skill named {SKILL}, or correct that exact skill if it already exists. "
            "Future runs receive ONLY new events, WITHOUT this original contract. Persist a "
            "self-contained procedure: include the supplied GENERAL CONTRACT verbatim in the skill, "
            "including exact input/output key names, types, schema-version constant, enum values, "
            "action-string mappings, and all selection/counting rules. Schema constants are reusable "
            "procedure, not fixture answers. Never replace them with references such as 'as required "
            "by the supplied contract'. Store no sample event IDs, timestamps, counts, input records, "
            "answers, personal facts, or credentials. After saving, read the skill back and verify "
            "that it can produce the exact output without the original contract. "
            "Do not contact Telegram or execute any operations. "
            "Return ONLY the report JSON object, with no prose or markdown."
        )
    else:
        prompt = (
            f"Process these new operations events. First discover and read the saved {SKILL} skill "
            "with the native skill tools, then follow its full report schema and procedure. "
            "The complete contract is deliberately not repeated: this tests persistent procedure reuse "
            "in a new conversation. Do not modify the skill, contact Telegram, or execute operations. "
            "Return ONLY the report JSON object, with no prose or markdown."
        )
    request = {"request_id": request_id, "prompt": prompt,
               "report_contract": (contract or fixtures.CONTRACT) if phase == "train" else "",
               "events": events}
    save(output_dir / "request.json", request)
    env = installer.clean_environment(root)
    env["HERMES_SAFE_MODE"] = "1"
    command = [str(root / "runtime/.venv/bin/python"), str(worker),
               "--profile-dir", str(root / "profile"),
               "--request-file", str(output_dir / "request.json"),
               "--receipt-file", str(output_dir / "worker-receipt.json"), "--phase", phase]
    started = time.monotonic()
    metadata = {"schemaVersion": 1, "requestId": request_id, "phase": phase,
                "startedAt": datetime.now(timezone.utc).isoformat(), "inputSha256": input_sha,
                "telegramDelivered": False, "reportVerified": False}
    process = None
    try:
        with (output_dir / "worker.log").open("x") as log:
            (output_dir / "worker.log").chmod(0o600)
            process = subprocess.Popen(command, env=env, cwd=root / "profile", stdout=log, stderr=log,
                                       text=True, start_new_session=True)
            code = process.wait(timeout=280)
        metadata["exitCode"] = code
        fixtures.require(code == 0, "Hermes worker failed; inspect its bounded receipt")
        receipt = fixtures.read_json(output_dir / "worker-receipt.json")
        fixtures.require(receipt.get("completed") is True, "Hermes did not complete")
        before, after = receipt.get("skills_before", {}), receipt.get("skills_after", {})
        skill_path = SKILL + "/SKILL.md"
        successful = {call.get("name") for call in receipt.get("tool_calls", []) if call.get("succeeded") is True}
        fixtures.require(set(after) == {skill_path}, "expected isolated procedure skill missing")
        if phase == "train":
            fixtures.require("skill_manage" in successful and before != after, "procedure creation not verified")
        else:
            fixtures.require("skill_view" in successful and before == after and "skill_manage" not in successful,
                             "persistent procedure reuse not verified")
        report = report_from_text(receipt.get("final_response", ""), fixtures)
        fixtures.require(fixtures.canonical(report) == fixtures.canonical(expected),
                         "Hermes report did not match independently computed evidence")
        fixtures.require(hashlib.sha256(input_file.read_bytes()).hexdigest() == input_sha, "input changed during run")
        save(output_dir / "report.json", report)
        metadata["reportVerified"] = True
        metadata["reportSha256"] = hashlib.sha256((output_dir / "report.json").read_bytes()).hexdigest()
        metadata["summary"] = report["summary"]
    except BaseException as error:
        metadata["errorType"] = type(error).__name__
        metadata["timedOut"] = isinstance(error, subprocess.TimeoutExpired)
        if process is not None and process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=10)
            metadata["exitCode"] = process.returncode
        raise
    finally:
        metadata["elapsedSeconds"] = round(time.monotonic() - started, 3)
        save(output_dir / "verification.json", metadata)
    return {"ok": True, "report": str(output_dir / "report.json"),
            "verification": str(output_dir / "verification.json"),
            "workerReceipt": str(output_dir / "worker-receipt.json"),
            "telegramDelivered": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-file", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--phase", choices=("train", "repeat"), default="repeat")
    args = parser.parse_args()
    os.umask(0o077)
    def interrupted(_signum, _frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    print(json.dumps(run_report(args.input_file, args.output_dir, root=args.root, phase=args.phase)))


if __name__ == "__main__":
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        print(json.dumps({"ok": False, "errorType": type(error).__name__,
                          "reason": "input, execution, or independent report verification failed; inspect private receipts"}),
              file=sys.stderr)
        sys.exit(1)
