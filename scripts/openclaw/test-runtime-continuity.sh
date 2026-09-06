#!/usr/bin/env bash
set -euo pipefail

assets="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-openclaw-continuity.XXXXXX")"
fixture="$(cd "$fixture" && pwd -P)"
trap 'rm -rf -- "$fixture"' EXIT
chmod 700 "$fixture"
export PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture"
export PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture/state"
export PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR="$fixture/LaunchAgents"
source "$assets/_runtime-test-fixture.sh"
openclaw_runtime_test_fixture "$fixture" "$fixture/state" "$fixture/LaunchAgents" "$assets"
source "$assets/soak-acceptance.sh"
mkdir "$fixture/raw"
chmod 700 "$fixture/raw"

fail() { echo "FAIL $*" >&2; exit 1; }
checks=0
expect_snapshot() {
    local expected="$1"
    local snapshot
    snapshot="$(openclaw_colima_snapshot "$fixture/raw")"
    [[ "$(jq -r '.healthy' <<< "$snapshot")" == "$expected" ]] || fail "runtime readiness: $snapshot"
    checks=$((checks + 1))
}
expect_snapshot true
FAKE_RUNTIME_REQUIRE_BREW_PATH=1 PATH=/usr/bin:/bin:/usr/sbin:/sbin expect_snapshot true
FAKE_RUNTIME_DOCKER_FAIL=1 expect_snapshot false
FAKE_RUNTIME_VM_STOPPED=1 expect_snapshot false
FAKE_RUNTIME_CONTEXT_PROFILE=default expect_snapshot false
FAKE_RUNTIME_BOOT_ID=malformed expect_snapshot false
FAKE_RUNTIME_DAEMON_START=0 expect_snapshot false

cp "$fixture/.colima/personaledge/colima.yaml" "$fixture/profile-before"
jq '.mounts += [{location:"/",writable:true}]' "$fixture/profile-before" \
    > "$fixture/.colima/personaledge/colima.yaml"
expect_snapshot false
cp "$fixture/profile-before" "$fixture/.colima/personaledge/colima.yaml"
cp "$fixture/LaunchAgents/com.personaledge.colima-runtime.plist" "$fixture/plist-before"
plutil -replace KeepAlive -bool NO "$fixture/LaunchAgents/com.personaledge.colima-runtime.plist"
expect_snapshot false
cp "$fixture/plist-before" "$fixture/LaunchAgents/com.personaledge.colima-runtime.plist"
expect_snapshot true

previous="$(openclaw_colima_snapshot "$fixture/raw" | jq -c '. + {service:{loaded:true,pid:123,runs:1}}')"
current="$(FAKE_RUNTIME_DAEMON_START=17434555 openclaw_colima_snapshot "$fixture/raw" | \
    jq -c '. + {service:{loaded:true,pid:123,runs:1}}')"
delta="$(soak_runtime_delta "$current" "$previous")"
jq -e '.daemonChanged and (.vmChanged | not) and (.service.generationChanged | not)' \
    <<< "$delta" >/dev/null || fail "daemon restart was hidden by a stable Colima process"
checks=$((checks + 1))
current="$(jq -c '.service.pid=124 | .service.runs=2' <<< "$previous")"
delta="$(soak_runtime_delta "$current" "$previous")"
jq -e '.service.generationChanged and .service.restartDelta == 1' <<< "$delta" >/dev/null ||
    fail "Colima process restart was hidden by stable Docker identity"
checks=$((checks + 1))

summary='{"failedSampleCount":0,"pendingSampleCount":0,"continuityPass":true,"rebootCount":0,
"gatewayLaunchdRunIncrease":0,"gatewayExitObservedCount":0,"gatewayCrashObservedCount":0,
"watchdogCrashObservedCount":0,"phaseOrderValid":true,"targetReached":true,"runtimeGenerationChangeCount":0}'
[[ "$(soak_result_for_summary "$summary" 86400)" == observed-window-pass ]] || fail "healthy window rejected"
checks=$((checks + 1))
summary="$(jq -c '.runtimeGenerationChangeCount=1' <<< "$summary")"
[[ "$(soak_result_for_summary "$summary" 86400)" == failed ]] || fail "runtime restart accepted as continuity"
checks=$((checks + 1))

# Simulate a collector whose child would survive a naive timeout; the process-group boundary must
# terminate that child before it can write. This uses only disposable fixture files.
if openclaw_runtime_bounded 1 /bin/bash -c 'sleep 2; printf escaped > "$1"' _ "$fixture/escaped"; then
    fail "hung observation did not time out"
else
    [[ "$?" == 124 ]] || fail "timeout did not preserve its bounded diagnostic exit"
fi
sleep 2
[[ ! -e "$fixture/escaped" ]] || fail "timed-out observation left a live child"
checks=$((checks + 1))

# Colima's normal stop changes the current Docker context to builtin default. Recover that
# precise transition only when no other local runtime owns the default endpoint.
FAKE_RUNTIME_CONTEXT_PROFILE=default openclaw_colima_rearm_context_safe "$fixture/raw" "$fixture/default.sock" ||
    fail "normal Colima stop context transition was refused"
checks=$((checks + 1))
printf 'another runtime' > "$fixture/default.sock"
if FAKE_RUNTIME_CONTEXT_PROFILE=default openclaw_colima_rearm_context_safe "$fixture/raw" "$fixture/default.sock"; then
    fail "recovery would replace another default runtime"
fi
checks=$((checks + 1))
rm "$fixture/default.sock"
ln -s "$fixture/absent.sock" "$fixture/default.sock"
if FAKE_RUNTIME_CONTEXT_PROFILE=default openclaw_colima_rearm_context_safe "$fixture/raw" "$fixture/default.sock"; then
    fail "recovery accepted an ambiguous default socket symlink"
fi
checks=$((checks + 1))
if FAKE_RUNTIME_CONTEXT_PROFILE=other-owner openclaw_colima_rearm_context_safe "$fixture/raw" "$fixture/no-socket"; then
    fail "recovery would alter an unrelated owner context"
fi
checks=$((checks + 1))

# Recovery must distinguish an idle stopped VM from a running/uncertain process. The fake
# launchctl records mutations; an actual launchctl command is never reached by these cases.
launchctl() {
    if [[ "$1" == print ]]; then
        [[ "${FAKE_RUNTIME_JOB_STATE:-missing}" != missing ]] || return 1
        printf 'state = %s\n' "$FAKE_RUNTIME_JOB_STATE"
    else
        printf '%s\n' "$*" >> "$fixture/rearm.log"
        [[ "$1" != bootstrap || "${FAKE_RUNTIME_BOOTSTRAP_FAIL:-0}" != 1 ]]
    fi
}
for job_state in running unknown; do
    if FAKE_RUNTIME_JOB_STATE="$job_state" openclaw_colima_rearm_stopped "$fixture/raw"; then
        fail "recovery touched a $job_state foreground job"
    fi
    [[ ! -e "$fixture/rearm.log" ]] || fail "uncertain runtime produced recovery mutations"
    checks=$((checks + 1))
done
if FAKE_RUNTIME_LIST_STATUS=Running openclaw_colima_rearm_stopped "$fixture/raw"; then
    fail "recovery touched a running VM"
fi
[[ ! -e "$fixture/rearm.log" ]] || fail "running VM produced recovery mutations"
checks=$((checks + 1))
openclaw_colima_rearm_stopped "$fixture/raw" || fail "stopped VM could not re-arm an absent job"
[[ "$(wc -l < "$fixture/rearm.log" | tr -d ' ')" == 3 ]] || fail "absent job recovery count changed"
if rg -q -- '-k|stop|bootout' "$fixture/rearm.log"; then fail "recovery became disruptive"; fi
checks=$((checks + 1))
rm "$fixture/rearm.log"
if FAKE_RUNTIME_BOOTSTRAP_FAIL=1 openclaw_colima_rearm_stopped "$fixture/raw"; then
    fail "failed bootstrap reported recovery"
fi
[[ "$(wc -l < "$fixture/rearm.log" | tr -d ' ')" == 1 ]] || fail "recovery continued after failed bootstrap"
checks=$((checks + 1))
unset -f launchctl

# Library sourcing must never invoke any host/service action or create a receipt.
[[ ! -d "$fixture/backups" ]] || fail "source-only loading unexpectedly started a soak"
checks=$((checks + 1))
echo "PASS Docker/Colima continuity regressions ($checks checks, no model calls)"
