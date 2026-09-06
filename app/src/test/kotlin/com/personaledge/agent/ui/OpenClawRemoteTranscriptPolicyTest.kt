package com.personaledge.agent.ui

import com.personaledge.agent.ChatRole
import com.personaledge.agent.OpenClawRemoteUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawRemoteTranscriptPolicyTest {
    @Test
    fun `a stored turn is left to the conversation so nothing renders twice`() {
        val state = OpenClawRemoteUiState(
            sentPrompt = "서울 날씨 알려줘",
            answer = "맑습니다.",
            questionStored = true,
            answerStored = true,
        )

        assertTrue(OpenClawRemoteTranscriptPolicy.liveEntries(state).isEmpty())
    }

    @Test
    fun `a running turn shows the streamed answer once the first text arrives`() {
        val running = OpenClawRemoteUiState(
            running = true,
            sentPrompt = "서울 날씨 알려줘",
            questionStored = true,
        )
        // No bubble before any text: the remote engine exposes no reasoning to disclose.
        assertTrue(OpenClawRemoteTranscriptPolicy.liveEntries(running).isEmpty())

        val streaming = OpenClawRemoteTranscriptPolicy.liveEntries(running.copy(answer = "맑"))
        assertEquals(1, streaming.size)
        assertEquals(ChatRole.ASSISTANT, streaming.single().role)
        assertEquals("맑", streaming.single().text)
    }

    @Test
    fun `an unstored turn keeps both of its own rows visible`() {
        val state = OpenClawRemoteUiState(
            sentPrompt = "저장이 막힌 질문",
            answer = "답변 본문",
        )

        val entries = OpenClawRemoteTranscriptPolicy.liveEntries(state)
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), entries.map { it.role })
        assertEquals(listOf("저장이 막힌 질문", "답변 본문"), entries.map { it.text })
        assertEquals(
            listOf(
                OpenClawRemoteTranscriptPolicy.LIVE_QUESTION_ID,
                OpenClawRemoteTranscriptPolicy.LIVE_ANSWER_ID,
            ),
            entries.map { it.id },
        )
    }

    @Test
    fun `a stored question with an unstored answer renders only the answer`() {
        val state = OpenClawRemoteUiState(
            sentPrompt = "저장된 질문",
            answer = "저장 실패한 답변",
            questionStored = true,
        )

        val entries = OpenClawRemoteTranscriptPolicy.liveEntries(state)
        assertEquals(listOf(ChatRole.ASSISTANT), entries.map { it.role })
        assertEquals("저장 실패한 답변", entries.single().text)
    }
}
