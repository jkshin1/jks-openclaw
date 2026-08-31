package com.personaledge.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.personaledge.agent.PendingMediaAttachment
import com.personaledge.agent.R
import com.personaledge.agent.VoiceRecordingUiState
import com.personaledge.agent.ui.theme.CapsuleShape
import com.personaledge.agent.ui.theme.PersonalEdgeMotion

/**
 * The prompt bar.
 *
 * Two deliberate changes from the previous version. The reason a send is impossible is stated
 * above the field instead of leaving a greyed-out button unexplained, and the button itself is one
 * control that morphs between send and stop, so the running turn always has a visible way out
 * exactly where the thumb already is.
 */
@Composable
internal fun Composer(
    prompt: String,
    onPromptChange: (String) -> Unit,
    canSend: Boolean,
    turnActive: Boolean,
    block: ComposerBlock?,
    media: ComposerMediaState,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val currentPrompt by rememberUpdatedState(prompt)
    val currentOnPromptChange by rememberUpdatedState(onPromptChange)
    val dropTarget = remember(focusRequester) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                // Updating the field is terminal. Sending remains an explicit gesture, and a
                // rejected drop must not mutate the prompt or move keyboard focus.
                return ComposerDropPolicy.applyDrop(
                    currentPrompt = currentPrompt,
                    payload = event.toComposerDropPayload(),
                    onPromptChange = currentOnPromptChange,
                    onRequestFocus = focusRequester::requestFocus,
                )
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            HorizontalDivider(
                thickness = 0.7.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            AnimatedVisibility(
                visible = block != null,
                enter = fadeIn(PersonalEdgeMotion.effects()) +
                    expandVertically(PersonalEdgeMotion.spatial()),
                exit = fadeOut(PersonalEdgeMotion.effects()) +
                    shrinkVertically(PersonalEdgeMotion.spatial()),
            ) {
                ComposerNotice(block = block, onOpenSettings = onOpenSettings)
            }
            AnimatedVisibility(
                visible = media.recording != null,
                enter = fadeIn(PersonalEdgeMotion.effects()) +
                    expandVertically(PersonalEdgeMotion.spatial()),
                exit = fadeOut(PersonalEdgeMotion.effects()) +
                    shrinkVertically(PersonalEdgeMotion.spatial()),
            ) {
                // Kept non-null for the exit animation, which renders one frame after it clears.
                media.recording?.let { recording ->
                    ComposerRecordingBar(
                        recording = recording,
                        onStop = media.onStopRecording,
                        onCancel = media.onCancelRecording,
                    )
                }
            }
            AnimatedVisibility(
                visible = media.attachment != null && media.recording == null,
                enter = fadeIn(PersonalEdgeMotion.effects()) +
                    expandVertically(PersonalEdgeMotion.spatial()),
                exit = fadeOut(PersonalEdgeMotion.effects()) +
                    shrinkVertically(PersonalEdgeMotion.spatial()),
            ) {
                media.attachment?.let { attachment ->
                    ComposerAttachmentChip(
                        attachment = attachment,
                        onRemove = media.onRemoveAttachment,
                    )
                }
            }
            if (media.visible) {
                ComposerMediaActions(
                    enabled = media.enabled,
                    onTakePhoto = media.onTakePhoto,
                    onPickImage = media.onPickImage,
                    onStartDictation = media.onStartDictation,
                    onStartVoiceAttachment = media.onStartVoiceAttachment,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 12.dp, top = 8.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                TextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .dragAndDropTarget(
                            shouldStartDragAndDrop = { event ->
                                !turnActive && ComposerDropPolicy.acceptsMimeTypes(
                                    event.mimeTypes().toSet(),
                                )
                            },
                            target = dropTarget,
                        ),
                    enabled = !turnActive,
                    placeholder = {
                        Text(
                            text = if (media.attachment != null) {
                                "첨부에 대해 물어보세요"
                            } else {
                                "무엇이든 요청하세요"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    maxLines = 5,
                    shape = CapsuleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (canSend) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSend()
                            }
                        },
                    ),
                )
                SendOrStopButton(
                    turnActive = turnActive,
                    canSend = canSend,
                    onSend = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSend()
                    },
                    onCancel = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCancel()
                    },
                )
            }
        }
    }
}

/**
 * Everything the composer needs to offer photo and voice input.
 *
 * Bundled into one parameter so the composer keeps a single media seam: adding a source later
 * changes this type rather than growing the composer's signature again.
 */
internal data class ComposerMediaState(
    /** The owner enabled media input, so the controls are shown at all. */
    val visible: Boolean,
    /** The controls are shown but currently refuse — a turn is running, or one is attached. */
    val enabled: Boolean,
    val attachment: PendingMediaAttachment?,
    val recording: VoiceRecordingUiState?,
    val onTakePhoto: () -> Unit,
    val onPickImage: () -> Unit,
    val onStartDictation: () -> Unit,
    val onStartVoiceAttachment: () -> Unit,
    val onStopRecording: () -> Unit,
    val onCancelRecording: () -> Unit,
    val onRemoveAttachment: () -> Unit,
)

private fun DragAndDropEvent.toComposerDropPayload(): ComposerDropPayload {
    val clipData = toAndroidDragEvent().clipData
    val item = clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)
    return ComposerDropPayload(
        mimeTypes = mimeTypes().toSet(),
        itemCount = clipData?.itemCount ?: 0,
        hasUri = item?.uri != null,
        hasIntent = item?.intent != null,
        text = item?.text?.toString(),
    )
}

@Composable
private fun ComposerNotice(block: ComposerBlock?, onOpenSettings: () -> Unit) {
    // Kept non-null for the exit animation, which still renders one frame after the block clears.
    val notice = block ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = notice.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (notice.opensSettings) {
            TextButton(onClick = onOpenSettings) {
                Text("설정 열기", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SendOrStopButton(
    turnActive: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = when {
            turnActive -> MaterialTheme.colorScheme.surfaceContainerHighest
            canSend -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = PersonalEdgeMotion.effects(),
        label = "send-container",
    )
    val content by animateColorAsState(
        targetValue = when {
            turnActive -> MaterialTheme.colorScheme.error
            canSend -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.outline
        },
        animationSpec = PersonalEdgeMotion.effects(),
        label = "send-content",
    )

    Surface(
        onClick = { if (turnActive) onCancel() else onSend() },
        enabled = turnActive || canSend,
        shape = CapsuleShape,
        color = container,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .size(46.dp)
            .semantics {
                contentDescription = if (turnActive) "응답 생성 취소" else "요청 보내기"
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Crossfade(targetState = turnActive, label = "send-icon") { stopping ->
                Icon(
                    painter = painterResource(
                        if (stopping) R.drawable.ic_stop else R.drawable.ic_arrow_up,
                    ),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(if (stopping) 20.dp else 22.dp),
                )
            }
        }
    }
}
