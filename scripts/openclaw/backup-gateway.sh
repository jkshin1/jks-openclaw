#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

usage() {
    cat <<'EOF'
Usage: backup-gateway.sh [--output DIRECTORY] --apply

Creates and verifies a consistent OpenClaw full archive with the official online-backup command.
Never raw-copies live SQLite, WAL, SHM, or journal files. Backups contain credentials and must be
kept encrypted off this Mac as well as locally.
EOF
}

output_dir="$openclaw_backup_root/full"
apply=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --output)
            [[ $# -ge 2 ]] || openclaw_fail "--output requires a value"
            output_dir="$2"
            shift 2
            ;;
        --apply) apply=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) openclaw_fail "unknown argument: $1" ;;
    esac
done

(( apply == 1 )) || openclaw_fail "refusing to write a credential-bearing backup without --apply"
openclaw_assert_safe_path "$output_dir" "backup output directory"
case "$output_dir/" in
    "$openclaw_backup_root/"|"$openclaw_backup_root"/*) ;;
    *) openclaw_fail "backup output must stay under the configured backup root" ;;
esac
openclaw_prepare_private_dir "$output_dir" "backup output directory"
openclaw_load_deployment
openclaw_run backup create --output "$output_dir" --verify
echo "OK OpenClaw created and verified a consistent private backup under $output_dir"
