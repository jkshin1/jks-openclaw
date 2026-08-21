package com.personaledge.core.llm

import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolProvider

/**
 * Security-critical default: model-emitted calls must return to Kotlin for validation,
 * confirmation, execution, and verification.
 */
object LiteRtConversationPolicy {
    const val AUTOMATIC_TOOL_CALLING: Boolean = false
    const val ENABLE_THINKING: Boolean = false

    fun createConfig(
        tools: List<ToolProvider> = emptyList(),
        maxOutputTokens: Int = PinnedModelManifest.value.maxOutputTokens,
    ): ConversationConfig {
        require(maxOutputTokens in 1..PinnedModelManifest.value.maxOutputTokens)
        return ConversationConfig(
            systemInstruction = Contents.of(
                "You are a private on-device assistant. Use a declared tool only when " +
                    "the user explicitly asks for that action. For a requested simulated " +
                    "arrival notice, call fake_arrival_notice exactly once with only the " +
                    "recipient and message string fields. Never claim this simulation sent " +
                    "a real external message. After its result, state only that the simulation " +
                    "completed.",
            ),
            tools = tools,
            automaticToolCalling = AUTOMATIC_TOOL_CALLING,
            extraContext = mapOf("enable_thinking" to ENABLE_THINKING),
            maxOutputToken = maxOutputTokens,
            thinkingConfig = ThinkingConfig(enableThinking = ENABLE_THINKING),
        )
    }
}
