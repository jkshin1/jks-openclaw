#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
models_dir="$project_root/models"
manifest_path="$models_dir/model-manifest.json"

for required_command in jq perl curl df awk ln unlink; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        echo "$required_command is required to download the model." >&2
        exit 1
    fi
done

model_file="$(jq -er '.file' "$manifest_path")"
model_repository="$(jq -er '.repository' "$manifest_path")"
model_revision="$(jq -er '.revision' "$manifest_path")"
model_sha256="$(jq -er '.sha256' "$manifest_path")"
expected_size="$(jq -er '.sizeBytes' "$manifest_path")"
download_url="$(jq -er '.downloadUrl' "$manifest_path")"
target_path="$models_dir/$model_file"
partial_path="$models_dir/.download-$model_sha256.partial"
lock_path="$models_dir/.download.lock"

if [[ "$model_file" != "$(basename "$model_file")" || "$model_file" == .* ]]; then
    echo "Unsafe model filename in manifest: $model_file" >&2
    exit 1
fi

if [[ "$model_repository" != "litert-community/gemma-4-E4B-it-litert-lm" ||
      ! "$model_revision" =~ ^[0-9a-f]{40}$ ||
      ! "$model_sha256" =~ ^[0-9a-f]{64}$ ||
      ! "$expected_size" =~ ^[1-9][0-9]*$ ||
      ${#expected_size} -gt 18 ]]; then
    echo "Invalid pinned model identity in $manifest_path" >&2
    exit 1
fi

expected_url="https://huggingface.co/$model_repository/resolve/$model_revision/$model_file"
if [[ "$download_url" != "$expected_url" ]]; then
    echo "Model URL does not match the pinned repository, revision, and filename." >&2
    exit 1
fi

if [[ ! -d "$models_dir" || -L "$models_dir" ]]; then
    echo "Models directory is missing or unsafe: $models_dir" >&2
    exit 1
fi

perl_bin="$(command -v perl)"
curl_bin="$(command -v curl)"

stat_fd() {
    local fd="$1"
    "$perl_bin" -MFcntl=:mode -e '
        my @s = stat(STDIN);
        exit 2 unless @s;
        printf "%u:%u:%u:%o:%u:%u\n",
            $s[0], $s[1], $s[3], $s[2], $s[7], S_ISREG($s[2]) ? 1 : 0;
    ' <&"$fd"
}

lstat_path() {
    local path="$1"
    "$perl_bin" -MFcntl=:mode -e '
        my @s = lstat($ARGV[0]);
        exit 2 unless @s;
        printf "%u:%u:%u:%o:%u:%u\n",
            $s[0], $s[1], $s[3], $s[2], $s[7], S_ISREG($s[2]) ? 1 : 0;
    ' -- "$path"
}

unlink_if_identity() {
    local path="$1"
    local expected_dev="$2"
    local expected_ino="$3"
    "$perl_bin" -MFcntl=:mode -e '
        my ($path, $expected_dev, $expected_ino) = @ARGV;
        my @s = lstat($path);
        exit 2 unless @s;
        exit 3 unless S_ISREG($s[2]);
        exit 4 unless $s[0] == $expected_dev && $s[1] == $expected_ino;
        unlink($path) or exit 5;
    ' -- "$path" "$expected_dev" "$expected_ino"
}

create_private_file_if_missing() {
    local path="$1"
    if [[ ! -e "$path" && ! -L "$path" ]]; then
        if ! (umask 077; set -o noclobber; : > "$path") 2>/dev/null; then
            if [[ ! -e "$path" && ! -L "$path" ]]; then
                echo "Could not create private file: $path" >&2
                exit 1
            fi
        fi
    fi
}

published_by_run=0
install_complete=0
published_dev=""
published_ino=""

cleanup() {
    local status=$?
    local target_stat=""
    local target_dev=""
    local target_ino=""
    trap - EXIT INT TERM

    if (( status != 0 && published_by_run == 1 && install_complete == 0 )); then
        target_stat="$(lstat_path "$target_path" 2>/dev/null || true)"
        if [[ -n "$target_stat" ]]; then
            IFS=: read -r target_dev target_ino _ <<< "$target_stat"
            if [[ "$target_dev" == "$published_dev" && "$target_ino" == "$published_ino" ]]; then
                # macOS cannot atomically unlink a pathname only if it still names a
                # given inode. Preserve even our expected inode because a same-UID
                # replacement between lstat and unlink could otherwise be deleted.
                echo "Leaving failed publication in place for fail-closed inspection: $target_path" >&2
            else
                echo "Published target identity changed; leaving it untouched: $target_path" >&2
            fi
        fi
    fi

    exit "$status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

create_private_file_if_missing "$lock_path"
lock_path_stat="$(lstat_path "$lock_path" 2>/dev/null || true)"
if [[ -z "$lock_path_stat" ]]; then
    echo "Download lock is missing or unsafe: $lock_path" >&2
    exit 1
fi
IFS=: read -r _ _ lock_nlink lock_mode _ lock_regular <<< "$lock_path_stat"
if [[ "$lock_regular" != "1" || "$lock_nlink" != "1" || "$lock_mode" != "100600" ]]; then
    echo "Download lock must be a private, single-link regular file: $lock_path" >&2
    exit 1
fi

exec 8<> "$lock_path"
lock_fd_stat="$(stat_fd 8)"
if [[ "$lock_fd_stat" != "$lock_path_stat" ]]; then
    echo "Download lock path changed while it was opened: $lock_path" >&2
    exit 1
fi

if ! "$perl_bin" -MFcntl=:flock -e \
    'flock(STDIN, LOCK_EX | LOCK_NB) or exit 75;' <&8; then
    echo "Another model download owns the OS lock: $lock_path" >&2
    exit 1
fi

if [[ "$(stat_fd 8)" != "$(lstat_path "$lock_path" 2>/dev/null || true)" ]]; then
    echo "Download lock path changed after lock acquisition: $lock_path" >&2
    exit 1
fi

if [[ -e "$target_path" || -L "$target_path" ]]; then
    "$project_root/scripts/verify-model.sh" "$target_path"
    exit 0
fi

create_private_file_if_missing "$partial_path"
partial_path_stat="$(lstat_path "$partial_path" 2>/dev/null || true)"
if [[ -z "$partial_path_stat" ]]; then
    echo "Partial model is missing or unsafe: $partial_path" >&2
    exit 1
fi
IFS=: read -r _ _ partial_nlink partial_mode _ partial_regular <<< "$partial_path_stat"
if [[ "$partial_regular" != "1" || "$partial_nlink" != "1" || "$partial_mode" != "100600" ]]; then
    echo "Partial model must be a private, single-link regular file: $partial_path" >&2
    exit 1
fi

exec 9>> "$partial_path"
partial_fd_stat="$(stat_fd 9)"
if [[ "$partial_fd_stat" != "$partial_path_stat" ]]; then
    echo "Partial model path changed while it was opened: $partial_path" >&2
    exit 1
fi
IFS=: read -r partial_dev partial_ino _ _ current_partial_size _ <<< "$partial_fd_stat"

assert_partial_identity() {
    local fd_stat
    local path_stat
    local current_dev
    local current_ino
    local current_nlink
    local current_mode
    local current_regular

    fd_stat="$(stat_fd 9)"
    path_stat="$(lstat_path "$partial_path" 2>/dev/null || true)"
    if [[ -z "$path_stat" || "$fd_stat" != "$path_stat" ]]; then
        echo "Partial model path changed during download: $partial_path" >&2
        exit 1
    fi
    IFS=: read -r current_dev current_ino current_nlink current_mode current_partial_size current_regular <<< "$fd_stat"
    if [[ "$current_dev" != "$partial_dev" ||
          "$current_ino" != "$partial_ino" ||
          "$current_nlink" != "1" ||
          "$current_mode" != "100600" ||
          "$current_regular" != "1" ]]; then
        echo "Partial model descriptor identity changed during download: $partial_path" >&2
        exit 1
    fi
}

assert_partial_identity
if (( current_partial_size > expected_size )); then
    echo "Partial model exceeds the pinned size: $current_partial_size > $expected_size" >&2
    exit 1
fi

preflight_available_space() {
    local remaining_bytes
    local available_kb
    local available_bytes

    remaining_bytes=$((expected_size - current_partial_size))
    if (( remaining_bytes == 0 )); then
        return
    fi
    available_kb="$(LC_ALL=C df -Pk "$models_dir" | awk 'NR == 2 { print $4; exit }')"
    if [[ ! "$available_kb" =~ ^[0-9]+$ || ${#available_kb} -gt 15 ]]; then
        echo "Could not determine available space for $models_dir" >&2
        exit 1
    fi
    available_bytes=$((available_kb * 1024))
    if (( available_bytes < remaining_bytes )); then
        echo "Insufficient space for model download: need $remaining_bytes bytes, have $available_bytes bytes." >&2
        exit 1
    fi
    echo "Download preflight: $remaining_bytes bytes remaining, $available_bytes bytes available."
}

download_to_expected_size() {
    local max_attempts=5
    local attempt=1
    local transfer_limit
    local curl_status=0
    local -a curl_arguments

    preflight_available_space
    while (( current_partial_size < expected_size )); do
        transfer_limit=$((expected_size - current_partial_size))
        curl_arguments=(
            --proto '=https'
            --proto-redir '=https'
            --location
            --fail
            --silent
            --show-error
            --connect-timeout 30
            --speed-limit 1024
            --speed-time 60
            --max-time 14400
            --max-filesize "$transfer_limit"
        )
        if (( current_partial_size > 0 )); then
            curl_arguments+=(--continue-at "$current_partial_size")
        fi

        echo "Downloading pinned model revision (attempt $attempt/$max_attempts)..."
        if "$curl_bin" "${curl_arguments[@]}" --output - "$download_url" >&9; then
            curl_status=0
        else
            curl_status=$?
        fi

        assert_partial_identity
        if (( current_partial_size > expected_size )); then
            echo "Downloaded data exceeds the pinned size: $current_partial_size > $expected_size" >&2
            exit 1
        fi
        if (( current_partial_size == expected_size )); then
            break
        fi
        if (( attempt >= max_attempts )); then
            echo "Model download did not reach the pinned size after $max_attempts attempts (curl status $curl_status)." >&2
            exit 1
        fi
        attempt=$((attempt + 1))
        sleep 2
    done
}

partial_verified=0
for verification_round in 1 2; do
    download_to_expected_size
    if "$project_root/scripts/verify-model.sh" "$partial_path"; then
        partial_verified=1
        break
    fi

    assert_partial_identity
    if (( verification_round == 2 )); then
        echo "Downloaded model failed pinned verification twice; preserving the partial for inspection." >&2
        exit 1
    fi
    echo "Discarding corrupt partial contents through the owned descriptor and retrying once." >&2
    if ! "$perl_bin" -e 'truncate(STDIN, 0) or exit 1;' <&9; then
        echo "Could not reset corrupt partial contents safely: $partial_path" >&2
        exit 1
    fi
    assert_partial_identity
    if (( current_partial_size != 0 )); then
        echo "Corrupt partial did not reset to zero bytes: $partial_path" >&2
        exit 1
    fi
done

if (( partial_verified != 1 )); then
    echo "Model partial was not verified: $partial_path" >&2
    exit 1
fi
assert_partial_identity
if (( current_partial_size != expected_size )); then
    echo "Verified partial size changed unexpectedly: $current_partial_size" >&2
    exit 1
fi

if [[ -e "$target_path" || -L "$target_path" ]]; then
    echo "Target appeared during verification; refusing to overwrite: $target_path" >&2
    exit 1
fi

published_dev="$partial_dev"
published_ino="$partial_ino"
if ! ln "$partial_path" "$target_path"; then
    echo "Could not publish model without overwriting an existing target." >&2
    exit 1
fi
published_by_run=1

target_stat="$(lstat_path "$target_path" 2>/dev/null || true)"
if [[ -z "$target_stat" ]]; then
    echo "Published model path cannot be inspected: $target_path" >&2
    exit 1
fi
IFS=: read -r target_dev target_ino target_nlink target_mode target_size target_regular <<< "$target_stat"
partial_fd_stat="$(stat_fd 9)"
partial_path_stat="$(lstat_path "$partial_path" 2>/dev/null || true)"
if [[ "$target_stat" != "$partial_fd_stat" ||
      "$partial_path_stat" != "$partial_fd_stat" ||
      "$target_dev" != "$partial_dev" ||
      "$target_ino" != "$partial_ino" ||
      "$target_nlink" != "2" ||
      "$target_mode" != "100600" ||
      "$target_size" != "$expected_size" ||
      "$target_regular" != "1" ]]; then
    echo "Published model identity mismatch." >&2
    exit 1
fi

if ! unlink_if_identity "$partial_path" "$partial_dev" "$partial_ino"; then
    echo "Could not remove the verified partial link safely: $partial_path" >&2
    exit 1
fi

final_fd_stat="$(stat_fd 9)"
final_target_stat="$(lstat_path "$target_path" 2>/dev/null || true)"
IFS=: read -r final_dev final_ino final_nlink final_mode final_size final_regular <<< "$final_target_stat"
if [[ "$final_fd_stat" != "$final_target_stat" ||
      "$final_dev" != "$partial_dev" ||
      "$final_ino" != "$partial_ino" ||
      "$final_nlink" != "1" ||
      "$final_mode" != "100600" ||
      "$final_size" != "$expected_size" ||
      "$final_regular" != "1" ]]; then
    echo "Final model identity mismatch after publication." >&2
    exit 1
fi

install_complete=1
echo "Installed verified model: $target_path"
