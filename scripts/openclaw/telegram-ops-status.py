#!/usr/bin/env python3
"""Read aggregate Mac/Telegram operations state without model calls or private content."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import time


DEFAULT_STATE = Path.home() / ".openclaw-personaledge"
DAY_MS = 86400000
QUEUE_STATUSES = {"pending", "sending", "retrying", "failed", "completed", "ambiguous", "dead_letter"}
TASK_STATUSES = {"queued", "running", "waiting", "succeeded", "failed", "cancelled", "canceled", "timed_out", "lost"}
# Native task-executor-policy treats lost as terminal even without ended_at.
TERMINAL_TASKS = {"succeeded", "failed", "cancelled", "canceled", "timed_out", "lost"}
DEFAULT_HERMES_ROOT = Path.home() / ".local/share/openclaw-hermes-worker"
# Executables an exec-capable turn may reach for. This is a reported inventory, never a readiness
# gate: promoting an optional convenience tool to a hard requirement would fail healthy deployments.
EXEC_DEPENDENCIES = ("rg", "jq", "ffmpeg")
MAX_FAILURE_GROUPS = 12
MAX_FAILURE_ROWS = 500
SAFE_TOKEN = re.compile(r"[A-Za-z0-9_.:-]{1,64}")
# Raw error text can carry a command line or a path, so it never leaves this classifier. Only the
# fixed label does.
#
# An app-authored error states its category as a prefix and may then embed arbitrary detail, so the
# anchored patterns are checked first and win. Without that, an exec whose own arguments happen to
# contain a word like "authorization" would be filed as an authentication fault and send a
# diagnosis after the wrong cause. The unanchored fallbacks only ever see a bounded prefix.
ERROR_CLASSES_ANCHORED = (
    ("heartbeat-agent-runner-failure", re.compile(r"heartbeat failed: agent-runner-failure", re.I)),
    ("heartbeat-other", re.compile(r"heartbeat failed", re.I)),
    ("command-failed", re.compile(r"command failed", re.I)),
)
ERROR_CLASSES_CONTAINED = (
    ("timeout", re.compile(r"timed?[ -]?out|timeout", re.I)),
    ("auth-or-subscription", re.compile(r"unauthor|forbidden|credential|auth|token|subscription", re.I)),
    ("cancelled", re.compile(r"cancel", re.I)),
    ("tool-error", re.compile(r"\btool\b", re.I)),
)
CLASSIFY_PREFIX = 80
# OpenClaw injects these curated roots at session start only while their recorded provenance is
# trusted. Its provenance is a one-way ratchet: once a root is stamped untrusted, later writes keep
# it untrusted and the file silently drops out of every new session's bootstrap context.
MEMORY_PROVENANCE_OWNER = "core:memory-artifact-provenance"
MEMORY_PROVENANCE_NAMESPACE = "workspace-files"
CURATED_MEMORY_ROOTS = ("MEMORY.md", "memory.md", "USER.md")
ISSUE_LABELS = {
    "delivery-failed": "전송 실패", "delivery-stale": "전송 대기 15분 초과",
    "task-long-running": "작업 실행 1시간 초과", "dreaming-unhealthy": "야간 예약 작업 오류",
    "dreaming-success-stale": "야간 예약 성공 기록 지연", "backup-unavailable": "복구용 백업 확인 필요",
    "backup-stale": "최근 백업 72시간 경과", "backup-restore-unverified": "백업 복구 검증 전",
    "observer-snapshot-stale": "정기 검사 기록 지연", "operations-read-failed": "운영 기록 조회 실패",
    "observer-gateway-unhealthy": "최근 Gateway 검사 실패",
    "gateway-policy-or-runtime-check-failed": "Gateway 설정 또는 실행 검사 실패",
    "gateway-check-timeout": "Gateway 검사 시간 초과", "gateway-check-unavailable": "Gateway 검사 실행 불가",
    "exec-dependency-missing": "실행 도구 누락", "hermes-worker-unavailable": "Hermes 호출 불가",
    "memory-bootstrap-untrusted": "기억 파일 자동 주입 제외(출처 untrusted)",
}


def issue_labels(codes):
    return ", ".join(ISSUE_LABELS.get(code, "확인이 필요한 항목") for code in codes)


def local_time(value):
    if not value:
        return "기록 없음"
    from datetime import timedelta
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(
        timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M:%S KST")


def iso(milliseconds):
    if milliseconds is None:
        return None
    return datetime.fromtimestamp(milliseconds / 1000, timezone.utc).isoformat()


def age_seconds(now, timestamp):
    return max(0, int((now - timestamp) / 1000)) if timestamp is not None else None


def safe_counts(rows, allowed):
    result = {}
    for status, count in rows:
        key = status if status in allowed else "other"
        result[key] = result.get(key, 0) + count
    return result


def safe_token(value):
    """Reduce an app-authored identifier to a fixed shape, or to a constant when it is not one."""
    if not isinstance(value, str) or not SAFE_TOKEN.fullmatch(value):
        return None if value is None else "other"
    return value


def error_class(value):
    if not isinstance(value, str) or not value.strip():
        return "none"
    text = value.strip()
    for label, pattern in ERROR_CLASSES_ANCHORED:
        if pattern.match(text):
            return label
    prefix = text[:CLASSIFY_PREFIX]
    for label, pattern in ERROR_CLASSES_CONTAINED:
        if pattern.search(prefix):
            return label
    return "other"


def exit_code(detail_json):
    try:
        detail = json.loads(detail_json) if isinstance(detail_json, str) else None
    except ValueError:
        return None
    code = detail.get("exitCode") if isinstance(detail, dict) else None
    return code if isinstance(code, int) and not isinstance(code, bool) else None


def task_failure_groups(connection, now, window_ms=DAY_MS):
    """Group recent terminal failures by cause-shaped keys.

    Counts alone cannot tell one repeated fault from several unrelated ones, which is what blocks a
    diagnosis from concluding. Grouping by (error class, exit code, kind, runtime, tool) answers
    that, and no command text, argument, or raw error string is read out of the row.
    """
    rows = connection.execute(
        "SELECT status, error, detail_json, task_kind, runtime, last_tool_name, "
        "COALESCE(ended_at, created_at) FROM task_runs "
        "WHERE COALESCE(ended_at, created_at) >= ? AND status IN ('failed','timed_out','lost') "
        "ORDER BY COALESCE(ended_at, created_at) DESC LIMIT ?",
        (now - window_ms, MAX_FAILURE_ROWS)).fetchall()
    groups = {}
    for status, error, detail, kind, runtime, tool, at in rows:
        key = (safe_token(status) or "other", error_class(error), exit_code(detail),
               safe_token(kind), safe_token(runtime), safe_token(tool))
        entry = groups.get(key)
        if entry is None:
            groups[key] = {"status": key[0], "errorClass": key[1], "exitCode": key[2],
                           "taskKind": key[3], "runtime": key[4], "lastToolName": key[5],
                           "count": 1, "firstAt": iso(at), "lastAt": iso(at)}
        else:
            entry["count"] += 1
            entry["firstAt"] = iso(at)
    ordered = sorted(groups.values(), key=lambda g: (-g["count"], g["errorClass"]))
    return {"scope": "classified-no-command-text", "windowSeconds": window_ms // 1000,
            "rowsScanned": len(rows), "truncated": len(rows) >= MAX_FAILURE_ROWS,
            "groupsOmitted": max(0, len(ordered) - MAX_FAILURE_GROUPS),
            "groups": ordered[:MAX_FAILURE_GROUPS]}


def gateway_exec_path(state):
    """Read only the PATH assignment out of the Gateway service environment.

    That file also holds secrets, so no other line is parsed, retained, or reported.
    """
    path = state / "service-env/ai.openclaw.personaledge.env"
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_uid != os.getuid():
            return None
        for line in path.read_text().splitlines():
            match = re.fullmatch(r"(?:export\s+)?PATH=['\"]?([^'\"]*)['\"]?", line.strip())
            if match:
                return match.group(1)
    except (OSError, ValueError):
        return None
    return None


def exec_capabilities(state, names=EXEC_DEPENDENCIES):
    """Report whether declared executables resolve in the Gateway's own exec PATH."""
    search = gateway_exec_path(state)
    if not search:
        return {"pathSource": "unavailable", "checked": False, "missing": [], "tools": {}}
    tools = {name: bool(shutil.which(name, path=search)) for name in names}
    return {"pathSource": "gateway-service-env", "checked": True,
            "missing": sorted(n for n, present in tools.items() if not present), "tools": tools}


def hermes_availability(root=DEFAULT_HERMES_ROOT):
    """Check that the operations controller could be invoked, without invoking it or any model."""
    try:
        if not root.is_dir() or root.is_symlink():
            return {"installed": False, "invocable": False, "reason": "not-installed"}
        controller = root / "bin/hermes-operations.py"
        if controller.is_symlink() or not controller.is_file() or controller.stat().st_uid != os.getuid():
            return {"installed": True, "invocable": False, "reason": "controller-missing"}
        # A virtualenv always links its interpreter at the real one, so the link itself is expected.
        # Resolve it and judge the target: a regular executable owned by this user or by root.
        interpreter = (root / "runtime/.venv/bin/python").resolve()
        if not interpreter.is_file() or not os.access(interpreter, os.X_OK):
            return {"installed": True, "invocable": False, "reason": "interpreter-not-executable"}
        if interpreter.stat().st_uid not in (os.getuid(), 0):
            return {"installed": True, "invocable": False, "reason": "interpreter-foreign-owner"}
        version = None
        try:
            installed = json.loads((root / "installation.json").read_text())
            version = installed.get("version") if isinstance(installed, dict) else None
        except (OSError, ValueError):
            return {"installed": True, "invocable": False, "reason": "installation-record-unreadable"}
        return {"installed": True, "invocable": True, "reason": None, "version": safe_token(version)}
    except OSError:
        return {"installed": False, "invocable": False, "reason": "not-readable"}


def backup_status(state, connection, now):
    receipt_path = state / "operations/telegram-backup-latest.json"
    if receipt_path.exists():
        try:
            receipt = json.loads(receipt_path.read_text())
            verified = datetime.fromisoformat(receipt["verifiedAt"].replace("Z", "+00:00"))
            digest = receipt.get("archiveSha256", "")
            archive = Path(receipt["archivePath"])
            if (receipt.get("schemaVersion") == 1 and receipt.get("status") == "VERIFIED"
                    and verified.tzinfo is not None and len(digest) == 64
                    and all(char in "0123456789abcdef" for char in digest)
                    and archive.is_file() and not archive.is_symlink()):
                return {"source": "telegram-verified-receipt", "status": "VERIFIED",
                        "verifiedAt": iso(verified.timestamp() * 1000),
                        "ageSeconds": age_seconds(now, verified.timestamp() * 1000),
                        "archivePresent": True,
                        "verificationScope": "offline-restore-rehearsal"}
        except (OSError, ValueError, TypeError, KeyError):
            pass
        # A missing/corrupt latest archive must not be disguised by an older native record.
        return {"source": "telegram-verified-receipt", "status": "INVALID", "verifiedAt": None,
                "ageSeconds": None, "archivePresent": False}
    row = connection.execute("SELECT created_at, archive_path FROM backup_runs WHERE status='ok' "
                             "ORDER BY created_at DESC LIMIT 1").fetchone()
    return {"source": "native-backup-record", "status": "UNVERIFIED" if row else "MISSING",
            "createdAt": iso(row[0]) if row else None,
            "ageSeconds": age_seconds(now, row[0]) if row else None,
            "archivePresent": bool(row and Path(row[1]).is_file()), "verifiedAt": None}


def memory_bootstrap_provenance(state, connection):
    """Name curated memory roots OpenClaw will leave out of session bootstrap; never read content."""
    config = json.loads((state / "openclaw.json").read_text())
    workspace = config.get("agents", {}).get("defaults", {}).get("workspace")
    if not isinstance(workspace, str) or not workspace:
        return {"scope": "unavailable", "untrusted": []}
    # Same address OpenClaw uses: sha256(realpath(workspace)) + ":" + sha256(relative path).
    workspace_key = hashlib.sha256(os.path.realpath(workspace).encode()).hexdigest()
    untrusted = []
    for name in CURATED_MEMORY_ROOTS:
        row = connection.execute("SELECT value_json FROM plugin_state_entries WHERE plugin_id=? "
                                 "AND namespace=? AND entry_key=?",
                                 (MEMORY_PROVENANCE_OWNER, MEMORY_PROVENANCE_NAMESPACE,
                                  workspace_key + ":" + hashlib.sha256(name.encode()).hexdigest())).fetchone()
        value = json.loads(row[0]) if row else {}
        if (isinstance(value, dict) and value.get("relativePath") == name
                and value.get("workspaceKey") == workspace_key and value.get("originClass") == "untrusted"):
            untrusted.append(name)
    return {"scope": "curated-roots", "untrusted": untrusted}


def collect(state=DEFAULT_STATE, now=None, queue_stale_seconds=900, long_task_seconds=3600,
            include_observer=True, include_failure_detail=False, hermes_root=DEFAULT_HERMES_ROOT):
    now = int(time.time() * 1000) if now is None else now
    result = {"schemaVersion": 1, "observedAt": iso(now), "ok": False, "issues": [], "warnings": []}
    database = state / "state/openclaw.sqlite"
    try:
        connection = sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True, timeout=5)
        try:
            connection.execute("PRAGMA query_only=ON")
            connection.execute("BEGIN")
            counts = safe_counts(connection.execute("SELECT status, COUNT(*) FROM delivery_queue_entries "
                                                     "GROUP BY status"), QUEUE_STATUSES)
            queue = connection.execute("SELECT COUNT(*), MIN(enqueued_at), "
                                       "SUM(CASE WHEN status='failed' THEN 1 ELSE 0 END) "
                                       "FROM delivery_queue_entries WHERE status!='completed'").fetchone()
            result["queue"] = {"statusCounts": counts, "pending": queue[0],
                               "oldestAgeSeconds": age_seconds(now, queue[1]), "failed": queue[2] or 0}
            recent = safe_counts(connection.execute("SELECT status,COUNT(*) FROM task_runs "
                                                      "WHERE COALESCE(ended_at,created_at)>=? GROUP BY status",
                                                      (now - DAY_MS,)), TASK_STATUSES)
            placeholders = ",".join("?" for _ in TERMINAL_TASKS)
            tasks = connection.execute("SELECT COUNT(*),MIN(COALESCE(started_at,created_at)), "
                                       "SUM(CASE WHEN COALESCE(started_at,created_at)<? THEN 1 ELSE 0 END) "
                                       "FROM task_runs WHERE status NOT IN (" + placeholders + ")",
                                       (now - long_task_seconds * 1000, *sorted(TERMINAL_TASKS))).fetchone()
            result["tasks"] = {"recent24hStatusCounts": recent, "active": tasks[0],
                               "longRunning": tasks[2] or 0, "longRunningThresholdSeconds": long_task_seconds,
                               "oldestActiveAgeSeconds": age_seconds(now, tasks[1]),
                               "failed24h": sum(recent.get(key, 0) for key in ("failed", "timed_out", "lost"))}
            cron = connection.execute("SELECT MAX(CASE WHEN status='ok' THEN finished_at_ms END), "
                                      "SUM(CASE WHEN status IN ('error','interrupted') AND started_at_ms>=? "
                                      "THEN 1 ELSE 0 END) FROM cron_run_receipts", (now - DAY_MS,)).fetchone()
            dreaming = connection.execute("SELECT state_json,enabled FROM cron_jobs "
                                           "WHERE declaration_key='memory-core:memory-dreaming-promotion' "
                                           "LIMIT 1").fetchone()
            dream_state = json.loads(dreaming[0]) if dreaming else {}
            last_run = dream_state.get("lastRunAtMs")
            dream_status = dream_state.get("lastRunStatus", dream_state.get("lastStatus"))
            dream_status = dream_status if dream_status in {"ok", "error", "skipped", "running"} else "unknown"
            dream_ok = connection.execute("SELECT MAX(r.finished_at_ms) FROM cron_run_receipts r "
                                           "JOIN cron_jobs j ON r.store_key=j.store_key AND r.job_id=j.job_id "
                                           "WHERE j.declaration_key='memory-core:memory-dreaming-promotion' "
                                           "AND r.status='ok'").fetchone()[0]
            result["cron"] = {"lastSuccessAt": iso(cron[0]), "failed24h": cron[1] or 0,
                              "dreaming": {"enabled": bool(dreaming and dreaming[1]), "lastStatus": dream_status,
                                           "lastRunAt": iso(last_run), "lastSuccessAt": iso(dream_ok),
                                           "lastSuccessAgeSeconds": age_seconds(now, dream_ok),
                                           "consecutiveErrors": int(dream_state.get("consecutiveErrors", 0)),
                                           "scope": "scheduler-receipt"}}
            result["backup"] = backup_status(state, connection, now)
            # A schema or config this check cannot read must not discard the other aggregates.
            try:
                result["memoryProvenance"] = memory_bootstrap_provenance(state, connection)
            except (sqlite3.Error, OSError, ValueError, TypeError, AttributeError) as error:
                result["memoryProvenance"] = {"scope": "unavailable", "untrusted": [],
                                              "errorType": type(error).__name__}
            if include_failure_detail:
                # An upgrade may add or rename task columns. Losing the grouping is acceptable;
                # letting that failure discard every other aggregate in this block is not.
                try:
                    result["taskFailureGroups"] = task_failure_groups(connection, now)
                except (sqlite3.Error, ValueError, TypeError) as error:
                    result["taskFailureGroups"] = {"scope": "unavailable", "groups": [],
                                                   "errorType": type(error).__name__}
        finally:
            connection.close()
        if result["queue"]["failed"]:
            result["issues"].append("delivery-failed")
        if (result["queue"]["oldestAgeSeconds"] or 0) >= queue_stale_seconds:
            result["issues"].append("delivery-stale")
        if result["tasks"]["longRunning"]:
            result["issues"].append("task-long-running")
        dream = result["cron"]["dreaming"]
        if not dream["enabled"] or dream["lastStatus"] == "error" or dream["consecutiveErrors"]:
            result["issues"].append("dreaming-unhealthy")
        if dream["lastSuccessAgeSeconds"] is None or dream["lastSuccessAgeSeconds"] > 36 * 3600:
            result["issues"].append("dreaming-success-stale")
        backup = result["backup"]
        if backup["status"] in {"MISSING", "INVALID"} or not backup["archivePresent"]:
            result["issues"].append("backup-unavailable")
        elif backup["ageSeconds"] > 72 * 3600:
            result["issues"].append("backup-stale")
        if backup["status"] == "UNVERIFIED":
            result["warnings"].append("backup-restore-unverified")
        if result["memoryProvenance"]["untrusted"]:
            result["issues"].append("memory-bootstrap-untrusted")
        result["ok"] = not result["issues"]
    except (sqlite3.Error, OSError, ValueError, TypeError, KeyError, OverflowError):
        result["issues"] = ["operations-read-failed"]
    try:
        saved = json.loads((state / "operations/telegram-watchdog-status.json").read_text())
        checked = datetime.fromisoformat(saved["observedAt"].replace("Z", "+00:00"))
        check_age = age_seconds(now, checked.timestamp() * 1000)
        result["observer"] = {"lastCheckAt": saved.get("observedAt"), "lastSuccessAt": saved.get("lastSuccessAt"),
                              "healthy": saved.get("healthy"), "gatewayHealthy": saved.get("gateway", {}).get("ok"),
                              "consecutiveFailures": saved.get("consecutiveFailures", 0),
                              "ageSeconds": check_age, "stale": check_age > 900}
    except (OSError, ValueError, TypeError, KeyError):
        result["observer"] = {"lastCheckAt": None, "lastSuccessAt": None, "healthy": None,
                              "gatewayHealthy": None, "consecutiveFailures": 0, "ageSeconds": None, "stale": True}
    if result["observer"]["stale"]:
        result["warnings"].append("observer-snapshot-stale")
    # Inventory only. These append warnings, never issues, so a missing convenience tool cannot
    # mark the deployment unhealthy or raise an owner alert.
    result["execCapabilities"] = exec_capabilities(state)
    if result["execCapabilities"]["missing"]:
        result["warnings"].append("exec-dependency-missing")
    result["hermes"] = hermes_availability(hermes_root)
    if result["hermes"]["installed"] and not result["hermes"]["invocable"]:
        result["warnings"].append("hermes-worker-unavailable")
    result["operationsOk"] = result["ok"]
    if include_observer and (result["observer"]["stale"] or result["observer"]["gatewayHealthy"] is not True):
        result["ok"] = False
        if result["observer"]["gatewayHealthy"] is False:
            result["issues"].append("observer-gateway-unhealthy")
    return result


def human(result):
    lines = ["OpenClaw 운영 상태", "조회: " + local_time(result["observedAt"]),
             "정기 검사: " + local_time(result["observer"]["lastCheckAt"])]
    if result["observer"]["stale"]:
        lines.append("정기 검사 기록이 15분 이상 지났거나 아직 없습니다.")
    gateway_state = result["observer"]["gatewayHealthy"]
    lines.append("Gateway: " + ("현재 상태 확인 필요" if result["observer"]["stale"] else
                 "최근 검사 정상" if gateway_state is True else "최근 검사 실패" if gateway_state is False else "검사 기록 없음"))
    if "queue" in result:
        queue, tasks = result["queue"], result["tasks"]
        lines.extend(["전송: 미완료 {}건 · 최대 대기 {}초 · 실패 {}건".format(
            queue["pending"], queue["oldestAgeSeconds"] or 0, queue["failed"]),
            "작업: 진행 {}건 · 1시간 초과 {}건 · 최근 24시간 실패/시간 초과 {}건".format(
                tasks["active"], tasks["longRunning"], tasks["failed24h"])])
    if "cron" in result:
        dream = result["cron"]["dreaming"]
        lines.append("야간 작업: {} · 최근 예약 성공 {}".format(dream["lastStatus"], local_time(dream["lastSuccessAt"])))
    if "backup" in result:
        backup = result["backup"]
        lines.append("백업: {} · 복구 검증 {}".format(backup["status"], local_time(backup.get("verifiedAt"))))
    capabilities = result.get("execCapabilities", {})
    if capabilities.get("checked") and capabilities.get("missing"):
        lines.append("실행 도구: " + ", ".join(capabilities["missing"]) + " 없음 (참고용 목록이며 정상 판정에는 영향 없음)")
    hermes = result.get("hermes", {})
    if hermes.get("installed"):
        lines.append("Hermes: " + ("호출 가능" if hermes.get("invocable") else "호출 불가 · " + str(hermes.get("reason"))))
    lines.append("확인 필요: " + (issue_labels(result["issues"]) if result["issues"] else "없음"))
    if result["warnings"]:
        lines.append("참고: " + issue_labels(result["warnings"]))
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    result = collect(args.state_dir)
    print(json.dumps(result, ensure_ascii=False) if args.json else human(result))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
