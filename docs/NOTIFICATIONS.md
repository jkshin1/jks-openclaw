# KakaoTalk notification capture, share, and reply

## Product boundary

KakaoTalk exposes no chat-history or arbitrary-friend send API to this app. Capture is a local cache
of allowlisted notifications that arrive while capture is enabled. It cannot see older messages,
muted/suppressed posts, messages read elsewhere before notification, or bodies KakaoTalk hides.
An empty search therefore means only that no matching cached notification was found.

## Confirmation-gated communication

`kakao_share_message` opens Android `ACTION_SEND` scoped to `com.kakao.talk` only after the exact
message preview is confirmed. An optional recipient is a preview hint; the app cannot select a
friend/room, press KakaoTalk's send button, or prove delivery. Its typed result reports only that
the picker opened and keeps `message_sent=false`.

`kakao_notification_reply` uses `RemoteInput` only for an exact currently active KakaoTalk
notification. It has an independent default-off setting, requires notification-listener access,
matches a visible label, requires exactly one free-form action, binds confirmation to an opaque
notification-key digest, and re-resolves the target immediately before execution. A successful
`PendingIntent.send` means only `reply_requested=true`, never delivery or read.

Both Tools are `COMMUNICATION`, require explicit confirmation and the Action Ledger, and re-check
their execution interlocks. Kotlin renders completed/refused write terminal answers directly from
the trusted receipt; it does not ask the model to restate the outcome. Unknown outcomes remain
owner-verification obligations and are never automatically retried. Automated tests do not send a
real message.

## Capture gates and disable-race contract

Notification access is a broad Android grant, so both gates are required:

1. the system notification-listener grant; and
2. the app's default-off `notificationCaptureEnabled` setting.

Package allowlisting and the app setting are checked at write and read time. The search Tool refuses
when either gate is closed, so disabling capture also makes previously captured rows unreadable.

Disabling is deliberately stronger than an asynchronous DataStore update:

- `requestCaptureEnabled(false)` closes an atomic process gate synchronously before launching the
  persistence coroutine;
- listener writes enter `withCaptureBoundary`, which checks that gate before and after acquiring the
  shared mutex;
- setting changes and explicit erasure use the same mutex, so a capture write cannot cross them;
- enable becomes visible only after the latest request is durably stored; and
- a failed disable leaves the process gate closed until an explicit retry or process restart.

This closes the race where a new notification could otherwise be stored after the owner switched
capture off but before DataStore finished. Stale enable/disable request tokens cannot overwrite the
latest request.

## Stored rows

Only `com.kakao.talk` message-bearing posts are eligible. The following are dropped:

| Dropped | Reason |
|---|---|
| Any other package | The allowlist is enforced on capture and search |
| Group summary | Aggregate text is not a message and could overwrite one |
| Ongoing/foreground post | Service state, not conversation content |
| Post without usable text | Nothing safe to store |

A sender is stored only when `MessagingStyle` supplies it. The app does not guess whether a title
is a room or person. Rows use the platform notification key, so updates replace rather than append.

Third-party text is hostile input. Before storage, Gemma control-token delimiters are replaced with
spaces and control/invisible formatting characters are removed. Sanitized content alone may enter
a prompt or confirmation preview.

## Bounds and erasure

- Retention is configurable from 1–180 days, default 14.
- A hard cap limits the store to 5,000 rows.
- Capture-triggered pruning is throttled; search and settings counts prune again at their read
  boundary.
- **Delete captured records** erases rows under the mutation mutex without changing the listener
  grant or capture setting.
- All rows live in `noBackupFilesDir` and are excluded from Android backup and encrypted transfer.

## Commitment proposals

Notification capture and commitment detection are separate default-off controls. The deterministic
detector sees only bounded sanitized KakaoTalk text and emits a review-only proposal with a source
hash. It never creates a calendar event or reminder. Promotion requires the existing owner review,
time selection, confirmation, execution interlock, and Action Ledger. Original notification text is
not copied into user-data export. The listener rechecks effective proposal consent at the use
boundary, so a pending/failed disable cannot create a proposal from an older settings snapshot.

## Evidence boundary

Source tests cover parsing, allowlisting, settings/read gates, replacement, escaped search, bounds,
retention, erasure, synchronous disable, stale request invalidation, proposal rechecks, and mutex
serialization. The current rc11 host `releaseGate` passed, and the scoped API 37 AVD suite completed
without failures. The tree was not installed or tested on a physical device, and no live KakaoTalk
capture/share/reply run is claimed for it. System permission, listener lifecycle, process
recreation, and real active-notification behavior remain separately approved acceptance work.
