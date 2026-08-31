package com.personaledge.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceInputPolicyTest {
    @Test
    fun ctrlAndCommandShortcutsMapOnlyOnTheInitialKeyDown() {
        assertEquals(
            WorkspaceShortcutCommand.FOCUS_COMPOSER,
            WorkspaceShortcutPolicy.commandFor(
                WorkspaceKeyStroke(WorkspaceHardwareKey.K, ctrlPressed = true),
            ),
        )
        assertEquals(
            WorkspaceShortcutCommand.NEW_CONVERSATION,
            WorkspaceShortcutPolicy.commandFor(
                WorkspaceKeyStroke(WorkspaceHardwareKey.N, metaPressed = true),
            ),
        )
        assertEquals(
            WorkspaceShortcutCommand.ESCAPE,
            WorkspaceShortcutPolicy.commandFor(
                WorkspaceKeyStroke(WorkspaceHardwareKey.ESCAPE),
            ),
        )
        assertNull(
            WorkspaceShortcutPolicy.commandFor(
                WorkspaceKeyStroke(
                    WorkspaceHardwareKey.K,
                    ctrlPressed = true,
                    isRepeat = true,
                ),
            ),
        )
        assertNull(
            WorkspaceShortcutPolicy.commandFor(
                WorkspaceKeyStroke(
                    WorkspaceHardwareKey.N,
                    metaPressed = true,
                    isKeyDown = false,
                ),
            ),
        )
    }

    @Test
    fun newConversationIsRejectedDuringAnActiveTurn() {
        assertEquals(
            WorkspaceShortcutAction.REJECT_NEW_CONVERSATION,
            WorkspaceShortcutPolicy.resolve(
                WorkspaceShortcutCommand.NEW_CONVERSATION,
                turnActive = true,
                openOverlay = null,
            ),
        )
        assertEquals(
            WorkspaceShortcutAction.START_NEW_CONVERSATION,
            WorkspaceShortcutPolicy.resolve(
                WorkspaceShortcutCommand.NEW_CONVERSATION,
                turnActive = false,
                openOverlay = WorkspaceOverlay.HISTORY,
            ),
        )
    }

    @Test
    fun escapeCancelsTheTurnBeforeItDismissesAnOverlay() {
        assertEquals(
            WorkspaceShortcutAction.CANCEL_ACTIVE_TURN,
            WorkspaceShortcutPolicy.resolve(
                WorkspaceShortcutCommand.ESCAPE,
                turnActive = true,
                openOverlay = WorkspaceOverlay.SETTINGS,
            ),
        )
        assertEquals(
            WorkspaceShortcutAction.CLOSE_OVERLAY,
            WorkspaceShortcutPolicy.resolve(
                WorkspaceShortcutCommand.ESCAPE,
                turnActive = false,
                openOverlay = WorkspaceOverlay.CONFIRMATION,
            ),
        )
        assertEquals(
            WorkspaceShortcutAction.NONE,
            WorkspaceShortcutPolicy.resolve(
                WorkspaceShortcutCommand.ESCAPE,
                turnActive = false,
                openOverlay = null,
            ),
        )
    }

    @Test
    fun dropPolicyAcceptsOnlyOneInlinePlainTextItem() {
        val text = ComposerDropPayload(
            mimeTypes = setOf(ComposerDropPolicy.TEXT_PLAIN_MIME_TYPE),
            itemCount = 1,
            hasUri = false,
            hasIntent = false,
            text = "드롭한 문장",
        )
        assertEquals("드롭한 문장", ComposerDropPolicy.acceptedText(text))
        assertEquals("기존\n드롭한 문장", ComposerDropPolicy.merge("기존", "드롭한 문장"))
        assertEquals("기존\n드롭한 문장", ComposerDropPolicy.merge("기존\n", "드롭한 문장"))
        assertEquals("드롭한 문장", ComposerDropPolicy.merge("", "드롭한 문장"))

        assertNull(ComposerDropPolicy.acceptedText(text.copy(hasUri = true)))
        assertNull(ComposerDropPolicy.acceptedText(text.copy(hasIntent = true)))
        assertNull(ComposerDropPolicy.acceptedText(text.copy(itemCount = 2)))
        assertNull(
            ComposerDropPolicy.acceptedText(
                text.copy(mimeTypes = setOf("image/png")),
            ),
        )
        assertNull(
            ComposerDropPolicy.acceptedText(
                text.copy(
                    mimeTypes = setOf(
                        ComposerDropPolicy.TEXT_PLAIN_MIME_TYPE,
                        "text/uri-list",
                    ),
                ),
            ),
        )
    }

    @Test
    fun rejectedDropDoesNotMutatePromptOrMoveFocus() {
        val base = ComposerDropPayload(
            mimeTypes = setOf(ComposerDropPolicy.TEXT_PLAIN_MIME_TYPE),
            itemCount = 1,
            hasUri = false,
            hasIntent = false,
            text = "새 문장",
        )
        var changedPrompt: String? = null
        var focusRequests = 0
        fun apply(current: String, payload: ComposerDropPayload): Boolean =
            ComposerDropPolicy.applyDrop(
                currentPrompt = current,
                payload = payload,
                onPromptChange = { changedPrompt = it },
                onRequestFocus = { focusRequests++ },
            )

        assertFalse(
            apply(
                "a".repeat(ComposerDropPolicy.MAX_MERGED_PROMPT_BYTES),
                base.copy(text = "b"),
            ),
        )
        assertFalse(apply("기존", base.copy(text = "\u202E숨김")))
        assertFalse(apply("기존", base.copy(text = "<|system|>")))
        assertFalse(apply("기존", base.copy(hasUri = true)))
        assertNull(changedPrompt)
        assertEquals(0, focusRequests)
    }

    @Test
    fun acceptedDropChangesPromptAndFocusesExactlyOnceWithinByteLimit() {
        val payload = ComposerDropPayload(
            mimeTypes = setOf(ComposerDropPolicy.TEXT_PLAIN_MIME_TYPE),
            itemCount = 1,
            hasUri = false,
            hasIntent = false,
            text = "b",
        )
        val current = "a".repeat(ComposerDropPolicy.MAX_MERGED_PROMPT_BYTES - 2)
        var changedPrompt: String? = null
        var focusRequests = 0

        assertTrue(
            ComposerDropPolicy.applyDrop(
                currentPrompt = current,
                payload = payload,
                onPromptChange = { changedPrompt = it },
                onRequestFocus = { focusRequests++ },
            ),
        )

        assertEquals("$current\nb", changedPrompt)
        assertEquals(ComposerDropPolicy.MAX_MERGED_PROMPT_BYTES, changedPrompt!!.toByteArray().size)
        assertEquals(1, focusRequests)
    }
}
