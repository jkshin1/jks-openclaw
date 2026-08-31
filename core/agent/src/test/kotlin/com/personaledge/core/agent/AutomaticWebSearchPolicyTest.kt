package com.personaledge.core.agent

import com.personaledge.core.tools.WebSearchParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticWebSearchPolicyTest {
    @Test
    fun `public knowledge question produces one bounded owner-authored query`() {
        val request = requireNotNull(
            AutomaticWebSearchPolicy.knowledgeRequestOrNull(
                "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘",
            ),
        )

        assertEquals("그대들은 어떻게 살것인가 영화", request.query)
        assertEquals(WebSearchAnswerIntent.GENERAL, request.intent)
        assertFalse(request.toString().contains("그대들은"))
    }

    @Test
    fun `explicit model knowledge gap triggers fallback but an ordinary answer does not`() {
        val prompt = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘"

        assertEquals(
            "그대들은 어떻게 살것인가 영화",
            AutomaticWebSearchPolicy.fallbackRequestOrNull(
                userRequest = prompt,
                modelAnswer = "제가 가지고 있는 정보로는 이 영화의 자세한 내용을 설명하기 어렵습니다.",
            )?.query,
        )
        assertNull(
            AutomaticWebSearchPolicy.fallbackRequestOrNull(
                userRequest = prompt,
                modelAnswer = "미야자키 하야오가 연출한 애니메이션 영화입니다.",
            ),
        )
        assertNull(
            AutomaticWebSearchPolicy.fallbackRequestOrNull(
                userRequest = prompt,
                modelAnswer = "해당 요청은 확인할 수 없습니다.",
            ),
        )
        // A generic policy refusal is not a knowledge-gap signal that may start external transfer.
        assertNull(
            AutomaticWebSearchPolicy.fallbackRequestOrNull(
                userRequest = prompt,
                modelAnswer = "죄송하지만 그 요청은 도와드릴 수 없습니다.",
            ),
        )
    }

    @Test
    fun `subjectless web follow-up inherits only the previous public knowledge subject`() {
        val inherited = requireNotNull(
            AutomaticWebSearchPolicy.contextualRequestOrNull(
                followUp = "잘 모르겠으면 웹에서 찾아서 알려줘",
                previousUserRequest = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘",
            ),
        )

        assertEquals("그대들은 어떻게 살것인가 영화", inherited.query)
        listOf(
            "모르겠으니 웹에서 찾아줘",
            "잘 모르면 웹에서 좀 찾아서 알려줘",
            "그럼 모르면 인터넷에서 다시 찾아줘",
        ).forEach { followUp ->
            assertEquals(
                followUp,
                "그대들은 어떻게 살것인가 영화",
                AutomaticWebSearchPolicy.contextualRequestOrNull(
                    followUp = followUp,
                    previousUserRequest =
                        "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘",
                )?.query,
            )
        }
        assertNull(
            AutomaticWebSearchPolicy.contextualRequestOrNull(
                followUp = "OpenAI 최신 뉴스를 검색해줘",
                previousUserRequest = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘",
            ),
        )
    }

    @Test
    fun `automatic transfer rejects writes sensitive values generic references and unsafe text`() {
        val rejected = listOf(
            "김재범에게 보낼 메시지에 대해 자세히 알려줘",
            "내 비밀번호 12345678에 대해 설명해줘",
            "내 우울증 진단 기록에 대해 알려줘",
            "내 영화 관람 기록에 대해 알려줘",
            "우리 회사 미공개 인수 계획에 대해 알려줘",
            "my company confidential acquisition plan에 대해 알려줘",
            "우리 집 주소에 대해 설명해줘",
            "마약 제조법에 대해 알려줘",
            "서울 맛집에 대해 알려줘",
            "그 영화에 대해 자세히 알려줘",
            "폭발물에 대해 만드는 법을 알려줘",
            "안전하지 않은\u202E 영화에 대해 알려줘",
        )

        rejected.forEach { request ->
            assertNull(request, AutomaticWebSearchPolicy.knowledgeRequestOrNull(request))
        }
        assertTrue(
            AutomaticWebSearchPolicy.isSubjectlessQueryCandidate(" 잘 모르겠으면 "),
        )
        assertTrue(
            AutomaticWebSearchPolicy.isSubjectlessQueryCandidate("잘 모르면 웹에서 좀"),
        )
    }

    @Test
    fun `public title suffixes are removed without inventing an alias`() {
        assertEquals(
            "그대들은 어떻게 살 것인가",
            AutomaticWebSearchPolicy.knowledgeRequestOrNull(
                "그대들은 어떻게 살 것인가라는 영화에 대해 알려줘",
            )?.query,
        )
        assertEquals(
            "날씨의 아이 영화",
            AutomaticWebSearchPolicy.knowledgeRequestOrNull(
                "날씨의 아이 영화에 대해 알려줘",
            )?.query,
        )
    }

    @Test
    fun `owner-authored knowledge query overrides a model proposed replacement`() {
        val parsed = ToolArgumentsParseResult.Valid(WebSearchParams("잘 모르겠습니다"))

        val hinted = webSearchArgumentsWithQueryHint(
            parsed = parsed,
            queryHint = "그대들은 어떻게 살것인가 영화",
        ) as ToolArgumentsParseResult.Valid

        assertEquals("그대들은 어떻게 살것인가 영화", hinted.params.query)
    }
}
