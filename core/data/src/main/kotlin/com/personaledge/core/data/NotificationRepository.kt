package com.personaledge.core.data

import androidx.room.withTransaction
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class CapturedMessage(
    val id: String,
    val packageName: String,
    val conversationTitle: String,
    /** Empty when the notification did not identify a sender separately from the room. */
    val sender: String,
    val text: String,
    val postedAtEpochMillis: Long,
)

data class CapturedNotificationDraft(
    val sourceKey: String,
    val packageName: String,
    val conversationTitle: String,
    val sender: String,
    val text: String,
    val postedAtEpochMillis: Long,
)

/**
 * Stores and searches captured notification text.
 *
 * This is the most sensitive data the app holds: other people's messages, captured without their
 * knowledge. It lives in `noBackupFilesDir`, is bounded by both age and row count, and is erasable
 * in one call. Nothing here decides *whether* to capture — that gate is in the listener, which
 * checks the user's setting on every post.
 *
 * Text is capped and control characters are stripped before storage, because it is written by
 * third parties and later enters a model prompt.
 */
class NotificationRepository(
    private val database: PersonalEdgeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.capturedNotificationDao()

    /** Replaces any earlier row with the same platform key, so an updated post is not duplicated. */
    suspend fun capture(draft: CapturedNotificationDraft): Boolean {
        val text = draft.text.sanitized(MAX_TEXT_CHARACTERS)
        if (text.isEmpty() || draft.sourceKey.isBlank() || draft.packageName.isBlank()) return false

        val sourceKeyHash = sha256(draft.sourceKey)
        database.withTransaction {
            // Remove the legacy truncated-key representation when the same platform post appears
            // after update, avoiding one legacy duplicate without ever retaining the full key.
            dao.deleteBySourceKey(draft.sourceKey.takeCodePoints(LEGACY_MAX_KEY_CHARACTERS))
            dao.upsert(
                CapturedNotificationEntity(
                    id = idFactory(),
                    sourceKey = sourceKeyHash,
                    packageName = draft.packageName,
                    conversationTitle = draft.conversationTitle.sanitized(MAX_LABEL_CHARACTERS),
                    sender = draft.sender.sanitized(MAX_LABEL_CHARACTERS),
                    text = text,
                    postedAtEpochMillis = draft.postedAtEpochMillis,
                ),
            )
            // Enforce the documented hard bound on every insert, not only on an hourly cleanup.
            dao.trimTo(MAX_STORED_ROWS)
        }
        return true
    }

    /**
     * A blank [query] returns the most recent messages instead of matching everything.
     *
     * [retentionDays] is mandatory so no caller can accidentally expose a row past the configured
     * privacy window. The stricter of that cutoff and [postedAtOrAfter] wins.
     */
    suspend fun search(
        packageNames: List<String>,
        query: String?,
        postedAtOrAfter: Long,
        retentionDays: Int,
        limit: Int,
    ): List<CapturedMessage> {
        val retentionCutoff = retentionCutoff(retentionDays)
        // Search is a natural maintenance boundary: the listener might have been idle since these
        // rows expired. Clamp the read as well as deleting, so a concurrent stale insert cannot
        // become visible between the prune and the SELECT.
        pruneAt(retentionCutoff, MAX_STORED_ROWS)
        if (packageNames.isEmpty()) return emptyList()
        val boundedLimit = limit.coerceIn(1, MAX_RESULTS)
        val trimmed = query?.trim().orEmpty()
        val effectivePostedAtOrAfter = maxOf(postedAtOrAfter, retentionCutoff)

        val rows = if (trimmed.isEmpty()) {
            dao.recent(packageNames, effectivePostedAtOrAfter, boundedLimit)
        } else {
            dao.search(packageNames, likePattern(trimmed), effectivePostedAtOrAfter, boundedLimit)
        }
        return rows.map(CapturedNotificationEntity::toCapturedMessage)
    }

    /**
     * Drops rows older than [retentionDays] and then everything past [maximumRows].
     *
     * Both bounds matter: age alone lets a busy day grow without limit, and a row cap alone lets a
     * quiet month keep messages far longer than the user asked for.
     */
    suspend fun prune(retentionDays: Int, maximumRows: Int = MAX_STORED_ROWS): Int {
        return pruneAt(retentionCutoff(retentionDays), maximumRows)
    }

    private fun retentionCutoff(retentionDays: Int): Long {
        val boundedDays = retentionDays.coerceIn(
            AgentSettings.MIN_NOTIFICATION_RETENTION_DAYS,
            AgentSettings.MAX_NOTIFICATION_RETENTION_DAYS,
        )
        return clock() - boundedDays.toLong() * MILLIS_PER_DAY
    }

    private suspend fun pruneAt(cutoff: Long, maximumRows: Int): Int {
        val byAge = dao.deleteOlderThan(cutoff)
        val byCount = dao.trimTo(maximumRows.coerceIn(1, MAX_STORED_ROWS))
        return byAge + byCount
    }

    suspend fun deleteAll(): Int = dao.deleteAll()

    suspend fun count(): Long = dao.count()

    /** Escapes the wildcards so a message containing `%` cannot widen someone else's search. */
    private fun likePattern(query: String): String {
        val escaped = query
            .takeCodePoints(MAX_QUERY_CHARACTERS)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }

    private fun String.sanitized(maximumCharacters: Int): String = trim()
        .filterNot(Char::isISOControl)
        .takeCodePoints(maximumCharacters)

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }

    companion object {
        const val MAX_RESULTS = 20
        const val MAX_STORED_ROWS = 5_000
        const val MAX_TEXT_CHARACTERS = 1_000
        const val MAX_LABEL_CHARACTERS = 120
        const val MAX_QUERY_CHARACTERS = 100
        private const val LEGACY_MAX_KEY_CHARACTERS = 256
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
    }
}

private fun CapturedNotificationEntity.toCapturedMessage() = CapturedMessage(
    id = id,
    packageName = packageName,
    conversationTitle = conversationTitle,
    sender = sender,
    text = text,
    postedAtEpochMillis = postedAtEpochMillis,
)
