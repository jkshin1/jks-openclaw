# KakaoTalk notification capture

## What this is, and what it is not

KakaoTalk exposes no API to this app. The only thing available is the notification stream, so the
feature is exactly that: a **local cache of notifications that arrived while capture was on**.

It is not chat history. It cannot see:

- messages that arrived before the feature was enabled,
- rooms the user muted, or notifications suppressed by Do Not Disturb,
- anything already read on another device before a notification posted,
- message bodies KakaoTalk chose to hide (for example under "미리보기 숨김").

The tool description states this, so the model does not report an empty result as "you have no
messages". Sending a KakaoTalk message is out of scope entirely — there is no write path.

## The two gates

Notification access is one of Android's broadest grants: once given, this app sees **every**
notification on the device, from every app. Two separate gates narrow that down, and both are
re-checked by the execution interlock immediately before any read:

1. **System grant** — the user enables notification access in system settings. Revoking it stops
   the listener entirely.
2. **App setting** — `notificationCaptureEnabled`, off by default. The listener re-reads it on
   every post, because the system grant can stay in place long after the user turns capture off.

The search tool refuses when either gate is closed. That matters for the second one in particular:
without it, turning capture off would still leave the previously captured store readable.

## What is stored

Only packages on the allowlist (`com.kakao.talk`), and only message-bearing posts:

| Dropped | Why |
|---|---|
| Any other package | The allowlist is the boundary, applied at capture *and* at read time |
| Group summaries (`FLAG_GROUP_SUMMARY`) | "새 메시지 3개" carries no message, and would overwrite one |
| Ongoing/foreground posts | Service state, not conversation |
| Posts with no usable text | Nothing to store |

A sender is recorded **only** when the notification used `MessagingStyle`, where the platform
states it. KakaoTalk puts the room name in the title for group chats and the sender's name for
one-to-one chats; guessing between them would attribute messages to the wrong person, so the
sender is simply left absent instead.

Each row is keyed by the platform notification key, so an updated post replaces its earlier row
rather than accumulating duplicates.

## Text is treated as hostile

Notification text is written by third parties and later enters a Gemma prompt and a confirmation
preview. Before storage it has Gemma control-token delimiters replaced with spaces, and ISO
control characters plus invisible formatting — bidi overrides, line/paragraph separators — removed
outright. Text that renders differently from what it contains is exactly what a preview must never
show.

Delimiters become a space rather than being deleted, because deleting them could join two
fragments into a token that was not in the original.

## Bounds and erasure

- Retention in days, from settings (default 14, 1–180).
- A hard cap of 5,000 rows regardless of age.
- Capture-triggered maintenance runs at most hourly. Retention is also applied inside every search
  and when the settings screen resumes to calculate its stored count, so rows that expire while
  the listener is idle are no longer readable indefinitely.
- "수집 기록 삭제" erases every captured row without touching the grant, so capture continues.

Everything lives in `noBackupFilesDir` and is excluded from cloud backup and device transfer.

## Verified on API 37

- The service binds: `exported="true"` with the system-only `BIND_NOTIFICATION_LISTENER_SERVICE`
  permission is what lets `NotificationManagerService` bind it, and nothing else can.
- A notification posted by another package (`com.android.shell`) with access granted and the
  service bound was **not** stored.
- `NotificationPostReaderTest` covers the `MessagingStyle` bundle parsing against real `Bundle`
  objects, including a wrongly typed `EXTRA_MESSAGES` and entries with no body.
- `NotificationCaptureSinkTest` covers the settings gate, the allowlist, replacement by key,
  search including `%`/`_` wildcard escaping, the time window, retention, and erasure.

The accept path for `com.kakao.talk` itself is covered by the sink tests, not end to end: a test
cannot post a notification as another package. Real KakaoTalk capture remains a Fold8 check.
