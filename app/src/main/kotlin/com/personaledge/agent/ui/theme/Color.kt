package com.personaledge.agent.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * A hand-tuned palette rather than the Material baseline or a dynamic wallpaper scheme.
 *
 * Two reasons it is fixed. Screenshots and diagnostics are read across builds, so a surface that
 * changes colour with the wallpaper makes "did the gate turn red" harder to answer. And the roles
 * carry meaning here: primary is the agent, tertiary marks a Tool receipt, error marks a refusal.
 */
private val SystemBlueLight = Color(0xFF0A6CFF)
private val SystemBlueDark = Color(0xFF3E9BFF)
private val SystemIndigoLight = Color(0xFF4F46E5)
private val SystemIndigoDark = Color(0xFF9B95FF)
private val SystemGreenLight = Color(0xFF1E8E3E)
private val SystemGreenDark = Color(0xFF4ADE80)
private val SystemRedLight = Color(0xFFD11B0E)
private val SystemRedDark = Color(0xFFFF6961)

internal val PersonalEdgeLightColors: ColorScheme = lightColorScheme(
    primary = SystemBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEAFF),
    onPrimaryContainer = Color(0xFF00204A),
    inversePrimary = SystemBlueDark,
    secondary = SystemIndigoLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E4FF),
    onSecondaryContainer = Color(0xFF1B1650),
    tertiary = SystemGreenLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8F3DF),
    onTertiaryContainer = Color(0xFF07301A),
    error = SystemRedLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFE0DC),
    onErrorContainer = Color(0xFF52100A),
    // iOS-style grouped background: the page is grey, the cards on it are white.
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF111114),
    surface = Color(0xFFF2F2F7),
    onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFE7E7EC),
    onSurfaceVariant = Color(0xFF5F5F65),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFBFD),
    surfaceContainer = Color(0xFFF6F6F9),
    surfaceContainerHigh = Color(0xFFEDEDF2),
    surfaceContainerHighest = Color(0xFFE5E5EB),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDDDE3),
    outline = Color(0xFFB9B9C0),
    outlineVariant = Color(0xFFDFDFE5),
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = Color(0xFFF2F2F7),
    scrim = Color(0xFF000000),
)

internal val PersonalEdgeDarkColors: ColorScheme = darkColorScheme(
    primary = SystemBlueDark,
    onPrimary = Color(0xFF00214C),
    primaryContainer = Color(0xFF15385F),
    onPrimaryContainer = Color(0xFFD3E6FF),
    inversePrimary = SystemBlueLight,
    secondary = SystemIndigoDark,
    onSecondary = Color(0xFF221C63),
    secondaryContainer = Color(0xFF302B66),
    onSecondaryContainer = Color(0xFFE3E1FF),
    tertiary = SystemGreenDark,
    onTertiary = Color(0xFF06331A),
    tertiaryContainer = Color(0xFF15412A),
    onTertiaryContainer = Color(0xFFC9F6D7),
    error = SystemRedDark,
    onError = Color(0xFF510F0A),
    errorContainer = Color(0xFF5E1B15),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFA6A6AC),
    surfaceContainerLowest = Color(0xFF0A0A0C),
    surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF252528),
    surfaceContainerHighest = Color(0xFF2E2E31),
    surfaceBright = Color(0xFF39393C),
    surfaceDim = Color(0xFF000000),
    outline = Color(0xFF4B4B4F),
    outlineVariant = Color(0xFF37373A),
    inverseSurface = Color(0xFFF2F2F7),
    inverseOnSurface = Color(0xFF1C1C1E),
    scrim = Color(0xFF000000),
)
