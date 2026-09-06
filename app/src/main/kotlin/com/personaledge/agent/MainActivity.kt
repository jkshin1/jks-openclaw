package com.personaledge.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.personaledge.agent.ui.PersonalEdgeScreen
import com.personaledge.agent.ui.OpenClawRemoteActions
import com.personaledge.agent.ui.WorkspaceFoldingSnapshot
import com.personaledge.agent.ui.WorkspaceHardwareKey
import com.personaledge.agent.ui.WorkspaceHingeOrientation
import com.personaledge.agent.ui.WorkspaceHingePosture
import com.personaledge.agent.ui.WorkspaceKeyStroke
import com.personaledge.agent.ui.WorkspaceShortcutCommand
import com.personaledge.agent.ui.WorkspaceShortcutPolicy
import com.personaledge.agent.ui.WorkspaceWindowState
import com.personaledge.agent.ui.WorkspaceWindowStateMapper
import com.personaledge.agent.ui.theme.PersonalEdgeAgentTheme
import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.data.UserDataSelection
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Wiring only.
 *
 * Everything drawn lives under `ui/`; this class owns the Android surface — the activity result
 * contracts, the runtime permission request, and the lifecycle refresh — and hands the screen a
 * flat set of callbacks. Keeping the composables out of here is what made it possible to render
 * every screen state in the debug UI gallery without a loaded model.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: PersonalEdgeViewModel by viewModels()
    private val reminderOpenRequest = MutableStateFlow<String?>(null)
    private val workspaceWindowState = MutableStateFlow(WorkspaceWindowState())
    private val workspaceShortcutCommands = MutableSharedFlow<WorkspaceShortcutCommand>(
        extraBufferCapacity = 8,
    )
    private var latestWindowLayoutInfo: WindowLayoutInfo? = null
    private var pendingUserDataExport: PendingUserDataExport? = null
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
    private val createUserDataDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val request = pendingUserDataExport
        pendingUserDataExport = null
        if (uri != null && request != null) {
            viewModel.exportUserData(uri, request.selection, request.passphrase)
        }
    }
    private val openUserDataDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.selectUserDataImport(uri)
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
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.onReminderNotificationPermissionResult()
    }

    /**
     * The system photo picker.
     *
     * `PickVisualMedia` needs no storage permission at all: the picker runs out of process and
     * hands back a grant for the one item the owner chose, so the app never gains the ability to
     * read the gallery.
     */
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        viewModel.onImageSelected(uri)
    }

    /**
     * The camera app.
     *
     * `TakePicture` only requires the CAMERA permission when the app declares it in the manifest.
     * This one deliberately does not, so photographing something never triggers a permission
     * prompt and the app never holds camera access of its own.
     */
    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        viewModel.onCameraCaptured(success)
    }

    /** Dictation asks on first use and resumes into the editable-text destination. */
    private val requestDictationMicrophonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startVoiceRecording(dictation = true)
        } else {
            viewModel.onMicrophonePermissionDenied()
        }
    }

    /** Audio attachment uses the same permission but keeps the recording as model input. */
    private val requestAttachmentMicrophonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startVoiceRecording(dictation = false)
        } else {
            viewModel.onMicrophonePermissionDenied()
        }
    }

    /**
     * Receives one shared photo from another app's share sheet.
     *
     * The intent is consumed once. Without that, a rotation or any other configuration change
     * would re-deliver the same share and re-stage the photo behind the owner's back.
     */
    private fun handleSharedContent(intent: Intent?) {
        if (intent == null || intent.getBooleanExtra(EXTRA_SHARE_CONSUMED, false)) return
        val stream: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val action = intent.action
        val mimeType = intent.type
        if (SharedImageIntentPolicy.acceptsSharedImage(action, mimeType, stream != null)) {
            intent.putExtra(EXTRA_SHARE_CONSUMED, true)
            viewModel.onSharedImageReceived(stream)
            return
        }
        SharedImageIntentPolicy.refusalMessageOrNull(action, mimeType, stream != null)
            ?.let { message ->
                intent.putExtra(EXTRA_SHARE_CONSUMED, true)
                viewModel.onSharedContentRefused(message)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The transcript scrolls under the status bar and the composer sits on the gesture bar, so
        // the window draws edge to edge and the insets are consumed by the screen itself.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        reminderOpenRequest.value = reminderIdFromIntent(intent)
        refreshWorkspaceWindowState()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .collect { layoutInfo ->
                        latestWindowLayoutInfo = layoutInfo
                        refreshWorkspaceWindowState()
                    }
            }
        }
        handleSharedContent(intent)
        setContent {
            PersonalEdgeAgentTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val pending by viewModel.confirmationCoordinator.pending.collectAsStateWithLifecycle()
                val calendarSetup by viewModel.calendarSetup.collectAsStateWithLifecycle()
                val chatHistory by viewModel.chatHistory.collectAsStateWithLifecycle()
                val notificationSetup by viewModel.notificationSetup.collectAsStateWithLifecycle()
                val credentials by viewModel.credentials.collectAsStateWithLifecycle()
                val networkSetup by viewModel.networkSetup.collectAsStateWithLifecycle()
                val memorySetup by viewModel.memorySetup.collectAsStateWithLifecycle()
                val reminderSetup by viewModel.reminderSetup.collectAsStateWithLifecycle()
                val diagnosticExport by viewModel.diagnosticExport.collectAsStateWithLifecycle()
                val userDataTransfer by viewModel.userDataTransfer.collectAsStateWithLifecycle()
                val openReminderSourceId by reminderOpenRequest.collectAsStateWithLifecycle()
                val windowState by workspaceWindowState.collectAsStateWithLifecycle()
                val attachmentPreview by viewModel.attachmentPreview.collectAsStateWithLifecycle()
                val remoteState by viewModel.remoteState.collectAsStateWithLifecycle()

                // Calendar access and synced accounts can change while the app is backgrounded.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    refreshSetupState()
                }

                PersonalEdgeScreen(
                    state = state,
                    remoteState = remoteState,
                    remoteActions = OpenClawRemoteActions(
                        select = viewModel::selectRemote,
                        configure = viewModel::configureRemote,
                        connect = viewModel::connectRemote,
                        disconnect = viewModel::disconnectRemote,
                        forget = viewModel::forgetRemote,
                        updatePrompt = viewModel::updateRemotePrompt,
                        send = viewModel::sendRemotePrompt,
                        cancel = viewModel::cancelRemoteTurn,
                        loadContext = viewModel::loadRemoteContext,
                        selectContext = viewModel::selectRemoteContext,
                        finishContextSelection = viewModel::finishRemoteContextSelection,
                        clearContext = viewModel::clearRemoteContext,
                        readHealth = viewModel::readRemoteMacHealth,
                    ),
                    calendarSetup = calendarSetup,
                    chatHistory = chatHistory,
                    notificationSetup = notificationSetup,
                    credentials = credentials,
                    networkSetup = networkSetup,
                    memorySetup = memorySetup,
                    reminderSetup = reminderSetup,
                    diagnosticExport = diagnosticExport,
                    userDataTransfer = userDataTransfer,
                    pendingConfirmation = pending,
                    workspaceWindowState = windowState,
                    shortcutCommands = workspaceShortcutCommands,
                    openReminderSourceId = openReminderSourceId,
                    onReminderSourceConsumed = { reminderOpenRequest.value = null },
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
                    onSetCalendarReadEnabled = viewModel::setCalendarReadEnabled,
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
                    onSetKakaoNotificationReply = viewModel::setKakaoNotificationReplyEnabled,
                    onDeleteCapturedNotifications = viewModel::deleteCapturedNotifications,
                    onStoreCredential = viewModel::storeCredential,
                    onDeleteCredential = viewModel::deleteCredential,
                    onSetRouteLookupEnabled = viewModel::setRouteLookupEnabled,
                    onSetWebSearchEnabled = viewModel::setWebSearchEnabled,
                    onStoreDefaultOrigin = viewModel::storeDefaultOrigin,
                    onDeleteDefaultOrigin = viewModel::deleteDefaultOrigin,
                    onSetMediaInputEnabled = viewModel::setMediaInputEnabled,
                    onSetMemoryEnabled = viewModel::setMemoryEnabled,
                    onStoreMemory = viewModel::storeMemory,
                    onReplaceMemory = viewModel::replaceMemory,
                    onReconfirmMemory = viewModel::reconfirmMemory,
                    onDeleteMemory = viewModel::deleteMemory,
                    onDeleteAllMemories = viewModel::deleteAllMemories,
                    onRequestReminderNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.onReminderNotificationPermissionResult()
                        }
                    },
                    onOpenExactAlarmSettings = {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = "package:$packageName".toUri()
                            },
                        )
                    },
                    onCreateReminder = viewModel::createReminder,
                    onCompleteReminder = viewModel::completeReminder,
                    onSnoozeReminder = viewModel::snoozeReminder,
                    onCancelReminder = viewModel::cancelReminder,
                    onSetDailyBriefEnabled = viewModel::setDailyBriefEnabled,
                    onSetDailyBriefTime = viewModel::setDailyBriefTime,
                    onSetProactiveRoutePlanningEnabled =
                        viewModel::setProactiveRoutePlanningEnabled,
                    onSetQuietHoursEnabled = viewModel::setQuietHoursEnabled,
                    onSetWeekendBriefEnabled = viewModel::setWeekendBriefEnabled,
                    onSetCommitmentProposalsEnabled = viewModel::setCommitmentProposalsEnabled,
                    onCaptureCommitmentInbox = viewModel::captureCommitmentInbox,
                    onPromoteCommitmentProposal = viewModel::promoteCommitmentProposal,
                    onDismissCommitmentProposal = viewModel::dismissCommitmentProposal,
                    onExportDiagnostics = {
                        createDiagnosticDocument.launch("personal-edge-diagnostics.jsonl")
                    },
                    onPrepareUserDataExport = viewModel::prepareUserDataExport,
                    onExportUserData = { selection, passphrase ->
                        pendingUserDataExport = PendingUserDataExport(selection, passphrase)
                        createUserDataDocument.launch("personal-edge-backup.pedge")
                    },
                    onSelectUserDataImport = {
                        openUserDataDocument.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onPreviewUserDataImport = viewModel::previewUserDataImport,
                    onImportUserData = viewModel::importUserData,
                    onPromptChange = viewModel::updatePrompt,
                    onImportModel = {
                        openModelDocument.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onInspectModel = viewModel::inspectInstalledModel,
                    onInitializeCpu = { viewModel.initializeRuntime(InferenceBackend.CPU) },
                    onInitializeGpu = { viewModel.initializeRuntime(InferenceBackend.GPU) },
                    // Opening settings re-reads the same state the resume path does, so a grant
                    // changed in system settings is reflected without leaving the app again.
                    onRefreshSetup = ::refreshSetupState,
                    onSend = viewModel::sendPrompt,
                    onCancel = viewModel::cancelTurn,
                    onTakePhoto = {
                        // The staging URI is created only after the ViewModel accepts the request,
                        // so a refused capture never leaves a file behind.
                        viewModel.requestCameraCapture { uri -> if (uri != null) takePicture.launch(uri) }
                    },
                    onPickImage = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onStartDictation = {
                        startVoiceRecordingOrRequestPermission(
                            dictation = true,
                            launcher = requestDictationMicrophonePermission,
                        )
                    },
                    onStartVoiceAttachment = {
                        startVoiceRecordingOrRequestPermission(
                            dictation = false,
                            launcher = requestAttachmentMicrophonePermission,
                        )
                    },
                    onStopRecording = viewModel::stopVoiceRecording,
                    onCancelRecording = viewModel::cancelVoiceRecording,
                    onRemoveAttachment = viewModel::removeAttachment,
                    onDismissMediaNotice = viewModel::dismissMediaNotice,
                    attachmentPreview = attachmentPreview,
                    onTurnRecovery = viewModel::resolveTurnRecovery,
                    onConfirmation = viewModel::resolveConfirmation,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setRemoteForeground(true)
    }

    override fun onStop() {
        viewModel.setRemoteForeground(false)
        super.onStop()
    }

    private fun startVoiceRecordingOrRequestPermission(
        dictation: Boolean,
        launcher: ActivityResultLauncher<String>,
    ) {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startVoiceRecording(dictation = dictation)
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_K -> WorkspaceHardwareKey.K
            KeyEvent.KEYCODE_N -> WorkspaceHardwareKey.N
            KeyEvent.KEYCODE_ESCAPE -> WorkspaceHardwareKey.ESCAPE
            else -> WorkspaceHardwareKey.OTHER
        }
        val command = WorkspaceShortcutPolicy.commandFor(
            WorkspaceKeyStroke(
                key = key,
                ctrlPressed = event.isCtrlPressed,
                metaPressed = event.isMetaPressed,
                isKeyDown = true,
                isRepeat = event.repeatCount > 0,
            ),
        )
        if (command != null && workspaceShortcutCommands.tryEmit(command)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onMultiWindowModeChanged(
        isInMultiWindowMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        refreshWorkspaceWindowState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshWorkspaceWindowState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reminderOpenRequest.value = reminderIdFromIntent(intent)
        // singleTop means a share into the already-running app arrives here, not in onCreate.
        handleSharedContent(intent)
    }

    private fun reminderIdFromIntent(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "personaledge" || data.host != "reminder") return null
        val segments = data.pathSegments
        return segments.takeIf { it.size == 2 && it[0] == "open" }
            ?.get(1)
            ?.takeIf(String::isNotBlank)
    }

    private fun refreshSetupState() {
        (application as? PersonalEdgeApplication)?.ensureCalendarReconciliationObserver()
        viewModel.refreshCalendarSetup()
        viewModel.refreshNotificationSetup()
        viewModel.refreshCredentials()
        viewModel.refreshNetworkSetup()
        viewModel.refreshMemorySetup()
        viewModel.refreshReminders()
    }

    private fun refreshWorkspaceWindowState() {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this)
        val bounds = metrics.bounds
        val foldingFeature = latestWindowLayoutInfo
            ?.displayFeatures
            ?.filterIsInstance<FoldingFeature>()
            ?.firstOrNull(FoldingFeature::isSeparating)
        val foldingSnapshot = foldingFeature?.let { feature ->
            val featureBounds = feature.bounds
            val orientation = when (feature.orientation) {
                FoldingFeature.Orientation.VERTICAL -> WorkspaceHingeOrientation.VERTICAL
                FoldingFeature.Orientation.HORIZONTAL -> WorkspaceHingeOrientation.HORIZONTAL
                else -> return@let null
            }
            WorkspaceFoldingSnapshot(
                isSeparating = feature.isSeparating,
                orientation = orientation,
                startPx = when (orientation) {
                    WorkspaceHingeOrientation.VERTICAL -> featureBounds.left
                    WorkspaceHingeOrientation.HORIZONTAL -> featureBounds.top
                },
                endPx = when (orientation) {
                    WorkspaceHingeOrientation.VERTICAL -> featureBounds.right
                    WorkspaceHingeOrientation.HORIZONTAL -> featureBounds.bottom
                },
                posture = when (feature.state) {
                    FoldingFeature.State.FLAT -> WorkspaceHingePosture.FLAT
                    FoldingFeature.State.HALF_OPENED -> WorkspaceHingePosture.HALF_OPENED
                    else -> return@let null
                },
            )
        }
        workspaceWindowState.value = WorkspaceWindowStateMapper.fromPixels(
            widthPx = bounds.width(),
            heightPx = bounds.height(),
            density = resources.displayMetrics.density,
            isInMultiWindowMode = isInMultiWindowMode,
            folding = foldingSnapshot,
        )
    }

    private data class PendingUserDataExport(
        val selection: UserDataSelection,
        val passphrase: String,
    )

    private companion object {
        /** Marks a share this Activity already staged, so a rotation cannot re-deliver it. */
        const val EXTRA_SHARE_CONSUMED = "com.personaledge.agent.SHARE_CONSUMED"
    }
}
