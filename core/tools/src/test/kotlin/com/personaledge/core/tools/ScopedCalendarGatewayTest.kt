package com.personaledge.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Several calendars can coexist on the device, so the scope is the boundary that keeps the agent
 * out of every other calendar.
 */
class ScopedCalendarGatewayTest {
    private val naverId = FakeCalendarGateway.NAVER_CALENDAR.id
    private val workId = FakeCalendarGateway.WORK_CALENDAR.id

    private fun event(eventId: Long, calendarId: Long) = CalendarEvent(
        eventId = eventId,
        calendarId = calendarId,
        calendarLabel = "label",
        title = "일정 $eventId",
        startEpochMillis = 1_000,
        endEpochMillis = 2_000,
        allDay = false,
        location = null,
    )

    private fun scoped(
        delegate: FakeCalendarGateway,
        pinned: Long? = naverId,
    ) = ScopedCalendarGateway(delegate) { pinned }

    @Test
    fun `only the pinned calendar is listed as writable`() = runBlocking {
        val delegate = FakeCalendarGateway(
            calendars = listOf(FakeCalendarGateway.NAVER_CALENDAR, FakeCalendarGateway.WORK_CALENDAR),
        )

        assertEquals(listOf(naverId), scoped(delegate).writableCalendars().map(CalendarAccount::id))
    }

    @Test
    fun `events from other calendars are never returned`() = runBlocking {
        val delegate = FakeCalendarGateway(
            events = listOf(event(1, naverId), event(2, workId), event(3, naverId)),
        )

        val visible = scoped(delegate).queryEvents(0, 10_000, limit = 10)

        assertEquals(listOf(1L, 3L), visible.map(CalendarEvent::eventId))
        assertEquals(listOf(naverId), delegate.queriedCalendarIds)
    }

    @Test
    fun `an event id from another calendar cannot be read or updated`() = runBlocking {
        val delegate = FakeCalendarGateway(events = listOf(event(2, workId)))
        val gateway = scoped(delegate)

        assertNull(gateway.findEvent(2))
        assertFalse(gateway.updateEvent(2, CalendarEventPatch(title = "탈취 시도")))
        assertTrue(delegate.patches.isEmpty())
    }

    @Test
    fun `provider move after scoped read is refused atomically`() = runBlocking {
        val delegate = FakeCalendarGateway(events = listOf(event(1, naverId))).apply {
            onProviderUpdate = { current -> current.copy(calendarId = workId) }
        }

        val updated = scoped(delegate).updateEvent(1, CalendarEventPatch(title = "수정 시도"))

        assertFalse(updated)
        assertEquals(workId, delegate.stored.getValue(1).calendarId)
        assertEquals("일정 1", delegate.stored.getValue(1).title)
        assertTrue(delegate.patches.isEmpty())
    }

    @Test
    fun `an insert aimed at another calendar is refused`() = runBlocking {
        val delegate = FakeCalendarGateway()
        val draft = CalendarEventDraft(
            calendarId = workId,
            title = "회사 일정",
            startEpochMillis = 1_000,
            endEpochMillis = 2_000,
            location = null,
            timeZoneId = "Asia/Seoul",
        )

        assertNull(scoped(delegate).insertEvent(draft))
        assertTrue(delegate.inserted.isEmpty())
    }

    @Test
    fun `nothing is readable or writable until a calendar is pinned`() = runBlocking {
        val delegate = FakeCalendarGateway(events = listOf(event(1, naverId)))
        val gateway = scoped(delegate, pinned = null)

        assertTrue(gateway.writableCalendars().isEmpty())
        try {
            gateway.queryEvents(0, 10_000, limit = 10)
            fail("A missing pin must not look like an empty successful calendar query.")
        } catch (expected: CalendarAccessException) {
            assertTrue(expected.message!!.contains("먼저 선택"))
        }
        assertNull(gateway.findEvent(1))
        assertFalse(gateway.updateEvent(1, CalendarEventPatch(title = "무시됨")))
    }

    @Test
    fun `provider scope is applied before the requested limit`() = runBlocking {
        val events = (1..30).map { index -> event(index.toLong(), workId) } +
            listOf(event(31, naverId), event(32, naverId))
        val delegate = FakeCalendarGateway(events = events)

        assertEquals(
            listOf(31L, 32L),
            scoped(delegate).queryEvents(0, 10_000, limit = 2).map(CalendarEvent::eventId),
        )
        assertEquals(listOf(naverId), delegate.queriedCalendarIds)
    }
}
