package com.personaledge.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnMediaTest {
    @Test
    fun `accepts a jpeg payload inside the byte envelope`() {
        val attachment = requireAttachment(TurnMediaAttachment.imageOrNull(jpeg(4_096)))

        assertEquals(TurnMediaKind.IMAGE, attachment.kind)
        assertEquals(TurnMediaFormat.JPEG, attachment.format)
        assertEquals(4_096, attachment.byteCount)
        assertNull(attachment.durationMillis)
    }

    @Test
    fun `accepts a png payload`() {
        val attachment = requireAttachment(TurnMediaAttachment.imageOrNull(png(1_024)))

        assertEquals(TurnMediaFormat.PNG, attachment.format)
    }

    @Test
    fun `rejects an unrecognized image container`() {
        // Native selects its decoder from the payload, so anything unrecognized never leaves here.
        assertNull(TurnMediaAttachment.imageOrNull(ByteArray(4_096) { 0x42 }))
        assertNull(TurnMediaAttachment.imageOrNull("GIF89a".toByteArray() + ByteArray(4_096)))
        assertNull(TurnMediaAttachment.imageOrNull(riffWave(4_096)))
    }

    @Test
    fun `rejects an image outside the byte envelope`() {
        assertNull(TurnMediaAttachment.imageOrNull(jpeg(TurnMediaBudget.MIN_IMAGE_BYTES - 1)))
        assertNull(TurnMediaAttachment.imageOrNull(jpeg(TurnMediaBudget.MAX_IMAGE_BYTES + 1)))
        assertNotNull(TurnMediaAttachment.imageOrNull(jpeg(TurnMediaBudget.MAX_IMAGE_BYTES)))
    }

    @Test
    fun `accepts a riff wave clip inside the duration envelope`() {
        val attachment = requireAttachment(
            TurnMediaAttachment.audioOrNull(riffWave(32_044), durationMillis = 1_000),
        )

        assertEquals(TurnMediaKind.AUDIO, attachment.kind)
        assertEquals(TurnMediaFormat.WAV, attachment.format)
        assertEquals(1_000, attachment.durationMillis)
    }

    @Test
    fun `rejects audio that is not a riff wave container`() {
        assertNull(TurnMediaAttachment.audioOrNull(jpeg(32_044), durationMillis = 1_000))
        val wrongForm = riffWave(32_044).also { bytes -> bytes[9] = 'X'.code.toByte() }
        assertNull(TurnMediaAttachment.audioOrNull(wrongForm, durationMillis = 1_000))
    }

    @Test
    fun `rejects audio outside the duration envelope`() {
        val bytes = riffWave(64_044)

        assertNull(
            TurnMediaAttachment.audioOrNull(bytes, TurnMediaBudget.MIN_AUDIO_MILLIS - 1),
        )
        assertNull(
            TurnMediaAttachment.audioOrNull(bytes, TurnMediaBudget.MAX_AUDIO_MILLIS + 1),
        )
        assertNotNull(
            TurnMediaAttachment.audioOrNull(bytes, TurnMediaBudget.MAX_AUDIO_MILLIS),
        )
    }

    @Test
    fun `rejects audio outside the byte envelope even when the duration is plausible`() {
        // A short declared duration must not smuggle a large payload into the prefill.
        assertNull(
            TurnMediaAttachment.audioOrNull(
                riffWave(TurnMediaBudget.MAX_AUDIO_BYTES + 1),
                durationMillis = 1_000,
            ),
        )
    }

    @Test
    fun `copies payload bytes in and out`() {
        val source = jpeg(256)
        val attachment = requireAttachment(TurnMediaAttachment.imageOrNull(source))

        source[100] = 0x7F
        val first = attachment.copyBytes()
        first[101] = 0x7F

        val second = attachment.copyBytes()
        assertEquals(0x00.toByte(), second[100])
        assertEquals(0x00.toByte(), second[101])
    }

    @Test
    fun `toString never renders the payload`() {
        val rendered = requireAttachment(TurnMediaAttachment.imageOrNull(jpeg(512))).toString()

        assertTrue("byteCount=512" in rendered)
        assertFalse("payload" in rendered)
        assertFalse("bytes" in rendered)
    }

    @Test
    fun `budget allows at most one attachment of distinct kinds`() {
        val image = requireAttachment(TurnMediaAttachment.imageOrNull(jpeg(512)))
        val audio = requireAttachment(TurnMediaAttachment.audioOrNull(riffWave(32_044), 1_000))

        assertTrue(TurnMediaBudget.allows(emptyList()))
        assertTrue(TurnMediaBudget.allows(listOf(image)))
        assertTrue(TurnMediaBudget.allows(listOf(audio)))
        assertFalse(TurnMediaBudget.allows(listOf(image, audio)))
        assertFalse(TurnMediaBudget.allows(listOf(image, image)))
    }

    @Test
    fun `documented context tokens round a partial second up`() {
        val image = requireAttachment(TurnMediaAttachment.imageOrNull(jpeg(512)))

        assertEquals(0, TurnMediaBudget.documentedContextTokens(emptyList()))
        assertEquals(256, TurnMediaBudget.documentedContextTokens(listOf(image)))

        val oneSecond = requireAttachment(
            TurnMediaAttachment.audioOrNull(riffWave(32_044), 1_000),
        )
        assertEquals(25, TurnMediaBudget.documentedContextTokens(listOf(oneSecond)))

        val justOver = requireAttachment(
            TurnMediaAttachment.audioOrNull(riffWave(32_100), 1_001),
        )
        assertEquals(50, TurnMediaBudget.documentedContextTokens(listOf(justOver)))
    }

    @Test
    fun `the longest permitted clip stays well inside the pinned context`() {
        val longest = requireAttachment(
            TurnMediaAttachment.audioOrNull(
                riffWave(TurnMediaBudget.MAX_AUDIO_BYTES),
                TurnMediaBudget.MAX_AUDIO_MILLIS,
            ),
        )

        // 20 s x 25 tokens leaves most of the 4,096-token context for the request and answer.
        assertEquals(500, TurnMediaBudget.documentedContextTokens(listOf(longest)))
        assertTrue(TurnMediaBudget.MAX_AUDIO_MILLIS < TurnMediaBudget.DOCUMENTED_MAX_AUDIO_MILLIS)
    }

    @Test
    fun `a manifest reports each declared modality`() {
        val textOnly = manifest(image = false, audio = false)
        assertFalse(textOnly.supportsModality(TurnMediaKind.IMAGE))
        assertFalse(textOnly.supportsModality(TurnMediaKind.AUDIO))

        val imageOnly = manifest(image = true, audio = false)
        assertTrue(imageOnly.supportsModality(TurnMediaKind.IMAGE))
        assertFalse(imageOnly.supportsModality(TurnMediaKind.AUDIO))
    }

    @Test
    fun `a manifest gates media by declared modality`() {
        val image = requireAttachment(TurnMediaAttachment.imageOrNull(jpeg(512)))
        val audio = requireAttachment(TurnMediaAttachment.audioOrNull(riffWave(32_044), 1_000))

        val textOnly = manifest(image = false, audio = false)
        assertTrue(textOnly.supportsMedia(emptyList()))
        assertFalse(textOnly.supportsMedia(listOf(image)))
        assertFalse(textOnly.supportsMedia(listOf(audio)))

        val imageOnly = manifest(image = true, audio = false)
        assertTrue(imageOnly.supportsMedia(listOf(image)))
        assertFalse(imageOnly.supportsMedia(listOf(audio)))

        val both = manifest(image = true, audio = true)
        assertTrue(both.supportsMedia(listOf(image)))
        assertTrue(both.supportsMedia(listOf(audio)))
    }

    private fun requireAttachment(attachment: TurnMediaAttachment?): TurnMediaAttachment {
        assertNotNull("Expected the payload to be accepted.", attachment)
        return attachment!!
    }

    private fun manifest(image: Boolean, audio: Boolean): ModelManifest = ModelManifest(
        schemaVersion = 1,
        repository = "test/model",
        revision = "0123456789abcdef0123456789abcdef01234567",
        file = "fixture.litertlm",
        sizeBytes = 1_024,
        sha256 = "0".repeat(64),
        litertLmVersion = "test",
        contextTokens = 4_096,
        maxOutputTokens = 1_024,
        supportsImageInput = image,
        supportsAudioInput = audio,
    )

    private fun jpeg(size: Int): ByteArray = ByteArray(size).also { bytes ->
        if (bytes.size >= 3) {
            bytes[0] = 0xFF.toByte()
            bytes[1] = 0xD8.toByte()
            bytes[2] = 0xFF.toByte()
        }
    }

    private fun png(size: Int): ByteArray = ByteArray(size).also { bytes ->
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        signature.copyInto(bytes)
    }

    private fun riffWave(size: Int): ByteArray = ByteArray(size).also { bytes ->
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WAVE".toByteArray().copyInto(bytes, 8)
    }
}
