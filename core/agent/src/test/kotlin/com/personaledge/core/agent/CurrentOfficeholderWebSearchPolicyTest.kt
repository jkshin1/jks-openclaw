package com.personaledge.core.agent

import com.personaledge.core.tools.WebSearchHit
import com.personaledge.core.tools.WebSearchProvider
import com.personaledge.core.tools.WebSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the exact failure sequence captured in the conversation screenshot. */
class CurrentOfficeholderWebSearchPolicyTest {
    @Test
    fun `current Korean president question becomes a bounded volatile fact search`() {
        val request = requireNotNull(
            AutomaticWebSearchPolicy.knowledgeRequestOrNull(
                "현재 대한민국 대통령이 누구야?",
            ),
        )

        assertEquals(EXPECTED_QUERY, request.query)
        assertEquals(WebSearchAnswerIntent.CURRENT_OFFICEHOLDER, request.intent)
        assertEquals(
            EXPECTED_QUERY,
            AutomaticWebSearchPolicy.knowledgeRequestOrNull(
                "웹에서 현재 대한민국의 대통령이 누구인지 찾아줘",
            )?.query,
        )
    }

    @Test
    fun `historical private and entityless officeholder questions are not auto transferred`() {
        listOf(
            "대한민국 초대 대통령이 누구야?",
            "우리 회사 현재 대표이사 이름이 뭐야?",
            "현재 대통령이 누구야?",
            "우리나라 대통령이 누구야?",
            "현재 대한민국 대통령이 누구고 서울 날씨도 알려줘",
            "현재 대한민국 대통령이 누구야? 비트코인 시세도 알려줘",
            "현재 대한민국 대통령과 미국 대통령이 누구야?",
        ).forEach { request ->
            assertNull(request, AutomaticWebSearchPolicy.knowledgeRequestOrNull(request))
            assertNull(request, DeterministicReadRouter.classify(request, false))
        }
    }

    @Test
    fun `search corrections inherit the immediately preceding owner question`() {
        val previousQuestion = "현재 대한민국 대통령이 누구야?"
        val followUps = listOf(
            "웹 검색 할 수 있잖아",
            "웹검색을 해서 첫질문에 대한 답을 해줘",
            "대통령 이름을 찾기위해 웹검색을 더 잘해봐",
        )

        followUps.forEach { followUp ->
            val inherited = requireNotNull(
                AutomaticWebSearchPolicy.contextualRequestOrNull(
                    followUp = followUp,
                    previousUserRequest = previousQuestion,
                ),
                { "follow-up was not inherited: $followUp" },
            )

            assertEquals(followUp, EXPECTED_QUERY, inherited.query)
            assertEquals(
                followUp,
                WebSearchAnswerIntent.CURRENT_OFFICEHOLDER,
                inherited.intent,
            )
            assertNull(followUp, explicitWebSearchQueryOrNull(followUp))
            assertNull(followUp, DeterministicReadRouter.classify(followUp, false))
        }
    }

    @Test
    fun `constitutional background without the current officeholder name is not answer evidence`() {
        val plan = WebSearchAnswerPolicy.prepare(
            query = EXPECTED_QUERY,
            result = WebSearchResult(
                provider = WebSearchProvider.YOU_COM,
                hits = listOf(
                    WebSearchHit(
                        title = "대한민국 대통령 - 위키백과",
                        link = "https://example.test/president-office",
                        snippet = "대한민국 대통령은 국가원수이며 임기는 5년입니다.",
                    ),
                    WebSearchHit(
                        title = "대한민국 대통령 선거와 임기",
                        link = "https://example.test/constitution",
                        snippet = "대한민국 헌법 제67조는 대통령 선거 방법을 규정합니다.",
                    ),
                ),
            ),
            intent = WebSearchAnswerIntent.CURRENT_OFFICEHOLDER,
        )

        assertTrue(plan.hits.isEmpty())
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "대한민국 대통령은 선거로 선출되며 임기는 5년입니다.",
            ),
        )
    }

    @Test
    fun `official fixture answers the question only when the officeholder name is present`() {
        val plan = WebSearchAnswerPolicy.prepare(
            query = EXPECTED_QUERY,
            result = WebSearchResult(
                provider = WebSearchProvider.TAVILY,
                hits = listOf(
                    WebSearchHit(
                        title = "대한민국 대통령실 - 대통령 소개",
                        link = "https://www.president.go.kr/fictional-test-fixture",
                        snippet = "대한민국의 현직 대통령은 홍길동입니다.",
                    ),
                ),
            ),
            intent = WebSearchAnswerIntent.CURRENT_OFFICEHOLDER,
        )

        assertEquals(1, plan.hits.size)
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "현재 대한민국 대통령 정보입니다.",
            ),
        )
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "현재 대한민국 대통령은 홍길동이 아닙니다.",
            ),
        )

        val answer = requireNotNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "현재 대한민국 대통령은 홍길동입니다.",
            ),
        )
        assertTrue(answer.startsWith("현재 대한민국 대통령은 홍길동입니다."))
        assertTrue(answer.contains("https://www.president.go.kr/fictional-test-fixture"))
    }

    private companion object {
        const val EXPECTED_QUERY = "대한민국 현직 대통령 이름 공식"
    }
}
