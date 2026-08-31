package com.personaledge.agent

import com.personaledge.core.llm.TurnMediaAttachment
import com.personaledge.core.llm.TurnMediaBudget
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCapturePolicyTest {
    @Test
    fun `the recording format is the one the audio encoder documents`() {
        // 16 kHz mono 16-bit: the runtime states only mono is supported, and the model's own
        // documentation specifies a 16 kHz single channel.
        assertEquals(16_000, VoiceCapturePolicy.SAMPLE_RATE_HZ)
        assertEquals(1, VoiceCapturePolicy.CHANNEL_COUNT)
        assertEquals(16, VoiceCapturePolicy.BITS_PER_SAMPLE)
        assertEquals(32_000, VoiceCapturePolicy.BYTES_PER_SECOND)
    }

    @Test
    fun `duration and byte count are inverses`() {
        assertEquals(0, VoiceCapturePolicy.durationMillis(0))
        assertEquals(1_000, VoiceCapturePolicy.durationMillis(32_000))
        assertEquals(500, VoiceCapturePolicy.durationMillis(16_000))
        assertEquals(32_000, VoiceCapturePolicy.pcmByteCountFor(1_000))
        assertEquals(16_000, VoiceCapturePolicy.pcmByteCountFor(500))
    }

    @Test
    fun `a byte count derived from a duration is always frame aligned`() {
        for (millis in listOf(1, 7, 333, 999, 1_001, 19_999)) {
            assertEquals(
                "A $millis ms buffer must not end mid-frame.",
                0,
                VoiceCapturePolicy.pcmByteCountFor(millis) % 2,
            )
        }
    }

    @Test
    fun `the recording cap fits the attachment envelope once framed`() {
        val maxPcm = VoiceCapturePolicy.maxPcmByteCount()

        assertEquals(VoiceCapturePolicy.MAX_MILLIS, VoiceCapturePolicy.durationMillis(maxPcm))
        assertTrue(
            "A full-length clip plus its WAV header must still be attachable.",
            maxPcm + WavEncoder.HEADER_BYTES <= TurnMediaBudget.MAX_AUDIO_BYTES,
        )
    }

    @Test
    fun `speech-level audio is not treated as silence`() {
        val speech = tone(amplitude = 6_000, millis = 1_000)

        assertFalse(VoiceCapturePolicy.isLikelySilent(speech))
        assertTrue(VoiceCapturePolicy.rootMeanSquare(speech) > VoiceCapturePolicy.SILENCE_RMS_THRESHOLD)
    }

    @Test
    fun `a quiet room is treated as silence`() {
        // Sending near-silence spends a whole multimodal prefill to hallucinate a transcript.
        assertTrue(VoiceCapturePolicy.isLikelySilent(ByteArray(32_000)))
        assertTrue(VoiceCapturePolicy.isLikelySilent(tone(amplitude = 60, millis = 1_000)))
    }

    @Test
    fun `an empty or partial buffer has no amplitude`() {
        assertEquals(0, VoiceCapturePolicy.rootMeanSquare(ByteArray(0)))
        // A single trailing byte is a truncated read, not a sample.
        assertEquals(0, VoiceCapturePolicy.rootMeanSquare(ByteArray(1) { 0x7F }))
    }

    @Test
    fun `the wav header describes the recorded format exactly`() {
        val pcm = tone(amplitude = 4_000, millis = 250)
        val wav = requireWav(WavEncoder.encodeOrNull(pcm))

        assertEquals(WavEncoder.HEADER_BYTES + pcm.size, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals(36 + pcm.size, littleEndianInt(wav, 4))
        assertEquals(16, littleEndianInt(wav, 16))
        assertEquals(1, littleEndianShort(wav, 20))
        assertEquals(1, littleEndianShort(wav, 22))
        assertEquals(16_000, littleEndianInt(wav, 24))
        assertEquals(32_000, littleEndianInt(wav, 28))
        assertEquals(2, littleEndianShort(wav, 32))
        assertEquals(16, littleEndianShort(wav, 34))
        assertEquals(pcm.size, littleEndianInt(wav, 40))
    }

    @Test
    fun `the wav body is the recorded samples unchanged`() {
        val pcm = tone(amplitude = 3_000, millis = 100)
        val wav = requireWav(WavEncoder.encodeOrNull(pcm))

        assertTrue(pcm.contentEquals(wav.copyOfRange(WavEncoder.HEADER_BYTES, wav.size)))
    }

    @Test
    fun `unusable pcm produces no wav`() {
        assertNull(WavEncoder.encodeOrNull(ByteArray(0)))
        // An odd length cannot be whole 16-bit frames.
        assertNull(WavEncoder.encodeOrNull(ByteArray(1_001)))
        assertNull(WavEncoder.encodeOrNull(ByteArray(VoiceCapturePolicy.maxPcmByteCount() + 2)))
    }

    @Test
    fun `an encoded clip is accepted by the runtime attachment contract`() {
        val pcm = tone(amplitude = 5_000, millis = 2_000)
        val wav = requireWav(WavEncoder.encodeOrNull(pcm))

        val attachment = TurnMediaAttachment.audioOrNull(
            bytes = wav,
            durationMillis = VoiceCapturePolicy.durationMillis(pcm.size),
        )

        assertNotNull("The recorder's own output must satisfy the runtime contract.", attachment)
        assertEquals(2_000, attachment!!.durationMillis)
    }

    @Test
    fun `a maximum-length clip is still accepted end to end`() {
        val pcm = tone(amplitude = 5_000, millis = VoiceCapturePolicy.MAX_MILLIS)
        val wav = requireWav(WavEncoder.encodeOrNull(pcm))

        assertNotNull(
            TurnMediaAttachment.audioOrNull(wav, VoiceCapturePolicy.durationMillis(pcm.size)),
        )
    }

    private fun tone(amplitude: Int, millis: Int): ByteArray {
        val bytes = ByteArray(VoiceCapturePolicy.pcmByteCountFor(millis))
        val frames = bytes.size / 2
        for (index in 0 until frames) {
            val sample = (amplitude * sin(index * 0.05)).toInt()
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun requireWav(wav: ByteArray?): ByteArray {
        assertNotNull("Expected the PCM to be framed.", wav)
        return wav!!
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
