#!/usr/bin/env python3
"""Stage, install, or roll back an exact OpenClaw 2026.9.3 GPT-6 Sol repair.

The default action only stages original files and candidate replacements. Run
--install only after the Gateway is stopped and offline tests have passed.
"""

import argparse
import hashlib
import json
import subprocess
import os
from pathlib import Path
import stat
import tempfile
from datetime import datetime, timezone


VERSION = "2026.9.3"
SOURCE = "https://github.com/openclaw/openclaw/blob/main/extensions/openai/openclaw.plugin.json"
FILES = {
    "dist/extensions/openai/openclaw.plugin.json": "3587126e05f1ebf7640518fb59cf90d5fd76dd7ae735ce22616140b634d5e753",
    "dist/thinking-policy-DdkggmZ1.mjs": "0342984d12824d9f9ccbc385d7a81688d5f0a57883e9376ac63c8a3899799a59",
    "dist/model-route-contract-pNgq17sM.mjs": "3ec4e0ad6a5da96d459b15a04f7fce2f013e2f38695f5b3acefb8ab5c1e82295",
    "dist/openai-chatgpt-provider-BB-cMiBP.mjs": "4cc7725f207168a6c60722ad070ff5b2ad1bb377c9d926404292fca014ab9423",
}
AFTER_SHA256 = {
    "dist/extensions/openai/openclaw.plugin.json": "a662962c79425982821e5e2e3ba568f7550a6b51bcee77090fb0f87207d02a63",
    "dist/thinking-policy-DdkggmZ1.mjs": "b70032df2b37e395406137a9e66db18483a30c253427cbd5aa49e6940b0c0f35",
    "dist/model-route-contract-pNgq17sM.mjs": "e544ab32d8dcc7c0ca37345a33ff6cb2eeec4721f93d52a1142b59e1a232994f",
    "dist/openai-chatgpt-provider-BB-cMiBP.mjs": "80b518a723443d68fd1e0565747bfc3e4077cc31eabfb26bc5a889d605db6379",
}

# Matches the official OpenClaw main manifest's GPT-6 Sol record. This is model
# metadata, not an API key, auth route, or permission change. Codex model/list
# must still independently expose Sol to the account before live promotion.
SOL_MODEL = {
    "id": "gpt-6-sol",
    "name": "GPT-6 Sol",
    "reasoning": True,
    "input": ["text", "image"],
    "contextWindow": 1050000,
    "contextTokens": 272000,
    "maxTokens": 128000,
    "cost": {
        "input": 2,
        "output": 10,
        "cacheRead": 0.2,
        "cacheWrite": 2.5,
        "tieredPricing": [
            {"range": [0, 272001], "input": 2, "output": 10,
             "cacheRead": 0.2, "cacheWrite": 2.5},
            {"range": [272001], "input": 4, "output": 15,
             "cacheRead": 0.4, "cacheWrite": 5},
        ],
    },
    "thinkingLevelMap": {"off": "none", "minimal": "low",
                         "xhigh": "xhigh", "max": "max"},
    "compat": {
        "supportsReasoningEffort": True,
        "supportedReasoningEfforts": ["none", "low", "medium", "high", "xhigh", "max"],
        "supportsTemperature": False,
        "codeMode": "preferred",
    },
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError("OpenClaw source anchor is absent or ambiguous; requalify this release")
    return source.replace(old, new, 1)


def rendered_model(indent):
    lines = json.dumps(SOL_MODEL, ensure_ascii=False, indent=2).splitlines()
    return "\n".join(indent + line for line in lines)


def patch_text(relative, source):
    if relative == "dist/extensions/openai/openclaw.plugin.json":
        anchor = '          {\n            "id": "gpt-5.6-sol"'
        source = replace_once(source, anchor, rendered_model("          ") + ",\n" + anchor)
        catalog = json.loads(source)["modelCatalog"]["providers"]["openai"]["models"]
        if [item["id"] for item in catalog].count("gpt-6-sol") != 1:
            raise ValueError("candidate manifest has an ambiguous GPT-6 Sol model")
        if next(item for item in catalog if item["id"] == "gpt-6-sol") != SOL_MODEL:
            raise ValueError("candidate manifest model differs from reviewed metadata")
        return source
    if relative == "dist/thinking-policy-DdkggmZ1.mjs":
        anchor = '\t\t\t{\n\t\t\t\t"id": "gpt-5.6-sol"'
        lines = json.dumps(SOL_MODEL, ensure_ascii=False, indent=2).splitlines()
        model_js = "\n".join("\t\t\t" + ("\t" * ((len(line) - len(line.lstrip())) // 2)) + line.lstrip()
                             for line in lines)
        return replace_once(source, anchor, model_js + ",\n" + anchor)
    if relative == "dist/model-route-contract-pNgq17sM.mjs":
        source = replace_once(source,
                              'const OPENAI_GPT_6_ASTRA_MODEL_ID = "gpt-6-astra";',
                              'const OPENAI_GPT_6_ASTRA_MODEL_ID = "gpt-6-astra";\n'
                              'const OPENAI_GPT_6_SOL_MODEL_ID = "gpt-6-sol";')
        return replace_once(source,
                            'const OPENAI_DUAL_ROUTE_MODEL_IDS = [\n\tOPENAI_GPT_6_ASTRA_MODEL_ID,',
                            'const OPENAI_DUAL_ROUTE_MODEL_IDS = [\n\tOPENAI_GPT_6_ASTRA_MODEL_ID,\n'
                            '\tOPENAI_GPT_6_SOL_MODEL_ID,')
    if relative == "dist/openai-chatgpt-provider-BB-cMiBP.mjs":
        return replace_once(source,
                            'if (lower === "gpt-6-astra") {',
                            'if (lower === "gpt-6-astra" || lower === "gpt-6-sol") {')
    raise ValueError("unreviewed runtime path")


def prepare(package, output):
    package = package.absolute()
    if package.is_symlink():
        raise ValueError("runtime package must not be a symlink")
    package = package.resolve(strict=True)
    output = output.absolute()
    if output.parent.is_symlink():
        raise ValueError("candidate output parent must not be a symlink")
    output = output.parent.resolve() / output.name
    if output == package or output.is_relative_to(package) or package.is_relative_to(output):
        raise ValueError("candidate output must be outside the runtime package")
    if output.exists() or output.is_symlink():
        raise ValueError("candidate output must not already exist")
    package_json = package / "package.json"
    if package_json.is_symlink() or json.loads(package_json.read_text())["version"] != VERSION:
        raise ValueError("unqualified OpenClaw release")

    prepared = {}
    for relative, expected in FILES.items():
        path = package / relative
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(package):
            raise ValueError("runtime source path is missing or redirected")
        before = path.read_bytes()
        if digest(before) != expected:
            raise ValueError("unreviewed or already patched OpenClaw source: " + relative)
        after = patch_text(relative, before.decode("utf-8")).encode("utf-8")
        if after == before:
            raise ValueError("GPT-6 Sol patch made no change: " + relative)
        if digest(after) != AFTER_SHA256[relative]:
            raise ValueError("candidate differs from reviewed GPT-6 Sol patch: " + relative)
        prepared[relative] = (path, before, after)

    old_umask = os.umask(0o077)
    try:
        output.mkdir(mode=0o700, parents=True, exist_ok=False)
        receipt = {"version": VERSION, "source": SOURCE, "package": str(package),
                   "installed": False,
                   "modelRequests": 0, "files": []}
        for relative, (path, before, after) in prepared.items():
            for tree, data in (("original", before), ("candidate", after)):
                target = output / tree / relative
                target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                target.write_bytes(data)
                target.chmod(stat.S_IMODE(path.stat().st_mode))
            receipt["files"].append({"path": relative, "beforeSha256": digest(before),
                                     "afterSha256": digest(after)})
        (output / "patch-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    finally:
        os.umask(old_umask)
    return receipt


def staged_bytes(package, output):
    """Verify the original, candidate, and receipt before touching a package."""
    package = package.absolute()
    output = output.absolute()
    if package.is_symlink() or output.is_symlink():
        raise ValueError("runtime package or candidate output is redirected")
    package = package.resolve(strict=True)
    output = output.resolve(strict=True)
    if output == package or output.is_relative_to(package) or package.is_relative_to(output):
        raise ValueError("candidate output must be outside the runtime package")
    if not output.is_dir():
        raise ValueError("candidate output is not a directory")
    package_json = package / "package.json"
    if package_json.is_symlink() or json.loads(package_json.read_text())["version"] != VERSION:
        raise ValueError("unqualified OpenClaw release")
    receipt_path = output / "patch-receipt.json"
    if receipt_path.is_symlink() or not receipt_path.is_file():
        raise ValueError("candidate receipt is missing or redirected")
    receipt = json.loads(receipt_path.read_text())
    expected_files = [{"path": relative, "beforeSha256": before,
                       "afterSha256": AFTER_SHA256[relative]}
                      for relative, before in FILES.items()]
    if (receipt.get("version") != VERSION or receipt.get("source") != SOURCE
            or receipt.get("package") != str(package)
            or receipt.get("modelRequests") != 0
            or receipt.get("files") != expected_files
            or not isinstance(receipt.get("installed"), bool)):
        raise ValueError("candidate receipt differs from reviewed patch")

    staged = {}
    for relative in FILES:
        path = package / relative
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(package):
            raise ValueError("runtime source path is missing or redirected: " + relative)
        copies = []
        for tree, expected in (("original", FILES[relative]),
                               ("candidate", AFTER_SHA256[relative])):
            source = output / tree / relative
            if source.is_symlink() or not source.is_file() or not source.resolve().is_relative_to(output):
                raise ValueError("staged source path is missing or redirected: " + relative)
            data = source.read_bytes()
            if digest(data) != expected:
                raise ValueError("staged source hash differs from reviewed patch: " + relative)
            copies.append(data)
        staged[relative] = (path, *copies)
    return receipt_path, receipt, staged


def replace_file(path, data):
    """Replace one verified runtime file atomically and preserve its mode."""
    descriptor, temporary = tempfile.mkstemp(prefix="." + path.name + ".sol-", dir=path.parent)
    temporary = Path(temporary)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.chmod(stat.S_IMODE(path.stat().st_mode))
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def save_receipt(path, receipt):
    descriptor, temporary = tempfile.mkstemp(prefix=".patch-receipt-", dir=path.parent)
    temporary = Path(temporary)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(receipt, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def apply(package, output, rollback=False):
    receipt_path, receipt, staged = staged_bytes(package, output)
    if not rollback and receipt["installed"]:
        raise ValueError("candidate is already recorded as installed")
    if rollback:
        permitted = {relative: {FILES[relative], AFTER_SHA256[relative]}
                     for relative in FILES}
    else:
        permitted = {relative: {FILES[relative]} for relative in FILES}
    for relative, (path, _before, _after) in staged.items():
        if digest(path.read_bytes()) not in permitted[relative]:
            raise ValueError("runtime source drift prevents patch operation: " + relative)

    changed = []
    try:
        for relative, (path, before, after) in staged.items():
            target = before if rollback else after
            if path.read_bytes() != target:
                replace_file(path, target)
                changed.append((path, before if not rollback else after))
        for relative, (path, _before, _after) in staged.items():
            expected = FILES[relative] if rollback else AFTER_SHA256[relative]
            if digest(path.read_bytes()) != expected:
                raise ValueError("post-write runtime hash mismatch: " + relative)
        receipt["installed"] = not rollback
        if rollback:
            receipt.pop("installedAtUtc", None)
        else:
            receipt["installedAtUtc"] = datetime.now(timezone.utc).isoformat()
        save_receipt(receipt_path, receipt)
    except Exception:
        # A process interruption may still leave a mixed group. A later
        # --rollback accepts only reviewed original/candidate hashes.
        for path, old_data in reversed(changed):
            replace_file(path, old_data)
        raise
    return receipt


def require_gateway_stopped(state_dir, runner=None):
    """Refuse to rewrite runtime files while the Gateway listens or when that cannot be checked."""
    config = json.loads((state_dir / "openclaw.json").read_text())
    port = config["gateway"]["port"]
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("invalid Gateway port")
    listener = (runner or subprocess.run)(["/usr/sbin/lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN"],
                                          capture_output=True, timeout=10)
    # lsof exits 1 with no output when nothing listens; anything else is active or uninspectable.
    if listener.returncode != 1 or listener.stdout:
        raise ValueError("Gateway must be stopped before patch activation")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--state-dir", type=Path, default=Path.home() / ".openclaw-personaledge")
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--install", action="store_true", help="install a prepared patch into a stopped Gateway")
    action.add_argument("--rollback", action="store_true", help="restore the reviewed original bytes")
    args = parser.parse_args()
    if args.install or args.rollback:
        require_gateway_stopped(args.state_dir)
        result = apply(args.package, args.output, rollback=args.rollback)
        print(json.dumps({"installed": result["installed"], "files": len(result["files"]),
                          "receipt": str((args.output.absolute() / "patch-receipt.json"))}))
    else:
        result = prepare(args.package, args.output)
        print(json.dumps({"prepared": True, "files": len(result["files"]),
                          "output": str(args.output.absolute())}))


if __name__ == "__main__":
    main()
