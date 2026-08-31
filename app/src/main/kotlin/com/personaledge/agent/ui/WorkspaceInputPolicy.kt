package com.personaledge.agent.ui

import com.personaledge.agent.PromptInputPolicy

internal enum class WorkspaceHardwareKey {
    K,
    N,
    ESCAPE,
    OTHER,
}

internal data class WorkspaceKeyStroke(
    val key: WorkspaceHardwareKey,
    val ctrlPressed: Boolean = false,
    val metaPressed: Boolean = false,
    val isKeyDown: Boolean = true,
    val isRepeat: Boolean = false,
)

internal enum class WorkspaceShortcutCommand {
    FOCUS_COMPOSER,
    NEW_CONVERSATION,
    ESCAPE,
}

internal enum class WorkspaceOverlay {
    SETTINGS,
    REMINDERS,
    HISTORY,
    CONFIRMATION,
}

internal enum class WorkspaceShortcutAction {
    FOCUS_COMPOSER,
    START_NEW_CONVERSATION,
    REJECT_NEW_CONVERSATION,
    CANCEL_ACTIVE_TURN,
    CLOSE_OVERLAY,
    NONE,
}

internal object WorkspaceShortcutPolicy {
    fun commandFor(stroke: WorkspaceKeyStroke): WorkspaceShortcutCommand? {
        if (!stroke.isKeyDown || stroke.isRepeat) return null
        val commandModifier = stroke.ctrlPressed || stroke.metaPressed
        return when {
            commandModifier && stroke.key == WorkspaceHardwareKey.K ->
                WorkspaceShortcutCommand.FOCUS_COMPOSER
            commandModifier && stroke.key == WorkspaceHardwareKey.N ->
                WorkspaceShortcutCommand.NEW_CONVERSATION
            !commandModifier && stroke.key == WorkspaceHardwareKey.ESCAPE ->
                WorkspaceShortcutCommand.ESCAPE
            else -> null
        }
    }

    fun resolve(
        command: WorkspaceShortcutCommand,
        turnActive: Boolean,
        openOverlay: WorkspaceOverlay?,
    ): WorkspaceShortcutAction = when (command) {
        WorkspaceShortcutCommand.FOCUS_COMPOSER ->
            WorkspaceShortcutAction.FOCUS_COMPOSER
        WorkspaceShortcutCommand.NEW_CONVERSATION -> if (turnActive) {
            WorkspaceShortcutAction.REJECT_NEW_CONVERSATION
        } else {
            WorkspaceShortcutAction.START_NEW_CONVERSATION
        }
        WorkspaceShortcutCommand.ESCAPE -> when {
            turnActive -> WorkspaceShortcutAction.CANCEL_ACTIVE_TURN
            openOverlay != null -> WorkspaceShortcutAction.CLOSE_OVERLAY
            else -> WorkspaceShortcutAction.NONE
        }
    }
}

/** Primitive representation of a single dropped item; it never carries a URI or permission. */
internal data class ComposerDropPayload(
    val mimeTypes: Set<String>,
    val itemCount: Int,
    val hasUri: Boolean,
    val hasIntent: Boolean,
    val text: String?,
)

internal object ComposerDropPolicy {
    const val TEXT_PLAIN_MIME_TYPE = "text/plain"
    const val PROMPT_SEPARATOR = "\n"
    const val MAX_MERGED_PROMPT_BYTES = PromptInputPolicy.MAX_ACCEPTED_BYTES

    fun acceptsMimeTypes(mimeTypes: Set<String>): Boolean =
        mimeTypes == setOf(TEXT_PLAIN_MIME_TYPE)

    fun acceptedText(payload: ComposerDropPayload): String? = payload.text?.takeIf { text ->
        acceptsMimeTypes(payload.mimeTypes) &&
            payload.itemCount == 1 &&
            !payload.hasUri &&
            !payload.hasIntent &&
            text.isNotEmpty() &&
            isSafePromptText(text)
    }

    fun merge(currentPrompt: String, droppedText: String): String = when {
        currentPrompt.isEmpty() -> droppedText
        currentPrompt.endsWith(PROMPT_SEPARATOR) || droppedText.startsWith(PROMPT_SEPARATOR) ->
            currentPrompt + droppedText
        else -> currentPrompt + PROMPT_SEPARATOR + droppedText
    }

    fun mergedPromptOrNull(currentPrompt: String, payload: ComposerDropPayload): String? {
        val droppedText = acceptedText(payload) ?: return null
        val merged = merge(currentPrompt, droppedText)
        return merged.takeIf { candidate ->
            candidate.toByteArray(Charsets.UTF_8).size <= MAX_MERGED_PROMPT_BYTES &&
                isSafePromptText(candidate)
        }
    }

    /** Runs both UI callbacks only after the complete merged prompt passes the closed policy. */
    fun applyDrop(
        currentPrompt: String,
        payload: ComposerDropPayload,
        onPromptChange: (String) -> Unit,
        onRequestFocus: () -> Unit,
    ): Boolean {
        val merged = mergedPromptOrNull(currentPrompt, payload) ?: return false
        onPromptChange(merged)
        onRequestFocus()
        return true
    }

    private fun isSafePromptText(value: String): Boolean {
        if (value.contains("<|") || value.contains("|>")) return false
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            val allowedWhitespace = codePoint == '\n'.code ||
                codePoint == '\r'.code ||
                codePoint == '\t'.code
            if (Character.isISOControl(codePoint) && !allowedWhitespace) return false
            when (Character.getType(codePoint)) {
                Character.FORMAT.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(),
                Character.SURROGATE.toInt(),
                -> return false
            }
        }
        return true
    }
}
