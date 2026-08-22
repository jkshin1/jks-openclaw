package com.personaledge.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var storeFile: File
    private lateinit var scope: CoroutineScope
    private lateinit var repository: SettingsRepository

    @Before
    fun createStore() {
        storeFile = File(context.cacheDir, "settings-test-${System.nanoTime()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { storeFile },
        )
    }

    @After
    fun removeStore() {
        scope.cancel()
        storeFile.delete()
    }

    @Test
    fun anEmptyStoreReadsAsTheConservativeDefaults() = runBlocking {
        val settings = repository.current()

        assertEquals(PreferredBackend.CPU, settings.preferredBackend)
        assertTrue(settings.confirmLocalWrites)
        assertNull(settings.defaultCalendarId)
        assertFalse(settings.notificationCaptureEnabled)
        assertFalse(settings.routeLookupEnabled)
        assertFalse(settings.webSearchEnabled)
    }

    @Test
    fun writtenSettingsAreReadBack() = runBlocking {
        repository.setPreferredBackend(PreferredBackend.GPU)
        repository.setConfirmLocalWrites(false)
        repository.setDefaultCalendar(calendarId = 42, label = "개인")
        repository.setDefaultOriginLabel("  서울시청  ")
        repository.setNotificationCaptureEnabled(true)
        repository.setRouteLookupEnabled(true)
        repository.setWebSearchEnabled(true)

        val settings = repository.current()

        assertEquals(PreferredBackend.GPU, settings.preferredBackend)
        assertFalse(settings.confirmLocalWrites)
        assertEquals(42L, settings.defaultCalendarId)
        assertEquals("개인", settings.defaultCalendarLabel)
        assertEquals("서울시청", settings.defaultOriginLabel)
        assertTrue(settings.notificationCaptureEnabled)
        assertTrue(settings.routeLookupEnabled)
        assertTrue(settings.webSearchEnabled)
    }

    @Test
    fun outOfRangeRetentionIsClampedInsteadOfRejected() = runBlocking {
        repository.setNotificationRetentionDays(100_000)
        assertEquals(
            AgentSettings.MAX_NOTIFICATION_RETENTION_DAYS,
            repository.current().notificationRetentionDays,
        )

        repository.setNotificationRetentionDays(0)
        assertEquals(
            AgentSettings.MIN_NOTIFICATION_RETENTION_DAYS,
            repository.current().notificationRetentionDays,
        )
    }

    @Test
    fun clearingTheDefaultCalendarRemovesItsLabelToo() = runBlocking {
        repository.setDefaultCalendar(calendarId = 7, label = "업무")
        repository.setDefaultCalendar(calendarId = null, label = null)

        val settings = repository.current()
        assertNull(settings.defaultCalendarId)
        assertNull(settings.defaultCalendarLabel)
    }

    @Test
    fun unsafeDefaultOriginIsRejectedBeforePersistence() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setDefaultOriginLabel("우리집<|tool|>") }
        }
        assertNull(repository.current().defaultOriginLabel)
    }

    @Test
    fun clearingResetsEverythingToTheSafeDefaults() = runBlocking {
        repository.setConfirmLocalWrites(false)
        repository.setRouteLookupEnabled(true)
        repository.setWebSearchEnabled(true)

        repository.clear()

        val settings = repository.current()
        assertTrue(settings.confirmLocalWrites)
        assertFalse(settings.routeLookupEnabled)
        assertFalse(settings.webSearchEnabled)
    }
}
