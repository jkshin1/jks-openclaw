package com.personaledge.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The app theme: a fixed palette, a tuned type scale, and continuous corners.
 *
 * Material 3 1.4.0 keeps `MaterialExpressiveTheme` and its motion scheme internal, so the
 * expressive motion this design depends on is supplied directly by [PersonalEdgeMotion] at the
 * call sites that animate.
 */
@Composable
fun PersonalEdgeAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) PersonalEdgeDarkColors else PersonalEdgeLightColors,
        shapes = PersonalEdgeShapes,
        typography = PersonalEdgeTypography,
        content = content,
    )
}
