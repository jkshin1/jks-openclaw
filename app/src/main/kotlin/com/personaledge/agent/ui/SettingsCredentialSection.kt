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
internal fun CredentialSection(
    credentials: CredentialsState,
    onStore: (CredentialSlot, String) -> Unit,
    onDelete: (CredentialSlot) -> Unit,
) {
    SettingsSection(
        title = "외부 서비스 키",
        footnote = "키는 Android Keystore 키로 암호화되어 저장되며, 저장 후에는 다시 볼 수 없습니다. " +
            "Tavily 키는 저장 전에 서비스에서 확인하며 실패하면 저장하지 않습니다. " +
            "백업·기기 이전에 포함되지 않으므로 재설치하면 다시 입력해야 합니다.",
    ) {
        credentials.statuses.forEachIndexed { index, status ->
            if (index > 0) RowDivider()
            CredentialRow(status = status, onStore = onStore, onDelete = onDelete)
        }
        credentials.error?.let { message -> SectionError(message) }
    }
}

/**
 * One credential, collapsed until it is being edited.
 *
 * The entry field only exists while the row is expanded, so the typed value is dropped from
 * composition the moment the row closes — on top of the existing rule that it is held in a plain
 * `remember`, never `rememberSaveable`, which would write a key to disk in the clear.
 */
@Composable
private fun CredentialRow(
    status: CredentialStatus,
    onStore: (CredentialSlot, String) -> Unit,
    onDelete: (CredentialSlot) -> Unit,
) {
    var expanded by remember(status.slot) { mutableStateOf(false) }
    var confirmingDelete by remember(status.slot) { mutableStateOf(false) }

    Column {
        SettingsRow(
            title = status.slot.label,
            subtitle = when (status.health) {
                SecretHealth.ABSENT -> "미설정"
                SecretHealth.READABLE -> "저장됨"
                SecretHealth.UNREADABLE -> "손상됨 · 다시 입력 필요"
            },
            iconRes = R.drawable.ic_key,
            iconTint = if (status.stored) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            iconContainer = if (status.stored) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            onClick = { expanded = !expanded },
        ) {
            Text(
                text = if (expanded) "닫기" else if (status.hasCiphertext) "교체" else "입력",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        AnimatedVisibility(visible = expanded) {
            CredentialEditor(
                status = status,
                onStore = { value ->
                    onStore(status.slot, value)
                    expanded = false
                },
                onDelete = {
                    confirmingDelete = true
                },
            )
        }
    }
    if (confirmingDelete) {
        DestructiveConfirmationDialog(
            title = "저장된 자격증명을 삭제할까요?",
            body = "암호화된 값을 삭제하면 관련 공급자 기능이 즉시 중단됩니다.",
            onConfirm = {
                onDelete(status.slot)
                confirmingDelete = false
                expanded = false
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
private fun CredentialEditor(
    status: CredentialStatus,
    onStore: (String) -> Unit,
    onDelete: () -> Unit,
) {
    // Plain remember, never rememberSaveable: a saved-state bundle would write the typed key to
    // disk in the clear, which is exactly what the vault exists to avoid.
    var entry by remember(status.slot) { mutableStateOf("") }
    var revealed by remember(status.slot) { mutableStateOf(false) }

    SectionContent(spacing = 8.dp) {
        OutlinedTextField(
            value = entry,
            onValueChange = { value -> entry = value },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            label = { Text(if (status.hasCiphertext) "새 값으로 교체" else status.slot.hint) },
            // Masked by default so the key is not left on screen; the toggle is for checking a
            // paste before saving, not for reading back a stored value.
            visualTransformation = if (revealed) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
            ),
            trailingIcon = {
                TextButton(onClick = { revealed = !revealed }) {
                    Text(
                        text = if (revealed) "숨기기" else "보기",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    onStore(entry)
                    // Dropped as soon as it is handed over; nothing else retains it.
                    entry = ""
                    revealed = false
                },
                enabled = entry.isNotBlank(),
                shape = CapsuleShape,
            ) {
                Text("저장")
            }
            if (status.hasCiphertext) {
                TextButton(onClick = onDelete) {
                    Text("삭제", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
