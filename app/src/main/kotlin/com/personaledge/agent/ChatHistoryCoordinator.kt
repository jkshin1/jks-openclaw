package com.personaledge.agent

import com.personaledge.core.data.ConversationEntity
import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.data.MessageEntity
import com.personaledge.core.data.MessageRole

data class ConversationSummaryUi(
    val id: String,
    val title: String,
    val updatedAtEpochMillis: Long,
)

data class ChatHistoryState(
    val conversations: List<ConversationSummaryUi> = emptyList(),
    val activeConversationId: String? = null,
    val visible: Boolean = false,
    val error: String? = null,
)

/**
 * The only place chat transcripts reach storage.
 *
 * A conversation row is created lazily on the first message rather than when a screen opens, so
 * launching the app and closing it again leaves nothing behind. Storage failures degrade to a
 * transcript that lives only in memory: losing history is annoying, but blocking a turn because a
 * write failed would be worse.
 *
 * Operational notices — thermal refusals, failure banners — are deliberately not stored. They
 * describe one moment of one session and would read as conversation content on restore.
 */
class ChatHistoryCoordinator(
    private val repository: ConversationRepository,
) {
    /** Returns the restored transcript, or an empty list when there is nothing to restore. */
    suspend fun restoreMostRecent(): RestoredConversation = runCatching {
        val newest = repository.mostRecentConversation()
            ?: return@runCatching RestoredConversation(null, emptyList())

        RestoredConversation(newest.id, loadEntries(newest.id))
    }.getOrDefault(RestoredConversation(null, emptyList()))

    suspend fun switchTo(conversationId: String): RestoredConversation = runCatching {
        RestoredConversation(conversationId, loadEntries(conversationId))
    }.getOrDefault(RestoredConversation(conversationId, emptyList()))

    /**
     * Returns the conversation to append to, creating one titled from [firstPrompt] if needed.
     * Null means storage is unavailable and this turn will not be persisted.
     */
    suspend fun ensureConversation(
        activeConversationId: String?,
        firstPrompt: String,
    ): String? {
        if (activeConversationId != null) return activeConversationId
        return runCatching { repository.createConversation(titleFrom(firstPrompt)) }.getOrNull()
    }

    suspend fun record(conversationId: String?, role: MessageRole, text: String) {
        if (conversationId == null || text.isBlank()) return
        runCatching { repository.appendMessage(conversationId, role, text) }
    }

    suspend fun listConversations(): List<ConversationSummaryUi> = runCatching {
        repository.recentConversations().map(ConversationEntity::toSummary)
    }.getOrDefault(emptyList())

    suspend fun delete(conversationId: String): Boolean =
        runCatching { repository.deleteConversation(conversationId) }.getOrDefault(false)

    suspend fun deleteAll(): Boolean =
        runCatching { repository.deleteAllConversations() }.isSuccess

    private suspend fun loadEntries(conversationId: String): List<ChatEntry> = repository
        .listMessages(conversationId)
        .map(MessageEntity::toChatEntry)

    /** A first line is a better handle than a timestamp when scanning a list of threads. */
    private fun titleFrom(prompt: String): String = prompt
        .lineSequence()
        .firstOrNull { line -> line.isNotBlank() }
        ?.trim()
        ?.take(MAX_TITLE_CHARACTERS)
        .orEmpty()

    private companion object {
        const val MAX_TITLE_CHARACTERS = 40
    }
}

data class RestoredConversation(
    val conversationId: String?,
    val entries: List<ChatEntry>,
)

private fun ConversationEntity.toSummary() = ConversationSummaryUi(
    id = id,
    title = title,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun MessageEntity.toChatEntry() = ChatEntry(
    id = id,
    role = when (role) {
        MessageRole.USER -> ChatRole.USER
        MessageRole.ASSISTANT -> ChatRole.ASSISTANT
        MessageRole.TOOL_RECEIPT -> ChatRole.TOOL
    },
    text = text,
)
