package com.personaledge.agent.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.personaledge.agent.CalendarOption
import com.personaledge.agent.CalendarSetupState
import com.personaledge.agent.CredentialSlot
import com.personaledge.agent.CredentialStatus
import com.personaledge.core.data.SecretHealth
import com.personaledge.core.data.MemoryCategory
import com.personaledge.agent.CredentialsState
import com.personaledge.agent.DiagnosticExportState
import com.personaledge.agent.ModelUiStatus
import com.personaledge.agent.MemorySetupState
import com.personaledge.agent.NetworkSetupState
import com.personaledge.agent.NotificationSetupState
import com.personaledge.agent.PersonalEdgeUiState
import com.personaledge.agent.PersonalEdgeViewModel
import com.personaledge.agent.UserDataTransferState
import com.personaledge.agent.R
import com.personaledge.agent.ThermalTurnPolicy
import com.personaledge.agent.ui.components.RowDivider
import com.personaledge.agent.ui.components.SectionContent
import com.personaledge.agent.ui.components.SettingsRow
import com.personaledge.agent.ui.components.SettingsSection
import com.personaledge.agent.ui.components.StatusPill
import com.personaledge.agent.ui.components.ToggleRow
import com.personaledge.agent.ui.theme.CapsuleShape
import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.data.UserDataSelection
import java.util.Locale

@Composable
internal fun UserDataTransferSection(
    state: UserDataTransferState,
    onPrepareExport: (UserDataSelection) -> Unit,
    onExport: (UserDataSelection, String) -> Unit,
    onSelectImport: () -> Unit,
    onPreviewImport: (String) -> Unit,
    onImport: (String) -> Unit,
) {
    var includeConversations by remember { mutableStateOf(true) }
    var includeMemories by remember { mutableStateOf(true) }
    var includeReminders by remember { mutableStateOf(true) }
    var includeProposals by remember { mutableStateOf(true) }
    var exportPassphrase by remember { mutableStateOf("") }
    var importPassphrase by remember { mutableStateOf("") }
    var showExportPassphrase by remember { mutableStateOf(false) }
    var showImportPassphrase by remember { mutableStateOf(false) }
    var confirmingImport by remember { mutableStateOf(false) }
    val selection = UserDataSelection(
        conversations = includeConversations,
        memories = includeMemories,
        reminders = includeReminders,
        proposals = includeProposals,
    )

    SettingsSection(
        title = "암호화 내보내기·가져오기",
        footnote = "자격증명, Action Ledger, 카카오톡 알림 원문, 캘린더 provider ID는 항상 제외합니다. " +
            "가져온 캘린더 범위는 이 기기에서 다시 선택해야 합니다.",
    ) {
        ToggleRow(
            title = "대화와 메시지",
            subtitle = "선택한 파일에 로컬 대화 원문과 요약 포함",
            checked = includeConversations,
            onCheckedChange = { includeConversations = it },
            iconRes = R.drawable.ic_history,
        )
        RowDivider()
        ToggleRow(
            title = "장기 기억",
            subtitle = "유형·유효일·재확인·대체 관계 포함",
            checked = includeMemories,
            onCheckedChange = { includeMemories = it },
            iconRes = R.drawable.ic_sparkle,
        )
        RowDivider()
        ToggleRow(
            title = "리마인더와 전달 이력",
            subtitle = "가져온 뒤 이 기기 OS 예약과 다시 조정",
            checked = includeReminders,
            onCheckedChange = { includeReminders = it },
            iconRes = R.drawable.ic_bell,
        )
        RowDivider()
        ToggleRow(
            title = "일정 후보 제안함",
            subtitle = "검토·승격·무시 상태 포함",
            checked = includeProposals,
            onCheckedChange = { includeProposals = it },
            iconRes = R.drawable.ic_calendar,
        )
        RowDivider(inset = false)
        SectionContent(spacing = 8.dp) {
            OutlinedButton(
                onClick = { onPrepareExport(selection) },
                enabled = !selection.isEmpty && !state.inProgress,
                shape = CapsuleShape,
            ) {
                Text("내보내기 포함 항목 미리보기")
            }
            state.exportPreview?.let { preview ->
                Text(preview.summaryText(), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = exportPassphrase,
                onValueChange = { value ->
                    if (value.codePointCount(0, value.length) <= 128) exportPassphrase = value
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("내보내기 암호 문구") },
                supportingText = { Text("12자 이상 · 앱에 저장되지 않으며 분실 시 복구 불가") },
                singleLine = true,
                visualTransformation = if (showExportPassphrase) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { showExportPassphrase = !showExportPassphrase }) {
                        Text(if (showExportPassphrase) "숨김" else "표시")
                    }
                },
            )
            Button(
                onClick = {
                    onExport(selection, exportPassphrase)
                    exportPassphrase = ""
                },
                enabled = state.exportPreview?.selection == selection &&
                    exportPassphrase.codePointCount(0, exportPassphrase.length) >= 12 &&
                    !state.inProgress,
                shape = CapsuleShape,
            ) {
                Text("암호화 파일 저장")
            }
        }
        RowDivider(inset = false)
        SectionContent(spacing = 8.dp) {
            OutlinedButton(onClick = onSelectImport, enabled = !state.inProgress, shape = CapsuleShape) {
                Text(if (state.importFileSelected) "다른 백업 선택" else "암호화 백업 선택")
            }
            if (state.importFileSelected) {
                OutlinedTextField(
                    value = importPassphrase,
                    onValueChange = { value ->
                        if (value.codePointCount(0, value.length) <= 128) importPassphrase = value
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("가져오기 암호 문구") },
                    singleLine = true,
                    visualTransformation = if (showImportPassphrase) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showImportPassphrase = !showImportPassphrase }) {
                            Text(if (showImportPassphrase) "숨김" else "표시")
                        }
                    },
                )
                OutlinedButton(
                    onClick = { onPreviewImport(importPassphrase) },
                    enabled = importPassphrase.codePointCount(0, importPassphrase.length) >= 12 &&
                        !state.inProgress,
                    shape = CapsuleShape,
                ) {
                    Text("복호화·무결성 미리보기")
                }
            }
            state.importPreview?.let { preview ->
                Text(preview.summaryText(), style = MaterialTheme.typography.bodySmall)
                Text(
                    "기존 행은 덮어쓰지 않고 건너뜁니다. 자격증명과 실행 원장은 변하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { confirmingImport = true },
                    enabled = !state.inProgress,
                    shape = CapsuleShape,
                ) {
                    Text("검증된 데이터 가져오기")
                }
            }
            state.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.succeeded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (state.inProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    if (confirmingImport) {
        DestructiveConfirmationDialog(
            title = "검증된 백업을 가져올까요?",
            body = "기존 데이터는 덮어쓰지 않지만 새 대화·기억·리마인더가 추가됩니다. " +
                "가져온 리마인더는 이 기기 예약 상태와 다시 조정됩니다.",
            confirmLabel = "가져오기",
            onConfirm = {
                onImport(importPassphrase)
                importPassphrase = ""
                confirmingImport = false
            },
            onDismiss = { confirmingImport = false },
        )
    }
}

private fun com.personaledge.core.data.UserDataTransferPreview.summaryText(): String = buildString {
    append("대화 ").append(conversationCount).append(" · 메시지 ").append(messageCount)
    append(" · 기억 ").append(memoryCount).append(" · 리마인더 ").append(reminderCount)
    append(" · 전달 이력 ").append(deliveryCount).append(" · 후보 ").append(proposalCount)
    if (calendarRemapRequired) {
        append(" · 캘린더 재선택 필요")
        defaultCalendarLabelHint?.let { append(" (").append(it).append(')') }
    }
}
