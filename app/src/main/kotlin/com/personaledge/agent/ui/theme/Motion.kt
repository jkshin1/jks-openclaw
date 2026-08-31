package com.personaledge.agent.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntSize

/**
 * Springs rather than durations.
 *
 * Everything that moves in this app moves under a spring, so an interruption — the user tapping
 * send while a sheet is still settling — retargets from the current velocity instead of snapping.
 * The damping ratios sit just under critical, giving the small overshoot a phone OS uses to make
 * a surface feel physical without visible bounce.
 */
internal object PersonalEdgeMotion {
    /** Position and size changes: sheets arriving, bubbles landing, rows expanding. */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.86f,
        stiffness = 380f,
    )

    /** Quick spatial response for direct manipulation, such as a button morphing under a tap. */
    fun <T> spatialFast(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.9f,
        stiffness = 900f,
    )

    /** Colour and alpha, where overshoot would read as a flicker. */
    fun <T> effects(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** `animateContentSize` needs the concrete size type. */
    fun contentSize(): FiniteAnimationSpec<IntSize> = spatial()
}
