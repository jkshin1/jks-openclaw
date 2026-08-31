package com.personaledge.agent

import com.personaledge.agent.ui.directRecurrenceRule
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderPresentationPolicyTest {
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun `daily brief time accepts only strict local HH mm`() {
        assertEquals(455, DailyBriefTimePolicy.parse("07:35"))
        assertEquals("07:35", DailyBriefTimePolicy.format(455))
        assertNull(DailyBriefTimePolicy.parse("7:35"))
        assertNull(DailyBriefTimePolicy.parse("24:00"))
        assertNull(DailyBriefTimePolicy.parse("09:60"))
    }

    @Test
    fun `undated inbox offers today evening and tomorrow morning before evening`() {
        val now = ZonedDateTime.of(2026, 8, 25, 10, 0, 0, 0, zone)
        val options = InboxClarificationPolicy.options(now.toInstant().toEpochMilli(), zone)

        assertEquals("2026-08-25T19:00", options.todayEveningTriggerAt)
        assertEquals("2026-08-26T09:00", options.tomorrowMorningTriggerAt)
    }

    @Test
    fun `past today evening is omitted but tomorrow remains`() {
        val now = ZonedDateTime.of(2026, 8, 25, 20, 0, 0, 0, zone)
        val options = InboxClarificationPolicy.options(now.toInstant().toEpochMilli(), zone)

        assertNull(options.todayEveningTriggerAt)
        assertEquals("2026-08-26T09:00", options.tomorrowMorningTriggerAt)
    }

    @Test
    fun `daily work projection targets the configured next local wall clock`() {
        val now = ZonedDateTime.of(2026, 8, 25, 7, 30, 0, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 8, 25, 8, 45, 0, 0, zone),
            ReminderWorkBootstrap.nextDailyBriefAt(now, 8 * 60 + 45),
        )
        assertEquals(
            ZonedDateTime.of(2026, 8, 26, 7, 0, 0, 0, zone),
            ReminderWorkBootstrap.nextDailyBriefAt(now, 7 * 60),
        )
    }

    @Test
    fun `direct recurrence choices map to the closed repository grammar`() {
        assertNull(directRecurrenceRule("none", "2026-08-25T09:00"))
        assertEquals("daily", directRecurrenceRule("daily", "2026-08-25T09:00"))
        assertEquals(
            "weekly:mon,tue,wed,thu,fri",
            directRecurrenceRule("weekdays", "2026-08-25T09:00"),
        )
        assertEquals("weekly:tue", directRecurrenceRule("weekly", "2026-08-25T09:00"))
        assertNull(directRecurrenceRule("weekly", "not-a-date"))
    }
}
