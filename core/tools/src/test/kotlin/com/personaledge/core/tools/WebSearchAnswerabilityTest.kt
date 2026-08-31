package com.personaledge.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchAnswerabilityTest {
    @Test
    fun `role to name assertion never returns the requested entity`() {
        val evidence = requireNotNull(
            WebSearchAnswerability.currentOfficeholderEvidenceOrNull(
                QUERY,
                hit(
                    link = "https://news.example/current-mayor",
                    snippet = "현재 한강시 시장은 홍길동입니다.",
                ),
            ),
        )

        assertEquals("홍길동", evidence.name)
        assertFalse(evidence.authoritative)
    }

    @Test
    fun `actual name before role assertion remains supported`() {
        val evidence = requireNotNull(
            WebSearchAnswerability.currentOfficeholderEvidenceOrNull(
                QUERY,
                hit(
                    link = "https://news.example/current-mayor",
                    snippet = "현재 홍길동 시장이 한강시를 이끌고 있습니다.",
                ),
            ),
        )

        assertEquals("홍길동", evidence.name)
    }

    @Test
    fun `entity only and office background are not person evidence`() {
        val nonAnswers = listOf(
            hit(
                link = "https://hanriver.go.kr/mayor",
                snippet = "한강시 시장입니다.",
            ),
            hit(
                link = "https://hanriver.go.kr/mayor/duties",
                snippet = "한강시 시장은 지역 행정을 총괄합니다.",
            ),
        )

        nonAnswers.forEach { candidate ->
            assertNull(
                candidate.snippet,
                WebSearchAnswerability.currentOfficeholderEvidenceOrNull(QUERY, candidate),
            )
        }
    }

    @Test
    fun `government authority accepts only exact gov and go kr suffix boundaries`() {
        val authoritativeLinks = listOf(
            "https://hanriver.go.kr/mayor",
            "https://www.agency.gov/mayor",
        )

        authoritativeLinks.forEach { link ->
            val evidence = requireNotNull(
                WebSearchAnswerability.currentOfficeholderEvidenceOrNull(
                    QUERY,
                    hit(link = link, snippet = "현재 한강시 시장은 홍길동입니다."),
                ),
                { "expected authoritative evidence for $link" },
            )
            assertEquals("홍길동", evidence.name)
            assertTrue(link, evidence.authoritative)
        }
    }

    @Test
    fun `official page without an explicit current marker is stale not current evidence`() {
        listOf(
            "https://hanriver.go.kr/mayor",
            "https://www.agency.gov/mayor",
            "https://korea.kr/mayor",
        ).forEach { link ->
            assertNull(
                link,
                WebSearchAnswerability.currentOfficeholderEvidenceOrNull(
                    QUERY,
                    hit(link = link, snippet = "한강시 시장은 홍길동입니다."),
                ),
            )
        }
    }

    @Test
    fun `past appointment event words never make an old officeholder current`() {
        listOf(
            "2022년 한강시 시장은 홍길동입니다. 2022년에 당선되었습니다.",
            "2022년 한강시 시장은 홍길동입니다. 2022년에 취임했습니다.",
            "2017년부터 2022년까지 한강시 시장은 홍길동이며 재임했습니다.",
            "2017년부터 한강시 시장은 홍길동이며 시정을 이끌고 있습니다.",
            "2017년부터 한강시 시장은 홍길동이며 직무를 맡고 있습니다.",
        ).forEach { snippet ->
            assertNull(
                snippet,
                WebSearchAnswerability.currentOfficeholderEvidenceOrNull(
                    QUERY,
                    hit(link = "https://hanriver.go.kr/archive", snippet = snippet),
                ),
            )
        }
    }

    @Test
    fun `current role to spaced or Latin person names is supported without using the entity`() {
        val cases = mapOf(
            "OpenAI의 current CEO is Sam Altman." to "Sam Altman",
            "OpenAI의 현재 CEO는 샘 올트먼입니다." to "샘 올트먼",
        )

        cases.forEach { (snippet, expectedName) ->
            val evidence = requireNotNull(
                WebSearchAnswerability.currentOfficeholderEvidenceOrNull(
                    "OpenAI 현직 ceo 이름 공식",
                    WebSearchHit(
                        title = "OpenAI leadership",
                        link = "https://openai.com/about",
                        snippet = snippet,
                    ),
                ),
                { "expected safe name evidence for $snippet" },
            )
            assertEquals(expectedName, evidence.name)
            assertFalse(evidence.authoritative)
        }
    }

    @Test
    fun `Latin background prose and entity tokens are never person names`() {
        listOf(
            "OpenAI current CEO information is not available.",
            "OpenAI current CEO is OpenAI.",
            "OpenAI current CEO is Chief Executive Officer.",
        ).forEach { snippet ->
            assertNull(
                snippet,
                WebSearchAnswerability.currentOfficeholderEvidenceOrNull(
                    "OpenAI 현직 ceo 이름 공식",
                    WebSearchHit(
                        title = "OpenAI leadership",
                        link = "https://example.test/openai",
                        snippet = snippet,
                    ),
                ),
            )
        }
    }

    @Test
    fun `government looking labels inside deceptive hosts are not authoritative`() {
        val deceptiveLinks = listOf(
            "https://agency.gov.example.com/mayor",
            "https://president.go.kr.example.com/mayor",
        )

        deceptiveLinks.forEach { link ->
            assertNull(
                link,
                WebSearchAnswerability.currentOfficeholderEvidenceOrNull(
                    QUERY,
                    hit(link = link, snippet = "한강시 시장은 홍길동입니다."),
                ),
            )
            val currentEvidence = requireNotNull(
                WebSearchAnswerability.currentOfficeholderEvidenceOrNull(
                    QUERY,
                    hit(link = link, snippet = "현재 한강시 시장은 홍길동입니다."),
                ),
            )
            assertFalse(link, currentEvidence.authoritative)
        }
    }

    private fun hit(link: String, snippet: String): WebSearchHit = WebSearchHit(
        title = "한강시 시장 안내",
        link = link,
        snippet = snippet,
    )

    private companion object {
        const val QUERY = "한강시 현직 시장 이름 공식"
    }
}
