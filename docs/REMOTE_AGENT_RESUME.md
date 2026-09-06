# Resume checkpoint — 2026-09-05

The owner chose to stop after one successful real Fold8 answer to conserve Codex usage. Do not
resume broader development, paid inference, recovery drills or a soak automatically. No 24-hour
observation or follow-up automation was started. Existing Gateway/watchdog services remain active.
The full original goal is incomplete; this is an explicit owner-selected stopping point.

## Last accepted device flow

`reports/openclaw/20260905-integration/fold8-live-answer-retry.log` passed one explicitly selected
signed-release physical test. The host transferred the Gateway bootstrap credential only inside
an ephemeral RSA-OAEP/AES-GCM envelope through a loopback ADB forward; no credential was placed in
argv, transcript or shared storage. The one newly created Android pairing request was approved.
The production controller then authenticated over Tailscale HTTPS, submitted exactly one fixed
question, and received a completed 55-byte Korean answer meeting the fixed claims: 대한민국,
수도, 서울, and REMOTE-KO-OK. The test disconnected and returned to local mode. Conversation,
message and memory counts were unchanged. Its 173.864-second total includes human/host pairing
coordination; it is not model-response latency. Do not generalize this one simple question into
broad Korean intent/context quality acceptance.

Installed app SHA256: `9520a912fc15e64c4dbd8adb1a7e033573d12ef5b5a153cff7836cb9a82017dd`.
Installed test candidate SHA256: `787153c26687e23ad55ee431a7510cffc3ea0d48b18d3745ee469d826a7afe86`.
Pulled-back app hash and release certificate matched, firstInstallTime stayed 2026-08-23 18:10:37.
Preservation snapshots: conversations23, messages104, memories0, notifications57, model3659530240
bytes, all three existing credential-presence flags and exact settings digest unchanged before
pairing. Remote endpoint/device credentials are intentional new pairing state. Do not reinstall a
debug build, uninstall, clear data, or run broad connectedAndroidTest on the physical device.

## Accepted source/runtime boundaries

- GLM 404 was isolated by a controlled max_tokens/max_completion_tokens differential. A narrow
  pinned-runtime GLM field patch passed direct-provider and Gateway exact-reply/model/zero-tool
  receipts. Runtime is locally patched2026.8.1, not byte-identical upstream. Local LiteRT stays default.
- Optional remote UI uses the existing renderer, vault and consent infrastructure. Memory/context
  sharing uses the existing repositories with individually selected items; no second memory DB or
  automatic owner-memory export was added. The installed baseline also showed an exact one-use
  outbound preview per question. On 2026-09-06 the owner replaced that per-question confirmation
  with the standing remote-mode/connection decision, and remote turns are now written to the same
  conversation as local ones. That change is host verified only and is not in the installed APK.
- Latest installed app boundary: full host test/lint/debug/release builds, app354 JVM tests,
  coreOpenClaw95, AVD UI3 + expectedguards6 and production surface1 passed. Physical preservation
  and surface tests passed. Existing owner memory was not sent to the provider by these tests.
- Mac optional health RPC source has25 host tests and adds no model Tool. App button is installed,
  but the host extension remains disabled and physical health read is unaccepted.
- Managed Tailscale Serve passed live verification: exactly one Foreground HTTPS443 route, no
  Funnel, dynamic loopback ingress owned by the actual Gateway PID/UID. Canonical raw rollback
  preserves node routes; Services-only get/set-config is insufficient for this pinned CLI.
- Docker-aware watchdog/common update passed first real exit0 after ProcessType=Standard fixed
  macOS Background I/O throttling. Full runtime hashes, five-minute cadence and security policies
  remain. Live receipt: watchdog-update-20260905T143041Z-34638. Serve receipt:
  tailscale-enable-20260905T143223Z under the private OpenClawBackups directory.
- Host script suite34/34 passed without tracing after removing a fixture's machine-speed assumption.
  The normal production interval did not change. AC autorestart1/sleep0/standby0 were read back.

## Resume in this order, when the owner asks

1. Use the installed release to test stored-device-token reconnection (no bootstrap), real cancel,
   idle/network-loss handling, background/activity recreation, and an independently recorded
   process force-stop/relaunch. Named methods are in Fold8OpenClawLiveAcceptanceTest; never run
   the full class unbounded. Check existing gateway state/output2048 cap and no other active runs.
2. Deploy the reviewed optional health RPC with a managed, backed-up plugin allowlist/provenance
   update; retain zero model Tools. Validate its actual Fold8 read, scope/arguments and stale state.
3. Finish Android reminder proposal integration into existing local history STARTED turn,
   confirmation/orchestrator/ledger/interlocks. Core source21 new tests and lint passed, but that
   later core delta is not in the installed APK and the app integration has not begun. Files:
   core/agent RemoteReminderProposal.kt, ManualToolAgentController.kt and associated tests.
   Known review follow-up: a refused recollection currently resets retainedExecutions before
   consumeExecution rejects replay. Move that refusal before clearing the in-memory receipt while
   preserving active ownership, and add receipt-preservation regression. Durable replay protection
   remains; do not enable this unfinished source in the app yet. App integration should reuse the
   existing Room/history/TurnOutcomes; never add another DB/ledger or infer authority from prose.
4. Run scoped synthetic context/quality and reminder confirmation acceptance without uploading
   pre-existing owner memory or touching owner events. Rebuild/revalidate the final integrated APK.
5. With no active remote run, repeat the dedicated Colima stopped-service recovery drill. The first
   drill failed because normal Colima shutdown resets Docker context; fallback restored it. The
   narrow default-context correction is deployed but a successful retry drill is still missing.
6. Start a fresh schema2 24-hour soak only after final host configuration is stable. Record initial,
   periodic and final samples and validate Docker/VM/daemon/Gateway continuity and HTTPS. A short
   fixture, first watchdog exit0 or online PID is not24-hour proof. FileVault still requires owner
   unlock after cold boot. Add an owner-requested heartbeat only when observation actually starts.

No Git commit/push was requested or performed. The shared tree has substantial pre-existing work;
stage only explicitly intended paths if a later commit is requested. Preserve all reports/private
recovery backups and never include secrets, keys, generated APKs or the model in Git.
