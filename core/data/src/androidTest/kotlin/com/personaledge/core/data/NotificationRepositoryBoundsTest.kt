package com.personaledge.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationRepositoryBoundsTest {
    private lateinit var database: PersonalEdgeDatabase
    private val ids = AtomicInteger()
    private lateinit var repository: NotificationRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            PersonalEdgeDatabase::class.java,
        ).build()
        repository = NotificationRepository(
            database,
            clock = { 10_000_000_000L },
            idFactory = { "captured-${ids.incrementAndGet()}" },
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun longPlatformKeysWithSamePrefixRemainDistinct() = runBlocking {
        val prefix = "same".repeat(80)
        repository.capture(draft(prefix + "-one", 1))
        repository.capture(draft(prefix + "-two", 2))

        assertEquals(2, repository.count())
    }

    @Test
    fun everyCaptureEnforcesFiveThousandRowHardCap() = runBlocking {
        repeat(NotificationRepository.MAX_STORED_ROWS) { index ->
            database.capturedNotificationDao().upsert(
                CapturedNotificationEntity(
                    id = "seed-$index",
                    sourceKey = "hash-$index",
                    packageName = "com.kakao.talk",
                    conversationTitle = "room",
                    sender = "sender",
                    text = "message",
                    postedAtEpochMillis = index.toLong(),
                ),
            )
        }

        repository.capture(draft("new-platform-key", 20_000))

        assertEquals(NotificationRepository.MAX_STORED_ROWS.toLong(), repository.count())
    }

    private fun draft(sourceKey: String, postedAt: Long) = CapturedNotificationDraft(
        sourceKey = sourceKey,
        packageName = "com.kakao.talk",
        conversationTitle = "room",
        sender = "sender",
        text = "message",
        postedAtEpochMillis = postedAt,
    )
}
