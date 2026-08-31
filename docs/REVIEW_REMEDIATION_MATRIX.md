# Improvement review remediation matrix

Reviewed 2026-08-25 against the original attached improvement report and reconciled through the rc11 working tree.
This file maps recommendations to source evidence. Runtime acceptance remains separately graded in
[`PROJECT_STATUS.md`](PROJECT_STATUS.md); implementation does not inherit a physical receipt.

## P0 recommendations

| Review item | Current implementation evidence | Grade |
|---|---|---|
| App-owned reminders, scheduling, notifications, and recovery | Reminder/delivery tables were introduced in Room schema 5; the current database is schema 10. `ReminderScheduling.kt`; exact-to-WorkManager degradation; notification channel/dispatcher; boot, time, time-zone, package-replace, and app-start reconciliation; runtime permission UI | Implemented; denied-permission storage physically accepted on its historical artifact; allowed-delivery matrix open |
| Date-bearing requests must not use `alarm_set` | Closed `reminder_create/update/cancel/query` Tool contracts carry absolute ISO date-time and zone; fixed evaluation corpus distinguishes Clock alarms from dated reminders | Implemented and host verified |
| Trusted date/time context must fail closed independently of optional context | `TurnContextBuilder` separates required temporal identity from settings/history/memory; time-related writes stop when it is unavailable and closed diagnostics record only component codes | Implemented and host verified |
| Installed release must match current source and preserve owner state | A historical owner-signed rc11 artifact (`versionCode=11`, `1.0.0-rc11`, SHA-256 `12379e405d93be94c765a2eb2088e5af8d19d25a070f73c2f84e037910f34981`) used same-certificate `adb install -r`, exact host/pulled hash match, unchanged v3 certificate/first-install time, and content-free preservation snapshots. The current changed source has host verification only. | Historical bounded Fold8 receipt retained; current-source install/preservation and exhaustive owner-state audit remain open |
| Core assistant UI must work without the model | Calendar setup precedes model setup; direct Today, Inbox, reminder creation, recurrence, completion, snooze, conflict, and delivery-state UI call deterministic Kotlin coordinators | Implemented and host verified; new Fold pixels open |

## Correctness and durability findings

| Review item | Resolution |
|---|---|
| Conversation switch can activate a missing ID | Typed `ConversationSwitchResult`; existence checked before activation; persistence failures are visible |
| Restore returns oldest rather than newest 500 messages | DAO selects newest bounded rows in descending order, then returns them chronologically |
| UTF-16 truncation can split surrogate pairs | Shared code-point and UTF-8-safe truncation utilities are used at storage, prompt, summary, and provider boundaries |
| Credential file can exist but be unreadable | Presence-only health is `ABSENT / READABLE / UNREADABLE`; provider preflight accepts only readable secrets |
| Calendar title can visually spoof confirmation | Provider text is control/bidi/model-token sanitized and code-point bounded for display while canonical execution binding stays typed |
| Calendar zone can drift between preview and execute | Validated canonical zone snapshot drives both preview and execution; a changed device zone refuses the confirmed write |
| Notification retention can leave expired physical rows | Daily prune worker plus app/settings/search/capture pruning; each capture transaction enforces the 5,000-row cap |
| Unrelated memory fallback | Zero lexical overlap injects no memory; route use is restricted to typed `PLACE` rows |
| Secret-memory detection gaps | Luhn, Korean RRN structure, JWT, long hex/base64url, normalized token, and entropy checks fail closed |

## Smaller code findings

| Review item | Resolution |
|---|---|
| Alarm icon | Uses `ic_bell` |
| Misleading Tool status | Copy states that reads can run within consent and writes require confirmation |
| Dead `preferredBackend` | Removed |
| Dead `confirmLocalWrites` | Removed; local/model-proposed writes retain confirmation |
| Truncated notification source key | Full SHA-256 identity |
| Confirmation digest exposed to owners | Hidden from the normal confirmation surface |
| Destructive actions execute immediately | Shared destructive confirmation covers conversation, memory, notification, and import/delete surfaces |
| Locale-sensitive formatting | Trusted/canonical formatting uses an explicit locale |
| Unsigned release can be produced | Release task fails closed when private signing is incomplete |
| Oversized UI/ViewModel | Settings host reduced to 208 lines with seven domain section files; history, reminder, memory, network, credential, calendar, notification, and confirmation coordinators own typed state |

## Product-value recommendations

| Recommendation | Resolution |
|---|---|
| Today Brief | Deterministic configurable local time, event/countdown/conflict/overdue/yesterday-snoozed summaries, quiet/weekend policy |
| “Do not forget” Inbox | Direct model-free review-only capture; one closed clarification with today evening, tomorrow morning, or specified date |
| Conflict detection | Epoch overlap policy runs before calendar create/update confirmation |
| Leave-by reminder | Calendar-owned projection, bounded live route lookup, fixed safe fallback, and no stale-traffic reuse |
| Notification actions/source | Versioned complete/snooze/calendar-open actions and exact internal source-detail deep link; no model load |
| Kakao commitment candidates | Separate default-off opt-in and local proposal detector; never auto-registers; explicit promotion only |
| User-driven encrypted transfer | Selection preview, PBKDF2/AES-GCM, integrity/schema validation, merge-without-overwrite, calendar remap; secrets/ledger/provider IDs excluded |
| Fold-specific information architecture | Cover Today/actions and expanded two-pane Today/chat plus side-by-side confirmation |

## Model and efficiency recommendations

- A fixed 26-case/23-category Korean Tool-use corpus and strict scorer cover selection, arguments,
  dates, clarification, final state, response language, latency, memory, battery, thermal, fold, and
  cancellation. E4B/MTP E4B/E2B promotion still requires matching verified artifacts and same-device
  runs; no result is manufactured without them.
- Native output ceilings are 128/256/384/1,024 by deterministic request policy. Background reminder,
  pruning, brief, conflict, and leave-by orchestration never load the 3.66 GB model.
- Default answers are Korean unless the current trusted request explicitly asks for another language;
  quoted history, memory, and Tool results cannot override it.

## External acceptance still requiring owner or disruptive device state

The following are not missing production implementations. They remain unaccepted external gates:

- owner-granted `POST_NOTIFICATIONS`, followed by actual posting, complete/snooze, and stale-action checks;
- reboot, manual clock/time-zone changes, Doze/background restriction, process-death delivery, and
  exact-alarm allowed/denied states;
- real SAF export/import on an owner-selected document and new-device/calendar-remap round trip;
- physical cover/unfold/fold-during-confirmation/action/IME pixel matrix;
- latest typed-memory and automatic network-read real-Gemma flows;
- proposal real-Gemma selection; reminder create/update/cancel now have one bounded all-confirmation
  denial receipt, but the preceding same-build update failure means an accuracy run remains open;
- same-condition E4B/MTP E4B/E2B corpus runs when independently verified candidate artifacts exist.

Do not grant permissions silently, change the owner's clock/time zone, reboot the phone, read owner
content, or replace the signed release with a debug build merely to close those rows.
