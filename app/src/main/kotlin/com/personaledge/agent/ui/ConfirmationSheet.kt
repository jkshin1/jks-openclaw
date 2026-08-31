package com.personaledge.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.personaledge.agent.PendingConfirmation
import com.personaledge.agent.R
import com.personaledge.agent.TodayBrief
import com.personaledge.agent.ui.components.AccentIcon
import com.personaledge.agent.ui.theme.CapsuleShape

/**
 * The execution gate.
 *
 * Presentation changed; the contract did not. Dismissing by swipe, scrim tap, or back is still a
 * denial, exactly as dismissing the previous dialog was, and the decision is delivered on the tap
 * rather than after an exit animation so a slow close cannot eat into the challenge's expiry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmationSheet(
    pending: PendingConfirmation,
    todayBrief: TodayBrief? = null,
    onDecision: (actionId: String, approved: Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val windowState = with(density) {
        WorkspaceWindowState(
            widthDp = windowSize.width.toDp().value,
            heightDp = windowSize.height.toDp().value,
        )
    }
    val expanded = WorkspaceLayoutPolicy.forWindow(windowState).layout == WorkspaceLayout.TWO_PANE
    val bodyScrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = { onDecision(pending.actionId, false) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(bodyScrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccentIcon(
                    iconRes = R.drawable.ic_shield,
                    tint = MaterialTheme.colorScheme.primary,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    size = 52.dp,
                )
                Text(
                    text = pending.preview.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                if (expanded && todayBrief != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CurrentScheduleCard(todayBrief, Modifier.weight(1f))
                        ConfirmationSummaryCard(pending, Modifier.weight(1f))
                    }
                } else {
                    ConfirmationSummaryCard(pending, Modifier.fillMaxWidth())
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Approval actions stay outside the scrolling body. Large font scales, short DeX
            // windows and long previews can move content, but cannot move denial/approval away.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDecision(pending.actionId, false)
                    },
                    shape = CapsuleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("거절")
                }
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDecision(pending.actionId, true)
                    },
                    shape = CapsuleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("실행")
                }
            }
        }
    }
}

@Composable
private fun CurrentScheduleCard(brief: TodayBrief, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("현재 오늘 일정", style = MaterialTheme.typography.titleSmall)
            when {
                !brief.calendarAvailable -> Text("캘린더를 읽지 못했습니다.")
                brief.events.isEmpty() -> Text("오늘 일정이 없습니다.")
                else -> brief.events.take(3).forEach { event ->
                    Text("${event.timeText} · ${event.title}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (brief.conflictCount > 0) {
                Text(
                    "기존 일정 충돌 ${brief.conflictCount}건",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ConfirmationSummaryCard(pending: PendingConfirmation, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("새 실행 제안", style = MaterialTheme.typography.titleSmall)
            Text(
                text = pending.preview.summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "확인한 내용 그대로 실행됩니다. 실행 직전에 권한·설정·열 상태 등 " +
                    "필요한 조건을 다시 확인합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
