#!/usr/bin/env python3
"""Qualify exact runtime bytes offline; optionally install the release's reviewed repairs."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


SOURCE = Path(__file__).resolve().parent
NODE = "/opt/homebrew/opt/node/bin/node"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(argv, output):
    result = subprocess.run(argv, capture_output=True, timeout=90)
    output.write_bytes(result.stdout)
    output.with_suffix(output.suffix + ".stderr").write_bytes(result.stderr)
    if result.returncode:
        raise ValueError("offline qualification failed: " + Path(argv[1]).name)


def qualify(package, output, state=None):
    if output.is_relative_to(package) or package.is_relative_to(output):
        raise ValueError("qualification output must be separate from package")
    version = json.loads((package / "package.json").read_text())["version"]
    specs = json.loads((SOURCE / "runtime-patch-specs.json").read_text())
    if version not in specs:
        raise ValueError("unreviewed runtime release")
    spec = specs[version]
    # From 2026.9.6 GLM thinking is configuration and the auth/Sol repairs ship upstream.
    items = [spec["token"], spec["memory"], *spec.get("thinking", []), *spec["delivery"]]
    if "authReprobe" in spec:
        items.append(spec["authReprobe"])
    if "claudeCliArgs" in spec:
        items.append(spec["claudeCliArgs"])
    for item in items:
        target = package / item["path"]
        relative = Path(item["path"])
        if relative.is_absolute() or ".." in relative.parts or target.is_symlink() or not target.resolve().is_relative_to(package):
            raise ValueError("runtime patch path redirected")
        if item.get("before") and digest(target) != item["before"]:
            raise ValueError("unreviewed or already patched runtime bytes")
        if not item.get("before") and target.exists():
            raise ValueError("new helper already exists")
    if state is not None:
        config = json.loads((state / "openclaw.json").read_text())
        port = config["gateway"]["port"]
        if type(port) is not int or not 1 <= port <= 65535:
            raise ValueError("invalid Gateway port")
        listener = subprocess.run(["/usr/sbin/lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN"],
                                  capture_output=True, timeout=10)
        if listener.returncode != 1 or listener.stdout:
            raise ValueError("Gateway must be stopped before patch activation")
        operations = state / "operations"
        if operations.is_symlink() or not operations.resolve().is_relative_to(state):
            raise ValueError("operations directory redirected")
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    run(["/usr/bin/python3", str(SOURCE / "patch-telegram-delivery.py"), "--package", str(package),
         "--output", str(output / "delivery")], output / "delivery-prepare.json")
    single_file_patches = [("token", "patch-glm-token-field.mjs", "glm-token-field-patch.json"),
                          ("memory", "patch-memory-admission.mjs", "memory-admission-patch.json")]
    if "authReprobe" in spec:
        single_file_patches.append(("authReprobe", "patch-auth-reprobe.mjs", "auth-reprobe-patch.json"))
    if "claudeCliArgs" in spec:
        single_file_patches.append(("claudeCliArgs", "patch-claude-cli-agent.mjs", "claude-cli-agent-patch.json"))
    for key, script, receipt in single_file_patches:
        run([NODE, str(SOURCE / script), str(package / spec[key]["path"]), str(output / (key + ".mjs"))],
            output / receipt)
    if "thinking" in spec:
        run([NODE, str(SOURCE / "patch-glm-thinking.mjs"), str(package), str(output / "thinking")],
            output / "thinking-prepare.json")
    else:
        run([NODE, str(SOURCE / "test-glm-thinking.mjs"), str(package)], output / "thinking-config-tests.json")
    run([NODE, str(SOURCE / "test-telegram-delivery-retry.mjs"), str(package), str(output / "delivery")],
        output / "delivery-tests.txt")
    if "authReprobe" in spec:
        run([NODE, str(SOURCE / "test-auth-reprobe.mjs"), str(package / spec["authReprobe"]["path"]),
             str(output / "authReprobe.mjs")], output / "auth-reprobe-tests.txt")
        auth_receipt = json.loads((output / "auth-reprobe-patch.json").read_text())
        reviewed = spec["authReprobe"]
        if not (isinstance(auth_receipt, dict) and auth_receipt.get("version") == version
                and auth_receipt.get("relativePath") == reviewed["path"]
                and auth_receipt.get("beforeSha256") == reviewed["before"]
                and auth_receipt.get("afterSha256") == reviewed["after"]
                and type(auth_receipt.get("inferenceRequests")) is int
                and auth_receipt["inferenceRequests"] == 0):
            raise ValueError("Codex auth reprobe receipt differs from reviewed patch")
    replacements = {spec["token"]["path"]: output / "token.mjs", spec["memory"]["path"]: output / "memory.mjs"}
    if "authReprobe" in spec:
        replacements[spec["authReprobe"]["path"]] = output / "authReprobe.mjs"
    if "claudeCliArgs" in spec:
        replacements[spec["claudeCliArgs"]["path"]] = output / "claudeCliArgs.mjs"
    replacements.update({item["path"]: output / "thinking" / item["path"] for item in spec.get("thinking", [])})
    replacements.update({item["path"]: output / "delivery" / Path(item["path"]).name for item in spec["delivery"]})
    for item in items:
        if digest(replacements[item["path"]]) != item["after"]:
            raise ValueError("candidate differs from reviewed patch bytes")
    receipt = {"version": version, "qualifiedAt": datetime.now(timezone.utc).isoformat(),
               "offlineQualified": True, "inferenceRequests": 0, "installed": False,
               "files": [{"path": item["path"], "sha256": item["after"]} for item in items]}
    if state is not None:
        # Caller must park the Gateway first. Keep all originals and old receipts for recovery.
        previous = output / "previous"
        previous.mkdir(mode=0o700)
        receipts = {"telegram-delivery-patch.json": output / "delivery/patch-receipt.json",
                    "glm-token-field-patch.json": output / "glm-token-field-patch.json",
                    "memory-admission-patch.json": output / "memory-admission-patch.json"}
        if "thinking" in spec:
            receipts["glm-thinking-patch.json"] = output / "thinking/glm-thinking-patch.json"
        if "authReprobe" in spec:
            receipts["auth-reprobe-patch.json"] = output / "auth-reprobe-patch.json"
        if "claudeCliArgs" in spec:
            receipts["claude-cli-agent-patch.json"] = output / "claude-cli-agent-patch.json"
        writes = list((package / name, source) for name, source in replacements.items())
        writes += [(state / "operations" / name, source) for name, source in receipts.items()]
        before = []
        for index, (target, source) in enumerate(writes):
            if target.is_symlink():
                raise ValueError("patch or receipt target redirected")
            backup = previous / str(index)
            existed = target.exists()
            if existed:
                shutil.copy2(target, backup)
            before.append((target, backup, existed))
        try:
            for target, source in writes:
                fd, temporary = tempfile.mkstemp(prefix=".patch-", dir=str(target.parent))
                with os.fdopen(fd, "wb") as stream:
                    stream.write(source.read_bytes())
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, target)
            for item in items:
                if digest(package / item["path"]) != item["after"]:
                    raise ValueError("installed patch verification failed")
            receipt["installed"] = True
            (output / "qualification.json").write_text(json.dumps(receipt, indent=2) + "\n")
        except Exception:
            for target, backup, existed in reversed(before):
                if existed:
                    shutil.copy2(backup, target)
                elif target.exists():
                    target.unlink()
            raise
    else:
        (output / "qualification.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--state-dir", type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if args.apply and not args.state_dir:
        parser.error("--apply requires --state-dir; park the Gateway before applying")
    os.umask(0o077)
    print(json.dumps(qualify(args.package.resolve(), args.output.resolve(),
                             args.state_dir.resolve() if args.apply else None)))


if __name__ == "__main__":
    main()
