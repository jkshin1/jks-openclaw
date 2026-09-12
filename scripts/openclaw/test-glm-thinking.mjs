// Exercise the installed provider/menu code and actual AI request builder without network access.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export async function verifyGlmThinking(packageRoot, candidates = {}) {
    const version = JSON.parse(await readFile(`${packageRoot}/package.json`, 'utf8')).version;
    const specs = JSON.parse(await readFile(new URL('./runtime-patch-specs.json', import.meta.url), 'utf8'));
    const spec = specs[version];
    assert(spec, 'Unqualified runtime version');
    let checks = 0;
    let networkAttempts = 0;
    const blockFetch = async () => {
        networkAttempts += 1;
        throw new Error('OFFLINE_NETWORK_BLOCK');
    };
    globalThis.fetch = blockFetch;
    const load = relativePath => import(pathToFileURL(`${packageRoot}/${relativePath}`).href);
    const { t: configureAiTransportHost } = await load(spec.test.host);
    configureAiTransportHost({ buildModelFetch: () => blockFetch });
    const { r: streamSimple } = await load(spec.test.completions);
    const { c: resolveProfile } = await load(spec.test.thinking);
    const policy = candidates.policy ?? await load(spec.thinking[0].path);
    const stream = candidates.stream ?? await load(spec.thinking[1].path);
    const originalPolicy = await load(spec.thinking[0].path);
    const originalStream = await load(spec.thinking[1].path);
    const model = {
        id: 'z-ai/glm-5.3-flash', name: 'GLM 5.3 Flash', provider: 'openrouter',
        api: 'openai-completions', baseUrl: 'https://openrouter.ai/api/v1', reasoning: true,
        input: ['text'], maxTokens: 131072, contextWindow: 1048576,
        compat: { maxTokensField: 'max_tokens' },
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    };
    const equal = (actual, expected) => { assert.deepEqual(actual, expected); checks += 1; };
    const profile = resolveProfile({
        provider: model.provider, model: model.id, catalog: [model], agentRuntime: 'openclaw',
        providerPolicySource: { providers: [{ provider: {
            id: 'openrouter', resolveThinkingProfile: ctx => policy.t(ctx.modelId),
        } }] },
    });
    equal(profile.levels.map(level => level.id), ['off', 'minimal', 'low', 'medium', 'high', 'max']);
    equal(profile.defaultLevel, undefined);
    equal(profile.levels.some(level => level.id === 'ultra'), false);
    equal(policy.t('openrouter/z-ai/glm-5.3-flash'), policy.t(model.id));
    for (const id of ['z-ai/glm-5.3', 'z-ai/glm-5.3-flash:free', 'z-ai/glm-5.3-flash-other',
        'deepseek/deepseek-v4-flash', 'openai/gpt-5.6-sol']) {
        equal(policy.t(id), originalPolicy.t(id));
    }

    async function capture(level, descriptor = model, providerStream = stream, extraParams) {
        let payload;
        const wrapped = providerStream.t({ streamFn: streamSimple, modelId: descriptor.id,
            thinkingLevel: level, extraParams });
        const events = wrapped(descriptor, {
            messages: [{ role: 'user', content: 'Synthetic offline probe.', timestamp: 1 }],
        }, {
            apiKey: 'dummy-offline-not-a-credential', reasoning: level, maxTokens: 131072,
            onPayload(value) { payload = structuredClone(value); throw new Error('PAYLOAD_CAPTURED'); },
        });
        for await (const event of events) { /* Drain the intentional local capture terminal. */ }
        assert(payload, `No payload for ${level}`);
        return payload;
    }
    for (const [level, effort] of [['max', 'max'], ['high', 'high'], ['low', 'low'], ['off', 'none']]) {
        const payload = await capture(level);
        equal(payload.reasoning, { effort });
        equal(payload.max_tokens, 131072);
        equal(payload.max_completion_tokens, undefined);
    }
    const routed = await capture('max', model, stream, { provider: { order: ['z-ai'] } });
    equal(routed.provider, { order: ['z-ai'] });
    equal(routed.reasoning.effort, 'max');
    for (const change of [
        { id: 'z-ai/glm-5.3' }, { id: 'z-ai/glm-5.3-flash:free' },
        { provider: 'openai', baseUrl: 'https://api.openai.com/v1' },
        { provider: 'openrouter', baseUrl: 'https://example.invalid/v1' },
    ]) {
        const descriptor = { ...model, ...change };
        equal(await capture('max', descriptor), await capture('max', descriptor, originalStream));
    }
    function fakePayload(level, descriptor, input) {
        const wrapped = stream.t({ modelId: descriptor.id, thinkingLevel: level,
            streamFn: (m, context, options) => { options.onPayload(input); return input; } });
        return wrapped(descriptor, {}, { onPayload: () => undefined });
    }
    equal(fakePayload('max', model, {
        reasoning: { effort: 'high', exclude: true, max_tokens: 500 }, reasoning_effort: 'high',
    }), { reasoning: { effort: 'max', exclude: true } });
    equal(fakePayload('max', model, {}), { reasoning: { effort: 'max' } });
    equal(fakePayload('max', model, { reasoning: { enabled: false, exclude: true } }),
        { reasoning: { enabled: true, exclude: true, effort: 'max' } });
    equal(fakePayload('max', { ...model, api: 'anthropic-messages' },
        { reasoning: { effort: 'high' } }), { reasoning: { effort: 'high' } });
    equal(fakePayload(undefined, model, { reasoning: { effort: 'low' } }), { reasoning: { effort: 'low' } });
    equal(networkAttempts, 0);
    return checks;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    const packageRoot = process.argv[2];
    assert(packageRoot, 'usage: node test-glm-thinking.mjs PACKAGE');
    console.log(JSON.stringify({ checks: await verifyGlmThinking(resolve(packageRoot)), inferenceRequests: 0 }));
}
