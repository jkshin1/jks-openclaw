import assert from 'node:assert/strict';
import { chmod, link, mkdir, mkdtemp, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import plugin from './index.mjs';
import { createHealthHandler, HEALTH_METHOD, MAX_SNAPSHOT_AGE_MS, validateSnapshot } from './snapshot.mjs';

const NOW = 1800000000000;
const snapshot = (changes = {}) => ({
  schemaVersion: 1, observedAtEpochMillis: NOW,
  gatewayHealthy: true, dockerHealthy: true, policyValid: true, secretsClean: true,
  ...changes,
});
const reader = { connect: { role: 'operator', scopes: ['operator.read'] } };

async function fixture(t) {
  const home = await mkdtemp(join(await realpath(tmpdir()), 'personaledge-health-test-'));
  t.after(() => rm(home, { recursive: true, force: true }));
  const state = join(home, '.openclaw-personaledge');
  const directory = join(state, 'operations');
  const path = join(directory, 'remote-health.json');
  await mkdir(directory, { recursive: true, mode: 0o700 });
  await chmod(state, 0o700);
  const write = async (text = JSON.stringify(snapshot()) + '\n') => writeFile(path, text, { mode: 0o600 });
  await write();
  const handler = createHealthHandler({ owner: { homedir: home, uid: process.getuid() }, now: () => NOW });
  const invoke = async (params = {}, client = reader, selectedHandler = handler) => {
    const responses = [];
    await selectedHandler({ params, client, respond: (...values) => responses.push(values) });
    assert.equal(responses.length, 1);
    return responses[0];
  };
  return { home, state, directory, path, write, handler, invoke };
}

test('registers only one authenticated read RPC, never a model Tool or service', () => {
  const registrations = [];
  plugin.register(new Proxy({}, { get: (_, key) => {
    assert.equal(key, 'registerGatewayMethod');
    return (...args) => registrations.push(args);
  } }));
  assert.equal(registrations.length, 1);
  assert.equal(registrations[0][0], HEALTH_METHOD);
  assert.equal(typeof registrations[0][1], 'function');
  assert.deepEqual(registrations[0][2], { scope: 'operator.read', profileAccess: 'required' });
});

test('returns only the exact content-free snapshot without probing the host', async (t) => {
  const f = await fixture(t);
  assert.deepEqual(await f.invoke(), [true, snapshot()]);
  await f.write(JSON.stringify(snapshot({ dockerHealthy: false })) + '\n');
  assert.deepEqual(await f.invoke(), [true, snapshot({ dockerHealthy: false })]);
});

test('accepts exact age and future-skew boundaries', () => {
  for (const delta of [-MAX_SNAPSHOT_AGE_MS, 5000]) {
    assert.deepEqual(validateSnapshot(snapshot({ observedAtEpochMillis: NOW + delta }), NOW),
      snapshot({ observedAtEpochMillis: NOW + delta }));
  }
});

test('scope guard refuses anonymous, node, write-only, and malformed principals', async (t) => {
  const f = await fixture(t);
  for (const client of [null, {}, { connect: { role: 'node', scopes: ['operator.read'] } },
    { connect: { role: 'operator', scopes: ['operator.write'] } },
    { connect: { role: 'operator', scopes: 'operator.read' } }]) {
    const result = await f.invoke({}, client);
    assert.equal(result[0], false);
    assert.equal(result[2].code, 'INVALID_REQUEST');
  }
  assert.equal((await f.invoke({}, { connect: { role: 'operator', scopes: ['operator.admin'] } }))[0], true);
});

test('rejects every argument shape including path, command, model and profile overrides', async (t) => {
  const f = await fixture(t);
  for (const params of [null, [], 'ignored', { path: f.path }, { command: 'uptime' },
    { model: 'secret' }, { profile: 'other' }]) {
    const result = await f.invoke(params);
    assert.equal(result[0], false);
    assert.equal(result[2].code, 'INVALID_REQUEST');
  }
});

const invalidContents = {
  stale: JSON.stringify(snapshot({ observedAtEpochMillis: NOW - MAX_SNAPSHOT_AGE_MS - 1 })) + '\n',
  future: JSON.stringify(snapshot({ observedAtEpochMillis: NOW + 5001 })) + '\n',
  extra: JSON.stringify(snapshot({ secret: 'PRIVATE-CONTENT-CANARY' })) + '\n',
  missing: JSON.stringify({ schemaVersion: 1, observedAtEpochMillis: NOW }) + '\n',
  coerced: JSON.stringify(snapshot({ gatewayHealthy: 'true' })) + '\n',
  nested: JSON.stringify(snapshot({ policyValid: { private: 'PRIVATE-CONTENT-CANARY' } })) + '\n',
  schema: JSON.stringify(snapshot({ schemaVersion: 2 })) + '\n',
  fractional: JSON.stringify(snapshot({ observedAtEpochMillis: NOW + 0.5 })) + '\n',
  unsafeInteger: JSON.stringify(snapshot({ observedAtEpochMillis: Number.MAX_SAFE_INTEGER + 1 })) + '\n',
  duplicate: JSON.stringify(snapshot()).replace('"schemaVersion":1', '"schemaVersion":2,"schemaVersion":1') + '\n',
  trailing: JSON.stringify(snapshot()) + '\nPRIVATE-CONTENT-CANARY',
  noncanonical: JSON.stringify(snapshot(), null, 2) + '\n',
  oversized: ' '.repeat(1025),
  invalidUtf8: Buffer.from([0xc0, 0xaf]),
};
for (const [name, contents] of Object.entries(invalidContents)) {
  test(`rejects ${name} snapshot with a content-free error`, async (t) => {
    const f = await fixture(t);
    await f.write(contents);
    assert.deepEqual(await f.invoke(), [false, undefined, {
      code: 'UNAVAILABLE', message: 'Health snapshot unavailable.', retryable: false,
    }]);
  });
}

test('rejects a missing file or directory in place of a file', async (t) => {
  const f = await fixture(t);
  await rm(f.path);
  assert.equal((await f.invoke())[0], false);
  await mkdir(f.path, { mode: 0o700 });
  assert.equal((await f.invoke())[0], false);
});

test('rejects a symlink snapshot even when its target is otherwise valid', async (t) => {
  const f = await fixture(t);
  const other = join(f.home, 'other.json');
  await writeFile(other, JSON.stringify(snapshot()) + '\n', { mode: 0o600 });
  await rm(f.path);
  await symlink(other, f.path);
  assert.equal((await f.invoke())[0], false);
});

test('rejects a symlink directory and never follows an alternate snapshot root', async (t) => {
  const f = await fixture(t);
  const other = join(f.home, 'elsewhere');
  await mkdir(other, { mode: 0o700 });
  await writeFile(join(other, 'remote-health.json'), JSON.stringify(snapshot()) + '\n', { mode: 0o600 });
  await rm(f.directory, { recursive: true });
  await symlink(other, f.directory);
  assert.equal((await f.invoke())[0], false);
});

test('rejects hard-linked snapshots', async (t) => {
  const f = await fixture(t);
  await link(f.path, join(f.home, 'hardlink.json'));
  assert.equal((await f.invoke())[0], false);
});

test('requires private owner-only snapshot and parent directories', async (t) => {
  const f = await fixture(t);
  for (const path of [f.path, f.directory, f.state]) {
    await chmod(path, path === f.path ? 0o644 : 0o755);
    assert.equal((await f.invoke())[0], false);
    await chmod(path, path === f.path ? 0o600 : 0o700);
  }
  const wrongOwner = createHealthHandler({ owner: { homedir: f.home, uid: process.getuid() + 1 }, now: () => NOW });
  assert.equal((await f.invoke({}, reader, wrongOwner))[0], false);
});

test('bounds concurrent reads and recovers after a rejected request', async (t) => {
  const f = await fixture(t);
  const results = await Promise.all([f.invoke(), f.invoke()]);
  assert.equal(results.filter((r) => r[0]).length, 1);
  assert.deepEqual(results.find((r) => !r[0])[2], {
    code: 'UNAVAILABLE', message: 'Health snapshot unavailable.', retryable: true,
  });
  assert.equal((await f.invoke())[0], true);
});
