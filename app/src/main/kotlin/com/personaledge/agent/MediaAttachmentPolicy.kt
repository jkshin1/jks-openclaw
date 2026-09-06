package com.personaledge.agent

import com.personaledge.core.llm.TurnMediaBudget

/** How a source image must be decoded and re-encoded before it can become an attachment. */
internal data class ImageDecodePlan(
    /**
     * `BitmapFactory.Options.inSampleSize`: a power of two, so a 108 MP camera file is never
     * fully decoded into heap just to be thrown away at the resize step.
     */
    val sampleSize: Int,
    val targetWidth: Int,
    val targetHeight: Int,
)

/**
 * Pure geometry and quality rules for turning a photo into a bounded model attachment.
 *
 * Kept free of Android types on purpose: the arithmetic that decides how much of a photo the
 * model actually sees, and how much heap decoding it costs, is the part worth testing on the host.
 * [ImageAttachmentLoader] does the platform work and nothing else.
 */
internal object MediaAttachmentPolicy {
    /**
     * Longest edge handed to the vision encoder.
     *
     * The encoder resizes internally anyway, so sending more pixels buys nothing and costs heap,
     * base64 inflation across the JNI bridge, and encode time. 768 matches the largest native
     * input resolution documented for this model family.
     */
    const val MAX_EDGE_PIXELS: Int = 768

    /** Below this an image carries no legible detail and is refused rather than upscaled. */
    const val MIN_EDGE_PIXELS: Int = 32

    /**
     * Long edge of the composer preview thumbnail.
     *
     * Small on purpose: this exists so the owner can see which photo is attached, and at 128 px it
     * costs about 64 KB of memory rather than carrying a second copy of the payload around.
     */
    const val PREVIEW_EDGE_PIXELS: Int = 128

    /** Guards against a decoder-reported size that no real photo has. */
    const val MAX_SOURCE_EDGE_PIXELS: Int = 20_000

    /**
     * Tried in order until the encoded result fits [TurnMediaBudget.MAX_IMAGE_BYTES].
     *
     * A document photo is the demanding case: dropping quality too fast smears small text, so the
     * ladder starts high and steps down rather than jumping straight to a small number.
     */
    val JPEG_QUALITY_LADDER: List<Int> = listOf(92, 85, 75, 65, 55)

    /**
     * Returns how to decode and resize a source of [sourceWidth] x [sourceHeight], or null when
     * the source is unusable.
     *
     * Aspect ratio is preserved and an image already inside the bound is never enlarged: upscaling
     * would invent detail the model would then describe.
     */
    fun decodePlanOrNull(sourceWidth: Int, sourceHeight: Int): ImageDecodePlan? {
        if (sourceWidth < MIN_EDGE_PIXELS || sourceHeight < MIN_EDGE_PIXELS) return null
        if (sourceWidth > MAX_SOURCE_EDGE_PIXELS || sourceHeight > MAX_SOURCE_EDGE_PIXELS) {
            return null
        }

        val longestEdge = maxOf(sourceWidth, sourceHeight)
        if (longestEdge <= MAX_EDGE_PIXELS) {
            return ImageDecodePlan(
                sampleSize = 1,
                targetWidth = sourceWidth,
                targetHeight = sourceHeight,
            )
        }

        // Largest power of two that still leaves both edges at or above the target, so the
        // subsequent exact scale only ever shrinks and never re-expands a subsampled bitmap.
        var sampleSize = 1
        while (longestEdge / (sampleSize * 2) >= MAX_EDGE_PIXELS) {
            sampleSize *= 2
        }

        val scale = MAX_EDGE_PIXELS.toDouble() / longestEdge
        val targetWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        return ImageDecodePlan(
            sampleSize = sampleSize,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
        )
    }

    /** True when an encoded payload can still be handed to the runtime. */
    fun fitsAttachmentBudget(encodedByteCount: Int): Boolean =
        encodedByteCount in TurnMediaBudget.MIN_IMAGE_BYTES..TurnMediaBudget.MAX_IMAGE_BYTES
}
