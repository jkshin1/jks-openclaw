// Deny Claude Code's native Agent tool on the claude-cli route; prepares a candidate only.
// 2026.9.6 lets a Claude answer reach the channel while native background agents continue, and
// that path can drop the Telegram reply (openclaw/openclaw#158626). Delegation already belongs to
// OpenClaw's own sub-agents (GPT-5.6 Sol), so the native Agent tool adds no required capability.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstat, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const sha256 = text => createHash('sha256').update(text).digest('hex');
const DENIED = 'ScheduleWakeup,CronCreate,Bash(run_in_background:true),Monitor';
const [input, output] = process.argv.slice(2);
assert(input && output, 'usage: node patch-claude-cli-agent.mjs ORIGINAL CANDIDATE');
assert.notEqual(resolve(input), resolve(output), 'Candidate must be separate from original');
const info = await lstat(input);
assert(info.isFile() && !info.isSymbolicLink(), 'Original must be a regular file');
const source = await readFile(input, 'utf8');
const specs = JSON.parse(await readFile(new URL('./runtime-patch-specs.json', import.meta.url), 'utf8'));
const [version, release] = Object.entries(specs)
    .find(([, value]) => value.claudeCliArgs?.before === sha256(source)) ?? [];
assert(release, 'Unreviewed runtime source; requalify after upgrade');
const spec = release.claudeCliArgs;
assert.equal(source.split(`"${DENIED}"`).length, 2, 'Ambiguous Claude CLI denial anchor');
const candidate = source.replace(`"${DENIED}"`, `"${DENIED},Agent"`);

// Execute the actual backend descriptor and argument resolver against the candidate bytes.
const base = pathToFileURL(input);
const executable = candidate.replace(/\b(from|import) "(\.\/[^"]+)"/g,
    (_, keyword, specifier) => `${keyword} "${new URL(specifier, base).href}"`);
const patched = await import(`data:text/javascript;base64,${Buffer.from(executable).toString('base64')}`);
const original = await import(base.href);
const shared = await import(new URL(spec.sharedModule, pathToFileURL(`${dirname(input)}/`)).href);
const resolveArgs = shared[spec.resolveExport];
let checks = 0;
const denials = args => args[args.indexOf('--disallowedTools') + 1].split(',');
const backend = patched[spec.export]();
const before = original[spec.export]();
for (const key of ['args', 'resumeArgs']) {
    assert.deepEqual(denials(backend.config[key]), [...DENIED.split(','), 'Agent']); checks++;
    // Nothing else in the launch contract moves.
    assert.deepEqual(backend.config[key].filter(arg => !arg.startsWith('ScheduleWakeup')),
        before.config[key].filter(arg => !arg.startsWith('ScheduleWakeup'))); checks++;
}
const agentTurn = resolveArgs({ baseArgs: backend.config.args, thinkingLevel: 'high',
    modelId: 'claude-opus-5-5', executionMode: 'agent' });
assert(denials(agentTurn).includes('Agent')); checks++;
// Restricted cron-style runs rebuild their flags but must keep every preserved denial.
const restricted = resolveArgs({ baseArgs: backend.config.args, thinkingLevel: 'high',
    modelId: 'claude-opus-5-5', executionMode: 'agent',
    toolAvailability: { openClaw: ['read'], native: [] } });
assert(denials(restricted).includes('Agent') && denials(restricted).includes('Monitor')); checks++;
await writeFile(output, candidate, { mode: 0o600, flag: 'wx' });
console.log(JSON.stringify({ version, relativePath: spec.path, beforeSha256: spec.before,
    afterSha256: sha256(candidate), checks, inferenceRequests: 0 }));
