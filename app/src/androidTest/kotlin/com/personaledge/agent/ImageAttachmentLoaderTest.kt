package com.personaledge.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.llm.TurnMediaBudget
import com.personaledge.core.llm.TurnMediaFormat
import com.personaledge.core.llm.TurnMediaKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The photo boundary, exercised against the real platform codecs.
 *
 * The two claims worth proving on a device rather than on the host are the ones the loader exists
 * for: the payload that reaches the model is bounded, and it carries none of the metadata the
 * camera attached to the original — GPS above all.
 */
@RunWith(AndroidJUnit4::class)
class ImageAttachmentLoaderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loader = ImageAttachmentLoader(context)
    private val scratch = File(context.cacheDir, "image-attachment-loader-test").apply {
        deleteRecursively()
        mkdirs()
    }

    @After
    fun clearScratch() {
        scratch.deleteRecursively()
    }

    @Test
    fun aLargePhotoIsDownscaledToTheModelsInputBound() = runBlocking {
        val source = writeJpeg(width = 3_200, height = 2_400)

        val loaded = requireLoaded(loader.loadOrNull(Uri.fromFile(source)))

        assertEquals(768, maxOf(loaded.pixelWidth, loaded.pixelHeight))
        assertEquals(576, minOf(loaded.pixelWidth, loaded.pixelHeight))
        assertEquals(TurnMediaKind.IMAGE, loaded.attachment.kind)
        assertEquals(TurnMediaFormat.JPEG, loaded.attachment.format)
        assertTrue(loaded.attachment.byteCount <= TurnMediaBudget.MAX_IMAGE_BYTES)
    }

    @Test
    fun aSmallPhotoIsNotEnlarged() = runBlocking {
        // Upscaling would invent detail the model would then describe as if it were observed.
        val source = writeJpeg(width = 320, height = 240)

        val loaded = requireLoaded(loader.loadOrNull(Uri.fromFile(source)))

        assertEquals(320, loaded.pixelWidth)
        assertEquals(240, loaded.pixelHeight)
    }

    @Test
    fun exifOrientationIsAppliedThenDiscarded() = runBlocking {
        // A portrait photo tagged ROTATE_90 must arrive upright; otherwise the model describes a
        // sideways scene and the owner has no way to tell why.
        val source = writeJpeg(width = 1_600, height = 1_200) { exif ->
            exif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
        }

        val loaded = requireLoaded(loader.loadOrNull(Uri.fromFile(source)))

        assertTrue(
            "A ROTATE_90 landscape source must come back portrait.",
            loaded.pixelHeight > loaded.pixelWidth,
        )
        assertEquals(768, loaded.pixelHeight)

        val encoded = File(scratch, "rotated-output.jpg")
        encoded.writeBytes(loaded.encodedBytes)
        assertEquals(
            "Orientation must be applied, not carried forward and applied twice.",
            ExifInterface.ORIENTATION_UNDEFINED,
            ExifInterface(encoded.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            ),
        )
    }

    @Test
    fun mirroredExifOrientationsAreAppliedToPixels() = runBlocking {
        val source = quadrantBitmap(width = 400, height = 300)
        val sourceBytes = encodeJpeg(source, quality = 100)
        val cases = listOf(
            OrientationCase(
                orientation = ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
                expected = listOf(Swatch.GREEN, Swatch.RED, Swatch.YELLOW, Swatch.BLUE),
                swapsDimensions = false,
            ),
            OrientationCase(
                orientation = ExifInterface.ORIENTATION_FLIP_VERTICAL,
                expected = listOf(Swatch.BLUE, Swatch.YELLOW, Swatch.RED, Swatch.GREEN),
                swapsDimensions = false,
            ),
            OrientationCase(
                orientation = ExifInterface.ORIENTATION_TRANSPOSE,
                expected = listOf(Swatch.RED, Swatch.BLUE, Swatch.GREEN, Swatch.YELLOW),
                swapsDimensions = true,
            ),
            OrientationCase(
                orientation = ExifInterface.ORIENTATION_TRANSVERSE,
                expected = listOf(Swatch.YELLOW, Swatch.GREEN, Swatch.BLUE, Swatch.RED),
                swapsDimensions = true,
            ),
        )

        cases.forEach { case ->
            val tagged = File(scratch, "orientation-${case.orientation}.jpg")
            tagged.writeBytes(sourceBytes)
            ExifInterface(tagged.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, case.orientation.toString())
                saveAttributes()
            }

            val loaded = requireLoaded(loader.loadOrNull(Uri.fromFile(tagged)))
            if (case.swapsDimensions) {
                assertEquals("orientation=${case.orientation}", 300, loaded.pixelWidth)
                assertEquals("orientation=${case.orientation}", 400, loaded.pixelHeight)
            } else {
                assertEquals("orientation=${case.orientation}", 400, loaded.pixelWidth)
                assertEquals("orientation=${case.orientation}", 300, loaded.pixelHeight)
            }
            assertQuadrants(loaded.encodedBytes, case)
        }
    }

    @Test
    fun bitmapOwnershipRecyclesEveryReplacedIntermediate() {
        val decoded = bitmap(width = 200, height = 120)
        val scaled = Bitmap.createScaledBitmap(decoded, 100, 60, true)
        val afterScale = replaceOwnedBitmap(decoded, scaled)

        assertTrue("The decoded predecessor must be released after scaling.", decoded.isRecycled)
        assertFalse(afterScale.isRecycled)

        val rotated = Bitmap.createBitmap(
            afterScale,
            0,
            0,
            afterScale.width,
            afterScale.height,
            android.graphics.Matrix().apply { setRotate(90f) },
            true,
        )
        val afterOrientation = replaceOwnedBitmap(afterScale, rotated)

        assertTrue("The scaled intermediate must be released after orientation.", scaled.isRecycled)
        assertFalse(afterOrientation.isRecycled)
        assertTrue(
            "An identity replacement must retain the only owned bitmap.",
            replaceOwnedBitmap(afterOrientation, afterOrientation) === afterOrientation,
        )
        assertFalse(afterOrientation.isRecycled)
        afterOrientation.recycle()
    }

    @Test
    fun locationAndDeviceMetadataNeverReachTheModel() = runBlocking {
        val source = writeJpeg(width = 1_200, height = 900) { exif ->
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,33/1,2604/100")
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "126/1,58/1,4128/100")
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            exif.setAttribute(ExifInterface.TAG_MAKE, "TestVendor")
            exif.setAttribute(ExifInterface.TAG_MODEL, "TestPhone")
            exif.setAttribute(ExifInterface.TAG_DATETIME, "2026:09:01 12:00:00")
        }
        // The fixture really does carry the tags, so their absence below is a stripped payload
        // rather than a fixture that never had them.
        val original = ExifInterface(source.absolutePath)
        assertNotNull(original.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNotNull(original.getAttribute(ExifInterface.TAG_MODEL))

        val loaded = requireLoaded(loader.loadOrNull(Uri.fromFile(source)))
        val encoded = File(scratch, "stripped-output.jpg")
        encoded.writeBytes(loaded.encodedBytes)

        val result = ExifInterface(encoded.absolutePath)
        assertNull(result.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(result.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertNull(result.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF))
        assertNull(result.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(result.getAttribute(ExifInterface.TAG_MODEL))
        assertNull(result.getAttribute(ExifInterface.TAG_DATETIME))
        assertFalse("TestVendor" in String(loaded.encodedBytes, Charsets.ISO_8859_1))
    }

    @Test
    fun theSameBoundsApplyToBytesFromTheCameraApp() = runBlocking {
        // The camera path hands over bytes rather than a URI, and must not be a looser lane.
        val bytes = writeJpeg(width = 3_200, height = 2_400).readBytes()

        val loaded = requireLoaded(loader.loadOrNull(bytes))

        assertEquals(768, maxOf(loaded.pixelWidth, loaded.pixelHeight))
        assertTrue(loaded.attachment.byteCount <= TurnMediaBudget.MAX_IMAGE_BYTES)
    }

    @Test
    fun anUnreadablePayloadProducesNoAttachment() = runBlocking {
        assertNull(loader.loadOrNull(ByteArray(4_096) { 0x42 }))
        assertNull(loader.loadOrNull(Uri.fromFile(File(scratch, "does-not-exist.jpg"))))

        val tooSmall = File(scratch, "tiny.jpg")
        tooSmall.writeBytes(encodeJpeg(bitmap(16, 16)))
        assertNull(loader.loadOrNull(Uri.fromFile(tooSmall)))
    }

    @Test
    fun aDetailedPhotoStaysInsideTheAttachmentBudget() = runBlocking {
        // High-frequency noise is the worst case for JPEG: it forces the quality ladder to step
        // down rather than accept an oversized payload.
        val noisy = bitmap(2_400, 2_400, noisy = true)
        val source = File(scratch, "noisy.jpg")
        source.writeBytes(encodeJpeg(noisy, quality = 100))

        val loaded = requireLoaded(loader.loadOrNull(Uri.fromFile(source)))

        assertTrue(loaded.attachment.byteCount <= TurnMediaBudget.MAX_IMAGE_BYTES)
        assertTrue(loaded.attachment.byteCount >= TurnMediaBudget.MIN_IMAGE_BYTES)
    }

    private fun requireLoaded(loaded: LoadedImageAttachment?): LoadedImageAttachment {
        assertNotNull("Expected the photo to become an attachment.", loaded)
        return loaded!!
    }

    private fun writeJpeg(
        width: Int,
        height: Int,
        tag: (ExifInterface) -> Unit = {},
    ): File {
        val file = File(scratch, "source-$width-$height-${System.nanoTime()}.jpg")
        file.writeBytes(encodeJpeg(bitmap(width, height)))
        ExifInterface(file.absolutePath).apply {
            tag(this)
            saveAttributes()
        }
        return file
    }

    private fun assertQuadrants(encoded: ByteArray, case: OrientationCase) {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size))
        try {
            val actual = listOf(
                swatchAt(bitmap, bitmap.width / 4, bitmap.height / 4),
                swatchAt(bitmap, bitmap.width * 3 / 4, bitmap.height / 4),
                swatchAt(bitmap, bitmap.width / 4, bitmap.height * 3 / 4),
                swatchAt(bitmap, bitmap.width * 3 / 4, bitmap.height * 3 / 4),
            )
            assertEquals("orientation=${case.orientation}", case.expected, actual)
        } finally {
            bitmap.recycle()
        }
    }

    private fun swatchAt(bitmap: Bitmap, x: Int, y: Int): Swatch {
        val pixel = bitmap.getPixel(x, y)
        return Swatch.entries.minBy { swatch ->
            val red = Color.red(pixel) - Color.red(swatch.color)
            val green = Color.green(pixel) - Color.green(swatch.color)
            val blue = Color.blue(pixel) - Color.blue(swatch.color)
            red * red + green * green + blue * blue
        }
    }

    private fun encodeJpeg(source: Bitmap, quality: Int = 90): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.JPEG, quality, output)
        source.recycle()
        return output.toByteArray()
    }

    private fun bitmap(width: Int, height: Int, noisy: Boolean = false): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        val paint = Paint()
        if (noisy) {
            var seed = 12_345
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    seed = seed * 1_103_515_245 + 12_345
                    paint.color = Color.rgb(
                        (seed ushr 16) and 0xFF,
                        (seed ushr 8) and 0xFF,
                        seed and 0xFF,
                    )
                    canvas.drawRect(
                        x.toFloat(),
                        y.toFloat(),
                        (x + 3).toFloat(),
                        (y + 3).toFloat(),
                        paint,
                    )
                    x += 3
                }
                y += 3
            }
        } else {
            // A distinguishable, non-uniform frame so a resize is visible and JPEG has real work.
            paint.color = Color.rgb(20, 90, 200)
            canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
            paint.color = Color.rgb(230, 120, 30)
            canvas.drawCircle(width * 0.75f, height * 0.5f, minOf(width, height) * 0.2f, paint)
        }
        return result
    }

    private fun quadrantBitmap(width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        listOf(
            Triple(0, 0, Swatch.RED),
            Triple(1, 0, Swatch.GREEN),
            Triple(0, 1, Swatch.BLUE),
            Triple(1, 1, Swatch.YELLOW),
        ).forEach { (column, row, swatch) ->
            paint.color = swatch.color
            canvas.drawRect(
                column * width / 2f,
                row * height / 2f,
                (column + 1) * width / 2f,
                (row + 1) * height / 2f,
                paint,
            )
        }
        return result
    }

    private data class OrientationCase(
        val orientation: Int,
        val expected: List<Swatch>,
        val swapsDimensions: Boolean,
    )

    private enum class Swatch(val color: Int) {
        RED(Color.rgb(230, 30, 30)),
        GREEN(Color.rgb(30, 200, 60)),
        BLUE(Color.rgb(30, 70, 230)),
        YELLOW(Color.rgb(235, 210, 30)),
    }
}
