#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
source "$script_dir/_common.sh"
source "$script_dir/watchdog.sh"
case "${1:---dry-run}" in
    --dry-run) apply=0 ;;
    --apply) apply=1 ;;
    -h|--help)
        echo "Usage: install-colima-runtime.sh [--dry-run|--apply]"
        echo "Installs the existing, stopped personaledge Colima profile as an owner LaunchAgent."
        exit 0 ;;
    *) openclaw_fail "expected --dry-run or --apply" ;;
esac
[[ $# -le 1 && "$(uname -s)" == Darwin ]] || openclaw_fail "one option and macOS are required"
colima_bin="${PERSONAL_EDGE_COLIMA_BIN:-/opt/homebrew/bin/colima}"
colima_home="$openclaw_user_root/.colima"
profile_config="$colima_home/personaledge/colima.yaml"
label="com.personaledge.colima-runtime"
plist_path="$openclaw_launch_agent_dir/$label.plist"
log_dir="$openclaw_state_dir/operations/colima-runtime"
service_target="gui/$(id -u)/$label"
[[ -x "$colima_bin" ]] || openclaw_fail "Colima executable is missing"
[[ "$(openclaw_runtime_bounded 10 env PATH="$openclaw_colima_command_path" "$colima_bin" version | head -1)" == "colima version 0.10.3" ]] ||
    openclaw_fail "this asset requires reviewed Colima 0.10.3"
for checked_path in "$profile_config" "$plist_path" "$log_dir"; do
    openclaw_assert_safe_path "$checked_path" "Colima runtime path"
done
openclaw_assert_owned_nonwritable_file "$profile_config" "existing Colima profile"
# Share the exact reviewed profile and service contract with readiness/watchdog/soak.
openclaw_assert_colima_profile
expected_plist="$(openclaw_colima_expected_plist)"
if [[ -e "$plist_path" || -L "$plist_path" ]]; then
    openclaw_assert_private_file "$plist_path" "existing Colima LaunchAgent"
    actual_plist="$(plutil -convert json -o - "$plist_path")"
    jq -e --argjson expected "$expected_plist" '. == $expected' <<< "$actual_plist" >/dev/null ||
        openclaw_fail "existing Colima LaunchAgent differs; it was not overwritten"
    if launchctl print "$service_target" >/dev/null 2>&1; then
        runtime_temp="$(mktemp -d "$openclaw_tmp_root/personal-edge-colima-install.XXXXXX")"
        chmod 700 "$runtime_temp"
        trap 'rm -rf -- "$runtime_temp"' EXIT
        snapshot="$(openclaw_colima_snapshot "$runtime_temp")"
        jq -e '.healthy' <<< "$snapshot" >/dev/null ||
            openclaw_fail "matching Colima job is loaded but Docker readiness failed; nothing changed"
        echo "OK matching Colima LaunchAgent is loaded and Docker readiness passes."
        exit 0
    fi
else
    if launchctl print "$service_target" >/dev/null 2>&1; then
        openclaw_fail "Colima job is loaded without its matching owner plist"
    fi
fi
# Colima start --foreground exits immediately on an already-active profile. Never stop a VM here.
profile_status="$(env PATH="$openclaw_colima_command_path" COLIMA_HOME="$colima_home" "$colima_bin" list --json | jq -se '
    [.[] | select(.name == "personaledge")] | select(length == 1) | .[0].status')"
[[ "$profile_status" == '"Stopped"' ]] ||
    openclaw_fail "personaledge must be stopped before foreground LaunchAgent ownership"
if (( apply == 0 )); then
    echo "DRY-RUN: existing profile preserved; would install/load $plist_path"
    jq . <<< "$expected_plist"
    exit 0
fi
openclaw_prepare_owned_dir "$openclaw_launch_agent_dir" "LaunchAgents directory"
openclaw_prepare_private_dir "$log_dir" "Colima runtime logs"
for log_path in "$log_dir/stdout.log" "$log_dir/stderr.log"; do
    [[ -e "$log_path" || -L "$log_path" ]] || (set -C; : > "$log_path")
    openclaw_assert_private_file "$log_path" "Colima runtime log"
done
if [[ ! -e "$plist_path" ]]; then
    candidate="$(mktemp "$openclaw_launch_agent_dir/.$label.XXXXXX")"
    trap 'rm -f -- "$candidate"' EXIT
    plutil -convert xml1 -o "$candidate" -- - <<< "$expected_plist"
    chmod 600 "$candidate"
    ln "$candidate" "$plist_path" || openclaw_fail "Colima plist appeared during installation"
fi
launchctl enable "$service_target"
launchctl bootstrap "gui/$(id -u)" "$plist_path"
echo "OK Colima LaunchAgent loaded. Verify colima status and docker info before model inference."
