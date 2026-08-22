package com.personaledge.core.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real provider, not a fake.
 *
 * The column semantics of `CalendarContract` — DTSTART/DTEND on Events versus BEGIN/END on
 * Instances, the DELETED tombstone, the sync-adapter-only calendar columns — are the part a
 * unit test cannot check, so the test creates its own local calendar and removes it afterwards.
 */
@RunWith(AndroidJUnit4::class)
class AndroidCalendarGatewayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val zone: ZoneId = ZoneId.systemDefault()

    private lateinit var gateway: AndroidCalendarGateway
    private var calendarId: Long = 0
    private val createdCalendarIds = mutableListOf<Long>()

    private fun at(local: String): Long =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun syncAdapterUri(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.ACCOUNT_TYPE_LOCAL,
        )
        .build()

    @Before
    fun createLocalCalendar() {
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            .forEach { permission ->
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            }

        calendarId = createLocalCalendar(CALENDAR_NAME)
        gateway = AndroidCalendarGateway(context)
    }

    private fun createLocalCalendar(displayName: String): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0x2E7D32)
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER,
            )
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, zone.id)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
        }
        val uri = context.contentResolver
            .insert(syncAdapterUri(CalendarContract.Calendars.CONTENT_URI), values)
        return requireNotNull(uri?.let(ContentUris::parseId)).also(createdCalendarIds::add)
    }

    @After
    fun removeLocalCalendar() {
        createdCalendarIds.asReversed().forEach { createdId ->
            context.contentResolver.delete(
                ContentUris.withAppendedId(
                    syncAdapterUri(CalendarContract.Calendars.CONTENT_URI),
                    createdId,
                ),
                null,
                null,
            )
        }
        createdCalendarIds.clear()
    }

    @Test
    fun theOwnedCalendarIsReportedAsWritable() = runBlocking {
        val calendar = gateway.writableCalendars().firstOrNull { it.id == calendarId }

        assertNotNull(calendar)
        assertEquals(CALENDAR_NAME, calendar!!.displayName)
        assertEquals(ACCOUNT_NAME, calendar.accountName)
        assertEquals(CalendarContract.ACCOUNT_TYPE_LOCAL, calendar.accountType)
        assertEquals(zone.id, calendar.timeZoneId)
    }

    @Test
    fun anInsertedEventIsFoundByIdAndByWindow() = runBlocking {
        val eventId = gateway.insertEvent(
            CalendarEventDraft(
                calendarId = calendarId,
                title = "치과 예약",
                startEpochMillis = at("2026-08-22T14:00"),
                endEpochMillis = at("2026-08-22T15:00"),
                location = "강남 치과",
                timeZoneId = zone.id,
            ),
        )
        assertNotNull(eventId)

        val found = gateway.findEvent(eventId!!)
        assertNotNull(found)
        assertEquals("치과 예약", found!!.title)
        assertEquals("강남 치과", found.location)
        assertEquals(at("2026-08-22T14:00"), found.startEpochMillis)
        assertEquals(at("2026-08-22T15:00"), found.endEpochMillis)
        assertFalse(found.allDay)
        assertFalse(found.recurring)
        assertEquals(CALENDAR_NAME, found.calendarLabel)

        val inWindow = gateway.queryEvents(
            calendarId = calendarId,
            startEpochMillis = at("2026-08-22T00:00"),
            endEpochMillis = at("2026-08-23T00:00"),
            limit = 10,
        )
        assertTrue(inWindow.any { event -> event.eventId == eventId })
    }

    @Test
    fun anRdateOnlySeriesIsReportedAsRecurring() = runBlocking {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, "명시 날짜 반복 일정")
            put(CalendarContract.Events.DTSTART, at("2026-08-22T14:00"))
            // CalendarContract series masters use DURATION instead of DTEND.
            put(CalendarContract.Events.DURATION, "P3600S")
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            put(
                CalendarContract.Events.RDATE,
                "20260822T050000Z,20260829T050000Z",
            )
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        val eventId = requireNotNull(uri?.let(ContentUris::parseId))

        val found = gateway.findEvent(eventId)

        assertNotNull(found)
        assertTrue(found!!.recurring)
    }

    @Test
    fun anEventOutsideTheWindowIsNotReturned() = runBlocking {
        val eventId = gateway.insertEvent(
            CalendarEventDraft(
                calendarId = calendarId,
                title = "다음 달 회의",
                startEpochMillis = at("2026-09-30T10:00"),
                endEpochMillis = at("2026-09-30T11:00"),
                location = null,
                timeZoneId = zone.id,
            ),
        )

        val inWindow = gateway.queryEvents(
            calendarId = calendarId,
            startEpochMillis = at("2026-08-22T00:00"),
            endEpochMillis = at("2026-08-23T00:00"),
            limit = 10,
        )

        assertFalse(inWindow.any { event -> event.eventId == eventId })
    }

    @Test
    fun queryScopesCalendarIdBeforeApplyingTheLimit() = runBlocking {
        val otherCalendarId = createLocalCalendar("Personal Edge Other")
        repeat(3) { index ->
            gateway.insertEvent(
                CalendarEventDraft(
                    calendarId = otherCalendarId,
                    title = "다른 캘린더 $index",
                    startEpochMillis = at("2026-08-22T08:0$index"),
                    endEpochMillis = at("2026-08-22T08:3$index"),
                    location = null,
                    timeZoneId = zone.id,
                ),
            )
        }
        val targetEventId = requireNotNull(
            gateway.insertEvent(
                CalendarEventDraft(
                    calendarId = calendarId,
                    title = "선택 캘린더 일정",
                    startEpochMillis = at("2026-08-22T14:00"),
                    endEpochMillis = at("2026-08-22T15:00"),
                    location = null,
                    timeZoneId = zone.id,
                ),
            ),
        )

        val inWindow = gateway.queryEvents(
            calendarId = calendarId,
            startEpochMillis = at("2026-08-22T00:00"),
            endEpochMillis = at("2026-08-23T00:00"),
            limit = 1,
        )

        assertEquals(listOf(targetEventId), inWindow.map(CalendarEvent::eventId))
    }

    @Test
    fun updatingChangesOnlyTheRequestedColumns() = runBlocking {
        val eventId = gateway.insertEvent(
            CalendarEventDraft(
                calendarId = calendarId,
                title = "치과 예약",
                startEpochMillis = at("2026-08-22T14:00"),
                endEpochMillis = at("2026-08-22T15:00"),
                location = "강남 치과",
                timeZoneId = zone.id,
            ),
        )!!

        val updated = gateway.updateEvent(
            expectedCalendarId = calendarId,
            eventId = eventId,
            patch = CalendarEventPatch(
                startEpochMillis = at("2026-08-22T16:00"),
                endEpochMillis = at("2026-08-22T17:00"),
            ),
        )

        assertTrue(updated)
        val found = gateway.findEvent(eventId)!!
        assertEquals("치과 예약", found.title)
        assertEquals("강남 치과", found.location)
        assertEquals(at("2026-08-22T16:00"), found.startEpochMillis)
    }

    @Test
    fun anUnknownEventIdReadsAsNullAndCannotBeUpdated() = runBlocking {
        assertNull(gateway.findEvent(Long.MAX_VALUE))
        assertFalse(
            gateway.updateEvent(
                expectedCalendarId = calendarId,
                eventId = Long.MAX_VALUE,
                patch = CalendarEventPatch(title = "없음"),
            ),
        )
    }

    @Test
    fun updateRefusesAnEventMovedOutOfTheExpectedCalendar() = runBlocking {
        val otherCalendarId = createLocalCalendar("Personal Edge Moved")
        val eventId = requireNotNull(
            gateway.insertEvent(
                CalendarEventDraft(
                    calendarId = calendarId,
                    title = "이동 전 제목",
                    startEpochMillis = at("2026-08-22T14:00"),
                    endEpochMillis = at("2026-08-22T15:00"),
                    location = null,
                    timeZoneId = zone.id,
                ),
            ),
        )
        val moved = context.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, otherCalendarId)
            },
            null,
            null,
        )
        assertEquals(1, moved)

        val updated = gateway.updateEvent(
            expectedCalendarId = calendarId,
            eventId = eventId,
            patch = CalendarEventPatch(title = "범위 밖 수정"),
        )

        assertFalse(updated)
        val found = requireNotNull(gateway.findEvent(eventId))
        assertEquals(otherCalendarId, found.calendarId)
        assertEquals("이동 전 제목", found.title)
    }

    @Test
    fun theToolsSeeOnlyThePinnedCalendar() = runBlocking {
        val eventId = gateway.insertEvent(
            CalendarEventDraft(
                calendarId = calendarId,
                title = "네이버 일정",
                startEpochMillis = at("2026-08-22T14:00"),
                endEpochMillis = at("2026-08-22T15:00"),
                location = null,
                timeZoneId = zone.id,
            ),
        )!!
        // Pinning a different calendar must hide an event that plainly exists.
        val elsewhere = ScopedCalendarGateway(gateway) { calendarId + 1_000 }
        val pinned = ScopedCalendarGateway(gateway) { calendarId }

        assertNull(elsewhere.findEvent(eventId))
        assertTrue(elsewhere.writableCalendars().isEmpty())
        assertNotNull(pinned.findEvent(eventId))
        assertEquals(listOf(calendarId), pinned.writableCalendars().map(CalendarAccount::id))
    }

    private companion object {
        const val ACCOUNT_NAME = "personal-edge-test@local"
        const val CALENDAR_NAME = "Personal Edge Test"
    }
}
