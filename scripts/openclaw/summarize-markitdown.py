#!/usr/bin/env python3
"""Narrow UVX_PATH bridge to the pinned, already-installed local document parser.

Summarize calls UVX_PATH with: --from markitdown[all] markitdown INPUT.
Also accepts one local path for the openclaw-extract command.
Only conversion is supported; no package installation or plugins.
"""

from pathlib import Path
import sys


def main():
    if len(sys.argv) == 2:
        source = Path(sys.argv[1])
    elif len(sys.argv) == 5 and sys.argv[1:4] == ["--from", "markitdown[all]", "markitdown"]:
        source = Path(sys.argv[4])
    else:
        raise ValueError("Only local MarkItDown conversion is supported")
    if not source.is_file() or source.suffix.lower() not in {".pdf", ".docx", ".xlsx"}:
        raise ValueError("A local PDF, DOCX or XLSX file is required")
    from markitdown import MarkItDown
    print(MarkItDown(enable_plugins=False).convert_local(str(source)).text_content)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Local document extraction failed ({type(error).__name__}).", file=sys.stderr)
        sys.exit(1)
