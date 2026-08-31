package com.personaledge.core.tools

internal object CalendarConflictPolicy {
    const val QUERY_LIMIT = 21

    fun overlapping(
        events: List<CalendarEvent>,
        startEpochMillis: Long,
        endEpochMillis: Long,
        excludingEventId: Long? = null,
    ): List<CalendarEvent> = events.filter { event ->
        event.eventId != excludingEventId &&
            event.startEpochMillis < endEpochMillis &&
            event.endEpochMillis > startEpochMillis
    }
}
