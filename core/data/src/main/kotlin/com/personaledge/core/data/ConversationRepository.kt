package com.personaledge.core.data

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class StoredMessage(
    val id: String,
    val ordinal: Long,
    val role: MessageRole,
    val text: String,
    val createdAtEpochMillis: Long,
)

data class ConversationContext(
    val conversationId: String,
    val summary: String?,
    val recentMessages: List<StoredMessage>,
)

/**
 * The only writer for chat history.
 *
 * Ordinals are assigned inside a transaction rather than from the clock, so concurrent appends
 * cannot collide and a device clock change cannot reorder a thread. Every stored string is
 * length-capped here, because the model, the notification listener, and the user can all supply
 * arbitrarily long text.
 */
class ConversationRepository(
    private val database: PersonalEdgeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val conversationDao = database.conversationDao()
    private val messageDao = database.messageDao()

    fun observeRecentConversations(limit: Int = DEFAULT_CONVERSATION_PAGE): Flow<List<ConversationEntity>> =
        conversationDao.observeRecent(limit.coerceIn(1, MAX_CONVERSATION_PAGE))

    fun observeMessages(
        conversationId: String,
        limit: Int = MAX_MESSAGES_PER_READ,
    ): Flow<List<MessageEntity>> =
        messageDao.observe(conversationId, limit.coerceIn(1, MAX_MESSAGES_PER_READ))

    /** One-shot read for the history list. Prefer [observeRecentConversations] for live UI. */
    suspend fun recentConversations(
        limit: Int = DEFAULT_CONVERSATION_PAGE,
    ): List<ConversationEntity> = conversationDao.listRecent(limit.coerceIn(1, MAX_CONVERSATION_PAGE))

    /** The thread to restore on launch, or null when nothing has been stored yet. */
    suspend fun mostRecentConversation(): ConversationEntity? =
        conversationDao.listRecent(1).firstOrNull()

    suspend fun listMessages(
        conversationId: String,
        limit: Int = MAX_MESSAGES_PER_READ,
    ): List<MessageEntity> =
        messageDao.list(conversationId, limit.coerceIn(1, MAX_MESSAGES_PER_READ))

    suspend fun createConversation(title: String): String {
        val now = clock()
        val conversation = ConversationEntity(
            id = idFactory(),
            title = title.sanitized(MAX_TITLE_CHARACTERS).ifEmpty { DEFAULT_TITLE },
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        conversationDao.insert(conversation)
        return conversation.id
    }

    suspend fun renameConversation(conversationId: String, title: String) {
        conversationDao.updateTitle(
            conversationId = conversationId,
            title = title.sanitized(MAX_TITLE_CHARACTERS).ifEmpty { DEFAULT_TITLE },
            updatedAt = clock(),
        )
    }

    /** Returns the assigned ordinal, or null when the conversation no longer exists. */
    suspend fun appendMessage(
        conversationId: String,
        role: MessageRole,
        text: String,
    ): Long? = database.withTransaction {
        conversationDao.find(conversationId) ?: return@withTransaction null

        val now = clock()
        val ordinal = messageDao.highestOrdinal(conversationId) + 1
        messageDao.insert(
            MessageEntity(
                id = idFactory(),
                conversationId = conversationId,
                ordinal = ordinal,
                role = role,
                text = text.sanitized(MAX_MESSAGE_CHARACTERS),
                createdAtEpochMillis = now,
            ),
        )
        conversationDao.touch(conversationId, now)
        ordinal
    }

    /**
     * Returns the bounded context used to seed a new turn: the stored summary plus the newest
     * [recentMessageLimit] messages in chronological order.
     */
    suspend fun loadContext(
        conversationId: String,
        recentMessageLimit: Int = DEFAULT_RECENT_MESSAGES,
    ): ConversationContext? {
        val conversation = conversationDao.find(conversationId) ?: return null
        val newestFirst = messageDao.listNewestFirst(
            conversationId = conversationId,
            limit = recentMessageLimit.coerceIn(1, MAX_MESSAGES_PER_READ),
        )
        return ConversationContext(
            conversationId = conversation.id,
            summary = conversation.summary,
            recentMessages = newestFirst.asReversed().map(MessageEntity::toStoredMessage),
        )
    }

    /**
     * Replaces the stored summary and drops the messages it now covers.
     *
     * [throughOrdinal] must come from a transcript the caller actually summarized; the newest
     * [keepRecentMessages] messages are always retained so the next turn keeps verbatim context.
     */
    suspend fun replaceSummary(
        conversationId: String,
        summary: String,
        throughOrdinal: Long,
        keepRecentMessages: Int = DEFAULT_RECENT_MESSAGES,
    ): Boolean = database.withTransaction {
        val conversation = conversationDao.find(conversationId) ?: return@withTransaction false
        if (throughOrdinal <= conversation.summarizedThroughMessageOrdinal) return@withTransaction false

        val highestOrdinal = messageDao.highestOrdinal(conversationId)
        if (throughOrdinal > highestOrdinal) return@withTransaction false

        val protectedFrom = highestOrdinal - keepRecentMessages.coerceIn(0, MAX_MESSAGES_PER_READ)
        val deletableThrough = minOf(throughOrdinal, protectedFrom)

        conversationDao.updateSummary(
            conversationId = conversationId,
            summary = summary.sanitized(MAX_SUMMARY_CHARACTERS).ifEmpty { null },
            throughOrdinal = throughOrdinal,
            updatedAt = clock(),
        )
        if (deletableThrough > 0) {
            messageDao.deleteThrough(conversationId, deletableThrough)
        }
        true
    }

    /** Cascades to every message in the thread. */
    suspend fun deleteConversation(conversationId: String): Boolean =
        conversationDao.delete(conversationId) > 0

    /**
     * Erases every conversation and message. The action ledger lives in a separate database and
     * is deliberately untouched, so clearing history cannot re-enable a replayed side effect.
     */
    suspend fun deleteAllConversations(): Int = conversationDao.deleteAll()

    suspend fun conversationCount(): Long = conversationDao.count()

    suspend fun messageCount(): Long = messageDao.count()

    private fun String.sanitized(maximumCharacters: Int): String = trim()
        .filterNot { character -> character.isISOControl() && character != '\n' }
        .take(maximumCharacters)

    companion object {
        const val DEFAULT_TITLE = "새 대화"
        const val DEFAULT_CONVERSATION_PAGE = 50
        const val DEFAULT_RECENT_MESSAGES = 12
        const val MAX_CONVERSATION_PAGE = 500
        const val MAX_MESSAGES_PER_READ = 500
        const val MAX_TITLE_CHARACTERS = 120
        const val MAX_MESSAGE_CHARACTERS = 8_000
        const val MAX_SUMMARY_CHARACTERS = 2_000
    }
}

private fun MessageEntity.toStoredMessage(): StoredMessage = StoredMessage(
    id = id,
    ordinal = ordinal,
    role = role,
    text = text,
    createdAtEpochMillis = createdAtEpochMillis,
)
