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

async function configuredCredential(name) {
    const profile = path.join(homedir(), '.openclaw-personaledge');
    process.env.OPENCLAW_STATE_DIR = profile;
    process.env.OPENCLAW_PROFILE = 'personaledge';
    const packageRoot = path.join(homedir(), '.local/openclaw-2026.8.1/lib/node_modules/openclaw');
    const packageJson = JSON.parse(await readFile(path.join(packageRoot, 'package.json'), 'utf8'));
    assert.equal(packageJson.version, '2026.8.1');
    const config = JSON.parse(await readFile(path.join(profile, 'openclaw.json'), 'utf8'));
    const reference = name === 'OPENROUTER_API_KEY'
        ? config.models.providers.openrouter.apiKey : config.gateway.auth.token;
    assert.deepEqual(reference, { source: 'store', provider: 'default', id: name });
    const dist = path.join(packageRoot, 'dist');
    const candidates = (await readdir(dist)).filter((name) => /^resolve-[A-Za-z0-9_-]+\.js$/.test(name));
    for (const name of candidates) {
        const source = await readFile(path.join(dist, name), 'utf8');
        if (/export \{ isMissingSecretRefResolutionError,/.test(source) && source.includes('resolveSecretRefString')) {
            const resolver = await import(pathToFileURL(path.join(dist, name)).href);
            // The pinned resolver reads only existing state SQLite. Never persist/print the value.
            return resolver.resolveSecretRefString(reference,
                { config, env: process.env });
        }
    }
    throw new Error('Pinned resolver not found');
}
