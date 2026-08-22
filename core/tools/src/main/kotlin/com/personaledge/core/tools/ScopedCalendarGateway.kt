package com.personaledge.core.tools

/**
 * Confines every calendar tool to the one calendar the user pinned in settings.
 *
 * CalendarContract can contain work accounts, shared family calendars, and birthdays. Filtering
 * here means a model that guesses a calendar or event id cannot read or modify anything outside
 * the pinned calendar. The scope is re-read on every call, so changing it in settings takes effect
 * immediately rather than at the next process start.
 */
class ScopedCalendarGateway(
    private val delegate: CalendarProviderGateway,
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
        val pinned = pinnedCalendarId()
            ?: throw CalendarAccessException("설정에서 사용할 캘린더를 먼저 선택하세요.")
        return delegate
            .queryEvents(pinned, startEpochMillis, endEpochMillis, limit)
            // Provider scoping is authoritative; retain this check as defense in depth against a
            // broken or malicious provider implementation.
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
        val pinned = pinnedCalendarId() ?: return false
        val existing = delegate.findEvent(eventId)
            ?.takeIf { event -> event.calendarId == pinned }
            ?: return false
        check(existing.eventId == eventId)
        // CalendarContract applies both predicates in one update. A sync that moves the row after
        // the read therefore yields zero updated rows instead of writing outside the pinned scope.
        return delegate.updateEvent(pinned, eventId, patch)
    }
}
