#!/usr/bin/env python3
"""Regression checks for access boundaries and the misleading old tool inventory."""

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("telegram_verifier", Path(__file__).with_name("verify-telegram-gateway.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def fixture():
    return {
        "gateway": {"bind": "loopback", "auth": {"mode": "token"}, "tailscale": {"mode": "off"}},
        "commands": {"ownerAllowFrom": ["telegram:12345"]},
        "channels": {"telegram": {"enabled": True, "dmPolicy": "allowlist", "allowFrom": ["12345"],
                                  "groupPolicy": "allowlist", "groupAllowFrom": ["12345"],
                                  "groups": {"*": {"requireMention": True}},
                                  "botToken": {"source": "store", "id": "TELEGRAM_BOT_TOKEN"}}},
        "agents": {"defaults": {"sandbox": {"mode": "off"}},
                   "entries": {"main": {"model": copy.deepcopy(verifier.MAIN_MODEL_ROUTE)}}},
        "tools": {"profile": "coding", "alsoAllow": ["browser"], "deny": ["computer"],
                  "exec": {"host": "gateway", "mode": "full"}, "elevated": {"enabled": False},
                  "fs": {"workspaceOnly": False}},
        "session": {"dmScope": "per-channel-peer"},
        "plugins": {"allow": ["memory-core"], "slots": {"memory": "memory-core"},
                    "entries": {"memory-core": {"enabled": True}}},
        "memory": {"search": {"enabled": True, "provider": "none", "sources": ["memory"],
                               "rememberAcrossConversations": False}},
    }


class TelegramPolicyTest(unittest.TestCase):
    def test_main_fallback_authorization_is_exact_and_keeps_routing_guards(self):
        opus, sol, glm = verifier.OPUS_MODEL, verifier.SOL_MODEL, verifier.GLM_MODEL
        approved = {"main": {"model": {"primary": opus, "fallbacks": [sol, glm]}}}
        config = fixture()
        config["agents"]["entries"] = copy.deepcopy(approved)
        verifier.owner_policy(config, "12345")
        changes = [
            {"main": {"model": {"primary": glm, "fallbacks": []}}},
            {"main": {"model": {"primary": sol, "fallbacks": [glm]}}},
            {"main": {"model": {"primary": "openai/gpt-6-astra", "fallbacks": [glm]}}},
            {"main": {"model": {"primary": "anthropic/claude-opus-5", "fallbacks": [sol, glm]}}},
            {"main": {"model": {"primary": opus, "fallbacks": [glm]}}},
            {"main": {"model": {"primary": opus, "fallbacks": [sol]}}},
            {"main": {"model": {"primary": opus, "fallbacks": [glm, sol]}}},
            {"main": {"model": {"primary": opus, "fallbacks": [sol, glm, "openrouter/other"]}}},
            dict(approved, other={}),
            {"main": dict(approved["main"], tools={"allow": ["exec"]})},
        ]
        for entries in changes:
            with self.subTest(entries=entries):
                config["agents"]["entries"] = entries
                with self.assertRaisesRegex(ValueError, "agent routing overrides"):
                    verifier.owner_policy(config, "12345")
        config["agents"]["entries"] = approved
        config["bindings"] = [{"agentId": "main"}]
        with self.assertRaisesRegex(ValueError, "agent routing overrides"):
            verifier.owner_policy(config, "12345")

    def test_turn_scoped_delivery_policy_detects_missing_and_drifted_contract(self):
        with tempfile.TemporaryDirectory() as temp:
            workspace = Path(temp)
            templates = Path(__file__).parent / "templates"
            agents = workspace / "AGENTS.md"
            agents.write_bytes((templates / "TELEGRAM_AGENTS.md").read_bytes())
            agents.chmod(0o600)
            soul = workspace / "SOUL.md"
            contract = (templates / "TELEGRAM_SOUL.md").read_text()
            with self.assertRaisesRegex(ValueError, "missing or symlinked"):
                verifier.workspace_delivery_policy(workspace)
            for invalid in ("", contract.replace("Do not call", "Call"), contract + contract):
                soul.write_text(invalid)
                soul.chmod(0o600)
                with self.assertRaises(ValueError):
                    verifier.workspace_delivery_policy(workspace)
            soul.write_text("Preserved unrelated identity.\n" + contract)
            verifier.workspace_delivery_policy(workspace)

    def test_codex_subscription_route_rejects_api_fallback_and_shared_home(self):
        config = {
            "auth": {"profiles": {"openai:owner": {"provider": "openai", "mode": "oauth"}},
                     "order": {"openai": ["openai:owner"]}},
            "agents": {"defaults": {
                "model": copy.deepcopy(verifier.DEFAULT_MODEL_ROUTE),
                "thinkingDefault": "high", "modelSelectionScope": "session",
                "models": {"openai/gpt-5.6-sol": {"alias": "sol", "agentRuntime": {"id": "codex"}},
                           "openai/gpt-6-astra": {"alias": "astra", "agentRuntime": {"id": "codex"}},
                           "openai/gpt-6-sol": {"alias": "codex", "agentRuntime": {"id": "codex"}}},
                "modelPolicy": {"allow": ["openrouter/*", "openai/gpt-5.6-sol", "openai/gpt-6-astra",
                                          "openai/gpt-6-sol", verifier.OPUS_MODEL]},
                "subagents": {"model": {"primary": "openai/gpt-5.6-sol", "fallbacks": []}},
                "pdfModel": {"primary": "openai/gpt-5.6-sol", "fallbacks": []}}},
            "plugins": {"allow": ["codex", "openai", "web-readability", "document-extract"],
                        "entries": {name: {"enabled": True} for name in
                                    ["codex", "openai", "web-readability", "document-extract"]}},
        }
        config["plugins"]["entries"]["codex"]["config"] = {
            "appServer": {"homeScope": "agent", "mode": "yolo",
                          "clearEnv": ["OPENAI_API_KEY", "CODEX_API_KEY"],
                          "command": verifier.APPROVED_CODEX_COMMAND},
            **{key: {"enabled": False} for key in
               ["sessionCatalog", "supervision", "codexPlugins", "computerUse"]}}
        listing = {"profiles": [{"id": "openai:owner", "provider": "openai", "type": "oauth"}]}
        verifier.codex_subscription_policy(config, listing)
        changes = [
            ("auth.order.openai", ["openai:owner", "openai:api"]),
            ("auth.profiles.openai:owner.mode", "api_key"),
            ("agents.defaults.model.primary", "openrouter/z-ai/glm-5.3-flash"),
            ("agents.defaults.model.primary", "openai/gpt-5.6-sol"),
            ("agents.defaults.model.primary", "openai/gpt-6-astra"),
            ("agents.defaults.model.primary", "openai/gpt-6-sol"),
            ("agents.defaults.model.fallbacks", ["openrouter/z-ai/glm-5.3-flash"]),
            ("agents.defaults.model.fallbacks", ["openai/gpt-6-sol", "openrouter/z-ai/glm-5.3-flash"]),
            ("agents.defaults.model.fallbacks", []),
            ("agents.defaults.thinkingDefault", "low"),
            ("agents.defaults.thinkingDefault", None),
            ("agents.defaults.modelSelectionScope", "global"),
            ("agents.defaults.modelSelectionScope", "agent"),
            ("agents.defaults.modelSelectionScope", None),
            ("agents.defaults.modelPolicy.allow", ["openrouter/*", "openai/*"]),
            ("agents.defaults.modelPolicy.allow", ["openrouter/*", "openai/gpt-5.6-sol"]),
            ("agents.defaults.modelPolicy.allow", ["openrouter/*", "openai/gpt-5.6-sol",
                                                   "openai/gpt-6-astra", "openai/gpt-6-sol"]),
            ("agents.defaults.modelPolicy.allow", ["openrouter/*", "openai/gpt-5.6-sol",
                                                   "openai/gpt-6-astra", "openai/gpt-6-sol",
                                                   "anthropic/*"]),
            ("agents.defaults.pdfModel.primary", "openrouter/z-ai/glm-5.3-flash"),
            ("agents.defaults.pdfModel.fallbacks", ["openrouter/paid-model"]),
            ("agents.defaults.subagents.model.primary", "openrouter/paid-model"),
            ("agents.defaults.subagents.model.fallbacks", ["openrouter/paid-model"]),
            ("plugins.entries.codex.config.appServer.homeScope", "user"),
            ("plugins.entries.codex.config.appServer.clearEnv", []),
            ("plugins.entries.codex.config.supervision.enabled", True),
        ]
        for path, value in changes:
            with self.subTest(path=path):
                bad = copy.deepcopy(config)
                keys = path.split(".")
                parent = bad
                for key in keys[:-1]:
                    parent = parent[key]
                parent[keys[-1]] = value
                with self.assertRaises(ValueError):
                    verifier.codex_subscription_policy(bad, listing)
        for model in ("openai/gpt-5.6-sol", "openai/gpt-6-astra", "openai/gpt-6-sol"):
            for replacement in (None, {"alias": "other", "agentRuntime": {"id": "codex"}},
                                {"alias": config["agents"]["defaults"]["models"][model]["alias"],
                                 "agentRuntime": {"id": "openclaw"}}):
                with self.subTest(model=model, replacement=replacement):
                    bad = copy.deepcopy(config)
                    if replacement is None:
                        del bad["agents"]["defaults"]["models"][model]
                    else:
                        bad["agents"]["defaults"]["models"][model] = replacement
                    with self.assertRaisesRegex(ValueError, "Codex model alias or runtime drifted"):
                        verifier.codex_subscription_policy(bad, listing)
        bad_listing = copy.deepcopy(listing)
        bad_listing["profiles"][0]["type"] = "api_key"
        with self.assertRaises(ValueError):
            verifier.codex_subscription_policy(config, bad_listing)

    def test_gpt6_sol_preserves_auth_and_rejects_unreviewed_commands(self):
        config = {
            "auth": {"profiles": {"openai:owner": {"provider": "openai", "mode": "oauth"}},
                     "order": {"openai": ["openai:owner"]}},
            "agents": {"defaults": {
                "model": copy.deepcopy(verifier.DEFAULT_MODEL_ROUTE),
                "thinkingDefault": "high", "modelSelectionScope": "session",
                "models": {"openai/gpt-5.6-sol": {"alias": "sol", "agentRuntime": {"id": "codex"}},
                           "openai/gpt-6-astra": {"alias": "astra", "agentRuntime": {"id": "codex"}},
                           "openai/gpt-6-sol": {"alias": "codex", "agentRuntime": {"id": "codex"}}},
                "modelPolicy": {"allow": ["openrouter/*", "openai/gpt-5.6-sol", "openai/gpt-6-astra",
                                          "openai/gpt-6-sol", verifier.OPUS_MODEL]},
                "subagents": {"model": {"primary": "openai/gpt-5.6-sol", "fallbacks": []}},
                "pdfModel": {"primary": "openai/gpt-5.6-sol", "fallbacks": []}}},
            "plugins": {"allow": ["codex", "openai", "web-readability", "document-extract"],
                        "entries": {name: {"enabled": True} for name in
                                    ["codex", "openai", "web-readability", "document-extract"]}},
        }
        config["plugins"]["entries"]["codex"]["config"] = {
            "appServer": {"homeScope": "agent", "mode": "yolo",
                          "clearEnv": ["OPENAI_API_KEY", "CODEX_API_KEY"],
                          "command": verifier.APPROVED_CODEX_COMMAND},
            **{key: {"enabled": False} for key in
               ["sessionCatalog", "supervision", "codexPlugins", "computerUse"]}}
        listing = {"profiles": [{"id": "openai:owner", "provider": "openai", "type": "oauth"}]}
        verifier.codex_subscription_policy(config, listing)
        server = config["plugins"]["entries"]["codex"]["config"]["appServer"]
        for command in ("/tmp/codex", verifier.APPROVED_CODEX_COMMAND + " --unsafe", "/usr/bin/true"):
            server["command"] = command
            with self.assertRaisesRegex(ValueError, "Codex auth environment"):
                verifier.codex_subscription_policy(config, listing)
        server["command"] = verifier.APPROVED_CODEX_COMMAND
        defaults = config["agents"]["defaults"]
        defaults["models"]["openai/gpt-6-sol"]["agentRuntime"]["id"] = "openclaw"
        with self.assertRaisesRegex(ValueError, "Codex model alias"):
            verifier.codex_subscription_policy(config, listing)
        config = fixture()
        verifier.owner_policy(config, "12345")
        config["agents"]["entries"]["main"]["model"]["fallbacks"].append("openrouter/other")
        with self.assertRaisesRegex(ValueError, "agent routing overrides"):
            verifier.owner_policy(config, "12345")

    def test_claude_route_uses_native_login_without_session_discovery(self):
        config = {
            "auth": {"profiles": {"openai:owner": {"provider": "openai", "mode": "oauth"}},
                     "order": {"openai": ["openai:owner"]}},
            "agents": {"defaults": {"models": {verifier.OPUS_MODEL: {
                "alias": "opus", "agentRuntime": {"id": "claude-cli"}}}}},
            "plugins": {"allow": ["codex", "anthropic"],
                        "entries": {"anthropic": {"enabled": True,
                                                  "config": {"sessionCatalog": {"enabled": False}}}}},
        }
        verifier.claude_subscription_policy(config, {"profiles": []})
        changes = [
            ("auth.profiles.anthropic:default", {"provider": "anthropic", "mode": "api_key"}),
            ("auth.profiles.anthropic:token", {"provider": "anthropic", "mode": "token"}),
            ("auth.order.anthropic", ["anthropic:default"]),
            ("models", {"providers": {"anthropic": {"baseUrl": "https://api.anthropic.com"}}}),
            ("agents.defaults.models." + verifier.OPUS_MODEL, {"alias": "opus"}),
            ("agents.defaults.models." + verifier.OPUS_MODEL,
             {"alias": "opus", "agentRuntime": {"id": "openclaw"}}),
            ("agents.defaults.models." + verifier.OPUS_MODEL,
             {"alias": "codex", "agentRuntime": {"id": "claude-cli"}}),
            ("plugins.allow", ["codex"]),
            ("plugins.entries.anthropic.enabled", False),
            ("plugins.entries.anthropic.config", {}),
            ("plugins.entries.anthropic.config.sessionCatalog.enabled", True),
        ]
        for path, value in changes:
            with self.subTest(path=path, value=value):
                bad = copy.deepcopy(config)
                keys = path.split(".", 3) if path.startswith("agents.defaults.models.") else path.split(".")
                parent = bad
                for key in keys[:-1]:
                    parent = parent[key]
                parent[keys[-1]] = value
                with self.assertRaises(ValueError):
                    verifier.claude_subscription_policy(bad, {"profiles": []})
        with self.assertRaisesRegex(ValueError, "stored Anthropic credential"):
            verifier.claude_subscription_policy(
                config, {"profiles": [{"id": "anthropic:default", "type": "api_key"}]})

    def test_claude_executable_must_be_first_on_service_path_and_from_cask(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cask = root / "Caskroom/claude-code@latest/2.1.282"
            cask.mkdir(parents=True)
            (cask / "claude").write_text("#!/bin/sh\n")
            (cask / "claude").chmod(0o755)
            homebrew_bin, early_bin = root / "homebrew-bin", root / "early-bin"
            homebrew_bin.mkdir()
            early_bin.mkdir()
            approved = homebrew_bin / "claude"
            approved.symlink_to(cask / "claude")
            service_env = root / "gateway.env"

            def check(path_value):
                service_env.write_text(f"export HOME='{temp}'\nexport PATH='{path_value}'\n")
                service_env.chmod(0o600)
                verifier.claude_cli_policy(service_env, approved, root / "Caskroom/claude-code@latest")

            check(f"{early_bin}:{homebrew_bin}:/usr/bin")
            (early_bin / "claude").write_text("#!/bin/sh\n")
            (early_bin / "claude").chmod(0o755)
            with self.assertRaisesRegex(ValueError, "unreviewed claude"):
                check(f"{early_bin}:{homebrew_bin}:/usr/bin")
            with self.assertRaisesRegex(ValueError, "unreviewed claude"):
                check("/usr/bin:/bin")
            approved.unlink()
            approved.symlink_to(early_bin / "claude")
            with self.assertRaisesRegex(ValueError, "not the Homebrew cask"):
                check(f"{homebrew_bin}:/usr/bin")
            service_env.write_text("export PATH=\"$PATH:/opt/homebrew/bin\"\n")
            with self.assertRaisesRegex(ValueError, "plain quoted"):
                verifier.claude_cli_policy(service_env, approved, root / "Caskroom/claude-code@latest")
            service_env.write_text("export HOME='/tmp'\n")
            with self.assertRaisesRegex(ValueError, "PATH missing"):
                verifier.claude_cli_policy(service_env, approved, root / "Caskroom/claude-code@latest")

    def test_automatic_memory_keeps_transcripts_out_and_bounds_promotion(self):
        config = json.loads(Path(__file__).with_name("automatic-memory.patch.json").read_text())
        verifier.automatic_memory_policy(config)
        mutations = [
            ("plugins.entries.memory-core.config.memoryPolicy.excludeSessions.chatTypes", ["group", "channel"]),
            ("plugins.entries.memory-core.config.dreaming.frequency", "* * * * *"),
            ("plugins.entries.memory-core.config.dreaming.model", "openrouter/z-ai/glm-5.3-flash"),
            ("plugins.entries.memory-core.config.dreaming.phases.deep.minScore", 0),
            ("plugins.entries.memory-core.config.dreaming.phases.deep.limit", 100),
            ("plugins.entries.memory-core.config.dreaming.phases.light.lookbackDays", 999),
            ("plugins.entries.memory-core.config.dreaming.phases.rem.limit", 999),
            ("agents.defaults.compaction.memoryFlush.enabled", False),
            ("agents.defaults.compaction.postCompactionSections", []),
        ]
        for path, value in mutations:
            with self.subTest(path=path):
                bad = copy.deepcopy(config)
                parts = path.split(".")
                parent = bad
                for part in parts[:-1]:
                    parent = parent[part]
                parent[parts[-1]] = value
                with self.assertRaises(ValueError):
                    verifier.automatic_memory_policy(bad)

    def test_owner_host_policy(self):
        verifier.owner_policy(fixture(), "12345")

    def test_reject_authority_expansion_and_obsolete_policy(self):
        changes = [
            ("channels.telegram.allowFrom", ["*"]),
            ("channels.telegram.allowFrom", ["12345", "67890"]),
            ("channels.telegram.groupAllowFrom", ["*"]),
            ("channels.telegram.dmPolicy", "pairing"),
            ("channels.telegram.groups", {}),
            ("channels.telegram.groups", {"*": {"requireMention": False}}),
            ("channels.telegram.groups", {"*": {"requireMention": True, "groupPolicy": "open"}}),
            ("channels.telegram.accounts", {"second": {"dmPolicy": "open"}}),
            ("channels.telegram.direct", {"*": {"tools": {"profile": "full"}}}),
            ("channels.telegram.botToken", "PLAINTEXT-TEST-TOKEN"),
            ("channels.discord", {"enabled": True}),
            ("commands.ownerAllowFrom", ["*"]),
            ("agents.entries", {"other": {}}),
            ("agents.defaults.sandbox.mode", "non-main"),
            ("tools.allow", ["group:ui"]),
            ("tools.exec.ask", "always"),
            ("tools.exec.host", "sandbox"),
            ("gateway.bind", "lan"),
            ("plugins.slots.memory", "none"),
            ("memory.search.provider", "openai"),
            ("memory.search.sources", ["memory", "sessions"]),
            ("memory.search.extraPaths", ["/Users/owner"]),
        ]
        for path, value in changes:
            with self.subTest(path=path, value=value):
                config = copy.deepcopy(fixture())
                parts = path.split(".")
                parent = config
                for key in parts[:-1]:
                    parent = parent.setdefault(key, {})
                parent[parts[-1]] = value
                with self.assertRaises(ValueError):
                    verifier.owner_policy(config, "12345")

    def test_live_inventory_must_prove_exec_memory_and_browser(self):
        health = {"ok": True, "plugins": {"loaded": list(verifier.REQUIRED_PLUGINS)},
                  "channels": {"telegram": {"running": True, "connected": True,
                                             "lifecycle": "ready", "mode": "polling"}}}
        effective = {"agentId": "main", "groups": [{"tools": [{"id": x} for x in verifier.REQUIRED_TOOLS]}]}
        verifier.live_policy(health, effective)
        for tool in verifier.REQUIRED_TOOLS:
            with self.subTest(missing_tool=tool):
                bad = copy.deepcopy(effective)
                bad["groups"][0]["tools"] = [t for t in bad["groups"][0]["tools"] if t["id"] != tool]
                with self.assertRaises(ValueError):
                    verifier.live_policy(health, bad)
        bad = copy.deepcopy(health)
        bad["channels"]["telegram"]["connected"] = False
        with self.assertRaises(ValueError):
            verifier.live_policy(bad, effective)
        bad = copy.deepcopy(health)
        bad["plugins"]["errors"] = [{"plugin": "memory-core"}]
        with self.assertRaises(ValueError):
            verifier.live_policy(bad, effective)

    def test_known_browser_inventory_gap_is_narrow(self):
        health = {"ok": True, "plugins": {"loaded": list(verifier.REQUIRED_PLUGINS)},
                  "channels": {"telegram": {"running": True, "connected": True,
                                             "lifecycle": "ready", "mode": "polling"}}}
        tools = [{"id": x} for x in verifier.REQUIRED_TOOLS if x != "browser"]
        gap = {"agentId": "main", "groups": [{"tools": tools}],
               "notices": [{"id": "browser-filtered-by-profile"}]}
        # Without a reviewed release record the missing browser tool still fails.
        with self.assertRaisesRegex(ValueError, "browser"):
            verifier.live_policy(health, gap)
        self.assertNotIn("browser", verifier.live_policy(health, gap, tolerate_browser_inventory_gap=True))
        denied = copy.deepcopy(gap)
        denied["notices"] = [{"id": "browser-denied-by-policy"}]
        unloaded = copy.deepcopy(health)
        unloaded["plugins"]["loaded"] = [p for p in unloaded["plugins"]["loaded"] if p != "browser"]
        with self.assertRaisesRegex(ValueError, "browser"):
            verifier.live_policy(health, denied, tolerate_browser_inventory_gap=True)
        # An unloaded browser plugin already fails the required-plugin check.
        with self.assertRaisesRegex(ValueError, "required live plugins"):
            verifier.live_policy(unloaded, gap, tolerate_browser_inventory_gap=True)
        # The tolerance never excuses any other required tool.
        other = copy.deepcopy(gap)
        other["groups"][0]["tools"] = [t for t in tools if t["id"] != "exec"]
        with self.assertRaisesRegex(ValueError, "exec"):
            verifier.live_policy(health, other, tolerate_browser_inventory_gap=True)

    def test_host_approval_overlay_cannot_hide_behind_full_config(self):
        scope = {"host": {"requested": "gateway"}, "security": {"effective": "full"},
                 "ask": {"effective": "off"}}
        verifier.approvals_policy({"effectivePolicy": {"scopes": [scope]}})
        for key, value in (("ask", "always"), ("security", "deny")):
            bad = copy.deepcopy(scope)
            bad[key]["effective"] = value
            with self.assertRaises(ValueError):
                verifier.approvals_policy({"effectivePolicy": {"scopes": [bad]}})
        with self.assertRaises(ValueError):
            verifier.approvals_policy({})


class GlmThinkingPatchPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.state = root / "state"
        self.package = root / "package"
        (self.state / "operations").mkdir(parents=True)
        (self.package / "dist").mkdir(parents=True)
        self.receipt_path = self.state / "operations/glm-thinking-patch.json"
        self.receipt = {"schemaVersion": 1, "version": "2026.9.2", "files": []}
        for relative_path in sorted(verifier.GLM_THINKING_PATCH_PATHS):
            content = ("patched " + relative_path).encode()
            (self.package / relative_path).write_bytes(content)
            self.receipt["files"].append({
                "relativePath": relative_path,
                "beforeSha256": hashlib.sha256(b"original").hexdigest(),
                "afterSha256": hashlib.sha256(content).hexdigest(),
            })
        self.write_receipt(self.receipt)

    def write_receipt(self, receipt):
        self.receipt_path.write_text(json.dumps(receipt))
        self.receipt_path.chmod(0o600)

    def verify(self):
        verifier.glm_thinking_patch_policy(self.state, self.package)

    def test_accepts_private_receipt_for_both_reviewed_files(self):
        self.verify()

    def test_requires_private_owned_non_symlink_receipt(self):
        self.receipt_path.unlink()
        with self.assertRaisesRegex(ValueError, "private file"):
            self.verify()
        self.write_receipt(self.receipt)
        self.receipt_path.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "permissions"):
            self.verify()
        self.receipt_path.chmod(0o600)
        with patch.object(verifier.os, "getuid", return_value=self.receipt_path.stat().st_uid + 1):
            with self.assertRaisesRegex(ValueError, "permissions"):
                self.verify()
        actual = self.receipt_path.with_suffix(".actual")
        self.receipt_path.rename(actual)
        self.receipt_path.symlink_to(actual)
        with self.assertRaisesRegex(ValueError, "private file"):
            self.verify()

    def test_rejects_malformed_schema_version_file_list_and_hashes(self):
        self.receipt_path.write_text("{")
        with self.assertRaises(ValueError):
            self.verify()
        invalid = [None, [], {}, {**self.receipt, "schemaVersion": True},
                   {**self.receipt, "schemaVersion": 2}, {**self.receipt, "version": "2026.9.3"},
                   {**self.receipt, "files": None}, {**self.receipt, "files": [{}]},
                   {**self.receipt, "files": [None, None]},
                   {**self.receipt, "files": [self.receipt["files"][0]] * 2}]
        for field, value in (("beforeSha256", None), ("beforeSha256", "z" * 64),
                             ("afterSha256", "bad"), ("relativePath", [])):
            receipt = copy.deepcopy(self.receipt)
            receipt["files"][0][field] = value
            invalid.append(receipt)
        receipt = copy.deepcopy(self.receipt)
        receipt["files"][0]["beforeSha256"] = receipt["files"][0]["afterSha256"]
        invalid.append(receipt)
        for receipt in invalid:
            with self.subTest(receipt=receipt):
                self.write_receipt(receipt)
                with self.assertRaises(ValueError):
                    self.verify()

    def test_rejects_foreign_paths_before_reading_runtime_files(self):
        for path in ("dist/unreviewed.js", "../private.txt", "/tmp/private.txt",
                     "dist/../dist/thinking-policy-DI_bnHxv.js"):
            with self.subTest(path=path):
                receipt = copy.deepcopy(self.receipt)
                receipt["files"][0]["relativePath"] = path
                self.write_receipt(receipt)
                with patch.object(Path, "read_bytes", side_effect=AssertionError("unexpected file read")):
                    with self.assertRaisesRegex(ValueError, "reviewed runtime files"):
                        self.verify()

    def test_rejects_tampered_missing_and_redirected_runtime(self):
        for entry in self.receipt["files"]:
            target = self.package / entry["relativePath"]
            original = target.read_bytes()
            with self.subTest(path=entry["relativePath"]):
                target.write_bytes(b"upgrade changed runtime")
                with self.assertRaisesRegex(ValueError, "patch drifted"):
                    self.verify()
                target.unlink()
                with self.assertRaisesRegex(ValueError, "file missing or redirected"):
                    self.verify()
                foreign = self.state / target.name
                foreign.write_bytes(original)
                target.symlink_to(foreign)
                with self.assertRaisesRegex(ValueError, "file missing or redirected"):
                    self.verify()
                target.unlink()
                target.write_bytes(original)


if __name__ == "__main__":
    unittest.main()
