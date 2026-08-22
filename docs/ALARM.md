# Alarms: what the platform actually allows

## The asymmetry

Android gives a third-party app a wide door for creating alarms and almost no door for reading
them. The tools are shaped around that, rather than around what "알람 조회·생성" sounds like it
should mean.

| Operation | Available? | Mechanism |
|---|---|---|
| Create an alarm | Yes | `AlarmClock.ACTION_SET_ALARM` intent, handled by the clock app |
| Read the next alarm | Yes, one value | `AlarmManager.getNextAlarmClock()` |
| List all alarms | **No public API** | — |
| Edit or delete an alarm | **No public API** | — |

So `alarm_next` reports a single trigger time with no label, and no way to tell a repeating alarm
from a one-shot. There is no `alarm_list` or `alarm_delete` tool because there is no honest way to
build one; the model is told this in the tool description so it does not promise the user a list.

## Measured on API 37, not assumed

The reference documentation does not clearly state whether `getNextAlarmClock()` needs a
permission or whether it sees alarms owned by the clock app rather than only alarms the caller
scheduled. Both were settled by instrumentation on the API 37 ARM64 AVD:

- `AndroidAlarmGatewayTest.readingTheNextAlarmNeedsNoPermission` — the test APK holds no alarm
  permission beyond normal `SET_ALARM`, and the read succeeds.
- `AndroidAlarmGatewayTest.anAlarmCreatedByTheClockAppIsReportedAsTheNextAlarm` — an alarm created
  through the platform intent from the shell is reported by `getNextAlarmClock()` at the exact
  requested hour and minute.
- `AlarmForegroundRequestTest` — with `MainActivity` in the foreground, this app's own gateway
  creates both a one-shot and a repeating alarm, and the next-alarm read confirms the time and the
  weekday the clock app actually chose.

Alarm creation cannot be undone through any API, so the tests that create one clear the clock
app's data afterwards and are skipped on anything but an emulator. Never run them on the Fold8.

## Why creating an alarm requires confirmation

`alarm_set` is `DATA_WRITE`, not `LOCAL_WRITE`, even though nothing in this app's storage changes.
The phone will ring at a time the model chose; that is a real-world effect and it goes through the
same confirmation dialog as a calendar write.

The intent is also fire-and-forget: it returns no result. The tool result therefore says
`requested`, never `created` — the clock app is the only authority on whether the alarm exists.
The result carries the observed next alarm so the model can read back what actually happened
instead of assuming.

Because there is no result and no way to delete, the durable action ledger matters more here than
anywhere else: an interrupted turn must not silently ask for the same alarm twice. Repeat days are
canonicalized into a fixed weekday order, so `"fri,mon,wed"` and `"mon,wed,fri"` produce the same
canonical snapshot and confirmation preview. The idempotency key additionally includes the current
request identity: replay within that request is blocked, while a separately submitted user turn is
a new action and must be judged from the target clock state.

## Requirements on the device

- `com.android.alarm.permission.SET_ALARM` — a normal permission, granted at install; there is no
  runtime prompt.
- A `<queries>` entry for `android.intent.action.SET_ALARM`. Without it, package visibility on
  API 30+ hides the clock app, and `clockAppAvailable()` cannot distinguish "no clock app" from
  "not visible".
- A foreground app. Starting the clock activity from the background is blocked; the tools run
  immediately after a confirmation dialog, and a blocked start is reported as `start_blocked`
  rather than being mistaken for success.

The interlock re-checks clock-app availability immediately before the durable claim, because the
clock app can be disabled or uninstalled while the confirmation dialog is on screen.

## What the tools accept

| Tool | Risk | Confirmation | Arguments |
|---|---|---|---|
| `alarm_set` | DATA_WRITE | required | `time` as 24-hour `HH:mm`, optional `label`, optional `days` |
| `alarm_next` | READ_ONLY | none | none |

`days` is a comma-separated subset of `mon,tue,wed,thu,fri,sat,sun`. Weekday tokens rather than
numbers, because a number would have to encode a locale-dependent week start. An unknown or
repeated token is refused during validation. Omitting `days` means a one-shot alarm at the next
occurrence of that time.
