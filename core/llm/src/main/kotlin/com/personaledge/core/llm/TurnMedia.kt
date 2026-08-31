package com.personaledge.core.llm

/**
 * Bounded, Kotlin-validated media that may be prefilled into one top-level user turn.
 *
 * Media is the widest untrusted input this app accepts: a photographed note or a recorded voice
 * can carry text that reads like an instruction. Two boundaries contain that, and both live
 * outside this file:
 *
 * - a turn carrying media is given no Tool schema at all, so nothing it "asks for" can execute;
 * - the bytes never enter Room, transfer, diagnostics, or the model's textual context.
 *
 * What this file owns is narrower and mechanical: only a declared modality of the pinned artifact
 * may be sent, only a recognized container may be sent, and the payload must fall inside a fixed
 * byte and duration envelope before it reaches the JNI bridge.
 */
enum class TurnMediaKind {
    IMAGE,
    AUDIO,
}

/**
 * Containers this app is willing to hand to LiteRT-LM 0.16.1.
 *
 * The native side selects its decoder from the payload itself, so an unrecognized or mislabeled
 * container is a native decode attempt on unvalidated bytes. Every accepted format is therefore
 * confirmed by signature here, never by a caller-supplied MIME string.
 */
enum class TurnMediaFormat(val kind: TurnMediaKind) {
    JPEG(TurnMediaKind.IMAGE),
    PNG(TurnMediaKind.IMAGE),

    /** Linear PCM in a RIFF/WAVE container: the one audio container this app produces. */
    WAV(TurnMediaKind.AUDIO),
}

/**
 * Fixed envelope for one turn's media.
 *
 * The token figures are Google's published per-modality costs for Gemma 4, recorded so the byte
 * and duration caps can be read against the 4,096-token context rather than chosen arbitrarily.
 * They are documentation, not a measurement of this runtime: the real prefill cost is observed on
 * device through the conversation's own token count and recorded as content-free diagnostics.
 */
object TurnMediaBudget {
    /**
     * One attachment per turn.
     *
     * Not an API limit. A second image would roughly double an already large prefill inside a
     * 4,096-token context that also has to hold the turn preamble, the request, and the answer
     * allowance, and no device measurement exists for that yet.
     */
    const val MAX_ATTACHMENTS_PER_TURN: Int = 1

    const val MIN_IMAGE_BYTES: Int = 64
    const val MAX_IMAGE_BYTES: Int = 1_500_000

    const val MIN_AUDIO_BYTES: Int = 128
    const val MAX_AUDIO_BYTES: Int = 700_000
    const val MIN_AUDIO_MILLIS: Int = 400
    const val MAX_AUDIO_MILLIS: Int = 20_000

    /** Documented Gemma 4 cost of one image. */
    const val DOCUMENTED_IMAGE_CONTEXT_TOKENS: Int = 256

    /** Documented Gemma 4 cost of one second of audio; Gemma 3n's is 6.25. */
    const val DOCUMENTED_AUDIO_CONTEXT_TOKENS_PER_SECOND: Int = 25

    /**
     * The runtime's own documented ceiling, kept as the hard duration bound's justification.
     * [MAX_AUDIO_MILLIS] stays well under it so a clip cannot crowd out the request and answer.
     */
    const val DOCUMENTED_MAX_AUDIO_MILLIS: Int = 30_000

    /** Documented, not measured: the prefill cost this turn is expected to add. */
    fun documentedContextTokens(attachments: List<TurnMediaAttachment>): Int {
        var total = 0
        for (attachment in attachments) {
            total += when (attachment.kind) {
                TurnMediaKind.IMAGE -> DOCUMENTED_IMAGE_CONTEXT_TOKENS
                TurnMediaKind.AUDIO -> {
                    val millis = attachment.durationMillis ?: MAX_AUDIO_MILLIS
                    // Rounded up: a partial second still costs a whole frame group.
                    val seconds = (millis + 999) / 1_000
                    seconds * DOCUMENTED_AUDIO_CONTEXT_TOKENS_PER_SECOND
                }
            }
        }
        return total
    }

    fun allows(attachments: List<TurnMediaAttachment>): Boolean =
        attachments.size <= MAX_ATTACHMENTS_PER_TURN &&
            attachments.distinctBy(TurnMediaAttachment::kind).size == attachments.size
}

/**
 * One signature-verified media payload.
 *
 * Construction is the validation: there is no way to hold an instance whose container was never
 * recognized or whose size falls outside [TurnMediaBudget]. The bytes are copied in and copied
 * out so a caller cannot mutate a payload the runtime is already prefilling.
 */
class TurnMediaAttachment private constructor(
    val format: TurnMediaFormat,
    private val payload: ByteArray,
    /** Wall-clock length of an audio clip. Always null for an image. */
    val durationMillis: Int?,
) {
    val kind: TurnMediaKind get() = format.kind

    val byteCount: Int get() = payload.size

    internal fun copyBytes(): ByteArray = payload.copyOf()

    /** Content-free by construction: an attachment must never render its own payload. */
    override fun toString(): String =
        "TurnMediaAttachment(kind=$kind, format=$format, byteCount=$byteCount, " +
            "durationMillis=$durationMillis)"

    companion object {
        /** Returns null for anything this app is unwilling to hand to the native decoder. */
        fun imageOrNull(bytes: ByteArray): TurnMediaAttachment? {
            if (bytes.size < TurnMediaBudget.MIN_IMAGE_BYTES) return null
            if (bytes.size > TurnMediaBudget.MAX_IMAGE_BYTES) return null
            val format = imageFormatOrNull(bytes) ?: return null
            return TurnMediaAttachment(format, bytes.copyOf(), durationMillis = null)
        }

        /**
         * Accepts a RIFF/WAVE clip whose declared duration is inside the recorded envelope.
         *
         * [durationMillis] comes from the recorder, not from the header, because a truncated or
         * hand-built header could under-report a long clip and quietly overrun the context.
         */
        fun audioOrNull(bytes: ByteArray, durationMillis: Int): TurnMediaAttachment? {
            if (durationMillis < TurnMediaBudget.MIN_AUDIO_MILLIS) return null
            if (durationMillis > TurnMediaBudget.MAX_AUDIO_MILLIS) return null
            if (bytes.size < TurnMediaBudget.MIN_AUDIO_BYTES) return null
            if (bytes.size > TurnMediaBudget.MAX_AUDIO_BYTES) return null
            if (!isRiffWave(bytes)) return null
            return TurnMediaAttachment(TurnMediaFormat.WAV, bytes.copyOf(), durationMillis)
        }

        private fun imageFormatOrNull(bytes: ByteArray): TurnMediaFormat? = when {
            // JPEG SOI plus a marker byte; the trailing EOI is not required because a stripped
            // re-encode may be truncated by the encoder rather than by tampering.
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> TurnMediaFormat.JPEG

            bytes.size >= PNG_SIGNATURE.size &&
                PNG_SIGNATURE.indices.all { index -> bytes[index] == PNG_SIGNATURE[index] } ->
                TurnMediaFormat.PNG

            else -> null
        }

        private fun isRiffWave(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            return bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'A'.code.toByte() &&
                bytes[10] == 'V'.code.toByte() &&
                bytes[11] == 'E'.code.toByte()
        }

        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}

/**
 * Whether the pinned artifact declares the modalities [attachments] needs.
 *
 * The manifest is the trust root the store already verified, so this is a fail-closed check
 * against a declared capability rather than a guess from the file name or a native probe.
 */
fun ModelManifest.supportsMedia(attachments: List<TurnMediaAttachment>): Boolean =
    attachments.all { attachment -> supportsModality(attachment.kind) }

/** Whether the pinned artifact declares [kind] at all. */
fun ModelManifest.supportsModality(kind: TurnMediaKind): Boolean = when (kind) {
    TurnMediaKind.IMAGE -> supportsImageInput
    TurnMediaKind.AUDIO -> supportsAudioInput
}
