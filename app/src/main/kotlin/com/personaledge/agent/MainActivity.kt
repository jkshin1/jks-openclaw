package com.personaledge.agent

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personaledge.agent.ui.theme.PersonalEdgeAgentTheme
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.llm.InferenceBackend
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: PersonalEdgeViewModel by viewModels()
    private val openModelDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.importModel(uri)
        }
    }

    private val requestCalendarPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.READ_CALENDAR] == true &&
            grants[Manifest.permission.WRITE_CALENDAR] == true
        // A denial that can no longer be re-prompted has to be resolved in system settings.
        val canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR) ||
            shouldShowRequestPermissionRationale(Manifest.permission.WRITE_CALENDAR)
        viewModel.onCalendarPermissionResult(granted = granted, canAskAgain = canAskAgain)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PersonalEdgeAgentTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val pending by viewModel.confirmationCoordinator.pending.collectAsStateWithLifecycle()
                val calendarSetup by viewModel.calendarSetup.collectAsStateWithLifecycle()

                // Calendar access and synced accounts can change while the app is backgrounded.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshCalendarSetup()
                }

                PersonalEdgeScreen(
                    state = state,
                    calendarSetup = calendarSetup,
                    pendingConfirmation = pending,
                    onRequestCalendarPermission = {
                        requestCalendarPermissions.launch(
                            arrayOf(
                                Manifest.permission.READ_CALENDAR,
                                Manifest.permission.WRITE_CALENDAR,
                            ),
                        )
                    },
                    onPinCalendar = viewModel::pinCalendar,
                    onUnpinCalendar = viewModel::unpinCalendar,
                    onPromptChange = viewModel::updatePrompt,
                    onImportModel = { openModelDocument.launch(arrayOf("application/octet-stream", "*/*")) },
                    onInspectModel = viewModel::inspectInstalledModel,
                    onInitializeCpu = { viewModel.initializeRuntime(InferenceBackend.CPU) },
                    onInitializeGpu = { viewModel.initializeRuntime(InferenceBackend.GPU) },
                    onSend = viewModel::sendPrompt,
                    onCancel = viewModel::cancelTurn,
                    onConfirmation = viewModel::resolveConfirmation,
                )
            }
        }
    }
}

@Composable
private fun PersonalEdgeScreen(
    state: PersonalEdgeUiState,
    calendarSetup: CalendarSetupState,
    pendingConfirmation: PendingConfirmation?,
    onRequestCalendarPermission: () -> Unit,
    onPinCalendar: (CalendarOption) -> Unit,
    onUnpinCalendar: () -> Unit,
    onPromptChange: (String) -> Unit,
    onImportModel: () -> Unit,
    onInspectModel: () -> Unit,
    onInitializeCpu: () -> Unit,
    onInitializeGpu: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onConfirmation: (String, Boolean) -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Personal Edge Agent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "LLM은 판단하고, Kotlin Runtime이 통제하며, Tool이 실행합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ModelStatusCard(
                state = state,
                onImportModel = onImportModel,
                onInspectModel = onInspectModel,
                onInitializeCpu = onInitializeCpu,
                onInitializeGpu = onInitializeGpu,
            )

            CalendarSetupCard(
                setup = calendarSetup,
                onRequestPermission = onRequestCalendarPermission,
                onPinCalendar = onPinCalendar,
                onUnpinCalendar = onUnpinCalendar,
            )

            Conversation(
                messages = state.messages,
                modifier = Modifier.weight(1f),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier.weight(1f),
                    enabled = state.modelStatus == ModelUiStatus.READY && state.activeTurnId == null,
                    label = { Text("온디바이스 요청") },
                    placeholder = { Text("예: 내일 오후 3시에 치과 일정 넣어줘") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (state.canSend) onSend() }),
                )
                Spacer(Modifier.width(10.dp))
                if (state.activeTurnId == null) {
                    Button(onClick = onSend, enabled = state.canSend) {
                        Text("전송")
                    }
                } else {
                    OutlinedButton(onClick = onCancel) {
                        Text("취소")
                    }
                }
            }
        }
    }

    if (pendingConfirmation != null) {
        AlertDialog(
            onDismissRequest = { onConfirmation(pendingConfirmation.actionId, false) },
            title = { Text(pendingConfirmation.preview.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pendingConfirmation.preview.summary)
                    Text(
                        text = "확인한 내용 그대로 실행됩니다. 실행 직전에 권한·열 상태·캘린더를 다시 확인합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "확인 바인딩: ${pendingConfirmation.parameterDigest.take(12)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { onConfirmation(pendingConfirmation.actionId, true) }) {
                    Text("실행")
                }
            },
            dismissButton = {
                TextButton(onClick = { onConfirmation(pendingConfirmation.actionId, false) }) {
                    Text("거절")
                }
            },
        )
    }
}

@Composable
private fun CalendarSetupCard(
    setup: CalendarSetupState,
    onRequestPermission: () -> Unit,
    onPinCalendar: (CalendarOption) -> Unit,
    onUnpinCalendar: () -> Unit,
) {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "캘린더",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            when {
                !setup.permissionGranted -> {
                    Text(
                        text = "일정 조회·등록·수정에는 캘린더 권한이 필요합니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (setup.permissionPermanentlyDenied) {
                        Text(
                            text = "권한 요청이 차단되어 시스템 설정에서 직접 허용해야 합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ),
                                )
                            },
                        ) {
                            Text("앱 설정 열기")
                        }
                    } else {
                        Button(onClick = onRequestPermission) {
                            Text("캘린더 권한 허용")
                        }
                    }
                }

                setup.calendars.isEmpty() -> Text(
                    text = "사용 가능한 기기 캘린더가 없습니다. NAVER 캘린더의 Android 연동은 " +
                        "현재 검증되지 않았으므로 캘린더 안내 문서를 확인하세요.",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> {
                    Text(
                        text = if (setup.pinnedCalendarId == null) {
                            "사용할 캘린더를 하나 선택하세요. 선택한 캘린더 밖은 읽지도 쓰지도 않습니다."
                        } else {
                            "선택됨: ${setup.pinnedCalendarLabel.orEmpty()}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        setup.calendars.forEach { option ->
                            AssistChip(
                                onClick = { onPinCalendar(option) },
                                enabled = option.writable,
                                label = {
                                    Text(
                                        if (option.id == setup.pinnedCalendarId) {
                                            "✓ ${option.label} · ${option.accountName}"
                                        } else {
                                            "${option.label} · ${option.accountName}"
                                        },
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(),
                            )
                        }
                    }
                    if (setup.pinnedCalendarId != null) {
                        TextButton(onClick = onUnpinCalendar) {
                            Text("선택 해제")
                        }
                    }
                }
            }

            setup.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ModelStatusCard(
    state: PersonalEdgeUiState,
    onImportModel: () -> Unit,
    onInspectModel: () -> Unit,
    onInitializeCpu: () -> Unit,
    onInitializeGpu: () -> Unit,
) {
    val manifest = PersonalEdgeViewModel.modelManifest
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Gemma 4 E4B", fontWeight = FontWeight.SemiBold)
                Text(
                    text = state.modelStatus.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor(state.modelStatus),
                )
            }
            Text(
                text = "rev ${manifest.revision.take(12)} · ${formatGiB(manifest.sizeBytes)} GiB · context ${manifest.contextTokens} · output ${manifest.maxOutputTokens}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(state.statusText, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = thermalStatusText(state.thermalStatus),
                style = MaterialTheme.typography.bodySmall,
                color = thermalStatusColor(state.thermalStatus),
            )
            state.modelProgress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            when (state.modelStatus) {
                ModelUiStatus.MISSING,
                ModelUiStatus.REJECTED,
                ModelUiStatus.ERROR -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImportModel, enabled = !state.isBusy) {
                        Text("모델 가져오기")
                    }
                    OutlinedButton(onClick = onInspectModel, enabled = !state.isBusy) {
                        Text("다시 검사")
                    }
                }
                ModelUiStatus.VERIFIED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val thermalAllowsStart = ThermalTurnPolicy.canStart(state.thermalStatus)
                    Button(onClick = onInitializeCpu, enabled = !state.isBusy && thermalAllowsStart) {
                        Text("CPU로 로드")
                    }
                    OutlinedButton(
                        onClick = onInitializeGpu,
                        enabled = !state.isBusy && thermalAllowsStart,
                    ) {
                        Text("GPU 시도")
                    }
                }
                ModelUiStatus.READY -> Text(
                    text = "자동 Tool 실행은 꺼져 있으며 모든 호출은 Kotlin 확인 경계를 통과합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                ModelUiStatus.CHECKING,
                ModelUiStatus.IMPORTING,
                ModelUiStatus.INITIALIZING -> Unit
            }
        }
    }
}

@Composable
private fun Conversation(
    messages: List<ChatEntry>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (messages.isEmpty()) {
            item {
                Text(
                    text = "모델을 검증·로드하고 캘린더를 선택하면 일정 조회·등록·수정을 요청할 수 있습니다.",
                    modifier = Modifier.padding(vertical = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(messages, key = ChatEntry::id) { entry ->
            val arrangement = if (entry.role == ChatRole.USER) Arrangement.End else Arrangement.Start
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = arrangement) {
                Surface(
                    color = messageColor(entry.role),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillParentMaxWidth(0.86f),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = entry.role.name.lowercase().replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (entry.text.isNotEmpty()) {
                            Spacer(Modifier.height(3.dp))
                            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: ModelUiStatus): Color = when (status) {
    ModelUiStatus.READY,
    ModelUiStatus.VERIFIED -> MaterialTheme.colorScheme.primary
    ModelUiStatus.ERROR,
    ModelUiStatus.REJECTED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun thermalStatusText(status: DiagnosticThermalStatus): String = when (status) {
    DiagnosticThermalStatus.NONE -> "열 상태 NONE · 추론 가능"
    DiagnosticThermalStatus.LIGHT -> "열 상태 LIGHT · 추론 계속"
    DiagnosticThermalStatus.MODERATE -> "열 상태 MODERATE · 완화 정책으로 추론 계속"
    DiagnosticThermalStatus.SEVERE -> "열 상태 SEVERE · 완화 정책으로 추론 계속"
    DiagnosticThermalStatus.CRITICAL -> "열 상태 CRITICAL · 새 요청 차단 및 진행 요청 취소"
    DiagnosticThermalStatus.EMERGENCY -> "열 상태 EMERGENCY · 추론 즉시 중단"
    DiagnosticThermalStatus.SHUTDOWN -> "열 상태 SHUTDOWN · 추론 즉시 중단"
    DiagnosticThermalStatus.UNKNOWN -> "열 상태 확인 불가 · 안전을 위해 추론 차단"
}

@Composable
private fun thermalStatusColor(status: DiagnosticThermalStatus): Color = when {
    ThermalTurnPolicy.directive(status) != ThermalDirective.CONTINUE ->
        MaterialTheme.colorScheme.error
    status == DiagnosticThermalStatus.MODERATE || status == DiagnosticThermalStatus.SEVERE ->
        MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun messageColor(role: ChatRole): Color = when (role) {
    ChatRole.USER -> MaterialTheme.colorScheme.primaryContainer
    ChatRole.ASSISTANT -> MaterialTheme.colorScheme.secondaryContainer
    ChatRole.TOOL -> MaterialTheme.colorScheme.tertiaryContainer
    ChatRole.STATUS -> MaterialTheme.colorScheme.surfaceVariant
}

private fun formatGiB(bytes: Long): String =
    String.format(Locale.US, "%.3f", bytes.toDouble() / 1024.0 / 1024.0 / 1024.0)
