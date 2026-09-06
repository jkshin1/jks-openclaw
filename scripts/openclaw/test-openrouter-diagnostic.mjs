import assert from 'node:assert/strict';
import test from 'node:test';
import { marker, runCanonicalSmoke } from './smoke-openrouter-canonical.mjs';
import { constants, createDecipheriv, generateKeyPairSync, privateDecrypt, randomBytes } from 'node:crypto';
import { encryptBootstrap } from './bootstrap-fold8-openclaw.mjs';

test('default dry run never resolves a key or sends a request', async () => {
    const forbidden = () => assert.fail('Side effect before explicit acknowledgement');
    const report = await runCanonicalSmoke({ argv: [], credential: forbidden, send: forbidden });
    assert.equal(report.providerRequests, 0);
    assert.equal(report.payload.max_tokens, 2048);
    assert(!Object.hasOwn(report.payload, 'max_completion_tokens'));
    assert(!Object.hasOwn(report.payload, 'tools'));
    assert.equal(report.payload.provider.zdr, true);
    assert.equal(report.payload.provider.data_collection, 'deny');
});
test('refuses malformed acknowledgement without side effects', async () => {
    await assert.rejects(runCanonicalSmoke({ argv: ['--acknowledge-one-model-charge', '--retry'] }));
});
test('404 is terminal and never retried or exposes raw provider error', async () => {
    let sends = 0;
    const report = await runCanonicalSmoke({ argv: ['--acknowledge-one-model-charge'],
        credential: async () => 'dummy-private-key', send: async () => {
            sends++;
            return new Response('private provider error', { status: 404 });
        } });
    assert.equal(sends, 1);
    assert.equal(report.httpStatus, 404);
    assert(!JSON.stringify(report).includes('private'));
});
test('matches exact model, fixed Korean answer and terminal stream', async () => {
    const data = `data: ${JSON.stringify({ model: 'z-ai/glm-5.3-flash',
        choices: [{ index: 0, delta: { content: marker } }],
        usage: { prompt_tokens: 25, completion_tokens: 10, cost: 0.00001 } })}\n\ndata: [DONE]\n\n`;
    const report = await runCanonicalSmoke({ argv: ['--acknowledge-one-model-charge'],
        credential: async () => 'dummy-private-key', send: async (url, options) => {
            assert.equal(url, 'https://openrouter.ai/api/v1/chat/completions');
            assert.equal(options.redirect, 'error');
            return new Response(data);
        } });
    assert.equal(report.passed, true);
    assert(!JSON.stringify(report).includes(marker));
});

test('bootstrap envelope decrypts only with the device key and exact endpoint/challenge', () => {
    const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 3072 });
    const challenge = randomBytes(32).toString('base64');
    const endpoint = 'https://fixture.example.ts.net';
    const frame = encryptBootstrap({
        publicKey: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
        challenge, endpoint, token: 'fixture-only-secret-token',
    });
    assert.equal(frame.readUInt32BE(0), frame.length - 4);
    assert(!frame.includes(Buffer.from('fixture-only-secret-token')));
    const envelope = JSON.parse(frame.subarray(4));
    const key = privateDecrypt({ key: privateKey, padding: constants.RSA_PKCS1_OAEP_PADDING,
        oaepHash: 'sha256' }, Buffer.from(envelope.wrappedKey, 'base64'));
    const encrypted = Buffer.from(envelope.ciphertext, 'base64');
    const decrypt = (aad) => {
        const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(envelope.iv, 'base64'));
        decipher.setAAD(Buffer.from(aad));
        decipher.setAuthTag(encrypted.subarray(-16));
        return Buffer.concat([decipher.update(encrypted.subarray(0, -16)), decipher.final()]);
    };
    assert.deepEqual(JSON.parse(decrypt(`PEOC1|${challenge}|${endpoint}`)),
        { endpoint, token: 'fixture-only-secret-token' });
    assert.throws(() => decrypt(`PEOC1|wrong-challenge|${endpoint}`));
    assert.throws(() => decrypt(`PEOC1|${challenge}|https://another.example.ts.net`));
});
