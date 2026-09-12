// Prepare a reviewed candidate only; installation and native recovery are separate.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstat, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

export const REVIEWED = Object.freeze({
    version: '2026.9.3',
    relativePath: 'dist/model-fallback-runner-DRMQDUbt.mjs',
    beforeSha256: '82ce89778d0663ec82fbb317cb38fc41240330b8ae16e88192dd2dea142f8c5b',
    dependencies: {
        'usage-UTE8_L_H.mjs': '5226a3914c42f6b3b7462fe61f92f42a60350a5e427572c1e2712874c1715b00',
        'order-C9kp98kf.mjs': 'ba6ded46760175806054562b52672f9836e18f11351e98607ccd25eefbd9262b',
        'model-fallback-attempt-CrIYd-9y.mjs': '3ae8806c11a10561a836f4d45cad4f815178c6464a3817c96856db06d9e43c87'
    }
});
export const sha256 = value => createHash('sha256').update(value).digest('hex');

const original = `\t\t\tif (!candidateHarnessAuth.skipsProviderAuthCooldown) {
\t\t\t\tconst orderedProfileIds = authRuntime.resolveAuthProfileOrder({
\t\t\t\t\tcfg: params.cfg,
\t\t\t\t\tstore: authStore,
\t\t\t\t\tprovider: candidate.provider,
\t\t\t\t\tforModel: candidate.model
\t\t\t\t});
\t\t\t\tcandidateAuthProfileIds = userLockedAuthProfileEligible && userLockedAuthProfileId ? [userLockedAuthProfileId, ...orderedProfileIds.filter((profileId) => profileId !== userLockedAuthProfileId)] : orderedProfileIds;
\t\t\t\tprofileIdsByCandidate.set(candidate, candidateAuthProfileIds);
\t\t\t\tauthRuntime.maybeReprobeWhamBlockedProfiles({
\t\t\t\t\tstore: authStore,
\t\t\t\t\tprofileIds: candidateAuthProfileIds,
\t\t\t\t\tagentDir: params.agentDir,
\t\t\t\t\tforModel: candidate.model
\t\t\t\t});
\t\t\t}`;

const replacement = `\t\t\t// Local fix: preserve scoped WHAM refresh for explicit Codex runtimes.
\t\t\tconst reprobeCodex = candidateHarnessAuth.skipsProviderAuthCooldown && resolveModelFallbackCandidateAgentRuntime({
\t\t\t\tcfg: params.cfg,
\t\t\t\tagentId: params.agentId,
\t\t\t\tsessionKey: params.sessionKey,
\t\t\t\tresolveAgentHarnessRuntimeOverride: () => candidateHarnessAuth.agentHarnessRuntimeOverride,
\t\t\t\t...candidate
\t\t\t}).runtime === "codex";
\t\t\tif (!candidateHarnessAuth.skipsProviderAuthCooldown || reprobeCodex) {
\t\t\t\tconst orderedProfileIds = authRuntime.resolveAuthProfileOrder({
\t\t\t\t\tcfg: params.cfg,
\t\t\t\t\tstore: authStore,
\t\t\t\t\tprovider: candidate.provider,
\t\t\t\t\tforModel: candidate.model
\t\t\t\t});
\t\t\t\tconst reprobeProfileIds = userLockedAuthProfileEligible && userLockedAuthProfileId ? [userLockedAuthProfileId, ...orderedProfileIds.filter((profileId) => profileId !== userLockedAuthProfileId)] : orderedProfileIds;
\t\t\t\tif (!candidateHarnessAuth.skipsProviderAuthCooldown) {
\t\t\t\t\tcandidateAuthProfileIds = reprobeProfileIds;
\t\t\t\t\tprofileIdsByCandidate.set(candidate, candidateAuthProfileIds);
\t\t\t\t}
\t\t\t\tauthRuntime.maybeReprobeWhamBlockedProfiles({
\t\t\t\t\tstore: authStore,
\t\t\t\t\tprofileIds: reprobeProfileIds,
\t\t\t\t\tagentDir: params.agentDir,
\t\t\t\t\tforModel: candidate.model
\t\t\t\t});
\t\t\t}`;

export function preparePatch(source) {
    assert.equal(sha256(source), REVIEWED.beforeSha256, 'Unreviewed runtime source; requalify after upgrade');
    assert.equal(source.split(original).length, 2, 'Ambiguous auth reprobe anchor');
    // Parse the replacement without importing or executing the installed runtime.
    new vm.Script(replacement);
    return source.replace(original, replacement);
}

export async function prepareFile(input, output) {
    assert(input && output, 'usage: node patch-auth-reprobe.mjs ORIGINAL CANDIDATE');
    assert.notEqual(resolve(input), resolve(output), 'Candidate must be separate from original');
    const info = await lstat(input);
    assert(info.isFile() && !info.isSymbolicLink(), 'Original must be a regular file');
    const candidate = preparePatch(await readFile(input, 'utf8'));
    // Exclusive creation rejects both preexisting files and output symlinks.
    await writeFile(output, candidate, { mode: 0o600, flag: 'wx' });
    return { version: REVIEWED.version, relativePath: REVIEWED.relativePath,
        beforeSha256: REVIEWED.beforeSha256, afterSha256: sha256(candidate),
        checks: 3, inferenceRequests: 0 };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    assert.equal(process.argv.length, 4, 'usage: node patch-auth-reprobe.mjs ORIGINAL CANDIDATE');
    console.log(JSON.stringify(await prepareFile(...process.argv.slice(2))));
}
