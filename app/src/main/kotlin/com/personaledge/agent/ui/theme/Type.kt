package com.personaledge.agent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

// Optical sizes closer to a modern phone OS than the Material baseline: a heavier, tighter large
// title, a 17sp reading size, and a 13sp footnote. The family stays the platform default so the
// Korean fallback keeps working; only weight, size, and tracking are tuned.
private val platformFamily = FontFamily.Default

private val snugLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: Float = 0f,
): TextStyle = TextStyle(
    fontFamily = platformFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = snugLineHeight,
)

internal val PersonalEdgeTypography = Typography(
    displayLarge = style(44, 52, FontWeight.Bold, -1.0f),
    displayMedium = style(38, 46, FontWeight.Bold, -0.8f),
    displaySmall = style(32, 40, FontWeight.Bold, -0.6f),
    headlineLarge = style(30, 38, FontWeight.Bold, -0.5f),
    headlineMedium = style(26, 33, FontWeight.Bold, -0.4f),
    headlineSmall = style(22, 29, FontWeight.Bold, -0.3f),
    titleLarge = style(20, 26, FontWeight.SemiBold, -0.2f),
    titleMedium = style(17, 23, FontWeight.SemiBold, -0.1f),
    titleSmall = style(15, 21, FontWeight.SemiBold),
    bodyLarge = style(17, 25, FontWeight.Normal),
    bodyMedium = style(15, 22, FontWeight.Normal),
    bodySmall = style(13, 19, FontWeight.Normal),
    labelLarge = style(15, 20, FontWeight.SemiBold),
    labelMedium = style(13, 17, FontWeight.Medium),
    labelSmall = style(11, 15, FontWeight.Medium, 0.2f),
)
