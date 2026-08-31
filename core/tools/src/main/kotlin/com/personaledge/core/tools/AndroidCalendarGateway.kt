package com.personaledge.core.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `CalendarContract` view of whatever calendars are synced onto the device.
 *
 * This adapter operates on calendars already published into the system provider. Provider
 * identity must be qualified separately; the app pins the one account it may touch through
 * [ScopedCalendarGateway].
 */
class AndroidCalendarGateway(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CalendarProviderGateway {
    private val contentResolver = context.applicationContext.contentResolver

    /** Every synced calendar, unfiltered. The settings screen uses this to let the user pick one. */
    suspend fun syncedCalendars(): List<CalendarAccount> = withContext(ioDispatcher) {
        readCalendars(writableOnly = false)
    }

    override suspend fun writableCalendars(): List<CalendarAccount> = withContext(ioDispatcher) {
        readCalendars(writableOnly = true)
    }

    override suspend fun queryEvents(
        calendarId: Long,
        startEpochMillis: Long,
        endEpochMillis: Long,
        limit: Int,
    ): List<CalendarEvent> = withContext(ioDispatcher) {
        require(limit in 1..MAX_ROWS)
        val labels = calendarLabels()

        // Instances expands recurring events into concrete occurrences inside the window.
        val uri = CalendarContract.Instances.CONTENT_URI
            .buildUpon()
            .appendPath(startEpochMillis.toString())
            .appendPath(endEpochMillis.toString())
            .build()

        guarded("일정을 읽지 못했습니다.") {
            contentResolver.query(
                uri,
                INSTANCE_PROJECTION,
                "${CalendarContract.Instances.CALENDAR_ID} = ?",
                arrayOf(calendarId.toString()),
                "${CalendarContract.Instances.BEGIN} ASC",
            )
        }.useRows(limit) { cursor ->
            val calendarId = cursor.getLong(INSTANCE_CALENDAR_ID)
            CalendarEvent(
                eventId = cursor.getLong(INSTANCE_EVENT_ID),
                calendarId = calendarId,
                calendarLabel = labels[calendarId].orEmpty(),
                title = cursor.getStringOrEmpty(INSTANCE_TITLE),
                startEpochMillis = cursor.getLong(INSTANCE_BEGIN),
                endEpochMillis = cursor.getLong(INSTANCE_END),
                allDay = cursor.getInt(INSTANCE_ALL_DAY) != 0,
                location = cursor.getStringOrNull(INSTANCE_LOCATION),
            )
        }
    }

    override suspend fun findEvent(eventId: Long): CalendarEvent? = withContext(ioDispatcher) {
        val labels = calendarLabels()
        guarded("일정을 읽지 못했습니다.") {
            contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                EVENT_PROJECTION,
                // A deleted row lingers until the sync adapter removes it; it is not a real event.
                "${CalendarContract.Events.DELETED} = 0",
                null,
                null,
            )
        }.useRows(1) { cursor ->
            val calendarId = cursor.getLong(EVENT_CALENDAR_ID)
            CalendarEvent(
                eventId = cursor.getLong(EVENT_ID),
                calendarId = calendarId,
                calendarLabel = labels[calendarId].orEmpty(),
                title = cursor.getStringOrEmpty(EVENT_TITLE),
                startEpochMillis = cursor.getLong(EVENT_DTSTART),
                endEpochMillis = cursor.getLong(EVENT_DTEND),
                allDay = cursor.getInt(EVENT_ALL_DAY) != 0,
                // CalendarContract supports both rule-based and explicit-date recurrence. Either
                // form represents a series master that must not be edited as one occurrence.
                recurring = cursor.getStringOrNull(EVENT_RRULE) != null ||
                    cursor.getStringOrNull(EVENT_RDATE) != null,
                // Preserve empty versus SQL NULL so the subsequent provider compare-and-set can
                // bind the exact nullable column rather than broadening its selection.
                location = cursor.getRawStringOrNull(EVENT_LOCATION),
            )
        }.firstOrNull()
    }

    override suspend fun insertEvent(draft: CalendarEventDraft): Long? = withContext(ioDispatcher) {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, draft.calendarId)
            put(CalendarContract.Events.TITLE, draft.title)
            put(CalendarContract.Events.DTSTART, draft.startEpochMillis)
            put(CalendarContract.Events.DTEND, draft.endEpochMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, draft.timeZoneId)
            draft.location?.let { value -> put(CalendarContract.Events.EVENT_LOCATION, value) }
        }

        val uri = try {
            contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        } catch (failure: SecurityException) {
            throw CalendarAccessException("캘린더 쓰기 권한이 없습니다.", failure)
        } catch (failure: IllegalArgumentException) {
            throw CalendarAccessException("캘린더가 일정을 거부했습니다.", failure)
        }
        uri?.let(ContentUris::parseId)?.takeIf { id -> id > 0 }
    }

    override suspend fun updateEvent(
        expected: CalendarEventMutationSnapshot,
        patch: CalendarEventPatch,
    ): Boolean = withContext(ioDispatcher) {
        if (
            patch.isEmpty ||
            expected.eventId <= 0L ||
            expected.calendarId <= 0L ||
            expected.recurring
        ) {
            return@withContext false
        }

        val values = ContentValues().apply {
            patch.title?.let { value -> put(CalendarContract.Events.TITLE, value) }
            patch.startEpochMillis?.let { value -> put(CalendarContract.Events.DTSTART, value) }
            patch.endEpochMillis?.let { value -> put(CalendarContract.Events.DTEND, value) }
            patch.location?.let { value -> put(CalendarContract.Events.EVENT_LOCATION, value) }
        }

        val selection = buildEventCompareAndSetSelection(expected)
        val updated = try {
            contentResolver.update(
                CalendarContract.Events.CONTENT_URI,
                values,
                selection.clause,
                selection.arguments.toTypedArray(),
            )
        } catch (failure: SecurityException) {
            throw CalendarAccessException("캘린더 쓰기 권한이 없습니다.", failure)
        } catch (failure: IllegalArgumentException) {
            throw CalendarAccessException("캘린더가 수정을 거부했습니다.", failure)
        }
        updated == 1
    }

    private fun buildEventCompareAndSetSelection(
        expected: CalendarEventMutationSnapshot,
    ): EventCompareAndSetSelection {
        val clauses = mutableListOf(
            "${CalendarContract.Events._ID} = ?",
            "${CalendarContract.Events.CALENDAR_ID} = ?",
            "${CalendarContract.Events.DELETED} = 0",
            "${CalendarContract.Events.DTSTART} = ?",
            "${CalendarContract.Events.DTEND} = ?",
            "${CalendarContract.Events.ALL_DAY} = ?",
            "(${CalendarContract.Events.RRULE} IS NULL OR ${CalendarContract.Events.RRULE} = '')",
            "(${CalendarContract.Events.RDATE} IS NULL OR ${CalendarContract.Events.RDATE} = '')",
        )
        val arguments = mutableListOf(
            expected.eventId.toString(),
            expected.calendarId.toString(),
            expected.startEpochMillis.toString(),
            expected.endEpochMillis.toString(),
            if (expected.allDay) "1" else "0",
        )
        if (expected.title.isEmpty()) {
            clauses += "(${CalendarContract.Events.TITLE} = ? OR ${CalendarContract.Events.TITLE} IS NULL)"
            arguments += ""
        } else {
            clauses += "${CalendarContract.Events.TITLE} = ?"
            arguments += expected.title
        }
        if (expected.location == null) {
            clauses += "${CalendarContract.Events.EVENT_LOCATION} IS NULL"
        } else {
            clauses += "${CalendarContract.Events.EVENT_LOCATION} = ?"
            arguments += expected.location
        }
        return EventCompareAndSetSelection(
            clause = clauses.joinToString(" AND "),
            arguments = arguments,
        )
    }

    private fun readCalendars(writableOnly: Boolean): List<CalendarAccount> {
        val selection = if (writableOnly) {
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        } else {
            null
        }
        val selectionArguments = if (writableOnly) {
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        } else {
            null
        }

        return guarded("캘린더 목록을 읽지 못했습니다.") {
            contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                CALENDAR_PROJECTION,
                selection,
                selectionArguments,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC, " +
                    "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )
        }.useRows(MAX_ROWS) { cursor ->
            CalendarAccount(
                id = cursor.getLong(CALENDAR_ID),
                displayName = cursor.getStringOrEmpty(CALENDAR_DISPLAY_NAME),
                accountName = cursor.getStringOrEmpty(CALENDAR_ACCOUNT_NAME),
                accountType = cursor.getStringOrEmpty(CALENDAR_ACCOUNT_TYPE),
                isPrimary = cursor.getInt(CALENDAR_IS_PRIMARY) != 0,
                timeZoneId = cursor.getStringOrNull(CALENDAR_TIME_ZONE),
            )
        }
    }

    private fun calendarLabels(): Map<Long, String> = readCalendars(writableOnly = false)
        .associate { calendar -> calendar.id to calendar.displayName }

    private inline fun guarded(message: String, query: () -> Cursor?): Cursor? = try {
        query()
    } catch (failure: SecurityException) {
        // Permission can be revoked between the interlock check and the query.
        throw CalendarAccessException(message, failure)
    } catch (failure: IllegalArgumentException) {
        throw CalendarAccessException(message, failure)
    }

    private inline fun <T> Cursor?.useRows(limit: Int, read: (Cursor) -> T): List<T> {
        if (this == null) return emptyList()
        return use { cursor ->
            buildList {
                while (size < limit && cursor.moveToNext()) {
                    add(read(cursor))
                }
            }
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)?.takeIf(String::isNotBlank)

    private fun Cursor.getRawStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.getStringOrEmpty(index: Int): String = getStringOrNull(index).orEmpty()

    private companion object {
        const val MAX_ROWS = 200

        val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_TIME_ZONE,
        )
        const val CALENDAR_ID = 0
        const val CALENDAR_DISPLAY_NAME = 1
        const val CALENDAR_ACCOUNT_NAME = 2
        const val CALENDAR_ACCOUNT_TYPE = 3
        const val CALENDAR_IS_PRIMARY = 4
        const val CALENDAR_TIME_ZONE = 5

        val INSTANCE_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        const val INSTANCE_EVENT_ID = 0
        const val INSTANCE_CALENDAR_ID = 1
        const val INSTANCE_TITLE = 2
        const val INSTANCE_BEGIN = 3
        const val INSTANCE_END = 4
        const val INSTANCE_ALL_DAY = 5
        const val INSTANCE_LOCATION = 6

        val EVENT_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.RDATE,
        )
        const val EVENT_ID = 0
        const val EVENT_CALENDAR_ID = 1
        const val EVENT_TITLE = 2
        const val EVENT_DTSTART = 3
        const val EVENT_DTEND = 4
        const val EVENT_ALL_DAY = 5
        const val EVENT_LOCATION = 6
        const val EVENT_RRULE = 7
        const val EVENT_RDATE = 8
    }
}

private data class EventCompareAndSetSelection(
    val clause: String,
    val arguments: List<String>,
)
