package com.personaledge.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.personaledge.agent.ui.StatusTone
import com.personaledge.agent.ui.theme.CapsuleShape

@Composable
internal fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    StatusTone.ACCENT -> MaterialTheme.colorScheme.primary
    StatusTone.POSITIVE -> MaterialTheme.colorScheme.tertiary
    StatusTone.CAUTION -> MaterialTheme.colorScheme.secondary
    StatusTone.CRITICAL -> MaterialTheme.colorScheme.error
}

@Composable
internal fun toneContainerColor(tone: StatusTone): Color = when (tone) {
    StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest
    StatusTone.ACCENT -> MaterialTheme.colorScheme.primaryContainer
    StatusTone.POSITIVE -> MaterialTheme.colorScheme.tertiaryContainer
    StatusTone.CAUTION -> MaterialTheme.colorScheme.secondaryContainer
    StatusTone.CRITICAL -> MaterialTheme.colorScheme.errorContainer
}

@Composable
internal fun onToneContainerColor(tone: StatusTone): Color = when (tone) {
    StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    StatusTone.ACCENT -> MaterialTheme.colorScheme.onPrimaryContainer
    StatusTone.POSITIVE -> MaterialTheme.colorScheme.onTertiaryContainer
    StatusTone.CAUTION -> MaterialTheme.colorScheme.onSecondaryContainer
    StatusTone.CRITICAL -> MaterialTheme.colorScheme.onErrorContainer
}

/** A status capsule: a coloured dot plus a short label, sized for the app bar subtitle. */
@Composable
internal fun StatusPill(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(toneContainerColor(tone), CapsuleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(toneColor(tone), CapsuleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = onToneContainerColor(tone),
        )
    }
}

/** A tinted rounded glyph tile, the leading element of every settings row. */
@Composable
internal fun AccentIcon(
    iconRes: Int,
    tint: Color,
    container: Color,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(container, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.62f),
        )
    }
}
