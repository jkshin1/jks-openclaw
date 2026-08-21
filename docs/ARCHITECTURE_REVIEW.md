# Architecture review

Reviewed on 2026-08-21 against the supplied 1,441-line design and current official
Android/LiteRT-LM documentation.

## Outcome

The core boundary is sound:

```text
LLM = judgment
Kotlin runtime = control
Tool = execution
```

The project should begin with one secure vertical slice: Compose text input, on-device
inference, a fake typed tool, Kotlin validation, confirmation, execution, and result
reinjection. Stable integrations should only be added after that loop is measurable on
the Fold8.

## Decisions encoded in this scaffold

1. LiteRT-LM is pinned to `0.16.1`; dynamic dependency versions are not allowed.
2. `ConversationConfig.automaticToolCalling` is always `false`. Model output is
   untrusted input and cannot execute tools directly.
3. Tool contracts are typed and separate validation from execution.
4. Communication, vehicle control, high-risk actions, and data writes require a
   confirmation policy. Confirmation is requested inside the orchestrator and is bound
   to an immutable canonical input, its digest, preview, and expiry. Execution uses that
   same snapshot, and expiry is checked again after the confirmation UI returns.
5. Model files are not committed or bundled in the base APK. They require revision,
   size, and SHA-256 verification plus resumable/atomic installation.
6. Accessibility automation is outside the Play-safe MVP. Kakao new-message and Samsung
   Clock UI automation belong in an explicit sideload-only experimental variant.
7. The initial maximum output is 1,024 tokens, not 4,000. The fake-only loop starts with
   two model steps, one Tool call, and a 60-second budget.
8. Raw model thinking is neither displayed nor persisted.
9. Side-effecting tools are rejected unless the orchestrator holds the module-owned
   `PersistentActionLedger` capability. The real atomic/process-persistent implementation
   is intentionally deferred; test fakes do not become production registrations.
10. The trusted Kotlin workflow, never model output, owns the stable request ID used to
    derive idempotency keys across retries and process restarts.
11. `LlmRuntime` accepts an opaque `VerifiedInstalledModel`, not a raw path. Model events,
    Tool calls, cancellation, and Tool responses are typed and bound to one turn ID.
12. The shipped demonstration Tool is truthfully `READ_ONLY` because it performs no side
    effect, but its `minimumConfirmation` raises the effective policy to explicit user
    confirmation. It does not pretend that an in-process ledger is durable.
13. The model package can support up to 32K context, but the first Android runtime budget
    is 4,096 total input/output tokens. A 32K CPU session reached 10GB RSS and was killed
    by LMK on the 12GB API 37 test AVD during first decode. Larger 8K/16K/32K budgets stay
    disabled until peak-memory, latency, and sustained-decode checks pass on the Fold8.
14. Top-level user requests are stateless in this first slice: a fresh Conversation is
    created before each later request. The Tool call and its response remain in one
    Conversation, with a native token-count guard reserving room for the final output.
15. LiteRT-LM exposes native Tool arguments as a parsed Map, not raw JSON. Exact field,
    type, nesting, and post-serialization size checks remain enforced, but duplicate-key
    evidence is already lost at that SDK boundary. Raw duplicate rejection therefore
    remains an upstream API/qualification gate rather than a production claim.
16. The fake Tool's trusted result is the minimal `{"simulated":true}` receipt. It never
    echoes model-controlled arguments into the Gemma Tool-response template, and pinned
    model control-token delimiters are rejected before confirmation or execution.
17. Gradle dependencies are locked per module and checksum verified. The LiteRT-LM group
    resolves only from the dedicated Google repository declaration.
18. Physical-device diagnostics use a fixed typed schema and bounded private rotation. Prompts,
    outputs, Tool arguments, confirmation material, paths, URIs, and raw serials cannot enter that
    schema. Raw app logcat and bugreports remain explicit sensitive opt-ins rather than default
    evidence.

## Required product decisions before external tools

- Distribution: private sideload only, Google Play, or separate product flavors.
- Calendar primary: `CalendarContract` on the device or Google Calendar REST/OAuth.
- One web-search provider and its data-retention terms.
- NAVER Maps and search credentials: APK embedding is not sufficient; use a narrow
  gateway when secrets must remain confidential.
- Sensitive-data retention, deletion, Android backup exclusion, and export policy.
- Model artifact/revision after CPU and GPU benchmarks on the physical Fold8.

## MVP boundary

Included after the secure vertical slice: web search, route estimate, calendar,
standard Android alarm intents, and Kakao notification capture/search.

Excluded: Kakao reply/send, Accessibility-driven Kakao send, full Samsung Clock editing,
Polestar control, always-on voice, multimodal input, and autonomous background workflows.

## Acceptance metrics to add

- cold/warm model load, time to first token, decode tokens/sec;
- peak resident/GPU memory, thermal state, and battery drain;
- tool-selection accuracy and valid-argument rate in Korean;
- zero confirmation bypasses and zero duplicate side effects;
- an atomic execution-time thermal/safety interlock before enabling any real side-effecting Tool;
- cancellation, timeout, process-death, and interrupted-download recovery;
- folded/unfolded, rotation, multi-window, and background/foreground behavior.
