package com.personaledge.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnOutputBudgetPolicyTest {
    @Test
    fun `short requested output keeps full budget when candidate selection has constraints`() {
        val selected = TurnOutputBudgetPolicy.forPrompt(
            "후보 A와 B를 세 조건에 비교해서 만족하는 코드만 짧게 답해 줘",
            AgentLoopLimits(maxOutputTokens = 1_024),
        )

        assertEquals(1_024, selected.maxOutputTokens)
    }

    @Test
    fun shortStructuredRequestsUseSmallerNativeDecodeBudgets() {
        assertEquals(
            TurnOutputBudgetPolicy.STRUCTURED_TOOL_OUTPUT_TOKENS,
            TurnOutputBudgetPolicy.forPrompt("내일 오전 7시 리마인더 만들어 줘").maxOutputTokens,
        )
        assertEquals(
            TurnOutputBudgetPolicy.SHORT_REPLY_OUTPUT_TOKENS,
            TurnOutputBudgetPolicy.forPrompt("안녕").maxOutputTokens,
        )
        assertEquals(
            TurnOutputBudgetPolicy.CONCISE_REPLY_OUTPUT_TOKENS,
            TurnOutputBudgetPolicy.forPrompt("Explain in one short sentence why rainbows appear.")
                .maxOutputTokens,
        )
        assertEquals(
            TurnOutputBudgetPolicy.CONCISE_REPLY_OUTPUT_TOKENS,
            TurnOutputBudgetPolicy.forPrompt("무지개가 생기는 이유를 한 문장으로 영어로 답해 줘.")
                .maxOutputTokens,
        )
        assertEquals(
            TurnOutputBudgetPolicy.STRUCTURED_TOOL_OUTPUT_TOKENS,
            TurnOutputBudgetPolicy.forPrompt("오늘 경기도 이천 날씨 알려줘").maxOutputTokens,
        )
    }

    @Test
    fun longFormAndAmbiguousRequestsKeepFullBudget() {
        assertEquals(
            1_024,
            TurnOutputBudgetPolicy.forPrompt("내 일정 관리 원칙을 자세히 비교해서 설명해 줘").maxOutputTokens,
        )
        assertEquals(
            1_024,
            TurnOutputBudgetPolicy.forPrompt("한국 근현대사의 주요 변화를 알려 줘").maxOutputTokens,
        )
    }

    @Test
    fun operationalReminderRequestStaysBoundedWhenTitleContainsALongFormTerm() {
        assertEquals(
            TurnOutputBudgetPolicy.STRUCTURED_TOOL_OUTPUT_TOKENS,
            TurnOutputBudgetPolicy.forPrompt(
                "보고서 확인 리마인더를 만들어 주세요",
            ).maxOutputTokens,
        )
    }

    @Test
    fun explicitLongFormSearchKeepsFullDecodeBudgetWithoutChangingToolSafetyLimits() {
        val base = AgentLoopLimits(
            maxSteps = 3,
            deadlineMillis = 90_000,
            maxOutputTokens = 1_024,
            maxToolCalls = 1,
            maxToolArgumentBytes = 1_024,
        )

        val selected = TurnOutputBudgetPolicy.forPrompt(
            "최신 자료를 검색해서 자세히 분석해 줘",
            base,
        )

        assertEquals(1_024, selected.maxOutputTokens)
        assertEquals(base.maxSteps, selected.maxSteps)
        assertEquals(base.deadlineMillis, selected.deadlineMillis)
        assertEquals(base.maxToolCalls, selected.maxToolCalls)
        assertEquals(base.maxToolArgumentBytes, selected.maxToolArgumentBytes)
        assertTrue(TurnOutputBudgetPolicy.requestsLongForm("영화에 대해 자세히 알려줘"))
        assertFalse(TurnOutputBudgetPolicy.requestsLongForm("영화를 웹에서 찾아줘"))
    }

    @Test
    fun policyNeverRaisesAnOwnerConfiguredCeiling() {
        val base = AgentLoopLimits(maxOutputTokens = 192)
        assertEquals(192, TurnOutputBudgetPolicy.forPrompt("다음 알람 알려 줘", base).maxOutputTokens)
    }

    @Test
    fun toolAndLongFormNeedsTakePriorityOverConciseWording() {
        assertEquals(
            TurnOutputBudgetPolicy.STRUCTURED_TOOL_OUTPUT_TOKENS,
            TurnOutputBudgetPolicy.forPrompt("예정된 리마인더를 한 문장씩 보여 줘").maxOutputTokens,
        )
        assertEquals(
            1_024,
            TurnOutputBudgetPolicy.forPrompt("한 문장이라는 표현의 역사를 자세히 설명해 줘").maxOutputTokens,
        )
    }
}
