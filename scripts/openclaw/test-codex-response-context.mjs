#!/usr/bin/env node
// Exercises the installed Codex bootstrap projection with isolated, nonpersonal files.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const plugin = process.argv[2];
assert(plugin, 'Pass the installed Codex plugin root');
// The plugin opens OpenClaw's shared state on import; keep that away from every real profile.
const isolatedState = await fs.mkdtemp(path.join(await fs.realpath(os.tmpdir()), 'codex-response-state-'));
process.env.OPENCLAW_STATE_DIR = isolatedState;
process.env.OPENCLAW_CONFIG_PATH = path.join(isolatedState, 'openclaw.json');
// 2026.9.3 bundled the builder in dist/attempt-context-*.js; 2026.9.6 moved it under dist/.setup/.
const matches = [];
for (const dir of [path.join(plugin, 'dist'), path.join(plugin, 'dist', '.setup')]) {
  for (const name of await fs.readdir(dir).catch(() => [])) {
    if (!/\.m?js$/.test(name)) continue;
    const exported = (await fs.readFile(path.join(dir, name), 'utf8'))
      .match(/export \{[^}]*\bbuildCodexWorkspaceBootstrapContext as (\w+)/);
    if (exported) matches.push([path.join(dir, name), exported[1]]);
  }
}
assert.equal(matches.length, 1, 'Codex workspace bootstrap builder missing or ambiguous');
const buildContext = (await import(pathToFileURL(matches[0][0])))[matches[0][1]];
assert.equal(typeof buildContext, 'function', 'Codex context export changed');
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
  for (const marker of ['TELEGRAM RESPONSE CONTRACT', 'final=false', 'sessions_yield', 'structured generated attachments', 'final=true', 'LAST tool action', 'Never end with only final=false']) {
    assert(second.turnScopedDeveloperInstructions?.includes(marker), marker + ' missing from live turn projection'); checks++;
  }
  await fs.writeFile(path.join(dir, 'SOUL.md'), contract + '\nFresh instruction revision marker.\n');
  const third = await context('response-fixture');
  assert(third.turnScopedDeveloperInstructions?.includes('Fresh instruction revision marker.')); checks++;
  assert(!first.turnScopedDeveloperInstructions?.includes('Fresh instruction revision marker.')); checks++;
  console.log(JSON.stringify({ok:true, checks, sameSessionRefresh:true, modelCalled:false, telegramDelivered:false}));
} finally {
  await fs.rm(dir, {recursive:true, force:true});
  await fs.rm(isolatedState, {recursive:true, force:true});
}
