package com.personaledge.agent

import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.data.MessageRole
import com.personaledge.core.data.StoredMessage
import com.personaledge.core.llm.MAX_USER_PROMPT_BYTES
import java.text.Normalizer
import java.util.Locale

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
    private val sourceBytesBeforeSummary: Int = DEFAULT_SOURCE_BYTES_BEFORE_SUMMARY,
    private val maximumPromptBytes: Int = MAX_PROMPT_BYTES,
) {
    init {
        require(messagesBeforeSummary > 0)
        require(sourceBytesBeforeSummary in 1..MAX_USER_PROMPT_BYTES)
        require(maximumPromptBytes in 1..MAX_USER_PROMPT_BYTES)
    }

    class SummaryRequest internal constructor(
        val conversationId: String,
        val prompt: String,
        val throughOrdinal: Long,
        /** Bounded prior capsule that was actually shown to the model. */
        val previousSummary: String?,
        sourceEvidence: String,
        newEvidence: String,
    ) {
        private val sourceEvidence = sourceEvidence
        private val newEvidence = newEvidence

        internal fun supportsLiterals(candidate: String): Boolean =
            SummaryLiteralValidator.supports(sourceEvidence, candidate)

        internal fun preservesPreviousAnchors(candidate: String): Boolean =
            SummaryLiteralValidator.preservesPrevious(
                previous = previousSummary.orEmpty(),
                newEvidence = newEvidence,
                candidate = candidate,
            )

        override fun toString(): String =
            "SummaryRequest(throughOrdinal=$throughOrdinal, " +
                "promptBytes=${prompt.utf8ByteSize()}, " +
                "hasPreviousSummary=${previousSummary != null})"
    }

    /** Null when the thread is too short, already summarized, or unreadable. */
    suspend fun requestFor(conversationId: String): SummaryRequest? {
        val conversation = runCatching { repository.findConversation(conversationId) }
            .getOrNull()
            ?: return null

        val pending = runCatching {
            repository.listMessagesAfter(
                conversationId = conversationId,
                afterOrdinal = conversation.summarizedThroughMessageOrdinal,
                limit = MAX_TRANSCRIPT_MESSAGES,
            )
        }.getOrNull().orEmpty()
        if (!shouldSummarize(pending.map(::toStored))) return null

        val previousSummary = conversation.summary
            ?.trim()
            ?.takeHeadAndTailUtf8(MAX_SUMMARY_UTF8_BYTES)
            ?.takeIf(String::isNotBlank)
        val built = buildPrompt(previousSummary, pending.map(::toStored)) ?: return null

        return SummaryRequest(
            conversationId = conversationId,
            prompt = built.prompt,
            throughOrdinal = built.throughOrdinal,
            previousSummary = previousSummary,
            sourceEvidence = built.sourceEvidence,
            newEvidence = built.newEvidence,
        )
    }

    /** Stores a model-produced summary. Returns false when it was unusable or already superseded. */
    suspend fun acceptSummary(request: SummaryRequest, summary: String): Boolean {
        val cleaned = summary.trim()
        val codePoints = cleaned.codePointCount(0, cleaned.length)
        if (codePoints !in MIN_SUMMARY_CHARACTERS..MAX_SUMMARY_CHARACTERS) return false
        if (cleaned.utf8ByteSize() > MAX_SUMMARY_UTF8_BYTES) return false
        if (!request.supportsLiterals(cleaned)) return false
        if (!request.preservesPreviousAnchors(cleaned)) return false

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
    private fun buildPrompt(
        existingSummary: String?,
        pending: List<StoredMessage>,
    ): PromptBuild? {
        val prompt = StringBuilder().apply {
            append("기존 압축 문맥과 새 대화를 이후 요청용 최신 한국어 압축 문맥 하나로 다시 작성하세요. ")
            append("UTF-8 480바이트 이내로, 필요한 항목만 [목표], [결정·제약], ")
            append("[미완료·참조] 순서로 작성하세요. 현재 사용자의 목표와 결정, 명시한 제약과 선호, ")
            append("아직 해결되지 않은 질문과 지시 대상, 사람·장소·날짜·약속을 보존하세요. ")
            append("기억 요청 문자열·코드·식별자·숫자는 원문 그대로 유지하세요. 충돌하면 최신 사용자 ")
            append("정정으로 교체하고 폐기값·인사·확인 문구는 버리세요. 요약문만 출력하세요.\n\n")
            existingSummary?.takeIf(String::isNotBlank)?.let { summary ->
                append("[기존 압축 문맥]\n")
                append(summary)
                append("\n\n")
            }
            append("[새 대화]\n")
        }
        var usedBytes = prompt.toString().utf8ByteSize()
        if (usedBytes > maximumPromptBytes) return null

        val sourceEvidence = StringBuilder()
        existingSummary?.let(sourceEvidence::append)
        val newEvidence = StringBuilder()
        var throughOrdinal: Long? = null
        for (message in pending) {
            // Long user requests often put the decisive instruction at the end. Preserve both
            // ends instead of allowing an early background paragraph to erase the actual ask.
            val prefix = "${message.role.label()}: "
            val suffix = "\n"
            val contentBudget = minOf(
                MAX_QUOTED_MESSAGE_UTF8_BYTES,
                maximumPromptBytes - usedBytes - prefix.utf8ByteSize() - suffix.utf8ByteSize(),
            )
            if (contentBudget < MIN_QUOTED_MESSAGE_UTF8_BYTES) break
            val quotedText = message.text.takeHeadAndTailUtf8(contentBudget)
            if (quotedText.isBlank()) break
            val block = prefix + quotedText + suffix
            val blockBytes = block.utf8ByteSize()
            if (blockBytes > maximumPromptBytes - usedBytes) break

            prompt.append(block)
            usedBytes += blockBytes
            if (sourceEvidence.isNotEmpty()) sourceEvidence.append('\n')
            sourceEvidence.append(quotedText)
            if (newEvidence.isNotEmpty()) newEvidence.append('\n')
            newEvidence.append(quotedText)
            throughOrdinal = message.ordinal
        }
        val includedThroughOrdinal = throughOrdinal ?: return null
        val builtPrompt = prompt.toString()
        check(usedBytes == builtPrompt.utf8ByteSize())
        check(usedBytes <= maximumPromptBytes)
        check(usedBytes <= MAX_USER_PROMPT_BYTES)
        return PromptBuild(
            prompt = builtPrompt,
            throughOrdinal = includedThroughOrdinal,
            sourceEvidence = sourceEvidence.toString(),
            newEvidence = newEvidence.toString(),
        )
    }

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

    /**
     * Message count handles ordinary chat; byte pressure catches a few unusually long turns before
     * the bounded context window has to omit their older parts. UTF-8 bytes are a conservative
     * tokenizer-independent proxy because the runtime input boundary is itself byte based.
     */
    private fun shouldSummarize(pending: List<StoredMessage>): Boolean {
        if (pending.size >= messagesBeforeSummary) return true
        var sourceBytes = 0
        for (message in pending) {
            val next = message.role.label().utf8ByteSize() + 2 + message.text.utf8ByteSize() + 1
            sourceBytes = (sourceBytes + next).coerceAtMost(sourceBytesBeforeSummary)
            if (sourceBytes >= sourceBytesBeforeSummary) return true
        }
        return false
    }

    companion object {
        const val DEFAULT_MESSAGES_BEFORE_SUMMARY = 10
        const val DEFAULT_SOURCE_BYTES_BEFORE_SUMMARY = MAX_USER_PROMPT_BYTES * 3 / 4
        const val MAX_SUMMARY_CHARACTERS = 300
        const val MAX_SUMMARY_UTF8_BYTES = 480
        const val MIN_SUMMARY_CHARACTERS = 8
        const val MAX_PROMPT_BYTES = MAX_USER_PROMPT_BYTES
        private const val MAX_TRANSCRIPT_MESSAGES = 60
        private const val MAX_QUOTED_MESSAGE_UTF8_BYTES = 600
        private const val MIN_QUOTED_MESSAGE_UTF8_BYTES = 24
    }

    private class PromptBuild(
        val prompt: String,
        val throughOrdinal: Long,
        val sourceEvidence: String,
        val newEvidence: String,
    )
}

private fun String.takeHeadAndTailUtf8(maximumBytes: Int): String {
    require(maximumBytes > 0)
    if (utf8ByteSize() <= maximumBytes) return this

    val separator = " … "
    val separatorBytes = separator.utf8ByteSize()
    if (maximumBytes <= separatorBytes) return truncateUtf8Prefix(maximumBytes)
    val contentBudget = maximumBytes - separatorBytes
    val head = truncateUtf8Prefix((contentBudget + 1) / 2)
    val tail = truncateUtf8Tail(contentBudget - head.utf8ByteSize())
    return (head + separator + tail).also { result ->
        check(result.utf8ByteSize() <= maximumBytes)
    }
}

private fun String.truncateUtf8Prefix(maximumBytes: Int): String {
    val result = StringBuilder()
    var used = 0
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val encoded = String(Character.toChars(codePoint))
        val encodedBytes = encoded.utf8ByteSize()
        if (used + encodedBytes > maximumBytes) break
        result.append(encoded)
        used += encodedBytes
        index += Character.charCount(codePoint)
    }
    return result.toString()
}

private fun String.truncateUtf8Tail(maximumBytes: Int): String {
    val result = StringBuilder()
    var used = 0
    var index = length
    while (index > 0) {
        val codePoint = codePointBefore(index)
        val encoded = String(Character.toChars(codePoint))
        val encodedBytes = encoded.utf8ByteSize()
        if (used + encodedBytes > maximumBytes) break
        result.insert(0, encoded)
        used += encodedBytes
        index -= Character.charCount(codePoint)
    }
    return result.toString()
}

/**
 * Rejects only summary literals whose exact source cannot be established from the window the
 * model actually saw. Ordinary prose may still be abstractive; dates, numbers, URLs, email
 * addresses, identifiers, and explicitly quoted strings may not be invented.
 */
private object SummaryLiteralValidator {
    private enum class LiteralKind {
        DATE,
        NUMBER,
        URL,
        EMAIL,
        IDENTIFIER,
        DATE_WORD,
        QUOTED,
    }

    private data class Literal(val kind: LiteralKind, val value: String)

    fun supports(source: String, candidate: String): Boolean {
        val candidateLiterals = extract(candidate)
        if (!extract(source).containsAll(candidateLiterals)) return false
        val normalizedSource = normalize(source)
        return quotedLiterals(candidate).all { literal ->
            normalizedSource.contains(normalize(literal))
        }
    }

    /**
     * A rolling capsule may replace an old literal only when the new window explicitly repeats the
     * old value, marks it as corrected, and supplies a same-kind replacement that survives in the
     * candidate. Unrelated prior anchors therefore cannot disappear during recompression.
     */
    fun preservesPrevious(previous: String, newEvidence: String, candidate: String): Boolean {
        if (previous.isBlank()) return true
        val required = anchors(previous)
        val retained = anchors(candidate)
        val missing = required - retained
        if (missing.isEmpty()) return true

        val normalizedNewEvidence = normalize(newEvidence)
        if (!CORRECTION_PATTERN.containsMatchIn(normalizedNewEvidence)) return false
        val correctionEvidence = anchors(newEvidence)
        return missing.all { old ->
            old in correctionEvidence &&
                correctionEvidence.any { replacement ->
                    replacement.kind == old.kind && replacement.value != old.value &&
                        replacement in retained
                }
        }
    }

    private fun anchors(value: String): Set<Literal> = buildSet {
        addAll(extract(value))
        quotedLiterals(value).forEach { quoted ->
            add(Literal(LiteralKind.QUOTED, normalize(quoted)))
        }
    }

    private fun extract(value: String): Set<Literal> = buildSet {
        val canonical = canonicalize(value)
        URL_PATTERN.findAll(canonical).forEach { match ->
            add(Literal(LiteralKind.URL, normalizeUrl(match.value)))
        }
        EMAIL_PATTERN.findAll(canonical).forEach { match ->
            add(Literal(LiteralKind.EMAIL, normalize(match.value)))
        }
        DATE_YMD_PATTERN.findAll(canonical).forEach { match ->
            add(
                Literal(
                    LiteralKind.DATE,
                    "${match.groupValues[1].toInt()}-" +
                        "${match.groupValues[2].toInt()}-${match.groupValues[3].toInt()}",
                ),
            )
        }
        DATE_MD_PATTERN.findAll(canonical).forEach { match ->
            add(
                Literal(
                    LiteralKind.DATE,
                    "${match.groupValues[1].toInt()}-${match.groupValues[2].toInt()}",
                ),
            )
        }
        DATE_WORD_PATTERN.findAll(canonical).forEach { match ->
            add(Literal(LiteralKind.DATE_WORD, normalize(match.value)))
        }
        ENGLISH_WEEKDAY_PATTERN.findAll(canonical).forEach { match ->
            add(Literal(LiteralKind.DATE_WORD, normalize(match.value)))
        }
        NUMBER_PATTERN.findAll(canonical).forEach { match ->
            add(Literal(LiteralKind.NUMBER, normalizeNumber(match.value)))
        }
        IDENTIFIER_PATTERN.findAll(canonical).forEach { match ->
            val identifier = match.value
            if (identifier.any(Char::isLetter) && identifier.any(Char::isDigit)) {
                add(Literal(LiteralKind.IDENTIFIER, normalize(identifier)))
            }
        }
    }

    private fun quotedLiterals(value: String): Set<String> = buildSet {
        val canonical = canonicalize(value)
        QUOTED_PATTERNS.forEach { pattern ->
            pattern.findAll(canonical).forEach { match ->
                match.groups[1]?.value
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }
    }

    private fun normalize(value: String): String = canonicalize(value)
        .trim()
        .replace(WHITESPACE_PATTERN, " ")
        .lowercase(Locale.ROOT)

    private fun canonicalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .filterNot { character -> Character.getType(character) == Character.FORMAT.toInt() }

    private fun normalizeNumber(value: String): String = normalize(value).replace(",", "")

    private fun normalizeUrl(value: String): String = normalize(value)
        .trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '。')

    private val WHITESPACE_PATTERN = Regex("\\s+")
    private val URL_PATTERN = Regex(
        "(?i)\\b(?:https?://|www\\.)[a-z0-9][a-z0-9._~:/?#\\[\\]@!\$&'()*+,;=%-]*",
    )
    private val EMAIL_PATTERN = Regex(
        """(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,63}(?![a-z0-9._%+-])""",
    )
    private val DATE_YMD_PATTERN = Regex(
        """(?<!\d)(\d{4})\s*(?:년\s*|[-./])(\d{1,2})\s*(?:월\s*|[-./])(\d{1,2})\s*일?(?!\d)""",
    )
    private val DATE_MD_PATTERN = Regex("""(?<!\d)(\d{1,2})\s*월\s*(\d{1,2})\s*일(?!\d)""")
    private val DATE_WORD_PATTERN = Regex(
        """오늘|내일|모레|어제|그제|월요일|화요일|수요일|목요일|금요일|토요일|일요일|이번\s*주|다음\s*주|다다음\s*주""",
    )
    private val ENGLISH_WEEKDAY_PATTERN = Regex(
        """(?i)\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
    )
    private val NUMBER_PATTERN = Regex(
        """(?<![a-zA-Z0-9_])[-+]?\d[\d,]*(?:\.\d+)?(?:%|퍼센트|원|달러|분|시간|일|개월|년|월|시)?(?![a-zA-Z0-9_])""",
    )
    private val IDENTIFIER_PATTERN = Regex(
        """(?i)(?<![a-z0-9])[a-z0-9][a-z0-9._:-]{1,63}(?![a-z0-9])""",
    )
    private val QUOTED_PATTERNS = listOf(
        Regex("\"([^\"\\n]{1,120})\""),
        Regex("'([^'\\n]{1,120})'"),
        Regex("“([^”\\n]{1,120})”"),
        Regex("‘([^’\\n]{1,120})’"),
        Regex("「([^」\\n]{1,120})」"),
        Regex("『([^』\\n]{1,120})』"),
    )
    private val CORRECTION_PATTERN = Regex(
        "정정|폐기|취소|변경|대신|바꾸|교체|replace|correct|cancel|change",
    )
}

private fun String.utf8ByteSize(): Int = toByteArray(Charsets.UTF_8).size
