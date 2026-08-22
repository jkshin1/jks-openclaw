package com.personaledge.agent

import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.data.MessageRole
import com.personaledge.core.data.StoredMessage

/**
 * Decides whether a thread needs a new summary and builds the request for one.
 *
 * Kept free of the runtime so the thresholds and the prompt are testable without a model. The
 * caller runs the returned request through the tool-free turn budget and hands the answer back to
 * [acceptSummary].
 *
 * Summarizing costs a whole extra model turn, and on-device decode is the slow part of this app.
 * It therefore runs only after a user turn has finished, only when enough new messages have
 * accumulated, and never while another turn is in flight.
 */
class ConversationSummarizer(
    private val repository: ConversationRepository,
    private val messagesBeforeSummary: Int = DEFAULT_MESSAGES_BEFORE_SUMMARY,
) {
    data class SummaryRequest(
        val conversationId: String,
        val prompt: String,
        val throughOrdinal: Long,
    )

    /** Null when the thread is too short, already summarized, or unreadable. */
    suspend fun requestFor(conversationId: String): SummaryRequest? {
        val conversation = runCatching { repository.findConversation(conversationId) }
            .getOrNull()
            ?: return null

        val transcript = runCatching {
            repository.listMessages(conversationId, limit = MAX_TRANSCRIPT_MESSAGES)
        }.getOrNull().orEmpty()

        val pending = transcript.filter { message ->
            message.ordinal > conversation.summarizedThroughMessageOrdinal
        }
        if (pending.size < messagesBeforeSummary) return null

        return SummaryRequest(
            conversationId = conversationId,
            prompt = buildPrompt(conversation.summary, pending.map(::toStored)),
            throughOrdinal = pending.last().ordinal,
        )
    }

    /** Stores a model-produced summary. Returns false when it was unusable or already superseded. */
    suspend fun acceptSummary(request: SummaryRequest, summary: String): Boolean {
        val cleaned = summary.trim().take(MAX_SUMMARY_CHARACTERS)
        if (cleaned.length < MIN_SUMMARY_CHARACTERS) return false

        return runCatching {
            repository.replaceSummary(
                conversationId = request.conversationId,
                summary = cleaned,
                throughOrdinal = request.throughOrdinal,
            )
        }.getOrDefault(false)
    }

    /**
     * The transcript is quoted as material to compress, and the instruction says to describe it.
     *
     * Stored messages include the user's own words and app-authored tool receipts, so this is not
     * hostile input — but the framing still keeps it as data rather than as a new instruction.
     */
    private fun buildPrompt(existingSummary: String?, pending: List<StoredMessage>): String =
        buildString {
            append("다음 대화 내용을 한국어 3문장 이내로 요약하세요. ")
            append("사람 이름, 장소, 날짜, 약속처럼 나중에 다시 필요할 사실만 남기고 ")
            append("인사말과 확인 문구는 버리세요. 요약문만 출력하세요.\n\n")
            existingSummary?.takeIf(String::isNotBlank)?.let { summary ->
                append("[기존 요약]\n")
                append(summary.take(MAX_SUMMARY_CHARACTERS))
                append("\n\n")
            }
            append("[새 대화]\n")
            pending.forEach { message ->
                append(message.role.label())
                append(": ")
                append(message.text.take(MAX_QUOTED_MESSAGE_CHARACTERS))
                append('\n')
            }
        }.take(MAX_PROMPT_CHARACTERS)

    private fun MessageRole.label(): String = when (this) {
        MessageRole.USER -> "사용자"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.TOOL_RECEIPT -> "실행"
    }

    private fun toStored(message: com.personaledge.core.data.MessageEntity) = StoredMessage(
        id = message.id,
        ordinal = message.ordinal,
        role = message.role,
        text = message.text,
        createdAtEpochMillis = message.createdAtEpochMillis,
    )

    companion object {
        const val DEFAULT_MESSAGES_BEFORE_SUMMARY = 10
        const val MAX_SUMMARY_CHARACTERS = 300
        const val MIN_SUMMARY_CHARACTERS = 8
        private const val MAX_TRANSCRIPT_MESSAGES = 60
        private const val MAX_QUOTED_MESSAGE_CHARACTERS = 300
        private const val MAX_PROMPT_CHARACTERS = 4_000
    }
}
