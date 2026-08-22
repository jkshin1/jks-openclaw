package com.personaledge.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.SecretVault
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses the real AndroidKeyStore, because the point of this feature is what the hardware key does.
 * Values here are obvious placeholders, never real credentials.
 */
@RunWith(AndroidJUnit4::class)
class CredentialSettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var vault: SecretVault
    private lateinit var settings: CredentialSettings

    @Before
    fun createVault() {
        vault = SecretVault.create(context)
        settings = CredentialSettings(vault)
        runBlocking { vault.clear() }
    }

    @After
    fun clearVault() {
        runBlocking { vault.clear() }
    }

    private suspend fun storedSlots() = settings.statuses()
        .filter(CredentialStatus::stored)
        .map(CredentialStatus::slot)

    private fun rejection(result: CredentialStoreResult) =
        (result as CredentialStoreResult.Rejected).reason

    @Test
    fun everySlotStartsUnset() = runBlocking {
        assertEquals(CredentialSlot.entries, settings.statuses().map(CredentialStatus::slot))
        assertTrue(storedSlots().isEmpty())
    }

    @Test
    fun aStoredKeyIsReportedAsPresentWithoutRevealingIt() = runBlocking {
        assertEquals(
            CredentialStoreResult.Stored,
            settings.store(CredentialSlot.NAVER_SEARCH_CLIENT_ID, "placeholder-value-1"),
        )

        assertEquals(listOf(CredentialSlot.NAVER_SEARCH_CLIENT_ID), storedSlots())
        // The status carries presence and nothing else; there is no field to leak the value.
        val status = settings.statuses().single { it.slot == CredentialSlot.NAVER_SEARCH_CLIENT_ID }
        assertTrue(status.stored)
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
        settings.store(CredentialSlot.NAVER_SEARCH_CLIENT_ID, "placeholder-first")

        assertEquals(
            CredentialStoreResult.Stored,
            settings.store(CredentialSlot.NAVER_SEARCH_CLIENT_ID, "placeholder-second"),
        )
        assertEquals(listOf(CredentialSlot.NAVER_SEARCH_CLIENT_ID), storedSlots())
    }

    @Test
    fun surroundingWhitespaceFromAPasteIsTrimmedRatherThanRejected() = runBlocking {
        assertEquals(
            CredentialStoreResult.Stored,
            settings.store(CredentialSlot.NAVER_SEARCH_CLIENT_ID, "  placeholder-value-3\n"),
        )
        assertTrue(storedSlots().contains(CredentialSlot.NAVER_SEARCH_CLIENT_ID))
    }

    @Test
    fun aPasteThatCaughtSurroundingTextIsRejected() = runBlocking {
        val result = settings.store(CredentialSlot.NAVER_SEARCH_CLIENT_ID, "Client Secret placeholder")

        assertEquals("공백이 포함되어 있습니다. 키만 붙여넣었는지 확인하세요.", rejection(result))
        assertTrue(storedSlots().isEmpty())
    }

    @Test
    fun blankOversizedAndControlCharacterInputAreRejected() = runBlocking {
        assertEquals("값을 입력하세요.", rejection(settings.store(CredentialSlot.NAVER_SEARCH_CLIENT_ID, "   ")))
        assertEquals(
            "키가 너무 깁니다. 붙여넣은 내용을 확인하세요.",
            rejection(
                settings.store(
                    CredentialSlot.NAVER_SEARCH_CLIENT_ID,
                    "a".repeat(CredentialSettings.MAX_CREDENTIAL_CHARACTERS + 1),
                ),
            ),
        )
        assertEquals(
            "사용할 수 없는 문자가 포함되어 있습니다.",
            rejection(settings.store(CredentialSlot.NAVER_SEARCH_CLIENT_ID, "placeholder\u0007value")),
        )
        assertTrue(storedSlots().isEmpty())
    }

    @Test
    fun aRejectionNeverEchoesTheValue() = runBlocking {
        val secretish = "placeholder value with spaces"

        val reason = rejection(settings.store(CredentialSlot.NAVER_SEARCH_CLIENT_ID, secretish))

        assertFalse(reason.contains("placeholder"))
    }

    @Test
    fun deletingAnAbsentKeyReportsFailureWithoutThrowing() = runBlocking {
        assertFalse(settings.delete(CredentialSlot.NAVER_MAP_CLIENT_ID))
        assertTrue(storedSlots().isEmpty())
    }
}
