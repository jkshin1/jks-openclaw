# OpenClaw productivity tools

Installed for the Telegram/Mac assistant on 2026-09-07 KST. GitHub was excluded from this
installation request. That acceptance used OpenClaw 2026.9.2. The Gateway was upgraded to
2026.9.3 on September 9; current meeting workflow and transport checks are recorded in
[the upgrade record](OPENCLAW_UPDATE_20260909.md).

## Installed components

| Capability | Installed implementation | Execution |
| --- | --- | --- |
| Summarize | Existing bundled skill; official `@steipete/summarize` 0.21.12 | `summarize` |
| Whisper | Existing bundled skill; `openai-whisper` 20250625; multilingual `small` model | `whisper` |
| Word/DOCX | `@ivangdavila/word-docx` 1.0.2; python-docx 1.2.0 | `openclaw-python` |
| Excel/XLSX | `@ivangdavila/excel-xlsx` 1.0.2; openpyxl 3.1.5 | `openclaw-python` |
| Office rendering and recalculation | Native LibreOffice 26.8.0.3; PDFium renderer | `openclaw-office`, `openclaw-python` |
| Local document extraction | MarkItDown 0.1.7 with PDF/DOCX/XLSX extras; plugins disabled | `openclaw-extract` |

The Word and Excel packages were verified as `pass/clean` immediately before installation. Their
installed `SKILL.md` SHA-256 values matched the exact registry artifacts. Both packages consist of
instructions; the native Python environment and LibreOffice provide their execution engines.
All four skills report `eligible=true`, `modelVisible=true`, and no missing dependencies.

The dedicated runtime is `~/.local/share/openclaw-skill-tools/`. Its Python 3.11 virtual environment
contains the libraries above, PyTorch, pypdf, pypdfium2, and pdf2image. It does not change repository
Python environments. npm's package lock and the recorded Python freeze preserve installed versions.
LibreOffice is installed at `~/Applications/LibreOffice.app`. Command wrappers are in
`/opt/homebrew/bin`, which is visible to this OpenClaw deployment.

Use `openclaw-office` for headless conversion. Plain `soffice` in this installed version failed to
discover Mac fonts: Korean glyphs disappeared visually and PDF text mappings were corrupted.
The scoped wrapper supplies `office-fonts.conf`, explicitly listing Mac system/user fonts and
LibreOffice fonts with a separate private cache. Both visual and text checks passed afterward.
Noto Sans CJK KR Regular/Bold were also installed from the official repository at commit
`f8d157532fbfaeda587e826d4cd5b21a49186f7c`; the private receipt records source URLs and SHA-256 values.
Existing document fonts were preserved in the final acceptance run.

## Use from Telegram

- Send an article or document with "핵심 내용을 한국어로 요약해줘".
- Send an audio file with "이 녹음을 텍스트와 자막으로 만들어줘".
- Ask for a Word document, or attach one and identify the requested edit.
- Ask for an Excel workbook, or attach one and identify the cells or calculation to change.

The agent instructions name the matching skills and the installed executables. Document edits
preserve the input file. Spreadsheet edits must retain formulas and relevant workbook structure,
then recalculate and inspect cached values before reporting completion.

## Summary backend

`~/.summarize/config.json` selects Korean output and only `cli/openclaw/main`. The installed
`summarize-openclaw.py` adapter uses a fresh incognito session with `modelRun=true`,
`promptMode=none`, explicit `openai/gpt-5.6-sol`, `thinking=low`, and `deliver=false` in the
2026-09-08 policy. It waits for a successful visible terminal
answer, rejects reported tool use, and deletes the temporary session. It does not invoke the
ordinary owner chat or copy API keys into a new provider configuration. This route uses native
Codex with ChatGPT OAuth and no API-key or GLM fallback. Under the 2026-09-08 policy, the parent
Telegram conversation defaulted to GPT-6 Astra/high; `/model` changed only that conversation by
default and did not change this explicit summary route. PDF, delegated tasks and Dreaming's
internal completion separately used GPT-5.6 Sol; the scheduled Dreaming agent turn inherited the
then-default Astra/high. As of 2026-09-23, the verified global and main-agent defaults are GPT-6
Sol/high, so the scheduled outer turn inherits Sol/high. The explicit GPT-5.6 Sol routes remain
separate from that default.
On 2026-09-08, the installed `summarize --force-summary` CLI completed on Sol/low and produced a
Korean summary preserving confirmed and pending decisions. Updated model guidance was delivered
to Telegram separately. This does not repeat every Office/media scenario below; the 2026-09-07
acceptance table retains its original GLM summary backend scope.

Examples:

```bash
summarize https://example.com --extract --plain --timeout 30s
summarize /path/to/notes.txt --plain --timeout 3m
openclaw-extract /path/to/report.docx > /path/to/new-extracted-text.txt
```

PDF extraction uses the pinned local MarkItDown runtime through a narrow `UVX_PATH` bridge;
it does not install Python packages on each use. This Summarize version rejects `--extract` for
local DOCX/XLSX files. Use `openclaw-extract` for those, then summarize the extracted text file.

Behavior of the installed CLI: short sources may bypass model generation unless `--force-summary`
is supplied; CLI-backend prompt input is limited to 120 KiB. For longer sources, extract the text
and process bounded chunks. The summary cache is disabled. Published-video transcript access,
blocked websites, OCR, and arbitrary media codecs require their own acceptance; the examples do
not establish support for every URL or scanned PDF.

## Local transcription

The Whisper wrapper defaults to the downloaded multilingual `small` model, CPU, fp32, and four
threads. Explicit flags can override defaults. This gives a predictable local baseline without
requiring a new speech API key. The model was downloaded from OpenAI's model endpoint and verified
against its published SHA-256 identifier:

`9ecf779972d90ba49c06d968637d720dd632c55bbf19d441fb42bf17a411e794`

```bash
whisper /path/to/audio.wav --language Korean --output_format all --output_dir /path/to/output
```

`all` produces TXT, SRT, VTT, TSV and JSON. Clear synthetic speech is only a baseline: real recordings,
background noise, dialects, multiple speakers and proper nouns require separate review.

## Verification on this Mac, 2026-09-07 KST

| Check | Result |
| --- | --- |
| Skill inventory | All four eligible and model-visible, no missing dependencies |
| Summarize text + model | Korean meeting summary preserved confirmed/pending decisions; approximately 57 s |
| Summarize public HTTPS | `example.com` article body and source link extracted; approximately 18 s |
| Summarize PDF + model | Correct Korean document summary through the configured GLM backend; 25 s |
| Office extraction | DOCX Korean edit and XLSX cached total extracted through `openclaw-extract` |
| Whisper | Clear synthetic Korean speech transcribed locally in about 5.5 s; exact source match after removing whitespace/punctuation; TXT/SRT/VTT/TSV/JSON generated |
| Word | Actual agent edited only the requested bold text; table/header and original input preserved; two-page PDF with correct Korean text |
| Excel | Actual agent changed C5 from 2 to 4, recalculated total 157,300, retained leading-zero/long IDs, formulas, hidden sheet, named range, validation, filter, freeze panes and original input |
| Visual output | All four pages of the final native Word/Excel PDFs inspected; Korean visible, no clipped content |
| Policy | Two summary boundary tests and five gateway policy tests passed; isolated durable-delivery regression checks passed |

The first broad Office agent run timed out at 420 seconds. A focused retry with thinking off
completed the edits and conversions. Font-path repair was then verified through another actual
agent run using `openclaw-office`; it completed with the Korean PDF and formula checks passing.
These receipts establish the installed tool paths and targeted workflows, not unlimited task
complexity or timing guarantees. Normal owner thinking remains high.

Tests used synthetic files and isolated incognito sessions with `deliver=false`. They did not
send Telegram attachments or exercise the owner's real Word/Excel documents. Tracked changes,
macros, complex templates, scanned PDFs, long recordings and noisy speech are outside this receipt.

## Telegram transport acceptance, 2026-09-09 KST

`telegram-productivity-acceptance.py` now provides reproducible prepare, verify, visual-review and
send stages. It uses synthetic inputs and the installed native engines, preserving original hashes,
DOCX structure, XLSX formulas/types and recalculated total 157,300. Korean transcription/subtitles
and all four rendered Word/Excel PDF pages passed review.

The initial outbound attempt exposed the local media-root restriction. The harness now stages
only verified artifact bytes under the existing state media root. The installed runtime also
rejects direct SRT as an unknown media type; the harness packages the exact SRT in a validated
single-entry ZIP and records both the original and transport hashes. The gateway media policy was
not expanded. Telegram accepted DOCX, XLSX, two PDFs, WAV, TXT and the SRT ZIP, seven receipts
(`messageId=126` through `132`). Previously delivered files were skipped during the subtitle retry.

The harness stores private failure diagnostics and rejects unreviewed rendering, changed artifacts,
recipient mismatch, dry runs and automatic retry of uncertain outcomes. Seventeen isolated tests
cover these boundaries. Actual owner upload and phone download/opening remain outside this receipt;
no parallel `getUpdates` consumer or fabricated user update was used. See
[the operating guide](OPENCLAW_OPERATIONS_KO.md) for repeatable commands.

## Maintenance

The offline adapter boundary checks are runnable without inference:

```bash
python3 scripts/openclaw/test-summarize-openclaw.py
python3 scripts/openclaw/test-telegram-gateway.py
scripts/openclaw/verify-gateway.sh --telegram --json
```

`productivity-fixtures.py` builds fresh synthetic integration fixtures for the specifically installed
native office libraries. `check-office` checks the agent's targeted DOCX edit, preserved bold text,
table/header content, recalculated XLSX result, leading-zero and long IDs, formulas, named ranges,
hidden sheet, validation, filter and freeze panes. It never opens owner documents by discovery.

Private installation and acceptance receipts are under
`~/.openclaw-personaledge/operations/productivity-install-20260906T145704Z/`.
The pre-install OpenClaw configuration was backed up there. Generated fixtures, model weights,
runtime dependencies and private receipts are not Git artifacts.

Sources: [Summarize upstream](https://github.com/steipete/summarize),
[Whisper upstream](https://github.com/openai/whisper),
[Word skill](https://clawhub.ai/ivangdavila/skills/word-docx),
[Excel skill](https://clawhub.ai/ivangdavila/skills/excel-xlsx),
[LibreOffice distribution](https://www.libreoffice.org/download/download-libreoffice/),
[MarkItDown](https://github.com/microsoft/markitdown),
[Noto CJK](https://github.com/notofonts/noto-cjk).
