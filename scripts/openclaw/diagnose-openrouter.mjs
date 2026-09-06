#!/usr/bin/env node
// Fixed GET-only diagnostic. Never calls an inference route or prints credential/account data.
import assert from 'node:assert/strict';
import { configuredOpenRouterCredential } from './openrouter-credential.mjs';

const authenticated = process.argv.slice(2).includes('--account');
if (process.argv.slice(2).some((arg) => arg !== '--account')) {
    throw new Error('Usage: diagnose-openrouter.mjs [--account]');
}
const modelId = 'z-ai/glm-5.3-flash';
const allowedPaths = new Set([
    `/api/v1/models/${modelId}/endpoints`, '/api/v1/endpoints/zdr', '/api/v1/models/user',
]);
async function getJson(route, credential) {
    assert(allowedPaths.has(route));
    const response = await fetch(`https://openrouter.ai${route}`, {
        method: 'GET', redirect: 'error', signal: AbortSignal.timeout(25000),
        headers: credential ? { Authorization: `Bearer ${credential}` } : {},
    });
    if (!response.ok) return { status: response.status, body: null };
    let bytes = 0;
    const chunks = [];
    for await (const chunk of response.body) {
        bytes += chunk.byteLength;
        assert(bytes <= 8 * 1024 * 1024, 'Metadata response too large');
        chunks.push(chunk);
    }
    return { status: response.status, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) };
}

try {
    const endpoints = await getJson(`/api/v1/models/${modelId}/endpoints`);
    const routes = endpoints.body?.data?.endpoints ?? [];
    const zdr = await getJson('/api/v1/endpoints/zdr');
    const zdrRoutes = (zdr.body?.data ?? []).filter((entry) =>
        entry.model_id === modelId || entry.model?.id === modelId || entry.model === modelId);
    const report = {
        schemaVersion: 1, observedAt: new Date().toISOString(), modelId, inferenceRequests: 0,
        publicStatus: endpoints.status, zdrStatus: zdr.status,
        publicEndpointCount: routes.length, zdrEndpointCount: zdrRoutes.length,
        parameterSupport: ['max_tokens', 'max_completion_tokens', 'reasoning'].map((parameter) => ({
            parameter, endpointCount: routes.filter((entry) => entry.supported_parameters?.includes(parameter)).length,
        })),
        routes: routes.map((entry) => ({
            tag: entry.tag, status: entry.status, maxCompletionTokens: entry.max_completion_tokens,
        })),
        account: { checked: false },
        limits: ['Catalog metadata does not prove inference routing or billing.',
            'Unlisted aliases may be normalized by the server; mismatch alone does not establish 404 causation.'],
    };
    if (authenticated) {
        const credential = await configuredOpenRouterCredential();
        const account = await getJson('/api/v1/models/user', credential);
        const row = account.body?.data?.find((entry) => entry.id === modelId);
        report.account = { checked: true, httpStatus: account.status,
            modelVisible: account.body ? Boolean(row) : null,
            supportedParameters: row?.supported_parameters ?? null };
    }
    process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
} catch {
    process.stderr.write('OpenRouter metadata diagnostic failed; no inference request was sent.\n');
    process.exitCode = 1;
}
