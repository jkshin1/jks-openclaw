#!/usr/bin/env python3
"""Offline checks of pilot containment, exact cleanup, and evidence rejection."""

from copy import deepcopy
from contextlib import closing
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import subprocess
import sqlite3


spec = importlib.util.spec_from_file_location("learning_pilot", Path(__file__).with_name("openclaw-learning-pilot.py"))
pilot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pilot)


class EvidenceTest(unittest.TestCase):
    def test_busy_and_truncated_idle_snapshots_cannot_authorize_config(self):
        for value in ({"sessions": [{"status": "running"}]},
                      {"sessions": [{"status": "done", "activeRunId": "other"}]},
                      {"sessions": [{"status": "done", "hasActiveRun": True}]},
                      {"sessions": [{"status": "done", "activeRunIds": ["other"]}]},
                      {"sessions": [], "hasActiveRun": True},
                      {"sessions": [], "activeRunIds": ["other"]},
                      {"sessions": [], "total": 1}, {"sessions": [], "hasMore": True}, {},
                      {"sessions": [], "total": "5"}, {"sessions": [], "total": None},
                      {"sessions": [], "count": True}, {"sessions": [{"status": "done"}], "total": 1, "count": 3},
                      {"sessions": [{"status": "done"}], "count": 2}, {"sessions": [], "total": -1}):
            with self.subTest(value=value), self.assertRaises(pilot.PilotError):
                pilot.idle_sessions(value)
        self.assertEqual(pilot.idle_sessions({"sessions": [{"status": "done"}]}),
                         {"sessionCount": 1, "active": 0})
        self.assertEqual(pilot.idle_sessions({"sessions": [{"status": "done"}], "total": 1, "count": 1}),
                         {"sessionCount": 1, "active": 0})

    def test_unexpected_shell_or_memory_tool_blocks_inference(self):
        for unwanted in ("exec", "read", "memory_search", "message"):
            with self.subTest(tool=unwanted), self.assertRaises(pilot.PilotError):
                pilot.tool_names({"groups": [{"tools": [{"id": "skill_workshop"}, {"id": unwanted}]}]})
        self.assertEqual(pilot.tool_names({"groups": [{"tools": [{"id": "skill_workshop"}]}]}),
                         ["skill_workshop"])

    def test_report_rejects_duplicate_keys_trailing_prose_and_nonfinite_numbers(self):
        for text in ('{"x":1,"x":2}', '{"x": NaN}', '{}\nDone.', 'Here: {}'):
            with self.subTest(text=text), self.assertRaises((pilot.PilotError, ValueError)):
                pilot.report_json(text)
        self.assertEqual(pilot.report_json('```json\n{"x":1}\n```'), {"x": 1})

    def test_native_read_requires_matching_successful_tool_result(self):
        events = [{"type": "message", "message": {"role": "assistant", "content": [
            {"type": "toolCall", "id": "call1", "name": "skill_workshop",
             "arguments": {"action": "read", "skill_name": pilot.SKILL}}]}}]
        self.assertFalse(pilot.native_steps(events)[0]["successful"])
        events.append({"type": "message", "message": {"role": "toolResult", "toolCallId": "call1",
                       "isError": True, "details": {"skillKey": pilot.SKILL, "contentIncluded": True}}})
        self.assertFalse(pilot.native_steps(events)[0]["successful"])
        events[-1]["message"]["isError"] = False
        events[-1]["message"]["details"]["contentIncluded"] = False
        step = pilot.native_steps(events)[0]
        self.assertTrue(step["successful"])
        self.assertFalse(step["readDetails"]["contentIncluded"])

    def test_paid_or_fallback_route_never_passes(self):
        native = {"status": "ok", "endedAt": 42,
                  "terminalReply": {"disposition": "visible", "text": "{}"},
                  "terminalReceipt": {"rerouted": False, "successfulToolNames": [],
                      "requested": {"provider": "openai", "model": "gpt-5.6-sol"},
                      "effective": {"provider": "openai", "model": "gpt-5.6-sol"}}}
        self.assertEqual(pilot.verify_terminal(native, "gpt-5.6-sol"), {})
        for change in ({"rerouted": True}, {"effective": {"provider": "openrouter", "model": "gpt-5.6-sol"}},
                       {"successfulToolNames": ["exec"]}):
            bad = deepcopy(native)
            bad["terminalReceipt"].update(change)
            with self.subTest(change=change), self.assertRaises(pilot.PilotError):
                pilot.verify_terminal(bad, "gpt-5.6-sol")

    def test_owner_activity_growth_allowed_but_identity_loss_rejected(self):
        before = {"sessionIdentities": {"a": "one"}, "sessionCount": 1, "transcriptEventCount": 10}
        after = {"sessionIdentities": {"a": "one", "b": "two"}, "sessionCount": 2, "transcriptEventCount": 14}
        self.assertEqual(pilot.main_preserved(before, after)["transcriptEventCountDelta"], 4)
        for change in ({"sessionIdentities": {}}, {"sessionIdentities": {"a": "replacement"}},
                       {"transcriptEventCount": 9}):
            with self.subTest(change=change), self.assertRaises(pilot.PilotError):
                pilot.main_preserved(before, {**after, **change})

    def test_usage_does_not_confuse_missing_counters_with_zero(self):
        events = [{"message": {"role": "assistant", "usage": {"input": 10, "output": 3, "cacheRead": 0,
                                                                  "totalTokens": 13}}},
                  {"message": {"role": "assistant", "usage": {"input": 5, "output": 2,
                                                                  "totalTokens": 7}}}]
        summary, samples = pilot.native_usage(events)
        self.assertEqual(summary["input_tokens"], 15)
        self.assertEqual(summary["total_tokens"], 20)
        self.assertIsNone(summary["cache_read_tokens"])
        self.assertIsNone(summary["cache_write_tokens"])
        self.assertEqual(summary["field_sample_counts"]["cache_read_tokens"], 1)
        self.assertEqual(len(samples), 2)
        missing, _ = pilot.native_usage([])
        self.assertFalse(missing["available"])
        self.assertIsNone(missing["total_tokens"])

    def test_explicit_oauth_selection_uses_current_order_and_checks_actual_readiness(self):
        profile = "openai:account-current"
        config = {"auth": {"order": {"openai": [profile]},
                           "profiles": {profile: {"provider": "openai", "mode": "oauth"}}}}
        status = {"providers": [{"provider": "openai", "profiles": [
            {"profileId": profile, "type": "oauth", "status": "ok"}]}]}
        original = deepcopy(config)
        selected, metadata = pilot.select_oauth_profile(config, status)
        self.assertEqual(selected, profile)
        self.assertNotIn(profile, json.dumps(metadata))
        self.assertEqual(config, original)
        for change in ("openai:default", "openai:missing"):
            invalid = deepcopy(config)
            invalid["auth"]["order"]["openai"] = [change]
            with self.assertRaises(pilot.PilotError):
                pilot.select_oauth_profile(invalid, status)
        with self.assertRaises(pilot.PilotError):
            pilot.verify_session_auth_pin({"authProfileOverride": "openai:default"}, profile)
        self.assertTrue(pilot.verify_session_auth_pin({"authProfileOverride": profile}, profile)["explicitSessionPin"])


class FakeState:
    def __init__(self, root):
        self.root = root
        self.current = {"agents": {"entries": {"main": {}}, "defaults": {"workspace": str(root / "owner")}},
                        "channels": {"telegram": {"enabled": True}},
                        "auth": {"order": {"openai": ["openai:test-owner"]},
                                 "profiles": {"openai:test-owner": {"provider": "openai", "mode": "oauth"}}}}
        (root / "openclaw.json").write_text(json.dumps(self.current))
        self.rows = {}
        self.proposal_rows = []

    def config(self):
        return deepcopy(self.current)

    def runtime_snapshot(self):
        return {"version": "test-qualified", "packageSha256": "synthetic-package-hash"}

    def agent_db(self, agent_id):
        return self.root / "agents" / agent_id / "agent/openclaw-agent.sqlite"

    def main_snapshot(self):
        return {"sessionIdentities": {"owner-key-hash": "owner-session-hash"},
                "sessionCount": 1, "transcriptEventCount": 11}

    def session(self, agent_id, key):
        return self.rows.get(key)

    def scheduled(self, agent_id):
        return []

    def workshop_name_claimed(self, agent_id, skill_key):
        return any(owner == agent_id and proposal.get("target", {}).get("skillKey") == skill_key
                   for owner, proposal in self.proposal_rows)

    def session_proposals(self, agent_id, skill_key, session_key):
        return [deepcopy(proposal) for owner, proposal in self.proposal_rows if owner == agent_id
                and proposal.get("target", {}).get("skillKey") == skill_key
                and proposal.get("origin", {}).get("sessionKey") == session_key]


class FakeGateway:
    def __init__(self, state):
        self.state, self.calls = state, []
        self.busy = False

    def rpc(self, method, params, **kwargs):
        self.calls.append((method, deepcopy(params)))
        if method == "sessions.list":
            return {"sessions": [{"status": "running" if self.busy else "done"}]}
        if method == "models.authStatus":
            return {"providers": [{"provider": "openai", "profiles": [
                {"profileId": "openai:test-owner", "type": "oauth", "status": "ok"}]}]}
        if method == "sessions.delete":
            self.state.rows.pop(params["key"], None)
            return {"ok": True}
        if method == "agents.delete":
            self.state.current["agents"]["entries"].pop(params["agentId"], None)
            self.state.current["agents"].pop("ownership", None)
            return {"ok": True}
        raise AssertionError("Unexpected RPC: " + method)

    def patch(self, patch):
        self.calls.append(("patch", deepcopy(patch)))
        def merge(current, delta):
            for key, value in delta.items():
                if value is None:
                    current.pop(key, None)
                elif isinstance(value, dict):
                    merge(current.setdefault(key, {}), value)
                else:
                    current[key] = deepcopy(value)
        merge(self.state.current, patch)


class LifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.state = FakeState(self.root)
        self.gateway = FakeGateway(self.state)
        self.runner = pilot.Pilot(self.root / "run", gateway=self.gateway, state=self.state, sleep=lambda _: None)

    def tearDown(self):
        self.temp.cleanup()

    def test_busy_preflight_performs_no_config_mutation_and_saves_private_backup(self):
        self.gateway.busy = True
        result = self.runner.run()
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "gateway-busy-pending")
        self.assertFalse(any(method in {"patch", "agents.create", "agent"} for method, _ in self.gateway.calls))
        self.assertEqual((self.runner.root / "config-before.json").stat().st_mode & 0o777, 0o600)

    def test_cleanup_deletes_only_armed_keys_and_keeps_owned_files(self):
        self.runner.preflight()
        self.gateway.patch(self.runner.pins)
        self.runner.pins_armed = True
        self.runner.agent_armed = True
        agent_id = self.runner.agent_id
        self.state.current["agents"]["entries"][agent_id] = {"workspace": str(self.runner.workspace)}
        self.state.current["agents"]["ownership"] = "explicit"
        own_key = "agent:" + agent_id + ":explicit:owned"
        self.state.rows = {own_key: {"sessionId": "own-id", "status": "done"},
                           "owner-telegram-key": {"sessionId": "owner-id", "status": "done"}}
        self.runner.sessions = [{"key": own_key, "sessionId": "own-id", "terminal": True}]
        self.assertTrue(self.runner.cleanup())
        deletes = [params for method, params in self.gateway.calls if method == "sessions.delete"]
        self.assertEqual([item["key"] for item in deletes], [own_key])
        self.assertIn("owner-telegram-key", self.state.rows)
        agent_delete = [params for method, params in self.gateway.calls if method == "agents.delete"]
        self.assertEqual(agent_delete, [{"agentId": agent_id, "deleteFiles": False}])
        self.assertEqual(self.state.config(), self.runner.before)

    def test_unrelated_concurrent_owner_configuration_is_preserved_during_cleanup(self):
        self.runner.preflight()
        self.gateway.patch(self.runner.pins)
        self.runner.pins_armed = True
        self.state.current["channels"]["telegram"]["newOwnerSetting"] = True
        self.runner.expected_config = self.state.config()
        self.assertTrue(self.runner.cleanup())
        self.assertTrue(self.state.current["channels"]["telegram"]["newOwnerSetting"])
        self.assertEqual(self.state.current["agents"], self.runner.before["agents"])
        self.assertIn("channels.telegram.newOwnerSetting",
                      self.runner.receipt["configRestoration"]["unrelatedCurrentDifferencesPreserved"])

    def test_owned_pin_conflict_does_not_overwrite_an_external_owner_change(self):
        self.runner.preflight()
        self.gateway.patch(self.runner.pins)
        self.runner.pins_armed = True
        self.runner.expected_config = self.state.config()
        self.state.current["agents"]["entries"]["main"]["workspace"] = "/externally-chosen-workspace"
        self.assertFalse(self.runner.cleanup())
        self.assertEqual(self.state.current["agents"]["entries"]["main"]["workspace"],
                         "/externally-chosen-workspace")

    def test_replaced_session_identity_is_not_deleted(self):
        self.runner.preflight()
        key = "agent:" + self.runner.agent_id + ":explicit:owned"
        self.runner.sessions = [{"key": key, "sessionId": "old", "terminal": True}]
        self.state.rows[key] = {"sessionId": "replacement", "status": "done"}
        self.assertFalse(self.runner.cleanup())
        self.assertIn(key, self.state.rows)
        self.assertFalse(any(method == "sessions.delete" for method, _ in self.gateway.calls))

    def test_ambiguous_agent_creation_still_cleans_armed_identity(self):
        original_rpc = self.gateway.rpc
        def rpc(method, params, **kwargs):
            if method == "agents.create":
                self.gateway.calls.append((method, deepcopy(params)))
                self.state.current["agents"]["entries"][params["name"]] = {"workspace": params["workspace"]}
                self.state.current["agents"]["ownership"] = "explicit"
                raise pilot.PilotError("gateway-call-failed:agents.create")
            return original_rpc(method, params, **kwargs)
        self.gateway.rpc = rpc
        result = self.runner.run()
        self.assertFalse(result["ok"])
        self.assertTrue(result["cleanup"]["ok"])
        self.assertEqual(self.state.config(), self.runner.before)
        self.assertTrue((self.runner.root / "cleanup-plan.json").is_file())
        self.assertEqual([params["agentId"] for method, params in self.gateway.calls
                          if method == "agents.delete"], [self.runner.agent_id])

    def test_runtime_drift_blocks_the_next_model_phase(self):
        self.runner.preflight()
        self.state.runtime_snapshot = lambda: {"version": "new-runtime", "packageSha256": "new-hash"}
        with self.assertRaisesRegex(pilot.PilotError, "runtime-changed-during-pilot"):
            self.runner.run_phase("train")
        self.assertFalse(any(method == "agent" for method, _ in self.gateway.calls))

    def test_cleanup_recovery_backfills_ids_from_only_the_owned_row(self):
        self.runner.preflight()
        self.gateway.patch(self.runner.pins)
        self.runner.pins_armed = self.runner.agent_armed = True
        agent_id = self.runner.agent_id
        self.state.current["agents"]["entries"][agent_id] = {"workspace": str(self.runner.workspace)}
        self.runner.remember_config()
        key = f"agent:{agent_id}:explicit:learning-train-test"
        self.runner.sessions = [{"key": key, "agentId": agent_id, "terminal": False}]
        self.runner.save_cleanup_plan()
        self.state.rows[key] = {"sessionId": "recovered-id",
                               "runId": "3c0dae10-8efb-4d8e-9357-34f5e06cb5de", "status": "failed"}
        recovered = pilot.Pilot.recover(self.runner.root, gateway=self.gateway, state=self.state, sleep=lambda _: None)
        self.assertEqual(recovered.sessions[0]["sessionId"], "recovered-id")
        self.assertEqual(recovered.sessions[0]["runId"], "3c0dae10-8efb-4d8e-9357-34f5e06cb5de")
        self.state.current["talk"] = {"newVersionSetting": True}
        result = recovered.run_cleanup_only()
        self.assertTrue(result["ok"])
        self.assertTrue(self.state.current["talk"]["newVersionSetting"])
        self.assertFalse(any(method == "agent" for method, _ in self.gateway.calls))

    def test_bounded_private_error_diagnostics_redact_tokens_and_hide_owner_list(self):
        gateway = pilot.Gateway(diagnostic_dir=self.root / "diagnostics")
        stderr = "Cannot find module x; Bearer abcdef123456; access_token=secret-value " + "X" * 12000
        process = subprocess.CompletedProcess([], 1, "", stderr)
        with patch.object(pilot.subprocess, "run", return_value=process):
            with self.assertRaisesRegex(pilot.PilotError, "missing-module"):
                gateway.rpc("agent.wait", {"runId": "learning-test"})
            with self.assertRaises(pilot.PilotError):
                gateway.rpc("sessions.list", {})
        records = [json.loads(file.read_text()) for file in (self.root / "diagnostics").iterdir()]
        native = next(item for item in records if item["method"] == "agent.wait")
        self.assertNotIn("abcdef123456", native["stderrExcerpt"])
        self.assertNotIn("secret-value", native["stderrExcerpt"])
        self.assertLessEqual(len(native["stderrExcerpt"].encode()), 8192)
        owner_list = next(item for item in records if item["method"] == "sessions.list")
        self.assertNotIn("stderrExcerpt", owner_list)

    def test_current_schema_proposals_are_selected_by_exact_agent_owner(self):
        folder = self.root / "state"
        folder.mkdir()
        with closing(sqlite3.connect(folder / "openclaw.sqlite")) as db:
            db.execute("CREATE TABLE skill_workshop_proposals(owner_agent_id TEXT,record_json TEXT)")
            db.executemany("INSERT INTO skill_workshop_proposals VALUES (?,?)", [
                (self.runner.agent_id, '{"id":"owned-proposal"}'),
                ("main", '{"id":"owner-private-proposal"}')])
            db.commit()
        observed = pilot.State(self.root).proposals(self.runner.agent_id)
        self.assertEqual(observed, [{"id": "owned-proposal"}])

    def test_current_agent_workshop_target_is_accepted_but_owner_target_is_not(self):
        path = self.runner.agent_dir / "workshop-skills" / pilot.SKILL / "SKILL.md"
        path.parent.mkdir(parents=True)
        path.write_text("Synthetic procedure only\n")
        proposal = {"id": "own", "status": "applied", "target": {"skillKey": pilot.SKILL,
                    "skillFile": str(path)}}
        self.state.proposals = lambda agent_id: [proposal] if agent_id == self.runner.agent_id else []
        self.runner.capture_learning()
        self.assertEqual(self.runner.receipt["learning"]["skillFile"], str(path))
        proposal["target"]["skillFile"] = str(self.state.agent_db("main").parent / "workshop-skills" / pilot.SKILL / "SKILL.md")
        with self.assertRaisesRegex(pilot.PilotError, "learned-skill-target-unverified"):
            self.runner.capture_learning()

    def test_failed_native_turn_keeps_observed_usage_and_pins_only_session(self):
        self.runner.preflight()
        original_rpc = self.gateway.rpc
        def rpc(method, params, **kwargs):
            if method == "sessions.create":
                self.assertEqual(params["model"], "openai/gpt-5.6-sol@openai:test-owner")
                self.state.rows[params["key"]] = {"sessionId": "synthetic-session", "status": "done",
                                                "authProfileOverride": "openai:test-owner"}
                return {"ok": True}
            if method == "tools.effective":
                return {"groups": [{"tools": [{"id": "skill_workshop"}]}]}
            if method == "agent":
                self.assertNotIn("model", params)
                self.assertNotIn("provider", params)
                return {"runId": "learning-observed"}
            if method == "agent.wait":
                return {"status": "error", "endedAt": 10, "error": "synthetic failure"}
            return original_rpc(method, params, **kwargs)
        self.gateway.rpc = rpc
        self.state.events = lambda agent_id, session_id: [{"message": {"role": "assistant", "usage": {
            "input": 20, "output": 5, "totalTokens": 25}}}]
        with self.assertRaisesRegex(pilot.PilotError, "run-not-successful"):
            self.runner.run_phase("train")
        phase = self.runner.receipt["phases"]["train"]
        self.assertFalse(phase["ok"])
        self.assertEqual(phase["usage"]["total_tokens"], 25)
        self.assertEqual(phase["nativeStatus"], "error")


class MainLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.state = FakeState(self.root)
        self.gateway = FakeGateway(self.state)
        self.runner = pilot.MainPilot(self.root / "main-run", gateway=self.gateway,
                                      state=self.state, sleep=lambda _: None)
        self.key = "agent:main:explicit:learning-train-" + "a" * 32

    def native(self, names):
        return {"status": "ok", "endedAt": 42,
                "terminalReply": {"disposition": "visible", "text": "{}"},
                "terminalReceipt": {"rerouted": False, "successfulToolNames": names,
                    "requested": {"provider": "openai", "model": "gpt-5.6-sol"},
                    "effective": {"provider": "openai", "model": "gpt-5.6-sol"}}}

    def events(self, name, arguments):
        return [{"message": {"role": "assistant", "content": [
            {"type": "toolCall", "id": "synthetic", "name": name, "arguments": arguments}]}},
                {"message": {"role": "toolResult", "toolCallId": "synthetic", "isError": False,
                             "details": {}}}]

    def test_explicit_main_setup_never_changes_config_or_creates_an_agent(self):
        original = self.state.config()
        self.runner.preflight()
        self.runner.setup()
        self.assertTrue(self.runner.cleanup())
        self.assertEqual(self.state.config(), original)
        self.assertFalse(any(method in {"patch", "agents.create", "agents.delete", "agent"}
                             for method, _ in self.gateway.calls))
        self.assertEqual(self.runner.receipt["setup"]["bootstrapInjection"], "existing-main-policy")
        self.assertTrue(self.runner.receipt["mainConfigurationUnchanged"])
        self.assertFalse(self.runner.pins_armed)
        self.assertFalse(self.runner.agent_armed)

    def test_main_inventory_override_does_not_change_default_strict_policy(self):
        inventory = {"groups": [{"tools": [{"id": "skill_workshop"}, {"id": "exec"}, {"id": "read"}]}]}
        self.assertIn("exec", self.runner.observe_tools(inventory))
        strict = pilot.Pilot(self.root / "isolated-run", gateway=self.gateway, state=self.state)
        with self.assertRaisesRegex(pilot.PilotError, "inventory-not-exact"):
            strict.observe_tools(inventory)
        strict.preflight()
        self.assertNotIn("authInheritance", strict.pins["agents"]["defaults"])

    def test_existing_procedure_directory_is_preserved_without_model_or_mutation(self):
        self.runner.skill_file.parent.mkdir(parents=True)
        self.runner.skill_file.write_text("owner procedure\n")
        result = self.runner.run()
        self.assertEqual(result["error"], "procedure-name-already-owned")
        self.assertEqual(self.runner.skill_file.read_text(), "owner procedure\n")
        self.assertFalse(any(method in {"patch", "agents.create", "sessions.create", "agent"}
                             for method, _ in self.gateway.calls))

    def test_existing_proposal_claim_blocks_new_training_even_without_file(self):
        self.state.proposal_rows = [("main", {"target": {"skillKey": pilot.SKILL}, "status": "pending"})]
        result = self.runner.run()
        self.assertEqual(result["error"], "procedure-name-already-owned")
        self.assertFalse(self.runner.skill_file.exists())
        self.assertFalse(any(method == "agent" for method, _ in self.gateway.calls))

    def test_cleanup_deletes_only_own_sessions_and_retains_new_native_procedure(self):
        self.runner.preflight()
        self.runner.setup()
        self.runner.sessions = [{"key": self.key, "agentId": "main", "sessionId": "synthetic-id", "terminal": True}]
        self.state.rows = {self.key: {"sessionId": "synthetic-id", "status": "done"},
                           "owner-telegram": {"sessionId": "owner-id", "status": "done"}}
        self.runner.skill_file.parent.mkdir(parents=True)
        self.runner.skill_file.write_text("new general procedure\n")
        proposal = {"id": "own-proposal", "status": "applied", "origin": {"sessionKey": self.key},
                    "target": {"skillKey": pilot.SKILL, "skillFile": str(self.runner.skill_file)}}
        foreign = deepcopy(proposal)
        foreign["id"], foreign["origin"]["sessionKey"] = "unrelated", "owner-telegram"
        self.state.proposal_rows = [("main", foreign), ("main", proposal), ("other-agent", proposal)]
        self.runner.capture_learning()
        self.assertEqual(self.runner.receipt["learning"]["proposalId"], "own-proposal")
        self.assertTrue(self.runner.cleanup())
        self.assertTrue(self.runner.skill_file.exists())
        self.assertIn("owner-telegram", self.state.rows)
        deleted = [params for method, params in self.gateway.calls if method == "sessions.delete"]
        self.assertEqual(deleted, [{"agentId": "main", "key": self.key, "expectedSessionId": "synthetic-id",
                                   "deleteTranscript": True, "emitLifecycleHooks": False}])
        self.assertFalse(any(method in {"patch", "agents.delete"} for method, _ in self.gateway.calls))

    def test_foreign_cleanup_key_and_replaced_identity_are_refused(self):
        self.runner.preflight()
        self.runner.sessions = [{"key": "agent:main:main", "agentId": "main", "sessionId": "owner-id"}]
        self.assertFalse(self.runner.cleanup())
        self.runner.sessions = [{"key": self.key, "agentId": "main", "sessionId": "old-id", "terminal": True}]
        self.state.rows[self.key] = {"sessionId": "new-owner-id", "status": "done"}
        self.assertFalse(self.runner.cleanup())
        self.assertFalse(any(method == "sessions.delete" for method, _ in self.gateway.calls))

    def test_main_recovery_remains_explicit_and_never_arms_agent_or_config_deletion(self):
        self.runner.preflight()
        self.runner.setup()
        self.runner.sessions = [{"key": self.key, "agentId": "main", "sessionId": "synthetic-id", "terminal": True}]
        self.state.rows[self.key] = {"sessionId": "synthetic-id", "status": "done"}
        self.runner.save_cleanup_plan()
        with self.assertRaisesRegex(pilot.PilotError, "recovery-agent-id-invalid"):
            pilot.Pilot.recover(self.runner.root, gateway=self.gateway, state=self.state)
        recovered = pilot.MainPilot.recover(self.runner.root, gateway=self.gateway, state=self.state,
                                            sleep=lambda _: None)
        self.assertFalse(recovered.agent_armed)
        self.assertFalse(recovered.pins_armed)
        self.assertTrue(recovered.run_cleanup_only()["ok"])
        self.assertFalse(any(method in {"patch", "agents.delete", "agent"} for method, _ in self.gateway.calls))

    def test_main_auxiliary_tools_require_exact_guide_or_own_plan_arguments(self):
        cases = [("read", {"path": str(pilot.SKILL_CREATOR_GUIDE), "offset": 1, "limit": 2000}),
                 ("progress_card", {"plan": [{"step": "합성 절차 검증", "status": "in_progress"}]})]
        for name, args in cases:
            events = self.events(name, args)
            self.assertEqual(self.runner.verify_phase_terminal(self.native([name]), pilot.native_steps(events), events), {})
            with self.assertRaises(pilot.PilotError):
                pilot.verify_terminal(self.native([name]), "gpt-5.6-sol")
        invalid = [("read", {"path": "/private/owner-history.json"}),
                   ("read", {"path": str(pilot.SKILL_CREATOR_GUIDE), "offset": True}),
                   ("progress_card", {"sessionKey": "owner-telegram", "plan": []}),
                   ("progress_card", {"markdown": "unscoped content"}),
                   ("exec", {"command": "true"})]
        for name, args in invalid:
            events = self.events(name, args)
            with self.subTest(name=name, args=args), self.assertRaises(pilot.PilotError):
                self.runner.verify_phase_terminal(self.native([name]), pilot.native_steps(events), events)

    def test_repeat_owned_skill_read_requires_provenance_hash_and_native_workshop_read_still_separate(self):
        self.runner.skill_file.parent.mkdir(parents=True)
        self.runner.skill_file.write_text("saved procedure")
        self.runner.current_phase = "repeat"
        self.runner.receipt["learning"] = {"skillFile": str(self.runner.skill_file),
            "skillSha256": pilot.digest(self.runner.skill_file.read_bytes())}
        events = self.events("read", {"path": str(self.runner.skill_file)})
        self.assertEqual(self.runner.verify_phase_terminal(self.native(["read"]), pilot.native_steps(events), events), {})
        # This helper qualifies only the auxiliary read. run_phase independently
        # continues to require a successful native Workshop read of the skill.
        self.runner.skill_file.write_text("mutated procedure")
        with self.assertRaisesRegex(pilot.PilotError, "learned-skill-read-unverified"):
            self.runner.verify_phase_terminal(self.native(["read"]), pilot.native_steps(events), events)

    def test_database_proposals_are_filtered_by_owner_skill_and_origin(self):
        folder = self.root / "state"
        folder.mkdir()
        own = {"id": "owned", "target": {"skillKey": pilot.SKILL}, "origin": {"sessionKey": self.key}}
        other_key = deepcopy(own)
        other_key["target"]["skillKey"] = "owner-other-procedure"
        other_origin = deepcopy(own)
        other_origin["origin"]["sessionKey"] = "owner-telegram"
        with closing(sqlite3.connect(folder / "openclaw.sqlite")) as db:
            db.execute("CREATE TABLE skill_workshop_proposals(owner_agent_id TEXT,record_json TEXT)")
            db.executemany("INSERT INTO skill_workshop_proposals VALUES (?,?)", [
                (owner, json.dumps(item)) for owner, item in
                [("main", own), ("main", other_key), ("main", other_origin), ("other-agent", own)]])
            db.commit()
        real_state = pilot.State(self.root)
        self.assertTrue(real_state.workshop_name_claimed("main", pilot.SKILL))
        self.assertFalse(real_state.workshop_name_claimed("main", "not-owned"))
        self.assertEqual(real_state.session_proposals("main", pilot.SKILL, self.key), [own])


if __name__ == "__main__":
    unittest.main()
