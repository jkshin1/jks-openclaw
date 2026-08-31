package com.personaledge.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderNotificationAddressPolicyTest {
    @Test
    fun `tagged addresses stay distinct even when legacy ids collide`() {
        val policy = ReminderNotificationAddressPolicy(legacyIdFactory = { 7 })

        val first = policy.current("reminder-a")
        val second = policy.current("reminder-b")

        assertEquals(7, policy.legacyId("reminder-a"))
        assertEquals(7, policy.legacyId("reminder-b"))
        assertEquals(0, first.id)
        assertEquals(0, second.id)
        assertNotEquals(first, second)
        assertEquals("reminder-a", first.tag)
        assertEquals("reminder-b", second.tag)
    }

    @Test
    fun `upgrade cleanup selects only untagged reminder channel notifications`() {
        assertTrue(
            ReminderNotifications.isLegacyReminderNotification(
                tag = null,
                channelId = ReminderNotifications.CHANNEL_ID,
            ),
        )
        assertFalse(
            ReminderNotifications.isLegacyReminderNotification(
                tag = "reminder-a",
                channelId = ReminderNotifications.CHANNEL_ID,
            ),
        )
        assertFalse(
            ReminderNotifications.isLegacyReminderNotification(
                tag = null,
                channelId = "another-channel",
            ),
        )
    }
}
