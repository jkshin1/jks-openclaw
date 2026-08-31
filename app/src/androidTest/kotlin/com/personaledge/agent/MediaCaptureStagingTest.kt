package com.personaledge.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one place a captured photo exists as a file.
 *
 * These assertions are about lifetime, not correctness of the image: a staged capture must be
 * readable exactly once, must not survive the read, and must not accumulate across captures or
 * across a process that died between the camera returning and the app reading.
 */
@RunWith(AndroidJUnit4::class)
class MediaCaptureStagingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val staging = MediaCaptureStaging(context)
    private val directory = File(context.cacheDir, "media-capture")

    @Before
    fun clearBefore() = runBlocking { staging.clear() }

    @After
    fun clearAfter() = runBlocking { staging.clear() }

    @Test
    fun aPreparedCaptureIsGrantedThroughTheAppsOwnAuthority() = runBlocking {
        val uri = staging.prepareCaptureUri()

        assertNotNull("Preparing a capture target must succeed on a normal device.", uri)
        assertEquals("content", uri!!.scheme)
        assertEquals("${context.packageName}.mediacapture", uri.authority)
    }

    @Test
    fun aStagedCaptureIsReadableExactlyOnce() = runBlocking {
        val payload = ByteArray(2_048) { index -> (index % 251).toByte() }
        stage(payload)

        val first = staging.consumeCapture()
        assertNotNull(first)
        assertTrue(payload.contentEquals(first!!))

        // The bytes are in memory now; leaving a copy on disk would outlive the turn.
        assertNull("A staged capture must not survive its own read.", staging.consumeCapture())
    }

    @Test
    fun readingLeavesNothingOnDisk() = runBlocking {
        stage(ByteArray(512) { 0x11 })

        staging.consumeCapture()

        assertEquals(0, directory.listFiles()?.size ?: 0)
    }

    @Test
    fun preparingSweepsALeftoverFromAnEarlierProcess() = runBlocking {
        // A process that dies between the camera writing and the app reading leaves a file behind.
        stage(ByteArray(512) { 0x22 })
        assertTrue((directory.listFiles()?.size ?: 0) > 0)

        staging.prepareCaptureUri()

        assertEquals(
            "Preparing must start from an empty staging directory.",
            0,
            directory.listFiles()?.size ?: 0,
        )
    }

    @Test
    fun processStartSweepRemovesAnInterruptedCapture() {
        stage(ByteArray(512) { 0x2A })
        val target = requireNotNull(directory.listFiles()?.singleOrNull())
        assertTrue(target.setLastModified(PROCESS_START_NOW - TWO_HOURS_MILLIS))
        assertEquals(1, directory.listFiles()?.size ?: 0)

        // PersonalEdgeApplication invokes this same bounded sweep on its background executor.
        staging.sweepProcessStartLeftovers(PROCESS_START_NOW)

        assertEquals(0, directory.listFiles()?.size ?: 0)
    }

    @Test
    fun processStartSweepDoesNotRaceAFreshExternalCameraTarget() {
        stage(ByteArray(512) { 0x2B })
        val target = requireNotNull(directory.listFiles()?.singleOrNull())
        assertTrue(target.setLastModified(PROCESS_START_NOW))

        staging.sweepProcessStartLeftovers(PROCESS_START_NOW)

        assertEquals(1, directory.listFiles()?.size ?: 0)
    }

    @Test
    fun atMostOneCaptureIsEverStaged() = runBlocking {
        repeat(3) {
            staging.prepareCaptureUri()
            stage(ByteArray(256) { 0x33 })
            assertEquals(1, directory.listFiles()?.size ?: 0)
        }
    }

    @Test
    fun anAbsurdlyLargeCaptureIsRefusedRatherThanRead() = runBlocking {
        // A 40 MB ceiling on the raw camera file, well before any decode is attempted.
        stage(ByteArray(1_024) { 0x44 }, declaredLengthOverride = 64L * 1_024L * 1_024L)

        assertNull(staging.consumeCapture())
        assertEquals(0, directory.listFiles()?.size ?: 0)
    }

    @Test
    fun consumingWithNothingStagedReturnsNothing() = runBlocking {
        assertNull(staging.consumeCapture())
    }

    private fun stage(payload: ByteArray, declaredLengthOverride: Long? = null) {
        directory.mkdirs()
        val target = File(directory, "capture.jpg")
        if (declaredLengthOverride == null) {
            target.writeBytes(payload)
        } else {
            // Sparse write: a real oversized file without spending the bytes.
            java.io.RandomAccessFile(target, "rw").use { file ->
                file.setLength(declaredLengthOverride)
            }
        }
    }

    private companion object {
        const val PROCESS_START_NOW = 2_000_000_000_000L
        const val TWO_HOURS_MILLIS = 2L * 60L * 60L * 1_000L
    }
}
