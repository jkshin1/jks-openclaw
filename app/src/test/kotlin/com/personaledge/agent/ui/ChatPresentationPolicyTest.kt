package com.personaledge.agent.ui

import com.personaledge.agent.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPresentationPolicyTest {
    @Test
    fun conversationBubblesExposeStableSpokenRoles() {
        assertEquals(
            ChatPresentationPolicy.USER_MESSAGE_DESCRIPTION,
            ChatPresentationPolicy.bubbleRoleDescription(ChatRole.USER),
        )
        assertEquals(
            ChatPresentationPolicy.ASSISTANT_MESSAGE_DESCRIPTION,
            ChatPresentationPolicy.bubbleRoleDescription(ChatRole.ASSISTANT),
        )
        assertNull(ChatPresentationPolicy.bubbleRoleDescription(ChatRole.TOOL))
        assertNull(ChatPresentationPolicy.bubbleRoleDescription(ChatRole.STATUS))
        assertTrue(ChatPresentationPolicy.TYPING_DESCRIPTION.isNotBlank())
        assertEquals("생각 중", ChatPresentationPolicy.THINKING_TITLE)
        assertTrue(ChatPresentationPolicy.THINKING_WAITING_TEXT.isNotBlank())
    }

    @Test
    fun thinkingDisclosureShowsOnlyTheMatchingAssistantReasoningVerbatim() {
        val rawReasoning = "  최신 수정을 우선한다.\n도구가 필요한지 확인한다."

        assertEquals(
            rawReasoning,
            ChatPresentationPolicy.reasoningTextForAssistant(
                assistantEntryId = "assistant-2",
                reasoningAssistantEntryId = "assistant-2",
                reasoningText = rawReasoning,
            ),
        )
        assertNull(
            ChatPresentationPolicy.reasoningTextForAssistant(
                assistantEntryId = "assistant-1",
                reasoningAssistantEntryId = "assistant-2",
                reasoningText = rawReasoning,
            ),
        )
        assertEquals(
            rawReasoning,
            ChatPresentationPolicy.displayedThinkingText(rawReasoning),
        )
        assertEquals(
            ChatPresentationPolicy.THINKING_WAITING_TEXT,
            ChatPresentationPolicy.displayedThinkingText(""),
        )
        assertEquals(
            ChatPresentationPolicy.THINKING_WAITING_TEXT,
            ChatPresentationPolicy.displayedThinkingText(null),
        )
    }

    @Test
    fun thinkingDisclosureOnlyReplacesTheBlankLatestActiveAssistant() {
        assertTrue(
            ChatPresentationPolicy.isThinkingAssistant(
                role = ChatRole.ASSISTANT,
                text = "",
                isLatest = true,
                turnActive = true,
            ),
        )
        assertFalse(
            ChatPresentationPolicy.isThinkingAssistant(
                role = ChatRole.ASSISTANT,
                text = "답변을 시작했습니다.",
                isLatest = true,
                turnActive = true,
            ),
        )
        assertFalse(
            ChatPresentationPolicy.isThinkingAssistant(
                role = ChatRole.USER,
                text = "",
                isLatest = true,
                turnActive = true,
            ),
        )
        assertFalse(
            ChatPresentationPolicy.isThinkingAssistant(
                role = ChatRole.ASSISTANT,
                text = "",
                isLatest = false,
                turnActive = true,
            ),
        )
        assertFalse(
            ChatPresentationPolicy.isThinkingAssistant(
                role = ChatRole.ASSISTANT,
                text = "",
                isLatest = true,
                turnActive = false,
            ),
        )
    }

    @Test
    fun onlyTheLatestActiveAssistantEntryStreams() {
        assertTrue(
            ChatPresentationPolicy.isStreamingAssistant(
                role = ChatRole.ASSISTANT,
                isLatest = true,
                turnActive = true,
            ),
        )
        assertFalse(
            ChatPresentationPolicy.isStreamingAssistant(
                role = ChatRole.ASSISTANT,
                isLatest = false,
                turnActive = true,
            ),
        )
        assertFalse(
            ChatPresentationPolicy.isStreamingAssistant(
                role = ChatRole.USER,
                isLatest = true,
                turnActive = true,
            ),
        )
        assertFalse(
            ChatPresentationPolicy.isStreamingAssistant(
                role = ChatRole.ASSISTANT,
                isLatest = true,
                turnActive = false,
            ),
        )
    }

    @Test
    fun streamingDefersRichTextParsingAndBucketsAutoScrollWork() {
        assertFalse(ChatPresentationPolicy.shouldRenderRichText(streaming = true))
        assertTrue(ChatPresentationPolicy.shouldRenderRichText(streaming = false))
        assertEquals(0, ChatPresentationPolicy.autoScrollRevision("가".repeat(31)))
        assertEquals(1, ChatPresentationPolicy.autoScrollRevision("가".repeat(32)))
        assertEquals(1, ChatPresentationPolicy.autoScrollRevision("😀".repeat(32)))
        assertEquals(2, ChatPresentationPolicy.autoScrollRevision("가".repeat(64)))
    }

    @Test
    fun transcriptAutoScrollTracksReasoningUntilTheFinalAnswerStarts() {
        assertEquals(
            "추론 스트림",
            ChatPresentationPolicy.streamingTextForAutoScroll(
                assistantEntryId = "assistant-1",
                assistantText = "",
                reasoningAssistantEntryId = "assistant-1",
                reasoningText = "추론 스트림",
            ),
        )
        assertEquals(
            "최종 답변",
            ChatPresentationPolicy.streamingTextForAutoScroll(
                assistantEntryId = "assistant-1",
                assistantText = "최종 답변",
                reasoningAssistantEntryId = "assistant-1",
                reasoningText = "이전 추론",
            ),
        )
        assertEquals(
            "",
            ChatPresentationPolicy.streamingTextForAutoScroll(
                assistantEntryId = "assistant-1",
                assistantText = "",
                reasoningAssistantEntryId = "assistant-2",
                reasoningText = "다른 단계의 추론",
            ),
        )
    }
}
