package com.personaledge.agent

import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.llm.TurnId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalEdgeUiStateTest {
    @Test
    fun `deterministic reads remain sendable when a model is missing`() {
        val state = PersonalEdgeUiState(
            modelStatus = ModelUiStatus.MISSING,
            prompt = "다음 알람 언제야?",
            thermalStatus = DiagnosticThermalStatus.NONE,
        )

        assertTrue(state.canSend)
    }

    @Test
    fun `compound and ambiguous prompts still require a ready model`() {
        val base = PersonalEdgeUiState(
            modelStatus = ModelUiStatus.MISSING,
            thermalStatus = DiagnosticThermalStatus.NONE,
        )

        assertFalse(base.copy(prompt = "동탄 날씨와 내일 일정 알려줘").canSend)
        assertFalse(base.copy(prompt = "이 요청을 알아서 처리해 줘").canSend)
        assertTrue(
            base.copy(
                modelStatus = ModelUiStatus.READY,
                prompt = "이 요청을 알아서 처리해 줘",
            ).canSend,
        )
    }

    @Test
    fun `model transition and stop-level thermal states keep send closed`() {
        val deterministic = PersonalEdgeUiState(
            modelStatus = ModelUiStatus.INITIALIZING,
            prompt = "리마인더 목록",
            thermalStatus = DiagnosticThermalStatus.NONE,
        )

        assertFalse(deterministic.canSend)
        assertFalse(
            deterministic.copy(
                modelStatus = ModelUiStatus.MISSING,
                thermalStatus = DiagnosticThermalStatus.CRITICAL,
            ).canSend,
        )
    }

    @Test
    fun `oversized visible draft cannot be sent to the model`() {
        val state = PersonalEdgeUiState(
            modelStatus = ModelUiStatus.READY,
            prompt = "a".repeat(PromptInputPolicy.MAX_ACCEPTED_BYTES + 1),
            promptInputWarning = PromptInputPolicy.LIMIT_WARNING,
            thermalStatus = DiagnosticThermalStatus.NONE,
        )

        assertFalse(state.canSend)
    }

    @Test
    fun `live reasoning is transient redacted state and keeps an active turn busy`() {
        val rawThought = "private model reasoning"
        val turnId = TurnId("turn-test")
        val reasoning = ActiveReasoningUiState(
            turnId = turnId,
            assistantEntryId = "assistant-test",
            text = rawThought,
        )
        val state = PersonalEdgeUiState(
            modelStatus = ModelUiStatus.READY,
            prompt = "next request",
            activeTurnId = turnId,
            activeReasoning = reasoning,
            thermalStatus = DiagnosticThermalStatus.NONE,
        )

        assertTrue(state.isBusy)
        assertFalse(state.canSend)
        assertFalse(reasoning.toString().contains(rawThought))
        assertTrue(PersonalEdgeUiState().activeReasoning == null)
    }

    @Test
    fun `reasoning deltas target only the current assistant phase`() {
        val turnId = TurnId("turn-test")
        val started = ActiveReasoningUiPolicy.start(turnId, "assistant-0")

        val stale = ActiveReasoningUiPolicy.append(
            current = started,
            activeTurnId = turnId,
            turnId = turnId,
            assistantEntryId = "assistant-stale",
            delta = "must not appear",
        )
        val live = ActiveReasoningUiPolicy.append(
            current = stale,
            activeTurnId = turnId,
            turnId = turnId,
            assistantEntryId = "assistant-0",
            delta = "live",
        )
        val nextPhase = ActiveReasoningUiPolicy.advance(
            current = live,
            activeTurnId = turnId,
            turnId = turnId,
            nextAssistantEntryId = "assistant-1",
        )

        assertTrue(stale === started)
        assertTrue(live?.text == "live")
        assertTrue(nextPhase?.assistantEntryId == "assistant-1")
        assertTrue(nextPhase?.text?.isEmpty() == true)
    }
}
