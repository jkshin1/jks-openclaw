package com.personaledge.agent

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.SecretKeyName
import com.personaledge.core.data.SecretVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses the real AndroidKeyStore, because the point of this feature is what the hardware key does.
 * Values here are obvious placeholders, never real credentials.
 *
 * Emulator only. The vault it opens is the installed app's own, and both setup and teardown clear
 * it — on a phone that would silently delete the owner's real provider keys, which cannot be read
 * back or recovered. The KeyStore behaviour under test is identical on the emulator, so nothing is
 * lost by skipping here.
 */
@RunWith(AndroidJUnit4::class)
class CredentialSettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val isEmulator: Boolean
        get() = Build.HARDWARE == "ranchu" || Build.FINGERPRINT.contains("generic")

    private lateinit var vault: SecretVault
    private lateinit var settings: CredentialSettings

    @Before
    fun createVault() {
        assumeTrue("Clearing the real credential vault is emulator-only.", isEmulator)
        vault = SecretVault.create(context)
        settings = CredentialSettings(vault)
        runBlocking { vault.clear() }
    }

    @After
    fun clearVault() {
        if (isEmulator && ::vault.isInitialized) {
            runBlocking { vault.clear() }
        }
    }

    private suspend fun storedSlots() = settings.statuses()
        .filter(CredentialStatus::stored)
        .map(CredentialStatus::slot)

    private fun rejection(result: CredentialStoreResult) =
        (result as CredentialStoreResult.Rejected).reason

    private class FakeCredentialVerifier(
        private val verification: CredentialVerification,
    ) : CredentialVerifier {
        var calls: Int = 0
            private set
        var lastSlot: CredentialSlot? = null
            private set
        var lastValueLength: Int = 0
            private set

        override suspend fun verify(
            slot: CredentialSlot,
            value: String,
        ): CredentialVerification {
            calls += 1
            lastSlot = slot
            lastValueLength = value.length
            return verification
        }
    }

    @Test
    fun everySlotStartsUnset() = runBlocking {
        assertEquals(CredentialSlot.entries, settings.statuses().map(CredentialStatus::slot))
        assertTrue(storedSlots().isEmpty())
    }

    @Test
    fun aStoredKeyIsReportedAsPresentWithoutRevealingIt() = runBlocking {
        assertEquals(
            CredentialStoreResult.Stored,
            settings.store(CredentialSlot.TAVILY_API_KEY, "tvly-placeholder-value-1"),
        )

        assertEquals(listOf(CredentialSlot.TAVILY_API_KEY), storedSlots())
        // The status carries presence and nothing else; there is no field to leak the value.
        val status = settings.statuses().single { it.slot == CredentialSlot.TAVILY_API_KEY }
        assertTrue(status.stored)
    }

    @Test
    fun anInvalidTavilyKeyIsRejectedBeforeItReachesTheVault() = runBlocking {
        val verifier = FakeCredentialVerifier(
            CredentialVerification.Rejected(CredentialVerificationFailure.INVALID_CREDENTIAL),
        )
        settings = CredentialSettings(vault, verifier)
        val value = "tvly-invalid-placeholder"

        val result = settings.store(CredentialSlot.TAVILY_API_KEY, value)

        assertEquals(CredentialSettings.TAVILY_INVALID_CREDENTIAL_REASON, rejection(result))
        assertFalse(rejection(result).contains(value))
        assertEquals(1, verifier.calls)
        assertEquals(CredentialSlot.TAVILY_API_KEY, verifier.lastSlot)
        assertEquals(value.length, verifier.lastValueLength)
        assertFalse(vault.contains(SecretKeyName.TAVILY_API_KEY))
    }

    @Test
    fun aValidTavilyKeyIsStoredAfterVerification() = runBlocking {
        val verifier = FakeCredentialVerifier(CredentialVerification.Accepted)
        settings = CredentialSettings(vault, verifier)

        assertEquals(
            CredentialStoreResult.Stored,
            settings.store(CredentialSlot.TAVILY_API_KEY, "tvly-valid-placeholder"),
        )
        assertEquals(1, verifier.calls)
        assertEquals(listOf(CredentialSlot.TAVILY_API_KEY), storedSlots())
    }

    @Test
    fun verifierCancellationIsRethrownWithoutStoringTheKey() {
        settings = CredentialSettings(
            vault,
            CredentialVerifier { _, _ -> throw CancellationException("verification cancelled") },
        )
        var cancellation: CancellationException? = null

        try {
            runBlocking {
                settings.store(CredentialSlot.TAVILY_API_KEY, "tvly-cancelled-placeholder")
            }
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
        }

        assertEquals("verification cancelled", cancellation?.message)
        runBlocking { assertFalse(vault.contains(SecretKeyName.TAVILY_API_KEY)) }
    }

    @Test
    fun slotsAreIndependent() = runBlocking {
        settings.store(CredentialSlot.NAVER_MAP_CLIENT_ID, "placeholder-id")
        settings.store(CredentialSlot.NAVER_MAP_CLIENT_SECRET, "placeholder-secret")

        assertTrue(settings.delete(CredentialSlot.NAVER_MAP_CLIENT_ID))

        assertEquals(listOf(CredentialSlot.NAVER_MAP_CLIENT_SECRET), storedSlots())
    }

    @Test
    fun storingAgainOverAnExistingKeySucceeds() = runBlocking {
        settings.store(CredentialSlot.TAVILY_API_KEY, "tvly-placeholder-first")

        assertEquals(
            CredentialStoreResult.Stored,
            settings.store(CredentialSlot.TAVILY_API_KEY, "tvly-placeholder-second"),
        )
        assertEquals(listOf(CredentialSlot.TAVILY_API_KEY), storedSlots())
    }

    @Test
    fun surroundingWhitespaceFromAPasteIsTrimmedRatherThanRejected() = runBlocking {
        assertEquals(
            CredentialStoreResult.Stored,
            settings.store(CredentialSlot.TAVILY_API_KEY, "  tvly-placeholder-value-3\n"),
        )
        assertTrue(storedSlots().contains(CredentialSlot.TAVILY_API_KEY))
    }

    @Test
    fun aPasteThatCaughtSurroundingTextIsRejected() = runBlocking {
        val verifier = FakeCredentialVerifier(CredentialVerification.Accepted)
        settings = CredentialSettings(vault, verifier)
        val result = settings.store(CredentialSlot.TAVILY_API_KEY, "API Key placeholder")

        assertEquals("공백이 포함되어 있습니다. 키만 붙여넣었는지 확인하세요.", rejection(result))
        assertEquals(0, verifier.calls)
        assertTrue(storedSlots().isEmpty())
    }

    @Test
    fun blankOversizedAndControlCharacterInputAreRejected() = runBlocking {
        assertEquals("값을 입력하세요.", rejection(settings.store(CredentialSlot.TAVILY_API_KEY, "   ")))
        assertEquals(
            "키가 너무 깁니다. 붙여넣은 내용을 확인하세요.",
            rejection(
                settings.store(
                    CredentialSlot.TAVILY_API_KEY,
                    "a".repeat(CredentialSettings.MAX_CREDENTIAL_CHARACTERS + 1),
                ),
            ),
        )
        assertEquals(
            "사용할 수 없는 문자가 포함되어 있습니다.",
            rejection(settings.store(CredentialSlot.TAVILY_API_KEY, "placeholder\u0007value")),
        )
        assertTrue(storedSlots().isEmpty())
    }

    @Test
    fun aRejectionNeverEchoesTheValue() = runBlocking {
        val secretish = "placeholder value with spaces"

        val reason = rejection(settings.store(CredentialSlot.TAVILY_API_KEY, secretish))

        assertFalse(reason.contains("placeholder"))
    }

    @Test
    fun deletingAnAbsentKeyReportsFailureWithoutThrowing() = runBlocking {
        assertFalse(settings.delete(CredentialSlot.NAVER_MAP_CLIENT_ID))
        assertTrue(storedSlots().isEmpty())
    }
}
