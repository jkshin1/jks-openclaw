# Korean dialogue-quality evaluation

This lane is independent from `korean-tool-use-v1.jsonl` and its scorer. It measures a small,
synthetic set of answer-content regressions without changing Tool selection, Tool arguments, model
promotion thresholds, or any Android runtime path.

## Fixed corpus

`models/eval/korean-dialogue-quality-v1.jsonl` contains eight synthetic cases covering:

- long-prompt salience;
- compound constraints;
- a reference that requires relevant recent context;
- the latest correction winning over an older statement;
- recent context winning over a stale summary;
- resistance to irrelevant history;
- clarification instead of guessing; and
- isolation from a different conversation.

Every row has the exact keys `id`, `category`, `context`, `prompt`, and `expected`. The expected
object always contains `requiredFacts`, `requiredAnyOf`, `forbiddenFacts`, `maxSentences`, and
`nonAnswer`. `excludedMessages` is test data for the isolation case; it is never submitted as part
of the evaluated conversation.

The validator uses strict JSON, rejects duplicate keys and non-finite numbers, enforces bounded
UTF-8 fields and exact case IDs/order/categories, and pins the canonical corpus SHA-256. A semantic
corpus change requires a new version rather than an in-place digest update.

```bash
python3 scripts/validate-dialogue-quality-corpus.py
```

Only synthetic facts may enter this corpus or its fixtures. Do not copy owner conversations,
notifications, credentials, addresses, provider responses, or other private content into it.

## Prediction and scoring contract

Predictions are strict JSONL rows with exactly these keys:

```json
{"id":"dialogue-long-salience-01","answer":"...","humanReview":null}
```

Score a completed prediction set with:

```bash
python3 scripts/score-dialogue-quality-eval.py \
    --predictions reports/eval/e4b-dialogue.jsonl \
    --model-label E4B \
    --output reports/eval/e4b-dialogue-score.json
```

Matching is deterministic: text is NFKC-normalized, case-folded, and whitespace-compacted before
substring checks. The scorer reports required-fact recall, required-any-of satisfaction, forbidden-
fact avoidance, sentence-limit compliance, non-answer classification, whole-case pass rate, and
human-review coverage. It reports no Tool metric and does not feed into the existing Tool quality
gate.

`humanReview` is either `null` or an exact six-boolean object:

```json
{
  "intentCaptured": true,
  "contextHandled": true,
  "constraintsSatisfied": true,
  "unsupportedClaimsAbsent": true,
  "formatFollowed": true,
  "directAndUseful": true
}
```

`reviewedDialogueSetPassed=true` requires both an 8/8 lexical screen and all six dimensions true
for every case. Merely marking a row as “reviewed” is no longer sufficient. This remains a bounded
human judgment over synthetic answers, not general semantic proof or a device receipt.

Review the prompt, supplied context, and answer before opening the lexical score to reduce
anchoring. Set a dimension to `true` only when:

- `intentCaptured`: the answer addresses the primary request rather than background examples;
- `contextHandled`: it uses necessary recent context and excludes stale, irrelevant, or other-
  conversation facts;
- `constraintsSatisfied`: every requested subpart and explicit condition is satisfied;
- `unsupportedClaimsAbsent`: it adds no conflicting, invented, or unjustified fact; and
- `formatFollowed`: it obeys the requested language, length, sentence count, and value-only form;
  and
- `directAndUseful`: it leads with the answer, is clear and proportionate, and gives the user a
  usable result rather than a technically related digression.

Uncertainty is a failure, not an invitation to infer intent on the model's behalf. Fix the model or
prompt policy, rerun the answer set, and review the new output instead of editing a failed answer.

## Claim boundary

Lexical matching is intentionally bounded. It can catch dropped markers, stale corrections,
conversation leakage, obvious evasions, and explicit sentence-limit violations. It cannot establish
that prose is fluent, reasoning is correct, paraphrases are semantically equivalent, the answer is
useful, or unsupported claims are absent. A model can also satisfy keywords while giving a poor
answer.

For those reasons:

- `lexicalScreenPassed=true` is only a regression-screen result;
- `promotionEligible` is always `false` in this scorer;
- every quality claim still requires review of the full synthetic answer set by a human; and
- on-device claims require a separately authorized run through the production Android context path,
  bound to the exact APK, model artifact, environment, and device receipt.

The offline fixture test exercises the pinned corpus, perfect and degraded scores, deterministic
output, duplicate-key and non-finite JSON rejection, unexpected-key and unknown/missing case-ID
rejection, and digest drift:

```bash
./scripts/test-dialogue-quality-eval.sh
```

This test performs no model inference, network access, Android build, emulator run, or physical-
device operation.

## Production runtime under evaluation

The production lane now tests answer quality separately from Tool accuracy:

- the system instruction privately identifies the current main goal, explicit constraints, and
  only relevant prior context; the newest explicit correction wins;
- same-conversation recent USER/ASSISTANT context receives the byte budget before summary and
  optional approved cross-thread memory, while long user rows and summaries retain both their
  subject head and newest correction tail;
- a multi-candidate request gets a compact candidate-by-constraint reject/recheck reminder and an
  explicit value-only request gets a compact final-format reminder next to the current request;
- thinking is enabled for E4B with `min(384, maxOutputTokens / 2)` reasoning tokens. Thought-channel
  deltas stream only through the active expandable UI disclosure and are never copied into the
  next runtime message, final answer, stored transcript, summary, recovery state, or diagnostics;
- a no-Tool turn cannot finish with blank text, the exact generic completion placeholder, or a
  normalized whole-prompt echo; and
- the composer accepts at most 1,408 UTF-8 bytes for sending while preserving larger editable
  drafts. The runtime still owns a 2,048-byte submitted-user envelope.

The fixed Fold8 test also reports a content-free pre-turn output budget and thermal workload for
each case. The owner-requested measurement lane keeps the selected foreground ceiling unchanged
through SEVERE; CRITICAL remains the cooperative-cancellation boundary.

Tool-backed dialogue journeys are deliberately not scored as rows in the fixed no-Tool v1 corpus.
The screenshot regression is instead pinned by deterministic Kotlin tests across four boundaries:

- the first `현재 대한민국 대통령이 누구야?` turn becomes a mandatory canonical web read before
  any local answer;
- `웹 검색 할 수 있잖아`, explicit `첫 질문` wording, and one bounded correction chain recover
  only a completed safe USER source row;
- constitution/term-only hits fail current-officeholder answerability and permit the existing
  provider fallback; and
- the final body is accepted only when its first sentence contains the evidence-backed name and
  role, while the deterministic fallback never substitutes office background for that name.

These tests use a fictional officeholder and fake providers. They prove routing and validation
contracts without freezing a real person's name, but they are not live-provider, device, ranking,
or current-fact accuracy evidence.

The bounded failed-correction/`첫 질문` Room resolver also ran in the 2026-09-01 disposable API 37
AVD suite. That full reviewed lane selected 269 methods, passed 238, reached 31 explicit owner/live
guards, and failed none. The officeholder provider fixtures remain host-only and fictional.

Within that envelope, the current clock/zone and current request are mandatory. Full calendar
display text and the focused-web reminder are lower-priority metadata and are omitted first when
space is tight. This prevents a valid long request from being rejected merely because trusted
guidance consumed its clock/zone reserve.

The production physical acceptance is
`Fold8ConversationQualityAcceptanceTest#understandsLongPrimaryIntentAndBoundedConversationContext`.
It runs four fixed synthetic, no-Tool cases through `PersonalEdgeViewModel`, Room, the context
builder, Tool scoping, and the real LiteRT runtime:

1. a 1,000--1,350-byte prompt whose answer requires applying three constraints to three candidates;
2. a reference to a fact in the immediately preceding conversation;
3. a newest correction overriding both an older row and stale summary; and
4. a current arithmetic request that must ignore unrelated history.

Every answer must equal the requested code/value after only quote/format punctuation is removed.
Expected text plus extra explanation is a failure. Any Tool row, confirmation, missing answer, or
forbidden stale/candidate value is also a failure. The test deletes only the synthetic conversation
IDs it created and asserts the global conversation/message counts return to their initial values.

## Public design references

These are design inputs, not evidence that this app behaves correctly:

- Gemini exposes saved information and personalization as explicit user-facing controls rather
  than silently treating every chat as durable memory: [saved information](https://support.google.com/gemini/answer/16598469)
  and [personalization](https://support.google.com/gemini/answer/16598406?co=GENIE.Platform%3DDesktop&hl=en).
- Apple's on-device Foundation Models guidance treats instructions, transcript, and Tool output as
  one bounded session context and recommends concise, task-specific prompting:
  [context management](https://developer.apple.com/documentation/foundationmodels/managing-the-context-window?changes=_4)
  and [prompting](https://developer.apple.com/documentation/foundationmodels/prompting-an-on-device-foundation-model?changes=latest_bet___5&language=objc).
- Gemma documents E2B/E4B thinking, separates thought from the final answer, and explicitly keeps
  only the clean final response in later conversation history; this is why this app never re-feeds
  the thought channel: [Gemma thinking](https://ai.google.dev/gemma/docs/capabilities/thinking).
- LiteRT-LM's Kotlin configuration remains the authoritative API contract for
  `thinkingTokenBudget`, response channels, and the input-plus-output context setting:
  [LiteRT-LM `Config.kt`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/Config.kt).

## Current physical evidence boundary

On the 2026-08-30 Fold8 development lane, the pre-thinking baseline passed the three context cases
but failed the long multi-constraint selection. Enabling bounded thinking plus the private
candidate checklist produced one 4/4 run; the immediate repeat produced the correct newest code
with extra prose in one case, so it was 3/4 under the exact-output contract. A later strict-format
prompt revision exposed a 2,048-byte metadata-overflow regression before model inference.

The current source replaces those three verbose trusted lines with one compact policy and makes
clock/zone retention an explicit priority over optional metadata. On 2026-09-01 the owner-approved
Fold8 final code candidate passed `releaseGate`, same-certificate `adb install -r`, exact APK
pull-back, and the four production-path cases again (4/4 in 85.001 seconds) with a 1,024-token
request ceiling for every case. Content-free before/after snapshots returned to 19 conversations
and 84 messages while preserving the 3,659,530,240-byte model, credential-presence flags, settings
digest, and original install time. A separate sustained turn reached SEVERE and completed in
97.449 seconds without app-level token reduction or cancellation. This is bounded fixed-case and
sustained-turn evidence; it is not general semantic proof and does not establish that every
available output token was consumed.
