package com.personaledge.agent

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import com.personaledge.core.data.ReminderSourceType

class LeaveByPlannerTest {
    @Test
    fun `reminder sources have closed truthful labels`() {
        assertEquals("직접 생성", ReminderSourcePresentation.label(ReminderSourceType.DIRECT))
        assertEquals(
            "잊지 말 것 Inbox",
            ReminderSourcePresentation.label(ReminderSourceType.INBOX),
        )
        assertEquals(
            "캘린더 출발 알림",
            ReminderSourcePresentation.label(ReminderSourceType.CALENDAR),
        )
    }

    @Test
    fun `travel and preparation buffers are subtracted deterministically`() {
        assertEquals(
            10_000_000L - 45L * 60_000,
            LeaveByPlanner.recommendedDeparture(
                eventStartEpochMillis = 10_000_000L,
                travelMinutes = 30,
            ),
        )
    }

    @Test
    fun `unbounded provider duration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            LeaveByPlanner.recommendedDeparture(10_000_000L, travelMinutes = 2_000)
        }
    }

    @Test
    fun `route failure uses a fixed buffer without inventing a traffic estimate`() {
        assertEquals(
            10_000_000L - LeaveByPlanner.FALLBACK_TOTAL_BUFFER_MINUTES * 60_000L,
            LeaveByPlanner.fallbackDeparture(10_000_000L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            LeaveByPlanner.fallbackDeparture(10_000_000L, totalBufferMinutes = 0)
        }
    }

    @Test
    fun `quiet hours move flexible work to seven without changing exact policy inputs`() {
        val zone = ZoneId.of("Asia/Seoul")
        val at = { value: String ->
            LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
        }

        assertEquals(
            at("2026-08-24T07:00"),
            QuietHoursPolicy.adjustIfNeeded(at("2026-08-23T23:30"), zone, enabled = true),
        )
        assertEquals(
            at("2026-08-23T21:30"),
            QuietHoursPolicy.adjustIfNeeded(at("2026-08-23T21:30"), zone, enabled = true),
        )
    }

    @Test
    fun `evening notifications can snooze to the next local morning`() {
        val zone = ZoneId.of("Asia/Seoul")
        val at = { value: String ->
            LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
        }

        assertEquals(false, ReminderSnoozePolicy.shouldOfferTomorrowMorning(at("2026-08-23T17:59"), zone))
        assertEquals(true, ReminderSnoozePolicy.shouldOfferTomorrowMorning(at("2026-08-23T18:00"), zone))
        assertEquals(
            at("2026-08-24T09:00"),
            ReminderSnoozePolicy.tomorrowMorning(at("2026-08-23T23:30"), zone),
        )
    }

    @Test
    fun `leave by projection keeps calendar identity stable and versions traffic changes`() {
        val plan = LeaveByPlan(
            calendarId = 6,
            eventId = 700,
            eventTitle = "병원 예약",
            eventStartEpochMillis = 10_000_000L,
            leaveAtEpochMillis = 8_020_000L,
            zoneId = "Asia/Seoul",
            usedFallback = false,
        )
        val first = requireNotNull(LeaveByReminderProjection.forPlan(plan, 1_000_000L)).draft
        val changedTraffic = requireNotNull(
            LeaveByReminderProjection.forPlan(
                plan.copy(leaveAtEpochMillis = 7_540_000L),
                1_000_000L,
            ),
        ).draft

        assertEquals(first.sourceRefHash, changedTraffic.sourceRefHash)
        assertNotEquals(first.confirmationDigest, changedTraffic.confirmationDigest)
        assertEquals(10_000_000L, first.triggerAtEpochMillis)
        assertEquals(33, first.leadTimeMinutes)
        assertEquals(41, changedTraffic.leadTimeMinutes)
        assertEquals("출발 준비 · 병원 예약", first.title)
        assertEquals(com.personaledge.core.data.ReminderSourceType.CALENDAR, first.sourceType)
        assertEquals(com.personaledge.core.data.ReminderCreator.SYSTEM, first.createdBy)
    }

    @Test
    fun `leave by projection refuses a departure that is no longer future`() {
        val plan = LeaveByPlan(
            calendarId = 6,
            eventId = 700,
            eventTitle = "병원 예약",
            eventStartEpochMillis = 10_000_000L,
            leaveAtEpochMillis = 8_020_000L,
            zoneId = "Asia/Seoul",
            usedFallback = true,
        )

        assertNotNull(LeaveByReminderProjection.forPlan(plan, 8_019_999L))
        assertNull(LeaveByReminderProjection.forPlan(plan, 8_020_000L))
    }

    @Test
    fun `leave by projection rejects an unsafe calendar title`() {
        val plan = LeaveByPlan(
            calendarId = 6,
            eventId = 700,
            eventTitle = "회의\u0000숨김",
            eventStartEpochMillis = 10_000_000L,
            leaveAtEpochMillis = 8_020_000L,
            zoneId = "Asia/Seoul",
            usedFallback = false,
        )

        assertNull(LeaveByReminderProjection.forPlan(plan, 1_000_000L))
    }

    @Test
    fun `snooze is an absolute delivery time and does not subtract lead again`() {
        assertEquals(
            8_200_000L,
            ReminderDeliveryTimePolicy.requestedAt(
                triggerAtEpochMillis = 10_000_000L,
                snoozeUntilEpochMillis = null,
                leadTimeMinutes = 30,
            ),
        )
        assertEquals(
            12_000_000L,
            ReminderDeliveryTimePolicy.requestedAt(
                triggerAtEpochMillis = 10_000_000L,
                snoozeUntilEpochMillis = 12_000_000L,
                leadTimeMinutes = 30,
            ),
        )
    }
}
