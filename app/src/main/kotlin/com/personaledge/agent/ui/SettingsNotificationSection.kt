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
internal fun NotificationSection(
    setup: NotificationSetupState,
    onOpenAccessSettings: () -> Unit,
    onSetCapture: (Boolean) -> Unit,
    onSetReplyEnabled: (Boolean) -> Unit,
    onDeleteCaptured: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    SettingsSection(
        title = "카카오톡 알림 수집",
        footnote = "알림 접근을 허용하면 이 앱은 기기의 모든 알림을 보게 됩니다. 저장하는 것은 " +
            "카카오톡 메시지 알림뿐이며, 기기 밖으로 나가지 않습니다.",
    ) {
        if (!setup.accessGranted) {
            SettingsRow(
                title = "알림 접근 권한 없음",
                subtitle = "시스템 설정에서 이 앱에 알림 접근을 허용하세요.",
                iconRes = R.drawable.ic_bell,
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                iconContainer = MaterialTheme.colorScheme.surfaceContainerHigh,
                onClick = onOpenAccessSettings,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            ToggleRow(
                title = "메시지 알림 수집",
                subtitle = "저장됨 ${setup.storedCount}건 · ${setup.retentionDays}일이 지난 기록은 다음 정리 시 삭제",
                checked = setup.captureEnabled,
                onCheckedChange = onSetCapture,
                iconRes = R.drawable.ic_bell,
                iconTint = if (setup.captureEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                iconContainer = if (setup.captureEnabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            RowDivider()
            ToggleRow(
                title = "알림에서 답장 허용",
                subtitle = "활성 카카오톡 알림에 답장 액션이 있을 때만 매번 확인 후 요청합니다.",
                checked = setup.replyEnabled,
                onCheckedChange = onSetReplyEnabled,
                iconRes = R.drawable.ic_bell,
                iconTint = if (setup.replyEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                iconContainer = if (setup.replyEnabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            RowDivider()
            SettingsRow(
                title = "접근 권한 관리",
                subtitle = "시스템 알림 접근 설정을 엽니다.",
                onClick = onOpenAccessSettings,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (setup.storedCount > 0) {
                RowDivider()
                SettingsRow(
                    title = "수집 기록 삭제",
                    subtitle = "저장된 ${setup.storedCount}건을 즉시 지웁니다.",
                    iconRes = R.drawable.ic_trash,
                    iconTint = MaterialTheme.colorScheme.error,
                    iconContainer = MaterialTheme.colorScheme.errorContainer,
                    onClick = { confirmingDelete = true },
                )
            }
        }
    }
    if (confirmingDelete) {
        DestructiveConfirmationDialog(
            title = "수집한 알림 기록을 삭제할까요?",
            body = "저장된 카카오톡 알림 캐시를 모두 지웁니다. 원래 대화 앱의 내용은 변경하지 않습니다.",
            confirmLabel = "기록 삭제",
            onConfirm = {
                onDeleteCaptured()
                confirmingDelete = false
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
internal fun DiagnosticsSection(
    state: DiagnosticExportState,
    onExport: () -> Unit,
) {
    SettingsSection(
        title = "진단",
        footnote = "프롬프트·모델 답변·Tool 인자/결과·API 키는 포함하지 않습니다.",
    ) {
        SettingsRow(
            title = "진단 JSONL 저장",
            subtitle = if (state.inProgress) {
                "내보내는 중"
            } else {
                "선택한 위치로 내용 비저장형 기록을 내보냅니다."
            },
            iconRes = R.drawable.ic_chart,
            iconTint = MaterialTheme.colorScheme.secondary,
            iconContainer = MaterialTheme.colorScheme.secondaryContainer,
            enabled = !state.inProgress,
            onClick = onExport,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
        state.message?.let { message ->
            RowDivider(inset = false)
            SectionContent(spacing = 0.dp) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.succeeded) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
internal fun SectionError(message: String) {
    RowDivider(inset = false)
    SectionContent(spacing = 0.dp) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
