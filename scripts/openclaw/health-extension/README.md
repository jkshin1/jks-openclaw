# Personal Edge host health RPC

This optional OpenClaw 2026.8.1 extension registers only `personaledge.health.read`, with
`operator.read` scope and required profile access. It registers no model Tool, shell command,
HTTP route, service, hook, or background work. It has no configuration or request parameters.
Adding this source does not install or enable it in the live Gateway. Enablement remains a
separate managed-deployment change after the physical tool-free conversation gate passes.

The implementation was checked against the installed pinned runtime:

- `dist/loader-DLF0KUIe.js`: `registerGatewayMethod` wraps the handler and records its scope.
- `dist/descriptor-C8WchCC9.js`: the new namespace keeps `operator.read`; reserved core
  administrative namespaces would force broader scope.
- `dist/server-methods-DOqyd9US.js`: authenticated authorization precedes dispatch; handlers
  receive `params = req.params ?? {}` and respond with `(ok, payload, error)`.
- `dist/plugin-entry-BMbml7Gi.d.ts`: the registration contract supports
  `{scope: "operator.read", profileAccess: "required"}`.

The core Android client treats hello advertisement of this method as optional. Missing support
does not prevent ordinary pairing or model runs. Only the typed, parameter-free
`OpenClawRpcClient.readHealthSnapshot()` can dispatch it, with a five-second timeout and no retry.
The generic app request function cannot use this method to send arbitrary arguments. The
server's existing scope authorization is also checked defensively by the handler.

## Snapshot publisher contract

The fixed snapshot is `<OS-account-home>/.openclaw-personaledge/operations/remote-health.json`.
The OS account record supplies the home; neither the request, environment state-dir override,
nor plugin configuration can select another file. Unit fixtures inject a temporary OS-account
record only into the reader factory. The state and operations directories must be private and
owned by that account. The file must be a private, single-link regular file owned by that account.
Symlinks in the resolved path, oversized files, races, invalid UTF-8 and malformed data fail closed.

The watchdog publisher atomically replaces a mode-0600 file only after the corresponding checks
complete. The file is exactly one canonical `JSON.stringify`-compatible line, with these keys in
this order and a single final newline:

```json
{"schemaVersion":1,"observedAtEpochMillis":1800000000000,"gatewayHealthy":true,"dockerHealthy":true,"policyValid":true,"secretsClean":true}
```

`observedAtEpochMillis` is the observation completion time in integer Unix milliseconds.
`policyValid` includes the restrictive configuration and required live plugin checks.
`secretsClean` follows the successful secret audit. These booleans contain no log text, device IDs,
endpoints, paths, credentials, prompts, model output, or provider identifiers. A false flag is a
valid unhealthy observation, never silently converted to true. New keys or schema changes require
a separately reviewed server and Android update.

The reader accepts at most 1,024 bytes, requires age at most 600,000 ms and future skew at most
5,000 ms, and rechecks file identity after the bounded read. Android validates the exact response
schema and the same timestamp bounds again at response receipt. One plugin read runs at a time.
All failures expose fixed, content-free errors. A missing or stale snapshot means unavailable;
an old successful sample is not evidence that the host is healthy now or continuously available.
The read neither refreshes the watchdog nor contacts Docker or a provider.

## Validation

```bash
node --test scripts/openclaw/health-extension/snapshot.test.mjs
source scripts/android-env.sh
./gradlew --offline :core:openclaw:testDebugUnitTest :core:openclaw:lintDebug
```

The dependency-free host tests use only temporary files. They cover scope, argument rejection,
exact schema/canonical encoding, duplicate keys, age, size, permissions, symlinks/hardlinks and
concurrency. Tests do not change the running Gateway or phone and never invoke a model.

Live acceptance must separately verify that the managed manifest permits only this added RPC
extension, restrictive wildcard model-Tool denial still produces `tools.effective=0`, hello
advertises the method, an explicitly authorized Android read shows the fixed snapshot, and the
same read rejects an unavailable snapshot. Existing model and Android action Tool policies remain
unchanged by this extension.
