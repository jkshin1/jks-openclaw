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
    private val readCalendarIds: suspend () -> Set<Long> = {
        pinnedCalendarId()?.let(::setOf).orEmpty()
    },
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
        val scopedIds = readCalendarIds().filter { it > 0 }.toSet()
        if (scopedIds.isEmpty()) {
            throw CalendarAccessException("설정에서 읽을 캘린더를 먼저 선택하세요.")
        }
        return scopedIds.flatMap { calendarId ->
            delegate
                .queryEvents(calendarId, startEpochMillis, endEpochMillis, limit)
                .filter { event -> event.calendarId == calendarId }
        }
            .sortedBy(CalendarEvent::startEpochMillis)
            .take(limit)
    }

    override suspend fun findEvent(eventId: Long): CalendarEvent? {
        val scopedIds = readCalendarIds()
        return delegate.findEvent(eventId)?.takeIf { event -> event.calendarId in scopedIds }
    }

    override suspend fun insertEvent(draft: CalendarEventDraft): Long? {
        val pinned = pinnedCalendarId() ?: return null
        if (draft.calendarId != pinned) return null
        return delegate.insertEvent(draft)
    }

    override suspend fun updateEvent(
        expected: CalendarEventMutationSnapshot,
        patch: CalendarEventPatch,
    ): Boolean {
        val pinned = pinnedCalendarId() ?: return false
        if (expected.calendarId != pinned || expected.eventId <= 0L) return false
        val existing = delegate.findEvent(expected.eventId)
            ?.takeIf { event -> event.calendarId == pinned }
            ?: return false
        if (existing.mutationSnapshot() != expected) return false
        // The provider repeats this complete comparison in the update WHERE. A sync after this
        // read therefore yields zero rows instead of overwriting state the owner never confirmed.
        return delegate.updateEvent(expected, patch)
    }
}
