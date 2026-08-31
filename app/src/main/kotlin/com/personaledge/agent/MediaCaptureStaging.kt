package com.personaledge.agent

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only place a captured photo is allowed to exist as a file.
 *
 * The camera app cannot hand bytes back in process: it writes to a `content://` URI this app
 * supplies. That file is the shortest-lived artifact in the system, so it gets its own directory
 * under the cache, one file at a time. Deletion is attempted as soon as the bytes have been read,
 * on the next process start, and before every new capture, so an interrupted capture is not
 * mistaken for current input. As with every cache file, the OS remains the final reclamation
 * boundary if the filesystem refuses an individual delete.
 *
 * Recorded audio never comes here at all; it stays in memory from `AudioRecord` to the attachment.
 */
internal class MediaCaptureStaging(
    private val context: Context,
) {
    private val directory: File
        get() = File(context.cacheDir, DIRECTORY_NAME)

    /**
     * Clears any leftovers and returns a fresh capture target, or null when storage refuses.
     *
     * A single fixed file name is intentional: it makes "at most one staged capture exists" a
     * property of the filesystem rather than of bookkeeping this class would have to get right.
     */
    suspend fun prepareCaptureUri(): Uri? = withContext(Dispatchers.IO) {
        val target = try {
            clearCaptureDirectory()
            if (!directory.exists() && !directory.mkdirs()) return@withContext null
            File(directory, CAPTURE_FILE_NAME)
        } catch (_: Exception) {
            return@withContext null
        }
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.mediacapture", target)
        } catch (_: Exception) {
            null
        }
    }

    /** Reads the staged capture and deletes it, whether or not the read succeeded. */
    suspend fun consumeCapture(): ByteArray? = withContext(Dispatchers.IO) {
        val target = File(directory, CAPTURE_FILE_NAME)
        try {
            if (!target.isFile) return@withContext null
            if (target.length() > MAX_STAGED_BYTES) return@withContext null
            target.readBytes()
        } catch (_: Exception) {
            null
        } finally {
            clearCaptureDirectory()
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) { clearCaptureDirectory() }
    }

    /**
     * Best-effort process-start sweep. A fresh target may still belong to an external camera that
     * is returning into a recreated process, so only old leftovers are deleted here. A new
     * [prepareCaptureUri] is an unambiguous replacement boundary and clears every prior file.
     */
    internal fun sweepProcessStartLeftovers(nowEpochMillis: Long = System.currentTimeMillis()) {
        val staleBefore = nowEpochMillis - PROCESS_START_STALE_AGE_MILLIS
        try {
            directory.listFiles()?.forEach { file ->
                if (file.lastModified() <= staleBefore) file.delete()
            }
        } catch (_: Exception) {
            // The next process start or capture preparation retries this bounded directory.
        }
    }

    private fun clearCaptureDirectory() {
        try {
            directory.listFiles()?.forEach { file -> file.delete() }
        } catch (_: Exception) {
            // A staging file that refuses to delete is not worth failing a turn over; the next
            // prepare overwrites the single fixed name anyway.
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "media-capture"
        const val CAPTURE_FILE_NAME = "capture.jpg"
        const val PROCESS_START_STALE_AGE_MILLIS = 60L * 60L * 1_000L

        /**
         * A generous bound on a raw camera file before any decoding is attempted. This is not the
         * attachment budget; the loader downscales far below it.
         */
        const val MAX_STAGED_BYTES = 40L * 1_024L * 1_024L
    }
}
