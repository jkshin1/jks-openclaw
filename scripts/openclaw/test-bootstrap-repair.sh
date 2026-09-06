#!/usr/bin/env bash
set -euo pipefail
assets="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/personal-edge-openclaw-bootstrap.XXXXXX")"
fixture="$(cd "$fixture" && pwd -P)"
trap 'rm -rf -- "$fixture"' EXIT
chmod 700 "$fixture"
export PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT="$fixture"
export PERSONAL_EDGE_OPENCLAW_STATE_DIR="$fixture/state"
export PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR="$fixture/state/workspace"
source "$assets/_common.sh"
source "$assets/repair-openrouter-token-field.sh"
mkdir -p "$openclaw_workspace_dir" "$fixture/saved" "$fixture/quarantine"
chmod 700 "$fixture/saved" "$fixture/quarantine" "$openclaw_workspace_dir"
cp "$assets/templates/AGENTS.md" "$openclaw_workspace_dir/AGENTS.md"
chmod 600 "$openclaw_workspace_dir/AGENTS.md"
fail() { echo "FAIL $*" >&2; exit 1; }
checks=0
repair_assert_workspace "$openclaw_workspace_dir" "$assets/templates/AGENTS.md" || fail "empty restricted workspace rejected"
checks=$((checks + 1))
for name in SOUL.md USER.md IDENTITY.md; do
    printf 'original bytes %s\n' "$name" > "$openclaw_workspace_dir/$name"
    chmod 600 "$openclaw_workspace_dir/$name"
    cp "$openclaw_workspace_dir/$name" "$fixture/saved/$name"
done
repair_assert_workspace "$openclaw_workspace_dir" "$assets/templates/AGENTS.md" || fail "exact generated names rejected"
repair_quarantine_workspace "$openclaw_workspace_dir" "$fixture/saved" "$fixture/quarantine"
for name in SOUL.md USER.md IDENTITY.md; do
    [[ ! -e "$openclaw_workspace_dir/$name" ]] || fail "generated entry remains active"
    cmp -s "$fixture/saved/$name" "$fixture/quarantine/$name" || fail "quarantine changed bytes"
done
checks=$((checks + 1))
repair_restore_workspace "$openclaw_workspace_dir" "$fixture/saved"
for name in SOUL.md USER.md IDENTITY.md; do
    cmp -s "$fixture/saved/$name" "$openclaw_workspace_dir/$name" || fail "rollback changed bytes"
    [[ -f "$fixture/quarantine/$name" ]] || fail "rollback destroyed quarantine"
done
checks=$((checks + 1))
printf 'owner change\n' > "$openclaw_workspace_dir/USER.md"
if repair_restore_workspace "$openclaw_workspace_dir" "$fixture/saved"; then fail "rollback overwrote a new owner edit"; fi
[[ "$(cat "$openclaw_workspace_dir/USER.md")" == 'owner change' ]] || fail "owner edit lost"
checks=$((checks + 1))
printf 'owner notes\n' > "$openclaw_workspace_dir/notes.txt"
if repair_assert_workspace "$openclaw_workspace_dir" "$assets/templates/AGENTS.md"; then fail "unknown owner file accepted for cleanup"; fi
checks=$((checks + 1))
rm "$openclaw_workspace_dir/notes.txt" "$openclaw_workspace_dir/USER.md"
ln -s "$fixture/saved/USER.md" "$openclaw_workspace_dir/USER.md"
if (repair_assert_workspace "$openclaw_workspace_dir" "$assets/templates/AGENTS.md") >/dev/null 2>&1; then fail "workspace symlink accepted"; fi
checks=$((checks + 1))

jq --arg workspace "$openclaw_workspace_dir" '.agents.defaults.workspace=$workspace |
    del(.agents.defaults.skipBootstrap,.agents.defaults.contextInjection)' \
    "$assets/templates/openclaw.json" > "$fixture/original-config.json"
chmod 600 "$fixture/original-config.json"
repair_prepare_config "$fixture/original-config.json" "$fixture/candidate.json" || fail "bootstrap repair rejected"
jq -e '.agents.defaults.skipBootstrap == true and .agents.defaults.contextInjection == "never"' \
    "$fixture/candidate.json" >/dev/null || fail "bootstrap boundary omitted"
checks=$((checks + 1))
for filter in 'del(.agents.defaults.skipBootstrap)' '.agents.defaults.contextInjection="always"'; do
    jq "$filter" "$fixture/candidate.json" > "$fixture/unsafe.json"
    if (openclaw_assert_restrictive_config "$fixture/unsafe.json") >/dev/null 2>&1; then fail "unsafe bootstrap defaults accepted"; fi
    checks=$((checks + 1))
done
printf 'before\n' > "$fixture/destination"
if repair_atomic_replace "$fixture/missing" "$fixture/destination" 600 >/dev/null 2>&1; then fail "missing replacement source succeeded"; fi
[[ "$(cat "$fixture/destination")" == before ]] || fail "failed replacement truncated original"
checks=$((checks + 1))
repair_atomic_replace "$fixture/original-config.json" "$fixture/destination" 600
cmp -s "$fixture/original-config.json" "$fixture/destination" || fail "atomic replacement changed bytes"
[[ "$(stat -f '%Lp' "$fixture/destination")" == 600 ]] || fail "atomic replacement changed privacy"
checks=$((checks + 1))
echo "PASS bootstrap repair preservation regressions ($checks checks, no model calls)"
