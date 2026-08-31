package com.personaledge.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchResponseContractTest {
    @Test
    fun `english one sentence instruction is separated from the search subject`() {
        val contract = WebSearchResponseContract.fromOwnerRequest(
            "OpenAI 최신 소식을 검색해서 영어 한 문장으로 요약해줘",
        )

        assertEquals(WebSearchResponseContract.Language.ENGLISH, contract.language)
        assertEquals(1, contract.exactSentenceCount)
        assertFalse(contract.sourcesOnly)
    }

    @Test
    fun `korean multi sentence and sources only instructions are closed`() {
        val korean = WebSearchResponseContract.fromOwnerRequest(
            "OpenAI를 검색해서 한국어 두 문장으로 정리해줘",
        )
        val sources = WebSearchResponseContract.fromOwnerRequest(
            "OpenAI를 검색해서 출처만 보여줘",
        )

        assertEquals(WebSearchResponseContract.Language.KOREAN, korean.language)
        assertEquals(2, korean.exactSentenceCount)
        assertTrue(sources.sourcesOnly)
    }

    @Test
    fun `subject vocabulary before search action is not treated as an output instruction`() {
        val contract = WebSearchResponseContract.fromOwnerRequest(
            "영어 한 문장이라는 표현을 검색해줘",
        )

        assertEquals(WebSearchResponseContract.Language.KOREAN, contract.language)
        assertNull(contract.exactSentenceCount)
        assertFalse(contract.sourcesOnly)
    }

    @Test
    fun `conflicting language request fails closed to the default language`() {
        val contract = WebSearchResponseContract.fromOwnerRequest(
            "OpenAI를 검색해서 영어와 한국어로 정리해줘",
        )

        assertEquals(WebSearchResponseContract.Language.KOREAN, contract.language)
    }
}
