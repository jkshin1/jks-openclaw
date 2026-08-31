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
import com.personaledge.core.agent.AgentPlanExecutionBridge
import com.personaledge.core.agent.DeterministicReadRouter
import com.personaledge.core.agent.ManualToolAgentController
import com.personaledge.core.agent.ManualToolRegistry
import com.personaledge.core.agent.PriorWebResultFollowUpPolicy
import com.personaledge.core.agent.ReminderDateTimeHint
import com.personaledge.core.agent.SideEffectTurnGate
import com.personaledge.core.agent.ToolFailureDetail
import com.personaledge.core.agent.TurnExecutionContract
import com.personaledge.core.agent.TurnMediaIntent
import com.personaledge.core.agent.TurnMediaPolicy
import com.personaledge.core.data.AgentSettings
import com.personaledge.core.data.MemoryCategory
import com.personaledge.core.data.MemoryEntity
import com.personaledge.core.data.MessageRole
import com.personaledge.core.data.TurnOutcomeFailureCode
import com.personaledge.core.data.TurnRecoverability
import com.personaledge.core.data.TurnToolCommitOutcome
import com.personaledge.core.data.EncryptedUserDataArchive
import com.personaledge.core.data.UserDataArchiveFailure
import com.personaledge.core.data.UserDataArchiveReadResult
import com.personaledge.core.data.UserDataImportResult
import com.personaledge.core.data.UserDataSelection
import com.personaledge.core.data.UserDataTransferPreview
import com.personaledge.core.data.takeCodePoints
import com.personaledge.core.diagnostics.DiagnosticBackend
import com.personaledge.core.diagnostics.DiagnosticConfirmationOutcome
import com.personaledge.core.diagnostics.DiagnosticContextComponent
import com.personaledge.core.diagnostics.DiagnosticErrorCode
import com.personaledge.core.diagnostics.DiagnosticEvent
import com.personaledge.core.diagnostics.DiagnosticMediaKind
import com.personaledge.core.diagnostics.DiagnosticMediaStage
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
import com.personaledge.core.llm.LlmFailureCode
import com.personaledge.core.llm.LlmRuntimeException
import com.personaledge.core.llm.LlmState
import com.personaledge.core.llm.LlmTurnToolScope
import com.personaledge.core.llm.MAX_USER_PROMPT_BYTES
import com.personaledge.core.llm.ModelArtifactStore
import com.personaledge.core.llm.ModelStoreException
import com.personaledge.core.llm.PinnedModelManifest
import com.personaledge.core.llm.TurnId
import com.personaledge.core.llm.TurnMediaAttachment
import com.personaledge.core.llm.TurnMediaKind
import com.personaledge.core.llm.VerifiedInstalledModel
import com.personaledge.core.llm.supportsModality
import com.personaledge.core.tools.AlarmNextTool
import com.personaledge.core.tools.AlarmSetTool
import com.personaledge.core.tools.CalendarAccount
import com.personaledge.core.tools.CalendarCreateEventTool
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.CalendarUpdateEventTool
import com.personaledge.core.tools.CommitmentProposalTool
import com.personaledge.core.tools.NotificationSearchTool
import com.personaledge.core.tools.KakaoNotificationReplyTool
import com.personaledge.core.tools.KakaoShareMessageTool
import com.personaledge.core.tools.MemoryRememberTool
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.ReminderCancelTool
import com.personaledge.core.tools.ReminderCreateTool
import com.personaledge.core.tools.ReminderQueryTool
import com.personaledge.core.tools.ReminderUpdateTool
import com.personaledge.core.tools.WebSearchTool
import com.personaledge.core.tools.WeatherTool
import com.personaledge.core.tools.ToolOrchestrator
import com.personaledge.core.tools.ToolExecutionOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
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
    val recoveryAction: ChatRecoveryAction? = null,
    /**
     * App-authored label for a photo or voice clip this message carried.
     *
     * A caption, never the media. The bytes exist only while the turn runs, so the transcript
     * shows what kind of attachment was sent and nothing that could reconstruct it.
     */
    val attachmentLabel: String? = null,
)

/**
 * Live model reasoning for the assistant placeholder that is currently decoding.
 *
 * This value exists only in the ViewModel state flow. It is deliberately separate from
 * [ChatEntry], so it cannot enter Room history, recovery capsules, conversation summaries, or
 * content-free diagnostics.
 */
data class ActiveReasoningUiState(
    val turnId: TurnId,
    val assistantEntryId: String,
    val text: String = "",
) {
    override fun toString(): String =
        "ActiveReasoningUiState(turnId=$turnId, assistantEntryId=<redacted>, text=<redacted>)"
}

internal object ActiveReasoningUiPolicy {
    fun start(turnId: TurnId, assistantEntryId: String): ActiveReasoningUiState =
        ActiveReasoningUiState(turnId = turnId, assistantEntryId = assistantEntryId)

    fun append(
        current: ActiveReasoningUiState?,
        activeTurnId: TurnId?,
        turnId: TurnId,
        assistantEntryId: String,
        delta: String,
    ): ActiveReasoningUiState? = if (
        delta.isNotEmpty() &&
        activeTurnId == turnId &&
        current?.turnId == turnId &&
        current.assistantEntryId == assistantEntryId
    ) {
        current.copy(text = current.text + delta)
    } else {
        current
    }

    fun clear(
        current: ActiveReasoningUiState?,
        turnId: TurnId,
        assistantEntryId: String,
    ): ActiveReasoningUiState? = if (
        current?.turnId == turnId && current.assistantEntryId == assistantEntryId
    ) {
        null
    } else {
        current
    }

    fun advance(
        current: ActiveReasoningUiState?,
        activeTurnId: TurnId?,
        turnId: TurnId,
        nextAssistantEntryId: String,
    ): ActiveReasoningUiState? = if (activeTurnId == turnId) {
        start(turnId, nextAssistantEntryId)
    } else {
        current
    }
}

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
 * outside it is read or written. The Fold8 product selection is the standard Samsung Account
 * row; provider identity comes from account type, never from an email-shaped account name.
 */
data class CalendarSetupState(
    val permissionGranted: Boolean = false,
    val permissionPermanentlyDenied: Boolean = false,
    val calendars: List<CalendarOption> = emptyList(),
    val pinnedCalendarId: Long? = null,
    val pinnedCalendarLabel: String? = null,
    val readCalendarIds: Set<Long> = emptySet(),
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
    val replyEnabled: Boolean = false,
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

data class DiagnosticExportState(
    val inProgress: Boolean = false,
    val message: String? = null,
    val succeeded: Boolean = false,
)

data class UserDataTransferState(
    val inProgress: Boolean = false,
    val exportPreview: UserDataTransferPreview? = null,
    val importFileSelected: Boolean = false,
    val importPreview: UserDataTransferPreview? = null,
    val message: String? = null,
    val succeeded: Boolean = false,
)

private sealed interface TrustedTurnContextResult {
    data class Ready(
        val text: String,
        val unavailableComponents: Set<DiagnosticContextComponent>,
        val recalledMemoryNotices: List<String>,
    ) : TrustedTurnContextResult

    data class Unavailable(
        val component: DiagnosticContextComponent,
        val userMessage: String,
    ) : TrustedTurnContextResult
}

data class PersonalEdgeUiState(
    val modelStatus: ModelUiStatus = ModelUiStatus.CHECKING,
    val modelProgress: Float? = null,
    val statusText: String = "고정 모델을 확인하는 중입니다.",
    val activeBackend: InferenceBackend? = null,
    val prompt: String = "",
    val promptInputWarning: String? = null,
    val messages: List<ChatEntry> = emptyList(),
    val activeTurnId: TurnId? = null,
    val activeReasoning: ActiveReasoningUiState? = null,
    val thermalStatus: DiagnosticThermalStatus = DiagnosticThermalStatus.UNKNOWN,
    /** The owner has turned photo and voice input on. Off until they do. */
    val mediaInputEnabled: Boolean = false,
    /** Metadata for the one staged attachment; the payload never enters UI state. */
    val pendingAttachment: PendingMediaAttachment? = null,
    val voiceRecording: VoiceRecordingUiState? = null,
    /** Live dictation: the answer becomes an editable draft rather than a transcript entry. */
    val transcribing: Boolean = false,
    /** App-authored one-shot notice about an attachment or a recording. */
    val mediaNotice: String? = null,
) {
    val isBusy: Boolean
        get() = modelStatus == ModelUiStatus.IMPORTING ||
            modelStatus == ModelUiStatus.INITIALIZING || activeTurnId != null ||
            voiceRecording != null || transcribing

    val canSend: Boolean
        get() {
            if (activeTurnId != null || voiceRecording != null || transcribing) return false
            if (pendingAttachment != null) {
                return MediaComposerPolicy.canSendWithAttachment(
                    attachment = pendingAttachment,
                    prompt = prompt,
                    modelStatus = modelStatus,
                    turnActive = false,
                    recording = false,
                    thermalStatus = thermalStatus,
                )
            }
            val modelAvailable = modelStatus == ModelUiStatus.READY
            val deterministicReadAvailable = modelStatus != ModelUiStatus.CHECKING &&
                modelStatus != ModelUiStatus.IMPORTING &&
                modelStatus != ModelUiStatus.INITIALIZING &&
                DeterministicReadRouter.canRunWithoutModel(prompt)
            return (modelAvailable || deterministicReadAvailable) &&
                prompt.isNotBlank() && PromptInputPolicy.isAccepted(prompt) &&
                ThermalTurnPolicy.canStart(thermalStatus)
        }
}

/** Live recording indicator. Holds elapsed time only; samples never reach UI state. */
data class VoiceRecordingUiState(
    val elapsedMillis: Int,
    val dictation: Boolean,
) {
    val remainingMillis: Int get() = (VoiceCapturePolicy.MAX_MILLIS - elapsedMillis).coerceAtLeast(0)
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
    private val reminderCoordinator = ReminderCoordinator(application, container, viewModelScope)
    val reminderSetup: StateFlow<ReminderSetupState> = reminderCoordinator.state
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
        weatherTool = WeatherTool(container.weather),
        kakaoShareMessageTool = KakaoShareMessageTool(container.kakaoShareGateway),
        kakaoNotificationReplyTool = KakaoNotificationReplyTool(container.kakaoReplyGateway),
        memoryRememberTool = MemoryRememberTool(container.memoryGateway),
        commitmentProposalTool = CommitmentProposalTool(container.commitmentProposalGateway),
        reminderCreateTool = ReminderCreateTool(container.reminderGateway),
        reminderUpdateTool = ReminderUpdateTool(container.reminderGateway),
        reminderCancelTool = ReminderCancelTool(container.reminderGateway),
        reminderQueryTool = ReminderQueryTool(container.reminderGateway),
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
                NetworkToolConsent.isAllowed(
                    toolName = toolName,
                    settings = container.settings.current(),
                    interlock = container.ownerConsentInterlock,
                )
            },
            memoryConsent = {
                val settings = container.settings.current()
                container.ownerConsentInterlock.allowed(
                    OwnerConsentFeature.MEMORY,
                    settings.memoryEnabled,
                )
            },
            proposalConsent = {
                val settings = container.settings.current()
                container.ownerConsentInterlock.allowed(
                    OwnerConsentFeature.COMMITMENT_PROPOSALS,
                    settings.commitmentProposalsEnabled,
                )
            },
            kakaoShareAvailable = container.kakaoShareGateway::kakaoTalkAvailable,
            kakaoReplyEnabled = {
                container.notificationCaptureInterlock.replyAllowed(
                    container.settings.current().kakaoNotificationReplyEnabled,
                )
            },
            kakaoReplyAvailable = KakaoNotificationReplyBridge::available,
            readCalendarIds = container::readCalendarIds,
            sideEffectingToolsEnabled = BuildConfig.SIDE_EFFECTING_TOOLS_ENABLED,
        ),
    )
    private val agentPlanBridge = AgentPlanExecutionBridge(
        registry = registry,
        orchestrator = orchestrator,
        checkpointSink = container.agentPlanCheckpointSink,
        reconcileInterruptedPlans = {
            container.agentPlanCheckpoints.closeInterruptedPlans()
        },
    )
    private val controller = ManualToolAgentController(
        runtime = runtime,
        registry = registry,
        orchestrator = orchestrator,
        agentPlanBridge = agentPlanBridge,
        limits = AgentLoopLimits(
            maxOutputTokens = PinnedModelManifest.value.maxOutputTokens,
        ),
        forceExplicitToolScope = BuildConfig.CANDIDATE_MODEL_LAB,
        sideEffectTurnGate = SideEffectTurnGate { turnId, toolName, trustedRisk ->
            val recoveryRisk = TurnRecoveryPolicy.validatedToolRisk(toolName, trustedRisk)
                ?: return@SideEffectTurnGate false
            container.turnOutcomes.armSideEffect(
                turnId = turnId.value,
                toolName = toolName,
                risk = recoveryRisk,
            )
        },
    )

    private val _uiState = MutableStateFlow(
        PersonalEdgeUiState(thermalStatus = thermalMonitor.observation.value.status),
    )
    val uiState: StateFlow<PersonalEdgeUiState> = _uiState.asStateFlow()

    private val history = ChatHistoryCoordinator(container.conversations, container.turnOutcomes)
    private val summarizer = ConversationSummarizer(container.conversations)
    private val _chatHistory = MutableStateFlow(ChatHistoryState())
    val chatHistory: StateFlow<ChatHistoryState> = _chatHistory.asStateFlow()

    private var verifiedModel: VerifiedInstalledModel? = null
    private var modelJob: Job? = null
    private var turnJob: Job? = null
    private var summaryJob: Job? = null
    private val mediaStaging = MediaCaptureStaging(application)
    private val imageLoader = ImageAttachmentLoader(application)
    private val voiceRecorder = VoiceRecorder()

    /**
     * The staged payload, deliberately outside [PersonalEdgeUiState].
     *
     * UI state is snapshotted, diffed, and held by Compose across recompositions; a multi-hundred
     * kilobyte photo has no business living there. The composer sees only
     * [PendingMediaAttachment] metadata, and this reference is cleared the moment the turn that
     * consumes it ends.
     */
    /** Modalities the live runtime actually loaded; empty until it is initialized. */
    private var initializedMediaModalities: Set<TurnMediaKind> = emptySet()
    private var stagedMedia: TurnMediaAttachment? = null
    private var stagedMediaIntent: TurnMediaIntent? = null
    private var voiceJob: Job? = null
    private val voiceRecordingActive = AtomicBoolean(false)
    private val conversationMutationGate = ConversationMutationGate()
    private val unresolvedActionWarningGate = UnresolvedActionWarningGate()
    private val activeThermalInitialization = AtomicReference<ActiveThermalInitialization?>(null)
    private val activeThermalTurn = AtomicReference<ActiveThermalTurn?>(null)
    private val activeRecoveryResolution = AtomicReference<String?>(null)
    private val thermalDirectiveSubscription: AutoCloseable

    init {
        restoreConversation()
        thermalDirectiveSubscription = thermalMonitor.setDirectiveListener(::applyThermalObservation)
        viewModelScope.launch {
            var lastRecordedStatus: DiagnosticThermalStatus? = null
            fun applyObservation(observation: ThermalObservation) {
                _uiState.update { it.copy(thermalStatus = observation.status) }
                if (!PredictiveThermalPolicy.allowBackgroundSummary(observation)) {
                    BackgroundSummaryPriority.cancelForThermalPolicy(summaryJob)
                }
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
        viewModelScope.launch {
            // The composer's attachment controls follow the durable setting, so turning media off
            // in settings closes them immediately rather than at the next launch.
            container.settings.settings.collect { settings ->
                val enabled = settings.mediaInputEnabled
                if (!enabled && _uiState.value.pendingAttachment != null) {
                    clearStagedMedia(DiagnosticMediaStage.DISCARDED)
                }
                _uiState.update { state -> state.copy(mediaInputEnabled = enabled) }
            }
        }
        inspectInstalledModel()
    }

    fun updatePrompt(value: String) {
        _uiState.update { state ->
            val update = PromptInputPolicy.apply(state.prompt, value)
            // With an attachment staged the app-authored media template also has to fit the same
            // 2 KiB turn envelope, so the typed line gets a smaller budget and says so.
            val mediaWarning = if (
                state.pendingAttachment != null &&
                !TurnMediaPolicy.isAcceptedOwnerText(update.prompt.trim())
            ) {
                "첨부와 함께 보낼 수 있는 글자 수를 넘었습니다. 내용은 유지됩니다."
            } else {
                null
            }
            state.copy(
                prompt = update.prompt,
                promptInputWarning = mediaWarning ?: update.warning,
            )
        }
    }

    fun refreshReminders() {
        // Returning from notification or exact-alarm system settings has no ActivityResult
        // callback. A unique reconciliation request makes the newly granted capability effective
        // immediately while Room remains the source of truth.
        container.reminderScheduler.requestReconcile()
        reminderCoordinator.refresh()
    }

    fun onReminderNotificationPermissionResult() = reminderCoordinator.onNotificationPermissionResult()

    fun createReminder(
        title: String,
        triggerAt: String,
        exact: Boolean,
        recurrenceRule: String?,
        untilCompleted: Boolean,
    ) = reminderCoordinator.create(title, triggerAt, exact, recurrenceRule, untilCompleted)

    fun completeReminder(id: String, version: Long) = reminderCoordinator.complete(id, version)

    fun snoozeReminder(id: String, version: Long, minutes: Int) =
        reminderCoordinator.snooze(id, version, minutes)

    fun cancelReminder(id: String, version: Long) = reminderCoordinator.cancel(id, version)

    fun setDailyBriefEnabled(enabled: Boolean) = reminderCoordinator.setDailyBriefEnabled(enabled)

    fun setDailyBriefTime(value: String) = reminderCoordinator.setDailyBriefTime(value)

    fun setProactiveRoutePlanningEnabled(enabled: Boolean) =
        reminderCoordinator.setProactiveRoutePlanningEnabled(enabled)

    fun setQuietHoursEnabled(enabled: Boolean) = reminderCoordinator.setQuietHoursEnabled(enabled)

    fun setWeekendBriefEnabled(enabled: Boolean) = reminderCoordinator.setWeekendBriefEnabled(enabled)

    fun setCommitmentProposalsEnabled(enabled: Boolean) =
        reminderCoordinator.setCommitmentProposalsEnabled(enabled)

    fun dismissCommitmentProposal(id: String) = reminderCoordinator.dismissProposal(id)

    fun captureCommitmentInbox(summary: String) = reminderCoordinator.captureInbox(summary)

    fun promoteCommitmentProposal(id: String, triggerAt: String, exact: Boolean) =
        reminderCoordinator.promoteProposal(id, triggerAt, exact)

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

    /**
     * Modalities the engine should load encoders for, from the owner's durable setting.
     *
     * Read once per initialization on purpose. Loading these executors costs the GPU backend for
     * the whole engine on the owner's device, so the runtime must not carry them for someone who
     * has media input switched off.
     */
    private suspend fun enabledMediaModalities(): Set<TurnMediaKind> {
        val enabled = runCatching { container.settings.current().mediaInputEnabled }
            .getOrDefault(false)
        if (!enabled) return emptySet()
        return TurnMediaKind.entries.filterTo(linkedSetOf(), modelManifest::supportsModality)
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
                val mediaModalities = enabledMediaModalities()
                runtime.initialize(
                    model = model,
                    backend = backend,
                    tools = controller.toolDefinitions,
                    mediaModalities = mediaModalities,
                )
                initializedMediaModalities = mediaModalities
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
        val mutationLease = conversationMutationGate.tryAcquire() ?: return
        // The visible conversation is changing, so the previous request may no longer be
        // on screen. Drop the follow-up carry-over rather than letting a reply in a
        // different conversation inherit it.
        controller.clearFollowUpContext()
        val job = viewModelScope.launch {
            val restored = history.restoreMostRecent()
            val globalVerificationEntries = unresolvedSideEffectEntriesOrNull()
            val unresolvedWarning = unresolvedActionWarningGate.load {
                container.actionLedger.unresolvedActionCheck()
            }
            if (_uiState.value.activeTurnId != null) return@launch
            _chatHistory.update { state ->
                state.copy(activeConversationId = restored.conversationId)
            }
            _uiState.update { state ->
                state.copy(
                    messages = ConversationRecoveryEntryPolicy.mergeGlobalVerification(
                        restored.entries,
                        globalVerificationEntries,
                    ) + listOfNotNull(
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
        mutationLease.closeOnCompletion(job)
    }

    private suspend fun unresolvedSideEffectEntriesOrNull(): List<ChatEntry>? =
        runCatching { history.unresolvedSideEffectEntries() }.getOrNull()

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
        val mutationLease = conversationMutationGate.tryAcquire() ?: return
        // The visible conversation is changing, so the previous request may no longer be
        // on screen. Drop the follow-up carry-over rather than letting a reply in a
        // different conversation inherit it.
        controller.clearFollowUpContext()
        if (_uiState.value.activeTurnId != null) {
            mutationLease.close()
            return
        }
        val job = viewModelScope.launch {
            if (_uiState.value.activeTurnId != null) return@launch
            val globalVerificationEntries = unresolvedSideEffectEntriesOrNull()
            _chatHistory.update { state -> state.copy(activeConversationId = null, visible = false) }
            _uiState.update { state ->
                state.copy(
                    promptInputWarning = null,
                    messages = ConversationRecoveryEntryPolicy.verificationOnly(
                        state.messages,
                        globalVerificationEntries,
                    ),
                )
            }
        }
        mutationLease.closeOnCompletion(job)
    }

    fun switchConversation(conversationId: String) {
        val mutationLease = conversationMutationGate.tryAcquire() ?: return
        // The visible conversation is changing, so the previous request may no longer be
        // on screen. Drop the follow-up carry-over rather than letting a reply in a
        // different conversation inherit it.
        controller.clearFollowUpContext()
        if (_uiState.value.activeTurnId != null) {
            mutationLease.close()
            return
        }
        val job = viewModelScope.launch {
            if (_uiState.value.activeTurnId != null) return@launch
            when (val result = history.switchTo(conversationId)) {
                is ConversationSwitchResult.Success -> {
                    val globalVerificationEntries = unresolvedSideEffectEntriesOrNull()
                    _chatHistory.update { state ->
                        state.copy(
                            activeConversationId = result.restored.conversationId,
                            visible = false,
                            error = null,
                        )
                    }
                    _uiState.update { state ->
                        state.copy(
                            messages = ConversationRecoveryEntryPolicy.mergeGlobalVerification(
                                result.restored.entries,
                                globalVerificationEntries,
                            ),
                        )
                    }
                }
                ConversationSwitchResult.NotFound -> {
                    _chatHistory.update { state ->
                        state.copy(
                            conversations = history.listConversations(),
                            error = "대화를 찾을 수 없습니다. 목록을 새로 불러왔습니다.",
                        )
                    }
                }
                ConversationSwitchResult.StorageUnavailable -> {
                    _chatHistory.update { state ->
                        state.copy(error = "대화 저장소를 읽지 못했습니다. 현재 대화를 유지합니다.")
                    }
                }
            }
        }
        mutationLease.closeOnCompletion(job)
    }

    fun deleteConversation(conversationId: String) {
        val mutationLease = conversationMutationGate.tryAcquire() ?: return
        // The visible conversation is changing, so the previous request may no longer be
        // on screen. Drop the follow-up carry-over rather than letting a reply in a
        // different conversation inherit it.
        controller.clearFollowUpContext()
        if (_uiState.value.activeTurnId != null) {
            mutationLease.close()
            return
        }
        val job = viewModelScope.launch {
            if (_uiState.value.activeTurnId != null) return@launch
            val deleted = history.delete(conversationId)
            val clearedActive = deleted && _chatHistory.value.activeConversationId == conversationId
            val globalVerificationEntries = unresolvedSideEffectEntriesOrNull()
            _uiState.update { state ->
                state.copy(
                    messages = if (clearedActive) {
                        ConversationRecoveryEntryPolicy.verificationOnly(
                            state.messages,
                            globalVerificationEntries,
                        )
                    } else {
                        ConversationRecoveryEntryPolicy.mergeGlobalVerification(
                            state.messages,
                            globalVerificationEntries,
                        )
                    },
                )
            }
            _chatHistory.update { state ->
                state.copy(
                    conversations = history.listConversations(),
                    activeConversationId = if (clearedActive) null else state.activeConversationId,
                    error = if (deleted) null else "대화를 삭제하지 못했습니다.",
                )
            }
        }
        mutationLease.closeOnCompletion(job)
    }

    /**
     * Erases every stored transcript. The action ledger lives in a separate database and is
     * deliberately untouched, so this cannot re-enable an already-executed side effect.
     */
    fun deleteAllConversations() {
        val mutationLease = conversationMutationGate.tryAcquire() ?: return
        // The visible conversation is changing, so the previous request may no longer be
        // on screen. Drop the follow-up carry-over rather than letting a reply in a
        // different conversation inherit it.
        controller.clearFollowUpContext()
        if (_uiState.value.activeTurnId != null) {
            mutationLease.close()
            return
        }
        val job = viewModelScope.launch {
            if (_uiState.value.activeTurnId != null) return@launch
            val deleted = history.deleteAll()
            val globalVerificationEntries = unresolvedSideEffectEntriesOrNull()
            _uiState.update { state ->
                state.copy(
                    messages = if (deleted) {
                        ConversationRecoveryEntryPolicy.verificationOnly(
                            state.messages,
                            globalVerificationEntries,
                        )
                    } else {
                        ConversationRecoveryEntryPolicy.mergeGlobalVerification(
                            state.messages,
                            globalVerificationEntries,
                        )
                    },
                )
            }
            _chatHistory.update { state ->
                state.copy(
                    conversations = history.listConversations(),
                    activeConversationId = if (deleted) null else state.activeConversationId,
                    error = if (deleted) null else "대화 기록을 모두 삭제하지 못했습니다.",
                )
            }
        }
        mutationLease.closeOnCompletion(job)
    }

    /** Resolves a durable recovery capsule exactly once. Reads re-enter the normal turn path. */
    fun resolveTurnRecovery(action: ChatRecoveryAction) {
        val mutationLease = conversationMutationGate.tryAcquire() ?: return
        if (
            _uiState.value.activeTurnId != null ||
            !ConversationRecoveryEntryPolicy.canResolve(
                action,
                _chatHistory.value.activeConversationId,
            )
        ) {
            mutationLease.close()
            return
        }
        if (!activeRecoveryResolution.compareAndSet(null, action.turnId)) {
            mutationLease.close()
            return
        }
        val job = viewModelScope.launch {
            try {
                if (
                    _uiState.value.activeTurnId != null ||
                    !ConversationRecoveryEntryPolicy.canResolve(
                        action,
                        _chatHistory.value.activeConversationId,
                    )
                ) {
                    return@launch
                }
                val recovery = history.recoveryRecord(action)
                if (recovery == null) {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.filterNot { entry ->
                                entry.id == actionEntryId(action)
                            },
                        )
                    }
                    addMessage(ChatRole.STATUS, "이 복구 요청은 이미 처리되었거나 만료되었습니다.")
                    return@launch
                }
                when (recovery.recoverability) {
                    TurnRecoverability.REQUERY_READ -> {
                        if (action.type != ChatRecoveryType.REQUERY_READ) {
                            addMessage(ChatRole.STATUS, "복구 상태가 바뀌어 요청을 시작하지 않았습니다.")
                            return@launch
                        }
                        val original = recovery.userRequest.trim()
                        if (
                            original.isEmpty() ||
                            original.toByteArray(Charsets.UTF_8).size >
                                PromptInputPolicy.MAX_ACCEPTED_BYTES
                        ) {
                            addMessage(
                                ChatRole.STATUS,
                                "원래 요청을 안전한 입력 범위로 복원할 수 없어 다시 수행하지 않습니다.",
                            )
                            return@launch
                        }
                        updatePrompt(original)
                        val started = startPrompt(
                            readOnlyRecovery = true,
                            predecessorRecovery = action,
                            heldConversationMutationLease = mutationLease,
                        )
                        if (started) {
                            addMessage(
                                ChatRole.STATUS,
                                "저장된 결과를 재사용하지 않고 원래 읽기 요청을 지금 새로 수행합니다.",
                            )
                        } else {
                            addMessage(
                                ChatRole.STATUS,
                                "원래 읽기 요청을 입력창에 불러왔습니다. 모델과 열 상태를 확인한 뒤 다시 선택하세요.",
                            )
                        }
                    }
                    TurnRecoverability.VERIFY_EXTERNAL_STATE -> {
                        if (action.type != ChatRecoveryType.VERIFY_EXTERNAL_STATE) {
                            addMessage(ChatRole.STATUS, "복구 상태가 바뀌어 요청을 닫지 않았습니다.")
                            return@launch
                        }
                        if (history.dismissRecovery(action)) {
                            _uiState.update { state ->
                                state.copy(
                                    messages = state.messages.filterNot { entry ->
                                        entry.id == actionEntryId(action)
                                    },
                                )
                            }
                            addMessage(
                                ChatRole.STATUS,
                                "외부 상태 확인 완료를 기록했습니다. 동일 작업은 자동 재실행하지 않았습니다.",
                            )
                        } else {
                            addMessage(ChatRole.STATUS, "외부 상태 확인 기록을 닫지 못했습니다.")
                        }
                    }
                    TurnRecoverability.NONE -> {
                        addMessage(ChatRole.STATUS, "이 요청은 자동 복구 대상이 아닙니다.")
                    }
                }
            } finally {
                activeRecoveryResolution.compareAndSet(action.turnId, null)
                mutationLease.close()
            }
        }
        job.invokeOnCompletion {
            activeRecoveryResolution.compareAndSet(action.turnId, null)
            mutationLease.close()
        }
    }

    private val credentialCoordinator = CredentialCoordinator(container, viewModelScope)
    val credentials: StateFlow<CredentialsState> = credentialCoordinator.state

    fun refreshCredentials() = credentialCoordinator.refresh()

    fun storeCredential(slot: CredentialSlot, value: String) =
        credentialCoordinator.store(slot, value)

    fun deleteCredential(slot: CredentialSlot) = credentialCoordinator.delete(slot)

    private val networkCoordinator = NetworkCoordinator(container, viewModelScope)
    val networkSetup: StateFlow<NetworkSetupState> = networkCoordinator.state

    fun refreshNetworkSetup() = networkCoordinator.refresh()

    fun setRouteLookupEnabled(enabled: Boolean) = networkCoordinator.setRouteLookupEnabled(enabled)

    fun setWebSearchEnabled(enabled: Boolean) = networkCoordinator.setWebSearchEnabled(enabled)

    fun storeDefaultOrigin(raw: String) = networkCoordinator.storeDefaultOrigin(raw)

    fun deleteDefaultOrigin() = networkCoordinator.deleteDefaultOrigin()

    private val memoryCoordinator = MemoryCoordinator(container, viewModelScope)
    val memorySetup: StateFlow<MemorySetupState> = memoryCoordinator.state

    fun refreshMemorySetup() = memoryCoordinator.refresh()

    fun setMemoryEnabled(enabled: Boolean) = memoryCoordinator.setEnabled(enabled)

    fun storeMemory(
        rawContent: String,
        category: MemoryCategory = MemoryCategory.FACT,
        rawValidUntil: String? = null,
    ) = memoryCoordinator.store(rawContent, category, rawValidUntil)

    fun replaceMemory(
        memoryId: String,
        rawContent: String,
        category: MemoryCategory,
        rawValidUntil: String? = null,
    ) = memoryCoordinator.replace(memoryId, rawContent, category, rawValidUntil)

    fun reconfirmMemory(memoryId: String) = memoryCoordinator.reconfirm(memoryId)

    fun deleteMemory(memoryId: String) = memoryCoordinator.delete(memoryId)

    fun deleteAllMemories() = memoryCoordinator.deleteAll()

    private val _diagnosticExport = MutableStateFlow(DiagnosticExportState())
    val diagnosticExport: StateFlow<DiagnosticExportState> = _diagnosticExport.asStateFlow()

    private val _userDataTransfer = MutableStateFlow(UserDataTransferState())
    val userDataTransfer: StateFlow<UserDataTransferState> = _userDataTransfer.asStateFlow()
    private var selectedImportArchive: Uri? = null

    fun prepareUserDataExport(selection: UserDataSelection) {
        if (selection.isEmpty || _userDataTransfer.value.inProgress) return
        viewModelScope.launch {
            _userDataTransfer.value = _userDataTransfer.value.copy(inProgress = true, message = null)
            val prepared = runCatching { transferSnapshot(selection) }.getOrNull()
            _userDataTransfer.value = if (prepared == null) {
                UserDataTransferState(message = "내보낼 로컬 데이터를 읽지 못했습니다.")
            } else {
                _userDataTransfer.value.copy(
                    inProgress = false,
                    exportPreview = EncryptedUserDataArchive.preview(prepared),
                    message = "포함 항목을 확인한 뒤 암호화 파일을 저장하세요.",
                )
            }
        }
    }

    fun exportUserData(destination: Uri, selection: UserDataSelection, passphrase: String) {
        if (selection.isEmpty || _userDataTransfer.value.inProgress) return
        viewModelScope.launch {
            _userDataTransfer.value = _userDataTransfer.value.copy(inProgress = true, message = null)
            val result = runCatching {
                val snapshot = transferSnapshot(selection)
                val archive = withContext(Dispatchers.Default) {
                    EncryptedUserDataArchive.encrypt(snapshot, passphrase)
                }
                try {
                    getApplication<Application>().contentResolver
                        .openOutputStream(destination, "wt")
                        ?.use { output -> output.write(archive); output.flush() }
                        ?: error("destination unavailable")
                    archive.size
                } finally {
                    archive.fill(0)
                }
            }
            _userDataTransfer.value = if (result.isSuccess) {
                UserDataTransferState(
                    exportPreview = _userDataTransfer.value.exportPreview,
                    message = "선택한 로컬 데이터를 ${result.getOrThrow()}바이트 암호화 파일로 내보냈습니다.",
                    succeeded = true,
                )
            } else {
                UserDataTransferState(
                    exportPreview = _userDataTransfer.value.exportPreview,
                    message = if (!validTransferPassphrase(passphrase)) {
                        "암호 문구는 제어문자 없이 12자 이상 128자 이하로 입력하세요."
                    } else {
                        "선택한 위치에 암호화 백업을 쓰지 못했습니다."
                    },
                )
            }
        }
    }

    fun selectUserDataImport(uri: Uri) {
        selectedImportArchive = uri
        _userDataTransfer.value = UserDataTransferState(
            importFileSelected = true,
            message = "암호 문구를 입력해 포맷·스키마·무결성을 먼저 확인하세요.",
        )
    }

    fun previewUserDataImport(passphrase: String) {
        val source = selectedImportArchive ?: return
        if (_userDataTransfer.value.inProgress) return
        viewModelScope.launch {
            _userDataTransfer.value = _userDataTransfer.value.copy(inProgress = true, message = null)
            val read = readUserDataArchive(source, passphrase)
            _userDataTransfer.value = when (read) {
                is UserDataArchiveReadResult.Ready -> UserDataTransferState(
                    importFileSelected = true,
                    importPreview = EncryptedUserDataArchive.preview(read.snapshot),
                    message = "무결성 검증을 통과했습니다. 가져오기는 기존 데이터를 덮어쓰지 않고 병합합니다.",
                )
                is UserDataArchiveReadResult.Failed -> UserDataTransferState(
                    importFileSelected = true,
                    message = read.failure.userMessage(),
                )
            }
        }
    }

    fun importUserData(passphrase: String) {
        val source = selectedImportArchive ?: return
        if (_userDataTransfer.value.inProgress || _userDataTransfer.value.importPreview == null) return
        viewModelScope.launch {
            _userDataTransfer.value = _userDataTransfer.value.copy(inProgress = true, message = null)
            val read = readUserDataArchive(source, passphrase)
            val imported = if (read is UserDataArchiveReadResult.Ready) {
                runCatching { container.userDataTransfer.import(read.snapshot) }.getOrNull()
            } else {
                null
            }
            if (imported == null) {
                _userDataTransfer.value = UserDataTransferState(
                    importFileSelected = true,
                    importPreview = _userDataTransfer.value.importPreview,
                    message = if (read is UserDataArchiveReadResult.Failed) {
                        read.failure.userMessage()
                    } else {
                        "검증된 데이터를 로컬 저장소에 병합하지 못했습니다."
                    },
                )
                return@launch
            }
            selectedImportArchive = null
            container.reminderScheduler.requestReconcile()
            memoryCoordinator.refresh()
            reminderCoordinator.refresh()
            _userDataTransfer.value = UserDataTransferState(
                message = imported.summaryMessage(),
                succeeded = true,
            )
        }
    }

    private suspend fun transferSnapshot(selection: UserDataSelection) =
        container.settings.current().let { settings ->
            container.userDataTransfer.snapshot(
                selection = selection,
                calendarRemapRequired = settings.defaultCalendarId != null ||
                    settings.readCalendarIds.isNotEmpty(),
                defaultCalendarLabelHint = settings.defaultCalendarLabel,
            )
        }

    private suspend fun readUserDataArchive(
        source: Uri,
        passphrase: String,
    ): UserDataArchiveReadResult = withContext(Dispatchers.IO) {
        val archive = runCatching {
            getApplication<Application>().contentResolver.openInputStream(source)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1_024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > EncryptedUserDataArchive.MAX_ARCHIVE_BYTES) {
                        throw IllegalArgumentException("archive too large")
                    }
                }
                output.toByteArray()
            } ?: error("source unavailable")
        }.getOrNull() ?: return@withContext UserDataArchiveReadResult.Failed(
            UserDataArchiveFailure.INVALID_FORMAT,
        )
        try {
            EncryptedUserDataArchive.decrypt(archive, passphrase)
        } finally {
            archive.fill(0)
        }
    }

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

    private val notificationCaptureCoordinator =
        NotificationCaptureCoordinator(container, viewModelScope)
    val notificationSetup: StateFlow<NotificationSetupState> = notificationCaptureCoordinator.state

    fun refreshNotificationSetup() = notificationCaptureCoordinator.refresh()

    fun setNotificationCaptureEnabled(enabled: Boolean) =
        notificationCaptureCoordinator.setCaptureEnabled(enabled)

    fun setKakaoNotificationReplyEnabled(enabled: Boolean) =
        notificationCaptureCoordinator.setReplyEnabled(enabled)

    fun deleteCapturedNotifications() = notificationCaptureCoordinator.deleteCaptured()

    private val calendarCoordinator = CalendarCoordinator(getApplication(), container, viewModelScope)
    val calendarSetup: StateFlow<CalendarSetupState> = calendarCoordinator.state

    fun refreshCalendarSetup() = calendarCoordinator.refresh()

    fun onCalendarPermissionResult(granted: Boolean, canAskAgain: Boolean) =
        calendarCoordinator.onPermissionResult(granted, canAskAgain)

    fun pinCalendar(option: CalendarOption) = calendarCoordinator.pin(option)

    fun unpinCalendar() = calendarCoordinator.unpin()

    fun setCalendarReadEnabled(calendarId: Long, enabled: Boolean) =
        calendarCoordinator.setReadEnabled(calendarId, enabled)

    /** Adds bounded device state plus explicitly quoted summary/recent-message context. */
    private suspend fun withTrustedTurnContext(
        prompt: String,
        requiredPriorAnswer: PriorWebResultReference? = null,
        contextConversationId: String? = null,
    ): TrustedTurnContextResult {
        val temporal = runCatching {
            val zone = ZoneId.systemDefault()
            zone to Instant.now().atZone(zone)
        }.getOrElse {
            return TrustedTurnContextResult.Unavailable(
                component = DiagnosticContextComponent.DEVICE_TIME,
                userMessage = "기기의 현재 날짜와 시간대를 확인하지 못해 요청을 시작하지 않았습니다.",
            )
        }
        val (zone, now) = temporal
        val unavailable = linkedSetOf<DiagnosticContextComponent>()

        val settings = runCatching { container.settings.current() }
            .onFailure { unavailable += DiagnosticContextComponent.SETTINGS }
            .getOrNull()
        val conversationId = requiredPriorAnswer?.conversationId
            ?: contextConversationId
            ?: _chatHistory.value.activeConversationId
        val conversation = conversationId?.let { activeConversationId ->
            runCatching {
                container.conversations.loadContext(
                    conversationId = activeConversationId,
                    recentMessageLimit = settings?.recentMessageWindow
                        ?: com.personaledge.core.data.ConversationRepository.DEFAULT_RECENT_MESSAGES,
                )
            }.onFailure { unavailable += DiagnosticContextComponent.CONVERSATION }
                .getOrNull()
        }
        if (requiredPriorAnswer != null) {
            val requiredAnswerAvailable = conversation?.let { loaded ->
                loaded.conversationId == requiredPriorAnswer.conversationId &&
                    loaded.recentMessages.any { message ->
                        message.ordinal == requiredPriorAnswer.assistantMessageOrdinal &&
                            message.role == MessageRole.ASSISTANT &&
                            message.text.isNotBlank()
                    }
            } == true
            if (!requiredAnswerAvailable) {
                return TrustedTurnContextResult.Unavailable(
                    component = DiagnosticContextComponent.CONVERSATION,
                    userMessage = PRIOR_WEB_RESULT_UNAVAILABLE_MESSAGE,
                )
            }
        }
        val recalledMemories = if (
            settings?.let { current ->
                container.ownerConsentInterlock.allowed(
                    OwnerConsentFeature.MEMORY,
                    current.memoryEnabled,
                )
            } == true
        ) {
            val recalled = runCatching {
                container.memories.relevantTo(
                    query = prompt,
                    categories = MemoryRecallPolicy.categoriesFor(prompt),
                )
            }
                .onFailure { unavailable += DiagnosticContextComponent.MEMORY }
                .getOrDefault(emptyList())
            val stillAllowed = runCatching {
                val current = container.settings.current()
                container.ownerConsentInterlock.allowed(
                    OwnerConsentFeature.MEMORY,
                    current.memoryEnabled,
                )
            }.onFailure { unavailable += DiagnosticContextComponent.SETTINGS }
                .getOrDefault(false)
            if (stillAllowed) recalled else emptyList()
        } else {
            emptyList()
        }
        val built = TurnContextBuilder.buildResult(
            prompt = prompt,
            device = TurnDeviceContext(
                localTimestamp = now.format(TURN_CONTEXT_FORMAT),
                timeZoneId = zone.id,
                calendarId = settings?.defaultCalendarId,
                calendarLabel = settings?.defaultCalendarLabel,
            ),
            conversation = conversation,
            memories = recalledMemories.map(MemoryEntity::content),
            maximumBytes = MAX_USER_PROMPT_BYTES,
            requiredPriorAnswer = requiredPriorAnswer,
        )
        if (!built.deviceContextIncluded) {
            return TrustedTurnContextResult.Unavailable(
                component = DiagnosticContextComponent.PROMPT_BUDGET,
                userMessage = "현재 날짜·시간 정보를 함께 전달할 공간이 부족합니다. 요청을 짧게 줄여 주세요.",
            )
        }
        if (requiredPriorAnswer != null && !built.requiredPriorAnswerIncluded) {
            return TrustedTurnContextResult.Unavailable(
                component = DiagnosticContextComponent.PROMPT_BUDGET,
                userMessage = PRIOR_WEB_RESULT_BUDGET_MESSAGE,
            )
        }
        return TrustedTurnContextResult.Ready(
            text = built.text,
            unavailableComponents = unavailable,
            recalledMemoryNotices = recalledMemories
                .take(built.includedMemoryCount)
                .map { memory -> memory.recallNotice(zone) },
        )
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
            if (!PredictiveThermalPolicy.allowBackgroundSummary(thermalMonitor.refresh())) {
                return@launch
            }
            val request = summarizer.requestFor(conversationId) ?: return@launch
            if (_uiState.value.modelStatus != ModelUiStatus.READY) return@launch

            val summary = StringBuilder()
            var toolAttempted = false
            var completed = false
            val turnId = TurnId("summary-${UUID.randomUUID()}")
            try {
                controller.runTurn(
                    turnId = turnId,
                    prompt = request.prompt,
                    turnLimits = controller.toolFreeLimits,
                    toolScope = LlmTurnToolScope.none(),
                )
                    .collect { event ->
                        when (event) {
                            is AgentEvent.ThoughtDelta -> Unit
                            is AgentEvent.TextDelta -> summary.append(event.text)
                            is AgentEvent.TrustedAnswer -> summary.append(event.text)
                            is AgentEvent.ToolExecuted -> toolAttempted = true
                            is AgentEvent.Failure -> toolAttempted = true
                            is AgentEvent.Completed -> completed = true
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }

            if (
                BackgroundSummaryPriority.mayAccept(
                    completed = completed,
                    toolAttemptedOrFailed = toolAttempted,
                    observation = thermalMonitor.refresh(),
                )
            ) {
                summarizer.acceptSummary(request, summary.toString())
            }
        }
    }


    // ------------------------------------------------------------------------------------------
    // Photo and voice input
    //
    // Everything below shares one rule with no exception: a turn that carries media is given no
    // Tool schema. A photographed note or a spoken sentence can read like an instruction, and this
    // app cannot tell an observed instruction from the owner's own. So media turns report, and
    // only report. The one path from speech to action is dictation, which puts a transcript in the
    // composer for the owner to read and send themselves — at which point it is an ordinary text
    // turn that has earned its scope the ordinary way.
    // ------------------------------------------------------------------------------------------

    fun setMediaInputEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { container.settings.setMediaInputEnabled(enabled) }
            if (!enabled) {
                cancelVoiceRecording()
                clearStagedMedia(DiagnosticMediaStage.DISCARDED)
            }
        }
    }

    fun dismissMediaNotice() {
        _uiState.update { state -> state.copy(mediaNotice = null) }
    }

    /**
     * Creates the one staged capture target and hands its URI back to the caller's launcher.
     *
     * The URI is produced only after the availability check passes, so a refused capture never
     * creates a file at all.
     */
    fun requestCameraCapture(onReady: (Uri?) -> Unit) {
        val reason = mediaUnavailableReason(TurnMediaKind.IMAGE)
        if (reason != null) {
            showMediaNotice(MediaComposerPolicy.message(reason))
            onReady(null)
            return
        }
        viewModelScope.launch {
            val uri = mediaStaging.prepareCaptureUri()
            if (uri == null) showMediaNotice(MediaComposerPolicy.rejectionMessage(TurnMediaKind.IMAGE))
            onReady(uri)
        }
    }

    fun onCameraCaptured(success: Boolean) {
        viewModelScope.launch {
            if (!success) {
                // A cancelled capture is not an error worth a notice; just remove the staged file.
                mediaStaging.clear()
                return@launch
            }
            val bytes = mediaStaging.consumeCapture()
            if (bytes == null) {
                recordMediaDiagnostic(TurnMediaKind.IMAGE, DiagnosticMediaStage.REJECTED, 0)
                showMediaNotice(MediaComposerPolicy.rejectionMessage(TurnMediaKind.IMAGE))
                return@launch
            }
            stageImage(imageLoader.loadOrNull(bytes), MediaAttachmentSource.CAMERA)
        }
    }

    fun onImageSelected(uri: Uri?) {
        if (uri == null) return
        val reason = mediaUnavailableReason(TurnMediaKind.IMAGE)
        if (reason != null) {
            showMediaNotice(MediaComposerPolicy.message(reason))
            return
        }
        viewModelScope.launch {
            stageImage(imageLoader.loadOrNull(uri), MediaAttachmentSource.GALLERY)
        }
    }

    fun onMicrophonePermissionDenied() {
        showMediaNotice(MediaComposerPolicy.MICROPHONE_PERMISSION_MESSAGE)
    }

    /**
     * Starts recording. [dictation] selects where the result goes, not what is recorded.
     *
     * With dictation the clip becomes an editable draft in the composer; without it the clip
     * becomes an attachment the owner can caption and send like a photo.
     */
    fun startVoiceRecording(dictation: Boolean) {
        val reason = mediaUnavailableReason(TurnMediaKind.AUDIO)
        if (reason != null) {
            showMediaNotice(MediaComposerPolicy.message(reason))
            return
        }
        if (!voiceRecordingActive.compareAndSet(false, true)) return
        _uiState.update { state ->
            state.copy(
                voiceRecording = VoiceRecordingUiState(elapsedMillis = 0, dictation = dictation),
                mediaNotice = null,
            )
        }
        voiceJob = viewModelScope.launch {
            val result = try {
                voiceRecorder.record(
                    shouldContinue = voiceRecordingActive::get,
                    onElapsed = { elapsed ->
                        _uiState.update { state ->
                            val recording = state.voiceRecording ?: return@update state
                            state.copy(voiceRecording = recording.copy(elapsedMillis = elapsed))
                        }
                    },
                )
            } finally {
                voiceRecordingActive.set(false)
                _uiState.update { state -> state.copy(voiceRecording = null) }
            }
            when (result) {
                is VoiceRecordingResult.Captured -> {
                    recordMediaDiagnostic(
                        kind = TurnMediaKind.AUDIO,
                        stage = DiagnosticMediaStage.STAGED,
                        byteCount = result.attachment.byteCount,
                        durationMillis = result.durationMillis,
                    )
                    if (dictation) {
                        transcribe(result.attachment, result.durationMillis)
                    } else {
                        stageVoiceAttachment(result.attachment, result.durationMillis)
                    }
                }

                VoiceRecordingResult.TooShort -> {
                    recordMediaDiagnostic(TurnMediaKind.AUDIO, DiagnosticMediaStage.REJECTED, 0, 0)
                    showMediaNotice(MediaComposerPolicy.RECORDING_TOO_SHORT_MESSAGE)
                }

                VoiceRecordingResult.Silent -> {
                    recordMediaDiagnostic(TurnMediaKind.AUDIO, DiagnosticMediaStage.REJECTED, 0, 0)
                    showMediaNotice(MediaComposerPolicy.RECORDING_SILENT_MESSAGE)
                }

                VoiceRecordingResult.Unavailable -> {
                    recordMediaDiagnostic(TurnMediaKind.AUDIO, DiagnosticMediaStage.REJECTED, 0, 0)
                    showMediaNotice(MediaComposerPolicy.RECORDING_UNAVAILABLE_MESSAGE)
                }
            }
        }
    }

    /** Ends the recording and keeps what was captured. */
    fun stopVoiceRecording() {
        voiceRecordingActive.set(false)
    }

    /** Ends the recording and throws away what was captured. */
    fun cancelVoiceRecording() {
        voiceRecordingActive.set(false)
        voiceJob?.cancel(CancellationException("Owner cancelled the recording."))
        voiceJob = null
        _uiState.update { state -> state.copy(voiceRecording = null) }
    }

    fun removeAttachment() {
        clearStagedMedia(DiagnosticMediaStage.DISCARDED)
    }

    private fun mediaUnavailableReason(kind: TurnMediaKind): MediaUnavailableReason? {
        val state = _uiState.value
        return MediaComposerPolicy.unavailableReason(
            kind = kind,
            enabled = state.mediaInputEnabled,
            modelSupportsKind = when (kind) {
                TurnMediaKind.IMAGE -> modelManifest.supportsImageInput
                TurnMediaKind.AUDIO -> modelManifest.supportsAudioInput
            },
            modelStatus = state.modelStatus,
            runtimeLoadedKind = kind in initializedMediaModalities,
            turnActive = state.activeTurnId != null || state.transcribing,
            recording = state.voiceRecording != null,
            hasAttachment = state.pendingAttachment != null,
            thermalStatus = state.thermalStatus,
        )
    }

    private fun stageImage(loaded: LoadedImageAttachment?, source: MediaAttachmentSource) {
        if (loaded == null) {
            recordMediaDiagnostic(TurnMediaKind.IMAGE, DiagnosticMediaStage.REJECTED, 0)
            showMediaNotice(MediaComposerPolicy.rejectionMessage(TurnMediaKind.IMAGE))
            return
        }
        // A second capture that lands while one is already staged replaces it rather than
        // silently winning or silently losing.
        stagedMedia = loaded.attachment
        stagedMediaIntent = null
        recordMediaDiagnostic(
            kind = TurnMediaKind.IMAGE,
            stage = DiagnosticMediaStage.STAGED,
            byteCount = loaded.attachment.byteCount,
        )
        _uiState.update { state ->
            state.copy(
                mediaNotice = null,
                pendingAttachment = PendingMediaAttachment(
                    id = UUID.randomUUID().toString(),
                    kind = TurnMediaKind.IMAGE,
                    source = source,
                    byteCount = loaded.attachment.byteCount,
                    pixelWidth = loaded.pixelWidth,
                    pixelHeight = loaded.pixelHeight,
                ),
            )
        }
    }

    private fun stageVoiceAttachment(attachment: TurnMediaAttachment, durationMillis: Int) {
        stagedMedia = attachment
        stagedMediaIntent = null
        _uiState.update { state ->
            state.copy(
                mediaNotice = null,
                pendingAttachment = PendingMediaAttachment(
                    id = UUID.randomUUID().toString(),
                    kind = TurnMediaKind.AUDIO,
                    source = MediaAttachmentSource.VOICE,
                    byteCount = attachment.byteCount,
                    durationMillis = durationMillis,
                ),
            )
        }
    }

    private fun clearStagedMedia(stage: DiagnosticMediaStage) {
        val current = _uiState.value.pendingAttachment
        if (current != null && stage == DiagnosticMediaStage.DISCARDED) {
            recordMediaDiagnostic(
                kind = current.kind,
                stage = stage,
                byteCount = current.byteCount,
                durationMillis = current.durationMillis,
            )
        }
        stagedMedia = null
        stagedMediaIntent = null
        _uiState.update { state -> state.copy(pendingAttachment = null) }
    }

    private fun showMediaNotice(message: String) {
        _uiState.update { state -> state.copy(mediaNotice = message) }
    }

    private fun recordMediaDiagnostic(
        kind: TurnMediaKind,
        stage: DiagnosticMediaStage,
        byteCount: Int,
        durationMillis: Int? = null,
    ) {
        diagnostics.recordSafely(
            DiagnosticEvent.MediaAttachment(
                kind = when (kind) {
                    TurnMediaKind.IMAGE -> DiagnosticMediaKind.IMAGE
                    TurnMediaKind.AUDIO -> DiagnosticMediaKind.AUDIO
                },
                stage = stage,
                byteCount = byteCount,
                durationSeconds = when (kind) {
                    // The event shape requires a duration for audio and forbids one for an image.
                    TurnMediaKind.AUDIO -> ((durationMillis ?: 0) + 999) / 1_000
                    TurnMediaKind.IMAGE -> null
                },
            ),
        )
    }


    /**
     * Runs one tool-free turn whose prefill carries the staged attachment.
     *
     * Deliberately a separate path from [startPrompt] rather than a flag on it. A text turn
     * carries automatic Tool scoping, deterministic read routing, follow-up carry-over, recovery
     * capsules and Tool receipts; a media turn has none of those by design, and threading an
     * attachment through all of them would mean five more places where "but not for media" has to
     * stay true. There is also nothing to recover: the attachment exists only while this runs, so
     * a media turn deliberately writes no recovery capsule.
     */
    private fun startMediaTurn() {
        val mutationLease = conversationMutationGate.tryAcquire() ?: return
        try {
            if (!conversationMutationGate.owns(mutationLease)) return
            val attachment = stagedMedia ?: return
            val snapshot = _uiState.value
            val pending = snapshot.pendingAttachment ?: return
            val ownerText = snapshot.prompt.trim()
            if (!snapshot.canSend) return
            if (snapshot.activeTurnId != null || turnJob?.isActive == true) return

            val startThermalObservation = thermalMonitor.refresh()
            if (!ThermalTurnPolicy.canStart(startThermalObservation.status)) {
                diagnostics.recordSafely(
                    DiagnosticEvent.ThermalGuard(
                        status = startThermalObservation.status,
                        action = DiagnosticThermalAction.TURN_REJECTED,
                    ),
                )
                addMessage(ChatRole.STATUS, "기기 열 보호 정책이 새 요청을 허용하지 않습니다.")
                return
            }

            val plan = TurnMediaPolicy.planOrNull(
                kind = attachment.kind,
                ownerText = ownerText,
                requestedIntent = stagedMediaIntent,
            )
            if (plan == null) {
                showMediaNotice(
                    "첨부와 함께 보낼 수 있는 글자 수를 넘었습니다. 내용을 줄여 주세요.",
                )
                return
            }

            val pendingSummary = BackgroundSummaryPriority.cancelForUserTurn(summaryJob)
            val turnId = TurnId("turn-${UUID.randomUUID()}")
            val thermalTurn = ActiveThermalTurn(
                turnId = turnId,
                baselineStopSequence = startThermalObservation.stopSequence,
            )
            if (!activeThermalTurn.compareAndSet(null, thermalTurn)) return

            val assistantEntryId = "assistant-${turnId.value}-0"
            val attachmentSummary = MessageAttachmentSummary.encode(pending)
            val attachmentLabel = MediaAttachmentPresentation.transcriptLabel(
                DecodedAttachmentSummary(pending.kind, pending.source, pending.durationMillis?.let {
                    (it + 999) / 1_000
                }),
            )
            stagedMedia = null
            stagedMediaIntent = null
            _uiState.update { state ->
                state.copy(
                    prompt = "",
                    promptInputWarning = null,
                    mediaNotice = null,
                    pendingAttachment = null,
                    activeTurnId = turnId,
                    activeReasoning = ActiveReasoningUiPolicy.start(turnId, assistantEntryId),
                    messages = state.messages + listOf(
                        ChatEntry(
                            id = "user-${turnId.value}",
                            role = ChatRole.USER,
                            text = ownerText,
                            attachmentLabel = attachmentLabel,
                        ),
                        ChatEntry(assistantEntryId, ChatRole.ASSISTANT, ""),
                    ),
                )
            }

            // The gate covers starting the turn, not its whole lifetime — the same contract the
            // text path uses, so switching or deleting a conversation stays possible while a photo
            // is being described.
            turnJob = viewModelScope.launch {
                BackgroundSummaryPriority.awaitRelease(pendingSummary)
                val startedAt = SystemClock.elapsedRealtime()
                var deltaCount = 0
                var deltaByteCount = 0L
                var completed = false
                var conversationId: String? = null
                try {
                    conversationId = history.ensureConversation(
                        activeConversationId = _chatHistory.value.activeConversationId,
                        firstPrompt = ownerText.ifEmpty { attachmentLabel },
                    )
                    _chatHistory.update { state -> state.copy(activeConversationId = conversationId) }

                    // A media turn keeps its separate, tool-free execution boundary, but it must
                    // not lose the trusted context every ordinary text turn receives. Build the
                    // context before recording this USER row so the current attachment is not
                    // echoed back as previous history. Only the app-authored media plan, current
                    // device clock/zone, and already persisted conversation data reach the model.
                    val trustedContext = withTrustedTurnContext(
                        prompt = plan.prompt,
                        contextConversationId = conversationId,
                    )
                    if (trustedContext is TrustedTurnContextResult.Unavailable) {
                        diagnostics.recordSafely(
                            DiagnosticEvent.ContextUnavailable(trustedContext.component),
                        )
                        removeMessageIfBlank(assistantEntryId)
                        addMessage(ChatRole.STATUS, trustedContext.userMessage)
                        return@launch
                    }
                    trustedContext as TrustedTurnContextResult.Ready
                    trustedContext.unavailableComponents.forEach { component ->
                        diagnostics.recordSafely(DiagnosticEvent.ContextUnavailable(component))
                    }
                    if (trustedContext.unavailableComponents.isNotEmpty()) {
                        addMessage(
                            ChatRole.STATUS,
                            "대화 문맥 일부를 불러오지 못했지만 현재 날짜와 시간대는 확인했습니다.",
                        )
                    }
                    trustedContext.recalledMemoryNotices.forEach { notice ->
                        addMessage(ChatRole.STATUS, notice)
                    }
                    val requestText = trustedContext.text

                    // The typed line and a content-free attachment shape; never the media itself.
                    val userMessageOrdinal = history.recordOrdinal(
                        conversationId = conversationId,
                        role = MessageRole.USER,
                        text = ownerText,
                        attachmentSummary = attachmentSummary,
                    )
                    if (userMessageOrdinal == null) {
                        _chatHistory.update { state ->
                            state.copy(error = "이 첨부 요청은 현재 기기에 저장되지 않고 있습니다.")
                        }
                    }

                    diagnostics.markPhase(DiagnosticPhase.TURN_PROCESSING)
                    diagnostics.recordSafely(
                        DiagnosticEvent.TurnStarted(requestText.toByteArray(Charsets.UTF_8).size),
                    )
                    recordMediaDiagnostic(
                        kind = attachment.kind,
                        stage = DiagnosticMediaStage.PREFILLED,
                        byteCount = attachment.byteCount,
                        durationMillis = attachment.durationMillis,
                    )

                    controller.runTurn(
                        turnId = turnId,
                        prompt = requestText,
                        turnLimits = mediaTurnLimits(plan.maxOutputTokens),
                        media = listOf(attachment),
                    ).collect { event ->
                        when (event) {
                            is AgentEvent.ThoughtDelta -> appendActiveReasoning(
                                turnId = turnId,
                                assistantEntryId = assistantEntryId,
                                delta = event.text,
                            )

                            is AgentEvent.TextDelta -> {
                                clearActiveReasoning(turnId, assistantEntryId)
                                if (event.text.isNotEmpty()) {
                                    if (deltaCount < Int.MAX_VALUE) deltaCount++
                                    deltaByteCount = saturatedAdd(
                                        deltaByteCount,
                                        event.text.toByteArray(Charsets.UTF_8).size.toLong(),
                                    )
                                }
                                appendToMessage(assistantEntryId, event.text)
                            }

                            is AgentEvent.TrustedAnswer -> {
                                clearActiveReasoning(turnId, assistantEntryId)
                                appendToMessage(assistantEntryId, event.text)
                            }

                            is AgentEvent.Completed -> {
                                completed = true
                                diagnostics.recordSafely(
                                    DiagnosticEvent.TurnCompleted(
                                        durationMillis = diagnosticDuration(startedAt),
                                        deltaCount = deltaCount,
                                        deltaByteCount = deltaByteCount,
                                    ),
                                )
                            }

                            is AgentEvent.Failure -> {
                                diagnostics.recordSafely(
                                    DiagnosticEvent.TurnFailed(
                                        durationMillis = diagnosticDuration(startedAt),
                                        deltaCount = deltaCount,
                                        deltaByteCount = deltaByteCount,
                                        errorCode = event.runtimeCode?.toDiagnosticErrorCode()
                                            ?: event.code.toDiagnosticErrorCode(),
                                    ),
                                )
                                removeMessageIfBlank(assistantEntryId)
                                addMessage(ChatRole.STATUS, mediaFailureText(event))
                            }

                            // A media turn is given no Tool, so a receipt here would mean the
                            // scope boundary failed. Surface it rather than rendering it.
                            is AgentEvent.ToolExecuted -> addMessage(
                                ChatRole.STATUS,
                                "첨부 요청에서 예상하지 않은 도구 실행이 보고돼 중단했습니다.",
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    diagnostics.recordSafely(
                        DiagnosticEvent.TurnCancelled(
                            durationMillis = diagnosticDuration(startedAt),
                            deltaCount = deltaCount,
                            deltaByteCount = deltaByteCount,
                            cause = thermalTurn.cancellationCause.current()
                                ?: DiagnosticTurnCancellationCause.LIFECYCLE,
                            thermalStatus = thermalTurn.latch.currentDecision()?.status,
                        ),
                    )
                    removeMessageIfBlank(assistantEntryId)
                    addMessage(ChatRole.STATUS, cancellationStatusText(thermalTurn))
                    throw cancelled
                } catch (failure: Exception) {
                    diagnostics.recordSafely(
                        DiagnosticEvent.TurnFailed(
                            durationMillis = diagnosticDuration(startedAt),
                            deltaCount = deltaCount,
                            deltaByteCount = deltaByteCount,
                            errorCode = DiagnosticErrorCode.UNKNOWN,
                            failure = failure.toDiagnosticFailureOrNull(),
                        ),
                    )
                    removeMessageIfBlank(assistantEntryId)
                    addMessage(ChatRole.STATUS, "예기치 않은 오류로 첨부 요청을 중단했습니다.")
                } finally {
                    // NonCancellable so a stopped turn still keeps whatever the model produced.
                    withContext(NonCancellable) {
                        val answer = currentMessageText(assistantEntryId)
                        if (answer.isNotBlank()) {
                            val stored = history.record(
                                conversationId = conversationId,
                                role = MessageRole.ASSISTANT,
                                text = answer,
                            )
                            if (!stored) {
                                _chatHistory.update { state ->
                                    state.copy(error = "첨부 답변을 현재 기기에 저장하지 못했습니다.")
                                }
                            }
                        } else {
                            removeMessageIfBlank(assistantEntryId)
                        }
                    }
                    if (!completed) diagnostics.markPhase(DiagnosticPhase.IDLE)
                    activeThermalTurn.compareAndSet(thermalTurn, null)
                    _uiState.update { state ->
                        if (state.activeTurnId == turnId) {
                            state.copy(activeTurnId = null, activeReasoning = null)
                        } else {
                            state
                        }
                    }
                    finishDiagnosticPhase()
                }
                // Media-only conversations need the same bounded rolling capsule as text chats.
                // Start it only after this turn has released the single-owner controller.
                summarizeInBackground(conversationId)
            }.also(thermalTurn.job::set)
        } finally {
            mutationLease.close()
        }
    }

    /**
     * Turns one recorded clip into an editable composer draft.
     *
     * Not a conversation turn: nothing is written to Room and nothing appears in the transcript.
     * That is the whole point of the dictation path — the owner reads what was heard, corrects it,
     * and only then sends a normal text request that may reach a Tool.
     */
    private fun transcribe(attachment: TurnMediaAttachment, durationMillis: Int) {
        if (turnJob?.isActive == true || _uiState.value.activeTurnId != null) return
        val plan = TurnMediaPolicy.planOrNull(
            kind = TurnMediaKind.AUDIO,
            ownerText = "",
            requestedIntent = TurnMediaIntent.AUDIO_TRANSCRIBE,
        ) ?: return

        val startThermalObservation = thermalMonitor.refresh()
        if (!ThermalTurnPolicy.canStart(startThermalObservation.status)) {
            showMediaNotice("기기 열 보호 정책이 새 요청을 허용하지 않습니다.")
            return
        }
        val turnId = TurnId("turn-${UUID.randomUUID()}")
        val thermalTurn = ActiveThermalTurn(
            turnId = turnId,
            baselineStopSequence = startThermalObservation.stopSequence,
        )
        if (!activeThermalTurn.compareAndSet(null, thermalTurn)) return
        _uiState.update { state -> state.copy(transcribing = true, mediaNotice = null) }

        turnJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val transcript = StringBuilder()
            try {
                recordMediaDiagnostic(
                    kind = TurnMediaKind.AUDIO,
                    stage = DiagnosticMediaStage.PREFILLED,
                    byteCount = attachment.byteCount,
                    durationMillis = durationMillis,
                )
                controller.runTurn(
                    turnId = turnId,
                    prompt = plan.prompt,
                    turnLimits = mediaTurnLimits(plan.maxOutputTokens),
                    media = listOf(attachment),
                ).collect { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            if (transcript.length < MAX_TRANSCRIPT_CHARACTERS) {
                                transcript.append(event.text)
                            }
                        }

                        is AgentEvent.Completed -> diagnostics.recordSafely(
                            DiagnosticEvent.TurnCompleted(
                                durationMillis = diagnosticDuration(startedAt),
                                deltaCount = 1,
                                deltaByteCount = transcript.length.toLong(),
                            ),
                        )

                        is AgentEvent.Failure -> showMediaNotice(mediaFailureText(event))

                        // Thoughts stay ephemeral, and a trusted answer or a Tool receipt has no
                        // meaning for a transcription; none of them belongs in the draft.
                        else -> Unit
                    }
                }
                applyTranscription(transcript.toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showMediaNotice(MediaComposerPolicy.RECORDING_UNAVAILABLE_MESSAGE)
            } finally {
                activeThermalTurn.compareAndSet(thermalTurn, null)
                _uiState.update { state -> state.copy(transcribing = false) }
                finishDiagnosticPhase()
            }
            // Registered synchronously rather than from inside the body, so a thermal directive
            // arriving before the coroutine starts still has an owning job to cancel.
        }.also(thermalTurn.job::set)
    }

    /**
     * Puts the transcript in the composer, appending to whatever the owner already typed.
     *
     * The result is a draft, never a sent request, and it goes through the same input policy as
     * typing: a transcript that would not fit as text does not get to bypass the limit.
     */
    private fun applyTranscription(rawTranscript: String) {
        val transcript = rawTranscript.trim()
        if (transcript.isEmpty()) {
            showMediaNotice(MediaComposerPolicy.TRANSCRIPTION_EMPTY_MESSAGE)
            return
        }
        val current = _uiState.value.prompt
        val candidate = if (current.isBlank()) transcript else "$current $transcript"
        val update = PromptInputPolicy.apply(current, candidate)
        _uiState.update { state ->
            state.copy(prompt = update.prompt, promptInputWarning = update.warning)
        }
        if (update.prompt == current) {
            showMediaNotice(MediaComposerPolicy.TRANSCRIPTION_TOO_LONG_MESSAGE)
        }
    }

    /**
     * A media turn's budget.
     *
     * `maxSteps = 1` is the enforcement, not a hint: the loop aborts on any Tool call in the first
     * completed step, before preparation, so no confirmation sheet can appear behind a photo. The
     * deadline is longer than a text turn's because the vision and audio encoders run a
     * substantial extra prefill before the first token.
     */
    private fun mediaTurnLimits(maxOutputTokens: Int): AgentLoopLimits = AgentLoopLimits(
        maxSteps = 1,
        deadlineMillis = MEDIA_TURN_DEADLINE_MILLIS,
        maxOutputTokens = maxOutputTokens,
        maxToolCalls = 1,
    )

    private fun mediaFailureText(event: AgentEvent.Failure): String = when (event.runtimeCode) {
        LlmFailureCode.MEDIA_UNSUPPORTED ->
            "설치된 모델이 이 입력 형식을 지원하지 않습니다."

        LlmFailureCode.INVALID_MEDIA ->
            "첨부를 사용할 수 없습니다. 다시 선택하거나 다시 녹음해 주세요."

        LlmFailureCode.CONTEXT_BUDGET_EXCEEDED ->
            "첨부와 요청이 한 번에 처리할 수 있는 크기를 넘었습니다. 요청을 줄여 주세요."

        else -> when (event.code) {
            AgentFailureCode.DEADLINE_EXCEEDED ->
                "첨부 처리 시간이 초과돼 요청을 중단했습니다."

            else -> "첨부 요청을 완료하지 못했습니다."
        }
    }

    fun sendPrompt() {
        if (stagedMedia != null) {
            startMediaTurn()
            return
        }
        startPrompt(readOnlyRecovery = false, predecessorRecovery = null)
    }

    private fun startPrompt(
        readOnlyRecovery: Boolean,
        predecessorRecovery: ChatRecoveryAction?,
        heldConversationMutationLease: ConversationMutationGate.Lease? = null,
    ): Boolean {
        val acquiredMutationLease = heldConversationMutationLease == null
        val mutationLease = heldConversationMutationLease
            ?: conversationMutationGate.tryAcquire()
            ?: return false
        try {
        if (!conversationMutationGate.owns(mutationLease)) return false
        val snapshot = _uiState.value
        val prompt = snapshot.prompt.trim()
        val deterministicReadAvailable =
            DeterministicReadRouter.canRunWithoutModel(prompt) &&
                snapshot.modelStatus != ModelUiStatus.CHECKING &&
                snapshot.modelStatus != ModelUiStatus.IMPORTING &&
                snapshot.modelStatus != ModelUiStatus.INITIALIZING &&
                modelJob?.isActive != true
        if (
            snapshot.modelStatus != ModelUiStatus.READY && !deterministicReadAvailable ||
            snapshot.activeTurnId != null ||
            prompt.isEmpty() ||
            !PromptInputPolicy.isAccepted(snapshot.prompt) ||
            turnJob?.isActive == true
        ) {
            return false
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
            return false
        }

        // A background recap uses the same single-owner controller. User work always wins: cancel
        // it now and join it inside the turn job before recording the prompt or calling runtime.
        val pendingSummary = BackgroundSummaryPriority.cancelForUserTurn(summaryJob)

        val turnId = TurnId("turn-${UUID.randomUUID()}")
        val thermalTurn = ActiveThermalTurn(
            turnId = turnId,
            baselineStopSequence = startThermalObservation.stopSequence,
        )
        if (!activeThermalTurn.compareAndSet(null, thermalTurn)) return false
        val initialAssistantEntryId = "assistant-${turnId.value}-0"
        _uiState.update {
            it.copy(
                prompt = "",
                promptInputWarning = null,
                activeTurnId = turnId,
                activeReasoning = ActiveReasoningUiPolicy.start(turnId, initialAssistantEntryId),
                messages = it.messages + buildList {
                    add(ChatEntry("user-${turnId.value}", ChatRole.USER, prompt))
                    add(ChatEntry(initialAssistantEntryId, ChatRole.ASSISTANT, ""))
                },
            )
        }

        turnJob = viewModelScope.launch {
            BackgroundSummaryPriority.awaitRelease(pendingSummary)
            val startedAt = SystemClock.elapsedRealtime()
            val priorWebResultFollowUp = PriorWebResultFollowUpPolicy.matches(prompt)
            val requiredPriorAnswer = if (priorWebResultFollowUp) {
                history.priorWebResultForFollowUp(
                    conversationId = _chatHistory.value.activeConversationId,
                    followUp = prompt,
                )
            } else {
                null
            }
            if (priorWebResultFollowUp && requiredPriorAnswer == null) {
                diagnostics.recordSafely(
                    DiagnosticEvent.ContextUnavailable(DiagnosticContextComponent.CONVERSATION),
                )
                removeMessageIfBlank(initialAssistantEntryId)
                addMessage(ChatRole.STATUS, PRIOR_WEB_RESULT_UNAVAILABLE_MESSAGE)
                activeThermalTurn.compareAndSet(thermalTurn, null)
                _uiState.update { state ->
                    if (state.activeTurnId == turnId) {
                        state.copy(activeTurnId = null, activeReasoning = null)
                    } else {
                        state
                    }
                }
                finishDiagnosticPhase()
                return@launch
            }
            val unfinishedReadRequest = history.unfinishedReadRequestForFollowUp(
                conversationId = _chatHistory.value.activeConversationId,
                followUp = prompt,
            )
            val contextualWebSearchRequest = if (
                unfinishedReadRequest == null && requiredPriorAnswer == null
            ) {
                history.contextualWebSearchRequestForFollowUp(
                    conversationId = _chatHistory.value.activeConversationId,
                    followUp = prompt,
                )
            } else {
                null
            }
            val recentWeatherRead = history.hasRecentWeatherRead(
                _chatHistory.value.activeConversationId,
            )
            // A short answer to this app's own question is a selector, not a request. Carrying the
            // original text keeps the local model from re-deriving the request from one word, and
            // the trusted line keeps it from asking the same question again.
            val approvedRequest = if (
                unfinishedReadRequest == null &&
                contextualWebSearchRequest == null &&
                requiredPriorAnswer == null
            ) {
                controller.resumedRequestOrNull(prompt)
            } else {
                null
            }
            val effectivePrompt = unfinishedReadRequest?.let { original ->
                buildString {
                    append("이전 읽기 요청은 도구 실행 후 최종 답변이 완료되지 않았습니다. ")
                    append("아래 원래 요청을 지금 새로 수행하고 결과를 답하세요. ")
                    append("이전 도구 영수증을 결과로 간주하지 말고 필요한 읽기 도구를 다시 호출하세요.\n")
                    append("[원래 요청]\n")
                    append(original.text)
                }
            } ?: approvedRequest?.let { original ->
                buildString {
                    append("사용자가 아래 원래 요청을 이미 승인했습니다. 확인 질문을 다시 하지 말고 ")
                    append("지금 필요한 도구를 호출해 실행하세요.\n")
                    append("[승인된 원래 요청]\n")
                    append(original)
                }
            } ?: prompt
            if (unfinishedReadRequest != null) {
                addMessage(ChatRole.STATUS, "이전의 미완료 읽기 요청을 다시 수행합니다.")
            }
            val trustedContext = withTrustedTurnContext(
                prompt = effectivePrompt,
                requiredPriorAnswer = requiredPriorAnswer,
            )
            if (trustedContext is TrustedTurnContextResult.Unavailable) {
                diagnostics.recordSafely(DiagnosticEvent.ContextUnavailable(trustedContext.component))
                removeMessageIfBlank(initialAssistantEntryId)
                addMessage(ChatRole.STATUS, trustedContext.userMessage)
                activeThermalTurn.compareAndSet(thermalTurn, null)
                _uiState.update { state ->
                    if (state.activeTurnId == turnId) {
                        state.copy(activeTurnId = null, activeReasoning = null)
                    } else {
                        state
                    }
                }
                finishDiagnosticPhase()
                return@launch
            }
            trustedContext as TrustedTurnContextResult.Ready
            trustedContext.unavailableComponents.forEach { component ->
                diagnostics.recordSafely(DiagnosticEvent.ContextUnavailable(component))
            }
            if (trustedContext.unavailableComponents.isNotEmpty()) {
                addMessage(
                    ChatRole.STATUS,
                    "대화 문맥 일부를 불러오지 못했지만 현재 날짜와 시간대는 확인했습니다.",
                )
            }
            trustedContext.recalledMemoryNotices.forEach { notice ->
                addMessage(ChatRole.STATUS, notice)
            }
            val requestText = trustedContext.text
            // The typed prompt is stored, never the derived preamble: the date and calendar in it
            // describe the moment of the turn and would be wrong on restore.
            val conversationId = requiredPriorAnswer?.conversationId
                ?: history.ensureConversation(
                    activeConversationId = _chatHistory.value.activeConversationId,
                    firstPrompt = prompt,
                )
            _chatHistory.update { state -> state.copy(activeConversationId = conversationId) }
            val userMessageOrdinal = history.recordOrdinal(conversationId, MessageRole.USER, prompt)
            if (userMessageOrdinal == null) {
                _chatHistory.update { state ->
                    state.copy(error = "이 대화는 현재 기기에 저장되지 않고 있습니다.")
                }
            }
            val recoveryCapsuleStarted = history.startTurnRecoveryCapsule(
                turnId = turnId.value,
                conversationId = conversationId,
                persistedUserMessageOrdinal = userMessageOrdinal,
                unfinishedReadRequest = unfinishedReadRequest,
                contextualWebSearchRequest = contextualWebSearchRequest,
                predecessorTurnId = predecessorRecovery?.turnId,
            )
            if (userMessageOrdinal != null && !recoveryCapsuleStarted) {
                _chatHistory.update { state ->
                    state.copy(error = "중단된 요청의 안전 복구 상태를 저장하지 못했습니다.")
                }
            }
            if (predecessorRecovery != null && !recoveryCapsuleStarted) {
                removeMessageIfBlank(initialAssistantEntryId)
                addMessage(
                    ChatRole.STATUS,
                    "기존 복구 상태를 새 요청으로 안전하게 넘기지 못해 읽기를 시작하지 않았습니다.",
                )
                activeThermalTurn.compareAndSet(thermalTurn, null)
                _uiState.update { state ->
                    if (state.activeTurnId == turnId) {
                        state.copy(activeTurnId = null, activeReasoning = null)
                    } else {
                        state
                    }
                }
                finishDiagnosticPhase()
                return@launch
            }
            if (predecessorRecovery != null) {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.filterNot { entry ->
                            entry.id == actionEntryId(predecessorRecovery)
                        },
                    )
                }
            }
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
            var turnCompleted = false
            var turnFailureCode: TurnOutcomeFailureCode? = null
            var turnCancelled = false
            val processedToolExecutions = mutableMapOf<Int, ProcessedToolExecution>()
            val failedToolReceiptOrdinals = mutableSetOf<Int>()

            suspend fun processToolExecution(event: AgentEvent.ToolExecuted): Boolean =
                ToolReceiptCommitBoundary.commit {
                    val execution = ProcessedToolExecution(
                        toolName = event.toolName,
                        outcome = event.outcome,
                    )
                    processedToolExecutions[event.ordinal]?.let { processed ->
                        // Controller delivery and retained-outcome reconciliation can race. The
                        // exact same trusted event is a successful no-op; reusing an ordinal for
                        // a different closed outcome is a protocol violation and fails closed.
                        return@commit processed == execution
                    }
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
                    val nextAssistantPhase = assistantPhase + 1
                    val nextAssistantEntryId =
                        "assistant-${turnId.value}-$nextAssistantPhase"
                    val finalizedAssistantText = currentMessageText(assistantEntryId)
                    val toolReceipt = ToolReceiptFormatter.text(event.toolName, event.outcome)
                    val committed = history.commitToolExecution(
                        conversationId = conversationId,
                        turnId = turnId.value,
                        assistantText = finalizedAssistantText,
                        toolReceipt = toolReceipt,
                        toolName = event.toolName,
                        toolRisk = TurnRecoveryPolicy.toolRisk(event.toolName),
                        trustedOrdinal = event.ordinal,
                        outcome = when (event.outcome) {
                            ToolExecutionOutcome.READ_COMPLETED ->
                                TurnToolCommitOutcome.READ_COMPLETED
                            ToolExecutionOutcome.WRITE_COMPLETED ->
                                TurnToolCommitOutcome.WRITE_COMPLETED
                            ToolExecutionOutcome.WRITE_REFUSED ->
                                TurnToolCommitOutcome.WRITE_REFUSED
                        },
                    )
                    if (!committed) {
                        failedToolReceiptOrdinals += event.ordinal
                        _chatHistory.update { state ->
                            state.copy(
                                error = "도구 영수증과 안전 복구 상태를 함께 저장하지 못했습니다.",
                            )
                        }
                        return@commit false
                    }
                    failedToolReceiptOrdinals -= event.ordinal
                    recordToolAndAdvanceAssistant(
                        turnId = turnId,
                        toolName = event.toolName,
                        outcome = event.outcome,
                        currentAssistantEntryId = assistantEntryId,
                        nextAssistantEntryId = nextAssistantEntryId,
                    )
                    assistantPhase = nextAssistantPhase
                    assistantEntryId = nextAssistantEntryId
                    processedToolExecutions[event.ordinal] = execution

                    // These refreshes are derived UI work. A transient refresh failure must not
                    // turn an already durable Tool outcome into a replay that duplicates its
                    // receipt or falsely reports Tool persistence failure.
                    if (event.toolName == MemoryRememberTool.NAME) {
                        runCatching { memoryCoordinator.refresh() }
                    }
                    if (event.toolName == CommitmentProposalTool.NAME) {
                        runCatching { reminderCoordinator.refresh() }
                    }
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
                val latestThermalObservation = thermalMonitor.refresh()
                if (latestThermalObservation.stopSequence > thermalTurn.baselineStopSequence) {
                    applyThermalTurnDirective(thermalTurn, latestThermalObservation)
                }
                if (thermalTurn.cancellationCause.current() != null ||
                    thermalTurn.latch.currentDecision() != null
                ) {
                    throw CancellationException("Thermal policy stopped the turn before registration.")
                }
                controller.runTurn(
                    turnId,
                    requestText,
                    PredictiveThermalPolicy.applyOutputBudget(
                        limits = PredictiveThermalPolicy.applyOutputBudget(
                            limits = controller.limitsForPrompt(
                                unfinishedReadRequest?.text ?: prompt,
                                inheritLongFormRequest =
                                    contextualWebSearchRequest?.inheritLongFormRequest == true,
                            ),
                            observation = startThermalObservation,
                        ),
                        observation = latestThermalObservation,
                    ),
                    reminderDateTimeHint = ReminderDateTimeHint.fromCurrentUserPrompt(
                        unfinishedReadRequest?.text ?: prompt,
                    ),
                    currentUserRequest = unfinishedReadRequest?.text ?: prompt,
                    contextualWebSearchRequest = contextualWebSearchRequest?.trustedRequest,
                    recentWeatherRead = recentWeatherRead,
                    readOnlyToolsOnly = readOnlyRecovery || unfinishedReadRequest != null ||
                        contextualWebSearchRequest != null,
                    requireReadTool = readOnlyRecovery || unfinishedReadRequest != null ||
                        contextualWebSearchRequest != null,
                    executionContract = predecessorRecovery?.let { recovery ->
                        TurnExecutionContract.exactReads(recovery.expectedReadTools)
                    },
                ).collect { event ->
                    when (event) {
                        is AgentEvent.ThoughtDelta -> {
                            if (event.text.isNotEmpty()) {
                                if (!firstTokenRecorded) {
                                    diagnostics.recordSafely(
                                        DiagnosticEvent.TurnFirstToken(
                                            ttftMillis = diagnosticDuration(startedAt),
                                        ),
                                    )
                                    firstTokenRecorded = true
                                }
                                appendActiveReasoning(
                                    turnId = turnId,
                                    assistantEntryId = assistantEntryId,
                                    delta = event.text,
                                )
                            }
                        }
                        is AgentEvent.TextDelta -> {
                            if (event.text.isNotEmpty()) {
                                clearActiveReasoning(turnId, assistantEntryId)
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
                        is AgentEvent.TrustedAnswer -> {
                            clearActiveReasoning(turnId, assistantEntryId)
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
                            appendToMessage(assistantEntryId, event.text)
                        }
                        is AgentEvent.ToolExecuted -> {
                            if (!processToolExecution(event)) {
                                throw ToolReceiptPersistenceException()
                            }
                        }
                        is AgentEvent.Completed -> {
                            check(currentMessageText(assistantEntryId).isNotBlank()) {
                                "A completed turn must contain a substantive assistant answer."
                            }
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
                            turnCompleted = true
                        }
                        is AgentEvent.Failure -> {
                            clearActiveReasoning(turnId, assistantEntryId)
                            reconcileRetainedToolExecutions()
                            turnFailureCode = TurnRecoveryPolicy.failureCode(event)
                            if (!terminalRecorded) {
                                diagnostics.recordSafely(
                                    DiagnosticEvent.TurnFailed(
                                        durationMillis = diagnosticDuration(startedAt),
                                        deltaCount = deltaCount,
                                        deltaByteCount = deltaByteCount,
                                        errorCode = event.toolFailure?.failureCode?.toDiagnosticErrorCode()
                                            ?: event.runtimeCode?.toDiagnosticErrorCode()
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
                                    knownToolExecution = processedToolExecutions.isNotEmpty(),
                                    toolFailure = event.toolFailure,
                                    runtimeCode = event.runtimeCode,
                                    webSearchExpected = contextualWebSearchRequest != null ||
                                        WEB_SEARCH_REQUEST_MARKERS.any(prompt::contains),
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
                    turnFailureCode = TurnOutcomeFailureCode.INVALID_MODEL_SEQUENCE
                }
            } catch (_: CancellationException) {
                val knownToolExecution = withContext(NonCancellable) {
                    reconcileRetainedToolExecutions()
                    controller.retainedToolExecutions(turnId).isNotEmpty()
                }
                if (!terminalRecorded) {
                    val cause = thermalTurn.cancellationCause.current()
                        ?: DiagnosticTurnCancellationCause.LIFECYCLE
                    turnCancelled = true
                    turnFailureCode = TurnRecoveryPolicy.cancellationCode(cause)
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
                    if (failedToolReceiptOrdinals.isNotEmpty()) {
                        "Tool 결과가 발생했지만 영수증을 안전하게 저장하지 못했습니다. " +
                            "외부 상태를 확인하고 자동으로 재시도하지 마세요."
                    } else if (knownToolExecution) {
                        "Tool 결과는 위 영수증대로 확정됐지만 후속 모델 답변은 취소됐습니다. " +
                            "자동으로 재시도하지 않습니다."
                    } else {
                        cancellationStatusText(thermalTurn)
                    },
                )
            } catch (failure: Exception) {
                val knownToolExecution = withContext(NonCancellable) {
                    reconcileRetainedToolExecutions()
                    controller.retainedToolExecutions(turnId).isNotEmpty()
                }
                if (!terminalRecorded) {
                    turnFailureCode = TurnOutcomeFailureCode.UNKNOWN
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
                    if (failedToolReceiptOrdinals.isNotEmpty()) {
                        "Tool 결과가 발생했지만 영수증을 안전하게 저장하지 못했습니다. " +
                            "외부 상태를 확인하고 자동으로 재시도하지 마세요."
                    } else if (knownToolExecution) {
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
                    val finalized = history.finalizeTurn(
                        conversationId = conversationId,
                        turnId = turnId.value,
                        assistantText = currentMessageText(assistantEntryId),
                        completed = turnCompleted,
                        cancelled = turnCancelled,
                        failureCode = turnFailureCode ?: TurnOutcomeFailureCode.UNKNOWN,
                    )
                    if (!finalized) {
                        _chatHistory.update { state ->
                            state.copy(
                                error = "최종 답변과 안전 복구 상태를 함께 저장하지 못했습니다.",
                            )
                        }
                    }
                    if (recoveryCapsuleStarted && !turnCompleted) {
                        history.recoveryEntry(conversationId)?.let(::replaceRecoveryEntry)
                    }
                }
                activeThermalTurn.compareAndSet(thermalTurn, null)
                _uiState.update { state ->
                    if (state.activeTurnId == turnId) {
                        state.copy(activeTurnId = null, activeReasoning = null)
                    } else {
                        state
                    }
                }
                finishDiagnosticPhase()
            }
            // After the turn is released, never during it: the controller runs one turn at a time.
            summarizeInBackground(conversationId)
        }.also(thermalTurn.job::set)
        return true
        } finally {
            if (acquiredMutationLease) mutationLease.close()
        }
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

    private fun appendActiveReasoning(
        turnId: TurnId,
        assistantEntryId: String,
        delta: String,
    ) {
        if (delta.isEmpty()) return
        _uiState.update { state ->
            val updated = ActiveReasoningUiPolicy.append(
                current = state.activeReasoning,
                activeTurnId = state.activeTurnId,
                turnId = turnId,
                assistantEntryId = assistantEntryId,
                delta = delta,
            )
            if (updated === state.activeReasoning) {
                state
            } else {
                state.copy(activeReasoning = updated)
            }
        }
    }

    private fun clearActiveReasoning(turnId: TurnId, assistantEntryId: String) {
        _uiState.update { state ->
            val updated = ActiveReasoningUiPolicy.clear(
                current = state.activeReasoning,
                turnId = turnId,
                assistantEntryId = assistantEntryId,
            )
            if (updated === state.activeReasoning) {
                state
            } else {
                state.copy(activeReasoning = updated)
            }
        }
    }

    private fun currentMessageText(id: String): String =
        _uiState.value.messages.firstOrNull { entry -> entry.id == id }?.text.orEmpty()

    /** Returns the assistant text being closed off, so the caller can persist it in order. */
    private fun recordToolAndAdvanceAssistant(
        turnId: TurnId,
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
                activeReasoning = ActiveReasoningUiPolicy.advance(
                    current = state.activeReasoning,
                    activeTurnId = state.activeTurnId,
                    turnId = turnId,
                    nextAssistantEntryId = nextAssistantEntryId,
                ),
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

    private fun replaceRecoveryEntry(entry: ChatEntry) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.filterNot { candidate ->
                    candidate.recoveryAction?.turnId == entry.recoveryAction?.turnId
                } + entry,
            )
        }
    }

    private fun actionEntryId(action: ChatRecoveryAction): String =
        "turn-recovery-${action.turnId}"

    private fun failureText(
        code: AgentFailureCode,
        knownToolExecution: Boolean = false,
        toolFailure: ToolFailureDetail? = null,
        runtimeCode: LlmFailureCode? = null,
        webSearchExpected: Boolean = false,
    ): String = if (knownToolExecution) {
        when {
            runtimeCode == LlmFailureCode.CONTEXT_BUDGET_EXCEEDED ->
                "Tool 결과는 위 영수증대로 확정됐지만 후속 답변용 문맥 공간이 부족했습니다. " +
                    "'계속 답해줘'라고 보내면 원래 읽기 요청을 새로 수행합니다."
            code == AgentFailureCode.DEADLINE_EXCEEDED ->
                "Tool 결과는 위 영수증대로 확정됐지만 후속 답변 시간이 초과됐습니다. " +
                    "자동으로 재시도하지 않습니다."
            else -> "Tool 결과는 위 영수증대로 확정됐지만 후속 처리가 중단됐습니다. " +
                "자동으로 재시도하지 않습니다."
        }
    } else if (toolFailure != null) {
        toolFailureText(toolFailure)
    } else when (code) {
        AgentFailureCode.BUSY -> "다른 요청이 진행 중입니다."
        AgentFailureCode.DEADLINE_EXCEEDED ->
            "요청 제한 시간 ${AgentLoopLimits().deadlineMillis / 1_000}초를 초과했습니다."
        AgentFailureCode.UNKNOWN_TOOL -> "등록되지 않은 Tool 호출을 차단했습니다."
        AgentFailureCode.INVALID_TOOL_CALL -> "유효하지 않은 Tool 인자를 차단했습니다."
        AgentFailureCode.TOOL_NOT_EXECUTED -> if (webSearchExpected) {
            "웹 검색 대상을 확정하지 못했거나 검색을 실행하지 못했습니다. " +
                "검색할 대상과 알고 싶은 항목을 구체적으로 적어 다시 요청해 주세요."
        } else {
            "Tool이 실행되지 않았거나 결과를 확정할 수 없습니다. 자동으로 재시도하지 " +
                "않았습니다. 대상 앱의 상타를 확인한 뒤 다시 결정하세요."
        }
        AgentFailureCode.TOOL_FAILED ->
            "Tool 조회가 완료되지 않았습니다. 입력과 외부 서비스 상태를 확인하세요."
        AgentFailureCode.STEP_LIMIT_EXCEEDED,
        AgentFailureCode.TOOL_CALL_LIMIT_EXCEEDED -> "Agent 실행 한도를 초과해 중단했습니다."
        AgentFailureCode.INVALID_TURN,
        AgentFailureCode.TURN_MISMATCH,
        AgentFailureCode.INVALID_MODEL_SEQUENCE,
        AgentFailureCode.MODEL_FAILURE -> "모델 응답을 안전하게 처리할 수 없어 중단했습니다."
    }

    private fun applyThermalObservation(observation: ThermalObservation) {
        if (observation.directive == ThermalDirective.CONTINUE) return
        BackgroundSummaryPriority.cancelForThermalPolicy(summaryJob)
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
        private const val UNRESOLVED_ACTION_WARNING_ID = "unresolved-action-warning"

        /**
         * A media turn's deadline.
         *
         * Longer than a text turn's because the vision and audio encoders run a substantial extra
         * prefill before the first token, and a CPU backend pays that in full.
         */
        private const val MEDIA_TURN_DEADLINE_MILLIS = 180_000L

        /** A 20-second clip cannot legitimately transcribe to more than this. */
        private const val MAX_TRANSCRIPT_CHARACTERS = 4_000
        private val WEB_SEARCH_REQUEST_MARKERS = listOf(
            "웹 검색", "웹검색", "웹에서", "인터넷에서", "검색", "찾아", "알아봐", "조사",
        )
        private const val PRIOR_WEB_RESULT_UNAVAILABLE_MESSAGE =
            "이 대화의 바로 앞 답변에서 완료된 웹 검색 결과를 확인할 수 없습니다. " +
                "검색할 대상을 구체적으로 적어 새로 검색해 주세요."
        private const val PRIOR_WEB_RESULT_BUDGET_MESSAGE =
            "이전 웹 검색 답변을 안전하게 전달할 공간이 부족해 후속 요청을 시작하지 않았습니다. " +
                "요청을 줄이거나 검색할 대상을 적어 새로 요청해 주세요."
        private val TURN_CONTEXT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd(E) HH:mm", Locale.KOREAN)
    }
}

/** Typed, content-free signal; never includes Tool arguments, results, or transcript text. */
private class ToolReceiptPersistenceException : Exception()

/** Exact content-free identity for one trusted controller execution slot. */
private data class ProcessedToolExecution(
    val toolName: String,
    val outcome: ToolExecutionOutcome,
)

private fun validTransferPassphrase(value: String): Boolean =
    value.codePointCount(0, value.length) in
        EncryptedUserDataArchive.MIN_PASSPHRASE_CODE_POINTS..
            EncryptedUserDataArchive.MAX_PASSPHRASE_CODE_POINTS &&
        value.codePoints().noneMatch { Character.isISOControl(it) }

private fun MemoryEntity.recallNotice(zone: ZoneId): String = buildString {
    append("기억 참고 · ")
    append(
        when (category) {
            MemoryCategory.PREFERENCE -> "선호"
            MemoryCategory.PERSON -> "사람"
            MemoryCategory.PLACE -> "장소"
            MemoryCategory.ROUTINE -> "루틴"
            MemoryCategory.FACT -> "사실"
        },
    )
    append(" · ‘")
    append(content.takeCodePoints(MAX_RECALL_NOTICE_CONTENT_CHARACTERS))
    append("’ · 요청과 관련 표현이 겹친 사용자 승인 기억")
    append(" · 마지막 확인 ")
    append(Instant.ofEpochMilli(lastConfirmedAtEpochMillis).atZone(zone).toLocalDate())
    validUntilEpochMillis?.let { expiry ->
        append(" · ")
        append(Instant.ofEpochMilli(expiry - 1L).atZone(zone).toLocalDate())
        append("까지")
    }
}

private const val MAX_RECALL_NOTICE_CONTENT_CHARACTERS = 80

private fun UserDataArchiveFailure.userMessage(): String = when (this) {
    UserDataArchiveFailure.INVALID_PASSPHRASE ->
        "암호 문구는 제어문자 없이 12자 이상 128자 이하로 입력하세요."
    UserDataArchiveFailure.TOO_LARGE -> "백업 파일이 비어 있거나 허용 크기를 초과했습니다."
    UserDataArchiveFailure.INVALID_FORMAT -> "Personal Edge 백업 파일 형식이 아닙니다."
    UserDataArchiveFailure.UNSUPPORTED_VERSION -> "지원하지 않는 백업 포맷 버전입니다."
    UserDataArchiveFailure.SCHEMA_MISMATCH -> "현재 앱과 백업의 데이터 스키마 버전이 다릅니다."
    UserDataArchiveFailure.AUTHENTICATION_FAILED -> "암호 문구가 다르거나 파일이 변조되었습니다."
    UserDataArchiveFailure.HASH_MISMATCH -> "복호화된 데이터의 SHA-256 무결성이 일치하지 않습니다."
    UserDataArchiveFailure.INVALID_PAYLOAD -> "백업 내부 데이터가 안전한 구조 검사를 통과하지 못했습니다."
}

private fun UserDataImportResult.summaryMessage(): String = buildString {
    append("가져오기 완료: 대화 ").append(importedConversations)
    append("개, 메시지 ").append(importedMessages)
    append("개, 기억 ").append(importedMemories)
    append("개, 리마인더 ").append(importedReminders)
    append("개, 후보 ").append(importedProposals).append("개")
    if (skippedRows > 0) append(" · 기존/충돌 ").append(skippedRows).append("개 건너뜀")
    if (calendarRemapRequired) append(" · 캘린더는 이 기기에서 다시 선택 필요")
}
