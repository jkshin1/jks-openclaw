#!/usr/bin/env python3
"""Run one private synthetic Workshop train/reuse pilot, with exact-owner cleanup.

Uses the existing Gateway and ChatGPT OAuth route. No Telegram delivery, package
patches, owner transcript reads, broad database writes, or gateway restarts.
"""

import argparse
from copy import deepcopy
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import time
import uuid


CLI = Path.home() / ".local/openclaw-2026.8.1/.personal-edge-management/bin/openclaw"
STATE = Path.home() / ".openclaw-personaledge"
PACKAGE = Path.home() / ".local/openclaw-2026.8.1/lib/node_modules/openclaw/package.json"
SKILL_CREATOR_GUIDE = PACKAGE.parent / "skills/skill-creator/SKILL.md"
SKILL = "operations-event-report"
MAX_RUN_SECONDS = 240
MAX_BYTES = 4 * 1024 * 1024


class PilotError(RuntimeError):
    """Only fixed, content-free diagnostic strings are exposed by this runner."""


def require(value, code):
    if not value:
        raise PilotError(code)


def digest(value):
    if not isinstance(value, bytes):
        value = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(value).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate-json-key")
        result[key] = value
    return result


def strict_json(text):
    return json.loads(text, object_pairs_hook=unique_object,
                      parse_constant=lambda _: (_ for _ in ()).throw(PilotError("nonfinite-json")))


def read_json(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= MAX_BYTES,
            "unsafe-json-file")
    return strict_json(path.read_text())


def private_write(path, value, exclusive=False):
    payload = value if isinstance(value, bytes) else (
        json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode()
    require(not path.is_symlink(), "symlink-output-file")
    target = path if exclusive else path.with_name(path.name + ".tmp-" + uuid.uuid4().hex)
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        if not exclusive:
            os.replace(target, path)
        directory_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if not exclusive and target.exists():
            target.unlink()


def safe_root(path):
    path = path.absolute()
    require(not any(p.is_symlink() for p in (path, *path.parents)), "symlink-run-directory")
    if not path.exists():
        path.mkdir(mode=0o700)
    require(path.is_dir() and path.stat().st_uid == os.getuid()
            and path.stat().st_mode & 0o077 == 0, "run-directory-must-be-private")
    require(set(p.name for p in path.iterdir()) <= {"train", "repeat"}, "run-directory-not-fresh")
    return path


def fixture_module():
    spec = importlib.util.spec_from_file_location(
        "agent_pilot_fixtures", Path(__file__).with_name("agent-pilot-fixtures.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def rpc_error_category(stdout, stderr):
    combined = (stdout + "\n" + stderr).lower()
    for category, needles in (
        ("missing-module", ("err_module_not_found", "cannot find module", "cannot find package", "module_not_found")),
        ("module-export-mismatch", ("does not provide an export", "export named")),
        ("invalid-params", ("invalid params", "invalid agent.wait params", "invalid sessions.delete params")),
        ("permission-denied", ("permission denied", "operation not permitted", "eacces", "eperm")),
        ("connection-unavailable", ("econnrefused", "gateway closed", "socket closed", "connection closed")),
        ("timeout", ("timed out", "timeout")),
        ("authentication", ("unauthorized", "authentication failed", "auth failed")),
    ):
        if any(needle in combined for needle in needles):
            return category
    return "unclassified"


def redacted_excerpt(text):
    text = re.sub(r"(?i)(bearer\s+)[A-Za-z0-9._~+/-]+", r"\1[REDACTED]", text)
    text = re.sub(r"\b(?:sk-[A-Za-z0-9_-]+|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)\b",
                  "[REDACTED]", text)
    text = re.sub(r'''(?i)(["']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|bot[_-]?token|secret|password|token)["']?\s*[:=]\s*)["']?[^\s,"'}]+''',
                  r"\1[REDACTED]", text)
    return text.encode()[:8192].decode(errors="replace")


class Gateway:
    def __init__(self, cli=CLI, diagnostic_dir=None):
        self.cli = cli
        self.diagnostic_dir = diagnostic_dir

    def diagnostic(self, method, params, returncode, stdout="", stderr="", category=None):
        category = category or rpc_error_category(stdout, stderr)
        if self.diagnostic_dir:
            self.diagnostic_dir.mkdir(mode=0o700, exist_ok=True)
            record = {"method": method, "category": category, "returncode": returncode,
                      "paramsSha256": digest(params), "stdoutSha256": digest(stdout.encode()),
                      "stderrSha256": digest(stderr.encode()), "stdoutBytes": len(stdout.encode()),
                      "stderrBytes": len(stderr.encode()), "observedAt": datetime.now(timezone.utc).isoformat()}
            # Session listing/config/auth results can contain owner state. Store
            # excerpts only for this runner's exact synthetic call surfaces.
            if method in {"agent", "agent.wait", "chat.abort", "sessions.create", "sessions.delete",
                          "agents.create", "agents.delete", "tools.effective"}:
                record.update(stdoutExcerpt=redacted_excerpt(stdout), stderrExcerpt=redacted_excerpt(stderr),
                              excerptLimitBytes=8192, excerptsRedacted=True)
            private_write(self.diagnostic_dir / (uuid.uuid4().hex + ".json"), record, True)
        return category

    def rpc(self, method, params, timeout=45):
        try:
            result = subprocess.run(
                [str(self.cli), "gateway", "call", method, "--json", "--timeout",
                 str((timeout - 3) * 1000), "--params", json.dumps(params)],
                capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            self.diagnostic(method, params, None, category="process-timeout")
            raise PilotError("gateway-call-failed:" + method + ":process-timeout") from None
        if result.returncode:
            category = self.diagnostic(method, params, result.returncode, result.stdout, result.stderr)
            raise PilotError("gateway-call-failed:" + method + ":" + category)
        # Never expose stderr or potentially secret-bearing command output.
        try:
            return strict_json(result.stdout)
        except (ValueError, PilotError):
            self.diagnostic(method, params, result.returncode, result.stdout, result.stderr,
                            category="invalid-json")
            raise PilotError("gateway-invalid-json:" + method) from None

    def patch(self, patch):
        # CLI patch uses the official validated mutation/hot-reload path. Unlike
        # gateway config.patch, it does not explicitly schedule a restart.
        result = subprocess.run([str(self.cli), "config", "patch", "--stdin"],
                                input=json.dumps(patch), capture_output=True,
                                text=True, timeout=45)
        if result.returncode:
            category = self.diagnostic("config.patch", patch, result.returncode, result.stdout, result.stderr)
            raise PilotError("config-patch-failed:" + category)


class State:
    def __init__(self, root=STATE):
        self.root = root

    def config(self):
        return read_json(self.root / "openclaw.json")

    def runtime_snapshot(self):
        package = read_json(PACKAGE)
        return {"version": package.get("version"), "packageSha256": digest(PACKAGE.read_bytes())}

    def query(self, path, sql, args=()):
        require(path.is_file(), "state-database-missing")
        with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)) as database:
            return database.execute(sql, args).fetchall()

    def agent_db(self, agent_id):
        return self.root / "agents" / agent_id / "agent/openclaw-agent.sqlite"

    def main_snapshot(self):
        rows = self.query(self.agent_db("main"),
                          "SELECT session_key,current_session_id FROM session_nodes")
        pairs = {digest(key): digest(session) for key, session in rows}
        count = self.query(self.agent_db("main"), "SELECT COUNT(*) FROM transcript_events")[0][0]
        return {"sessionIdentities": pairs, "sessionCount": len(rows), "transcriptEventCount": count}

    def session(self, agent_id, key):
        path = self.agent_db(agent_id)
        if not path.exists():
            return None
        rows = self.query(path, "SELECT current_session_id,status,entry_json FROM session_nodes WHERE session_key=?",
                          (key,))
        if not rows:
            return None
        entry = strict_json(rows[0][2])
        return {"sessionId": rows[0][0], "status": rows[0][1],
                "runId": entry.get("activeWriterRunId") or entry.get("lifecycleRunId"),
                "authProfileOverride": entry.get("authProfileOverride"),
                "authProfileOverrideSource": entry.get("authProfileOverrideSource")}

    def events(self, agent_id, session_id):
        return [strict_json(row[0]) for row in self.query(
            self.agent_db(agent_id),
            "SELECT event_json FROM transcript_events WHERE session_id=? ORDER BY seq", (session_id,))]

    def proposals(self, agent_id):
        return [strict_json(row[0]) for row in self.query(
            self.root / "state/openclaw.sqlite",
            "SELECT record_json FROM skill_workshop_proposals WHERE owner_agent_id=?", (agent_id,))]

    def workshop_name_claimed(self, agent_id, skill_key):
        return bool(self.query(self.root / "state/openclaw.sqlite",
            "SELECT 1 FROM skill_workshop_proposals WHERE owner_agent_id=? "
            "AND json_extract(record_json,'$.target.skillKey')=? LIMIT 1", (agent_id, skill_key)))

    def session_proposals(self, agent_id, skill_key, session_key):
        return [strict_json(row[0]) for row in self.query(self.root / "state/openclaw.sqlite",
            "SELECT record_json FROM skill_workshop_proposals WHERE owner_agent_id=? "
            "AND json_extract(record_json,'$.target.skillKey')=? "
            "AND json_extract(record_json,'$.origin.sessionKey')=?", (agent_id, skill_key, session_key))]

    def scheduled(self, agent_id):
        return self.query(self.root / "state/openclaw.sqlite",
                          "SELECT job_id FROM cron_jobs WHERE agent_id=? OR owner_agent_id=?",
                          (agent_id, agent_id))


def idle_sessions(result):
    require(not result.get("hasActiveRun") and not result.get("activeRunIds"),
            "gateway-busy-pending")
    rows = result.get("sessions")
    require(isinstance(rows, list), "idle-sample-schema-unverified")
    require(not result.get("hasMore") and not result.get("nextCursor"), "idle-sample-truncated")
    total = result.get("total", result.get("count", len(rows)))
    require(not isinstance(total, int) or total <= len(rows), "idle-sample-truncated")
    for row in rows:
        require(isinstance(row, dict), "idle-session-schema-unverified")
        status = row.get("status")
        require(status in (None, "done", "failed", "killed", "timeout", "idle", "running"),
                "idle-session-status-unverified")
        require(status != "running" and not row.get("activeRunId")
                and not row.get("isRunning") and not row.get("runActive")
                and not row.get("hasActiveRun") and not row.get("activeRunIds"), "gateway-busy-pending")
    return {"sessionCount": len(rows), "active": 0}


def tool_names(result):
    groups = result.get("groups")
    require(isinstance(groups, list), "tool-inventory-schema-unverified")
    names = {tool.get("id", tool.get("name")) for group in groups
             for tool in group.get("tools", [])}
    require(names == {"skill_workshop"}, "pilot-tool-inventory-not-exact")
    return sorted(names)


def report_json(text):
    text = text.strip()
    match = re.fullmatch(r"```(?:json)?\s*\n([\s\S]*?)\n```", text)
    if match:
        text = match.group(1)
    require(text.startswith("{"), "report-not-json")
    value = strict_json(text)
    require(isinstance(value, dict), "report-not-object")
    return value


def native_steps(events):
    calls, results = {}, {}
    for event in events:
        message = event.get("message", {})
        if message.get("role") == "assistant":
            for block in message.get("content", []):
                if isinstance(block, dict) and block.get("type") in ("toolCall", "tool_use"):
                    args = block.get("arguments", block.get("input", {}))
                    if isinstance(args, str):
                        args = strict_json(args)
                    calls[block.get("id")] = {"tool": block.get("name"),
                                               "action": args.get("action"),
                                               "skill_name": args.get("skill_name"),
                                               "name": args.get("name")}
        if message.get("role") in ("toolResult", "tool"):
            details = message.get("details") or {}
            require(isinstance(details, dict), "native-tool-details-schema-unverified")
            texts = [block["text"] for block in message.get("content", [])
                     if isinstance(block, dict) and block.get("type") == "text"
                     and isinstance(block.get("text"), str)]
            results[message.get("toolCallId", message.get("tool_call_id"))] = {
                "successful": message.get("isError") is not True,
                "readDetails": {key: details.get(key)
                                for key in ("skillKey", "contentIncluded", "sizeBytes")},
                "returnedTextSha256": [digest(text.encode()) for text in texts]}
    return [{**call, **results.get(call_id, {"successful": False})}
            for call_id, call in calls.items()]


def verify_terminal(result, model, allowed_tools=frozenset({"skill_workshop"})):
    require(result.get("status") == "ok" and result.get("endedAt"), "run-not-successful")
    native = result.get("terminalReceipt", {})
    require(native.get("rerouted") is False, "model-reroute-unverified")
    for field in ("requested", "effective"):
        route = native.get(field, {})
        require(route.get("provider") == "openai" and route.get("model") == model,
                "model-route-mismatch")
    require(set(native.get("successfulToolNames", [])) <= allowed_tools, "unexpected-tool-use")
    reply = result.get("terminalReply", {})
    require(reply.get("disposition") == "visible" and isinstance(reply.get("text"), str),
            "terminal-report-unavailable")
    return report_json(reply["text"])


def verify_oauth_status(status, profile_id):
    providers = status.get("providers", [])
    matches = [profile for provider in providers if provider.get("provider") == "openai"
               for profile in provider.get("profiles", []) if profile.get("profileId") == profile_id]
    require(len(matches) == 1 and matches[0].get("type") == "oauth"
            and matches[0].get("status") == "ok", "selected-oauth-profile-not-runtime-ready")
    return {"profileIdSha256": digest(profile_id), "provider": "openai", "type": "oauth",
            "status": "ok", "source": "models.authStatus-refresh-false"}


def select_oauth_profile(config, status):
    auth = config.get("auth", {})
    order = auth.get("order", {}).get("openai")
    require(isinstance(order, list) and len(order) > 0, "openai-auth-order-missing")
    selected = order[0]
    require(isinstance(selected, str) and 0 < len(selected) <= 256 and "@" not in selected
            and all(32 < ord(char) < 127 for char in selected), "openai-auth-order-invalid")
    metadata = auth.get("profiles", {}).get(selected, {})
    require(metadata.get("provider") == "openai" and metadata.get("mode") == "oauth",
            "selected-profile-is-not-configured-openai-oauth")
    return selected, verify_oauth_status(status, selected)


def verify_session_auth_pin(row, selected):
    require(row.get("authProfileOverride") == selected, "session-oauth-profile-pin-mismatch")
    require(row.get("authProfileOverrideSource") in (None, "user", "user-link"),
            "session-oauth-profile-pin-source-unverified")
    return {"profileIdSha256": digest(row["authProfileOverride"]), "explicitSessionPin": True,
            "source": row.get("authProfileOverrideSource") or "explicit-model-profile-suffix"}


def protected_config(config):
    # All user-authored config is protected; generated update timestamps are not
    # evidence of a behavioral change. Restoration below only touches pilot pins.
    result = deepcopy(config)
    result.pop("meta", None)
    return result


def native_usage(events):
    samples = [event["message"]["usage"] for event in events
               if event.get("message", {}).get("role") == "assistant"
               and isinstance(event["message"].get("usage"), dict)]
    aliases = {"input_tokens": ("input", "inputTokens", "input_tokens"),
               "output_tokens": ("output", "outputTokens", "output_tokens"),
               "cache_read_tokens": ("cacheRead", "cacheReadTokens", "cache_read_tokens"),
               "cache_write_tokens": ("cacheWrite", "cacheWriteTokens", "cache_write_tokens"),
               "total_tokens": ("totalTokens", "total_tokens")}
    totals, counts = {}, {}
    for field, keys in aliases.items():
        values = []
        for sample in samples:
            for key in keys:
                value = sample.get(key)
                if type(value) is int and value >= 0:
                    values.append(value)
                    break
        counts[field] = len(values)
        # A missing counter is unknown, never zero or an incomplete total.
        totals[field] = sum(values) if samples and len(values) == len(samples) else None
    return {"source": "native-synthetic-assistant-events", "available": bool(samples),
            "sample_count": len(samples), "field_sample_counts": counts, **totals}, samples


def main_preserved(before, after):
    require(all(after["sessionIdentities"].get(key) == value
                for key, value in before["sessionIdentities"].items()), "main-session-identity-changed")
    require(after["transcriptEventCount"] >= before["transcriptEventCount"],
            "main-transcript-count-decreased")
    return {"existingSessionIdentitiesPreserved": True,
            "sessionCountDelta": after["sessionCount"] - before["sessionCount"],
            "transcriptEventCountDelta": after["transcriptEventCount"] - before["transcriptEventCount"]}


def config_value(config, parts):
    value = config
    for part in parts:
        if not isinstance(value, dict) or part not in value:
            return None
        value = value[part]
    return value


def set_patch_value(patch, parts, value):
    for part in parts[:-1]:
        patch = patch.setdefault(part, {})
    patch[parts[-1]] = deepcopy(value)


def changed_config_paths(before, after, prefix=""):
    changes = []
    for key in sorted(set(before) | set(after)):
        if not prefix and key == "meta":
            continue
        path = prefix + "." + key if prefix else key
        left, right = before.get(key), after.get(key)
        if left != right:
            if isinstance(left, dict) and isinstance(right, dict):
                changes.extend(changed_config_paths(left, right, path))
            else:
                changes.append(path)
    return changes


class Pilot:
    uses_existing_main = False

    def __init__(self, run_dir, model="gpt-5.6-sol", gateway=None, state=None, sleep=time.sleep):
        require(model == "gpt-5.6-sol", "pilot-model-not-qualified")
        self.root = safe_root(run_dir)
        self.gateway, self.state = gateway or Gateway(), state or State()
        if isinstance(self.gateway, Gateway):
            self.gateway.diagnostic_dir = self.root / "diagnostics"
        self.sleep, self.model = sleep, model
        self.agent_id = "workshop-pilot-" + uuid.uuid4().hex[:12]
        self.workspace = self.root / "workspace"
        self.agent_dir = self.state.agent_db(self.agent_id).parent
        self.fixtures = fixture_module()
        self.sessions = []
        self.agent_armed = False
        self.pins_armed = False
        self.expected_config = None
        self.receipt = {"schema_version": 1, "syntheticOnly": True, "agentId": self.agent_id,
                        "model": "openai/" + model, "thinking": "high", "telegramDelivered": False,
                        "artifactsRetained": True, "reworkCount": 0, "phases": {}}

    @classmethod
    def recover(cls, run_dir, gateway=None, state=None, sleep=time.sleep):
        root = run_dir.absolute()
        require(root.is_dir() and not any(p.is_symlink() for p in (root, *root.parents))
                and root.stat().st_uid == os.getuid() and root.stat().st_mode & 0o077 == 0,
                "recovery-directory-must-be-private")
        plan = read_json(root / "cleanup-plan.json")
        main_mode = cls.uses_existing_main
        require((plan.get("agentId") == "main" and plan.get("mode") == "existing-main") if main_mode
                else bool(re.fullmatch(r"workshop-pilot-[a-f0-9]{12}", plan.get("agentId", ""))),
                "recovery-agent-id-invalid")
        require(plan.get("workspace") == str(root / "workspace") and plan.get("deleteFiles") is False,
                "recovery-workspace-ownership-mismatch")
        self = cls.__new__(cls)
        self.root, self.workspace, self.agent_id = root, root / "workspace", plan["agentId"]
        self.gateway, self.state = gateway or Gateway(), state or State()
        self.agent_dir = self.state.agent_db(self.agent_id).parent
        if isinstance(self.gateway, Gateway):
            self.gateway.diagnostic_dir = root / "diagnostics"
        self.sleep, self.model = sleep, "gpt-5.6-sol"
        self.before = read_json(root / "config-before.json")
        self.expected_config = self.before if main_mode else read_json(root / "config-pilot-latest.json")
        require(main_mode or self.agent_id not in self.before.get("agents", {}).get("entries", {}),
                "recovery-agent-was-preexisting")
        expected_entry = self.expected_config.get("agents", {}).get("entries", {}).get(self.agent_id)
        require(main_mode or not expected_entry or expected_entry.get("workspace") == str(self.workspace),
                "recovery-expected-agent-ownership-mismatch")
        self.owner_before = read_json(root / "main-before.json")
        self.receipt = read_json(root / "receipt.json")
        require(self.receipt.get("agentId") == self.agent_id, "recovery-receipt-agent-mismatch")
        if main_mode:
            require(self.receipt.get("mode") == "existing-main"
                    and self.receipt.get("mainConfigurationWrites") is False, "recovery-main-mode-unverified")
            self.skill_file = self.agent_dir / "workshop-skills" / SKILL / "SKILL.md"
        self.sessions = plan.get("sessions", [])
        require(isinstance(self.sessions, list) and len(self.sessions) <= 2, "recovery-session-count-invalid")
        seen = set()
        for owned in self.sessions:
            key = owned.get("key", "")
            require(key.startswith(f"agent:{self.agent_id}:explicit:learning-") and key not in seen,
                    "recovery-session-key-invalid")
            if main_mode:
                self.validate_owned_session(owned)
            seen.add(key)
            row = self.state.session(self.agent_id, key)
            if row:
                require(not owned.get("sessionId") or owned["sessionId"] == row["sessionId"],
                        "recovery-session-identity-mismatch")
                owned.setdefault("sessionId", row["sessionId"])
                if row.get("runId"):
                    # Gateway recovery may replace the original request label
                    # with a native UUID writer ID. Authority comes from the
                    # already-verified exact session key and session identity,
                    # not the spelling of this informational run identifier.
                    require(isinstance(row["runId"], str) and 0 < len(row["runId"]) <= 256
                            and all(32 <= ord(char) < 127 for char in row["runId"]),
                            "recovery-run-id-invalid")
                    owned.setdefault("runId", row["runId"])
                    owned["recoveredNativeRunId"] = row["runId"]
                owned["stage"] = "recovered-from-exact-session-row"
        self.agent_armed, self.pins_armed = not main_mode, not main_mode
        self.save_cleanup_plan()
        return self

    def save(self):
        private_write(self.root / "receipt.json", self.receipt)

    def save_cleanup_plan(self):
        private_write(self.root / "cleanup-plan.json", {"agentId": self.agent_id,
            "workspace": str(self.workspace), "sessions": self.sessions, "deleteFiles": False,
            "mode": "existing-main" if self.uses_existing_main else "isolated-agent",
            "updatedAt": datetime.now(timezone.utc).isoformat()})

    def runtime_check(self, stage):
        current = self.state.runtime_snapshot()
        self.receipt.setdefault("runtimeSnapshots", {})[stage] = current
        self.save()
        require(current == self.receipt["runtimeSnapshots"]["before"], "runtime-changed-during-pilot")

    def idle(self, allow_owned=False):
        samples = []
        for index in range(3):
            result = self.gateway.rpc("sessions.list", {"limit": 10000, "includeGlobal": True,
                "includeUnknown": True, "configuredAgentsOnly": False, "includeDerivedTitles": False,
                "includeLastMessage": False})
            if allow_owned:
                result = deepcopy(result)
                owned_keys = {row["key"] for row in self.sessions}
                own_ids = {row.get("runId") for row in self.sessions}
                require(not result.get("activeRunIds") or set(result["activeRunIds"]) <= own_ids,
                        "gateway-busy-pending")
                rows = result.get("sessions")
                require(isinstance(rows, list), "idle-sample-schema-unverified")
                removed = [row for row in rows if row.get("key", row.get("sessionKey")) in owned_keys]
                result["sessions"] = [row for row in rows if row not in removed]
                for count_key in ("count", "total"):
                    if isinstance(result.get(count_key), int):
                        result[count_key] -= len(removed)
                if removed:
                    result.pop("hasActiveRun", None)
                    result.pop("activeRunIds", None)
            samples.append(idle_sessions(result))
            if index < 2:
                self.sleep(1)
        return samples

    def preflight(self):
        self.before = self.state.config()
        self.receipt["runtimeSnapshots"] = {"before": self.state.runtime_snapshot()}
        agents = self.before.get("agents", {})
        require(not self.agent_dir.exists(), "pilot-agent-directory-already-exists")
        require(set(agents.get("entries", {})) == {"main"}, "pilot-requires-current-sole-main")
        require(not agents.get("list"), "legacy-roster-not-supported")
        main = agents["entries"]["main"]
        main_workspace = main.get("workspace", agents.get("defaults", {}).get("workspace"))
        require(main_workspace and Path(main_workspace).is_absolute(), "main-workspace-not-explicit")
        require(agents.get("defaults", {}).get("systemAgent", {}).get("agentId", "main") == "main",
                "ambient-owner-not-main")
        require(self.before.get("channels", {}).get("telegram", {}).get("enabled") is not False,
                "telegram-configuration-unexpected")
        # A single explicit main route for Telegram preserves the previously sole
        # fallback across the temporary multi-agent roster, including group chats.
        bindings = deepcopy(self.before.get("bindings", []))
        require(all(binding.get("agentId") == "main" for binding in bindings), "foreign-routing-config")
        if not any(binding.get("match") == {"channel": "telegram", "accountId": "*"}
                   for binding in bindings):
            bindings.append({"type": "route", "agentId": "main",
                             "match": {"channel": "telegram", "accountId": "*"}})
        self.pins = {"agents": {"entries": {"main": {"workspace": main_workspace}},
                   "defaults": {"systemAgent": {"agentId": "main"}}}, "bindings": bindings}
        private_write(self.root / "config-before.json", (self.state.root / "openclaw.json").read_bytes(), True)
        self.owner_before = self.state.main_snapshot()
        private_write(self.root / "main-before.json", self.owner_before, True)
        auth_status = self.gateway.rpc("models.authStatus", {"agentId": "main", "refresh": False})
        self.auth_profile, self.receipt["authSelection"] = select_oauth_profile(self.before, auth_status)
        self.receipt["idleBeforeConfig"] = self.idle()
        self.receipt["configBeforeSha256"] = digest(protected_config(self.before))
        self.prepare_fixtures()
        self.save()

    def prepare_fixtures(self):
        for variant in ("train", "repeat"):
            folder = self.root / variant
            if not folder.exists():
                self.fixtures.prepare(folder, variant)
            manifest = self.fixtures.read_json(folder / "fixture-manifest.json")
            require(manifest == self.fixtures.manifest_for(variant), "fixture-manifest-mismatch")
            for name, content in self.fixtures.fixture_files(variant).items():
                require(self.fixtures.read_file(folder / name) == content, "fixture-input-mismatch")

    def observe_tools(self, result):
        return tool_names(result)

    def verify_phase_terminal(self, result, steps, events):
        require(all(step.get("tool") == "skill_workshop" for step in steps), "unexpected-native-tool-call")
        return verify_terminal(result, self.model)

    def remember_config(self):
        self.expected_config = self.state.config()
        private_write(self.root / "config-pilot-latest.json", self.expected_config)

    def setup(self):
        self.pins_armed = True
        self.save()
        self.gateway.patch(self.pins)
        self.remember_config()
        self.workspace.mkdir(mode=0o700)
        # Arm ownership before the request: a timeout can hide a successful create.
        self.agent_armed = True
        self.save_cleanup_plan()
        self.gateway.rpc("agents.create", {"name": self.agent_id, "workspace": str(self.workspace),
                                          "model": "openai/" + self.model})
        self.remember_config()
        entry = deepcopy(self.expected_config.get("agents", {}).get("entries", {}).get(self.agent_id))
        require(isinstance(entry, dict) and entry.get("workspace") == str(self.workspace), "pilot-agent-ownership-mismatch")
        entry.update(contextInjection="never", thinkingDefault="high",
                     model={"primary": "openai/" + self.model, "fallbacks": []},
                     tools={"profile": "coding", "allow": ["skill_workshop"]})
        self.gateway.patch({"agents": {"entries": {self.agent_id: entry}}})
        self.remember_config()
        require(self.expected_config["agents"]["entries"]["main"].get("workspace")
                == self.pins["agents"]["entries"]["main"]["workspace"], "main-workspace-drift")
        self.receipt["setup"] = {"isolatedWorkspace": True, "bootstrapInjection": "never"}
        self.save()

    def prompt(self, variant):
        events = (self.root / variant / "events.json").read_text()
        tool_boundary = ("Your existing main-agent tool policy and bootstrap context remain active. "
                         "Use skill_workshop for this synthetic task. The only optional auxiliary calls are "
                         "read of " + str(SKILL_CREATOR_GUIDE) + " and progress_card with your own plan steps/status. "
                         "During repeat you may also read the exact learned skill file "
                         + str(getattr(self, "skill_file", "")) + ". "
                         "Do not call any other tools or read other files. "
                         if self.uses_existing_main else "You have no filesystem tool. ")
        if variant == "train":
            contract = (self.root / variant / "contract.md").read_text()
            return ("This is a synthetic, private operations-report learning acceptance. All data is inline. "
                    "Never contact Telegram, use owner data, or invent a receipt. Treat report.json as the "
                    "final JSON reply. " + tool_boundary + "First solve the supplied events using "
                    "the contract. Then author a reusable class-level skill named " + SKILL +
                    " using skill_workshop action=create. Include the supplied GENERAL CONTRACT verbatim "
                    "in the skill: exact input/output keys, types, enum values, action-string mappings, "
                    "and selection/counting rules. Future runs receive ONLY new events WITHOUT this "
                    "contract. Schema constants are reusable procedure, not fixture answers. Store no "
                    "sample event IDs, timestamps, counts, records, or reports; do not replace the schema "
                    "with references to a supplied contract. Explicitly apply that "
                    "proposal using action=apply (authorized). The next fresh session must solve a different "
                    "batch solely from this skill. Read the saved skill back to verify self-sufficiency. "
                    "Your final reply must contain only the report JSON, "
                    "without a wrapper, prose, or the proposal. Keep the tool sequence bounded.\n\n" +
                    contract + "\n\nSynthetic events.json:\n" + events)
        return (tool_boundary + "This is the fresh repeat of the synthetic operations-report task. Discover the saved "
                "applied operations-report skill with skill_workshop action=list and read it using "
                "action=read. Follow its stored output contract on the NEW inline events below. "
                "Do not create or update a skill. All data is synthetic; do not contact Telegram or "
                "use owner data. Return only the exact report JSON with no prose or wrapper.\n\n" + events)

    def run_phase(self, variant):
        self.current_phase = variant
        empty_usage, _ = native_usage([])
        self.receipt["phases"][variant] = {"ok": False, "status": "setup", "usage": empty_usage,
                                          "steps": [], "nativeUsageSamples": []}
        self.save()
        self.runtime_check(variant)
        key = f"agent:{self.agent_id}:explicit:learning-{variant}-{uuid.uuid4().hex}"
        owned = {"key": key, "agentId": self.agent_id, "terminal": False}
        self.sessions.append(owned)
        self.save_cleanup_plan()
        self.gateway.rpc("sessions.create", {"agentId": self.agent_id, "key": key,
            "model": "openai/" + self.model + "@" + self.auth_profile,
            "thinkingLevel": "high", "permissionMode": "full"})
        row = self.state.session(self.agent_id, key)
        require(row is not None, "pilot-session-not-persisted")
        owned["sessionId"] = row["sessionId"]
        owned["stage"] = "session-created"
        self.save_cleanup_plan()
        pin = verify_session_auth_pin(row, self.auth_profile)
        self.receipt.setdefault("authBeforeInference", {})[variant] = {"pin": pin}
        self.save()
        auth_status = self.gateway.rpc("models.authStatus", {"agentId": self.agent_id, "refresh": False})
        ready = verify_oauth_status(auth_status, self.auth_profile)
        self.receipt.setdefault("authBeforeInference", {})[variant] = {"pin": pin, "readiness": ready}
        self.save()
        tools = self.observe_tools(self.gateway.rpc("tools.effective", {"agentId": self.agent_id, "sessionKey": key}))
        self.runtime_check(variant + "-before-inference")
        started_at = time.monotonic()
        run_id = "learning-" + uuid.uuid4().hex
        owned.update(runId=run_id, stage="agent-request-armed")
        self.save_cleanup_plan()
        started = self.gateway.rpc("agent", {"agentId": self.agent_id, "sessionKey": key,
            "message": self.prompt(variant), "deliver": False, "disableMessageTool": True,
            "thinking": "high", "promptMode": "full", "timeout": MAX_RUN_SECONDS,
            "idempotencyKey": run_id})
        run_id = started.get("runId", run_id)
        owned["runId"] = run_id
        owned["stage"] = "agent-started"
        self.save_cleanup_plan()
        private_write(self.root / variant / "native-started.json", started, True)
        self.receipt["phases"][variant].update(status="started", runId=run_id)
        self.save()
        deadline = started_at + MAX_RUN_SECONDS + 15
        while time.monotonic() < deadline:
            result = self.gateway.rpc("agent.wait", {"runId": run_id, "timeoutMs": 30000})
            if result.get("endedAt") or result.get("status") != "timeout":
                owned["terminal"] = True
                owned["stage"] = "terminal"
                self.save_cleanup_plan()
                break
        else:
            raise PilotError("pilot-run-deadline-exceeded")
        private_write(self.root / variant / "native-terminal.json", result, True)
        events = self.state.events(self.agent_id, owned["sessionId"])
        private_write(self.root / variant / "native-events.json", events, True)
        steps = native_steps(events)
        usage, usage_samples = native_usage(events)
        self.receipt["phases"][variant] = {"ok": False, "status": "terminal-observed",
            "nativeStatus": result.get("status"), "elapsedSeconds": round(time.monotonic() - started_at, 3),
            "tools": tools, "steps": steps, "terminalReceipt": result.get("terminalReceipt"),
            "runId": run_id, "usage": usage, "nativeUsageSamples": usage_samples}
        self.save()
        report = self.verify_phase_terminal(result, steps, events)
        private_write(self.root / variant / "report.json", report, True)
        verified = self.fixtures.verify(self.root / variant, self.root / variant / "report.json")
        successful = [step for step in steps if step["successful"] and step["tool"] == "skill_workshop"]
        required = {"create", "apply"} if variant == "train" else {"read"}
        require(required <= {step["action"] for step in successful}, "native-learning-action-evidence-missing")
        if variant == "repeat":
            expected_hash = self.receipt["learning"]["skillSha256"]
            require(any(step["action"] == "read" and (
                        (step.get("readDetails", {}).get("skillKey") == SKILL
                         and step.get("readDetails", {}).get("contentIncluded") is True)
                        or expected_hash in step.get("returnedTextSha256", []))
                        for step in successful), "repeat-did-not-read-learned-skill")
            require(not any(step["action"] in {"create", "update", "patch", "revise", "apply"}
                            for step in successful), "repeat-unexpected-learning-mutation")
        self.receipt["phases"][variant].update(ok=True, status="verified", verification=verified)
        self.save()

    def capture_learning(self):
        proposals = self.state.proposals(self.agent_id)
        selected = [item for item in proposals if item.get("status") == "applied"
                    and item.get("target", {}).get("skillKey") == SKILL]
        require(len(selected) == 1, "applied-skill-proposal-unverified")
        proposal = selected[0]
        skill_file = Path(proposal.get("target", {}).get("skillFile", ""))
        allowed = {self.agent_dir / "workshop-skills" / SKILL / "SKILL.md",
                   self.workspace / "skills" / SKILL / "SKILL.md"}
        require(skill_file in allowed and skill_file.is_file()
                and not any(p.is_symlink() for p in (skill_file, *skill_file.parents)),
                "learned-skill-target-unverified")
        private_write(self.root / "native-proposals.json", proposals, True)
        self.receipt["learning"] = {"proposalId": proposal.get("id"), "status": "applied",
            "skillFile": str(skill_file), "skillSha256": digest(skill_file.read_bytes()),
            "scan": proposal.get("scan"), "ownerAgentId": self.agent_id}
        self.save()

    def restore_pins(self):
        current = self.state.config()
        require(self.agent_id not in current.get("agents", {}).get("entries", {}),
                "cleanup-owned-agent-remains")
        expected = self.expected_config or current
        patch, restored = {}, []
        owned_paths = [("agents", "entries", "main", "workspace"),
                       ("agents", "defaults", "systemAgent"),
                       ("agents", "defaults", "authInheritance"), ("agents", "ownership")]
        # Only fields this pilot actually changed may be restored. A separately
        # authorized upgrade can change unrelated settings while the pilot runs.
        for parts in owned_paths:
            original = config_value(self.before, parts)
            written = config_value(expected, parts)
            actual = config_value(current, parts)
            if written == original or actual == original:
                continue
            require(actual == written, "cleanup-owned-pin-concurrent-change:" + ".".join(parts))
            set_patch_value(patch, parts, original)
            restored.append(".".join(parts))
        before_bindings = self.before.get("bindings", [])
        expected_bindings = expected.get("bindings", [])
        actual_bindings = deepcopy(current.get("bindings", []))
        own_binding = {"type": "route", "agentId": "main", "match": {"channel": "telegram", "accountId": "*"}}
        inserted = expected_bindings.count(own_binding) - before_bindings.count(own_binding)
        if inserted > 0 and actual_bindings.count(own_binding) > before_bindings.count(own_binding):
            for _ in range(min(inserted, actual_bindings.count(own_binding) - before_bindings.count(own_binding))):
                actual_bindings.remove(own_binding)
            patch["bindings"] = actual_bindings or ([] if "bindings" in self.before else None)
            restored.append("bindings:pilot-main-telegram-route")
        if patch:
            self.gateway.patch(patch)
        after = self.state.config()
        for parts in owned_paths:
            if ".".join(parts) in restored:
                require(config_value(after, parts) == config_value(self.before, parts),
                        "config-owned-pin-restore-verification-failed")
        if "bindings" in patch:
            require(after.get("bindings", []) == actual_bindings, "config-routing-restore-verification-failed")
        differences = changed_config_paths(self.before, after)
        self.receipt["configRestoration"] = {"restoredOwnedPaths": restored,
            "unrelatedCurrentDifferencesPreserved": differences,
            "originalConfigExactlyRestored": protected_config(after) == protected_config(self.before)}

    def cleanup(self):
        failures = []
        for owned in self.sessions:
            try:
                row = self.state.session(self.agent_id, owned["key"])
                if not row:
                    continue
                require(not owned.get("sessionId") or owned["sessionId"] == row["sessionId"],
                        "owned-session-identity-mismatch")
                if not owned.get("terminal") and row.get("status") == "running":
                    self.gateway.rpc("chat.abort", {"sessionKey": owned["key"]})
                    for _ in range(15):
                        row = self.state.session(self.agent_id, owned["key"])
                        if not row or row.get("status") != "running":
                            break
                        # A restarted Gateway can retain a stale running row
                        # although its runtime has no active writer. Require an
                        # explicit live false before deleting that exact row.
                        listed = self.gateway.rpc("sessions.list", {"agentId": self.agent_id,
                            "limit": 100, "includeDerivedTitles": False, "includeLastMessage": False})
                        live_rows = [item for item in listed.get("sessions", [])
                                     if item.get("key", item.get("sessionKey")) == owned["key"]]
                        if (len(live_rows) == 1 and live_rows[0].get("hasActiveRun") is False
                                and not live_rows[0].get("activeRunIds") and not live_rows[0].get("activeRunId")):
                            owned["staleRunningRowNoRuntimeWriter"] = True
                            break
                        self.sleep(1)
                    require(not row or row.get("status") != "running"
                            or owned.get("staleRunningRowNoRuntimeWriter"), "owned-run-still-active")
                if row:
                    self.gateway.rpc("sessions.delete", {"agentId": self.agent_id, "key": owned["key"],
                        "expectedSessionId": row["sessionId"], "deleteTranscript": True, "emitLifecycleHooks": False})
                require(self.state.session(self.agent_id, owned["key"]) is None, "owned-session-cleanup-failed")
                owned["stage"] = "session-deleted"
                self.save_cleanup_plan()
            except (PilotError, OSError, ValueError, subprocess.SubprocessError):
                failures.append("owned-session-cleanup-pending")
        if self.agent_armed and not failures:
            try:
                config = self.state.config()
                entry = config.get("agents", {}).get("entries", {}).get(self.agent_id)
                if entry:
                    require(entry.get("workspace") == str(self.workspace), "cleanup-agent-ownership-mismatch")
                    self.gateway.rpc("agents.delete", {"agentId": self.agent_id, "deleteFiles": False})
                require(not self.state.scheduled(self.agent_id), "pilot-scheduled-jobs-remain")
            except (PilotError, OSError, ValueError, subprocess.SubprocessError):
                failures.append("owned-agent-cleanup-pending")
        if self.pins_armed and not failures:
            try:
                self.restore_pins()
            except (PilotError, OSError, ValueError, subprocess.SubprocessError) as error:
                failures.append(str(error) if isinstance(error, PilotError) else "config-restore-pending")
        if hasattr(self, "owner_before"):
            try:
                after = self.state.main_snapshot()
                private_write(self.root / "main-after.json", after)
                self.receipt["mainPreservation"] = main_preserved(self.owner_before, after)
            except (PilotError, OSError, ValueError, sqlite3.Error):
                failures.append("main-preservation-unverified")
        self.receipt["cleanup"] = {"ok": not failures, "pending": failures,
                                   "ownedArtifactsKept": True}
        self.save()
        return not failures

    def run_cleanup_only(self):
        self.receipt["cleanupOnlyRuntime"] = self.state.runtime_snapshot()
        self.receipt["idleBeforeRecovery"] = self.idle(allow_owned=True)
        clean = self.cleanup()
        self.receipt["cleanupOnlyCompletedAt"] = datetime.now(timezone.utc).isoformat()
        self.save()
        return {"ok": clean, "cleanup": self.receipt["cleanup"], "cleanupOnly": True,
                "originalPilotSucceeded": self.receipt.get("ok") is True}

    def run(self):
        error = None
        try:
            self.preflight()
            self.setup()
            self.run_phase("train")
            self.capture_learning()
            self.run_phase("repeat")
            skill_file = Path(self.receipt["learning"]["skillFile"])
            require(digest(skill_file.read_bytes()) == self.receipt["learning"]["skillSha256"],
                    "repeat-changed-learned-skill")
        except (PilotError, OSError, ValueError, sqlite3.Error, subprocess.SubprocessError) as exc:
            error = str(exc) if isinstance(exc, PilotError) else "pilot-operation-failed:" + type(exc).__name__
            if hasattr(self, "current_phase"):
                self.receipt["phases"].setdefault(self.current_phase, {}).update(ok=False, status="failed", error=error)
        finally:
            clean = self.cleanup()
        self.receipt.update(ok=error is None and clean, error=error,
                            completedAt=datetime.now(timezone.utc).isoformat())
        self.save()
        return self.receipt


class MainPilot(Pilot):
    """Explicit main-route mode: native learning with existing policy, no config writes."""

    uses_existing_main = True

    def __init__(self, run_dir, model="gpt-5.6-sol", gateway=None, state=None, sleep=time.sleep):
        super().__init__(run_dir, model, gateway, state, sleep)
        self.agent_id = "main"
        self.agent_dir = self.state.agent_db("main").parent
        self.skill_file = self.agent_dir / "workshop-skills" / SKILL / "SKILL.md"
        self.receipt.update(agentId="main", mode="existing-main", mainConfigurationWrites=False,
            openclawToolPolicy="existing-main-policy-with-native-tool-audit", learnedProcedureRetained=True)

    @staticmethod
    def validate_owned_session(owned):
        require(isinstance(owned, dict) and owned.get("agentId") == "main"
                and re.fullmatch(r"agent:main:explicit:learning-(?:train|repeat)-[a-f0-9]{32}", owned.get("key", "")),
                "main-cleanup-session-not-owned")

    def observe_tools(self, result):
        groups = result.get("groups")
        require(isinstance(groups, list), "tool-inventory-schema-unverified")
        names = {tool.get("id", tool.get("name")) for group in groups for tool in group.get("tools", [])}
        require("skill_workshop" in names and all(isinstance(name, str) for name in names),
                "main-workshop-not-available")
        return sorted(names)

    def verify_phase_terminal(self, result, steps, events):
        allowed = {"skill_workshop"}
        for event in events:
            message = event.get("message", {})
            if message.get("role") != "assistant":
                continue
            for call in message.get("content", []):
                if not isinstance(call, dict) or call.get("type") not in ("toolCall", "tool_use"):
                    continue
                name = call.get("name")
                if name == "skill_workshop":
                    continue
                args = call.get("arguments", call.get("input", {}))
                if isinstance(args, str):
                    args = strict_json(args)
                require(isinstance(args, dict), "main-auxiliary-arguments-invalid")
                if name == "read":
                    allowed_paths = {str(SKILL_CREATOR_GUIDE)}
                    if args.get("path") == str(self.skill_file):
                        learned = self.receipt.get("learning", {})
                        require(getattr(self, "current_phase", None) == "repeat"
                                and learned.get("skillFile") == str(self.skill_file)
                                and self.skill_file.is_file() and not self.skill_file.is_symlink()
                                and digest(self.skill_file.read_bytes()) == learned.get("skillSha256"),
                                "main-learned-skill-read-unverified")
                        allowed_paths.add(str(self.skill_file))
                    require(set(args) <= {"path", "offset", "limit"}
                            and args.get("path") in allowed_paths, "main-read-outside-owned-guides")
                    require(all(type(args[key]) is int and 0 < args[key] <= 10000
                                for key in ("offset", "limit") if key in args), "main-guide-read-bounds-invalid")
                elif name == "progress_card":
                    plan = args.get("plan")
                    require(set(args) == {"plan"} and isinstance(plan, list) and 0 < len(plan) <= 50,
                            "main-progress-card-not-own-plan")
                    require(all(isinstance(step, dict) and set(step) == {"step", "status"}
                                and isinstance(step["step"], str) and 0 < len(step["step"]) <= 500
                                and step["status"] in {"pending", "in_progress", "completed"}
                                for step in plan), "main-progress-card-step-invalid")
                else:
                    raise PilotError("unexpected-native-tool-call")
                allowed.add(name)
        require(all(step.get("tool") in allowed for step in steps), "unexpected-native-tool-call")
        self.receipt["auditedAuxiliaryTools"] = sorted(allowed - {"skill_workshop"})
        return verify_terminal(result, self.model, allowed_tools=allowed)

    def preflight(self):
        self.before = self.state.config()
        agents = self.before.get("agents", {})
        require("main" in agents.get("entries", {}), "existing-main-agent-required")
        workspace = agents["entries"]["main"].get("workspace", agents.get("defaults", {}).get("workspace"))
        require(isinstance(workspace, str) and Path(workspace).is_absolute(), "main-workspace-not-explicit")
        for path in (self.skill_file.parent, Path(workspace) / "skills" / SKILL):
            require(not path.exists() and not any(p.is_symlink() for p in (path, *path.parents)),
                    "procedure-name-already-owned")
        require(not self.state.workshop_name_claimed("main", SKILL), "procedure-name-already-owned")
        self.receipt["runtimeSnapshots"] = {"before": self.state.runtime_snapshot()}
        self.owner_before = self.state.main_snapshot()
        private_write(self.root / "main-before.json", self.owner_before, True)
        private_write(self.root / "config-before.json", (self.state.root / "openclaw.json").read_bytes(), True)
        self.receipt["idleBeforeTest"] = self.idle()
        status = self.gateway.rpc("models.authStatus", {"agentId": "main", "refresh": False})
        self.auth_profile, self.receipt["authSelection"] = select_oauth_profile(self.before, status)
        self.prepare_fixtures()
        self.save()

    def setup(self):
        self.receipt["setup"] = {"existingMainAgent": True, "newAgentCreated": False,
            "bootstrapInjection": "existing-main-policy", "input": "inline-synthetic-fixtures-only"}
        self.save_cleanup_plan()
        self.save()

    def capture_learning(self):
        require(bool(self.sessions), "main-training-session-missing")
        training = self.sessions[0]
        self.validate_owned_session(training)
        selected = [item for item in self.state.session_proposals("main", SKILL, training["key"])
                    if item.get("status") == "applied"]
        require(len(selected) == 1, "own-applied-procedure-not-proven")
        proposal = selected[0]
        require(proposal.get("origin", {}).get("sessionKey") == training["key"]
                and proposal.get("target", {}).get("skillKey") == SKILL
                and proposal.get("target", {}).get("skillFile") == str(self.skill_file)
                and self.skill_file.is_file()
                and not any(p.is_symlink() for p in (self.skill_file, *self.skill_file.parents)),
                "main-learned-procedure-target-unverified")
        private_write(self.root / "native-proposals.json", [proposal], True)
        private_write(self.root / "learned-SKILL.md", self.skill_file.read_bytes(), True)
        self.receipt["learning"] = {"proposalId": proposal.get("id"), "status": "applied",
            "skillFile": str(self.skill_file), "skillSha256": digest(self.skill_file.read_bytes()),
            "ownerAgentId": "main", "retainedForOwner": True}
        self.save()

    def cleanup(self):
        try:
            require(not self.agent_armed and not self.pins_armed, "main-agent-mutation-never-armed")
            for owned in self.sessions:
                self.validate_owned_session(owned)
        except PilotError as error:
            self.receipt["cleanup"] = {"ok": False, "pending": [str(error)], "ownedArtifactsKept": True}
            self.save()
            return False
        clean = super().cleanup()
        if hasattr(self, "before"):
            unchanged = protected_config(self.before) == protected_config(self.state.config())
            self.receipt["mainConfigurationUnchanged"] = unchanged
            if not unchanged:
                self.receipt["cleanup"]["ok"] = False
                self.receipt["cleanup"]["pending"].append("main-config-drift")
                clean = False
        self.save()
        return clean


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--model", default="gpt-5.6-sol", choices=["gpt-5.6-sol"])
    parser.add_argument("--keep-artifacts", action="store_true", default=True,
                        help="All owned files are always retained for review")
    parser.add_argument("--cleanup-only", action="store_true",
                        help="Recover only the exact previously armed agent and sessions; never run a model")
    parser.add_argument("--use-existing-main", action="store_true",
                        help="Explicitly use main OAuth/policy, retain the new learned skill, and never edit agent config")
    args = parser.parse_args()
    try:
        runner = MainPilot if args.use_existing_main else Pilot
        result = (runner.recover(args.run_dir).run_cleanup_only() if args.cleanup_only
                  else runner(args.run_dir, args.model).run())
        print(json.dumps({"ok": result["ok"], "error": result.get("error"),
                          "cleanup": result.get("cleanup"), "receipt": str(args.run_dir.absolute() / "receipt.json")},
                         ensure_ascii=False))
        return 0 if result["ok"] else 1
    except (PilotError, OSError, ValueError) as exc:
        print(json.dumps({"ok": False, "error": str(exc) if isinstance(exc, PilotError)
                          else "pilot-start-failed:" + type(exc).__name__}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
