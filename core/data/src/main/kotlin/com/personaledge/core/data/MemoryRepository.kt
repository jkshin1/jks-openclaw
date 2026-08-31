package com.personaledge.core.data

import androidx.room.withTransaction
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.math.ln

sealed interface RememberResult {
    data class Saved(val memory: MemoryEntity, val replacedExisting: Boolean) : RememberResult

    data object CapacityReached : RememberResult

    data object Invalid : RememberResult
}

/**
 * User-controlled, cross-thread memory storage.
 *
 * Content is bounded and normalized here rather than trusting either a model Tool call or a UI
 * field. The database lives in `noBackupFilesDir`, like chat history, and never participates in
 * Android backup or device transfer.
 */
class MemoryRepository(
    private val database: PersonalEdgeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val memoryDao = database.memoryDao()

    suspend fun remember(
        rawContent: String,
        category: MemoryCategory = MemoryCategory.FACT,
        validUntilEpochMillis: Long? = null,
        supersedesId: String? = null,
    ): RememberResult {
        val content = MemoryTextPolicy.sanitize(rawContent) ?: return RememberResult.Invalid
        val normalized = normalize(content)
        if (normalized.isEmpty()) return RememberResult.Invalid
        val now = clock()
        if (validUntilEpochMillis != null && validUntilEpochMillis <= now) {
            return RememberResult.Invalid
        }
        val boundedSupersedesId = supersedesId
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() && value.length <= MAX_MEMORY_ID_CHARACTERS }
        if (supersedesId != null && boundedSupersedesId == null) return RememberResult.Invalid

        return database.withTransaction {
            val superseded = if (boundedSupersedesId == null) {
                null
            } else {
                memoryDao.findById(boundedSupersedesId)
            }
            if (boundedSupersedesId != null && superseded == null) {
                return@withTransaction RememberResult.Invalid
            }
            val existing = memoryDao.findByNormalizedContent(normalized)
            if (existing != null) {
                if (existing.id == boundedSupersedesId) return@withTransaction RememberResult.Invalid
                val updated = existing.copy(
                    content = content,
                    category = category,
                    validUntilEpochMillis = validUntilEpochMillis,
                    lastConfirmedAtEpochMillis = now,
                    supersedesId = boundedSupersedesId ?: existing.supersedesId,
                    updatedAtEpochMillis = now,
                )
                memoryDao.update(updated)
                return@withTransaction RememberResult.Saved(updated, replacedExisting = true)
            }
            if (memoryDao.count() >= MAX_MEMORIES) {
                return@withTransaction RememberResult.CapacityReached
            }
            val created = MemoryEntity(
                id = idFactory(),
                content = content,
                normalizedContent = normalized,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                category = category,
                validUntilEpochMillis = validUntilEpochMillis,
                lastConfirmedAtEpochMillis = now,
                supersedesId = boundedSupersedesId,
            )
            memoryDao.insert(created)
            RememberResult.Saved(created, replacedExisting = false)
        }
    }

    suspend fun recent(limit: Int = DEFAULT_MEMORY_PAGE): List<MemoryEntity> =
        memoryDao.listRecent(limit.coerceIn(1, MAX_MEMORIES))

    /**
     * Selects a small relevance-ranked set for one turn.
     *
     * Korean inflections make exact word matching brittle, so the score combines normalized words
     * with Hangul/letter bigrams. No overlap returns no memory: recency alone is not relevance and
     * must not make an unrelated place or preference influence a Tool request.
     */
    suspend fun relevantTo(
        query: String,
        limit: Int = DEFAULT_RECALL_LIMIT,
        categories: Set<MemoryCategory> = MemoryCategory.entries.toSet(),
    ): List<MemoryEntity> {
        val boundedLimit = limit.coerceIn(1, MAX_RECALL_LIMIT)
        if (categories.isEmpty()) return emptyList()
        val now = clock()
        val candidates = memoryDao.listRecallCandidates(
            now = now,
            confirmedAtOrAfter = (now - RECONFIRM_AFTER_MILLIS).coerceAtLeast(0L),
            categories = categories,
            limit = MAX_MEMORIES,
        )
        if (candidates.isEmpty()) return emptyList()

        val queryFeatures = relevanceFeatures(query)
        val scored = candidates.map { memory ->
            val score = if (queryFeatures.isEmpty()) {
                0
            } else {
                relevanceFeatures(memory.content).count(queryFeatures::contains)
            }
            memory to score
        }
        val matching = scored
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryEntity, Int>> { (_, score) -> score }
                    .thenByDescending { (memory, _) -> memory.updatedAtEpochMillis },
            )
            .map { (memory, _) -> memory }

        return matching.take(boundedLimit)
    }

    /** Renews a stale memory only after the user explicitly confirms it in settings. */
    suspend fun reconfirm(memoryId: String): Boolean {
        if (memoryId.isBlank()) return false
        return memoryDao.confirm(memoryId, clock()) > 0
    }

    suspend fun replace(
        memoryId: String,
        rawContent: String,
        category: MemoryCategory,
        validUntilEpochMillis: Long? = null,
    ): RememberResult {
        val current = memoryDao.findById(memoryId) ?: return RememberResult.Invalid
        val sanitized = MemoryTextPolicy.sanitize(rawContent) ?: return RememberResult.Invalid
        return if (normalize(sanitized) == current.normalizedContent) {
            remember(
                rawContent = sanitized,
                category = category,
                validUntilEpochMillis = validUntilEpochMillis,
                supersedesId = current.supersedesId,
            )
        } else {
            remember(
                rawContent = sanitized,
                category = category,
                validUntilEpochMillis = validUntilEpochMillis,
                supersedesId = memoryId,
            )
        }
    }

    suspend fun delete(memoryId: String): Boolean =
        memoryId.isNotBlank() && memoryDao.delete(memoryId) > 0

    suspend fun deleteAll(): Int = memoryDao.deleteAll()

    suspend fun count(): Long = memoryDao.count()

    private fun normalize(content: String): String = Normalizer
        .normalize(content, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ")
        .trim()

    private fun relevanceFeatures(value: String): Set<String> {
        val normalized = normalize(value)
        val words = FEATURE_WORD.findAll(normalized)
            .map(MatchResult::value)
            .filter { word -> word !in RECALL_STOP_WORDS }
            .toList()
        return buildSet {
            words.forEach { word ->
                if (word.length >= MIN_FEATURE_CHARACTERS) add("w:$word")
                if (word.length >= MIN_BIGRAM_SOURCE_CHARACTERS) {
                    word.windowed(BIGRAM_CHARACTERS).forEach { bigram -> add("b:$bigram") }
                }
            }
        }
    }

    companion object {
        const val MAX_MEMORIES = 50
        const val DEFAULT_MEMORY_PAGE = MAX_MEMORIES
        const val DEFAULT_RECALL_LIMIT = 4
        const val MAX_RECALL_LIMIT = 6
        const val MAX_MEMORY_ID_CHARACTERS = 80
        const val RECONFIRM_AFTER_DAYS = 180L
        const val RECONFIRM_AFTER_MILLIS = RECONFIRM_AFTER_DAYS * 24L * 60L * 60L * 1_000L
        private const val MIN_FEATURE_CHARACTERS = 2
        private const val MIN_BIGRAM_SOURCE_CHARACTERS = 4
        private const val BIGRAM_CHARACTERS = 2
        private val WHITESPACE = Regex("\\s+")
        private val FEATURE_WORD = Regex("[\\p{L}\\p{N}]+")
        private val RECALL_STOP_WORDS = setOf(
            "기억", "기억해", "알려", "알려줘", "뭐야", "무엇", "대답", "질문", "사용자",
            "assistant", "도우미", "저장", "삭제",
        )
    }
}

/** Shared validator for direct settings writes and model-proposed memory Tool calls. */
object MemoryTextPolicy {
    const val MAX_CONTENT_CODE_POINTS = 240

    fun sanitize(rawContent: String): String? {
        val collapsed = rawContent
            .trim()
            .replace(WHITESPACE, " ")
        if (collapsed.codePointCount(0, collapsed.length) !in 1..MAX_CONTENT_CODE_POINTS) return null
        if (collapsed.contains("<|") || collapsed.contains("|>")) return null
        if (collapsed.codePoints().anyMatch(::isUnsafeCodePoint)) return null
        if (looksLikeSecret(collapsed)) return null
        return collapsed
    }

    private fun looksLikeSecret(value: String): Boolean {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return SECRET_LABELS.any(normalized::contains) ||
            PREFIXED_TOKEN.containsMatchIn(value) ||
            JWT_TOKEN.containsMatchIn(value) ||
            LONG_HEX_TOKEN.containsMatchIn(value) ||
            LONG_BASE64URL_TOKEN.containsMatchIn(value) ||
            HIGH_ENTROPY_CANDIDATE.findAll(value).any { match ->
                shannonEntropy(match.value) >= MIN_SECRET_ENTROPY_BITS_PER_CHARACTER
            } ||
            KOREAN_RESIDENT_NUMBER.containsMatchIn(value) ||
            value.possiblePaymentCardNumbers().any(::passesLuhn)
    }

    /** Auxiliary detector for unlabelled random-looking credentials missed by fixed formats. */
    private fun shannonEntropy(value: String): Double {
        if (value.length < MIN_HIGH_ENTROPY_CHARACTERS ||
            value.toSet().size < MIN_HIGH_ENTROPY_UNIQUE_CHARACTERS
        ) return 0.0
        val length = value.length.toDouble()
        return value.groupingBy { it }.eachCount().values.sumOf { count ->
            val probability = count / length
            -probability * (ln(probability) / LN_2)
        }
    }

    private fun isUnsafeCodePoint(codePoint: Int): Boolean =
        Character.isISOControl(codePoint) || when (Character.getType(codePoint)) {
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> true
            else -> false
        }

    private val WHITESPACE = Regex("\\s+")
    private fun String.possiblePaymentCardNumbers(): Sequence<String> =
        PAYMENT_CARD_CANDIDATE.findAll(this)
            .map { match -> match.value.filter(Char::isDigit) }
            .filter { digits -> digits.length in 13..19 }

    private fun passesLuhn(digits: String): Boolean {
        var sum = 0
        val parity = digits.length % 2
        digits.forEachIndexed { index, character ->
            var digit = character.digitToInt()
            if (index % 2 == parity) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
        }
        return sum > 0 && sum % 10 == 0
    }

    private val PREFIXED_TOKEN = Regex("(?i)(?:sk-|key-|token-)[a-z0-9_-]{12,}")
    private val JWT_TOKEN = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])")
    private val LONG_HEX_TOKEN = Regex("(?i)(?<![a-f0-9])[a-f0-9]{32,}(?![a-f0-9])")
    private val LONG_BASE64URL_TOKEN = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{40,}={0,2}(?![A-Za-z0-9_-])")
    private val HIGH_ENTROPY_CANDIDATE =
        Regex("(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/=_-]{24,}(?![A-Za-z0-9+/=_-])")
    private val KOREAN_RESIDENT_NUMBER = Regex("(?<!\\d)\\d{6}[- ]?[1-8]\\d{6}(?!\\d)")
    private val PAYMENT_CARD_CANDIDATE = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")
    private val SECRET_LABELS = setOf(
        "password", "passcode", "api key", "api_key", "apikey", "client secret",
        "access token", "refresh token", "비밀번호", "암호", "api 키", "시크릿",
        "인증 토큰", "주민등록번호", "신용카드", "카드번호", "계좌번호",
    )
    private const val MIN_HIGH_ENTROPY_CHARACTERS = 24
    private const val MIN_HIGH_ENTROPY_UNIQUE_CHARACTERS = 12
    private const val MIN_SECRET_ENTROPY_BITS_PER_CHARACTER = 4.2
    private val LN_2 = ln(2.0)
}
