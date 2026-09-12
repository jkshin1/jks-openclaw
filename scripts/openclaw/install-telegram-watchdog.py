#!/usr/bin/env python3
"""Atomically install the Telegram operations observer with validation and rollback."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile


LABEL = "com.personaledge.openclaw-telegram-watchdog"
OLD_LABEL = "com.personaledge.openclaw-watchdog"
FILES = ["verify-telegram-gateway.py", "runtime-patch-specs.json", "telegram-watchdog.py", "telegram-ops-status.py",
         "templates/TELEGRAM_AGENTS.md", "templates/TELEGRAM_SOUL.md"]


def command(arguments, required=True):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=300)
    if required and result.returncode:
        raise ValueError("installation command failed: " + Path(arguments[0]).name)
    return result


def atomic_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(prefix="." + path.name, dir=str(path.parent))
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def disabled_flags(domain):
    result = command(["launchctl", "print-disabled", domain])
    return {label: bool(re.search(r'"' + re.escape(label) + r'"\s*=>\s*true', result.stdout))
            for label in (LABEL, OLD_LABEL)}


def strict_check(directory, args):
    result = command([sys.executable, str(directory / "verify-telegram-gateway.py"),
                      "--state-dir", str(args.state_dir), "--cli", str(args.cli),
                      "--package", str(args.package), "--json"])
    receipt = json.loads(result.stdout)
    if receipt.get("ok") is not True or receipt.get("live") is not True:
        raise ValueError("strict live verification did not pass")
    return {key: receipt.get(key) for key in ("ok", "observedAt", "version", "static", "live")}


def install(args):
    source = Path(__file__).resolve().parent
    strict_check(source, args)
    if not args.apply:
        print("PLAN install validated operations observer, owner alerts, and a 5-minute schedule")
        return
    os.umask(0o077)
    installed = args.installed_dir
    if installed.is_symlink():
        raise ValueError("installed operations directory must not be a symlink")
    installed.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    operations = args.state_dir / "operations"
    operations.mkdir(mode=0o700, parents=True, exist_ok=True)
    backup = Path(tempfile.mkdtemp(prefix="telegram-observer-install-", dir=str(operations)))
    staged = Path(tempfile.mkdtemp(prefix=".telegram-observer-stage-", dir=str(installed.parent)))
    plist_path = args.launchagents_dir / (LABEL + ".plist")
    old_plist_path = args.launchagents_dir / (OLD_LABEL + ".plist")
    domain = "gui/{}".format(os.getuid())
    flags = disabled_flags(domain)
    loaded = {label: command(["launchctl", "print", domain + "/" + label], required=False).returncode == 0
              for label in (LABEL, OLD_LABEL)}
    prior_plist = plist_path.read_bytes() if plist_path.exists() else None
    if prior_plist is not None:
        atomic_write(backup / "observer.plist", prior_plist)
    if old_plist_path.exists():
        atomic_write(backup / "archived-watchdog.plist", old_plist_path.read_bytes())
    if installed.exists():
        shutil.copytree(installed, backup / "installed")
    atomic_write(backup / "launch-state.json", json.dumps({"loaded": loaded, "disabled": flags}).encode())
    moved_old = backup / "previous-installed"
    swapped = False
    launch_touched = False
    try:
        for name in FILES:
            destination = staged / name
            destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            atomic_write(destination, (source / name).read_bytes())
        strict_check(staged, args)
        definition = {"Label": LABEL, "ProgramArguments": [sys.executable, str(installed / "telegram-watchdog.py"),
                      "--state-dir", str(args.state_dir), "--cli", str(args.cli), "--package", str(args.package)],
                      "RunAtLoad": True, "StartInterval": args.interval, "ProcessType": "Background", "Umask": 63,
                      "StandardOutPath": "/dev/null", "StandardErrorPath": "/dev/null"}
        # Unload only the observer; Gateway and Telegram polling remain running.
        launch_touched = True
        if loaded[LABEL]:
            command(["launchctl", "bootout", domain + "/" + LABEL])
        if installed.exists():
            os.replace(installed, moved_old)
        os.replace(staged, installed)
        swapped = True
        installed_receipt = strict_check(installed, args)
        # The installation smoke must not start an incident review: it is a health probe, and a
        # model call is not something an install should decide to spend. Scheduled runs still may.
        run = command([sys.executable, str(installed / "telegram-watchdog.py"), "--state-dir", str(args.state_dir),
                       "--cli", str(args.cli), "--package", str(args.package), "--no-notify",
                       "--no-hermes", "--json"], required=False)
        initial = json.loads(run.stdout)
        # Degraded operations are recorded separately from a failed Gateway validation.
        if initial.get("gatewayOk") is not True:
            raise ValueError("installed observer first gateway check failed")
        atomic_write(plist_path, plistlib.dumps(definition))
        command(["launchctl", "enable", domain + "/" + LABEL])
        command(["launchctl", "bootstrap", domain, str(plist_path)])
        command(["launchctl", "disable", domain + "/" + OLD_LABEL])
        if loaded[OLD_LABEL]:
            command(["launchctl", "bootout", domain + "/" + OLD_LABEL])
        receipt = {"schemaVersion": 1, "status": "INSTALLED", "installedAt": datetime.now(timezone.utc).isoformat(),
                   "intervalSeconds": args.interval, "firstInstalledVerification": installed_receipt,
                   "firstObserverRun": initial, "backupDirectory": str(backup),
                   "files": {name: hashlib.sha256((installed / name).read_bytes()).hexdigest() for name in FILES}}
        atomic_write(operations / "telegram-observer-install-latest.json", json.dumps(receipt, indent=2).encode())
        print(json.dumps({"ok": True, "status": "INSTALLED", "firstGatewayCheck": True,
                          "operationsHealthy": initial.get("ok"), "intervalSeconds": args.interval}))
    except Exception:
        rollback_errors = []
        try:
            if launch_touched:
                if command(["launchctl", "print", domain + "/" + LABEL], required=False).returncode == 0:
                    command(["launchctl", "bootout", domain + "/" + LABEL])
                if swapped and installed.exists():
                    os.replace(installed, backup / "rejected-installed")
                if moved_old.exists():
                    os.replace(moved_old, installed)
                if prior_plist is not None:
                    atomic_write(plist_path, prior_plist)
                elif plist_path.exists():
                    plist_path.unlink()
                for label in (LABEL, OLD_LABEL):
                    command(["launchctl", "disable" if flags[label] else "enable", domain + "/" + label])
                    path = plist_path if label == LABEL else old_plist_path
                    is_loaded = command(["launchctl", "print", domain + "/" + label], required=False).returncode == 0
                    if loaded[label] and not is_loaded and path.exists():
                        command(["launchctl", "bootstrap", domain, str(path)])
        except Exception:
            rollback_errors.append("launchagent-or-file-rollback-failed")
        atomic_write(backup / "install-failure.json", json.dumps({"status": "ROLLBACK_FAILED" if rollback_errors
                                                                  else "ROLLED_BACK", "errors": rollback_errors}).encode())
        raise ValueError("observer install failed; " + ("manual rollback required" if rollback_errors else "previous installation restored")) from None
    finally:
        if staged.exists():
            shutil.rmtree(staged)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--state-dir", type=Path, default=Path.home() / ".openclaw-personaledge")
    parser.add_argument("--installed-dir", type=Path, default=Path.home() / ".local/share/openclaw-telegram-ops")
    parser.add_argument("--launchagents-dir", type=Path, default=Path.home() / "Library/LaunchAgents")
    parser.add_argument("--cli", type=Path, default=Path.home() / ".local/openclaw-2026.8.1/.personal-edge-management/bin/openclaw")
    parser.add_argument("--package", type=Path, default=Path.home() / ".local/openclaw-2026.8.1/lib/node_modules/openclaw")
    parser.add_argument("--interval", type=int, default=300)
    args = parser.parse_args()
    if args.interval < 60:
        parser.error("interval must be at least 60 seconds")
    install(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.TimeoutExpired):
        print("FAIL observer installation; inspect the private installation rollback receipt", file=sys.stderr)
        raise SystemExit(1)
