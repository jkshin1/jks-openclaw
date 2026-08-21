package com.personaledge.core.tools

/** Deterministic in-memory stand-in for `CalendarContract`. */
internal class FakeCalendarGateway(
    var calendars: List<CalendarAccount> = listOf(NAVER_CALENDAR),
    events: List<CalendarEvent> = emptyList(),
) : CalendarGateway {
    val stored = events.associateBy(CalendarEvent::eventId).toMutableMap()
    val inserted = mutableListOf<CalendarEventDraft>()
    val patches = mutableListOf<Pair<Long, CalendarEventPatch>>()

    var readFailure: CalendarAccessException? = null
    var nextEventId = 9_000L

    /** Simulates a background sync that moves an event while the confirmation dialog is open. */
    var onFindEvent: ((CalendarEvent) -> CalendarEvent)? = null

    override suspend fun writableCalendars(): List<CalendarAccount> {
        readFailure?.let { failure -> throw failure }
        return calendars
    }

    override suspend fun queryEvents(
        startEpochMillis: Long,
        endEpochMillis: Long,
        limit: Int,
    ): List<CalendarEvent> {
        readFailure?.let { failure -> throw failure }
        return stored.values
            .filter { event ->
                event.startEpochMillis < endEpochMillis && event.endEpochMillis > startEpochMillis
            }
            .sortedBy(CalendarEvent::startEpochMillis)
            .take(limit)
    }

    override suspend fun findEvent(eventId: Long): CalendarEvent? {
        readFailure?.let { failure -> throw failure }
        val event = stored[eventId] ?: return null
        return onFindEvent?.invoke(event) ?: event
    }

    override suspend fun insertEvent(draft: CalendarEventDraft): Long? {
        inserted += draft
        val id = nextEventId++
        stored[id] = CalendarEvent(
            eventId = id,
            calendarId = draft.calendarId,
            calendarLabel = calendars.firstOrNull { it.id == draft.calendarId }?.displayName.orEmpty(),
            title = draft.title,
            startEpochMillis = draft.startEpochMillis,
            endEpochMillis = draft.endEpochMillis,
            allDay = false,
            location = draft.location,
        )
        return id
    }

    override suspend fun updateEvent(eventId: Long, patch: CalendarEventPatch): Boolean {
        val existing = stored[eventId] ?: return false
        patches += eventId to patch
        stored[eventId] = existing.copy(
            title = patch.title ?: existing.title,
            startEpochMillis = patch.startEpochMillis ?: existing.startEpochMillis,
            endEpochMillis = patch.endEpochMillis ?: existing.endEpochMillis,
            location = patch.location ?: existing.location,
        )
        return true
    }

    companion object {
        val NAVER_CALENDAR = CalendarAccount(
            id = 11,
            displayName = "네이버 캘린더",
            accountName = "personal@naver.com",
            isPrimary = true,
            timeZoneId = "Asia/Seoul",
        )
        val WORK_CALENDAR = CalendarAccount(
            id = 22,
            displayName = "회사 일정",
            accountName = "work@example.com",
            isPrimary = false,
            timeZoneId = "Asia/Seoul",
        )
    }
}
