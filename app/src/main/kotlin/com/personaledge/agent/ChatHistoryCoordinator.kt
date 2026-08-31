package com.personaledge.agent

import com.personaledge.core.agent.AutomaticWebSearchPolicy
import com.personaledge.core.agent.PriorWebResultFollowUpPolicy
import com.personaledge.core.agent.TrustedWebSearchRequest
import com.personaledge.core.agent.TurnOutputBudgetPolicy
import com.personaledge.core.data.ConversationEntity
import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.data.MessageEntity
import com.personaledge.core.data.MessageRole
import com.personaledge.core.data.TurnOutcomeRepository
import com.personaledge.core.data.TurnOutcomeFailureCode
import com.personaledge.core.data.TurnRecoverability
import com.personaledge.core.data.TurnToolCommitOutcome
import com.personaledge.core.data.TurnToolRisk
import com.personaledge.core.data.takeCodePoints

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
 * A legacy transcript-only read that can be upgraded to the durable recovery state machine.
 *
 * [text] is the bounded request used for the immediate retry. The conversation and ordinal keep
 * every later recovery capsule bound to the original user-owned transcript row rather than to a
 * follow-up such as "why did you stop?".
 */
internal data class UnfinishedReadRequest(
    val conversationId: String,
    val userMessageOrdinal: Long,
    val text: String,
)

/**
 * One subjectless web-search follow-up bound to the immediately preceding owner USER row.
 *
 * The typed request hides its query outside `core:agent`; [userMessageOrdinal] is the durable
 * recovery authority if the fresh read completes but its final answer is interrupted.
 */
internal data class ContextualWebSearchRequest(
    val conversationId: String,
    val userMessageOrdinal: Long,
    val trustedRequest: TrustedWebSearchRequest,
    val inheritLongFormRequest: Boolean,
) {
    override fun toString(): String =
        "ContextualWebSearchRequest(userMessageOrdinal=$userMessageOrdinal, " +
            "inheritLongFormRequest=$inheritLongFormRequest, query=<redacted>)"
}

/**
 * Content-free identity of the stored answer a prior-web-result transformation is allowed to use.
 *
 * The answer text stays in Room and reaches the model only through [TurnContextBuilder]. Binding
 * the conversation and ordinal here prevents a same-looking answer from another thread, a summary,
 * or an older non-web turn from satisfying the follow-up guard.
 */
internal data class PriorWebResultReference(
    val conversationId: String,
    val assistantMessageOrdinal: Long,
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
    private val turnOutcomes: TurnOutcomeRepository? = null,
) {
    /** Returns the restored transcript, or an empty list when there is nothing to restore. */
    suspend fun restoreMostRecent(): RestoredConversation = runCatching {
        val newest = repository.mostRecentConversation()
            ?: return@runCatching RestoredConversation(null, emptyList())

        RestoredConversation(newest.id, loadEntries(newest.id))
    }.getOrDefault(RestoredConversation(null, emptyList()))

    suspend fun switchTo(conversationId: String): ConversationSwitchResult = try {
        val conversation = repository.findConversation(conversationId)
            ?: return ConversationSwitchResult.NotFound
        ConversationSwitchResult.Success(
            RestoredConversation(conversation.id, loadEntries(conversation.id)),
        )
    } catch (_: Exception) {
        ConversationSwitchResult.StorageUnavailable
    }

    /**
     * Returns the conversation to append to, creating one titled from [firstPrompt] if needed.
     * Null means storage is unavailable and this turn will not be persisted.
     */
    suspend fun ensureConversation(
        activeConversationId: String?,
        firstPrompt: String,
    ): String? {
        return runCatching {
            activeConversationId
                ?.takeIf { conversationId -> repository.findConversation(conversationId) != null }
                ?: repository.createConversation(titleFrom(firstPrompt))
        }.getOrNull()
    }

    suspend fun record(conversationId: String?, role: MessageRole, text: String): Boolean {
        if (conversationId == null) return false
        if (text.isBlank()) return true
        return recordOrdinal(conversationId, role, text) != null
    }

    /** Returns the durable ordinal needed by a content-free turn recovery capsule. */
    suspend fun recordOrdinal(
        conversationId: String?,
        role: MessageRole,
        text: String,
        attachmentSummary: String? = null,
    ): Long? {
        if (conversationId == null) return null
        // An attachment can be the whole message: a photo sent with no typed line still has to be
        // recorded, or the transcript would lose the turn the answer refers to.
        if (text.isBlank() && attachmentSummary == null) return null
        return runCatching {
            repository.appendMessage(conversationId, role, text, attachmentSummary)
        }.getOrNull()
    }

    /** One Room transaction for the transcript phase, app receipt, and closed Tool metadata. */
    suspend fun commitToolExecution(
        conversationId: String?,
        turnId: String,
        assistantText: String,
        toolReceipt: String,
        toolName: String,
        toolRisk: TurnToolRisk,
        trustedOrdinal: Int,
        outcome: TurnToolCommitOutcome,
    ): Boolean {
        if (conversationId == null) return false
        return repository.commitToolExecution(
            conversationId = conversationId,
            turnId = turnId,
            assistantText = assistantText,
            toolReceipt = toolReceipt,
            toolName = toolName,
            toolRisk = toolRisk,
            trustedOrdinal = trustedOrdinal,
            outcome = outcome,
        )
    }

    /** A transient action derived from durable closed state; it is never written to chat history. */
    suspend fun recoveryEntry(conversationId: String?): ChatEntry? {
        if (conversationId == null) return null
        val recovery = turnOutcomes?.latestRecovery(conversationId) ?: return null
        val action = when (recovery.recoverability) {
            TurnRecoverability.REQUERY_READ -> ChatRecoveryAction(
                turnId = recovery.turnId,
                conversationId = recovery.conversationId,
                type = ChatRecoveryType.REQUERY_READ,
                label = "읽기 다시 수행",
                expectedReadTools = recovery.expectedReadTools,
            )
            TurnRecoverability.VERIFY_EXTERNAL_STATE -> ChatRecoveryAction(
                turnId = recovery.turnId,
                conversationId = recovery.conversationId,
                type = ChatRecoveryType.VERIFY_EXTERNAL_STATE,
                label = "외부 상태 확인 완료",
                expectedReadTools = emptyList(),
            )
            TurnRecoverability.NONE -> return null
        }
        val message = when (action.type) {
            ChatRecoveryType.REQUERY_READ ->
                "이 읽기 요청의 최종 답변이 완료되지 않았습니다. 이전 결과를 이어 쓰지 않고 새로 조회할 수 있습니다."
            ChatRecoveryType.VERIFY_EXTERNAL_STATE ->
                TurnRecoveryPolicy.verificationGuidance(recovery)
        }
        return ChatEntry(
            id = recoveryEntryId(recovery.turnId),
            role = ChatRole.STATUS,
            text = message,
            recoveryAction = action,
        )
    }

    /** Verification obligations survive transcript deletion and are therefore queried globally. */
    suspend fun unresolvedSideEffectEntries(): List<ChatEntry> =
        turnOutcomes?.unresolvedSideEffects().orEmpty().map { recovery ->
            val action = ChatRecoveryAction(
                turnId = recovery.turnId,
                conversationId = recovery.conversationId,
                type = ChatRecoveryType.VERIFY_EXTERNAL_STATE,
                label = "외부 상태 확인 완료",
                expectedReadTools = emptyList(),
            )
            ChatEntry(
                id = recoveryEntryId(recovery.turnId),
                role = ChatRole.STATUS,
                text = TurnRecoveryPolicy.verificationGuidance(recovery),
                recoveryAction = action,
            )
        }

    suspend fun recoveryRecord(action: ChatRecoveryAction) =
        turnOutcomes?.recovery(action.turnId, action.conversationId)?.takeIf { recovery ->
            when (action.type) {
                ChatRecoveryType.REQUERY_READ ->
                    recovery.recoverability == TurnRecoverability.REQUERY_READ &&
                        TurnRecoveryPolicy.matchesExpectedReadTools(
                            expected = recovery.expectedReadTools,
                            observed = action.expectedReadTools,
                        )
                ChatRecoveryType.VERIFY_EXTERNAL_STATE ->
                    recovery.recoverability == TurnRecoverability.VERIFY_EXTERNAL_STATE &&
                        action.expectedReadTools.isEmpty()
            }
        }

    suspend fun dismissRecovery(action: ChatRecoveryAction): Boolean =
        turnOutcomes?.dismissVerification(action.turnId, action.conversationId) ?: false

    suspend fun finalizeTurn(
        conversationId: String?,
        turnId: String,
        assistantText: String,
        completed: Boolean,
        cancelled: Boolean,
        failureCode: TurnOutcomeFailureCode,
    ): Boolean {
        if (conversationId == null) return false
        return repository.finalizeTurn(
            conversationId = conversationId,
            turnId = turnId,
            assistantText = assistantText,
            completed = completed,
            cancelled = cancelled,
            failureCode = failureCode,
        )
    }

    /**
     * Starts the durable capsule for a normal, typed-recovery, or legacy-transcript retry.
     *
     * A legacy retry deliberately points at [UnfinishedReadRequest.userMessageOrdinal]. The newly
     * persisted follow-up stays visible in the transcript, but can never replace the original read
     * request as the recovery authority if this fresh read is interrupted again.
     */
    internal suspend fun startTurnRecoveryCapsule(
        turnId: String,
        conversationId: String?,
        persistedUserMessageOrdinal: Long?,
        unfinishedReadRequest: UnfinishedReadRequest?,
        contextualWebSearchRequest: ContextualWebSearchRequest? = null,
        predecessorTurnId: String?,
    ): Boolean {
        val outcomeRepository = turnOutcomes ?: return false
        if (
            conversationId == null ||
            persistedUserMessageOrdinal == null ||
            persistedUserMessageOrdinal <= 0L ||
            listOfNotNull(unfinishedReadRequest, contextualWebSearchRequest).size > 1 ||
            ((unfinishedReadRequest != null || contextualWebSearchRequest != null) &&
                predecessorTurnId != null)
        ) {
            return false
        }
        if (unfinishedReadRequest != null) {
            if (
                unfinishedReadRequest.conversationId != conversationId ||
                unfinishedReadRequest.userMessageOrdinal <= 0L ||
                unfinishedReadRequest.text.isBlank()
            ) {
                return false
            }
            return outcomeRepository.start(
                turnId = turnId,
                conversationId = conversationId,
                userMessageOrdinal = unfinishedReadRequest.userMessageOrdinal,
            )
        }
        if (contextualWebSearchRequest != null) {
            if (
                contextualWebSearchRequest.conversationId != conversationId ||
                contextualWebSearchRequest.userMessageOrdinal <= 0L
            ) {
                return false
            }
            return outcomeRepository.startContextualRead(
                turnId = turnId,
                conversationId = conversationId,
                userMessageOrdinal = persistedUserMessageOrdinal,
                recoverySourceUserMessageOrdinal =
                    contextualWebSearchRequest.userMessageOrdinal,
            )
        }
        return if (predecessorTurnId == null) {
            outcomeRepository.start(
                turnId = turnId,
                conversationId = conversationId,
                userMessageOrdinal = persistedUserMessageOrdinal,
            )
        } else {
            outcomeRepository.startSuccessor(
                predecessorTurnId = predecessorTurnId,
                turnId = turnId,
                conversationId = conversationId,
                userMessageOrdinal = persistedUserMessageOrdinal,
            )
        }
    }

    /**
     * Resolves a contextual web instruction from bounded USER rows in this conversation only.
     *
     * Ordinarily the source is the latest completed USER/ASSISTANT pair. A failed correction can
     * leave a trailing USER row, so that row may be skipped only when the core policy classifies it
     * as a closed search correction. Assistant text, summaries, memories, and Tool results are
     * never candidate query sources, and an unrelated USER row stops the walk.
     */
    internal suspend fun contextualWebSearchRequestForFollowUp(
        conversationId: String?,
        followUp: String,
    ): ContextualWebSearchRequest? {
        if (conversationId == null) return null
        return runCatching {
            val messages = repository.listMessages(
                conversationId,
                CONTEXTUAL_SEARCH_MESSAGE_WINDOW,
            )
            val last = messages.lastOrNull() ?: return@runCatching null
            if (last.role == MessageRole.USER &&
                !AutomaticWebSearchPolicy.isContextualSearchCorrection(last.text)
            ) {
                return@runCatching null
            }
            messages.asReversed()
                .asSequence()
                .filter { message -> message.role == MessageRole.USER }
                .take(MAX_CONTEXTUAL_USER_ROWS)
                .forEach { previousUser ->
                    val trusted = AutomaticWebSearchPolicy.contextualRequestOrNull(
                        followUp = followUp,
                        previousUserRequest = previousUser.text,
                    )
                    if (trusted != null) {
                        val completed = messages.any { message ->
                            message.ordinal > previousUser.ordinal &&
                                message.role == MessageRole.ASSISTANT
                        }
                        if (!completed) return@runCatching null
                        return@runCatching ContextualWebSearchRequest(
                            conversationId = conversationId,
                            userMessageOrdinal = previousUser.ordinal,
                            trustedRequest = trusted,
                            inheritLongFormRequest =
                                TurnOutputBudgetPolicy.requestsLongForm(previousUser.text),
                        )
                    }
                    if (!AutomaticWebSearchPolicy.isContextualSearchCorrection(previousUser.text)) {
                        return@runCatching null
                    }
                }
            null
        }.getOrNull()
    }

    /**
     * Resolves the exact answer for a request that transforms the preceding web-search result.
     *
     * Only the latest stored turn can qualify. It must end in a nonblank ASSISTANT row and contain
     * this app's exact completed `web_search` receipt after that turn's latest USER row and before
     * the answer. The bounded scan fails closed on a fresh thread, storage failure, incomplete
     * search, or a newer non-web turn.
     */
    internal suspend fun priorWebResultForFollowUp(
        conversationId: String?,
        followUp: String,
    ): PriorWebResultReference? {
        if (
            conversationId == null ||
            !PriorWebResultFollowUpPolicy.matches(followUp)
        ) {
            return null
        }
        return runCatching {
            val messages = repository.listMessages(
                conversationId,
                PRIOR_WEB_RESULT_MESSAGE_WINDOW,
            )
            val answer = messages.lastOrNull()?.takeIf { message ->
                message.role == MessageRole.ASSISTANT && message.text.isNotBlank()
            } ?: return@runCatching null
            val latestUserIndex = messages.indexOfLast { message ->
                message.role == MessageRole.USER
            }
            if (latestUserIndex < 0) return@runCatching null
            val hasCompletedWebSearch = messages
                .subList(latestUserIndex + 1, messages.size)
                .any { message ->
                    message.ordinal < answer.ordinal &&
                        message.role == MessageRole.TOOL_RECEIPT &&
                        message.text == WEB_SEARCH_READ_RECEIPT
                }
            if (!hasCompletedWebSearch) return@runCatching null
            PriorWebResultReference(
                conversationId = conversationId,
                assistantMessageOrdinal = answer.ordinal,
            )
        }.getOrNull()
    }

    /**
     * Finds the original read request only when the stored transcript ends at a closed-set
     * READ_ONLY Tool receipt and the current message asks to continue the missing answer.
     *
     * Tool payloads are deliberately not persisted. Recovery therefore means performing the
     * original read again, never treating the old receipt as if it contained an answer. Only a
     * closed set of content-free READ_ONLY receipts is accepted so a write can never be replayed.
     */
    internal suspend fun unfinishedReadRequestForFollowUp(
        conversationId: String?,
        followUp: String,
    ): UnfinishedReadRequest? {
        if (conversationId == null || !UnfinishedReadFollowUp.matches(followUp)) return null
        return runCatching {
            // Schema-6 state is authoritative. A VERIFY capsule must never be bypassed by a
            // transcript phrase, and a REQUERY capsule has its own exactly-scoped UI action.
            if (turnOutcomes?.latestRecovery(conversationId) != null) return@runCatching null
            val messages = repository.listMessages(conversationId, RECOVERY_MESSAGE_WINDOW)
            val last = messages.lastOrNull() ?: return@runCatching null
            val userIndex = messages.indexOfLast { message -> message.role == MessageRole.USER }
            if (userIndex < 0) return@runCatching null
            val afterUser = messages.drop(userIndex + 1)
            val toolReceipts = afterUser.filter { message -> message.role == MessageRole.TOOL_RECEIPT }
            val original = messages[userIndex].takeIf {
                last.role == MessageRole.TOOL_RECEIPT &&
                    last.text in RECOVERABLE_READ_RECEIPTS &&
                    toolReceipts.isNotEmpty() &&
                    toolReceipts.all { receipt -> receipt.text in RECOVERABLE_READ_RECEIPTS }
            }
            original?.let { message ->
                message.text
                    .trim()
                    .takeCodePoints(MAX_RECOVERED_REQUEST_CHARACTERS)
                    .takeIf(String::isNotBlank)
                    ?.let { boundedText ->
                        UnfinishedReadRequest(
                            conversationId = conversationId,
                            userMessageOrdinal = message.ordinal,
                            text = boundedText,
                        )
                    }
            }
        }.getOrNull()
    }

    /** Role-aware context for short corrections such as "서울이 아니라 동탄" after weather. */
    suspend fun hasRecentWeatherRead(conversationId: String?): Boolean {
        if (conversationId == null) return false
        return runCatching {
            repository.listMessages(conversationId, RECOVERY_MESSAGE_WINDOW)
                .takeLast(RECENT_WEATHER_CONTEXT_MESSAGES)
                .any { message ->
                    message.role == MessageRole.TOOL_RECEIPT &&
                        message.text == WEATHER_READ_RECEIPT
                }
        }.getOrDefault(false)
    }

    suspend fun listConversations(): List<ConversationSummaryUi> = runCatching {
        repository.recentConversations().map(ConversationEntity::toSummary)
    }.getOrDefault(emptyList())

    suspend fun delete(conversationId: String): Boolean =
        runCatching { repository.deleteConversation(conversationId) }.getOrDefault(false)

    suspend fun deleteAll(): Boolean =
        runCatching { repository.deleteAllConversations() }.isSuccess

    private suspend fun loadEntries(conversationId: String): List<ChatEntry> = buildList {
        addAll(repository.listMessages(conversationId).map(MessageEntity::toChatEntry))
        recoveryEntry(conversationId)?.let(::add)
    }

    /** A first line is a better handle than a timestamp when scanning a list of threads. */
    private fun titleFrom(prompt: String): String = prompt
        .lineSequence()
        .firstOrNull { line -> line.isNotBlank() }
        ?.trim()
        ?.takeCodePoints(MAX_TITLE_CHARACTERS)
        .orEmpty()

    private companion object {
        const val MAX_TITLE_CHARACTERS = 40
        const val MAX_RECOVERED_REQUEST_CHARACTERS = 500
        const val RECOVERY_MESSAGE_WINDOW = 12
        const val CONTEXTUAL_SEARCH_MESSAGE_WINDOW = 8
        const val MAX_CONTEXTUAL_USER_ROWS = 3
        const val PRIOR_WEB_RESULT_MESSAGE_WINDOW = 12
        const val RECENT_WEATHER_CONTEXT_MESSAGES = 6
        const val WEB_SEARCH_READ_RECEIPT = "웹 검색을 완료했습니다."
        const val WEATHER_READ_RECEIPT = "현재 및 오늘 날씨를 확인했습니다."
        val RECOVERABLE_READ_RECEIPTS = setOf(
            WEB_SEARCH_READ_RECEIPT,
            WEATHER_READ_RECEIPT,
            "캘린더에서 일정을 읽었습니다.",
            "다음 알람 시각을 확인했습니다.",
            "수집된 카카오톡 알림을 검색했습니다.",
            "네이버 지도에서 이동 시간을 조회했습니다.",
            "로컬 리마인더를 조회했습니다.",
        )

        fun recoveryEntryId(turnId: String) = "turn-recovery-$turnId"
    }
}

internal object UnfinishedReadFollowUp {
    fun matches(value: String): Boolean {
        val normalized = value.trim().lowercase()
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length) > 120) {
            return false
        }
        return STOPPED_PHRASES.any(normalized::contains) ||
            CONTINUATION_PHRASES.any(normalized::contains) ||
            (normalized.contains("답변") && NEGATIVE_ANSWER_TERMS.any(normalized::contains))
    }

    private val CONTINUATION_PHRASES = listOf(
        "계속해", "계속 해", "마저", "이어줘", "이어 줘", "결과 알려", "다시 알려",
        "답변해줘", "답변해 줘", "답해줘", "답해 줘", "continue", "finish the answer",
    )
    private val NEGATIVE_ANSWER_TERMS = listOf(
        "안 해", "안해", "못 해", "못해", "없어", "않", "왜",
    )
    private val STOPPED_PHRASES = listOf(
        "왜 중단", "왜 멈", "중단했", "중단됐", "멈췄", "왜 실패", "why did you stop",
    )
}

enum class ChatRecoveryType {
    REQUERY_READ,
    VERIFY_EXTERNAL_STATE,
}

class ChatRecoveryAction(
    val turnId: String,
    val conversationId: String,
    val type: ChatRecoveryType,
    val label: String,
    expectedReadTools: List<String> = emptyList(),
) {
    val expectedReadTools: List<String> = expectedReadTools.toList()

    init {
        require(
            when (type) {
                ChatRecoveryType.REQUERY_READ ->
                    TurnRecoveryPolicy.validExpectedReadTools(expectedReadTools)
                ChatRecoveryType.VERIFY_EXTERNAL_STATE -> expectedReadTools.isEmpty()
            },
        )
    }

    /** [label] is UI text and must never be copied into incidental diagnostic rendering. */
    override fun toString(): String =
        "ChatRecoveryAction(" +
            "turnId=$turnId, conversationId=$conversationId, type=$type, " +
            "expectedReadTools=$expectedReadTools)"
}

data class RestoredConversation(
    val conversationId: String?,
    val entries: List<ChatEntry>,
)

sealed interface ConversationSwitchResult {
    data class Success(val restored: RestoredConversation) : ConversationSwitchResult

    data object NotFound : ConversationSwitchResult

    data object StorageUnavailable : ConversationSwitchResult
}

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
    // Only this app writes the column, and only these codes render; anything else shows nothing
    // rather than putting an unrecognized stored string in front of the owner.
    attachmentLabel = MessageAttachmentSummary
        .decodeOrNull(attachmentSummary)
        ?.let(MediaAttachmentPresentation::transcriptLabel),
)
