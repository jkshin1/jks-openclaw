#!/usr/bin/env python3
"""Prepare an exact OpenClaw owner-retry recovery patch; never writes to the installed runtime.

2026.9.2/2026.9.3 keep the platform-send claim in the storage module. 2026.9.6 moved that claim
into a SQLite kernel executed by the shared-state worker, so the storage module only forwards the
owner-retry flag and the kernel carries the policy check.
"""

import argparse
import hashlib
import json
from pathlib import Path
import shutil

IMPORT = 'import { canRetryOwnerTelegramDelivery, ownerTelegramRetryBudget } from "./telegram-delivery-retry.mjs";\n'
KINDS = (("delivery-queue-recovery-", "recovery"), ("delivery-queue-storage-", "storage"),
         ("delivery-queue-sqlite-namespace.kernel-", "kernel"))
# Replaces the kernel's proven-not-sent condition with one that also admits the owner's retry.
RECONCILED = ('const reconciledNotSent = entry.recoveryState === "send_attempt_started" &&',
              'const authorizedUnknownReplay = params.ownerRetry === true && entry.recoveryState === "unknown_after_send" && canRetryOwnerTelegramDelivery(entry);\n\t\tconst reconciledNotSent = (entry.recoveryState === "send_attempt_started" || authorizedUnknownReplay) &&')


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError("runtime anchor missing or ambiguous; requalify against this version")
    return text.replace(old, new, 1)


def kind_of(name):
    for prefix, kind in KINDS:
        if name.startswith(prefix):
            return kind
    raise ValueError("unreviewed delivery module: " + name)


def patch_recovery(after, worker_claim):
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
    call = "claimDeliveryPlatformSendAttempt(entry.id, opts.stateDir, reconciledPlatformSendStartedAt, reconciledPlatformSendAttemptId"
    closing = ", stateContext)" if worker_claim else ")"
    return replace_once(after, call + closing, call + closing[:-1] + ", ownerRetry)")


def patch_storage(after, worker_claim):
    if worker_claim:
        # The kernel runs in the shared-state worker; forward the flag through the command input.
        after = replace_once(after, "async function claimDeliveryPlatformSendAttempt(id, stateDir, reconciledPlatformSendStartedAt, reconciledPlatformSendAttemptId, context) {",
                             "async function claimDeliveryPlatformSendAttempt(id, stateDir, reconciledPlatformSendStartedAt, reconciledPlatformSendAttemptId, context, ownerRetry = false) {")
        return replace_once(after, "\t\t\t...reconciledPlatformSendAttemptId !== void 0 ? { reconciledPlatformSendAttemptId } : {}\n",
                            "\t\t\t...reconciledPlatformSendAttemptId !== void 0 ? { reconciledPlatformSendAttemptId } : {},\n"
                            "\t\t\t...ownerRetry === true ? { ownerRetry: true } : {}\n")
    after = replace_once(IMPORT + after, *RECONCILED)
    after = replace_once(after, "async function claimDeliveryPlatformSendAttempt(id, stateDir, reconciledPlatformSendStartedAt, reconciledPlatformSendAttemptId) {",
                         "async function claimDeliveryPlatformSendAttempt(id, stateDir, reconciledPlatformSendStartedAt, reconciledPlatformSendAttemptId, ownerRetry = false) {")
    anchor = 'queueName: OUTBOUND_DELIVERY_QUEUE_NAME,\n\t\tid,\n\t\tstateDir,\n\t\t...reconciledPlatformSendStartedAt'
    return replace_once(after, anchor,
                        'queueName: OUTBOUND_DELIVERY_QUEUE_NAME,\n\t\tid,\n\t\tstateDir,\n\t\townerRetry,\n\t\t...reconciledPlatformSendStartedAt')


def prepare(package, output):
    package = package.resolve()
    output = output.resolve()
    if output.is_relative_to(package) or package.is_relative_to(output):
        raise ValueError("prepare output must be outside the runtime package")
    version = json.loads((package / "package.json").read_text())["version"]
    specs = json.loads(Path(__file__).with_name("runtime-patch-specs.json").read_text())
    if version not in specs:
        raise ValueError("unqualified OpenClaw release")
    files = {}
    for spec in specs[version]["delivery"]:
        if not spec.get("before"):
            continue
        source = package / spec["path"]
        if hashlib.sha256(source.read_bytes()).hexdigest() != spec["before"]:
            raise ValueError("unreviewed delivery source; requalify after upgrade")
        kind = kind_of(source.name)
        # A second module of the same kind would silently replace the first and leave it unpatched.
        if kind in files:
            raise ValueError("delivery patch spec names more than one " + kind + " module")
        files[kind] = source
    worker_claim = "kernel" in files
    if set(files) != ({"recovery", "storage", "kernel"} if worker_claim else {"recovery", "storage"}):
        raise ValueError("delivery patch spec must name recovery and storage modules")
    output.mkdir(parents=True, exist_ok=False)
    receipt = {"version": version, "files": []}
    for kind, source in files.items():
        before = source.read_text()
        if "telegram-delivery-retry.mjs" in before or "ownerRetry" in before:
            raise ValueError("runtime already patched; verify installed receipt instead")
        if kind == "recovery":
            after = patch_recovery(IMPORT + before, worker_claim)
        elif kind == "storage":
            after = patch_storage(before, worker_claim)
        else:
            after = replace_once(IMPORT + before, *RECONCILED)
        (output / source.name).write_text(after)
        receipt["files"].append({"name": source.name, "beforeSha256": hashlib.sha256(before.encode()).hexdigest(),
                                 "afterSha256": hashlib.sha256(after.encode()).hexdigest()})
    helper = Path(__file__).with_name("telegram-delivery-retry.mjs")
    shutil.copyfile(helper, output / helper.name)
    receipt["files"].append({"name": helper.name, "afterSha256": hashlib.sha256(helper.read_bytes()).hexdigest()})
    (output / "patch-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"prepared": True, "output": str(output), "patchedModules": len(files)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.package, args.output)
