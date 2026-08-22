# CalendarContract scope and NAVER qualification

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

## What the Fold8 provider check established

On 2026-08-22 the device had a `com.nhn.android.naveraccount` account, but that account published
zero rows into `CalendarContract`. Rows with a naver.com-shaped `account_name` had Samsung
`account_type` values (`com.osp.app.signin` or `com.samsung.android.mobileservice`); they were
Samsung calendars for an owner who used a NAVER address as a Samsung ID. Account name is not
provider identity, and a write there is not NAVER sync evidence.

The settings UI therefore shows display name, account name, account type, and calendar ID together.
The supported product claim is generic `CalendarContract` access to the row the owner selected.
NAVER publication and remote synchronization remain unqualified unless a new documented transport
is designed and independently accepted.

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
| `calendar_update_event` | DATA_WRITE | required | Refuses all-day, recurring-series, and no-op changes |

Times are local wall clock (`2026-08-21T14:30`), never an offset the model invented. The device
zone is applied during validation, and the resulting instant is what the confirmation dialog shows
and what the write uses. Each turn is prefixed with a trusted line carrying today's date, the
device time zone, and the pinned calendar name, so "내일 오후 3시" resolves against the real clock.

Event ids cross the model boundary as decimal strings, both in the tool result and in the update
schema, so a JSON number can never round-trip through a float and land on a different event.

## Update safety

The model chooses which event to update, so the update tool never trusts that choice:

1. During validation the event is read and checked for writability. All-day events are refused
   because their date/UTC convention differs. Recurring masters are refused because the current
   confirmation contract cannot distinguish one occurrence from the whole series.
2. The preview shows the event's current title and times next to every proposed change.
3. A digest of the event as confirmed is stored in the canonical input.
4. Immediately before the write, the event is read again and the digest re-checked. If a sync
   moved it while the dialog was open, the update aborts with `changed_since_confirmation` and
   nothing is written.

Combined with the durable action ledger, an approved change is applied at most once, to exactly
the event the user saw.
