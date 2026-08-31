# Proactive Opportunity Engine

## Current implementation

`ProactiveOpportunityEngine` is a deterministic, local proposal kernel. It accepts only three
closed event types:

- commitment review;
- leave-by review; and
- reminder review.

Every domain is disabled by default. Enabling a domain permits only a review proposal to be
offered to the existing commitment proposal inbox. The engine and adapter cannot create a
reminder, calendar event, alarm, notification, network request, or Tool execution.

Events contain an opaque SHA-256 event ID and typed timestamps/zone only. They have no field for
notification text, search snippets, provider responses, titles, destinations, or other source
content. Rules select fixed local summaries and fixed reason IDs. The stored provenance is limited
to bounded fixed IDs plus a domain-separated SHA-256 reference; no source content or source path is
stored.

The kernel applies, in deterministic order:

1. per-domain owner policy (default off);
2. observation and expiry checks;
3. source-reference deduplication; and
4. a bounded rolling rate cap.

The repository adapter fails closed if history cannot be read, rechecks expiry immediately before
each offer, and serializes evaluation and offers within one adapter instance. It delegates only to
`CommitmentProposalRepository.offer()`, preserving that repository's unique source hash and pending
capacity protections.

## Owner authority and existing workflow

Stored rows are ordinary commitment proposals. The existing proposal UI and
`ReminderCoordinator` remain the only dismiss/promote path. Dismiss is a terminal owner decision.
Promotion is still explicit and is the point where the existing reminder workflow may create and
schedule a reminder. The proactive engine itself never promotes a row and has no scheduling API.

## Rate-history boundary

`CommitmentProposalRepository.recentHistory()` exposes a schema-neutral Room projection containing
only `sourceRefHash` and `createdAtEpochMillis`. It includes pending, dismissed, and promoted rows,
limits the window to seven days, excludes future rows, and clamps results to at most 2,200. The
adapter loads this projection before every enabled evaluation, so an owner dismiss/promote action
cannot reset the fatigue cap and a recreated process recovers the same rate history.

History-read cancellation propagates normally. Any other database read failure stops the run before
an offer is attempted. No separate rolling store, raw proposal text, status, row ID, or provider
content is introduced.

## Activation gap

The kernel and production repository adapter are implemented but intentionally not activated.
There is no AppContainer binding, scheduler, WorkManager job, network producer, notification
listener integration, UI setting, or device action in this change. A later integration must:

- expose an explicit owner-controlled domain policy;
- reduce each local source to the closed event type before crossing the engine boundary;
- inject one process-singleton repository adapter through app wiring; and
- retain the existing explicit proposal dismiss/promote workflow.

Host JVM tests cover default-off behavior, dedupe, expiry and pre-offer recheck, rate limits,
fixed-output/write boundaries, bounded provenance, process recreation, cancellation, and database
failure. The Room all-status projection also has an Android instrumentation regression test; this
test passed as part of the 25-case `core:data` run on the isolated API 37 Foldable AVD. No
physical-device execution evidence is claimed.
