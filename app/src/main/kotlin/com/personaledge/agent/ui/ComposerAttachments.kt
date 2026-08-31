package com.personaledge.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personaledge.agent.PendingMediaAttachment
import com.personaledge.agent.R
import com.personaledge.agent.VoiceCapturePolicy
import com.personaledge.agent.VoiceRecordingUiState
import com.personaledge.agent.ui.theme.CapsuleShape
import com.personaledge.core.llm.TurnMediaKind

/**
 * The attachment controls in their own row above the prompt field.
 *
 * Four visible labels rather than a "+" menu or two indistinguishable microphone icons: the owner
 * chooses before speaking whether audio becomes editable dictation or stays an attachment. The
 * row remains above the text field so four actions do not squeeze the editor on the Fold cover.
 */
@Composable
internal fun ComposerMediaActions(
    enabled: Boolean,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    onStartDictation: () -> Unit,
    onStartVoiceAttachment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val callbacks = mapOf(
        ComposerMediaAction.TAKE_PHOTO to onTakePhoto,
        ComposerMediaAction.PICK_IMAGE to onPickImage,
        ComposerMediaAction.START_DICTATION to onStartDictation,
        ComposerMediaAction.ATTACH_AUDIO to onStartVoiceAttachment,
    )
    BoxWithConstraints(modifier = modifier) {
        val columns = ComposerMediaActionLayoutPolicy.columnsForWidth(maxWidth.value)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ComposerMediaAction.entries.chunked(columns).forEach { actions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions.forEach { action ->
                        MediaActionButton(
                            action = action,
                            enabled = enabled,
                            onClick = checkNotNull(callbacks[action]),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - actions.size) {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaActionButton(
    action: ComposerMediaAction,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CapsuleShape,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = action.contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = action.visibleLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline
                },
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

internal enum class ComposerMediaAction(
    val visibleLabel: String,
    val contentDescription: String,
) {
    TAKE_PHOTO(
        visibleLabel = "촬영",
        contentDescription = "카메라로 사진 촬영해서 첨부",
    ),
    PICK_IMAGE(
        visibleLabel = "사진",
        contentDescription = "기기에서 사진 선택해서 첨부",
    ),
    START_DICTATION(
        visibleLabel = "받아쓰기",
        contentDescription = "음성을 텍스트로 받아쓰기 시작",
    ),
    ATTACH_AUDIO(
        visibleLabel = "음성 첨부",
        contentDescription = "음성을 녹음해서 대화에 첨부",
    ),
}

internal object ComposerMediaActionLayoutPolicy {
    /** Four 64 dp actions plus three 4 dp gaps, measured after the composer's outer padding. */
    const val FOUR_COLUMN_MIN_WIDTH_DP = 268f

    fun columnsForWidth(availableWidthDp: Float): Int =
        if (availableWidthDp >= FOUR_COLUMN_MIN_WIDTH_DP) 4 else 2
}

/**
 * The staged attachment, shown above the prompt field until it is sent or removed.
 *
 * A label rather than a thumbnail. The payload never enters UI state — that is what keeps a photo
 * out of state snapshots and recomposition traces — so the composer describes the attachment
 * instead of re-decoding it for a preview.
 */
@Composable
internal fun ComposerAttachmentChip(
    attachment: PendingMediaAttachment,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 12.dp, top = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    when (attachment.kind) {
                        TurnMediaKind.IMAGE -> R.drawable.ic_image
                        TurnMediaKind.AUDIO -> R.drawable.ic_microphone
                    },
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = attachment.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            )
            TextButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = "첨부 제거" },
            ) {
                Text("제거", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * The live recording row.
 *
 * Elapsed and remaining time are both shown because the clip has a hard ceiling: the owner needs
 * to know they are running out of room before the recorder stops on its own.
 */
@Composable
internal fun ComposerRecordingBar(
    recording: VoiceRecordingUiState,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 12.dp, top = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_microphone),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = buildString {
                    append(if (recording.dictation) "받아쓰는 중" else "녹음 중")
                    append(" · ")
                    append(seconds(recording.elapsedMillis))
                    append("초 / ")
                    append(seconds(VoiceCapturePolicy.MAX_MILLIS))
                    append("초")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
                    .semantics {
                        contentDescription = "녹음 중, 남은 시간 " +
                            "${seconds(recording.remainingMillis)}초"
                    },
            )
            TextButton(onClick = onCancel) {
                Text("취소", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onStop) {
                Text("완료", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun seconds(millis: Int): Int = millis / 1_000
