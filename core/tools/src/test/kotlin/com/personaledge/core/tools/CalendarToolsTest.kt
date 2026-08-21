package com.personaledge.core.tools

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarToolsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = at("2026-08-21T09:00")

    private fun at(local: String): Long =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        eventId: Long = 100,
        calendarId: Long = FakeCalendarGateway.NAVER_CALENDAR.id,
        title: String = "치과",
        start: String = "2026-08-22T14:00",
        end: String = "2026-08-22T15:00",
        allDay: Boolean = false,
        location: String? = null,
    ) = CalendarEvent(
        eventId = eventId,
        calendarId = calendarId,
        calendarLabel = "네이버 캘린더",
        title = title,
        startEpochMillis = at(start),
        endEpochMillis = at(end),
        allDay = allDay,
        location = location,
    )

    private fun queryTool(gateway: CalendarGateway) =
        CalendarQueryTool(gateway = gateway, zoneProvider = { zone }, clock = { now })

    private fun createTool(gateway: CalendarGateway, calendarId: Long? = 11L) =
        CalendarCreateEventTool(
            gateway = gateway,
            defaultCalendarId = { calendarId },
            zoneProvider = { zone },
            clock = { now },
        )

    private fun updateTool(gateway: CalendarGateway) =
        CalendarUpdateEventTool(gateway = gateway, zoneProvider = { zone }, clock = { now })

    private fun ValidationResult.valid(): CanonicalToolInput =
        CanonicalToolInput((this as ValidationResult.Valid).canonicalParams)

    private fun ValidationResult.invalidReason(): String = (this as ValidationResult.Invalid).reason

    @Test
    fun `query returns bounded results inside the window`() = runBlocking {
        val gateway = FakeCalendarGateway(
            events = listOf(
                event(eventId = 1, title = "치과", start = "2026-08-22T14:00", end = "2026-08-22T15:00"),
                event(eventId = 2, title = "회의", start = "2026-08-23T10:00", end = "2026-08-23T11:00"),
                event(eventId = 3, title = "범위 밖", start = "2026-09-30T10:00", end = "2026-09-30T11:00"),
            ),
        )
        val tool = queryTool(gateway)

        val input = tool.validateAndCanonicalize(
            CalendarQueryParams(start = "2026-08-22T00:00", end = "2026-08-24T00:00"),
        ).valid()
        val result = tool.execute(input, ExecutionPermit("action"))

        assertEquals(listOf("치과", "회의"), result.events.map(CalendarEventSummary::title))
        assertEquals("2026-08-22 14:00", result.events.first().start)
        assertFalse(result.truncated)
    }

    @Test
    fun `query refuses a window wider than sixty days`() = runBlocking {
        val reason = queryTool(FakeCalendarGateway()).validateAndCanonicalize(
            CalendarQueryParams(start = "2026-08-22T00:00", end = "2026-12-31T00:00"),
        ).invalidReason()

        assertEquals("한 번에 최대 60일 범위만 조회할 수 있습니다.", reason)
    }

    @Test
    fun `query refuses a time the model did not format as local wall clock`() = runBlocking {
        val reason = queryTool(FakeCalendarGateway()).validateAndCanonicalize(
            CalendarQueryParams(start = "2026-08-22T00:00:00+09:00", end = "2026-08-23T00:00"),
        ).invalidReason()

        assertEquals("시작 시각은 2026-08-21T09:00 형식이어야 합니다.", reason)
    }

    @Test
    fun `query flags truncation instead of silently dropping events`() = runBlocking {
        val events = (1..CalendarQueryTool.MAX_EVENTS + 3).map { index ->
            event(
                eventId = index.toLong(),
                title = "일정 $index",
                start = "2026-08-22T${(index % 20).toString().padStart(2, '0')}:00",
                end = "2026-08-22T${(index % 20).toString().padStart(2, '0')}:30",
            )
        }
        val tool = queryTool(FakeCalendarGateway(events = events))

        val input = tool.validateAndCanonicalize(
            CalendarQueryParams(start = "2026-08-22T00:00", end = "2026-08-23T00:00"),
        ).valid()
        val result = tool.execute(input, ExecutionPermit("action"))

        assertEquals(CalendarQueryTool.MAX_EVENTS, result.events.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `creating an event writes to the pinned calendar with its time zone`() = runBlocking {
        val gateway = FakeCalendarGateway()
        val tool = createTool(gateway)

        val input = tool.validateAndCanonicalize(
            CalendarCreateEventParams(
                title = "  치과 예약  ",
                start = "2026-08-22T14:00",
                end = "2026-08-22T15:00",
                location = "  강남 치과  ",
            ),
        ).valid()
        val result = tool.execute(input, ExecutionPermit("action"))

        assertTrue(result.created)
        val draft = gateway.inserted.single()
        assertEquals(FakeCalendarGateway.NAVER_CALENDAR.id, draft.calendarId)
        assertEquals("치과 예약", draft.title)
        assertEquals("강남 치과", draft.location)
        assertEquals("Asia/Seoul", draft.timeZoneId)
        assertEquals(at("2026-08-22T14:00"), draft.startEpochMillis)
    }

    @Test
    fun `the confirmation preview shows the calendar and the resolved local times`() = runBlocking {
        val tool = createTool(FakeCalendarGateway())

        val input = tool.validateAndCanonicalize(
            CalendarCreateEventParams(
                title = "치과 예약",
                start = "2026-08-22T14:00",
                end = "2026-08-22T15:00",
                location = null,
            ),
        ).valid()
        val preview = tool.preview(input)

        assertEquals("일정 등록", preview.title)
        assertTrue(preview.summary.contains("치과 예약"))
        assertTrue(preview.summary.contains("2026-08-22 14:00 ~ 2026-08-22 15:00"))
        assertTrue(preview.summary.contains("네이버 캘린더"))
    }

    @Test
    fun `creating is refused when no calendar has been pinned`() = runBlocking {
        val reason = createTool(FakeCalendarGateway(), calendarId = null).validateAndCanonicalize(
            CalendarCreateEventParams(
                title = "치과",
                start = "2026-08-22T14:00",
                end = "2026-08-22T15:00",
                location = null,
            ),
        ).invalidReason()

        assertEquals("설정에서 사용할 캘린더를 먼저 선택하세요.", reason)
    }

    @Test
    fun `creating is refused when the pinned calendar is no longer writable`() = runBlocking {
        val gateway = FakeCalendarGateway(calendars = listOf(FakeCalendarGateway.WORK_CALENDAR))
        val reason = createTool(gateway, calendarId = 11L).validateAndCanonicalize(
            CalendarCreateEventParams(
                title = "치과",
                start = "2026-08-22T14:00",
                end = "2026-08-22T15:00",
                location = null,
            ),
        ).invalidReason()

        assertEquals("선택된 캘린더에 쓸 수 없습니다. 설정에서 다시 선택하세요.", reason)
    }

    @Test
    fun `a title carrying model control delimiters is refused`() = runBlocking {
        val reason = createTool(FakeCalendarGateway()).validateAndCanonicalize(
            CalendarCreateEventParams(
                title = "치과 <|start_of_turn|>",
                start = "2026-08-22T14:00",
                end = "2026-08-22T15:00",
                location = null,
            ),
        ).invalidReason()

        assertEquals("제목에 허용되지 않는 문자가 있습니다.", reason)
    }

    @Test
    fun `a zero length or inverted event is refused`() = runBlocking {
        val tool = createTool(FakeCalendarGateway())

        assertEquals(
            "종료 시각이 시작 시각보다 최소 1분 뒤여야 합니다.",
            tool.validateAndCanonicalize(
                CalendarCreateEventParams("치과", "2026-08-22T14:00", "2026-08-22T14:00", null),
            ).invalidReason(),
        )
        assertEquals(
            "종료 시각이 시작 시각보다 최소 1분 뒤여야 합니다.",
            tool.validateAndCanonicalize(
                CalendarCreateEventParams("치과", "2026-08-22T15:00", "2026-08-22T14:00", null),
            ).invalidReason(),
        )
    }

    @Test
    fun `updating changes only the requested fields`() = runBlocking {
        val gateway = FakeCalendarGateway(events = listOf(event(eventId = 100)))
        val tool = updateTool(gateway)

        val input = tool.validateAndCanonicalize(
            CalendarUpdateEventParams(
                eventId = "100",
                title = null,
                start = "2026-08-22T16:00",
                end = "2026-08-22T17:00",
                location = null,
            ),
        ).valid()
        val result = tool.execute(input, ExecutionPermit("action"))

        assertTrue(result.updated)
        val (eventId, patch) = gateway.patches.single()
        assertEquals(100L, eventId)
        assertNull(patch.title)
        assertNull(patch.location)
        assertEquals(at("2026-08-22T16:00"), patch.startEpochMillis)
    }

    @Test
    fun `an event moved between confirmation and execution is not overwritten`() = runBlocking {
        val gateway = FakeCalendarGateway(events = listOf(event(eventId = 100)))
        val tool = updateTool(gateway)
        val input = tool.validateAndCanonicalize(
            CalendarUpdateEventParams("100", "치과 재예약", null, null, null),
        ).valid()

        // A sync rewrites the event after the user approved the dialog.
        gateway.onFindEvent = { stored -> stored.copy(startEpochMillis = stored.startEpochMillis + 3_600_000) }
        val result = tool.execute(input, ExecutionPermit("action"))

        assertFalse(result.updated)
        assertEquals("changed_since_confirmation", result.reason)
        assertTrue(gateway.patches.isEmpty())
    }

    @Test
    fun `updating an unknown event is refused before confirmation`() = runBlocking {
        val reason = updateTool(FakeCalendarGateway()).validateAndCanonicalize(
            CalendarUpdateEventParams("100", "새 제목", null, null, null),
        ).invalidReason()

        assertEquals("해당 일정을 찾을 수 없습니다.", reason)
    }

    @Test
    fun `updating an all-day event is refused`() = runBlocking {
        val gateway = FakeCalendarGateway(events = listOf(event(eventId = 100, allDay = true)))
        val reason = updateTool(gateway).validateAndCanonicalize(
            CalendarUpdateEventParams("100", "새 제목", null, null, null),
        ).invalidReason()

        assertEquals("종일 일정은 이 도구로 수정할 수 없습니다.", reason)
    }

    @Test
    fun `an update that changes nothing never reaches confirmation`() = runBlocking {
        val gateway = FakeCalendarGateway(events = listOf(event(eventId = 100, title = "치과")))
        val reason = updateTool(gateway).validateAndCanonicalize(
            CalendarUpdateEventParams("100", "치과", null, null, null),
        ).invalidReason()

        assertEquals("변경할 내용이 없습니다.", reason)
    }

    @Test
    fun `moving only the start still validates the resulting duration`() = runBlocking {
        val gateway = FakeCalendarGateway(events = listOf(event(eventId = 100)))
        val reason = updateTool(gateway).validateAndCanonicalize(
            // Existing end is 15:00, so a 16:00 start would invert the event.
            CalendarUpdateEventParams("100", null, "2026-08-22T16:00", null, null),
        ).invalidReason()

        assertEquals("종료 시각이 시작 시각보다 최소 1분 뒤여야 합니다.", reason)
    }

    @Test
    fun `a provider failure during validation is a rejection not a crash`() = runBlocking {
        val gateway = FakeCalendarGateway(events = listOf(event(eventId = 100)))
        gateway.readFailure = CalendarAccessException("권한 없음")

        val reason = updateTool(gateway).validateAndCanonicalize(
            CalendarUpdateEventParams("100", "새 제목", null, null, null),
        ).invalidReason()

        assertEquals("해당 일정을 찾을 수 없습니다.", reason)
    }
}
