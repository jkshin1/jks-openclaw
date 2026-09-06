#!/usr/bin/env bash
set -euo pipefail
assets="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-openclaw-health-publisher.XXXXXX")"
fixture="$(cd "$fixture" && pwd -P)"
trap 'rm -rf -- "$fixture"' EXIT
chmod 700 "$fixture"
export PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture"
export PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture/state"
source "$assets/_common.sh"
source "$assets/watchdog.sh"
fail() { echo "FAIL $*" >&2; exit 1; }
target="$fixture/state/operations/remote-health.json"
openclaw_publish_remote_health
[[ "$(stat -f '%Lp' "$target")" == 600 && "$(stat -f '%z' "$target")" -lt 1024 ]] || fail "snapshot privacy/size"
[[ "$(wc -l < "$target" | tr -d ' ')" == 1 ]] || fail "snapshot must have one canonical line"
jq -e --argjson now "$(( $(date -u +%s) * 1000 ))" '
    keys == (["schemaVersion","observedAtEpochMillis","gatewayHealthy","dockerHealthy","policyValid","secretsClean"] | sort) and
    .schemaVersion == 1 and (.observedAtEpochMillis | type == "number" and . == floor) and
    .observedAtEpochMillis <= $now and .observedAtEpochMillis >= ($now - 5000) and
    .gatewayHealthy and .dockerHealthy and .policyValid and .secretsClean
' "$target" >/dev/null || fail "snapshot schema/time"
[[ "$(cat "$target")" == "$(jq -c . "$target")" ]] || fail "snapshot is not canonical JSON"
cp "$target" "$fixture/before.json"
chmod 644 "$target"
if (openclaw_publish_remote_health) >/dev/null 2>&1; then fail "nonprivate existing snapshot accepted"; fi
cmp -s "$fixture/before.json" "$target" || fail "unsafe snapshot was overwritten"
rm "$target"
ln -s "$fixture/before.json" "$target"
if (openclaw_publish_remote_health) >/dev/null 2>&1; then fail "snapshot symlink accepted"; fi
[[ -L "$target" ]] || fail "symlink target was altered"
echo "PASS remote health publisher (6 privacy/schema/refusal checks, no model calls)"
