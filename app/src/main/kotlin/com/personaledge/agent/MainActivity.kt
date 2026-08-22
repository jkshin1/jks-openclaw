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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personaledge.agent.ui.theme.PersonalEdgeAgentTheme
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.llm.InferenceBackend
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: PersonalEdgeViewModel by viewModels()
    private val createDiagnosticDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson"),
    ) { uri ->
        if (uri != null) viewModel.exportDiagnostics(uri)
    }
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
                val chatHistory by viewModel.chatHistory.collectAsStateWithLifecycle()
                val notificationSetup by viewModel.notificationSetup.collectAsStateWithLifecycle()
                val credentials by viewModel.credentials.collectAsStateWithLifecycle()
                val networkSetup by viewModel.networkSetup.collectAsStateWithLifecycle()
                val diagnosticExport by viewModel.diagnosticExport.collectAsStateWithLifecycle()

                // Calendar access and synced accounts can change while the app is backgrounded.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshCalendarSetup()
                    viewModel.refreshNotificationSetup()
                    viewModel.refreshCredentials()
                    viewModel.refreshNetworkSetup()
                }

                PersonalEdgeScreen(
                    state = state,
                    calendarSetup = calendarSetup,
                    chatHistory = chatHistory,
                    notificationSetup = notificationSetup,
                    credentials = credentials,
                    networkSetup = networkSetup,
                    diagnosticExport = diagnosticExport,
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
                    onOpenHistory = viewModel::openHistory,
                    onCloseHistory = viewModel::closeHistory,
                    onNewConversation = viewModel::startNewConversation,
                    onSwitchConversation = viewModel::switchConversation,
                    onDeleteConversation = viewModel::deleteConversation,
                    onDeleteAllConversations = viewModel::deleteAllConversations,
                    onOpenNotificationAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onSetNotificationCapture = viewModel::setNotificationCaptureEnabled,
                    onDeleteCapturedNotifications = viewModel::deleteCapturedNotifications,
                    onStoreCredential = viewModel::storeCredential,
                    onDeleteCredential = viewModel::deleteCredential,
                    onSetRouteLookupEnabled = viewModel::setRouteLookupEnabled,
                    onSetWebSearchEnabled = viewModel::setWebSearchEnabled,
                    onStoreDefaultOrigin = viewModel::storeDefaultOrigin,
                    onDeleteDefaultOrigin = viewModel::deleteDefaultOrigin,
                    onExportDiagnostics = {
                        createDiagnosticDocument.launch("personal-edge-diagnostics.jsonl")
                    },
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
    chatHistory: ChatHistoryState,
    notificationSetup: NotificationSetupState,
    credentials: CredentialsState,
    networkSetup: NetworkSetupState,
    diagnosticExport: DiagnosticExportState,
    pendingConfirmation: PendingConfirmation?,
    onRequestCalendarPermission: () -> Unit,
    onPinCalendar: (CalendarOption) -> Unit,
    onUnpinCalendar: () -> Unit,
    onOpenHistory: () -> Unit,
    onCloseHistory: () -> Unit,
    onNewConversation: () -> Unit,
    onSwitchConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onDeleteAllConversations: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onSetNotificationCapture: (Boolean) -> Unit,
    onDeleteCapturedNotifications: () -> Unit,
    onStoreCredential: (CredentialSlot, String) -> Unit,
    onDeleteCredential: (CredentialSlot) -> Unit,
    onSetRouteLookupEnabled: (Boolean) -> Unit,
    onSetWebSearchEnabled: (Boolean) -> Unit,
    onStoreDefaultOrigin: (String) -> Unit,
    onDeleteDefaultOrigin: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onPromptChange: (String) -> Unit,
    onImportModel: () -> Unit,
    onInspectModel: () -> Unit,
    onInitializeCpu: () -> Unit,
    onInitializeGpu: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onConfirmation: (String, Boolean) -> Unit,
) {
    // Expanded on first run because the model still has to be imported; collapsing is remembered
    // for the session so a returning user lands on the transcript.
    var settingsExpanded by rememberSaveable { mutableStateOf(true) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
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
                }
                // Both are disabled mid-turn: switching threads under a streaming answer would
                // attribute it to the wrong conversation.
                TextButton(onClick = onNewConversation, enabled = state.activeTurnId == null) {
                    Text("새 대화")
                }
                TextButton(onClick = onOpenHistory, enabled = state.activeTurnId == null) {
                    Text("기록")
                }
                TextButton(onClick = { settingsExpanded = !settingsExpanded }) {
                    Text(if (settingsExpanded) "설정 접기" else "설정")
                }
            }

            // The setup cards outgrew a phone screen once there were four of them, pushing the
            // input row off the bottom. They share the free space with the transcript and scroll
            // inside it, so the prompt field is reachable at every size and fold state.
            if (settingsExpanded) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ModelStatusCard(
                        state = state,
                        onImportModel = onImportModel,
                        onInspectModel = onInspectModel,
                        onInitializeCpu = onInitializeCpu,
                        onInitializeGpu = onInitializeGpu,
                    )

                    DiagnosticsExportCard(
                        state = diagnosticExport,
                        onExport = onExportDiagnostics,
                    )

                    CalendarSetupCard(
                        setup = calendarSetup,
                        onRequestPermission = onRequestCalendarPermission,
                        onPinCalendar = onPinCalendar,
                        onUnpinCalendar = onUnpinCalendar,
                    )

                    NotificationSetupCard(
                        setup = notificationSetup,
                        onOpenAccessSettings = onOpenNotificationAccess,
                        onSetCapture = onSetNotificationCapture,
                        onDeleteCaptured = onDeleteCapturedNotifications,
                    )

                    CredentialsCard(
                        credentials = credentials,
                        onStore = onStoreCredential,
                        onDelete = onDeleteCredential,
                    )

                    NetworkSetupCard(
                        setup = networkSetup,
                        onSetRouteLookupEnabled = onSetRouteLookupEnabled,
                        onSetWebSearchEnabled = onSetWebSearchEnabled,
                        onStoreDefaultOrigin = onStoreDefaultOrigin,
                        onDeleteDefaultOrigin = onDeleteDefaultOrigin,
                    )
                }
            } else {
                CompactStatusRow(state = state)
            }

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

    if (chatHistory.visible) {
        ConversationHistoryDialog(
            history = chatHistory,
            onDismiss = onCloseHistory,
            onSwitch = onSwitchConversation,
            onDelete = onDeleteConversation,
            onDeleteAll = onDeleteAllConversations,
        )
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

/** One line standing in for the collapsed setup cards, so state is never fully hidden. */
@Composable
private fun CompactStatusRow(state: PersonalEdgeUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.modelStatus.name,
            style = MaterialTheme.typography.labelMedium,
            color = statusColor(state.modelStatus),
        )
        Text(
            // thermalStatusText already begins with "열 상태"; prefixing it again read as "열 열".
            text = thermalStatusText(state.thermalStatus),
            style = MaterialTheme.typography.labelMedium,
            color = thermalStatusColor(state.thermalStatus),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        state.activeBackend?.let { backend ->
            Text(
                text = backend.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConversationHistoryDialog(
    history: ChatHistoryState,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
) {
    var confirmingDeleteAll by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("대화 기록") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (history.conversations.isEmpty()) {
                    Text(
                        text = "저장된 대화가 없습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = "대화는 기기 안에만 저장되며 백업에 포함되지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(history.conversations, key = ConversationSummaryUi::id) { conversation ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = { onSwitch(conversation.id) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = if (conversation.id == history.activeConversationId) {
                                            "✓ ${conversation.title}"
                                        } else {
                                            conversation.title
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                TextButton(onClick = { onDelete(conversation.id) }) {
                                    Text("삭제", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                history.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (confirmingDeleteAll) {
                    Text(
                        text = "모든 대화를 지웁니다. 되돌릴 수 없습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (history.conversations.isEmpty()) {
                TextButton(onClick = onDismiss) { Text("닫기") }
            } else if (confirmingDeleteAll) {
                TextButton(
                    onClick = {
                        confirmingDeleteAll = false
                        onDeleteAll()
                    },
                ) {
                    Text("모두 삭제", color = MaterialTheme.colorScheme.error)
                }
            } else {
                // Two taps, because this is the one action in the dialog with no undo.
                TextButton(onClick = { confirmingDeleteAll = true }) {
                    Text("전체 삭제", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            if (history.conversations.isNotEmpty()) {
                TextButton(
                    onClick = {
                        confirmingDeleteAll = false
                        onDismiss()
                    },
                ) {
                    Text("닫기")
                }
            }
        },
    )
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
                    Text(
                        text = "계정 이름뿐 아니라 계정 유형과 캘린더 ID를 확인하세요. " +
                            "@naver.com 주소만으로 NAVER 캘린더 동기화가 증명되지는 않습니다.",
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
                                            "✓ ${option.label} · ${option.accountName} · " +
                                                "${option.accountType} · ID ${option.id}"
                                        } else {
                                            "${option.label} · ${option.accountName} · " +
                                                "${option.accountType} · ID ${option.id}"
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
private fun CredentialsCard(
    credentials: CredentialsState,
    onStore: (CredentialSlot, String) -> Unit,
    onDelete: (CredentialSlot) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "외부 서비스 키",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "키는 Android Keystore 키로 암호화되어 저장되며, 저장 후에는 다시 볼 수 " +
                    "없습니다. 백업·기기 이전에 포함되지 않으므로 재설치하면 다시 입력해야 합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            credentials.statuses.forEach { status ->
                CredentialRow(
                    status = status,
                    onStore = onStore,
                    onDelete = onDelete,
                )
            }

            credentials.error?.let { message ->
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
private fun CredentialRow(
    status: CredentialStatus,
    onStore: (CredentialSlot, String) -> Unit,
    onDelete: (CredentialSlot) -> Unit,
) {
    // Plain remember, never rememberSaveable: a saved-state bundle would write the typed key to
    // disk in the clear, which is exactly what the vault exists to avoid.
    var entry by remember(status.slot) { mutableStateOf("") }
    var revealed by remember(status.slot) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = status.slot.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (status.stored) "저장됨" else "미설정",
                style = MaterialTheme.typography.labelMedium,
                color = if (status.stored) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = entry,
                onValueChange = { value -> entry = value },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(if (status.stored) "새 값으로 교체" else status.slot.hint) },
                // Masked by default so the key is not left on screen; the toggle is for checking
                // a paste before saving, not for reading back a stored value.
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
                        Text(if (revealed) "숨기기" else "보기", style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    onStore(status.slot, entry)
                    // Dropped as soon as it is handed over; nothing else retains it.
                    entry = ""
                    revealed = false
                },
                enabled = entry.isNotBlank(),
            ) {
                Text("저장")
            }
        }

        if (status.stored) {
            TextButton(onClick = { onDelete(status.slot) }) {
                Text("삭제", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun NetworkSetupCard(
    setup: NetworkSetupState,
    onSetRouteLookupEnabled: (Boolean) -> Unit,
    onSetWebSearchEnabled: (Boolean) -> Unit,
    onStoreDefaultOrigin: (String) -> Unit,
    onDeleteDefaultOrigin: () -> Unit,
) {
    // A home label can be sensitive. It is never rememberSaveable and the stored value is never
    // read back into UI; only a presence bit is exposed after save.
    var originEntry by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "네이버 외부 조회",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "아래 동의를 켜도 자동 전송되지 않습니다. 실제 요청마다 전송될 검색어 또는 " +
                    "출발지·도착지를 다시 보여 주고 승인을 받은 뒤 NAVER로 보냅니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ConsentRow(
                title = "경로 조회 허용",
                detail = "출발지와 도착지를 NAVER Cloud Maps에 전송",
                checked = setup.routeLookupEnabled,
                onCheckedChange = onSetRouteLookupEnabled,
            )

            OutlinedTextField(
                value = originEntry,
                onValueChange = { value ->
                    if (value.codePointCount(0, value.length) <= 80) originEntry = value
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = {
                    Text(if (setup.defaultOriginConfigured) "기본 출발지 교체" else "기본 출발지")
                },
                supportingText = {
                    Text(
                        if (setup.defaultOriginConfigured) {
                            "저장됨 · 개인정보 보호를 위해 저장된 값은 다시 표시하지 않습니다."
                        } else {
                            "예: 서울시청 (선택 사항, 기기 백업 제외 저장소에 보관)"
                        },
                    )
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onStoreDefaultOrigin(originEntry)
                        originEntry = ""
                    },
                    enabled = originEntry.isNotBlank(),
                ) {
                    Text("출발지 저장")
                }
                if (setup.defaultOriginConfigured) {
                    TextButton(onClick = onDeleteDefaultOrigin) {
                        Text("출발지 삭제", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            ConsentRow(
                title = "웹 검색 허용",
                detail = "검색어를 NAVER Search API에 전송",
                checked = setup.webSearchEnabled,
                onCheckedChange = onSetWebSearchEnabled,
            )

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
private fun ConsentRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NotificationSetupCard(
    setup: NotificationSetupState,
    onOpenAccessSettings: () -> Unit,
    onSetCapture: (Boolean) -> Unit,
    onDeleteCaptured: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "카카오톡 알림 수집",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "알림 접근을 허용하면 이 앱은 기기의 모든 알림을 보게 됩니다. " +
                    "저장하는 것은 카카오톡 메시지 알림뿐이며, 기기 밖으로 나가지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!setup.accessGranted) {
                Button(onClick = onOpenAccessSettings) {
                    Text("알림 접근 설정 열기")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (setup.captureEnabled) "수집 중" else "수집 꺼짐",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = setup.captureEnabled,
                        onCheckedChange = onSetCapture,
                    )
                }
                Text(
                    text = "저장됨 ${setup.storedCount}건 · ${setup.retentionDays}일 후 자동 삭제",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenAccessSettings) {
                        Text("접근 권한 관리")
                    }
                    if (setup.storedCount > 0) {
                        TextButton(onClick = onDeleteCaptured) {
                            Text("수집 기록 삭제", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsExportCard(
    state: DiagnosticExportState,
    onExport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "진단 내보내기",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "서명된 릴리스에서도 선택한 위치로 내용 비저장형 JSONL을 내보냅니다. " +
                    "프롬프트·모델 답변·Tool 인자/결과·API 키는 포함하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onExport, enabled = !state.inProgress) {
                Text(if (state.inProgress) "내보내는 중" else "진단 JSONL 저장")
            }
            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.succeeded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
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
