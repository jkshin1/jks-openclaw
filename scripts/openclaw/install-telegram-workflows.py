#!/usr/bin/env python3
"""Install the reviewed task, meeting and weekly briefing command bundle."""

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import py_compile
import shutil
import tempfile


FILES = ["telegram-task-status.py", "telegram-meeting.py", "telegram-briefing.py",
         "telegram-weekly-briefing.py", "telegram-productivity-acceptance.py"]


def safe_path(path, directory=False):
    path = Path(path)
    if ".." in path.parts:
        raise ValueError("parent traversal is not an installation path")
    path = path.absolute()
    if path == Path(path.anchor) or any(part.is_symlink() for part in [path, *path.parents]):
        raise ValueError("installation path must not be a root or contain symlinks")
    if path.exists() and (path.stat().st_uid != os.getuid() or
                          (directory and not path.is_dir()) or (not directory and not path.is_file())):
        raise ValueError("installation path has unexpected ownership or type")
    return path


def atomic_bytes(path, data):
    safe_path(path)
    fd, temporary = tempfile.mkstemp(prefix=".workflow-receipt-", dir=str(path.parent))
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        descriptor = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def json_bytes(value):
    return (json.dumps(value, indent=2) + "\n").encode()


@contextmanager
def install_lock(destination):
    path = safe_path(destination.parent / ("." + destination.name + ".install.lock"))
    descriptor = os.open(path, os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(descriptor, "w") as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError("another workflow installation is active") from None
        yield


def install(source, destination, state):
    source = safe_path(source, directory=True)
    destination = safe_path(destination, directory=True)
    state = safe_path(state, directory=True)
    if not source.is_dir():
        raise ValueError("workflow source directory missing")
    for protected in (source, state, Path.home()):
        if destination == protected or protected.is_relative_to(destination):
            raise ValueError("installation would replace a protected source/state/home directory")
    if destination.is_relative_to(source) or destination.is_relative_to(state):
        raise ValueError("installed bundle must be separate from source and runtime state")
    for name in FILES:
        path = safe_path(source / name)
        if not path.is_file() or path.stat().st_size > 2 * 1024 * 1024:
            raise ValueError("workflow source file missing or oversized")
    safe_path(destination.parent, directory=True)
    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    operations = safe_path(state / "operations", directory=True)
    operations.mkdir(mode=0o700, parents=True, exist_ok=True)
    latest = safe_path(operations / "telegram-workflows-install-latest.json")
    with install_lock(destination):
        return install_locked(source, destination, operations, latest)


def install_locked(source, destination, operations, latest):
    old_latest = latest.read_bytes() if latest.exists() else None
    backup = Path(tempfile.mkdtemp(prefix="telegram-workflow-install-", dir=str(operations)))
    staged = Path(tempfile.mkdtemp(prefix=".workflow-stage-", dir=str(destination.parent)))
    moved = activated = latest_attempted = False
    candidate_receipt = None
    try:
        if old_latest is not None:
            atomic_bytes(backup / "previous-latest.json", old_latest)
        files = {}
        for name in FILES:
            data = safe_path(source / name).read_bytes()
            target = staged / name
            target.write_bytes(data)
            target.chmod(0o600)
            py_compile.compile(str(target), cfile=str(backup / (name + ".pyc")), doraise=True)
            files[name] = hashlib.sha256(data).hexdigest()
        if destination.exists():
            destination.rename(backup / "previous")
            moved = True
        staged.rename(destination)
        activated = True
        for name, expected in files.items():
            if hashlib.sha256(safe_path(destination / name).read_bytes()).hexdigest() != expected:
                raise ValueError("installed workflow bytes differ")
        receipt = {"schemaVersion": 1, "status": "INSTALLED", "observedAt": datetime.now(timezone.utc).isoformat(),
                   "installedDirectory": str(destination), "files": files,
                   "schedulesCreated": False, "messagesSent": False, "backup": str(backup)}
        candidate_receipt = json_bytes(receipt)
        atomic_bytes(backup / "receipt.json", candidate_receipt)
        latest_attempted = True
        atomic_bytes(latest, candidate_receipt)
        return receipt
    except Exception as error:
        rollback_errors = []
        try:
            if activated:
                destination.rename(backup / "failed-candidate")
            if moved:
                (backup / "previous").rename(destination)
        except OSError as rollback_error:
            rollback_errors.append(type(rollback_error).__name__)
        if latest_attempted:
            try:
                current = latest.read_bytes() if latest.exists() else None
                if current not in (None, old_latest, candidate_receipt):
                    raise ValueError("latest receipt changed concurrently")
                if old_latest is not None:
                    atomic_bytes(latest, old_latest)
                elif current is not None:
                    latest.unlink()
            except (ValueError, OSError) as rollback_error:
                rollback_errors.append(type(rollback_error).__name__)
        failure = {"schemaVersion": 1, "status": "ROLLBACK_FAILED" if rollback_errors else
                   "ROLLED_BACK" if activated or moved else "NOT_APPLIED",
                   "observedAt": datetime.now(timezone.utc).isoformat(), "errorType": type(error).__name__,
                   "rollbackErrors": rollback_errors, "backup": str(backup),
                   "schedulesCreated": False, "messagesSent": False}
        atomic_bytes(backup / "receipt.json", json_bytes(failure))
        raise ValueError("workflow installation failed; " +
                         ("rollback needs inspection" if rollback_errors else "previous installation preserved") +
                         "; private receipt: " + str(backup / "receipt.json")) from None
    finally:
        if staged.exists() and not staged.is_symlink():
            shutil.rmtree(staged)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--state-dir", type=Path, default=Path.home() / ".openclaw-personaledge")
    parser.add_argument("--installed-dir", type=Path,
                        default=Path.home() / ".local/share/openclaw-telegram-workflows")
    args = parser.parse_args()
    if not args.apply:
        print(json.dumps({"status": "PLAN", "files": FILES}))
        return
    os.umask(0o077)
    print(json.dumps(install(Path(__file__).resolve().parent, args.installed_dir, args.state_dir)))


if __name__ == "__main__":
    main()
