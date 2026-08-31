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

/**
 * Setup as a sheet rather than a permanent half of the screen.
 *
 * Six stacked cards used to share the window with the transcript, which left both cramped and hid
 * the prompt field behind a scroll on a folded display. Setup is occasional, so it now lives in a
 * dismissible sheet, grouped by subject, while the chat keeps the whole window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    state: PersonalEdgeUiState,
    calendarSetup: CalendarSetupState,
    notificationSetup: NotificationSetupState,
    credentials: CredentialsState,
    networkSetup: NetworkSetupState,
    memorySetup: MemorySetupState = MemorySetupState(),
    diagnosticExport: DiagnosticExportState,
    userDataTransfer: UserDataTransferState = UserDataTransferState(),
    onDismiss: () -> Unit,
    onImportModel: () -> Unit,
    onInspectModel: () -> Unit,
    onInitializeCpu: () -> Unit,
    onInitializeGpu: () -> Unit,
    onRequestCalendarPermission: () -> Unit,
    onPinCalendar: (CalendarOption) -> Unit,
    onUnpinCalendar: () -> Unit,
    onSetCalendarReadEnabled: (Long, Boolean) -> Unit,
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
    onSetMediaInputEnabled: (Boolean) -> Unit = {},
    onSetMemoryEnabled: (Boolean) -> Unit = {},
    onStoreMemory: (String, MemoryCategory, String?) -> Unit = { _, _, _ -> },
    onReplaceMemory: (String, String, MemoryCategory, String?) -> Unit = { _, _, _, _ -> },
    onReconfirmMemory: (String) -> Unit = {},
    onDeleteMemory: (String) -> Unit = {},
    onDeleteAllMemories: () -> Unit = {},
    onExportDiagnostics: () -> Unit,
    onPrepareUserDataExport: (UserDataSelection) -> Unit = {},
    onExportUserData: (UserDataSelection, String) -> Unit = { _, _ -> },
    onSelectUserDataImport: () -> Unit = {},
    onPreviewUserDataImport: (String) -> Unit = {},
    onImportUserData: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // This long child owns vertical scrolling. If sheet gestures remain enabled, an
        // unconsumed drag at either boundary is handed to ModalBottomSheet and pulls the already
        // expanded Surface past its anchor on Fold8. Back, scrim tap, and the explicit close button
        // still dismiss the sheet, so remove the misleading drag handle as well.
        sheetGesturesEnabled = false,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.94f)
                .imePadding(),
        ) {
            SheetHeader(title = "설정", onClose = onDismiss)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                ModelSection(
                    state = state,
                    onImportModel = onImportModel,
                    onInspectModel = onInspectModel,
                    onInitializeCpu = onInitializeCpu,
                    onInitializeGpu = onInitializeGpu,
                )
                MemorySection(
                    setup = memorySetup,
                    onSetEnabled = onSetMemoryEnabled,
                    onStore = onStoreMemory,
                    onReplace = onReplaceMemory,
                    onReconfirm = onReconfirmMemory,
                    onDelete = onDeleteMemory,
                    onDeleteAll = onDeleteAllMemories,
                )
                CalendarSection(
                    setup = calendarSetup,
                    onRequestPermission = onRequestCalendarPermission,
                    onPinCalendar = onPinCalendar,
                    onUnpinCalendar = onUnpinCalendar,
                    onSetReadEnabled = onSetCalendarReadEnabled,
                )
                MediaInputSection(
                    enabled = state.mediaInputEnabled,
                    onSetEnabled = onSetMediaInputEnabled,
                )
                NetworkSection(
                    setup = networkSetup,
                    onSetRouteLookupEnabled = onSetRouteLookupEnabled,
                    onSetWebSearchEnabled = onSetWebSearchEnabled,
                    onStoreDefaultOrigin = onStoreDefaultOrigin,
                    onDeleteDefaultOrigin = onDeleteDefaultOrigin,
                )
                CredentialSection(
                    credentials = credentials,
                    onStore = onStoreCredential,
                    onDelete = onDeleteCredential,
                )
                NotificationSection(
                    setup = notificationSetup,
                    onOpenAccessSettings = onOpenNotificationAccess,
                    onSetCapture = onSetNotificationCapture,
                    onSetReplyEnabled = onSetKakaoNotificationReply,
                    onDeleteCaptured = onDeleteCapturedNotifications,
                )
                DiagnosticsSection(
                    state = diagnosticExport,
                    onExport = onExportDiagnostics,
                )
                UserDataTransferSection(
                    state = userDataTransfer,
                    onPrepareExport = onPrepareUserDataExport,
                    onExport = onExportUserData,
                    onSelectImport = onSelectUserDataImport,
                    onPreviewImport = onPreviewUserDataImport,
                    onImport = onImportUserData,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
