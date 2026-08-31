# Repomix Review Guide

This bundle is a review snapshot of the current working tree, not a release receipt. It can contain
uncommitted and untracked source files. Review the implementation as packaged, then independently
verify any build, device, provider, signing, or backup claim before promoting it.

## Included for review

- Production Kotlin, Compose UI, Android manifests/resources, ProGuard rules, and module wiring.
- JVM and Android instrumentation tests, including emulator-only safety guards and opt-in live tests.
- Gradle build files, dependency locks, version catalog, wrapper properties, and dependency
  verification metadata.
- Room schemas, host scripts, model manifests and checksums, architecture/security documentation,
  current status, handoff notes, and bounded acceptance evidence written into project documents.

Comments and empty lines are retained, and code is not compressed. Line numbers are added by
Repomix so findings can identify exact packaged locations.

## Intentionally excluded or sanitized

- The model binary, APK/AAB/build output, caches, local SDK/JDK state, and IDE state.
- Keystores, private keys, environment files, local/signing properties, service credentials, and
  raw credential JSON.
- Raw reports, traces, captures, JSONL diagnostics, databases, logs, heap dumps, and other device or
  runtime artifacts that can contain owner data.
- Git history and diffs, because deleted lines can reintroduce values that are absent from the
  current files.
- In the final bundle, physical-device serials, local user home names, non-example consumer email
  addresses, phone numbers, and MAC addresses are replaced with explicit `REDACTED` markers.

The public model/dependency hashes, release certificate fingerprint, signing-artifact checksum, and
synthetic values under reserved example domains are retained because they are non-secret review and
provenance data. Signing key bytes and passwords are never included.

## Required review boundaries

1. Treat LiteRT output and all recalled/network/provider text as untrusted input. Confirm that model
   proposals cannot call platform APIs without strict Kotlin parsing, confirmation when required,
   an execution-time interlock, and durable idempotency handling.
2. Keep `automaticToolCalling=false`. Look for paths that bypass confirmation, consent, permission,
   network destination restrictions, thermal cancellation, ledger claims, or trusted receipts.
3. Check every external write for validation, replay/cancellation/expiry behavior, durable claims,
   and honest handling of unknown outcomes. Do not infer provider success from intent dispatch.
4. Review credential storage and transport without requesting real keys. Values must remain masked,
   outside prompts/history/diagnostics, and protected by Android Keystore-backed encryption.
5. Review prompt construction, summaries, notification text, memories, search results, route data,
   and calendar text for injection, delimiter, byte-limit, persistence, and deletion-boundary bugs.
6. Verify Room migrations, backup exclusions, process-death behavior, concurrency, lifecycle races,
   fold/cover layouts, and thermal state transitions. Host/emulator tests do not prove Fold8 behavior.
7. Distinguish `implemented`, `host tested`, `emulator verified`, `physical-device accepted`,
   `direct-provider accepted`, and `owner-attested`. Documentation or a passing build alone must not
   be promoted to a stronger evidence class.
8. Do not weaken privacy, signing, confirmation, network, permission, thermal, or device-safety gates
   to make tests pass. Physical-device tests guarded for emulator-only destructive behavior must stay
   guarded.

Prioritize actionable findings with file/line evidence, exploit or failure preconditions, user and
privacy impact, and a narrowly scoped remediation. Separate confirmed defects from design tradeoffs,
stale documentation, missing tests, and evidence that simply cannot be established from this bundle.
