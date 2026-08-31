# App-owned reminders

Personal Edge reminders are durable app data, not aliases for Samsung Clock alarms or calendar
provider reminders. The feature remains usable when the model is missing, unloaded, hot, or has
failed: direct Compose controls call deterministic Kotlin use cases.

## Contract

- Room is the source of truth for title, absolute trigger instant, zone, recurrence, precision,
  state, schedule version, snooze, lead time, escalation policy, creator, and delivery history.
- `reminder_create`, `reminder_update`, and `reminder_cancel` are local writes. They use the shared
  strict parser, immutable confirmation preview, execution-time interlock, and Action Ledger.
- Notification permission is a delivery gate, not a storage gate. A confirmed create or update is
  retained in Room with a visible blocked state when posting is unavailable, then reconciled after
  the owner grants permission. Saving a reminder never claims that an alert was delivered.
- `reminder_query` is a bounded local read. Date-bearing requests use reminders; `alarm_set` is only
  for the next occurrence of a time-of-day and optional weekdays.
- Recurrence is a small closed grammar: `daily` or `weekly:mon,tue,...`. Date/time parsing and DST
  uniqueness belong to Kotlin, never the model.
- A schedule version is embedded in alarm/work payloads and notification actions. Stale delivery,
  complete, snooze, or cancel requests are refused rather than applied to a replacement schedule;
  stale notification-button attempts leave a content-free delivery-history receipt.

## Delivery and recovery

Exact requests use `AlarmManager` when `canScheduleExactAlarms()` is true. Otherwise the scheduler
records a visible degraded state and uses unique WorkManager work. Notification permission and the
global notification-enabled state are independent delivery gates; a blocked notification is a
typed delivery result, not a successful alert.

Returning from either notification settings or exact-alarm settings requests a unique Room-to-OS
reconciliation, so a newly granted capability does not wait for the next periodic worker.

Boot, time change, time-zone change, package replacement, app start, and explicit setting changes
request reconciliation from Room. Work and alarm identities are stable per reminder, while each
mutation increments the version. Old one-shot reminders imported from another device remain
overdue without immediately firing; old recurring reminders advance deterministically to the next
future occurrence. A device that missed several occurrences advances in one step rather than
posting a catch-up burst, and a daily/weekly local time that falls inside a DST gap or overlap is
skipped instead of being silently shifted or assigned an arbitrary offset.

Reminder notifications are private on the lock screen and expose bounded complete and snooze
actions. The third action is one hour during the day and next 09:00 in the canonical zone after
18:00; DST ambiguity is refused. Calendar-owned leave-by alerts instead prioritize the visible
`완료 / 10분 후 / 일정 열기` set. Every reminder shows a closed source label, and tapping the
notification body opens the exact reminder's source detail without loading the model. Quiet hours,
owner-selected daily-brief time, weekend policy, conflict counts, overdue/yesterday-snoozed counts,
next-future-event countdown, and leave-by recommendations are Kotlin-owned. A failed live route lookup uses a fixed
60-minute total safety buffer and labels the estimate unavailable instead of reusing old traffic.
Proactive route planning has a separate default-off consent and never loads the model in background
work. Its worker owns only `SYSTEM + CALENDAR` rows, versions traffic changes, refuses duplicate
posting after delivery, leaves an existing row untouched when CalendarContract is unavailable, and
debounces live event edits in addition to app-start, periodic, boot, time, and time-zone recovery.
A snooze is an absolute delivery instant; lead time is never subtracted a second time.

Daily brief, proactive route planning, and commitment proposals each combine durable opt-in with
the process-wide latest-request gate. Pending changes close immediately; app-scope serialized
persistence lets only the latest successful enable reopen a feature. Workers and the notification
listener re-read effective consent immediately before route lookup, notification posting, or
proposal storage, so an older settings snapshot cannot cross a later disable request.

## Calendar and commitment boundaries

Several calendars may be explicitly selected for reads, but only one pinned writable calendar is
the write target. App reminders do not silently become calendar events. KakaoTalk notification text
may create a review-only commitment proposal only when notification capture and the separate
proposal opt-in are both enabled. Promotion requires an explicit user action; the proposal itself
never schedules anything. Independently, the owner can put arbitrary text into the direct Inbox
without enabling notification capture. An undated row asks once with the closed choices today
evening, tomorrow morning, or a specified date; only that explicit choice promotes it. Direct
controls also expose the repository's closed
daily/weekday/weekly recurrence grammar and the separately selected until-completed escalation.

## Evidence boundary

JVM and Android-test sources cover recurrence, DST, version races, fallback, notification actions,
reconciliation, proposal promotion, and import normalization. On 2026-08-25 installed rc8 passed a
focused Fold8 denial receipt: one fixed synthetic reminder was stored as
`BLOCKED_NOTIFICATION_PERMISSION`, no notification was active, and cleanup deleted only its exact
primary key. This proves storage and visible blocked state while posting
is unavailable, not delivery. `POST_NOTIFICATIONS` remains ungranted, so posting, complete/snooze,
stale-action refusal on the notification surface, Doze, reboot, process-death delivery,
exact-alarm denial, and time/time-zone changes remain open. Run the explicit-serial physical matrix
in `PROJECT_STATUS.md` before calling those paths accepted.

A fixed real-Gemma matrix on final rc8 selected `reminder_create`, `reminder_update`, and
`reminder_cancel`; all three reached the production confirmation boundary and were explicitly
denied before ledger claim or execution. The exact synthetic reminder and isolated conversations
were removed. The accepted run took 61.067 seconds; one preceding same-build run stopped with a
generic update failure, so this is bounded selection/denial evidence rather than an accuracy rate.
When the model emits an invalid date-time, Kotlin first reuses one exact ISO local timestamp only if
the current typed request contains exactly one; the confirmation preview remains authoritative. If
that hint is unavailable, one static content-free retry is allowed, after which the turn fails
closed. The diagnostic allowlist includes all four reminder Tools with their declared risks.
