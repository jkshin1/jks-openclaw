package com.personaledge.agent

import android.app.NotificationManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.ReminderCreateResult
import com.personaledge.core.data.ReminderCreator
import com.personaledge.core.data.ReminderDeliveryOutcome
import com.personaledge.core.data.ReminderDraft
import com.personaledge.core.data.ReminderPrecision
import com.personaledge.core.data.ReminderScheduleState
import com.personaledge.core.data.ReminderSourceType
import com.personaledge.core.data.ReminderState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, exact-ID Fold8 receipts for the production reminder database and notification boundary.
 *
 * Every row is synthetic, its generated id is captured before any assertion, and cleanup deletes
 * only that primary key (with delivery rows cascading). No owner title, reminder, or notification
 * content is queried or printed.
 */
@RunWith(AndroidJUnit4::class)
class Fold8ReminderAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication
    private val container = application.container

    @Test
    fun notificationDeniedStillStoresAVisibleBlockedReminder() = runBlocking {
        requireOptIn()
        assumeFalse(
            "This receipt requires notification posting to be unavailable.",
            NotificationPermissionPolicy.canPost(application),
        )
        cleanupStaleSyntheticFixtures()

        var reminderId: String? = null
        try {
            val created = createSyntheticReminder(ReminderPrecision.FLEXIBLE)
            reminderId = created.reminder.id
            container.reminderScheduler.schedule(created.reminder)

            val stored = container.reminders.find(created.reminder.id)
            assertNotNull(stored)
            assertEquals(
                ReminderScheduleState.BLOCKED_NOTIFICATION_PERMISSION,
                stored?.scheduleState,
            )
            assertTrue(container.reminders.deliveries(created.reminder.id).isEmpty())
            assertFalse(isNotificationActive(created.reminder.id))

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("reminder_permission_denied_storage", "blocked_and_retained")
                    putString("reminder_permission_denied_notification", "not_posted")
                },
            )
        } finally {
            reminderId?.let { cleanupExactFixture(it) }
        }
    }

    @Test
    fun notificationAllowedPostsAndRefusesAStaleActionVersion() = runBlocking {
        requireOptIn()
        assumeTrue(
            "Grant notification permission through the production UI before this receipt.",
            NotificationPermissionPolicy.canPost(application),
        )
        cleanupStaleSyntheticFixtures()

        var reminderId: String? = null
        try {
            val created = createSyntheticReminder(ReminderPrecision.FLEXIBLE)
            reminderId = created.reminder.id
            container.reminderScheduler.schedule(created.reminder)
            val scheduled = requireNotNull(container.reminders.find(created.reminder.id))
            assertTrue(
                scheduled.scheduleState in setOf(
                    ReminderScheduleState.SCHEDULED_INEXACT,
                    ReminderScheduleState.DEGRADED_TO_INEXACT,
                ),
            )

            ReminderDeliveryCoordinator(
                application,
                container.reminders,
                container.reminderScheduler,
            ).deliver(scheduled.id, scheduled.scheduleVersion)
            val delivered = requireNotNull(container.reminders.find(scheduled.id))
            assertEquals(ReminderScheduleState.DELIVERED, delivered.scheduleState)
            assertTrue(isNotificationActive(delivered.id))
            assertTrue(
                container.reminders.deliveries(delivered.id)
                    .any { it.outcome == ReminderDeliveryOutcome.POSTED },
            )

            val snoozed = requireNotNull(
                container.reminders.snooze(
                    delivered.id,
                    System.currentTimeMillis() + 10L * 60L * 1_000L,
                    delivered.scheduleVersion,
                ),
            )
            container.reminderScheduler.schedule(snoozed)
            val actions = ReminderNotificationActionCoordinator(
                application,
                container.reminders,
                container.reminderScheduler,
            )
            assertEquals(
                ReminderNotificationActionOutcome.STALE_REFUSED,
                actions.handle(
                    action = ReminderActionReceiver.ACTION_COMPLETE,
                    reminderId = snoozed.id,
                    scheduleVersion = delivered.scheduleVersion,
                ),
            )
            assertEquals(snoozed.scheduleVersion, container.reminders.find(snoozed.id)?.scheduleVersion)
            assertTrue(
                container.reminders.deliveries(snoozed.id).any { delivery ->
                    delivery.outcome == ReminderDeliveryOutcome.STALE_ACTION_REFUSED &&
                        delivery.scheduleVersion == delivered.scheduleVersion
                },
            )
            assertEquals(
                ReminderNotificationActionOutcome.COMPLETED,
                actions.handle(
                    action = ReminderActionReceiver.ACTION_COMPLETE,
                    reminderId = snoozed.id,
                    scheduleVersion = snoozed.scheduleVersion,
                ),
            )
            assertEquals(ReminderState.COMPLETED, container.reminders.find(snoozed.id)?.state)

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("reminder_notification_allowed", "posted")
                    putString("reminder_stale_action", "refused_and_recorded")
                    putString("reminder_current_action", "completed")
                },
            )
        } finally {
            reminderId?.let { cleanupExactFixture(it) }
        }
    }

    private suspend fun createSyntheticReminder(
        precision: ReminderPrecision,
    ): ReminderCreateResult.Created {
        val result = container.reminders.create(
            ReminderDraft(
                title = SYNTHETIC_TITLE,
                triggerAtEpochMillis = System.currentTimeMillis() + 10L * 60L * 1_000L,
                zoneId = java.time.ZoneId.systemDefault().id,
                precision = precision,
                sourceType = ReminderSourceType.DIRECT,
                createdBy = ReminderCreator.USER,
                confirmationDigest = SYNTHETIC_CONFIRMATION_DIGEST,
            ),
        )
        assertTrue(result is ReminderCreateResult.Created)
        return result as ReminderCreateResult.Created
    }

    private suspend fun cleanupExactFixture(reminderId: String) {
        container.reminderScheduler.cancel(reminderId)
        application.getSystemService(NotificationManager::class.java)?.let { manager ->
            manager.cancel(reminderId, ReminderDeliveryCoordinator.TAGGED_NOTIFICATION_ID)
        }
        container.database.openHelper.writableDatabase.execSQL(
            "DELETE FROM reminders WHERE id = ?",
            arrayOf<Any>(reminderId),
        )
        assertNull(container.reminders.find(reminderId))
    }

    private suspend fun cleanupStaleSyntheticFixtures() {
        val staleIds = mutableListOf<String>()
        container.database.openHelper.writableDatabase.query(
            """
            SELECT id FROM reminders
            WHERE title = ?
              AND confirmation_digest = ?
              AND source_type = ?
              AND created_by = ?
            """.trimIndent(),
            arrayOf(
                SYNTHETIC_TITLE,
                SYNTHETIC_CONFIRMATION_DIGEST,
                ReminderSourceType.DIRECT.name,
                ReminderCreator.USER.name,
            ),
        ).use { cursor ->
            while (cursor.moveToNext()) staleIds += cursor.getString(0)
        }
        for (staleId in staleIds) cleanupExactFixture(staleId)
        if (staleIds.isNotEmpty()) {
            instrumentation.sendStatus(
                0,
                Bundle().apply { putInt("stale_synthetic_reminders_recovered", staleIds.size) },
            )
        }
    }

    private fun isNotificationActive(reminderId: String): Boolean {
        return application.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.any { notification ->
                notification.tag == reminderId &&
                    notification.id == ReminderDeliveryCoordinator.TAGGED_NOTIFICATION_ID
            }
            ?: false
    }

    private fun requireOptIn() {
        assumeTrue(
            "Fold8 reminder acceptance requires -e liveReminder true.",
            InstrumentationRegistry.getArguments().getString(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("This acceptance is restricted to the owner's Fold8.", isTargetFold8())
    }

    private fun isTargetFold8(): Boolean =
        android.os.Build.MODEL == TARGET_FOLD8_MODEL &&
            !android.os.Build.FINGERPRINT.startsWith(GENERIC_PREFIX)

    private companion object {
        const val LIVE_ARGUMENT = "liveReminder"
        const val TARGET_FOLD8_MODEL = "SM-F971N"
        const val GENERIC_PREFIX = "generic"
        const val SYNTHETIC_TITLE = "Personal Edge synthetic reminder acceptance"
        const val SYNTHETIC_CONFIRMATION_DIGEST =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    }
}
