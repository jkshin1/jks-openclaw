#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

usage() {
    cat <<'EOF'
Usage: export-diagnostics.sh --output FILE

Creates OpenClaw's sanitized diagnostics archive. The output must be a new .zip path under the
configured private backup root. Raw Gateway logs are deliberately not copied.
EOF
}

output_path=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --output)
            [[ $# -ge 2 ]] || openclaw_fail "--output requires a value"
            output_path="$2"
            shift 2
            ;;
        -h|--help) usage; exit 0 ;;
        *) openclaw_fail "unknown argument: $1" ;;
    esac
done

[[ -n "$output_path" ]] || openclaw_fail "--output is required"
[[ "$output_path" == *.zip ]] || openclaw_fail "diagnostics output must end in .zip"
[[ ! -e "$output_path" ]] || openclaw_fail "refusing to overwrite diagnostics: $output_path"
openclaw_assert_safe_path "$output_path" "diagnostics output"
openclaw_prepare_private_dir "$openclaw_backup_root" "OpenClaw backup directory"
case "$output_path" in
    "$openclaw_backup_root"/*.zip) ;;
    *) openclaw_fail "diagnostics output must be under the configured backup root" ;;
esac
openclaw_prepare_private_dir "$(dirname "$output_path")" "diagnostics output directory"
openclaw_load_deployment
openclaw_run gateway diagnostics export --output "$output_path" --json
chmod 600 "$output_path"
echo "OK sanitized diagnostics exported to $output_path"
