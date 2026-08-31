package com.personaledge.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personaledge.agent.ChatHistoryState
import com.personaledge.agent.R
import com.personaledge.agent.ui.components.RowDivider
import com.personaledge.agent.ui.components.SectionContent
import com.personaledge.agent.ui.components.SettingsRow
import com.personaledge.agent.ui.components.SettingsSection

/**
 * Saved conversations.
 *
 * The destructive action keeps its two-tap shape from the previous dialog — it is the one control
 * here with no undo — but it is now a row that changes in place instead of a confirm button that
 * silently swaps meaning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistorySheet(
    history: ChatHistoryState,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmingDeleteAll by rememberSaveable { mutableStateOf(false) }
    var deleteCandidateId by rememberSaveable { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 640.dp)
                .navigationBarsPadding(),
        ) {
            SheetHeader(title = "대화 기록", onClose = onDismiss)
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (history.conversations.isEmpty()) {
                    SettingsSection(title = "저장된 대화") {
                        SectionContent {
                            Text(
                                text = "저장된 대화가 없습니다.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    SettingsSection(
                        title = "저장된 대화 ${history.conversations.size}개",
                        footnote = "대화는 기기 안에만 저장되며 백업에 포함되지 않습니다.",
                    ) {
                        history.conversations.forEachIndexed { index, conversation ->
                            if (index > 0) RowDivider()
                            val active = conversation.id == history.activeConversationId
                            SettingsRow(
                                title = conversation.title,
                                subtitle = if (active) "현재 대화" else null,
                                iconRes = if (active) {
                                    R.drawable.ic_check
                                } else {
                                    R.drawable.ic_history
                                },
                                iconTint = if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                iconContainer = if (active) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                onClick = { onSwitch(conversation.id) },
                            ) {
                                IconButton(onClick = { deleteCandidateId = conversation.id }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_trash),
                                        contentDescription = "이 대화 삭제",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }

                    SettingsSection(title = "전체 삭제") {
                        if (confirmingDeleteAll) {
                            SectionContent {
                                Text(
                                    text = "모든 대화를 지웁니다. 되돌릴 수 없습니다. 실행 원장은 " +
                                        "따로 보관되므로 이미 실행된 작업은 취소되지 않습니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                androidx.compose.foundation.layout.Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextButton(
                                        onClick = {
                                            confirmingDeleteAll = false
                                            onDeleteAll()
                                        },
                                    ) {
                                        Text(
                                            text = "모두 삭제",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    TextButton(onClick = { confirmingDeleteAll = false }) {
                                        Text("취소")
                                    }
                                }
                            }
                        } else {
                            SettingsRow(
                                title = "모든 대화 삭제",
                                subtitle = "확인을 한 번 더 받습니다.",
                                iconRes = R.drawable.ic_trash,
                                iconTint = MaterialTheme.colorScheme.error,
                                iconContainer = MaterialTheme.colorScheme.errorContainer,
                                onClick = { confirmingDeleteAll = true },
                            )
                        }
                    }
                }

                history.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }

    deleteCandidateId?.let { conversationId ->
        DestructiveConfirmationDialog(
            title = "이 대화를 삭제할까요?",
            body = "대화 내용은 되돌릴 수 없지만 실행 원장은 그대로 유지됩니다.",
            onConfirm = {
                onDelete(conversationId)
                deleteCandidateId = null
            },
            onDismiss = { deleteCandidateId = null },
        )
    }
}
