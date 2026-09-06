import { constants } from 'node:fs';
import { lstat, open, realpath } from 'node:fs/promises';
import { userInfo } from 'node:os';
import { dirname, join, resolve } from 'node:path';

export const HEALTH_METHOD = 'personaledge.health.read';
export const MAX_SNAPSHOT_BYTES = 1024;
export const MAX_SNAPSHOT_AGE_MS = 600000;
export const MAX_FUTURE_SKEW_MS = 5000;
const FIELDS = [
  'schemaVersion', 'observedAtEpochMillis', 'gatewayHealthy', 'dockerHealthy',
  'policyValid', 'secretsClean',
];

function unavailable() {
  // Never expose the rejected file, its contents, or a filesystem/library exception.
  throw new Error('HEALTH_SNAPSHOT_UNAVAILABLE');
}

export function validateSnapshot(value, nowEpochMillis) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).length !== FIELDS.length ||
      FIELDS.some((field) => !Object.hasOwn(value, field)) ||
      value.schemaVersion !== 1 || !Number.isSafeInteger(value.observedAtEpochMillis) ||
      value.observedAtEpochMillis <= 0 || !Number.isSafeInteger(nowEpochMillis) ||
      nowEpochMillis <= 0 ||
      value.observedAtEpochMillis < nowEpochMillis - MAX_SNAPSHOT_AGE_MS ||
      value.observedAtEpochMillis > nowEpochMillis + MAX_FUTURE_SKEW_MS ||
      FIELDS.slice(2).some((field) => typeof value[field] !== 'boolean')) unavailable();
  // Rebuild the exact content-free projection; never return an unvalidated source object.
  return Object.fromEntries(FIELDS.map((field) => [field, value[field]]));
}

function privateOwned(stat, uid, directory = false) {
  return (directory ? stat.isDirectory() : stat.isFile() && stat.nlink === 1) &&
    stat.uid === uid && (stat.mode & 0o077) === 0;
}

/**
 * owner is injected only by isolated unit fixtures. Production obtains the OS account record;
 * RPC/config/environment inputs cannot select a home directory, filename, or reader operation.
 */
export function createHealthHandler({ owner = userInfo(), now = Date.now } = {}) {
  const ownerHome = resolve(owner.homedir);
  const stateDirectory = join(ownerHome, '.openclaw-personaledge');
  const snapshotDirectory = join(stateDirectory, 'operations');
  const snapshotPath = join(snapshotDirectory, 'remote-health.json');
  let reading = false;

  async function readSnapshot() {
    if (ownerHome !== owner.homedir || await realpath(ownerHome) !== ownerHome ||
        await realpath(snapshotDirectory) !== snapshotDirectory) unavailable();
    for (const directory of [stateDirectory, snapshotDirectory]) {
      if (!privateOwned(await lstat(directory), owner.uid, true)) unavailable();
    }
    const before = await lstat(snapshotPath);
    if (!privateOwned(before, owner.uid) || before.size < 1 ||
        before.size > MAX_SNAPSHOT_BYTES) unavailable();
    // NONBLOCK avoids blocking even if a raced replacement is a FIFO. NOFOLLOW rejects a raced
    // final symlink, while path realpath/lstat checks reject symlink ancestors and private dirs
    // prevent an unrelated account from replacing them during this bounded read.
    const file = await open(snapshotPath, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    try {
      const opened = await file.stat();
      if (!privateOwned(opened, owner.uid) || opened.ino !== before.ino ||
          opened.dev !== before.dev || opened.size !== before.size) unavailable();
      const buffer = Buffer.alloc(MAX_SNAPSHOT_BYTES + 1);
      const { bytesRead } = await file.read(buffer, 0, buffer.length, 0);
      const after = await file.stat();
      const current = await lstat(snapshotPath);
      if (bytesRead !== opened.size || after.size !== opened.size ||
          after.mtimeMs !== opened.mtimeMs || after.ctimeMs !== opened.ctimeMs ||
          !privateOwned(after, owner.uid) || current.ino !== opened.ino ||
          current.dev !== opened.dev || !privateOwned(current, owner.uid) ||
          await realpath(dirname(snapshotPath)) !== snapshotDirectory) unavailable();
      const text = new TextDecoder('utf-8', { fatal: true }).decode(buffer.subarray(0, bytesRead));
      const snapshot = validateSnapshot(JSON.parse(text), now());
      // Watchdog publishes this exact canonical JSON line. This also rejects duplicate keys,
      // alternate number encodings, extra whitespace and hidden trailing data before projection.
      if (text !== JSON.stringify(snapshot) + '\n') unavailable();
      return snapshot;
    } finally {
      await file.close();
    }
  }

  return async ({ params, client, respond }) => {
    const scopes = client?.connect?.scopes;
    if (client?.connect?.role !== 'operator' || !Array.isArray(scopes) ||
        !scopes.some((scope) => scope === 'operator.read' || scope === 'operator.admin')) {
      respond(false, undefined, { code: 'INVALID_REQUEST', message: 'Read scope required.' });
      return;
    }
    if (!params || typeof params !== 'object' || Array.isArray(params) || Object.keys(params).length !== 0) {
      respond(false, undefined, { code: 'INVALID_REQUEST', message: 'No parameters are accepted.' });
      return;
    }
    if (reading) {
      respond(false, undefined, { code: 'UNAVAILABLE', message: 'Health snapshot unavailable.', retryable: true });
      return;
    }
    reading = true;
    try {
      respond(true, await readSnapshot());
    } catch {
      respond(false, undefined, { code: 'UNAVAILABLE', message: 'Health snapshot unavailable.', retryable: false });
    } finally {
      reading = false;
    }
  };
}
