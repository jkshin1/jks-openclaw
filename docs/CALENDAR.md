# Calendar: NAVER Qualification Through CalendarContract

## Why the device calendar, not the NAVER API

NAVER Calendar is the intended calendar. The implemented adapter uses Android
`CalendarContract`, not NAVER's Open API, because the Open API cannot cover the full requirement:

> [NAVER Calendar Open API](https://developers.naver.com/docs/login/calendar-api/calendar-api.md)
> documents exactly one endpoint, `POST https://openapi.naver.com/calendar/createSchedule.json`,
> described as "캘린더 일정 추가". There is no endpoint for reading, updating, or deleting a
> schedule.

Create-only covers only one part of 조회·등록·수정 and still needs OAuth and network access.
The `CalendarContract` tools can perform all three operations on a compatible writable calendar,
but a supported way to publish a NAVER calendar there has **not** been established. NAVER's
[official CalDAV help](https://help.naver.com/service/5620/contents/2426?lang=ko) explicitly says
Android is unsupported. Current emulator tests use a local calendar, not a NAVER account.

## Qualification required on the Fold8

1. Check whether the official NAVER Calendar app exposes the NAVER account as a writable
   `CalendarContract` calendar.
2. If it does, grant calendar permission, pin only that row, and verify remote read/create/update
   synchronization without exposing other accounts.
3. If it does not, stop: do not label a local calendar or an unverified third-party CalDAV setup
   as NAVER integration. Select and review a different personal-use adapter first.

Until a writable calendar is pinned, the calendar tools refuse to run. That is the interlock
working, not a bug. Pinning alone proves only CalendarContract access, not NAVER identity or sync.

## The scope boundary

A compatible calendar provider may place several accounts together — work calendars, shared
calendars, birthdays, and possibly the target calendar. `ScopedCalendarGateway` filters every read
and write to the one pinned calendar:

- `writableCalendars()` returns the pinned calendar or nothing;
- `queryEvents()` drops rows from any other calendar before the model sees them;
- `findEvent()` returns null for an event id outside it;
- `insertEvent()` refuses a draft aimed elsewhere;
- `updateEvent()` goes through the scoped read first.

So a model that guesses an event id, or is talked into naming another calendar, still cannot read
or modify anything outside the calendar the user pinned.

## What the tools accept

| Tool | Risk | Confirmation | Notes |
|---|---|---|---|
| `calendar_query` | READ_ONLY | none | Window ≤ 60 days, ≤ 20 events, truncation is reported |
| `calendar_create_event` | DATA_WRITE | required | Writes only to the pinned calendar |
| `calendar_update_event` | DATA_WRITE | required | Refuses all-day events and no-op changes |

Times are local wall clock (`2026-08-21T14:30`), never an offset the model invented. The device
zone is applied during validation, and the resulting instant is what the confirmation dialog shows
and what the write uses. Each turn is prefixed with a trusted line carrying today's date, the
device time zone, and the pinned calendar name, so "내일 오후 3시" resolves against the real clock.

Event ids cross the model boundary as decimal strings, both in the tool result and in the update
schema, so a JSON number can never round-trip through a float and land on a different event.

## Update safety

The model chooses which event to update, so the update tool never trusts that choice:

1. During validation the event is read, checked for writability, and rejected if it is all-day.
2. The preview shows the event's current title and times next to every proposed change.
3. A digest of the event as confirmed is stored in the canonical input.
4. Immediately before the write, the event is read again and the digest re-checked. If a sync
   moved it while the dialog was open, the update aborts with `changed_since_confirmation` and
   nothing is written.

Combined with the durable action ledger, an approved change is applied at most once, to exactly
the event the user saw.
