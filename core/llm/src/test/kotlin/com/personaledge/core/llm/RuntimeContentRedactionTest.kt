package com.personaledge.core.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeContentRedactionTest {
    @Test
    fun `runtime value strings never expose prompts arguments payloads or deltas`() {
        val sensitive = "owner-private-runtime-content"
        val values = listOf(
            LlmToolCall("private-call-id", "lookup", "{\"value\":\"$sensitive\"}"),
            TrustedToolResponse("private-call-id", "lookup", "{\"value\":\"$sensitive\"}"),
            ModelEvent.ThoughtDelta(TurnId("turn-redaction"), sensitive),
            ModelEvent.TextDelta(TurnId("turn-redaction"), sensitive),
            RuntimeTurnInput.User(sensitive),
            RuntimeTurnInput.ToolResponses(
                listOf(RuntimeToolResponse("lookup", "{\"value\":\"$sensitive\"}")),
            ),
            RuntimeChunk(
                thoughtDeltas = listOf(sensitive),
                textDeltas = listOf(sensitive),
                toolCalls = listOf(RuntimeToolCall("lookup", "{\"value\":\"$sensitive\"}")),
            ),
            RuntimeToolResponse("lookup", "{\"value\":\"$sensitive\"}"),
            RuntimeToolCall("lookup", "{\"value\":\"$sensitive\"}"),
        )

        values.forEach { value ->
            assertFalse(value.toString().contains(sensitive))
        }
        assertTrue(values.all { value -> value.toString().isNotBlank() })
    }
}
