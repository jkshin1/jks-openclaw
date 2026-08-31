package com.personaledge.agent

import android.Manifest
import android.app.Application
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.tools.CalendarEventDraft
import com.personaledge.core.tools.CalendarEventPatch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bounded live round trip against the owner-selected Samsung Account calendar.
 *
 * The test creates one uniquely named event, proves scoped query and update, and removes only the
 * exact id/title/calendar tuple it created. It refuses to delete if any of those ownership checks
 * no longer match. Never include this class in an unattended physical-device suite.
 */
@RunWith(AndroidJUnit4::class)
class SamsungCalendarLiveAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun selectedSamsungCalendarCompletesCreateQueryUpdateAndOwnedCleanup() = runBlocking {
        assertTrue(
            "Set -e liveSamsungCalendarRoundTrip true only for an owner-approved live round trip.",
            InstrumentationRegistry.getArguments()
                .getString("liveSamsungCalendarRoundTrip") == "true",
        )
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }

        val container = (context.applicationContext as Application).appContainer()
        val pinnedId = container.settings.current().defaultCalendarId
        assertNotNull("A calendar must be pinned before live acceptance.", pinnedId)
        val selected = container.deviceCalendars.writableCalendars()
            .singleOrNull { calendar -> calendar.id == pinnedId }
        assertNotNull("The pinned calendar is not writable.", selected)
        assertEquals(SAMSUNG_ACCOUNT_TYPE, selected!!.accountType)

        val zone = ZoneId.systemDefault()
        val start = Instant.now()
            .plus(2, ChronoUnit.DAYS)
            .truncatedTo(ChronoUnit.MINUTES)
            .toEpochMilli()
        val end = start + 30 * 60_000L
        val suffix = UUID.randomUUID().toString().take(8)
        val initialTitle = "$OWNED_TITLE_PREFIX$suffix"
        val updatedTitle = "$initialTitle 수정"
        var createdId: Long? = null

        try {
            createdId = container.scopedCalendar.insertEvent(
                CalendarEventDraft(
                    calendarId = pinnedId!!,
                    title = initialTitle,
                    startEpochMillis = start,
                    endEpochMillis = end,
                    location = "자동 검증",
                    timeZoneId = zone.id,
                ),
            )
            assertNotNull("Samsung Calendar refused the test insert.", createdId)
            val insertedId = requireNotNull(createdId)

            val queried = container.scopedCalendar.queryEvents(
                startEpochMillis = start - 60_000L,
                endEpochMillis = end + 60_000L,
                limit = 20,
            )
            assertEquals(1, queried.count { event -> event.eventId == insertedId })
            assertEquals(initialTitle, queried.single { event -> event.eventId == insertedId }.title)

            assertTrue(
                container.scopedCalendar.updateEvent(
                    eventId = insertedId,
                    patch = CalendarEventPatch(title = updatedTitle),
                ),
            )
            val updated = container.scopedCalendar.findEvent(insertedId)
            assertNotNull(updated)
            assertEquals(updatedTitle, updated!!.title)

            println("privacy_samsung_calendar_create=true")
            println("privacy_samsung_calendar_query_match_count=1")
            println("privacy_samsung_calendar_update=true")
        } finally {
            val eventId = createdId
            if (eventId != null) {
                val owned = runCatching { container.deviceCalendars.findEvent(eventId) }
                    .getOrNull()
                    ?.takeIf { event ->
                        event.calendarId == pinnedId &&
                            event.title in setOf(initialTitle, updatedTitle)
                    }
                if (owned != null) {
                    val deleted = context.contentResolver.delete(
                        CalendarContract.Events.CONTENT_URI,
                        "${CalendarContract.Events._ID} = ? AND " +
                            "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
                            "${CalendarContract.Events.TITLE} = ?",
                        arrayOf(eventId.toString(), pinnedId.toString(), owned.title),
                    )
                    assertEquals("The owned test event was not cleaned up.", 1, deleted)
                }
                assertNull(
                    "The owned test event still exists after cleanup.",
                    container.deviceCalendars.findEvent(eventId),
                )
                println("privacy_samsung_calendar_owned_cleanup=true")
            }
        }
    }

    /** Recovery for a previous acceptance process that stopped after its owned insert. */
    @Test
    fun cleanupOneExplicitlyIdentifiedOwnedAcceptanceEvent() = runBlocking {
        val eventId = InstrumentationRegistry.getArguments()
            .getString("ownedSamsungCalendarEventId")
            ?.toLongOrNull()
        assertNotNull("Pass the exact owned event id.", eventId)

        val container = (context.applicationContext as Application).appContainer()
        val pinnedId = container.settings.current().defaultCalendarId
        assertNotNull("The selected calendar is missing.", pinnedId)
        val event = container.deviceCalendars.findEvent(eventId!!)
        assertNotNull("The explicit event no longer exists.", event)
        assertEquals(pinnedId, event!!.calendarId)
        assertTrue(
            "Refusing to delete an event this acceptance harness did not create.",
            event.title.startsWith(OWNED_TITLE_PREFIX),
        )

        val deleted = context.contentResolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events._ID} = ? AND " +
                "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
                "${CalendarContract.Events.TITLE} = ?",
            arrayOf(eventId.toString(), pinnedId.toString(), event.title),
        )
        assertEquals(1, deleted)
        assertNull(container.deviceCalendars.findEvent(eventId))
        println("privacy_samsung_calendar_owned_recovery_cleanup=true")
    }

    private companion object {
        const val SAMSUNG_ACCOUNT_TYPE = "com.osp.app.signin"
        const val OWNED_TITLE_PREFIX = "Personal Edge 검증 "
    }
}
