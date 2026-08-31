package com.personaledge.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import com.personaledge.agent.ui.ConfirmationSheet
import com.personaledge.agent.ui.HistorySheet
import com.personaledge.agent.ui.PersonalEdgeScreen
import com.personaledge.agent.ui.SettingsSheet
import com.personaledge.agent.ui.theme.PersonalEdgeAgentTheme
import com.personaledge.core.llm.TurnId
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.tools.ActionPreview

/**
 * A debug harness for reviewing the UI without a device that can run the model.
 *
 * The screens here are the production composables; only the state is fabricated. That is the
 * point — a screenshot from this activity is evidence about the real layout, spacing, and colours,
 * while remaining explicitly *not* evidence about the runtime, the tools, or any gate. Nothing in
 * this file touches the ViewModel, the vault, the calendar provider, or diagnostics.
 *
 * `adb shell am start -n com.personaledge.agent/.UiGalleryActivity --es scene chat`
 */
class UiGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val scene = intent?.getStringExtra(EXTRA_SCENE) ?: SCENE_CHAT
        setContent {
            PersonalEdgeAgentTheme {
                GalleryScene(scene)
            }
        }
    }

    private companion object {
        const val EXTRA_SCENE = "scene"
        const val SCENE_CHAT = "chat"
    }
}

@Composable
private fun GalleryScene(scene: String) {
    val calendarReady = CalendarSetupState(
        permissionGranted = true,
        calendars = sampleCalendars,
        pinnedCalendarId = 12,
        pinnedCalendarLabel = "개인",
    )
    val networkOn = NetworkSetupState(
        routeLookupEnabled = true,
        webSearchEnabled = false,
        defaultOriginConfigured = true,
    )

    when (scene) {
        "onboarding" -> GalleryScreen(
            state = PersonalEdgeUiState(
                modelStatus = ModelUiStatus.MISSING,
                statusText = "고정된 Gemma 파일을 아직 가져오지 않았습니다.",
                thermalStatus = DiagnosticThermalStatus.NONE,
            ),
            calendarSetup = CalendarSetupState(permissionGranted = false),
            networkSetup = NetworkSetupState(),
        )

        "empty" -> GalleryScreen(
            state = PersonalEdgeUiState(
                modelStatus = ModelUiStatus.READY,
                statusText = "GPU에서 준비되었습니다.",
                activeBackend = InferenceBackend.GPU,
                thermalStatus = DiagnosticThermalStatus.NONE,
            ),
            calendarSetup = calendarReady,
            networkSetup = networkOn,
        )

        "streaming" -> GalleryScreen(
            state = PersonalEdgeUiState(
                modelStatus = ModelUiStatus.READY,
                statusText = "GPU에서 준비되었습니다.",
                activeBackend = InferenceBackend.GPU,
                thermalStatus = DiagnosticThermalStatus.MODERATE,
                activeTurnId = TurnId("turn-gallery"),
                activeReasoning = ActiveReasoningUiState(
                    turnId = TurnId("turn-gallery"),
                    assistantEntryId = "m10",
                    text = "요청의 핵심은 출발 시각 알림을 맞추는 것이다. " +
                        "직전 대화의 2시 30분 출발 권고와 사용자가 지정한 " +
                        "2시 20분을 구분해 알람 도구 인자를 구성한다.",
                ),
                prompt = "",
                messages = streamingMessages,
            ),
            calendarSetup = calendarReady,
            networkSetup = networkOn,
        )

        "math" -> GalleryScreen(
            state = PersonalEdgeUiState(
                modelStatus = ModelUiStatus.READY,
                statusText = "GPU에서 준비되었습니다.",
                activeBackend = InferenceBackend.GPU,
                thermalStatus = DiagnosticThermalStatus.NONE,
                messages = mathMessages,
            ),
            calendarSetup = calendarReady,
            networkSetup = networkOn,
        )

        "settings" -> GalleryScreen(
            state = readyWithHistory,
            calendarSetup = calendarReady,
            networkSetup = networkOn,
            overlay = { state, calendar, network ->
                SettingsSheet(
                    state = state,
                    calendarSetup = calendar,
                    notificationSetup = NotificationSetupState(
                        accessGranted = true,
                        captureEnabled = true,
                        storedCount = 42,
                    ),
                    credentials = sampleCredentials,
                    networkSetup = network,
                    diagnosticExport = DiagnosticExportState(),
                    onDismiss = {},
                    onImportModel = {},
                    onInspectModel = {},
                    onInitializeCpu = {},
                    onInitializeGpu = {},
                    onRequestCalendarPermission = {},
                    onPinCalendar = {},
                    onUnpinCalendar = {},
                    onSetCalendarReadEnabled = { _, _ -> },
                    onOpenNotificationAccess = {},
                    onSetNotificationCapture = {},
                    onSetKakaoNotificationReply = {},
                    onDeleteCapturedNotifications = {},
                    onStoreCredential = { _, _ -> },
                    onDeleteCredential = {},
                    onSetRouteLookupEnabled = {},
                    onSetWebSearchEnabled = {},
                    onStoreDefaultOrigin = {},
                    onDeleteDefaultOrigin = {},
                    onExportDiagnostics = {},
                )
            },
        )

        "settings_setup" -> GalleryScreen(
            state = PersonalEdgeUiState(
                modelStatus = ModelUiStatus.VERIFIED,
                statusText = "SHA-256 검증됨. 아직 불러오지 않았습니다.",
                thermalStatus = DiagnosticThermalStatus.NONE,
            ),
            calendarSetup = CalendarSetupState(
                permissionGranted = true,
                calendars = sampleCalendars,
            ),
            networkSetup = NetworkSetupState(),
            overlay = { state, calendar, network ->
                SettingsSheet(
                    state = state,
                    calendarSetup = calendar,
                    notificationSetup = NotificationSetupState(accessGranted = false),
                    credentials = CredentialsState(
                        statuses = CredentialSlot.entries.map { slot ->
                            CredentialStatus(slot = slot, stored = false)
                        },
                    ),
                    networkSetup = network,
                    diagnosticExport = DiagnosticExportState(),
                    onDismiss = {},
                    onImportModel = {},
                    onInspectModel = {},
                    onInitializeCpu = {},
                    onInitializeGpu = {},
                    onRequestCalendarPermission = {},
                    onPinCalendar = {},
                    onUnpinCalendar = {},
                    onSetCalendarReadEnabled = { _, _ -> },
                    onOpenNotificationAccess = {},
                    onSetNotificationCapture = {},
                    onSetKakaoNotificationReply = {},
                    onDeleteCapturedNotifications = {},
                    onStoreCredential = { _, _ -> },
                    onDeleteCredential = {},
                    onSetRouteLookupEnabled = {},
                    onSetWebSearchEnabled = {},
                    onStoreDefaultOrigin = {},
                    onDeleteDefaultOrigin = {},
                    onExportDiagnostics = {},
                )
            },
        )

        "history" -> GalleryScreen(
            state = readyWithHistory,
            calendarSetup = calendarReady,
            networkSetup = networkOn,
            overlay = { _, _, _ ->
                HistorySheet(
                    history = sampleHistory.copy(visible = true),
                    onDismiss = {},
                    onSwitch = {},
                    onDelete = {},
                    onDeleteAll = {},
                )
            },
        )

        "confirm" -> GalleryScreen(
            state = readyWithHistory,
            calendarSetup = calendarReady,
            networkSetup = networkOn,
            overlay = { _, _, _ ->
                ConfirmationSheet(
                    pending = PendingConfirmation(
                        actionId = "gallery-action",
                        toolName = "calendar_create_event",
                        preview = ActionPreview(
                            title = "일정을 등록할까요?",
                            summary = "2026-08-24 15:00–16:00 · 치과\n캘린더: 개인 (ID 12)",
                        ),
                        parameterDigest = "9f2c41ab77de0c5581",
                        expiresAtEpochMillis = Long.MAX_VALUE,
                    ),
                    onDecision = { _, _ -> },
                )
            },
        )

        else -> GalleryScreen(
            state = readyWithHistory,
            calendarSetup = calendarReady,
            networkSetup = networkOn,
        )
    }
}

@Composable
private fun GalleryScreen(
    state: PersonalEdgeUiState,
    calendarSetup: CalendarSetupState,
    networkSetup: NetworkSetupState,
    overlay: @Composable (
        PersonalEdgeUiState,
        CalendarSetupState,
        NetworkSetupState,
    ) -> Unit = { _, _, _ -> },
) {
    PersonalEdgeScreen(
        state = state,
        calendarSetup = calendarSetup,
        chatHistory = ChatHistoryState(),
        notificationSetup = NotificationSetupState(),
        credentials = sampleCredentials,
        networkSetup = networkSetup,
        diagnosticExport = DiagnosticExportState(),
        pendingConfirmation = null,
        onRequestCalendarPermission = {},
        onPinCalendar = {},
        onUnpinCalendar = {},
        onOpenHistory = {},
        onCloseHistory = {},
        onNewConversation = {},
        onSwitchConversation = {},
        onDeleteConversation = {},
        onDeleteAllConversations = {},
        onOpenNotificationAccess = {},
        onSetNotificationCapture = {},
        onSetKakaoNotificationReply = {},
        onDeleteCapturedNotifications = {},
        onStoreCredential = { _, _ -> },
        onDeleteCredential = {},
        onSetRouteLookupEnabled = {},
        onSetWebSearchEnabled = {},
        onStoreDefaultOrigin = {},
        onDeleteDefaultOrigin = {},
        onExportDiagnostics = {},
        onPromptChange = {},
        onImportModel = {},
        onInspectModel = {},
        onInitializeCpu = {},
        onInitializeGpu = {},
        onRefreshSetup = {},
        onSend = {},
        onCancel = {},
        onConfirmation = { _, _ -> },
    )
    overlay(state, calendarSetup, networkSetup)
}

private val sampleCalendars = listOf(
    CalendarOption(
        id = 12,
        label = "개인",
        accountName = "owner@example.com",
        accountType = "com.google",
        writable = true,
    ),
    CalendarOption(
        id = 31,
        label = "공휴일",
        accountName = "holidays@example.com",
        accountType = "com.google",
        writable = false,
    ),
)

private val sampleCredentials = CredentialsState(
    statuses = CredentialSlot.entries.mapIndexed { index, slot ->
        CredentialStatus(slot = slot, stored = index < 2)
    },
)

private val sampleHistory = ChatHistoryState(
    conversations = listOf(
        ConversationSummaryUi(
            id = "c1",
            title = "내일 치과 일정 넣어줘",
            updatedAtEpochMillis = 0,
        ),
        ConversationSummaryUi(
            id = "c2",
            title = "강남까지 얼마나 걸려?",
            updatedAtEpochMillis = 0,
        ),
        ConversationSummaryUi(
            id = "c3",
            title = "다음 알람 언제야?",
            updatedAtEpochMillis = 0,
        ),
    ),
    activeConversationId = "c1",
)

private val conversationMessages = listOf(
    ChatEntry("m1", ChatRole.USER, "내일 오후 3시에 치과 일정 넣어줘"),
    ChatEntry(
        "m2",
        ChatRole.ASSISTANT,
        "내일 오후 3시 치과 일정을 등록하려고 합니다. 확인 창에서 승인해 주세요.",
    ),
    ChatEntry(
        "m3",
        ChatRole.TOOL,
        "calendar_create_event 완료 · 2026-08-24 15:00–16:00 · 캘린더 개인 (ID 12)",
    ),
    ChatEntry("m4", ChatRole.ASSISTANT, "등록했습니다. 내일 오후 3시에 치과 일정이 있습니다."),
    ChatEntry("m5", ChatRole.USER, "거기까지 얼마나 걸려?"),
    ChatEntry("m6", ChatRole.STATUS, "경로 조회 동의를 확인했습니다."),
    ChatEntry(
        "m7",
        ChatRole.TOOL,
        "route_estimate 완료 · 21분 · 11.8km · 실시간 교통 반영",
    ),
    ChatEntry("m8", ChatRole.ASSISTANT, "차로 약 21분, 11.8km입니다. 2시 30분쯤 출발하시면 됩니다."),
)

private val readyWithHistory = PersonalEdgeUiState(
    modelStatus = ModelUiStatus.READY,
    statusText = "GPU에서 준비되었습니다.",
    activeBackend = InferenceBackend.GPU,
    thermalStatus = DiagnosticThermalStatus.NONE,
    messages = conversationMessages,
)

private val streamingMessages = conversationMessages + listOf(
    ChatEntry("m9", ChatRole.USER, "그럼 2시 20분에 알람도 맞춰줘"),
    ChatEntry("m10", ChatRole.ASSISTANT, ""),
)

private val mathMessages = listOf(
    ChatEntry("math-user", ChatRole.USER, "17 더하기 25를 단계별로 설명해줘"),
    ChatEntry(
        "math-assistant",
        ChatRole.ASSISTANT,
        """
        **1. 일의 자리 더하기**
        먼저 일의 자리 숫자를 더합니다.
        ${'$'}7 + 5 = 12${'$'}

        **2. 십의 자리 더하기**
        ${'$'}1 \text{(올림수)} + 1 \text{(17의 십의 자리)} + 2 \text{(25의 십의 자리)} = 4${'$'}

        **결과:** 따라서 ${'$'}17 + 25 = 42${'$'}입니다.
        """.trimIndent(),
    ),
)
