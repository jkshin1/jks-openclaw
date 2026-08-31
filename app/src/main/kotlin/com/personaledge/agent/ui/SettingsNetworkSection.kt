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
internal fun NetworkSection(
    setup: NetworkSetupState,
    onSetRouteLookupEnabled: (Boolean) -> Unit,
    onSetWebSearchEnabled: (Boolean) -> Unit,
    onStoreDefaultOrigin: (String) -> Unit,
    onDeleteDefaultOrigin: () -> Unit,
) {
    // A home label can be sensitive. It is never rememberSaveable and the stored value is never
    // read back into UI; only a presence bit is exposed after save.
    var originEntry by remember { mutableStateOf("") }

    SettingsSection(
        title = "외부 네트워크 조회",
        footnote = "허용하면 조회 요청을 별도 확인 없이 실행합니다. 경로는 NAVER Cloud Maps로, " +
            "웹 검색은 You.com 및 필요 시 Tavily로, 날씨 위치는 기기 위치 검색 서비스와 " +
            "Open-Meteo로 전송하며 설정에서 언제든 끌 수 있습니다.",
    ) {
        ToggleRow(
            title = "경로 조회 허용",
            subtitle = "출발지와 도착지를 NAVER Cloud Maps에 전송",
            checked = setup.routeLookupEnabled,
            onCheckedChange = onSetRouteLookupEnabled,
            iconRes = R.drawable.ic_route,
            iconTint = MaterialTheme.colorScheme.primary,
            iconContainer = MaterialTheme.colorScheme.primaryContainer,
        )
        RowDivider()
        ToggleRow(
            title = "웹 검색·날씨 조회 허용",
            subtitle = "명시 검색어와 공개 지식 질문 주제는 You.com/Tavily로, 날씨 위치는 위치 검색/Open-Meteo로 전송",
            checked = setup.webSearchEnabled,
            onCheckedChange = onSetWebSearchEnabled,
            iconRes = R.drawable.ic_globe,
            iconTint = MaterialTheme.colorScheme.primary,
            iconContainer = MaterialTheme.colorScheme.primaryContainer,
        )
        RowDivider(inset = false)
        SectionContent {
            OutlinedTextField(
                value = originEntry,
                onValueChange = { value ->
                    if (value.codePointCount(0, value.length) <= 80) originEntry = value
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
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
                    shape = CapsuleShape,
                ) {
                    Text("출발지 저장")
                }
                if (setup.defaultOriginConfigured) {
                    TextButton(onClick = onDeleteDefaultOrigin) {
                        Text("출발지 삭제", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        setup.error?.let { message -> SectionError(message) }
    }
}
