#!/usr/bin/env bash
set -euo pipefail
assets="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-managed-ingress.XXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
chmod 700 "$fixture"
export PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture"
source "$assets/_common.sh"
fail() { echo "FAIL $*" >&2; exit 1; }
launchctl() { printf 'state = running\npid = 41001\n'; }
ps() { id -u; }
lsof() { printf '%s\n' p41001 n127.0.0.1:18789 n127.0.0.1:49569 'n[::1]:18789'; }
receipt="$fixture/serve.json"
printf '%s\n' '{"Foreground":{"fixture":{"TCP":{"443":{"HTTPS":true}},"Web":{"fixture.tailnet.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:49569"}}}}}}}' > "$receipt"
openclaw_assert_managed_tailscale_serve_receipt "$receipt"
tests=1
for mutation in \
    '.AllowFunnel={"fixture.tailnet.ts.net:443":true}' \
    '.Foreground.fixture.AllowFunnel={"fixture.tailnet.ts.net:443":true}' \
    '.Services={"svc:owner":{}}' \
    '.Foreground.extra=.Foreground.fixture' \
    '.Web=.Foreground.fixture.Web | .TCP=.Foreground.fixture.TCP' \
    '.Foreground.fixture.Web["fixture.tailnet.ts.net:443"].Handlers["/"].Proxy="http://127.0.0.1:18789"' \
    '.Foreground.fixture.Web["fixture.tailnet.ts.net:443"].Handlers["/"].Proxy="http://127.0.0.1:49569/private"' \
    '.Foreground.fixture.Web["fixture.tailnet.ts.net:443"].Handlers["/"].Proxy="http://192.168.1.2:49569"' \
    '.Foreground.fixture.TCP["443"].TCPForward="127.0.0.1:49569"'; do
    jq "$mutation" "$receipt" > "$fixture/invalid.json"
    if (openclaw_assert_managed_tailscale_serve_receipt "$fixture/invalid.json") > "$fixture/refusal.log" 2>&1; then
        fail "unsafe Serve receipt accepted: $mutation"
    fi
    tests=$((tests + 1))
done
for listeners in \
    $'p41002\nn127.0.0.1:18789\nn127.0.0.1:49569' \
    $'p41001\nn127.0.0.1:18789' \
    $'p41001\nn127.0.0.1:18789\nn*:49569' \
    $'p41001\nn127.0.0.1:18789\nn127.0.0.1:49569\nn127.0.0.1:9999'; do
    lsof() { printf '%s\n' "$listeners"; }
    if (openclaw_assert_managed_tailscale_serve_receipt "$receipt") > "$fixture/refusal.log" 2>&1; then
        fail "wrong process or listeners accepted"
    fi
    tests=$((tests + 1))
done
lsof() { printf '%s\n' p41001 n127.0.0.1:18789 n127.0.0.1:49569; }
ps() { printf '%s\n' 99999; }
if (openclaw_assert_managed_tailscale_serve_receipt "$receipt") > "$fixture/refusal.log" 2>&1; then
    fail "wrong process owner accepted"
fi
tests=$((tests + 1))
echo "PASS managed Tailscale ingress ($tests route/process/owner checks, no network calls)"
