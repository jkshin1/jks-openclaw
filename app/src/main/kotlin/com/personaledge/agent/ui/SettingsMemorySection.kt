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
internal fun MemorySection(
    setup: MemorySetupState,
    onSetEnabled: (Boolean) -> Unit,
    onStore: (String, MemoryCategory, String?) -> Unit,
    onReplace: (String, String, MemoryCategory, String?) -> Unit,
    onReconfirm: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
) {
    // A draft can contain personal context. Keep it only in composition, never a saved-state
    // bundle, and clear it immediately after the explicit save action.
    var draft by remember { mutableStateOf("") }
    var validUntil by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MemoryCategory.FACT) }
    var replacementId by remember { mutableStateOf<String?>(null) }
    var deleteCandidateId by remember { mutableStateOf<String?>(null) }
    var confirmingDeleteAll by remember { mutableStateOf(false) }

    SettingsSection(
        title = "장기 기억",
        footnote = "기기 안에만 저장되고 Android 백업·기기 이전에는 포함되지 않습니다. " +
            "대화 중 모델이 제안한 기억은 정확한 내용을 확인한 뒤에만 저장됩니다. " +
            "탐지 가능한 인증·금융정보는 차단됩니다. " +
            "알림이 필요한 일은 기억 대신 캘린더나 알람을 사용하세요.",
    ) {
        ToggleRow(
            title = "대화에서 기억 활용",
            subtitle = if (setup.enabled) {
                "관련 기억을 새 대화의 참고 데이터로 사용 · 저장됨 ${setup.memories.size}개"
            } else {
                "꺼져 있어 새 기억 제안과 답변 활용을 차단함 · 저장됨 ${setup.memories.size}개"
            },
            checked = setup.enabled,
            onCheckedChange = onSetEnabled,
            iconRes = R.drawable.ic_sparkle,
            iconTint = if (setup.enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            iconContainer = if (setup.enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        )

        if (setup.enabled) {
            RowDivider(inset = false)
            SectionContent(spacing = 8.dp) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { value ->
                        if (value.codePointCount(0, value.length) <= 240) draft = value
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    label = { Text("기억 직접 추가") },
                    supportingText = {
                        Text("오래 유효한 선호나 사실 한 문장 · 탐지 가능한 인증·금융정보 차단")
                    },
                    minLines = 2,
                    maxLines = 4,
                )
                Text(
                    text = "기억 유형",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MemoryCategory.entries.chunked(3).forEach { rowCategories ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowCategories.forEach { option ->
                            FilterChip(
                                selected = category == option,
                                onClick = { category = option },
                                label = { Text(option.displayLabel()) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = validUntil,
                    onValueChange = { value ->
                        if (value.length <= 10) validUntil = value
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    label = { Text("유효일 (선택)") },
                    placeholder = { Text("YYYY-MM-DD") },
                    supportingText = { Text("비워 두면 만료일 없음 · 오래된 기억은 180일 후 재확인") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Button(
                    onClick = {
                        val replacing = replacementId
                        if (replacing == null) {
                            onStore(draft, category, validUntil.takeIf(String::isNotBlank))
                        } else {
                            onReplace(
                                replacing,
                                draft,
                                category,
                                validUntil.takeIf(String::isNotBlank),
                            )
                        }
                        draft = ""
                        validUntil = ""
                        category = MemoryCategory.FACT
                        replacementId = null
                    },
                    enabled = draft.isNotBlank(),
                    shape = CapsuleShape,
                ) {
                    Text(if (replacementId == null) "기억 저장" else "새 내용으로 대체")
                }
                if (replacementId != null) {
                    TextButton(
                        onClick = {
                            draft = ""
                            validUntil = ""
                            category = MemoryCategory.FACT
                            replacementId = null
                        },
                    ) {
                        Text("대체 취소")
                    }
                }
            }
        }

        setup.memories.forEach { memory ->
            RowDivider()
            SettingsRow(
                title = memory.content,
                subtitle = memory.statusLabel(),
                iconRes = R.drawable.ic_history,
                iconTint = MaterialTheme.colorScheme.tertiary,
                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (memory.needsReconfirmation && !memory.expired && !memory.superseded) {
                        TextButton(onClick = { onReconfirm(memory.id) }) {
                            Text("다시 확인")
                        }
                    }
                    TextButton(
                        onClick = {
                            draft = memory.content
                            category = memory.category
                            validUntil = memory.validUntilEpochMillis?.toInclusiveLocalDate().orEmpty()
                            replacementId = memory.id
                        },
                    ) {
                        Text("대체")
                    }
                    TextButton(onClick = { deleteCandidateId = memory.id }) {
                        Text("삭제", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (setup.memories.isNotEmpty()) {
            RowDivider(inset = false)
            SettingsRow(
                title = "장기 기억 전체 삭제",
                subtitle = "대화 기록은 남고 승인된 기억만 지웁니다.",
                iconRes = R.drawable.ic_trash,
                iconTint = MaterialTheme.colorScheme.error,
                iconContainer = MaterialTheme.colorScheme.errorContainer,
                onClick = { confirmingDeleteAll = true },
            )
        }

        setup.error?.let { message -> SectionError(message) }
    }

    deleteCandidateId?.let { memoryId ->
        DestructiveConfirmationDialog(
            title = "이 기억을 삭제할까요?",
            body = "삭제한 장기 기억은 새 대화에서 더 이상 참고되지 않으며 되돌릴 수 없습니다.",
            onConfirm = {
                onDelete(memoryId)
                deleteCandidateId = null
            },
            onDismiss = { deleteCandidateId = null },
        )
    }
    if (confirmingDeleteAll) {
        DestructiveConfirmationDialog(
            title = "장기 기억을 모두 삭제할까요?",
            body = "승인해 저장한 모든 기억을 지웁니다. 대화 기록과 실행 원장은 남습니다.",
            confirmLabel = "모두 삭제",
            onConfirm = {
                onDeleteAll()
                confirmingDeleteAll = false
            },
            onDismiss = { confirmingDeleteAll = false },
        )
    }
}

private fun MemoryCategory.displayLabel(): String = when (this) {
    MemoryCategory.PREFERENCE -> "선호"
    MemoryCategory.PERSON -> "사람"
    MemoryCategory.PLACE -> "장소"
    MemoryCategory.ROUTINE -> "루틴"
    MemoryCategory.FACT -> "사실"
}

private fun com.personaledge.agent.MemorySummaryUi.statusLabel(): String = buildString {
    append(category.displayLabel())
    when {
        superseded -> append(" · 새 기억으로 대체됨 · 회상 제외")
        expired -> append(" · 유효기간 만료 · 회상 제외")
        needsReconfirmation -> append(" · 180일 경과 · 재확인 전 회상 제외")
        else -> append(" · 승인된 로컬 기억")
    }
    append(" · 저장 ")
    append(createdAtEpochMillis.toLocalDate())
    append(" · 마지막 확인 ")
    append(lastConfirmedAtEpochMillis.toLocalDate())
    validUntilEpochMillis?.let { epoch ->
        append(" · ")
        append(epoch.toInclusiveLocalDate())
        append("까지")
    }
}

private fun Long.toInclusiveLocalDate(): String = java.time.Instant.ofEpochMilli(this - 1L)
    .atZone(java.time.ZoneId.systemDefault())
    .toLocalDate()
    .toString()

private fun Long.toLocalDate(): String = java.time.Instant.ofEpochMilli(this)
    .atZone(java.time.ZoneId.systemDefault())
    .toLocalDate()
    .toString()
