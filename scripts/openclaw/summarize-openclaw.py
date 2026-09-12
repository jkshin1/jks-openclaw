#!/usr/bin/env python3
"""Summarize CLI backend using isolated, tool-free OpenClaw model runs.

No API keys are exported. The normal owner conversation and workspace context
are not used. Output follows Summarize's OpenClaw CLI result envelope.
"""

import argparse
import json
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid


CLI = Path.home() / ".local/openclaw-2026.8.1/.personal-edge-management/bin/openclaw"
SUMMARY_PROVIDER = "openai"
SUMMARY_MODEL = "gpt-5.6-sol"


def rpc(method, params):
    result = subprocess.run(
        [str(CLI), "gateway", "call", method, "--json", "--timeout", "40000",
         "--params", json.dumps(params, ensure_ascii=False)],
        capture_output=True, text=True, timeout=45,
    )
    if result.returncode:
        raise RuntimeError(f"OpenClaw {method} failed; inspect local Gateway logs")
    return json.loads(result.stdout)


def model_request(message, key, run_id, timeout):
    return {"agentId": "main", "sessionKey": key, "message": message,
            "modelRun": True, "promptMode": "none", "thinking": "low",
            "deliver": False, "timeout": timeout, "idempotencyKey": run_id}


def completed_text(result):
    if result.get("status") != "ok" or not result.get("endedAt"):
        raise RuntimeError("Summary did not complete successfully")
    receipt = result.get("terminalReceipt")
    if not isinstance(receipt, dict):
        raise RuntimeError("Summary has no model route receipt")
    if receipt.get("successfulToolNames") != [] or receipt.get("rerouted") is not False:
        raise RuntimeError("Unexpected tool use or model rerouting in isolated summary")
    expected_route = {"provider": SUMMARY_PROVIDER, "model": SUMMARY_MODEL}
    if (receipt.get("requested") != expected_route or
            receipt.get("effective") != {**expected_route, "responseModel": SUMMARY_MODEL}):
        raise RuntimeError("Summary did not use the requested Codex model")
    reply = result.get("terminalReply", {})
    if reply.get("disposition") != "visible" or not isinstance(reply.get("text"), str) or not reply["text"].strip():
        raise RuntimeError("Summary has no visible final answer")
    return reply["text"].strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["agent"])
    parser.add_argument("--agent", choices=["main"], default="main")
    parser.add_argument("-m", "--message", required=True)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    if not args.message.strip() or len(args.message.encode()) > 120 * 1024:
        raise RuntimeError("Summary input must be nonempty and at most 120 KiB")
    # Leave time for terminal receipt and cleanup inside the caller's timeout.
    timeout = min(150, max(10, args.timeout - 15))
    token = uuid.uuid4().hex
    key = "agent:main:dashboard:incognito-summarize-" + token
    run_id = "summarize-" + token
    created = False
    terminal = False
    answer = None
    try:
        # CLI callers select the model on the session; agent-level overrides are
        # reserved for trusted backend callers. Keep this temporary session pinned
        # even when the owner's main default changes.
        rpc("sessions.create", {"key": key, "agentId": "main", "incognito": True,
                                "model": f"{SUMMARY_PROVIDER}/{SUMMARY_MODEL}",
                                "thinkingLevel": "low"})
        created = True
        rpc("agent", model_request(args.message, key, run_id, timeout))
        deadline = time.monotonic() + timeout + 5
        while time.monotonic() < deadline:
            result = rpc("agent.wait", {"runId": run_id, "timeoutMs": 30000})
            if result.get("endedAt") or result.get("status") != "timeout":
                terminal = True
                answer = completed_text(result)
                break
        if answer is None:
            raise RuntimeError("Summary timed out")
    finally:
        if created:
            if not terminal:
                try:
                    rpc("chat.abort", {"sessionKey": key, "runId": run_id})
                except Exception:
                    pass
            rpc("sessions.delete", {"key": key, "agentId": "main",
                                    "deleteTranscript": True, "emitLifecycleHooks": False})
    print(json.dumps({"status": "ok", "result": {"payloads": [{"text": answer}]}},
                     ensure_ascii=False))


def interrupted(_signum, _frame):
    raise RuntimeError("Summary interrupted")


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, interrupted)
    try:
        main()
    except Exception as error:
        # Do not echo the input prompt, command arguments, or raw provider errors.
        print(f"OpenClaw summary failed ({type(error).__name__}); check local logs.", file=sys.stderr)
        sys.exit(1)
