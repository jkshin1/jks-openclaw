# OpenClaw skill recommendations

Registry recommendations checked 2026-09-06; execution policy synchronized 2026-09-09 KST.
At the owner's request, Summarize, Whisper, Word and Excel are now installed and executable.
GitHub was excluded from this installation. See [native execution and acceptance](OPENCLAW_PRODUCTIVITY.md).

## Recommended order

| Skill | Useful work | Current Mac state | Adoption evidence | Review result |
| --- | --- | --- | --- | --- |
| [Summarize](https://github.com/steipete/summarize) | Extract and summarize articles, PDFs and local text | Bundled skill + CLI 0.21.12; isolated Codex OAuth backend, GPT-5.6 Sol/low | Upstream GitHub showed about 6.6k stars; active repository with tests and release documentation | Korean text/PDF model summaries and HTTPS extraction passed; September 8 Sol/low receipt is recorded in the productivity guide |
| [OpenAI Whisper](https://clawhub.ai/steipete/skills/openai-whisper) | Local transcription of voice recordings; text and subtitle output | Bundled skill + Whisper 20250625, downloaded multilingual small model | 87,984 downloads; 2,973 installs | Reviewed registry package pass/clean; reused newer bundled guidance; synthetic Korean transcription passed |
| [Word / DOCX](https://clawhub.ai/ivangdavila/skills/word-docx) | Word reports, styles, tables and careful revision workflows | Skill 1.0.2 + python-docx and native LibreOffice | 91,588 downloads; 2,914 installs | Pass/clean; actual agent edit, Korean PDF text and visual checks passed |
| [Excel / XLSX](https://clawhub.ai/ivangdavila/skills/excel-xlsx) | Spreadsheet generation/editing, formula and recalculation checks | Skill 1.0.2 + openpyxl and native LibreOffice | 79,921 downloads; 2,695 installs | Pass/clean; actual agent edit, formula recalculation, structure and visual checks passed |
| [GitHub](https://clawhub.ai/steipete/skills/github) | Issues, pull requests, CI results and failed-job logs | Bundled and eligible; reuse the existing skill | 197,625 downloads; 7,630 installs | `@steipete/github` 1.0.0: pass/clean; uses the installed `gh` CLI's account permissions |

Summarize extraction runs locally. Model summaries use the existing OpenClaw provider through a
scoped tool-free adapter; no provider keys were exported into a second program's environment.
Word/Excel text extraction uses the pinned local `openclaw-extract` parser.

Whisper's downloaded small model and the four skills' native engines have task-specific acceptance.
Real noisy recordings and complex Office documents still require output review. The Word and Excel
packages are workflow instructions; their libraries, rendering and recalculation engines were
installed and tested separately, including a headless Mac font-path fix.

## Optional or deferred

- [Skill Vetter](https://clawhub.ai/spclaudehome/skills/skill-vetter), `@spclaudehome/skill-vetter`
  1.0.0: 271,772 downloads / 12,190 installs, pass/clean. It is a human-readable review checklist,
  not an enforcement engine or a guarantee. OpenClaw already has `skills verify`; useful when
  adding community skills frequently, but not required to enable the recommended tools.
- [self-improving agent](https://clawhub.ai/pskoett/skills/self-improving-agent),
  `@pskoett/self-improving-agent` 4.0.2: 477,988 downloads / 18,399 installs, pass/clean.
  Defer for this deployment: its learning logs and optional transcript-sweeping hooks overlap
  with the newly defined memory policy. Add only after reviewing which one owns learning,
  retention, corrections, and deletion. No claim is made that the currently verified version
  is malicious.
- Automatic runtime/skill updater packages: defer. This Gateway has four qualified runtime patches;
  an unattended update can overwrite GLM compatibility or Telegram retry behavior. Its supported
  upgrade path includes backup, patch requalification and live acceptance.
- Extra browser/agent orchestration packages: start with the bundled `browser-automation`,
  `taskflow`, debugging and GitHub capabilities before adding parallel implementations.

## Evidence boundaries

Counts are a 2026-09-06 snapshot of the registry's `downloads` and `installs` fields, not unique
active users or proof of quality. Popularity and a security scan cannot certify behavior on this Mac.
The five reviewed small packages above (GitHub, Whisper, Word, Excel, Skill Vetter) each contain one
`SKILL.md`; their downloaded review copies matched the SHA-256 values returned by `skills verify`.
The review phase did not execute instructions from downloaded review copies; the later authorized
installation used the verified Word/Excel packages. The self-improving package has 14 files;
its registry report was reviewed, but a complete source audit was not performed.

The registry now permits the same slug under different publishers. Always use the exact scoped
reference and recheck the intended version before installation. For example:

```bash
openclaw skills verify @ivangdavila/word-docx --version 1.0.2 --json
```

The `github` and `openai-whisper` registry packages are older publications than the current bundled
guidance; installing them would duplicate or override an existing capability. Summarize's historical
unscoped registry listing is ambiguous, so this report recommends the current bundled/upstream
project rather than guessing a different publisher with the same name.

Read-only review receipts were saved under `/tmp/openclaw-verify-*.json` and
`/tmp/clawhub-*-SKILL.md` during this run. Durable facts needed to reproduce the selection are in
this report; temporary receipts are not Git artifacts.
