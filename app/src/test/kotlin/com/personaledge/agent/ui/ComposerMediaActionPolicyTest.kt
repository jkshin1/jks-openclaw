package com.personaledge.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerMediaActionPolicyTest {
    @Test
    fun `dictation and audio attachment are distinct visible accessible actions`() {
        val dictation = ComposerMediaAction.START_DICTATION
        val attachment = ComposerMediaAction.ATTACH_AUDIO

        assertTrue("받아쓰기" in dictation.menuLabel)
        assertTrue("받아쓰기" in dictation.contentDescription)
        assertTrue("첨부" in attachment.menuLabel)
        assertTrue("첨부" in attachment.contentDescription)
        assertTrue(dictation.menuLabel != attachment.menuLabel)
        assertTrue(dictation.contentDescription != attachment.contentDescription)
        assertEquals(4, ComposerMediaAction.entries.map { it.menuLabel }.distinct().size)
        assertEquals(4, ComposerMediaAction.entries.map { it.contentDescription }.distinct().size)
    }

    @Test
    fun `dictation is not buried in the attachment menu`() {
        // Both are voice actions with very different consequences: one edits the composer, the
        // other attaches a recording to the turn. Sharing one menu is how they get confused.
        assertEquals(
            listOf(
                ComposerMediaAction.TAKE_PHOTO,
                ComposerMediaAction.PICK_IMAGE,
                ComposerMediaAction.ATTACH_AUDIO,
            ),
            ComposerMediaAction.menuActions,
        )
        assertTrue(ComposerMediaAction.START_DICTATION !in ComposerMediaAction.menuActions)
    }

    @Test
    fun `every menu action carries its own icon and description`() {
        for (action in ComposerMediaAction.menuActions) {
            assertTrue(action.menuLabel.isNotBlank())
            assertTrue(action.contentDescription.isNotBlank())
            assertTrue(action.iconRes != 0)
        }
    }

}
