#!/usr/bin/env node
// Exercises the installed Codex bootstrap projection with isolated, nonpersonal files.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const plugin = process.argv[2];
assert(plugin, 'Pass the installed Codex plugin root');
const dist = path.join(plugin, 'dist');
const names = (await fs.readdir(dist)).filter(n => /^attempt-context-.*\.js$/.test(n));
assert.equal(names.length, 1);
const {i: buildContext} = await import(pathToFileURL(path.join(dist, names[0])));
assert.equal(typeof buildContext, 'function', '2026.9.3 context export changed');
const dir = await fs.mkdtemp(path.join(await fs.realpath(os.tmpdir()), 'codex-response-context-'));
let checks = 0;
try {
  await fs.writeFile(path.join(dir, 'AGENTS.md'), 'Old startup policy without image waiting guidance.');
  const context = async (key) => buildContext({
    params: {config: {}, sessionId: key, chatType: 'direct', agentId: 'main'},
    resolvedWorkspace: dir, effectiveWorkspace: dir, executionWorkspace: dir,
    sessionKey: key, sessionAgentId: 'main', memoryToolNames: [], ringZeroActive: false,
    sandboxed: false
  });
  const first = await context('response-fixture');
  assert(!first.turnScopedDeveloperInstructions?.includes('TELEGRAM RESPONSE CONTRACT')); checks++;
  const contract = await fs.readFile(new URL('./templates/TELEGRAM_SOUL.md', import.meta.url), 'utf8');
  await fs.writeFile(path.join(dir, 'SOUL.md'), contract);
  const second = await context('response-fixture');
  for (const marker of ['TELEGRAM RESPONSE CONTRACT', 'final=false', 'sessions_yield', 'structured generated attachments']) {
    assert(second.turnScopedDeveloperInstructions?.includes(marker), marker + ' missing from live turn projection'); checks++;
  }
  await fs.writeFile(path.join(dir, 'SOUL.md'), contract + '\nFresh instruction revision marker.\n');
  const third = await context('response-fixture');
  assert(third.turnScopedDeveloperInstructions?.includes('Fresh instruction revision marker.')); checks++;
  assert(!first.turnScopedDeveloperInstructions?.includes('Fresh instruction revision marker.')); checks++;
  console.log(JSON.stringify({ok:true, checks, sameSessionRefresh:true, modelCalled:false, telegramDelivered:false}));
} finally {
  await fs.rm(dir, {recursive:true, force:true});
}
