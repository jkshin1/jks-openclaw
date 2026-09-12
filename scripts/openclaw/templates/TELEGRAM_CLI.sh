#!/usr/bin/env bash
set -euo pipefail
# Preserve the actual npm installation owner for official update/repair commands.
export PATH=/Users/jk/.local/openclaw-2026.8.1/bin:/opt/homebrew/opt/node/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin
export npm_config_prefix=/Users/jk/.local/openclaw-2026.8.1
export OPENCLAW_PROFILE=personaledge
export OPENCLAW_STATE_DIR=/Users/jk/.openclaw-personaledge
export OPENCLAW_CONFIG_PATH=/Users/jk/.openclaw-personaledge/openclaw.json
exec /opt/homebrew/opt/node/bin/node /Users/jk/.local/openclaw-2026.8.1/lib/node_modules/openclaw/openclaw.mjs --profile personaledge "$@"
