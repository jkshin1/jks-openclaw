package com.personaledge.core.tools

import java.time.ZoneId

data class CalendarQueryParams(
    val start: String,
    val end: String,
) : ToolParams

data class CalendarQueryResult(
    val events: List<CalendarEventSummary>,
    val truncated: Boolean,
)

data class CalendarEventSummary(
    val eventId: Long,
    val title: String,
    val start: String,
    val end: String,
    val allDay: Boolean,
    val location: String?,
    val calendar: String,
)

/**
 * Reads a bounded window of the device calendar.
 *
 * READ_ONLY is truthful — nothing is written — so no confirmation is required, but the interlock
 * still re-checks the calendar permission immediately before the read.
 */
class CalendarQueryTool(
    private val gateway: CalendarGateway,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentTool<CalendarQueryParams, CalendarQueryResult> {

    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Read calendar events between two local date-times",
        risk = ToolRisk.READ_ONLY,
        requiredCapabilities = setOf(ToolCapability.READ_CALENDAR),
    )

    override suspend fun validateAndCanonicalize(params: CalendarQueryParams): ValidationResult {
        val zone = zoneProvider()
        val now = clock()

        val start = CalendarText.parseLocalDateTime(params.start, zone)
            ?: return ValidationResult.Invalid("시작 시각은 2026-08-21T09:00 형식이어야 합니다.")
        val end = CalendarText.parseLocalDateTime(params.end, zone)
            ?: return ValidationResult.Invalid("종료 시각은 2026-08-21T18:00 형식이어야 합니다.")

        if (end <= start) {
            return ValidationResult.Invalid("종료 시각이 시작 시각보다 뒤여야 합니다.")
        }
        if (end - start > CalendarText.MAX_QUERY_WINDOW_MILLIS) {
            return ValidationResult.Invalid("한 번에 최대 60일 범위만 조회할 수 있습니다.")
        }
        if (!CalendarText.isWithinSupportedRange(start, now) ||
            !CalendarText.isWithinSupportedRange(end, now)
        ) {
            return ValidationResult.Invalid("과거 1년에서 미래 2년 사이만 조회할 수 있습니다.")
        }

        return ValidationResult.Valid(
            CanonicalFields.encode(
                mapOf(
                    FIELD_START to start.toString(),
                    FIELD_END to end.toString(),
                ),
            ),
        )
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val zone = zoneProvider()
        val fields = CanonicalFields.decode(input)
        val start = CalendarText.formatLocalDateTime(fields.requiredLong(FIELD_START), zone)
        val end = CalendarText.formatLocalDateTime(fields.requiredLong(FIELD_END), zone)
        return ActionPreview(
            title = "일정 조회",
            summary = "$start 부터 $end 까지의 일정을 읽습니다.",
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): CalendarQueryResult {
        val zone = zoneProvider()
        val fields = CanonicalFields.decode(input)

        // One extra row distinguishes "exactly at the cap" from "more than the cap".
        val events = gateway.queryEvents(
            startEpochMillis = fields.requiredLong(FIELD_START),
            endEpochMillis = fields.requiredLong(FIELD_END),
            limit = MAX_EVENTS + 1,
        )

        return CalendarQueryResult(
            events = events.take(MAX_EVENTS).map { event -> event.toSummary(zone) },
            truncated = events.size > MAX_EVENTS,
        )
    }

    companion object {
        const val NAME = "calendar_query"
        const val MAX_EVENTS = 20
        internal const val FIELD_START = "start"
        internal const val FIELD_END = "end"
    }
}

internal fun CalendarEvent.toSummary(zone: ZoneId): CalendarEventSummary = CalendarEventSummary(
    eventId = eventId,
    title = CalendarText
        .sanitizeForModel(title, CalendarText.MAX_TITLE_CHARACTERS)
        .ifEmpty { "(제목 없음)" },
    start = CalendarText.formatLocalDateTime(startEpochMillis, zone),
    end = CalendarText.formatLocalDateTime(endEpochMillis, zone),
    allDay = allDay,
    location = location
        ?.let { value -> CalendarText.sanitizeForModel(value, CalendarText.MAX_LOCATION_CHARACTERS) }
        ?.takeIf(String::isNotEmpty),
    calendar = CalendarText
        .sanitizeForModel(calendarLabel, CalendarText.MAX_TITLE_CHARACTERS)
        .ifEmpty { "(이름 없음)" },
)
