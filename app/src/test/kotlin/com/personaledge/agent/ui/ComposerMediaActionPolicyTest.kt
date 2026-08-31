package com.personaledge.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerMediaActionPolicyTest {
    @Test
    fun `dictation and audio attachment are distinct visible accessible actions`() {
        val dictation = ComposerMediaAction.START_DICTATION
        val attachment = ComposerMediaAction.ATTACH_AUDIO

        assertTrue("받아쓰기" in dictation.visibleLabel)
        assertTrue("받아쓰기" in dictation.contentDescription)
        assertTrue("첨부" in attachment.visibleLabel)
        assertTrue("첨부" in attachment.contentDescription)
        assertTrue(dictation.visibleLabel != attachment.visibleLabel)
        assertTrue(dictation.contentDescription != attachment.contentDescription)
        assertEquals(4, ComposerMediaAction.entries.map { it.visibleLabel }.distinct().size)
        assertEquals(4, ComposerMediaAction.entries.map { it.contentDescription }.distinct().size)
    }

    @Test
    fun `fold cover width keeps all four actions in one compact row`() {
        assertEquals(4, ComposerMediaActionLayoutPolicy.columnsForWidth(320f))
        assertEquals(4, ComposerMediaActionLayoutPolicy.columnsForWidth(400f))
        assertEquals(
            4,
            ComposerMediaActionLayoutPolicy.columnsForWidth(
                ComposerMediaActionLayoutPolicy.FOUR_COLUMN_MIN_WIDTH_DP,
            ),
        )
    }

    @Test
    fun `narrower panes wrap actions instead of crushing their labels`() {
        assertEquals(
            2,
            ComposerMediaActionLayoutPolicy.columnsForWidth(
                ComposerMediaActionLayoutPolicy.FOUR_COLUMN_MIN_WIDTH_DP - 1f,
            ),
        )
    }
}
