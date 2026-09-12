// Offline tests against reviewed runtime bytes; no installed module imports.
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import vm from 'node:vm';
import { prepareFile, preparePatch, REVIEWED, sha256 } from './patch-auth-reprobe.mjs';

const [input, output] = process.argv.slice(2);
assert.equal(process.argv.length, 4, 'usage: node test-auth-reprobe.mjs ORIGINAL CANDIDATE');
const source = await readFile(input, 'utf8');
const candidateSource = await readFile(output, 'utf8');
assert.equal(candidateSource, preparePatch(source), 'Candidate bytes differ from reviewed transformation');
const dependencies = {};
for (const [name, expected] of Object.entries(REVIEWED.dependencies)) {
    const content = await readFile(join(dirname(input), name), 'utf8');
    assert.equal(sha256(content), expected, 'Native dependency changed: ' + name);
    dependencies[name] = content;
}
const attemptSource = dependencies['model-fallback-attempt-CrIYd-9y.mjs'];
const usageSource = dependencies['usage-UTE8_L_H.mjs'];
const orderSource = dependencies['order-C9kp98kf.mjs'];
function section(text, start, end) {
    assert.equal(text.split(start).length, 2, 'Unique start required: ' + start);
    const offset = text.indexOf(start);
    const finish = text.indexOf(end, offset + start.length);
    assert(finish > offset, 'End required: ' + end);
    return text.slice(offset, finish);
}
let checks = 0;
async function check(name, body) {
    await body();
    checks++;
    console.log('ok ' + checks + ' - ' + name);
}
const plain = value => JSON.parse(JSON.stringify(value));

// Exercise the real runtime resolver/precheck and altered runner block. Fake
// auth adapters record arguments; they cannot load a credential or persist state.
async function observeRunner(text, options = {}) {
    const observations = { probes: [], orders: [], eligible: [], overrideCalls: 0 };
    const candidate = { provider: options.provider ?? 'openai', model: 'synthetic-model' };
    const cfg = { synthetic: true };
    const store = { synthetic: true };
    const params = { cfg, agentId: 'synthetic-agent', sessionKey: 'synthetic-session',
        agentDir: '/synthetic/agent',
        resolveAgentHarnessRuntimeOverride: () => {
            observations.overrideCalls++;
            return options.override;
        } };
    const context = { candidate, params, authStore: options.noStore ? null : store,
        userLockedAuthProfileId: options.locked,
        profileIdsByCandidate: new Map(),
        normalizeOptionalString: value => value?.trim(),
        normalizeOptionalAgentRuntimeId: value => value,
        isDefaultAgentRuntimeId: value => value === 'auto',
        resolveAgentHarnessPolicy: () => ({ runtime: options.runtime ?? 'codex',
            runtimeSource: options.runtimeSource ?? 'model' }),
        isCliProvider: () => false, isCliRuntimeAlias: () => false,
        getRegisteredAgentHarness: () => true,
        authRuntime: options.noRuntime ? null : {
            resolveAuthProfileEligibility: args => {
                assert.equal(args.store, store); assert.equal(args.cfg, cfg);
                observations.eligible.push({ provider: args.provider, profileId: args.profileId });
                return { eligible: options.lockEligible !== false };
            },
            resolveAuthProfileOrder: args => {
                assert.equal(args.store, store); assert.equal(args.cfg, cfg);
                observations.orders.push({ provider: args.provider, forModel: args.forModel });
                return options.order ?? ['selected', 'alternate'];
            },
            maybeReprobeWhamBlockedProfiles: args => {
                assert.equal(args.store, store);
                observations.probes.push({ profileIds: Array.from(args.profileIds),
                    agentDir: args.agentDir, forModel: args.forModel });
            }
        } };
    const resolver = section(attemptSource, 'function isCliAgentRuntime(', '\nfunction resolveCandidateAttemptError(');
    const block = section(text, '\t\tlet candidateAuthProfileIds;', '\n\t\tconst candidateAuthScope =');
    const result = await vm.runInNewContext(resolver + `\n(async () => {
        const candidateHarnessAuth = await resolveModelFallbackCandidateHarnessAuthPrecheck({ ...params, ...candidate });
        ${block}
        return { candidateAuthProfileIds, userLockedAuthProfileEligible,
            mapped: profileIdsByCandidate.get(candidate), skips: candidateHarnessAuth.skipsProviderAuthCooldown };
    })()`, context, { timeout: 1000 });
    return plain({ ...observations, ...result });
}

await check('explicit Codex starts native refresh while preserving its cooldown exemption and auth scope', async () => {
    const old = await observeRunner(source);
    const next = await observeRunner(candidateSource);
    assert.equal(old.probes.length, 0);
    assert.deepEqual(next.probes, [{ profileIds: ['selected', 'alternate'],
        agentDir: '/synthetic/agent', forModel: 'synthetic-model' }]);
    assert.equal(next.skips, true);
    assert.equal(next.candidateAuthProfileIds, undefined);
    assert.equal(next.mapped, undefined);
    assert.equal(next.overrideCalls, 1, 'Do not resolve a live override twice');
});
await check('implicit Codex and OpenClaw keep their original auth behavior', async () => {
    for (const options of [{ runtimeSource: 'implicit' }, { runtime: 'openclaw' }]) {
        assert.deepEqual(await observeRunner(candidateSource, options), await observeRunner(source, options));
    }
});
await check('other registered harnesses remain untouched, including runtime overrides', async () => {
    for (const options of [{ runtime: 'custom' }, { override: 'custom' }]) {
        assert.deepEqual(await observeRunner(candidateSource, options), await observeRunner(source, options));
    }
});
await check('eligible locked profile is first with no duplicate; provider/model/agent scope is preserved', async () => {
    const result = await observeRunner(candidateSource, { locked: 'selected', order: ['alternate', 'selected'] });
    assert.deepEqual(result.eligible, [{ provider: 'openai', profileId: 'selected' }]);
    assert.deepEqual(result.orders, [{ provider: 'openai', forModel: 'synthetic-model' }]);
    assert.deepEqual(result.probes[0].profileIds, ['selected', 'alternate']);
    const other = await observeRunner(candidateSource, { provider: 'other', locked: 'wrong-provider',
        lockEligible: false, order: ['other-selected'] });
    assert.deepEqual(other.orders, [{ provider: 'other', forModel: 'synthetic-model' }]);
    assert.deepEqual(other.probes[0].profileIds, ['other-selected']);
});
await check('ordinary auth profile order and map remain identical with and without an eligible pin', async () => {
    for (const lockEligible of [true, false]) {
        const options = { runtime: 'openclaw', locked: 'selected', lockEligible, order: ['alternate', 'selected'] };
        assert.deepEqual(await observeRunner(candidateSource, options), await observeRunner(source, options));
    }
});
await check('absent auth runtime or store cannot trigger discovery or refresh', async () => {
    for (const options of [{ noRuntime: true }, { noStore: true }]) {
        const result = await observeRunner(candidateSource, options);
        assert.equal(result.orders.length, 0); assert.equal(result.probes.length, 0);
    }
});

// Use native guards and native WHAM response classification, with a synthetic
// clock and fetch. The source module is read as bytes, never imported.
const now = 1_900_000_000_000;
const interval = 45 * 60 * 1000;
const fixture = () => ({ profiles: { selected: { type: 'oauth', provider: 'openai',
    access: 'synthetic-token', expires: now + 86_400_000 } },
    usageStats: { selected: { blockedSource: 'wham', blockedReason: 'subscription_limit',
        blockedUntil: now + 86_400_000, lastProbeAt: now - interval } } });
const nativePrefix = section(usageSource, 'const WHAM_USAGE_URL =', '\nasync function claimWhamHalfOpenReprobe(');
const activeWindow = section(orderSource, 'function isActiveUnusableWindow(', '\nfunction isBlockedWindowActiveForModel(');
function nativeContext(extra = {}) {
    return { Date: { now: () => now },
        normalizeProviderId: value => value.trim().toLowerCase(),
        isFutureDateTimestampMs: value => Number.isFinite(value) && value > now,
        asDateTimestampMs: value => Number.isFinite(value) ? value : undefined,
        resolveExpiresAtMsFromDurationMs: (value, options) => options.nowMs + value,
        resolveExpiresAtMsFromEpochSeconds: value => value * 1000,
        positiveSecondsToSafeMilliseconds: value => value * 1000,
        AbortController, setTimeout: () => 0, clearTimeout: () => {},
        process: { env: {} }, resolveProviderRequestHeaders: args => args.defaultHeaders,
        readProviderJsonResponse: async response => response.body,
        cancelUnreadResponseBody: async () => {}, authProfileUsageLog: { warn: () => {} },
        ...extra };
}
function canProbe(store) {
    return vm.runInNewContext(activeWindow + nativePrefix + '\nshouldHalfOpenProbeWhamBlock(params)',
        nativeContext({ params: { store, profileId: 'selected', forModel: 'synthetic-model', now } }),
        { timeout: 1000 });
}
await check('native 45-minute throttle and long-block boundary are unchanged', () => {
    assert.equal(canProbe(fixture()), true);
    const recent = fixture(); recent.usageStats.selected.lastProbeAt++;
    assert.equal(canProbe(recent), false);
    const short = fixture(); short.usageStats.selected.blockedUntil = now + interval;
    assert.equal(canProbe(short), false);
});
await check('native auth, active cooldown, disable and model-scope guards still reject refresh', () => {
    const changes = [s => { s.profiles.selected.type = 'api_key'; },
        s => { s.profiles.selected.provider = 'other'; },
        s => { s.profiles.selected.expires = now - 1; },
        s => { s.usageStats.selected.cooldownUntil = now + 1; },
        s => { s.usageStats.selected.disabledUntil = now + 1; },
        s => { s.usageStats.selected.blockedSource = 'provider'; },
        s => { s.usageStats.selected.blockedReason = 'billing'; },
        s => { Object.assign(s.usageStats.selected, { blockedScope: 'model', blockedModel: 'other-model' }); }];
    for (const change of changes) {
        const store = fixture(); change(store); assert.equal(canProbe(store), false);
    }
});
await check('actual WHAM limit remains blocked; available and failed responses retain native classification', async () => {
    for (const [body, expected] of [
        [{ rate_limit: { limit_reached: true, primary_window: { reset_after_seconds: 7200 } } }, 'blocked'],
        [{ rate_limit: { limit_reached: false } }, 'available'],
        [{}, 'unknown']
    ]) {
        let requests = 0;
        const result = await vm.runInNewContext(activeWindow + nativePrefix + '\nprobeWhamForCooldown(store, "selected")',
            nativeContext({ store: fixture(), fetch: async (url, options) => {
                requests++;
                assert.equal(url, 'https://chatgpt.com/backend-api/wham/usage');
                assert.equal(options.method, 'GET');
                return { ok: true, body };
            } }), { timeout: 1000 });
        assert.equal(requests, 1);
        assert.equal(result.available === true, expected === 'available');
        assert.equal(Boolean(result.blockedUntil), expected === 'blocked');
        if (expected === 'blocked') assert.equal(result.blockedUntil, now + 7_200_000);
    }
});
await check('native generation comparison rejects a newer failure or changed block', () => {
    const stats = { blockedUntil: now + 86_400_000, blockedModel: 'synthetic-model', blockedScope: 'model',
        lastFailureAt: now - 100, failureCounts: { rate_limit: 1 } };
    const generation = { ...stats, rateLimitFailureCount: 1 };
    const compare = changed => vm.runInNewContext(nativePrefix + '\nmatchesWhamBlockGeneration(stats, generation)',
        { stats: changed, generation }, { timeout: 1000 });
    assert.equal(compare(stats), true);
    assert.equal(compare({ ...stats, lastFailureAt: now }), false);
    assert.equal(compare({ ...stats, blockedUntil: now + 99 }), false);
    assert.equal(compare({ ...stats, failureCounts: { rate_limit: 2 } }), false);
});
await check('unreviewed, already patched, and tampered source bytes are refused', () => {
    for (const text of [source + '\n', candidateSource, source.replace('maybeReprobeWhamBlockedProfiles', 'disabledReprobe')]) {
        assert.throws(() => preparePatch(text), /Unreviewed runtime source/);
    }
});
await check('candidate creation is exclusive and cannot overwrite input or follow a file symlink', async () => {
    const temp = await mkdtemp(join(tmpdir(), 'auth-reprobe-test-'));
    try {
        const original = join(temp, 'original.mjs');
        const proposed = join(temp, 'candidate.mjs');
        await writeFile(original, source);
        const receipt = await prepareFile(original, proposed);
        assert.equal(receipt.afterSha256, sha256(candidateSource));
        await assert.rejects(() => prepareFile(original, proposed), /EEXIST/);
        await assert.rejects(() => prepareFile(original, original), /separate/);
        const link = join(temp, 'link.mjs');
        await symlink(original, link);
        await assert.rejects(() => prepareFile(link, join(temp, 'unused.mjs')), /regular file/);
        await assert.rejects(() => prepareFile(original, link), /EEXIST/);
        assert.equal(await readFile(original, 'utf8'), source);
    } finally { await rm(temp, { recursive: true, force: true }); }
});
console.log(JSON.stringify({ checks, version: REVIEWED.version,
    beforeSha256: sha256(source), afterSha256: sha256(candidateSource),
    nativeUsageSha256: sha256(usageSource), networkRequests: 0, inferenceRequests: 0 }));
