#!/usr/bin/env python3
"""Collect bounded OpenClaw evidence, run Hermes, and test proposed changes offline."""

import argparse
import copy
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
import fcntl
import fnmatch
import hashlib
import importlib.util
import inspect
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
import uuid

DEFAULT_ROOT = Path.home() / ".local/share/openclaw-hermes-worker"
DEFAULT_REPO = Path("/Users/jk/projects/python/my-local-agent")
DEFAULT_STATE = Path.home() / ".openclaw-personaledge"
DEFAULT_PACKAGE = Path.home() / ".local/openclaw-2026.8.1/lib/node_modules/openclaw"
DEFAULT_GATEWAY_LOG = Path.home() / "Library/Logs/openclaw/gateway-personaledge.log"
KST = timezone(timedelta(hours=9))
UPSTREAM = {
    "openclaw": "https://api.github.com/repos/openclaw/openclaw/releases/latest",
    "hermes": "https://api.github.com/repos/NousResearch/hermes-agent/releases/latest",
}
LEDGER_LIMIT = 8
APPLIED_LIMIT = 8
LEDGER_MAX_BYTES = 262144
SAFE_ID = re.compile(r"[A-Za-z0-9_.:-]{1,64}")
SEVERITIES = ("critical", "high", "error", "warning", "medium", "low", "info")
PATCH_SPECS = "scripts/openclaw/runtime-patch-specs.json"
DOCUMENTS = (
    "OPENCLAW_TELEGRAM.md", "OPENCLAW_OPERATIONS_KO.md", "OPENCLAW_TASK_STATUS.md",
    "OPENCLAW_HEARTBEAT_RECOVERY_20260910.md", "OPENCLAW_UPDATE_20260909.md",
    "OPENCLAW_HERMES_OPERATIONS.md", "OPENCLAW_BRIEFING.md", "OPENCLAW_WEEKLY_BRIEFING.md",
)
# An incident review reads the checks that raised the incident, not the whole tree. Offering every
# source made incident runs spend their input budget on unrelated files (7 of 8 failed, 2026-09).
# Keep this set equal to telegram-watchdog.HERMES_INCIDENT_ISSUES.
INCIDENT_ISSUES = frozenset({
    "gateway-policy-or-runtime-check-failed", "gateway-check-timeout", "gateway-check-unavailable",
    "observer-gateway-unhealthy", "task-long-running", "dreaming-unhealthy",
})
INCIDENT_BASE_SOURCES = ("scripts/openclaw/telegram-ops-status.py", "docs/OPENCLAW_OPERATIONS_KO.md")
INCIDENT_GATEWAY_SOURCES = ("scripts/openclaw/verify-telegram-gateway.py", "scripts/openclaw/telegram-watchdog.py",
                            "scripts/openclaw/runtime-patch-specs.json")
INCIDENT_TASK_SOURCES = ("scripts/openclaw/telegram-task-status.py", "docs/OPENCLAW_TASK_STATUS.md")
# Update impact, release notes and review history belong to weekly and manual reviews.
INCIDENT_EVIDENCE_KEYS = ("observedAt", "scope", "operations", "configuration", "openclaw", "hermes",
                          "failureDetailAvailable", "historicalLogTailCounts", "findingLifecycle",
                          "procedureKnowledge", "upstreamChecked", "incident")
INCIDENT_REQUEST = ("감시기가 연 현재 사건의 원인을 진단하세요. 제공된 사건 관련 소스와 근거만 사용하고, "
                    "업데이트 영향 검토와 전체 소스 감사는 이번 범위가 아닙니다.")
OBSERVER_CODE = re.compile(r"[a-z0-9][a-z0-9-]{0,63}")
# The whole source bundle (~1.9 MB, ~474K estimated tokens on 2026-09-27) exceeds the 272K Sol
# window, so a complete source audit runs one area per worker. Each area keeps code with its tests
# and stays near 200 KB so it can be read in full before the worker's early finalization.
# A path belongs to the first matching area; anything unmatched lands in "unassigned" and is still
# reviewed, so a new file is never silently skipped.
_S = "scripts/openclaw/"
REVIEW_AREAS = (
    ("telegram-workflows", (_S + "telegram-meeting.py", _S + "test-telegram-meeting.py", _S + "telegram-briefing.py",
                            _S + "test-telegram-briefing.py", _S + "telegram-weekly-briefing.py",
                            _S + "test-telegram-weekly-briefing.py", "docs/OPENCLAW_BRIEFING.md",
                            "docs/OPENCLAW_WEEKLY_BRIEFING.md")),
    ("telegram-tasks-productivity", (_S + "telegram-task-status.py", _S + "test-telegram-task-status.py",
                                     _S + "telegram-productivity-acceptance.py", _S + "test-telegram-productivity-acceptance.py",
                                     _S + "productivity-fixtures.py", _S + "summarize-*.py", _S + "test-summarize-openclaw.py",
                                     _S + "office.sh", _S + "install-telegram-workflows.py",
                                     _S + "test-install-telegram-workflows.py", _S + "test-plugin-extractors.mjs",
                                     "docs/OPENCLAW_TASK_STATUS.md")),
    ("telegram-operations", (_S + "telegram-ops-status.py", _S + "telegram-watchdog.py", _S + "install-telegram-watchdog.py",
                             _S + "test-telegram-operations.py", _S + "telegram-backup.py", _S + "test-telegram-backup.py",
                             _S + "backup-gateway.sh", _S + "restore-gateway.sh", "docs/OPENCLAW_OPERATIONS_KO.md",
                             "docs/OPENCLAW_HEARTBEAT_RECOVERY_20260910.md")),
    ("telegram-gateway-delivery", (_S + "verify-telegram-gateway.py", _S + "test-telegram-gateway.py",
                                   _S + "telegram-delivery-retry.mjs", _S + "test-telegram-delivery-retry.mjs",
                                   _S + "patch-telegram-delivery.py", _S + "smoke-response-contract.py",
                                   _S + "test-smoke-response-contract.py",
                                   _S + "test-codex-response-context.mjs", _S + "templates/*", "docs/OPENCLAW_TELEGRAM.md")),
    ("gateway-install", (_S + "_common.sh", _S + "adopt-existing.sh", _S + "install-gateway.sh", _S + "harden-existing.sh",
                         _S + "enable-tailscale-serve.sh", _S + "verify-gateway.sh", _S + "rollback-gateway.sh",
                         _S + "status-gateway.sh", _S + "host-readiness.sh", _S + "install-colima-runtime.sh",
                         _S + "export-diagnostics.sh", _S + "security-audit.sh", _S + "bootstrap-fold8-openclaw.mjs",
                         _S + "update-watchdog.sh", _S + "watchdog.sh", _S + "health-extension/*",
                         _S + "test-managed-tailscale-ingress.sh", _S + "test-remote-health-publisher.sh",
                         _S + "test-bootstrap-repair.sh")),
    ("gateway-acceptance", (_S + "test-deployment-assets.sh", _S + "soak-acceptance.sh", _S + "test-runtime-continuity.sh",
                            _S + "_runtime-test-fixture.sh")),
    ("runtime-patches-models", (_S + "patch-*.mjs", _S + "patch-gpt6-sol.py", _S + "test-patch-gpt6-sol.py",
                                _S + "qualify-runtime-patches.py", PATCH_SPECS, _S + "*.patch.json",
                                _S + "test-runtime-patches.py", _S + "test-auth-reprobe.mjs", _S + "test-glm-thinking.mjs",
                                _S + "smoke-glm-thinking.py", _S + "smoke-model.sh", _S + "*openrouter*",
                                "docs/OPENCLAW_UPDATE_20260909.md")),
    ("hermes-controller", (_S + "hermes-operations.py", _S + "test-hermes-operations.py", _S + "hermes-ops-changes.py",
                           _S + "test-hermes-ops-changes.py", _S + "hermes-ops-patches.py", _S + "test-hermes-ops-patches.py",
                           _S + "hermes-ops-knowledge.py")),
    ("hermes-worker", (_S + "hermes-ops-worker.py", _S + "test-hermes-ops-worker.py", _S + "hermes-report-worker.py",
                       _S + "test-hermes-report-worker.py", _S + "hermes-operations-report.py",
                       _S + "test-hermes-operations-report.py")),
    ("hermes-knowledge-install", (_S + "test-hermes-ops-knowledge.py", _S + "install-hermes-*.py",
                                  _S + "test-install-hermes-*.py", "docs/OPENCLAW_HERMES_OPERATIONS.md")),
    ("learning-pilots", (_S + "*pilot*", _S + "agent-pilot-fixtures.py", _S + "test-agent-pilot-fixtures.py")),
)
UNASSIGNED_AREA = "unassigned"
AREA_NAMES = tuple(name for name, _patterns in REVIEW_AREAS) + (UNASSIGNED_AREA,)


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), Path(__file__).with_name(name + ".py"))
    value = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = value
    spec.loader.exec_module(value)
    return value


def require(value, message):
    if not value:
        raise ValueError(message)


def safe(path, exists=False):
    path = Path(path)
    require(path.is_absolute() and ".." not in path.parts, "ABSOLUTE_PATH_REQUIRED")
    require(not any(p.is_symlink() for p in (path, *path.parents)), "SYMLINK_REFUSED")
    require(not exists or path.exists(), "PATH_MISSING")
    if path.exists():
        require(path.stat().st_uid == os.getuid(), "OWNER_MISMATCH")
    return path


def private_dir(path):
    safe(path).mkdir(mode=0o700, parents=True, exist_ok=True)
    path.chmod(0o700)
    return path


def write_json(path, value):
    safe(path)
    fd, temporary = tempfile.mkstemp(prefix=".ops-", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_json(path, default=None):
    if not path.exists() and default is not None:
        return default
    safe(path, True)
    require(path.is_file() and path.stat().st_size <= 16 * 1024 * 1024, "JSON_FILE_INVALID")
    return json.loads(path.read_text())


def now_iso():
    return datetime.now(timezone.utc).isoformat()


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def source_paths(repo):
    paths = []
    for path in sorted((repo / "scripts/openclaw").rglob("*")):
        if path.is_file() and path.suffix in {".py", ".mjs", ".sh", ".json", ".md"}:
            if "__pycache__" not in path.parts:
                paths.append(str(path.relative_to(repo)))
    paths.extend("docs/" + name for name in DOCUMENTS if (repo / "docs" / name).is_file())
    return paths


def area_assignment(paths):
    """Map every source path to exactly one review area, in declaration order."""
    areas = {name: [] for name in AREA_NAMES}
    for path in paths:
        area = next((name for name, patterns in REVIEW_AREAS
                     if any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns)), UNASSIGNED_AREA)
        areas[area].append(path)
    return areas


def area_view(evidence, area, sources):
    """Model evidence and sources for one area of a split source audit."""
    paths = area_assignment(sorted(sources))[area]
    view = dict(evidence)
    view["areaScope"] = {"area": area, "sourcePaths": paths,
                         "sourceBytes": sum(len(sources[path]["content"].encode()) for path in paths),
                         "notIncluded": ["other-areas", "update-impact", "release-notes"]}
    return view, paths


def area_request(scope, extra=""):
    text = ("소스 영역 '{area}' 검토: 제공된 소스 {count}개({size} bytes)를 모두 읽고 결함, 운영 위험, "
            "테스트 공백을 찾으세요. 현재 운영 근거와 대조하되 다른 영역과 업데이트 영향 검토는 이번 범위가 "
            "아닙니다. 코드 후보는 후보 정책이 수정을 허용하는 파일에만 작성하고, 그 밖의 결함은 재현 근거와 "
            "함께 발견사항으로만 보고하세요.").format(
        area=scope["area"], count=len(scope["sourcePaths"]), size=scope["sourceBytes"])
    return text + ("\n\n" + extra if extra else "")


def append_bounded_line(path, value, max_bytes=LEDGER_MAX_BYTES):
    safe(path)
    line = json.dumps(value, ensure_ascii=False, sort_keys=True, allow_nan=False) + "\n"
    if path.exists() and path.stat().st_size + len(line.encode()) > max_bytes:
        os.replace(path, path.with_suffix(path.suffix + ".1"))
    handle = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    with os.fdopen(handle, "a") as stream:
        stream.write(line)


def read_bounded_lines(path, limit):
    if not path.exists():
        return []
    try:
        safe(path, True)
        rows = []
        for line in path.read_text().splitlines()[-limit:]:
            try:
                value = json.loads(line)
            except ValueError:
                continue
            if isinstance(value, dict):
                rows.append(value)
        return rows
    except (OSError, ValueError):
        return []


def finding_ids(findings):
    """Keep only fixed-shape identifiers. Model prose never re-enters a later request."""
    ids = []
    for finding in findings if isinstance(findings, list) else []:
        value = finding.get("id") if isinstance(finding, dict) else None
        if isinstance(value, str) and SAFE_ID.fullmatch(value):
            ids.append(value)
    return sorted(set(ids))


def severity_counts(findings):
    counts = {}
    for finding in findings if isinstance(findings, list) else []:
        value = finding.get("severity") if isinstance(finding, dict) else None
        key = value if value in SEVERITIES else "other"
        counts[key] = counts.get(key, 0) + 1
    return counts


def review_history(operations):
    """Recent review outcomes so a repeated finding is visible as repetition, not as a fresh opinion."""
    rows = read_bounded_lines(operations / "review-ledger.jsonl", LEDGER_LIMIT)
    repeats = {}
    for row in rows:
        for value in row.get("findingIds", []) if isinstance(row.get("findingIds"), list) else []:
            if isinstance(value, str) and SAFE_ID.fullmatch(value):
                repeats[value] = repeats.get(value, 0) + 1
    return {"scope": "prior-run-identifiers-and-outcomes-only", "reviews": rows,
            "findingRepeatCounts": dict(sorted(repeats.items(), key=lambda kv: (-kv[1], kv[0]))[:32])}


def applied_changes(operations):
    """What was actually installed since the last review, so a recommendation is not re-proposed."""
    rows = read_bounded_lines(operations / "applied-changes.jsonl", APPLIED_LIMIT)
    return {"scope": "owner-or-agent-recorded-deployments", "count": len(rows), "entries": rows}


def patch_coverage(repo, evidence, upstream):
    """Say whether the pinned local runtime patches are qualified for the newest published release.

    Qualification itself needs that release's bytes, so this reports coverage only: an upgrade with
    no spec entry is an unqualified upgrade, which is exactly the thing worth knowing in advance.
    """
    result = {"scope": "spec-coverage-not-a-qualification-run", "installedVersion": None,
              "latestTag": None, "specVersions": [], "installedCovered": None,
              "latestCovered": None, "upgradeAvailable": None}
    try:
        specs = read_json(safe(repo / PATCH_SPECS, True))
        result["specVersions"] = sorted(specs) if isinstance(specs, dict) else []
    except (OSError, ValueError, TypeError):
        return dict(result, available=False)
    installed = (evidence.get("openclaw") or {}).get("version")
    result["installedVersion"] = installed if isinstance(installed, str) and SAFE_ID.fullmatch(installed) else None
    for row in upstream if isinstance(upstream, list) else []:
        if row.get("name") == "openclaw" and row.get("ok") and isinstance(row.get("tag"), str):
            tag = row["tag"].lstrip("v")
            result["latestTag"] = tag if SAFE_ID.fullmatch(tag) else None
    if result["installedVersion"]:
        result["installedCovered"] = result["installedVersion"] in result["specVersions"]
    if result["latestTag"]:
        result["latestCovered"] = result["latestTag"] in result["specVersions"]
        result["upgradeAvailable"] = result["latestTag"] != result["installedVersion"]
    result["available"] = True
    return result


def configured_main_routing(config):
    """Project configured main-agent routing, without reading session or credential state."""
    agents = config.get("agents", {})
    defaults = agents.get("defaults", {})
    if "entries" in agents:
        entries = agents["entries"]
        main = entries.get("main", {}) if isinstance(entries, dict) else {}
    else:
        entries = agents.get("list", [])
        main = next((entry for entry in entries if isinstance(entry, dict)
                     and entry.get("id") == "main"), {}) if isinstance(entries, list) else {}
    main = main if isinstance(main, dict) else {}

    def primary(value):
        value = value.get("primary") if isinstance(value, dict) else value
        return value.strip() or None if isinstance(value, str) else None

    def fallbacks(value):
        value = value.get("fallbacks") if isinstance(value, dict) else None
        return [ref for ref in value if isinstance(ref, str)] if isinstance(value, list) else []

    default_model = defaults.get("model")
    main_model = main.get("model")
    main_primary = primary(main_model)
    effective_fallbacks = fallbacks(default_model)
    # Match OpenClaw's resolveSelectedModelFallbacksOverride: an explicit primary is strict
    # unless its object supplies fallbacks. An explicit [] must never inherit the global ladder.
    if isinstance(main_model, dict) and "fallbacks" in main_model:
        if isinstance(main_model["fallbacks"], list):
            effective_fallbacks = fallbacks(main_model)
    elif main_primary:
        effective_fallbacks = []
    thinking = main.get("thinkingDefault")
    return {
        "scope": "main-agent-configured-routing-not-session-or-runtime",
        "model": main_primary or primary(default_model),
        "fallbacks": effective_fallbacks,
        "thinking": defaults.get("thinkingDefault") if thinking is None else thinking,
        "thinkingScope": "agent-or-global-thinking-default",
        "defaults": {"model": primary(default_model), "fallbacks": fallbacks(default_model),
                     "thinking": defaults.get("thinkingDefault")},
    }


def collect_evidence(state, package, root, collector=None):
    collector = collector or module("telegram-ops-status").collect
    result = {"observedAt": now_iso(), "scope": "aggregate-operational-state-no-conversations"}
    # Ask for failure grouping only when the collector really supports it. Passing an unsupported
    # keyword would raise TypeError into the guard below, which would quietly reduce every field of
    # operational evidence to a read failure instead of just omitting the new section.
    try:
        detailed = "include_failure_detail" in inspect.signature(collector).parameters
    except (TypeError, ValueError):
        detailed = False
    result["failureDetailAvailable"] = detailed
    try:
        result["operations"] = collector(state, include_failure_detail=True) if detailed else collector(state)
    except Exception as error:
        result["operations"] = {"ok": False, "issues": ["operations-read-failed"],
                                "errorType": type(error).__name__}
    try:
        config_path = safe(state / "openclaw.json", True)
        require(config_path.stat().st_mode & 0o077 == 0, "CONFIG_NOT_PRIVATE")
        config = read_json(config_path)
        result["configuration"] = {
            **configured_main_routing(config),
            "gatewayMode": config.get("gateway", {}).get("mode"),
            "telegramEnabled": config.get("channels", {}).get("telegram", {}).get("enabled"),
        }
    except Exception as error:
        result["configuration"] = {"available": False, "errorType": type(error).__name__}
    for name, path, keys in (
        ("openclaw", package / "package.json", ("version",)),
        ("hermes", root / "installation.json", ("version", "commit", "lockSha256")),
    ):
        try:
            data = read_json(path)
            result[name] = {k: data.get(k) for k in keys}
        except Exception as error:
            result[name] = {"available": False, "errorType": type(error).__name__}
    # Only aggregate known error categories; raw log messages never enter the request.
    categories = ("subscription_limit", "refresh_token_reused", "EADDRINUSE",
                  "SKILL_SCOPE_VIOLATION", "selected_auth_profile_unavailable")
    tails = {}
    log_paths = [state / "logs" / name for name in ("gateway.err.log", "gateway.log")]
    if state == DEFAULT_STATE:
        log_paths.append(DEFAULT_GATEWAY_LOG)
    for path in log_paths:
        if not path.is_file() or path.is_symlink():
            continue
        try:
            safe(path, True)
            with path.open("rb") as stream:
                stream.seek(max(0, path.stat().st_size - 131072))
                raw = stream.read(131072).decode("utf-8", errors="replace")
            tails[path.name] = {k: raw.count(k) for k in categories}
        except OSError:
            pass
    result["historicalLogTailCounts"] = {"scope": "bounded-tail-may-include-resolved-incidents", "files": tails}
    operations = root / "operations"
    result["reviewHistory"] = review_history(operations)
    result["appliedChanges"] = applied_changes(operations)
    return result


def observer_timestamp(value):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.isoformat() if parsed.tzinfo is not None else None
    except (AttributeError, TypeError, ValueError):
        return None


def observer_codes(value):
    return sorted({item for item in value if isinstance(item, str) and OBSERVER_CODE.fullmatch(item)}
                  if isinstance(value, list) else [])


def observer_incident(state):
    """The observer's current incident as fixed codes and times; never check output or messages."""
    try:
        saved = read_json(safe(state / "operations/telegram-watchdog-status.json", True))
        require(isinstance(saved, dict), "OBSERVER_STATUS_INVALID")
    except (OSError, ValueError, TypeError):
        return {"available": False, "scope": "observer-incident-fixed-codes-only"}
    incident = saved.get("incident") if isinstance(saved.get("incident"), dict) else {}
    dispatch = saved.get("hermesDispatch") if isinstance(saved.get("hermesDispatch"), dict) else {}
    gateway = saved.get("gateway") if isinstance(saved.get("gateway"), dict) else {}
    incident_id = incident.get("id")
    incident_id = incident_id if isinstance(incident_id, str) and re.fullmatch(r"[a-f0-9]{6,32}", incident_id) else None
    failures = saved.get("consecutiveFailures")
    return {"available": True, "scope": "observer-incident-fixed-codes-only", "id": incident_id,
            "active": incident.get("active") is True,
            "startedAt": observer_timestamp(incident.get("startedAt")),
            "recoveredAt": observer_timestamp(incident.get("recoveredAt")),
            "issues": observer_codes(incident.get("issues")),
            "dispatchedIssues": observer_codes(dispatch.get("issues"))
            if incident_id and dispatch.get("lastIncidentId") == incident_id else [],
            "lastCheckAt": observer_timestamp(saved.get("observedAt")),
            "gatewayOkAtLastCheck": gateway.get("ok") if isinstance(gateway.get("ok"), bool) else None,
            "gatewayFailureAtLastCheck": gateway.get("reason") if gateway.get("ok") is False
            and isinstance(gateway.get("reason"), str) and OBSERVER_CODE.fullmatch(gateway["reason"]) else None,
            "consecutiveFailures": failures if type(failures) is int and failures >= 0 else None}


def incident_view(evidence, incident, available_paths):
    """Model evidence and sources for one incident: the issues that raised it and their checks."""
    issues = (incident.get("dispatchedIssues") or incident.get("issues")
              or (evidence.get("operations") or {}).get("issues") or [])
    qualified = sorted(INCIDENT_ISSUES.intersection(issues))
    paths = list(INCIDENT_BASE_SOURCES)
    if any(issue.startswith("gateway-") or issue == "observer-gateway-unhealthy" for issue in qualified):
        paths += INCIDENT_GATEWAY_SOURCES
    if "task-long-running" in qualified:
        paths += INCIDENT_TASK_SOURCES
    paths = [path for path in dict.fromkeys(paths) if path in available_paths]
    view = {key: evidence[key] for key in INCIDENT_EVIDENCE_KEYS if key in evidence}
    view["incident"] = dict(incident, reviewScope={
        "issues": qualified, "sourcePaths": paths,
        "notIncluded": ["full-source-audit", "update-impact", "release-notes", "review-history"]})
    return view, paths


def review_baseline(operations, previous):
    """Read a completed review only after checking its immutable snapshot hashes."""
    if not previous:
        return None, "none"
    try:
        run_id = previous.get("runId")
        require(isinstance(run_id, str) and SAFE_ID.fullmatch(run_id), "BASELINE_ID_INVALID")
        directory = safe(operations / "runs" / run_id, True)
        receipt = read_json(directory / "receipt.json")
        require(receipt.get("runId") == run_id and receipt.get("status") == "reviewed"
                and receipt.get("ok") is True, "BASELINE_NOT_COMPLETED")
        patcher = module("hermes-ops-patches")
        manifest, contents = patcher.verify_snapshot(directory / "snapshot", receipt["snapshotSha256"])
        evidence = read_baseline_artifact(directory / "evidence.json", receipt.get("evidenceSha256"))
        upstream = read_baseline_artifact(directory / "upstream.json", receipt.get("upstreamSha256"))
        require(isinstance(evidence, dict) and isinstance(upstream, list)
                and all(isinstance(row, dict) for row in upstream), "BASELINE_EVIDENCE_INVALID")
        sources = {entry["path"]: {"sha256": entry["sha256"],
                                   "content": contents[entry["path"]].decode("utf-8")}
                   for entry in manifest["files"]}
        return {"runId": run_id, "snapshotSha256": receipt["snapshotSha256"], "sources": sources,
                "evidence": evidence, "upstream": upstream}, "available"
    except (OSError, ValueError, TypeError, KeyError):
        return None, "unavailable"


def read_baseline_artifact(path, expected_sha):
    """Verify the exact bytes being decoded, including the prior release bodies."""
    require(isinstance(expected_sha, str) and re.fullmatch(r"[a-f0-9]{64}", expected_sha),
            "BASELINE_ARTIFACT_HASH_REQUIRED")
    path = safe(path, True)
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_size <= 16 * 1024 * 1024,
            "BASELINE_ARTIFACT_INVALID")
    data = path.read_bytes()
    require(hashlib.sha256(data).hexdigest() == expected_sha, "BASELINE_ARTIFACT_HASH_MISMATCH")
    return json.loads(data)


def knowledge_context(operations, evidence):
    versions = {name: evidence[name]["version"] for name in ("openclaw", "hermes")
                if isinstance(evidence.get(name), dict) and isinstance(evidence[name].get("version"), str)}
    summary = module("hermes-ops-knowledge").summary(operations, versions)
    procedures = summary.get("procedures", [])
    # Procedure text is supplied once in learningContext. Evidence retains only safe identities
    # and outcomes; worker citations may point to procedureKnowledge.
    evidence["procedureKnowledge"] = {key: value for key, value in summary.items()
                                      if key not in {"procedures", "findingLifecycle"}}
    evidence["procedureKnowledge"]["procedures"] = [
        {key: row[key] for key in ("id", "version", "sha256")} for row in procedures]
    evidence["findingLifecycle"] = summary.get("findingLifecycle", [])
    evidence["procedureKnowledge"]["proofStateSha256"] = digest({
        "available": evidence["procedureKnowledge"]["procedures"],
        "unavailable": summary.get("unavailableProcedures", []),
        "closures": [{"id": row["id"], "valid": row["closureEvidenceValid"]}
                     for row in evidence["findingLifecycle"] if "closureEvidenceValid" in row],
        "invalidReuses": [{"id": row["id"], "count": row["invalidReuseEvidence"]}
                          for row in summary.get("procedureStats", []) if row.get("invalidReuseEvidence")]})
    evidence["procedureKnowledge"]["attentionRequired"] = (
        any(row.get("reason") == "evidence-invalid" for row in summary.get("unavailableProcedures", []))
        or any(row.get("closureEvidenceValid") is False for row in evidence["findingLifecycle"])
        or any(row.get("invalidReuseEvidence") for row in summary.get("procedureStats", [])))
    return {"schemaVersion": 1, "procedures": procedures}


def bounded_model_evidence(evidence, upstream):
    """Keep full local evidence; bound growing knowledge only in the model view."""
    result = json.loads(json.dumps(evidence, ensure_ascii=False))
    knowledge = result.get("procedureKnowledge", {})
    rows = [(knowledge, "procedureStats"), (result, "findingLifecycle"),
            (knowledge, "unavailableProcedures")]
    counts = {key: len(parent.get(key, [])) for parent, key in rows}
    knowledge["modelView"] = {"truncated": False, "omittedCounts": {key: 0 for key in counts}}
    worker = module("hermes-ops-worker")
    def oversized():
        return (len(worker.encoded({"evidence": result, "upstream": upstream})) > 65536
                or len(worker.encoded({"knowledge": knowledge,
                                       "findings": result.get("findingLifecycle", [])})) > 8192)
    for parent, key in rows:
        while parent.get(key) and oversized():
            parent[key].pop()
            knowledge["modelView"]["truncated"] = True
            knowledge["modelView"]["omittedCounts"][key] += 1
    return result


def collect_upstream(fetcher=None):
    fetcher = fetcher or module("telegram-briefing").fetch_public

    def fetch(item):
        name, url = item
        try:
            response = fetcher(url, timeout=25, max_bytes=524288)
            value = json.loads(response["body"])
            require(isinstance(value, dict) and isinstance(value.get("tag_name"), str), "RELEASE_RESPONSE_INVALID")
            body = value.get("body") or ""
            require(isinstance(body, str), "RELEASE_BODY_INVALID")
            clipped = body.encode("utf-8")[:24000].decode("utf-8", errors="ignore")
            return {"name": name, "url": url, "ok": True, "tag": value["tag_name"],
                    "publishedAt": value.get("published_at"), "releaseUrl": value.get("html_url"),
                    "body": clipped, "truncated": clipped != body,
                    "fetchedAt": response["fetchedAt"], "sourceTextUntrusted": True}
        except Exception as error:
            row = {"name": name, "url": url, "ok": False, "errorType": type(error).__name__,
                   "sourceTextUntrusted": True}
            if isinstance(error, ValueError) and re.fullmatch(r"[A-Z0-9_]{1,80}", str(error)):
                row["errorCode"] = str(error)
            return row

    with ThreadPoolExecutor(max_workers=2) as pool:
        return list(pool.map(fetch, UPSTREAM.items()))


def failure_shape(ops):
    """Failure identity without volume.

    Counts move every run, so including them would defeat deduplication; the set of distinct fault
    shapes is what actually changes when something new breaks.
    """
    groups = (ops.get("taskFailureGroups") or {}).get("groups", [])
    return sorted({"|".join(str(g.get(k)) for k in ("status", "errorClass", "exitCode", "taskKind",
                                                     "runtime", "lastToolName"))
                   for g in groups if isinstance(g, dict)})


def stable_fingerprint(evidence, upstream, snapshot_sha):
    ops = evidence.get("operations", {})
    stable = {"issues": ops.get("issues"), "warnings": ops.get("warnings"),
              "failureShape": failure_shape(ops),
              "execMissing": (ops.get("execCapabilities") or {}).get("missing"),
              "hermesInvocable": (ops.get("hermes") or {}).get("invocable"),
              "configuration": evidence.get("configuration"), "openclaw": evidence.get("openclaw"),
              "hermes": evidence.get("hermes"), "snapshot": snapshot_sha,
              "knowledgeRevision": (evidence.get("procedureKnowledge") or {}).get("operatorRevision", 0),
              "knowledgeProofState": (evidence.get("procedureKnowledge") or {}).get("proofStateSha256"),
              "releases": [{"name": r["name"], "ok": r["ok"], "tag": r.get("tag"),
                            "bodyHash": digest(r.get("body", ""))} for r in upstream]}
    if isinstance(evidence.get("incident"), dict):
        # A new observer incident with the same fault shape is a separate event and must reach the
        # model; only a repeated dispatch of the same incident is deduplicated.
        stable["incident"] = {key: evidence["incident"].get(key) for key in ("id", "startedAt")}
    return digest(stable)


def run_worker(root, request_path, receipt_path, log_path, runner=subprocess.Popen):
    installer = module("install-hermes-worker")
    command = [str(root / "runtime/.venv/bin/python"), str(Path(__file__).with_name("hermes-ops-worker.py")),
               "--profile-dir", str(root / "profile"), "--request-file", str(request_path),
               "--receipt-file", str(receipt_path)]
    with log_path.open("x") as log:
        log_path.chmod(0o600)
        process = runner(command, env=installer.clean_environment(root), cwd=root / "profile",
                         stdout=log, stderr=log, start_new_session=True)
        try:
            # Outlive the worker's own 900 s run budget so its receipt is written first.
            code = process.wait(timeout=960)
        except BaseException:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
            raise
    receipt = read_json(receipt_path)
    require(code == 0 and receipt.get("completed") is True,
            "HERMES_WORKER_FAILED:" + str(receipt.get("error_code", "UNKNOWN")))
    return receipt


def report_text(receipt, result):
    lines = ["# Hermes OpenClaw 운영 검토", "", "실행: " + receipt["runId"],
             "시각: " + receipt["startedAt"], "", result.get("analysis", ""), ""]
    for finding in result.get("findings", []):
        label = " (근거 불충분)" if finding.get("evidenceStatus") == "insufficient" else ""
        lines += ["- " + finding.get("severity", "info") + ": " + finding.get("title", finding.get("id", "finding")) + label,
                  "  " + finding.get("recommendation", "")]
    coverage = receipt.get("sourceCoverage")
    if isinstance(coverage, dict):
        state = {True: "전체 읽음", False: "일부만 읽음", None: "증분 검토"}[receipt.get("sourceReviewComplete")]
        lines += ["", "소스 읽기: {} ({}/{}개 끝까지 읽음)".format(state, coverage["fullyRead"], coverage["sourceCount"])]
    lines += ["", "코드 후보 검증: " + str(receipt.get("candidateVerification", {}).get("status", "none")),
              "운영 반영: 별도 배포 기록 필요", "Telegram 전송: 수행하지 않음", ""]
    return "\n".join(lines)


def source_coverage(sources, read_metrics):
    """Deterministic read coverage of the offered sources; a model's own claim is not evidence."""
    read = (read_metrics or {}).get("sources") or {}
    partial, unread = [], []
    for path, entry in sources.items():
        total = len(entry["content"].splitlines())
        seen = read.get(path)
        if not isinstance(seen, dict):
            unread.append(path)
        elif not isinstance(seen.get("unique_lines"), int) or seen["unique_lines"] < total:
            partial.append(path)
    return {"complete": not partial and not unread, "sourceCount": len(sources),
            "fullyRead": len(sources) - len(partial) - len(unread), "partial": partial, "unread": unread}


def retain_worker_evidence(receipt, worker, path):
    metrics = worker.get("model_input_metrics")
    attempts = metrics.get("dispatch_attempts") if isinstance(metrics, dict) else None
    # A missing response does not prove no request was sent. Legacy receipts did
    # not count failed dispatches, so their request count remains unavailable.
    inference_requests = attempts if type(attempts) is int and attempts >= 0 else None
    receipt.update(model=worker.get("model"), usage=worker.get("usage"),
                   modelInferenceRequests=inference_requests,
                   modelResponses=len(worker["response_models"])
                   if isinstance(worker.get("response_models"), list) else None,
                   reusedSkill=any(t.get("name") == "skill_view" and t.get("succeeded") is True
                                   for t in worker.get("tool_calls", [])),
                   workerReceiptPath=str(path))
    for key in ("read_metrics", "model_input_metrics"):
        if key in worker:
            receipt[key] = worker[key]


def run_review(args, worker_runner=run_worker, upstream_collector=collect_upstream, evidence_collector=collect_evidence):
    root, repo, state = safe(args.root, True), safe(args.repo, True), safe(args.state_dir, True)
    operations = private_dir(root / "operations")
    lock_path = operations / ".review.lock"
    safe(lock_path)
    with lock_path.open("a") as lock:
        lock_path.chmod(0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return {"ok": True, "status": "busy", "modelInferenceRequests": 0, "modelResponses": 0, "applied": False}
        if getattr(args, "area", None) == "all":
            if args.mode != "manual":
                return {"ok": False, "status": "failed", "errorCode": "AREA_REQUIRES_MANUAL_MODE",
                        "modelInferenceRequests": 0, "modelResponses": 0, "applied": False}
            return run_all_areas(args, root, repo, state, operations, worker_runner, upstream_collector,
                                 evidence_collector)
        return run_locked(args, root, repo, state, operations, worker_runner, upstream_collector, evidence_collector)


def run_all_areas(args, root, repo, state, operations, worker_runner, upstream_collector, evidence_collector):
    """One worker per non-empty area, serially under the review lock; a failed area does not stop the rest."""
    started = now_iso()
    areas = area_assignment(source_paths(repo))
    runs = []
    for area in AREA_NAMES:
        if not areas[area]:
            continue
        area_args = copy.copy(args)
        area_args.area = area
        receipt = run_locked(area_args, root, repo, state, operations, worker_runner, upstream_collector,
                             evidence_collector)
        runs.append({"area": area, "runId": receipt["runId"], "status": receipt["status"], "ok": receipt.get("ok") is True,
                     "receiptPath": receipt["receiptPath"], "reportPath": receipt.get("reportPath"),
                     "sourceCount": len(areas[area]), "attentionRequired": bool(receipt.get("attentionRequired")),
                     "modelInferenceRequests": receipt.get("modelInferenceRequests"),
                     "sourceReviewComplete": receipt.get("sourceReviewComplete"),
                     "finalizationReason": (receipt.get("model_input_metrics") or {}).get("finalization_reason")})
    summary = {"schemaVersion": 1, "status": "area-audit", "ok": bool(runs) and all(run["ok"] for run in runs),
               "startedAt": started, "finishedAt": now_iso(), "applied": False, "telegramDelivered": False,
               "areas": runs, "failedAreas": [run["area"] for run in runs if not run["ok"]],
               "incompleteAreas": [run["area"] for run in runs if run["sourceReviewComplete"] is False],
               "modelInferenceRequests": sum(run["modelInferenceRequests"] or 0 for run in runs)}
    write_json(operations / "latest-area-audit.json", summary)
    return summary


def run_locked(args, root, repo, state, operations, worker_runner, upstream_collector, evidence_collector):
    attempts_path = operations / "weekly-attempts.json"
    attempts = read_json(attempts_path, {})
    week = datetime.now(KST).strftime("%G-W%V")
    area = getattr(args, "area", None)
    if area and (area not in AREA_NAMES or args.mode != "manual"):
        return {"ok": False, "status": "failed", "errorCode": "AREA_REQUIRES_MANUAL_MODE",
                "modelInferenceRequests": 0, "modelResponses": 0, "applied": False}
    if args.mode == "weekly" and week in attempts and not args.force:
        return {"ok": attempts[week].get("status") == "reviewed", "status": "already-attempted",
                "previous": attempts[week], "modelInferenceRequests": 0, "modelResponses": 0, "applied": False}
    run_id = "ops-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    directory = private_dir(operations / "runs" / run_id)
    receipt_path = directory / "receipt.json"
    receipt = {"schemaVersion": 1, "runId": run_id, "mode": args.mode, "status": "collecting", "area": area,
               "startedAt": now_iso(), "applied": False, "telegramDelivered": False,
               "receiptPath": str(receipt_path), "modelInferenceRequests": 0, "modelResponses": 0, "workerInvocations": 0}
    write_json(receipt_path, receipt)
    # An explicit --full-review keeps the broad behaviour even for an incident.
    scoped = args.mode == "incident" and not getattr(args, "full_review", False)
    try:
        patcher = module("hermes-ops-patches")
        snapshot = directory / "snapshot"
        manifest = patcher.create_snapshot(repo, snapshot, source_paths(repo))
        evidence = evidence_collector(state, args.package, root)
        learning = knowledge_context(operations, evidence)
        # Release notes cannot explain an open incident; a scoped review does not fetch them.
        # An area audit reviews code, not update impact; the unscoped review keeps release notes.
        upstream = upstream_collector() if not args.offline and not scoped and not area else []
        evidence["upstreamChecked"] = not args.offline and not scoped and not area
        if scoped:
            evidence["incident"] = observer_incident(state)
            receipt["upstreamSkipped"] = "incident-scope"
        elif area:
            receipt["upstreamSkipped"] = "area-scope"
        write_json(directory / "upstream.json", upstream)
        receipt["upstreamSha256"] = hashlib.sha256((directory / "upstream.json").read_bytes()).hexdigest()
        receipt["evidencePath"] = str(directory / "evidence.json")
        receipt["snapshotSha256"] = manifest["snapshotSha256"]
        evidence["runtimePatchCoverage"] = patch_coverage(repo, evidence, upstream)
        write_json(directory / "evidence.json", evidence)
        receipt["evidenceSha256"] = hashlib.sha256((directory / "evidence.json").read_bytes()).hexdigest()
        receipt["fingerprint"] = stable_fingerprint(evidence, upstream, manifest["snapshotSha256"])
        # None means not attempted by design, which is neither complete nor a failure.
        receipt["upstreamComplete"] = None if scoped or area else not args.offline and all(r["ok"] for r in upstream)
        # Area audits keep their own per-area baseline so they never become the weekly deduplication baseline.
        previous = (read_json(operations / "area-reviews.json", {}).get(area, {}) if area
                    else read_json(operations / "last-review.json", {}))
        baseline, baseline_status = review_baseline(operations, previous)
        sources = {entry["path"]: {"sha256": entry["sha256"],
                                   "content": (snapshot / entry["path"]).read_text()}
                   for entry in manifest["files"]}
        model_evidence = evidence
        if scoped:
            # The snapshot stays complete so a candidate is still tested against the whole tree;
            # only what the model is offered to read and cite narrows.
            model_evidence, paths = incident_view(evidence, evidence["incident"], sources)
            sources = {path: sources[path] for path in paths}
            if baseline:
                baseline = dict(baseline, sources={path: entry for path, entry in baseline["sources"].items()
                                                   if path in sources})
            receipt["incidentScope"] = model_evidence["incident"]["reviewScope"]
        elif area:
            model_evidence, paths = area_view(evidence, area, sources)
            sources = {path: sources[path] for path in paths}
            if baseline:
                # Keep the area's previous paths, not just today's, so a deleted file is reported as removed.
                previous_paths = set(area_assignment(sorted(baseline["sources"]))[area])
                baseline = dict(baseline, sources={path: entry for path, entry in baseline["sources"].items()
                                                   if path in previous_paths})
            receipt["areaScope"] = model_evidence["areaScope"]
        changes = module("hermes-ops-changes")
        delta = changes.context(model_evidence, upstream, sources, previous=baseline, baseline_status=baseline_status,
                                full_review=args.mode == "weekly" or getattr(args, "full_review", False) or bool(area),
                                scoped=scoped)
        write_json(directory / "changes.json", delta)
        receipt["changesPath"] = str(directory / "changes.json")
        receipt["changeSummary"] = {"baselineStatus": baseline_status, "fullReview": delta["fullReview"],
                                    "changedSources": len(delta["changedSourcePaths"]),
                                    "changedEvidence": len(delta["changedEvidenceIds"]),
                                    "truncated": delta["truncated"]}
        unchanged = (args.mode in {"weekly", "incident"} and not args.force and not getattr(args, "full_review", False)
                     and previous.get("fingerprint") == receipt["fingerprint"]
                     and previous.get("status") == "reviewed" and baseline_status == "available")
        if args.collect_only or (args.mode == "incident" and evidence.get("operations", {}).get("ok") is True
                                 and not evidence["procedureKnowledge"]["attentionRequired"]):
            receipt.update(ok=True, status="collected" if args.collect_only else "healthy-no-incident")
        elif area and not sources:
            receipt.update(ok=True, status="empty-area", modelInferenceRequests=0)
        elif unchanged:
            # Identical evidence, source and upstream cannot produce a new conclusion, so spending a
            # model call on it would only re-notify a finding the owner has already seen.
            receipt.update(ok=True, status="unchanged", attentionRequired=False,
                           previousRunId=previous.get("runId"), modelInferenceRequests=0)
        else:
            default_request = (INCIDENT_REQUEST if scoped else
                               "현재 OpenClaw의 운영 상태와 업데이트 영향을 검토하고, 재현 가능한 개선이 있으면 검증할 코드 후보를 작성하세요.")
            if area:
                default_request = area_request(model_evidence["areaScope"], args.request)
            request = {"schemaVersion": 1, "request_id": run_id, "mode": args.mode,
                       "request": default_request if area else args.request or default_request,
                       "snapshotSha256": manifest["snapshotSha256"], "sources": sources,
                       "evidence": model_evidence,
                       "upstream": changes.compact_upstream(upstream, baseline, delta["fullReview"]),
                       "changeContext": delta, "learningContext": learning}
            request["evidence"] = bounded_model_evidence(model_evidence, request["upstream"])
            module("hermes-ops-worker").validate_request(request)
            write_json(directory / "request.json", request)
            receipt.update(status="running", modelInferenceRequests=None, modelResponses=None, workerInvocations=1)
            write_json(receipt_path, receipt)
            if args.mode == "weekly":
                attempts[week] = {"runId": run_id, "status": "running", "receiptPath": str(receipt_path)}
                write_json(attempts_path, attempts)  # At-most-one automatic model dispatch per week.
            worker_path = directory / "worker-receipt.json"
            try:
                worker = worker_runner(root, directory / "request.json", worker_path, directory / "worker.log")
            except Exception:
                try:
                    failed_worker = read_json(worker_path)
                    retain_worker_evidence(receipt, failed_worker, worker_path)
                except (ValueError, OSError, TypeError, KeyError):
                    pass
                raise
            retain_worker_evidence(receipt, worker, worker_path)
            result = worker.get("result")
            if result is None and isinstance(worker.get("final_response"), str):
                result = json.loads(worker["final_response"])
            require(isinstance(result, dict), "STRUCTURED_RESULT_MISSING")
            candidate = result.get("patches", {"schemaVersion": 1, "snapshotSha256": manifest["snapshotSha256"], "replacements": []})
            write_json(directory / "analysis.json", result)
            write_json(directory / "candidate.json", candidate)
            patcher.validate_candidate(snapshot, candidate, expected_snapshot_sha256=manifest["snapshotSha256"])
            if candidate.get("replacements"):
                verification = patcher.evaluate(snapshot, candidate, directory / "candidate-evaluation",
                                                 expected_snapshot_sha256=manifest["snapshotSha256"])
                verification["status"] = "passed" if verification.get("ok") is True else "failed"
            else:
                verification = {"status": "no-changes", "ok": True, "checks": [], "activated": False}
            coverage = source_coverage(sources, receipt.get("read_metrics"))
            receipt["sourceCoverage"] = coverage
            # Only a full review promises every source; an incremental review reads its changes.
            receipt["sourceReviewComplete"] = coverage["complete"] if delta["fullReview"] else None
            receipt.update(ok=True, status="reviewed",
                           candidateVerification=verification, analysisPath=str(directory / "analysis.json"),
                           reportPath=str(directory / "report.md"), workerReceiptPath=str(directory / "worker-receipt.json"))
            observed_problem = (evidence.get("operations", {}).get("ok") is not True
                                or evidence["procedureKnowledge"]["attentionRequired"]
                                or bool(evidence.get("operations", {}).get("issues"))
                                or bool(evidence.get("operations", {}).get("warnings"))
                                or any(evidence.get(key, {}).get("available") is False
                                       for key in ("configuration", "openclaw", "hermes")))
            receipt["attentionRequired"] = (observed_problem or receipt["upstreamComplete"] is False
                                            or receipt["sourceReviewComplete"] is False
                                            or bool(candidate.get("replacements"))
                                            or any(f.get("severity") in {"warning", "critical", "high", "medium", "error"}
                                                   for f in result.get("findings", [])))
            receipt["findingIds"] = finding_ids(result.get("findings"))
            receipt["severityCounts"] = severity_counts(result.get("findings"))
            receipt["insufficientEvidenceFindingIds"] = finding_ids(
                [f for f in result.get("findings", []) if isinstance(f, dict)
                 and f.get("evidenceStatus") == "insufficient"])
            receipt["knowledgeObservation"] = module("hermes-ops-knowledge").observe_review(
                operations, run_id, result, receipt)
            report = directory / "report.md"
            report.write_text(report_text(receipt, result))
            report.chmod(0o600)
    except Exception as error:
        receipt.update(ok=False, status="failed", errorType=type(error).__name__)
        # Only fixed local validation codes are safe to emit, never provider bodies.
        if isinstance(error, ValueError) and re.fullmatch(r"[A-Z0-9_:.-]{1,160}", str(error)):
            receipt["errorCode"] = str(error)
    finally:
        receipt["finishedAt"] = now_iso()
        write_json(receipt_path, receipt)
        write_json(operations / "latest.json", receipt)
        try:
            append_bounded_line(operations / "review-ledger.jsonl", {
                "runId": run_id, "mode": args.mode, "area": area, "status": receipt["status"],
                "finishedAt": receipt["finishedAt"], "fingerprint": receipt.get("fingerprint"),
                "attentionRequired": bool(receipt.get("attentionRequired")),
                "sourceReviewComplete": receipt.get("sourceReviewComplete"),
                "candidateStatus": (receipt.get("candidateVerification") or {}).get("status"),
                "findingIds": receipt.get("findingIds", []),
                "findingKinds": {f["id"]: f.get("kind", "unclassified")
                                 for f in result.get("findings", []) if isinstance(f, dict)
                                 and f.get("id") in receipt.get("findingIds", [])}
                                if receipt.get("status") == "reviewed" else {},
                "severityCounts": receipt.get("severityCounts", {})})
            # Only a completed review may become the deduplication baseline; a failure must not
            # suppress the next attempt.
            if receipt.get("status") == "reviewed" and receipt.get("fingerprint") and area:
                area_reviews = read_json(operations / "area-reviews.json", {})
                area_reviews[area] = {"runId": run_id, "fingerprint": receipt["fingerprint"],
                                      "status": receipt["status"], "finishedAt": receipt["finishedAt"]}
                write_json(operations / "area-reviews.json", area_reviews)
            elif receipt.get("status") == "reviewed" and receipt.get("fingerprint"):
                write_json(operations / "last-review.json",
                           {"runId": run_id, "fingerprint": receipt["fingerprint"],
                            "status": receipt["status"], "finishedAt": receipt["finishedAt"]})
        except (OSError, ValueError, TypeError):
            receipt["ledgerWritten"] = False
        if args.mode == "weekly" and week in attempts and attempts[week].get("runId") == run_id:
            attempts[week]["status"] = receipt["status"]
            write_json(attempts_path, attempts)
    return receipt


def knowledge_command(args):
    operations = private_dir(safe(args.root, True) / "operations")
    knowledge = module("hermes-ops-knowledge")
    if args.command == "knowledge":
        versions = {}
        for name, path in (("openclaw", args.package / "package.json"),
                           ("hermes", args.root / "installation.json")):
            try:
                value = read_json(safe(path, True)).get("version")
                if isinstance(value, str):
                    versions[name] = value
            except (OSError, ValueError, TypeError):
                pass
        return {"ok": True, "knowledge": knowledge.summary(operations, versions)}
    require(args.payload_file is not None, "PAYLOAD_FILE_REQUIRED")
    payload_path = safe(args.payload_file, True)
    require(payload_path.stat().st_mode & 0o077 == 0, "PAYLOAD_NOT_PRIVATE")
    payload = knowledge.read_json(payload_path)
    require(isinstance(payload, dict), "PAYLOAD_OBJECT_REQUIRED")
    if args.command == "register-finding":
        result = knowledge.register_finding(operations, **payload)
    elif args.command == "update-finding":
        result = knowledge.update_finding(operations, **payload)
    elif args.command == "record-experience":
        result = knowledge.record_experience(operations, payload)
    elif args.command == "promote-procedure":
        result = knowledge.promote_procedure(operations, **payload)
    else:
        result = knowledge.record_reuse(operations, payload)
    return {"ok": True, "status": "recorded", "result": result}


def record_applied(args):
    """Record that a reviewed candidate was actually installed.

    Without this the next review cannot tell an accepted recommendation from an ignored one, and
    re-proposes work that is already done. Recording is deliberately separate from the review: only
    the operator or agent that performed the deployment knows it really landed.
    """
    root = safe(args.root, True)
    operations = private_dir(root / "operations")
    paths = [value for value in (args.paths or "").split(",") if value]
    require(len(paths) <= 32, "APPLIED_PATHS_TOO_MANY")
    for value in paths:
        require(re.fullmatch(r"[A-Za-z0-9_./-]{1,200}", value) and ".." not in value, "APPLIED_PATH_INVALID")
    require(args.run_id is None or SAFE_ID.fullmatch(args.run_id), "APPLIED_RUN_ID_INVALID")
    require(len((args.note or "").encode("utf-8")) <= 500, "APPLIED_NOTE_TOO_LONG")
    entry = {"appliedAt": now_iso(), "runId": args.run_id, "paths": sorted(set(paths)),
             "note": args.note or "", "verifiedBy": args.verified_by or "unspecified"}
    append_bounded_line(operations / "applied-changes.jsonl", entry)
    # A new deployment invalidates the deduplication baseline: the next review must look again.
    baseline = operations / "last-review.json"
    if baseline.exists():
        os.unlink(baseline)
    return {"ok": True, "status": "recorded", "entry": entry}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("run", "status", "record-applied", "knowledge", "register-finding", "update-finding",
                                            "record-experience", "promote-procedure", "record-reuse"))
    parser.add_argument("--payload-file", type=Path, help="private JSON payload for a knowledge action")
    parser.add_argument("--full-review", action="store_true", help="retain full release bodies and request a broad review")
    parser.add_argument("--run-id", help="record-applied: the review run whose candidate was installed")
    parser.add_argument("--paths", default="", help="record-applied: comma-separated repository paths")
    parser.add_argument("--note", default="", help="record-applied: short deployment note")
    parser.add_argument("--verified-by", default="", help="record-applied: who confirmed real behaviour")
    parser.add_argument("--mode", choices=("manual", "weekly", "incident"), default="manual")
    parser.add_argument("--area", choices=AREA_NAMES + ("all",),
                        help="manual source audit of one area, or all areas one worker at a time")
    parser.add_argument("--request", default="")
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--repo", type=Path, default=DEFAULT_REPO)
    parser.add_argument("--state-dir", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--package", type=Path, default=DEFAULT_PACKAGE)
    parser.add_argument("--collect-only", action="store_true")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--force", action="store_true", help="explicitly retry this week's model review")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    require(len(args.request.encode("utf-8")) <= 8192, "REQUEST_TOO_LONG")
    if args.command == "status":
        result = read_json(safe(args.root / "operations/latest.json"), {"status": "never-run", "ok": False})
    elif args.command == "record-applied":
        result = record_applied(args)
    elif args.command != "run":
        result = knowledge_command(args)
    else:
        result = run_review(args)
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return 0 if result.get("ok") is True else 1


if __name__ == "__main__":
    sys.exit(main())
