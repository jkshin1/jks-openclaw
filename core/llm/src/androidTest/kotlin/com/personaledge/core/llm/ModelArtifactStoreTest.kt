package com.personaledge.core.llm

import androidx.test.platform.app.InstrumentationRegistry
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Test

class ModelArtifactStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun verifiedBytesBecomeTheOnlyActiveArtifact() = runBlocking {
        val bytes = "small deterministic LiteRT fixture".toByteArray()
        val store = testStore(bytes)

        val installed = store.importFromStreamForTest(ByteArrayInputStream(bytes))
        val inspected = store.inspect()

        assertEquals(bytes.size.toLong(), installed.manifest.sizeBytes)
        assertTrue(inspected is InstalledModelState.Ready)
        assertEquals(installed.inode, (inspected as InstalledModelState.Ready).model.inode)
    }

    @Test
    fun digestMismatchNeverCommitsCurrentPointer() = runBlocking {
        val expected = "expected".toByteArray()
        val store = testStore(expected)

        val error = expectModelStoreFailure {
            store.importFromStreamForTest(ByteArrayInputStream("tampered".toByteArray()))
        }

        assertEquals(ModelStoreErrorCode.INVALID_DIGEST, error.code)
        assertEquals(InstalledModelState.Missing, store.inspect())
    }

    @Test
    fun sizeMismatchNeverCommitsCurrentPointer() = runBlocking {
        val expected = "expected content".toByteArray()
        val store = testStore(expected)

        val error = expectModelStoreFailure {
            store.importFromStreamForTest(ByteArrayInputStream("short".toByteArray()))
        }

        assertEquals(ModelStoreErrorCode.INVALID_SIZE, error.code)
        assertEquals(InstalledModelState.Missing, store.inspect())
    }

    @Test
    fun twoStoreInstancesConvergeOnOneImmutableArtifact() = runBlocking {
        val bytes = ByteArray(128 * 1024) { index -> (index % 251).toByte() }
        val namespace = namespace()
        val manifest = manifestFor(bytes)
        val stores = List(2) {
            ModelArtifactStore.forTesting(context, manifest, namespace)
        }

        val installed = stores.map { store ->
            async {
                store.importFromStreamForTest(ByteArrayInputStream(bytes))
            }
        }.awaitAll()

        assertEquals(installed[0].inode, installed[1].inode)
        assertTrue(stores.all { it.inspect() is InstalledModelState.Ready })
    }

    @Test
    fun sourceFailureAndCancellationNeverCommitAPartial() = runBlocking {
        val bytes = ByteArray(2 * 1024 * 1024) { 7 }
        val sourceFailureStore = testStore(bytes)
        val sourceError = expectModelStoreFailure {
            sourceFailureStore.importFromStreamForTest(
                object : InputStream() {
                    override fun read(): Int = throw IOException("fixture")
                },
            )
        }
        assertEquals(ModelStoreErrorCode.STORAGE_FAILURE, sourceError.code)
        assertEquals(InstalledModelState.Missing, sourceFailureStore.inspect())

        val cancelledStore = testStore(bytes)
        try {
            cancelledStore.importFromStreamForTest(ByteArrayInputStream(bytes)) { copied, _ ->
                if (copied > 0) throw CancellationException("fixture")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation is never converted into an installed artifact.
        }
        assertEquals(InstalledModelState.Missing, cancelledStore.inspect())
    }

    @Test
    fun writableArtifactsAreRejected() = runBlocking {
        val bytes = "immutable fixture".toByteArray()
        val writableStore = testStore(bytes)
        val writable = writableStore.importFromStreamForTest(ByteArrayInputStream(bytes))
        Os.chmod(
            writable.file.absolutePath,
            OsConstants.S_IRUSR or OsConstants.S_IWUSR,
        )
        val writableState = writableStore.inspect()
        assertTrue(writableState is InstalledModelState.Rejected)
        assertEquals(
            ModelStoreErrorCode.INVALID_FILE_TYPE,
            (writableState as InstalledModelState.Rejected).code,
        )
    }

    @Test
    fun multiplyLinkedArtifactsAreRejectedWhenTheFilesystemAllowsHardLinks() = runBlocking {
        val bytes = "linked fixture".toByteArray()
        val linkedStore = testStore(bytes)
        val linked = linkedStore.importFromStreamForTest(ByteArrayInputStream(bytes))
        val sibling = java.io.File(linked.file.parentFile, ".test-extra-link-${UUID.randomUUID()}")
        try {
            Os.link(linked.file.absolutePath, sibling.absolutePath)
        } catch (error: ErrnoException) {
            assumeNoException("Filesystem policy does not permit app hard links", error)
        }
        try {
            val linkedState = linkedStore.inspect()
            assertTrue(linkedState is InstalledModelState.Rejected)
            assertEquals(
                ModelStoreErrorCode.INVALID_FILE_TYPE,
                (linkedState as InstalledModelState.Rejected).code,
            )
        } finally {
            Os.remove(sibling.absolutePath)
        }
    }

    @Test
    fun aRejectedSingleLinkArtifactCanBeSafelyReinstalled() = runBlocking {
        val bytes = "repair fixture".toByteArray()
        val store = testStore(bytes)
        val first = store.importFromStreamForTest(ByteArrayInputStream(bytes))
        Os.chmod(
            first.file.absolutePath,
            OsConstants.S_IRUSR or OsConstants.S_IWUSR,
        )

        val repaired = store.importFromStreamForTest(ByteArrayInputStream(bytes))

        assertEquals(
            OsConstants.S_IRUSR,
            Os.lstat(repaired.file.absolutePath).st_mode and 0x1FF,
        )
        assertTrue(store.inspect() is InstalledModelState.Ready)
    }

    private fun testStore(bytes: ByteArray): ModelArtifactStore =
        ModelArtifactStore.forTesting(context, manifestFor(bytes), namespace())

    private fun manifestFor(bytes: ByteArray): ModelManifest = ModelManifest(
        schemaVersion = 1,
        repository = "test/model",
        revision = "0123456789abcdef0123456789abcdef01234567",
        file = "fixture.litertlm",
        sizeBytes = bytes.size.toLong(),
        sha256 = sha256(bytes),
        litertLmVersion = "test",
        contextTokens = 32,
        maxOutputTokens = 8,
        supportsImageInput = false,
        supportsAudioInput = false,
    )

    private fun namespace(): String = UUID.randomUUID().toString().replace("-", "")

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private suspend fun expectModelStoreFailure(
        block: suspend () -> Unit,
    ): ModelStoreException = try {
        block()
        fail("Expected ModelStoreException")
        error("unreachable")
    } catch (error: ModelStoreException) {
        error
    }
}
