package com.personaledge.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, SecretVault.DIRECTORY_NAME)

    private lateinit var vault: SecretVault

    @Before
    fun createVault() {
        directory.deleteRecursively()
        vault = SecretVault.create(context)
    }

    @After
    fun clearVault() {
        runBlocking { vault.clear() }
        directory.deleteRecursively()
    }

    @Test
    fun aStoredSecretRoundTrips() = runBlocking {
        vault.store(SecretKeyName.NAVER_MAP_CLIENT_ID, "client-id-value")

        assertTrue(vault.contains(SecretKeyName.NAVER_MAP_CLIENT_ID))
        assertEquals(SecretHealth.READABLE, vault.health(SecretKeyName.NAVER_MAP_CLIENT_ID))
        assertEquals("client-id-value", vault.read(SecretKeyName.NAVER_MAP_CLIENT_ID))
    }

    @Test
    fun theCiphertextOnDiskDoesNotContainThePlaintext() = runBlocking {
        vault.store(SecretKeyName.TAVILY_API_KEY, "tvly-super-secret-search-key")

        val stored = File(directory, SecretKeyName.TAVILY_API_KEY.fileName).readBytes()
        val asText = String(stored, Charsets.ISO_8859_1)

        assertFalse(asText.contains("super-secret-search-key"))
        assertNotEquals(0, stored.size)
    }

    @Test
    fun encryptingTheSameValueTwiceProducesDifferentBytes() = runBlocking {
        vault.store(SecretKeyName.NAVER_MAP_CLIENT_SECRET, "repeated-value")
        val first = File(directory, SecretKeyName.NAVER_MAP_CLIENT_SECRET.fileName).readBytes()

        vault.store(SecretKeyName.NAVER_MAP_CLIENT_SECRET, "repeated-value")
        val second = File(directory, SecretKeyName.NAVER_MAP_CLIENT_SECRET.fileName).readBytes()

        // A fresh GCM IV per write; a repeated IV under one key would leak the plaintext.
        assertFalse(first.contentEquals(second))
        assertEquals("repeated-value", vault.read(SecretKeyName.NAVER_MAP_CLIENT_SECRET))
    }

    @Test
    fun aTamperedCiphertextIsReportedAsUnreadableRatherThanReturned() = runBlocking {
        vault.store(SecretKeyName.TAVILY_API_KEY, "tvly-authentic-value")

        val file = File(directory, SecretKeyName.TAVILY_API_KEY.fileName)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertNull(vault.read(SecretKeyName.TAVILY_API_KEY))
        assertEquals(SecretHealth.UNREADABLE, vault.health(SecretKeyName.TAVILY_API_KEY))
    }

    @Test
    fun anExistingEmptyCiphertextIsUnreadableRatherThanAbsent() = runBlocking {
        val file = File(directory, SecretKeyName.NAVER_MAP_CLIENT_SECRET.fileName)
        file.writeBytes(byteArrayOf())

        assertTrue(vault.contains(SecretKeyName.NAVER_MAP_CLIENT_SECRET))
        assertNull(vault.read(SecretKeyName.NAVER_MAP_CLIENT_SECRET))
        assertEquals(
            SecretHealth.UNREADABLE,
            vault.health(SecretKeyName.NAVER_MAP_CLIENT_SECRET),
        )
    }

    @Test
    fun anAbsentSecretReadsAsNull() = runBlocking {
        assertFalse(vault.contains(SecretKeyName.NAVER_MAP_CLIENT_ID))
        assertNull(vault.read(SecretKeyName.NAVER_MAP_CLIENT_ID))
        assertEquals(SecretHealth.ABSENT, vault.health(SecretKeyName.NAVER_MAP_CLIENT_ID))
    }

    @Test
    fun clearingRemovesEverySecret() = runBlocking {
        SecretKeyName.entries.forEach { name -> vault.store(name, "value-for-${name.name}") }

        vault.clear()

        SecretKeyName.entries.forEach { name -> assertFalse(vault.contains(name)) }
    }

    @Test
    fun secretsLiveOutsideTheBackupSet() = runBlocking {
        vault.store(SecretKeyName.NAVER_MAP_CLIENT_ID, "location-check")

        assertEquals(context.noBackupFilesDir, directory.parentFile)
    }
}
