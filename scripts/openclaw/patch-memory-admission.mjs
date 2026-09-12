// Close the unclassified-session gap when all conversation types are excluded.
// Prepare a version-pinned candidate; installation is a separate operation.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import vm from 'node:vm';

const sha256 = text => createHash('sha256').update(text).digest('hex');
const [input, output] = process.argv.slice(2);
assert(input && output, 'usage: node patch-memory-admission.mjs ORIGINAL CANDIDATE');
const source = await readFile(input, 'utf8');
const specs = JSON.parse(await readFile(new URL('./runtime-patch-specs.json', import.meta.url), 'utf8'));
const [version, release] = Object.entries(specs).find(([, value]) => value.memory.before === sha256(source)) ?? [];
assert(release, 'Unreviewed runtime source; requalify after upgrade');
const spec = release.memory;
const originalSha256 = spec.before;
assert.equal(sha256(source), originalSha256, 'Unreviewed runtime source; requalify after upgrade');
const anchor = 'function sessionExclusionReason(source, policy, forgottenSessionIds) {';
assert.equal(source.split(anchor).length, 2, 'Ambiguous admission anchor');
const candidate = source.replace(anchor, anchor + '\n' +
    '\t// Local privacy policy: excluding every chat type also excludes missing metadata.\n' +
    '\tif (["direct", "group", "channel"].every((type) => policy?.chatTypes?.includes(type))) return "all-session-chat-types";');

// Execute the actual original/candidate function with deterministic metadata fixtures.
function evaluate(text, metadata, sourceOverride, policy, forgotten = new Set()) {
    const fn = text.slice(text.indexOf(anchor), text.indexOf('\nfunction sessionIngestionStateKeyFromCorpus'));
    const context = { loadMemorySessionMetadata: () => metadata, listMemorySessionTombstones: () => [],
        source: sourceOverride, policy, forgotten };
    return vm.runInNewContext(fn + '\nsessionExclusionReason(source, policy, forgotten)', context);
}
const session = { sessionOrigin: { agentId: 'main', sessionId: 'synthetic' }, buildOptions: {} };
const all = { chatTypes: ['direct', 'group', 'channel'], channels: [], hookExternalContentSources: [] };
assert.equal(evaluate(source, { chatType: null }, session, all), undefined);
let checks = 1;
for (const metadata of [undefined, {}, { chatType: null },
    ...['direct', 'group', 'channel'].map(chatType => ({ chatType }))]) {
    assert.equal(evaluate(candidate, metadata, session, all), 'all-session-chat-types');
    checks++;
}
assert.equal(evaluate(candidate, undefined, {}, all), 'all-session-chat-types');
checks++;
const partial = { ...all, chatTypes: ['direct'] };
for (const policy of [undefined, partial]) {
    for (const metadata of [undefined, { chatType: 'direct' }, { chatType: 'group' }]) {
        for (const forgotten of [new Set(), new Set(['synthetic'])]) {
            assert.equal(evaluate(candidate, metadata, session, policy, forgotten),
                evaluate(source, metadata, session, policy, forgotten));
            checks++;
        }
    }
}
await writeFile(output, candidate, { mode: 0o600, flag: 'wx' });
console.log(JSON.stringify({ version,
    relativePath: spec.path, beforeSha256: originalSha256,
    afterSha256: sha256(candidate), checks, inferenceRequests: 0 }));
