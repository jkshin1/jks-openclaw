package com.personaledge.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedImageIntentPolicyTest {
    @Test
    fun `a single shared photo is accepted`() {
        assertTrue(accepts(mimeType = "image/jpeg"))
        assertTrue(accepts(mimeType = "image/png"))
        assertTrue(accepts(mimeType = "image/heic"))
        // Gallery and camera share sheets routinely send the wildcard with a usable URI.
        assertTrue(accepts(mimeType = "image/*"))
        assertTrue(accepts(mimeType = "IMAGE/JPEG"))
    }

    @Test
    fun `a share without a stream is not an attachment`() {
        assertFalse(accepts(mimeType = "image/jpeg", hasStream = false))
    }

    @Test
    fun `non-image shares are refused`() {
        assertFalse(accepts(mimeType = "text/plain"))
        assertFalse(accepts(mimeType = "application/pdf"))
        assertFalse(accepts(mimeType = "video/mp4"))
        assertFalse(accepts(mimeType = null))
    }

    @Test
    fun `a multi-item share is refused rather than silently reduced`() {
        // A turn carries at most one attachment; picking "the first" would be a guess.
        assertFalse(
            accepts(action = SharedImageIntentPolicy.ACTION_SEND_MULTIPLE, mimeType = "image/jpeg"),
        )
        assertEquals(
            "첨부는 한 번에 하나만 보낼 수 있어 여러 장 공유는 받지 않았습니다.",
            SharedImageIntentPolicy.refusalMessageOrNull(
                action = SharedImageIntentPolicy.ACTION_SEND_MULTIPLE,
                mimeType = "image/jpeg",
                hasStream = true,
            ),
        )
    }

    @Test
    fun `an unrelated action is neither accepted nor explained`() {
        assertFalse(accepts(action = "android.intent.action.MAIN", mimeType = "image/jpeg"))
        assertNull(
            SharedImageIntentPolicy.refusalMessageOrNull(
                action = "android.intent.action.MAIN",
                mimeType = "image/jpeg",
                hasStream = true,
            ),
        )
    }

    @Test
    fun `an accepted share needs no explanation`() {
        assertNull(
            SharedImageIntentPolicy.refusalMessageOrNull(
                action = SharedImageIntentPolicy.ACTION_SEND,
                mimeType = "image/jpeg",
                hasStream = true,
            ),
        )
    }

    private fun accepts(
        action: String = SharedImageIntentPolicy.ACTION_SEND,
        mimeType: String?,
        hasStream: Boolean = true,
    ): Boolean = SharedImageIntentPolicy.acceptsSharedImage(action, mimeType, hasStream)
}
