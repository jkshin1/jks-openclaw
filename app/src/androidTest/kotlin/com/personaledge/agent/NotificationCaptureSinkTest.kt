package com.personaledge.agent

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.AgentSettings
import com.personaledge.core.data.NotificationRepository
import com.personaledge.core.data.PersonalEdgeDatabase
import com.personaledge.core.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationCaptureSinkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var database: PersonalEdgeDatabase
    private lateinit var notifications: NotificationRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var storeFile: File

    private var now = 1_700_000_000_000L

    @Before
    fun openStores() {
        database = Room
            .inMemoryDatabaseBuilder(context, PersonalEdgeDatabase::class.java)
            .build()
        notifications = NotificationRepository(database, clock = { now })
        storeFile = File(context.cacheDir, "capture-test-${System.nanoTime()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { storeFile })
    }

    @After
    fun closeStores() {
        database.close()
        scope.cancel()
        storeFile.delete()
    }

    private fun sink() = NotificationCaptureSink(
        settings = settings,
        notifications = notifications,
        clock = { now },
    )

    private fun post(
        sourceKey: String = "key-1",
        text: String = "저녁에 시간 돼?",
        postedAtEpochMillis: Long = now,
    ) = NotificationPost(
        packageName = NotificationCapture.KAKAO_TALK_PACKAGE,
        sourceKey = sourceKey,
        postedAtEpochMillis = postedAtEpochMillis,
        isGroupSummary = false,
        isOngoing = false,
        title = "김철수",
        text = text,
        conversationTitle = null,
        messagingSender = null,
        messagingText = null,
    )

    private suspend fun search(
        query: String? = null,
        since: Long = 0,
        retentionDays: Int = AgentSettings.DEFAULT_NOTIFICATION_RETENTION_DAYS,
    ) = notifications.search(
        packageNames = listOf(NotificationCapture.KAKAO_TALK_PACKAGE),
        query = query,
        postedAtOrAfter = since,
        retentionDays = retentionDays,
        limit = NotificationRepository.MAX_RESULTS,
    )

    @Test
    fun nothingIsStoredWhileCaptureIsOff() = runBlocking {
        // The listener keeps running as long as notification access is granted, so the setting is
        // the only thing standing between a granted device and a stored transcript.
        assertFalse(sink().accept(post()))
        assertEquals(0L, notifications.count())
    }

    @Test
    fun enablingCaptureStartsStoring() = runBlocking {
        settings.setNotificationCaptureEnabled(true)

        assertTrue(sink().accept(post()))
        assertEquals(1L, notifications.count())
        assertEquals("저녁에 시간 돼?", search().single().text)
    }

    @Test
    fun turningCaptureOffStopsFurtherStorage() = runBlocking {
        val sink = sink()
        settings.setNotificationCaptureEnabled(true)
        assertTrue(sink.accept(post(sourceKey = "key-1")))

        settings.setNotificationCaptureEnabled(false)
        assertFalse(sink.accept(post(sourceKey = "key-2")))

        assertEquals(1L, notifications.count())
    }

    @Test
    fun anUpdatedPostReplacesItsEarlierRow() = runBlocking {
        settings.setNotificationCaptureEnabled(true)
        val sink = sink()

        sink.accept(post(sourceKey = "same-key", text = "첫 메시지"))
        sink.accept(post(sourceKey = "same-key", text = "수정된 메시지"))

        assertEquals(1L, notifications.count())
        assertEquals("수정된 메시지", search().single().text)
    }

    @Test
    fun otherApplicationsAreNeverStored() = runBlocking {
        settings.setNotificationCaptureEnabled(true)

        val fromBank = post().copy(packageName = "com.example.bank")

        assertFalse(sink().accept(fromBank))
        assertEquals(0L, notifications.count())
    }

    @Test
    fun searchMatchesSenderRoomAndBody() = runBlocking {
        settings.setNotificationCaptureEnabled(true)
        val sink = sink()
        sink.accept(post(sourceKey = "a", text = "치과 예약 잊지 마"))
        sink.accept(post(sourceKey = "b", text = "저녁 같이 먹자"))

        assertEquals(listOf("치과 예약 잊지 마"), search(query = "치과").map { it.text })
        assertEquals(2, search(query = "김철수").size)
        assertTrue(search(query = "존재하지않는말").isEmpty())
    }

    @Test
    fun aWildcardInTheQueryCannotWidenTheSearch() = runBlocking {
        settings.setNotificationCaptureEnabled(true)
        val sink = sink()
        sink.accept(post(sourceKey = "a", text = "치과 예약"))
        sink.accept(post(sourceKey = "b", text = "저녁 약속"))

        // A bare % would otherwise match every stored message.
        assertTrue(search(query = "%").isEmpty())
        assertTrue(search(query = "_").isEmpty())
    }

    @Test
    fun aSearchWindowExcludesOlderMessages() = runBlocking {
        settings.setNotificationCaptureEnabled(true)
        val sink = sink()
        val dayMillis = 24L * 60 * 60 * 1_000
        sink.accept(post(sourceKey = "old", text = "예전 메시지", postedAtEpochMillis = now - 5 * dayMillis))
        sink.accept(post(sourceKey = "new", text = "최근 메시지"))

        assertEquals(listOf("최근 메시지"), search(since = now - dayMillis).map { it.text })
    }

    @Test
    fun searchEnforcesRetentionAndPhysicallyPrunesIdleExpiredRows() = runBlocking {
        val dayMillis = 24L * 60 * 60 * 1_000
        notifications.capture(
            NotificationCapture.extract(
                post(sourceKey = "old", text = "예전", postedAtEpochMillis = now - 3 * dayMillis),
            )!!,
        )
        notifications.capture(NotificationCapture.extract(post(sourceKey = "new", text = "최근"))!!)

        assertEquals(listOf("최근"), search(retentionDays = 1).map { it.text })
        assertEquals(1L, notifications.count())
    }

    @Test
    fun callerSearchWindowCanBeNarrowerThanRetention() = runBlocking {
        val dayMillis = 24L * 60 * 60 * 1_000
        notifications.capture(
            NotificationCapture.extract(
                post(sourceKey = "older", text = "이틀 전", postedAtEpochMillis = now - 2 * dayMillis),
            )!!,
        )
        notifications.capture(NotificationCapture.extract(post(sourceKey = "new", text = "최근"))!!)

        assertEquals(
            listOf("최근"),
            search(since = now - dayMillis, retentionDays = 14).map { it.text },
        )
        // The two-day-old row is still inside retention; a narrower query must not delete it.
        assertEquals(2L, notifications.count())
    }

    @Test
    fun deletingCapturedMessagesLeavesNothingSearchable() = runBlocking {
        settings.setNotificationCaptureEnabled(true)
        val sink = sink()
        repeat(5) { index -> sink.accept(post(sourceKey = "key-$index", text = "메시지 $index")) }

        assertEquals(5, notifications.deleteAll())

        assertEquals(0L, notifications.count())
        assertTrue(search().isEmpty())
    }
}
