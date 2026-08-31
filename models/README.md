# Model artifacts

Model binaries are intentionally excluded from Git and the base APK.

The first device vertical slice uses:

- Repository: `litert-community/gemma-4-E4B-it-litert-lm`
- Candidate file: `gemma-4-E4B-it.litertlm`
- Exact size: 3,659,530,240 bytes (3.66 GB / 3.408 GiB)
- Supported context ceiling: 32K; initial Android runtime budget: 4,096 tokens

The accepted candidate metadata is pinned in `model-manifest.json`. It records upstream
revision `2eee7ac325f20eb8c9ac1d0e972f7c84663062da`, exact byte size, SHA-256, and
LiteRT-LM compatibility. The binary becomes usable only after `verify-model.sh` succeeds.
After the project receives its first commit, the tracked manifest is the trust root while
the downloaded artifact remains ignored; an uncommitted working tree is not a release
trust anchor.

```bash
source ./scripts/android-env.sh
./scripts/download-model.sh
./scripts/verify-model.sh
```

Record physical-device CPU/GPU benchmark receipts separately before promoting a backend
or a replacement model revision.

`model-manifest-qwen3-8b.json` is a separate candidate trust root for the instrumentation-only
`qwen8bLab` build. It must never replace `model-manifest.json` or share the production model store.
The candidate binary remains ignored and excluded from every APK; its exact size and SHA-256 must
be verified independently before a named lab instrumentation run. Current API 37 CPU evidence
proves text generation and cancellation recovery but fails structured Tool calling, so this
manifest is reproducibility metadata rather than promotion approval.

Before comparing E4B, an MTP E4B artifact, E2B, or another candidate, validate the fixed synthetic
Korean corpus and score content-minimal receipts as described in
[`../docs/MODEL_EVALUATION.md`](../docs/MODEL_EVALUATION.md). The current runtime keeps the 4,096
context ceiling and selects a 256, 384, or 1,024 native output ceiling per top-level prompt. Do not
raise context length or replace the pinned artifact from host scores alone; PSS, thermal, battery,
fold transitions, and cancellation recovery require the physical Fold8.
