#!/usr/bin/env python3
"""Run one bounded synthetic pilot phase using existing authenticated runtimes.

No authentication grant is copied/shared, config patched, native learned skill changed,
or Telegram delivery requested. Hermes's native repeat worker reads its existing
general report skill; corrected procedures are isolated pilot artifacts. This
establishes model-proposed procedure validation/reuse, not native skill creation.
"""

import argparse
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


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    loaded = importlib.util.module_from_spec(spec)
    sys.modules[name] = loaded
    spec.loader.exec_module(loaded)
    return loaded


PILOT = module("hermes_procedure_pilot_live_evaluator", "hermes-procedure-pilot.py")
GATEWAY = module("hermes_procedure_pilot_gateway", "openclaw-learning-pilot.py")
INSTALLER = module("hermes_procedure_pilot_installer", "install-hermes-worker.py")
WORKER_ROOT = Path.home() / ".local/share/openclaw-hermes-worker"
MODEL = "gpt-5.6-sol"
MAX_RUN_SECONDS = 240
SKILL_BOOKKEEPING = {".usage.json", ".usage.json.lock", ".curator_ledger.jsonl"}
PUBLIC_SKILL_HEADER = (
    "---\nname: operations-event-report\n"
    "description: Use when reporting synthetic operations events. Apply the exact event-selection, "
    "counting, action-mapping, and JSON-validation contract.\n---\n\n")


def require(condition, code):
    PILOT.require(condition, code)


def sha_file(path):
    return hashlib.sha256(PILOT.FIXTURES.read_file(path)).hexdigest()


def common_request(phase, plan, feedback=None):
    return {"request_id": "procedure-pilot-" + phase,
            "prompt": ("All data below is synthetic. Never contact Telegram, use owner data, or modify any native skill. "
                       "Do not execute an underlying task, read external files, or use any tool except an existing "
                       "Hermes report-skill read required by your host. Return ONLY one JSON object with exactly "
                       "two outer fields: report and procedure. report is the exact report object in the supplied "
                       "contract; procedure is the exact declarative procedure object. This outer envelope is the "
                       "only change to the report root contract. " +
                       ("Correct the supplied faulty candidate using the training feedback. Preserve general rules only. "
                        if phase == "train" else
                        "Use the supplied previously validated procedure unchanged on NEW events; return that identical procedure. ") +
                       "CANDIDATE_PROCEDURE=" + PILOT.FIXTURES.canonical(plan) +
                       "\nTRAINING_FEEDBACK=" + PILOT.FIXTURES.canonical(feedback or {})),
            "report_contract": PILOT.CONTRACT, "events": PILOT.events(phase)}


def parse_output(text):
    value = GATEWAY.report_json(text)
    require(set(value) == {"report", "procedure"}, "OUTER_OUTPUT_SCHEMA_INVALID")
    return value


def skill_hashes(profile):
    base = profile / "skills"
    result = {}
    if base.exists():
        for path in sorted(base.rglob("*")):
            PILOT.path_safe(path)
            if path.parent == base and path.name in SKILL_BOOKKEEPING:
                # Runtime counters/ledger are not skill text or injected
                # context. Reading the skill may update these counters.
                continue
            if path.is_file():
                result[str(path.relative_to(base))] = sha_file(path)
    return result


def normalize_usage(usage, available):
    keys = {"noncache_input_tokens": "input_tokens", "cache_read_tokens": "cache_read_tokens",
            "cache_write_tokens": "cache_write_tokens", "output_tokens": "output_tokens"}
    return {target: usage.get(source) if available and type(usage.get(source)) is int
            and usage[source] >= 0 else None for target, source in keys.items()}


def public_skill_proof(profile):
    """Reject any existing skill content outside this public synthetic fixture."""
    hashes = skill_hashes(profile)
    require(set(hashes) == {"operations-event-report/SKILL.md"}, "PUBLIC_SYNTHETIC_SKILL_ONLY_REQUIRED")
    expected = (PUBLIC_SKILL_HEADER + PILOT.FIXTURES.CONTRACT).encode()
    actual = PILOT.FIXTURES.read_file(profile / "skills/operations-event-report/SKILL.md")
    require(actual == expected, "EXISTING_SKILL_NOT_EXACT_PUBLIC_FIXTURE")
    return {"public_fixture_exact_match": True, "bytes": len(actual),
            "skill_sha256": hashlib.sha256(actual).hexdigest(),
            "contract_sha256": PILOT.digest(PILOT.FIXTURES.CONTRACT.encode()),
            "content_scope": "fixed synthetic-only frontmatter plus exact repository agent-pilot-fixtures CONTRACT",
            "bookkeeping_excluded": sorted(SKILL_BOOKKEEPING),
            "native_skill_changed": False}


def run_hermes(request, out, worker_root=WORKER_ROOT):
    worker_root = PILOT.path_safe(worker_root)
    installation = PILOT.read_json(worker_root / "installation.json")
    require(installation.get("commit") == INSTALLER.COMMIT, "HERMES_RUNTIME_NOT_PINNED")
    profile = worker_root / "profile"
    PILOT.write_new(out / "public-skill-proof.json", public_skill_proof(profile))
    before = skill_hashes(profile)
    require("operations-event-report/SKILL.md" in before, "EXISTING_REPORT_SKILL_REQUIRED")
    config_before = sha_file(profile / "config.yaml")
    request_path = out / "native-request.json"
    PILOT.write_new(request_path, request)
    receipt_path = out / "native-worker-receipt.json"
    worker = Path(__file__).with_name("hermes-report-worker.py")
    command = [str(worker_root / "runtime/.venv/bin/python"), str(worker), "--profile-dir", str(profile),
               "--request-file", str(request_path), "--receipt-file", str(receipt_path), "--phase", "repeat"]
    process = subprocess.Popen(command, cwd=profile, env=INSTALLER.clean_environment(worker_root),
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    try:
        code = process.wait(timeout=MAX_RUN_SECONDS + 40)
    except BaseException:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)
        raise
    finally:
        preservation = {"config_unchanged": sha_file(profile / "config.yaml") == config_before,
                        "skills_unchanged": skill_hashes(profile) == before,
                        "before_skills": before, "after_skills": skill_hashes(profile)}
        PILOT.write_new(out / "preservation.json", preservation)
    require(preservation["config_unchanged"] and preservation["skills_unchanged"], "HERMES_PRESERVATION_FAILED")
    receipt = PILOT.read_json(receipt_path)
    require(code == 0 and receipt.get("completed") is True, "HERMES_RUN_FAILED:" + str(receipt.get("error_code", "unknown")))
    require(receipt.get("model") == MODEL and receipt.get("reasoning_effort") == "high"
            and receipt.get("provider") == "openai-codex" and receipt.get("api_mode") == "codex_responses"
            and receipt.get("response_models") and all(m == MODEL for m in receipt["response_models"]),
            "HERMES_EFFECTIVE_ROUTE_UNVERIFIED")
    verify_read_only_calls(receipt.get("tool_calls"))
    output = parse_output(receipt["final_response"])
    return output, {"run_id": request["request_id"] + "-" + out.name,
                    "runtime_version": receipt["hermes_version"], "native_provider": receipt["provider"],
                    "native_source": str(receipt_path), "usage": normalize_usage(receipt.get("usage") or {},
                                                                                receipt.get("usage_available") is True),
                    "context_difference": "Hermes reads its pre-existing general report skill; no native skill mutation"}


def run_openclaw(request, out, gateway=None, state=None):
    gateway, state = gateway or GATEWAY.Gateway(diagnostic_dir=out / "diagnostics"), state or GATEWAY.State()
    config_path = state.root / "openclaw.json"
    config_before = sha_file(config_path)
    # Keep a private byte-for-byte backup without exposing credentials in output.
    PILOT.write_new(out / "config-before.json", PILOT.FIXTURES.read_file(config_path))
    config = state.config()
    auth_status = gateway.rpc("models.authStatus", {"agentId": "main", "refresh": False})
    selected, auth_evidence = GATEWAY.select_oauth_profile(config, auth_status)
    requested_key = "agent:main:dashboard:incognito-procedure-pilot-" + uuid.uuid4().hex
    run_id = "procedure-pilot-" + uuid.uuid4().hex
    key, session_id, terminal = requested_key, None, False
    created = None
    cleanup = {"ok": False, "key": key, "owned_only": True, "deleted": False}
    PILOT.write_new(out / "cleanup-plan.json", {"key": key, "run_id": run_id, "incognito": True})
    try:
        created = gateway.rpc("sessions.create", {"agentId": "main", "key": key, "incognito": True,
            "model": "openai/" + MODEL + "@" + selected, "thinkingLevel": "high",
            "permissionMode": "read-only", "toolOverrides": {"webSearch": False}, "emitCommandHooks": False})
        PILOT.write_new(out / "native-created.json", created)
        key, session_id = created.get("key"), created.get("sessionId")
        require(key == requested_key and type(session_id) is str and created.get("entry", {}).get("incognito") is True,
                "INCOGNITO_OWNERSHIP_UNVERIFIED")
        GATEWAY.verify_session_auth_pin(created.get("entry", {}), selected)
        require(created.get("entry", {}).get("thinkingLevel") == "high", "OPENCLAW_EFFORT_UNVERIFIED")
        # Raw model mode skips the owner's bootstrap and removes runtime tools.
        # This is a deliberately narrow OpenClaw model-run baseline, recorded
        # as such rather than mistaken for the full Telegram main workflow.
        params = {"agentId": "main", "sessionKey": key,
                  "message": json.dumps(request, ensure_ascii=False, sort_keys=True), "deliver": False,
                  "disableMessageTool": True, "thinking": "high", "promptMode": "none", "modelRun": True,
                  "timeout": MAX_RUN_SECONDS, "idempotencyKey": run_id}
        PILOT.write_new(out / "native-request.json", params)
        started = gateway.rpc("agent", params)
        run_id = started.get("runId", run_id)
        PILOT.write_new(out / "native-started.json", started)
        deadline = time.monotonic() + MAX_RUN_SECONDS + 15
        while time.monotonic() < deadline:
            result = gateway.rpc("agent.wait", {"runId": run_id, "timeoutMs": 30000})
            if result.get("endedAt") or result.get("status") != "timeout":
                terminal = True
                break
        else:
            raise ValueError("OPENCLAW_RUN_DEADLINE")
        PILOT.write_new(out / "native-terminal.json", result)
        output = GATEWAY.verify_terminal(result, MODEL, allowed_tools=frozenset())
        require(set(output) == {"report", "procedure"}, "OUTER_OUTPUT_SCHEMA_INVALID")
        messages, history_status, history_sha = [], "not_available", None
        try:
            history = gateway.rpc("chat.history", {"agentId": "main", "sessionKey": key,
                "limit": 100, "maxBytes": 900000})
        except GATEWAY.PilotError:
            # Raw model runs use a separate internal-effects session removed
            # at completion. Terminal output survives; its token counters may
            # not. Missing telemetry must not erase valid work or become zero.
            history = None
        if history is not None and history.get("sessionId") == session_id and not history.get("hasMore"):
            messages = history.get("messages")
            require(type(messages) is list, "SYNTHETIC_HISTORY_INVALID")
            assistant = [m for m in messages if m.get("role") == "assistant"]
            require(all(not any(c.get("type") in {"toolCall", "tool_use"}
                        for c in m.get("content", []) if isinstance(c, dict)) for m in assistant), "OPENCLAW_TOOL_CALL_REFUSED")
            history_path = out / "native-history.json"
            PILOT.write_new(history_path, history)
            history_sha = sha_file(history_path)
            history_status = "available" if assistant else "empty_raw_model_session"
        usage, samples = GATEWAY.native_usage([{"message": message} for message in messages])
        source = out / "native-observation.json"
        PILOT.write_new(source, {"terminal": result, "history_sha256": history_sha, "history_status": history_status,
                                "usage": usage, "nativeUsageSamples": samples, "auth": auth_evidence,
                                "context": "OpenClaw incognito raw model baseline; no bootstrap or tools"})
        return output, {"run_id": run_id, "runtime_version": state.runtime_snapshot()["version"],
                        "native_provider": "openai", "native_source": str(source),
                        "usage": normalize_usage(usage, usage.get("available") is True),
                        "context_difference": "OpenClaw incognito raw model baseline, no bootstrap or tool calls"}
    finally:
        if created is not None and key == requested_key and session_id:
            if not terminal:
                try:
                    gateway.rpc("chat.abort", {"sessionKey": key, "runId": run_id})
                except Exception:
                    pass
            try:
                deleted = gateway.rpc("sessions.delete", {"agentId": "main", "key": key,
                    "expectedSessionId": session_id, "deleteTranscript": True, "emitLifecycleHooks": False})
                cleanup.update(ok=deleted.get("ok") is True and deleted.get("archived") == [],
                               deleted=deleted.get("deleted") is True,
                               native=deleted)
            except Exception as exc:
                cleanup["error_type"] = type(exc).__name__
        cleanup["config_unchanged"] = sha_file(config_path) == config_before
        cleanup["ok"] = cleanup["ok"] and cleanup["deleted"] and cleanup["config_unchanged"]
        PILOT.write_new(out / "cleanup.json", cleanup)
        require(cleanup["ok"], "OPENCLAW_CLEANUP_OR_PRESERVATION_FAILED")


# Failures raised only after the terminal output was verified: history/usage collection. A tool call,
# route, schema, deadline or cleanup failure is never recoverable from the retained output.
TELEMETRY_FAILURES = frozenset({"SYNTHETIC_HISTORY_INVALID"})
TELEMETRY_ERROR_TYPES = frozenset({"KeyError", "TypeError", "AttributeError"})


def verify_read_only_calls(calls):
    """Hermes may list skills and must successfully read its report skill; any other call is refused."""
    require(isinstance(calls, list) and calls and all(isinstance(call, dict) for call in calls),
            "HERMES_READ_ONLY_REUSE_UNVERIFIED")
    require(all(call.get("name") in {"skills_list", "skill_view"} for call in calls)
            and any(call.get("name") == "skill_view" and call.get("succeeded") is True for call in calls),
            "HERMES_READ_ONLY_REUSE_UNVERIFIED")


def recover_training_output(root):
    """Verify a completed train output after telemetry failed; never reinfer."""
    root = PILOT.validate_root(root)
    out = root / "live/openclaw/train"
    prior = PILOT.read_json(out / "acceptance.json")
    require(prior.get("ok") is False and prior.get("phase") == "train", "FAILED_TRAIN_ATTEMPT_REQUIRED")
    require(prior.get("error") in TELEMETRY_FAILURES or prior.get("error_type") in TELEMETRY_ERROR_TYPES,
            "NON_TELEMETRY_FAILURE_NOT_RECOVERABLE")
    require(PILOT.read_json(out / "cleanup.json").get("ok") is True, "CLEANUP_REQUIRED")
    # The retained output must answer exactly the recorded train request for this candidate.
    common = PILOT.read_json(out / "common-request.json")
    require(PILOT.digest(common) == prior.get("common_request_sha256")
            and common.get("request_id") == "procedure-pilot-train"
            and ("CANDIDATE_PROCEDURE=" + PILOT.FIXTURES.canonical(PILOT.read_json(root / "train/candidate-procedure.json")))
            in common.get("prompt", ""), "TRAIN_REQUEST_PROVENANCE_INVALID")
    native = PILOT.read_json(out / "native-request.json")
    require(native.get("message") == json.dumps(common, ensure_ascii=False, sort_keys=True)
            and native.get("modelRun") is True and native.get("promptMode") == "none", "TRAIN_REQUEST_PROVENANCE_INVALID")
    terminal = PILOT.read_json(out / "native-terminal.json")
    output = GATEWAY.verify_terminal(terminal, MODEL, allowed_tools=frozenset())
    require(set(output) == {"report", "procedure"}, "OUTER_OUTPUT_SCHEMA_INVALID")
    report_path, plan_path = out / "report.json", out / "procedure.json"
    PILOT.write_new(report_path, output["report"])
    PILOT.write_new(plan_path, output["procedure"])
    verification = PILOT.verify(root, "train", plan_path, report_path)
    result = {"ok": verification["ok"], "agent": "openclaw", "phase": "train", "synthetic_only": True,
              "correction_origin": "retained_model_output_after_telemetry_failure", "model": MODEL,
              "verification": verification, "new_model_calls": 0, "usage": normalize_usage({}, False),
              "observed_attempt_elapsed_seconds": prior.get("elapsed_seconds"),
              "timing_limit": "Original attempt ended before this independent verification; not comparable total duration",
              "comparison_limit": prior["comparison_limit"]}
    PILOT.write_new(out / "recovered-acceptance.json", result)
    return result


def run(root, agent, phase, training_receipt=None, worker_root=WORKER_ROOT):
    root = PILOT.validate_root(root)
    out = root / "live" / agent / phase
    PILOT.path_safe(out)
    require(not out.exists(), "LIVE_PHASE_ALREADY_EXISTS")
    out.mkdir(mode=0o700, parents=True)
    started = time.monotonic()
    status = {"ok": False, "agent": agent, "phase": phase, "synthetic_only": True,
              "native_skills_mutated": None, "telegram_delivered": False,
              "scope": "model_proposed_procedure_validation_and_external_artifact_reuse",
              "comparison_limit": "OpenClaw raw model baseline versus Hermes existing report-skill worker"}
    try:
        if phase == "train":
            plan_path = root / "train/candidate-procedure.json"
            negative = PILOT.verify(root, "train", plan_path)
            require(not negative["ok"], "NEGATIVE_CONTROL_FAILED")
            plan = PILOT.read_json(plan_path)
            feedback = {"candidate_rejected": True, "error_codes": negative["error_codes"],
                        "correction_needed": "Use actual UTC ordering; accepted delivery needs a receipt; retry transport only."}
        else:
            require(training_receipt is not None, "TRAINING_RECEIPT_REQUIRED")
            training = PILOT.read_json(training_receipt)
            require(training.get("ok") is True and training.get("phase") == "train", "VALID_TRAINING_REQUIRED")
            plan_path = Path(training["procedure_path"])
            plan = PILOT.read_json(plan_path)
            # Verify approval before spending any model call.
            PILOT.verify(root, "holdout", plan_path, training_receipt_path=training_receipt, persist=False)
            feedback = None
        request = common_request(phase, plan, feedback)
        status["common_request_sha256"] = PILOT.digest(request)
        PILOT.write_new(out / "common-request.json", request)
        output, observed = (run_hermes(request, out, worker_root) if agent == "hermes"
                            else run_openclaw(request, out))
        procedure_path, report_path = out / "procedure.json", out / "report.json"
        PILOT.write_new(procedure_path, output["procedure"])
        PILOT.write_new(report_path, output["report"])
        verification = PILOT.verify(root, phase, procedure_path, report_path, training_receipt)
        status.update(ok=verification["ok"], verification=verification, native_skills_mutated=False,
                      correction_origin="model_output" if phase == "train" else "unchanged_model_output_reuse",
                      observed=observed)
        require(verification["ok"], "MODEL_OUTPUT_VERIFICATION_FAILED")
        elapsed = round(time.monotonic() - started, 3)
        if phase == "holdout":
            envelope = PILOT.run_template()
            envelope.update(agent=agent, status="completed", run_id=observed["run_id"], model=MODEL,
                            provider="chatgpt_codex_responses", reasoning_effort="high",
                            runtime_version=observed["runtime_version"], input_sha256=verification["input_sha256"],
                            procedure_sha256=verification["procedure_sha256"], procedure_path=str(procedure_path),
                            report_path=str(report_path), training_receipt_path=str(PILOT.path_safe(training_receipt)),
                            source_artifact_path=observed["native_source"],
                            source_artifact_sha256=sha_file(observed["native_source"]), elapsed_seconds=elapsed,
                            usage=observed["usage"])
            PILOT.write_new(out / "observed-run.json", envelope)
            status["comparison_envelope"] = str(out / "observed-run.json")
    except Exception as exc:
        status.update(ok=False, error=str(exc), error_type=type(exc).__name__)
    finally:
        status["elapsed_seconds"] = round(time.monotonic() - started, 3)
        PILOT.write_new(out / "acceptance.json", status)
    return status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--agent", choices=("openclaw", "hermes"), required=True)
    parser.add_argument("--phase", choices=PILOT.PHASES, required=True)
    parser.add_argument("--training-receipt", type=Path)
    parser.add_argument("--worker-root", type=Path, default=WORKER_ROOT)
    parser.add_argument("--recover-training-output", action="store_true")
    args = parser.parse_args()
    try:
        if args.recover_training_output:
            require(args.agent == "openclaw" and args.phase == "train", "RECOVERY_IS_OPENCLAW_TRAIN_ONLY")
            result = recover_training_output(args.root)
        else:
            result = run(args.root, args.agent, args.phase, args.training_receipt, args.worker_root)
    except Exception as exc:
        result = {"ok": False, "error": str(exc), "error_type": type(exc).__name__}
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, allow_nan=False))
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
