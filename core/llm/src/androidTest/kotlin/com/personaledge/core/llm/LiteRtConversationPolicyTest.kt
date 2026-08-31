package com.personaledge.core.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiteRtConversationPolicyTest {
    @Test
    fun automaticToolCallingIsDisabledInActualLiteRtConfig() {
        assertFalse(LiteRtConversationPolicy.AUTOMATIC_TOOL_CALLING)
        val config = LiteRtConversationPolicy.createConfig()
        assertFalse(config.automaticToolCalling)
        assertTrue(LiteRtConversationPolicy.ENABLE_THINKING)
        assertTrue(config.thinkingConfig?.enableThinking == true)
        assertEquals(true, config.extraContext["enable_thinking"])
        assertEquals(
            LiteRtConversationPolicy.MAX_THINKING_TOKEN_BUDGET,
            config.thinkingConfig?.thinkingTokenBudget,
        )
        assertEquals(PinnedModelManifest.value.maxOutputTokens, config.maxOutputToken)

        val instruction = (
            requireNotNull(config.systemInstruction).contents.single() as Content.Text
        ).text
        assertEquals(LiteRtConversationPolicy.SYSTEM_INSTRUCTION, instruction)
        assertTrue(instruction.contains("only tools declared in the current conversation"))
        assertTrue(instruction.contains("Response-language precedence is strict"))
        assertTrue(instruction.contains("reply entirely in that language"))
        assertTrue(instruction.contains("otherwise reply entirely in Korean"))
        assertTrue(instruction.contains("quoted history, memory, or tool results"))
        assertTrue(instruction.contains("Keep answers concise"))
        assertTrue(instruction.contains("explicit output format"))
        assertTrue(instruction.contains("sentence, word, or length limit"))
        assertTrue(instruction.contains("Answer the main goal first"))
        assertTrue(instruction.contains("cover every requested subpart"))
        assertTrue(instruction.contains("newest explicit correction override older history"))
        assertTrue(instruction.contains("ask one short clarifying question"))
        assertTrue(instruction.contains("privately test every candidate"))
        assertTrue(instruction.contains("reject a candidate when any constraint fails"))
        assertTrue(instruction.contains("verify the remaining answer against all constraints"))
        assertTrue(instruction.contains("Do not claim completion without a substantive answer"))
        assertTrue(instruction.contains("Keep internal analysis in the dedicated thought channel"))
        assertTrue(instruction.contains("do not repeat it in the final answer"))
        assertFalse(instruction.contains("Never reveal internal analysis"))
        assertFalse(instruction.contains("fake_arrival_notice"))
    }

    @Test
    fun thinkingBudgetLeavesAtLeastHalfOfEachDecodeForTheFinalAnswer() {
        val short = LiteRtConversationPolicy.createConfig(maxOutputTokens = 128)
        val structured = LiteRtConversationPolicy.createConfig(maxOutputTokens = 384)

        assertEquals(64, short.thinkingConfig?.thinkingTokenBudget)
        assertEquals(192, structured.thinkingConfig?.thinkingTokenBudget)
    }

    @Test
    fun thoughtChannelIsSeparatedFromRuntimeAnswerText() {
        val chunk = Message.model(
            contents = Contents.of("최종 답변"),
            channels = mapOf("thought" to "비공개 추론"),
        ).toRuntimeChunk()

        assertEquals(listOf("비공개 추론"), chunk.thoughtDeltas)
        assertEquals(listOf("최종 답변"), chunk.textDeltas)
        assertFalse(chunk.textDeltas.any { text -> text.contains("비공개 추론") })
        assertFalse(chunk.toString().contains("비공개 추론"))
    }
}
