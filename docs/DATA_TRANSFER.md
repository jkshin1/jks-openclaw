# Encrypted user data transfer

Android automatic backup stays disabled. Device replacement is an explicit user-owned file flow:
the user chooses categories, reviews counts, supplies a passphrase, and selects a destination or
source with the Storage Access Framework.

The live application database is Room schema 11. The archive envelope remains binary format version
1, while the current selected-data transfer schema marker is `6` with payload version `2`. Those
versions describe the encrypted transfer format, not the current Room schema. Import also accepts
legacy marker `5` with payload version `1`; its messages predate attachment summaries and therefore
restore that field as `null`.

## Included by selection

- conversations and messages, including the app-written content-free
  `messages.attachment_summary` kind/source/whole-second code;
- approved typed memories, including validity, reconfirmation, and supersession metadata;
- app-owned reminders and bounded delivery history; and
- pending or handled commitment proposals.

## Structurally excluded

- Keystore-backed credentials and ciphertext;
- Action Ledger claims and execution identity;
- turn outcomes, ordered read executions, unresolved side-effect obligations, and AgentPlan
  checkpoints;
- captured KakaoTalk notification rows and source keys;
- model artifacts, diagnostics, settings consent, permissions, and provider row IDs.

Of calendar metadata, only a label hint may cross the archive. Import marks calendar remapping as
required; the new device must grant access and explicitly select its read calendars and one writable
calendar. A label is never treated as provider identity.

## Archive boundary

Version 1 uses PBKDF2-HMAC-SHA256 with a random 16-byte salt, 210,000 iterations, a 256-bit key,
and AES-GCM with a random 12-byte nonce. The authenticated header binds magic, archive and transfer-
schema versions, selection flags, plaintext length, salt, nonce, and plaintext SHA-256. Passphrases
must contain 12–128 Unicode code points.

Transfer schema `6` adds one nullable field after each message's existing payload: the bounded
attachment summary. Its validator accepts only canonical image or audio shape codes and never media
bytes, file names, paths, URIs, dimensions, millisecond timing, or captured content. The schema and
payload versions advance together so a legacy schema `5` archive remains unambiguous and importable.

Version words are interpreted without narrowing through a signed `Int`; unsupported values,
including injected `Long.MAX_VALUE` byte patterns, fail closed. Plaintext and ciphertext lengths
must be in range, AES-GCM ciphertext must equal plaintext plus the tag, and the declared ciphertext
must exactly match the bytes remaining in the archive before allocation. Encoding uses a bounded
output stream, so oversized aggregate content is rejected while it is written rather than after an
unbounded buffer growth.

Payload booleans accept only `0` or `1`. Counts and string lengths are treated as unsigned
declarations, checked against both fixed maxima and remaining bytes before list/string allocation.
Malformed UTF-8, unknown enums/flags, trailing data, authentication failure, and hash mismatch all
return closed failure codes without including user content in logs or errors.

## Graph validation before import

The same full validator runs before archive creation, after decode, and as the first statement of
the Room import transaction. It enforces:

- fixed maxima for conversations, messages, memories, reminders, deliveries, and proposals;
- unique row IDs in every collection;
- selected-category consistency and valid parent conversation/reminder references;
- positive, unique, contiguous message ordinals per conversation;
- summary boundaries that neither exceed the highest retained message nor leave an unexplained
  gap; retained recent messages may overlap a valid summary boundary, matching normal compaction;
- safe text/ID/hash/time-zone/recurrence/timestamp fields; and
- reminder schedule versions that can be incremented without overflow.

Any structural failure occurs before a row is inserted. Import remains one transaction and uses
insert-ignore merge semantics, so existing local rows are not overwritten. Messages and deliveries
are imported only with parents newly inserted by that merge.

Future active reminders become pending with a new schedule version. Past one-shot reminders remain
overdue/delivered without immediately firing an old alert; recurring reminders advance to a future
occurrence. Reconciliation runs only after a successful merge.

## Evidence boundary

JVM tests cover round trip, wrong passphrase, tampering, version/length/count attacks, collection
caps, duplicate IDs, ordinal/summary invariants, and overflow refusal. Android test source covers
merge behavior and rejection before partial import. The current rc11 host `releaseGate` passed and
the Room/data API 37 AVD suite passed 82/82. No physical-device installation or SAF/new-device test
was performed for this working tree. Real file copy, clean-device merge, calendar remap, and alert
reconciliation remain external acceptance gates.
