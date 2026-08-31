package com.personaledge.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoToolFinalAnswerPolicyTest {
    @Test
    fun `blank model output is rejected`() {
        assertFalse(NoToolFinalAnswerPolicy.accepts("질문입니다", ""))
        assertFalse(NoToolFinalAnswerPolicy.accepts("질문입니다", " \n\t"))
    }

    @Test
    fun `only the normalized exact generic completion receipt is rejected`() {
        assertFalse(
            NoToolFinalAnswerPolicy.accepts(
                currentUserPrompt = "결과를 설명해 줘",
                modelAnswer = " 요청 처리를  완료했습니다. \n",
            ),
        )
        assertTrue(
            NoToolFinalAnswerPolicy.accepts(
                currentUserPrompt = "결과를 설명해 줘",
                modelAnswer = "요청 처리를 완료했습니다. 결과 코드는 ORBIT-7입니다.",
            ),
        )
    }

    @Test
    fun `unicode case and whitespace normalized whole prompt echo is rejected`() {
        assertFalse(
            NoToolFinalAnswerPolicy.accepts(
                currentUserPrompt = "  ORBIT－7을   설명해 줘 ",
                modelAnswer = "orbit-7을 설명해줘",
            ),
        )
    }

    @Test
    fun `short or prompt-referencing answers are not rejected heuristically`() {
        assertTrue(NoToolFinalAnswerPolicy.accepts("가능한지 답해 줘", "네."))
        assertTrue(
            NoToolFinalAnswerPolicy.accepts(
                currentUserPrompt = "ORBIT-7을 설명해 줘",
                modelAnswer = "ORBIT-7을 설명해 달라는 요청이며, 오프라인 후보입니다.",
            ),
        )
    }
}
