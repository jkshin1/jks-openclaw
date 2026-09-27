#!/usr/bin/env python3
"""Install the pinned, isolated Hermes worker; never import another agent's credentials."""

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys


COMMIT = "ead7e91dabf1e963796ec834b196984a2fa44ff4"
VERSION = "0.21.1"
DEFAULT_ROOT = Path.home() / ".local/share/openclaw-hermes-worker"
PYTHON = Path("/opt/homebrew/opt/python@3.11/bin/python3.11")
UV = Path("/opt/homebrew/bin/uv")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def private_directory(path):
    require(path.is_absolute(), "absolute installation path required")
    require(not any(p.is_symlink() for p in [path, *path.parents]), "redirected installation path")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(path.stat().st_uid == os.getuid(), "installation owner mismatch")
    path.chmod(0o700)


def clean_environment(root):
    # Deliberately do not inherit API keys, proxy endpoints, plugin hooks, or other agent state.
    env = {key: os.environ[key] for key in ("HOME", "TMPDIR", "LANG", "TERM") if key in os.environ}
    env.update({
        "PATH": "/opt/homebrew/opt/node/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin",
        "HERMES_HOME": str(root / "profile"),
        "CODEX_HOME": str(root / "profile/codex-disabled-import"),
        "HERMES_DISABLE_LAZY_INSTALLS": "1",
        "HERMES_SAFE_MODE": "1",
        "UV_CACHE_DIR": str(root / "cache/uv"),
        "PYTHONNOUSERSITE": "1",
    })
    return env


def run(command, *, env, log=None, timeout=900):
    process = subprocess.Popen([str(x) for x in command], env=env, text=True,
                               stdout=log or subprocess.PIPE, stderr=log or subprocess.PIPE,
                               start_new_session=True)
    try:
        stdout, _stderr = process.communicate(timeout=timeout)
    except BaseException:
        if process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.communicate(timeout=10)
        raise
    require(process.returncode == 0, "installation command failed; inspect private install.log")
    return stdout.strip() if stdout else ""


def install(root, source):
    source = source.resolve(strict=True)
    env = clean_environment(root)
    require(PYTHON.is_file() and UV.is_file(), "Python 3.11 and uv are required")
    require(run(["git", "-C", source, "rev-parse", "HEAD"], env=env) == COMMIT,
            "source commit is not the reviewed Hermes revision")
    require(not run(["git", "-C", source, "status", "--porcelain", "--untracked-files=no"], env=env),
            "reviewed Hermes source has modifications")
    require(not root.exists(), "installation already exists; refusing to replace it")
    private_directory(root)
    try:
        for relative in ("profile", "profile/codex-disabled-import", "runs", "cache", "bin"):
            private_directory(root / relative)
        # The empty Codex home prevents Hermes's automatic recovery from borrowing a refresh grant.
        runtime = root / "runtime"
        log_path = root / "install.log"
        with log_path.open("x") as log:
            log_path.chmod(0o600)
            run(["git", "clone", "--no-hardlinks", "--no-checkout", source, runtime], env=env, log=log)
            run(["git", "-C", runtime, "checkout", "--detach", COMMIT], env=env, log=log)
            run([UV, "sync", "--frozen", "--no-dev", "--no-default-groups", "--python", PYTHON,
                 "--project", runtime], env=env, log=log)
        require(run(["git", "-C", runtime, "rev-parse", "HEAD"], env=env) == COMMIT, "runtime revision drift")
    except BaseException:
        # This call created the root (it did not exist above). Keep the partial tree and its log for
        # inspection under a new name so a retry is not blocked; no credentials exist yet.
        failed = root.with_name(root.name + ".failed-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
        if root.is_dir() and not root.is_symlink() and not failed.exists():
            root.rename(failed)
        raise
    manifest = {
        "schemaVersion": 1, "installedAt": datetime.now(timezone.utc).isoformat(),
        "source": "https://github.com/NousResearch/hermes-agent", "commit": COMMIT,
        "version": VERSION, "python": str(runtime / ".venv/bin/python"),
        "lockSha256": hashlib.sha256((runtime / "uv.lock").read_bytes()).hexdigest(),
        "telegramGatewayInstalled": False, "credentialsImported": False,
    }
    (root / "installation.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (root / "installation.json").chmod(0o600)
    print(json.dumps({"installed": True, "root": str(root), "commit": COMMIT,
                      "authentication": "independent-login-required"}))


def authenticate(root):
    require((root / "installation.json").is_file(), "install the isolated worker first")
    manifest = json.loads((root / "installation.json").read_text())
    require(manifest.get("commit") == COMMIT, "unqualified runtime")
    require(not (root / "profile/auth.json").exists(),
            "independent credentials already exist; do not add a duplicate login")
    for relative in ("profile", "profile/codex-disabled-import"):
        private_directory(root / relative)
    require(not (root / "profile/codex-disabled-import/auth.json").exists(), "Codex credential import must stay disabled")
    # auth add uses a fresh provider device-code login, not the existing-login import wizard.
    command = [str(root / "runtime/.venv/bin/hermes"), "auth", "add", "openai-codex",
               "--type", "oauth", "--label", "openclaw-hermes-worker"]
    os.execve(command[0], command, clean_environment(root))


def restore_startup(profile, backup, moved, placeholder, marker, marker_existed):
    """Undo a failed configure in reverse order; never touch auth.json. The backup is removed only
    once every moved file is back, so a partial restore still leaves the originals recoverable."""
    if (profile / "config.yaml").is_file() and not (profile / "config.yaml").is_symlink():
        (profile / "config.yaml").unlink()
    if not marker_existed and marker.is_file() and not marker.is_symlink():
        marker.unlink()
    skills = profile / "skills"
    # configure always creates an empty skills directory; an original one is restored from the backup below.
    if skills.is_dir() and not skills.is_symlink() and not any(skills.iterdir()):
        skills.rmdir()
    if placeholder is not None and not placeholder.exists():
        shutil.move(str(backup / "codex-placeholder.toml"), str(placeholder))
    for name in reversed(moved):
        if not (profile / name).exists():
            shutil.move(str(backup / name), str(profile / name))
    if not any(backup.iterdir()):
        backup.rmdir()


def configure(root):
    """Seal a newly installed profile after independent login; retain seeded files privately."""
    require(not (root / "configuration.json").exists(), "profile already configured")
    manifest = json.loads((root / "installation.json").read_text())
    require(manifest.get("commit") == COMMIT, "unqualified runtime")
    profile = root / "profile"
    private_directory(profile)
    auth_path = profile / "auth.json"
    require(auth_path.is_file() and not auth_path.is_symlink(), "independent login required")
    before_auth = auth_path.read_bytes()
    spec = importlib.util.spec_from_file_location("hermes_report_worker", Path(__file__).with_name("hermes-report-worker.py"))
    worker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(worker)
    worker.validate_auth(json.loads(before_auth))
    backup = root / "startup-backup"
    require(not backup.exists(), "startup backup already exists")
    private_directory(backup)
    moved = []
    placeholder = profile / "codex-disabled-import/config.toml"
    placeholder_moved = False
    marker = profile / ".no-bundled-skills"
    marker_existed = marker.exists()
    config = profile / "config.yaml"
    try:
        for name in ("SOUL.md", "hooks", "skills", "config.yaml"):
            source = profile / name
            if source.exists():
                require(not source.is_symlink(), "redirected startup file")
                shutil.move(str(source), str(backup / name))
                moved.append(name)
        if placeholder.exists():
            require(placeholder.read_text() == "# No shared Codex authentication.\n", "unexpected Codex startup config")
            shutil.move(str(placeholder), str(backup / "codex-placeholder.toml"))
            placeholder_moved = True
        private_directory(profile / "skills")
        config.write_text(json.dumps(worker.REQUIRED_CONFIG, indent=2) + "\n")
        config.chmod(0o600)
        # Supported upstream marker prevents the auth CLI from reseeding bundled skills later.
        marker.write_text("Isolated operations-report worker; bundled skills are not used.\n")
        marker.chmod(0o600)
        require(auth_path.read_bytes() == before_auth, "authentication changed during configuration")
        worker.validate_profile(profile, clean_environment(root))
    except BaseException:
        restore_startup(profile, backup, moved, placeholder if placeholder_moved else None, marker, marker_existed)
        raise
    receipt = {"schemaVersion": 1, "configuredAt": datetime.now(timezone.utc).isoformat(),
               "commit": COMMIT, "configSha256": hashlib.sha256(config.read_bytes()).hexdigest(),
               "startupFilesPreserved": moved, "independentOAuth": True,
               "authPreserved": True, "telegramGatewayInstalled": False}
    path = root / "configuration.json"
    path.write_text(json.dumps(receipt, indent=2) + "\n")
    path.chmod(0o600)
    print(json.dumps({"configured": True, "independentOAuth": True, "authPreserved": True}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("install", "auth", "configure"))
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--source", type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    require(args.root.is_absolute(), "absolute root required")
    if args.command == "install":
        require(args.source is not None, "--source is required")
        install(args.root, args.source)
    elif args.command == "auth":
        authenticate(args.root)
    else:
        configure(args.root)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Hermes setup failed: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
