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
    private val turnOutcomeDao = database.turnOutcomeDao()
    private val turnOutcomes = TurnOutcomeRepository(database = database, clock = clock)

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

    suspend fun findConversation(conversationId: String): ConversationEntity? =
        conversationDao.find(conversationId)

    /** The thread to restore on launch, or null when nothing has been stored yet. */
    suspend fun mostRecentConversation(): ConversationEntity? =
        conversationDao.listRecent(1).firstOrNull()

    suspend fun listMessages(
        conversationId: String,
        limit: Int = MAX_MESSAGES_PER_READ,
    ): List<MessageEntity> =
        messageDao.list(conversationId, limit.coerceIn(1, MAX_MESSAGES_PER_READ))

    /**
     * Returns the oldest rows strictly after [afterOrdinal], in chronological order.
     *
     * Summary callers use this instead of the recent-message query so a bounded batch can never
     * jump over older unsummarized rows and then claim that a newer ordinal covers them.
     */
    suspend fun listMessagesAfter(
        conversationId: String,
        afterOrdinal: Long,
        limit: Int = MAX_MESSAGES_PER_READ,
    ): List<MessageEntity> {
        require(afterOrdinal >= 0L)
        return messageDao.listAfterOrdinal(
            conversationId = conversationId,
            afterOrdinal = afterOrdinal,
            limit = limit.coerceIn(1, MAX_MESSAGES_PER_READ),
        )
    }

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
        val ordinal = nextMessageOrdinal(messageDao.highestOrdinal(conversationId))
            ?: return@withTransaction null
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
     * Atomically commits the durable portion of one trusted ToolExecuted event.
     *
     * [assistantText] and [toolReceipt] are transcript content already rendered by the app; Tool
     * arguments and results are intentionally absent. Any transcript insert or outcome transition
     * failure rolls back the entire operation, leaving the capsule non-complete.
     */
    suspend fun commitToolExecution(
        conversationId: String,
        turnId: String,
        assistantText: String,
        toolReceipt: String,
        toolName: String,
        toolRisk: TurnToolRisk,
        trustedOrdinal: Int,
        outcome: TurnToolCommitOutcome,
    ): Boolean {
        val storedAssistant = assistantText.sanitized(MAX_MESSAGE_CHARACTERS)
        val storedReceipt = toolReceipt.sanitized(MAX_MESSAGE_CHARACTERS)
        if (
            conversationId.isBlank() ||
            storedReceipt.isEmpty() ||
            !TURN_ID_PATTERN.matches(turnId) ||
            !TOOL_NAME_PATTERN.matches(toolName) ||
            trustedOrdinal !in 1..MAX_READ_EXECUTIONS ||
            !validCommitOutcome(outcome, toolRisk)
        ) {
            return false
        }
        val markerPrefix = toolCommitMarkerPrefix(turnId, trustedOrdinal)
        val markerId = toolCommitMarkerId(
            prefix = markerPrefix,
            toolName = toolName,
            toolRisk = toolRisk,
            outcome = outcome,
        )
        return runCatching {
            database.withTransaction {
                conversationDao.find(conversationId) ?: return@withTransaction false
                val current = turnOutcomeDao.find(turnId) ?: return@withTransaction false
                if (current.conversationId != conversationId) return@withTransaction false

                val existingMarkers = messageDao.listByIdPrefix(markerPrefix)
                if (existingMarkers.isNotEmpty()) {
                    // The trusted controller ordinal is an idempotency slot. Only the exact same
                    // closed Tool event may reuse it; args/results and assistant text are absent
                    // from the marker so an uncertain caller return cannot duplicate transcript.
                    return@withTransaction existingMarkers.size == 1 &&
                        existingMarkers.single().let { marker ->
                            marker.id == markerId &&
                                marker.conversationId == conversationId &&
                                marker.role == MessageRole.TOOL_RECEIPT &&
                                marker.text == storedReceipt
                        }
                }
                if (
                    outcome == TurnToolCommitOutcome.WRITE_REFUSED &&
                    (current.state != TurnOutcomeState.WRITE_PENDING ||
                        current.lastToolName != toolName)
                ) {
                    // Never persist a definitive refusal receipt after a write became unknown.
                    return@withTransaction false
                }

                val now = clock()
                val firstCommitOrdinal = nextMessageOrdinal(
                    messageDao.highestOrdinal(conversationId),
                ) ?: return@withTransaction false
                val receiptOrdinal = if (storedAssistant.isEmpty()) {
                    firstCommitOrdinal
                } else {
                    nextMessageOrdinal(firstCommitOrdinal) ?: return@withTransaction false
                }
                if (storedAssistant.isNotEmpty()) {
                    messageDao.insert(
                        MessageEntity(
                            id = idFactory(),
                            conversationId = conversationId,
                            ordinal = firstCommitOrdinal,
                            role = MessageRole.ASSISTANT,
                            text = storedAssistant,
                            createdAtEpochMillis = now,
                        ),
                    )
                }
                messageDao.insert(
                    MessageEntity(
                        id = markerId,
                        conversationId = conversationId,
                        ordinal = receiptOrdinal,
                        role = MessageRole.TOOL_RECEIPT,
                        text = storedReceipt,
                        createdAtEpochMillis = now,
                    ),
                )

                val outcomeStored = when (outcome) {
                    TurnToolCommitOutcome.READ_COMPLETED -> turnOutcomes.recordTool(
                        turnId = turnId,
                        toolName = toolName,
                        risk = toolRisk,
                        trustedOrdinal = trustedOrdinal,
                    )
                    TurnToolCommitOutcome.WRITE_COMPLETED -> turnOutcomes.recordTool(
                        turnId = turnId,
                        toolName = toolName,
                        risk = toolRisk,
                    )
                    TurnToolCommitOutcome.WRITE_REFUSED ->
                        turnOutcomes.recordWriteRefused(turnId, toolName)
                }
                check(outcomeStored) { "Atomic Tool execution outcome update failed." }
                conversationDao.touch(conversationId, now)
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Atomically stores the final assistant phase and closes its recovery capsule.
     *
     * A completed answer can therefore never be restored beside a stale retry action, and a
     * capsule cannot disappear while its final answer failed to persist. Transient status text is
     * still excluded because callers pass only the assistant phase owned by this turn.
     */
    suspend fun finalizeTurn(
        conversationId: String,
        turnId: String,
        assistantText: String,
        completed: Boolean,
        cancelled: Boolean,
        failureCode: TurnOutcomeFailureCode,
    ): Boolean = runCatching {
        database.withTransaction {
            conversationDao.find(conversationId) ?: return@withTransaction false
            val current = turnOutcomeDao.find(turnId)
            if (current != null && current.conversationId != conversationId) {
                return@withTransaction false
            }
            // A terminal capsule makes a repeated finalization call idempotent and prevents a
            // duplicate assistant phase after an uncertain caller return.
            if (current?.state == TurnOutcomeState.ANSWER_COMPLETE) {
                return@withTransaction true
            }
            val now = clock()
            val storedAssistant = assistantText.sanitized(MAX_MESSAGE_CHARACTERS)
            if (storedAssistant.isNotEmpty()) {
                val ordinal = nextMessageOrdinal(messageDao.highestOrdinal(conversationId))
                    ?: return@withTransaction false
                messageDao.insert(
                    MessageEntity(
                        id = idFactory(),
                        conversationId = conversationId,
                        ordinal = ordinal,
                        role = MessageRole.ASSISTANT,
                        text = storedAssistant,
                        createdAtEpochMillis = now,
                    ),
                )
                conversationDao.touch(conversationId, now)
            }

            if (current == null) return@withTransaction true
            val next = if (completed) {
                current.copy(
                    state = TurnOutcomeState.ANSWER_COMPLETE,
                    recoverability = TurnRecoverability.NONE,
                    failureCode = null,
                    updatedAtEpochMillis = now,
                )
            } else {
                val state = when (current.state) {
                    TurnOutcomeState.READ_EXECUTED,
                    TurnOutcomeState.WRITE_PENDING,
                    TurnOutcomeState.WRITE_UNKNOWN,
                    -> current.state
                    TurnOutcomeState.STARTED,
                    TurnOutcomeState.FAILED,
                    TurnOutcomeState.CANCELLED,
                    -> if (cancelled) TurnOutcomeState.CANCELLED else TurnOutcomeState.FAILED
                    TurnOutcomeState.ANSWER_COMPLETE -> TurnOutcomeState.ANSWER_COMPLETE
                }
                current.copy(
                    state = state,
                    failureCode = failureCode,
                    updatedAtEpochMillis = now,
                )
            }
            check(turnOutcomeDao.update(next) > 0) { "Turn outcome terminal update failed." }
            true
        }
    }.getOrDefault(false)

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
        val recoverableRequestOrdinal = turnOutcomeDao.oldestRecoverableUserOrdinal(
            conversationId = conversationId,
            now = clock(),
        )
        val recoveryProtectedThrough = recoverableRequestOrdinal?.minus(1L) ?: Long.MAX_VALUE
        val deletableThrough = minOf(throughOrdinal, protectedFrom, recoveryProtectedThrough)

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
        .takeCodePoints(maximumCharacters)

    companion object {
        const val DEFAULT_TITLE = "새 대화"
        const val DEFAULT_CONVERSATION_PAGE = 50
        const val DEFAULT_RECENT_MESSAGES = 12
        const val MAX_CONVERSATION_PAGE = 500
        const val MAX_MESSAGES_PER_READ = 500
        const val MAX_TITLE_CHARACTERS = 120
        const val MAX_MESSAGE_CHARACTERS = 8_000
        const val MAX_SUMMARY_CHARACTERS = 2_000
        private const val TOOL_COMMIT_MARKER_PREFIX = "tool-commit:"
    }

    private fun validCommitOutcome(
        outcome: TurnToolCommitOutcome,
        risk: TurnToolRisk,
    ): Boolean = when (outcome) {
        TurnToolCommitOutcome.READ_COMPLETED -> risk == TurnToolRisk.READ_ONLY
        TurnToolCommitOutcome.WRITE_COMPLETED,
        TurnToolCommitOutcome.WRITE_REFUSED,
        -> risk != TurnToolRisk.NONE && risk != TurnToolRisk.READ_ONLY
    }

    private fun toolCommitMarkerPrefix(turnId: String, trustedOrdinal: Int): String =
        "$TOOL_COMMIT_MARKER_PREFIX$turnId:$trustedOrdinal:"

    private fun toolCommitMarkerId(
        prefix: String,
        toolName: String,
        toolRisk: TurnToolRisk,
        outcome: TurnToolCommitOutcome,
    ): String = "$prefix${toolRisk.name}:${outcome.name}:$toolName"

}

private fun MessageEntity.toStoredMessage(): StoredMessage = StoredMessage(
    id = id,
    ordinal = ordinal,
    role = role,
    text = text,
    createdAtEpochMillis = createdAtEpochMillis,
)

/** Message ordinals are positive and never wrap into the negative range on corrupted storage. */
private fun nextMessageOrdinal(current: Long): Long? =
    current.takeIf { it in 0 until Long.MAX_VALUE }?.plus(1L)
