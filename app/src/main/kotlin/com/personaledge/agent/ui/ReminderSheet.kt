package com.personaledge.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personaledge.agent.ReminderSetupState
import com.personaledge.agent.ReminderUiItem
import com.personaledge.agent.ui.components.RowDivider
import com.personaledge.agent.ui.components.SectionContent
import com.personaledge.agent.ui.components.SettingsRow
import com.personaledge.agent.ui.components.SettingsSection
import com.personaledge.agent.ui.components.ToggleRow
import com.personaledge.agent.R
import com.personaledge.agent.ui.theme.CapsuleShape
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderSheet(
    state: ReminderSetupState,
    openSourceReminderId: String?,
    onSourceDialogDismissed: () -> Unit,
    onDismiss: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onCreate: (String, String, Boolean, String?, Boolean) -> Unit,
    onComplete: (String, Long) -> Unit,
    onSnooze: (String, Long, Int) -> Unit,
    onCancel: (String, Long) -> Unit,
    onSetDailyBriefEnabled: (Boolean) -> Unit,
    onSetDailyBriefTime: (String) -> Unit,
    onSetProactiveRoutePlanningEnabled: (Boolean) -> Unit,
    onSetQuietHoursEnabled: (Boolean) -> Unit,
    onSetWeekendBriefEnabled: (Boolean) -> Unit,
    onSetCommitmentProposalsEnabled: (Boolean) -> Unit,
    onCaptureInbox: (String) -> Unit,
    onPromoteProposal: (String, String, Boolean) -> Unit,
    onDismissProposal: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf("") }
    var triggerAt by remember {
        mutableStateOf(LocalDateTime.now().plusHours(1).withSecond(0).withNano(0).format(INPUT_FORMAT))
    }
    var exact by remember { mutableStateOf(false) }
    var recurrenceChoice by remember { mutableStateOf(DIRECT_RECURRENCE_NONE) }
    var untilCompleted by remember { mutableStateOf(false) }
    var dailyBriefTime by remember(state.dailyBriefTime) { mutableStateOf(state.dailyBriefTime) }
    var inboxSummary by remember { mutableStateOf("") }
    var cancelCandidate by remember { mutableStateOf<ReminderUiItem?>(null) }
    var dismissProposalCandidate by remember { mutableStateOf<com.personaledge.agent.CommitmentProposalUiItem?>(null) }
    var sourceCandidate by remember { mutableStateOf<ReminderUiItem?>(null) }
    val proposalTimes = remember { mutableStateMapOf<String, String>() }
    val manualDateRows = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(openSourceReminderId, state.reminders) {
        if (openSourceReminderId != null) {
            state.reminders.firstOrNull { it.id == openSourceReminderId }?.let {
                sourceCandidate = it
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.94f)
                .imePadding(),
        ) {
            SheetHeader("리마인더", onDismiss)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsSection(
                    title = "오늘",
                    footnote = "일정·충돌·미완료 항목은 Kotlin 규칙으로 계산하며 모델을 로드하지 않습니다.",
                ) {
                    SectionContent(spacing = 6.dp) {
                        Text(
                            state.todayBrief.notificationText().ifBlank {
                                "오늘 예정된 일정과 리마인더가 없습니다."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!state.todayBrief.calendarAvailable) {
                            Text(
                                "캘린더를 읽지 못해 리마인더만 표시합니다.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        state.todayBrief.routeStatus?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    RowDivider(inset = false)
                    ToggleRow(
                        title = "매일 ${state.dailyBriefTime} 브리핑",
                        subtitle = "선택한 현지 시각에 모델 없이 오늘 요약 알림을 게시",
                        checked = state.dailyBriefEnabled,
                        onCheckedChange = onSetDailyBriefEnabled,
                        iconRes = R.drawable.ic_calendar,
                    )
                    RowDivider()
                    SectionContent(spacing = 8.dp) {
                        OutlinedTextField(
                            value = dailyBriefTime,
                            onValueChange = { value ->
                                if (value.length <= 5) dailyBriefTime = value
                            },
                            label = { Text("브리핑 시각") },
                            supportingText = { Text("HH:mm · ${state.deviceZoneId}") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onSetDailyBriefTime(dailyBriefTime) },
                            enabled = dailyBriefTime.length == 5 && dailyBriefTime != state.dailyBriefTime,
                            shape = CapsuleShape,
                        ) {
                            Text("시각 적용")
                        }
                    }
                    RowDivider()
                    ToggleRow(
                        title = "주말에도 브리핑",
                        subtitle = "끄면 토·일요일 오전 브리핑을 건너뜀",
                        checked = state.weekendBriefEnabled,
                        onCheckedChange = onSetWeekendBriefEnabled,
                        iconRes = R.drawable.ic_calendar,
                    )
                    RowDivider()
                    ToggleRow(
                        title = "출발 시각 미리 계산",
                        subtitle = "별도 동의 · 다음 일정 장소와 기본 출발지를 NAVER로 전송",
                        checked = state.proactiveRoutePlanningEnabled,
                        onCheckedChange = onSetProactiveRoutePlanningEnabled,
                        iconRes = R.drawable.ic_route,
                    )
                }

                SettingsSection(
                    title = "전달 상태",
                    footnote = "리마인더 DB·예약·알림은 모델 로드 및 열 상태와 독립적으로 동작합니다.",
                ) {
                    SettingsRow(
                        title = if (state.canNotify) "알림 전달 가능" else "알림 권한 필요",
                        subtitle = if (state.canNotify) {
                            "앱 알림 허용됨 · ${state.deviceZoneId}"
                        } else {
                            "권한을 허용해야 새 리마인더를 실제로 받을 수 있습니다."
                        },
                    ) {
                        if (!state.canNotify) {
                            TextButton(onClick = onRequestNotificationPermission) { Text("허용") }
                        }
                    }
                    RowDivider()
                    SettingsRow(
                        title = if (state.exactAlarmAvailable) "정확 알람 사용 가능" else "정확 알람 미허용",
                        subtitle = if (state.exactAlarmAvailable) {
                            "정확 시각 요청은 AlarmManager로 예약"
                        } else {
                            "정확 시각 요청도 WorkManager 근사 예약으로 안전하게 전환"
                        },
                    ) {
                        if (!state.exactAlarmAvailable) {
                            TextButton(onClick = onOpenExactAlarmSettings) { Text("설정") }
                        }
                    }
                    RowDivider()
                    ToggleRow(
                        title = "조용한 시간 22:00–07:00",
                        subtitle = "근사 리마인더만 오전 7시로 이동 · 정확 시각 요청은 유지",
                        checked = state.quietHoursEnabled,
                        onCheckedChange = onSetQuietHoursEnabled,
                        iconRes = R.drawable.ic_bell,
                    )
                }

                SettingsSection(
                    title = "잊지 말 것 Inbox ${state.proposals.size}개",
                    footnote = "직접 적거나 별도 동의한 알림에서 찾은 후보를 보관합니다. " +
                        "자동 등록하지 않으며, 시각을 한 번 선택해야 리마인더가 됩니다.",
                ) {
                    SectionContent(spacing = 8.dp) {
                        OutlinedTextField(
                            value = inboxSummary,
                            onValueChange = { value ->
                                if (value.codePointCount(0, value.length) <= 180) inboxSummary = value
                            },
                            label = { Text("잊지 말 것") },
                            supportingText = { Text("날짜가 없어도 먼저 Inbox에 보관할 수 있습니다.") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                onCaptureInbox(inboxSummary)
                                inboxSummary = ""
                            },
                            enabled = inboxSummary.isNotBlank(),
                            shape = CapsuleShape,
                        ) {
                            Text("Inbox에 보관")
                        }
                    }
                    RowDivider(inset = false)
                    ToggleRow(
                        title = "로컬 약속 후보 탐지",
                        subtitle = if (state.commitmentProposalsEnabled) {
                            "새로 캡처되는 카카오톡 알림에서 검토 후보만 생성"
                        } else {
                            "꺼짐 · 기존 후보는 보존되지만 새 후보는 만들지 않음"
                        },
                        checked = state.commitmentProposalsEnabled,
                        onCheckedChange = onSetCommitmentProposalsEnabled,
                        iconRes = R.drawable.ic_bell,
                    )
                    if (state.proposals.isEmpty()) {
                        RowDivider(inset = false)
                        SectionContent { Text("검토할 일정 후보가 없습니다.") }
                    }
                    state.proposals.forEach { proposal ->
                        RowDivider()
                        SectionContent(spacing = 8.dp) {
                            Text(proposal.summary, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "출처 · ${proposal.sourceLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (proposal.suggestedTriggerAt == null) {
                                Text("언제쯤 다시 알려드릴까요?", style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    proposal.todayEveningTriggerAt?.let { todayEvening ->
                                        OutlinedButton(
                                            onClick = {
                                                onPromoteProposal(proposal.id, todayEvening, false)
                                            },
                                            shape = CapsuleShape,
                                        ) { Text("오늘 저녁") }
                                    }
                                    proposal.tomorrowMorningTriggerAt?.let { tomorrowMorning ->
                                        OutlinedButton(
                                            onClick = {
                                                onPromoteProposal(proposal.id, tomorrowMorning, false)
                                            },
                                            shape = CapsuleShape,
                                        ) { Text("내일 오전") }
                                    }
                                    TextButton(
                                        onClick = { manualDateRows[proposal.id] = true },
                                    ) { Text("날짜 지정") }
                                }
                            } else {
                                Text(
                                    "결정론적 시각 제안 · ${proposal.zoneId ?: state.deviceZoneId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (proposal.suggestedTriggerAt != null || manualDateRows[proposal.id] == true) {
                                OutlinedTextField(
                                    value = proposalTimes[proposal.id]
                                        ?: proposal.suggestedTriggerAt.orEmpty(),
                                    onValueChange = { value ->
                                        if (value.length <= 16) proposalTimes[proposal.id] = value
                                    },
                                    label = { Text("리마인더 시각") },
                                    supportingText = { Text("yyyy-MM-dd'T'HH:mm") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick = {
                                        onPromoteProposal(
                                            proposal.id,
                                            proposalTimes[proposal.id]
                                                ?: proposal.suggestedTriggerAt.orEmpty(),
                                            false,
                                        )
                                    },
                                    enabled = (proposalTimes[proposal.id]
                                        ?: proposal.suggestedTriggerAt.orEmpty()).length == 16,
                                    shape = CapsuleShape,
                                ) {
                                    Text("리마인더로 승인")
                                }
                            }
                            TextButton(onClick = { dismissProposalCandidate = proposal }) {
                                Text("무시")
                            }
                        }
                    }
                }

                SettingsSection(
                    title = "직접 추가",
                    footnote = "현지 절대 날짜를 사용합니다. 예: 2026-08-25T15:00",
                ) {
                    SectionContent(spacing = 10.dp) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { value ->
                                if (value.codePointCount(0, value.length) <= 120) title = value
                            },
                            label = { Text("할 일") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = triggerAt,
                            onValueChange = { triggerAt = it.take(16) },
                            label = { Text("알림 시각") },
                            supportingText = { Text("yyyy-MM-dd'T'HH:mm · ${state.deviceZoneId}") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("정확 시각", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "미허용 시 근사 예약으로 전환됩니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = exact, onCheckedChange = { exact = it })
                        }
                        Text(
                            "반복 · ${directRecurrenceLabel(recurrenceChoice)}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { recurrenceChoice = DIRECT_RECURRENCE_NONE },
                                shape = CapsuleShape,
                            ) { Text("없음") }
                            OutlinedButton(
                                onClick = { recurrenceChoice = DIRECT_RECURRENCE_DAILY },
                                shape = CapsuleShape,
                            ) { Text("매일") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { recurrenceChoice = DIRECT_RECURRENCE_WEEKDAYS },
                                shape = CapsuleShape,
                            ) { Text("평일") }
                            OutlinedButton(
                                onClick = { recurrenceChoice = DIRECT_RECURRENCE_WEEKLY },
                                shape = CapsuleShape,
                            ) { Text("매주 같은 요일") }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("완료할 때까지 재알림", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (recurrenceChoice == DIRECT_RECURRENCE_NONE) {
                                        "완료하지 않으면 15분 간격으로 다시 알림"
                                    } else {
                                        "반복 리마인더는 다음 회차로 진행하므로 함께 사용하지 않음"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = untilCompleted && recurrenceChoice == DIRECT_RECURRENCE_NONE,
                                onCheckedChange = { untilCompleted = it },
                                enabled = recurrenceChoice == DIRECT_RECURRENCE_NONE,
                            )
                        }
                        Button(
                            onClick = {
                                onCreate(
                                    title,
                                    triggerAt,
                                    exact,
                                    directRecurrenceRule(recurrenceChoice, triggerAt),
                                    untilCompleted && recurrenceChoice == DIRECT_RECURRENCE_NONE,
                                )
                                title = ""
                            },
                            enabled = title.isNotBlank() && triggerAt.length == 16,
                            shape = CapsuleShape,
                        ) {
                            Text("리마인더 저장")
                        }
                    }
                }

                SettingsSection(
                    title = "예정 및 미완료 ${state.reminders.size}개",
                    footnote = "알림에서 완료하거나 미룬 결과도 로컬 delivery history에 남습니다.",
                ) {
                    if (state.reminders.isEmpty()) {
                        SectionContent { Text("활성 리마인더가 없습니다.") }
                    }
                    state.reminders.forEachIndexed { index, reminder ->
                        if (index > 0) RowDivider()
                        ReminderRow(
                            reminder = reminder,
                            onComplete = { onComplete(reminder.id, reminder.scheduleVersion) },
                            onSnooze = { minutes ->
                                onSnooze(reminder.id, reminder.scheduleVersion, minutes)
                            },
                            onCancel = { cancelCandidate = reminder },
                        )
                    }
                    state.error?.let {
                        RowDivider(inset = false)
                        SectionContent { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    cancelCandidate?.let { reminder ->
        AlertDialog(
            onDismissRequest = { cancelCandidate = null },
            title = { Text("리마인더를 취소할까요?") },
            text = { Text("‘${reminder.title}’ 예약과 미완료 상태를 취소합니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCancel(reminder.id, reminder.scheduleVersion)
                        cancelCandidate = null
                    },
                ) { Text("취소 실행", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { cancelCandidate = null }) { Text("돌아가기") }
            },
        )
    }
    dismissProposalCandidate?.let { proposal ->
        AlertDialog(
            onDismissRequest = { dismissProposalCandidate = null },
            title = { Text("이 일정 후보를 무시할까요?") },
            text = { Text("자동 등록된 일정은 없습니다. 무시하면 같은 알림에서 후보를 다시 만들지 않습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissProposal(proposal.id)
                        dismissProposalCandidate = null
                    },
                ) { Text("후보 무시") }
            },
            dismissButton = {
                TextButton(onClick = { dismissProposalCandidate = null }) { Text("돌아가기") }
            },
        )
    }
    sourceCandidate?.let { reminder ->
        AlertDialog(
            onDismissRequest = {
                sourceCandidate = null
                onSourceDialogDismissed()
            },
            title = { Text("리마인더 출처") },
            text = {
                Text(
                    "‘${reminder.title}’\n\n출처 · ${reminder.sourceLabel}\n" +
                        "예약 상태 · ${reminder.scheduleLabel}",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sourceCandidate = null
                        onSourceDialogDismissed()
                    },
                ) { Text("확인") }
            },
        )
    }
}

@Composable
private fun ReminderRow(
    reminder: ReminderUiItem,
    onComplete: () -> Unit,
    onSnooze: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    SectionContent(spacing = 7.dp) {
        Text(reminder.title, style = MaterialTheme.typography.titleMedium)
        Text(
            "${reminder.triggerText} · ${reminder.scheduleLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "출처 · ${reminder.sourceLabel}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onComplete, shape = CapsuleShape) { Text("완료") }
            OutlinedButton(onClick = { onSnooze(10) }, shape = CapsuleShape) { Text("10분") }
            TextButton(onClick = onCancel) { Text("취소", color = MaterialTheme.colorScheme.error) }
        }
    }
}

private val INPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

private const val DIRECT_RECURRENCE_NONE = "none"
private const val DIRECT_RECURRENCE_DAILY = "daily"
private const val DIRECT_RECURRENCE_WEEKDAYS = "weekdays"
private const val DIRECT_RECURRENCE_WEEKLY = "weekly"

internal fun directRecurrenceLabel(choice: String): String = when (choice) {
    DIRECT_RECURRENCE_DAILY -> "매일"
    DIRECT_RECURRENCE_WEEKDAYS -> "월–금"
    DIRECT_RECURRENCE_WEEKLY -> "매주 같은 요일"
    else -> "없음"
}

internal fun directRecurrenceRule(choice: String, triggerAt: String): String? = when (choice) {
    DIRECT_RECURRENCE_DAILY -> "daily"
    DIRECT_RECURRENCE_WEEKDAYS -> "weekly:mon,tue,wed,thu,fri"
    DIRECT_RECURRENCE_WEEKLY -> runCatching {
        val token = when (LocalDateTime.parse(triggerAt, INPUT_FORMAT).dayOfWeek) {
            java.time.DayOfWeek.MONDAY -> "mon"
            java.time.DayOfWeek.TUESDAY -> "tue"
            java.time.DayOfWeek.WEDNESDAY -> "wed"
            java.time.DayOfWeek.THURSDAY -> "thu"
            java.time.DayOfWeek.FRIDAY -> "fri"
            java.time.DayOfWeek.SATURDAY -> "sat"
            java.time.DayOfWeek.SUNDAY -> "sun"
        }
        "weekly:$token"
    }.getOrNull()
    else -> null
}
