package com.personaledge.agent

import com.personaledge.core.llm.TurnMediaBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAttachmentPolicyTest {
    @Test
    fun `an image already inside the bound is never enlarged`() {
        val plan = requirePlan(MediaAttachmentPolicy.decodePlanOrNull(640, 480))

        assertEquals(1, plan.sampleSize)
        assertEquals(640, plan.targetWidth)
        assertEquals(480, plan.targetHeight)
    }

    @Test
    fun `an image exactly at the bound is left alone`() {
        val plan = requirePlan(
            MediaAttachmentPolicy.decodePlanOrNull(
                MediaAttachmentPolicy.MAX_EDGE_PIXELS,
                MediaAttachmentPolicy.MAX_EDGE_PIXELS,
            ),
        )

        assertEquals(1, plan.sampleSize)
        assertEquals(768, plan.targetWidth)
        assertEquals(768, plan.targetHeight)
    }

    @Test
    fun `a large photo is subsampled then scaled to the long edge`() {
        // A 4:3 12 MP camera frame.
        val plan = requirePlan(MediaAttachmentPolicy.decodePlanOrNull(4_032, 3_024))

        assertEquals(768, maxOf(plan.targetWidth, plan.targetHeight))
        assertEquals(576, minOf(plan.targetWidth, plan.targetHeight))
        assertTrue("Subsampling must actually reduce the decode.", plan.sampleSize > 1)
    }

    @Test
    fun `subsampling never drops an edge below the target`() {
        // The exact scale step can only shrink, so a sample size that undershoots the target would
        // force an upscale of an already-subsampled bitmap.
        val widths = listOf(769, 900, 1_536, 1_537, 3_072, 4_032, 8_000, 12_000)
        for (width in widths) {
            val height = width * 3 / 4
            val plan = requirePlan(MediaAttachmentPolicy.decodePlanOrNull(width, height))

            assertTrue(
                "sampleSize must be a power of two, got ${plan.sampleSize} for $width.",
                plan.sampleSize > 0 && plan.sampleSize and (plan.sampleSize - 1) == 0,
            )
            val sampledLongEdge = maxOf(width, height) / plan.sampleSize
            assertTrue(
                "A $width x $height source subsampled to $sampledLongEdge would need upscaling.",
                sampledLongEdge >= MediaAttachmentPolicy.MAX_EDGE_PIXELS,
            )
        }
    }

    @Test
    fun `aspect ratio survives the resize`() {
        val plan = requirePlan(MediaAttachmentPolicy.decodePlanOrNull(3_000, 1_000))

        assertEquals(768, plan.targetWidth)
        assertEquals(256, plan.targetHeight)
    }

    @Test
    fun `an extremely tall document photo keeps its long edge bounded`() {
        val plan = requirePlan(MediaAttachmentPolicy.decodePlanOrNull(1_000, 6_000))

        assertEquals(768, plan.targetHeight)
        assertTrue(plan.targetWidth in 1..768)
    }

    @Test
    fun `an unusable source is refused rather than clamped`() {
        assertNull(MediaAttachmentPolicy.decodePlanOrNull(0, 0))
        assertNull(MediaAttachmentPolicy.decodePlanOrNull(-1, 100))
        assertNull(MediaAttachmentPolicy.decodePlanOrNull(31, 400))
        assertNull(MediaAttachmentPolicy.decodePlanOrNull(400, 31))
        assertNull(MediaAttachmentPolicy.decodePlanOrNull(20_001, 400))
        assertNull(MediaAttachmentPolicy.decodePlanOrNull(400, 20_001))
    }

    @Test
    fun `the quality ladder starts high and only steps down`() {
        val ladder = MediaAttachmentPolicy.JPEG_QUALITY_LADDER

        assertTrue(ladder.isNotEmpty())
        assertEquals(ladder.sortedDescending(), ladder)
        assertTrue("A document photo needs a high first attempt.", ladder.first() >= 90)
        assertTrue("Even the last rung must stay legible.", ladder.last() >= 50)
    }

    @Test
    fun `the attachment budget matches the runtime's own bounds`() {
        assertFalse(MediaAttachmentPolicy.fitsAttachmentBudget(0))
        assertFalse(
            MediaAttachmentPolicy.fitsAttachmentBudget(TurnMediaBudget.MIN_IMAGE_BYTES - 1),
        )
        assertTrue(MediaAttachmentPolicy.fitsAttachmentBudget(TurnMediaBudget.MIN_IMAGE_BYTES))
        assertTrue(MediaAttachmentPolicy.fitsAttachmentBudget(TurnMediaBudget.MAX_IMAGE_BYTES))
        assertFalse(
            MediaAttachmentPolicy.fitsAttachmentBudget(TurnMediaBudget.MAX_IMAGE_BYTES + 1),
        )
    }

    private fun requirePlan(plan: ImageDecodePlan?): ImageDecodePlan {
        assertNotNull("Expected a usable decode plan.", plan)
        return plan!!
    }
}
