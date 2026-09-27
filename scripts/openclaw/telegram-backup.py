#!/usr/bin/env python3
"""Private Telegram backup and offline restore rehearsal; never activate restored state.

The installed CLI owns live SQLite snapshots. This wrapper adds runtime recovery files,
content hashes, logical database checks and an independently verifiable local receipt.
"""

import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import sqlite3
import stat
import subprocess
import sys
import tarfile
import uuid


USER_ROOT = Path.home()
DEFAULT_STATE = USER_ROOT / ".openclaw-personaledge"
DEFAULT_PACKAGE = USER_ROOT / ".local/openclaw-2026.8.1/lib/node_modules/openclaw"
DEFAULT_CLI = USER_ROOT / ".local/openclaw-2026.8.1/.personal-edge-management/bin/openclaw"
DEFAULT_BACKUPS = USER_ROOT / "Library/Application Support/PersonalEdge/OpenClawBackups/telegram"
PATCH_RECEIPTS = ("telegram-delivery-patch.json", "glm-token-field-patch.json", "memory-admission-patch.json")
RUNTIME_PATCH_SPECS = json.loads(Path(__file__).with_name("runtime-patch-specs.json").read_text())
GLOBAL_TABLES = {"schema_meta", "config_machine_state", "secret_store_entries", "cron_jobs",
                 "delivery_queue_entries", "task_runs"}
AGENT_TABLES = {"schema_meta", "session_nodes", "transcript_events", "auth_profile_store",
                "memory_index_chunks", "memory_index_sources"}
MAX_MEMBERS = 500000
MAX_EXPANDED_BYTES = 30 * 1024 ** 3


def require(condition, message):
    if not condition:
        raise ValueError(message)


def now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def stream_digest(stream):
    value = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        value.update(chunk)
    return value.hexdigest()


def digest(path):
    with path.open("rb") as stream:
        return stream_digest(stream)


def safe_path(path):
    """Reject symlink ancestors as well as lexical traversal before a write."""
    path = Path(path)
    require(path.is_absolute() and ".." not in path.parts and path != Path("/"),
            "path must be absolute, normalized and below root")
    require(not any(ord(char) < 32 for char in str(path)), "control character in path")
    for ancestor in (path, *path.parents):
        require(not ancestor.is_symlink(), "symlinked filesystem path refused")
    return path


def private_file(path):
    safe_path(path)
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
            and info.st_mode & 0o077 == 0, "backup file must be private and owner-controlled")


def private_directory(path, *, fresh=False):
    path = safe_path(path)
    if fresh:
        require(not path.exists(), "restore/bundle target must be a NEW directory")
    elif path.exists():
        require(path.is_dir() and path.stat().st_uid == os.getuid()
                and path.stat().st_mode & 0o077 == 0, "existing backup directory must be private")
    path.mkdir(parents=True, mode=0o700, exist_ok=not fresh)
    os.chmod(path, 0o700)
    return path


def write_json(path, data, *, replace=False):
    safe_path(path)
    encoded = (json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    with temporary.open("xb") as stream:
        os.chmod(temporary, 0o600)
        stream.write(encoded)
        stream.flush()
        os.fsync(stream.fileno())
    if replace:
        os.replace(temporary, path)
    else:
        try:
            os.link(temporary, path)
        finally:
            temporary.unlink()


def diagnostic_text(value):
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value if isinstance(value, str) else ""


def run_official(cli, *arguments, diagnostics_path=None):
    started = now()

    def retain(returncode, stdout, stderr, timed_out=False):
        if diagnostics_path is not None:
            private_directory(diagnostics_path.parent)
            write_json(diagnostics_path, {"schemaVersion": 1, "command": arguments[0],
                       "startedAt": started, "finishedAt": now(), "returncode": returncode,
                       "timedOut": timed_out, "stdout": diagnostic_text(stdout),
                       "stderr": diagnostic_text(stderr)})

    try:
        result = subprocess.run([str(cli), "backup", *arguments, "--json"], capture_output=True,
                                text=True, timeout=900)
    except subprocess.TimeoutExpired as error:
        # subprocess.run kills and waits for its direct child before raising. Retain partial
        # evidence, then stop immediately; never continue archive/supplement/receipt writes.
        retain(None, error.stdout, error.stderr, timed_out=True)
        raise
    retain(result.returncode, result.stdout, result.stderr)
    # CLI output can contain credential details; preserve it privately, never echo it.
    require(result.returncode == 0, "official backup command failed (output suppressed)")
    start = result.stdout.find("{")
    require(start >= 0, "official backup command returned no JSON")
    value = json.loads(result.stdout[start:])
    require(isinstance(value, dict), "official backup result must be an object")
    return value


def member_name(value):
    require(isinstance(value, str) and value and "\\" not in value
            and not any(ord(char) < 32 for char in value), "invalid archive member path")
    require(not value.startswith("/"), "absolute archive path refused")
    clean = value.rstrip("/")
    path = PurePosixPath(clean)
    require(clean == str(path) and all(part not in ("", ".", "..") for part in path.parts)
            and ":" not in path.parts[0], "archive traversal or noncanonical path refused")
    return str(path)


def archive_inventory(archive):
    """Read every payload byte, validate paths before extraction and hash regular files."""
    entries = {}
    portable = set()
    total = 0
    manifest = None
    with tarfile.open(archive, "r:gz") as source:
        for member in source:
            name = member_name(member.name)
            require(name not in entries and name.casefold() not in portable,
                    "duplicate or case-colliding archive member")
            portable.add(name.casefold())
            require(len(entries) < MAX_MEMBERS, "archive member limit exceeded")
            total += member.size
            require(total <= MAX_EXPANDED_BYTES and member.size >= 0, "archive size limit exceeded")
            require(member.isdir() or member.isfile() or member.issym() or member.islnk(),
                    "device/socket/special archive member refused")
            entry = {"type": "directory" if member.isdir() else "file", "size": member.size}
            if member.issym() or member.islnk():
                # Links are replayed only after all files. Reject external/escaping targets.
                require(not member.linkname.startswith("/") and "\\" not in member.linkname
                        and not any(ord(char) < 32 for char in member.linkname),
                        "unsafe archive link target")
                base = PurePosixPath(name).parent if member.issym() else PurePosixPath()
                parts = []
                for part in (base / member.linkname).parts:
                    if part == "..":
                        require(parts, "archive link escapes root")
                        parts.pop()
                    elif part != ".":
                        parts.append(part)
                require(parts and parts[0] == PurePosixPath(name).parts[0],
                        "archive link escapes declared root")
                entry = {"type": "symlink" if member.issym() else "hardlink",
                         "target": "/".join(parts), "linkname": member.linkname}
            elif member.isfile():
                stream = source.extractfile(member)
                require(stream is not None, "unreadable archive member")
                if len(PurePosixPath(name).parts) == 2 and name.endswith("/manifest.json"):
                    require(member.size <= 2 * 1024 * 1024 and manifest is None,
                            "invalid archive manifest count/size")
                    content = stream.read()
                    manifest = json.loads(content)
                    entry["sha256"] = hashlib.sha256(content).hexdigest()
                else:
                    entry["sha256"] = stream_digest(stream)
            entries[name] = entry
    require(isinstance(manifest, dict) and manifest.get("schemaVersion") == 1,
            "unsupported or missing archive manifest")
    root = member_name(manifest.get("archiveRoot"))
    require("/" not in root and all(name == root or name.startswith(root + "/") for name in entries),
            "archive entries escape manifest root")
    for name, entry in entries.items():
        for parent in PurePosixPath(name).parents:
            if str(parent) in entries:
                require(entries[str(parent)]["type"] == "directory", "archive parent is a link/file")
        if entry["type"] in ("symlink", "hardlink"):
            target = entries.get(entry["target"])
            require(target is not None and target["type"] in ("file", "directory"),
                    "archive link must directly name an included regular file/directory")
            require(entry["type"] != "hardlink" or target["type"] == "file",
                    "hardlink target must be a file")
    return {"entries": entries, "manifest": manifest, "expandedBytes": total}


def extract_offline(archive, target, inventory):
    private_directory(target, fresh=True)
    entries = inventory["entries"]
    with tarfile.open(archive, "r:gz") as source:
        for member in source:
            name = member_name(member.name)
            entry = entries[name]
            destination = target / name
            if entry["type"] == "directory":
                destination.mkdir(parents=True, exist_ok=True, mode=0o700)
            elif entry["type"] == "file":
                destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                with destination.open("xb") as stream:
                    os.chmod(destination, 0o600)
                    shutil.copyfileobj(source.extractfile(member), stream)
                require(digest(destination) == entry["sha256"], "restored file checksum mismatch")
    for name, entry in entries.items():
        if entry["type"] in ("symlink", "hardlink"):
            destination = target / name
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            if entry["type"] == "symlink":
                destination.symlink_to(entry["linkname"])
            else:
                os.link(target / entry["target"], destination)


def database_observation(path, required_tables=()):
    # immutable=1 is safe only for the isolated archive snapshot, never a live SQLite DB.
    with closing(sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True)) as database:
        database.execute("PRAGMA query_only=ON")
        require(database.execute("PRAGMA integrity_check").fetchall() == [("ok",)],
                "restored SQLite integrity failure")
        schema = database.execute("SELECT type,name,tbl_name,sql FROM sqlite_schema "
                                  "ORDER BY type,name").fetchall()
        tables = {row[1] for row in schema if row[0] == "table"}
        require(set(required_tables) <= tables, "restored SQLite required schema is missing")
        counts = {}
        for table in sorted(tables):
            quoted = '"' + table.replace('"', '""') + '"'
            counts[table] = database.execute("SELECT count(*) FROM " + quoted).fetchone()[0]
        return {"integrity": "ok", "userVersion": database.execute("PRAGMA user_version").fetchone()[0],
                "schemaSha256": hashlib.sha256(json.dumps(schema).encode()).hexdigest(),
                "tableCounts": counts}


def validate_restored(target, inventory, state):
    manifest = inventory["manifest"]
    require(manifest.get("paths", {}).get("stateDir") == str(state),
            "archive was created for a different state directory")
    assets = [asset for asset in manifest.get("assets", []) if asset.get("kind") == "state"]
    require(len(assets) == 1 and assets[0].get("sourcePath") == str(state),
            "archive must contain the full configured state asset")
    state_root = target / member_name(assets[0]["archivePath"])
    required_files = ["openclaw.json", "state/openclaw.sqlite", "agents/main/agent/openclaw-agent.sqlite",
                      "workspace/AGENTS.md", "workspace/MEMORY_CONTROL.md", "workspace/MEMORY.md"]
    for name in required_files:
        require((state_root / name).is_file() and not (state_root / name).is_symlink(),
                "required state/config/memory control file missing from backup")
    config = json.loads((state_root / "openclaw.json").read_text())
    token = config.get("channels", {}).get("telegram", {}).get("botToken", {})
    require(isinstance(token, dict) and token.get("source") == "store",
            "restored Telegram config lost its secret-store reference")
    observations = {}
    for path in target.rglob("*.sqlite"):
        if path.is_symlink() or not path.is_file():
            continue
        relative = path.relative_to(target).as_posix()
        required = GLOBAL_TABLES if path == state_root / "state/openclaw.sqlite" else (
            AGENT_TABLES if path == state_root / "agents/main/agent/openclaw-agent.sqlite" else ())
        observations[relative] = database_observation(path, required)
    require(len(observations) >= 2, "both global and agent database snapshots are required")
    return observations


def copy_recovery_file(source, destination):
    safe_path(source)
    require(source.is_file(), "required recovery file is missing")
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with source.open("rb") as reader, destination.open("xb") as writer:
        os.chmod(destination, 0o600)
        shutil.copyfileobj(reader, writer)
    return {"sourcePath": str(source), "sha256": digest(destination), "size": destination.stat().st_size,
            "originalMode": stat.S_IMODE(source.stat().st_mode)}


def recovery_supplement(bundle, args):
    files = {}

    def add(source, relative):
        files[relative] = copy_recovery_file(source, bundle / relative)

    package_data = json.loads((args.package / "package.json").read_text())
    version = package_data["version"]
    require(version in RUNTIME_PATCH_SPECS, "unreviewed runtime release for backup recovery")
    auth_reprobe = RUNTIME_PATCH_SPECS[version].get("authReprobe")
    gpt6_sol = RUNTIME_PATCH_SPECS[version].get("gpt6Sol")
    # From 2026.9.6 GLM thinking is configuration, so only earlier releases carry its receipt.
    receipts = (PATCH_RECEIPTS
                + (("glm-thinking-patch.json",) if "thinking" in RUNTIME_PATCH_SPECS[version] else ())
                + (("auth-reprobe-patch.json",) if auth_reprobe is not None else ())
                + (("claude-cli-agent-patch.json",) if "claudeCliArgs" in RUNTIME_PATCH_SPECS[version] else ())
                + (("gpt6-sol-patch.json",) if gpt6_sol is not None else ()))
    add(args.package / "package.json", "recovery/runtime/package.json")
    add(args.cli, "recovery/management/openclaw")
    for name in ("ai.openclaw.personaledge.plist", "com.personaledge.openclaw-telegram-watchdog.plist"):
        add(args.launch_agents / name, "recovery/launchagents/" + name)
    for name in receipts:
        receipt_path = args.state_dir / "operations" / name
        private_file(receipt_path)
        receipt = json.loads(receipt_path.read_text())
        require(isinstance(receipt, dict), "invalid runtime patch receipt")
        require(receipt.get("version") == package_data["version"], "runtime patch version drifted")
        if name == "auth-reprobe-patch.json":
            require(receipt.get("relativePath") == auth_reprobe["path"]
                    and receipt.get("beforeSha256") == auth_reprobe["before"]
                    and receipt.get("afterSha256") == auth_reprobe["after"]
                    and type(receipt.get("inferenceRequests")) is int and receipt["inferenceRequests"] == 0,
                    "Codex auth reprobe receipt differs from reviewed patch")
        if name == "gpt6-sol-patch.json":
            expected_files = [{"path": entry["path"], "beforeSha256": entry["before"],
                               "afterSha256": entry["after"]} for entry in gpt6_sol]
            require(len(expected_files) == 4 and len({entry["path"] for entry in expected_files}) == 4
                    and receipt.get("installed") is True
                    and receipt.get("package") == str(args.package)
                    and type(receipt.get("modelRequests")) is int and receipt["modelRequests"] == 0
                    and receipt.get("files") == expected_files,
                    "GPT-6 Sol receipt differs from reviewed installed patch")
        add(args.state_dir / "operations" / name, "recovery/receipts/" + name)
        if name == "auth-reprobe-patch.json":
            patch_files = [receipt]
        elif name == "gpt6-sol-patch.json":
            patch_files = [{"relativePath": entry["path"], "afterSha256": entry["after"]}
                           for entry in gpt6_sol]
        else:
            patch_files = receipt.get("files", [receipt])
        for entry in patch_files:
            relative = entry.get("relativePath") or "dist/" + entry.get("name", "")
            relative = member_name(relative)
            source = args.package / relative
            require(source.resolve().is_relative_to(args.package.resolve()), "runtime patch path escaped package")
            require(digest(source) == entry.get("afterSha256"), "runtime patch hash drifted")
            key = "recovery/runtime/" + relative
            if key not in files:
                add(source, key)
                require(files[key]["sha256"] == entry["afterSha256"], "runtime patch changed during copy")
    scripts = Path(__file__).parent
    for source in sorted(scripts.glob("*")):
        if source.is_file() and source.suffix in (".py", ".sh", ".mjs", ".json"):
            add(source, "recovery/operator-scripts/" + source.name)
    for source in sorted((scripts / "templates").glob("*")):
        if source.is_file():
            add(source, "recovery/operator-scripts/templates/" + source.name)
    node = subprocess.run(["/opt/homebrew/opt/node/bin/node", "--version"], capture_output=True,
                          text=True, timeout=10)
    require(node.returncode == 0, "Node runtime version unavailable")
    metadata = {"runtimeVersion": package_data["version"], "nodeVersion": node.stdout.strip(),
                "platform": platform.platform(), "packagePath": str(args.package), "cliPath": str(args.cli),
                "profile": "personaledge", "statePath": str(args.state_dir),
                "runtimeRecovery": "Install the exact official package version and verify/reapply the saved patch bytes; "
                                   "review saved LaunchAgents and paths before manual activation.",
                "activationTested": False, "files": files}
    return metadata


def restore_recovery(bundle, target, recovery):
    for name, entry in recovery["files"].items():
        name = member_name(name)
        require(name.startswith("recovery/"), "recovery file escapes supplement root")
        source = bundle / name
        private_file(source)
        require(digest(source) == entry["sha256"], "runtime recovery file checksum mismatch")
        restored = copy_recovery_file(source, target / name)
        require(restored["sha256"] == entry["sha256"], "restored runtime recovery file checksum mismatch")


def check_target(target, state, package):
    safe_path(target)
    for live_root in (state, package, DEFAULT_CLI.parent.parent, USER_ROOT / ".local/share/openclaw-telegram-ops",
                      USER_ROOT / "Library/LaunchAgents"):
        require(not target.is_relative_to(live_root) and not live_root.is_relative_to(target),
                "restore target overlaps a live runtime/state/service root")
    require(not target.exists(), "restore target must be a NEW directory")


def create(args):
    safe_path(args.state_dir)
    bundle_path = args.output / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8])
    check_target(bundle_path, args.state_dir, args.package)
    private_directory(args.output)
    bundle = private_directory(bundle_path, fresh=True)
    archive = bundle / "state.tar.gz"
    official = run_official(args.cli, "create", "--output", str(archive), "--verify",
                            diagnostics_path=bundle / "diagnostics/create.json")
    require(Path(official.get("archivePath", "")) == archive, "official backup output path mismatch")
    private_file(archive)
    verification = run_official(args.cli, "verify", str(archive),
                                diagnostics_path=bundle / "diagnostics/verify.json")
    require(verification.get("ok") is True, "official archive verification failed")
    archive_hash = digest(archive)
    inventory = archive_inventory(archive)
    recovery = recovery_supplement(bundle, args)
    target = bundle / "rehearsal"
    check_target(target, args.state_dir, args.package)
    extract_offline(archive, target, inventory)
    databases = validate_restored(target, inventory, args.state_dir)
    restore_recovery(bundle, target, recovery)
    require(digest(archive) == archive_hash, "archive changed during verification")
    manifest = {"schemaVersion": 1, "createdAt": now(), "archiveSha256": archive_hash,
                "archiveName": archive.name, "inventory": inventory, "databases": databases,
                "recovery": recovery}
    write_json(bundle / "recovery-manifest.json", manifest)
    receipt = {"schemaVersion": 1, "status": "VERIFIED", "verifiedAt": now(),
               "archivePath": str(archive), "archiveSha256": archive_hash,
               "manifestSha256": digest(bundle / "recovery-manifest.json"),
               "rehearsalPath": str(target), "databasesVerified": len(databases),
               "fileCount": sum(entry["type"] == "file" for entry in inventory["entries"].values()),
               "runtimeVersion": recovery["runtimeVersion"], "productionActivated": False}
    write_json(bundle / "verification.json", receipt)
    private_directory(args.state_dir / "operations")
    write_json(args.state_dir / "operations/telegram-backup-latest.json", receipt, replace=True)
    return receipt


def rehearse(args):
    private_file(args.archive)
    manifest_path = args.archive.parent / "recovery-manifest.json"
    private_file(manifest_path)
    manifest = json.loads(manifest_path.read_text())
    receipt_path = args.archive.parent / "verification.json"
    private_file(receipt_path)
    receipt = json.loads(receipt_path.read_text())
    require(manifest.get("schemaVersion") == 1 and receipt.get("status") == "VERIFIED",
            "unverified recovery bundle")
    require(digest(manifest_path) == receipt.get("manifestSha256"), "recovery manifest checksum mismatch")
    require(digest(args.archive) == manifest.get("archiveSha256") == receipt.get("archiveSha256"),
            "archive checksum mismatch")
    for name, entry in manifest["recovery"]["files"].items():
        source = args.archive.parent / member_name(name)
        private_file(source)
        require(digest(source) == entry["sha256"], "runtime recovery file checksum mismatch")
    check_target(args.target, args.state_dir, args.package)
    diagnostics = args.archive.parent / "diagnostics" / ("rehearse-" + uuid.uuid4().hex)
    verification = run_official(args.cli, "verify", str(args.archive),
                                diagnostics_path=diagnostics / "verify.json")
    require(verification.get("ok") is True, "official archive verification failed")
    inventory = archive_inventory(args.archive)
    require(inventory == manifest["inventory"], "archive inventory mismatch")
    extract_offline(args.archive, args.target, inventory)
    databases = validate_restored(args.target, inventory, args.state_dir)
    require(databases == manifest["databases"], "restored database logical manifest mismatch")
    restore_recovery(args.archive.parent, args.target, manifest["recovery"])
    require(digest(args.archive) == manifest["archiveSha256"], "archive changed during restore")
    result = {"schemaVersion": 1, "status": "VERIFIED", "verifiedAt": now(),
              "archiveSha256": manifest["archiveSha256"], "rehearsalPath": str(args.target),
              "databasesVerified": len(databases), "productionActivated": False}
    write_json(args.target / "rehearsal-verification.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("create", "rehearse"))
    parser.add_argument("--state-dir", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--cli", type=Path, default=DEFAULT_CLI)
    parser.add_argument("--package", type=Path, default=DEFAULT_PACKAGE)
    parser.add_argument("--launch-agents", type=Path, default=USER_ROOT / "Library/LaunchAgents")
    parser.add_argument("--output", type=Path, default=DEFAULT_BACKUPS)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--target", type=Path)
    parser.add_argument("--rehearse", action="store_true", help="create always includes offline rehearsal")
    parser.add_argument("--apply", action="store_true", help="authorize local credential-bearing backup writes")
    args = parser.parse_args()
    require(args.apply, "--apply is required for private local backup/restore writes")
    os.umask(0o077)
    if args.command == "rehearse":
        require(args.archive is not None and args.target is not None, "--archive and --target are required")
    result = create(args) if args.command == "create" else rehearse(args)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, sqlite3.Error, tarfile.TarError, subprocess.TimeoutExpired) as error:
        # Error classes identify failure without printing data from credential-bearing archives.
        print("FAIL Telegram backup/rehearsal: " + type(error).__name__ + ": "
              + (str(error) if isinstance(error, ValueError) else "local backup validation failed"), file=sys.stderr)
        sys.exit(1)
