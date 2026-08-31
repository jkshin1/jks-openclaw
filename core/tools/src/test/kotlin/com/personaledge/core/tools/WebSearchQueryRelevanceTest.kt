package com.personaledge.core.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchQueryRelevanceTest {
    @Test
    fun `compact Korean comparison accepts title spacing variants`() {
        val hit = WebSearchHit(
            title = "그대들은 어떻게 살 것인가",
            link = "https://film.example/a",
            snippet = "미야자키 하야오의 애니메이션 영화입니다.",
        )

        assertTrue(
            WebSearchQueryRelevance.hasRelevantHit(
                "그대들은 어떻게 살것인가 영화",
                listOf(hit),
            ),
        )
    }

    @Test
    fun `two unrelated domains do not pass a multi-term query quality gate`() {
        val hits = listOf(
            WebSearchHit("오늘 날씨", "https://one.example/a", "서울 기온입니다."),
            WebSearchHit("증시 뉴스", "https://two.example/b", "주가 소식입니다."),
        )

        assertFalse(
            WebSearchQueryRelevance.hasRelevantHit(
                "그대들은 어떻게 살것인가 영화",
                hits,
            ),
        )
    }

    @Test
    fun `short ambiguous query keeps the existing provider shape gate`() {
        assertTrue(
            WebSearchQueryRelevance.hasRelevantHit(
                "업",
                listOf(WebSearchHit("Up", "https://film.example/up", "애니메이션 영화")),
            ),
        )
    }

    @Test
    fun `single meaningful term still requires a lexical match`() {
        val unrelated = WebSearchHit(
            "오늘 날씨",
            "https://weather.example/today",
            "서울 기온과 강수 정보입니다.",
        )
        val related = WebSearchHit(
            "인터스텔라 작품 정보",
            "https://film.example/interstellar",
            "우주를 배경으로 한 영화입니다.",
        )

        assertFalse(WebSearchQueryRelevance.hasRelevantHit("인터스텔라", listOf(unrelated)))
        assertTrue(WebSearchQueryRelevance.hasRelevantHit("인터스텔라", listOf(related)))
    }
}
