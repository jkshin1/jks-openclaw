#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=_common.sh
source "$script_dir/_common.sh"
source "$script_dir/watchdog.sh"

[[ "$(uname -s)" == "Darwin" ]] || openclaw_fail "this readiness check is macOS-only"
for required_command in awk fdesetup pmset stat sw_vers uname jq launchctl mktemp shasum; do
    openclaw_require_command "$required_command"
done

failures=0
warnings=0

check_power_value() {
    local key="$1"
    local expected="$2"
    local severity="$3"
    local actual

    actual="$(awk -v wanted="$key" '$1 == wanted { print $2; exit }' <<< "$ac_power_settings")"
    if [[ "$actual" == "$expected" ]]; then
        echo "OK   AC $key=$actual"
    elif [[ "$severity" == "fail" ]]; then
        echo "MISS AC $key expected $expected, found ${actual:-unavailable}"
        failures=$((failures + 1))
    else
        echo "WARN AC $key expected $expected, found ${actual:-unavailable}"
        warnings=$((warnings + 1))
    fi
}

echo "OpenClaw 24-hour host readiness"
echo "macOS: $(sw_vers -productVersion)"
echo "Arch:  $(uname -m)"

power_output="$(pmset -g custom)"
ac_power_settings="$(awk '
    /^AC Power:/ { in_ac = 1; next }
    /^[^[:space:]].*:$/ && in_ac { exit }
    in_ac { print }
' <<< "$power_output")"
[[ -n "$ac_power_settings" ]] || openclaw_fail "could not read AC power settings"

check_power_value sleep 0 fail
check_power_value standby 0 fail
check_power_value autorestart 1 fail
check_power_value powernap 0 warn
check_power_value lowpowermode 0 warn
check_power_value womp 1 warn
check_power_value tcpkeepalive 1 warn

console_user="$(stat -f '%Su' /dev/console 2>/dev/null || true)"
current_user="$(id -un)"
if [[ "$console_user" == "$current_user" ]]; then
    echo "OK   current user owns the active GUI login session"
else
    echo "MISS Gateway LaunchAgent will not run until $current_user logs in"
    failures=$((failures + 1))
fi

filevault_status="$(fdesetup status 2>/dev/null || true)"
if [[ "$filevault_status" == *"On."* ]]; then
    echo "WARN FileVault requires an owner unlock after a cold boot or power loss"
    warnings=$((warnings + 1))
elif [[ -n "$filevault_status" ]]; then
    echo "INFO $filevault_status"
fi

runtime_temp="$(mktemp -d "$openclaw_tmp_root/personal-edge-runtime-readiness.XXXXXX")"
chmod 700 "$runtime_temp"
trap 'rm -rf -- "$runtime_temp"' EXIT
runtime_snapshot="$(openclaw_colima_snapshot "$runtime_temp")"
if jq -e '.healthy' <<< "$runtime_snapshot" >/dev/null &&
    launchctl print "gui/$(id -u)/com.personaledge.colima-runtime" > "$runtime_temp/colima.launchctl" 2>&1 &&
    awk '$1 == "state" && $2 == "=" && $3 == "running" { state=1 }
        $1 == "pid" && $2 == "=" && $3 ~ /^[1-9][0-9]*$/ { pid=1 }
        END { exit !(state && pid) }' "$runtime_temp/colima.launchctl"; then
    echo "OK   reviewed Colima VM, Docker daemon, default context, and restart identities"
else
    echo "MISS Colima/Docker runtime readiness"
    jq . <<< "$runtime_snapshot"
    failures=$((failures + 1))
fi

if (openclaw_check_managed_tailscale_serve "$runtime_temp"); then
    echo "OK   configured Tailscale ingress policy and Gateway process binding"
else
    echo "MISS managed Tailscale ingress readiness"
    failures=$((failures + 1))
fi

if (( failures > 0 )); then
    echo "Host readiness failed with $failures blocking setting(s) and $warnings warning(s)."
    exit 1
fi
echo "Host runtime settings pass with $warnings warning(s)."
echo "NOTE A per-user LaunchAgent still starts only after login; use a UPS and an owner reboot runbook."
