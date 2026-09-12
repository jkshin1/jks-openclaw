// Owner-authorized at-least-once recovery for transient Telegram DM delivery failures.
// This is a replay policy, never evidence that an uncertain platform send did not happen.
import { readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

export function loadRetryPolicy(env = process.env) {
    try {
        if (!env.OPENCLAW_STATE_DIR) return null;
        const path = join(env.OPENCLAW_STATE_DIR, 'operations', 'telegram-delivery-policy.json');
        const stat = statSync(path);
        if (!stat.isFile() || stat.uid !== process.getuid() || (stat.mode & 0o077) || stat.size > 4096) return null;
        const policy = JSON.parse(readFileSync(path, 'utf8'));
        if (policy.schemaVersion !== 1 || policy.allowAmbiguousReplay !== true ||
            !/^[1-9][0-9]+$/.test(policy.ownerId) || policy.accountId !== 'default' ||
            policy.maxAttempts !== 1008 || policy.maxAgeMs !== 7 * 24 * 60 * 60 * 1000) return null;
        return policy;
    } catch {
        return null;
    }
}

export function canRetryOwnerTelegramDelivery(entry, cfg, policy = loadRetryPolicy(), now = Date.now()) {
    if (!policy || entry.channel !== 'telegram' || (entry.accountId ?? 'default') !== policy.accountId ||
        ![policy.ownerId, `telegram:${policy.ownerId}`].includes(entry.to) || entry.threadId != null ||
        !Number.isFinite(entry.enqueuedAt) || now < entry.enqueuedAt || now - entry.enqueuedAt > policy.maxAgeMs) return false;
    if (cfg) {
        const channel = cfg.channels?.telegram;
        if (channel?.enabled !== true || channel.dmPolicy !== 'allowlist' ||
            JSON.stringify(channel.allowFrom) !== JSON.stringify([policy.ownerId]) ||
            JSON.stringify(cfg.commands?.ownerAllowFrom) !== JSON.stringify([`telegram:${policy.ownerId}`])) return false;
    }
    // Authentication, permission, bad payloads and receipt-persistence failures stay fail-closed.
    // Partial sends can duplicate a chunk; the owner explicitly selected eventual delivery.
    const error = entry.lastError ?? '';
    if (typeof error !== 'string' || /unauthorized|forbidden|blocked by|chat not found|parse entities|receipt|persistence|finalization/i.test(error)) return false;
    return /Network request for ['"]send[A-Za-z]+['"] failed|ECONNRESET|ETIMEDOUT|EAI_AGAIN|ENETUNREACH|ECONNREFUSED|fetch failed|socket (?:closed|hang up)|temporarily unavailable/i.test(error);
}

export function ownerTelegramRetryBudget(entry) {
    const policy = loadRetryPolicy();
    return canRetryOwnerTelegramDelivery(entry, undefined, policy) ? policy.maxAttempts : undefined;
}
