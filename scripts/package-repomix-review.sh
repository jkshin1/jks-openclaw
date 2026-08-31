#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/.." && pwd)"
requested_output="${1:-reports/repomix/personal-edge-review.xml}"

if [[ "$requested_output" = /* ]]; then
    review_output="$requested_output"
else
    review_output="$project_root/$requested_output"
fi

if ! command -v repomix >/dev/null 2>&1; then
    echo "ERROR: repomix is not installed or not on PATH." >&2
    exit 1
fi

review_tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-repomix.XXXXXX")"
trap 'rm -rf -- "$review_tmp_dir"' EXIT
raw_output="$review_tmp_dir/raw-review.xml"

mkdir -p "$(dirname "$review_output")"

(
    cd "$project_root"
    repomix . \
        --config "$project_root/repomix.config.json" \
        --output "$raw_output"
)

# Repomix/Secretlint protects credentials. This second, output-only pass removes owner/device
# identifiers without weakening the source snapshot used by builds and evidence documents.
perl -CSDA -pe '
    s{\bR5[A-Z0-9]{9}\b}{[REDACTED_ANDROID_SERIAL]}g;
    s{/Users/[A-Za-z0-9._-]+}{/Users/[REDACTED_USER]}g;
    s{\b[A-Za-z0-9._%+-]+\@(?:naver\.com|gmail\.com|googlemail\.com|daum\.net|hanmail\.net|kakao\.com|icloud\.com|outlook\.com|hotmail\.com)\b}{[REDACTED_EMAIL]}gi;
    s{(?<![0-9A-Za-z])(?:\+?82[- ]?)?10[- ]?[0-9]{3,4}[- ]?[0-9]{4}(?![0-9A-Za-z])}{[REDACTED_PHONE]}g;
    s{\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b}{[REDACTED_MAC]}g;
' "$raw_output" > "$review_output"

privacy_scan_status=0
rg --pcre2 -q 'R5[A-Z0-9]{9}|/Users/[A-Za-z0-9._-]+|\b[A-Za-z0-9._%+-]+\@(naver\.com|gmail\.com|googlemail\.com|daum\.net|hanmail\.net|kakao\.com|icloud\.com|outlook\.com|hotmail\.com)\b|(?<![0-9A-Za-z])(?:\+?82[- ]?)?10[- ]?[0-9]{3,4}[- ]?[0-9]{4}(?![0-9A-Za-z])|\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b' "$review_output" || privacy_scan_status=$?
if [[ "$privacy_scan_status" -eq 0 ]]; then
    echo "ERROR: privacy sanitization verification failed." >&2
    exit 1
fi
if [[ "$privacy_scan_status" -ne 1 ]]; then
    echo "ERROR: privacy sanitization scan could not be completed." >&2
    exit 1
fi

if rg -q -- '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\b(?:AKIA|ASIA)[A-Z0-9]{16}\b|\bAIza[0-9A-Za-z_-]{35}\b' "$review_output"; then
    echo "ERROR: credential-pattern verification failed." >&2
    exit 1
fi

output_bytes="$(stat -f '%z' "$review_output")"
output_sha256="$(shasum -a 256 "$review_output" | awk '{print $1}')"
echo "Review bundle: $review_output"
echo "Bytes: $output_bytes"
echo "SHA-256: $output_sha256"
