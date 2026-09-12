// Version-pinned compatibility repair; prepares candidates without changing the installation.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { verifyGlmThinking } from './test-glm-thinking.mjs';

const sha256 = value => createHash('sha256').update(value).digest('hex');
const [packageArgument, outputArgument] = process.argv.slice(2);
assert(packageArgument && outputArgument, 'usage: node patch-glm-thinking.mjs PACKAGE OUTPUT_DIRECTORY');
const packageRoot = resolve(packageArgument);
const outputRoot = resolve(outputArgument);
assert.notEqual(packageRoot, outputRoot, 'Prepare candidates outside the live package');
const version = JSON.parse(await readFile(`${packageRoot}/package.json`, 'utf8')).version;
const specs = JSON.parse(await readFile(new URL('./runtime-patch-specs.json', import.meta.url), 'utf8'));
assert(specs[version], 'Unqualified runtime version');
const sources = specs[version].thinking.map(file => [file.path, file.before]);
const original = await Promise.all(sources.map(async ([relativePath, expected]) => {
    const text = await readFile(`${packageRoot}/${relativePath}`, 'utf8');
    assert.equal(sha256(text), expected, `Unreviewed source: ${relativePath}; requalify after upgrade`);
    return text;
}));
function replaceOnce(text, anchor, replacement) {
    assert.equal(text.split(anchor).length, 2, `Missing or ambiguous anchor: ${anchor}`);
    return text.replace(anchor, replacement);
}
let policy = replaceOnce(original[0],
    'import { t as isOpenRouterDeepSeekV4ModelId }',
    'import { i as normalizeOpenRouterModelFamilyId, t as isOpenRouterDeepSeekV4ModelId }');
policy = replaceOnce(policy,
    'function resolveOpenRouterThinkingProfile(modelId) {',
    `// GLM 5.3 Flash supports native max effort. Retain existing compatibility levels/defaults.
const OPENROUTER_GLM53_FLASH_THINKING_PROFILE = {
\tlevels: ["off", "minimal", "low", "medium", "high", "max"].map((id) => ({ id }))
};
function resolveOpenRouterThinkingProfile(modelId) {
\tif (normalizeOpenRouterModelFamilyId(modelId) === "z-ai/glm-5.3-flash") return OPENROUTER_GLM53_FLASH_THINKING_PROFILE;`);
let stream = replaceOnce(original[1],
    'function wrapOpenRouterProviderStream(ctx) {',
    `// The generic AI adapter clamps max to high for this catalog entry. Restore the
// selected native effort after generic payload construction, only on this route.
function createOpenRouterGlm53FlashMaxWrapper(baseStreamFn, thinkingLevel) {
\tif (thinkingLevel !== "max") return baseStreamFn;
\treturn createPayloadPatchStreamWrapper(baseStreamFn, ({ payload }) => {
\t\tconst reasoning = { ...asNonArrayRecord(payload.reasoning) };
\t\tdelete reasoning.max_tokens;
\t\tif (reasoning.enabled === false) reasoning.enabled = true;
\t\treasoning.effort = "max";
\t\tpayload.reasoning = reasoning;
\t\tdelete payload.reasoning_effort;
\t}, { shouldPatch: ({ model }) => shouldPatchOpenRouterRoutingPayload(model) && normalizeOpenRouterModelFamilyId(model.id) === "z-ai/glm-5.3-flash" });
}
function wrapOpenRouterProviderStream(ctx) {`);
stream = replaceOnce(stream,
    'createOpenRouterAuthHeaderWrapper, createOpenRouterAnthropicPrefillWrapper);',
    'createOpenRouterAuthHeaderWrapper, createOpenRouterAnthropicPrefillWrapper, (streamFn) => createOpenRouterGlm53FlashMaxWrapper(streamFn, ctx.thinkingLevel));');

async function loadCandidate(source, relativePath) {
    const base = pathToFileURL(`${packageRoot}/${relativePath}`);
    const executable = source.replace(/\b(from|import) "(\.\/[^\"]+)"/g,
        (_, keyword, specifier) => `${keyword} "${new URL(specifier, base).href}"`);
    return import(`data:text/javascript;base64,${Buffer.from(executable).toString('base64')}`);
}
const checks = await verifyGlmThinking(packageRoot, {
    policy: await loadCandidate(policy, sources[0][0]),
    stream: await loadCandidate(stream, sources[1][0]),
});
await mkdir(outputRoot, { mode: 0o700, recursive: true });
const files = [];
for (const [index, candidate] of [policy, stream].entries()) {
    const [relativePath, beforeSha256] = sources[index];
    await mkdir(resolve(outputRoot, 'dist'), { mode: 0o700, recursive: true });
    await writeFile(resolve(outputRoot, relativePath), candidate, { mode: 0o600, flag: 'wx' });
    files.push({ relativePath, beforeSha256, afterSha256: sha256(candidate) });
}
const receipt = { schemaVersion: 1, version, files, checks, inferenceRequests: 0 };
await writeFile(resolve(outputRoot, 'glm-thinking-patch.json'), JSON.stringify(receipt, null, 2) + '\n',
    { mode: 0o600, flag: 'wx' });
console.log(JSON.stringify(receipt));
