import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

export const originalSha256 = 'd76487f151510c8d36cf650803c007b5f350e90171624e72d443ccd21fe2dea5';
const before = 'const usesMaxTokens = endpointClass === "chutes-native"';
const after = 'const usesMaxTokens = (isOpenRouterLike && modelId === "z-ai/glm-5.3-flash") || endpointClass === "chutes-native"';
export const sha256 = (text) => createHash('sha256').update(text).digest('hex');
export function patchedSource(original) {
    assert.equal(sha256(original), originalSha256, 'Unreviewed runtime source');
    assert.equal(original.split(before).length, 2, 'Ambiguous token-field patch');
    return original.replace(before, after);
}

export async function verifyCompatibility(originalPath) {
    const source = await readFile(originalPath, 'utf8');
    const candidate = patchedSource(source);
    // Resolve sibling imports back to the pinned source tree; no installed file is modified.
    const executable = candidate.replace(/from "(\.\/[^"]+)"/g,
        (_, specifier) => `from "${new URL(specifier, pathToFileURL(originalPath)).href}"`);
    const patched = await import(`data:text/javascript;base64,${Buffer.from(executable).toString('base64')}`);
    const original = await import(pathToFileURL(originalPath).href);
    const resolveCapabilities = () => ({ endpointClass: 'openrouter' });
    const glm = { id: 'z-ai/glm-5.3-flash', provider: 'openrouter', baseUrl: 'https://openrouter.ai/api/v1' };
    assert.equal(original.b(glm, resolveCapabilities).maxTokensField, 'max_completion_tokens');
    assert.equal(patched.b(glm, resolveCapabilities).maxTokensField, 'max_tokens');
    for (const id of ['z-ai/glm-5.3', 'z-ai/glm-5.3-flash-other', 'openai/gpt-4o']) {
        const model = { ...glm, id };
        assert.deepEqual(patched.b(model, resolveCapabilities), original.b(model, resolveCapabilities));
    }
    assert.equal(patched.b({ ...glm, compat: { maxTokensField: 'max_completion_tokens' } },
        resolveCapabilities).maxTokensField, 'max_completion_tokens');
    const native = { ...glm, provider: 'openai' };
    assert.deepEqual(patched.b(native, () => ({ endpointClass: 'openai-public' })),
        original.b(native, () => ({ endpointClass: 'openai-public' })));
    return { sourceSha256: sha256(source), patchedSha256: sha256(candidate), checks: 7, inferenceRequests: 0 };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const [mode, input, output] = process.argv.slice(2);
    assert(['--verify', '--stage'].includes(mode) && input && (mode !== '--stage' || output));
    const verified = await verifyCompatibility(input);
    if (mode === '--stage') await writeFile(output, patchedSource(await readFile(input, 'utf8')), { mode: 0o600, flag: 'wx' });
    console.log(JSON.stringify(verified));
}
