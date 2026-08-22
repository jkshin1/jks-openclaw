package com.personaledge.core.tools

import java.time.ZoneId

data class CalendarUpdateEventParams(
    val eventId: String,
    val title: String?,
    val start: String?,
    val end: String?,
    val location: String?,
) : ToolParams

data class CalendarUpdateEventResult(
    val updated: Boolean,
    val reason: String?,
)

/**
 * Changes one existing event.
 *
 * The model chooses the event id, so this tool never trusts it: the event is read during
 * validation, the preview shows the current values next to the proposed ones, and a digest of the
 * event as confirmed is re-checked immediately before the write. If a sync moved the event while
 * the dialog was open, the update aborts rather than overwriting something the user never saw.
 */
class CalendarUpdateEventTool(
    private val gateway: CalendarGateway,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentTool<CalendarUpdateEventParams, CalendarUpdateEventResult> {

    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Change the title, time, or location of one existing calendar event",
        risk = ToolRisk.DATA_WRITE,
        requiredCapabilities = setOf(ToolCapability.READ_CALENDAR, ToolCapability.WRITE_CALENDAR),
    )

    override suspend fun validateAndCanonicalize(
        params: CalendarUpdateEventParams,
    ): ValidationResult {
        val zone = zoneProvider()
        val now = clock()

        val eventId = params.eventId.trim().toLongOrNull()
            ?: return ValidationResult.Invalid("일정 ID가 올바르지 않습니다.")
        if (eventId <= 0) {
            return ValidationResult.Invalid("일정 ID가 올바르지 않습니다.")
        }

        val existing = try {
            gateway.findEvent(eventId)
        } catch (_: CalendarAccessException) {
            null
        } ?: return ValidationResult.Invalid("해당 일정을 찾을 수 없습니다.")

        val writable = try {
            gateway.writableCalendars().any { calendar -> calendar.id == existing.calendarId }
        } catch (_: CalendarAccessException) {
            false
        }
        if (!writable) {
            return ValidationResult.Invalid("이 일정이 속한 캘린더는 수정할 수 없습니다.")
        }
        if (existing.allDay) {
            // All-day events use a separate date/UTC convention; editing them here would corrupt them.
            return ValidationResult.Invalid("종일 일정은 이 도구로 수정할 수 없습니다.")
        }
        if (existing.recurring) {
            // Instances exposes one occurrence with the master event ID. Updating Events/{id}
            // would silently rewrite the whole series while the user appears to approve one row.
            return ValidationResult.Invalid("반복 일정은 범위 선택 기능이 준비될 때까지 수정할 수 없습니다.")
        }

        val title = params.title?.trim()
        if (title != null) {
            if (title.isEmpty() ||
                CalendarText.codePointLength(title) > CalendarText.MAX_TITLE_CHARACTERS
            ) {
                return ValidationResult.Invalid("제목은 1자 이상 120자 이하여야 합니다.")
            }
            if (!CalendarText.isSafeText(title)) {
                return ValidationResult.Invalid("제목에 허용되지 않는 문자가 있습니다.")
            }
        }

        val location = params.location?.trim()
        if (location != null && location.isNotEmpty()) {
            if (CalendarText.codePointLength(location) > CalendarText.MAX_LOCATION_CHARACTERS) {
                return ValidationResult.Invalid("장소는 200자 이하여야 합니다.")
            }
            if (!CalendarText.isSafeText(location)) {
                return ValidationResult.Invalid("장소에 허용되지 않는 문자가 있습니다.")
            }
        }

        val start = params.start?.let { value ->
            CalendarText.parseLocalDateTime(value, zone)
                ?: return ValidationResult.Invalid("시작 시각은 2026-08-21T09:00 형식이어야 합니다.")
        }
        val end = params.end?.let { value ->
            CalendarText.parseLocalDateTime(value, zone)
                ?: return ValidationResult.Invalid("종료 시각은 2026-08-21T10:00 형식이어야 합니다.")
        }

        // Times are validated as the pair the event will actually have, not one field at a time.
        val resultingStart = start ?: existing.startEpochMillis
        val resultingEnd = end ?: existing.endEpochMillis
        val duration = resultingEnd - resultingStart
        if (duration < CalendarText.MIN_DURATION_MILLIS) {
            return ValidationResult.Invalid("종료 시각이 시작 시각보다 최소 1분 뒤여야 합니다.")
        }
        if (duration > CalendarText.MAX_DURATION_MILLIS) {
            return ValidationResult.Invalid("한 일정은 최대 30일까지만 이어질 수 있습니다.")
        }
        if (!CalendarText.isWithinSupportedRange(resultingStart, now) ||
            !CalendarText.isWithinSupportedRange(resultingEnd, now)
        ) {
            return ValidationResult.Invalid("과거 1년에서 미래 2년 사이만 수정할 수 있습니다.")
        }

        val changesTitle = title != null && title != existing.title
        val changesLocation = location != null && location != existing.location.orEmpty()
        val changesStart = start != null && start != existing.startEpochMillis
        val changesEnd = end != null && end != existing.endEpochMillis
        if (!changesTitle && !changesLocation && !changesStart && !changesEnd) {
            return ValidationResult.Invalid("변경할 내용이 없습니다.")
        }

        return ValidationResult.Valid(
            CanonicalFields.encode(
                buildMap {
                    put(FIELD_EVENT_ID, eventId.toString())
                    put(FIELD_EXPECTED_DIGEST, CalendarText.eventDigest(existing))
                    put(FIELD_CURRENT_TITLE, existing.title)
                    put(FIELD_CURRENT_START, existing.startEpochMillis.toString())
                    put(FIELD_CURRENT_END, existing.endEpochMillis.toString())
                    if (changesTitle) put(FIELD_TITLE, title)
                    if (changesStart) put(FIELD_START, start.toString())
                    if (changesEnd) put(FIELD_END, end.toString())
                    if (changesLocation) put(FIELD_LOCATION, location)
                },
            ),
        )
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val zone = zoneProvider()
        val fields = CanonicalFields.decode(input)
        val currentStart = CalendarText.formatLocalDateTime(fields.requiredLong(FIELD_CURRENT_START), zone)
        val currentEnd = CalendarText.formatLocalDateTime(fields.requiredLong(FIELD_CURRENT_END), zone)

        return ActionPreview(
            title = "일정 수정",
            summary = buildString {
                append("현재: \"${fields.requiredString(FIELD_CURRENT_TITLE)}\" $currentStart ~ $currentEnd\n")
                append("변경:\n")
                fields[FIELD_TITLE]?.let { value -> append("  제목 → \"$value\"\n") }
                fields[FIELD_START]?.let { value ->
                    append("  시작 → ${CalendarText.formatLocalDateTime(value.toLong(), zone)}\n")
                }
                fields[FIELD_END]?.let { value ->
                    append("  종료 → ${CalendarText.formatLocalDateTime(value.toLong(), zone)}\n")
                }
                fields[FIELD_LOCATION]?.let { value -> append("  장소 → \"$value\"\n") }
            }.trimEnd(),
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): CalendarUpdateEventResult {
        val fields = CanonicalFields.decode(input)
        val eventId = fields.requiredLong(FIELD_EVENT_ID)

        val current = try {
            gateway.findEvent(eventId)
        } catch (_: CalendarAccessException) {
            null
        } ?: return CalendarUpdateEventResult(updated = false, reason = "not_found")

        if (CalendarText.eventDigest(current) != fields.requiredString(FIELD_EXPECTED_DIGEST)) {
            return CalendarUpdateEventResult(updated = false, reason = "changed_since_confirmation")
        }

        val patch = CalendarEventPatch(
            title = fields[FIELD_TITLE],
            startEpochMillis = fields[FIELD_START]?.toLong(),
            endEpochMillis = fields[FIELD_END]?.toLong(),
            location = fields[FIELD_LOCATION],
        )
        if (patch.isEmpty) {
            return CalendarUpdateEventResult(updated = false, reason = "no_change")
        }

        val updated = gateway.updateEvent(eventId, patch)
        return CalendarUpdateEventResult(
            updated = updated,
            reason = if (updated) null else "rejected_by_provider",
        )
    }

    override fun executionOutcome(result: CalendarUpdateEventResult): ToolExecutionOutcome =
        if (result.updated) {
            ToolExecutionOutcome.WRITE_COMPLETED
        } else {
            ToolExecutionOutcome.WRITE_REFUSED
        }

    companion object {
        const val NAME = "calendar_update_event"
        internal const val FIELD_EVENT_ID = "event_id"
        internal const val FIELD_EXPECTED_DIGEST = "expected_digest"
        internal const val FIELD_CURRENT_TITLE = "current_title"
        internal const val FIELD_CURRENT_START = "current_start"
        internal const val FIELD_CURRENT_END = "current_end"
        internal const val FIELD_TITLE = "title"
        internal const val FIELD_START = "start"
        internal const val FIELD_END = "end"
        internal const val FIELD_LOCATION = "location"
    }
}
