package com.personaledge.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderRepositoryTest {
    private lateinit var database: PersonalEdgeDatabase
    private val zone = ZoneId.of("Asia/Seoul")
    private var now = at("2026-08-23T09:00")
    private val ids = AtomicInteger()
    private lateinit var repository: ReminderRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            PersonalEdgeDatabase::class.java,
        ).build()
        repository = ReminderRepository(
            database = database,
            clock = { now },
            idFactory = { "generated-${ids.incrementAndGet()}" },
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun oneShotIsValidAndBecomesDeliveredWithoutDuplicating() = runBlocking {
        val created = repository.create(draft("2026-08-24T07:00")) as ReminderCreateResult.Created

        val delivered = repository.afterNotificationPosted(
            created.reminder.id,
            created.reminder.scheduleVersion,
        )

        assertEquals(ReminderScheduleState.DELIVERED, delivered?.scheduleState)
        assertEquals(1, repository.deliveries(created.reminder.id).size)
        assertNull(
            repository.afterNotificationPosted(
                created.reminder.id,
                created.reminder.scheduleVersion + 1,
            ),
        )
        assertEquals(1, repository.deliveries(created.reminder.id).size)
    }

    @Test
    fun dailyDeliveryAdvancesLocalWallTimeAndInvalidatesStaleActions() = runBlocking {
        val created = repository.create(
            draft("2026-08-24T07:00", recurrence = "daily"),
        ) as ReminderCreateResult.Created

        val advanced = repository.afterNotificationPosted(
            created.reminder.id,
            created.reminder.scheduleVersion,
        )!!

        assertEquals(at("2026-08-25T07:00"), advanced.triggerAtEpochMillis)
        assertEquals(2, advanced.scheduleVersion)
        assertEquals(ReminderScheduleState.PENDING, advanced.scheduleState)
        assertNull(repository.complete(advanced.id, expectedScheduleVersion = 1))
        assertTrue(repository.recordStaleAction(advanced.id, attemptedScheduleVersion = 1))
        val staleReceipt = repository.deliveries(advanced.id).first {
            it.outcome == ReminderDeliveryOutcome.STALE_ACTION_REFUSED
        }
        assertEquals(1, staleReceipt.scheduleVersion)
        assertTrue(repository.complete(advanced.id, expectedScheduleVersion = 2) != null)
    }

    @Test
    fun todayBriefCanCountOnlyStillActiveItemsSnoozedInsideYesterdayRange() = runBlocking {
        val created = repository.create(draft("2026-08-24T07:00")) as ReminderCreateResult.Created
        repository.recordDelivery(created.reminder, ReminderDeliveryOutcome.SNOOZED_FROM_APP)

        assertEquals(
            1,
            repository.countActiveSnoozedBetween(
                at("2026-08-23T00:00"),
                at("2026-08-24T00:00"),
            ),
        )
        repository.complete(created.reminder.id, created.reminder.scheduleVersion)
        assertEquals(
            0,
            repository.countActiveSnoozedBetween(
                at("2026-08-23T00:00"),
                at("2026-08-24T00:00"),
            ),
        )
    }

    private fun draft(local: String, recurrence: String? = null) = ReminderDraft(
        title = "병원 가기",
        triggerAtEpochMillis = at(local),
        zoneId = zone.id,
        recurrenceRule = recurrence,
        sourceType = ReminderSourceType.DIRECT,
        createdBy = ReminderCreator.USER,
        confirmationDigest = "a".repeat(64),
    )

    private fun at(local: String): Long = LocalDateTime.parse(local)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
}
