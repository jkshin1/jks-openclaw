# Calendar: NAVER via CalDAV

## Why the device calendar, not the NAVER API

NAVER Calendar is the calendar this app works with. It reaches the app through
`CalendarContract`, not through NAVER's Open API, because the Open API cannot do the job:

> [`naver/naver-openapi-guide`](https://github.com/naver/naver-openapi-guide/blob/master/ko/login/calendar-api/calendar-api.md)
> documents exactly one endpoint, `POST https://openapi.naver.com/calendar/createSchedule.json`,
> described as "캘린더 일정 추가". There is no endpoint for reading, updating, or deleting a
> schedule.

Create-only would cover one third of the requirement (조회·등록·수정) and would still need OAuth
and network access. NAVER does support CalDAV, so the workable path is to let a CalDAV sync client
publish the NAVER calendar into the Android calendar provider, where all three operations work
offline through a single local API.

## One-time device setup

1. Install a CalDAV sync client that creates an Android calendar account — DAVx⁵ is the usual
   choice on Android.
2. Add the NAVER account to it. NAVER's CalDAV endpoint and app-password requirements are
   documented by NAVER; the same settings that work for iOS CalDAV work here.
3. Let it sync at least once, and confirm the calendar is writable in the client.
4. In this app: grant the calendar permission, then tap the NAVER calendar in the 캘린더 card.

Until step 4 is done the calendar tools refuse to run. That is the interlock working, not a bug.

## The scope boundary

CalDAV sync puts the NAVER calendar next to everything else on the device — work accounts, shared
calendars, birthdays. `ScopedCalendarGateway` filters every read and write to the one pinned
calendar:

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
