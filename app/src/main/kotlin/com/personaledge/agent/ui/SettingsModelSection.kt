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
import androidx.compose.ui.res.stringResource
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
internal fun ModelSection(
    state: PersonalEdgeUiState,
    onImportModel: () -> Unit,
    onInspectModel: () -> Unit,
    onInitializeCpu: () -> Unit,
    onInitializeGpu: () -> Unit,
) {
    val manifest = PersonalEdgeViewModel.modelManifest
    val chip = modelStatusChip(state.modelStatus)

    SettingsSection(
        title = "온디바이스 모델",
        footnote = "rev ${manifest.revision.take(12)} · ${formatGiB(manifest.sizeBytes)} GiB · " +
            "context ${manifest.contextTokens} · output ${manifest.maxOutputTokens}",
    ) {
        SettingsRow(
            title = stringResource(R.string.model_display_name),
            subtitle = state.statusText,
            iconRes = R.drawable.ic_bolt,
            iconTint = MaterialTheme.colorScheme.primary,
            iconContainer = MaterialTheme.colorScheme.primaryContainer,
        ) {
            StatusPill(label = chip.label, tone = chip.tone)
        }
        RowDivider()
        SettingsRow(
            title = thermalShortLabel(state.thermalStatus),
            subtitle = thermalDetail(state.thermalStatus),
            iconRes = R.drawable.ic_chart,
            iconTint = com.personaledge.agent.ui.components.toneColor(
                thermalTone(state.thermalStatus),
            ),
            iconContainer = com.personaledge.agent.ui.components.toneContainerColor(
                thermalTone(state.thermalStatus),
            ),
        )
        state.activeBackend?.let { backend ->
            RowDivider()
            SettingsRow(
                title = "추론 백엔드",
                subtitle = "현재 ${backend.name}에서 실행 중",
                iconRes = R.drawable.ic_sparkle,
                iconTint = MaterialTheme.colorScheme.tertiary,
                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
            )
        }

        state.modelProgress?.let { progress ->
            RowDivider(inset = false)
            SectionContent(spacing = 6.dp) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (state.modelStatus) {
            ModelUiStatus.MISSING,
            ModelUiStatus.REJECTED,
            ModelUiStatus.ERROR -> {
                RowDivider(inset = false)
                SectionContent {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onImportModel,
                            enabled = !state.isBusy,
                            shape = CapsuleShape,
                        ) {
                            Text("모델 가져오기")
                        }
                        OutlinedButton(
                            onClick = onInspectModel,
                            enabled = !state.isBusy,
                            shape = CapsuleShape,
                        ) {
                            Text("다시 검사")
                        }
                    }
                }
            }

            ModelUiStatus.VERIFIED -> {
                RowDivider(inset = false)
                BackendPicker(
                    enabled = !state.isBusy && ThermalTurnPolicy.canStart(state.thermalStatus),
                    onInitializeCpu = onInitializeCpu,
                    onInitializeGpu = onInitializeGpu,
                )
            }

            ModelUiStatus.READY -> {
                RowDivider()
                SettingsRow(
                    title = "조회는 허용 범위에서 자동 실행",
                    subtitle = "상태 변경은 실행 전 확인하며 모든 호출은 Kotlin 안전 경계를 통과합니다.",
                    iconRes = R.drawable.ic_shield,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                )
            }

            ModelUiStatus.CHECKING,
            ModelUiStatus.IMPORTING,
            ModelUiStatus.INITIALIZING -> Unit
        }
    }
}

/**
 * GPU is the default attempt and CPU is the documented fallback, so the backend is chosen first
 * and loaded second. Loading immediately from a two-button row made the fallback look like a
 * second, equal action rather than a retry.
 */
@Composable
private fun BackendPicker(
    enabled: Boolean,
    onInitializeCpu: () -> Unit,
    onInitializeGpu: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(InferenceBackend.GPU) }

    SectionContent {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            InferenceBackend.entries.forEachIndexed { index, backend ->
                SegmentedButton(
                    selected = selected == backend,
                    onClick = { selected = backend },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = InferenceBackend.entries.size,
                    ),
                    label = { Text(if (backend == InferenceBackend.GPU) "GPU" else "CPU") },
                )
            }
        }
        Text(
            text = if (selected == InferenceBackend.GPU) {
                "GPU 가속을 시도합니다. 실패하면 CPU로 다시 불러오세요."
            } else {
                "CPU는 느리지만 GPU를 쓸 수 없을 때의 대비책입니다."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                if (selected == InferenceBackend.GPU) onInitializeGpu() else onInitializeCpu()
            },
            enabled = enabled,
            shape = CapsuleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("${selected.name}로 불러오기")
        }
    }
}


private fun formatGiB(bytes: Long): String =
    String.format(Locale.US, "%.3f", bytes.toDouble() / 1024.0 / 1024.0 / 1024.0)
