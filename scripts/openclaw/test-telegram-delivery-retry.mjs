import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, symlinkSync, copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, basename } from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import { canRetryOwnerTelegramDelivery, loadRetryPolicy } from './telegram-delivery-retry.mjs';

const policy = { schemaVersion: 1, ownerId: '12345', accountId: 'default', allowAmbiguousReplay: true,
    maxAttempts: 1008, maxAgeMs: 604800000 };
const cfg = { commands: { ownerAllowFrom: ['telegram:12345'] },
    channels: { telegram: { enabled: true, dmPolicy: 'allowlist', allowFrom: ['12345'] } } };
const entry = { channel: 'telegram', to: 'telegram:12345', accountId: 'default',
    enqueuedAt: Date.now(), lastError: "Network request for 'sendMessage' failed!" };
assert.equal(canRetryOwnerTelegramDelivery(entry, cfg, policy), true);
for (const mutation of [{ channel: 'discord' }, { to: '67890' }, { to: '-12345' }, { accountId: 'other' },
    { threadId: 7 }, { lastError: '403 Forbidden' }, { lastError: '401 Unauthorized' },
    { lastError: 'receipt persistence failed' }, { lastError: 'Bad Request: cannot parse entities' },
    { enqueuedAt: Date.now() - 604800001 }, { enqueuedAt: Date.now() + 60000 }]) {
    assert.equal(canRetryOwnerTelegramDelivery({ ...entry, ...mutation }, cfg, policy), false);
}
assert.equal(canRetryOwnerTelegramDelivery(entry, cfg, null), false);
assert.equal(canRetryOwnerTelegramDelivery(entry, { channels: { telegram: { dmPolicy: 'open' } } }, policy), false);
console.log('PASS retry policy: owner/account/DM, transient errors, opt-in, age, revoked access');

// Import candidate modules with the installed dependencies but a completely isolated state DB.
const [packagePath, candidatePath] = process.argv.slice(2);
assert(packagePath && candidatePath, 'Pass installed package path and prepared candidate directory');
const root = mkdtempSync(join(tmpdir(), 'openclaw-delivery-test-'));
const state = join(root, 'state'); mkdirSync(state, { mode: 0o700 });
mkdirSync(join(state, 'operations'), { mode: 0o700 });
writeFileSync(join(state, 'operations/telegram-delivery-policy.json'), JSON.stringify(policy), { mode: 0o600 });
process.env.OPENCLAW_STATE_DIR = state;
process.env.OPENCLAW_CONFIG_PATH = join(state, 'openclaw.json');
writeFileSync(process.env.OPENCLAW_CONFIG_PATH, JSON.stringify(cfg), { mode: 0o600 });
assert.deepEqual(loadRetryPolicy(), policy);
const packageFixture = join(root, 'package'); mkdirSync(packageFixture);
for (const name of readdirSync(packagePath)) {
    if (name !== 'dist') symlinkSync(join(packagePath, name), join(packageFixture, name));
}
const dist = join(packageFixture, 'dist'); mkdirSync(dist);
const receipt = JSON.parse(readFileSync(join(candidatePath, 'patch-receipt.json'), 'utf8'));
const patchedNames = new Set(receipt.files.map(file => file.name));
for (const name of readdirSync(join(packagePath, 'dist'))) {
    if (!patchedNames.has(name)) symlinkSync(join(packagePath, 'dist', name), join(dist, name));
}
for (const name of patchedNames) copyFileSync(join(candidatePath, name), join(dist, name));
async function moduleExports(prefix) {
    const name = receipt.files.find(file => file.name.startsWith(prefix)).name;
    const text = readFileSync(join(dist, name), 'utf8');
    const imported = await import(pathToFileURL(join(dist, name)));
    const mapping = Object.fromEntries([...text.matchAll(/(\w+) as (\w+)/g)].map(match => [match[1], match[2]]));
    return new Proxy({}, { get: (_, key) => imported[mapping[key] ?? key] });
}
const storage = await moduleExports('delivery-queue-storage-');
const recovery = await moduleExports('delivery-queue-recovery-');
async function failedEntry() {
    const id = await storage.enqueueDelivery({ channel: 'telegram', to: '12345', accountId: 'default',
        queuePolicy: 'required', requiresProducerClaim: true, payloads: [{ text: 'synthetic retry fixture' }] }, state);
    const claim = await storage.claimDeliveryPlatformSendAttempt(id, state);
    assert(claim);
    assert.equal((await storage.reserveDeliveryAttempt(id, 5, state, claim)).status, 'reserved');
    await storage.markDeliveryPlatformSendAttemptStarted(id, state, {}, claim);
    await storage.failDeliveryAfterPlatformSend(id, entry.lastError, state, claim);
    return { id, claim, saved: await storage.loadPendingDelivery(id, state) };
}
const first = await failedEntry();
assert.equal(first.saved.recoveryState, 'unknown_after_send');
assert.equal(await storage.claimDeliveryPlatformSendAttempt(first.id, state,
    first.saved.platformSendStartedAt, first.saved.platformSendAttemptId), undefined);
assert.equal(await storage.claimDeliveryPlatformSendAttempt(first.id, state,
    first.saved.platformSendStartedAt, 'wrong-attempt', true), undefined);
const claims = await Promise.all([0, 1].map(() => storage.claimDeliveryPlatformSendAttempt(first.id, state,
    first.saved.platformSendStartedAt, first.saved.platformSendAttemptId, true)));
assert.equal(claims.filter(Boolean).length, 1);
await storage.ackDelivery(first.id, state, { expectedPlatformSendAttemptId: claims.find(Boolean) });
console.log('PASS durable ownership: opt-in required, stale identity rejected, one concurrent claimant');

let sends = 0;
let failing = true;
const second = await failedEntry();
const logs = [];
const options = { stateDir: state, cfg, drainKey: 'test-owner', logLabel: 'synthetic test',
    log: Object.fromEntries(['info', 'warn', 'error'].map(key => [key, message => logs.push(message)])),
    selectEntry: candidate => ({ match: candidate.id === second.id, bypassBackoff: true }),
    deliver: async params => {
        sends++;
        await storage.markDeliveryPlatformSendAttemptStarted(params.deliveryQueueId, state, {}, params.deliveryProducerClaimId);
        await params.onPlatformSendStart?.();
        if (failing) throw new Error(entry.lastError);
        const result = { channel: 'telegram', messageId: 'synthetic-message-id', chatId: '12345' };
        await params.onDeliveryResult?.(result);
        return [result];
    } };
await recovery.drainPendingDeliveriesCore(options);
assert.equal(sends, 1, JSON.stringify(logs));
const retained = await storage.loadPendingDelivery(second.id, state);
assert(retained, JSON.stringify(logs));
assert.equal(retained.recoveryState, 'send_attempt_started');
assert(retained.retryCount >= 2);
await recovery.drainPendingDeliveriesCore({ ...options, selectEntry: candidate => ({ match: candidate.id === second.id }) });
assert.equal(sends, 1, 'persisted backoff must defer immediate retry');
// A new OS process recovers only from the persisted SQLite queue, after an interrupted producer.
const recoveryFile = receipt.files.find(file => file.name.startsWith('delivery-queue-recovery-')).name;
const storageFile = receipt.files.find(file => file.name.startsWith('delivery-queue-storage-')).name;
const childSource = `
import * as recovery from ${JSON.stringify(pathToFileURL(join(dist, recoveryFile)).href)};
import * as storage from ${JSON.stringify(pathToFileURL(join(dist, storageFile)).href)};
const state = ${JSON.stringify(state)};
let sends = 0;
await recovery.t({stateDir:state,cfg:${JSON.stringify(cfg)},drainKey:'after-restart',logLabel:'fixture',
 log:{info(){},warn(){},error(){}},selectEntry:entry=>({match:entry.id===${JSON.stringify(second.id)},bypassBackoff:true}),
 deliver:async params=>{sends++;await storage.v(params.deliveryQueueId,state,{},params.deliveryProducerClaimId);
 const result={channel:'telegram',messageId:'synthetic-message-id',chatId:'12345'};
 await params.onDeliveryResult(result);return [result];}});
if(sends!==1 || await storage.f(${JSON.stringify(second.id)},state)) process.exit(1);
console.log('PASS fresh-process recovery and queue acknowledgement');process.exit(0);`;
const child = spawnSync(process.execPath, ['--input-type=module', '-e', childSource], { env: process.env, encoding: 'utf8', timeout: 30000 });
assert.equal(child.status, 0, child.stderr + child.stdout);
console.log(child.stdout.trim());
assert.equal(await storage.loadPendingDelivery(second.id, state), null);
await recovery.drainPendingDeliveriesCore(options);
assert.equal(sends, 1);
console.log('PASS transient failure retained, subsequent recovery sent, acknowledged entry not replayed');
console.log(`Fixture retained for review: ${root}`);
