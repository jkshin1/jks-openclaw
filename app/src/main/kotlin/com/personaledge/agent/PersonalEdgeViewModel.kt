package com.personaledge.agent

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personaledge.core.agent.AgentEvent
import com.personaledge.core.agent.AgentFailureCode
import com.personaledge.core.agent.AgentLoopLimits
import com.personaledge.core.agent.ManualToolAgentController
import com.personaledge.core.agent.ManualToolRegistry
import com.personaledge.core.data.AgentSettings
import com.personaledge.core.data.MessageRole
import com.personaledge.core.diagnostics.DiagnosticBackend
import com.personaledge.core.diagnostics.DiagnosticConfirmationOutcome
import com.personaledge.core.diagnostics.DiagnosticErrorCode
import com.personaledge.core.diagnostics.DiagnosticEvent
import com.personaledge.core.diagnostics.DiagnosticExportResult
import com.personaledge.core.diagnostics.DiagnosticPhase
import com.personaledge.core.diagnostics.DiagnosticResult
import com.personaledge.core.diagnostics.DiagnosticThermalAction
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.diagnostics.DiagnosticToolStage
import com.personaledge.core.diagnostics.DiagnosticTurnCancellationCause
import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.llm.InstalledModelState
import com.personaledge.core.llm.LiteRtLlmRuntime
import com.personaledge.core.llm.LlmRuntimeException
import com.personaledge.core.llm.LlmState
import com.personaledge.core.llm.MAX_USER_PROMPT_BYTES
import com.personaledge.core.llm.ModelArtifactStore
import com.personaledge.core.llm.ModelStoreException
import com.personaledge.core.llm.PinnedModelManifest
import com.personaledge.core.llm.TurnId
import com.personaledge.core.llm.VerifiedInstalledModel
import com.personaledge.core.tools.AlarmNextTool
import com.personaledge.core.tools.AlarmSetTool
import com.personaledge.core.tools.CalendarAccount
import com.personaledge.core.tools.CalendarCreateEventTool
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.CalendarUpdateEventTool
import com.personaledge.core.tools.NotificationSearchTool
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.WebSearchTool
import com.personaledge.core.tools.ToolOrchestrator
import com.personaledge.core.tools.ToolExecutionOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ModelUiStatus {
    CHECKING,
    MISSING,
    REJECTED,
    IMPORTING,
    VERIFIED,
    INITIALIZING,
    READY,
    ERROR,
}

enum class ChatRole {
    USER,
    ASSISTANT,
    TOOL,
    STATUS,
}

data class ChatEntry(
    val id: String,
    val role: ChatRole,
    val text: String,
)

data class CalendarOption(
    val id: Long,
    val label: String,
    val accountName: String,
    val accountType: String,
    val writable: Boolean,
)

/**
 * Which calendar the agent may touch.
 *
 * The list shows calendars published through `CalendarContract`; the user pins one and nothing
 * outside it is read or written. Actual NAVER Calendar publication on Android is a separate,
 * currently unresolved physical-device qualification gate.
 */
data class CalendarSetupState(
    val permissionGranted: Boolean = false,
    val permissionPermanentlyDenied: Boolean = false,
    val calendars: List<CalendarOption> = emptyList(),
    val pinnedCalendarId: Long? = null,
    val pinnedCalendarLabel: String? = null,
    val error: String? = null,
) {
    val isReady: Boolean
        get() = permissionGranted && pinnedCalendarId != null
}

/**
 * Notification capture is off by default and stays off until both gates are open.
 *
 * [accessGranted] is a system-level grant that lets this app see every notification on the device;
 * [captureEnabled] is the user's decision to actually store KakaoTalk messages. Showing them
 * separately makes it obvious that revoking one does not revoke the other.
 */
data class NotificationSetupState(
    val accessGranted: Boolean = false,
    val captureEnabled: Boolean = false,
    val storedCount: Long = 0,
    val retentionDays: Int = AgentSettings.DEFAULT_NOTIFICATION_RETENTION_DAYS,
)

/**
 * Presence only. A typed value lives in the text field composable and nowhere else — not in this
 * state, not in saved instance state, and never in diagnostics.
 */
data class CredentialsState(
    val statuses: List<CredentialStatus> = emptyList(),
    val error: String? = null,
)

/** Presence and consent only; the stored home label itself is never exposed back to UI state. */
data class NetworkSetupState(
    val routeLookupEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val defaultOriginConfigured: Boolean = false,
    val error: String? = null,
)

data class DiagnosticExportState(
    val inProgress: Boolean = false,
    val message: String? = null,
    val succeeded: Boolean = false,
)

data class PersonalEdgeUiState(
    val modelStatus: ModelUiStatus = ModelUiStatus.CHECKING,
    val modelProgress: Float? = null,
    val statusText: String = "고정 모델을 확인하는 중입니다.",
    val activeBackend: InferenceBackend? = null,
    val prompt: String = "",
    val messages: List<ChatEntry> = emptyList(),
    val activeTurnId: TurnId? = null,
    val thermalStatus: DiagnosticThermalStatus = DiagnosticThermalStatus.UNKNOWN,
) {
    val isBusy: Boolean
        get() = modelStatus == ModelUiStatus.IMPORTING ||
            modelStatus == ModelUiStatus.INITIALIZING || activeTurnId != null

    val canSend: Boolean
        get() = modelStatus == ModelUiStatus.READY && activeTurnId == null && prompt.isNotBlank() &&
            ThermalTurnPolicy.canStart(thermalStatus)
}

class PersonalEdgeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val diagnostics = application.personalEdgeDiagnostics()
    private val thermalMonitor = ThermalStatusMonitor.create(application)
    private val modelStore = ModelArtifactStore(application)
    private val runtime = LiteRtLlmRuntime(
        context = application,
        cpuThreadCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
    )
    private val container = application.appContainer()
    private val registry = ManualToolRegistry.forDeviceTools(
        queryTool = CalendarQueryTool(container.scopedCalendar),
        createEventTool = CalendarCreateEventTool(
            gateway = container.scopedCalendar,
            defaultCalendarId = container::pinnedCalendarId,
        ),
        updateEventTool = CalendarUpdateEventTool(container.scopedCalendar),
        alarmSetTool = AlarmSetTool(container.alarms),
        alarmNextTool = AlarmNextTool(container.alarms),
        notificationSearchTool = NotificationSearchTool(container.notificationGateway),
        routeEstimateTool = RouteEstimateTool(
            gateway = container.routes,
            defaultOrigin = container::defaultOriginLabel,
        ),
        webSearchTool = WebSearchTool(container.webSearch),
    )
    val confirmationCoordinator = ConfirmationCoordinator(
        diagnostics = diagnostics,
        markPhase = diagnostics::markPhase,
    )
    private val orchestrator = ToolOrchestrator(
        // Durable and process-persistent: side-effecting tools are refused without it.
        actionLedger = container.actionLedger,
        userConfirmationGate = confirmationCoordinator,
        executionInterlock = DeviceExecutionInterlock(
            context = application,
            thermalStatus = { thermalMonitor.observation.value.status },
            pinnedCalendarId = container::pinnedCalendarId,
            calendarIsReadable = container::calendarIsReadable,
            alarmGateway = container.alarms,
            notificationGateway = container.notificationGateway,
            networkConsent = { toolName ->
                NetworkToolConsent.isEnabled(toolName, container.settings.current())
            },
        ),
    )
    private val controller = ManualToolAgentController(
        runtime = runtime,
        registry = registry,
        orchestrator = orchestrator,
    )

    private val _uiState = MutableStateFlow(
        PersonalEdgeUiState(thermalStatus = thermalMonitor.observation.value.status),
    )
    val uiState: StateFlow<PersonalEdgeUiState> = _uiState.asStateFlow()

    private val history = ChatHistoryCoordinator(container.conversations)
    private val summarizer = ConversationSummarizer(container.conversations)
    private val _chatHistory = MutableStateFlow(ChatHistoryState())
    val chatHistory: StateFlow<ChatHistoryState> = _chatHistory.asStateFlow()

    private var verifiedModel: VerifiedInstalledModel? = null
    private var modelJob: Job? = null
    private var turnJob: Job? = null
    private var summaryJob: Job? = null
    private val unresolvedActionWarningGate = UnresolvedActionWarningGate()
    private val activeThermalInitialization = AtomicReference<ActiveThermalInitialization?>(null)
    private val activeThermalTurn = AtomicReference<ActiveThermalTurn?>(null)
    private val thermalDirectiveSubscription: AutoCloseable

    init {
        restoreConversation()
        thermalDirectiveSubscription = thermalMonitor.setDirectiveListener(::applyThermalObservation)
        viewModelScope.launch {
            var lastRecordedStatus: DiagnosticThermalStatus? = null
            fun applyObservation(observation: ThermalObservation) {
                _uiState.update { it.copy(thermalStatus = observation.status) }
                if (lastRecordedStatus != observation.status) {
                    diagnostics.recordSafely(
                        DiagnosticEvent.ThermalGuard(
                            status = observation.status,
                            action = DiagnosticThermalAction.STATUS_OBSERVED,
                        ),
                    )
                    lastRecordedStatus = observation.status
                }
            }
            applyObservation(thermalMonitor.observation.value)
            thermalMonitor.statusEvents.collect(::applyObservation)
        }
        inspectInstalledModel()
    }

    fun updatePrompt(value: String) {
        if (value.toByteArray(Charsets.UTF_8).size <= MAX_PROMPT_BYTES) {
            _uiState.update { it.copy(prompt = value) }
        }
    }

    fun inspectInstalledModel() {
        if (modelJob?.isActive == true || turnJob?.isActive == true) return
        modelJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            diagnostics.markPhase(DiagnosticPhase.MODEL_INSPECT)
            _uiState.update {
                it.copy(
                    modelStatus = ModelUiStatus.CHECKING,
                    modelProgress = 0f,
                    statusText = "앱 전용 저장소의 모델 무결성을 확인하는 중입니다.",
                )
            }
            try {
                when (val installed = modelStore.inspect(::updateModelProgress)) {
                    InstalledModelState.Missing -> {
                        verifiedModel = null
                        diagnostics.recordSafely(
                            DiagnosticEvent.ModelInspected(
                                sizeBytes = modelManifest.sizeBytes,
                                durationMillis = diagnosticDuration(startedAt),
                                result = DiagnosticResult.MISSING,
                            ),
                        )
                        setModelStatus(ModelUiStatus.MISSING, "검증된 모델이 없습니다.")
                    }
                    is InstalledModelState.Rejected -> {
                        verifiedModel = null
                        diagnostics.recordSafely(
                            DiagnosticEvent.ModelInspected(
                                sizeBytes = modelManifest.sizeBytes,
                                durationMillis = diagnosticDuration(startedAt),
                                result = DiagnosticResult.REJECTED,
                                errorCode = installed.code.toDiagnosticErrorCode(),
                            ),
                        )
                        setModelStatus(
                            ModelUiStatus.REJECTED,
                            "설치된 모델이 고정된 무결성 정책을 통과하지 못했습니다.",
                        )
                    }
                    is InstalledModelState.Ready -> {
                        verifiedModel = installed.model
                        diagnostics.recordSafely(
                            DiagnosticEvent.ModelInspected(
                                sizeBytes = modelManifest.sizeBytes,
                                durationMillis = diagnosticDuration(startedAt),
                                result = DiagnosticResult.SUCCESS,
                            ),
                        )
                        setModelStatus(ModelUiStatus.VERIFIED, "고정 모델 검증을 완료했습니다.")
                    }
                }
            } catch (cancelled: CancellationException) {
                diagnostics.recordSafely(
                    DiagnosticEvent.ModelInspected(
                        sizeBytes = modelManifest.sizeBytes,
                        durationMillis = diagnosticDuration(startedAt),
                        result = DiagnosticResult.CANCELLED,
                    ),
                )
                throw cancelled
            } catch (failure: Exception) {
                verifiedModel = null
                diagnostics.recordSafely(
                    DiagnosticEvent.ModelInspected(
                        sizeBytes = modelManifest.sizeBytes,
                        durationMillis = diagnosticDuration(startedAt),
                        result = DiagnosticResult.FAILURE,
                        errorCode = DiagnosticErrorCode.INSPECTION_FAILED,
                        failure = failure.toDiagnosticFailureOrNull(),
                    ),
                )
                setModelStatus(ModelUiStatus.ERROR, "모델 파일을 검사할 수 없습니다.")
            } finally {
                finishDiagnosticPhase()
            }
        }
    }

    fun importModel(uri: Uri) {
        if (modelJob?.isActive == true || turnJob?.isActive == true) return
        modelJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            diagnostics.markPhase(DiagnosticPhase.MODEL_IMPORT)
            _uiState.update {
                it.copy(
                    modelStatus = ModelUiStatus.IMPORTING,
                    modelProgress = 0f,
                    statusText = "모델을 앱 전용 저장소로 복사하고 검증하는 중입니다.",
                )
            }
            try {
                verifiedModel = modelStore.importFrom(uri, ::updateModelProgress)
                diagnostics.recordSafely(
                    DiagnosticEvent.ModelImported(
                        sizeBytes = modelManifest.sizeBytes,
                        durationMillis = diagnosticDuration(startedAt),
                        result = DiagnosticResult.SUCCESS,
                    ),
                )
                setModelStatus(ModelUiStatus.VERIFIED, "모델 설치와 SHA-256 검증을 완료했습니다.")
            } catch (cancelled: CancellationException) {
                diagnostics.recordSafely(
                    DiagnosticEvent.ModelImported(
                        sizeBytes = modelManifest.sizeBytes,
                        durationMillis = diagnosticDuration(startedAt),
                        result = DiagnosticResult.CANCELLED,
                    ),
                )
                throw cancelled
            } catch (failure: ModelStoreException) {
                verifiedModel = null
                diagnostics.recordSafely(
                    DiagnosticEvent.ModelImported(
                        sizeBytes = modelManifest.sizeBytes,
                        durationMillis = diagnosticDuration(startedAt),
                        result = DiagnosticResult.FAILURE,
                        errorCode = failure.code.toDiagnosticErrorCode(),
                        failure = failure.toDiagnosticFailureOrNull(),
                    ),
                )
                setModelStatus(ModelUiStatus.ERROR, "모델 설치에 실패했습니다. 원본과 저장공간을 확인하세요.")
            } catch (failure: Exception) {
                verifiedModel = null
                diagnostics.recordSafely(
                    DiagnosticEvent.ModelImported(
                        sizeBytes = modelManifest.sizeBytes,
                        durationMillis = diagnosticDuration(startedAt),
                        result = DiagnosticResult.FAILURE,
                        errorCode = DiagnosticErrorCode.IMPORT_FAILED,
                        failure = failure.toDiagnosticFailureOrNull(),
                    ),
                )
                setModelStatus(ModelUiStatus.ERROR, "모델 파일을 읽을 수 없습니다.")
            } finally {
                finishDiagnosticPhase()
            }
        }
    }

    fun initializeRuntime(backend: InferenceBackend) {
        if (modelJob?.isActive == true || turnJob?.isActive == true) return
        val model = verifiedModel ?: run {
            setModelStatus(ModelUiStatus.MISSING, "먼저 고정 모델을 가져와 검증하세요.")
            return
        }
        val thermalObservation = thermalMonitor.refresh()
        if (!ThermalTurnPolicy.canStart(thermalObservation.status)) {
            diagnostics.recordSafely(
                DiagnosticEvent.ThermalGuard(
                    status = thermalObservation.status,
                    action = DiagnosticThermalAction.RUNTIME_INITIALIZATION_REJECTED,
                ),
            )
            setModelStatus(
                ModelUiStatus.VERIFIED,
                if (thermalObservation.status == DiagnosticThermalStatus.UNKNOWN) {
                    "기기 열 상태를 확인할 수 없어 런타임을 초기화하지 않았습니다."
                } else {
                    "기기 열 보호 정책이 런타임 초기화를 허용하지 않습니다."
                },
            )
            return
        }
        val thermalInitialization = ActiveThermalInitialization(
            baselineStopSequence = thermalObservation.stopSequence,
        )
        if (!activeThermalInitialization.compareAndSet(null, thermalInitialization)) return
        modelJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            diagnostics.markPhase(DiagnosticPhase.RUNTIME_INITIALIZATION)
            _uiState.update {
                it.copy(
                    modelStatus = ModelUiStatus.INITIALIZING,
                    modelProgress = null,
                    statusText = "LiteRT-LM을 초기화하는 중입니다.",
                )
            }
            try {
                val latestThermalObservation = thermalMonitor.observation.value
                if (latestThermalObservation.stopSequence > thermalInitialization.baselineStopSequence) {
                    applyThermalInitializationDirective(
                        initialization = thermalInitialization,
                        observation = latestThermalObservation,
                    )
                }
                if (thermalInitialization.latch.currentDecision() != null) {
                    throw CancellationException(
                        "Thermal policy stopped runtime initialization before native entry.",
                    )
                }
                runtime.initialize(
                    model = model,
                    backend = backend,
                    tools = controller.toolDefinitions,
                )
                val active = (runtime.state.value as? LlmState.Ready)?.backend ?: backend
                diagnostics.recordSafely(
                    DiagnosticEvent.RuntimeInitialized(
                        requestedBackend = backend.toDiagnosticBackend(),
                        activeBackend = active.toDiagnosticBackend(),
                        durationMillis = diagnosticDuration(startedAt),
                        result = DiagnosticResult.SUCCESS,
                    ),
                )
                _uiState.update {
                    it.copy(
                        modelStatus = ModelUiStatus.READY,
                        modelProgress = null,
                        activeBackend = active,
                        statusText = if (backend == InferenceBackend.GPU && active == InferenceBackend.CPU) {
                            "GPU 초기화가 실패해 CPU로 안전하게 전환했습니다."
                        } else {
                            "온디바이스 모델이 ${active.name} 백엔드로 준비됐습니다."
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                diagnostics.recordSafely(
                    DiagnosticEvent.RuntimeInitialized(
                        requestedBackend = backend.toDiagnosticBackend(),
                        activeBackend = null,
                        durationMillis = diagnosticDuration(startedAt),
                        result = DiagnosticResult.CANCELLED,
                    ),
                )
                setModelStatus(
                    ModelUiStatus.VERIFIED,
                    if (thermalInitialization.latch.currentDecision() != null) {
                        "기기 열 보호 정책에 따라 런타임 초기화를 중단했습니다."
                    } else {
                        "런타임 초기화가 취소되었습니다."
                    },
                )
                throw cancelled
            } catch (failure: LlmRuntimeException) {
                diagnostics.recordSafely(
                    DiagnosticEvent.RuntimeInitialized(
                        requestedBackend = backend.toDiagnosticBackend(),
                        activeBackend = null,
                        durationMillis = diagnosticDuration(startedAt),
                        result = DiagnosticResult.FAILURE,
                        errorCode = failure.code.toDiagnosticErrorCode(),
                        failure = failure.toDiagnosticFailureOrNull(),
                    ),
                )
                setModelStatus(ModelUiStatus.ERROR, "LiteRT-LM 초기화에 실패했습니다.")
            } catch (failure: Exception) {
                diagnostics.recordSafely(
                    DiagnosticEvent.RuntimeInitialized(
                        requestedBackend = backend.toDiagnosticBackend(),
                        activeBackend = null,
                        durationMillis = diagnosticDuration(startedAt),
                        result = DiagnosticResult.FAILURE,
                        errorCode = DiagnosticErrorCode.INITIALIZATION_FAILED,
                        failure = failure.toDiagnosticFailureOrNull(),
                    ),
                )
                setModelStatus(ModelUiStatus.ERROR, "모델 런타임을 시작할 수 없습니다.")
            } finally {
                activeThermalInitialization.compareAndSet(thermalInitialization, null)
                finishDiagnosticPhase()
            }
        }.also(thermalInitialization.job::set)
    }

    /**
     * Restores the most recent thread so closing and reopening the app does not look like data
     * loss. Only stored roles come back; transient status notices are not persisted.
     */
    private fun restoreConversation() {
        viewModelScope.launch {
            val restored = history.restoreMostRecent()
            val unresolvedWarning = unresolvedActionWarningGate.load {
                container.actionLedger.unresolvedActionCheck()
            }
            _chatHistory.update { state ->
                state.copy(activeConversationId = restored.conversationId)
            }
            if (restored.entries.isNotEmpty() || unresolvedWarning != null) {
                _uiState.update { state ->
                    state.copy(
                        messages = restored.entries + listOfNotNull(
                            unresolvedWarning?.let { warning ->
                                ChatEntry(
                                    id = UNRESOLVED_ACTION_WARNING_ID,
                                    role = ChatRole.STATUS,
                                    text = warning,
                                )
                            },
                        ),
                    )
                }
            }
        }
    }

    fun openHistory() {
        viewModelScope.launch {
            _chatHistory.update { state ->
                state.copy(visible = true, conversations = history.listConversations(), error = null)
            }
        }
    }

    fun closeHistory() {
        _chatHistory.update { state -> state.copy(visible = false, error = null) }
    }

    /** Leaves the stored thread untouched; the next message creates a new one. */
    fun startNewConversation() {
        if (_uiState.value.activeTurnId != null) return
        _chatHistory.update { state -> state.copy(activeConversationId = null, visible = false) }
        _uiState.update { state -> state.copy(messages = emptyList()) }
    }

    fun switchConversation(conversationId: String) {
        if (_uiState.value.activeTurnId != null) return
        viewModelScope.launch {
            val restored = history.switchTo(conversationId)
            _chatHistory.update { state ->
                state.copy(activeConversationId = restored.conversationId, visible = false)
            }
            _uiState.update { state -> state.copy(messages = restored.entries) }
        }
    }

    fun deleteConversation(conversationId: String) {
        if (_uiState.value.activeTurnId != null) return
        viewModelScope.launch {
            val deleted = history.delete(conversationId)
            val clearedActive = deleted && _chatHistory.value.activeConversationId == conversationId
            if (clearedActive) {
                _uiState.update { state -> state.copy(messages = emptyList()) }
            }
            _chatHistory.update { state ->
                state.copy(
                    conversations = history.listConversations(),
                    activeConversationId = if (clearedActive) null else state.activeConversationId,
                    error = if (deleted) null else "대화를 삭제하지 못했습니다.",
                )
            }
        }
    }

    /**
     * Erases every stored transcript. The action ledger lives in a separate database and is
     * deliberately untouched, so this cannot re-enable an already-executed side effect.
     */
    fun deleteAllConversations() {
        if (_uiState.value.activeTurnId != null) return
        viewModelScope.launch {
            val deleted = history.deleteAll()
            _uiState.update { state -> state.copy(messages = emptyList()) }
            _chatHistory.update { state ->
                state.copy(
                    conversations = history.listConversations(),
                    activeConversationId = null,
                    error = if (deleted) null else "대화 기록을 모두 삭제하지 못했습니다.",
                )
            }
        }
    }

    private val _credentials = MutableStateFlow(CredentialsState())
    val credentials: StateFlow<CredentialsState> = _credentials.asStateFlow()

    fun refreshCredentials() {
        viewModelScope.launch {
            _credentials.value = CredentialsState(statuses = container.credentials.statuses())
        }
    }

    /**
     * Stores one credential. The value is used here and dropped; nothing retains it, and the
     * rejection reason never contains what the user typed.
     */
    fun storeCredential(slot: CredentialSlot, value: String) {
        viewModelScope.launch {
            val error = when (val result = container.credentials.store(slot, value)) {
                CredentialStoreResult.Stored -> null
                is CredentialStoreResult.Rejected -> result.reason
            }
            _credentials.value = CredentialsState(
                statuses = container.credentials.statuses(),
                error = error,
            )
        }
    }

    fun deleteCredential(slot: CredentialSlot) {
        viewModelScope.launch {
            val deleted = container.credentials.delete(slot)
            _credentials.value = CredentialsState(
                statuses = container.credentials.statuses(),
                error = if (deleted) null else "키를 삭제하지 못했습니다.",
            )
        }
    }

    private val _networkSetup = MutableStateFlow(NetworkSetupState())
    val networkSetup: StateFlow<NetworkSetupState> = _networkSetup.asStateFlow()

    /** Re-read because DataStore can fail independently and every failure must look like opt-out. */
    fun refreshNetworkSetup() {
        viewModelScope.launch { loadNetworkSetup() }
    }

    fun setRouteLookupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val failure = runCatching { container.settings.setRouteLookupEnabled(enabled) }
                .exceptionOrNull()
            loadNetworkSetup(if (failure == null) null else "경로 조회 동의를 저장하지 못했습니다.")
        }
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val failure = runCatching { container.settings.setWebSearchEnabled(enabled) }
                .exceptionOrNull()
            loadNetworkSetup(if (failure == null) null else "웹 검색 동의를 저장하지 못했습니다.")
        }
    }

    fun storeDefaultOrigin(raw: String) {
        when (val validation = SettingsTextPolicy.validateDefaultOrigin(raw)) {
            is SettingsTextValidation.Invalid -> {
                _networkSetup.update { state -> state.copy(error = validation.reason) }
            }
            is SettingsTextValidation.Valid -> viewModelScope.launch {
                val failure = runCatching {
                    container.settings.setDefaultOriginLabel(validation.value)
                }.exceptionOrNull()
                loadNetworkSetup(
                    if (failure == null) null else "기본 출발지를 저장하지 못했습니다.",
                )
            }
        }
    }

    fun deleteDefaultOrigin() {
        viewModelScope.launch {
            val failure = runCatching { container.settings.setDefaultOriginLabel(null) }
                .exceptionOrNull()
            loadNetworkSetup(if (failure == null) null else "기본 출발지를 삭제하지 못했습니다.")
        }
    }

    private suspend fun loadNetworkSetup(error: String? = null) {
        val settings = runCatching { container.settings.current() }.getOrNull()
        _networkSetup.value = NetworkSetupState(
            routeLookupEnabled = settings?.routeLookupEnabled ?: false,
            webSearchEnabled = settings?.webSearchEnabled ?: false,
            defaultOriginConfigured = settings?.defaultOriginLabel != null,
            error = error ?: if (settings == null) "네트워크 설정을 읽지 못했습니다." else null,
        )
    }

    private val _diagnosticExport = MutableStateFlow(DiagnosticExportState())
    val diagnosticExport: StateFlow<DiagnosticExportState> = _diagnosticExport.asStateFlow()

    /** SAF destination export works in both debug and signed release builds; no run-as required. */
    fun exportDiagnostics(destination: Uri) {
        if (_diagnosticExport.value.inProgress) return
        _diagnosticExport.value = DiagnosticExportState(inProgress = true)
        val accepted = diagnostics.exportContentFreeJsonl(
            openDestination = {
                getApplication<Application>().contentResolver.openOutputStream(destination, "wt")
            },
            onComplete = { result ->
                _diagnosticExport.value = when (result) {
                    is DiagnosticExportResult.Success -> DiagnosticExportState(
                        message = "내용 비저장형 진단 ${result.sourceFileCount}개 파일, " +
                            "${result.byteCount}바이트를 내보냈습니다.",
                        succeeded = true,
                    )
                    DiagnosticExportResult.Unavailable -> DiagnosticExportState(
                        message = "진단 저장소를 사용할 수 없습니다.",
                    )
                    DiagnosticExportResult.SourceRejected -> DiagnosticExportState(
                        message = "안전 검사를 통과하지 못한 진단 원본이 있어 내보내지 않았습니다.",
                    )
                    DiagnosticExportResult.DestinationFailed -> DiagnosticExportState(
                        message = "선택한 위치에 진단 파일을 쓰지 못했습니다.",
                    )
                }
            },
        )
        if (!accepted) {
            _diagnosticExport.value = DiagnosticExportState(
                message = "진단 작업을 시작하지 못했습니다.",
            )
        }
    }

    private val _notificationSetup = MutableStateFlow(NotificationSetupState())
    val notificationSetup: StateFlow<NotificationSetupState> = _notificationSetup.asStateFlow()

    /** Re-read on resume: notification access is granted outside this app and can be revoked there. */
    fun refreshNotificationSetup() {
        viewModelScope.launch {
            val settings = runCatching { container.settings.current() }.getOrNull()
            _notificationSetup.value = NotificationSetupState(
                accessGranted = container.notificationGateway.accessGranted(),
                captureEnabled = settings?.notificationCaptureEnabled ?: false,
                storedCount = container.notificationGateway.storedCount(),
                retentionDays = settings?.notificationRetentionDays
                    ?: AgentSettings.DEFAULT_NOTIFICATION_RETENTION_DAYS,
            )
        }
    }

    fun setNotificationCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { container.settings.setNotificationCaptureEnabled(enabled) }
            refreshNotificationSetup()
        }
    }

    /** Erases captured messages without touching the grant, so capture can continue afterwards. */
    fun deleteCapturedNotifications() {
        viewModelScope.launch {
            container.notificationGateway.deleteAll()
            refreshNotificationSetup()
        }
    }

    private val _calendarSetup = MutableStateFlow(CalendarSetupState())
    val calendarSetup: StateFlow<CalendarSetupState> = _calendarSetup.asStateFlow()

    /**
     * Re-reads permission state, the synced calendar list, and the pinned choice.
     *
     * Called whenever the screen resumes because the user can revoke calendar access or remove a
     * synced account from outside the app.
     */
    fun refreshCalendarSetup() {
        viewModelScope.launch {
            val granted = hasCalendarReadPermission()
            val settings = runCatching { container.settings.current() }.getOrNull()

            if (!granted) {
                _calendarSetup.value = CalendarSetupState(
                    permissionGranted = false,
                    pinnedCalendarId = settings?.defaultCalendarId,
                    pinnedCalendarLabel = settings?.defaultCalendarLabel,
                )
                return@launch
            }

            val writableIds = runCatching { container.deviceCalendars.writableCalendars() }
                .getOrDefault(emptyList())
                .map(CalendarAccount::id)
                .toSet()
            val calendars = runCatching { container.deviceCalendars.syncedCalendars() }

            _calendarSetup.value = CalendarSetupState(
                permissionGranted = true,
                calendars = calendars.getOrDefault(emptyList()).map { calendar ->
                    CalendarOption(
                        id = calendar.id,
                        label = SettingsTextPolicy.sanitizeProviderLabel(calendar.displayName),
                        accountName = SettingsTextPolicy.sanitizeProviderLabel(calendar.accountName),
                        accountType = SettingsTextPolicy.sanitizeProviderLabel(calendar.accountType),
                        writable = calendar.id in writableIds,
                    )
                },
                pinnedCalendarId = settings?.defaultCalendarId,
                pinnedCalendarLabel = settings?.defaultCalendarLabel,
                error = if (calendars.isFailure) "캘린더 목록을 읽지 못했습니다." else null,
            )
        }
    }

    fun onCalendarPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        _calendarSetup.update { state ->
            state.copy(permissionPermanentlyDenied = !granted && !canAskAgain)
        }
        refreshCalendarSetup()
    }

    /** Only a writable calendar can be pinned; a read-only one would fail at execution time. */
    fun pinCalendar(option: CalendarOption) {
        if (!option.writable) {
            _calendarSetup.update { state ->
                state.copy(error = "이 캘린더에는 쓸 수 없습니다. 동기화 설정을 확인하세요.")
            }
            return
        }
        viewModelScope.launch {
            runCatching { container.settings.setDefaultCalendar(option.id, option.label) }
            refreshCalendarSetup()
        }
    }

    fun unpinCalendar() {
        viewModelScope.launch {
            runCatching { container.settings.setDefaultCalendar(null, null) }
            refreshCalendarSetup()
        }
    }

    private fun hasCalendarReadPermission(): Boolean = ContextCompat.checkSelfPermission(
        getApplication(),
        Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

    /** Adds bounded device state plus explicitly quoted summary/recent-message context. */
    private suspend fun withTrustedTurnContext(prompt: String): String {
        return runCatching {
            val zone = ZoneId.systemDefault()
            val now = Instant.now().atZone(zone)
            val settings = container.settings.current()
            val conversation = _chatHistory.value.activeConversationId?.let { conversationId ->
                container.conversations.loadContext(
                    conversationId = conversationId,
                    recentMessageLimit = settings.recentMessageWindow,
                )
            }
            TurnContextBuilder.build(
                prompt = prompt,
                device = TurnDeviceContext(
                    localTimestamp = now.format(TURN_CONTEXT_FORMAT),
                    timeZoneId = zone.id,
                    calendarId = settings.defaultCalendarId,
                    calendarLabel = settings.defaultCalendarLabel,
                ),
                conversation = conversation,
                maximumBytes = MAX_USER_PROMPT_BYTES,
            )
        }.getOrDefault(prompt)
    }

    /**
     * Compresses older messages after a turn finishes.
     *
     * Runs on the tool-free budget, so a model that tries to call a tool during summarization is
     * stopped before anything is prepared and no confirmation dialog appears behind the user's
     * back. A failed or refused summary simply leaves the thread unsummarized.
     */
    private fun summarizeInBackground(conversationId: String?) {
        if (conversationId == null) return
        if (summaryJob?.isActive == true) return

        summaryJob = viewModelScope.launch {
            val request = summarizer.requestFor(conversationId) ?: return@launch
            if (_uiState.value.modelStatus != ModelUiStatus.READY) return@launch

            val summary = StringBuilder()
            var toolAttempted = false
            val turnId = TurnId("summary-${UUID.randomUUID()}")
            try {
                controller.runTurn(turnId, request.prompt, controller.toolFreeLimits)
                    .collect { event ->
                        when (event) {
                            is AgentEvent.TextDelta -> summary.append(event.text)
                            is AgentEvent.ToolExecuted -> toolAttempted = true
                            is AgentEvent.Failure -> toolAttempted = true
                            is AgentEvent.Completed -> Unit
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }

            if (!toolAttempted) {
                summarizer.acceptSummary(request, summary.toString())
            }
        }
    }

    fun sendPrompt() {
        val snapshot = _uiState.value
        val prompt = snapshot.prompt.trim()
        if (
            snapshot.modelStatus != ModelUiStatus.READY ||
            snapshot.activeTurnId != null ||
            prompt.isEmpty() ||
            turnJob?.isActive == true
        ) {
            return
        }

        val startThermalObservation = thermalMonitor.refresh()
        if (!ThermalTurnPolicy.canStart(startThermalObservation.status)) {
            diagnostics.recordSafely(
                DiagnosticEvent.ThermalGuard(
                    status = startThermalObservation.status,
                    action = DiagnosticThermalAction.TURN_REJECTED,
                ),
            )
            addMessage(
                ChatRole.STATUS,
                if (startThermalObservation.status == DiagnosticThermalStatus.UNKNOWN) {
                    "기기 열 상태를 확인할 수 없어 새 요청을 시작하지 않았습니다."
                } else {
                    "기기 열 보호 정책이 새 요청을 허용하지 않습니다."
                },
            )
            return
        }

        // A background recap uses the same single-owner controller. User work always wins: cancel
        // it now and join it inside the turn job before recording the prompt or calling runtime.
        val pendingSummary = BackgroundSummaryPriority.cancelForUserTurn(summaryJob)

        val turnId = TurnId("turn-${UUID.randomUUID()}")
        val thermalTurn = ActiveThermalTurn(
            turnId = turnId,
            baselineStopSequence = startThermalObservation.stopSequence,
        )
        if (!activeThermalTurn.compareAndSet(null, thermalTurn)) return
        val initialAssistantEntryId = "assistant-${turnId.value}-0"
        _uiState.update {
            it.copy(
                prompt = "",
                activeTurnId = turnId,
                messages = it.messages + listOf(
                    ChatEntry("user-${turnId.value}", ChatRole.USER, prompt),
                    ChatEntry(initialAssistantEntryId, ChatRole.ASSISTANT, ""),
                ),
            )
        }

        turnJob = viewModelScope.launch {
            BackgroundSummaryPriority.awaitRelease(pendingSummary)
            val startedAt = SystemClock.elapsedRealtime()
            val requestText = withTrustedTurnContext(prompt)
            // The typed prompt is stored, never the derived preamble: the date and calendar in it
            // describe the moment of the turn and would be wrong on restore.
            val conversationId = history.ensureConversation(
                activeConversationId = _chatHistory.value.activeConversationId,
                firstPrompt = prompt,
            )
            _chatHistory.update { state -> state.copy(activeConversationId = conversationId) }
            history.record(conversationId, MessageRole.USER, prompt)
            diagnostics.markPhase(DiagnosticPhase.TURN_PROCESSING)
            diagnostics.recordSafely(
                DiagnosticEvent.TurnStarted(requestText.toByteArray(Charsets.UTF_8).size),
            )
            var assistantPhase = 0
            var assistantEntryId = initialAssistantEntryId
            var firstTokenRecorded = false
            var deltaCount = 0
            var deltaByteCount = 0L
            var terminalRecorded = false
            val processedToolOrdinals = mutableSetOf<Int>()

            suspend fun processToolExecution(event: AgentEvent.ToolExecuted): Boolean =
                ToolReceiptCommitBoundary.commit {
                    if (event.ordinal in processedToolOrdinals) return@commit false
                    diagnostics.recordKnownToolPhase(
                        toolName = event.toolName,
                        stage = DiagnosticToolStage.EXECUTED,
                        outcome = when (event.outcome) {
                            ToolExecutionOutcome.READ_COMPLETED,
                            ToolExecutionOutcome.WRITE_COMPLETED,
                            -> DiagnosticConfirmationOutcome.EXECUTED_SUCCESS
                            ToolExecutionOutcome.WRITE_REFUSED ->
                                DiagnosticConfirmationOutcome.EXECUTED_REFUSED
                        },
                    )
                    diagnostics.markPhase(DiagnosticPhase.TURN_PROCESSING)
                    assistantPhase++
                    val nextAssistantEntryId = "assistant-${turnId.value}-$assistantPhase"
                    val finalizedAssistantText = recordToolAndAdvanceAssistant(
                        toolName = event.toolName,
                        outcome = event.outcome,
                        currentAssistantEntryId = assistantEntryId,
                        nextAssistantEntryId = nextAssistantEntryId,
                    )
                    history.record(conversationId, MessageRole.ASSISTANT, finalizedAssistantText)
                    history.record(
                        conversationId,
                        MessageRole.TOOL_RECEIPT,
                        ToolReceiptFormatter.text(event.toolName, event.outcome),
                    )
                    assistantEntryId = nextAssistantEntryId
                    processedToolOrdinals.add(event.ordinal)
                    true
                }

            suspend fun reconcileRetainedToolExecutions(): Int {
                var reconciled = 0
                controller.retainedToolExecutions(turnId)
                    .sortedBy(AgentEvent.ToolExecuted::ordinal)
                    .forEach { event -> if (processToolExecution(event)) reconciled++ }
                return reconciled
            }
            try {
                val latestThermalObservation = thermalMonitor.observation.value
                if (latestThermalObservation.stopSequence > thermalTurn.baselineStopSequence) {
                    applyThermalTurnDirective(thermalTurn, latestThermalObservation)
                }
                if (thermalTurn.cancellationCause.current() != null ||
                    thermalTurn.latch.currentDecision() != null
                ) {
                    throw CancellationException("Thermal policy stopped the turn before registration.")
                }
                controller.runTurn(turnId, requestText).collect { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            if (event.text.isNotEmpty()) {
                                if (!firstTokenRecorded) {
                                    diagnostics.recordSafely(
                                        DiagnosticEvent.TurnFirstToken(
                                            ttftMillis = diagnosticDuration(startedAt),
                                        ),
                                    )
                                    firstTokenRecorded = true
                                }
                                if (deltaCount < Int.MAX_VALUE) deltaCount++
                                deltaByteCount = saturatedAdd(
                                    deltaByteCount,
                                    event.text.toByteArray(Charsets.UTF_8).size.toLong(),
                                )
                            }
                            appendToMessage(assistantEntryId, event.text)
                        }
                        is AgentEvent.ToolExecuted -> {
                            processToolExecution(event)
                        }
                        is AgentEvent.Completed -> {
                            if (!terminalRecorded) {
                                diagnostics.recordSafely(
                                    DiagnosticEvent.TurnCompleted(
                                        durationMillis = diagnosticDuration(startedAt),
                                        deltaCount = deltaCount,
                                        deltaByteCount = deltaByteCount,
                                    ),
                                )
                                terminalRecorded = true
                            }
                            ensureAssistantMessage(assistantEntryId)
                        }
                        is AgentEvent.Failure -> {
                            reconcileRetainedToolExecutions()
                            if (!terminalRecorded) {
                                diagnostics.recordSafely(
                                    DiagnosticEvent.TurnFailed(
                                        durationMillis = diagnosticDuration(startedAt),
                                        deltaCount = deltaCount,
                                        deltaByteCount = deltaByteCount,
                                        errorCode = event.runtimeCode?.toDiagnosticErrorCode()
                                            ?: event.code.toDiagnosticErrorCode(),
                                    ),
                                )
                                terminalRecorded = true
                            }
                            removeMessageIfBlank(assistantEntryId)
                            addMessage(
                                ChatRole.STATUS,
                                failureText(
                                    code = event.code,
                                    knownToolExecution = processedToolOrdinals.isNotEmpty(),
                                ),
                            )
                        }
                    }
                }
                if (!terminalRecorded) {
                    diagnostics.recordSafely(
                        DiagnosticEvent.TurnFailed(
                            durationMillis = diagnosticDuration(startedAt),
                            deltaCount = deltaCount,
                            deltaByteCount = deltaByteCount,
                            errorCode = DiagnosticErrorCode.INVALID_MODEL_SEQUENCE,
                        ),
                    )
                    terminalRecorded = true
                }
            } catch (_: CancellationException) {
                val knownToolExecution = withContext(NonCancellable) {
                    reconcileRetainedToolExecutions()
                    processedToolOrdinals.isNotEmpty()
                }
                if (!terminalRecorded) {
                    val cause = thermalTurn.cancellationCause.current()
                        ?: DiagnosticTurnCancellationCause.LIFECYCLE
                    val thermalStatus = if (cause == DiagnosticTurnCancellationCause.THERMAL) {
                        thermalTurn.latch.currentDecision()?.status
                    } else {
                        null
                    }
                    diagnostics.recordSafely(
                        DiagnosticEvent.TurnCancelled(
                            durationMillis = diagnosticDuration(startedAt),
                            deltaCount = deltaCount,
                            deltaByteCount = deltaByteCount,
                            cause = cause,
                            thermalStatus = thermalStatus,
                        ),
                    )
                    terminalRecorded = true
                }
                removeMessageIfBlank(assistantEntryId)
                addMessage(
                    ChatRole.STATUS,
                    if (knownToolExecution) {
                        "Tool 결과는 위 영수증대로 확정됐지만 후속 모델 답변은 취소됐습니다. " +
                            "자동으로 재시도하지 않습니다."
                    } else {
                        cancellationStatusText(thermalTurn)
                    },
                )
            } catch (failure: Exception) {
                val knownToolExecution = withContext(NonCancellable) {
                    reconcileRetainedToolExecutions()
                    processedToolOrdinals.isNotEmpty()
                }
                if (!terminalRecorded) {
                    diagnostics.recordSafely(
                        DiagnosticEvent.TurnFailed(
                            durationMillis = diagnosticDuration(startedAt),
                            deltaCount = deltaCount,
                            deltaByteCount = deltaByteCount,
                            errorCode = DiagnosticErrorCode.UNKNOWN,
                            failure = failure.toDiagnosticFailureOrNull(),
                        ),
                    )
                    terminalRecorded = true
                }
                removeMessageIfBlank(assistantEntryId)
                addMessage(
                    ChatRole.STATUS,
                    if (knownToolExecution) {
                        "Tool 결과는 위 영수증대로 확정됐지만 후속 처리에 실패했습니다. " +
                            "자동으로 재시도하지 않습니다."
                    } else {
                        "예기치 않은 오류로 요청을 중단했습니다."
                    },
                )
            } finally {
                // NonCancellable so a cancelled or thermally stopped turn still keeps whatever the
                // model had already produced; a partial answer is history, not garbage.
                withContext(NonCancellable) {
                    reconcileRetainedToolExecutions()
                    history.record(
                        conversationId,
                        MessageRole.ASSISTANT,
                        currentMessageText(assistantEntryId),
                    )
                }
                activeThermalTurn.compareAndSet(thermalTurn, null)
                _uiState.update { state ->
                    if (state.activeTurnId == turnId) state.copy(activeTurnId = null) else state
                }
                finishDiagnosticPhase()
            }
            // After the turn is released, never during it: the controller runs one turn at a time.
            summarizeInBackground(conversationId)
        }.also(thermalTurn.job::set)
    }

    fun cancelTurn() {
        val turnId = _uiState.value.activeTurnId ?: return
        val active = activeThermalTurn.get()?.takeIf { it.turnId == turnId } ?: return
        active.cancellationCause.record(DiagnosticTurnCancellationCause.USER)
        active.job.get()?.cancel(CancellationException("User cancelled the active turn."))
        viewModelScope.launch {
            runCatching { controller.cancel(turnId) }
        }
    }

    fun resolveConfirmation(actionId: String, approved: Boolean) {
        confirmationCoordinator.resolve(actionId, approved)
    }

    private fun updateModelProgress(done: Long, total: Long) {
        if (total <= 0) return
        val progress = (done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        _uiState.update { it.copy(modelProgress = progress) }
    }

    private fun setModelStatus(status: ModelUiStatus, text: String) {
        _uiState.update {
            it.copy(
                modelStatus = status,
                modelProgress = null,
                activeBackend = if (status == ModelUiStatus.READY) it.activeBackend else null,
                statusText = text,
            )
        }
    }

    private fun appendToMessage(id: String, delta: String) {
        if (delta.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { entry ->
                    if (entry.id == id) entry.copy(text = entry.text + delta) else entry
                },
            )
        }
    }

    private fun ensureAssistantMessage(id: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { entry ->
                    if (entry.id == id && entry.text.isBlank()) {
                        entry.copy(text = "요청 처리를 완료했습니다.")
                    } else {
                        entry
                    }
                },
            )
        }
    }

    private fun currentMessageText(id: String): String =
        _uiState.value.messages.firstOrNull { entry -> entry.id == id }?.text.orEmpty()

    /** Returns the assistant text being closed off, so the caller can persist it in order. */
    private fun recordToolAndAdvanceAssistant(
        toolName: String,
        outcome: ToolExecutionOutcome,
        currentAssistantEntryId: String,
        nextAssistantEntryId: String,
    ): String {
        val finalizedText = currentMessageText(currentAssistantEntryId)
        _uiState.update { state ->
            val transcript = state.messages.filterNot { entry ->
                entry.id == currentAssistantEntryId && entry.text.isBlank()
            }
            state.copy(
                messages = transcript + listOf(
                    ChatEntry(
                        id = UUID.randomUUID().toString(),
                        role = ChatRole.TOOL,
                        text = ToolReceiptFormatter.text(toolName, outcome),
                    ),
                    ChatEntry(nextAssistantEntryId, ChatRole.ASSISTANT, ""),
                ),
            )
        }
        return finalizedText
    }

    private fun removeMessageIfBlank(id: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.filterNot { entry ->
                    entry.id == id && entry.text.isBlank()
                },
            )
        }
    }

    private fun addMessage(role: ChatRole, text: String) {
        _uiState.update {
            it.copy(messages = it.messages + ChatEntry(UUID.randomUUID().toString(), role, text))
        }
    }

    private fun failureText(
        code: AgentFailureCode,
        knownToolExecution: Boolean = false,
    ): String = if (knownToolExecution) {
        when (code) {
            AgentFailureCode.DEADLINE_EXCEEDED ->
                "Tool 결과는 위 영수증대로 확정됐지만 후속 답변 시간이 초과됐습니다. " +
                    "자동으로 재시도하지 않습니다."
            else -> "Tool 결과는 위 영수증대로 확정됐지만 후속 처리가 중단됐습니다. " +
                "자동으로 재시도하지 않습니다."
        }
    } else when (code) {
        AgentFailureCode.BUSY -> "다른 요청이 진행 중입니다."
        AgentFailureCode.DEADLINE_EXCEEDED ->
            "요청 제한 시간 ${AgentLoopLimits().deadlineMillis / 1_000}초를 초과했습니다."
        AgentFailureCode.UNKNOWN_TOOL -> "등록되지 않은 Tool 호출을 차단했습니다."
        AgentFailureCode.INVALID_TOOL_CALL -> "유효하지 않은 Tool 인자를 차단했습니다."
        AgentFailureCode.TOOL_NOT_EXECUTED ->
            "Tool이 실행되지 않았거나 결과를 확정할 수 없습니다. 자동으로 재시도하지 " +
                "않았습니다. 대상 앱의 상태를 확인한 뒤 다시 결정하세요."
        AgentFailureCode.STEP_LIMIT_EXCEEDED,
        AgentFailureCode.TOOL_CALL_LIMIT_EXCEEDED -> "Agent 실행 한도를 초과해 중단했습니다."
        AgentFailureCode.INVALID_TURN,
        AgentFailureCode.TURN_MISMATCH,
        AgentFailureCode.INVALID_MODEL_SEQUENCE,
        AgentFailureCode.MODEL_FAILURE -> "모델 응답을 안전하게 처리할 수 없어 중단했습니다."
    }

    private fun applyThermalObservation(observation: ThermalObservation) {
        if (observation.directive == ThermalDirective.CONTINUE) return
        activeThermalInitialization.get()?.let { initialization ->
            applyThermalInitializationDirective(initialization, observation)
        }
        activeThermalTurn.get()?.let { turn ->
            applyThermalTurnDirective(turn, observation)
        }
    }

    private fun applyThermalInitializationDirective(
        initialization: ActiveThermalInitialization,
        observation: ThermalObservation,
    ) {
        if (observation.stopSequence <= initialization.baselineStopSequence) return
        val decision = initialization.latch.apply(observation) ?: return

        initialization.job.get()?.cancel(
            CancellationException("Thermal policy cancelled runtime initialization."),
        )
        diagnostics.recordSafely(
            DiagnosticEvent.ThermalGuard(
                status = decision.status,
                action = DiagnosticThermalAction.RUNTIME_INITIALIZATION_CANCEL_REQUESTED,
            ),
        )
    }

    private fun applyThermalTurnDirective(
        active: ActiveThermalTurn,
        observation: ThermalObservation,
    ) {
        if (observation.stopSequence <= active.baselineStopSequence) return

        val decision = active.latch.apply(observation) ?: return
        active.cancellationCause.record(DiagnosticTurnCancellationCause.THERMAL)
        confirmationCoordinator.denyPending()

        // Owning-job cancellation closes the gap before ManualToolAgentController registers the ID.
        active.job.get()?.cancel(CancellationException("Thermal policy cancelled the active turn."))
        if (decision.directive == ThermalDirective.IMMEDIATE_ABORT) {
            viewModelScope.launch {
                runCatching { controller.cancel(active.turnId) }
            }
        }
        diagnostics.recordSafely(
            DiagnosticEvent.ThermalGuard(
                status = decision.status,
                action = when (decision.directive) {
                    ThermalDirective.COOPERATIVE_CANCEL ->
                        DiagnosticThermalAction.COOPERATIVE_CANCEL_REQUESTED
                    ThermalDirective.IMMEDIATE_ABORT ->
                        DiagnosticThermalAction.IMMEDIATE_ABORT_REQUESTED
                    ThermalDirective.CONTINUE -> return
                },
            ),
        )
    }

    private fun cancellationStatusText(turn: ActiveThermalTurn): String = when {
        turn.cancellationCause.current() == DiagnosticTurnCancellationCause.THERMAL &&
            turn.latch.currentDecision()?.directive == ThermalDirective.IMMEDIATE_ABORT ->
            "기기 열 상태 ${turn.latch.currentDecision()?.status?.name}에서 요청을 즉시 중단했습니다."
        turn.cancellationCause.current() == DiagnosticTurnCancellationCause.THERMAL &&
            turn.latch.currentDecision()?.directive == ThermalDirective.COOPERATIVE_CANCEL ->
            "기기 열 상태 ${turn.latch.currentDecision()?.status?.name}에서 요청을 안전하게 중단했습니다."
        turn.cancellationCause.current() == DiagnosticTurnCancellationCause.LIFECYCLE ->
            "앱 종료로 현재 요청을 중단했습니다."
        turn.cancellationCause.current() == DiagnosticTurnCancellationCause.USER ->
            "현재 요청을 취소했습니다."
        else -> "앱 종료로 현재 요청을 중단했습니다."
    }

    private fun diagnosticDuration(startedAt: Long): Long =
        elapsedMillisSince(startedAt, SystemClock.elapsedRealtime())

    private fun finishDiagnosticPhase() {
        diagnostics.markPhase(DiagnosticPhase.IDLE)
        runCatching { diagnostics.recordResourceSnapshot() }
    }

    private fun InferenceBackend.toDiagnosticBackend(): DiagnosticBackend = when (this) {
        InferenceBackend.CPU -> DiagnosticBackend.CPU
        InferenceBackend.GPU -> DiagnosticBackend.GPU
    }

    private fun saturatedAdd(first: Long, second: Long): Long =
        if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

    override fun onCleared() {
        runCatching(thermalDirectiveSubscription::close)
        thermalMonitor.close()
        confirmationCoordinator.denyPending()
        activeThermalTurn.get()?.let { active ->
            active.cancellationCause.record(DiagnosticTurnCancellationCause.LIFECYCLE)
            active.job.get()?.cancel(CancellationException("ViewModel cleared."))
        }
        activeThermalInitialization.get()?.job?.get()?.cancel(
            CancellationException("ViewModel cleared."),
        )
        modelJob?.cancel()
        runtime.close()
        super.onCleared()
    }

    private class ActiveThermalTurn(
        val turnId: TurnId,
        val baselineStopSequence: Long,
    ) {
        val job = AtomicReference<Job?>(null)
        val latch = ThermalTurnLatch(baselineStopSequence)
        val cancellationCause = TurnCancellationCauseLatch()
    }

    private class ActiveThermalInitialization(
        val baselineStopSequence: Long,
    ) {
        val job = AtomicReference<Job?>(null)
        val latch = ThermalTurnLatch(baselineStopSequence)
    }

    companion object {
        val modelManifest = PinnedModelManifest.value
        // The typed prompt shares the runtime budget with the trusted preamble prepended below.
        // Long prompts reserve 640 bytes for the date line and at least one recent message; short
        // prompts automatically receive more history while the final request remains <= 2 KiB.
        private const val MIN_TURN_CONTEXT_BYTES = 640
        private const val MAX_PROMPT_BYTES = MAX_USER_PROMPT_BYTES - MIN_TURN_CONTEXT_BYTES
        private const val UNRESOLVED_ACTION_WARNING_ID = "unresolved-action-warning"
        private val TURN_CONTEXT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd(E) HH:mm", Locale.KOREAN)
    }
}
