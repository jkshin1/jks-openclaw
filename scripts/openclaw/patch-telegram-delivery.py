#!/usr/bin/env python3
"""Prepare an exact OpenClaw 2026.9.2 recovery patch; never writes to the installed runtime."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError("runtime anchor missing or ambiguous; requalify against this version")
    return text.replace(old, new, 1)


def prepare(package, output):
    package = package.resolve()
    output = output.resolve()
    if output.is_relative_to(package) or package.is_relative_to(output):
        raise ValueError("prepare output must be outside the runtime package")
    version = json.loads((package / "package.json").read_text())["version"]
    specs = json.loads(Path(__file__).with_name("runtime-patch-specs.json").read_text())
    if version not in specs:
        raise ValueError("unqualified OpenClaw release")
    output.mkdir(parents=True, exist_ok=False)
    files = {}
    for kind, spec in zip(("recovery", "storage"), specs[version]["delivery"][:2]):
        source = package / spec["path"]
        if hashlib.sha256(source.read_bytes()).hexdigest() != spec["before"]:
            raise ValueError("unreviewed delivery source; requalify after upgrade")
        files[kind] = source
    receipt = {"version": version, "files": []}
    for kind, source in files.items():
        before = source.read_text()
        if "telegram-delivery-retry.mjs" in before:
            raise ValueError("runtime already patched; verify installed receipt instead")
        after = 'import { canRetryOwnerTelegramDelivery, ownerTelegramRetryBudget } from "./telegram-delivery-retry.mjs";\n' + before
        if kind == "recovery":
            after = replace_once(after, "function resolveMaxRetries(entry) {\n\tconst configured = entry.maxRetries;",
                                 "function resolveMaxRetries(entry) {\n\tconst configured = ownerTelegramRetryBudget(entry) ?? entry.maxRetries;")
            after = replace_once(after, "\tlet reconciledPlatformSendAttemptId;", "\tlet ownerRetry = false;\n\tlet reconciledPlatformSendAttemptId;")
            old = '\t\tif (reconciliation?.status === "not_sent" && entry.recoveryState === "send_attempt_started") {'
            new = '''\t\tconst provenNotSent = reconciliation?.status === "not_sent" && entry.recoveryState === "send_attempt_started";
\t\townerRetry = !provenNotSent && !attemptBudgetExhausted && canRetryOwnerTelegramDelivery(entry, opts.cfg);
\t\tif (provenNotSent || ownerRetry) {'''
            after = replace_once(after, old, new)
            after = replace_once(after, 'opts.log.info(`Delivery entry ${entry.id} reconciled ${entry.recoveryState} as not sent; replaying`);',
                                 'opts.log.info(ownerRetry ? `Delivery entry ${entry.id}: owner-authorized Telegram retry after network failure; duplicates possible` : `Delivery entry ${entry.id} reconciled ${entry.recoveryState} as not sent; replaying`);')
            after = replace_once(after, "claimDeliveryPlatformSendAttempt(entry.id, opts.stateDir, reconciledPlatformSendStartedAt, reconciledPlatformSendAttemptId)",
                                 "claimDeliveryPlatformSendAttempt(entry.id, opts.stateDir, reconciledPlatformSendStartedAt, reconciledPlatformSendAttemptId, ownerRetry)")
        else:
            after = replace_once(after, "const reconciledNotSent = entry.recoveryState === \"send_attempt_started\" &&",
                                 'const authorizedUnknownReplay = params.ownerRetry === true && entry.recoveryState === "unknown_after_send" && canRetryOwnerTelegramDelivery(entry);\n\t\tconst reconciledNotSent = (entry.recoveryState === "send_attempt_started" || authorizedUnknownReplay) &&')
            after = replace_once(after, "async function claimDeliveryPlatformSendAttempt(id, stateDir, reconciledPlatformSendStartedAt, reconciledPlatformSendAttemptId) {",
                                 "async function claimDeliveryPlatformSendAttempt(id, stateDir, reconciledPlatformSendStartedAt, reconciledPlatformSendAttemptId, ownerRetry = false) {")
            anchor = 'queueName: OUTBOUND_DELIVERY_QUEUE_NAME,\n\t\tid,\n\t\tstateDir,\n\t\t...reconciledPlatformSendStartedAt'
            after = replace_once(after, anchor,
                                 'queueName: OUTBOUND_DELIVERY_QUEUE_NAME,\n\t\tid,\n\t\tstateDir,\n\t\townerRetry,\n\t\t...reconciledPlatformSendStartedAt')
        (output / source.name).write_text(after)
        receipt["files"].append({"name": source.name, "beforeSha256": hashlib.sha256(before.encode()).hexdigest(),
                                 "afterSha256": hashlib.sha256(after.encode()).hexdigest()})
    helper = Path(__file__).with_name("telegram-delivery-retry.mjs")
    shutil.copyfile(helper, output / helper.name)
    receipt["files"].append({"name": helper.name, "afterSha256": hashlib.sha256(helper.read_bytes()).hexdigest()})
    (output / "patch-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"prepared": True, "output": str(output), "patchedModules": 2}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.package, args.output)
