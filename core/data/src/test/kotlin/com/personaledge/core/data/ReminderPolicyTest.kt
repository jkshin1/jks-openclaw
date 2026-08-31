package com.personaledge.core.data

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReminderPolicyTest {
    @Test
    fun `one shot recurrence is valid and remains null`() {
        val result = ReminderRecurrencePolicy.validate(null)

        assertEquals(ReminderRecurrenceValidation.Valid(null), result)
        assertEquals(ReminderRecurrenceValidation.Valid(null), ReminderRecurrencePolicy.validate("none"))
    }

    @Test
    fun `weekly recurrence is deterministically ordered`() {
        assertEquals(
            ReminderRecurrenceValidation.Valid("weekly:mon,wed,sun"),
            ReminderRecurrencePolicy.validate("weekly:sun,mon,wed"),
        )
    }

    @Test
    fun `invalid recurrence fails closed`() {
        assertSame(ReminderRecurrenceValidation.Invalid, ReminderRecurrencePolicy.validate("monthly:1"))
        assertSame(ReminderRecurrenceValidation.Invalid, ReminderRecurrencePolicy.validate("weekly:mon,mon"))
    }

    @Test
    fun `overdue recurrence advances directly to the first future occurrence`() {
        val zone = ZoneId.of("Asia/Seoul")
        val original = at("2026-08-20T07:00", zone)
        val now = at("2026-08-25T08:00", zone)

        assertEquals(
            at("2026-08-26T07:00", zone),
            ReminderRecurrencePolicy.nextFutureTrigger(original, zone.id, "daily", now),
        )
    }

    @Test
    fun `daily recurrence skips DST gap and overlap wall times`() {
        val zone = ZoneId.of("America/New_York")

        assertEquals(
            at("2026-03-09T02:30", zone),
            ReminderRecurrencePolicy.nextFutureTrigger(
                at("2026-03-07T02:30", zone),
                zone.id,
                "daily",
                at("2026-03-07T03:00", zone),
            ),
        )
        assertEquals(
            at("2026-11-02T01:30", zone),
            ReminderRecurrencePolicy.nextFutureTrigger(
                at("2026-10-31T01:30", zone),
                zone.id,
                "daily",
                at("2026-10-31T02:00", zone),
            ),
        )
    }

    @Test
    fun `title policy rejects invisible controls and model delimiters`() {
        assertEquals("병원 예약", ReminderTextPolicy.sanitizeTitle("  병원   예약 "))
        assertNull(ReminderTextPolicy.sanitizeTitle("확인\u202E숨김"))
        assertNull(ReminderTextPolicy.sanitizeTitle("<|tool|>"))
    }

    private fun at(local: String, zone: ZoneId): Long {
        val value = LocalDateTime.parse(local)
        val offsets = zone.rules.getValidOffsets(value)
        require(offsets.size == 1)
        return value.toInstant(offsets.single()).toEpochMilli()
    }
}
