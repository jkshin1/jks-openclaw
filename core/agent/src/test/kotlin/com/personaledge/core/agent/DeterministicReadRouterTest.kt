package com.personaledge.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicReadRouterTest {
    @Test
    fun `closed grammar accepts command-first and query-first reads`() {
        assertEquals(
            "동탄",
            (DeterministicReadRouter.classify("날씨 알려줘 동탄", false)
                as DeterministicReadRequest.Weather).location,
        )
        assertEquals(
            "동탄",
            (DeterministicReadRouter.classify("오늘 동탄 날씨를 알려줘", false)
                as DeterministicReadRequest.Weather).location,
        )
        assertEquals(
            "OpenAI",
            (DeterministicReadRouter.classify("검색해줘: OpenAI", false)
                as DeterministicReadRequest.WebSearch).query,
        )
        assertEquals(
            "OpenAI",
            (DeterministicReadRouter.classify("OpenAI를 웹에서 검색해줘", false)
                as DeterministicReadRequest.WebSearch).query,
        )
        assertEquals(
            DeterministicReadRequest.AlarmNext,
            DeterministicReadRouter.classify("다음 알람 언제야?", false),
        )
        assertEquals(
            DeterministicReadRequest.ReminderQuery,
            DeterministicReadRouter.classify("예정된 리마인더 목록 보여줘", false),
        )
    }

    @Test
    fun `compound or mutating requests never take a partial direct path`() {
        listOf(
            "동탄 날씨와 내일 일정 알려줘",
            "SK하이닉스 검색하고 내일 일정도 알려줘",
            "다음 알람을 확인하고 07:30 알람 등록해 줘",
            "리마인더 목록을 보고 하나 삭제해 줘",
            "날씨 알려줘 서울과 동탄",
            "검색해줘: OpenAI\u202E",
            "검색결과를 정리해서 요약해줘",
            "위 검색 결과 핵심만 알려줘",
            "방금 찾은 내용에서 출처만 정리해줘",
        ).forEach { prompt ->
            assertFalse(prompt, DeterministicReadRouter.canRunWithoutModel(prompt))
        }
    }

    @Test
    fun `person requests reduce to name and organization whatever the reference phrasing`() {
        // "에 대해" was trimmed and "에 대한" was not, so this phrasing reached the provider as the
        // literal sentence fragment and returned nothing for a subject with wide coverage.
        listOf(
            "SK하이닉스 곽노정이란 사람에 대한 정보를 찾아서 알려줘",
            "SK하이닉스 곽노정이란 사람에 대해 조사해서 알려줘",
            "SK하이닉스 곽노정에 관한 자료 찾아줘",
        ).forEach { request ->
            assertEquals(request, "SK하이닉스 곽노정", explicitWebSearchQueryOrNull(request))
        }
        assertEquals("곽노정", explicitWebSearchQueryOrNull("곽노정에 대한 정보를 검색해줘"))
    }

    @Test
    fun `trailing trimming never eats an ordinary word ending`() {
        assertEquals(
            "삼성전자 주가",
            explicitWebSearchQueryOrNull("삼성전자 주가 검색해줘"),
        )
        assertEquals(
            "테슬라 관련 최신 뉴스",
            explicitWebSearchQueryOrNull("테슬라 관련 최신 뉴스 검색해줘"),
        )
        assertEquals(
            "리튬 배터리 화재 원인",
            explicitWebSearchQueryOrNull("리튬 배터리 화재 원인 조사해줘"),
        )
    }

    @Test
    fun `previous-result transforms are not new queries but explicit research remains searchable`() {
        assertEquals(
            null,
            explicitWebSearchQueryOrNull("검색결과를 정리해서 요약해줘"),
        )
        assertEquals(
            null,
            explicitWebSearchQueryOrNull("위 검색 결과를 비교해서 설명해줘"),
        )
        assertEquals(
            "SK하이닉스 김재범 최신 소식",
            explicitWebSearchQueryOrNull("SK하이닉스 김재범 최신 소식을 다시 검색해줘"),
        )
    }

    @Test
    fun `a subjectless web follow-up is never sent as its literal condition`() {
        val followUp = "잘 모르겠으면 웹에서 찾아서 알려줘"

        assertEquals(null, explicitWebSearchQueryOrNull(followUp))
        assertFalse(DeterministicReadRouter.canRunWithoutModel(followUp))
    }

    @Test
    fun `public predicate exposes only deterministic availability`() {
        listOf(
            "날씨 알려줘 동탄",
            "검색해줘: OpenAI",
            "다음 알람 확인",
            "리마인더 목록",
        ).forEach { prompt ->
            assertTrue(prompt, DeterministicReadRouter.canRunWithoutModel(prompt))
        }
        assertFalse(DeterministicReadRouter.canRunWithoutModel("이 요청을 알아서 처리해 줘"))
    }

    @Test
    fun `ephemeral direct requests redact arguments from descriptions`() {
        val weather = DeterministicReadRouter.classify("날씨 알려줘 비밀장소", false)
        val web = DeterministicReadRouter.classify("검색해줘: 비밀검색어", false)

        assertFalse(weather.toString().contains("비밀장소"))
        assertFalse(web.toString().contains("비밀검색어"))
    }
}
