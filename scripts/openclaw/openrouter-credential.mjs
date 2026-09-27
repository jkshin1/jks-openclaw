import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { homedir } from 'node:os';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

export async function configuredOpenRouterCredential() {
    return configuredCredential('OPENROUTER_API_KEY');
}

export async function configuredGatewayCredential() {
    return configuredCredential('OPENCLAW_GATEWAY_TOKEN');
}

// Runtime versions whose resolver layout was qualified alongside the runtime patches.
async function supportedVersions() {
    const specs = JSON.parse(await readFile(new URL('./runtime-patch-specs.json', import.meta.url), 'utf8'));
    return new Set(Object.keys(specs));
}

export async function pinnedResolverPath(packageRoot, supported) {
    const packageJson = JSON.parse(await readFile(path.join(packageRoot, 'package.json'), 'utf8'));
    assert(supported.has(packageJson.version), `unsupported OpenClaw runtime ${packageJson.version}`);
    const dist = path.join(packageRoot, 'dist');
    const matches = [];
    for (const name of (await readdir(dist)).filter((entry) => /^resolve-[A-Za-z0-9_-]+\.m?js$/.test(entry))) {
        const source = await readFile(path.join(dist, name), 'utf8');
        if (/export \{ isMissingSecretRefResolutionError,/.test(source) && source.includes('resolveSecretRefString')) {
            matches.push(path.join(dist, name));
        }
    }
    // Two candidates means the layout changed in a way this helper was not qualified for.
    assert.equal(matches.length, 1, 'expected exactly one pinned secret resolver');
    return matches[0];
}

async function configuredCredential(name) {
    const profile = path.join(homedir(), '.openclaw-personaledge');
    process.env.OPENCLAW_STATE_DIR = profile;
    process.env.OPENCLAW_PROFILE = 'personaledge';
    // The install directory name predates in-place runtime upgrades; the package version is authoritative.
    const packageRoot = path.join(homedir(), '.local/openclaw-2026.8.1/lib/node_modules/openclaw');
    const resolverPath = await pinnedResolverPath(packageRoot, await supportedVersions());
    const config = JSON.parse(await readFile(path.join(profile, 'openclaw.json'), 'utf8'));
    const reference = name === 'OPENROUTER_API_KEY'
        ? config.models.providers.openrouter.apiKey : config.gateway.auth.token;
    assert.deepEqual(reference, { source: 'store', provider: 'default', id: name });
    const resolver = await import(pathToFileURL(resolverPath).href);
    // The pinned resolver reads only existing state SQLite. Never persist/print the value.
    return resolver.resolveSecretRefString(reference, { config, env: process.env });
}
