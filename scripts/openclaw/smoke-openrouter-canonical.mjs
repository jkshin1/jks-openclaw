#!/usr/bin/env node
// Isolates provider routing from the Gateway adapter. Passing is not Gateway/Fold8 acceptance.
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { configuredOpenRouterCredential } from './openrouter-credential.mjs';

export const marker = '원격 연결 확인 완료';
export function canonicalPayload() {
    return {
        model: 'z-ai/glm-5.3-flash',
        messages: [{ role: 'user', content: `다른 내용 없이 정확히 다음 문장으로만 답하세요: ${marker}` }],
        max_tokens: 2048, reasoning: { effort: 'low' }, stream: true,
        provider: { allow_fallbacks: true, require_parameters: true,
            data_collection: 'deny', zdr: true, sort: 'latency',
            max_price: { prompt: 1, completion: 1 } },
    };
}

export async function runCanonicalSmoke({ argv, credential = configuredOpenRouterCredential, send = fetch }) {
    assert(argv.length === 0 || (argv.length === 1 && argv[0] === '--acknowledge-one-model-charge'),
        'Usage: smoke-openrouter-canonical.mjs [--acknowledge-one-model-charge]');
    const payload = canonicalPayload();
    if (argv.length === 0) return { dryRun: true, providerRequests: 0, payload,
        maximumOutputTokens: 2048, timeoutSeconds: 60, retries: 0,
        scope: 'Direct provider diagnostic only; no Gateway or Fold8 acceptance.' };
    const key = await credential();
    // Exactly one application-level POST. Redirects, SDK retries, fallback models and tools absent.
    const response = await send('https://openrouter.ai/api/v1/chat/completions', {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(60000),
        headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    const report = { schemaVersion: 1, scope: 'direct-provider-only', observedAt: new Date().toISOString(),
        providerRequests: 1, httpStatus: response.status, markerMatched: false,
        responseModelMatched: false, toolCalls: 0, finished: false };
    if (!response.ok) {
        await response.body?.cancel();
        return report;
    }
    let buffer = '', answer = '', bytes = 0, responseModel;
    const decoder = new TextDecoder();
    for await (const chunk of response.body) {
        bytes += chunk.byteLength;
        assert(bytes <= 1024 * 1024, 'Response exceeded bounded diagnostic size');
        buffer += decoder.decode(chunk, { stream: true });
        let newline;
        while ((newline = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, newline).trimEnd();
            buffer = buffer.slice(newline + 1);
            if (!line.startsWith('data:')) continue;
            const data = line.slice(5).trim();
            if (data === '[DONE]') { report.finished = true; continue; }
            const frame = JSON.parse(data);
            assert(!frame.error, 'Provider stream failed');
            if (frame.model) {
                assert(responseModel === undefined || responseModel === frame.model, 'Model changed during stream');
                responseModel = frame.model;
            }
            for (const choice of frame.choices ?? []) {
                assert(choice.index === 0, 'Unexpected choice');
                answer += choice.delta?.content ?? '';
                report.toolCalls += choice.delta?.tool_calls?.length ?? 0;
                assert(!choice.delta?.function_call, 'Unexpected legacy function call');
            }
            if (frame.usage) report.usage = Object.fromEntries(
                ['prompt_tokens', 'completion_tokens', 'total_tokens', 'cost']
                    .filter((field) => Number.isFinite(frame.usage[field]))
                    .map((field) => [field, frame.usage[field]]));
        }
    }
    report.markerMatched = answer.trim() === marker;
    report.responseModelMatched = responseModel === payload.model;
    report.passed = report.finished && report.markerMatched && report.responseModelMatched && report.toolCalls === 0;
    return report;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    try {
        const report = await runCanonicalSmoke({ argv: process.argv.slice(2) });
        process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
        if (!report.dryRun && !report.passed) process.exitCode = 1;
    } catch {
        process.stderr.write('Bounded provider diagnostic failed. No automatic retry; provider outcome may be unknown.\n');
        process.exitCode = 1;
    }
}
