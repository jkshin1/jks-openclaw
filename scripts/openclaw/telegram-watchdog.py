#!/usr/bin/env python3
"""Observe operations and send bounded owner-only failure/recovery alerts without inference."""

import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


DEFAULT_STATE = Path.home() / ".openclaw-personaledge"
DEFAULT_CLI = Path.home() / ".local/openclaw-2026.8.1/.personal-edge-management/bin/openclaw"
DEFAULT_PACKAGE = Path.home() / ".local/openclaw-2026.8.1/lib/node_modules/openclaw"
DEFAULT_HERMES_ROOT = Path.home() / ".local/share/openclaw-hermes-worker"
# Only faults a source-and-evidence review can actually explain. Transport and backup problems have
# deterministic operator procedures and would spend a model call to restate them.
HERMES_INCIDENT_ISSUES = frozenset({
    "gateway-policy-or-runtime-check-failed", "gateway-check-timeout", "gateway-check-unavailable",
    "observer-gateway-unhealthy", "task-long-running", "dreaming-unhealthy",
})
HERMES_DISPATCH_INTERVAL_SECONDS = 21600  # At most one automatic incident review per six hours.
HERMES_DISPATCH_DAILY_LIMIT = 2


def load_status():
    spec = importlib.util.spec_from_file_location("telegram_ops_status", Path(__file__).with_name("telegram-ops-status.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(prefix="." + path.name, dir=str(path.parent))
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(value, stream, ensure_ascii=False, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def owner_target(state):
    # Transport must remain owner-only even if the strict verifier has detected unrelated drift.
    config_path = state / "openclaw.json"
    stat = config_path.stat()
    if config_path.is_symlink() or stat.st_uid != os.getuid() or stat.st_mode & 0o077:
        raise ValueError("owner-policy-unavailable")
    config = json.loads(config_path.read_text())
    owners = config.get("commands", {}).get("ownerAllowFrom", [])
    if len(owners) != 1 or not isinstance(owners[0], str) or not owners[0].startswith("telegram:"):
        raise ValueError("owner-policy-unavailable")
    owner = owners[0][9:]
    channel = config.get("channels", {}).get("telegram", {})
    if (not owner.isascii() or not owner.isdigit() or int(owner) <= 0
            or channel.get("enabled") is not True or channel.get("dmPolicy") != "allowlist"
            or channel.get("allowFrom") != [owner] or channel.get("accounts")):
        raise ValueError("owner-policy-unavailable")
    return owner


def verify(state, cli, package, runner=subprocess.run):
    try:
        result = runner([sys.executable, str(Path(__file__).with_name("verify-telegram-gateway.py")),
                         "--state-dir", str(state), "--cli", str(cli), "--package", str(package), "--json"],
                        capture_output=True, text=True, timeout=240)
        receipt = json.loads(result.stdout) if result.returncode == 0 else {}
        if result.returncode == 0 and receipt.get("ok") is True and receipt.get("live") is True:
            return {"ok": True, "live": True, "observedAt": receipt.get("observedAt")}
        return {"ok": False, "live": False, "reason": "gateway-policy-or-runtime-check-failed"}
    except subprocess.TimeoutExpired:
        return {"ok": False, "live": False, "reason": "gateway-check-timeout"}
    except (OSError, ValueError, TypeError):
        return {"ok": False, "live": False, "reason": "gateway-check-unavailable"}


def transition(saved, healthy, issues, now, threshold=3):
    """Keep one incident and one pending message; persistent failures never create repeated alerts."""
    previous = dict(saved)
    previous.update(schemaVersion=1, observedAt=now, healthy=healthy,
                    consecutiveFailures=0 if healthy else saved.get("consecutiveFailures", 0) + 1,
                    issues=sorted(set(issues)))
    previous["issueStreaks"] = {issue: saved.get("issueStreaks", {}).get(issue, 0) + 1
                               for issue in set(issues)} if not healthy else {}
    if not healthy and previous.get("pendingNotification", {}).get("kind") == "recovery":
        previous.pop("pendingNotification", None)
        # Keep this incident open so its recovery is emitted after health returns.
        previous["incident"] = dict(previous["incident"], active=True)
    if healthy:
        previous["lastSuccessAt"] = now
        incident = previous.get("incident")
        if incident and incident.get("active"):
            incident = dict(incident, active=False, recoveredAt=now)
            previous["incident"] = incident
            if incident.get("failureDelivered"):
                previous["pendingNotification"] = {"id": incident["id"] + ":recovery", "kind": "recovery",
                                                    "createdAt": now, "attempts": 0}
            else:
                previous.pop("pendingNotification", None)
    elif previous["consecutiveFailures"] >= threshold:
        incident = previous.get("incident")
        qualified = sorted(issue for issue, count in previous["issueStreaks"].items() if count >= threshold)
        if qualified and (not incident or not incident.get("active")):
            incident_id = hashlib.sha256(now.encode()).hexdigest()[:12]
            previous["incident"] = {"id": incident_id, "active": True, "startedAt": now,
                                    "failureDelivered": False, "issues": sorted(set(issues)),
                                    "alertedIssues": qualified}
            previous["pendingNotification"] = {"id": incident_id + ":failure", "kind": "failure",
                                                "createdAt": now, "attempts": 0}
        elif qualified and incident and incident.get("active"):
            additions = sorted(set(qualified) - set(incident.get("alertedIssues", incident.get("issues", []))))
            if additions:
                previous["incident"] = dict(incident, alertedIssues=sorted(set(qualified) |
                                            set(incident.get("alertedIssues", incident.get("issues", [])))))
                # Coalesce additions into a pending alert; once delivered, notify only newly persistent categories.
                if not previous.get("pendingNotification"):
                    suffix = hashlib.sha256(",".join(additions).encode()).hexdigest()[:8]
                    previous["pendingNotification"] = {"id": incident["id"] + ":escalation:" + suffix,
                                                        "kind": "failure", "escalation": True,
                                                        "createdAt": now, "attempts": 0}
    return previous


def hermes_dispatch_allowed(saved, issues, now_ms):
    """Decide whether this incident earns one automatic Hermes review.

    The observer must stay cheap and non-inferential, so dispatch is rate limited, restricted to
    fault categories a review can explain, and tied to one already-open incident. The controller
    holds its own lock and its own weekly budget, so a refused or duplicated call is still safe.
    """
    incident = saved.get("incident") or {}
    if not incident.get("active") or not incident.get("id"):
        return None
    qualified = sorted(HERMES_INCIDENT_ISSUES.intersection(issues))
    if not qualified:
        return None
    dispatch = saved.get("hermesDispatch") or {}
    if dispatch.get("lastIncidentId") == incident["id"]:
        return None
    if now_ms - dispatch.get("lastAtMs", 0) < HERMES_DISPATCH_INTERVAL_SECONDS * 1000:
        return None
    recent = [value for value in dispatch.get("recentAtMs", []) if now_ms - value < 86400000]
    if len(recent) >= HERMES_DISPATCH_DAILY_LIMIT:
        return None
    return {"incidentId": incident["id"], "issues": qualified, "recentAtMs": recent}


def dispatch_hermes(saved, plan, root, now_ms, persist, spawner=subprocess.Popen):
    """Start one detached incident review. The observer never waits on it and never reads a model."""
    controller = root / "bin/hermes-operations.py"
    interpreter = root / "runtime/.venv/bin/python"
    record = {"lastIncidentId": plan["incidentId"], "lastAtMs": now_ms,
              "recentAtMs": plan["recentAtMs"] + [now_ms], "issues": plan["issues"]}
    if not controller.is_file() or controller.is_symlink() or not os.access(interpreter, os.X_OK):
        saved["hermesDispatch"] = dict(record, state="unavailable")
        persist(saved)
        return saved
    # Durable intent precedes the spawn, so a crash cannot turn into an unbudgeted repeat.
    saved["hermesDispatch"] = dict(record, state="dispatching")
    persist(saved)
    try:
        log = root / "operations/incident-dispatch.log"
        log.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        handle = os.open(log, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        with os.fdopen(handle, "a") as stream:
            spawner([str(interpreter), str(controller), "run", "--mode", "incident", "--json"],
                    stdout=stream, stderr=stream, start_new_session=True,
                    cwd=str(root), env={"PATH": "/usr/bin:/bin", "HOME": str(Path.home())})
        saved["hermesDispatch"] = dict(record, state="dispatched")
    except (OSError, ValueError, TypeError):
        saved["hermesDispatch"] = dict(record, state="failed")
    persist(saved)
    return saved


def alert_text(saved):
    pending = saved["pendingNotification"]
    if pending["kind"] == "test":
        return ("[OpenClaw 운영 알림 시험] 전송 경로 확인용 메시지입니다. 실제 장애 알림이 아닙니다.\n"
                "알림 번호: " + pending["id"])
    label = "지속 장애 확인" if pending["kind"] == "failure" else "복구 확인"
    if pending.get("escalation"):
        label = "지속 장애 추가 확인"
    status = load_status()
    lines = ["[OpenClaw 운영 알림] " + label, "검사: " + status.local_time(saved["observedAt"]), "알림 번호: " + pending["id"]]
    if pending["kind"] == "failure":
        lines.append("연속 실패: {}회".format(saved["consecutiveFailures"]))
        lines.append("확인 항목: " + status.issue_labels(saved["issues"]))
    lines.append("'운영 상태 보여줘'라고 요청하면 상세 수치를 확인할 수 있습니다.")
    return "\n".join(lines)


def send_pending(saved, state, cli, now_ms, persist, runner=subprocess.run, retry_seconds=900):
    pending = saved.get("pendingNotification")
    if not pending or now_ms - saved.get("lastNotificationAttemptAtMs", 0) < retry_seconds * 1000:
        return saved
    try:
        owner = owner_target(state)
    except (ValueError, OSError, TypeError, KeyError):
        saved["notificationState"] = "owner-policy-unavailable"
        persist(saved)
        return saved
    saved["lastNotificationAttemptAtMs"] = now_ms
    pending["attempts"] = pending.get("attempts", 0) + 1
    saved["notificationState"] = "sending"
    persist(saved)  # Durable intent precedes the sole external action.
    try:
        result = runner([str(cli), "message", "send", "--channel", "telegram", "--account", "default",
                         "--target", owner, "--message", alert_text(saved), "--json"],
                        capture_output=True, text=True, timeout=60)
        receipt = json.loads(result.stdout) if result.returncode == 0 else {}
        receipt = receipt if isinstance(receipt, dict) else {}
        payload = receipt.get("payload", {})
        payload = payload if isinstance(payload, dict) else {}
        message_id = payload.get("messageId")
        delivered = (result.returncode == 0 and receipt.get("action") == "send"
                     and receipt.get("channel") == "telegram" and receipt.get("dryRun") is False
                     and payload.get("ok") is True and str(payload.get("chatId")) == owner
                     and isinstance(message_id, (int, str)) and not isinstance(message_id, bool)
                     and str(message_id).isdigit() and int(message_id) > 0)
    except (OSError, ValueError, TypeError, subprocess.TimeoutExpired):
        delivered = False
    if delivered:
        saved["lastNotification"] = {"id": pending["id"], "kind": pending["kind"],
                                     "messageId": str(message_id), "deliveredAtMs": now_ms}
        if pending["kind"] == "failure":
            saved["incident"]["failureDelivered"] = True
        saved.pop("pendingNotification", None)
        saved["notificationState"] = "delivered"
    else:
        # Retry only this saved alert, never the underlying user/model/cron work.
        # Ambiguous transport outcomes can duplicate an alert; its stable ID makes this visible.
        saved["notificationState"] = "pending-retry"
    persist(saved)
    return saved


def append_bounded_log(path, receipt, max_bytes=262144):
    line = json.dumps(receipt, ensure_ascii=False, sort_keys=True) + "\n"
    if path.exists() and path.stat().st_size + len(line.encode()) > max_bytes:
        os.replace(path, path.with_suffix(path.suffix + ".1"))
    fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    with os.fdopen(fd, "a") as stream:
        stream.write(line)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--cli", type=Path, default=DEFAULT_CLI)
    parser.add_argument("--package", type=Path, default=DEFAULT_PACKAGE)
    parser.add_argument("--no-notify", action="store_true")
    parser.add_argument("--no-hermes", action="store_true",
                        help="observe and alert without dispatching an incident review")
    parser.add_argument("--hermes-root", type=Path, default=DEFAULT_HERMES_ROOT)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--test-notification", action="store_true",
                        help="explicitly send one labeled owner notification test, separate from incidents")
    args = parser.parse_args()
    operations = args.state_dir / "operations"
    operations.mkdir(mode=0o700, parents=True, exist_ok=True)
    lock_fd = os.open(operations / "telegram-watchdog.lock", os.O_RDWR | os.O_CREAT, 0o600)
    with os.fdopen(lock_fd, "w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            if args.test_notification:
                print(json.dumps({"ok": False, "notificationState": "observer-busy"}))
                return 75
            return 0
        if args.test_notification:
            if args.no_notify:
                raise ValueError("test notification conflicts with no-notify")
            test_path = operations / "telegram-watchdog-notification-test.json"
            try:
                test = json.loads(test_path.read_text())
            except (OSError, ValueError):
                test = {}
            if test.get("notificationState") == "delivered":
                print(json.dumps({"ok": True, "alreadyDelivered": True, "receipt": test["lastNotification"]}))
                return 0
            now = datetime.now(timezone.utc).isoformat()
            if not test:
                test = {"schemaVersion": 1, "observedAt": now, "pendingNotification": {
                    "id": "ops-connectivity-test-" + hashlib.sha256(now.encode()).hexdigest()[:12],
                    "kind": "test", "createdAt": now, "attempts": 0}}
            test = send_pending(test, args.state_dir, args.cli, int(time.time() * 1000),
                                lambda value: atomic_json(test_path, value))
            delivered = test.get("notificationState") == "delivered"
            print(json.dumps({"ok": delivered, "receipt": test.get("lastNotification"),
                              "notificationState": test.get("notificationState")}, ensure_ascii=False))
            return 0 if delivered else 1
        state_path = operations / "telegram-watchdog-status.json"
        try:
            saved = json.loads(state_path.read_text())
            if not isinstance(saved, dict):
                saved = {}
        except (OSError, ValueError):
            saved = {}
        gateway = verify(args.state_dir, args.cli, args.package)
        summary = load_status().collect(args.state_dir, include_observer=False)
        issues = summary["issues"] + ([] if gateway["ok"] else [gateway["reason"]])
        healthy = gateway["ok"] and summary["ok"]
        now = datetime.now(timezone.utc).isoformat()
        saved = transition(saved, healthy, issues, now)
        saved["gateway"] = gateway
        saved["summary"] = summary
        persist = lambda value: atomic_json(state_path, value)
        persist(saved)
        if not args.no_notify:
            saved = send_pending(saved, args.state_dir, args.cli, int(time.time() * 1000), persist)
        if not args.no_hermes and not healthy:
            plan = hermes_dispatch_allowed(saved, issues, int(time.time() * 1000))
            if plan:
                saved = dispatch_hermes(saved, plan, args.hermes_root, int(time.time() * 1000), persist)
        receipt = {"schemaVersion": 1, "observedAt": now, "ok": healthy,
                   "gatewayOk": gateway["ok"], "issues": issues,
                   "consecutiveFailures": saved["consecutiveFailures"],
                   "hermesDispatchState": (saved.get("hermesDispatch") or {}).get("state", "none"),
                   "notificationState": saved.get("notificationState", "none")}
        append_bounded_log(operations / "telegram-watchdog-runs.jsonl", receipt)
        if args.json:
            print(json.dumps(receipt, ensure_ascii=False))
        return 0 if healthy else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, TypeError, KeyError):
        print("FAIL operations observer could not persist or inspect local state", file=sys.stderr)
        raise SystemExit(1)
