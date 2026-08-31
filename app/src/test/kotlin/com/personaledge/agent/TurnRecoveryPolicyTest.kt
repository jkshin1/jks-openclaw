package com.personaledge.agent

import com.personaledge.core.agent.AgentEvent
import com.personaledge.core.agent.AgentFailureCode
import com.personaledge.core.data.TurnOutcomeFailureCode
import com.personaledge.core.data.TurnOutcomeState
import com.personaledge.core.data.TurnRecoverability
import com.personaledge.core.data.TurnRecoveryRecord
import com.personaledge.core.data.TurnToolRisk
import com.personaledge.core.llm.LlmFailureCode
import com.personaledge.core.llm.TurnId
import com.personaledge.core.tools.AlarmSetTool
import com.personaledge.core.tools.AlarmNextTool
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.KakaoNotificationReplyTool
import com.personaledge.core.tools.ReminderCreateTool
import com.personaledge.core.tools.WebSearchTool
import com.personaledge.core.tools.WeatherTool
import com.personaledge.core.tools.ToolRisk
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRecoveryPolicyTest {
    @Test
    fun `closed tool classification keeps reads separate from state changes`() {
        assertEquals(TurnToolRisk.READ_ONLY, TurnRecoveryPolicy.toolRisk(WebSearchTool.NAME))
        assertEquals(TurnToolRisk.READ_ONLY, TurnRecoveryPolicy.toolRisk(CalendarQueryTool.NAME))
        assertEquals(TurnToolRisk.LOCAL_WRITE, TurnRecoveryPolicy.toolRisk(ReminderCreateTool.NAME))
        assertEquals(TurnToolRisk.DATA_WRITE, TurnRecoveryPolicy.toolRisk(AlarmSetTool.NAME))
        assertEquals(
            TurnToolRisk.COMMUNICATION,
            TurnRecoveryPolicy.toolRisk(KakaoNotificationReplyTool.NAME),
        )
        assertEquals(TurnToolRisk.HIGH_RISK, TurnRecoveryPolicy.toolRisk("future_tool"))
        assertEquals(
            TurnToolRisk.READ_ONLY,
            TurnRecoveryPolicy.validatedToolRisk(WebSearchTool.NAME, ToolRisk.READ_ONLY),
        )
        assertNull(TurnRecoveryPolicy.validatedToolRisk(WebSearchTool.NAME, ToolRisk.DATA_WRITE))
        assertNull(TurnRecoveryPolicy.validatedToolRisk("future_tool", ToolRisk.HIGH_RISK))
    }

    @Test
    fun `context budget failure remains a typed recoverable failure`() {
        val failure = AgentEvent.Failure(
            turnId = TurnId("turn-00000000-0000-0000-0000-000000000000"),
            code = AgentFailureCode.MODEL_FAILURE,
            runtimeCode = LlmFailureCode.CONTEXT_BUDGET_EXCEEDED,
        )

        assertEquals(
            TurnOutcomeFailureCode.CONTEXT_BUDGET_EXCEEDED,
            TurnRecoveryPolicy.failureCode(failure),
        )
    }

    @Test
    fun `read recovery identity rejects substitution and preserves ordered multiplicity`() {
        val expected = listOf(
            CalendarQueryTool.NAME,
            WeatherTool.NAME,
            WeatherTool.NAME,
        )

        assertTrue(TurnRecoveryPolicy.validExpectedReadTools(expected))
        assertTrue(TurnRecoveryPolicy.matchesExpectedReadTools(expected, expected.toList()))
        assertTrue(
            TurnRecoveryPolicy.matchesExpectedReadTool(
                expected,
                trustedOrdinal = 1,
                observedToolName = CalendarQueryTool.NAME,
            ),
        )
        assertEquals(
            false,
            TurnRecoveryPolicy.matchesExpectedReadTool(
                expected,
                trustedOrdinal = 1,
                observedToolName = AlarmNextTool.NAME,
            ),
        )
        assertEquals(
            false,
            TurnRecoveryPolicy.matchesExpectedReadTools(
                expected,
                listOf(WeatherTool.NAME, CalendarQueryTool.NAME, WeatherTool.NAME),
            ),
        )
        assertEquals(
            false,
            TurnRecoveryPolicy.matchesExpectedReadTools(
                expected,
                listOf(CalendarQueryTool.NAME, WeatherTool.NAME),
            ),
        )
    }

    @Test
    fun `communication uncertainty only produces manual verification guidance`() {
        val guidance = TurnRecoveryPolicy.verificationGuidance(
            TurnRecoveryRecord(
                turnId = "turn-00000000-0000-0000-0000-000000000000",
                conversationId = "conversation",
                userRequest = "메시지를 보내줘",
                state = TurnOutcomeState.WRITE_UNKNOWN,
                recoverability = TurnRecoverability.VERIFY_EXTERNAL_STATE,
                failureCode = TurnOutcomeFailureCode.UNKNOWN,
                lastToolName = KakaoNotificationReplyTool.NAME,
                lastToolRisk = TurnToolRisk.COMMUNICATION,
                updatedAtEpochMillis = 1L,
            ),
        )

        assertTrue(guidance.contains("직접 확인"))
        assertTrue(guidance.contains("자동 재전송하지 않습니다"))
    }

    @Test
    fun `recovery record string rendering omits user request`() {
        val secretRequest = "private request 010-1234-5678"
        val recovery = TurnRecoveryRecord(
            turnId = "turn-00000000-0000-0000-0000-000000000000",
            conversationId = "conversation",
            userRequest = secretRequest,
            state = TurnOutcomeState.READ_EXECUTED,
            recoverability = TurnRecoverability.REQUERY_READ,
            failureCode = TurnOutcomeFailureCode.UNKNOWN,
            lastToolName = WeatherTool.NAME,
            lastToolRisk = TurnToolRisk.READ_ONLY,
            updatedAtEpochMillis = 1L,
            expectedReadTools = listOf(WeatherTool.NAME),
        )

        assertEquals(false, recovery.toString().contains(secretRequest))
        assertTrue(recovery.toString().contains(WeatherTool.NAME))
    }
}
