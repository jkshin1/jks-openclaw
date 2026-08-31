package com.personaledge.agent

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.CapturedNotificationDraft
import com.personaledge.core.data.NotificationRepository
import com.personaledge.core.data.PersonalEdgeDatabase
import com.personaledge.core.data.SettingsRepository
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoredNotificationGatewayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var database: PersonalEdgeDatabase
    private lateinit var notifications: NotificationRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var storeFile: File

    private var now = 1_700_000_000_000L

    @Before
    fun openStores() {
        database = Room.inMemoryDatabaseBuilder(context, PersonalEdgeDatabase::class.java).build()
        notifications = NotificationRepository(database, clock = { now })
        storeFile = File(context.cacheDir, "gateway-test-${System.nanoTime()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { storeFile })
    }

    @After
    fun closeStores() {
        database.close()
        scope.cancel()
        storeFile.delete()
    }

    private fun gateway(
        interlock: NotificationCaptureInterlock = NotificationCaptureInterlock(),
    ) = StoredNotificationGateway(
        context = context,
        notifications = notifications,
        settings = settings,
        captureInterlock = interlock,
        zoneProvider = { ZoneOffset.UTC },
    )

    private suspend fun capture(sourceKey: String, text: String, postedAt: Long) {
        notifications.capture(
            CapturedNotificationDraft(
                sourceKey = sourceKey,
                packageName = NotificationCapture.KAKAO_TALK_PACKAGE,
                conversationTitle = "테스트방",
                sender = "보낸이",
                text = text,
                postedAtEpochMillis = postedAt,
            ),
        )
    }

    @Test
    fun searchUsesConfiguredRetentionEvenWhenRequestedWindowIsWider() = runBlocking {
        val dayMillis = 24L * 60 * 60 * 1_000
        settings.setNotificationCaptureEnabled(true)
        settings.setNotificationRetentionDays(1)
        capture("expired", "만료", now - 2 * dayMillis)
        capture("current", "유효", now)

        val messages = gateway().search(
            query = null,
            postedAtOrAfter = now - 30 * dayMillis,
            limit = NotificationRepository.MAX_RESULTS,
        )

        assertEquals(listOf("유효"), messages.map { it.text })
        assertEquals(1L, notifications.count())
    }

    @Test
    fun storedCountPrunesRowsThatExpiredWhileListenerWasIdle() = runBlocking {
        val dayMillis = 24L * 60 * 60 * 1_000
        settings.setNotificationRetentionDays(1)
        capture("current", "곧 만료", now)
        assertEquals(1L, notifications.count())

        now += 2 * dayMillis

        assertEquals(0L, gateway().storedCount())
        assertEquals(0L, notifications.count())
    }

    @Test
    fun disableRequestImmediatelyBlocksSearchOfExistingCache() = runBlocking {
        settings.setNotificationCaptureEnabled(true)
        capture("existing", "기존 메시지", now)
        val interlock = NotificationCaptureInterlock()
        val gateway = gateway(interlock)
        assertEquals(1, gateway.search(null, 0, 10).size)

        interlock.requestCaptureEnabled(false)

        assertFalse(gateway.captureEnabled())
        assertEquals(0, gateway.search(null, 0, 10).size)
        assertEquals(1L, notifications.count())
    }
}
