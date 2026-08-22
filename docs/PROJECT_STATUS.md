# Project Status

Last reviewed: 2026-08-21 at commit `bd65501` plus the alarm-tool change.

This document is the current scope and evidence ledger. Treat these states separately:

- **Implemented**: code and regression tests exist.
- **Emulator verified**: Android behavior passed on the API 37 ARM64 AVD.
- **Physical accepted**: the intended flow passed on the Fold8 with a diagnostics receipt.

Do not promote a feature merely because a lower state passed.

## Current Snapshot

| Area | State | Evidence and limits |
|---|---|---|
| Gemma/LiteRT runtime | Physical accepted for the 4K GPU slice | Verified model import, streaming, cancellation, recovery, and natural `SEVERE` continuation passed on the Fold8. Natural `CRITICAL`, battery, fold/background, and 8K/16K remain unverified. Exact requested output length also failed in the sustained probe. |
| Tool safety | Emulator verified | Manual Tool calling, typed validation, confirmation, execution interlock, and durable SQLite at-most-once claims are wired. This is not exactly-once execution. |
| Local data foundation | Emulator verified | Room conversation/message/notification schema, DataStore settings, and Keystore AES-GCM vault passed 20 instrumentation tests. Notification capture and provider credentials are still not connected to any UI or runtime path. |
| Chat history | Emulator verified | Turns persist to Room and restore on launch; the history dialog switches, deletes one thread, and deletes all. Verified on the AVD by seeding the real database and driving the UI. **Bounded summaries are not implemented**: `replaceSummary` and `loadContext` exist and are tested, but nothing calls them, and no stored context is fed back into a turn. Turns remain stateless. |
| CalendarContract tools | Emulator verified | Query, create, and update are scoped to one pinned writable calendar; confirmation, replay protection, change digest, permissions, and setup UI are implemented. Tests used a local AVD calendar. |
| NAVER Calendar | **Not physically accepted** | The app has no direct NAVER login/API/CalDAV implementation. No real NAVER account, remote sync, Fold8, or Gemma calendar E2E receipt exists. |
| Standard alarm tools | Emulator verified | `alarm_set` creates one-shot and repeating alarms through `AlarmClock.ACTION_SET_ALARM`; `alarm_next` reads `getNextAlarmClock()`. Both confirmed on the API 37 AVD from the app's own foreground. The platform offers no way to list, edit, or delete alarms, so no such tool exists. Not exercised through a real Gemma turn or on the Fold8. |
| Kakao notification capture | Emulator verified, one gap | Listener binds, non-allowlisted posts are ignored, capture is off by default behind two gates, and search/retention/erasure are covered. Text is stripped of control tokens and invisible formatting before storage. **The accept path for com.kakao.talk itself is not end-to-end verified**: a test cannot post as another package, so real capture is a Fold8 check. |
| Third-party credentials | Emulator verified | Settings screen stores four keys — an NCP pair for maps and a Developers pair for search — into the Keystore AES-GCM vault. Values move one way: the UI reports presence only and cannot read a key back. Read per request by the network gateways. |
| Route estimate and web search | Implemented, **never called live** | `route_estimate` (geocode + directions) and `web_search` go through one transport pinned to two NAVER hosts, HTTPS only, no redirects, bounded time and body. Request construction, response parsing, and every transport refusal are covered by host tests against recorded shapes. **No live request has been made** — that needs real credentials and is a Fold8 step. Treat "the recorded shape matches production" as an assumption. |
| Conversation summaries | Emulator verified, **model path unproven** | After a turn, a thread with 10+ unsummarized messages is compressed on the tool-free budget (`maxSteps = 1`, so a tool call aborts before anything is prepared and no dialog can appear in the background). The stored summary is injected into the next turn's preamble within a 480-byte cap. Threshold logic, prompt construction, and storage are tested; the **actual model summary has never been generated**, so quality and added latency are unmeasured. |
| Setup and settings UI | Emulator verified | Model import, calendar permission and pinning, notification access and capture toggle, credential entry, and conversation history are all reachable from one screen. The four setup cards collapse behind a toggle and scroll inside a bounded area, because at phone width they had been pushing the prompt field off-screen entirely. Verified at 1080x2316 as well as the unfolded size. |
| Diagnostics and thermal policy | Physical accepted for current slice | Content-free rotating diagnostics and evidence collection work. `NONE` through `SEVERE` continue, `CRITICAL` cooperatively cancels, and `EMERGENCY+` immediately cancels; the latter two branches lack natural physical evidence. |
| Personal installation | Partially ready | Signing scripts exist, but a stable personal key and signed update-preservation receipt are still required. Play Store, AAB, and public CI are out of scope. |

At this snapshot, host unit tests report 209 passes. API 37 instrumentation reports 96 tests: 95 passes and one expected SELinux hard-link skip. Lint has no errors, and debug plus unsigned release APKs build.

## NAVER Calendar Qualification Gate

The current generic `CalendarContract` adapter is useful only if the intended NAVER calendar is
actually published there. NAVER's official [Calendar Open API](https://developers.naver.com/docs/login/calendar-api/calendar-api.md)
documents schedule creation only; it does not provide the required read/update operations. NAVER's
official [CalDAV help](https://help.naver.com/service/5620/contents/2426?lang=ko) explicitly says
Android is unsupported. Therefore, do not treat a local CalendarProvider test or an assumed DAVx5
configuration as NAVER support.

The CalendarContract implementation and its safety boundary remain valid for compatible local or
synced calendars. They are not evidence that NAVER publishes a calendar on Android.

Next decision and acceptance steps:

1. Confirm on the Fold8 whether the official NAVER Calendar app exposes the user's NAVER calendar
   as a writable `CalendarContract` row. Record account type, read, create, update, and remote sync.
2. If it does not, choose a documented alternative. The official create-only API cannot satisfy
   조회·등록·수정 by itself; any unofficial or UI-automation adapter must remain explicit personal
   sideload experimentation with separate credentials and safety review.
3. Run real Gemma Tool selection, confirmation, denial, replay, sync-conflict, and process-death
   tests before marking NAVER Calendar physically accepted.

## Next Milestones

Every feature on the MVP list is now implemented. What remains cannot be finished from a
development machine — each item needs the physical Fold8, real credentials, or the owner's own
password.

1. **Create and back up the personal signing key.** `./scripts/create-release-keystore.sh` prompts
   for a password only the owner should choose, so this cannot be done for them. Until it is run,
   `assembleRelease` produces an unsigned APK that cannot be installed. Then verify that
   `adb install -r` preserves the imported model and app data across an update.
2. **Qualify the NAVER Calendar transport** (see the gate below). The CalendarContract adapter
   works against any writable calendar; whether a NAVER calendar can be published there on Android
   is unresolved.
3. **Make the first live network calls.** Enter real NAVER keys and confirm the recorded response
   shapes match production for geocoding, directions, and web search.
4. **Run a real Gemma tool-selection pass on the Fold8.** No tool has yet been chosen by the actual
   model — every tool test drives the orchestrator directly. Korean tool selection, argument
   validity, confirmation, denial, replay, and process-death recovery all need device evidence.
   The same run produces the first real conversation summary, whose quality and latency cost are
   currently unmeasured.
5. **Complete Fold8 fold/rotation/background, battery, offline, and natural `CRITICAL` validation.**

## Chat History Boundaries

Stored: user prompts, assistant answers, and app-authored tool receipts. Not stored: the trusted
per-turn preamble (its date and calendar describe one moment and would be wrong on restore), raw
model thinking, and transient status notices such as thermal refusals.

Everything lives in `noBackupFilesDir` and is excluded from cloud backup and device transfer.
"전체 삭제" clears conversations and messages only; the action ledger is a separate database and is
deliberately untouched, so erasing history can never re-enable an already-executed side effect.

## Network Boundaries

One transport, two allowed hosts, HTTPS only, no redirects, bounded time and body. Web search
results are hostile third-party text that reaches a model prompt; they are data because a tool
result cannot invoke a tool and every side effect needs confirmation, not because any filter makes
them safe. Details in [`NETWORK.md`](NETWORK.md).

## Credential Handling

Third-party keys are typed by the user in settings and encrypted under a hardware-backed
AndroidKeyStore AES-GCM key. The rules:

- Values move one way. `CredentialStatus` carries presence and nothing else; there is no path that
  returns a stored key to the UI.
- The entry field uses plain `remember`, never `rememberSaveable` — saved instance state would
  write the typed key to disk in the clear.
- Rejection messages are app-authored and never echo what was typed.
- Keys never enter diagnostics, logs, chat history, or a model prompt.
- The key is device-bound and non-exportable, so a reinstall or new phone means re-entering the
  credential. That is by design; see [`RELEASE_AND_BACKUP.md`](RELEASE_AND_BACKUP.md).

## Notification Capture Boundaries

Notification access lets this app see every notification on the device. Two gates narrow it: the
system grant, and a `notificationCaptureEnabled` setting that is off by default and re-read on
every post. The interlock re-checks both before any read, so turning capture off also stops the
existing store from being searched.

Captured data is a local cache of notifications, never chat history — it cannot see muted rooms,
messages from before the feature was enabled, or hidden previews. Never describe it as reading
KakaoTalk. There is no send path and none is planned. Details are in
[`NOTIFICATIONS.md`](NOTIFICATIONS.md).

## Alarm Platform Limits

Android exposes no public API to list, edit, or delete alarms owned by the clock app. "알람 조회"
therefore means exactly one value: the device's next alarm time, with no label and no repeat
information. Do not add an `alarm_list` or `alarm_delete` tool without a documented mechanism.
Alarm creation is also fire-and-forget, so tool results say `requested`, never `created`. Details
and the measurements behind these claims are in [`ALARM.md`](ALARM.md).

Tests that create alarms cannot clean up through any API; they clear the clock app's data and are
emulator-guarded. Never run them on the Fold8.

## Deferred

Kakao new-message Accessibility automation, full Samsung Clock editing, Polestar control,
always-on voice, multimodal input, autonomous background workflows, and 32K context remain
experimental. Update this file in the same commit whenever one of these boundaries changes.
