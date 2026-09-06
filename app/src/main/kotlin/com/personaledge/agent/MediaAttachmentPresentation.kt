package com.personaledge.agent

import com.personaledge.core.data.MessageRole
import com.personaledge.core.data.StoredMessage
import com.personaledge.core.llm.TurnMediaKind

/** Where an attachment came from. Shown to the owner and recorded content-free in the transcript. */
enum class MediaAttachmentSource {
    CAMERA,
    GALLERY,

    /** Sent in from another app's share sheet. */
    SHARE,
    VOICE,
}

/**
 * The composer's view of one staged attachment.
 *
 * Deliberately metadata only. The validated payload stays in a private ViewModel field, so no
 * image or audio byte can reach UI state, a state snapshot, a crash report, or a recomposition
 * trace.
 */
data class PendingMediaAttachment(
    val id: String,
    val kind: TurnMediaKind,
    val source: MediaAttachmentSource,
    val byteCount: Int,
    val durationMillis: Int? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
) {
    val label: String get() = MediaAttachmentPresentation.chipLabel(this)
}

/**
 * Content-free transcript record of an attachment.
 *
 * A restored conversation has to be able to say "a photo was attached here" without the photo
 * still existing anywhere. This is the entire durable trace: a kind, a source, and for audio a
 * whole-second length. No pixels, no samples, no file name, no path, no URI, no timestamp.
 */
internal object MessageAttachmentSummary {
    private const val SEPARATOR = ':'
    const val MAX_ENCODED_LENGTH = 32

    fun encode(attachment: PendingMediaAttachment): String = buildString {
        append(attachment.kind.name)
        append(SEPARATOR)
        append(attachment.source.name)
        if (attachment.kind == TurnMediaKind.AUDIO) {
            append(SEPARATOR)
            // Whole seconds only: a millisecond figure is a finer fingerprint than this needs.
            append(((attachment.durationMillis ?: 0) + 999) / 1_000)
        }
    }

    /** Returns null for anything this app did not write, so a hand-edited row renders nothing. */
    fun decodeOrNull(encoded: String?): DecodedAttachmentSummary? {
        if (encoded == null || encoded.length > MAX_ENCODED_LENGTH) return null
        val parts = encoded.split(SEPARATOR)
        if (parts.size !in 2..3) return null
        val kind = TurnMediaKind.entries.firstOrNull { it.name == parts[0] } ?: return null
        val source = MediaAttachmentSource.entries.firstOrNull { it.name == parts[1] } ?: return null
        if (kind == TurnMediaKind.IMAGE && source !in IMAGE_SOURCES) return null
        if (kind == TurnMediaKind.AUDIO && source != MediaAttachmentSource.VOICE) return null
        val seconds = when {
            kind == TurnMediaKind.AUDIO && parts.size == 3 ->
                parts[2].toIntOrNull()?.takeIf { it in 0..3_600 } ?: return null

            kind == TurnMediaKind.AUDIO -> return null
            parts.size == 3 -> return null
            else -> null
        }
        return DecodedAttachmentSummary(kind, source, seconds)
    }

    private val IMAGE_SOURCES = setOf(
        MediaAttachmentSource.CAMERA,
        MediaAttachmentSource.GALLERY,
    )
}

internal data class DecodedAttachmentSummary(
    val kind: TurnMediaKind,
    val source: MediaAttachmentSource,
    val seconds: Int?,
)

/** App-authored Korean labels. Never derived from model output or from the media itself. */
internal object MediaAttachmentPresentation {
    fun chipLabel(attachment: PendingMediaAttachment): String = when (attachment.kind) {
        TurnMediaKind.IMAGE -> when (attachment.source) {
            MediaAttachmentSource.CAMERA -> "촬영한 사진"
            MediaAttachmentSource.GALLERY -> "선택한 사진"
            MediaAttachmentSource.SHARE -> "공유받은 사진"
            MediaAttachmentSource.VOICE -> "사진"
        }

        TurnMediaKind.AUDIO -> {
            val seconds = ((attachment.durationMillis ?: 0) + 999) / 1_000
            "녹음 ${seconds}초"
        }
    }

    fun transcriptLabel(summary: DecodedAttachmentSummary): String = when (summary.kind) {
        TurnMediaKind.IMAGE -> when (summary.source) {
            MediaAttachmentSource.CAMERA -> "사진 1장(촬영)"
            MediaAttachmentSource.GALLERY -> "사진 1장(선택)"
            MediaAttachmentSource.SHARE -> "사진 1장(공유)"
            MediaAttachmentSource.VOICE -> "사진 1장"
        }

        TurnMediaKind.AUDIO -> "음성 ${summary.seconds ?: 0}초"
    }
}

/**
 * Renders durable attachment shape for model context without ever exposing an encoded value.
 *
 * Only a USER row can own an attachment. Unknown/tampered codes and attachment codes on another
 * role contribute nothing. The Korean label is app-authored from the closed codec and contains no
 * payload, path, URI, byte count, dimensions, or sub-second timing.
 */
internal fun StoredMessage.contentFreeContextText(): String {
    val label = if (role == MessageRole.USER) {
        MessageAttachmentSummary
            .decodeOrNull(attachmentSummary)
            ?.let(MediaAttachmentPresentation::transcriptLabel)
    } else {
        null
    }
    if (label == null) return text
    val attachmentText = "[첨부: $label]"
    return if (text.isBlank()) attachmentText else "$text $attachmentText"
}
