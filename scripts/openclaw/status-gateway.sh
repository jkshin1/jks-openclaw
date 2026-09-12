#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
if [[ "${1:-}" == "--telegram" ]]; then
    shift
    exec python3 "$script_dir/telegram-ops-status.py" "$@"
fi
# shellcheck source=_common.sh
source "$script_dir/_common.sh"

openclaw_load_deployment
echo "OpenClaw: $PERSONAL_EDGE_OPENCLAW_VERSION"
echo "Node:     $("$openclaw_node_path" --version)"
echo "Model:    $PERSONAL_EDGE_OPENCLAW_MODEL"
echo "Bind:     $(jq -r '.gateway.bind' "$openclaw_config_path"):$PERSONAL_EDGE_OPENCLAW_PORT"
echo "Ingress:  $(jq -r '.gateway.tailscale.mode' "$openclaw_config_path")"
echo "State:    $openclaw_state_dir"

openclaw_run gateway status --require-rpc
openclaw_run gateway health --port "$PERSONAL_EDGE_OPENCLAW_PORT"

for log_path in \
    "$openclaw_state_dir/logs/gateway.log" \
    "$openclaw_state_dir/logs/gateway.err.log" \
    "$openclaw_state_dir/logs/personal-edge-watchdog.log" \
    "$openclaw_state_dir/logs/personal-edge-watchdog.err.log"; do
    if [[ -f "$log_path" && ! -L "$log_path" ]]; then
        echo "LOG  $(basename "$log_path"): $(stat -f '%z bytes, modified %Sm' "$log_path")"
    fi
done
