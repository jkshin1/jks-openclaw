package com.personaledge.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.personaledge.agent.CalendarOption
import com.personaledge.agent.CalendarSetupState
import com.personaledge.agent.ChatHistoryState
import com.personaledge.agent.ChatRecoveryAction
import com.personaledge.agent.ChatRole
import com.personaledge.agent.CredentialSlot
import com.personaledge.agent.CredentialsState
import com.personaledge.agent.DiagnosticExportState
import com.personaledge.agent.NetworkSetupState
import com.personaledge.agent.MemorySetupState
import com.personaledge.agent.NotificationSetupState
import com.personaledge.agent.ReminderSetupState
import com.personaledge.agent.PendingConfirmation
import androidx.compose.ui.graphics.ImageBitmap
import com.personaledge.agent.ModelUiStatus
import com.personaledge.agent.OpenClawRemoteUiState
import com.personaledge.agent.PersonalEdgeUiState
import com.personaledge.agent.R
import com.personaledge.agent.ui.components.StatusPill
import com.personaledge.agent.ui.components.onToneContainerColor
import com.personaledge.agent.ui.components.toneContainerColor
import com.personaledge.agent.ui.theme.CapsuleShape
import com.personaledge.agent.ui.theme.PersonalEdgeMotion
import com.personaledge.core.data.MemoryCategory
import com.personaledge.core.data.UserDataSelection
import com.personaledge.agent.UserDataTransferState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The single screen.
 *
 * Setup used to occupy the top half of the window and the transcript fought it for space. Here the
 * conversation owns the window, one banner carries the next setup step when there is one, and
 * everything else moved into sheets that are opened deliberately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalEdgeScreen(
    state: PersonalEdgeUiState,
    remoteState: OpenClawRemoteUiState = OpenClawRemoteUiState(),
    remoteActions: OpenClawRemoteActions = OpenClawRemoteActions(),
    calendarSetup: CalendarSetupState,
    chatHistory: ChatHistoryState,
    notificationSetup: NotificationSetupState,
    credentials: CredentialsState,
    networkSetup: NetworkSetupState,
    memorySetup: MemorySetupState = MemorySetupState(),
    reminderSetup: ReminderSetupState = ReminderSetupState(),
    diagnosticExport: DiagnosticExportState,
    userDataTransfer: UserDataTransferState = UserDataTransferState(),
    pendingConfirmation: PendingConfirmation?,
    workspaceWindowState: WorkspaceWindowState = WorkspaceWindowState(),
    shortcutCommands: Flow<WorkspaceShortcutCommand> = emptyFlow(),
    openReminderSourceId: String? = null,
    onReminderSourceConsumed: () -> Unit = {},
    onRequestCalendarPermission: () -> Unit,
    onPinCalendar: (CalendarOption) -> Unit,
    onUnpinCalendar: () -> Unit,
    onSetCalendarReadEnabled: (Long, Boolean) -> Unit = { _, _ -> },
    onOpenHistory: () -> Unit,
    onCloseHistory: () -> Unit,
    onNewConversation: () -> Unit,
    onSwitchConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onDeleteAllConversations: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onSetNotificationCapture: (Boolean) -> Unit,
    onSetKakaoNotificationReply: (Boolean) -> Unit,
    onDeleteCapturedNotifications: () -> Unit,
    onStoreCredential: (CredentialSlot, String) -> Unit,
    onDeleteCredential: (CredentialSlot) -> Unit,
    onSetRouteLookupEnabled: (Boolean) -> Unit,
    onSetWebSearchEnabled: (Boolean) -> Unit,
    onStoreDefaultOrigin: (String) -> Unit,
    onDeleteDefaultOrigin: () -> Unit,
    onSetMemoryEnabled: (Boolean) -> Unit = {},
    onStoreMemory: (String, MemoryCategory, String?) -> Unit = { _, _, _ -> },
    onReplaceMemory: (String, String, MemoryCategory, String?) -> Unit = { _, _, _, _ -> },
    onReconfirmMemory: (String) -> Unit = {},
    onDeleteMemory: (String) -> Unit = {},
    onDeleteAllMemories: () -> Unit = {},
    onRequestReminderNotificationPermission: () -> Unit = {},
    onOpenExactAlarmSettings: () -> Unit = {},
    onCreateReminder: (String, String, Boolean, String?, Boolean) -> Unit = { _, _, _, _, _ -> },
    onCompleteReminder: (String, Long) -> Unit = { _, _ -> },
    onSnoozeReminder: (String, Long, Int) -> Unit = { _, _, _ -> },
    onCancelReminder: (String, Long) -> Unit = { _, _ -> },
    onSetDailyBriefEnabled: (Boolean) -> Unit = {},
    onSetDailyBriefTime: (String) -> Unit = {},
    onSetProactiveRoutePlanningEnabled: (Boolean) -> Unit = {},
    onSetQuietHoursEnabled: (Boolean) -> Unit = {},
    onSetWeekendBriefEnabled: (Boolean) -> Unit = {},
    onSetCommitmentProposalsEnabled: (Boolean) -> Unit = {},
    onCaptureCommitmentInbox: (String) -> Unit = {},
    onPromoteCommitmentProposal: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onDismissCommitmentProposal: (String) -> Unit = {},
    onExportDiagnostics: () -> Unit,
    onPrepareUserDataExport: (UserDataSelection) -> Unit = {},
    onExportUserData: (UserDataSelection, String) -> Unit = { _, _ -> },
    onSelectUserDataImport: () -> Unit = {},
    onPreviewUserDataImport: (String) -> Unit = {},
    onImportUserData: (String) -> Unit = {},
    onPromptChange: (String) -> Unit,
    onImportModel: () -> Unit,
    onInspectModel: () -> Unit,
    onInitializeCpu: () -> Unit,
    onInitializeGpu: () -> Unit,
    onRefreshSetup: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onSetMediaInputEnabled: (Boolean) -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onPickImage: () -> Unit = {},
    onStartDictation: () -> Unit = {},
    onStartVoiceAttachment: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    onRemoveAttachment: () -> Unit = {},
    onDismissMediaNotice: () -> Unit = {},
    attachmentPreview: ImageBitmap? = null,
    onTurnRecovery: (ChatRecoveryAction) -> Unit = {},
    onConfirmation: (String, Boolean) -> Unit,
) {
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var remindersVisible by rememberSaveable { mutableStateOf(false) }
    var sourceReminderId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusComposerRequest by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val composerFocusRequester = remember { FocusRequester() }
    val previewShortcutCommands = remember {
        MutableSharedFlow<WorkspaceShortcutCommand>(extraBufferCapacity = 8)
    }
    val turnActive = state.activeTurnId != null || remoteState.running
    val composerMedia = ComposerMediaState(
        visible = state.mediaInputEnabled,
        // Shown but refusing is deliberate: a control that disappears mid-turn moves the other
        // controls under the owner's thumb. Disabled keeps the row's geometry stable.
        enabled = state.mediaInputEnabled && !turnActive && !state.transcribing &&
            state.voiceRecording == null && state.pendingAttachment == null &&
            state.modelStatus == ModelUiStatus.READY,
        attachment = state.pendingAttachment,
        preview = attachmentPreview,
        recording = state.voiceRecording,
        onTakePhoto = onTakePhoto,
        onPickImage = onPickImage,
        onStartDictation = onStartDictation,
        onStartVoiceAttachment = onStartVoiceAttachment,
        onStopRecording = onStopRecording,
        onCancelRecording = onCancelRecording,
        onRemoveAttachment = onRemoveAttachment,
    )

    androidx.compose.runtime.LaunchedEffect(openReminderSourceId) {
        if (openReminderSourceId != null) {
            sourceReminderId = openReminderSourceId
            remindersVisible = true
            onReminderSourceConsumed()
        }
    }

    // Two visible items of slack: appending a message must not count as "the user scrolled away",
    // or the transcript would stop following the answer as soon as it started streaming.
    val nearBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            last == null || last.index >= layout.totalItemsCount - 2
        }
    }

    val latestMessage = state.messages.lastOrNull()
    val latestAssistant = state.messages.lastOrNull { entry ->
        entry.role == ChatRole.ASSISTANT
    }
    val streamingText = ChatPresentationPolicy.streamingTextForAutoScroll(
        assistantEntryId = latestAssistant?.id,
        assistantText = latestAssistant?.text.orEmpty(),
        reasoningAssistantEntryId = state.activeReasoning?.assistantEntryId,
        reasoningText = state.activeReasoning?.text,
    )
    val streamScrollRevision = ChatPresentationPolicy.autoScrollRevision(
        streamingText,
    )
    androidx.compose.runtime.LaunchedEffect(
        latestMessage?.id,
        latestAssistant?.id,
        streamScrollRevision,
        turnActive,
    ) {
        if (state.messages.isNotEmpty() && nearBottom) {
            // Streaming deltas are bucketed by code-point growth. Keep the answer pinned without
            // starting and cancelling a new scroll animation for every token.
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    // The keyboard takes about a third of the window, and it arrives over an animation rather
    // than in one step. Keying on the inset value re-pins the end of the transcript on every
    // frame of that animation; reacting only to "the keyboard opened" scrolls once, at the start,
    // and the rest of the animation pushes the newest message back under the composer.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    androidx.compose.runtime.LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && state.messages.isNotEmpty() && nearBottom) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    val openSettings: () -> Unit = {
        onRefreshSetup()
        settingsVisible = true
    }

    val openOverlay = when {
        pendingConfirmation != null -> WorkspaceOverlay.CONFIRMATION
        chatHistory.visible -> WorkspaceOverlay.HISTORY
        remindersVisible -> WorkspaceOverlay.REMINDERS
        settingsVisible -> WorkspaceOverlay.SETTINGS
        else -> null
    }
    androidx.compose.runtime.LaunchedEffect(
        shortcutCommands,
        previewShortcutCommands,
        turnActive,
        remoteState.selected,
        openOverlay,
        pendingConfirmation?.actionId,
    ) {
        merge(shortcutCommands, previewShortcutCommands).collect { command ->
            when (WorkspaceShortcutPolicy.resolve(command, turnActive, openOverlay)) {
                WorkspaceShortcutAction.FOCUS_COMPOSER -> if (!remoteState.selected) {
                    settingsVisible = false
                    remindersVisible = false
                    if (chatHistory.visible) onCloseHistory()
                    focusComposerRequest++
                }
                WorkspaceShortcutAction.START_NEW_CONVERSATION -> if (!remoteState.selected) {
                    settingsVisible = false
                    remindersVisible = false
                    if (chatHistory.visible) onCloseHistory()
                    onNewConversation()
                }
                WorkspaceShortcutAction.REJECT_NEW_CONVERSATION -> Unit
                WorkspaceShortcutAction.CANCEL_ACTIVE_TURN -> if (remoteState.selected) remoteActions.cancel() else onCancel()
                WorkspaceShortcutAction.CLOSE_OVERLAY -> when (openOverlay) {
                    WorkspaceOverlay.SETTINGS -> settingsVisible = false
                    WorkspaceOverlay.REMINDERS -> remindersVisible = false
                    WorkspaceOverlay.HISTORY -> onCloseHistory()
                    WorkspaceOverlay.CONFIRMATION -> pendingConfirmation?.let { pending ->
                        onConfirmation(pending.actionId, false)
                    }
                    null -> Unit
                }
                WorkspaceShortcutAction.NONE -> Unit
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(
        focusComposerRequest,
        settingsVisible,
        remindersVisible,
        chatHistory.visible,
        remoteState.selected,
    ) {
        if (focusComposerRequest > 0 &&
            !settingsVisible &&
            !remindersVisible &&
            !chatHistory.visible &&
            !remoteState.selected
        ) {
            composerFocusRequester.requestFocus()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val key = when (event.key) {
                    Key.K -> WorkspaceHardwareKey.K
                    Key.N -> WorkspaceHardwareKey.N
                    Key.Escape -> WorkspaceHardwareKey.ESCAPE
                    else -> WorkspaceHardwareKey.OTHER
                }
                val command = WorkspaceShortcutPolicy.commandFor(
                    WorkspaceKeyStroke(
                        key = key,
                        ctrlPressed = event.isCtrlPressed,
                        metaPressed = event.isMetaPressed,
                        isKeyDown = event.type == KeyEventType.KeyDown,
                        isRepeat = event.nativeKeyEvent.repeatCount > 0,
                    ),
                )
                command != null && previewShortcutCommands.tryEmit(command)
            },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                if (remoteState.selected) {
                    CenterAlignedTopAppBar(
                        title = { Text("Personal Edge · 원격", style = MaterialTheme.typography.titleMedium) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                } else {
                    WorkspaceTopBar(
                        state = state,
                        turnActive = turnActive,
                        windowPlan = WorkspaceLayoutPolicy.forWindow(workspaceWindowState),
                        onNewConversation = onNewConversation,
                        onOpenReminders = { remindersVisible = true },
                        onOpenHistory = onOpenHistory,
                        onOpenSettings = openSettings,
                    )
                }
                AgentModeSelector(
                    remote = remoteState,
                    localBusy = state.activeTurnId != null || state.voiceRecording != null ||
                        state.transcribing || state.pendingAttachment != null,
                    actions = remoteActions,
                )
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val contentTopOffsetDp = innerPadding.calculateTopPadding().value
            val windowPlan = WorkspaceLayoutPolicy.forWindow(
                workspaceWindowState.copy(
                    widthDp = maxWidth.value,
                    heightDp = maxHeight.value + contentTopOffsetDp,
                    contentTopInsetDp = contentTopOffsetDp,
                ),
            )
            if (remoteState.selected) {
                // The same transcript the local model appends to; the engine is not a boundary.
                OpenClawRemotePane(
                    state = remoteState,
                    actions = remoteActions,
                    messages = state.messages,
                )
            } else when (windowPlan.layout) {
                WorkspaceLayout.COVER -> Column(Modifier.fillMaxSize()) {
                    SetupBanners(
                        state = state,
                        calendarSetup = calendarSetup,
                        onImportModel = onImportModel,
                        onInitializeGpu = onInitializeGpu,
                        onRequestCalendarPermission = onRequestCalendarPermission,
                        onOpenSettings = openSettings,
                    )
                    // Keep the short-input surface usable when the cover keyboard opens.
                    if (imeBottom == 0) {
                        CoverTodayCard(
                            state = reminderSetup,
                            onComplete = onCompleteReminder,
                            onSnooze = onSnoozeReminder,
                            onOpenReminders = { remindersVisible = true },
                        )
                    }
                    ConversationWorkspace(
                        state = state,
                        calendarReady = calendarSetup.isReady,
                        networkSetup = networkSetup,
                        listState = listState,
                        nearBottom = nearBottom,
                        onPromptChange = onPromptChange,
                        onSend = onSend,
                        onCancel = onCancel,
                        onTurnRecovery = onTurnRecovery,
                        onOpenSettings = openSettings,
                        media = composerMedia,
                        composerFocusRequester = composerFocusRequester,
                        modifier = Modifier.weight(1f),
                    )
                }

                WorkspaceLayout.TWO_PANE -> Row(Modifier.fillMaxSize()) {
                    ExpandedTodayPane(
                        state = reminderSetup,
                        onComplete = onCompleteReminder,
                        onSnooze = onSnoozeReminder,
                        onOpenReminders = { remindersVisible = true },
                        modifier = Modifier.weight(WorkspaceLayoutPolicy.TWO_PANE_TODAY_WEIGHT),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.weight(WorkspaceLayoutPolicy.TWO_PANE_CONVERSATION_WEIGHT)) {
                        SetupBanners(
                            state = state,
                            calendarSetup = calendarSetup,
                            onImportModel = onImportModel,
                            onInitializeGpu = onInitializeGpu,
                            onRequestCalendarPermission = onRequestCalendarPermission,
                            onOpenSettings = openSettings,
                        )
                        ConversationWorkspace(
                            state = state,
                            calendarReady = calendarSetup.isReady,
                            networkSetup = networkSetup,
                            listState = listState,
                            nearBottom = nearBottom,
                            onPromptChange = onPromptChange,
                            onSend = onSend,
                            onCancel = onCancel,
                            onTurnRecovery = onTurnRecovery,
                            onOpenSettings = openSettings,
                            media = composerMedia,
                            composerFocusRequester = composerFocusRequester,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                WorkspaceLayout.BOOK -> {
                    val hingeStartDp = checkNotNull(windowPlan.hingeStartDp)
                        .coerceIn(0f, maxWidth.value)
                    val hingeEndDp = checkNotNull(windowPlan.hingeEndDp)
                        .coerceIn(hingeStartDp, maxWidth.value)
                    Row(Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .width(hingeStartDp.dp)
                                .fillMaxHeight(),
                        ) {
                            ExpandedTodayPane(
                                state = reminderSetup,
                                onComplete = onCompleteReminder,
                                onSnooze = onSnoozeReminder,
                                onOpenReminders = { remindersVisible = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.width((hingeEndDp - hingeStartDp).dp))
                        Column(Modifier.weight(1f)) {
                            SetupBanners(
                                state = state,
                                calendarSetup = calendarSetup,
                                onImportModel = onImportModel,
                                onInitializeGpu = onInitializeGpu,
                                onRequestCalendarPermission = onRequestCalendarPermission,
                                onOpenSettings = openSettings,
                            )
                            ConversationWorkspace(
                                state = state,
                                calendarReady = calendarSetup.isReady,
                                networkSetup = networkSetup,
                                listState = listState,
                                nearBottom = nearBottom,
                                onPromptChange = onPromptChange,
                                onSend = onSend,
                                onCancel = onCancel,
                                onTurnRecovery = onTurnRecovery,
                                onOpenSettings = openSettings,
                                media = composerMedia,
                                composerFocusRequester = composerFocusRequester,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                WorkspaceLayout.TABLETOP -> {
                    val hingeStartDp = (checkNotNull(windowPlan.hingeStartDp) -
                        contentTopOffsetDp).coerceIn(0f, maxHeight.value)
                    val hingeEndDp = (checkNotNull(windowPlan.hingeEndDp) -
                        contentTopOffsetDp).coerceIn(hingeStartDp, maxHeight.value)
                    Column(Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(hingeStartDp.dp),
                        ) {
                            ExpandedTodayPane(
                                state = reminderSetup,
                                onComplete = onCompleteReminder,
                                onSnooze = onSnoozeReminder,
                                onOpenReminders = { remindersVisible = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.height((hingeEndDp - hingeStartDp).dp))
                        Column(Modifier.weight(1f)) {
                            SetupBanners(
                                state = state,
                                calendarSetup = calendarSetup,
                                onImportModel = onImportModel,
                                onInitializeGpu = onInitializeGpu,
                                onRequestCalendarPermission = onRequestCalendarPermission,
                                onOpenSettings = openSettings,
                            )
                            ConversationWorkspace(
                                state = state,
                                calendarReady = calendarSetup.isReady,
                                networkSetup = networkSetup,
                                listState = listState,
                                nearBottom = nearBottom,
                                onPromptChange = onPromptChange,
                                onSend = onSend,
                                onCancel = onCancel,
                                onTurnRecovery = onTurnRecovery,
                                onOpenSettings = openSettings,
                                media = composerMedia,
                                composerFocusRequester = composerFocusRequester,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (settingsVisible) {
        SettingsSheet(
            state = state,
            calendarSetup = calendarSetup,
            notificationSetup = notificationSetup,
            credentials = credentials,
            networkSetup = networkSetup,
            memorySetup = memorySetup,
            diagnosticExport = diagnosticExport,
            userDataTransfer = userDataTransfer,
            onDismiss = { settingsVisible = false },
            onImportModel = onImportModel,
            onInspectModel = onInspectModel,
            onInitializeCpu = onInitializeCpu,
            onInitializeGpu = onInitializeGpu,
            onRequestCalendarPermission = onRequestCalendarPermission,
            onPinCalendar = onPinCalendar,
            onUnpinCalendar = onUnpinCalendar,
            onSetCalendarReadEnabled = onSetCalendarReadEnabled,
            onOpenNotificationAccess = onOpenNotificationAccess,
            onSetNotificationCapture = onSetNotificationCapture,
            onSetKakaoNotificationReply = onSetKakaoNotificationReply,
            onDeleteCapturedNotifications = onDeleteCapturedNotifications,
            onStoreCredential = onStoreCredential,
            onDeleteCredential = onDeleteCredential,
            onSetRouteLookupEnabled = onSetRouteLookupEnabled,
            onSetWebSearchEnabled = onSetWebSearchEnabled,
            onStoreDefaultOrigin = onStoreDefaultOrigin,
            onDeleteDefaultOrigin = onDeleteDefaultOrigin,
            onSetMediaInputEnabled = onSetMediaInputEnabled,
            onSetMemoryEnabled = onSetMemoryEnabled,
            onStoreMemory = onStoreMemory,
            onReplaceMemory = onReplaceMemory,
            onReconfirmMemory = onReconfirmMemory,
            onDeleteMemory = onDeleteMemory,
            onDeleteAllMemories = onDeleteAllMemories,
            onExportDiagnostics = onExportDiagnostics,
            onPrepareUserDataExport = onPrepareUserDataExport,
            onExportUserData = onExportUserData,
            onSelectUserDataImport = onSelectUserDataImport,
            onPreviewUserDataImport = onPreviewUserDataImport,
            onImportUserData = onImportUserData,
        )
    }

    if (remindersVisible) {
        ReminderSheet(
            state = reminderSetup,
            openSourceReminderId = sourceReminderId,
            onSourceDialogDismissed = { sourceReminderId = null },
            onDismiss = { remindersVisible = false },
            onRequestNotificationPermission = onRequestReminderNotificationPermission,
            onOpenExactAlarmSettings = onOpenExactAlarmSettings,
            onCreate = onCreateReminder,
            onComplete = onCompleteReminder,
            onSnooze = onSnoozeReminder,
            onCancel = onCancelReminder,
            onSetDailyBriefEnabled = onSetDailyBriefEnabled,
            onSetDailyBriefTime = onSetDailyBriefTime,
            onSetProactiveRoutePlanningEnabled = onSetProactiveRoutePlanningEnabled,
            onSetQuietHoursEnabled = onSetQuietHoursEnabled,
            onSetWeekendBriefEnabled = onSetWeekendBriefEnabled,
            onSetCommitmentProposalsEnabled = onSetCommitmentProposalsEnabled,
            onCaptureInbox = onCaptureCommitmentInbox,
            onPromoteProposal = onPromoteCommitmentProposal,
            onDismissProposal = onDismissCommitmentProposal,
        )
    }

    if (chatHistory.visible) {
        HistorySheet(
            history = chatHistory,
            onDismiss = onCloseHistory,
            onSwitch = onSwitchConversation,
            onDelete = onDeleteConversation,
            onDeleteAll = onDeleteAllConversations,
        )
    }

    if (pendingConfirmation != null) {
        ConfirmationSheet(
            pending = pendingConfirmation,
            todayBrief = reminderSetup.todayBrief,
            onDecision = onConfirmation,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceTopBar(
    state: PersonalEdgeUiState,
    turnActive: Boolean,
    windowPlan: WorkspaceLayoutPlan,
    onNewConversation: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bookPosture = windowPlan.layout == WorkspaceLayout.BOOK
        val hingeStartDp = windowPlan.hingeStartDp
            ?.coerceIn(0f, maxWidth.value)
            ?: 0f
        val hingeEndDp = windowPlan.hingeEndDp
            ?.coerceIn(hingeStartDp, maxWidth.value)
            ?: hingeStartDp
        Column {
            if (bookPosture) {
                Row(Modifier.fillMaxWidth()) {
                    // Today already identifies the left pane; app actions stay entirely right of
                    // the physical hinge instead of centering a title across an occluded region.
                    Spacer(Modifier.width(hingeEndDp.dp))
                    PersonalEdgeTopAppBar(
                        state = state,
                        turnActive = turnActive,
                        onNewConversation = onNewConversation,
                        onOpenReminders = onOpenReminders,
                        onOpenHistory = onOpenHistory,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                PersonalEdgeTopAppBar(
                    state = state,
                    turnActive = turnActive,
                    onNewConversation = onNewConversation,
                    onOpenReminders = onOpenReminders,
                    onOpenHistory = onOpenHistory,
                    onOpenSettings = onOpenSettings,
                )
            }
            if (bookPosture) {
                Row(Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        modifier = Modifier.width(hingeStartDp.dp),
                        thickness = 0.7.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(Modifier.width((hingeEndDp - hingeStartDp).dp))
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        thickness = 0.7.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            } else {
                HorizontalDivider(
                    thickness = 0.7.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalEdgeTopAppBar(
    state: PersonalEdgeUiState,
    turnActive: Boolean,
    onNewConversation: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Personal Edge",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(3.dp))
                val chip = modelStatusChip(state.modelStatus)
                StatusPill(
                    label = state.activeBackend?.let { "${chip.label} · ${it.name}" }
                        ?: chip.label,
                    tone = chip.tone,
                )
            }
        },
        navigationIcon = {
            // Switching threads under a streaming answer would misattribute it.
            IconButton(onClick = onNewConversation, enabled = !turnActive) {
                BarIcon(R.drawable.ic_plus, "새 대화", enabled = !turnActive)
            }
        },
        actions = {
            IconButton(onClick = onOpenReminders) {
                BarIcon(R.drawable.ic_bell, "리마인더")
            }
            IconButton(onClick = onOpenHistory, enabled = !turnActive) {
                BarIcon(R.drawable.ic_history, "대화 기록", enabled = !turnActive)
            }
            IconButton(onClick = onOpenSettings) {
                BarIcon(R.drawable.ic_sliders, "설정")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
private fun ConversationWorkspace(
    state: PersonalEdgeUiState,
    calendarReady: Boolean,
    networkSetup: NetworkSetupState,
    listState: LazyListState,
    nearBottom: Boolean,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onTurnRecovery: (ChatRecoveryAction) -> Unit,
    onOpenSettings: () -> Unit,
    media: ComposerMediaState,
    composerFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val turnActive = state.activeTurnId != null
    Column(modifier) {
        Box(modifier = Modifier.weight(1f)) {
            ChatTranscript(
                messages = state.messages,
                listState = listState,
                turnActive = turnActive,
                activeReasoning = state.activeReasoning,
                suggestions = promptSuggestions(
                    calendarReady = calendarReady,
                    routeLookupEnabled = networkSetup.routeLookupEnabled,
                    webSearchEnabled = networkSetup.webSearchEnabled,
                ),
                onSuggestion = { suggestion -> onPromptChange(suggestion.prompt) },
                onRecoveryAction = onTurnRecovery,
                recoveryActionsEnabled = !turnActive,
                modifier = Modifier.fillMaxSize(),
            )

            JumpToLatestPill(
                visible = !nearBottom && state.messages.isNotEmpty(),
                onClick = {
                    scope.launch { listState.animateScrollToItem(state.messages.lastIndex) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }

        Composer(
            prompt = state.prompt,
            onPromptChange = onPromptChange,
            canSend = state.canSend,
            turnActive = turnActive,
            block = composerBlock(
                modelStatus = state.modelStatus,
                thermalStatus = state.thermalStatus,
                turnActive = turnActive,
                // The media notice is the more specific of the two and answers a question the
                // owner just asked by tapping a control, so it wins the one notice slot.
                inputWarning = state.mediaNotice ?: state.promptInputWarning,
            ),
            media = media,
            onSend = onSend,
            onCancel = onCancel,
            onOpenSettings = onOpenSettings,
            focusRequester = composerFocusRequester,
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.ime.union(WindowInsets.navigationBars),
            ),
        )
    }
}

/**
 * Reading back through a long transcript should not be interrupted by an arriving answer, so the
 * automatic scroll stops when the user scrolls away and this offers the way back instead.
 */
@Composable
private fun JumpToLatestPill(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(PersonalEdgeMotion.effects()) +
            scaleIn(PersonalEdgeMotion.spatial(), initialScale = 0.85f),
        exit = fadeOut(PersonalEdgeMotion.effects()) +
            scaleOut(PersonalEdgeMotion.spatial(), targetScale = 0.85f),
        modifier = modifier,
    ) {
        Surface(
            onClick = onClick,
            shape = CapsuleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_down),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "최신 메시지",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * An explicit tint overrides the content colour an `IconButton` fades when it is disabled, so the
 * disabled state has to be drawn here or a locked-out action would look tappable mid-turn.
 */
@Composable
private fun BarIcon(iconRes: Int, contentDescription: String, enabled: Boolean = true) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f),
        modifier = Modifier.size(22.dp),
    )
}

/**
 * At most one setup step and, separately, a thermal state worth reacting to. A checklist of every
 * unfinished thing would be back where the six cards started.
 */
@Composable
private fun SetupBanners(
    state: PersonalEdgeUiState,
    calendarSetup: CalendarSetupState,
    onImportModel: () -> Unit,
    onInitializeGpu: () -> Unit,
    onRequestCalendarPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val nudge = setupNudge(
        modelStatus = state.modelStatus,
        calendarPermissionGranted = calendarSetup.permissionGranted,
        calendarPinned = calendarSetup.pinnedCalendarId != null,
    )
    val thermal = thermalBanner(state.thermalStatus)

    Column(modifier = Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = nudge != null,
            enter = fadeIn(PersonalEdgeMotion.effects()),
            exit = fadeOut(PersonalEdgeMotion.effects()),
        ) {
            nudge?.let { step ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = step.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = step.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = {
                                when (step.step) {
                                    SetupStep.IMPORT_MODEL -> onImportModel()
                                    SetupStep.LOAD_MODEL -> onInitializeGpu()
                                    SetupStep.GRANT_CALENDAR -> onRequestCalendarPermission()
                                    SetupStep.RETRY_MODEL, SetupStep.PIN_CALENDAR ->
                                        onOpenSettings()
                                }
                            },
                            shape = CapsuleShape,
                        ) {
                            Text(step.actionLabel)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = thermal != null,
            enter = fadeIn(PersonalEdgeMotion.effects()),
            exit = fadeOut(PersonalEdgeMotion.effects()),
        ) {
            thermal?.let { banner ->
                Surface(
                    shape = CapsuleShape,
                    color = toneContainerColor(banner.tone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = banner.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = onToneContainerColor(banner.tone),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}
