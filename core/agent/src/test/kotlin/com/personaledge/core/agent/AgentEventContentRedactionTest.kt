package com.personaledge.core.agent

import com.personaledge.core.llm.TurnId
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentEventContentRedactionTest {
    @Test
    fun `agent event strings never expose model or grounded answer text`() {
        val sensitive = "owner-private-agent-content"
        val events = listOf(
            AgentEvent.ThoughtDelta(TurnId("turn-redaction"), sensitive),
            AgentEvent.TextDelta(TurnId("turn-redaction"), sensitive),
            AgentEvent.TrustedAnswer(TurnId("turn-redaction"), sensitive),
        )

        events.forEach { event ->
            assertFalse(event.toString().contains(sensitive))
        }
    }
}
