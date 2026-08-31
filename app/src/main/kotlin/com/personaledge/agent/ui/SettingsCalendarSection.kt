package com.personaledge.agent.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.personaledge.agent.CalendarOption
import com.personaledge.agent.CalendarSetupState
import com.personaledge.agent.R
import com.personaledge.agent.ui.components.RowDivider
import com.personaledge.agent.ui.components.SectionContent
import com.personaledge.agent.ui.components.SettingsRow
import com.personaledge.agent.ui.components.SettingsSection
import com.personaledge.agent.ui.theme.CapsuleShape

@Composable
internal fun CalendarSection(
    setup: CalendarSetupState,
    onRequestPermission: () -> Unit,
    onPinCalendar: (CalendarOption) -> Unit,
    onUnpinCalendar: () -> Unit,
    onSetReadEnabled: (Long, Boolean) -> Unit,
) {
    val context = LocalContext.current

    SettingsSection(
        title = "캘린더",
        footnote = "명시적으로 고른 캘린더만 함께 조회합니다. 등록·수정은 별도로 지정한 " +
            "기본 쓰기 캘린더 하나에만 허용됩니다.",
    ) {
        when {
            !setup.permissionGranted -> SectionContent {
                Text(
                    text = "일정 조회·등록·수정에는 캘린더 권한이 필요합니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (setup.permissionPermanentlyDenied) {
                    Text(
                        text = "권한 요청이 차단되어 시스템 설정에서 직접 허용해야 합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        shape = CapsuleShape,
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
                    Button(onClick = onRequestPermission, shape = CapsuleShape) {
                        Text("캘린더 권한 허용")
                    }
                }
            }

            setup.calendars.isEmpty() -> SectionContent {
                Text(
                    text = "사용 가능한 기기 캘린더가 없습니다. 삼성 캘린더의 동기화 계정과 " +
                        "캘린더 표시 설정을 확인하세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            else -> {
                setup.calendars.forEachIndexed { index, option ->
                    if (index > 0) RowDivider()
                    val pinned = option.id == setup.pinnedCalendarId
                    val readSelected = option.id in setup.readCalendarIds
                    SettingsRow(
                        title = option.label,
                        subtitle = "${option.accountName} · ${option.accountType} · ID ${option.id}",
                        iconRes = R.drawable.ic_calendar,
                        iconTint = if (pinned || readSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        iconContainer = if (pinned || readSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        enabled = true,
                        onClick = { onSetReadEnabled(option.id, !readSelected) },
                    ) {
                        if (pinned) {
                            Text(
                                text = "읽기 · 기본 쓰기",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (option.writable) {
                            TextButton(onClick = { onPinCalendar(option) }) {
                                Text(if (readSelected) "기본 쓰기" else "읽기 + 기본 쓰기")
                            }
                        } else {
                            Text(
                                text = if (readSelected) "읽기 선택됨" else "읽기 전용 · 미선택",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (setup.pinnedCalendarId != null) {
                    RowDivider(inset = false)
                    SettingsRow(
                        title = "선택 해제",
                        subtitle = "해제하면 캘린더 Tool이 모두 거절됩니다.",
                        iconRes = R.drawable.ic_close,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconContainer = MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = onUnpinCalendar,
                    )
                }
            }
        }

        setup.error?.let { message -> SectionError(message) }
    }
}
