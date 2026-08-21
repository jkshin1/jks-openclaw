package com.personaledge.core.tools

import java.time.ZoneId

data class CalendarCreateEventParams(
    val title: String,
    val start: String,
    val end: String,
    val location: String?,
) : ToolParams

data class CalendarCreateEventResult(
    val created: Boolean,
    val eventId: Long?,
)

/**
 * Writes one new event into the user's default calendar.
 *
 * The target calendar is chosen by the app, never by the model: [defaultCalendarId] comes from
 * settings and is verified against the writable list during validation. If that calendar is gone,
 * the tool refuses instead of quietly writing somewhere else.
 */
class CalendarCreateEventTool(
    private val gateway: CalendarGateway,
    private val defaultCalendarId: suspend () -> Long?,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentTool<CalendarCreateEventParams, CalendarCreateEventResult> {

    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Create one calendar event in the user's default calendar",
        risk = ToolRisk.DATA_WRITE,
        requiredCapabilities = setOf(ToolCapability.READ_CALENDAR, ToolCapability.WRITE_CALENDAR),
    )

    override suspend fun validateAndCanonicalize(
        params: CalendarCreateEventParams,
    ): ValidationResult {
        val zone = zoneProvider()
        val now = clock()

        val title = params.title.trim()
        if (title.isEmpty() || CalendarText.codePointLength(title) > CalendarText.MAX_TITLE_CHARACTERS) {
            return ValidationResult.Invalid("제목은 1자 이상 120자 이하여야 합니다.")
        }
        if (!CalendarText.isSafeText(title)) {
            return ValidationResult.Invalid("제목에 허용되지 않는 문자가 있습니다.")
        }

        val location = params.location?.trim().orEmpty()
        if (location.isNotEmpty()) {
            if (CalendarText.codePointLength(location) > CalendarText.MAX_LOCATION_CHARACTERS) {
                return ValidationResult.Invalid("장소는 200자 이하여야 합니다.")
            }
            if (!CalendarText.isSafeText(location)) {
                return ValidationResult.Invalid("장소에 허용되지 않는 문자가 있습니다.")
            }
        }

        val start = CalendarText.parseLocalDateTime(params.start, zone)
            ?: return ValidationResult.Invalid("시작 시각은 2026-08-21T09:00 형식이어야 합니다.")
        val end = CalendarText.parseLocalDateTime(params.end, zone)
            ?: return ValidationResult.Invalid("종료 시각은 2026-08-21T10:00 형식이어야 합니다.")

        val duration = end - start
        if (duration < CalendarText.MIN_DURATION_MILLIS) {
            return ValidationResult.Invalid("종료 시각이 시작 시각보다 최소 1분 뒤여야 합니다.")
        }
        if (duration > CalendarText.MAX_DURATION_MILLIS) {
            return ValidationResult.Invalid("한 일정은 최대 30일까지만 만들 수 있습니다.")
        }
        if (!CalendarText.isWithinSupportedRange(start, now) ||
            !CalendarText.isWithinSupportedRange(end, now)
        ) {
            return ValidationResult.Invalid("과거 1년에서 미래 2년 사이만 등록할 수 있습니다.")
        }

        val calendarId = defaultCalendarId()
            ?: return ValidationResult.Invalid("설정에서 사용할 캘린더를 먼저 선택하세요.")
        val calendar = writableCalendarOrNull(calendarId)
            ?: return ValidationResult.Invalid("선택된 캘린더에 쓸 수 없습니다. 설정에서 다시 선택하세요.")

        return ValidationResult.Valid(
            CanonicalFields.encode(
                buildMap {
                    put(FIELD_TITLE, title)
                    put(FIELD_START, start.toString())
                    put(FIELD_END, end.toString())
                    put(FIELD_CALENDAR_ID, calendar.id.toString())
                    put(FIELD_CALENDAR_LABEL, calendar.displayName)
                    put(FIELD_TIME_ZONE, calendar.timeZoneId ?: zone.id)
                    if (location.isNotEmpty()) put(FIELD_LOCATION, location)
                },
            ),
        )
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val zone = zoneProvider()
        val fields = CanonicalFields.decode(input)
        val start = CalendarText.formatLocalDateTime(fields.requiredLong(FIELD_START), zone)
        val end = CalendarText.formatLocalDateTime(fields.requiredLong(FIELD_END), zone)
        val location = fields[FIELD_LOCATION]

        return ActionPreview(
            title = "일정 등록",
            summary = buildString {
                append("\"${fields.requiredString(FIELD_TITLE)}\"\n")
                append("$start ~ $end\n")
                if (location != null) append("장소: $location\n")
                append("캘린더: ${fields.requiredString(FIELD_CALENDAR_LABEL)}")
            },
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): CalendarCreateEventResult {
        val fields = CanonicalFields.decode(input)
        val calendarId = fields.requiredLong(FIELD_CALENDAR_ID)

        // The calendar could have been removed or demoted between confirmation and here.
        val calendar = writableCalendarOrNull(calendarId)
            ?: return CalendarCreateEventResult(created = false, eventId = null)

        val eventId = gateway.insertEvent(
            CalendarEventDraft(
                calendarId = calendar.id,
                title = fields.requiredString(FIELD_TITLE),
                startEpochMillis = fields.requiredLong(FIELD_START),
                endEpochMillis = fields.requiredLong(FIELD_END),
                location = fields[FIELD_LOCATION],
                timeZoneId = fields.requiredString(FIELD_TIME_ZONE),
            ),
        )
        return CalendarCreateEventResult(created = eventId != null, eventId = eventId)
    }

    private suspend fun writableCalendarOrNull(calendarId: Long): CalendarAccount? = try {
        gateway.writableCalendars().firstOrNull { calendar -> calendar.id == calendarId }
    } catch (_: CalendarAccessException) {
        null
    }

    companion object {
        const val NAME = "calendar_create_event"
        internal const val FIELD_TITLE = "title"
        internal const val FIELD_START = "start"
        internal const val FIELD_END = "end"
        internal const val FIELD_LOCATION = "location"
        internal const val FIELD_CALENDAR_ID = "calendar_id"
        internal const val FIELD_CALENDAR_LABEL = "calendar_label"
        internal const val FIELD_TIME_ZONE = "time_zone"
    }
}
