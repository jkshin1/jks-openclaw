package com.personaledge.core.tools

import java.time.ZoneId
import java.util.Locale

data class AlarmSetParams(
    val time: String,
    val label: String?,
    val days: String?,
) : ToolParams

data class AlarmSetResult(
    /** The request reached the clock app. Not proof that an alarm now exists — see [AlarmOutcome]. */
    val requested: Boolean,
    val reason: String?,
    /** The device's next alarm as observed right after the request, for the model to read back. */
    val nextAlarm: String?,
)

/**
 * Asks the clock app to create one standard Android alarm.
 *
 * The phone will ring because of this, so it is a DATA_WRITE that requires confirmation even
 * though nothing in this app's own storage changes. The request is fire-and-forget, which is
 * exactly why the durable ledger matters: an interrupted turn must not silently ask twice.
 */
class AlarmSetTool(
    private val gateway: AlarmGateway,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
) : AgentTool<AlarmSetParams, AlarmSetResult> {

    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Create a standard Android clock alarm at the next occurrence of a time, " +
            "optionally repeating by weekday. Never use this when the user names a calendar " +
            "date, today, or tomorrow; use reminder_create so the requested date is preserved.",
        risk = ToolRisk.DATA_WRITE,
        requiredCapabilities = setOf(ToolCapability.SCHEDULE_ALARM),
    )

    override suspend fun validateAndCanonicalize(params: AlarmSetParams): ValidationResult {
        val time = params.time.trim()
        if (!TIME_OF_DAY.matches(time)) {
            return ValidationResult.Invalid("시각은 07:30 형식의 24시간 표기여야 합니다.")
        }
        val hour = time.substring(0, 2).toInt()
        val minute = time.substring(3, 5).toInt()

        val label = params.label?.trim()
        if (label != null) {
            if (label.isEmpty() || CalendarText.codePointLength(label) > MAX_LABEL_CHARACTERS) {
                return ValidationResult.Invalid("알람 이름은 1자 이상 60자 이하여야 합니다.")
            }
            if (!CalendarText.isSafeText(label)) {
                return ValidationResult.Invalid("알람 이름에 허용되지 않는 문자가 있습니다.")
            }
        }

        val days = when (val parsed = parseDays(params.days)) {
            null -> return ValidationResult.Invalid(
                "요일은 mon,tue,wed,thu,fri,sat,sun 중에서 쉼표로 구분해 지정하세요.",
            )
            else -> parsed
        }

        if (!gateway.clockAppAvailable()) {
            return ValidationResult.Invalid("알람을 처리할 시계 앱이 없습니다.")
        }

        return ValidationResult.Valid(
            CanonicalFields.encode(
                buildMap {
                    put(FIELD_HOUR, hour.toString())
                    put(FIELD_MINUTE, minute.toString())
                    if (label != null) put(FIELD_LABEL, label)
                    // Canonical order comes from the enum, so "wed,mon" and "mon,wed" agree.
                    if (days.isNotEmpty()) {
                        put(FIELD_DAYS, days.joinToString(",", transform = AlarmDay::token))
                    }
                },
            ),
        )
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val fields = CanonicalFields.decode(input)
        val hour = fields.requiredLong(FIELD_HOUR).toInt()
        val minute = fields.requiredLong(FIELD_MINUTE).toInt()
        val days = decodeDays(fields)

        return ActionPreview(
            title = "알람 추가",
            summary = buildString {
                append("%02d:%02d".format(Locale.ROOT, hour, minute))
                fields[FIELD_LABEL]?.let { label -> append(" · \"$label\"") }
                append("\n")
                append(
                    if (days.isEmpty()) {
                        "다음 해당 시각에 한 번 울립니다."
                    } else {
                        "매주 ${days.joinToString("·", transform = AlarmDay::korean)}요일 반복"
                    },
                )
            },
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): AlarmSetResult {
        val fields = CanonicalFields.decode(input)
        val outcome = gateway.requestAlarm(
            AlarmRequest(
                hour = fields.requiredLong(FIELD_HOUR).toInt(),
                minute = fields.requiredLong(FIELD_MINUTE).toInt(),
                label = fields[FIELD_LABEL],
                days = decodeDays(fields).toSet(),
            ),
        )

        return when (outcome) {
            is AlarmOutcome.Refused -> AlarmSetResult(
                requested = false,
                reason = when (outcome.refusal) {
                    AlarmRefusal.NO_CLOCK_APP -> "no_clock_app"
                    AlarmRefusal.START_BLOCKED -> "start_blocked"
                },
                nextAlarm = null,
            )
            AlarmOutcome.Delivered -> AlarmSetResult(
                requested = true,
                reason = null,
                nextAlarm = runCatching { gateway.nextAlarm() }
                    .getOrNull()
                    ?.let { alarm ->
                        CalendarText.formatLocalDateTime(alarm.triggerAtEpochMillis, zoneProvider())
                    },
            )
        }
    }

    override fun executionOutcome(result: AlarmSetResult): ToolExecutionOutcome =
        if (result.requested) {
            ToolExecutionOutcome.WRITE_COMPLETED
        } else {
            ToolExecutionOutcome.WRITE_REFUSED
        }

    /** Returns null when a token is unknown or repeated; an empty set means a one-shot alarm. */
    private fun parseDays(raw: String?): List<AlarmDay>? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()

        val tokens = trimmed.split(',').map(String::trim)
        if (tokens.size > AlarmDay.entries.size) return null

        val days = mutableSetOf<AlarmDay>()
        tokens.forEach { token ->
            val day = AlarmDay.fromToken(token) ?: return null
            if (!days.add(day)) return null
        }
        return AlarmDay.entries.filter { day -> day in days }
    }

    private fun decodeDays(fields: Map<String, String>): List<AlarmDay> {
        val encoded = fields[FIELD_DAYS] ?: return emptyList()
        return encoded.split(',').map { token ->
            checkNotNull(AlarmDay.fromToken(token)) { "Invalid canonical alarm day." }
        }
    }

    companion object {
        const val NAME = "alarm_set"
        const val MAX_LABEL_CHARACTERS = 60
        private val TIME_OF_DAY = Regex("""([01]\d|2[0-3]):[0-5]\d""")
        internal const val FIELD_HOUR = "hour"
        internal const val FIELD_MINUTE = "minute"
        internal const val FIELD_LABEL = "label"
        internal const val FIELD_DAYS = "days"
    }
}
