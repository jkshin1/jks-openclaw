package com.personaledge.agent.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.personaledge.agent.ui.theme.GroupedSectionShape

internal object ToggleRowAccessibilityPolicy {
    const val ON_STATE_DESCRIPTION = "켜짐"
    const val OFF_STATE_DESCRIPTION = "꺼짐"

    fun stateDescription(checked: Boolean): String =
        if (checked) ON_STATE_DESCRIPTION else OFF_STATE_DESCRIPTION

    fun nextValue(checked: Boolean): Boolean = !checked
}

/**
 * One grouped section of the settings sheet: a quiet caption, a single inset card holding the
 * rows, and an optional footnote.
 *
 * The old screen gave every subject its own elevated card with its own title inside it, so six
 * subjects produced six competing headings and no visual hierarchy. Pulling the heading out of the
 * card and letting the rows share one surface is what makes a long settings list scannable.
 */
@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    footnote: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 7.dp),
        )
        Surface(
            shape = GroupedSectionShape,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
        if (footnote != null) {
            Text(
                text = footnote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 7.dp),
            )
        }
    }
}

/** The hairline between rows, inset past the glyph so the icons read as one column. */
@Composable
internal fun RowDivider(inset: Boolean = true) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (inset) 58.dp else 0.dp),
        thickness = 0.7.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * A settings row. [trailing] carries whatever the row acts through — a chevron, a value, a switch
 * — and [onClick] is optional so a purely informational row does not fake being tappable.
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconRes: Int? = null,
    iconTint: Color? = null,
    iconContainer: Color? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null && enabled) {
        modifier.fillMaxWidth().clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (iconRes != null) {
            AccentIcon(
                iconRes = iconRes,
                tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                container = iconContainer ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke(this)
    }
}

/** A settings row whose action is a switch; the whole row is the target, not just the switch. */
@Composable
internal fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconRes: Int? = null,
    iconTint: Color? = null,
    iconContainer: Color? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        modifier = modifier
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    onCheckedChange(ToggleRowAccessibilityPolicy.nextValue(checked))
                },
            )
            .semantics {
                stateDescription = ToggleRowAccessibilityPolicy.stateDescription(checked)
            },
        subtitle = subtitle,
        iconRes = iconRes,
        iconTint = iconTint,
        iconContainer = iconContainer,
        onClick = null,
        enabled = enabled,
    ) {
        Switch(
            checked = checked,
            // The row owns the single toggle action and semantics node. The visible thumb is a
            // child indicator only, so TalkBack and pointer input cannot activate it a second time.
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** Free-form content inside a section card, with the row padding applied for alignment. */
@Composable
internal fun SectionContent(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}
