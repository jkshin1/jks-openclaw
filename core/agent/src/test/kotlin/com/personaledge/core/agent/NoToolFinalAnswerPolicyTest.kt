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

    @Test
    fun `unexecuted write scope rejects success claims`() {
        assertFalse(
            NoToolFinalAnswerPolicy.accepts(
                currentUserPrompt = "내일 오후 3시에 회의 일정을 등록해 줘",
                modelAnswer = "캘린더에 일정을 등록했습니다.",
                unexecutedWriteScope = true,
            ),
        )
        assertFalse(
            NoToolFinalAnswerPolicy.accepts(
                currentUserPrompt = "민수에게 도착했다고 보내 줘",
                modelAnswer = "메시지를 보냈습니다.",
                unexecutedWriteScope = true,
            ),
        )
        assertFalse(
            NoToolFinalAnswerPolicy.accepts(
                currentUserPrompt = "내일 알람을 설정해 줘",
                modelAnswer = "알람이 설정되었습니다.",
                unexecutedWriteScope = true,
            ),
        )
        listOf(
            "일정을 등록해 두었어요.",
            "메시지 전송을 완료했어요.",
            "리마인더를 추가해 드렸습니다.",
            "예약됐어요.",
        ).forEach { answer ->
            assertFalse(
                NoToolFinalAnswerPolicy.accepts(
                    currentUserPrompt = "요청을 처리해 줘",
                    modelAnswer = answer,
                    unexecutedWriteScope = true,
                ),
            )
        }
    }

    @Test
    fun `write success vocabulary is not rejected outside write scope`() {
        assertTrue(
            NoToolFinalAnswerPolicy.accepts(
                currentUserPrompt = "이 문장을 그대로 평가해 줘",
                modelAnswer = "캘린더에 일정을 등록했습니다.",
                unexecutedWriteScope = false,
            ),
        )
    }

    @Test
    fun `unexecuted write scope still accepts clarification questions`() {
        assertTrue(
            NoToolFinalAnswerPolicy.accepts(
                currentUserPrompt = "일정을 등록해 줘",
                modelAnswer = "어느 일정을 언제 등록할까요?",
                unexecutedWriteScope = true,
            ),
        )
    }

    @Test
    fun `unexecuted write scope accepts an explicit non-execution statement`() {
        assertTrue(
            NoToolFinalAnswerPolicy.accepts(
                currentUserPrompt = "일정을 등록해 줘",
                modelAnswer = "필수 시간이 없어 일정을 등록하지 않았습니다.",
                unexecutedWriteScope = true,
            ),
        )
    }
}
