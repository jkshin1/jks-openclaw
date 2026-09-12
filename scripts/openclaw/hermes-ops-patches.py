#!/usr/bin/env python3
"""Validate proposed replacements and test a private copy; never activate changes."""

import argparse
import ast
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import shutil
import signal
import subprocess
import sys
import tempfile
import time


MANIFEST = "snapshot-manifest.json"
MAX_FILE = 1024 * 1024
MAX_TOTAL = 8 * 1024 * 1024
MAX_LOG = 65536
SANDBOX = Path("/usr/bin/sandbox-exec")
PYTHON = Path("/Library/Developer/CommandLineTools/usr/bin/python3")
CODE_PATHS = {"scripts/openclaw/" + name for name in (
    "telegram-ops-status.py", "telegram-task-status.py", "telegram-briefing.py",
    "telegram-weekly-briefing.py")}
FIXED_TESTS = tuple("scripts/openclaw/" + name for name in (
    "test-telegram-operations.py", "test-telegram-task-status.py", "test-telegram-briefing.py",
    "test-telegram-weekly-briefing.py", "test-telegram-gateway.py"))
SOURCE_SUFFIXES = {".py", ".mjs", ".js", ".sh", ".json", ".md", ".toml", ".yaml", ".yml"}
PRIVATE_NAMES = {"auth.json", "credentials.json", "configuration.json", "installation.json",
                 "request.json", "receipt.json", "verification.json", "worker-receipt.json"}
PRIVATE_DIRS = {"reports", "runs", "state", "profile", "profiles", "cache"}


def require(condition, code):
    if not condition:
        raise ValueError(code)


def digest(content):
    return hashlib.sha256(content).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                      allow_nan=False).encode("utf-8")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def read_json(path):
    data = read_regular(path, limit=MAX_TOTAL)
    try:
        return json.loads(data, object_pairs_hook=unique_object,
                          parse_constant=lambda _value: require(False, "NONFINITE_JSON"))
    except (UnicodeError, json.JSONDecodeError):
        raise ValueError("INVALID_JSON") from None


def safe_path(path):
    path = Path(path)
    require(path.is_absolute() and ".." not in path.parts, "ABSOLUTE_NORMAL_PATH_REQUIRED")
    for part in (path, *path.parents):
        require(not part.is_symlink(), "SYMLINK_REFUSED")
    return path


def relative_path(value):
    require(isinstance(value, str) and value and len(value) < 512, "INVALID_SOURCE_PATH")
    path = PurePosixPath(value)
    require(not path.is_absolute() and str(path) == value and "\\" not in value
            and all(part not in (".", "..") and not part.startswith(".") for part in path.parts),
            "INVALID_SOURCE_PATH")
    require(all(ord(char) >= 32 for char in value), "INVALID_SOURCE_PATH")
    return path


def source_allowed(value):
    path = relative_path(value)
    if path.name in PRIVATE_NAMES or PRIVATE_DIRS.intersection(path.parts):
        return False
    if path.name == "openclaw.json" and value != "scripts/openclaw/templates/openclaw.json":
        return False
    return ((value.startswith("scripts/openclaw/") and path.suffix in SOURCE_SUFFIXES)
            or (len(path.parts) == 2 and path.parts[0] == "docs"
                and re.fullmatch(r"OPENCLAW[A-Z0-9_]*\.md", path.name) is not None))


def change_allowed(value):
    return value in CODE_PATHS or (value.startswith("docs/") and source_allowed(value))


def read_regular(path, limit=MAX_FILE):
    path = safe_path(path)
    info = path.stat()
    require(path.is_file() and info.st_size <= limit, "SOURCE_FILE_REQUIRED_OR_TOO_LARGE")
    data = path.read_bytes()
    require(len(data) <= limit, "SOURCE_FILE_TOO_LARGE")
    return data


def write_private(path, content):
    safe_path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with path.open("xb") as stream:
        os.chmod(path, 0o600)
        stream.write(content)


def fresh_directory(path):
    path = safe_path(path)
    require(not path.exists(), "FRESH_OUTPUT_REQUIRED")
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.mkdir(mode=0o700)
    return path


def separate(first, second):
    require(first != second and first not in second.parents and second not in first.parents,
            "OUTPUT_MUST_BE_SEPARATE")


def create_snapshot(source_root, output_dir, paths=None):
    source_root, output_dir = safe_path(source_root), safe_path(output_dir)
    separate(source_root, output_dir)
    if paths is None:
        paths = [str(path.relative_to(source_root)) for path in
                 (source_root / "scripts/openclaw").rglob("*") if path.is_file()
                 and not any(part.startswith(".") for part in path.relative_to(source_root).parts) and
                 source_allowed(str(path.relative_to(source_root)))]
        paths += [str(path.relative_to(source_root)) for path in (source_root / "docs").glob("OPENCLAW*.md")]
    require(isinstance(paths, (list, tuple)) and paths and len(paths) == len(set(paths)), "INVALID_SNAPSHOT_PATHS")
    contents, entries = {}, []
    for name in sorted(paths):
        require(source_allowed(name), "SOURCE_PATH_NOT_ALLOWED")
        data = read_regular(source_root / name)
        try:
            data.decode("utf-8")
        except UnicodeError:
            raise ValueError("UTF8_SOURCE_REQUIRED") from None
        contents[name] = data
        entries.append({"path": name, "sha256": digest(data), "size": len(data)})
    require(sum(map(len, contents.values())) <= MAX_TOTAL, "SNAPSHOT_TOO_LARGE")
    core = {"schemaVersion": 1, "files": entries}
    manifest = {**core, "snapshotSha256": digest(canonical(core))}
    fresh_directory(output_dir)
    for name, data in contents.items():
        write_private(output_dir / name, data)
    write_private(output_dir / MANIFEST, canonical(manifest) + b"\n")
    # A concurrent edit cannot be accepted as a consistent original snapshot.
    require(all(read_regular(source_root / name) == data for name, data in contents.items()),
            "SOURCE_CHANGED_DURING_SNAPSHOT")
    return manifest


def verify_snapshot(snapshot_dir, expected_snapshot_sha256=None):
    snapshot_dir = safe_path(snapshot_dir)
    manifest = read_json(snapshot_dir / MANIFEST)
    require(isinstance(manifest, dict) and set(manifest) == {"schemaVersion", "files", "snapshotSha256"}
            and type(manifest["schemaVersion"]) is int and manifest["schemaVersion"] == 1
            and isinstance(manifest["files"], list), "INVALID_MANIFEST")
    core = {"schemaVersion": 1, "files": manifest["files"]}
    require(digest(canonical(core)) == manifest["snapshotSha256"], "MANIFEST_HASH_MISMATCH")
    if expected_snapshot_sha256 is not None:
        require(manifest["snapshotSha256"] == expected_snapshot_sha256, "SNAPSHOT_IDENTITY_MISMATCH")
    names, contents = [], {}
    for entry in manifest["files"]:
        require(isinstance(entry, dict) and set(entry) == {"path", "sha256", "size"}, "INVALID_MANIFEST_ENTRY")
        name = entry["path"]
        require(source_allowed(name) and name not in contents, "INVALID_MANIFEST_PATH")
        data = read_regular(snapshot_dir / name)
        require(type(entry["size"]) is int and len(data) == entry["size"] and digest(data) == entry["sha256"],
                "SNAPSHOT_SOURCE_CHANGED")
        names.append(name)
        contents[name] = data
    require(names == sorted(names) and names and sum(map(len, contents.values())) <= MAX_TOTAL,
            "INVALID_MANIFEST_ORDER_OR_SIZE")
    actual = set()
    for path in snapshot_dir.rglob("*"):
        require(not path.is_symlink(), "SYMLINK_REFUSED")
        if path.is_file():
            actual.add(str(path.relative_to(snapshot_dir)))
    require(actual == set(names) | {MANIFEST}, "UNMANIFESTED_SNAPSHOT_FILE")
    return manifest, contents


def validate_candidate(snapshot_dir, candidate, *, expected_snapshot_sha256=None):
    manifest, contents = verify_snapshot(snapshot_dir, expected_snapshot_sha256)
    require(isinstance(candidate, dict) and set(candidate) == {"schemaVersion", "snapshotSha256", "replacements"}
            and type(candidate["schemaVersion"]) is int and candidate["schemaVersion"] == 1,
            "INVALID_CANDIDATE_SCHEMA")
    require(candidate["snapshotSha256"] == manifest["snapshotSha256"], "CANDIDATE_SNAPSHOT_MISMATCH")
    replacements = candidate["replacements"]
    require(isinstance(replacements, list) and len(replacements) <= 8, "INVALID_REPLACEMENTS")
    seen, normalized = set(), []
    for item in replacements:
        require(isinstance(item, dict) and set(item) == {"path", "beforeSha256", "content"},
                "INVALID_REPLACEMENT_SCHEMA")
        name = item["path"]
        relative_path(name)
        require(name in contents and name not in seen, "NEW_OR_DUPLICATE_PATH_REFUSED")
        require(change_allowed(name), "PROTECTED_SOURCE_REFUSED")
        require(item["beforeSha256"] == digest(contents[name]), "REPLACEMENT_BASE_MISMATCH")
        text = item["content"]
        require(isinstance(text, str) and text.strip() and "\x00" not in text, "EMPTY_OR_INVALID_REPLACEMENT")
        data = text.encode("utf-8")
        require(len(data) <= MAX_FILE and data != contents[name], "OVERSIZE_OR_UNCHANGED_REPLACEMENT")
        if name.endswith(".py"):
            try:
                tree = ast.parse(text, filename=name)
                previous = ast.parse(contents[name].decode("utf-8"), filename=name)
                compile(tree, name, "exec", dont_inherit=True)
            except (SyntaxError, UnicodeError):
                raise ValueError("INVALID_PYTHON_REPLACEMENT") from None
            tests = lambda root: {node.name for node in ast.walk(root) if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                                  and node.name.startswith("test_")}
            require(tests(previous) <= tests(tree), "EMBEDDED_TEST_DELETION_REFUSED")
        seen.add(name)
        normalized.append({"path": name, "beforeSha256": item["beforeSha256"],
                           "afterSha256": digest(data), "content": text})
    return normalized


def sandbox_profile(candidate_dir):
    # No home, live checkout, OpenClaw state, credentials, sockets or network.
    quote = lambda value: json.dumps(str(value))
    roots = ["/System/Library", "/System/Cryptexes", "/System/Volumes/Preboot",
             "/usr/bin", "/usr/lib", "/usr/libexec", "/usr/share", "/bin", "/sbin",
             "/Library/Developer", "/Library/Apple",
             "/opt/homebrew/Cellar", "/opt/homebrew/opt", "/opt/homebrew/lib"]
    reads = " ".join("(subpath " + quote(path) + ")" for path in [*roots, candidate_dir])
    return ('(version 1)(deny default)(allow process-exec)(allow process-fork)(allow sysctl-read)'
            '(allow file-read-metadata)(allow file-read* ' + reads + ')'
            # macOS process startup reads the root directory itself, not its descendants.
            '(allow file-read* (literal "/"))'
            '(allow file-read* (literal "/dev/null") (literal "/dev/urandom") (literal "/dev/random"))'
            '(allow file-write* (subpath ' + quote(candidate_dir) + '))'
            '(allow file-write-data (literal "/dev/null"))(deny network*)')


def run_check(command, candidate_dir, log_path, timeout=90):
    require(SANDBOX.is_file() and PYTHON.is_file(), "SANDBOX_RUNTIME_UNAVAILABLE")
    private = candidate_dir / ".check-state"
    env = {"PATH": "/usr/bin:/bin", "HOME": str(private / "home"), "TMPDIR": str(private / "tmp"),
           "LANG": "en_US.UTF-8", "PYTHONNOUSERSITE": "1", "PYTHONDONTWRITEBYTECODE": "1"}
    process = subprocess.Popen([str(SANDBOX), "-p", sandbox_profile(candidate_dir), *map(str, command)],
                               cwd=candidate_dir, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               start_new_session=True)
    started, captured, total, timed_out = time.monotonic(), bytearray(), 0, False
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                if time.monotonic() - started > timeout:
                    timed_out = True
                    break
                for key, _events in selector.select(timeout=0.1):
                    chunk = os.read(key.fd, 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    total += len(chunk)
                    captured.extend(chunk[:max(0, MAX_LOG - len(captured))])
            if timed_out:
                os.killpg(process.pid, signal.SIGKILL)
            code = process.wait(timeout=10)
    finally:
        # A child may outlive an exited test parent and retain inherited handles.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=10)
        process.stdout.close()
    write_private(log_path, bytes(captured))
    return {"ok": code == 0 and not timed_out, "exitCode": code, "timedOut": timed_out,
            "elapsedSeconds": round(time.monotonic() - started, 3), "outputBytes": total,
            "outputTruncated": total > len(captured), "logSha256": digest(captured)}


def evaluate(snapshot_dir, candidate, output_dir, *, expected_snapshot_sha256=None):
    snapshot_dir, output_dir = safe_path(snapshot_dir), safe_path(output_dir)
    separate(snapshot_dir, output_dir)
    require(snapshot_dir.parent == output_dir.parent, "EVALUATION_MUST_SHARE_PRIVATE_RUN_PARENT")
    for directory in (snapshot_dir, snapshot_dir.parent):
        info = directory.stat()
        require(info.st_uid == os.getuid() and info.st_mode & 0o077 == 0, "PRIVATE_RUN_DIRECTORY_REQUIRED")
    manifest, contents = verify_snapshot(snapshot_dir, expected_snapshot_sha256)
    replacements = validate_candidate(snapshot_dir, candidate, expected_snapshot_sha256=manifest["snapshotSha256"])
    require(all(name in contents for name in FIXED_TESTS), "FIXED_TEST_SOURCES_MISSING")
    require(SANDBOX.is_file() and PYTHON.is_file(), "SANDBOX_RUNTIME_UNAVAILABLE")
    fresh_directory(output_dir)
    worktree = output_dir / "candidate"
    stage = Path(tempfile.mkdtemp(prefix=".candidate-", dir=output_dir))
    changed = {item["path"]: item["content"].encode("utf-8") for item in replacements}
    expected = {name: changed.get(name, data) for name, data in contents.items()}
    try:
        for name, data in expected.items():
            write_private(stage / name, data)
        verify_snapshot(snapshot_dir, manifest["snapshotSha256"])
        os.replace(stage, worktree)
    finally:
        if stage.exists():
            shutil.rmtree(stage)
    for name in ("home", "tmp"):
        (worktree / ".check-state" / name).mkdir(parents=True, mode=0o700)
    checks = []
    receipt = {"schemaVersion": 1, "snapshotSha256": manifest["snapshotSha256"],
               "validatorSha256": digest(Path(__file__).read_bytes()),
               "candidateSha256": digest(canonical(candidate)), "ok": False, "activated": False,
               "testPolicy": "fixed-suite-unchanged-tests", "sandbox": "macos-seatbelt-no-network",
               "replacements": [{key: value for key, value in item.items() if key != "content"}
                                for item in replacements], "checks": checks}
    try:
        for index, name in enumerate(FIXED_TESTS):
            result = run_check([PYTHON, "-I", "-B", name], worktree, output_dir / ("check-" + str(index) + ".log"))
            checks.append({"name": name, **result})
        # Tests execute in a disposable copy and may not rewrite submitted source.
        require(all(read_regular(worktree / name) == data for name, data in expected.items()),
                "CANDIDATE_CHANGED_DURING_CHECKS")
        for base in (worktree / "scripts", worktree / "docs"):
            if base.exists():
                for path in base.rglob("*"):
                    require(not path.is_symlink(), "CHECK_CREATED_SYMLINK")
                    if path.is_file():
                        require(str(path.relative_to(worktree)) in expected, "CHECK_CREATED_SOURCE_FILE")
        verify_snapshot(snapshot_dir, manifest["snapshotSha256"])
        receipt["ok"] = all(item["ok"] for item in checks)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        receipt["errorType"] = type(error).__name__
    finally:
        receipt["completedAt"] = datetime.now(timezone.utc).isoformat()
        write_private(output_dir / "patch-verification.json", canonical(receipt) + b"\n")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    snapshot = commands.add_parser("snapshot")
    snapshot.add_argument("--source-root", type=Path, required=True)
    snapshot.add_argument("--output-dir", type=Path, required=True)
    verify = commands.add_parser("evaluate")
    verify.add_argument("--snapshot-dir", type=Path, required=True)
    verify.add_argument("--candidate", type=Path, required=True)
    verify.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    result = (create_snapshot(args.source_root, args.output_dir) if args.command == "snapshot" else
              evaluate(args.snapshot_dir, read_json(args.candidate), args.output_dir))
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result.get("ok", True) else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError) as error:
        print(json.dumps({"ok": False, "errorType": type(error).__name__}), file=sys.stderr)
        sys.exit(1)
