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
