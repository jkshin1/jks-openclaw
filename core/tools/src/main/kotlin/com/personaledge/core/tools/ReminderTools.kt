package com.personaledge.core.tools

import java.security.MessageDigest
import java.text.Normalizer
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ReminderCreateParams(
    val title: String,
    val triggerAt: String,
    val zoneId: String,
    val recurrenceRule: String?,
    val precision: String?,
    val leadTimeMinutes: String?,
    val escalationPolicy: String?,
) : ToolParams

data class ReminderUpdateParams(
    val reminderId: String,
    val expectedVersion: String,
    val title: String,
    val triggerAt: String,
    val zoneId: String,
    val recurrenceRule: String?,
    val precision: String?,
    val leadTimeMinutes: String?,
    val escalationPolicy: String?,
) : ToolParams

data class ReminderCancelParams(
    val reminderId: String,
    val expectedVersion: String,
) : ToolParams

data class ReminderQueryParams(val limit: String?) : ToolParams

data class ReminderWriteRequest(
    val title: String,
    val triggerAtEpochMillis: Long,
    val zoneId: String,
    val recurrenceRule: String?,
    val precision: ReminderToolPrecision,
    val leadTimeMinutes: Int?,
    val escalationPolicy: String?,
    val confirmationDigest: String,
)

enum class ReminderToolPrecision {
    FLEXIBLE,
    EXACT,
}

enum class ReminderMutationOutcome {
    SAVED,
    NOT_FOUND,
    VERSION_CONFLICT,
    CAPACITY_REACHED,
    REJECTED,
}

data class ReminderMutationResult(
    val outcome: ReminderMutationOutcome,
    val reminderId: String? = null,
    val scheduleVersion: Long? = null,
)

data class ReminderSummary(
    val reminderId: String,
    val title: String,
    val triggerAtEpochMillis: Long,
    val zoneId: String,
    val recurrenceRule: String?,
    val precision: ReminderToolPrecision,
    val scheduleVersion: Long,
)

interface ReminderGateway {
    suspend fun create(request: ReminderWriteRequest): ReminderMutationResult

    suspend fun update(
        reminderId: String,
        expectedVersion: Long,
        request: ReminderWriteRequest,
    ): ReminderMutationResult

    suspend fun cancel(reminderId: String, expectedVersion: Long): ReminderMutationResult

    suspend fun upcoming(limit: Int): List<ReminderSummary>
}

class ReminderCreateTool(
    private val gateway: ReminderGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentTool<ReminderCreateParams, ReminderMutationResult> {
    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Create an app-owned reminder for one absolute local date and time. Use " +
            "this instead of alarm_set whenever the user names a date such as today, tomorrow, " +
            "or 2026-08-25. Set trigger_at to local wall time exactly YYYY-MM-DDTHH:mm: do not " +
            "append seconds, Z, a UTC offset, or a time-zone suffix. Put the trusted IANA zone " +
            "only in zone_id. Kotlin validates the date, time zone, recurrence, and DST meaning. " +
            "The user must approve the exact reminder before it is stored and scheduled. The " +
            "Room record remains valid when notification permission is unavailable; delivery " +
            "then stays visibly blocked until the user grants it.",
        risk = ToolRisk.LOCAL_WRITE,
        minimumConfirmation = ConfirmationRequirement.UserConfirmation,
    )

    override suspend fun validateAndCanonicalize(params: ReminderCreateParams): ValidationResult =
        ReminderCanonicalizer.canonicalize(
            title = params.title,
            triggerAt = params.triggerAt,
            zoneId = params.zoneId,
            recurrenceRule = params.recurrenceRule,
            precision = params.precision,
            leadTimeMinutes = params.leadTimeMinutes,
            escalationPolicy = params.escalationPolicy,
            now = clock(),
        )

    override fun preview(input: CanonicalToolInput): ActionPreview =
        ReminderCanonicalizer.preview(input, "리마인더 추가")

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): ReminderMutationResult = gateway.create(ReminderCanonicalizer.toWriteRequest(input))

    override fun executionOutcome(result: ReminderMutationResult): ToolExecutionOutcome =
        result.asExecutionOutcome()

    companion object {
        const val NAME = "reminder_create"
    }
}

class ReminderUpdateTool(
    private val gateway: ReminderGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentTool<ReminderUpdateParams, ReminderMutationResult> {
    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Update one existing app-owned reminder. When the user already provides " +
            "its id and current version, call reminder_update directly without reminder_query. " +
            "Only ask for or query the identity first when either value is missing. " +
            "Every effect-bearing field is required so an omitted model field cannot silently " +
            "inherit stale state. Set trigger_at to local wall time exactly YYYY-MM-DDTHH:mm " +
            "without seconds, Z, an offset, or a zone suffix; put the IANA zone only in zone_id. " +
            "The user must approve the replacement. Notification permission " +
            "controls delivery, not whether the durable replacement may be stored.",
        risk = ToolRisk.LOCAL_WRITE,
        minimumConfirmation = ConfirmationRequirement.UserConfirmation,
    )

    override suspend fun validateAndCanonicalize(params: ReminderUpdateParams): ValidationResult {
        val identity = ReminderCanonicalizer.canonicalIdentity(
            params.reminderId,
            params.expectedVersion,
        ) ?: return ValidationResult.Invalid(
            reason = "리마인더 ID 또는 버전이 올바르지 않습니다.",
            failureCode = ToolFailureCode.REMINDER_INVALID_IDENTITY,
        )
        val canonical = ReminderCanonicalizer.canonicalize(
            title = params.title,
            triggerAt = params.triggerAt,
            zoneId = params.zoneId,
            recurrenceRule = params.recurrenceRule,
            precision = params.precision,
            leadTimeMinutes = params.leadTimeMinutes,
            escalationPolicy = params.escalationPolicy,
            now = clock(),
        )
        return when (canonical) {
            is ValidationResult.Invalid -> canonical
            is ValidationResult.Valid -> ValidationResult.Valid(
                CanonicalFields.encode(
                    CanonicalFields.decode(CanonicalToolInput(canonical.canonicalParams)) + identity,
                ),
            )
        }
    }

    override fun preview(input: CanonicalToolInput): ActionPreview =
        ReminderCanonicalizer.preview(input, "리마인더 변경")

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): ReminderMutationResult {
        val fields = CanonicalFields.decode(input)
        return gateway.update(
            reminderId = fields.requiredString(ReminderCanonicalizer.FIELD_ID),
            expectedVersion = fields.requiredLong(ReminderCanonicalizer.FIELD_VERSION),
            request = ReminderCanonicalizer.toWriteRequest(input),
        )
    }

    override fun executionOutcome(result: ReminderMutationResult): ToolExecutionOutcome =
        result.asExecutionOutcome()

    companion object {
        const val NAME = "reminder_update"
    }
}

class ReminderCancelTool(
    private val gateway: ReminderGateway,
) : AgentTool<ReminderCancelParams, ReminderMutationResult> {
    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Cancel one app-owned reminder. When the user already provides its id and " +
            "current version, call reminder_cancel directly without reminder_query. Only ask for " +
            "or query the identity first when either value is missing. The user must approve the " +
            "cancellation.",
        risk = ToolRisk.LOCAL_WRITE,
        minimumConfirmation = ConfirmationRequirement.UserConfirmation,
    )

    override suspend fun validateAndCanonicalize(params: ReminderCancelParams): ValidationResult {
        val identity = ReminderCanonicalizer.canonicalIdentity(
            params.reminderId,
            params.expectedVersion,
        ) ?: return ValidationResult.Invalid(
            reason = "리마인더 ID 또는 버전이 올바르지 않습니다.",
            failureCode = ToolFailureCode.REMINDER_INVALID_IDENTITY,
        )
        return ValidationResult.Valid(CanonicalFields.encode(identity))
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val fields = CanonicalFields.decode(input)
        return ActionPreview(
            title = "리마인더 취소",
            summary = "선택한 리마인더를 취소합니다.\nID: ${fields.requiredString(ReminderCanonicalizer.FIELD_ID)}",
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): ReminderMutationResult {
        val fields = CanonicalFields.decode(input)
        return gateway.cancel(
            reminderId = fields.requiredString(ReminderCanonicalizer.FIELD_ID),
            expectedVersion = fields.requiredLong(ReminderCanonicalizer.FIELD_VERSION),
        )
    }

    override fun executionOutcome(result: ReminderMutationResult): ToolExecutionOutcome =
        result.asExecutionOutcome()

    companion object {
        const val NAME = "reminder_cancel"
    }
}

class ReminderQueryTool(
    private val gateway: ReminderGateway,
) : AgentTool<ReminderQueryParams, List<ReminderSummary>> {
    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "List upcoming app-owned reminders with immutable ids and versions. This " +
            "does not list alarms owned by the device clock app.",
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun validateAndCanonicalize(params: ReminderQueryParams): ValidationResult {
        val limit = params.limit?.trim()?.toIntOrNull() ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_LIMIT) return ValidationResult.Invalid(
            reason = "조회 개수는 1 이상 100 이하여야 합니다.",
            failureCode = ToolFailureCode.REMINDER_INVALID_QUERY_LIMIT,
        )
        return ValidationResult.Valid(CanonicalFields.encode(mapOf(FIELD_LIMIT to limit.toString())))
    }

    override fun preview(input: CanonicalToolInput): ActionPreview =
        ActionPreview("리마인더 조회", "예정된 앱 리마인더를 조회합니다.")

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): List<ReminderSummary> = gateway.upcoming(
        CanonicalFields.decode(input).requiredLong(FIELD_LIMIT).toInt(),
    )

    companion object {
        const val NAME = "reminder_query"
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 100
        internal const val FIELD_LIMIT = "limit"
    }
}

private fun ReminderMutationResult.asExecutionOutcome(): ToolExecutionOutcome =
    if (outcome == ReminderMutationOutcome.SAVED) {
        ToolExecutionOutcome.WRITE_COMPLETED
    } else {
        ToolExecutionOutcome.WRITE_REFUSED
    }

internal object ReminderCanonicalizer {
    // Some runtimes emit ISO seconds even when the schema asks for minute precision. Accept only
    // representations that are exactly on the minute; non-zero seconds remain fail-closed.
    private val LOCAL_DATE_TIME = Regex(
        """\d{4}-\d{2}-\d{2}[Tt ]\d{2}:\d{1,2}(?::00(?:[.,]0{1,18})?)?""",
    )
    private val OFFSET_DATE_TIME = Regex(
        """\d{4}-\d{2}-\d{2}[Tt ]\d{2}:\d{1,2}(?::00(?:[.,]0{1,18})?)?(?:Z|[+-]\d{2}:\d{2})""",
        RegexOption.IGNORE_CASE,
    )
    private val KOREAN_LOCAL_DATE_TIME = Regex(
        """(\d{4})-(\d{2})-(\d{2})\s+(오전|오후)\s+(\d{1,2}):(\d{2})""",
    )
    private val KOREAN_WORD_LOCAL_DATE_TIME = Regex(
        """(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일\s*(오전|오후)?\s*""" +
            """(\d{1,2})시(?:\s*(\d{1,2})분)?""",
    )
    private val KOREAN_WORD_DATE_COLON_TIME = Regex(
        """(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일\s*(오전|오후)?\s*""" +
            """(\d{1,2}):(\d{1,2})""",
    )
    private val FLEXIBLE_NUMERIC_LOCAL_DATE_TIME = Regex(
        """(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})[Tt ]\s*(\d{1,2})\s*:\s*(\d{1,2})""" +
            """(?:(?::00(?:[.,]0{1,18})?)|(?:[.,]0{1,18}))?""",
    )
    private val HOUR_ONLY_LOCAL_DATE_TIME = Regex(
        """(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})[Tt ](\d{1,2})""",
    )
    private val ZERO_PADDED_MINUTE_LOCAL_DATE_TIME = Regex(
        """(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})[Tt ]\s*(\d{1,2})\s*:\s*0{3,18}""",
    )
    private val ENGLISH_LOCAL_DATE_TIME = Regex(
        """(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})\s*(AM|PM)""",
        RegexOption.IGNORE_CASE,
    )
    private val DATE_ONLY = Regex("""\d{4}-\d{2}-\d{2}""")
    private val NON_ZERO_SECONDS = Regex(
        """\d{4}-\d{2}-\d{2}[Tt ]\d{2}:\d{1,2}:(?!00(?:[.,]0+)?(?:$|Z|[+-]))\d{2}.*""",
        RegexOption.IGNORE_CASE,
    )
    private val ANY_OFFSET_DATE_TIME = Regex(
        """\d{4}-\d{2}-\d{2}[Tt ].*(?:Z|[+-]\d{2}:?\d{0,2})(?:\[[^]]+])?""",
        RegexOption.IGNORE_CASE,
    )
    private val EPOCH_TIME = Regex("""(?:\d{10}|\d{13})""")
    private val AT_CONNECTOR = Regex("""\s+at\s+""", RegexOption.IGNORE_CASE)
    private val LOCAL_TIME_SUFFIX = Regex(
        """\s+(?:in\s+)?\(?(?:local|local\s+time|local\s+wall\s+time)\)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val ASCII_RELATIVE_WORD = Regex("""\b(?:today|tomorrow|yesterday)\b""", RegexOption.IGNORE_CASE)
    private val ASCII_MONTH_NAME = Regex(
        """\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|""" +
            """jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ASCII_AM_PM = Regex("""\b(?:am|pm)\b""", RegexOption.IGNORE_CASE)
    private val ASCII_ZONE_LABEL = Regex(
        """(?:(?:utc|gmt|kst)|[A-Za-z]+/[A-Za-z_]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val ASCII_NUMERIC_PUNCTUATION = Regex("""[0-9\s:./+_-]+""")
    private val ASCII_ISO_LIKE = Regex(
        """\d{4}[-/.]\d{1,2}[-/.]\d{1,2}T.*""",
        RegexOption.IGNORE_CASE,
    )
    private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")
    private val DAY_ORDER = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private const val MAX_FUTURE_MILLIS = 5L * 366 * 24 * 60 * 60 * 1_000
    private const val MAX_TITLE_CODE_POINTS = 120
    private const val MAX_LEAD_MINUTES = 7 * 24 * 60

    const val FIELD_ID = "id"
    const val FIELD_VERSION = "version"
    private const val FIELD_TITLE = "title"
    private const val FIELD_TRIGGER = "trigger"
    private const val FIELD_ZONE = "zone"
    private const val FIELD_RECURRENCE = "recurrence"
    private const val FIELD_PRECISION = "precision"
    private const val FIELD_LEAD = "lead"
    private const val FIELD_ESCALATION = "escalation"

    fun canonicalize(
        title: String,
        triggerAt: String,
        zoneId: String,
        recurrenceRule: String?,
        precision: String?,
        leadTimeMinutes: String?,
        escalationPolicy: String?,
        now: Long,
    ): ValidationResult {
        val safeTitle = title.trim().replace(Regex("\\s+"), " ")
        if (safeTitle.codePointCount(0, safeTitle.length) !in 1..MAX_TITLE_CODE_POINTS ||
            !CalendarText.isSafeText(safeTitle)
        ) {
            return ValidationResult.Invalid(
                reason = "리마인더 제목은 안전한 1자 이상 120자 이하 텍스트여야 합니다.",
                failureCode = ToolFailureCode.REMINDER_INVALID_TITLE,
            )
        }
        val zone = try {
            ZoneId.of(zoneId.trim())
        } catch (_: DateTimeException) {
            return ValidationResult.Invalid(
                reason = "알 수 없는 시간대입니다.",
                failureCode = ToolFailureCode.REMINDER_INVALID_TIME_ZONE,
            )
        }
        val local = try {
            val value = Normalizer.normalize(triggerAt.trim(), Normalizer.Form.NFKC)
            parseExactMinuteLocalDateTime(value, zone) ?: return ValidationResult.Invalid(
                reason = "시각은 2026-08-25T15:00 형식의 절대 현지 날짜와 시각이어야 합니다.",
                failureCode = classifyDateTimeFormatFailure(value),
            )
        } catch (_: DateTimeException) {
            return ValidationResult.Invalid(
                reason = "존재하지 않는 날짜 또는 시각입니다.",
                failureCode = ToolFailureCode.REMINDER_INVALID_DATE_TIME_VALUE,
            )
        }
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.size != 1) {
            return ValidationResult.Invalid(
                reason = "일광절약시간 전환 때문에 존재하지 않거나 모호한 시각입니다.",
                failureCode = ToolFailureCode.REMINDER_INVALID_DATE_TIME_DST,
            )
        }
        val trigger = local.toInstant(offsets.single()).toEpochMilli()
        if (trigger <= now || trigger > now + MAX_FUTURE_MILLIS) {
            return ValidationResult.Invalid(
                reason = "리마인더 시각은 현재 이후 5년 이내여야 합니다.",
                failureCode = ToolFailureCode.REMINDER_INVALID_DATE_TIME_RANGE,
            )
        }
        val recurrence = canonicalRecurrence(recurrenceRule)
            ?: return ValidationResult.Invalid(
                reason = "반복은 none, daily 또는 weekly:mon,tue 형식이어야 합니다.",
                failureCode = ToolFailureCode.REMINDER_INVALID_RECURRENCE,
            )
        val exactness = when (precision?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
            "", "flexible" -> "flexible"
            "exact" -> "exact"
            else -> return ValidationResult.Invalid(
                reason = "정확도는 flexible 또는 exact여야 합니다.",
                failureCode = ToolFailureCode.REMINDER_INVALID_PRECISION,
            )
        }
        val lead = leadTimeMinutes?.trim()?.takeIf(String::isNotEmpty)?.toIntOrNull()
        if (leadTimeMinutes != null && (lead == null || lead !in 0..MAX_LEAD_MINUTES)) {
            return ValidationResult.Invalid(
                reason = "미리 알림 시간은 0분 이상 10080분 이하여야 합니다.",
                failureCode = ToolFailureCode.REMINDER_INVALID_LEAD_TIME,
            )
        }
        val escalation = escalationPolicy?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
        if (escalation != null && escalation !in setOf("once", "until_completed")) {
            return ValidationResult.Invalid(
                reason = "반복 알림 정책은 once 또는 until_completed여야 합니다.",
                failureCode = ToolFailureCode.REMINDER_INVALID_ESCALATION,
            )
        }
        return ValidationResult.Valid(
            CanonicalFields.encode(
                buildMap {
                    put(FIELD_TITLE, safeTitle)
                    put(FIELD_TRIGGER, trigger.toString())
                    put(FIELD_ZONE, zone.id)
                    recurrence.takeIf(String::isNotEmpty)?.let { put(FIELD_RECURRENCE, it) }
                    put(FIELD_PRECISION, exactness)
                    lead?.let { put(FIELD_LEAD, it.toString()) }
                    escalation?.let { put(FIELD_ESCALATION, it) }
                },
            ),
        )
    }

    fun canonicalIdentity(id: String, version: String): Map<String, String>? {
        val safeId = id.trim()
        val safeVersion = version.trim().toLongOrNull()
        if (!IDENTIFIER.matches(safeId) || safeVersion == null || safeVersion < 1) return null
        return mapOf(FIELD_ID to safeId, FIELD_VERSION to safeVersion.toString())
    }

    private fun parseExactMinuteLocalDateTime(value: String, zone: ZoneId): LocalDateTime? {
        val withoutSentencePunctuation = value.trimEnd { character ->
            character == '.' || character == ',' || character == ';'
        }
        val withoutLocalTimeSuffix = withoutSentencePunctuation.replace(LOCAL_TIME_SUFFIX, "")
        val zoneSuffixes = buildList {
            add(" ${zone.id}")
            add("[${zone.id}]")
            add(" (${zone.id})")
            if (zone.id == "Asia/Seoul") add(" KST")
        }
        val withoutMatchingZone = zoneSuffixes.firstOrNull(withoutLocalTimeSuffix::endsWith)
            ?.let(withoutLocalTimeSuffix::removeSuffix)
            ?.trimEnd() ?: withoutLocalTimeSuffix
        if (EPOCH_TIME.matches(withoutMatchingZone)) {
            val raw = withoutMatchingZone.toLongOrNull() ?: return null
            val instant = if (withoutMatchingZone.length == 10) {
                Instant.ofEpochSecond(raw)
            } else {
                Instant.ofEpochMilli(raw)
            }
            val resolved = instant.atZone(zone).toLocalDateTime()
            return resolved.takeIf { it.second == 0 && it.nano == 0 }
        }
        val korean = KOREAN_LOCAL_DATE_TIME.matchEntire(withoutMatchingZone)
        if (korean != null) {
            val (year, month, day, period, hourText, minute) = korean.destructured
            val hour12 = hourText.toInt()
            if (hour12 !in 1..12) return null
            val hour24 = when (period) {
                "오전" -> if (hour12 == 12) 0 else hour12
                else -> if (hour12 == 12) 12 else hour12 + 12
            }
            return LocalDateTime.of(
                year.toInt(),
                month.toInt(),
                day.toInt(),
                hour24,
                minute.toInt(),
            )
        }
        listOf(KOREAN_WORD_LOCAL_DATE_TIME, KOREAN_WORD_DATE_COLON_TIME).forEach { pattern ->
            pattern.matchEntire(withoutMatchingZone)?.let { match ->
                val (year, month, day, period, hourText, minuteText) = match.destructured
                val hour = hourText.toInt()
                val minute = minuteText.takeIf(String::isNotEmpty)?.toInt() ?: 0
                val hour24 = when (period) {
                    "오전" -> if (hour == 12) 0 else hour.takeIf { it in 1..11 } ?: return null
                    "오후" -> when (hour) {
                        12 -> 12
                        in 1..11 -> hour + 12
                        else -> return null
                    }
                    else -> hour
                }
                return LocalDateTime.of(
                    year.toInt(),
                    month.toInt(),
                    day.toInt(),
                    hour24,
                    minute,
                )
            }
        }
        val withoutAtConnector = withoutMatchingZone.replace(AT_CONNECTOR, " ")
        HOUR_ONLY_LOCAL_DATE_TIME.matchEntire(withoutAtConnector)?.let { match ->
            val (year, month, day, hour) = match.destructured
            return LocalDateTime.of(
                year.toInt(),
                month.toInt(),
                day.toInt(),
                hour.toInt(),
                0,
            )
        }
        ZERO_PADDED_MINUTE_LOCAL_DATE_TIME.matchEntire(withoutAtConnector)?.let { match ->
            val (year, month, day, hour) = match.destructured
            return LocalDateTime.of(
                year.toInt(),
                month.toInt(),
                day.toInt(),
                hour.toInt(),
                0,
            )
        }
        FLEXIBLE_NUMERIC_LOCAL_DATE_TIME.matchEntire(withoutAtConnector)?.let { match ->
            val (year, month, day, hour, minute) = match.destructured
            return LocalDateTime.of(
                year.toInt(),
                month.toInt(),
                day.toInt(),
                hour.toInt(),
                minute.toInt(),
            )
        }
        ENGLISH_LOCAL_DATE_TIME.matchEntire(withoutAtConnector)?.let { match ->
            val (year, month, day, hourText, minute, period) = match.destructured
            val hour12 = hourText.toInt()
            if (hour12 !in 1..12) return null
            val hour24 = when {
                period.equals("AM", ignoreCase = true) -> if (hour12 == 12) 0 else hour12
                hour12 == 12 -> 12
                else -> hour12 + 12
            }
            return LocalDateTime.of(
                year.toInt(),
                month.toInt(),
                day.toInt(),
                hour24,
                minute.toInt(),
            )
        }
        val isoValue = if (
            withoutMatchingZone.length > 10 && withoutMatchingZone[10] != 'T'
        ) {
            withoutMatchingZone.replaceRange(10, 11, "T")
        } else {
            withoutMatchingZone
        }
        if (LOCAL_DATE_TIME.matches(withoutMatchingZone)) {
            return LocalDateTime.parse(isoValue).withSecond(0).withNano(0)
        }
        if (!OFFSET_DATE_TIME.matches(withoutMatchingZone)) return null
        val offset = OffsetDateTime.parse(isoValue)
        val local = offset.toLocalDateTime().withSecond(0).withNano(0)
        return local.takeIf { offset.offset in zone.rules.getValidOffsets(local) }
    }

    private fun classifyDateTimeFormatFailure(value: String): ToolFailureCode = when {
        value.isEmpty() -> ToolFailureCode.REMINDER_INVALID_DATE_TIME_EMPTY
        value.contains("YYYY", ignoreCase = true) || value.contains("HH", ignoreCase = true) ->
            ToolFailureCode.REMINDER_INVALID_DATE_TIME_PLACEHOLDER
        DATE_ONLY.matches(value) -> ToolFailureCode.REMINDER_INVALID_DATE_TIME_DATE_ONLY
        NON_ZERO_SECONDS.matches(value) ->
            ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_ZERO_SECONDS
        ANY_OFFSET_DATE_TIME.matches(value) ->
            ToolFailureCode.REMINDER_INVALID_DATE_TIME_OFFSET_CONFLICT
        value.codePoints().anyMatch { codePoint ->
            Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL
        } -> ToolFailureCode.REMINDER_INVALID_DATE_TIME_NATURAL_LANGUAGE
        value.all { character -> character.code in 0..127 } -> when {
            ASCII_RELATIVE_WORD.containsMatchIn(value) ->
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_RELATIVE
            ASCII_MONTH_NAME.containsMatchIn(value) ->
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_MONTH_NAME
            ASCII_AM_PM.containsMatchIn(value) ->
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_AM_PM
            ASCII_ZONE_LABEL.containsMatchIn(value) ->
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ZONE_LABEL
            ASCII_NUMERIC_PUNCTUATION.matches(value) ->
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_NUMERIC
            ASCII_ISO_LIKE.matches(value) -> classifyAsciiIsoLikeFailure(value)
            value.any(Char::isLetter) -> ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_TEXT
            else -> ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_OTHER
        }
        value.any { character -> character.code > 127 } ->
            ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_HANGUL_UNICODE
        else -> ToolFailureCode.REMINDER_INVALID_DATE_TIME_OTHER_FORMAT
    }

    /**
     * Returns only a closed structural category. It deliberately never records the model value,
     * its digits, punctuation positions, or length in diagnostics.
     */
    private fun classifyAsciiIsoLikeFailure(value: String): ToolFailureCode {
        val timePart = value.substringAfter('T', value.substringAfter('t', ""))
        if (timePart.any(Char::isLetter)) {
            return ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ALPHA
        }
        return when (timePart.count { it == ':' }) {
            0 -> ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_NO_COLON
            1 -> when {
                Regex("""\d{1,2}:\d{1,2}[^0-9]+""").matches(timePart) ->
                    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_SUFFIX_SYMBOLS
                Regex("""\d{1,2}:\d{3,}""").matches(timePart) ->
                    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_EXTRA_DIGITS
                else -> ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_OTHER
            }
            else -> ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_MULTI_COLON
        }
    }

    fun preview(input: CanonicalToolInput, title: String): ActionPreview {
        val fields = CanonicalFields.decode(input)
        val zone = ZoneId.of(fields.requiredString(FIELD_ZONE))
        val whenText = Instant.ofEpochMilli(fields.requiredLong(FIELD_TRIGGER))
            .atZone(zone)
            .format(DISPLAY_FORMAT)
        return ActionPreview(
            title = title,
            summary = buildString {
                append(fields.requiredString(FIELD_TITLE))
                append("\n")
                append(whenText)
                append(" · ")
                append(zone.id)
                fields[FIELD_RECURRENCE]?.let { append("\n반복: $it") }
                append("\n정확도: ")
                append(if (fields.requiredString(FIELD_PRECISION) == "exact") "정확" else "근사 허용")
                fields[FIELD_LEAD]?.let { append(" · ${it}분 전") }
            },
        )
    }

    fun toWriteRequest(input: CanonicalToolInput): ReminderWriteRequest {
        val fields = CanonicalFields.decode(input)
        return ReminderWriteRequest(
            title = fields.requiredString(FIELD_TITLE),
            triggerAtEpochMillis = fields.requiredLong(FIELD_TRIGGER),
            zoneId = fields.requiredString(FIELD_ZONE),
            recurrenceRule = fields[FIELD_RECURRENCE],
            precision = if (fields.requiredString(FIELD_PRECISION) == "exact") {
                ReminderToolPrecision.EXACT
            } else {
                ReminderToolPrecision.FLEXIBLE
            },
            leadTimeMinutes = fields[FIELD_LEAD]?.toInt(),
            escalationPolicy = fields[FIELD_ESCALATION],
            confirmationDigest = sha256(input.encoded),
        )
    }

    private fun canonicalRecurrence(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (value.isEmpty() || value == "none") return ""
        if (value == "daily") return value
        if (!value.startsWith("weekly:")) return null
        val tokens = value.removePrefix("weekly:").split(',').map(String::trim)
        if (tokens.isEmpty() || tokens.any { it !in DAY_ORDER } || tokens.distinct().size != tokens.size) {
            return null
        }
        return "weekly:" + DAY_ORDER.filter(tokens::contains).joinToString(",")
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}
