package com.personaledge.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptInputPolicyTest {
    @Test
    fun `over-limit edit stays visible and exposes a warning`() {
        val previous = "기존 초안"
        val oversized = "가".repeat(PromptInputPolicy.MAX_ACCEPTED_BYTES)
        val result = PromptInputPolicy.apply(
            currentPrompt = previous,
            candidate = oversized,
        )

        assertEquals(oversized, result.prompt)
        assertEquals(PromptInputPolicy.LIMIT_WARNING, result.warning)
    }

    @Test
    fun `edit beyond the ui-only cap preserves the previous draft and explains rejection`() {
        val previous = "기존 초안"
        val result = PromptInputPolicy.apply(
            currentPrompt = previous,
            candidate = "a".repeat(PromptInputPolicy.MAX_VISIBLE_DRAFT_BYTES + 1),
        )

        assertEquals(previous, result.prompt)
        assertEquals(PromptInputPolicy.VISIBLE_LIMIT_WARNING, result.warning)
    }

    @Test
    fun `valid edit at the utf8 boundary is accepted and clears the warning`() {
        val candidate = "가".repeat(PromptInputPolicy.MAX_ACCEPTED_BYTES / 3) + "a"
        assertEquals(
            PromptInputPolicy.MAX_ACCEPTED_BYTES,
            candidate.toByteArray(Charsets.UTF_8).size,
        )

        val result = PromptInputPolicy.apply(
            currentPrompt = "이전 초안",
            candidate = candidate,
        )

        assertEquals(candidate, result.prompt)
        assertNull(result.warning)
    }
}
