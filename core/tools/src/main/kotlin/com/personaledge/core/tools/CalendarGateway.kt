package com.personaledge.core.tools

data class CalendarAccount(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val isPrimary: Boolean,
    val timeZoneId: String?,
)

data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val calendarLabel: String,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val allDay: Boolean,
    val location: String?,
)

data class CalendarEventDraft(
    val calendarId: Long,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val location: String?,
    val timeZoneId: String,
)

/** A null field means "leave this alone", which is why the update tool never fills in blanks. */
data class CalendarEventPatch(
    val title: String? = null,
    val startEpochMillis: Long? = null,
    val endEpochMillis: Long? = null,
    val location: String? = null,
) {
    val isEmpty: Boolean
        get() = title == null && startEpochMillis == null && endEpochMillis == null && location == null
}

/**
 * The device calendar as the tools see it.
 *
 * Every method may throw [CalendarAccessException]; the tools convert that into a rejection rather
 * than letting a provider failure surface as an opaque crash.
 */
interface CalendarGateway {
    /** Calendars this app may actually write to. An empty list means creation must be refused. */
    suspend fun writableCalendars(): List<CalendarAccount>

    suspend fun queryEvents(
        startEpochMillis: Long,
        endEpochMillis: Long,
        limit: Int,
    ): List<CalendarEvent>

    suspend fun findEvent(eventId: Long): CalendarEvent?

    /** Returns the new event id, or null when the provider refused the insert. */
    suspend fun insertEvent(draft: CalendarEventDraft): Long?

    suspend fun updateEvent(eventId: Long, patch: CalendarEventPatch): Boolean
}

class CalendarAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)
