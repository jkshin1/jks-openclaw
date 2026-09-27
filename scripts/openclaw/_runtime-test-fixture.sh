#!/usr/bin/env bash
# Source-only fixture setup. Never accepts the actual owner root.
openclaw_runtime_test_fixture() {
    local root="$1" state="$2" agents="$3" assets="$4" canonical home
    # Compare resolved paths: "$HOME/personal-edge-openclaw-x/.." matches a text pattern but is HOME.
    [[ -d "$root" && ! -L "$root" ]] || return 1
    canonical="$(cd "$root" && pwd -P)" || return 1
    home="$(cd "$HOME" && pwd -P)" || return 1
    [[ "$canonical" != "$home" && "$home" != "$canonical"/* && "${canonical##*/}" == personal-edge-openclaw-* ]] ||
        return 1
    root="$canonical"
    mkdir -p "$root/runtime-stubs" "$root/.colima/personaledge" "$root/.docker" "$agents"
    jq -n --arg mount "$state/sandboxes" '{cpu:2,memory:2,disk:10,rootDisk:10,runtime:"docker",
        vmType:"vz",mountType:"virtiofs",sshConfig:false,mounts:[{location:$mount,writable:true}]}' \
        > "$root/.colima/personaledge/colima.yaml"
    cat > "$root/runtime-stubs/colima" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${FAKE_RUNTIME_REQUIRE_BREW_PATH:-0}" == 1 ]]; then
    case ":$PATH:" in *:/opt/homebrew/bin:*) ;; *) exit 90 ;; esac
fi
case "$1" in
    version) echo 'colima version 0.10.3' ;;
    list) printf '{"name":"personaledge","status":"%s"}\n' "${FAKE_RUNTIME_LIST_STATUS:-Stopped}" ;;
    status)
        [[ "${FAKE_RUNTIME_VM_STOPPED:-0}" != 1 ]] || exit 1
        jq -cn --arg socket "unix://$PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT/.colima/personaledge/docker.sock" \
            '{driver:"macOS Virtualization.Framework",runtime:"docker",mount_type:"virtiofs",
              docker_socket:$socket,cpu:2,memory:2147483648,disk:10737418240}' ;;
    ssh)
        printf '%s\n' "${FAKE_RUNTIME_BOOT_ID:-c3bf0804-8387-4e60-a3d2-58a83450d81d}" \
            'MainPID=1601' "ExecMainStartTimestampMonotonic=${FAKE_RUNTIME_DAEMON_START:-17434288}" ;;
    *) exit 88 ;;
esac
STUB
    cat > "$root/runtime-stubs/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == 'context inspect' ]]; then
    if [[ "${FAKE_RUNTIME_CONTEXT_PROFILE:-personaledge}" == default ]]; then
        printf '%s\n' '[{"Name":"default","Endpoints":{"docker":{"Host":"unix:///var/run/docker.sock"}}}]'
        exit 0
    fi
    jq -cn --arg socket "unix://$PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT/.colima/${FAKE_RUNTIME_CONTEXT_PROFILE:-personaledge}/docker.sock" \
        '[{Endpoints:{docker:{Host:$socket}}}]'
elif [[ "$1" == --host && "$2" == "unix://$PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT/.colima/personaledge/docker.sock" &&
    "$3" == info && "$4" == --format ]]; then
    [[ "${FAKE_RUNTIME_DOCKER_FAIL:-0}" != 1 ]] || exit 1
    echo '"29.5.2"'
else exit 89
fi
STUB
    chmod 700 "$root/runtime-stubs/colima" "$root/runtime-stubs/docker"
    chmod 600 "$root/.colima/personaledge/colima.yaml"
    export PERSONAL_EDGE_COLIMA_BIN="$root/runtime-stubs/colima"
    export PERSONAL_EDGE_DOCKER_BIN="$root/runtime-stubs/docker"
    (
        source "$assets/watchdog.sh"
        openclaw_user_root="$root"
        openclaw_state_dir="$state"
        openclaw_colima_expected_plist | plutil -convert xml1 -o \
            "$agents/com.personaledge.colima-runtime.plist" -- -
    )
    chmod 600 "$agents/com.personaledge.colima-runtime.plist"
}
