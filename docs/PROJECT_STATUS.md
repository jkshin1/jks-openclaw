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
| Diagnostics and thermal policy | Physical accepted for current slice | Content-free rotating diagnostics and evidence collection work. `NONE` through `SEVERE` continue, `CRITICAL` cooperatively cancels, and `EMERGENCY+` immediately cancels; the latter two branches lack natural physical evidence. |
| Personal installation | Partially ready | Signing scripts exist, but a stable personal key and signed update-preservation receipt are still required. Play Store, AAB, and public CI are out of scope. |

At this snapshot, host unit tests report 161 passes. API 37 instrumentation reports 60 tests: 59 passes and one expected SELinux hard-link skip. Lint has no errors, and debug plus unsigned release APKs build.

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

1. Resolve and qualify the NAVER Calendar transport above.
2. Add bounded conversation summaries and feed stored context back into a turn. Persistence,
   restore, and deletion are done; summarization and context injection are not, and both need a
   token budget decision first — the prompt cap is 2,048 bytes against a 4,096-token context.
3. Add NAVER Maps travel time, Kakao notification capture/search, then web search. Android alarms
   are implemented; they still need a real Gemma tool-selection run and Fold8 evidence.
4. Create and back up one personal signing key; verify `adb install -r` preserves model and data.
5. Complete Fold8 fold/rotation/background, battery, offline, and natural `CRITICAL` validation.

## Chat History Boundaries

Stored: user prompts, assistant answers, and app-authored tool receipts. Not stored: the trusted
per-turn preamble (its date and calendar describe one moment and would be wrong on restore), raw
model thinking, and transient status notices such as thermal refusals.

Everything lives in `noBackupFilesDir` and is excluded from cloud backup and device transfer.
"전체 삭제" clears conversations and messages only; the action ledger is a separate database and is
deliberately untouched, so erasing history can never re-enable an already-executed side effect.

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
