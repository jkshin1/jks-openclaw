#!/usr/bin/env python3
"""Verify /think max persistence and one isolated GLM response; never deliver to Telegram."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
import subprocess
import time
import uuid


CLI = Path.home() / ".local/openclaw-2026.8.1/.personal-edge-management/bin/openclaw"
STATE = Path.home() / ".openclaw-personaledge"


def rpc(method, params):
    process = subprocess.run([str(CLI), "gateway", "call", method, "--json", "--timeout", "40000",
                              "--params", json.dumps(params)], capture_output=True, text=True, timeout=45)
    if process.returncode:
        raise RuntimeError(f"Gateway {method} failed; inspect private runtime logs")
    return json.loads(process.stdout)


def session_entry(key):
    path = STATE / "agents/main/agent/openclaw-agent.sqlite"
    with sqlite3.connect(path.as_uri() + "?mode=ro", uri=True) as database:
        row = database.execute("SELECT current_session_id, entry_json FROM session_nodes WHERE session_key=?",
                               (key,)).fetchone()
    return (row[0], json.loads(row[1])) if row else None


def verify_terminal(result):
    if result.get("status") != "ok" or not result.get("endedAt"):
        raise RuntimeError("GLM max run did not complete successfully")
    terminal = result.get("terminalReceipt", {})
    if terminal.get("successfulToolNames") != [] or terminal.get("rerouted") is not False:
        raise RuntimeError("Unexpected tool use or model rerouting")
    for field in ("requested", "effective"):
        route = terminal.get(field, {})
        if route.get("provider") != "openrouter" or route.get("model") != "z-ai/glm-5.3-flash":
            raise RuntimeError("Unexpected model route")
    reply = result.get("terminalReply", {})
    if reply.get("disposition") != "visible" or reply.get("text", "").strip() != "GLM-MAX-OK":
        raise RuntimeError("Model reply did not match the synthetic marker")
    return {"ok": True, "model": "openrouter/z-ai/glm-5.3-flash", "replyMarkerMatched": True,
            "modelRun": True, "successfulTools": [], "rerouted": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    if args.receipt.exists():
        raise RuntimeError("Receipt exists; choose a fresh path")
    token = uuid.uuid4().hex
    # Use a fresh synthetic session so persisted command state and traces can be read back.
    # Incognito storage is process-local and cannot be independently checked with SQLite.
    key = "agent:main:dashboard:glm-max-smoke-" + token
    run_id = "glm-max-" + token
    created = False
    terminal = False
    receipt = {"observedAt": datetime.now(timezone.utc).isoformat(), "telegramDelivered": False}
    try:
        rpc("sessions.create", {"key": key, "agentId": "main",
                                "model": "openrouter/z-ai/glm-5.3-flash"})
        created = True
        rpc("chat.send", {"sessionKey": key, "message": "/think max", "deliver": False,
                          "idempotencyKey": "glm-max-command-" + token})
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            row = session_entry(key)
            if row and row[1].get("thinkingLevel") == "max":
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("Slash command did not persist max")
        receipt["slashCommandPersisted"] = "max"
        # No explicit thinking argument: prove the persisted session preference is used.
        started = rpc("agent", {"agentId": "main", "sessionKey": key, "message": "Reply exactly GLM-MAX-OK.",
                                "modelRun": True, "promptMode": "none", "deliver": False,
                                "disableMessageTool": True, "timeout": 120, "idempotencyKey": run_id})
        run_id = started.get("runId", run_id)
        deadline = time.monotonic() + 135
        while time.monotonic() < deadline:
            result = rpc("agent.wait", {"runId": run_id, "timeoutMs": 30000})
            if result.get("endedAt") or result.get("status") != "timeout":
                terminal = True
                break
        if not terminal:
            raise RuntimeError("GLM max run did not complete successfully")
        receipt.update(verify_terminal(result))
        # Gateway forces modelRun into internal session effects and deletes its trajectory.
        # Thinking payload evidence comes separately from test-glm-thinking.mjs; do not
        # mistake the provider/model terminal receipt for a retained live effort trace.
        receipt["liveThinkingTraceRetained"] = False
    finally:
        if created:
            if not terminal:
                try:
                    rpc("chat.abort", {"sessionKey": key})
                except RuntimeError:
                    # Still attempt exact-key cleanup if there is no active run to abort.
                    pass
            rpc("sessions.delete", {"key": key, "agentId": "main", "deleteTranscript": True,
                                    "emitLifecycleHooks": False})
            if session_entry(key) is not None:
                raise RuntimeError("Synthetic session cleanup failed")
            receipt["syntheticSessionDeleted"] = True
    with args.receipt.open("x") as output:
        json.dump(receipt, output, indent=2)
        output.write("\n")
    args.receipt.chmod(0o600)
    print(json.dumps(receipt))


if __name__ == "__main__":
    main()
