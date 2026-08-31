package com.personaledge.agent

import com.personaledge.core.llm.TurnMediaBudget

/**
 * The recording format and bounds the audio encoder expects, plus the WAV framing around it.
 *
 * Two facts drive every constant here. The runtime decodes audio with miniaudio and states that
 * only mono is supported, and the model's own documentation specifies 16 kHz single-channel input.
 * Recording directly in that shape means no resampling or downmixing step can quietly corrupt the
 * clip between the microphone and the encoder.
 */
internal object VoiceCapturePolicy {
    const val SAMPLE_RATE_HZ: Int = 16_000
    const val CHANNEL_COUNT: Int = 1
    const val BITS_PER_SAMPLE: Int = 16
    const val BYTES_PER_SAMPLE: Int = BITS_PER_SAMPLE / 8

    const val MIN_MILLIS: Int = TurnMediaBudget.MIN_AUDIO_MILLIS
    const val MAX_MILLIS: Int = TurnMediaBudget.MAX_AUDIO_MILLIS

    /**
     * Amplitude below which a clip is treated as nothing having been said.
     *
     * Sending near-silence wastes a full multimodal prefill and reliably produces a confident
     * hallucinated transcript, which is worse than telling the owner nothing was recorded.
     * Roughly -46 dBFS: quiet room noise stays below it, ordinary speech sits far above.
     */
    const val SILENCE_RMS_THRESHOLD: Int = 160

    const val BYTES_PER_SECOND: Int = SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE

    /** Hard cap on captured PCM, so a stuck recorder cannot grow without bound. */
    fun maxPcmByteCount(): Int = BYTES_PER_SECOND * MAX_MILLIS / 1_000

    fun durationMillis(pcmByteCount: Int): Int {
        val frames = pcmByteCount / (CHANNEL_COUNT * BYTES_PER_SAMPLE)
        return (frames.toLong() * 1_000L / SAMPLE_RATE_HZ).toInt()
    }

    fun pcmByteCountFor(millis: Int): Int =
        millis.toLong().times(BYTES_PER_SECOND).div(1_000L).toInt() and 1.inv()

    /**
     * Root-mean-square amplitude of little-endian 16-bit mono PCM.
     *
     * An odd trailing byte is ignored rather than treated as a sample; a partial frame is a
     * truncated read, not audio.
     */
    fun rootMeanSquare(pcm: ByteArray): Int {
        val frameCount = pcm.size / 2
        if (frameCount == 0) return 0
        var sumOfSquares = 0.0
        for (index in 0 until frameCount) {
            val low = pcm[index * 2].toInt() and 0xFF
            val high = pcm[index * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sumOfSquares += sample.toDouble() * sample.toDouble()
        }
        return Math.sqrt(sumOfSquares / frameCount).toInt()
    }

    fun isLikelySilent(pcm: ByteArray): Boolean = rootMeanSquare(pcm) < SILENCE_RMS_THRESHOLD
}

/**
 * Minimal RIFF/WAVE framing for the PCM this app records.
 *
 * Written by hand rather than through `MediaRecorder` so the exact sample rate, channel count and
 * bit depth in the header are the ones [VoiceCapturePolicy] declares, and so the encoder is a pure
 * function the host can test byte for byte.
 */
internal object WavEncoder {
    const val HEADER_BYTES: Int = 44

    /** Returns null when the PCM is empty, misaligned, or larger than the recording envelope. */
    fun encodeOrNull(pcm: ByteArray): ByteArray? {
        if (pcm.isEmpty()) return null
        if (pcm.size % (VoiceCapturePolicy.CHANNEL_COUNT * VoiceCapturePolicy.BYTES_PER_SAMPLE) != 0) {
            return null
        }
        if (pcm.size > VoiceCapturePolicy.maxPcmByteCount()) return null

        val byteRate = VoiceCapturePolicy.BYTES_PER_SECOND
        val blockAlign = VoiceCapturePolicy.CHANNEL_COUNT * VoiceCapturePolicy.BYTES_PER_SAMPLE
        val output = ByteArray(HEADER_BYTES + pcm.size)

        writeAscii(output, 0, "RIFF")
        writeLittleEndianInt(output, 4, 36 + pcm.size)
        writeAscii(output, 8, "WAVE")

        writeAscii(output, 12, "fmt ")
        writeLittleEndianInt(output, 16, 16)
        writeLittleEndianShort(output, 20, 1) // PCM, uncompressed.
        writeLittleEndianShort(output, 22, VoiceCapturePolicy.CHANNEL_COUNT)
        writeLittleEndianInt(output, 24, VoiceCapturePolicy.SAMPLE_RATE_HZ)
        writeLittleEndianInt(output, 28, byteRate)
        writeLittleEndianShort(output, 32, blockAlign)
        writeLittleEndianShort(output, 34, VoiceCapturePolicy.BITS_PER_SAMPLE)

        writeAscii(output, 36, "data")
        writeLittleEndianInt(output, 40, pcm.size)
        pcm.copyInto(output, HEADER_BYTES)
        return output
    }

    private fun writeAscii(target: ByteArray, offset: Int, value: String) {
        for (index in value.indices) {
            target[offset + index] = value[index].code.toByte()
        }
    }

    private fun writeLittleEndianInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeLittleEndianShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
