package com.personaledge.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.personaledge.core.llm.TurnMediaAttachment
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LoadedImageAttachment(
    val attachment: TurnMediaAttachment,
    val pixelWidth: Int,
    val pixelHeight: Int,
    /**
     * The exact bytes handed to [TurnMediaAttachment], kept only until the caller stages the
     * attachment and drops this value. The attachment holds its own copy, so nothing retains
     * this array afterwards; it exists so the app can verify what it produced without widening
     * the runtime's payload accessor.
     */
    val encodedBytes: ByteArray,
) {
    /** Content-free by construction: a loaded photo must never render its own payload. */
    override fun toString(): String =
        "LoadedImageAttachment(pixelWidth=$pixelWidth, pixelHeight=$pixelHeight, " +
            "byteCount=${encodedBytes.size})"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Turns a picked or captured photo into a bounded, metadata-free JPEG.
 *
 * The re-encode is the privacy step, not just a size step. A camera file carries EXIF: GPS
 * coordinates, the device model, the capture timestamp, sometimes a thumbnail of the original
 * frame. Decoding to a bitmap and compressing a fresh JPEG drops all of it, so what reaches the
 * model is pixels and nothing else. Orientation is the one tag that must be honoured before it is
 * discarded, otherwise a portrait photo arrives sideways and the model describes it that way.
 *
 * [MediaAttachmentPolicy] owns the arithmetic; this class owns only the platform calls.
 */
internal class ImageAttachmentLoader(
    private val context: Context,
) {
    suspend fun loadOrNull(uri: Uri): LoadedImageAttachment? = withContext(Dispatchers.IO) {
        val bounds = readBoundsOrNull(uri) ?: return@withContext null
        val plan = MediaAttachmentPolicy.decodePlanOrNull(bounds.first, bounds.second)
            ?: return@withContext null
        val orientation = readOrientation(uri)
        val decoded = decodeOrNull(uri, plan.sampleSize) ?: return@withContext null
        encode(decoded, plan, orientation)
    }

    suspend fun loadOrNull(bytes: ByteArray): LoadedImageAttachment? = withContext(Dispatchers.IO) {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        val plan = MediaAttachmentPolicy
            .decodePlanOrNull(boundsOptions.outWidth, boundsOptions.outHeight)
            ?: return@withContext null
        val orientation = try {
            bytes.inputStream().use { stream -> ExifInterface(stream).normalizedOrientation() }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = plan.sampleSize },
            )
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        encode(decoded, plan, orientation)
    }

    private fun encode(
        decoded: Bitmap,
        plan: ImageDecodePlan,
        orientation: Int,
    ): LoadedImageAttachment? {
        var working = decoded
        try {
            working = replaceOwnedBitmap(working, scaled(working, plan))
            working = replaceOwnedBitmap(working, oriented(working, orientation))

            for (quality in MediaAttachmentPolicy.JPEG_QUALITY_LADDER) {
                val encoded = compressOrNull(working, quality) ?: continue
                if (!MediaAttachmentPolicy.fitsAttachmentBudget(encoded.size)) continue
                val attachment = TurnMediaAttachment.imageOrNull(encoded) ?: continue
                return LoadedImageAttachment(
                    attachment = attachment,
                    pixelWidth = working.width,
                    pixelHeight = working.height,
                    encodedBytes = encoded,
                )
            }
            return null
        } catch (_: Exception) {
            return null
        } catch (_: OutOfMemoryError) {
            // A photo this app cannot hold is a refusal, not a crash of the whole session.
            return null
        } finally {
            // Every successful replacement recycles its predecessor immediately. At this point
            // `working` is therefore the one and only bitmap still owned by the loader.
            working.recycle()
        }
    }

    private fun scaled(source: Bitmap, plan: ImageDecodePlan): Bitmap {
        // The subsampled bitmap is already near the target; this only trims it to the exact size.
        val width = minOf(plan.targetWidth, source.width)
        val height = minOf(plan.targetHeight, source.height)
        if (width == source.width && height == source.height) return source
        if (width < 1 || height < 1) return source
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun oriented(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    setRotate(180f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                else -> return source
            }
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun compressOrNull(bitmap: Bitmap, quality: Int): ByteArray? = try {
        ByteArrayOutputStream().use { output ->
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                output.toByteArray()
            } else {
                null
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun readBoundsOrNull(uri: Uri): Pair<Int, Int>? = openOrNull(uri)?.use { stream ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, options)
        options.outWidth.takeIf { it > 0 }?.let { width ->
            options.outHeight.takeIf { it > 0 }?.let { height -> width to height }
        }
    }

    private fun readOrientation(uri: Uri): Int = try {
        openOrNull(uri)?.use { stream -> ExifInterface(stream).normalizedOrientation() }
            ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun decodeOrNull(uri: Uri, sampleSize: Int): Bitmap? = try {
        openOrNull(uri)?.use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun openOrNull(uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (_: Exception) {
        null
    }

    private fun ExifInterface.normalizedOrientation(): Int =
        when (val orientation = getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
            -> orientation
            else -> ExifInterface.ORIENTATION_NORMAL
        }
}

/**
 * Transfers the loader's single ownership reference to [replacement].
 *
 * Bitmap transforms may return their input when no work is needed. When they allocate, releasing
 * the predecessor here is what prevents a scale-then-orient pipeline from losing its intermediate
 * scaled bitmap before the final `finally` block can see it.
 */
internal fun replaceOwnedBitmap(source: Bitmap, replacement: Bitmap): Bitmap {
    if (replacement !== source) source.recycle()
    return replacement
}
