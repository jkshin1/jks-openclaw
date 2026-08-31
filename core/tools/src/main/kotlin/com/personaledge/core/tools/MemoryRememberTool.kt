package com.personaledge.core.tools

import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.ln

data class MemoryRememberParams(
    val content: String,
    val category: String? = null,
    val validUntil: String? = null,
    val zoneId: String? = null,
    val supersedesId: String? = null,
) : ToolParams

enum class MemoryKind(val wireValue: String, val displayName: String) {
    PREFERENCE("preference", "선호"),
    PERSON("person", "사람"),
    PLACE("place", "장소"),
    ROUTINE("routine", "루틴"),
    FACT("fact", "사실"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryKind? =
            if (value == null) FACT else entries.firstOrNull { it.wireValue == value }
    }
}

enum class MemoryWriteOutcome {
    SAVED,
    CAPACITY_REACHED,
    REJECTED,
}

data class MemoryRememberResult(
    val outcome: MemoryWriteOutcome,
)

fun interface MemoryGateway {
    suspend fun remember(request: MemoryWriteRequest): MemoryWriteOutcome
}

data class MemoryWriteRequest(
    val content: String,
    val category: MemoryKind,
    val validUntilEpochMillis: Long?,
    val supersedesId: String?,
)

/**
 * Proposes one cross-thread memory while Kotlin retains storage authority.
 *
 * The model does not write directly: the exact canonical sentence is shown in the confirmation
 * UI, the memory opt-in is rechecked immediately before execution, and the repository validates
 * it again before persistence.
 */
class MemoryRememberTool(
    private val gateway: MemoryGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentTool<MemoryRememberParams, MemoryRememberResult> {
    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Propose one durable user preference or personal fact for future " +
            "conversations. Use only when the user clearly asks to remember it or it is stable " +
            "and likely useful later. Never store credentials, secrets, temporary tasks, " +
            "reminders, health or financial identifiers, or claims about other people. The user " +
            "must approve the exact text before it is stored. Classify it as preference, person, " +
            "place, routine, or fact. An optional validity date must include the trusted zone.",
        risk = ToolRisk.LOCAL_WRITE,
        minimumConfirmation = ConfirmationRequirement.UserConfirmation,
        requiredCapabilities = setOf(ToolCapability.WRITE_MEMORY),
    )

    override suspend fun validateAndCanonicalize(params: MemoryRememberParams): ValidationResult {
        val content = sanitize(params.content) ?: return ValidationResult.Invalid(
            "기억은 1자 이상 ${MAX_CONTENT_CODE_POINTS}자 이하의 안전한 한 문장이어야 합니다.",
        )
        if (looksLikeSecret(content)) {
            return ValidationResult.Invalid("비밀번호, API 키, 토큰 또는 금융 식별자는 기억에 저장할 수 없습니다.")
        }
        val category = MemoryKind.fromWire(params.category)
            ?: return ValidationResult.Invalid("기억 유형은 preference, person, place, routine, fact 중 하나여야 합니다.")
        val supersedesId = params.supersedesId?.trim()?.takeIf(String::isNotEmpty)
        if (supersedesId != null && supersedesId.length > MAX_MEMORY_ID_CHARACTERS) {
            return ValidationResult.Invalid("대체할 기억 식별자가 너무 깁니다.")
        }
        val validUntilEpochMillis = when {
            params.validUntil == null && params.zoneId == null -> null
            params.validUntil == null || params.zoneId == null -> return ValidationResult.Invalid(
                "기억 유효일과 시간대는 함께 지정해야 합니다.",
            )
            else -> parseEndExclusive(params.validUntil, params.zoneId)
                ?: return ValidationResult.Invalid("기억 유효일 또는 시간대가 올바르지 않습니다.")
        }
        if (validUntilEpochMillis != null && validUntilEpochMillis <= clock()) {
            return ValidationResult.Invalid("기억 유효일은 현재 날짜 이후여야 합니다.")
        }
        return ValidationResult.Valid(
            CanonicalFields.encode(
                buildMap {
                    put(FIELD_CONTENT, content)
                    put(FIELD_CATEGORY, category.wireValue)
                    validUntilEpochMillis?.let { put(FIELD_VALID_UNTIL_EPOCH_MILLIS, it.toString()) }
                    params.validUntil?.let { put(FIELD_VALID_UNTIL, it) }
                    params.zoneId?.let { put(FIELD_ZONE_ID, ZoneId.of(it).id) }
                    supersedesId?.let { put(FIELD_SUPERSEDES_ID, it) }
                },
            ),
        )
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val content = CanonicalFields.decode(input).requiredString(FIELD_CONTENT)
        val fields = CanonicalFields.decode(input)
        val category = requireNotNull(MemoryKind.fromWire(fields.requiredString(FIELD_CATEGORY)))
        val validity = fields[FIELD_VALID_UNTIL]?.let { date ->
            "$date (${fields.requiredString(FIELD_ZONE_ID)})까지"
        } ?: "만료일 없음"
        return ActionPreview(
            title = "장기 기억에 저장",
            summary = "${category.displayName} · $validity\n다음 내용을 새 대화에서도 참고합니다.\n\"$content\"" +
                if (fields[FIELD_SUPERSEDES_ID] != null) {
                    "\n기존 기억 하나를 이 내용으로 대체합니다."
                } else {
                    ""
                },
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): MemoryRememberResult {
        val fields = CanonicalFields.decode(input)
        val category = requireNotNull(MemoryKind.fromWire(fields.requiredString(FIELD_CATEGORY)))
        return MemoryRememberResult(
            gateway.remember(
                MemoryWriteRequest(
                    content = fields.requiredString(FIELD_CONTENT),
                    category = category,
                    validUntilEpochMillis = fields[FIELD_VALID_UNTIL_EPOCH_MILLIS]?.toLong(),
                    supersedesId = fields[FIELD_SUPERSEDES_ID],
                ),
            ),
        )
    }

    override fun executionOutcome(result: MemoryRememberResult): ToolExecutionOutcome =
        if (result.outcome == MemoryWriteOutcome.SAVED) {
            ToolExecutionOutcome.WRITE_COMPLETED
        } else {
            ToolExecutionOutcome.WRITE_REFUSED
        }

    private fun sanitize(value: String): String? {
        val collapsed = value.trim().replace(WHITESPACE, " ")
        if (collapsed.codePointCount(0, collapsed.length) !in 1..MAX_CONTENT_CODE_POINTS) return null
        if (collapsed.contains("<|") || collapsed.contains("|>")) return null
        if (collapsed.codePoints().anyMatch(::isUnsafeCodePoint)) return null
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

    private fun parseEndExclusive(dateText: String, zoneText: String): Long? = try {
        val date = LocalDate.parse(dateText, DateTimeFormatter.ISO_LOCAL_DATE)
        val zone = ZoneId.of(zoneText)
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    private fun isUnsafeCodePoint(codePoint: Int): Boolean =
        Character.isISOControl(codePoint) || when (Character.getType(codePoint)) {
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> true
            else -> false
        }

    companion object {
        const val NAME = "memory_remember"
        const val MAX_CONTENT_CODE_POINTS = 240
        const val MAX_MEMORY_ID_CHARACTERS = 80
        internal const val FIELD_CONTENT = "content"
        internal const val FIELD_CATEGORY = "category"
        internal const val FIELD_VALID_UNTIL = "valid_until"
        internal const val FIELD_VALID_UNTIL_EPOCH_MILLIS = "valid_until_epoch_millis"
        internal const val FIELD_ZONE_ID = "zone_id"
        internal const val FIELD_SUPERSEDES_ID = "supersedes_id"
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
}
