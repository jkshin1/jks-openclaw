package com.personaledge.core.data

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureSecretFileSystemTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var directory: File
    private lateinit var target: File
    private lateinit var temporary: File
    private lateinit var outside: File
    private lateinit var fileSystem: AndroidSecureSecretFileSystem

    @Before
    fun createIsolatedDirectory() {
        val suffix = "${android.os.Process.myPid()}-${System.nanoTime()}"
        directory = File(context.noBackupFilesDir, "secure-secret-fs-test-$suffix")
        target = File(directory, SecretKeyName.NAVER_MAP_CLIENT_ID.fileName)
        temporary = File(directory, ".${SecretKeyName.NAVER_MAP_CLIENT_ID.fileName}.tmp")
        outside = File(context.cacheDir, "secure-secret-fs-outside-$suffix")
        fileSystem = AndroidSecureSecretFileSystem(directory)
    }

    @After
    fun removeIsolatedDirectory() {
        runCatching { fileSystem.clear() }
        assertTrue("isolated secret test directory must be removable", directory.delete())
        if (outside.exists()) assertTrue("outside test file must be removable", outside.delete())
    }

    @Test
    fun interruptedPublishKeepsOldTargetAndNextWriteRemovesTheStaleTemp() {
        val original = "old-ciphertext".toByteArray()
        val replacement = "new-ciphertext".toByteArray()
        fileSystem.atomicReplace(target, temporary, original, MAX_BYTES)

        val interrupted = AndroidSecureSecretFileSystem(directory) {
            throw SimulatedInterruption()
        }
        assertThrows(SimulatedInterruption::class.java) {
            interrupted.atomicReplace(target, temporary, replacement, MAX_BYTES)
        }

        assertArrayEquals(original, fileSystem.readOrNull(target, MAX_BYTES))
        assertTrue(temporary.exists())

        val recovered = AndroidSecureSecretFileSystem(directory)
        recovered.atomicReplace(target, temporary, replacement, MAX_BYTES)

        assertArrayEquals(replacement, recovered.readOrNull(target, MAX_BYTES))
        assertFalse(temporary.exists())
    }

    @Test
    fun staleTempSymlinkIsRefusedWithoutChangingItsReferent() {
        outside.writeText("outside-sentinel")
        Os.symlink(outside.path, temporary.path)

        assertThrows(IllegalStateException::class.java) {
            fileSystem.atomicReplace(
                target,
                temporary,
                "ciphertext".toByteArray(),
                MAX_BYTES,
            )
        }

        assertEquals("outside-sentinel", outside.readText())
        assertFalse(target.exists())
    }

    @Test
    fun targetSymlinkCannotBeReadOrReplacedAndRemovalDoesNotFollowIt() {
        outside.writeText("outside-sentinel")
        Os.symlink(outside.path, target.path)

        assertNull(fileSystem.readOrNull(target, MAX_BYTES))
        assertThrows(IllegalStateException::class.java) {
            fileSystem.atomicReplace(
                target,
                temporary,
                "ciphertext".toByteArray(),
                MAX_BYTES,
            )
        }

        assertTrue(fileSystem.remove(target))
        assertFalse(target.exists())
        assertEquals("outside-sentinel", outside.readText())
    }

    @Test
    fun publishedFileIsPrivateAndPermissiveExistingFileIsRefused() {
        fileSystem.atomicReplace(
            target,
            temporary,
            "ciphertext".toByteArray(),
            MAX_BYTES,
        )

        assertEquals(OWNER_ONLY_DIRECTORY_MODE, Os.lstat(directory.path).st_mode and PERMISSION_MASK)
        assertEquals(OWNER_ONLY_FILE_MODE, Os.lstat(target.path).st_mode and PERMISSION_MASK)
        Os.chmod(target.path, WORLD_READABLE_FILE_MODE)

        assertNull(fileSystem.readOrNull(target, MAX_BYTES))
        assertThrows(IllegalStateException::class.java) {
            fileSystem.atomicReplace(
                target,
                temporary,
                "replacement".toByteArray(),
                MAX_BYTES,
            )
        }
    }

    private class SimulatedInterruption : RuntimeException()

    private companion object {
        const val MAX_BYTES = 1_024
        const val OWNER_ONLY_DIRECTORY_MODE = 0x1C0 // 0700
        const val OWNER_ONLY_FILE_MODE = 0x180 // 0600
        const val WORLD_READABLE_FILE_MODE = 0x1A4 // 0644
        const val PERMISSION_MASK = 0x1FF // 0777
    }
}
