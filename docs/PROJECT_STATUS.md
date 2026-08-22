# Project Status

Last reviewed: 2026-08-22 against the `1.0.0-rc1` candidate tree and Fold8 receipt.

This is the evidence ledger, not a feature checklist. Keep these states separate:

- **Implemented**: the production path and regression tests exist in source.
- **Host verified**: the relevant JVM/script checks passed.
- **Emulator verified**: Android behavior passed on an API 37 ARM64 AVD.
- **Physical accepted**: the intended flow passed on the Fold8 with a bounded receipt.
- **Provider/live accepted**: a real external account or API completed the intended round trip.

A working-tree implementation does not inherit an older build or device result. Re-run and record
the applicable gate before promoting it.

## Current Snapshot

| Area | State | Evidence and limits |
|---|---|---|
| Gemma/LiteRT runtime | Physical accepted for the 4K GPU slice | Verified model import, streaming, cancellation, recovery, and natural `SEVERE` continuation passed on the Fold8. Natural `CRITICAL`, battery, fold/background, and 8K/16K remain unverified. Exact requested output length failed in the sustained probe. |
| Real Gemma Tool selection | Physical accepted for bounded calendar and alarm cases | On 2026-08-22 the Fold8 model selected `calendar_create_event`, produced valid arguments, resolved a relative date from the trusted device preamble, and completed one approved write. A Korean instrumentation path selected the same Tool and denial wrote nothing. The current RC then selected and completed one read-only `alarm_next` turn on GPU. These are bounded phrases, not an accuracy measurement. |
| Device Tool registry | Implemented; mixed downstream evidence | The shipped closed registry has exactly eight Tools: `calendar_query`, `calendar_create_event`, `calendar_update_event`, `alarm_set`, `alarm_next`, `kakao_notification_search`, `route_estimate`, and `web_search`. The fake arrival Tool is a test fixture and is not registered in the app. Each integration retains its own evidence row below. |
| Tool control and receipts | Host and current Fold8 regression accepted; one historical physical write | Automatic Tool calling is off. Kotlin owns name resolution, strict argument validation, canonical previews, confirmation, execution-time interlocks, and the durable SQLite claim. Typed terminal outcomes distinguish completed reads, completed writes, and provider refusals. Side-effect execution plus receipt commit is non-cancellable after the durable claim; a later process restart shows a content-free warning for `CLAIMED/UNKNOWN_AFTER_CLAIM` rows. A separately submitted user turn is still a new action, not semantically deduplicated. |
| Conversation continuity | Host verified; bounded physical restore/turn, quality pending | Every top-level request gets a fresh native LiteRT conversation, but receives a byte-bounded, sanitized, explicitly quoted Room summary and newest recent messages. Newest messages win under pressure. A user turn cancels and joins a background summary before acquiring the controller. The RC restored prior history and completed a new Tool turn on Fold8; real follow-up quality and actual model-summary latency remain unmeasured. |
| Chat history | Host/emulator verified; physical restore observed | User prompts, assistant text, and app-authored Tool receipts persist to Room and restore on launch. The current RC restored the existing transcript and appended the read-only Tool receipt. The derived per-turn device/context preamble and transient status notices are not stored. |
| CalendarContract tools | Current Fold8 instrumentation accepted; one historical provider create | Query/create/update stay inside one pinned writable `CalendarContract` row. Provider queries apply `CALENDAR_ID` before limits, reads fail when the pin is missing/deleted, and updates atomically require both event ID and expected calendar ID. Settings display ID, account name, and account type; an `@naver.com` name alone is never labeled NAVER. All-day and RRULE/RDATE recurring-series updates are refused. Instrumentation uses only its own local calendars; this does not qualify NAVER sync. |
| NAVER Calendar | Provider transport unqualified | Fold8 inspection on 2026-08-22 found a `com.nhn.android.naveraccount` account publishing zero `CalendarContract` rows. Rows whose account name ended in naver.com were Samsung-account calendars with Samsung account types, not proof of NAVER sync. The official Open API is create-only and official CalDAV guidance does not support Android, so generic CalendarContract success is not NAVER acceptance. |
| Standard alarm tools | `alarm_next` current Fold8 physical accepted; `alarm_set` emulator only | `alarm_set` requests one-shot or repeating alarms through `AlarmClock.ACTION_SET_ALARM`; `alarm_next` reads `getNextAlarmClock()`. The current RC completed one real Gemma `alarm_next` turn and app-authored receipt on Fold8. Alarm creation tests remain emulator-only because cleanup clears the clock app. Android exposes no public list/edit/delete API. |
| Kakao notification capture | Implemented and emulator verified; live Kakao gap | Capture is off by default behind the Android grant and an app setting. Only allowlisted message notifications are stored, and hostile text is sanitized before storage. Retention is enforced during capture and again on search/settings resume, so expired rows do not remain readable merely because the listener was idle. A test cannot post as `com.kakao.talk`; real Kakao capture remains a Fold8 gate. |
| Third-party credentials | Emulator verified; current Fold8 UI accepted without touching owner vault | Four settings slots store the NCP Maps pair and NAVER Developers Search pair under Android Keystore AES-GCM. Fold8 settings showed presence-only rows and no retained values; the nine tests that clear the app vault skipped intentionally. A reinstall or new device requires re-entry. Hardware-backed storage is not claimed. |
| Route estimate and web search | Implemented; host-shaped responses only; never live | Each feature has a persistent opt-in that defaults off and is re-checked by the interlock. Every individual request also shows the canonical query or endpoints and requires confirmation before user text leaves the phone. One transport allows only the two NAVER HTTPS hosts, refuses redirects, and bounds time/body. No real credentialed request has been accepted; recorded response shapes remain an assumption. |
| Diagnostics and thermal policy | Current Fold8 debug export accepted; signed-release device pending | The SAF path exported 103 validated records / 14,118 bytes on Fold8; every line parsed and the field set contained no prompt, answer, Tool argument/result, query, or credential fields. Strict export validation rejects malformed/nested/duplicate-key/extra-field records. Content-free diagnostics recorded GPU initialization success, one `alarm_next` execution, 3,064 ms TTFT, 4,197 ms turn duration, and thermal `none`. The signed-release UI path uses the same code but is not device-accepted until release migration. |
| Setup and settings UI | Current unfolded Fold8 accepted | On the 1,848×2,448 unfolded display, model/load state, diagnostic export, calendar identity/pinning, notification controls, masked credential rows, both default-off network opt-ins, presence-only default origin, history, chat, and prompt remained reachable by bounded scrolling. Folded outer-display regression remains separate. |
| Update preservation | Current Fold8 physical accepted with the debug key | Certificate equality was checked before `adb install -r`. The update retained calendar grants and the read-only model file at exactly 3,659,530,240 bytes with the pinned digest filename and unchanged timestamp. This does not qualify debug-to-release migration because the certificates differ. |
| Personal release | Signed `1.0.0-rc1` artifact verified; release migration pending | Both APKs declare API 37 and `versionCode=1`, `versionName="1.0.0-rc1"`. Release SHA-256 is `7efb2c331b5465668f0d7426bf6150660b1590f698a319c8ece96341d7b56ac6`; v3 verification passed with non-debug certificate SHA-256 `e0f66d4b4c8064db6a9d46097d77903cf13fbccacbdfc6e49e9f7c380b8e457a`. Offline key/password backup is not evidenced. The Fold8 still needs a deliberate debug uninstall, signed-release install, model re-import, and release regression; promote with a higher version code. |

Fresh current-candidate receipts: environment doctor PASS; 15/15 host-script tests; 240 JVM tests
with zero failures; lint clean; debug/release assembly PASS; 111 Fold8 instrumentation tests with
98 passes, 13 intentional protection/filesystem skips, and zero failures. The bounded collector
receipt is `reports/fold8-20260822T112358Z-8daac8ade96e.oii0Vs/manifest.json`. App/logcat and
bugreport collection were deliberately not enabled.

## NAVER Calendar Qualification Boundary

NAVER's official [Calendar Open API](https://developers.naver.com/docs/login/calendar-api/calendar-api.md)
documents schedule creation only; it cannot supply the required read and update operations.
NAVER's official [CalDAV help](https://help.naver.com/service/5620/contents/2426?lang=ko) does not
support Android. Physical provider inspection also found no NAVER-published `CalendarContract` row.

The generic adapter remains useful for a compatible local, Samsung, Google, or other provider
calendar selected by the owner. It must not be presented as NAVER Calendar publication or sync.
If NAVER itself remains a product requirement, choose and review a documented new transport; do
not infer one from an email-shaped account name or from local provider tests.

## Remaining Gates Before Promoting `1.0.0-rc1` to `1.0.0`

1. The RC restored two old `CLAIMED/UNKNOWN_AFTER_CLAIM` ledger rows and now warns that their Tool
   results cannot be established. Inspect the relevant calendar/clock state before submitting any
   semantically identical request. Do not delete or relabel those claims to make the warning pass.
2. With owner-entered credentials, separately qualify real geocoding, directions, and web search.
   Verify what text leaves the phone and denial-before-network behavior. Provider/live acceptance
   must not be inferred from host response fixtures.
3. Verify an offline copy of the personal signing key and its password. Only then choose the
   destructive debug-to-release migration, re-import the 3.66GB model, and repeat the critical
   flows plus in-app diagnostic export on the signed release.
4. Qualify actual NAVER Calendar publication/sync, a real KakaoTalk notification post, and a
   deliberate physical `alarm_set` if those integrations are required; generic/provider or
   emulator evidence is not a substitute.
5. Complete Fold8 folded/outer-display, rotation/background, battery, offline, sustained-use, real
   follow-up/summary quality, and natural `CRITICAL` validation. Natural `SEVERE` continuation is
   already a bounded historical result.

## Chat History Boundaries

Stored: user prompts, assistant answers, app-authored Tool receipts, and a bounded thread summary.
Not stored: the derived device/context preamble, raw model thinking, network credentials, or
transient thermal/status notices.

The next turn receives a sanitized and explicitly quoted subset of the summary and newest recent
messages within the 2 KiB prompt envelope. It is context data, not a new system instruction.
`"전체 삭제"` clears conversations and messages only; the action ledger is deliberately separate,
so deleting history cannot re-enable an already claimed side effect. All app state is under
`noBackupFilesDir` and excluded from Android backup/device transfer.

## Running Instrumentation on the Fold8

`connectedAndroidTest` can uninstall the app and destroy the imported model. The repository sets
`android.injected.androidTest.leaveApksInstalledAfterRun=true`; do not remove it. Tests that clear
the clock app or credential vault are emulator-guarded and must remain skipped on a phone. Always
set the exact `ANDROID_SERIAL`, query before deleting calendar rows, and read
`no_backup/diagnostics/diagnostics.jsonl` before interpreting a turn.

Debug and release certificates cannot replace each other. Treat switching variants as a deliberate
uninstall/reinstall and model re-import, never as an ordinary validation step.

## Network and Credential Boundaries

The two network capabilities default to opt-out. Persistent consent is necessary but insufficient:
the canonical query or route endpoints require a new confirmation for every call. Denial, disabled
consent, missing credentials, offline state, or a changed execution-time interlock stops before the
gateway. Details are in [`NETWORK.md`](NETWORK.md).

Third-party keys are typed into non-saveable fields and encrypted under an Android Keystore AES-GCM
key. Presence is visible; values are not. Keys never enter diagnostics, logs, chat history, or a
model prompt. The optional default origin follows the same presence-only UI rule, although it is a
DataStore setting rather than a credential.

## Notification Capture Boundaries

Notification access lets the app observe every package, so both the Android grant and the app's
default-off capture setting are required. Package allowlisting applies on write and read. Retention
is enforced on capture, search, and settings-screen resume; explicit erasure remains available.
This is a local notification cache, not KakaoTalk history, and there is no send path. See
[`NOTIFICATIONS.md`](NOTIFICATIONS.md).

## Alarm Platform Limits

Android exposes no public API to list, edit, or delete alarms owned by the clock app. `alarm_next`
therefore reports only the next trigger. `alarm_set` is fire-and-forget, so its result reports
`requested` or a refusal rather than inventing provider success. See [`ALARM.md`](ALARM.md).

## Deferred

Kakao send/reply automation, full Samsung Clock editing, Polestar control, always-on voice,
multimodal input, autonomous background workflows, and 8K/16K/32K production contexts remain
outside the current product boundary.
