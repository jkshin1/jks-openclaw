#!/usr/bin/env bash

# Shared helpers. This file is sourced by the operator scripts and is not an entry point.

readonly PERSONAL_EDGE_OPENCLAW_VERSION="2026.8.1"
readonly PERSONAL_EDGE_OPENCLAW_PROFILE="personaledge"
readonly PERSONAL_EDGE_OPENCLAW_NODE_MAJOR="26"
readonly PERSONAL_EDGE_OPENCLAW_OFFICIAL_NODE_VERSION="26.5.0"
readonly PERSONAL_EDGE_OPENCLAW_MODEL="openrouter/z-ai/glm-5.3-flash"
readonly PERSONAL_EDGE_OPENCLAW_PORT="18789"
readonly PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL="ai.openclaw.personaledge"
readonly PERSONAL_EDGE_OPENCLAW_WATCHDOG_LABEL="com.personaledge.openclaw-watchdog"

openclaw_assets_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
openclaw_project_root="$(cd "$openclaw_assets_dir/../.." && pwd -P)"
openclaw_user_root="${PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT:-${HOME:?HOME is required}}"
openclaw_state_dir="${PERSONAL_EDGE_OPENCLAW_STATE_DIR:-$HOME/.openclaw-personaledge}"
openclaw_config_path="${PERSONAL_EDGE_OPENCLAW_CONFIG_PATH:-$openclaw_state_dir/openclaw.json}"
openclaw_workspace_dir="${PERSONAL_EDGE_OPENCLAW_WORKSPACE_DIR:-$openclaw_state_dir/workspace}"
openclaw_runtime_root="${PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT:-$HOME/.local/openclaw-$PERSONAL_EDGE_OPENCLAW_VERSION}"
openclaw_management_root="${PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT:-$openclaw_runtime_root/.personal-edge-management}"
openclaw_backup_root="${PERSONAL_EDGE_OPENCLAW_BACKUP_DIR:-$HOME/Library/Application Support/PersonalEdge/OpenClawBackups}"
openclaw_launch_agent_dir="${PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR:-$HOME/Library/LaunchAgents}"
openclaw_gateway_plist="$openclaw_launch_agent_dir/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL.plist"
openclaw_watchdog_plist="$openclaw_launch_agent_dir/$PERSONAL_EDGE_OPENCLAW_WATCHDOG_LABEL.plist"
openclaw_deployment_manifest="${PERSONAL_EDGE_OPENCLAW_DEPLOYMENT_MANIFEST:-$openclaw_management_root/deployment.json}"
openclaw_cli_path="$openclaw_management_root/bin/openclaw"
openclaw_management_common="$openclaw_management_root/libexec/_common.sh"
openclaw_management_watchdog="$openclaw_management_root/libexec/watchdog.sh"
openclaw_loaded_install_method=""
openclaw_package_json="$openclaw_runtime_root/lib/node_modules/openclaw/package.json"
openclaw_cli_entry="$openclaw_runtime_root/lib/node_modules/openclaw/openclaw.mjs"
openclaw_gateway_entry="$openclaw_runtime_root/lib/node_modules/openclaw/dist/index.js"
openclaw_tmp_root="${TMPDIR:-/tmp}"
openclaw_tmp_root="${openclaw_tmp_root%/}"

openclaw_fail() {
    echo "FAIL $*" >&2
    exit 1
}

openclaw_note() {
    echo "INFO $*"
}

# The active Mac/Telegram host agent is upgraded in place, so its installed runtime moves ahead of
# the version these scripts pin. That pin is deliberate: this tooling describes the frozen
# tool-free Android relay, which is a different deployment rather than a stale copy of the current
# one, so a newer installed runtime is an expected boundary and not a corrupted or tampered
# install. Say which deployment the caller reached instead of reporting bare drift.
openclaw_fail_archived_relay_version() {
    local subject="$1" observed="$2"
    {
        echo "FAIL $subject is $observed, not the pinned $PERSONAL_EDGE_OPENCLAW_VERSION"
        echo "     These scripts manage the archived tool-free Android relay only. They do not"
        echo "     manage the active Mac/Telegram host agent, whose runtime is upgraded in place."
        echo "     This is the expected archived-relay boundary, not a corrupted install."
        echo "     For the active deployment use the --telegram entry points, for example:"
        echo "       scripts/openclaw/status-gateway.sh --telegram"
        echo "       scripts/openclaw/verify-gateway.sh --telegram"
        echo "     See AGENTS.md and docs/OPENCLAW_TELEGRAM.md."
    } >&2
    exit 1
}

openclaw_require_command() {
    command -v "$1" >/dev/null 2>&1 || openclaw_fail "required command not found: $1"
}

openclaw_assert_safe_path() {
    local target="$1"
    local label="$2"
    local allowed_root ancestor ancestor_real

    [[ "$target" == /* ]] || openclaw_fail "$label must be absolute: $target"
    [[ "$target" != "/" && "$target" != "$openclaw_user_root" ]] ||
        openclaw_fail "unsafe $label: $target"
    [[ "$target" != *$'\n'* && "$target" != *$'\r'* ]] ||
        openclaw_fail "$label contains a line break"
    [[ "$target" != */../* && "$target" != */.. && "$target" != */./* ]] ||
        openclaw_fail "$label contains an unsafe path component: $target"
    [[ -d "$openclaw_user_root" && ! -L "$openclaw_user_root" ]] ||
        openclaw_fail "allowed root is not a real directory: $openclaw_user_root"
    allowed_root="$(cd "$openclaw_user_root" && pwd -P)"
    case "$target/" in
        "$allowed_root"/*) ;;
        *) openclaw_fail "$label escapes the allowed root: $target" ;;
    esac
    [[ ! -L "$target" ]] || openclaw_fail "$label must not be a symlink: $target"

    # Check the nearest existing ancestor before any caller runs mkdir. This closes the case where
    # a lexical child of HOME traverses a symlink to another volume or another user's directory.
    ancestor="$target"
    while [[ ! -e "$ancestor" && ! -L "$ancestor" ]]; do
        [[ "$ancestor" != "/" ]] || openclaw_fail "$label has no safe existing ancestor"
        ancestor="$(dirname "$ancestor")"
    done
    if [[ -d "$ancestor" ]]; then
        ancestor_real="$(cd "$ancestor" && pwd -P)"
    else
        ancestor_real="$(cd "$(dirname "$ancestor")" && pwd -P)/$(basename "$ancestor")"
    fi
    case "$ancestor_real/" in
        "$allowed_root"/*) ;;
        *) openclaw_fail "$label traverses outside the allowed root: $ancestor_real" ;;
    esac
}

openclaw_prepare_private_dir() {
    local target="$1"
    local label="$2"
    local allowed_root_real target_real target_owner target_mode

    openclaw_assert_safe_path "$target" "$label"
    mkdir -p "$target"
    [[ -d "$target" && ! -L "$target" ]] || openclaw_fail "$label is not a real directory"
    allowed_root_real="$(cd "$openclaw_user_root" && pwd -P)"
    target_real="$(cd "$target" && pwd -P)"
    case "$target_real/" in
        "$allowed_root_real"/*) ;;
        *) openclaw_fail "$label resolves outside the allowed root: $target_real" ;;
    esac
    target_owner="$(stat -f '%u' "$target")"
    [[ "$target_owner" == "$(id -u)" ]] || openclaw_fail "$label is not owned by the current user"
    chmod 700 "$target"
    target_mode="$(stat -f '%Lp' "$target")"
    (( (8#$target_mode & 8#077) == 0 )) || openclaw_fail "$label is accessible by group or other"
}

openclaw_prepare_owned_dir() {
    local target="$1"
    local label="$2"
    local owner mode

    openclaw_assert_safe_path "$target" "$label"
    mkdir -p "$target"
    [[ -d "$target" && ! -L "$target" ]] || openclaw_fail "$label is not a real directory"
    owner="$(stat -f '%u' "$target")"
    [[ "$owner" == "$(id -u)" ]] || openclaw_fail "$label is not owned by the current user"
    mode="$(stat -f '%Lp' "$target")"
    (( (8#$mode & 8#022) == 0 )) || openclaw_fail "$label is group/world writable"
}

openclaw_assert_private_file() {
    local target="$1"
    local label="$2"
    local owner mode

    [[ -f "$target" && ! -L "$target" ]] || openclaw_fail "$label is not a regular file: $target"
    owner="$(stat -f '%u' "$target")"
    [[ "$owner" == "$(id -u)" ]] || openclaw_fail "$label is not owned by the current user"
    mode="$(stat -f '%Lp' "$target")"
    (( (8#$mode & 8#077) == 0 )) || openclaw_fail "$label must not be readable by group or other"
}

openclaw_assert_private_secret_file() {
    local target="$1"
    local label="$2"
    local byte_count line_count

    openclaw_assert_private_file "$target" "$label"
    byte_count="$(stat -f '%z' "$target")"
    line_count="$(awk 'END { print NR }' "$target")"
    (( line_count == 1 )) || openclaw_fail "$label must contain exactly one line"
    (( byte_count >= 20 && byte_count <= 1025 )) || openclaw_fail "$label has an invalid size"
    awk 'length($0) >= 20 && length($0) <= 1024 && $0 ~ /^[A-Za-z0-9._~+\/=:-]+$/ { ok = 1 }
         END { exit(ok ? 0 : 1) }' "$target" || openclaw_fail "$label contains unsupported characters"
}

openclaw_assert_owned_nonwritable_file() {
    local target="$1"
    local label="$2"
    local owner mode

    [[ -f "$target" && ! -L "$target" ]] || openclaw_fail "$label is not a regular file: $target"
    owner="$(stat -f '%u' "$target")"
    [[ "$owner" == "$(id -u)" ]] || openclaw_fail "$label is not owned by the current user"
    mode="$(stat -f '%Lp' "$target")"
    (( (8#$mode & 8#022) == 0 )) || openclaw_fail "$label is group/world writable"
}

openclaw_assert_owned_nonwritable_dir() {
    local target="$1"
    local label="$2"
    local owner mode

    [[ -d "$target" && ! -L "$target" ]] || openclaw_fail "$label is not a real directory: $target"
    owner="$(stat -f '%u' "$target")"
    [[ "$owner" == "$(id -u)" ]] || openclaw_fail "$label is not owned by the current user"
    mode="$(stat -f '%Lp' "$target")"
    (( (8#$mode & 8#022) == 0 )) || openclaw_fail "$label is group/world writable"
}

openclaw_sha256() {
    shasum -a 256 "$1" | awk '{ print $1 }'
}

openclaw_runtime_tree_sha256() {
    [[ -x /usr/bin/perl && ! -L /usr/bin/perl ]] ||
        openclaw_fail "trusted system Perl is required for runtime integrity hashing"

    # A 2026.8.1 npm tree contains tens of thousands of files. Keep the legacy deterministic
    # F/L stream exactly, but hash it in one process instead of spawning one shasum per file.
    /usr/bin/env -i \
        LC_ALL=C \
        OPENCLAW_RUNTIME_HASH_ROOT="$openclaw_runtime_root" \
        OPENCLAW_RUNTIME_HASH_MANAGEMENT_ROOT="$openclaw_management_root" \
        /usr/bin/perl -MDigest::SHA -MFile::Find -MCwd=abs_path -MFcntl=:mode - <<'PERL'
use strict;
use warnings;
use bytes;
$SIG{__WARN__} = sub { die "runtime traversal failed\n" };

my $root = $ENV{OPENCLAW_RUNTIME_HASH_ROOT};
my $management = $ENV{OPENCLAW_RUNTIME_HASH_MANAGEMENT_ROOT};
defined($root) && defined($management) or die "runtime hash paths are missing\n";
$root !~ /[\r\n]/ && $management !~ /[\r\n]/ or
    die "runtime hash path contains a line break\n";
my @root_metadata = lstat($root);
@root_metadata && S_ISDIR($root_metadata[2]) or die "runtime root is not a real directory\n";

my (@files, @links);
File::Find::find(
    {
        no_chdir => 1,
        wanted => sub {
            my $path = $File::Find::name;
            if ($path eq $management) {
                $File::Find::prune = 1;
                return;
            }
            $path !~ /[\r\n]/ or die "runtime contains a filename with a line break\n";
            my @metadata = lstat($path);
            @metadata or die "could not inspect runtime path\n";
            push @files, $path if S_ISREG($metadata[2]);
            push @links, $path if S_ISLNK($metadata[2]);
        },
    },
    $root,
);

my $aggregate = Digest::SHA->new(256);
for my $path (sort { $a cmp $b } @files) {
    open my $handle, '<:raw', $path or die "could not read runtime file\n";
    my $file_hash = Digest::SHA->new(256);
    $file_hash->addfile($handle);
    close $handle or die "could not close runtime file\n";
    my $relative = index($path, "$root/") == 0 ? substr($path, length($root) + 1) : $path;
    $aggregate->add('F ' . $file_hash->hexdigest . ' ' . $relative . "\n");
}
for my $path (sort { $a cmp $b } @links) {
    my @target_metadata = stat($path);
    @target_metadata or die "runtime contains a broken symbolic link\n";
    my $raw_target = readlink($path);
    defined($raw_target) or die "runtime symbolic link could not be read\n";
    $raw_target !~ /[\r\n]/ or die "runtime link target contains a line break\n";
    my $target = abs_path($path);
    defined($target) or die "runtime contains a broken symbolic link\n";
    $target !~ /[\r\n]/ or die "runtime link target contains a line break\n";
    my $relative = index($path, "$root/") == 0 ? substr($path, length($root) + 1) : $path;
    my $display_target = index($target, "$root/") == 0
        ? substr($target, length($root) + 1)
        : $target;
    $aggregate->add('L ' . $display_target . ' ' . $relative . "\n");
}
print $aggregate->hexdigest, "\n";
PERL
}

openclaw_write_managed_wrapper() {
    local node_path="$1"
    local wrapper_temp

    wrapper_temp="$(mktemp "$openclaw_management_root/bin/.openclaw.partial.XXXXXX")"
    {
        printf '#!/usr/bin/env bash\n'
        printf 'set -euo pipefail\n'
        printf 'export PATH=%q\n' "$(dirname "$node_path"):/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
        printf 'export OPENCLAW_PROFILE=%q\n' "$PERSONAL_EDGE_OPENCLAW_PROFILE"
        printf 'export OPENCLAW_STATE_DIR=%q\n' "$openclaw_state_dir"
        printf 'export OPENCLAW_CONFIG_PATH=%q\n' "$openclaw_config_path"
        printf 'exec %q %q --profile %q "$@"\n' \
            "$node_path" "$openclaw_cli_entry" "$PERSONAL_EDGE_OPENCLAW_PROFILE"
    } > "$wrapper_temp"
    chmod 700 "$wrapper_temp"
    mv -f "$wrapper_temp" "$openclaw_cli_path"
}

openclaw_write_deployment_manifest() {
    local install_method="$1"
    local node_path="$2"
    local manifest_target="${3:-$openclaw_deployment_manifest}"
    local manifest_temp node_version

    node_version="$("$node_path" --version)"
    openclaw_assert_safe_path "$manifest_target" "deployment manifest target"
    manifest_temp="$(mktemp "$(dirname "$manifest_target")/.deployment.partial.XXXXXX")"
    jq -n \
        --arg version "$PERSONAL_EDGE_OPENCLAW_VERSION" \
        --arg profile "$PERSONAL_EDGE_OPENCLAW_PROFILE" \
        --arg method "$install_method" \
        --arg model "$PERSONAL_EDGE_OPENCLAW_MODEL" \
        --arg stateDir "$openclaw_state_dir" \
        --arg configPath "$openclaw_config_path" \
        --arg workspaceDir "$openclaw_workspace_dir" \
        --arg runtimeRoot "$openclaw_runtime_root" \
        --arg cli "$openclaw_cli_path" \
        --arg node "$node_path" \
        --arg nodeVersion "$node_version" \
        --arg nodeSha "$(openclaw_sha256 "$node_path")" \
        --arg runtimeTreeSha "$(openclaw_runtime_tree_sha256)" \
        --arg wrapperSha "$(openclaw_sha256 "$openclaw_cli_path")" \
        --arg commonPath "$openclaw_management_common" \
        --arg commonSha "$(openclaw_sha256 "$openclaw_management_common")" \
        --arg watchdogPath "$openclaw_management_watchdog" \
        --arg watchdogSha "$(openclaw_sha256 "$openclaw_management_watchdog")" \
        --arg packageJson "$openclaw_package_json" \
        --arg packageSha "$(openclaw_sha256 "$openclaw_package_json")" \
        --arg cliEntry "$openclaw_cli_entry" \
        --arg cliEntrySha "$(openclaw_sha256 "$openclaw_cli_entry")" \
        --arg gatewayEntry "$openclaw_gateway_entry" \
        --arg gatewayEntrySha "$(openclaw_sha256 "$openclaw_gateway_entry")" '
        {
            schemaVersion: 4,
            version: $version,
            profile: $profile,
            installMethod: $method,
            model: $model,
            stateDir: $stateDir,
            configPath: $configPath,
            workspaceDir: $workspaceDir,
            runtimeRoot: $runtimeRoot,
            cliPath: $cli,
            wrapperPath: $cli,
            wrapperSha256: $wrapperSha,
            commonPath: $commonPath,
            commonSha256: $commonSha,
            watchdogPath: $watchdogPath,
            watchdogSha256: $watchdogSha,
            nodePath: $node,
            nodeVersion: $nodeVersion,
            nodeSha256: $nodeSha,
            runtimeTreeSha256: $runtimeTreeSha,
            packageJsonPath: $packageJson,
            packageJsonSha256: $packageSha,
            cliEntryPath: $cliEntry,
            cliEntrySha256: $cliEntrySha,
            gatewayEntryPath: $gatewayEntry,
            gatewayEntrySha256: $gatewayEntrySha
        }
    ' > "$manifest_temp"
    chmod 600 "$manifest_temp"
    mv -f "$manifest_temp" "$manifest_target"
}

openclaw_assert_restrictive_config() {
    local target="$1"

    openclaw_require_command jq
    jq -e \
        --arg model "$PERSONAL_EDGE_OPENCLAW_MODEL" \
        --arg workspace "$openclaw_workspace_dir" '
        .gateway.mode == "local" and
        .gateway.port == 18789 and
        .gateway.bind == "loopback" and
        .gateway.auth == {
            mode: "token",
            token: {
            source: "store", provider: "default", id: "OPENCLAW_GATEWAY_TOKEN"
            },
            allowTailscale: false,
            rateLimit: {
                maxAttempts: 10, windowMs: 60000, lockoutMs: 300000,
                exemptLoopback: false
            }
        } and
        (.gateway.tailscale.mode == "off" or .gateway.tailscale.mode == "serve") and
        .gateway.controlUi == {
            enabled: false, toolTitles: false, sessionObserver: false,
            automaticallyFetchFavicons: false, allowExternalEmbedUrls: false,
            allowedOrigins: [], dangerouslyAllowHostHeaderOriginFallback: false
        } and
        .gateway.cliAgents == {enabled: false} and
        .gateway.terminal == {enabled: false} and
        .gateway.http == {endpoints: {
            chatCompletions: {enabled: false}, responses: {enabled: false}
        }} and
        .gateway.nodes == {
            browser: {mode: "off"},
            pairing: {autoApproveLocal: false, sshVerify: false},
            commands: {
                allow: [],
                deny: [
                    "system.run", "system.which", "browser.proxy", "camera.snap",
                    "camera.clip", "screen.record", "desktop.stream", "sms.search",
                    "sms.send", "health.summary"
                ]
            },
            pluginTools: {enabled: false},
            allowSkills: false
        } and
        .gateway.tools.allow == [] and
        .gateway.tools.deny[0] == "*" and
        ([
            "*", "session_status", "exec", "process", "code_execution", "read", "write",
            "edit", "apply_patch", "web_search", "web_fetch", "browser", "terminal",
            "memory_search", "memory_get", "message", "sessions_spawn", "sessions_send",
            "cron", "gateway", "nodes", "computer"
        ] - .gateway.tools.deny | length) == 0 and
        .session.dmScope == "per-channel-peer" and
        .session.maintenance == {
            mode: "warn",
            pruneAfter: "24h",
            archiveDashboardAfter: false,
            maxEntries: 64,
            preserveRecent: false,
            resetArchiveRetention: "24h",
            maxDiskBytes: "100mb",
            highWaterBytes: "80mb"
        } and
        .agents.defaults.workspace == $workspace and
        .agents.defaults.skipBootstrap == true and
        .agents.defaults.contextInjection == "never" and
        .agents.defaults.model.primary == $model and
        .agents.defaults.model.fallbacks == [] and
        .agents.defaults.modelPolicy.allow == [$model] and
        .agents.defaults.utilityModel == "" and
        .agents.defaults.thinkingDefault == "low" and
        .agents.defaults.reasoningDefault == "off" and
        (.agents.defaults.models | keys) == [$model] and
        .agents.defaults.models[$model] == {params: {maxTokens: 2048}} and
        .agents.defaults.skills == [] and
        .agents.defaults.startupContext.enabled == false and
        .agents.defaults.compaction.memoryFlush.enabled == false and
        .agents.defaults.compaction.postCompactionSections == [] and
        .agents.defaults.sandbox == {mode: "non-main", scope: "agent"} and
        ((.agents.entries // []) | length) == 0 and
        .tools.profile == "minimal" and
        .tools.deny[0] == "*" and
        .tools.exec.security == "deny" and
        .tools.exec.ask == "always" and
        .tools.elevated.enabled == false and
        .tools.fs.workspaceOnly == true and
        .tools.sessions.visibility == "agent" and
        .tools.agentToAgent.enabled == false and
        .tools.codeMode.enabled == false and
        .browser.enabled == false and
        .browser.evaluateEnabled == false and
        .cron.enabled == false and
        .cron.triggers.enabled == false and
        .hooks.enabled == false and
        .hooks.internal.enabled == false and
        .acp.enabled == false and
        .discovery.mdns.mode == "off" and
        .telemetry.enabled == false and
        .update.checkOnStart == false and
        .update.auto.enabled == false and
        .plugins.enabled == true and
        .plugins.allow == ["openrouter", "device-pair"] and
        .plugins.entries == {
            openrouter: {enabled: true}, "device-pair": {enabled: true}
        } and
        .plugins.slots.memory == "none" and
        ((.plugins.load.paths // []) | length) == 0 and
        ((.skills.load.extraDirs // []) | length) == 0 and
        .nodeHost == {
            agentRuns: {claude: {enabled: false}},
            workerRuns: {enabled: false},
            browserProxy: {enabled: false},
            mcp: {servers: {}},
            skills: {enabled: false}
        } and
        .channels == {} and
        ([
            "*", "session_status", "group:automation", "group:runtime", "group:fs",
            "group:web", "group:ui", "group:memory", "group:messaging",
            "group:sessions", "group:nodes", "group:agents", "group:media",
            "group:plugins", "exec", "process", "code_execution", "read", "write",
            "edit", "apply_patch", "web_search", "web_fetch", "browser", "terminal",
            "portal", "canvas", "memory_search", "memory_get", "message",
            "sessions_spawn", "sessions_send", "subagents", "cron", "gateway",
            "nodes", "computer", "skill_workshop"
        ] - .tools.deny | length) == 0 and
        (.models.providers | keys) == ["openrouter"] and
        ((.models.providers.openrouter | keys) == ["apiKey", "params"] or
         (.models.providers.openrouter | keys) == ["params"]) and
        (.models.providers.openrouter.params | keys) == ["provider"] and
        (
            (.models.providers.openrouter | has("apiKey") | not) or
            .models.providers.openrouter.apiKey == {
                source: "store", provider: "default", id: "OPENROUTER_API_KEY"
            }
        ) and
        .models.providers.openrouter.params.provider == {
            allow_fallbacks: true,
            require_parameters: true,
            data_collection: "deny",
            zdr: true,
            sort: "latency"
        }
    ' "$target" >/dev/null || openclaw_fail "restrictive OpenClaw config drifted: $target"
}

openclaw_existing_cli() {
    local node_path="${PERSONAL_EDGE_OPENCLAW_NODE_BIN:-/opt/homebrew/opt/node/bin/node}"

    env \
        PATH="$(dirname "$node_path"):/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
        OPENCLAW_PROFILE="$PERSONAL_EDGE_OPENCLAW_PROFILE" \
        OPENCLAW_STATE_DIR="$openclaw_state_dir" \
        OPENCLAW_CONFIG_PATH="$openclaw_config_path" \
        "$node_path" "$openclaw_cli_entry" --profile "$PERSONAL_EDGE_OPENCLAW_PROFILE" "$@"
}

openclaw_assert_existing_runtime() {
    local node_path="${PERSONAL_EDGE_OPENCLAW_NODE_BIN:-/opt/homebrew/opt/node/bin/node}"
    local node_version node_major installed_version_output installed_version unsafe_path link_path link_target
    local package_version

    openclaw_require_command find
    openclaw_require_command jq
    openclaw_require_command realpath
    openclaw_assert_safe_path "$openclaw_runtime_root" "OpenClaw runtime root"
    openclaw_assert_owned_nonwritable_dir "$openclaw_runtime_root" "OpenClaw runtime root"
    openclaw_assert_owned_nonwritable_file "$openclaw_package_json" "OpenClaw package metadata"
    openclaw_assert_owned_nonwritable_file "$openclaw_cli_entry" "OpenClaw CLI entry"
    openclaw_assert_owned_nonwritable_file "$openclaw_gateway_entry" "OpenClaw Gateway entry"
    [[ -x "$node_path" && ! -L "$node_path" ]] ||
        openclaw_fail "pinned Homebrew Node path is missing or unsafe: $node_path"
    openclaw_assert_owned_nonwritable_file "$node_path" "pinned Homebrew Node"
    package_version="$(jq -r '.version' "$openclaw_package_json")"
    [[ "$package_version" == "$PERSONAL_EDGE_OPENCLAW_VERSION" ]] ||
        openclaw_fail_archived_relay_version "existing OpenClaw package" "$package_version"

    unsafe_path="$(find "$openclaw_runtime_root" \( -type f -o -type d \) \
        \( -perm -020 -o -perm -002 \) -print -quit)"
    [[ -z "$unsafe_path" ]] || openclaw_fail "runtime contains a group/world-writable path: $unsafe_path"
    unsafe_path="$(find "$openclaw_runtime_root" \( -type f -o -type d \) ! -user "$(id -un)" -print -quit)"
    [[ -z "$unsafe_path" ]] || openclaw_fail "runtime contains a foreign-owned path: $unsafe_path"
    while IFS= read -r -d '' link_path; do
        link_target="$(realpath "$link_path" 2>/dev/null || true)"
        case "$link_target/" in
            "$openclaw_runtime_root"/*) ;;
            *) openclaw_fail "runtime symlink escapes or is broken: $link_path" ;;
        esac
    done < <(find "$openclaw_runtime_root" -type l -print0)

    # Only execute Node/OpenClaw after every reachable runtime path passed ownership, mode, and
    # symlink-containment checks.
    node_version="$("$node_path" --version)"
    node_major="${node_version#v}"
    node_major="${node_major%%.*}"
    [[ "$node_major" == "$PERSONAL_EDGE_OPENCLAW_NODE_MAJOR" ]] ||
        openclaw_fail "existing Node is not major 26: $node_version"
    installed_version_output="$(openclaw_existing_cli --version)"
    installed_version="$(openclaw_extract_version "$installed_version_output")"
    [[ "$installed_version" == "$PERSONAL_EDGE_OPENCLAW_VERSION" ]] ||
        openclaw_fail_archived_relay_version "existing OpenClaw CLI" "$installed_version"
}

openclaw_assert_gateway_plist() {
    local plist_json="$1"

    jq -e \
        --arg label "$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL" \
        --arg port "$PERSONAL_EDGE_OPENCLAW_PORT" \
        --arg workingDirectory "$openclaw_state_dir" '
        .Label == $label and
        .RunAtLoad == true and
        .KeepAlive == true and
        .WorkingDirectory == $workingDirectory and
        (.ProgramArguments | type == "array") and
        (.ProgramArguments | index("gateway")) != null and
        ([.ProgramArguments as $args | range(0; ($args | length) - 1) |
          select($args[.] == "--port" and $args[. + 1] == $port)] | length) == 1 and
        ([.ProgramArguments[] | select(. == "0.0.0.0" or . == "lan" or . == "funnel")] | length) == 0
    ' <<< "$plist_json" >/dev/null || openclaw_fail "Gateway LaunchAgent identity or arguments drifted"
}

openclaw_assert_watchdog_plist() {
    local plist_json="$1"

    jq -e \
        --arg label "$PERSONAL_EDGE_OPENCLAW_WATCHDOG_LABEL" \
        --arg scriptPath "$openclaw_management_watchdog" \
        --arg allowedRoot "$openclaw_user_root" \
        --arg stateDir "$openclaw_state_dir" \
        --arg configPath "$openclaw_config_path" \
        --arg runtimeRoot "$openclaw_runtime_root" \
        --arg managementRoot "$openclaw_management_root" \
        --arg launchAgentDir "$openclaw_launch_agent_dir" \
        --arg stdoutPath "$openclaw_state_dir/logs/personal-edge-watchdog.log" \
        --arg stderrPath "$openclaw_state_dir/logs/personal-edge-watchdog.err.log" '
        keys == ([
            "EnvironmentVariables", "Label", "ProcessType", "ProgramArguments", "RunAtLoad",
            "StandardErrorPath", "StandardOutPath", "StartInterval"
        ] | sort) and
        .Label == $label and
        .ProgramArguments == ["/bin/bash", $scriptPath] and
        .EnvironmentVariables == {
            PERSONAL_EDGE_OPENCLAW_ALLOWED_ROOT: $allowedRoot,
            PERSONAL_EDGE_OPENCLAW_STATE_DIR: $stateDir,
            PERSONAL_EDGE_OPENCLAW_CONFIG_PATH: $configPath,
            PERSONAL_EDGE_OPENCLAW_RUNTIME_ROOT: $runtimeRoot,
            PERSONAL_EDGE_OPENCLAW_MANAGEMENT_ROOT: $managementRoot,
            PERSONAL_EDGE_OPENCLAW_LAUNCH_AGENT_DIR: $launchAgentDir
        } and
        .RunAtLoad == true and
        .StartInterval == 300 and
        .ProcessType == "Standard" and
        .StandardOutPath == $stdoutPath and
        .StandardErrorPath == $stderrPath
    ' <<< "$plist_json" >/dev/null || openclaw_fail "watchdog LaunchAgent policy drifted"
}

openclaw_assert_service_definition() {
    local node_path="$1"
    local service_mode="$2"
    local service_wrapper="$openclaw_state_dir/service-env/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL-env-wrapper.sh"
    local service_environment="$openclaw_state_dir/service-env/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL.env"
    local gateway_plist_json expected_service_wrapper_hash expected_service_path

    openclaw_require_command awk
    openclaw_require_command jq
    openclaw_require_command plutil
    openclaw_assert_owned_nonwritable_file "$openclaw_gateway_plist" "Gateway LaunchAgent"
    plutil -lint "$openclaw_gateway_plist" >/dev/null
    gateway_plist_json="$(plutil -convert json -o - "$openclaw_gateway_plist")"
    openclaw_assert_gateway_plist "$gateway_plist_json"
    case "$service_mode" in
        adopted)
            jq -e \
                --arg wrapper "$service_wrapper" \
                --arg environment "$service_environment" \
                --arg node "$node_path" \
                --arg entry "$openclaw_gateway_entry" '
                .ProgramArguments == [
                    "/bin/sh", $wrapper, $environment, $node, "--max-old-space-size=16384",
                    $entry, "gateway", "--port", "18789"
                ] and ((.EnvironmentVariables // {}) == {})
            ' <<< "$gateway_plist_json" >/dev/null ||
                openclaw_fail "adopted Gateway LaunchAgent command or environment drifted"
            ;;
        fresh)
            jq -e \
                --arg wrapper "$service_wrapper" \
                --arg environment "$service_environment" \
                --arg managedWrapper "$openclaw_cli_path" '
                .ProgramArguments == [
                    "/bin/sh", $wrapper, $environment, $managedWrapper,
                    "gateway", "--port", "18789"
                ] and ((.EnvironmentVariables // {}) == {})
            ' <<< "$gateway_plist_json" >/dev/null ||
                openclaw_fail "managed Gateway LaunchAgent command or environment drifted"
            ;;
        *) openclaw_fail "unknown Gateway service definition mode" ;;
    esac

    openclaw_assert_private_file "$service_wrapper" "Gateway service environment wrapper"
    openclaw_assert_private_file "$service_environment" "Gateway service environment"
    expected_service_wrapper_hash="$(printf '%s\n' \
        '#!/bin/sh' \
        'set -eu' \
        'env_file="$1"' \
        'shift' \
        'if [ -f "$env_file" ]; then' \
        '  . "$env_file"' \
        'fi' \
        'exec "$@"' | shasum -a 256 | awk '{ print $1 }')"
    [[ "$(openclaw_sha256 "$service_wrapper")" == "$expected_service_wrapper_hash" ]] ||
        openclaw_fail "Gateway service environment wrapper content drifted"
    expected_service_path="$(dirname "$node_path"):$openclaw_runtime_root/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
    awk \
        -v expectedHome="$HOME" \
        -v expectedConfig="$openclaw_config_path" \
        -v expectedPort="$PERSONAL_EDGE_OPENCLAW_PORT" \
        -v expectedLabel="$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL" \
        -v expectedProfile="$PERSONAL_EDGE_OPENCLAW_PROFILE" \
        -v expectedState="$openclaw_state_dir" \
        -v expectedTmp="$openclaw_state_dir/tmp" \
        -v expectedPath="$expected_service_path" '
        BEGIN {
            split("HOME NODE_EXTRA_CA_CERTS NODE_OPTIONS NODE_USE_SYSTEM_CA OPENCLAW_CONFIG_PATH OPENCLAW_GATEWAY_PORT OPENCLAW_LAUNCHD_LABEL OPENCLAW_PROFILE OPENCLAW_SERVICE_KIND OPENCLAW_SERVICE_MARKER OPENCLAW_STATE_DIR OPENCLAW_SYSTEMD_UNIT OPENCLAW_WINDOWS_TASK_HIDDEN_LAUNCHER OPENCLAW_WINDOWS_TASK_NAME PATH TMPDIR", names, " ")
            for (i in names) allowed[names[i]] = 1
        }
        NR == 1 {
            if ($0 != "# Generated by OpenClaw. Do not edit while the gateway service is installed.") exit 1
            next
        }
        {
            # OpenClaw emits a descriptive Windows task name even in the shared macOS service
            # environment. Literal parentheses are inert inside this single-quoted value; keep
            # every shell-active character (including a quote) excluded.
            if ($0 !~ "^export [A-Z][A-Z0-9_]*=\047[A-Za-z0-9_./: @,+%=()\-]*\047$") exit 1
            key = $2
            sub(/=.*/, "", key)
            if (!allowed[key] || seen[key]++) exit 1
            if (key == "HOME" && $0 != "export HOME=\047" expectedHome "\047") exit 1
            if (key == "NODE_EXTRA_CA_CERTS" && $0 != "export NODE_EXTRA_CA_CERTS=\047/etc/ssl/cert.pem\047") exit 1
            if (key == "NODE_OPTIONS" && $0 != "export NODE_OPTIONS=\047\047") exit 1
            if (key == "NODE_USE_SYSTEM_CA" && $0 != "export NODE_USE_SYSTEM_CA=\0471\047") exit 1
            if (key == "OPENCLAW_CONFIG_PATH" && $0 != "export OPENCLAW_CONFIG_PATH=\047" expectedConfig "\047") exit 1
            if (key == "OPENCLAW_GATEWAY_PORT" && $0 != "export OPENCLAW_GATEWAY_PORT=\047" expectedPort "\047") exit 1
            if (key == "OPENCLAW_LAUNCHD_LABEL" && $0 != "export OPENCLAW_LAUNCHD_LABEL=\047" expectedLabel "\047") exit 1
            if (key == "OPENCLAW_PROFILE" && $0 != "export OPENCLAW_PROFILE=\047" expectedProfile "\047") exit 1
            if (key == "OPENCLAW_SERVICE_KIND" && $0 != "export OPENCLAW_SERVICE_KIND=\047gateway\047") exit 1
            if (key == "OPENCLAW_SERVICE_MARKER" && $0 != "export OPENCLAW_SERVICE_MARKER=\047openclaw\047") exit 1
            if (key == "OPENCLAW_STATE_DIR" && $0 != "export OPENCLAW_STATE_DIR=\047" expectedState "\047") exit 1
            if (key == "OPENCLAW_SYSTEMD_UNIT" && $0 != "export OPENCLAW_SYSTEMD_UNIT=\047openclaw-gateway-personaledge.service\047") exit 1
            if (key == "OPENCLAW_WINDOWS_TASK_HIDDEN_LAUNCHER" && $0 != "export OPENCLAW_WINDOWS_TASK_HIDDEN_LAUNCHER=\0471\047") exit 1
            if (key == "OPENCLAW_WINDOWS_TASK_NAME" && $0 != "export OPENCLAW_WINDOWS_TASK_NAME=\047OpenClaw Gateway (personaledge)\047") exit 1
            if (key == "PATH" && $0 != "export PATH=\047" expectedPath "\047") exit 1
            if (key == "TMPDIR" && $0 != "export TMPDIR=\047" expectedTmp "\047") exit 1
        }
        END {
            for (name in allowed) if (seen[name] != 1) exit 1
        }
    ' "$service_environment" ||
        openclaw_fail "Gateway service environment contains unexpected shell syntax, keys, or identity"
}

openclaw_assert_adopted_service_definition() {
    openclaw_assert_service_definition "$1" adopted
}

openclaw_assert_fresh_service_definition() {
    openclaw_assert_service_definition "$1" fresh
}

openclaw_assert_clean_secrets_receipt() {
    local receipt_path="$1"

    jq -e '
        .status == "clean" and
        .summary.plaintextCount == 0 and
        .summary.unresolvedRefCount == 0 and
        .summary.shadowedRefCount == 0 and
        .summary.storeResidueCount == 0 and
        .summary.legacyResidueCount == 0
    ' "$receipt_path" >/dev/null ||
        openclaw_fail "secrets audit is not clean; no secret values were printed"
}

# Record only byte hashes for authored secret-bearing files and logical hashes for the stable
# credential tables in the live SQLite stores.  WAL/SHM bytes and unrelated runtime tables are
# deliberately excluded: a healthy Gateway can change those while a read-only preflight runs.
# The receipt contains counts and hashes, never a credential value.
openclaw_snapshot_security_state() {
    local output_path="$1"
    local scratch_dir="$2"
    local service_wrapper="$3"
    local service_environment="$4"
    local shared_auth_db="$openclaw_state_dir/state/openclaw.sqlite"
    local agent_auth_db="$openclaw_state_dir/agents/main/agent/openclaw-agent.sqlite"
    local paths_path="$scratch_dir/security-paths"
    local sorted_path="$scratch_dir/security-paths-sorted"
    local protected_path relative_path database table table_count table_hash

    openclaw_require_command sqlite3
    : > "$paths_path"
    printf '%s\n' "$openclaw_config_path" "$service_wrapper" "$service_environment" \
        >> "$paths_path"
    for protected_root in \
        "$openclaw_state_dir/credentials" "$openclaw_state_dir/mcp-oauth" \
        "$openclaw_state_dir/service-env"; do
        if [[ -d "$protected_root" && ! -L "$protected_root" ]]; then
            find "$protected_root" -type f \
                ! -name '*.sqlite' ! -name '*.sqlite-wal' ! -name '*.sqlite-shm' \
                ! -name '*.sqlite-journal' -print >> "$paths_path"
        elif [[ -e "$protected_root" || -L "$protected_root" ]]; then
            openclaw_fail "protected credential root is not a real directory: $protected_root"
        fi
    done
    for protected_root in "$openclaw_state_dir/state" "$openclaw_state_dir/agents"; do
        if [[ -d "$protected_root" && ! -L "$protected_root" ]]; then
            find "$protected_root" -type f \
                \( -name '*auth*.json' -o -name '*oauth*.json' \) -print >> "$paths_path"
        else
            openclaw_fail "protected OpenClaw state root is missing or unsafe: $protected_root"
        fi
    done
    sort -u "$paths_path" > "$sorted_path"
    : > "$output_path"
    while IFS= read -r protected_path; do
        [[ -f "$protected_path" && ! -L "$protected_path" ]] ||
            openclaw_fail "protected state is missing or unsafe: $protected_path"
        [[ "$protected_path" != *$'\n'* && "$protected_path" != *$'\r'* &&
           "$protected_path" != *$'\t'* ]] ||
            openclaw_fail "protected state path contains a line break"
        relative_path="${protected_path#"$openclaw_state_dir/"}"
        printf 'file\t%s\t%s\t%s\n' \
            "$(openclaw_sha256 "$protected_path")" \
            "$(stat -f '%z' "$protected_path")" \
            "$relative_path" >> "$output_path"
    done < "$sorted_path"

    for database in "$shared_auth_db" "$agent_auth_db"; do
        openclaw_assert_private_file "$database" "SQLite credential store"
    done
    while IFS='|' read -r database table; do
        [[ "$table" =~ ^[a-z_]+$ ]] || openclaw_fail "unsafe SQLite table identifier"
        [[ "$(sqlite3 -readonly -cmd '.timeout 5000' "$database" \
            "SELECT count(*) FROM sqlite_schema WHERE type='table' AND name='$table';")" == "1" ]] ||
            openclaw_fail "required SQLite credential table is missing: $table"
        IFS='|' read -r table_count table_hash < <(
            sqlite3 -readonly -cmd '.timeout 5000' "$database" \
                "PRAGMA query_only=ON; SELECT count(*), lower(hex(sha3_query('SELECT * FROM $table ORDER BY rowid',256))) FROM $table;"
        )
        [[ "$table_count" =~ ^[0-9]+$ && "$table_hash" =~ ^[a-f0-9]{64}$ ]] ||
            openclaw_fail "could not fingerprint SQLite credential table: $table"
        printf 'sqlite\t%s\t%s\t%s\n' \
            "${database#"$openclaw_state_dir/"}:$table" "$table_count" "$table_hash" \
            >> "$output_path"
    done <<EOF
$shared_auth_db|secret_store_entries
$shared_auth_db|mcp_oauth_stores
$agent_auth_db|auth_profile_store
EOF
    LC_ALL=C sort -o "$output_path" "$output_path"
    chmod 600 "$output_path"
}

openclaw_assert_required_plugins_receipt() {
    local receipt_path="$1"

    jq -e --arg bundledRoot "$openclaw_runtime_root/lib/node_modules/openclaw/dist/extensions" '
        ([.plugins[] | select(.id == "openrouter" and .enabled == true and
          .status == "loaded" and .origin == "bundled" and
          .rootDir == ($bundledRoot + "/openrouter") and
          (.providerIds | index("openrouter") != null))] | length) == 1 and
        ([.plugins[] | select(.id == "device-pair" and .enabled == true and
          .status == "loaded" and .origin == "bundled" and
          .rootDir == ($bundledRoot + "/device-pair") and
          (.commands | index("pair") != null))] | length) == 1
    ' "$receipt_path" >/dev/null ||
        openclaw_fail "required OpenRouter or device-pair plugin is not loaded"
}

openclaw_assert_live_plugins_receipt() {
    local receipt_path="$1"

    jq -e '
        (.plugins | type) == "object" and
        (.plugins.loaded | type) == "array" and
        (.plugins.errors | type) == "array" and
        ((.plugins.unavailable // []) | type) == "array" and
        (.plugins.loaded | index("openrouter")) != null and
        (.plugins.loaded | index("device-pair")) != null and
        (.plugins.errors | length) == 0 and
        ((.plugins.unavailable // []) | length) == 0
    ' "$receipt_path" >/dev/null ||
        openclaw_fail "running Gateway lacks required plugins or reports a plugin failure"
}

# Pinned 2026.8.1 claims one foreground Serve route to its own ephemeral loopback ingress.
# This parser validates the entire receipt, including competing root/service/Funnel routes.
openclaw_tailscale_serve_backend_port() {
    jq -er '
        def emptyMap: . == null or (type == "object" and length == 0);
        . as $root |
        if type != "object" or ((keys - ["TCP","Web","AllowFunnel","Foreground","Services"]) | length) != 0 or
           ((.Services | emptyMap) | not) or
           (((.Foreground // {}) | type) != "object") then error("unexpected Serve root") else . end |
        ([select((.TCP | emptyMap | not) or (.Web | emptyMap | not))] +
          [(.Foreground // {}) | to_entries[] | .value]) as $routes |
        if ($routes | length) != 1 or
           ([.. | objects | select(has("AllowFunnel")) | .AllowFunnel |
             if type == "object" then to_entries[] | select(.value != false) else true end] | length) != 0
        then error("ambiguous or public Serve route") else $routes[0] end |
        if type != "object" or ((keys - ["TCP","Web","AllowFunnel"]) | length) != 0 or
           .TCP != {"443":{"HTTPS":true}} or (.Web | type) != "object" or (.Web | length) != 1
        then error("not one HTTPS443 route") else . end |
        .Web | to_entries[0] |
        if (.key | test("^[a-zA-Z0-9.-]+\\.ts\\.net:443$")) and
           (.value | keys) == ["Handlers"] and (.value.Handlers | keys) == ["/"] and
           (.value.Handlers["/"] | keys) == ["Proxy"]
        then .value.Handlers["/"].Proxy else error("unexpected Serve handler") end |
        capture("^http://127\\.0\\.0\\.1:(?<port>[0-9]{1,5})$").port |
        select((tonumber) > 0 and (tonumber) <= 65535)
    ' "$1" || openclaw_fail "Tailscale status is not one exact tailnet-only loopback Serve route with Funnel off"
}

openclaw_assert_tailscale_serve_receipt() {
    local observed_port
    observed_port="$(openclaw_tailscale_serve_backend_port "$1")" || return 1
    [[ "$observed_port" == "$2" ]] || openclaw_fail "Tailscale Serve backend port does not match"
}

openclaw_assert_managed_tailscale_serve_receipt() {
    local receipt_path="$1" backend_port gateway_pid repeated_pid listener_rows listener_name
    local ingress_count=0 gateway_count=0 owner_uid
    backend_port="$(openclaw_tailscale_serve_backend_port "$receipt_path")" || return 1
    # The pinned runtime always uses a foreground claim and a separate port-zero ingress.
    jq -e '(.Foreground | type == "object" and length == 1) and
        ((.TCP // {}) | length == 0) and ((.Web // {}) | length == 0)' "$receipt_path" >/dev/null ||
        openclaw_fail "managed Tailscale ingress requires one foreground claim"
    [[ "$backend_port" != "$PERSONAL_EDGE_OPENCLAW_PORT" ]] ||
        openclaw_fail "managed Tailscale ingress must use its dedicated backend"
    gateway_pid="$(launchctl print "gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL" |
        awk '$1 == "pid" && $2 == "=" && $3 ~ /^[1-9][0-9]*$/ {print $3}')" || return 1
    [[ "$gateway_pid" =~ ^[1-9][0-9]*$ ]] || openclaw_fail "Gateway PID is unavailable or ambiguous"
    owner_uid="$(ps -p "$gateway_pid" -o uid= | tr -d ' ')" || return 1
    [[ "$owner_uid" == "$(id -u)" ]] || openclaw_fail "Gateway process owner changed"
    listener_rows="$(lsof -a -p "$gateway_pid" -nP -iTCP -sTCP:LISTEN -F pn)" ||
        openclaw_fail "Gateway listener ownership is unavailable"
    [[ "$(awk '/^p/ {print substr($0,2)}' <<< "$listener_rows")" == "$gateway_pid" ]] ||
        openclaw_fail "Serve backend belongs to another process"
    while IFS= read -r listener_name; do
        case "$listener_name" in
            "n127.0.0.1:$backend_port") ingress_count=$((ingress_count + 1)) ;;
            "n127.0.0.1:$PERSONAL_EDGE_OPENCLAW_PORT"|"n[::1]:$PERSONAL_EDGE_OPENCLAW_PORT")
                gateway_count=$((gateway_count + 1)) ;;
            *) openclaw_fail "Gateway has a non-loopback or unexpected listener" ;;
        esac
    done < <(awk '/^n/ {print}' <<< "$listener_rows")
    (( ingress_count == 1 && gateway_count > 0 )) ||
        openclaw_fail "Serve backend is not the managed Gateway loopback ingress"
    repeated_pid="$(launchctl print "gui/$(id -u)/$PERSONAL_EDGE_OPENCLAW_GATEWAY_LABEL" |
        awk '$1 == "pid" && $2 == "=" && $3 ~ /^[1-9][0-9]*$/ {print $3}')" || return 1
    [[ "$repeated_pid" == "$gateway_pid" ]] || openclaw_fail "Gateway restarted during Serve verification"
}

openclaw_check_managed_tailscale_serve() {
    local sample_temp="$1" tailscale_binary
    [[ "$(jq -r '.gateway.tailscale.mode' "$openclaw_config_path")" == serve ]] || return 0
    if [[ -n "${PERSONAL_EDGE_OPENCLAW_FIXTURE_TAILSCALE_BIN:-}" ]]; then
        [[ "$(cd "$openclaw_user_root" && pwd -P)" != "$(cd "$HOME" && pwd -P)" ]] ||
            openclaw_fail "fixture CLI is forbidden for the owner root"
        tailscale_binary="$PERSONAL_EDGE_OPENCLAW_FIXTURE_TAILSCALE_BIN"
        openclaw_assert_safe_path "$tailscale_binary" "fixture Tailscale CLI"
        openclaw_assert_owned_nonwritable_file "$tailscale_binary" "fixture Tailscale CLI"
    else
        [[ "$(cd "$openclaw_user_root" && pwd -P)" == "$(cd "$HOME" && pwd -P)" ]] ||
            openclaw_fail "isolated fixture must supply its own Tailscale CLI"
        tailscale_binary="/Applications/Tailscale.app/Contents/MacOS/Tailscale"
    fi
    env TAILSCALE_BE_CLI=1 "$tailscale_binary" status --json > "$sample_temp/managed-tailnet.json" || return 1
    jq -e '.BackendState == "Running" and .Self.Online == true' "$sample_temp/managed-tailnet.json" >/dev/null ||
        openclaw_fail "Tailscale is not online"
    env TAILSCALE_BE_CLI=1 "$tailscale_binary" serve status --json > "$sample_temp/managed-serve.json" || return 1
    openclaw_assert_managed_tailscale_serve_receipt "$sample_temp/managed-serve.json"
}

openclaw_load_deployment() {
    local install_method expected_node node_version node_major deployment_json package_version
    local candidate_manifest final_manifest

    openclaw_require_command jq
    openclaw_require_command realpath
    openclaw_require_command shasum
    candidate_manifest="$openclaw_management_root/.deployment.candidate.json"
    final_manifest="$openclaw_management_root/deployment.json"
    if [[ ! -f "$openclaw_deployment_manifest" || -L "$openclaw_deployment_manifest" ]]; then
        if [[ "$openclaw_deployment_manifest" == "$candidate_manifest" &&
              -f "$final_manifest" && ! -L "$final_manifest" ]]; then
            openclaw_deployment_manifest="$final_manifest"
        else
            openclaw_fail "deployment manifest not found: $openclaw_deployment_manifest"
        fi
    fi
    openclaw_assert_safe_path "$openclaw_deployment_manifest" "deployment manifest"
    openclaw_assert_private_file "$openclaw_deployment_manifest" "deployment manifest"
    if ! deployment_json="$(jq -c -s \
        'if length == 1 then .[0] else error("expected exactly one manifest object") end' \
        "$openclaw_deployment_manifest" 2>/dev/null)"; then
        # A RunAtLoad watchdog can select the candidate immediately before its atomic final
        # publication. Retry only that exact transition; never fall back for an arbitrary path.
        if [[ "$openclaw_deployment_manifest" == "$candidate_manifest" &&
              -f "$final_manifest" && ! -L "$final_manifest" ]]; then
            openclaw_deployment_manifest="$final_manifest"
            openclaw_assert_safe_path "$openclaw_deployment_manifest" "deployment manifest"
            openclaw_assert_private_file "$openclaw_deployment_manifest" "deployment manifest"
            deployment_json="$(jq -c -s \
                'if length == 1 then .[0] else error("expected exactly one manifest object") end' \
                "$openclaw_deployment_manifest" 2>/dev/null)" ||
                openclaw_fail "deployment manifest is not valid JSON"
        else
            openclaw_fail "deployment manifest is not valid JSON"
        fi
    fi
    jq -e \
        --arg version "$PERSONAL_EDGE_OPENCLAW_VERSION" \
        --arg profile "$PERSONAL_EDGE_OPENCLAW_PROFILE" \
        --arg model "$PERSONAL_EDGE_OPENCLAW_MODEL" \
        --arg stateDir "$openclaw_state_dir" \
        --arg configPath "$openclaw_config_path" \
        --arg workspaceDir "$openclaw_workspace_dir" \
        --arg runtimeRoot "$openclaw_runtime_root" \
        --arg cli "$openclaw_cli_path" \
        --arg commonPath "$openclaw_management_common" \
        --arg watchdogPath "$openclaw_management_watchdog" \
        --arg packageJson "$openclaw_package_json" \
        --arg cliEntry "$openclaw_cli_entry" \
        --arg gatewayEntry "$openclaw_gateway_entry" '
        type == "object" and
        keys == ([
            "cliEntryPath", "cliEntrySha256", "cliPath", "commonPath", "commonSha256",
            "configPath", "gatewayEntryPath", "gatewayEntrySha256", "installMethod", "model",
            "nodePath", "nodeSha256", "nodeVersion", "packageJsonPath", "packageJsonSha256",
            "profile", "runtimeRoot", "runtimeTreeSha256", "schemaVersion", "stateDir", "version",
            "watchdogPath", "watchdogSha256", "workspaceDir", "wrapperPath", "wrapperSha256"
        ] | sort) and
        .schemaVersion == 4 and
        .version == $version and
        .profile == $profile and
        .model == $model and
        (.installMethod == "homebrew-node" or .installMethod == "official-user" or
         .installMethod == "adopted-homebrew-node") and
        .stateDir == $stateDir and
        .configPath == $configPath and
        .workspaceDir == $workspaceDir and
        .runtimeRoot == $runtimeRoot and
        .cliPath == $cli and
        .wrapperPath == $cli and
        .commonPath == $commonPath and
        .watchdogPath == $watchdogPath and
        .packageJsonPath == $packageJson and
        .cliEntryPath == $cliEntry and
        .gatewayEntryPath == $gatewayEntry and
        (.nodePath | type == "string" and startswith("/")) and
        (.nodeVersion | type == "string" and test("^v26\\.")) and
        (.nodeSha256 | type == "string" and test("^[a-f0-9]{64}$")) and
        (.runtimeTreeSha256 | type == "string" and test("^[a-f0-9]{64}$")) and
        (.wrapperSha256 | type == "string" and test("^[a-f0-9]{64}$")) and
        (.commonSha256 | type == "string" and test("^[a-f0-9]{64}$")) and
        (.watchdogSha256 | type == "string" and test("^[a-f0-9]{64}$")) and
        (.packageJsonSha256 | type == "string" and test("^[a-f0-9]{64}$")) and
        (.cliEntrySha256 | type == "string" and test("^[a-f0-9]{64}$")) and
        (.gatewayEntrySha256 | type == "string" and test("^[a-f0-9]{64}$"))
    ' <<< "$deployment_json" >/dev/null || openclaw_fail "deployment manifest is invalid"

    install_method="$(jq -r '.installMethod' <<< "$deployment_json")"
    openclaw_cli_path="$(jq -r '.cliPath' <<< "$deployment_json")"
    openclaw_node_path="$(jq -r '.nodePath' <<< "$deployment_json")"
    openclaw_wrapper_path="$(jq -r '.wrapperPath' <<< "$deployment_json")"

    if [[ "$install_method" == "official-user" ]]; then
        expected_node="$openclaw_runtime_root/tools/node-v$PERSONAL_EDGE_OPENCLAW_OFFICIAL_NODE_VERSION/bin/node"
    else
        expected_node="${PERSONAL_EDGE_OPENCLAW_NODE_BIN:-/opt/homebrew/opt/node/bin/node}"
    fi
    [[ "$openclaw_node_path" == "$expected_node" ]] ||
        openclaw_fail "deployment manifest contains an unexpected Node path"
    [[ -x "$openclaw_node_path" && ! -L "$openclaw_node_path" ]] ||
        openclaw_fail "deployment Node is missing or unsafe: $openclaw_node_path"
    openclaw_assert_private_file "$openclaw_cli_path" "managed OpenClaw wrapper"
    openclaw_assert_private_file "$openclaw_management_common" "managed OpenClaw common library"
    openclaw_assert_private_file "$openclaw_management_watchdog" "managed OpenClaw watchdog"
    openclaw_assert_owned_nonwritable_file "$openclaw_package_json" "OpenClaw package metadata"
    openclaw_assert_owned_nonwritable_file "$openclaw_cli_entry" "OpenClaw CLI entry"
    openclaw_assert_owned_nonwritable_file "$openclaw_gateway_entry" "OpenClaw Gateway entry"
    package_version="$(jq -r '.version' "$openclaw_package_json")"
    [[ "$package_version" == "$PERSONAL_EDGE_OPENCLAW_VERSION" ]] ||
        openclaw_fail_archived_relay_version "OpenClaw package metadata" "$package_version"
    [[ "$(openclaw_sha256 "$openclaw_package_json")" == "$(jq -r '.packageJsonSha256' <<< "$deployment_json")" ]] ||
        openclaw_fail "OpenClaw package metadata hash drifted"
    [[ "$(openclaw_sha256 "$openclaw_cli_entry")" == "$(jq -r '.cliEntrySha256' <<< "$deployment_json")" ]] ||
        openclaw_fail "OpenClaw CLI entry hash drifted"
    [[ "$(openclaw_sha256 "$openclaw_gateway_entry")" == "$(jq -r '.gatewayEntrySha256' <<< "$deployment_json")" ]] ||
        openclaw_fail "OpenClaw Gateway entry hash drifted"
    [[ "$(openclaw_sha256 "$openclaw_node_path")" == "$(jq -r '.nodeSha256' <<< "$deployment_json")" ]] ||
        openclaw_fail "deployment Node binary hash drifted"
    [[ "$(openclaw_sha256 "$openclaw_cli_path")" == "$(jq -r '.wrapperSha256' <<< "$deployment_json")" ]] ||
        openclaw_fail "managed OpenClaw wrapper hash drifted"
    [[ "$(openclaw_sha256 "$openclaw_management_common")" == "$(jq -r '.commonSha256' <<< "$deployment_json")" ]] ||
        openclaw_fail "managed OpenClaw common library hash drifted"
    [[ "$(openclaw_sha256 "$openclaw_management_watchdog")" == "$(jq -r '.watchdogSha256' <<< "$deployment_json")" ]] ||
        openclaw_fail "managed OpenClaw watchdog hash drifted"
    [[ "$(openclaw_runtime_tree_sha256)" == "$(jq -r '.runtimeTreeSha256' <<< "$deployment_json")" ]] ||
        openclaw_fail "OpenClaw runtime tree hash drifted"
    node_version="$("$openclaw_node_path" --version)"
    [[ "$node_version" == "$(jq -r '.nodeVersion' <<< "$deployment_json")" ]] ||
        openclaw_fail "deployment Node patch version drifted"
    node_major="${node_version#v}"
    node_major="${node_major%%.*}"
    [[ "$node_major" == "$PERSONAL_EDGE_OPENCLAW_NODE_MAJOR" ]] ||
        openclaw_fail "deployment Node is not major 26"
    openclaw_loaded_install_method="$install_method"
}

openclaw_run() {
    local node_dir

    [[ -x "$openclaw_cli_path" && ! -L "$openclaw_cli_path" ]] ||
        openclaw_fail "OpenClaw CLI wrapper is missing or unsafe: $openclaw_cli_path"
    if [[ -n "${openclaw_node_path:-}" ]]; then
        node_dir="$(dirname "$openclaw_node_path")"
    else
        node_dir="/opt/homebrew/opt/node/bin"
    fi
    env \
        PATH="$node_dir:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
        OPENCLAW_STATE_DIR="$openclaw_state_dir" \
        OPENCLAW_CONFIG_PATH="$openclaw_config_path" \
        "$openclaw_cli_path" "$@"
}

openclaw_extract_version() {
    printf '%s\n' "$1" | grep -Eo '[0-9]{4}\.[0-9]+\.[0-9]+' | head -n 1
}
