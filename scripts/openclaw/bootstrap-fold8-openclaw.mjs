#!/usr/bin/env node
// Sends a Gateway credential only inside an ephemeral device-key-encrypted envelope over ADB.
import assert from 'node:assert/strict';
import { constants, createCipheriv, createPublicKey, publicEncrypt, randomBytes } from 'node:crypto';
import { readFile, lstat } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';
import { createConnection } from 'node:net';
import { configuredGatewayCredential } from './openrouter-credential.mjs';

export function encryptBootstrap({ publicKey, challenge, endpoint, token }) {
    const parsed = createPublicKey({ key: Buffer.from(publicKey, 'base64'), format: 'der', type: 'spki' });
    assert.equal(parsed.asymmetricKeyType, 'rsa');
    assert.equal(parsed.asymmetricKeyDetails.modulusLength, 3072);
    assert.equal(Buffer.from(challenge, 'base64').length, 32);
    assert.equal(new URL(endpoint).protocol, 'https:');
    const key = randomBytes(32), iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', key, iv);
    cipher.setAAD(Buffer.from(`PEOC1|${challenge}|${endpoint}`, 'utf8'));
    const plaintext = Buffer.from(JSON.stringify({ endpoint, token }), 'utf8');
    const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]);
    const wrappedKey = publicEncrypt({ key: parsed, padding: constants.RSA_PKCS1_OAEP_PADDING,
        oaepHash: 'sha256' }, key);
    key.fill(0); plaintext.fill(0);
    const envelope = Buffer.from(JSON.stringify({ version: 1, wrappedKey: wrappedKey.toString('base64'),
        iv: iv.toString('base64'), ciphertext: ciphertext.toString('base64') }));
    assert(envelope.length <= 16 * 1024);
    const header = Buffer.alloc(4);
    header.writeUInt32BE(envelope.length);
    return Buffer.concat([header, envelope]);
}

async function main() {
    const [transcript, serial, endpoint] = process.argv.slice(2);
    assert(transcript && serial && endpoint && process.argv.length === 5,
        'Usage: bootstrap-fold8-openclaw.mjs TRANSCRIPT SERIAL HTTPS_ENDPOINT');
    assert(/^[A-Za-z0-9._:-]{1,128}$/.test(serial) && !serial.startsWith('-'));
    const url = new URL(endpoint);
    assert(url.protocol === 'https:' && url.hostname.endsWith('.ts.net') && url.pathname === '/' &&
        !url.username && !url.password && !url.search && !url.hash && !url.port);
    const metadata = await lstat(transcript);
    assert(metadata.isFile() && !metadata.isSymbolicLink() && metadata.uid === process.getuid());
    assert(metadata.size <= 256 * 1024 && Date.now() - metadata.mtimeMs < 180000);
    const text = await readFile(transcript, 'utf8');
    const field = (name) => {
        const values = [...text.matchAll(new RegExp(`^INSTRUMENTATION_STATUS: ${name}=(.+)$`, 'gm'))];
        assert.equal(values.length, 1, 'Expected one fresh bootstrap');
        return values[0][1].trim();
    };
    assert.equal(field('remote_bootstrap_protocol'), 'PEOC1_RSA_OAEP_SHA256_AES256_GCM');
    const port = Number(field('remote_bootstrap_port'));
    assert(Number.isInteger(port) && port >= 1024 && port <= 65535);
    const publicKey = field('remote_bootstrap_public_key_spki');
    const challenge = field('remote_bootstrap_challenge');
    assert(publicKey.length < 2048 && challenge.length < 64);
    const adb = join(homedir(), 'Library/Android/sdk/platform-tools/adb');
    let localPort;
    try {
        localPort = execFileSync(adb, ['-s', serial, 'forward', 'tcp:0', `tcp:${port}`],
            { encoding: 'utf8', timeout: 15000 }).trim();
        assert(/^[0-9]{1,5}$/.test(localPort) && Number(localPort) > 0 && Number(localPort) <= 65535);
        const token = await configuredGatewayCredential();
        const frame = encryptBootstrap({ publicKey, challenge, endpoint, token });
        await new Promise((resolve, reject) => {
            const socket = createConnection({ host: '127.0.0.1', port: Number(localPort) });
            socket.setTimeout(10000, () => socket.destroy(new Error('Transfer timeout')));
            socket.once('error', reject);
            socket.once('connect', () => socket.end(frame));
            socket.once('close', (hadError) => { if (!hadError) resolve(); });
        });
        console.log('OK encrypted one-shot bootstrap transferred; no credential in argv, logs, or shared storage.');
    } finally {
        if (localPort) execFileSync(adb, ['-s', serial, 'forward', '--remove', `tcp:${localPort}`], { timeout: 15000 });
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    try { await main(); } catch {
        console.error('Encrypted bootstrap transfer failed; secret details suppressed.');
        process.exitCode = 1;
    }
}
