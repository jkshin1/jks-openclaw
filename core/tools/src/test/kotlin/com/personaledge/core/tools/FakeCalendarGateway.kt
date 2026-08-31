package com.personaledge.core.tools

/** Deterministic in-memory stand-in for `CalendarContract`. */
internal class FakeCalendarGateway(
    var calendars: List<CalendarAccount> = listOf(NAVER_CALENDAR),
    events: List<CalendarEvent> = emptyList(),
) : CalendarGateway, CalendarProviderGateway {
    val stored = events.associateBy(CalendarEvent::eventId).toMutableMap()
    val inserted = mutableListOf<CalendarEventDraft>()
    val patches = mutableListOf<Pair<Long, CalendarEventPatch>>()

    var readFailure: CalendarAccessException? = null
    var nextEventId = 9_000L
    val queriedCalendarIds = mutableListOf<Long>()

    /** Simulates a background sync that moves an event while the confirmation dialog is open. */
    var onFindEvent: ((CalendarEvent) -> CalendarEvent)? = null

    /** Simulates a provider move after the scoped read but before its atomic update selection. */
    var onProviderUpdate: ((CalendarEvent) -> CalendarEvent)? = null

    override suspend fun writableCalendars(): List<CalendarAccount> {
        readFailure?.let { failure -> throw failure }
        return calendars
    }

    override suspend fun queryEvents(
        startEpochMillis: Long,
        endEpochMillis: Long,
        limit: Int,
    ): List<CalendarEvent> = queryEventsInternal(
        calendarId = null,
        startEpochMillis = startEpochMillis,
        endEpochMillis = endEpochMillis,
        limit = limit,
    )

    override suspend fun queryEvents(
        calendarId: Long,
        startEpochMillis: Long,
        endEpochMillis: Long,
        limit: Int,
    ): List<CalendarEvent> {
        queriedCalendarIds += calendarId
        return queryEventsInternal(calendarId, startEpochMillis, endEpochMillis, limit)
    }

    private fun queryEventsInternal(
        calendarId: Long?,
        startEpochMillis: Long,
        endEpochMillis: Long,
        limit: Int,
    ): List<CalendarEvent> {
        readFailure?.let { failure -> throw failure }
        return stored.values
            .filter { event ->
                (calendarId == null || event.calendarId == calendarId) &&
                    event.startEpochMillis < endEpochMillis &&
                    event.endEpochMillis > startEpochMillis
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
        return applyPatch(eventId, existing, patch)
    }

    override suspend fun updateEvent(
        expectedCalendarId: Long,
        eventId: Long,
        patch: CalendarEventPatch,
    ): Boolean {
        var existing = stored[eventId] ?: return false
        onProviderUpdate?.let { move ->
            existing = move(existing)
            stored[eventId] = existing
        }
        if (existing.calendarId != expectedCalendarId) return false
        return applyPatch(eventId, existing, patch)
    }

    override suspend fun updateEvent(
        expected: CalendarEventMutationSnapshot,
        patch: CalendarEventPatch,
    ): Boolean {
        var existing = stored[expected.eventId] ?: return false
        onProviderUpdate?.let { mutate ->
            existing = mutate(existing)
            stored[expected.eventId] = existing
        }
        if (existing.mutationSnapshot() != expected) return false
        return applyPatch(expected.eventId, existing, patch)
    }

    private fun applyPatch(
        eventId: Long,
        existing: CalendarEvent,
        patch: CalendarEventPatch,
    ): Boolean {
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
            accountType = "fake.naver.calendar",
            isPrimary = true,
            timeZoneId = "Asia/Seoul",
        )
        val WORK_CALENDAR = CalendarAccount(
            id = 22,
            displayName = "회사 일정",
            accountName = "work@example.com",
            accountType = "fake.work.calendar",
            isPrimary = false,
            timeZoneId = "Asia/Seoul",
        )
    }
}
