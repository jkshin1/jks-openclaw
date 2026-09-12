# OpenClaw Gateway on the always-on Mac

> Archived Android relay runbook. For the active Mac/Telegram host agent, use
> [OPENCLAW_TELEGRAM.md](OPENCLAW_TELEGRAM.md). The tool-free policy and pinned adoption manifest
> below are historical and must not be reapplied to the current deployment.

This runbook prepares the current Mac as the single-owner OpenClaw Gateway for Personal Edge.
The deployment is pinned to OpenClaw `2026.8.1`, Node 26, profile `personaledge`, and
`openrouter/z-ai/glm-5.3-flash`. Android remains the only authority for device permissions,
owner confirmation, execution-time interlocks, idempotency, and the Action Ledger.

The repository contains non-secret deployment assets only. Tests and every `--dry-run` are
non-installing. An `--apply` command changes the owner's local OpenClaw state and must be run only
after its dry-run receipt has been reviewed.

Official references:

- [Installation](https://docs.openclaw.ai/install)
- [Gateway exposure runbook](https://docs.openclaw.ai/gateway/security/exposure-runbook)
- [Tailscale Serve](https://docs.openclaw.ai/gateway/tailscale)
- [Gateway CLI](https://docs.openclaw.ai/cli/gateway)
- [Secrets](https://docs.openclaw.ai/cli/secrets)
- [Backups and restore](https://docs.openclaw.ai/install/backups)

## Network choice and resource evidence, 2026-09-05

Tailscale is the selected private network transport for this deployment, not a requirement of
local LiteRT inference, existing memory, or the app's protocol. Android uses a validated HTTPS/WSS
endpoint and has no Tailscale SDK dependency. A different authenticated HTTPS path can replace it
without introducing another memory database or Android executor. Same-Wi-Fi access needs a
properly authenticated/TLS local endpoint; external access needs a reachable VPN or relay path.
A Telegram bot uses Telegram as the message intermediary and therefore does not require direct
phone-to-Mac connectivity. It is a different product channel, not a drop-in replacement for this
app's current UI/context/confirmation flow.

Short idle measurements in this session: Mac Tailscale app RSS 92,480 KiB plus network extension
54,176 KiB (combined about 143 MiB), each CPU 0.0%; Fold8 Tailscale PSS 94,237 KiB (about 92 MiB),
with two one-second top CPU samples at 0.0%. RSS and PSS are different accounting measures and
must not be presented as a direct cross-platform benchmark. These are idle snapshots, not peak
load or battery endurance evidence. Mac exit-node use/advertisement was false; phone exit-node
selection was not independently inspected. USB-connected acceptance cannot measure unplugged
standby drain. Tailscale documents mobile battery drain, particularly with exit nodes, and slower
fallback relay connections; keep both separate from Gateway/Docker watchdog overhead.

References: [Tailscale connection types](https://tailscale.com/docs/reference/connection-types),
[mobile battery guidance](https://tailscale.com/docs/reference/troubleshooting/mobile/battery-drains),
[Telegram update delivery](https://core.telegram.org/bots/api#getting-updates).

## Exact local layout

The scripts default to the currently prepared Mac layout:

| Item | Exact value |
| --- | --- |
| Profile | `personaledge` |
| State/config | `/Users/jk/.openclaw-personaledge/openclaw.json` |
| Workspace | `/Users/jk/.openclaw-personaledge/workspace` |
| Runtime | `/Users/jk/.local/openclaw-2026.8.1` |
| Homebrew Node | `/opt/homebrew/opt/node/bin/node` (major 26) |
| Gateway LaunchAgent | `ai.openclaw.personaledge` |
| Watchdog LaunchAgent | `com.personaledge.openclaw-watchdog` |
| Listener | loopback TCP `18789` |

The schema-4 managed manifest records the exact Node patch/binary hash, OpenClaw entry hashes, the
runtime-tree hash, and individual hashes for the managed wrapper, common library, and watchdog.
It is published atomically only after the live verifier succeeds. A candidate manifest is never
treated as an installed deployment. Because launchd runs the watchdog immediately at bootstrap,
the watchdog may use the exact private candidate only while the final marker is absent. It validates
the same schema, paths, permissions, and hashes, and reads that JSON once so an in-flight atomic
candidate-to-final rename cannot invalidate later checks. The final marker remains the last
installation publication and is authoritative for every subsequent watchdog process.

Candidate authority is deliberately narrower than an installed deployment. The watchdog validates
the candidate's exact private path, complete schema, runtime and management hashes, service files,
config, secrets, and plugins before any recovery branch is reachable. If Gateway health is bad
while that process still uses the candidate, it fails without restarting or re-arming the Gateway;
recovery waits for a later process that sees the final manifest. An untrappable kill or power loss
between watchdog bootstrap and final publication can leave the loaded job, candidate, and partial
assets behind. That is not a committed installation: the next adoption refuses those unmanaged
paths so the owner can boot out the watchdog and quarantine/review the partial state rather than
silently resuming it.

## Trust boundary

```text
Fold8 Personal Edge
  -> tailnet-only WSS (after explicit Serve enablement)
  -> Tailscale Serve on this Mac
  -> 127.0.0.1:18789
  -> OpenClaw Gateway 2026.8.1
  -> OpenRouter / GLM-5.3 Flash

OpenClaw text or typed proposal
  -> Android Kotlin schema validation
  -> owner confirmation
  -> execution-time interlock
  -> Action Ledger
  -> Android platform action
```

OpenClaw never directly owns Android camera, location, notification, calendar, alarm, browser,
or shell capabilities. An OpenClaw response is not evidence that an action ran.

## Restrictive baseline

The reviewed config is deliberately narrower than normal onboarding:

- Gateway mode is local and loopback-only. Auth is a SQLite-store token SecretRef;
  `allowTailscale=false`, rate limiting includes loopback-proxied requests, and Tailscale starts
  off. LAN bind, Funnel, public port forwarding, and `0.0.0.0` are outside this runbook.
- Control UI, terminal, CLI agents, `/v1/chat/completions`, `/v1/responses`, `/tools/invoke`, node
  browser/command/plugin-tool/skill surfaces, auto-pairing, and SSH verification are disabled.
- Top-level and Gateway tool deny lists begin with `"*"`. This is essential in `2026.8.1`:
  an empty allow list does **not** mean deny-all. `exec` is denied, elevated mode is off, and
  filesystem access remains workspace-confined as a redundant guard.
- The exact GLM model is the only allowed primary model; there are no model fallbacks or utility
  model. Thinking defaults to low and reasoning visibility to off. The provider requires request
  parameters, denies data-collection routes, requires ZDR, and selects the lowest-latency eligible
  same-model upstream. `allow_fallbacks=true` permits eligible upstream failover for the same model,
  not a different model.
- Only the bundled `openrouter` and `device-pair` plugins are allowed. Their origin, directory,
  loaded state, provider/command registrations, and live health loaded/error/unavailable state are
  verified. Memory slots,
  skills, MCP, ACP, hooks, cron, channels, browser proxy, worker runs, and Claude agent runs are off.
- Update checks/automatic update, telemetry, mDNS, favicons, external embeds, tool titles, and the
  session observer are off.
- Session maintenance is observation-only (`mode="warn"`) with reviewed warning thresholds of
  24-hour stale age, 64 entries, and a 100 MB/80 MB disk high-water pair. It never runs automatic
  cleanup in this baseline.
- The workspace contains only the reviewed restrictive `AGENTS.md`. Any prior `.git`,
  `BOOTSTRAP.md`, `MEMORY.md`, `USER.md`, `SOUL.md`, `BOOT.md`, or `memory/` tree is moved intact
  into a private timestamped quarantine during adoption; it is not deleted.

`agents.defaults.sandbox.mode="non-main"` does not sandbox the main agent process. The v1 safety
claim therefore comes from the independent wildcard tool policy plus the live
`tools.effective=0` gate—not from the sandbox setting. Do not run ordinary `openclaw onboard` after
deployment because it can recreate bootstrap state or broaden defaults.

### Transcript retention boundary

An external Personal Edge client cannot request OpenClaw's internal
`suppressPromptPersistence` capability. The Android remote-Gateway path is still default-off, so
Personal Edge has not created remote transcripts under this deployment. Once that path is enabled,
normal prompts and answers can be persisted in the OpenClaw session store.

The Android OpenClaw UI and wiring also remain a disabled scaffold. Its planned orderly
background/disable close path enqueues `chat.abort` for every reserved nonterminal run (bounded at
64) before closing the socket, but an enqueue result of `QUEUED` is not proof that the remote run
was cancelled. Abrupt process, socket, or network loss cannot send that abort. Under the current
remote-agent contract, such a run can continue for up to 30 minutes, incur provider cost, and leave
hidden logical SQLite state or physical WAL/free-page residue. That limitation must remain visible
in Android acceptance; the Gateway smoke's one-shot model-run cleanup is not evidence that an
abrupt production run was cancelled or securely erased.

The Android scaffold now has an OpenClaw-only durable revocation barrier even though no UI or
network wiring consumes it yet. Process startup closes the in-memory gate before reading state. A
fixed `0700` child of the platform-managed `noBackupFilesDir` holds only a version and random CAS
identity; its marker is `0600`, uses `O_NOFOLLOW`, and is published by temp-file fsync, atomic
rename, and directory fsync. Android's app-private `no_backup` root is allowed in its AOSP `0771`
form ([Android 16 `ContextImpl`](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/app/ContextImpl.java#843)), while the dedicated child and
marker enforce their stricter modes.

Every OpenClaw consent mutation closes the process gate and recovery barrier first, publishes that
marker, and only then edits DataStore. Disable removes the exact marker only after `enabled=false`
commits durably. Enable also uses a temporary marker, so a crash after `enabled=true` but before
the epoch is accepted recovers to false rather than silently reconnecting. Startup treats a marker,
leftover temp, malformed entry, permission/UID mismatch, or read uncertainty as blocked; it attempts
a durable false repair and retains the blocking entry on failure. An older enable/disable cannot
remove a newer marker because unlink is exact-token CAS and all commits are serialized and
epoch-checked. If marker creation or publication fails, an explicit disable still best-effort
commits `enabled=false`, but returns `FAILED`, keeps both gates blocked, and never clears a partial
or unproven marker. A mutation killed before its first marker fsync is necessarily unacknowledged,
so a future UI must not display completion before the returned durable result succeeds. These are
JVM, Android-source-compile, and lint invariants, not a process-kill instrumentation or Fold8 receipt.
All future OpenClaw toggles, including a settings-reset path, must enter this feature-specific
controller before calling the low-level SettingsRepository mutation; direct writes bypass the
marker contract and are not an approved product wiring.

The baseline deliberately keeps `session.maintenance.mode="warn"`: the configured `24h`, `64`,
`100mb`, and `80mb` values report pressure but do not prune session rows, transcripts, archives, or
other history. Do not add cleanup to the watchdog. Before any future switch to `enforce`, run and
review the owner-specific impact report:

```bash
/Users/jk/.local/openclaw-2026.8.1/.personal-edge-management/bin/openclaw \
  sessions cleanup --dry-run --all-agents --json
```

Enforcement requires a separate owner approval because it can delete conversation history. Under
the exact `2026.8.1` cleanup implementation, an active or admitted session remains protected. An
ordinary unprotected session becomes stale-cleanup eligible after 24 hours of inactivity; cleanup
first archives its unreferenced transcript and then removes its SQLite session/transcript state.
Pinned, archived, and durable externally referenced sessions have additional protection. The
64-entry cap and disk budget are independent pressure thresholds, so an enforcement proposal must
review their victim preview too rather than treating 24 hours as an unconditional minimum.

## Host readiness and 24-hour limits

Run the read-only readiness check:

```bash
./scripts/openclaw/host-readiness.sh
```

For an always-on desktop, AC power should use `sleep=0`, `standby=0`, `powernap=0`,
`lowpowermode=0`, `womp=1`, `tcpkeepalive=1`, and `autorestart=1`. Changing power settings is an
owner-approved administrator action; the scripts only report them.

This Mac cannot provide unconditional 24-hour availability:

- The supported service is a per-user LaunchAgent. It starts after that user logs in and survives
  screen lock, but does not run at the pre-login screen.
- FileVault requires owner unlock after a cold boot or some power failures. This runbook never
  disables FileVault or enables automatic login.
- `autorestart=1` can reboot after power returns but cannot unlock FileVault.
- A UPS should cover the Mac, router, and network termination. A second host or hosted service is
  required to survive failure of this Mac, its power, or its Internet connection.

The Gateway plist uses `RunAtLoad` and `KeepAlive`. A five-minute watchdog first validates runtime
hashes, restrictive config, clean SecretRefs, model auth, bundled plugins, and the exact Gateway
and watchdog plists. Runtime hashing walks and hashes the large npm tree in one isolated system
Perl process while retaining the legacy deterministic digest; it does not spawn one process per
file. It will not restart a drifted service. After a safe re-arm it also verifies the required live
plugins.

### Colima sandbox runtime at login

Non-main sandbox sessions require a working Docker daemon even when model Tools are denied.
The current host uses the separate Colima `personaledge` profile: 2 CPUs, 2 GiB memory,
10 GiB data disk and 10 GiB root disk, Docker on VZ/virtiofs, with only
`/Users/jk/.openclaw-personaledge/sandboxes` mounted writable. Review that existing profile before
loading its owner LaunchAgent; the installer validates these settings and never rewrites the YAML.

```bash
./scripts/openclaw/install-colima-runtime.sh --dry-run
./scripts/openclaw/install-colima-runtime.sh --apply
```

The profile must be stopped before initial installation. Colima 0.10.3 returns immediately if
`start --foreground` finds an already-running profile, so finish any container work and perform
the controlled `colima stop --profile personaledge` before transferring ownership. The installer
does not stop it. This follows the foreground service pattern in the installed Homebrew formula;
the [upstream start implementation](https://github.com/abiosoft/colima/blob/v0.10.3/cmd/start.go)
also shows its already-running and signal-handling behavior.

The `com.personaledge.colima-runtime` LaunchAgent uses RunAtLoad, KeepAlive, a 30-second restart
throttle, `--save-config=false`, and private stdout/stderr logs under
`~/.openclaw-personaledge/operations/colima-runtime/`. An existing different plist is refused.
After loading, verify `colima status --profile personaledge`, `docker info`, and the exact sandbox
image before inference. Foreground process supervision does not itself prove Docker responsiveness
or VM recovery; include those checks in the runtime acceptance. It retains the same login and
FileVault limitations as the Gateway, and no Gateway management manifest or watchdog is changed.

## Existing current-Mac path: migrate, harden, then adopt

Adoption intentionally preserves the config, SQLite secret/auth stores, OAuth profiles, service
environment, and LaunchAgent definition. Consequently, an existing permissive config must be
hardened first; adoption will fail closed rather than silently rewriting it.

First migrate supported plaintext credentials using OpenClaw's official
`secrets configure --apply` flow. Review the generated plan and keep only structured `store`
SecretRefs. The success gate is `secrets audit --check --json` with plaintext, unresolved,
shadowed, store-residue, and legacy-residue counts all zero. Never create a profile `.env`.

Then run the hardening plan and apply it only after review:

```bash
./scripts/openclaw/harden-existing.sh --dry-run
./scripts/openclaw/harden-existing.sh --apply
```

Hardening requires the exact runtime and existing service definition, structured Gateway token
ref, usable OpenRouter auth, clean secrets audit, and bundled provider/pairing plugins. Before its
first installed OpenClaw CLI call, it creates a temporary consistent recovery snapshot of config,
service files, and both SQLite stores. A normal successful run deletes it; an unverifiable failed
preflight retains its private mode-`700` path for recovery review. The CLI could perform internal
schema bookkeeping, so “no change” receipts are deliberately limited to authored config/service
bytes and credential/auth-profile logical rows.

The hardening candidate is an exact full baseline applied through OpenClaw's validated
`config patch` path with explicit top-level replacement/deletion. Apply creates and verifies a full
persistent backup before its config write. It preserves the Gateway/OpenRouter SecretRef objects
and SQLite auth-profile/OAuth/secret rows. If any post-write restart, config, auth, plugin, model,
or listener check fails, it atomically restores the original config bytes and restarts the previous
definition.

OpenClaw owns `meta.lastTouchedVersion` and migration stamps, so the hardening patch neither
replaces nor deletes `meta`. The post-write comparison requires every reviewed non-`meta` field to
match the candidate exactly, then independently requires the `2026.8.1` managed stamp. A rejected
patch prints only a content-free failure and the path of its private stderr receipt; it never copies
that receipt to the console.

Next adopt the exact installation:

```bash
./scripts/openclaw/install-gateway.sh --adopt-existing --dry-run
./scripts/openclaw/install-gateway.sh --adopt-existing --apply \
    --acknowledge-transient-session-write
```

The dry-run executes no script-authored installed-state write. It compares authored config/service
files byte-for-byte and logical SHA3 fingerprints of `secret_store_entries`, `mcp_oauth_stores`,
and `auth_profile_store`. Raw SQLite/WAL/SHM hashes are deliberately not used because a live
Gateway may legitimately update unrelated runtime rows and WAL bytes. No secret value is printed
or copied to a receipt. Before the first installed CLI call, adoption also makes the same private,
temporary config/service/online-SQLite recovery snapshot used by hardening. It is removed after a
verified preflight and retained for review if that preflight fails before its after-snapshot can be
confirmed.

Apply repeats the preflight, makes and verifies a backup first, stages management assets, moves the
old workspace intact to quarantine, restarts the existing Gateway, and verifies exact runtime,
config, secrets, auth, cold/live plugins, plist, loopback listener, and RPC health against an
unpublished candidate manifest. Failure restores the original workspace, reloads and health-checks
that restored Gateway generation (or stops it best-effort), and quarantines staging and partial
management/watchdog assets. The final manifest is the last atomic publication. Apply also creates
and deletes one message-free incognito session to prove the effective tool inventory is empty, so
the separate acknowledgement flag is mandatory; dry-run does not create that session.

## Fresh installation

In-place runtime repair is intentionally disabled because a partial npm rewrite could leave a
running unmanifested tree. Stage a fresh pinned runtime and repeat the reviewed migration instead.
If fresh installation fails after service creation, cleanup unloads/quarantines the watchdog,
stops and disables the Gateway best-effort, and reports if launchd/listener shutdown cannot be
proven.

Export the OpenRouter key from the password manager into a new current-user-owned mode-`600`,
one-line file. Do not put it in a shell argument, config string, or `.env`.

Use the existing Homebrew Node 26:

```bash
./scripts/openclaw/install-gateway.sh \
    --method homebrew-node \
    --openrouter-key-file /absolute/private/path/openrouter-key.txt \
    --apply \
    --acknowledge-transient-session-write
```

Or use the official user-prefix installer with pinned Node `26.5.0`:

```bash
./scripts/openclaw/install-gateway.sh \
    --method official-user \
    --openrouter-key-file /absolute/private/path/openrouter-key.txt \
    --apply \
    --acknowledge-transient-session-write
```

Fresh install refuses an unrelated config, non-empty state/workspace/runtime, or unmanaged
manifest. It creates the Gateway token in a mode-`600` temporary file and streams both credentials
directly into the SQLite secret store. Config contains only structured store SecretRefs. The key
transfer file is not deleted automatically; remove it only after verifying the password-manager
copy. The official installer URL is mutable, so archive/review it separately when stronger
supply-chain reproducibility is required.

## Tailscale Serve

Install Tailscale from its signed macOS distribution, connect this Mac and Fold8 to the same owner
tailnet, and review ACLs. Then explicitly enable Serve:

```bash
./scripts/openclaw/enable-tailscale-serve.sh --apply
```

The script first requires Tailscale `BackendState="Running"` and `Self.Online=true`, then captures
the complete owner Serve configuration with `serve get-config --all`, its JSON status, and the exact
OpenClaw config bytes. It also creates a verified private OpenClaw backup before changing the
profile. It reasserts loopback/token auth, enables managed tailnet-only Serve, restarts, checks live
plugins and health, runs a deep audit, proves the Gateway still listens only on loopback, and
validates `tailscale serve status --json` as exactly one HTTPS route whose proxy is loopback port
`18789` with no backend path and every `AllowFunnel` value false.

The signed app-bundled executable is preferred over a PATH binary, and every Tailscale invocation
sets `TAILSCALE_BE_CLI=1` so macOS cannot treat it as a GUI launch. Any later failure restores the
original OpenClaw config bytes, restarts and health-checks that generation, restores the complete
owner Serve prestate with `serve set-config --all`, and compares canonical post-restore config and
status to the snapshots. If either restoration cannot be proven, it stops/disables the Gateway
best-effort and retains the private recovery snapshot; it must not claim rollback while a new route
may remain. `allowTailscale=false` means Tailscale identity headers do not replace Gateway
token/device authentication. Control UI remains disabled, and dangerous host-header origin
fallback remains off. Do not forward TCP 18789 on the router.

## Verification and paid smoke

Run after installation, login/reboot, or any config, Node, package, or Tailscale change:

```bash
./scripts/openclaw/host-readiness.sh
./scripts/openclaw/verify-gateway.sh --acknowledge-transient-session-write
./scripts/openclaw/security-audit.sh --deep
./scripts/openclaw/status-gateway.sh
```

Full live verification creates and deletes one message-free, read-only incognito session to prove
the running `tools.effective` inventory is empty. The acknowledgement covers that transient logical
write and its crash/WAL residue boundary. It does not authorize a model call. OpenClaw's config,
secret, and security audit commands can also perform permission hardening, so these full gates are
not an observation-only loop even when no configuration value changes.

The empty probe uses a unique, lower-case `agent:main:dashboard:incognito-...` key, as required by
the pinned server's incognito-session contract. The local token-auth CLI intentionally omits a
device identity, so this single-dispatch probe omits the optional, principal-scoped
`sessions.create.idempotencyKey`; it never retries creation and arms exact-key deletion before
sending. The paid model run retains its own independent idempotency key.

Successful verification removes its temporary receipts. If a private CLI step fails, the console
prints only the failed stage and a path to the retained mode-`700` verification directory; stderr
content is not copied to the console. Review that directory locally, then remove it after diagnosis.
This also preserves the actionable failure evidence when adoption later restores the original
workspace and quarantines its unpublished management candidate.

The first provider request is explicit because it costs money:

```bash
./scripts/openclaw/smoke-model.sh --acknowledge-model-charge
```

That flag acknowledges both the paid provider request and smoke's own transient incognito session,
run-owned logical state, cleanup-failure risk, and possible SQLite WAL/free-page residue. It is
local to the paid smoke script and never bypasses the verifier's separate
`--acknowledge-transient-session-write` requirement.

Before inference, smoke creates one message-free, read-only, incognito session and requests the
running Gateway's `tools.effective` inventory. The sum of every inventory group's tools must be
zero; the temporary session is then deleted. Only then does it submit the fixed-marker request with
the `agent` Gateway RPC using a fresh `model-run-<uuid>` session id, its exact
`agent:main:explicit:<session-id>` key, `modelRun=true`, `promptMode="none"`, `deliver=false`,
`disableMessageTool=true`, `cleanupBundleMcpOnRunEnd=true`, and `thinking="low"`, followed by
`agent.wait`. The independent idempotency key is armed for outcome-unknown cleanup observation
before the request is sent. Do not substitute the ordinary `openclaw agent` CLI path: that path
can create a durable transcript.

The 2026-09-05 public OpenRouter catalog marks GLM-5.3 Flash reasoning mandatory, with `low`,
`high`, and `max` supported. The pinned adapter maps `thinking="off"` to unsupported effort
`none`, so the smoke uses `low` with the existing 2,048-token output cap. This is provider
compatibility preparation; successful paid inference remains a separate live gate. Sources:
[model catalog](https://openrouter.ai/api/v1/models) and
[reasoning options](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens#discovering-per-model-reasoning-options).

The 2026-09-05 owner-approved test reached OpenRouter once and returned HTTP 404,
`No endpoints found that can handle the requested parameters`; no model answer was produced.
An earlier local attempt failed on missing Docker before provider dispatch; the dedicated Colima
runtime above now satisfies that prerequisite. The generic Gateway `model_not_found` wording does
not establish an invalid id: the official [model endpoints](https://openrouter.ai/api/v1/models/z-ai/glm-5.3-flash/endpoints)
and [ZDR endpoints](https://openrouter.ai/api/v1/endpoints/zdr) include this model. Preserve
`require_parameters=true`, `data_collection="deny"`, and `zdr=true` while diagnosing the fully
wrapped request. Public metadata cannot prove account-specific eligibility or the exact rejected
parameter. A failed request does not grant another paid retry; the first-request approval is used.

The zero-tool verifier arms deletion of its exact synthetic requested key before `sessions.create`
is sent, covering an outcome-unknown transport failure. The returned key must equal that request.
If it does not, verification fails and retains the private receipt, but never auto-deletes the
untrusted returned value because it could name an unrelated owner session.

The terminal reply must equal the fixed marker. The server-authored terminal receipt must identify
requested and effective `openrouter` / `z-ai/glm-5.3-flash`, report no reroute, and contain zero
successful tool names. Exact-model evidence is deliberately the conjunction of reviewed config,
successful `models status --check`, and this terminal receipt—not a recursive search for a model
name in arbitrary CLI output.

The one-shot RPC uses no reusable or visible session context. Exact `2026.8.1` normally deletes
its run-owned logical session effects in the run-finally path. Smoke derives only its own explicit
and internal keys/ids and counts matching rows across the agent's session/transcript tables before
and after the call. It never selects transcript or owner content; both counts must be zero, with a
short bounded poll to cover terminal-event/cleanup ordering and an outcome-unknown request path.
This is a logical-row check, not secure erase: a crash or cleanup failure can leave rows, and old
bytes can remain in SQLite WAL/free pages or storage media even after logical deletion. This proves
one bounded text turn at that time—not Korean dialogue quality, Android transport, Fold8 behavior,
or sustained availability.

## Observation-only 24-hour soak

Run a full owner-approved verification and deep security audit first. The soak deliberately does
not repeat OpenClaw's secret/model/config audit commands because those commands can permission-
harden files. It instead reuses static restrictive-policy, SecretRef, ownership, permission, plist,
runtime-hash, loopback-listener, and authenticated live-health checks. Prior full audit evidence is
therefore a prerequisite, not something the soak establishes.

Start the default 24-hour observation with five-minute samples:

```bash
./scripts/openclaw/soak-acceptance.sh --start
```

The receipt path is printed and is an immediate child of the configured private backup root. To
continue after an interruption or reboot, or to validate a completed receipt without accessing the
live profile or host, use that exact absolute path:

```bash
./scripts/openclaw/soak-acceptance.sh --resume \
    --receipt-dir "/absolute/private/path/soak-YYYYMMDDTHHMMSSZ-PID"
./scripts/openclaw/soak-acceptance.sh --validate \
    --receipt-dir "/absolute/private/path/soak-YYYYMMDDTHHMMSSZ-PID"
```

The runner never invokes a model, creates or deletes a session, cleans history, changes config or
power settings, or restarts/repairs Gateway, watchdog, or Tailscale. Initial and final boundary
samples run the non-mutating verifier lane, status probe, readiness report, AC-power snapshot,
FileVault status, and console-user comparison. Periodic samples use only direct health, exact
loopback listener, launchd, Tailscale, and boot observations. Raw command output is held only in a
private temporary directory and deleted; the mode-`700` receipt contains mode-`600`, content-free
bounded JSON metadata.

Samples form an exact-schema, monotonically indexed journal. A fixed `lockf` lock excludes two
resumers. The runner validates every derived gap, boot and launchd delta, gate outcome, summary,
result, ISO timestamp/epoch pair, and completion chronology; it can reconcile at most one fully
written sample committed before a manifest update. Probe and sleep children receive the lock file
descriptor closed, so a surviving child cannot retain the receipt lock after its runner dies.
Start removes only exact script-owned stale raw-temp names whose runner PID is gone; resume and
validation, after acquiring the receipt lock, remove only raw temps carrying that receipt id.
It fsyncs the sample before its rename and the journal directory before fsyncing and publishing the
manifest update. This supports recovery from runner interruption, reboot, and ordinary OS crash.
macOS `fsync` is not `F_FULLFSYNC`, so the receipt does **not** prove survival of sudden mains loss
or drive-cache loss. In particular, a Mac without a UPS is not disaster-resilient.

Receipt results have deliberately narrow meanings:

- `observed-window-pass` means only that the complete target window was actually observed from its
  first `initial` sample through one `final` sample, required checks passed at every recorded
  sample, no reboot or Gateway launchd run/exit/crash delta was seen, and configured Tailscale
  ingress was ready at every recorded sample. A shorter outage between five-minute samples can go
  unseen, so this neither proves uninterrupted uptime nor establishes availability beyond that
  observed window.
- `pending` means the window completed but an external ingress gate was unmet. With Tailscale mode
  off and no Tailscale installation, the receipt records explicit pending rather than pretending
  remote availability was tested.
- `diagnostic` is a deliberately short fixture/operator observation and makes no 24-hour claim.
- `failed` means a required sample, continuity rule, listener, health, power, service, or journal
  invariant failed. A failed initial gate is finalized immediately rather than waiting 24 hours.

The boundary AC gate requires `sleep=0`, `standby=0`, and `autorestart=1`. A running Gateway must
have a positive parsed PID and launchd `runs` counter; a loaded watchdog must expose its `runs`
counter. Generation and crash counters remain nullable where launchd omits them. The stored
FileVault state, active-user match, UPS presence, and single-host topology are explicit limitations,
not availability gates: FileVault/pre-login ownership still blocks cold-boot recovery, absence of a
UPS still leaves power continuity unproved, and one Mac has no redundancy. The readiness report is
stored as advisory while its directly measured AC settings and required static/live checks remain
hard gates. A successful result must never be described as unconditional or disaster-resilient.

## Logs, diagnostics, backup, and restore

`status-gateway.sh` prints non-secret versions, bind/ingress mode, service/RPC health, and log
metadata. Raw logs and transcripts can contain private content and remain outside the repository.

Create a bounded sanitized support export only to the configured private backup root:

```bash
./scripts/openclaw/export-diagnostics.sh \
    --output "/Users/jk/Library/Application Support/PersonalEdge/OpenClawBackups/diagnostics.zip"
```

Review even a sanitized archive before sharing. Never attach profile state, raw logs, session
databases, auth profiles, service environment, secret audit details, or backup archives to an
issue.

Create a consistent archive through OpenClaw's SQLite online-backup path:

```bash
./scripts/openclaw/backup-gateway.sh --apply
```

Never copy a live `.sqlite`, `-wal`, `-shm`, or `-journal` with Finder, `cp`, or `rsync`. Backups
contain credentials, device auth, sessions, and history. Keep a verified encrypted off-Mac copy;
a backup stored only on this Mac is not disaster recovery.

Restore always stages into a fresh directory and never activates it:

```bash
./scripts/openclaw/restore-gateway.sh \
    --archive /absolute/private/path/openclaw-backup.tar.gz \
    --target /absolute/private/path/restored-openclaw \
    --apply
```

Before activation, stop Gateway/watchdog, make a new backup, inspect the archive manifest, restore
all recorded custom paths, run database preflight if versions differ, and repeat config, secrets,
plugins, model, health, and deep-security gates. Restoring sessions/approvals/delivery state is time
travel; review pending work and re-pair integrations.

## Emergency rollback and change control

If exposure or workspace policy may have drifted:

```bash
./scripts/openclaw/rollback-gateway.sh --apply
```

Rollback stops/disables Gateway first, attempts a forensic online backup, quarantines unexpected
workspace content, writes the loopback/tool-free baseline, rotates the Gateway token in the SQLite
store, and leaves Gateway stopped. After reviewing backup, quarantine, config, credentials,
Tailscale state, and audit, an owner may explicitly run it again with `--restart`. Rotate the
OpenRouter key separately if it may have leaked.

- Never use `latest`, `openclaw update`, or a floating Node path.
- Stage an exact new release in a new version directory and repeat backup, schema, runtime hash,
  zero-tool, provider, and live acceptance gates.
- Never place credentials in plist, config plaintext, command arguments, or `.env`.
- Never relax tool, secret, listener, or Android confirmation policy merely to pass a test.
- Mac tools belong to a later reviewed milestone. Android state-changing work remains a typed
  proposal validated and confirmed by Kotlin.
