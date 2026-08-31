package com.personaledge.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personaledge.agent.ReminderSetupState
import com.personaledge.agent.ReminderUiItem
import com.personaledge.agent.TodayBrief
import com.personaledge.agent.TodayBriefItem
import com.personaledge.agent.ui.theme.CapsuleShape

/** A bounded, model-independent glance surface for the Fold cover display. */
@Composable
internal fun CoverTodayCard(
    state: ReminderSetupState,
    onComplete: (String, Long) -> Unit,
    onSnooze: (String, Long, Int) -> Unit,
    onOpenReminders: () -> Unit,
) {
    val brief = state.todayBrief
    val firstReminder = state.reminders.firstOrNull()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("오늘", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onOpenReminders) { Text("전체 보기") }
            }
            TodayEventLine(
                brief.nextEvent,
                brief.calendarAvailable,
                brief.nextEventCountdownText,
            )
            brief.leaveByText?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            BriefCounts(brief)
            firstReminder?.let { reminder ->
                Text(
                    "미완료 · ${reminder.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
                ReminderActions(
                    reminder = reminder,
                    onComplete = onComplete,
                    onSnooze = onSnooze,
                )
            }
        }
    }
}

/** The unfolded left pane keeps deterministic schedule facts visible beside the conversation. */
@Composable
internal fun ExpandedTodayPane(
    state: ReminderSetupState,
    onComplete: (String, Long) -> Unit,
    onSnooze: (String, Long, Int) -> Unit,
    onOpenReminders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brief = state.todayBrief
    Column(
        modifier = modifier
            .fillMaxHeight()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("오늘", style = MaterialTheme.typography.headlineSmall)
        Text(
            "캘린더와 로컬 리마인더에서 모델 없이 구성",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BriefCounts(brief)
        brief.leaveByText?.let {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    it,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        brief.routeStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text("일정", style = MaterialTheme.typography.titleMedium)
        if (!brief.calendarAvailable) {
            Text("캘린더를 읽지 못했습니다.", color = MaterialTheme.colorScheme.error)
        } else if (brief.events.isEmpty()) {
            Text("오늘 일정이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            brief.events.take(MAX_EXPANDED_EVENTS).forEach { event ->
                TimelineEvent(event)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("미완료", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onOpenReminders) { Text("관리") }
        }
        if (state.reminders.isEmpty()) {
            Text("활성 리마인더가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.reminders.take(MAX_EXPANDED_REMINDERS).forEach { reminder ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(reminder.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            reminder.triggerText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ReminderActions(reminder, onComplete, onSnooze)
                    }
                }
            }
        }
        if (state.proposals.isNotEmpty()) {
            OutlinedButton(onClick = onOpenReminders, shape = CapsuleShape) {
                Text("검토할 일정 후보 ${state.proposals.size}개")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TodayEventLine(
    event: TodayBriefItem?,
    calendarAvailable: Boolean,
    countdownText: String?,
) {
    val text = when {
        !calendarAvailable -> "캘린더를 읽지 못했습니다."
        event == null -> "다음 일정 없음"
        else -> listOfNotNull(
            "다음 · ${event.timeText} ${event.title}",
            countdownText,
        ).joinToString(" · ")
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (calendarAvailable) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.error
        },
        maxLines = 2,
    )
}

@Composable
private fun BriefCounts(brief: TodayBrief) {
    val values = buildList {
        add("일정 ${brief.totalEventCount}")
        add("오늘 알림 ${brief.dueReminderCount}")
        if (brief.overdueReminderCount > 0) add("지연 ${brief.overdueReminderCount}")
        if (brief.yesterdaySnoozedCount > 0) add("어제 미룸 ${brief.yesterdaySnoozedCount}")
        if (brief.conflictCount > 0) add("충돌 ${brief.conflictCount}")
    }
    Text(
        values.joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = if (brief.conflictCount > 0 || brief.overdueReminderCount > 0) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun TimelineEvent(event: TodayBriefItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(event.timeText, style = MaterialTheme.typography.labelLarge)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(event.title, style = MaterialTheme.typography.bodyMedium)
            event.location?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReminderActions(
    reminder: ReminderUiItem,
    onComplete: (String, Long) -> Unit,
    onSnooze: (String, Long, Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onComplete(reminder.id, reminder.scheduleVersion) },
            shape = CapsuleShape,
        ) { Text("완료") }
        OutlinedButton(
            onClick = { onSnooze(reminder.id, reminder.scheduleVersion, 10) },
            shape = CapsuleShape,
        ) { Text("10분 미루기") }
    }
}

private const val MAX_EXPANDED_EVENTS = 6
private const val MAX_EXPANDED_REMINDERS = 4
