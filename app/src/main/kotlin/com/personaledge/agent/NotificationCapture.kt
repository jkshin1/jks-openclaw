package com.personaledge.agent

import com.personaledge.core.data.CapturedNotificationDraft

/**
 * One posted notification, reduced to plain values.
 *
 * The listener converts `StatusBarNotification` into this so the capture rules can be tested
 * without the framework. Nothing here holds an Android object, and the raw `extras` bundle is
 * never carried further than the conversion.
 */
data class NotificationPost(
    val packageName: String,
    val sourceKey: String,
    val postedAtEpochMillis: Long,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean,
    val title: String?,
    val text: String?,
    val conversationTitle: String?,
    /** Newest MessagingStyle entry, when the notification used one. */
    val messagingSender: String?,
    val messagingText: String?,
)

/**
 * Decides what, if anything, to store from a notification.
 *
 * Deliberately conservative. A notification is third-party text that will later be read back into
 * a model prompt, so anything ambiguous is dropped rather than guessed at:
 *
 * - Only allowlisted packages are considered at all.
 * - Group summaries ("3 new messages") and ongoing/foreground notifications carry no conversation
 *   content and would otherwise overwrite real messages.
 * - The sender is taken only from MessagingStyle, where the platform states it. KakaoTalk puts the
 *   room name in the title for group chats and the sender's name for one-to-one chats, and
 *   guessing between them would attribute messages to the wrong person.
 */
object NotificationCapture {
    const val KAKAO_TALK_PACKAGE = "com.kakao.talk"

    val DEFAULT_ALLOWED_PACKAGES = setOf(KAKAO_TALK_PACKAGE)

    fun extract(
        post: NotificationPost,
        allowedPackages: Set<String> = DEFAULT_ALLOWED_PACKAGES,
    ): CapturedNotificationDraft? {
        if (post.packageName !in allowedPackages) return null
        if (post.isGroupSummary || post.isOngoing) return null
        if (post.sourceKey.isBlank()) return null
        if (post.postedAtEpochMillis <= 0) return null

        val messagingText = post.messagingText.cleaned()
        val fallbackText = post.text.cleaned()
        val text = messagingText ?: fallbackText ?: return null

        // With MessagingStyle the room name is explicit; otherwise the title is all there is, and
        // whether it names a room or a person is genuinely unknown.
        val room = if (messagingText != null) {
            post.conversationTitle.cleaned() ?: post.title.cleaned()
        } else {
            post.title.cleaned()
        }
        val sender = if (messagingText != null) post.messagingSender.cleaned() else null

        return CapturedNotificationDraft(
            sourceKey = post.sourceKey,
            packageName = post.packageName,
            conversationTitle = room.orEmpty(),
            sender = sender.orEmpty(),
            text = text,
            postedAtEpochMillis = post.postedAtEpochMillis,
        )
    }

    /**
     * Notification text reaches both a Gemma prompt and the user's screen, so it is neutralized
     * here rather than downstream.
     *
     * Delimiters are replaced with a space rather than deleted: removing them could join two
     * fragments into a token that was not in the original. Bidi overrides and other invisible
     * formatting are dropped outright, since text that renders differently from what it contains
     * is exactly what a confirmation preview must not show.
     */
    private fun String?.cleaned(): String? = this
        ?.replace(MODEL_CONTROL_TOKEN_OPEN, " ")
        ?.replace(MODEL_CONTROL_TOKEN_CLOSE, " ")
        ?.filterNot { character -> character.isISOControl() || character.isInvisibleFormatting() }
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun Char.isInvisibleFormatting(): Boolean = when (Character.getType(this).toByte()) {
        Character.FORMAT,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        -> true
        else -> false
    }

    private const val MODEL_CONTROL_TOKEN_OPEN = "<|"
    private const val MODEL_CONTROL_TOKEN_CLOSE = "|>"
}
