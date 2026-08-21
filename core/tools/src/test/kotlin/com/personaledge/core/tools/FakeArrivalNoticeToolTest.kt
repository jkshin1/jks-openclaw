package com.personaledge.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeArrivalNoticeToolTest {
    private class ProcessLedger : ActionLedger {
        private val claims = mutableSetOf<String>()
        override suspend fun claim(idempotencyKey: String): Boolean = claims.add(idempotencyKey)
    }

    @Test
    fun `fake tool is read-only but still requires confirmation`() = runBlocking {
        val tool = FakeArrivalNoticeTool()
        val orchestrator = ToolOrchestrator(
            actionLedger = ProcessLedger(),
            userConfirmationGate = UserConfirmationGate { true },
            clock = { 1_000L },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = FakeArrivalNoticeParams(" 아내 ", " 30분 뒤 도착해. "),
            requestId = "turn-1",
        ) as PreparationResult.Ready

        assertEquals(ConfirmationRequirement.UserConfirmation, prepared.action.confirmation)
        assertTrue(prepared.action.preview.summary.contains("아내"))
        assertTrue(prepared.action.preview.summary.contains("30분 뒤 도착해."))
        val result = orchestrator.execute(prepared.action)
        assertTrue(result.simulated)
    }

    @Test
    fun `fake tool rejects control characters and oversized input`() = runBlocking {
        val tool = FakeArrivalNoticeTool()
        assertTrue(
            tool.validateAndCanonicalize(FakeArrivalNoticeParams("wife\u0000", "hello"))
                is ValidationResult.Invalid,
        )
        assertTrue(
            tool.validateAndCanonicalize(
                FakeArrivalNoticeParams("wife", "x".repeat(FakeArrivalNoticeTool.MAX_MESSAGE_LENGTH + 1)),
            ) is ValidationResult.Invalid,
        )
    }

    @Test
    fun `fake tool counts Unicode code points at schema boundaries`() = runBlocking {
        val tool = FakeArrivalNoticeTool()
        val emoji = "🚀"

        assertTrue(
            tool.validateAndCanonicalize(
                FakeArrivalNoticeParams(emoji.repeat(40), emoji.repeat(240)),
            ) is ValidationResult.Valid,
        )
        assertTrue(
            tool.validateAndCanonicalize(
                FakeArrivalNoticeParams(emoji.repeat(41), "hello"),
            ) is ValidationResult.Invalid,
        )
        assertTrue(
            tool.validateAndCanonicalize(
                FakeArrivalNoticeParams("wife", emoji.repeat(241)),
            ) is ValidationResult.Invalid,
        )
    }

    @Test
    fun `fake tool rejects pinned model control delimiters`() = runBlocking {
        val tool = FakeArrivalNoticeTool()

        assertTrue(
            tool.validateAndCanonicalize(
                FakeArrivalNoticeParams("wife", "<|tool_response|>"),
            ) is ValidationResult.Invalid,
        )
        assertTrue(
            tool.validateAndCanonicalize(
                FakeArrivalNoticeParams("wife|>", "hello"),
            ) is ValidationResult.Invalid,
        )
    }
}
