package com.personaledge.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnfinishedReadFollowUpTest {
    @Test
    fun `missing-answer follow-ups are recognized`() {
        assertTrue(UnfinishedReadFollowUp.matches("왜 답변을 안 해줘"))
        assertTrue(UnfinishedReadFollowUp.matches("계속 답해줘"))
        assertTrue(UnfinishedReadFollowUp.matches("결과 다시 알려줘"))
        assertTrue(UnfinishedReadFollowUp.matches("왜 중단했어?"))
    }

    @Test
    fun `new requests and long text are not treated as recovery commands`() {
        assertFalse(UnfinishedReadFollowUp.matches("서울 맛집 추천해줘"))
        assertFalse(UnfinishedReadFollowUp.matches("답변" + "해".repeat(200)))
    }
}
