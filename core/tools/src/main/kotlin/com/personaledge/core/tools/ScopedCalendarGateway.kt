package com.personaledge.core.tools

/**
 * Confines every calendar tool to the one calendar the user pinned in settings.
 *
 * NAVER Calendar arrives on the device through a CalDAV sync client, so it sits alongside whatever
 * else is synced — work accounts, shared family calendars, birthdays. Filtering here means a model
 * that guesses a calendar id or an event id cannot read or modify anything outside the pinned
 * calendar, no matter what it asks for. The scope is re-read on every call, so changing it in
 * settings takes effect immediately rather than at the next process start.
 */
class ScopedCalendarGateway(
    private val delegate: CalendarGateway,
    private val pinnedCalendarId: suspend () -> Long?,
) : CalendarGateway {

    override suspend fun writableCalendars(): List<CalendarAccount> {
        val pinned = pinnedCalendarId() ?: return emptyList()
        return delegate.writableCalendars().filter { calendar -> calendar.id == pinned }
    }

    override suspend fun queryEvents(
        startEpochMillis: Long,
        endEpochMillis: Long,
        limit: Int,
    ): List<CalendarEvent> {
        val pinned = pinnedCalendarId() ?: return emptyList()
        // Over-fetch so filtering out other calendars cannot silently shorten the page.
        val fetchLimit = (limit.toLong() * OVER_FETCH_FACTOR)
            .coerceAtMost(MAX_FETCH_ROWS.toLong())
            .toInt()
        return delegate
            .queryEvents(startEpochMillis, endEpochMillis, fetchLimit)
            .filter { event -> event.calendarId == pinned }
            .take(limit)
    }

    override suspend fun findEvent(eventId: Long): CalendarEvent? {
        val pinned = pinnedCalendarId() ?: return null
        return delegate.findEvent(eventId)?.takeIf { event -> event.calendarId == pinned }
    }

    override suspend fun insertEvent(draft: CalendarEventDraft): Long? {
        val pinned = pinnedCalendarId() ?: return null
        if (draft.calendarId != pinned) return null
        return delegate.insertEvent(draft)
    }

    override suspend fun updateEvent(eventId: Long, patch: CalendarEventPatch): Boolean {
        // Reuses the scoped read, so an event outside the pinned calendar is never updated.
        findEvent(eventId) ?: return false
        return delegate.updateEvent(eventId, patch)
    }

    private companion object {
        const val OVER_FETCH_FACTOR = 5
        const val MAX_FETCH_ROWS = 200
    }
}
