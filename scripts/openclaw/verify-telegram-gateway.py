#!/usr/bin/env python3
"""Observe the owner-only Mac/Telegram deployment without creating sessions or model turns."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sqlite3
import sys


REQUIRED_TOOLS = {"read", "write", "edit", "exec", "process", "memory_search", "memory_get", "browser", "pdf"}
REQUIRED_PLUGINS = {"browser", "device-pair", "openrouter", "telegram", "memory-core",
                    "openai", "codex", "anthropic"}
EXTRACTOR_CONTRACTS = {"web-readability": ("webContentExtractors", "readability"),
                       "document-extract": ("documentExtractors", "pdf")}
RUNTIME_PATCH_SPECS = json.loads(Path(__file__).with_name("runtime-patch-specs.json").read_text())
APPROVED_CODEX_COMMAND = str(Path.home() / ".local/share/openclaw-codex-runtimes/0.156.1/node_modules/.bin/codex")
GLM_THINKING_PATCH_PATHS = {"dist/thinking-policy-DI_bnHxv.js", "dist/stream-CGolKR6w.js"}
OPUS_MODEL = "anthropic/claude-opus-5-5"
SOL_MODEL = "openai/gpt-6-sol"
GLM_MODEL = "openrouter/z-ai/glm-5.3-flash"
# Owner-approved order: Claude subscription, then ChatGPT subscription, then paid OpenRouter GLM.
MAIN_MODEL_ROUTE = {"primary": OPUS_MODEL, "fallbacks": [SOL_MODEL, GLM_MODEL]}
DEFAULT_MODEL_ROUTE = {"primary": OPUS_MODEL, "fallbacks": [SOL_MODEL]}
# The Gateway resolves the plugin-owned `claude` command through its service PATH. Opus 5.5
# needs Claude Code 2.1.280 or newer, which the stable `claude-code` cask did not yet ship.
APPROVED_CLAUDE_COMMAND = Path("/opt/homebrew/bin/claude")
CLAUDE_CASK_ROOT = Path("/opt/homebrew/Caskroom/claude-code@latest")


def require(condition, label):
    if not condition:
        raise ValueError(label)


def workspace_delivery_policy(workspace, templates=None):
    templates = templates or Path(__file__).parent / "templates"
    policy = workspace / "AGENTS.md"
    private_file(policy)
    require(policy.read_bytes() == (templates / "TELEGRAM_AGENTS.md").read_bytes(),
            "workspace instructions differ from reviewed Mac policy")
    soul = workspace / "SOUL.md"
    private_file(soul)
    expected = (templates / "TELEGRAM_SOUL.md").read_text().strip()
    actual = soul.read_text()
    require(actual.count("<!-- BEGIN OPENCLAW TELEGRAM RESPONSE CONTRACT -->") == 1
            and actual.count("<!-- END OPENCLAW TELEGRAM RESPONSE CONTRACT -->") == 1
            and expected in actual, "turn-scoped response contract missing or drifted")


def owner_policy(config, owner):
    """Reject alternate ingress/agent policies rather than silently validating only defaults."""
    require(owner.isascii() and owner.isdigit() and int(owner) > 0, "invalid owner ID")
    gateway = config.get("gateway", {})
    require(gateway.get("bind") == "loopback", "gateway must bind loopback")
    require(gateway.get("auth", {}).get("mode") == "token", "gateway token authentication required")
    require(gateway.get("tailscale", {}).get("mode") == "off", "unexpected Tailscale ingress")
    require(config.get("commands", {}).get("ownerAllowFrom") == [f"telegram:{owner}"],
            "command owner mismatch")
    telegram = config.get("channels", {}).get("telegram", {})
    require(telegram.get("enabled") is True, "Telegram disabled")
    require(telegram.get("dmPolicy") == "allowlist" and telegram.get("allowFrom") == [owner],
            "DM owner allowlist mismatch")
    require(telegram.get("groupPolicy") == "allowlist" and telegram.get("groupAllowFrom") == [owner],
            "group sender allowlist mismatch")
    require(not telegram.get("accounts"), "account overrides require separate review")
    require(not telegram.get("direct"), "DM overrides require separate review")
    require(telegram.get("groups", {}).get("*") == {"requireMention": True},
            "explicit group mention requirement missing")
    for group in telegram.get("groups", {}).values():
        require(group == {"requireMention": True}, "group overrides require separate review")
    for key, channel in config.get("channels", {}).items():
        require(key == "telegram" or (isinstance(channel, dict) and channel.get("enabled") is False),
                "unexpected enabled channel")
    token = telegram.get("botToken")
    require(isinstance(token, dict) and token.get("source") == "store"
            and token.get("id") == "TELEGRAM_BOT_TOKEN", "Telegram token must remain a store reference")
    agents = config.get("agents", {})
    require(agents.get("entries", {}) == {"main": {"model": MAIN_MODEL_ROUTE}} and
            not agents.get("list") and not config.get("bindings"),
            "agent routing overrides require separate review")
    require(agents.get("defaults", {}).get("sandbox", {}).get("mode") == "off",
            "Mac agent must execute on the host")
    tools = config.get("tools", {})
    require(tools.get("profile") == "coding" and not tools.get("allow"),
            "coding profile must not be intersected with a second allowlist")
    require("group:ui" in tools.get("alsoAllow", []) or "browser" in tools.get("alsoAllow", []),
            "browser must be added to the coding profile")
    require("computer" in tools.get("deny", []), "computer exclusion drifted")
    execute = tools.get("exec", {})
    require(execute.get("host") == "gateway" and execute.get("mode") == "full"
            and "ask" not in execute and "security" not in execute, "host exec policy mismatch")
    require(tools.get("elevated", {}).get("enabled") is False, "elevated policy drifted")
    require(tools.get("fs", {}).get("workspaceOnly") is False, "Mac file access unavailable")
    require(config.get("session", {}).get("dmScope") == "per-channel-peer", "DM isolation drifted")
    plugins = config.get("plugins", {})
    require(plugins.get("slots", {}).get("memory") == "memory-core", "memory backend missing")
    require("memory-core" in plugins.get("allow", []) and
            plugins.get("entries", {}).get("memory-core", {}).get("enabled") is True,
            "memory-core must be enabled and allowed")
    memory = config.get("memory", {}).get("search", {})
    require(memory.get("enabled") is True and memory.get("provider") == "none"
            and memory.get("sources") == ["memory"]
            and memory.get("rememberAcrossConversations") is False
            and not memory.get("extraPaths") and not memory.get("multimodal", {}).get("enabled"),
            "local memory policy drifted")
    return telegram


def live_policy(health, effective):
    require(health.get("ok") is True, "gateway RPC unhealthy")
    plugins = health.get("plugins", {})
    require(REQUIRED_PLUGINS <= set(plugins.get("loaded", [])) and not plugins.get("errors")
            and not plugins.get("unavailable"), "required live plugins unavailable")
    telegram = health.get("channels", {}).get("telegram", {})
    require(telegram.get("running") is True and telegram.get("connected") is True
            and telegram.get("lifecycle") == "ready" and telegram.get("mode") == "polling"
            and not telegram.get("lastError"), "Telegram transport not ready")
    require(effective.get("agentId") == "main", "unexpected effective agent")
    ids = {tool["id"] for group in effective.get("groups", []) for tool in group.get("tools", [])}
    require(REQUIRED_TOOLS <= ids, "required effective tools missing: " + ", ".join(sorted(REQUIRED_TOOLS - ids)))
    require("computer" not in ids, "computer exclusion not effective")
    return sorted(ids)


def automatic_memory_policy(config):
    compaction = config.get("agents", {}).get("defaults", {}).get("compaction", {})
    require(compaction.get("memoryFlush", {}).get("enabled") is True
            and "Memory policy" in compaction.get("postCompactionSections", []),
            "bounded automatic capture or post-compaction instructions missing")
    memory = config.get("plugins", {}).get("entries", {}).get("memory-core", {}).get("config", {})
    require(set(memory.get("memoryPolicy", {}).get("excludeSessions", {}).get("chatTypes", [])) ==
            {"direct", "group", "channel"}, "raw transcript ingestion must remain excluded")
    dreaming = memory.get("dreaming", {})
    require(dreaming.get("enabled") is True and dreaming.get("frequency") == "0 3 * * *"
            and dreaming.get("timezone") == "Asia/Seoul"
            and dreaming.get("model") == "openai/gpt-5.6-sol",
            "daily local-time memory consolidation drifted")
    for phase, limit in (("light", 20), ("rem", 10)):
        settings = dreaming.get("phases", {}).get(phase, {})
        require(settings.get("enabled") is True and settings.get("lookbackDays") == 7
                and settings.get("limit") == limit, "daily note selection limits drifted")
    deep = dreaming.get("phases", {}).get("deep", {})
    require(deep.get("enabled") is True and deep.get("limit") == 5
            and deep.get("minScore") == 0.8 and deep.get("minRecallCount") == 3
            and deep.get("minUniqueQueries") == 3 and deep.get("maxAgeDays") == 90
            and deep.get("maxPromotedSnippetTokens") == 160
            and deep.get("maxPriorEntryLossFraction") == 0.1,
            "memory promotion limits drifted")


def approvals_policy(approvals):
    scopes = approvals.get("effectivePolicy", {}).get("scopes", [])
    require(scopes and all(scope.get("host", {}).get("requested") == "gateway"
                           and scope.get("security", {}).get("effective") == "full"
                           and scope.get("ask", {}).get("effective") == "off"
                           for scope in scopes), "host approvals override the requested exec policy")


def codex_subscription_policy(config, auth_listing=None):
    """Keep the user-approved Codex path on one ChatGPT credential without API fallback."""
    auth = config.get("auth", {})
    order = auth.get("order", {}).get("openai", [])
    profiles = {key: value for key, value in auth.get("profiles", {}).items()
                if value.get("provider") == "openai"}
    require(len(order) == 1 and set(profiles) == set(order)
            and all(profile.get("mode") == "oauth" for profile in profiles.values()),
            "Codex must use exactly one ChatGPT OAuth profile")
    defaults = config.get("agents", {}).get("defaults", {})
    require(defaults.get("model") == DEFAULT_MODEL_ROUTE,
            "default conversation must use subscription routes without a paid fallback")
    require(defaults.get("thinkingDefault") == "high", "default Codex reasoning effort drifted")
    require(defaults.get("modelSelectionScope") == "session",
            "chat model selection must default to the current session")
    for model, alias in (("openai/gpt-5.6-sol", "sol"), ("openai/gpt-6-astra", "astra"),
                         ("openai/gpt-6-sol", "codex")):
        require(defaults.get("models", {}).get(model) ==
                {"alias": alias, "agentRuntime": {"id": "codex"}},
                "Codex model alias or runtime drifted: " + model)
    require(defaults.get("modelPolicy", {}).get("allow") ==
            ["openrouter/*", "openai/gpt-5.6-sol", "openai/gpt-6-astra", "openai/gpt-6-sol",
             OPUS_MODEL],
            "model override policy drifted")
    require(defaults.get("pdfModel") == {"primary": "openai/gpt-5.6-sol", "fallbacks": []},
            "PDF subscription route drifted")
    require(defaults.get("subagents", {}).get("model") ==
            {"primary": "openai/gpt-5.6-sol", "fallbacks": []},
            "default delegated work must use Codex without a paid fallback")
    require(not config.get("models", {}).get("providers", {}).get("openai"),
            "explicit OpenAI provider override requires billing review")
    plugins = config.get("plugins", {})
    for name in ("codex", "openai", "web-readability", "document-extract"):
        require(name in plugins.get("allow", []) and
                plugins.get("entries", {}).get(name, {}).get("enabled") is True,
                "requested plugin unavailable: " + name)
    settings = plugins["entries"]["codex"].get("config", {})
    server = settings.get("appServer", {})
    expected_server = {"homeScope": "agent", "mode": "yolo",
                       "clearEnv": ["OPENAI_API_KEY", "CODEX_API_KEY"]}
    require(server == dict(expected_server, command=APPROVED_CODEX_COMMAND),
            "Codex auth environment or state scope drifted")
    for key in ("sessionCatalog", "supervision", "codexPlugins", "computerUse"):
        require(settings.get(key) == {"enabled": False}, "unreviewed Codex integration: " + key)
    if auth_listing is not None:
        stored = auth_listing.get("profiles", [])
        require(len(stored) == 1 and stored[0].get("id") == order[0]
                and stored[0].get("provider") == "openai" and stored[0].get("type") == "oauth",
                "stored Codex credential is not the selected ChatGPT OAuth profile")


def claude_subscription_policy(config, auth_listing=None):
    """Keep Opus on the host's own Claude Code login; OpenClaw must hold no Anthropic credential."""
    auth = config.get("auth", {})
    require(not auth.get("order", {}).get("anthropic")
            and not any(profile.get("provider") == "anthropic"
                        for profile in auth.get("profiles", {}).values()),
            "Claude must use the native Claude Code login, not an OpenClaw credential")
    require(not config.get("models", {}).get("providers", {}).get("anthropic"),
            "explicit Anthropic provider override requires billing review")
    require(config.get("agents", {}).get("defaults", {}).get("models", {}).get(OPUS_MODEL) ==
            {"alias": "opus", "agentRuntime": {"id": "claude-cli"}},
            "Claude model alias or runtime drifted")
    plugins = config.get("plugins", {})
    # Native session discovery would list the owner's unrelated Claude Code conversations.
    require("anthropic" in plugins.get("allow", []) and plugins.get("entries", {}).get("anthropic") ==
            {"enabled": True, "config": {"sessionCatalog": {"enabled": False}}},
            "Claude plugin must stay enabled without native session discovery")
    if auth_listing is not None:
        require(not auth_listing.get("profiles"),
                "stored Anthropic credential would bypass the Claude Code login")


def claude_cli_policy(service_env, approved=APPROVED_CLAUDE_COMMAND, cask_root=CLAUDE_CASK_ROOT):
    """Require the Gateway's first `claude` on PATH to be the Homebrew Claude Code cask."""
    private_file(service_env)
    lines = [line for line in service_env.read_text().splitlines() if line.startswith("export PATH=")]
    require(len(lines) == 1, "Gateway service PATH missing or ambiguous")
    match = re.fullmatch(r"export PATH='([^']*)'", lines[0])
    require(match is not None, "Gateway service PATH is not a plain quoted value")
    found = next((Path(entry) / "claude" for entry in match.group(1).split(":")
                  if entry and os.access(Path(entry) / "claude", os.X_OK)), None)
    require(found == approved, "Gateway resolves an unreviewed claude executable")
    require(found.resolve().is_relative_to(cask_root.resolve()) and found.resolve().is_file(),
            "Claude Code executable is not the Homebrew cask")


def run_json(cli, *arguments):
    result = subprocess.run([str(cli), *arguments], capture_output=True, text=True, timeout=40)
    require(result.returncode == 0, f"command failed: {' '.join(arguments[:2])}")
    # Do not echo provider diagnostics: they may contain private paths or credential material.
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        raise ValueError(f"invalid JSON: {' '.join(arguments[:2])}") from None


def glm_thinking_patch_policy(state, package):
    """Check the pinned local patch without trusting receipt-supplied filesystem paths."""
    receipt_path = state / "operations/glm-thinking-patch.json"
    private_file(receipt_path)
    receipt = json.loads(receipt_path.read_text())
    require(isinstance(receipt, dict) and type(receipt.get("schemaVersion")) is int
            and receipt["schemaVersion"] == 1 and receipt.get("version") in RUNTIME_PATCH_SPECS,
            "GLM thinking receipt missing or invalid")
    reviewed_paths = {item["path"] for item in RUNTIME_PATCH_SPECS[receipt["version"]]["thinking"]}
    files = receipt.get("files")
    require(isinstance(files, list) and len(files) == len(reviewed_paths)
            and all(isinstance(entry, dict) for entry in files),
            "GLM thinking receipt files missing or invalid")
    paths = [entry.get("relativePath") for entry in files]
    require(all(isinstance(path, str) for path in paths)
            and set(paths) == reviewed_paths,
            "GLM thinking receipt must name exactly the reviewed runtime files")
    for entry in files:
        for field in ("beforeSha256", "afterSha256"):
            digest = entry.get(field)
            require(isinstance(digest, str) and len(digest) == 64
                    and all(char in "0123456789abcdef" for char in digest),
                    "GLM thinking receipt hash invalid")
        require(entry["beforeSha256"] != entry["afterSha256"],
                "GLM thinking receipt does not describe a patch")
        target = package / entry["relativePath"]
        require(target.is_file() and not target.is_symlink()
                and target.resolve().is_relative_to(package.resolve()),
                "GLM thinking runtime file missing or redirected")
        require(hashlib.sha256(target.read_bytes()).hexdigest() == entry["afterSha256"],
                "GLM thinking patch drifted; requalify after upgrade")


def auth_reprobe_patch_policy(state, package, version):
    """Require the reviewed receipt and bytes only on releases carrying this repair."""
    spec = RUNTIME_PATCH_SPECS[version].get("authReprobe")
    if spec is None:
        return
    receipt_path = state / "operations/auth-reprobe-patch.json"
    private_file(receipt_path)
    receipt = json.loads(receipt_path.read_text())
    require(isinstance(receipt, dict) and receipt.get("version") == version
            and receipt.get("relativePath") == spec["path"]
            and receipt.get("beforeSha256") == spec["before"]
            and receipt.get("afterSha256") == spec["after"]
            and type(receipt.get("inferenceRequests")) is int and receipt["inferenceRequests"] == 0,
            "Codex auth reprobe receipt differs from reviewed patch")
    target = package / spec["path"]
    require(target.is_file() and not target.is_symlink()
            and target.resolve().is_relative_to(package.resolve()), "Codex auth reprobe runtime redirected")
    require(hashlib.sha256(target.read_bytes()).hexdigest() == spec["after"],
            "Codex auth reprobe runtime drifted; requalify after upgrade")


def runtime_policy(state, package, owner):
    version = json.loads((package / "package.json").read_text()).get("version")
    require(version in RUNTIME_PATCH_SPECS, "runtime version changed; requalify local patches")
    spec = RUNTIME_PATCH_SPECS[version]
    # Pinned reviewed bytes are authoritative, not self-reported receipt hashes.
    items = [spec["token"], spec["memory"], *spec["thinking"], *spec["delivery"],
             *spec.get("gpt6Sol", [])]
    if "authReprobe" in spec:
        items.append(spec["authReprobe"])
    for item in items:
        target = package / item["path"]
        require(target.is_file() and not target.is_symlink()
                and target.resolve().is_relative_to(package.resolve()), "runtime patch path redirected")
        require(hashlib.sha256(target.read_bytes()).hexdigest() == item["after"],
                "reviewed runtime patch bytes drifted; requalify after upgrade")
    policy_path = state / "operations/telegram-delivery-policy.json"
    private_file(policy_path)
    policy = json.loads(policy_path.read_text())
    require(policy == {"schemaVersion": 1, "ownerId": owner, "accountId": "default",
                       "allowAmbiguousReplay": True, "maxAttempts": 1008, "maxAgeMs": 604800000},
            "owner-authorized retry policy drifted")
    receipt_path = state / "operations/telegram-delivery-patch.json"
    private_file(receipt_path)
    receipt = json.loads(receipt_path.read_text())
    require(receipt.get("version") == version and len(receipt.get("files", [])) == 3,
            "delivery patch receipt missing or invalid")
    require({entry.get("name") for entry in receipt["files"]} ==
            {Path(entry["path"]).name for entry in spec["delivery"]}, "delivery receipt paths drifted")
    for file in receipt["files"]:
        name = file["name"]
        require(name == Path(name).name, "invalid runtime receipt filename")
        target = package / "dist" / name
        require(hashlib.sha256(target.read_bytes()).hexdigest() == file["afterSha256"],
                "delivery runtime patch drifted; requalify after upgrade")
    glm_receipt_path = state / "operations/glm-token-field-patch.json"
    private_file(glm_receipt_path)
    glm = json.loads(glm_receipt_path.read_text())
    require(glm.get("version") == version and glm.get("relativePath") == spec["token"]["path"],
            "GLM compatibility receipt missing or invalid")
    require(hashlib.sha256((package / glm["relativePath"]).read_bytes()).hexdigest() ==
            glm.get("afterSha256"), "GLM compatibility patch drifted; requalify after upgrade")
    admission_path = state / "operations/memory-admission-patch.json"
    private_file(admission_path)
    admission = json.loads(admission_path.read_text())
    require(admission.get("version") == version and admission.get("relativePath") == spec["memory"]["path"], "memory admission receipt missing or invalid")
    require(hashlib.sha256((package / admission["relativePath"]).read_bytes()).hexdigest() ==
            admission.get("afterSha256"), "memory admission patch drifted; requalify after upgrade")
    require(json.loads((state / "operations/glm-thinking-patch.json").read_text()).get("version") == version,
            "thinking receipt version mismatch")
    glm_thinking_patch_policy(state, package)
    auth_reprobe_patch_policy(state, package, version)
    if "gpt6Sol" in spec:
        sol_receipt_path = state / "operations/gpt6-sol-patch.json"
        private_file(sol_receipt_path)
        sol_receipt = json.loads(sol_receipt_path.read_text())
        expected = {item["path"]: (item["before"], item["after"]) for item in spec["gpt6Sol"]}
        files = sol_receipt.get("files")
        require(sol_receipt.get("version") == version and sol_receipt.get("installed") is True
                and sol_receipt.get("package") == str(package.resolve())
                and type(sol_receipt.get("modelRequests")) is int
                and sol_receipt["modelRequests"] == 0 and isinstance(files, list)
                and len(files) == len(expected), "GPT-6 Sol runtime receipt missing or invalid")
        actual = {item.get("path"): (item.get("beforeSha256"), item.get("afterSha256"))
                  for item in files if isinstance(item, dict)}
        require(actual == expected, "GPT-6 Sol runtime receipt differs from reviewed patch")


def private_file(path):
    require(path.is_file() and not path.is_symlink(), "missing or symlinked private file")
    stat = path.stat()
    require(stat.st_uid == os.getuid() and stat.st_mode & 0o077 == 0, "private file permissions drifted")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, default=Path.home() / ".openclaw-personaledge")
    parser.add_argument("--cli", type=Path, default=Path.home() /
                        ".local/openclaw-2026.8.1/.personal-edge-management/bin/openclaw")
    parser.add_argument("--owner-id")
    parser.add_argument("--package", type=Path, default=Path.home() /
                        ".local/openclaw-2026.8.1/lib/node_modules/openclaw")
    parser.add_argument("--skip-live", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    config_path = args.state_dir / "openclaw.json"
    private_file(config_path)
    config = json.loads(config_path.read_text())
    owners = config.get("commands", {}).get("ownerAllowFrom", [])
    require(len(owners) == 1 and owners[0].startswith("telegram:"), "exactly one Telegram owner required")
    owner = args.owner_id or owners[0].removeprefix("telegram:")
    owner_policy(config, owner)
    automatic_memory_policy(config)
    codex_subscription_policy(config)
    claude_subscription_policy(config)
    claude_cli_policy(args.state_dir / "service-env/ai.openclaw.personaledge.env")
    runtime_policy(args.state_dir, args.package, owner)
    require(config["agents"]["defaults"].get("reasoningDefault") == "off", "reasoning display must be off")
    workspace = Path(config["agents"]["defaults"]["workspace"])
    policy = workspace / "AGENTS.md"
    private_file(policy)
    private_file(workspace / "MEMORY_CONTROL.md")
    workspace_delivery_policy(workspace)
    receipt = {"ok": True, "observedAt": datetime.now(timezone.utc).isoformat(),
               "version": json.loads((args.package / "package.json").read_text())["version"], "policy": "owner-only-mac-telegram", "static": True, "live": False}
    if not args.skip_live:
        codex_subscription_policy(config, run_json(args.cli, "models", "auth", "list",
                                                   "--provider", "openai", "--json"))
        claude_subscription_policy(config, run_json(args.cli, "models", "auth", "list",
                                                    "--provider", "anthropic", "--json"))
        # These capability-only plugins load on demand, so Gateway health.loaded is not their
        # availability contract. Inspect actual imported providers and their declared contracts.
        for name, (contract, capability) in EXTRACTOR_CONTRACTS.items():
            inspection = run_json(args.cli, "plugins", "inspect", name, "--runtime", "--json")
            plugin = inspection.get("plugin", {})
            require(plugin.get("enabled") is True and plugin.get("status") == "loaded"
                    and plugin.get("imported") is True and not inspection.get("diagnostics")
                    and capability in plugin.get("contracts", {}).get(contract, []),
                    "extraction capability unavailable: " + name)
        receipt["extractors"] = sorted(EXTRACTOR_CONTRACTS)
        health = run_json(args.cli, "gateway", "call", "health", "--json")
        effective = run_json(args.cli, "gateway", "call", "tools.effective", "--json", "--params",
                             json.dumps({"agentId": "main", "sessionKey": f"agent:main:telegram:direct:{owner}"}))
        receipt["tools"] = live_policy(health, effective)
        approvals_policy(run_json(args.cli, "exec-policy", "show", "--json"))
        # CLI policy omits /exec session overrides. Inspect only the owner's metadata, not chat.
        database = args.state_dir / "agents/main/agent/openclaw-agent.sqlite"
        connection = sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)
        try:
            row = connection.execute("SELECT entry_json FROM session_nodes WHERE session_key=?",
                                     (f"agent:main:telegram:direct:{owner}",)).fetchone()
            require(row is not None, "owner Telegram session missing")
            entry = json.loads(row[0])
            for key, expected in (("execHost", "gateway"), ("execSecurity", "full"), ("execAsk", "off")):
                require(entry.get(key) in (None, expected), "owner session exec override drifted")
        finally:
            connection.close()
        port = config["gateway"]["port"]
        listeners = subprocess.run(["/usr/sbin/lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-F", "n"],
                                   capture_output=True, text=True, timeout=10)
        addresses = {line[1:] for line in listeners.stdout.splitlines() if line.startswith("n")}
        require(listeners.returncode == 0 and addresses and
                addresses <= {f"127.0.0.1:{port}", f"[::1]:{port}"}, "non-loopback or missing listener")
        receipt.update(live=True, gateway="ready", telegram="polling", plugins=health["plugins"]["loaded"])
    print(json.dumps(receipt, ensure_ascii=False) if args.json else
          f"OK owner-only Mac/Telegram policy; {'live RPC, plugins, tools, polling and loopback verified' if receipt['live'] else 'static checks only'}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, sqlite3.Error, subprocess.TimeoutExpired) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
