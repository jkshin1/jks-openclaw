package com.personaledge.core.tools

data class CalendarAccount(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
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
    /** True when this row is the master of a recurring series. */
    val recurring: Boolean = false,
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
 * Effect-bearing provider state that must still match when an approved update is applied.
 *
 * [calendarLabel] is deliberately absent because it is display metadata from the calendar table,
 * not part of the event row being changed. Recurrence is represented as the closed boolean the
 * tool already validates; the write path only accepts a non-recurring snapshot.
 */
data class CalendarEventMutationSnapshot(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val allDay: Boolean,
    val recurring: Boolean,
    val location: String?,
)

fun CalendarEvent.mutationSnapshot(): CalendarEventMutationSnapshot =
    CalendarEventMutationSnapshot(
        eventId = eventId,
        calendarId = calendarId,
        title = title,
        startEpochMillis = startEpochMillis,
        endEpochMillis = endEpochMillis,
        allDay = allDay,
        recurring = recurring,
        location = location,
    )

/** Common provider operations shared by the scoped Tool view and the raw Android adapter. */
interface CalendarOperations {
    /** Calendars this app may actually write to. An empty list means creation must be refused. */
    suspend fun writableCalendars(): List<CalendarAccount>

    suspend fun findEvent(eventId: Long): CalendarEvent?

    /** Returns the new event id, or null when the provider refused the insert. */
    suspend fun insertEvent(draft: CalendarEventDraft): Long?
}

/**
 * The already-scoped calendar view exposed to Tools.
 *
 * Every method may throw [CalendarAccessException]; the tools convert that into a rejection rather
 * than letting a provider failure surface as an opaque crash.
 */
interface CalendarGateway : CalendarOperations {
    suspend fun queryEvents(
        startEpochMillis: Long,
        endEpochMillis: Long,
        limit: Int,
    ): List<CalendarEvent>

    /** Legacy entry point is fail-closed; confirmed writes must provide the full expected row. */
    suspend fun updateEvent(eventId: Long, patch: CalendarEventPatch): Boolean = false

    suspend fun updateEvent(
        expected: CalendarEventMutationSnapshot,
        patch: CalendarEventPatch,
    ): Boolean = false
}

/**
 * Raw provider boundary used only by [ScopedCalendarGateway].
 *
 * Requiring [calendarId] here prevents a global provider page from being truncated before the
 * pinned-calendar filter runs. The Android query itself must carry this scope.
 */
interface CalendarProviderGateway : CalendarOperations {
    suspend fun queryEvents(
        calendarId: Long,
        startEpochMillis: Long,
        endEpochMillis: Long,
        limit: Int,
    ): List<CalendarEvent>

    /** Legacy entry point is fail-closed; provider writes require an effect-bearing snapshot. */
    suspend fun updateEvent(
        expectedCalendarId: Long,
        eventId: Long,
        patch: CalendarEventPatch,
    ): Boolean = false

    /** The provider must compare every expected field in the same selection as the update. */
    suspend fun updateEvent(
        expected: CalendarEventMutationSnapshot,
        patch: CalendarEventPatch,
    ): Boolean = false
}

class CalendarAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)
