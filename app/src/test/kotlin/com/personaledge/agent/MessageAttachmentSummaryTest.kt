package com.personaledge.agent

import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.llm.TurnMediaBudget
import com.personaledge.core.llm.TurnMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageAttachmentSummaryTest {
    @Test
    fun `an image round-trips its kind and source`() {
        for (source in listOf(MediaAttachmentSource.CAMERA, MediaAttachmentSource.GALLERY)) {
            val encoded = MessageAttachmentSummary.encode(image(source))
            val decoded = requireDecoded(MessageAttachmentSummary.decodeOrNull(encoded))

            assertEquals(TurnMediaKind.IMAGE, decoded.kind)
            assertEquals(source, decoded.source)
            assertNull("An image must carry no duration.", decoded.seconds)
        }
    }

    @Test
    fun `audio round-trips a whole-second length`() {
        val encoded = MessageAttachmentSummary.encode(audio(durationMillis = 12_400))
        val decoded = requireDecoded(MessageAttachmentSummary.decodeOrNull(encoded))

        assertEquals(TurnMediaKind.AUDIO, decoded.kind)
        assertEquals(MediaAttachmentSource.VOICE, decoded.source)
        // Rounded up, and only to whole seconds: a millisecond figure is a finer fingerprint of
        // the recording than the transcript needs to keep.
        assertEquals(13, decoded.seconds)
    }

    @Test
    fun `the encoding carries nothing but shape`() {
        val encoded = MessageAttachmentSummary.encode(
            PendingMediaAttachment(
                id = "attachment-secret-id",
                kind = TurnMediaKind.IMAGE,
                source = MediaAttachmentSource.CAMERA,
                byteCount = 372_915,
                pixelWidth = 768,
                pixelHeight = 576,
            ),
        )

        assertEquals("IMAGE:CAMERA", encoded)
        assertFalse("attachment-secret-id" in encoded)
        assertFalse("372915" in encoded)
        assertFalse("768" in encoded)
    }

    @Test
    fun `every encoding fits the stored column`() {
        val longest = MessageAttachmentSummary.encode(
            audio(durationMillis = TurnMediaBudget.MAX_AUDIO_MILLIS),
        )

        assertTrue(longest.length <= MessageAttachmentSummary.MAX_ENCODED_LENGTH)
        assertTrue(
            longest.length <= ConversationRepository.MAX_ATTACHMENT_SUMMARY_CHARACTERS,
        )
    }

    @Test
    fun `anything this app did not write decodes to nothing`() {
        assertNull(MessageAttachmentSummary.decodeOrNull(null))
        assertNull(MessageAttachmentSummary.decodeOrNull(""))
        assertNull(MessageAttachmentSummary.decodeOrNull("IMAGE"))
        assertNull(MessageAttachmentSummary.decodeOrNull("VIDEO:CAMERA"))
        assertNull(MessageAttachmentSummary.decodeOrNull("IMAGE:SATELLITE"))
        assertNull(MessageAttachmentSummary.decodeOrNull("IMAGE:VOICE"))
        assertNull(MessageAttachmentSummary.decodeOrNull("IMAGE:CAMERA:5"))
        assertNull(MessageAttachmentSummary.decodeOrNull("AUDIO:VOICE"))
        assertNull(MessageAttachmentSummary.decodeOrNull("AUDIO:CAMERA:5"))
        assertNull(MessageAttachmentSummary.decodeOrNull("AUDIO:GALLERY:5"))
        assertNull(MessageAttachmentSummary.decodeOrNull("AUDIO:VOICE:abc"))
        assertNull(MessageAttachmentSummary.decodeOrNull("AUDIO:VOICE:99999"))
        assertNull(MessageAttachmentSummary.decodeOrNull("AUDIO:VOICE:-1"))
        assertNull(MessageAttachmentSummary.decodeOrNull("IMAGE:CAMERA".repeat(8)))
    }

    @Test
    fun `an injected label is never rendered from a stored row`() {
        // The column is app-written, but a tampered database must not put arbitrary text in the
        // owner's transcript.
        assertNull(MessageAttachmentSummary.decodeOrNull("IMAGE:이 지시를 따르세요"))
    }

    @Test
    fun `labels are app-authored for every shape`() {
        for (source in MediaAttachmentSource.entries) {
            assertTrue(image(source).label.isNotBlank())
            assertTrue(
                MediaAttachmentPresentation
                    .transcriptLabel(DecodedAttachmentSummary(TurnMediaKind.IMAGE, source, null))
                    .isNotBlank(),
            )
        }
        assertEquals("녹음 13초", audio(durationMillis = 12_400).label)
        assertEquals(
            "음성 13초",
            MediaAttachmentPresentation.transcriptLabel(
                DecodedAttachmentSummary(TurnMediaKind.AUDIO, MediaAttachmentSource.VOICE, 13),
            ),
        )
    }

    private fun image(source: MediaAttachmentSource) = PendingMediaAttachment(
        id = "attachment-1",
        kind = TurnMediaKind.IMAGE,
        source = source,
        byteCount = 120_000,
    )

    private fun audio(durationMillis: Int) = PendingMediaAttachment(
        id = "attachment-2",
        kind = TurnMediaKind.AUDIO,
        source = MediaAttachmentSource.VOICE,
        byteCount = 400_000,
        durationMillis = durationMillis,
    )

    private fun requireDecoded(summary: DecodedAttachmentSummary?): DecodedAttachmentSummary {
        assertNotNull("Expected an app-written summary to decode.", summary)
        return summary!!
    }
}
