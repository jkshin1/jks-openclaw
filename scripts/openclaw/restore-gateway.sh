#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
if [[ "${1:-}" == "--telegram" ]]; then
    shift
    exec python3 "$script_dir/telegram-backup.py" rehearse "$@"
fi
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

usage() {
    cat <<'EOF'
Usage: restore-gateway.sh --archive FILE --target NEW_DIRECTORY --apply
       restore-gateway.sh --telegram --archive FILE --target NEW_DIRECTORY --apply

Verifies and restores a trusted OpenClaw archive into a fresh staging directory. It never
overwrites or activates live state. Review the manifest and follow docs/OPENCLAW_GATEWAY.md before
an offline activation.
EOF
}

archive_path=""
target_dir=""
apply=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --archive)
            [[ $# -ge 2 ]] || openclaw_fail "--archive requires a value"
            archive_path="$2"
            shift 2
            ;;
        --target)
            [[ $# -ge 2 ]] || openclaw_fail "--target requires a value"
            target_dir="$2"
            shift 2
            ;;
        --apply) apply=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) openclaw_fail "unknown argument: $1" ;;
    esac
done

[[ -n "$archive_path" ]] || openclaw_fail "--archive is required"
[[ -n "$target_dir" ]] || openclaw_fail "--target is required"
(( apply == 1 )) || openclaw_fail "refusing to restore without --apply"
openclaw_assert_private_file "$archive_path" "OpenClaw backup archive"
openclaw_assert_safe_path "$target_dir" "restore staging directory"
for live_root in "$openclaw_state_dir" "$openclaw_workspace_dir" "$openclaw_runtime_root"; do
    case "$target_dir/" in
        "$live_root/"|"$live_root"/*) openclaw_fail "restore target is inside live state: $live_root" ;;
    esac
    case "$live_root/" in
        "$target_dir/"|"$target_dir"/*) openclaw_fail "restore target contains live state: $live_root" ;;
    esac
done
[[ ! -e "$target_dir" ]] || {
    [[ -d "$target_dir" && ! -L "$target_dir" ]] ||
        openclaw_fail "restore target must be a new or empty real directory"
    first_entry="$(find "$target_dir" -mindepth 1 -maxdepth 1 -print -quit)"
    [[ -z "$first_entry" ]] || openclaw_fail "restore target must be empty"
}

openclaw_load_deployment
openclaw_run backup restore "$archive_path" --target "$target_dir"
chmod 700 "$target_dir"
echo "OK archive verified and restored to staging only: $target_dir"
echo "NOTE Live Gateway state was not changed or activated."
