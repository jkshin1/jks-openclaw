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
    fun `closed volatile public facts require one owner-authored subject and immediate grounding`() {
        val cases = mapOf(
            "OpenAI 최신 뉴스" to "OpenAI 최신 뉴스",
            "비트코인 시세" to "비트코인 시세",
            "원달러 환율" to "원달러 환율",
            "한국은행 기준금리" to "한국은행 기준금리",
            "KBO 순위" to "KBO 순위",
            "삼성전자 최근 실적" to "삼성전자 최근 실적",
            "삼성전자 현재 주가를 알려줘" to "삼성전자 현재 주가",
        )

        cases.forEach { (prompt, expectedQuery) ->
            val request = requireNotNull(
                AutomaticWebSearchPolicy.knowledgeRequestOrNull(prompt),
                { "volatile fact was not classified: $prompt" },
            )
            assertEquals(prompt, expectedQuery, request.query)
            assertEquals(prompt, WebSearchAnswerIntent.GENERAL, request.intent)
            assertTrue(prompt, request.requiresImmediateSearch)
        }
    }

    @Test
    fun `volatile facts reject subjectless private sensitive write and compound text`() {
        listOf(
            "최신 뉴스",
            "현재 주가를 알려줘",
            "내 비트코인 시세",
            "내가 가진 비트코인 시세",
            "내프로젝트 최신 뉴스",
            "내 비밀번호 12345678 최신 뉴스",
            "우리 회사 최신 실적",
            "우리회사 최신 실적",
            "OpenAI 최신 뉴스를 저장해",
            "OpenAI 최신 뉴스와 비트코인 시세",
            "OpenAI 최신뉴스 비트코인 시세",
            "OpenAI 최신 뉴스와 내일 일정 알려줘",
            "비트코인 시세와 원달러 환율",
            "OpenAI 최신 뉴스\u202E",
        ).forEach { prompt ->
            assertNull(prompt, AutomaticWebSearchPolicy.knowledgeRequestOrNull(prompt))
        }
    }

    @Test
    fun `standalone web-assisted subject never inherits an older conversation subject`() {
        val followUp = "웹 검색을 활용해서 오펜하이머 영화 정보를 찾아줘"

        assertEquals("오펜하이머 영화", explicitWebSearchQueryOrNull(followUp))
        assertNull(
            AutomaticWebSearchPolicy.contextualRequestOrNull(
                followUp = followUp,
                previousUserRequest = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘",
            ),
        )
    }

    @Test
    fun `bounded meta corrections inherit safe general subjects but standalone searches win`() {
        val film = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘"
        listOf(
            "웹 검색을 더 잘해봐",
            "검색어를 바꿔서 다시 찾아줘",
            "웹 검색 할 수 있잖아",
        ).forEach { correction ->
            assertEquals(
                correction,
                "그대들은 어떻게 살것인가 영화",
                AutomaticWebSearchPolicy.contextualRequestOrNull(correction, film)?.query,
            )
            assertNull(correction, explicitWebSearchQueryOrNull(correction))
        }

        assertEquals(
            "OpenAI",
            AutomaticWebSearchPolicy.contextualRequestOrNull(
                followUp = "웹 검색 할 수 있잖아",
                previousUserRequest = "OpenAI를 검색해줘",
            )?.query,
        )
        val standalone = "비트코인 시세를 다시 검색해줘"
        assertNull(AutomaticWebSearchPolicy.contextualRequestOrNull(standalone, film))
        assertEquals("비트코인 시세", explicitWebSearchQueryOrNull(standalone))
    }

    @Test
    fun `contextual correction walk is bounded and unsafe rows stop inheritance`() {
        val correction = "웹 검색을 더 잘해봐"
        val source = "OpenAI를 검색해줘"
        assertEquals(
            "OpenAI",
            AutomaticWebSearchPolicy.contextualRequestOrNull(
                followUp = correction,
                previousUserRequestsNewestFirst = listOf(
                    "검색어를 바꿔서 다시 찾아줘",
                    "웹 검색 할 수 있잖아",
                    source,
                ),
            )?.query,
        )
        assertNull(
            AutomaticWebSearchPolicy.contextualRequestOrNull(
                followUp = correction,
                previousUserRequestsNewestFirst = listOf(
                    "검색어를 바꿔서 다시 찾아줘",
                    "내 비밀번호 12345678을 검색해줘",
                    source,
                ),
            ),
        )
        assertNull(
            AutomaticWebSearchPolicy.contextualRequestOrNull(
                followUp = correction,
                previousUserRequestsNewestFirst = listOf(
                    "웹 검색을 더 잘해봐",
                    "검색어를 바꿔서 다시 찾아줘",
                    "웹 검색 할 수 있잖아",
                    source,
                ),
            ),
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
