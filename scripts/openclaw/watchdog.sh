#!/usr/bin/env bash
set -euo pipefail

# These observation helpers are also sourced by readiness and soak. Keeping them in the
# manifest-hashed watchdog preserves the managed deployment's existing integrity boundary.
openclaw_colima_command_path="/opt/homebrew/bin:/opt/homebrew/sbin:/usr/bin:/bin:/usr/sbin:/sbin"
openclaw_runtime_bounded() {
    local seconds="$1"
    shift
    /usr/bin/perl -MPOSIX=setsid -e '
        my $seconds = shift @ARGV;
        my $pid = fork(); defined $pid or die "fork";
        if ($pid == 0) { setsid() >= 0 or die "setsid"; exec @ARGV; exit 127; }
        $SIG{ALRM} = sub { kill "TERM", -$pid; select undef, undef, undef, 0.2;
            kill "KILL", -$pid; waitpid($pid, 0); exit 124; };
        alarm $seconds; waitpid($pid, 0); alarm 0;
        exit(($? & 127) ? 128 + ($? & 127) : $? >> 8);
    ' "$seconds" "$@"
}

openclaw_colima_expected_plist() {
    jq -cn --arg bin "${PERSONAL_EDGE_COLIMA_BIN:-/opt/homebrew/bin/colima}" \
        --arg owner "$openclaw_user_root" --arg logs "$openclaw_state_dir/operations/colima-runtime" '{
        Label:"com.personaledge.colima-runtime",
        ProgramArguments:[$bin,"start","--profile","personaledge","--foreground",
            "--save-config=false","--ssh-config=false"],
        RunAtLoad:true,KeepAlive:true,ThrottleInterval:30,ExitTimeOut:120,Umask:63,
        WorkingDirectory:$owner,
        EnvironmentVariables:{HOME:$owner,COLIMA_HOME:($owner+"/.colima"),
            PATH:"/opt/homebrew/bin:/opt/homebrew/sbin:/usr/bin:/bin:/usr/sbin:/sbin"},
        StandardOutPath:($logs+"/stdout.log"),StandardErrorPath:($logs+"/stderr.log")
    }'
}

openclaw_assert_colima_profile() {
    local profile_config="$openclaw_user_root/.colima/personaledge/colima.yaml"
    openclaw_assert_safe_path "$profile_config" "Colima profile"
    openclaw_assert_owned_nonwritable_file "$profile_config" "Colima profile"
    /usr/bin/ruby -r yaml -e '
        c = YAML.safe_load(File.read(ARGV[0]))
        expected = {"cpu"=>2,"memory"=>2,"disk"=>10,"rootDisk"=>10,
            "runtime"=>"docker","vmType"=>"vz","mountType"=>"virtiofs","sshConfig"=>false}
        abort "FAIL unreviewed Colima profile" unless c.is_a?(Hash) &&
            expected.all? { |k,v| c[k] == v } &&
            c["mounts"] == [{"location"=>ARGV[1],"writable"=>true}]
    ' "$profile_config" "$openclaw_state_dir/sandboxes"
}

openclaw_assert_colima_definition() {
    local colima_bin="${PERSONAL_EDGE_COLIMA_BIN:-/opt/homebrew/bin/colima}"
    local plist="$openclaw_launch_agent_dir/com.personaledge.colima-runtime.plist"
    [[ -x "$colima_bin" ]] || openclaw_fail "Colima is missing"
    [[ "$(openclaw_runtime_bounded 10 env PATH="$openclaw_colima_command_path" "$colima_bin" version | head -1)" == \
        "colima version 0.10.3" ]] || openclaw_fail "Colima version is unreviewed"
    openclaw_assert_colima_profile || openclaw_fail "Colima profile drifted"
    openclaw_assert_safe_path "$plist" "Colima LaunchAgent"
    openclaw_assert_private_file "$plist" "Colima LaunchAgent"
    plutil -convert json -o - "$plist" | \
        jq -e --argjson expected "$(openclaw_colima_expected_plist)" '. == $expected' >/dev/null ||
        openclaw_fail "Colima LaunchAgent drifted"
}

openclaw_colima_snapshot() {
    local sample_temp="$1"
    local colima_bin="${PERSONAL_EDGE_COLIMA_BIN:-/opt/homebrew/bin/colima}"
    local docker_bin="${PERSONAL_EDGE_DOCKER_BIN:-/opt/homebrew/bin/docker}"
    local socket="unix://$openclaw_user_root/.colima/personaledge/docker.sock"
    local definition=false vm=false docker=false context=false vm_generation=null daemon_generation=null
    local identity boot_id daemon_pid daemon_started
    if (openclaw_assert_colima_definition) > "$sample_temp/colima-definition.out" \
        2> "$sample_temp/colima-definition.err"; then
        definition=true
    fi
    # Do not contact an unreviewed profile, alternate Docker endpoint, or ambient Docker context.
    if [[ "$definition" == true ]]; then
        if openclaw_runtime_bounded 10 env PATH="$openclaw_colima_command_path" COLIMA_HOME="$openclaw_user_root/.colima" \
            "$colima_bin" status --profile personaledge --json \
            > "$sample_temp/colima-status.json" 2> "$sample_temp/colima-status.err" &&
            jq -e --arg socket "$socket" '
                .runtime == "docker" and .driver == "macOS Virtualization.Framework" and
                .mount_type == "virtiofs" and .docker_socket == $socket and
                .cpu == 2 and .memory == 2147483648 and .disk == 10737418240
            ' "$sample_temp/colima-status.json" >/dev/null 2>&1; then
            vm=true
        fi
        if [[ -x "$docker_bin" ]] && openclaw_runtime_bounded 10 \
            env -u DOCKER_CONTEXT -u DOCKER_HOST -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
                DOCKER_CONFIG="$openclaw_user_root/.docker" "$docker_bin" context inspect \
            > "$sample_temp/docker-context.json" 2> "$sample_temp/docker-context.err" &&
            jq -e --arg socket "$socket" 'length == 1 and .[0].Endpoints.docker.Host == $socket' \
                "$sample_temp/docker-context.json" >/dev/null 2>&1; then
            context=true
        fi
        if [[ "$vm" == true && -x "$docker_bin" ]] && openclaw_runtime_bounded 10 \
            env -u DOCKER_CONTEXT -u DOCKER_HOST -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
                DOCKER_CONFIG="$openclaw_user_root/.docker" "$docker_bin" --host "$socket" \
                info --format '{{json .ServerVersion}}' \
            > "$sample_temp/docker-info.json" 2> "$sample_temp/docker-info.err" &&
            jq -e 'type == "string" and test("^[0-9]+[.][0-9]+[.][0-9]+$")' \
                "$sample_temp/docker-info.json" >/dev/null 2>&1; then
            docker=true
        fi
        if [[ "$vm" == true && "$docker" == true ]] && openclaw_runtime_bounded 10 \
            env PATH="$openclaw_colima_command_path" COLIMA_HOME="$openclaw_user_root/.colima" "$colima_bin" ssh --profile personaledge -- \
                sh -c 'cat /proc/sys/kernel/random/boot_id; systemctl show docker --property=MainPID --property=ExecMainStartTimestampMonotonic' \
            > "$sample_temp/docker-generation.txt" 2> "$sample_temp/docker-generation.err"; then
            identity="$(cat "$sample_temp/docker-generation.txt")"
            boot_id="$(head -1 <<< "$identity")"
            daemon_pid="$(awk -F= '$1 == "MainPID" { print $2 }' <<< "$identity")"
            daemon_started="$(awk -F= '$1 == "ExecMainStartTimestampMonotonic" { print $2 }' <<< "$identity")"
            if [[ "$boot_id" =~ ^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$ &&
                "$daemon_pid" =~ ^[1-9][0-9]*$ && "$daemon_started" =~ ^[1-9][0-9]*$ &&
                "$(wc -l <<< "$identity" | tr -d ' ')" == 3 ]]; then
                vm_generation="\"$(printf '%s' "$boot_id" | shasum -a 256 | awk '{ print $1 }')\""
                daemon_generation="\"$(printf '%s:%s:%s' "$boot_id" "$daemon_pid" "$daemon_started" | \
                    shasum -a 256 | awk '{ print $1 }')\""
            fi
        fi
    fi
    jq -cn --argjson definitionValid "$definition" --argjson vmRunning "$vm" \
        --argjson dockerResponsive "$docker" --argjson defaultContextMatches "$context" \
        --argjson vmGeneration "$vm_generation" --argjson daemonGeneration "$daemon_generation" '{
        definitionValid:$definitionValid,vmRunning:$vmRunning,dockerResponsive:$dockerResponsive,
        defaultContextMatches:$defaultContextMatches,vmGeneration:$vmGeneration,
        daemonGeneration:$daemonGeneration,
        healthy:($definitionValid and $vmRunning and $dockerResponsive and $defaultContextMatches and
            $vmGeneration != null and $daemonGeneration != null)
    }'
}

openclaw_colima_rearm_context_safe() {
    local sample_temp="$1" default_socket="$2"
    local docker_bin="${PERSONAL_EDGE_DOCKER_BIN:-/opt/homebrew/bin/docker}"
    local expected_socket="unix://$openclaw_user_root/.colima/personaledge/docker.sock"
    openclaw_runtime_bounded 10 env -u DOCKER_CONTEXT -u DOCKER_HOST \
        -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH DOCKER_CONFIG="$openclaw_user_root/.docker" \
        "$docker_bin" context inspect > "$sample_temp/docker-rearm-context.json" \
        2> "$sample_temp/docker-rearm-context.err" || return 1
    if jq -e --arg socket "$expected_socket" \
        'length == 1 and .[0].Endpoints.docker.Host == $socket' \
        "$sample_temp/docker-rearm-context.json" >/dev/null; then
        return 0
    fi
    # A normal Colima stop restores the builtin default context. Only its exact empty local
    # endpoint is recoverable: an existing socket (even a stale link) belongs to another runtime
    # until proven otherwise, and a different context is an owner choice we must preserve.
    jq -e 'length == 1 and .[0].Name == "default" and
        .[0].Endpoints.docker.Host == "unix:///var/run/docker.sock"' \
        "$sample_temp/docker-rearm-context.json" >/dev/null || return 1
    [[ ! -e "$default_socket" && ! -L "$default_socket" && ! -S "$default_socket" ]]
}

openclaw_publish_remote_health() {
    local operations="$openclaw_state_dir/operations" target temporary epoch_millis
    target="$operations/remote-health.json"
    openclaw_assert_safe_path "$target" "remote health snapshot"
    openclaw_prepare_private_dir "$operations" "operations directory"
    if [[ -e "$target" || -L "$target" ]]; then
        openclaw_assert_private_file "$target" "remote health snapshot"
    fi
    epoch_millis=$(( $(date -u +%s) * 1000 ))
    temporary="$(mktemp "$operations/.remote-health.partial.XXXXXX")" || return 1
    if jq -cn --argjson observed "$epoch_millis" '
        {schemaVersion:1,observedAtEpochMillis:$observed,gatewayHealthy:true,dockerHealthy:true,
         policyValid:true,secretsClean:true}
    ' > "$temporary" && chmod 600 "$temporary" && mv -f "$temporary" "$target"; then
        return 0
    fi
    rm -f -- "$temporary"
    return 1
}

# Re-arm only a stopped reviewed VM whose foreground job is absent or no longer running.
# Never kill a running VM/daemon or restart a paid request whose outcome is unknown.
openclaw_colima_rearm_stopped() {
    local sample_temp="$1"
    local colima_bin="${PERSONAL_EDGE_COLIMA_BIN:-/opt/homebrew/bin/colima}"
    local target="gui/$(id -u)/com.personaledge.colima-runtime"
    local plist="$openclaw_launch_agent_dir/com.personaledge.colima-runtime.plist"
    local loaded=false
    (openclaw_assert_colima_definition) || return 1
    openclaw_runtime_bounded 10 env PATH="$openclaw_colima_command_path" COLIMA_HOME="$openclaw_user_root/.colima" \
        "$colima_bin" list --json > "$sample_temp/colima-list.json" 2> "$sample_temp/colima-list.err" || return 1
    jq -se '[.[] | select(.name == "personaledge")] | length == 1 and .[0].status == "Stopped"' \
        "$sample_temp/colima-list.json" >/dev/null || return 1
    openclaw_colima_rearm_context_safe "$sample_temp" /var/run/docker.sock || return 1
    if launchctl print "$target" > "$sample_temp/colima-rearm.launchctl" 2>&1; then
        loaded=true
        # Only explicit stopped states permit re-arm; missing/unparseable state is uncertainty.
        awk '$1 == "state" && $2 == "=" && ($3 == "exited" || $3 == "waiting" ||
            ($3 == "not" && $4 == "running")) { found=1 } END { exit !found }' \
            "$sample_temp/colima-rearm.launchctl" || return 1
    fi
    [[ "$loaded" == true ]] || launchctl bootstrap "gui/$(id -u)" "$plist" || return 1
    launchctl enable "$target" || return 1
    launchctl kickstart "$target"
}

# Source mode exposes observations only. No manifest loading, audit, or recovery is dispatched.
if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
    return 0
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

# During a fresh install or adoption, launchd honors RunAtLoad before the final manifest can be
# published. The exact private candidate is therefore the watchdog's bootstrap authority only
# while the final marker is absent. Once publication occurs, every later process selects the final
# manifest. openclaw_load_deployment reads the selected JSON once so an in-flight atomic rename
# cannot invalidate its remaining integrity checks.
watchdog_management_root="${PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT:-$(cd "$script_dir/.." && pwd -P)}"
watchdog_final_manifest="$watchdog_management_root/deployment.json"
watchdog_candidate_manifest="$watchdog_management_root/.deployment.candidate.json"
if [[ ! -e "$watchdog_final_manifest" && ! -L "$watchdog_final_manifest" &&
      -f "$watchdog_candidate_manifest" && ! -L "$watchdog_candidate_manifest" ]]; then
    export PERSONAL_EDGE_OPENCLAW_DEPLOYMENT_MANIFEST="$watchdog_candidate_manifest"
fi
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

openclaw_load_deployment
watchdog_using_candidate=0
if [[ "$openclaw_deployment_manifest" == "$watchdog_candidate_manifest" ]]; then
    watchdog_using_candidate=1
fi
service_target="gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL"
[[ -f "$openclaw_gateway_plist" && ! -L "$openclaw_gateway_plist" ]] ||
    openclaw_fail "Gateway LaunchAgent is missing or unsafe"
[[ ! -e "$openclaw_state_dir/.env" && ! -L "$openclaw_state_dir/.env" ]] ||
    openclaw_fail "legacy plaintext profile .env blocks watchdog operation"
openclaw_assert_owned_nonwritable_file "$openclaw_watchdog_plist" "watchdog LaunchAgent"
plutil -lint "$openclaw_watchdog_plist" >/dev/null
watchdog_plist_json="$(plutil -convert json -o - "$openclaw_watchdog_plist")"
openclaw_assert_watchdog_plist "$watchdog_plist_json"
openclaw_assert_restrictive_config "$openclaw_config_path"
openclaw_run config validate >/dev/null
watchdog_temp="$(mktemp -d "$openclaw_tmp_root/personal-edge-openclaw-watchdog.XXXXXX")"
cleanup_watchdog_temp() {
    case "$watchdog_temp" in
        /tmp/personal-edge-openclaw-watchdog.*|/private/tmp/personal-edge-openclaw-watchdog.*|*/T/personal-edge-openclaw-watchdog.*)
            rm -rf -- "$watchdog_temp"
            ;;
    esac
}
trap cleanup_watchdog_temp EXIT INT TERM
chmod 700 "$watchdog_temp"
openclaw_run secrets audit --check --json > "$watchdog_temp/secrets.json" 2> "$watchdog_temp/secrets.err"
openclaw_assert_clean_secrets_receipt "$watchdog_temp/secrets.json"
openclaw_run plugins list --json > "$watchdog_temp/plugins.json" 2> "$watchdog_temp/plugins.err"
openclaw_assert_required_plugins_receipt "$watchdog_temp/plugins.json"
openclaw_run models status --check --json > "$watchdog_temp/models.json" 2> "$watchdog_temp/models.err"

plutil -lint "$openclaw_gateway_plist" >/dev/null
gateway_plist_json="$(plutil -convert json -o - "$openclaw_gateway_plist")"
openclaw_assert_gateway_plist "$gateway_plist_json"
install_method="$openclaw_loaded_install_method"
if [[ "$install_method" == "adopted-homebrew-node" ]]; then
    openclaw_assert_adopted_service_definition "$openclaw_node_path"
else
    openclaw_assert_fresh_service_definition "$openclaw_node_path"
fi

# Docker is required even for tool-free sandbox model runs. Observe it before declaring health.
colima_snapshot="$(openclaw_colima_snapshot "$watchdog_temp")"
if ! jq -e '.healthy' <<< "$colima_snapshot" >/dev/null; then
    if (( watchdog_using_candidate == 1 )); then
        openclaw_fail "Colima/Docker failed during candidate bootstrap; recovery is withheld until final manifest publication"
    fi
    if jq -e '.definitionValid and (.vmRunning | not)' \
        <<< "$colima_snapshot" >/dev/null && openclaw_colima_rearm_stopped "$watchdog_temp"; then
        attempt=1
        # The reviewed cold VM took about 20 seconds to provision Docker; allow a bounded
        # startup window instead of declaring failure after only six seconds.
        while (( attempt <= 15 )); do
            sleep 2
            colima_snapshot="$(openclaw_colima_snapshot "$watchdog_temp")"
            jq -e '.healthy' <<< "$colima_snapshot" >/dev/null && break
            attempt=$((attempt + 1))
        done
    fi
    jq -e '.healthy' <<< "$colima_snapshot" >/dev/null ||
        openclaw_fail "Colima/Docker readiness failed; running or uncertain runtimes require owner recovery"
fi
if ! launchctl print "gui/$(id -u)/com.personaledge.colima-runtime" > "$watchdog_temp/colima.launchctl" 2>&1 ||
    ! awk '$1 == "state" && $2 == "=" && $3 == "running" { found=1 } END { exit !found }' \
        "$watchdog_temp/colima.launchctl"; then
    openclaw_fail "Colima foreground LaunchAgent is not running; owner recovery is required"
fi

if openclaw_run gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT" >/dev/null 2>&1; then
    openclaw_run health --json > "$watchdog_temp/health.json" 2> "$watchdog_temp/health.err"
    openclaw_assert_live_plugins_receipt "$watchdog_temp/health.json"
    openclaw_check_managed_tailscale_serve "$watchdog_temp"
    if (( watchdog_using_candidate == 0 )); then
        openclaw_publish_remote_health
    fi
    echo "OK gateway and Docker healthy with restrictive config, clean secrets, and required live plugins"
    exit 0
fi

if (( watchdog_using_candidate == 1 )); then
    openclaw_fail "Gateway health failed during candidate bootstrap; recovery is withheld until final manifest publication"
fi
echo "WARN gateway health failed; re-arming its verified LaunchAgent" >&2
if ! launchctl print "$service_target" >/dev/null 2>&1; then
    launchctl bootstrap "gui/$(id -u)" "$openclaw_gateway_plist"
fi
launchctl enable "$service_target"
launchctl kickstart -k "$service_target"

attempt=1
while (( attempt <= 6 )); do
    if openclaw_run gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT" >/dev/null 2>&1; then
        openclaw_run health --json > "$watchdog_temp/health-recovered.json" \
            2> "$watchdog_temp/health-recovered.err"
        openclaw_assert_live_plugins_receipt "$watchdog_temp/health-recovered.json"
        openclaw_check_managed_tailscale_serve "$watchdog_temp"
        openclaw_publish_remote_health
        echo "OK gateway recovered after watchdog re-arm"
        exit 0
    fi
    sleep 2
    attempt=$((attempt + 1))
done

openclaw_fail "Gateway remained unhealthy after bounded recovery"
