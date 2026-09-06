package com.personaledge.core.data

import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SecretVaultCompareAndSetTest {
    @Test
    fun `absent expectation inserts once and cannot overwrite the winner`() = runTest {
        val fileSystem = MemorySecretFileSystem()
        val vault = vault(fileSystem)
        val name = SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD

        assertTrue(vault.compareAndStore(name, expectedValue = null, value = "first-record"))
        assertFalse(vault.compareAndStore(name, expectedValue = null, value = "stale-record"))

        assertEquals("first-record", vault.read(name))
    }

    @Test
    fun `exact expected record replaces while stale expected record cannot`() = runTest {
        val vault = vault(MemorySecretFileSystem())
        val name = SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD
        vault.store(name, "origin-a-record")

        assertFalse(
            vault.compareAndStore(
                name,
                expectedValue = "stale-origin-record",
                value = "must-not-win",
            ),
        )
        assertTrue(
            vault.compareAndStore(
                name,
                expectedValue = "origin-a-record",
                value = "origin-b-record",
            ),
        )

        assertEquals("origin-b-record", vault.read(name))
    }

    @Test
    fun `conditional removal preserves a record replaced by another epoch`() = runTest {
        val vault = vault(MemorySecretFileSystem())
        val name = SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD
        vault.store(name, "newer-origin-record")

        assertFalse(vault.compareAndRemove(name, expectedValue = "older-origin-record"))
        assertEquals("newer-origin-record", vault.read(name))
        assertTrue(vault.compareAndRemove(name, expectedValue = "newer-origin-record"))
        assertNull(vault.read(name))
    }

    @Test
    fun `unreadable existing slot never satisfies an absent or plaintext expectation`() = runTest {
        val fileSystem = MemorySecretFileSystem()
        val vault = vault(fileSystem)
        val name = SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD
        fileSystem.putUnreadable(name)

        assertFalse(vault.compareAndStore(name, expectedValue = null, value = "replacement"))
        assertFalse(
            vault.compareAndStore(
                name,
                expectedValue = "guessed-record",
                value = "replacement",
            ),
        )
        assertFalse(vault.compareAndRemove(name, expectedValue = "guessed-record"))
        assertEquals(SecretHealth.UNREADABLE, vault.health(name))
    }

    private fun kotlinx.coroutines.test.TestScope.vault(
        fileSystem: MemorySecretFileSystem,
    ): SecretVault = SecretVault(
        directory = File("/content-free-test-vault"),
        keyStoreProvider = {
            SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
        },
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        fileSystem = fileSystem,
    )

    private class MemorySecretFileSystem : SecureSecretFileSystem {
        private val records = mutableMapOf<String, ByteArray>()

        fun putUnreadable(name: SecretKeyName) {
            records[name.fileName] = byteArrayOf(1, 2, 3)
        }

        override fun atomicReplace(
            target: File,
            temporary: File,
            bytes: ByteArray,
            maxBytes: Int,
        ) {
            check(target.name != temporary.name)
            check(bytes.isNotEmpty() && bytes.size <= maxBytes)
            records[target.name] = bytes.copyOf()
        }

        override fun readOrNull(target: File, maxBytes: Int): ByteArray? = records[target.name]
            ?.takeIf { bytes -> bytes.size <= maxBytes }
            ?.copyOf()

        override fun exists(target: File): Boolean = records.containsKey(target.name)

        override fun remove(target: File): Boolean = records.remove(target.name) != null

        override fun clear() {
            records.clear()
        }
    }
}
