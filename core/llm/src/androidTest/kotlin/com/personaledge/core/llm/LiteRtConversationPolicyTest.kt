package com.personaledge.core.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.litertlm.Content
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
        assertFalse(LiteRtConversationPolicy.ENABLE_THINKING)
        assertFalse(config.thinkingConfig?.enableThinking ?: true)
        assertEquals(false, config.extraContext["enable_thinking"])
        assertEquals(PinnedModelManifest.value.maxOutputTokens, config.maxOutputToken)

        val instruction = (
            requireNotNull(config.systemInstruction).contents.single() as Content.Text
        ).text
        assertEquals(LiteRtConversationPolicy.SYSTEM_INSTRUCTION, instruction)
        assertTrue(instruction.contains("only tools declared in the current conversation"))
        assertFalse(instruction.contains("fake_arrival_notice"))
    }
}
