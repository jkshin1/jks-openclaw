package com.personaledge.agent.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * A rounded rectangle whose corners keep curving instead of snapping from arc to straight edge.
 *
 * A circular corner meets the edge with a curvature discontinuity, which is what makes a plain
 * `RoundedCornerShape` read as boxy at large radii. Each corner here is one cubic whose control
 * points hold the edge tangent for a third of the corner run, so curvature ramps up and back down.
 *
 * [smoothing] extends the corner along the edge: `0f` is an ordinary circular corner, and the
 * default spends 55% more edge on the transition. The run is clamped to half the shorter side, so
 * an oversized radius degrades to a capsule instead of self-intersecting.
 */
internal class SquircleShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
    private val smoothing: Float = DEFAULT_SMOOTHING,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = SquircleShape(topStart, topEnd, bottomEnd, bottomStart, smoothing)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        if (size.minDimension <= 0f) return Outline.Rectangle(Rect(Offset.Zero, size))

        val mirrored = layoutDirection == LayoutDirection.Rtl
        val limit = size.minDimension / 2f
        val extend = 1f + smoothing.coerceIn(0f, 1f)
        val tl = min((if (mirrored) topEnd else topStart) * extend, limit)
        val tr = min((if (mirrored) topStart else topEnd) * extend, limit)
        val br = min((if (mirrored) bottomStart else bottomEnd) * extend, limit)
        val bl = min((if (mirrored) bottomEnd else bottomStart) * extend, limit)

        val path = Path().apply {
            moveTo(tl, 0f)
            lineTo(size.width - tr, 0f)
            cubicTo(
                size.width - tr * TANGENT_HOLD, 0f,
                size.width, tr * TANGENT_HOLD,
                size.width, tr,
            )
            lineTo(size.width, size.height - br)
            cubicTo(
                size.width, size.height - br * TANGENT_HOLD,
                size.width - br * TANGENT_HOLD, size.height,
                size.width - br, size.height,
            )
            lineTo(bl, size.height)
            cubicTo(
                bl * TANGENT_HOLD, size.height,
                0f, size.height - bl * TANGENT_HOLD,
                0f, size.height - bl,
            )
            lineTo(0f, tl)
            cubicTo(
                0f, tl * TANGENT_HOLD,
                tl * TANGENT_HOLD, 0f,
                tl, 0f,
            )
            close()
        }
        return Outline.Generic(path)
    }

    override fun toString(): String = "SquircleShape(topStart=$topStart, topEnd=$topEnd, " +
        "bottomEnd=$bottomEnd, bottomStart=$bottomStart, smoothing=$smoothing)"

    override fun equals(other: Any?): Boolean = other is SquircleShape &&
        topStart == other.topStart && topEnd == other.topEnd &&
        bottomEnd == other.bottomEnd && bottomStart == other.bottomStart &&
        smoothing == other.smoothing

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        result = 31 * result + smoothing.hashCode()
        return result
    }

    private companion object {
        const val DEFAULT_SMOOTHING = 0.55f

        /** How far the control point holds the edge tangent, as a fraction of the corner run. */
        const val TANGENT_HOLD = 0.34f
    }
}

internal fun SquircleShape(radius: Dp, smoothing: Float = 0.55f): SquircleShape = SquircleShape(
    topStart = CornerSize(radius),
    topEnd = CornerSize(radius),
    bottomEnd = CornerSize(radius),
    bottomStart = CornerSize(radius),
    smoothing = smoothing,
)

internal val PersonalEdgeShapes = Shapes(
    extraSmall = SquircleShape(8.dp),
    small = SquircleShape(12.dp),
    medium = SquircleShape(16.dp),
    large = SquircleShape(22.dp),
    extraLarge = SquircleShape(30.dp),
)

/** Grouped-list card corner, matched across the settings sections. */
internal val GroupedSectionShape = SquircleShape(18.dp)

/** A capsule: the corner run is clamped to half the height, so this is a stadium at any size. */
internal val CapsuleShape = SquircleShape(1000.dp, smoothing = 0f)
