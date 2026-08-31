package com.personaledge.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test
    fun postingRequiresBothRuntimePermissionAndEnabledAppNotifications() {
        assertTrue(
            NotificationPermissionPolicy.canPost(
                runtimePermissionGranted = true,
                notificationsEnabled = true,
            ),
        )
        assertFalse(
            NotificationPermissionPolicy.canPost(
                runtimePermissionGranted = false,
                notificationsEnabled = true,
            ),
        )
        assertFalse(
            NotificationPermissionPolicy.canPost(
                runtimePermissionGranted = true,
                notificationsEnabled = false,
            ),
        )
    }
}
