package com.personaledge.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.personaledge.agent.ActiveReasoningUiState
import com.personaledge.agent.ChatEntry
import com.personaledge.agent.ChatRecoveryAction
import com.personaledge.agent.ChatRole
import com.personaledge.agent.R
import com.personaledge.agent.ui.components.AccentIcon
import com.personaledge.agent.ui.theme.CapsuleShape
import com.personaledge.agent.ui.theme.SquircleShape

private val BubbleRadius = CornerSize(20.dp)
private val BubbleTail = CornerSize(7.dp)

private val UserBubbleShape = SquircleShape(
    topStart = BubbleRadius,
    topEnd = BubbleRadius,
    bottomEnd = BubbleTail,
    bottomStart = BubbleRadius,
)

private val AgentBubbleShape = SquircleShape(
    topStart = BubbleRadius,
    topEnd = BubbleRadius,
    bottomEnd = BubbleRadius,
    bottomStart = BubbleTail,
)

/** Pure decisions shared by rendering and local JVM accessibility/performance tests. */
internal object ChatPresentationPolicy {
    const val USER_MESSAGE_DESCRIPTION = "사용자 메시지"
    const val ASSISTANT_MESSAGE_DESCRIPTION = "Personal Edge 응답"
    const val TYPING_DESCRIPTION = "Personal Edge가 생각하고 답변을 준비하는 중"
    const val THINKING_TITLE = "생각 중"
    const val THINKING_WAITING_TEXT = "추론 내용을 기다리는 중입니다."
    const val STREAM_SCROLL_CODE_POINT_STEP = 32

    fun bubbleRoleDescription(role: ChatRole): String? = when (role) {
        ChatRole.USER -> USER_MESSAGE_DESCRIPTION
        ChatRole.ASSISTANT -> ASSISTANT_MESSAGE_DESCRIPTION
        ChatRole.TOOL, ChatRole.STATUS -> null
    }

    fun isStreamingAssistant(role: ChatRole, isLatest: Boolean, turnActive: Boolean): Boolean =
        role == ChatRole.ASSISTANT && isLatest && turnActive

    fun isThinkingAssistant(
        role: ChatRole,
        text: String,
        isLatest: Boolean,
        turnActive: Boolean,
    ): Boolean = text.isEmpty() && isStreamingAssistant(role, isLatest, turnActive)

    fun shouldRenderRichText(streaming: Boolean): Boolean = !streaming

    fun reasoningTextForAssistant(
        assistantEntryId: String,
        reasoningAssistantEntryId: String?,
        reasoningText: String?,
    ): String? = reasoningText?.takeIf { assistantEntryId == reasoningAssistantEntryId }

    fun displayedThinkingText(reasoningText: String?): String =
        reasoningText?.takeUnless(String::isEmpty) ?: THINKING_WAITING_TEXT

    fun streamingTextForAutoScroll(
        assistantEntryId: String?,
        assistantText: String,
        reasoningAssistantEntryId: String?,
        reasoningText: String?,
    ): String = if (
        assistantText.isEmpty() &&
        assistantEntryId != null &&
        assistantEntryId == reasoningAssistantEntryId
    ) {
        reasoningText.orEmpty()
    } else {
        assistantText
    }

    fun autoScrollRevision(text: String): Int =
        text.codePointCount(0, text.length) / STREAM_SCROLL_CODE_POINT_STEP
}

/**
 * The transcript.
 *
 * Roles are told apart by placement and surface rather than by a printed "User"/"Assistant" label
 * on every bubble, which is both quieter and closer to how a messaging app reads. The two roles
 * that are *not* conversation keep their labels: a Tool receipt is app-authored evidence of a real
 * side effect, and a status line is the runtime speaking, so both stay explicitly marked.
 */
@Composable
internal fun ChatTranscript(
    messages: List<ChatEntry>,
    listState: LazyListState,
    turnActive: Boolean,
    activeReasoning: ActiveReasoningUiState?,
    suggestions: List<PromptSuggestion>,
    onSuggestion: (PromptSuggestion) -> Unit,
    onRecoveryAction: (ChatRecoveryAction) -> Unit = {},
    recoveryActionsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var thinkingExpanded by rememberSaveable(activeReasoning?.turnId?.value) {
        mutableStateOf(false)
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // A bubble hugs its text and stops at this ceiling, so a two-word reply stays small while
        // a long one still leaves the opposite margin visible and the sides readable.
        val maxBubbleWidth = (maxWidth - HorizontalGutter * 2) * 0.88f
        // App-authored STATUS rows may be inserted after the active blank assistant (for example,
        // a recovery or recalled-memory notice). They must not hide the thinking disclosure.
        val latestAssistantId = messages.lastOrNull { entry ->
            entry.role == ChatRole.ASSISTANT
        }?.id

        LazyColumn(
            state = listState,
            // Full height, not wrap-content: the bottom-anchored arrangement below only has
            // somewhere to push a short thread if the list actually owns the free space.
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = HorizontalGutter,
                end = HorizontalGutter,
                top = 10.dp,
                bottom = 14.dp,
            ),
            // Bottom-anchored: a two-message thread rests on the composer instead of
            // floating under the app bar with a screen of empty space beneath it.
            verticalArrangement = Arrangement.spacedBy(9.dp, Alignment.Bottom),
        ) {
            if (messages.isEmpty()) {
                item(key = "empty-state") {
                    Box(
                        modifier = Modifier.fillParentMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyTranscript(
                            suggestions = suggestions,
                            onSuggestion = onSuggestion,
                        )
                    }
                }
            }

            items(messages, key = ChatEntry::id) { entry ->
                when (entry.role) {
                    ChatRole.USER -> UserBubble(
                        text = entry.text,
                        attachmentLabel = entry.attachmentLabel,
                        maxWidth = maxBubbleWidth,
                    )
                    ChatRole.ASSISTANT -> {
                        val streaming = ChatPresentationPolicy.isStreamingAssistant(
                            role = entry.role,
                            isLatest = entry.id == latestAssistantId,
                            turnActive = turnActive,
                        )
                        if (
                            ChatPresentationPolicy.isThinkingAssistant(
                                role = entry.role,
                                text = entry.text,
                                isLatest = entry.id == latestAssistantId,
                                turnActive = turnActive,
                            )
                        ) {
                            ThinkingDisclosure(
                                reasoningText = ChatPresentationPolicy.reasoningTextForAssistant(
                                    assistantEntryId = entry.id,
                                    reasoningAssistantEntryId = activeReasoning?.assistantEntryId,
                                    reasoningText = activeReasoning?.text,
                                ),
                                expanded = thinkingExpanded,
                                onExpandedChange = { thinkingExpanded = it },
                                maxWidth = maxBubbleWidth,
                            )
                        } else {
                            AgentBubble(
                                text = entry.text,
                                streaming = streaming,
                                maxWidth = maxBubbleWidth,
                            )
                        }
                    }
                    ChatRole.TOOL -> ToolReceipt(entry.text)
                    ChatRole.STATUS -> StatusLine(
                        text = entry.text,
                        recoveryAction = entry.recoveryAction,
                        actionEnabled = recoveryActionsEnabled,
                        onRecoveryAction = onRecoveryAction,
                    )
                }
            }
        }
    }
}

private val HorizontalGutter = 16.dp

@Composable
private fun UserBubble(text: String, attachmentLabel: String?, maxWidth: Dp) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = UserBubbleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .semantics(mergeDescendants = true) {
                    contentDescription = checkNotNull(
                        ChatPresentationPolicy.bubbleRoleDescription(ChatRole.USER),
                    )
                },
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (attachmentLabel != null) {
                    // An app-authored caption, never a thumbnail: the media is gone once the turn
                    // ends, so the transcript records that it was there and nothing more.
                    Text(
                        text = attachmentLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = if (text.isEmpty()) 0.dp else 4.dp),
                    )
                }
                if (text.isNotEmpty()) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentBubble(text: String, streaming: Boolean, maxWidth: Dp) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = AgentBubbleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .semantics(mergeDescendants = true) {
                    contentDescription = checkNotNull(
                        ChatPresentationPolicy.bubbleRoleDescription(ChatRole.ASSISTANT),
                    )
                },
        ) {
            if (ChatPresentationPolicy.shouldRenderRichText(streaming)) {
                AssistantRichText(
                    text = text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            } else {
                Text(
                    // Rich parsing is deferred until the active answer is final. Re-parsing
                    // incomplete Markdown/LaTeX for every token wastes sustained-decode budget.
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * A user-controlled disclosure for the active pre-answer phase.
 *
 * The active model thought channel is rendered verbatim and only while its assistant placeholder
 * is active. It is intentionally plain text: rendering must not interpret links or Markdown, and
 * the disclosure itself does not copy the stream into transcript or saved UI state.
 */
@Composable
private fun ThinkingDisclosure(
    reasoningText: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    maxWidth: Dp,
) {
    val displayedText = ChatPresentationPolicy.displayedThinkingText(reasoningText)
    val reasoningScrollState = rememberScrollState()
    LaunchedEffect(expanded, reasoningScrollState) {
        if (expanded) {
            // The stream remains tail-following while it grows. Once generation stops, the user
            // can freely inspect earlier lines in this bounded inner scroll area.
            snapshotFlow { reasoningScrollState.maxValue }.collect { maximum ->
                reasoningScrollState.scrollTo(maximum)
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            onClick = { onExpandedChange(!expanded) },
            shape = AgentBubbleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .semantics(mergeDescendants = true) {
                    // Do not mark the token stream as a live region: doing so would repeatedly
                    // announce the entire growing thought. It remains readable when focused.
                    contentDescription = ChatPresentationPolicy.TYPING_DESCRIPTION
                    stateDescription = if (expanded) "펼쳐짐" else "접힘"
                },
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TypingDots(announceForAccessibility = false)
                    Text(
                        text = ChatPresentationPolicy.THINKING_TITLE,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(
                        painter = painterResource(
                            if (expanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down,
                        ),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Text(
                        text = displayedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                            .heightIn(max = 280.dp)
                            .verticalScroll(reasoningScrollState),
                    )
                }
            }
        }
    }
}

/**
 * A Tool receipt is not model text: it is written by the app after the side effect resolved, so it
 * is drawn as evidence — its own surface, its own accent, and a label that says where it came from.
 */
@Composable
private fun ToolReceipt(text: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AccentIcon(
                iconRes = R.drawable.ic_shield,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                container = Color.Transparent,
                size = 22.dp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "Tool 실행 결과 · 앱이 작성",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(
    text: String,
    recoveryAction: ChatRecoveryAction?,
    actionEnabled: Boolean,
    onRecoveryAction: (ChatRecoveryAction) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            shape = CapsuleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (recoveryAction != null) {
                    TextButton(
                        onClick = { onRecoveryAction(recoveryAction) },
                        enabled = actionEnabled,
                    ) {
                        Text(recoveryAction.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingDots(
    modifier: Modifier = Modifier,
    announceForAccessibility: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "typing")
    val accessibilityModifier = if (announceForAccessibility) {
        Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = ChatPresentationPolicy.TYPING_DESCRIPTION
        }
    } else {
        Modifier
    }
    Row(
        modifier = modifier.then(accessibilityModifier),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.28f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, delayMillis = index * 160),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "typing-dot-$index",
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CapsuleShape),
            )
        }
    }
}

@Composable
private fun EmptyTranscript(
    suggestions: List<PromptSuggestion>,
    onSuggestion: (PromptSuggestion) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AccentIcon(
            iconRes = R.drawable.ic_sparkle,
            tint = MaterialTheme.colorScheme.primary,
            container = MaterialTheme.colorScheme.primaryContainer,
            size = 58.dp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "무엇을 도와드릴까요?",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "조회는 바로 실행하고, 일정·알람 생성처럼 상태를 바꾸는 작업은 먼저 확인합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        suggestions.forEach { suggestion ->
            SuggestionCard(suggestion = suggestion, onClick = { onSuggestion(suggestion) })
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: PromptSuggestion, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccentIcon(
                iconRes = suggestionIcon(suggestion.kind),
                tint = MaterialTheme.colorScheme.primary,
                container = MaterialTheme.colorScheme.primaryContainer,
                size = 30.dp,
            )
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = suggestion.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = suggestion.prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

internal fun suggestionIcon(kind: SuggestionKind): Int = when (kind) {
    SuggestionKind.CALENDAR -> R.drawable.ic_calendar
    SuggestionKind.ALARM -> R.drawable.ic_bell
    SuggestionKind.ROUTE -> R.drawable.ic_route
    SuggestionKind.SEARCH -> R.drawable.ic_globe
}
