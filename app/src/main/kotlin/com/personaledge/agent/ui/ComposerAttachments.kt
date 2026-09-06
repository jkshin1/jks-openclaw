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
import androidx.compose.foundation.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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
 * The attachment entry point, benchmarked on the ChatGPT and Claude Android composers.
 *
 * A single "+" inside the prompt row rather than a row of labelled buttons above it: the previous
 * four-button row cost a whole line of vertical space on every turn to expose actions that are
 * used occasionally. Dictation keeps its own microphone next to send, because it is the one voice
 * action reached constantly and because it must stay visibly distinct from attaching a recording
 * — those two do very different things with what the owner says.
 */
@Composable
internal fun ComposerAttachMenu(
    enabled: Boolean,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    onAttachVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val callbacks = mapOf(
        ComposerMediaAction.TAKE_PHOTO to onTakePhoto,
        ComposerMediaAction.PICK_IMAGE to onPickImage,
        ComposerMediaAction.ATTACH_AUDIO to onAttachVoice,
    )
    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            enabled = enabled,
            shape = CapsuleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .size(42.dp)
                .semantics { contentDescription = ATTACH_MENU_DESCRIPTION },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ComposerMediaAction.menuActions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.menuLabel) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(action.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        expanded = false
                        checkNotNull(callbacks[action]).invoke()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = action.contentDescription
                    },
                )
            }
        }
    }
}

/** Dictation, kept as its own control beside send exactly as the benchmarked apps place it. */
@Composable
internal fun ComposerDictationButton(
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
            .size(42.dp)
            .semantics {
                contentDescription = ComposerMediaAction.START_DICTATION.contentDescription
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_microphone),
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

internal const val ATTACH_MENU_DESCRIPTION = "사진과 음성 첨부 메뉴 열기"

internal enum class ComposerMediaAction(
    val menuLabel: String,
    val contentDescription: String,
    val iconRes: Int,
) {
    TAKE_PHOTO(
        menuLabel = "사진 촬영",
        contentDescription = "카메라로 사진 촬영해서 첨부",
        iconRes = R.drawable.ic_camera,
    ),
    PICK_IMAGE(
        menuLabel = "사진 선택",
        contentDescription = "기기에서 사진 선택해서 첨부",
        iconRes = R.drawable.ic_image,
    ),
    START_DICTATION(
        menuLabel = "받아쓰기",
        contentDescription = "음성을 텍스트로 받아쓰기 시작",
        iconRes = R.drawable.ic_microphone,
    ),
    ATTACH_AUDIO(
        menuLabel = "음성 첨부",
        contentDescription = "음성을 녹음해서 대화에 첨부",
        iconRes = R.drawable.ic_microphone,
    ),
    ;

    companion object {
        /**
         * What the "+" menu offers.
         *
         * Dictation is deliberately absent: it has its own button, and burying it in the same
         * menu as "음성 첨부" is exactly how an owner ends up attaching a recording when they
         * meant to dictate.
         */
        val menuActions: List<ComposerMediaAction> = listOf(TAKE_PHOTO, PICK_IMAGE, ATTACH_AUDIO)
    }
}

/**
 * The staged attachment, shown above the prompt field until it is sent or removed.
 *
 * A photo shows its own bounded thumbnail, the way the benchmarked apps do, because "which photo
 * did I attach" is otherwise unanswerable without sending it. The thumbnail is a small separate
 * derivative; the turn payload itself still never enters UI state. Audio has nothing to show, so
 * it keeps the labelled chip.
 */
@Composable
internal fun ComposerAttachmentChip(
    attachment: PendingMediaAttachment,
    preview: ImageBitmap?,
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
            if (preview != null && attachment.kind == TurnMediaKind.IMAGE) {
                Image(
                    bitmap = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.small),
                )
            } else {
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
            }
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
