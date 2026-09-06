package com.personaledge.core.agent

import com.personaledge.core.tools.AlarmNextResult
import com.personaledge.core.tools.CalendarEventSummary
import com.personaledge.core.tools.CalendarQueryResult
import com.personaledge.core.tools.ReminderSummary
import com.personaledge.core.tools.ReminderToolPrecision
import com.personaledge.core.tools.RouteEstimateResult
import com.personaledge.core.tools.WebSearchHit
import com.personaledge.core.tools.WebSearchProvider
import com.personaledge.core.tools.WebSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedEvidenceTest {
    @Test
    fun `calendar renderer distinguishes empty from truncated results`() {
        val empty = GroundedEvidenceRenderer.render(
            listOf(
                GroundedReadEvidence.CalendarQuery(
                    ordinal = 1,
                    result = CalendarQueryResult(events = emptyList(), truncated = false),
                ),
            ),
        )
        val truncated = GroundedEvidenceRenderer.render(
            listOf(
                GroundedReadEvidence.CalendarQuery(
                    ordinal = 1,
                    result = CalendarQueryResult(
                        events = List(9) { index -> calendarEvent(index) },
                        truncated = true,
                    ),
                ),
            ),
        )

        assertTrue(empty.contains("일정이 없습니다"))
        assertFalse(empty.contains("일부만"))
        assertTrue(truncated.contains("비밀 일정 0"))
        assertFalse(truncated.contains("비밀 일정 8"))
        assertTrue(truncated.contains("일부만 표시했습니다"))
    }

    @Test
    fun `two read evidence records render by execution ordinal not insertion order`() {
        val set = GroundedEvidenceSet()
        assertTrue(
            set.add(
                GroundedReadEvidence.RouteEstimate(
                    ordinal = 2,
                    result = RouteEstimateResult(
                        origin = "서울시청",
                        destination = "강남역",
                        durationMinutes = 31,
                        distanceKilometres = "12.4",
                    ),
                ),
            ),
        )
        assertTrue(
            set.add(
                GroundedReadEvidence.AlarmNext(
                    ordinal = 1,
                    result = AlarmNextResult(hasAlarm = false, triggerAt = null),
                ),
            ),
        )

        val rendered = set.renderTrustedAnswer()

        assertTrue(rendered.indexOf("조회 1 · 다음 알람") < rendered.indexOf("조회 2 · 이동 경로"))
        assertTrue(rendered.contains("다음 알람이 없습니다"))
        assertTrue(rendered.contains("31분"))
        assertTrue(rendered.contains("12.4km"))
    }

    @Test
    fun `reminder renderer is bounded and evidence metadata never stringifies raw values`() {
        val evidence = GroundedReadEvidence.ReminderQuery(
            ordinal = 1,
            reminders = List(9) { index ->
                ReminderSummary(
                    reminderId = "private-id-$index",
                    title = "비밀 리마인더 $index",
                    triggerAtEpochMillis = 1_788_157_800_000L + index * 60_000L,
                    zoneId = "Asia/Seoul",
                    recurrenceRule = null,
                    precision = ReminderToolPrecision.EXACT,
                    scheduleVersion = index + 1L,
                )
            },
        )

        val rendered = GroundedEvidenceRenderer.render(listOf(evidence))

        assertTrue(rendered.contains("비밀 리마인더 0"))
        assertFalse(rendered.contains("비밀 리마인더 8"))
        assertTrue(rendered.contains("일부만 표시했습니다"))
        assertFalse(evidence.toString().contains("비밀 리마인더"))
        assertFalse(evidence.toString().contains("private-id"))
    }

    @Test
    fun `person search answer filters obituary and name-list noise then puts sources last`() {
        val evidence = GroundedReadEvidence.WebSearch(
            ordinal = 1,
            query = "SK하이닉스 김재범",
            result = WebSearchResult(
                provider = WebSearchProvider.YOU_COM,
                hits = listOf(
                    WebSearchHit(
                        title = "SK하이닉스 김재범 부사장, 국가전략기술 공로 표창",
                        link = "https://news.skhynix.co.kr/official",
                        snippet = "SK하이닉스 미래기술연구원 김재범 부사장은 R&D 전략을 담당하며 공로 표창을 받았다.",
                    ),
                    WebSearchHit(
                        title = "김재범 부친상",
                        link = "https://obituary.example/kim",
                        snippet = "김재범 씨의 부친이 별세해 빈소가 마련됐다.",
                    ),
                    WebSearchHit(
                        title = "[인사] SK하이닉스",
                        link = "https://people.example/list",
                        snippet = "▲강준호 ▲권로미 ▲김재범 ▲김정우 임원 신규 선임 명단",
                    ),
                ),
            ),
            intent = WebSearchAnswerIntent.PERSON_LOOKUP,
        )

        val rendered = GroundedEvidenceRenderer.render(listOf(evidence))

        assertTrue(rendered.startsWith("공개 검색 자료에서 직접 관련된 내용만 추리면"))
        assertTrue(rendered.contains("미래기술연구원 김재범"))
        assertTrue(rendered.contains("\n\n출처\n1. "))
        assertTrue(rendered.contains("https://news.skhynix.co.kr/official"))
        assertFalse(rendered.contains("obituary.example"))
        assertFalse(rendered.contains("부친상"))
        assertFalse(rendered.contains("people.example"))
        assertFalse(rendered.contains("검색 제공:"))
        assertFalse(evidence.toString().contains("김재범"))
    }

    @Test
    fun `model web summary cannot replace app-owned links`() {
        val plan = WebSearchAnswerPolicy.prepare(
            query = "SK하이닉스 김재범",
            result = WebSearchResult(
                provider = WebSearchProvider.YOU_COM,
                hits = listOf(
                    WebSearchHit(
                        title = "SK하이닉스 김재범 부사장 공개 자료",
                        link = "https://news.skhynix.co.kr/source",
                        snippet = "SK하이닉스 김재범 부사장은 미래기술연구원에서 R&D 전략을 담당한다.",
                    ),
                ),
            ),
            intent = WebSearchAnswerIntent.PERSON_LOOKUP,
        )

        val accepted = WebSearchAnswerPolicy.answerFromModelOrNull(
            plan,
            "김재범은 SK하이닉스 미래기술연구원에서 R&D 전략을 담당하는 부사장입니다.",
        )

        requireNotNull(accepted)
        assertTrue(accepted.contains("https://news.skhynix.co.kr/source"))
        assertTrue(accepted.indexOf("김재범은") < accepted.indexOf("\n\n출처"))
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "김재범 관련 자료입니다. https://attacker.example/fake",
            ),
        )
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "김재범은 삼성전자 대표입니다.",
            ),
        )
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "1. 김재범은 SK하이닉스 부사장입니다. 2. 공개 자료입니다.",
            ),
        )
        val salvaged = requireNotNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "김재범은 SK하이닉스 미래기술연구원에서 R&D 전략을 담당하는 부사장입니다. " +
                    "김재범은 SK하이닉스 미래기술연구원에서",
            ),
        )
        assertTrue(salvaged.startsWith("김재범은 SK하이닉스 미래기술연구원"))
        assertFalse(salvaged.substringBefore("\n\n출처").endsWith("연구원에서"))
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "김재범은 SK하이닉스 미래기술연구원에서 R&D 전략을",
            ),
        )
    }

    @Test
    fun `general search keeps query-relevant sources without a person caveat`() {
        assertEquals(
            WebSearchAnswerIntent.GENERAL,
            WebSearchAnswerPolicy.intentForRequest("사람들이 많이 쓰는 AI 앱을 검색해줘"),
        )
        val plan = WebSearchAnswerPolicy.prepare(
            query = "OpenAI 최신 소식",
            result = WebSearchResult(
                provider = WebSearchProvider.TAVILY,
                hits = listOf(
                    WebSearchHit(
                        title = "OpenAI 새 모델 공개",
                        link = "https://openai.com/news/model",
                        snippet = "OpenAI는 새로운 모델을 공개했습니다.",
                    ),
                    WebSearchHit(
                        title = "OpenAI 개발자 문서 업데이트",
                        link = "https://platform.openai.com/docs/update",
                        snippet = "OpenAI 개발자 문서가 업데이트됐습니다.",
                    ),
                ),
            ),
            intent = WebSearchAnswerIntent.GENERAL,
        )

        assertEquals(2, plan.hits.size)
        val answer = WebSearchAnswerPolicy.answerFromModelOrNull(
            plan,
            "OpenAI는 새로운 모델을 공개했습니다. OpenAI 개발자 문서는 업데이트됐습니다.",
        )

        requireNotNull(answer)
        assertTrue(answer.startsWith("OpenAI는 새로운 모델"))
        assertTrue(answer.contains("\n\n출처\n1. OpenAI 새 모델 공개"))
        assertTrue(answer.contains("\n2. OpenAI 개발자 문서 업데이트"))
        assertFalse(answer.contains("동명이인"))
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "OpenAI는 개발자 문서를 공개했습니다.",
            ),
        )
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "공개 검색 자료에서 OpenAI 관련 내용을 확인했습니다.",
            ),
        )
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "OpenAI의 최신 정보를 확인했습니다.",
            ),
        )
    }

    @Test
    fun `latest version search rejects help prose and answers with the identified release`() {
        val plan = WebSearchAnswerPolicy.prepare(
            query = "Android 최신 버전",
            result = WebSearchResult(
                provider = WebSearchProvider.TAVILY,
                hits = listOf(
                    WebSearchHit(
                        title = "Android 버전 확인 및 업데이트",
                        link = "https://support.example/android/version-help",
                        snippet = "설정 앱에서 Android 버전을 확인하고 업데이트할 수 있습니다.",
                    ),
                    WebSearchHit(
                        title = "Android 17 release",
                        link = "https://developer.example/android/17",
                        snippet = "Android 17은 현재 최신 안정 버전입니다.",
                    ),
                ),
            ),
            intent = WebSearchAnswerIntent.GENERAL,
        )

        assertEquals(
            listOf("https://developer.example/android/17"),
            plan.hits.map(WebSearchHit::link),
        )
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "Android 버전은 설정 앱에서 확인할 수 있습니다.",
            ),
        )
        val answer = WebSearchAnswerPolicy.fallbackAnswer(plan)
        assertTrue(answer.startsWith("현재 Android의 최신 버전은 17입니다."))
        assertFalse(answer.contains("설정 앱"))
        assertTrue(answer.contains("https://developer.example/android/17"))
    }

    @Test
    fun `web synthesis enforces english one sentence owner contract`() {
        val plan = WebSearchAnswerPolicy.prepare(
            query = "OpenAI latest news",
            result = WebSearchResult(
                provider = WebSearchProvider.TAVILY,
                hits = listOf(
                    WebSearchHit(
                        title = "OpenAI releases Orion model",
                        link = "https://openai.com/news/orion",
                        snippet = "OpenAI released the Orion model for developers.",
                    ),
                ),
            ),
            intent = WebSearchAnswerIntent.GENERAL,
            responseContract = WebSearchResponseContract(
                language = WebSearchResponseContract.Language.ENGLISH,
                exactSentenceCount = 1,
            ),
        )

        val prompt = requireNotNull(WebSearchAnswerPolicy.synthesisPromptOrNull(plan))
        assertTrue(prompt.contains("영어로만"))
        assertTrue(prompt.contains("정확히 1개"))
        val accepted = requireNotNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "OpenAI released the Orion model for developers.",
            ),
        )
        assertTrue(accepted.startsWith("OpenAI released"))
        assertTrue(accepted.contains("\n\nSources\n1. "))
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "OpenAI는 Orion 모델을 개발자에게 공개했습니다.",
            ),
        )
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                plan,
                "OpenAI released the Orion model. The release targets developers.",
            ),
        )
    }

    @Test
    fun `sources only contract skips synthesis and returns no provider prose`() {
        val plan = WebSearchAnswerPolicy.prepare(
            query = "OpenAI latest news",
            result = WebSearchResult(
                provider = WebSearchProvider.TAVILY,
                hits = listOf(
                    WebSearchHit(
                        title = "OpenAI releases Orion model",
                        link = "https://openai.com/news/orion",
                        snippet = "Provider prose must not be rendered in sources-only mode.",
                    ),
                ),
            ),
            intent = WebSearchAnswerIntent.GENERAL,
            responseContract = WebSearchResponseContract(
                language = WebSearchResponseContract.Language.ENGLISH,
                sourcesOnly = true,
            ),
        )

        assertNull(WebSearchAnswerPolicy.synthesisPromptOrNull(plan))
        assertNull(WebSearchAnswerPolicy.answerFromModelOrNull(plan, "Provider prose."))
        val answer = WebSearchAnswerPolicy.fallbackAnswer(plan)
        assertTrue(answer.startsWith("Sources\n1. OpenAI releases Orion model"))
        assertTrue(answer.contains("https://openai.com/news/orion"))
        assertFalse(answer.contains("Provider prose must not be rendered"))
    }

    @Test
    fun `sources only contract states explicitly when no verified source survived filtering`() {
        val plan = WebSearchAnswerPolicy.prepare(
            query = "OpenAI latest news",
            result = WebSearchResult(
                provider = WebSearchProvider.TAVILY,
                hits = emptyList(),
            ),
            intent = WebSearchAnswerIntent.GENERAL,
            responseContract = WebSearchResponseContract(
                language = WebSearchResponseContract.Language.ENGLISH,
                sourcesOnly = true,
            ),
        )

        assertEquals("No verified sources were found.", WebSearchAnswerPolicy.fallbackAnswer(plan))
    }

    @Test
    fun `model synthesis cannot flip negative evidence into an affirmative claim`() {
        val english = WebSearchAnswerPolicy.prepare(
            query = "Acme feature availability",
            result = WebSearchResult(
                provider = WebSearchProvider.TAVILY,
                hits = listOf(
                    WebSearchHit(
                        title = "Acme feature availability",
                        link = "https://example.test/acme",
                        snippet = "The Acme feature is not available on Android.",
                    ),
                ),
            ),
            intent = WebSearchAnswerIntent.GENERAL,
            responseContract = WebSearchResponseContract(
                language = WebSearchResponseContract.Language.ENGLISH,
                exactSentenceCount = 1,
            ),
        )

        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                english,
                "The Acme feature is available on Android.",
            ),
        )
        assertTrue(
            requireNotNull(
                WebSearchAnswerPolicy.answerFromModelOrNull(
                    english,
                    "The Acme feature is not available on Android.",
                ),
            ).startsWith("The Acme feature is not available"),
        )

        val korean = WebSearchAnswerPolicy.prepare(
            query = "아크미 기능 사용 가능 여부",
            result = WebSearchResult(
                provider = WebSearchProvider.YOU_COM,
                hits = listOf(
                    WebSearchHit(
                        title = "아크미 기능 사용 안내",
                        link = "https://example.test/acme-ko",
                        snippet = "아크미 기능은 안드로이드에서 사용할 수 없습니다.",
                    ),
                ),
            ),
            intent = WebSearchAnswerIntent.GENERAL,
        )
        assertNull(
            WebSearchAnswerPolicy.answerFromModelOrNull(
                korean,
                "아크미 기능은 안드로이드에서 사용할 수 있습니다.",
            ),
        )
    }

    @Test
    fun `fallback keeps only complete provider sentences before app-owned sources`() {
        val plan = WebSearchAnswerPolicy.prepare(
            query = "OpenAI 모델",
            result = WebSearchResult(
                provider = WebSearchProvider.YOU_COM,
                hits = listOf(
                    WebSearchHit(
                        title = "OpenAI 모델 자료",
                        link = "https://openai.com/model",
                        snippet = "OpenAI는 모델을 공개했습니다. 개발자 문서에서는 새 모델을",
                    ),
                    WebSearchHit(
                        title = "OpenAI 모델 추가 자료",
                        link = "https://platform.openai.com/model",
                        snippet = "OpenAI 모델 관련 설명이 이어지지만 문장 종결이 없는 요약",
                    ),
                ),
            ),
            intent = WebSearchAnswerIntent.GENERAL,
        )

        val answer = WebSearchAnswerPolicy.fallbackAnswer(plan)
        val body = answer.substringBefore("\n\n출처")

        assertTrue(body.contains("OpenAI는 모델을 공개했습니다."))
        assertFalse(body.contains("개발자 문서에서는"))
        assertFalse(body.contains("문장 종결이 없는 요약"))
        assertTrue(body.trimEnd().endsWith('.'))
    }

    @Test
    fun `model text is never accepted when relevance filtering selected no evidence`() {
        val plan = WebSearchAnswerPolicy.prepare(
            query = "OpenAI 최신 소식",
            result = WebSearchResult(
                provider = WebSearchProvider.YOU_COM,
                hits = listOf(
                    WebSearchHit(
                        title = "전혀 다른 주제",
                        link = "https://example.com/unrelated",
                        snippet = "이 문서는 요리법을 설명합니다.",
                    ),
                ),
            ),
            intent = WebSearchAnswerIntent.GENERAL,
        )

        assertTrue(plan.hits.isEmpty())
        assertNull(WebSearchAnswerPolicy.answerFromModelOrNull(plan, "OpenAI가 새 모델을 공개했습니다."))
    }

    private fun calendarEvent(index: Int): CalendarEventSummary = CalendarEventSummary(
        eventId = index + 1L,
        title = "비밀 일정 $index",
        start = "2026-08-25T0${index.coerceAtMost(9)}:00",
        end = "2026-08-25T0${index.coerceAtMost(9)}:30",
        allDay = false,
        location = null,
        calendar = "개인",
    )
}
