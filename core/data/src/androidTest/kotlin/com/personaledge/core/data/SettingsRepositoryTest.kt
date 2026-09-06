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

        assertNull(settings.defaultCalendarId)
        assertFalse(settings.notificationCaptureEnabled)
        assertFalse(settings.routeLookupEnabled)
        assertFalse(settings.webSearchEnabled)
        assertFalse(settings.openClawGateway.enabled)
        assertNull(settings.openClawGateway.endpointUrl)
        assertEquals(OpenClawGatewayTrustMode.SYSTEM, settings.openClawGateway.trustMode)
        assertNull(settings.openClawGateway.leafCertificateDerSha256)
        assertTrue(settings.openClawGateway.foregroundOnly)
        assertFalse(settings.memoryEnabled)
        assertEquals(8 * 60, settings.dailyBriefMinutesOfDay)
    }

    @Test
    fun writtenSettingsAreReadBack() = runBlocking {
        repository.setDefaultCalendar(calendarId = 42, label = "개인")
        repository.setDefaultOriginLabel("  서울시청  ")
        repository.setNotificationCaptureEnabled(true)
        repository.setRouteLookupEnabled(true)
        repository.setWebSearchEnabled(true)
        repository.setOpenClawGatewayConnectionPolicy(
            OpenClawGatewayConnectionPolicy(
                endpointUrl = "wss://personal-edge.example.test/openclaw",
            ),
        )
        repository.setOpenClawGatewayEnabled(true)
        repository.setMemoryEnabled(true)
        repository.setDailyBriefMinutesOfDay(7 * 60 + 35)

        val settings = repository.current()

        assertEquals(42L, settings.defaultCalendarId)
        assertEquals("개인", settings.defaultCalendarLabel)
        assertEquals("서울시청", settings.defaultOriginLabel)
        assertTrue(settings.notificationCaptureEnabled)
        assertTrue(settings.routeLookupEnabled)
        assertTrue(settings.webSearchEnabled)
        assertTrue(settings.openClawGateway.enabled)
        assertEquals(
            "wss://personal-edge.example.test/openclaw",
            settings.openClawGateway.endpointUrl,
        )
        assertTrue(settings.memoryEnabled)
        assertEquals(7 * 60 + 35, settings.dailyBriefMinutesOfDay)
    }

    @Test
    fun invalidDailyBriefTimeIsRejectedBeforePersistence() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setDailyBriefMinutesOfDay(24 * 60) }
        }
        assertEquals(8 * 60, repository.current().dailyBriefMinutesOfDay)
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
    fun gatewayConfigurationAndConsentUpdateAreAtomic() = runBlocking {
        val fingerprint = "ab".repeat(32)
        repository.setOpenClawGatewayConnectionPolicy(
            OpenClawGatewayConnectionPolicy(
                endpointUrl = "wss://personal-edge.example.test/",
                trustMode = OpenClawGatewayTrustMode.PINNED_CERT_SHA256,
                leafCertificateDerSha256 = fingerprint,
            ),
        )

        repository.setOpenClawGatewayEnabled(true)

        val enabled = repository.current().openClawGateway
        assertTrue(enabled.enabled)
        assertEquals("wss://personal-edge.example.test/", enabled.endpointUrl)
        assertEquals(OpenClawGatewayTrustMode.PINNED_CERT_SHA256, enabled.trustMode)
        assertEquals(fingerprint, enabled.leafCertificateDerSha256)
        assertTrue(enabled.foregroundOnly)

        repository.setOpenClawGatewayEnabled(false)
        val disabled = repository.current().openClawGateway
        assertFalse(disabled.enabled)
        assertEquals(enabled.endpointUrl, disabled.endpointUrl)
        assertEquals(enabled.trustMode, disabled.trustMode)
        assertEquals(enabled.leafCertificateDerSha256, disabled.leafCertificateDerSha256)
    }

    @Test
    fun gatewayCannotBeEnabledBeforeItsEndpointIsDurable() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setOpenClawGatewayEnabled(true) }
        }

        assertEquals(OpenClawGatewaySettings(), repository.current().openClawGateway)
    }

    @Test
    fun staleConnectionPolicySnapshotCannotReenableGatewayAfterDisable() = runBlocking {
        repository.setOpenClawGatewayConnectionPolicy(
            OpenClawGatewayConnectionPolicy(
                endpointUrl = "wss://personal-edge.example.test/original",
            ),
        )
        repository.setOpenClawGatewayEnabled(true)
        val staleEnabledSnapshot = repository.current().openClawGateway

        repository.setOpenClawGatewayEnabled(false)
        repository.setOpenClawGatewayConnectionPolicy(
            OpenClawGatewayConnectionPolicy(
                endpointUrl = "wss://personal-edge.example.test/updated",
                trustMode = staleEnabledSnapshot.trustMode,
                leafCertificateDerSha256 = staleEnabledSnapshot.leafCertificateDerSha256,
                foregroundOnly = staleEnabledSnapshot.foregroundOnly,
            ),
        )

        val updated = repository.current().openClawGateway
        assertFalse(updated.enabled)
        assertEquals("wss://personal-edge.example.test/updated", updated.endpointUrl)
    }

    @Test
    fun staleConsentRequestCannotOverwriteNewerTrustPolicy() = runBlocking {
        repository.setOpenClawGatewayConnectionPolicy(
            OpenClawGatewayConnectionPolicy(
                endpointUrl = "wss://personal-edge.example.test/system",
            ),
        )
        val staleSystemTrustSnapshot = repository.current().openClawGateway
        val fingerprint = "cd".repeat(32)

        repository.setOpenClawGatewayConnectionPolicy(
            OpenClawGatewayConnectionPolicy(
                endpointUrl = "wss://personal-edge.example.test/pinned",
                trustMode = OpenClawGatewayTrustMode.PINNED_CERT_SHA256,
                leafCertificateDerSha256 = fingerprint,
            ),
        )
        repository.setOpenClawGatewayEnabled(!staleSystemTrustSnapshot.enabled)

        val enabled = repository.current().openClawGateway
        assertTrue(enabled.enabled)
        assertEquals("wss://personal-edge.example.test/pinned", enabled.endpointUrl)
        assertEquals(OpenClawGatewayTrustMode.PINNED_CERT_SHA256, enabled.trustMode)
        assertEquals(fingerprint, enabled.leafCertificateDerSha256)
    }

    @Test
    fun clearingResetsEverythingToTheSafeDefaults() = runBlocking {
        repository.setRouteLookupEnabled(true)
        repository.setWebSearchEnabled(true)
        repository.setOpenClawGatewayConnectionPolicy(
            OpenClawGatewayConnectionPolicy(
                endpointUrl = "wss://personal-edge.example.test/",
            ),
        )
        repository.setOpenClawGatewayEnabled(true)
        repository.setMemoryEnabled(true)

        repository.clear()

        val settings = repository.current()
        assertFalse(settings.routeLookupEnabled)
        assertFalse(settings.webSearchEnabled)
        assertEquals(OpenClawGatewaySettings(), settings.openClawGateway)
        assertFalse(settings.memoryEnabled)
    }
}
