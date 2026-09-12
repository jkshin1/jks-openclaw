#!/usr/bin/env python3
"""Install the bounded operations controller without starting any agent or service."""

import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import uuid


DEFAULT_ROOT = Path.home() / ".local/share/openclaw-hermes-worker"
DEFAULT_STATE = Path.home() / ".openclaw-personaledge"
INSTALL_FILES = (
    "hermes-operations.py", "hermes-ops-worker.py", "hermes-ops-patches.py",
    "hermes-ops-knowledge.py", "hermes-ops-changes.py", "hermes-procedure-pilot.py", "agent-pilot-fixtures.py",
    "telegram-ops-status.py", "telegram-briefing.py", "telegram-task-status.py",
    "install-hermes-operations.py",
)
PRESERVED_FILES = ("hermes-report-worker.py", "install-hermes-worker.py")
MAX_BYTES = 16 * 1024 * 1024
CONTRACT_START = b"<!-- hermes-operations-contract:start -->"
CONTRACT_END = b"<!-- hermes-operations-contract:end -->"


class InstallError(ValueError):
    pass


def require(value, code):
    if not value:
        raise InstallError(code)


def safe(path, exists=False):
    path = Path(path)
    require(path.is_absolute() and ".." not in path.parts, "ABSOLUTE_PATH_REQUIRED")
    require(not any(p.is_symlink() for p in (path, *path.parents)), "SYMLINK_REFUSED")
    require(not exists or path.exists(), "PATH_MISSING")
    if path.exists():
        require(path.stat().st_uid == os.getuid(), "OWNER_MISMATCH")
    return path


def content(path, private=False):
    path = safe(path, True)
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, "REGULAR_SINGLE_LINK_FILE_REQUIRED")
    require(info.st_size <= MAX_BYTES, "FILE_TOO_LARGE")
    require(not private or info.st_mode & 0o077 == 0, "PRIVATE_FILE_REQUIRED")
    return path.read_bytes()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode()


def merge_runbook(current, seed):
    """Update the managed evidence contract while preserving independently learned prose."""
    if CONTRACT_START not in seed:
        return current
    require(seed.count(CONTRACT_START) == seed.count(CONTRACT_END) == 1, "RUNBOOK_CONTRACT_INVALID")
    start, end = seed.index(CONTRACT_START), seed.index(CONTRACT_END) + len(CONTRACT_END)
    require(start < end - len(CONTRACT_END), "RUNBOOK_CONTRACT_INVALID")
    contract = seed[start:end]
    if CONTRACT_START in current or CONTRACT_END in current:
        require(current.count(CONTRACT_START) == current.count(CONTRACT_END) == 1, "RUNBOOK_CONTRACT_INVALID")
        first, last = current.index(CONTRACT_START), current.index(CONTRACT_END) + len(CONTRACT_END)
        require(first < last - len(CONTRACT_END), "RUNBOOK_CONTRACT_INVALID")
        return current[:first] + contract + current[last:]
    # This exact older sentence conflates repeated observations with unresolved issues.
    # Upgrade only its known wording, retaining all other operator-learned material.
    legacy = (b"A\n  finding that repeats across reviews is a persistent unaddressed issue, not a new observation, and\n"
              b"  deserves a different recommendation than a first sighting.")
    current = current.replace(legacy, b"Repetition records occurrence history; findingLifecycle determines whether an issue remains open.")
    return current.rstrip() + b"\n\n" + contract + b"\n"


def load_module(path):
    # Load only the caller-selected reviewed source. No Hermes runtime import or
    # auth CLI is necessary; the worker exposes a pure configuration contract.
    safe(path, True)
    name = "operations_installer_" + uuid.uuid4().hex
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    sys.modules[name] = value
    previous = sys.dont_write_bytecode
    try:
        sys.dont_write_bytecode = True
        spec.loader.exec_module(value)
    finally:
        sys.dont_write_bytecode = previous
    return value


def ensure_directory(path, created=None):
    path = safe(path)
    if path.exists():
        require(path.is_dir() and path.stat().st_mode & 0o077 == 0, "PRIVATE_DIRECTORY_REQUIRED")
        return
    if not path.parent.exists():
        ensure_directory(path.parent, created)
    path.mkdir(mode=0o700)
    if created is not None:
        created.append(path)


def atomic_write(path, data, mode=0o600):
    safe(path)
    descriptor, temporary = tempfile.mkstemp(prefix=".operations-install-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            os.fchmod(stream.fileno(), mode)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def unchanged(guards):
    return all(path.exists() and sha(content(path, private=True)) == expected
               for path, expected in guards.items())


def validate_contract(function, *args):
    try:
        return function(*args)
    except Exception as error:
        code = str(error)
        raise InstallError(code if re.fullmatch(r"[A-Z][A-Z0-9_]{1,100}", code)
                           else "WORKER_CONTRACT_VALIDATION_FAILED") from None


def rollback(entries, created):
    """Undo only bytes written by this transaction; retain conflicting edits."""
    conflicts = []
    for entry in reversed(entries):
        if not entry.get("attempted"):
            continue
        target = Path(entry["path"])
        try:
            current = sha(content(target)) if target.exists() else None
            current_mode = stat.S_IMODE(target.stat().st_mode) if target.exists() else None
            if current == entry["beforeSha256"] and current_mode == entry["beforeMode"]:
                continue
            require(current == entry["afterSha256"] and current_mode == 0o600, "ROLLBACK_EXTERNAL_CHANGE")
            if entry["backupPath"] is None:
                target.unlink()
            else:
                before = content(Path(entry["backupPath"]), private=True)
                require(sha(before) == entry["beforeSha256"], "ROLLBACK_BACKUP_CHANGED")
                atomic_write(target, before, entry["beforeMode"])
        except Exception:
            conflicts.append(str(target))
    for path in reversed(created):
        try:
            safe(path, True)
            path.rmdir()  # Never recursively remove state added by another process.
        except OSError:
            pass
    return conflicts


def install_locked(source, root, state):
    profile = root / "profile"
    for path in (source, root, profile, root / "bin", state, state / "workspace"):
        safe(path, True)
        require(path.is_dir(), "DIRECTORY_REQUIRED")
    for path in (root, profile, root / "bin"):
        require(path.stat().st_mode & 0o077 == 0, "PRIVATE_DIRECTORY_REQUIRED")

    payloads = {name: content(source / name) for name in INSTALL_FILES}
    source_guards = {source / name: sha(data) for name, data in payloads.items()}
    for name, data in payloads.items():
        compile(data, name, "exec")
    preserved = {}
    for name in PRESERVED_FILES:
        expected = sha(content(source / name))
        source_guards[source / name] = expected
        require(sha(content(root / "bin" / name, private=True)) == expected,
                "PRESERVED_DEPENDENCY_MISMATCH")
        preserved[root / "bin" / name] = expected

    parent_auth = profile / "auth.json"
    parent_config = profile / "config.yaml"
    state_config = state / "openclaw.json"
    # Authentication is validated in memory, never backed up or copied to the
    # child profile. Native token refresh belongs to the runtime, not installer.
    auth_bytes = content(parent_auth, private=True)
    report_worker = load_module(source / "hermes-report-worker.py")
    validate_contract(report_worker.validate_auth, json.loads(auth_bytes))
    guards = {**preserved, parent_auth: sha(auth_bytes),
              parent_config: sha(content(parent_config, private=True)),
              state_config: sha(content(state_config, private=True))}
    installer_worker = load_module(source / "install-hermes-worker.py")
    validate_contract(report_worker.validate_profile, profile, installer_worker.clean_environment(root))
    pool = json.loads(auth_bytes).get("credential_pool", {}).get(report_worker.PROVIDER, [])
    require(not json.loads(auth_bytes).get("providers", {}).get(report_worker.PROVIDER),
            "SHARED_OAUTH_SINGLETON_REFUSED")
    require(len(pool) == 1 and isinstance(pool[0].get("id"), str) and bool(pool[0]["id"]),
            "SHARED_OAUTH_POOL_REQUIRED")
    manifest_path = root / "installation.json"
    manifest = json.loads(content(manifest_path, private=True))
    require(manifest.get("commit") == installer_worker.COMMIT, "UNQUALIFIED_HERMES_RUNTIME")
    guards[manifest_path] = sha(content(manifest_path, private=True))
    worker = load_module(source / "hermes-ops-worker.py")
    child = safe(worker.profile_home(profile))
    require(child == profile / "profiles/operations", "OPERATIONS_PROFILE_PATH_MISMATCH")
    require(not (child / "auth.json").exists(), "CHILD_AUTH_REFUSED")
    codex_home = safe(child / "codex-disabled-import")
    require(not codex_home.exists() or (codex_home.is_dir() and not any(codex_home.iterdir())),
            "CODEX_IMPORT_DIRECTORY_NOT_EMPTY")
    require(isinstance(worker.REQUIRED_CONFIG, dict), "OPERATIONS_CONFIG_CONTRACT_INVALID")

    targets = {root / "bin" / name: data for name, data in payloads.items()}
    targets[child / "config.yaml"] = json_bytes(worker.REQUIRED_CONFIG)
    targets[child / ".no-bundled-skills"] = b"Bounded OpenClaw operations profile; no bundled skill import.\n"
    runbook = child / "skills/openclaw-ops-runbook/SKILL.md"
    seed = content(source / "templates/HERMES_OPS_RUNBOOK.md")
    source_guards[source / "templates/HERMES_OPS_RUNBOOK.md"] = sha(seed)
    # Native skill learning must survive a later installer update.
    targets[runbook] = merge_runbook(content(runbook, private=True), seed) if runbook.exists() else seed
    skill = state / "workspace/skills/hermes-operations/SKILL.md"
    targets[skill] = content(source / "templates/HERMES_OPERATIONS_SKILL.md")
    source_guards[source / "templates/HERMES_OPERATIONS_SKILL.md"] = sha(targets[skill])
    require(targets[skill].startswith(b"---\nname: hermes-operations\n"), "SKILL_NAME_MISMATCH")
    for target in targets:
        safe(target)
        if target.exists():
            content(target, private=True)
    install_manifest = root / "operations-install.json"
    if install_manifest.exists():
        content(install_manifest, private=True)

    identifier = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:10]
    backups = root / "operations-install-backups" / identifier
    ensure_directory(backups)
    # This is the requested scoped OpenClaw config backup; profile auth is absent.
    atomic_write(backups / "openclaw.json", content(state_config, private=True))
    entries, created = [], []
    plan_path = backups / "transaction.json"
    plan = {"schemaVersion": 1, "id": identifier, "status": "PREPARING",
            "root": str(root), "stateDir": str(state), "entries": entries,
            "createdDirectories": [], "openclawConfigBackup": str(backups / "openclaw.json")}
    try:
        for target, data in targets.items():
            ensure_directory(target.parent, created)
            before = content(target, private=True) if target.exists() else None
            backup = backups / (str(len(entries)) + ".before") if before is not None else None
            if backup is not None:
                atomic_write(backup, before)
            entries.append({"path": str(target), "beforeSha256": sha(before) if before is not None else None,
                            "beforeMode": stat.S_IMODE(target.stat().st_mode) if before is not None else None,
                            "backupPath": str(backup) if backup else None, "afterSha256": sha(data),
                            "attempted": False})
        ensure_directory(codex_home, created)
        before_manifest = content(install_manifest, private=True) if install_manifest.exists() else None
        manifest_backup = backups / "manifest.before" if before_manifest is not None else None
        if manifest_backup:
            atomic_write(manifest_backup, before_manifest)
        receipt = {"schemaVersion": 1, "installedAt": datetime.now(timezone.utc).isoformat(),
                   "id": identifier, "root": str(root), "profile": str(child),
                   "source": str(source), "backupPath": str(backups),
                   "sourceFiles": [{"path": str(path), "sha256": expected}
                                   for path, expected in source_guards.items()],
                   "files": [{"path": str(path), "sha256": sha(data)} for path, data in targets.items()],
                   "preservedFiles": [{"path": str(path), "sha256": expected}
                                      for path, expected in preserved.items()],
                   "skillPath": str(skill), "parentAuthenticationPreserved": True,
                   "parentConfigurationPreserved": True, "childAuthenticationCreated": False,
                   "openclawConfigurationPreserved": True, "gatewayRestarted": False,
                   "modelCalled": False, "telegramDelivered": False, "scheduleCreated": False}
        targets[install_manifest] = json_bytes(receipt)
        entries.append({"path": str(install_manifest),
                        "beforeSha256": sha(before_manifest) if before_manifest is not None else None,
                        "beforeMode": stat.S_IMODE(install_manifest.stat().st_mode) if before_manifest is not None else None,
                        "backupPath": str(manifest_backup) if manifest_backup else None,
                        "afterSha256": sha(targets[install_manifest]), "attempted": False})
        plan.update(status="ARMED", createdDirectories=[str(p) for p in created])
        atomic_write(plan_path, json_bytes(plan))
        require(unchanged(guards), "PROTECTED_STATE_CHANGED")
        require(all(sha(content(path)) == expected for path, expected in source_guards.items()), "SOURCE_CHANGED")
        for entry in entries:
            target = Path(entry["path"])
            before_sha = sha(content(target)) if target.exists() else None
            require(before_sha == entry["beforeSha256"], "TARGET_CHANGED_DURING_INSTALL")
            entry["attempted"] = True
            atomic_write(plan_path, json_bytes(plan))
            atomic_write(target, targets[target])
            require(content(target, private=True) == targets[target], "INSTALLED_BYTES_MISMATCH")
        require(unchanged(guards), "PROTECTED_STATE_CHANGED")
        require(all(sha(content(path)) == expected for path, expected in source_guards.items()), "SOURCE_CHANGED")
        require(not (child / "auth.json").exists() and not any(codex_home.iterdir()), "CHILD_AUTH_REFUSED")
        require(all(content(target, private=True) == data for target, data in targets.items()),
                "INSTALLED_BYTES_MISMATCH")
        require(validate_contract(worker.validate_profile, profile, installer_worker.clean_environment(root)) == child,
                "OPERATIONS_PROFILE_PATH_MISMATCH")
        validate_contract(worker.validate_config, json.loads(content(child / "config.yaml", private=True)))
        plan["status"] = "COMMITTED"
        atomic_write(plan_path, json_bytes(plan))
        return {"ok": True, "installed": True, "receiptPath": str(install_manifest),
                "backupPath": str(backups), "gatewayRestarted": False, "modelCalled": False,
                "telegramDelivered": False, "scheduleCreated": False}
    except BaseException:
        conflicts = rollback(entries, created)
        plan.update(status="ROLLBACK_CONFLICT" if conflicts else "ROLLED_BACK", conflicts=conflicts,
                    createdDirectories=[str(p) for p in created])
        atomic_write(plan_path, json_bytes(plan))
        if conflicts:
            raise InstallError("ROLLBACK_CONFLICT") from None
        raise


def install(source, root, state):
    source, root, state = safe(source, True), safe(root, True), safe(state, True)
    profile = safe(root / "profile", True)
    require(profile.is_dir() and profile.stat().st_mode & 0o077 == 0, "PRIVATE_DIRECTORY_REQUIRED")
    lock_path = safe(profile / ".pilot-worker.lock")
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
    with os.fdopen(os.open(lock_path, flags, 0o600), "a") as lock:
        require(os.fstat(lock.fileno()).st_nlink == 1, "LOCK_HARDLINK_REFUSED")
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise InstallError("WORKER_BUSY") from None
        return install_locked(source, root, state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--state-dir", type=Path, default=DEFAULT_STATE)
    args = parser.parse_args()
    os.umask(0o077)
    try:
        print(json.dumps(install(args.source, args.root, args.state_dir), ensure_ascii=False))
    except Exception as error:
        code = str(error) if isinstance(error, InstallError) else type(error).__name__
        print(json.dumps({"ok": False, "errorCode": code}))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
