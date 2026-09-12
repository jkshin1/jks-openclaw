// Prepare and verify the GLM/OpenRouter compatibility fix for OpenClaw 2026.9.2.
// Catalog-owned routes ignore config compat overrides in this release.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const sha256 = text => createHash('sha256').update(text).digest('hex');
const [input, output] = process.argv.slice(2);
assert(input && output, 'usage: node patch-glm-token-field.mjs ORIGINAL CANDIDATE');
const source = await readFile(input, 'utf8');
const specs = JSON.parse(await readFile(new URL('./runtime-patch-specs.json', import.meta.url), 'utf8'));
const [version, release] = Object.entries(specs).find(([, value]) => value.token.before === sha256(source)) ?? [];
assert(release, 'Unreviewed runtime source; requalify after upgrade');
const spec = release.token;
const originalSha256 = spec.before;
assert.equal(sha256(source), originalSha256, 'Unreviewed runtime source; requalify after upgrade');
const before = 'const usesMaxTokens = endpointClass === "chutes-native"';
assert.equal(source.split(before).length, 2, 'Ambiguous token-field anchor');
const candidate = source.replace(before,
    'const usesMaxTokens = (isOpenRouterLike && modelId === "z-ai/glm-5.3-flash") || endpointClass === "chutes-native"');
const executable = candidate.replace(/from "(\.\/[^\"]+)"/g,
    (_, specifier) => `from "${new URL(specifier, pathToFileURL(input)).href}"`);
const patched = await import(`data:text/javascript;base64,${Buffer.from(executable).toString('base64')}`);
const original = await import(pathToFileURL(input).href);
const capabilities = () => ({ endpointClass: 'openrouter' });
const glm = { id: 'z-ai/glm-5.3-flash', provider: 'openrouter', baseUrl: 'https://openrouter.ai/api/v1' };
assert.equal(original[spec.export](glm, capabilities).maxTokensField, 'max_completion_tokens');
assert.equal(patched[spec.export](glm, capabilities).maxTokensField, 'max_tokens');
for (const id of ['z-ai/glm-5.3', 'z-ai/glm-5.3-flash-other', 'openai/gpt-4o']) {
    assert.deepEqual(patched[spec.export]({ ...glm, id }, capabilities), original[spec.export]({ ...glm, id }, capabilities));
}
assert.equal(patched[spec.export]({ ...glm, compat: { maxTokensField: 'max_completion_tokens' } },
    capabilities).maxTokensField, 'max_completion_tokens');
assert.deepEqual(patched[spec.export]({ ...glm, provider: 'openai' }, () => ({ endpointClass: 'openai-public' })),
    original[spec.export]({ ...glm, provider: 'openai' }, () => ({ endpointClass: 'openai-public' })));
await writeFile(output, candidate, { mode: 0o600, flag: 'wx' });
console.log(JSON.stringify({ version, relativePath: spec.path, beforeSha256: originalSha256,
    afterSha256: sha256(candidate), checks: 7, inferenceRequests: 0 }));
