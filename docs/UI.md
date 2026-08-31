# UI

Written 2026-08-23, when the single-screen layout was replaced.

## What changed and why

The previous screen stacked six setup cards above the transcript. Every subject had its own
elevated card with its own heading, so there was no hierarchy, and on a folded display the cards
and the conversation competed for the same window — the prompt field could end up behind a scroll.
Colour and type were the Material baseline, which is why it read as flat.

The redesign keeps every control and every gate; it changes where they live.

| Before | Now |
|---|---|
| Six setup cards permanently above the transcript | Setup lives in a dismissible sheet, grouped by subject |
| Text buttons in a header row | Icon actions in a centre-aligned app bar with a status pill |
| Setup state read off six cards | One banner naming the single next step, plus a thermal banner when it matters |
| Send disabled with no reason given | The blocking reason is stated above the composer, with a way to fix it |
| Empty transcript showed one sentence | Starter prompts, filtered to the capabilities that are actually enabled |
| Confirmation and history as `AlertDialog` | Modal bottom sheets; the confirmation keeps deny-on-dismiss |
| Bubbles labelled "User"/"Assistant" on every row | Visual role comes from placement/surface; merged TalkBack semantics still announce user versus Personal Edge |

## Design language

Material 3 (`material3` 1.4.0) components, tuned toward the look of a current phone OS.

- **Continuous corners.** `SquircleShape` in `ui/theme/Shape.kt` is a `CornerBasedShape` whose
  corners are single cubics holding the edge tangent for a third of the corner run, so curvature
  ramps instead of jumping from arc to straight edge. That is the difference between a rounded box
  and a squircle.
- **A fixed palette**, not dynamic colour. Screenshots and diagnostics get compared across builds,
  and the roles carry meaning: primary is the agent, tertiary marks a Tool receipt, error marks a
  refusal. A wallpaper-derived scheme would make "did the gate turn red" harder to answer.
- **Grouped, inset lists** for settings: a quiet caption, one card holding the rows, a footnote
  underneath. Rows share a surface instead of each subject owning a card.
- **A 17sp reading size** with a heavier, tighter large title. The font family stays the platform
  default so the Korean fallback keeps working; only size, weight, and tracking are tuned.
- **Springs, not durations.** `PersonalEdgeMotion` supplies the specs. `MaterialExpressiveTheme`
  and its motion scheme are still internal in 1.4.0, so the springs are declared directly.
- **Icons are local vector drawables** in `res/drawable/ic_*.xml`, stroked rather than filled. No
  icon dependency was added, so `gradle/verification-metadata.xml` is untouched.

## Layout

`app/src/main/kotlin/com/personaledge/agent/ui/`

| File | Role |
|---|---|
| `PersonalEdgeScreen.kt` | Scaffold, app bar, setup/thermal banners, jump-to-latest, sheet hosting |
| `TodayWorkspace.kt` | Width policy, cover Today glance card, unfolded deterministic timeline pane |
| `ChatTranscript.kt` | Bubbles, Tool receipts, status lines, typing dots, empty state |
| `Composer.kt` | Prompt field, blocking notice, one button that morphs between send and stop |
| `SettingsSheet.kt` | 208-line settings host that only orders sections and forwards callbacks |
| `SettingsCalendarSection.kt` | Calendar permission and explicit read/write-scope selection |
| `SettingsModelSection.kt` | Verified model state, import, and CPU/GPU initialization controls |
| `SettingsMemorySection.kt` | Typed memory capture, reconfirmation, replacement, and destructive confirmations |
| `SettingsNetworkSection.kt` | Default-off route/search consent and presence-only default origin |
| `SettingsCredentialSection.kt` | Write-only credential health and replacement controls |
| `SettingsNotificationSection.kt` | Capture grant, local opt-in, retention, and diagnostics export sections |
| `SettingsTransferSection.kt` | Encrypted user-selected export/import preview and confirmation |
| `SheetHeader.kt` | Shared settings/reminder sheet header |
| `HistorySheet.kt` | Saved conversations, per-thread delete, two-tap delete-all |
| `ConfirmationSheet.kt` | The execution gate |
| `ReminderSheet.kt` | Direct reminder/recurrence/escalation controls, configurable Today policy, and direct/proposed Inbox |
| `AgentPresentation.kt` | Pure logic: status chips, the next setup step, why send is blocked, suggestions |
| `components/` | Grouped-list primitives, status pill, tinted glyph tile, tone mapping |
| `theme/` | Colour, type, shape, motion |

`MainActivity` is wiring only: activity result contracts, the permission request, the lifecycle
refresh, notification source-detail deep links, and a flat set of callbacks. Reminder, memory,
network-consent, credentials, calendar scope, notification capture, confirmation, and history
state/CRUD live in separate coordinators rather than one ViewModel.

`AgentPresentation.kt` exists so the decisions that used to be spread across composables are
ordinary Kotlin. `AgentPresentationTest` covers them, which is why a wrong label is a failing JVM
test rather than something noticed in a screenshot. Deterministic calendar setup now precedes the
optional model step; direct Today, Inbox, reminder, completion, and snooze surfaces remain usable
when the model is missing or unavailable.

Compact or short windows use the cover information architecture, which puts the next event, leave-by guidance,
due/overdue/conflict counts, and the first incomplete reminder above the conversation. Complete and
10-minute snooze are one tap and do not require the model. The Today card hides while the IME is
open so short input retains usable height. Wide mode requires at least 700 dp overall, 480 dp of
usable content height, and 272 dp for each actual pane; with the 38/62 split the effective width
boundary is 716 dp. A separating hinge is accepted only when both sides retain those usable bounds
and the occlusion is at most 96 dp. On an expanded confirmation sheet, the current Today schedule
and the new execution proposal render side by side. Its body scrolls independently while the
`거절` and `실행` actions remain pinned, including at large font scales and in short DeX windows.

## Behaviour and search-provider disclosure

The original redesign was presentation. The later read-only simplification changes when the
confirmation sheet appears while retaining the storage and write-safety boundaries:

- **Dismissing a write confirmation is still a denial.** Swipe, scrim tap, and back all deliver
  `false`, exactly as dismissing the previous dialog did. The decision is delivered on the tap
  rather than after an exit animation, so a slow close cannot eat into the challenge's expiry.
- **Conversation accessibility does not depend on colour or placement.** User and Personal Edge
  bubbles merge a spoken role with their text, and the pending indicator is a polite live region.
  A settings toggle is one row-level Switch semantics/action; its visual thumb is not a second
  TalkBack focus target.
- **Streaming avoids per-token layout work.** Auto-follow uses code-point growth buckets and an
  immediate end pin instead of starting an animation for every delta. Rich formatting is deferred
  until the active answer is final. The completed Assistant bubble then applies a bounded local
  renderer for emphasis, lists, bare HTTPS links, and common `$...$`, `$$...$$`, `\(...\)`, and
  `\[...\]` math forms. Common operators, `\text{...}`, scripts, diagonal fractions, and square
  roots receive readable typography without HTML, WebView, a CDN, or a new network dependency.
  Closed numeric math such as `$12$` and `$12.5$` is rendered, while an unclosed currency token
  such as `$12` and shell-like `$HOME` remain literal. Code spans/fences remain literal, malformed
  markup remains visible, and hidden Markdown links or images are not executed. The Room/ChatEntry
  text stays byte-for-byte model output.
- **`생각 중` is a real, user-controlled stream.** The collapsed row remains compact; expanding it
  shows the active LiteRT thought channel verbatim as plain text in a bounded tail-following area.
  The text is ViewModel-only and disappears when the answer starts or the turn ends; it is never a
  transcript row, restored history, summary, diagnostic field, link, or Markdown surface. TalkBack
  announces the disclosure state without re-reading the growing raw stream on every token.
- **Plain-text drop is atomic.** One newline separates existing and dropped text. URI/intent,
  control-format/model-delimiter text, and a merged prompt above the same 1,408-byte composer bound
  return `false` before prompt or focus callbacks run.
- **Credentials are still write-only.** The two NAVER Cloud Maps fields and optional Tavily field
  use plain `remember`, never `rememberSaveable`, so a saved-state bundle cannot write a key to
  disk in the clear. The field exists only while its row is expanded, and saved values return only
  as presence. You.com keyless search has no credential row. A Tavily candidate is checked against
  the fixed `/usage` endpoint before it is stored; failure leaves the prior stored state unchanged.
- **Consent defaults stay off,** and both switches remain reachable. Each row names its remote
  recipient. Enabling a switch authorizes later read-only route/search requests without another
  confirmation sheet; turning it off or failing the execution-time interlock stops before any
  provider call. Calendar/alarm writes and approved-memory storage still require confirmation.
- **The default origin remains presence-only** and is never read back into the UI.
- **Delete-all still takes two taps.**
- **The long settings list owns vertical gestures.** The settings sheet disables its own drag
  gestures and omits the drag handle, so leftover motion at the list's top or bottom cannot pull the
  already-expanded sheet past its anchor. Back, scrim tap, and the explicit X still dismiss it.
- **Thermal state is more visible, not less.** NONE and LIGHT are quiet; anything warmer gets a
  banner, and a blocking state is also the composer's stated reason.
- **Only the pinned calendar is offered,** with account name, account type, and calendar ID on the
  row. The footnote now names the standard Samsung Account calendar as the product choice and
  distinguishes the separate Samsung sharing-service row.

## Verifying without a phone

Debug builds carry `UiGalleryActivity`. It renders the production composables against fabricated
state, so states that would otherwise need a loaded 3.66 GB model — a streaming turn, a Tool
receipt, a pending confirmation — can be reviewed on an emulator.

```bash
adb shell am start -n com.personaledge.agent/.UiGalleryActivity --es scene chat
```

Scenes: `chat`, `empty`, `onboarding`, `streaming`, `settings`, `settings_setup`, `history`,
`confirm`.

It is debug-only: `app/src/debug/` is not part of the release variant, and the release APK's
manifest contains `MainActivity` alone. It touches no ViewModel, vault, calendar provider, or
diagnostics.

**A gallery screenshot is evidence about layout, spacing, and colour. It is not evidence about the
runtime, the tools, or any gate** — the callbacks are empty. Anything about behaviour has to come
from the real app.

## Device acceptance

On 2026-09-01 the final code candidate was exercised in the normal owner-signed Fold8 MainActivity.
The actual LiteRT thought channel appeared beneath `생각 중`, streamed while expanded, disappeared
when collapsed, and cleared after the answer without a white-screen transition. The earlier white
surface belonged to the instrumentation-only empty Activity; its test manifest now uses a black
theme. A stored model answer containing bold Markdown and dollar math then displayed bold step
headings and centred equations without raw `**`, `$...$`, or `\text` markers. This qualifies that
bounded scenario, not arbitrary Markdown or full TeX compatibility.

The pre-search-replacement UI baseline was accepted on the Fold8 on 2026-08-23 with the real model.
Unfolded (1,848×2,448): restored transcript, model load from the setup banner, one real
`alarm_next` turn with its receipt, the settings and history sheets against real data, the SAF
export picker, and the composer above the Samsung IME. Folded to `CLOSED`, the app continued onto
the 1,248×1,972 cover display, where the transcript, both sheets, and the IME were re-checked; the
confirmation sheet was checked there through the gallery only. Diagnostics grew from 197 to 222
records with no prompt, answer, query, or address field. The signed-release build was not covered.

The current provider-specific delta now has bounded unfolded and cover-display evidence on final debug APK
`5119c10f4eb0cc4df03b147c77a4c33e517f921abc38c03ffb36563801acdf10`. The owner used the real
settings flow to pass Tavily verification and save the key. After a bottom-boundary shake was
reported, the sheet's parent drag was disabled: in the fabricated-data gallery, the close node and
`진단 JSONL 저장` row retained identical bounds after reaching the bottom, 15 additional boundary
swipes, and a long downward header drag. In the real app, Back, X, and a non-status-bar scrim tap
each dismissed the unfolded sheet. The same coordinate test passed on the 1,248×1,972 cover display;
Back and X remained reachable there. The cover sheet occupies the full width, so it has no practical
scrim touch target. The direct You.com and Tavily gateway/parser tests then passed separately on the
same installed APK.

The older confirmation-gated real-model search path was accepted on the cover display with the same
fixed public query.
Gemma selected `web_search`; the production confirmation sheet displayed the exact query and both
possible recipients; pressing the real `실행` button produced an app-authored Tool receipt and
answer, while a second turn using the real `거절` button produced no Tool receipt. Content-free
diagnostics recorded `executed_success` for approval and `tool_not_executed` for denial. The
post-fix invalid-key state was then checked with a non-secret placeholder: the row retained
`저장됨`, showed the Tavily-specific rejection guidance, and the prior ciphertext's inode, size,
and timestamp remained unchanged. Gallery scenes remain pixel evidence only. Details and the
current evidence grade are in [`PROJECT_STATUS.md`](PROJECT_STATUS.md). Those approval/denial
receipts do not qualify the current automatic read path, which still needs Fold8 UI acceptance.

The cover Today card, unfolded two-pane workspace, and side-by-side confirmation view are later
working-tree changes. Their width policy has JVM coverage and all Android sources compile, but the
older Fold8 screenshots and lifecycle receipts above do not qualify this new layout. Re-run cover,
unfolded, IME/long-list, fold-during-confirmation, and notification-action-during-fold cases.

Later that day the physical cover display was temporarily set to night mode while the fabricated
settings and confirmation scenes were open. Cards, status chips, transcript bubbles, disclosure
copy, and both decision buttons remained visible without clipping; the system was restored to its
original `night=auto`. On the unfolded display, a temporary system rotation changed the debug chat
scene from 2,448×1,848 landscape to 1,848×2,448 portrait with the app bar, transcript, and composer
all present, then restored `user-rotation=free`, accelerometer rotation `1`, and user rotation `0`.
The real ready GPU MainActivity separately survived that same external orientation round trip, and
an Activity recreation plus background/resume test retained settings, credential-presence flags,
and the notification row count. The gallery screenshots prove only layout; the instrumentation
receipts prove lifecycle/state preservation.
