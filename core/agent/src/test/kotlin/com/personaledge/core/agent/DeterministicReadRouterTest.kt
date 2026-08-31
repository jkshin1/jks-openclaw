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
        assertFalse(
            PriorWebResultFollowUpPolicy.matches("그 결과를 설명하는 자료를 웹에서 검색해줘"),
        )
        assertEquals(
            "그 결과를 설명하는 자료",
            explicitWebSearchQueryOrNull("그 결과를 설명하는 자료를 웹에서 검색해줘"),
        )
        assertEquals(
            "오펜하이머 영화",
            explicitWebSearchQueryOrNull("웹 검색을 활용해서 오펜하이머 영화 정보를 찾아줘"),
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
            "현재 대한민국 대통령이 누구야?",
        ).forEach { prompt ->
            assertTrue(prompt, DeterministicReadRouter.canRunWithoutModel(prompt))
        }
        assertFalse(DeterministicReadRouter.canRunWithoutModel("이 요청을 알아서 처리해 줘"))
    }

    @Test
    fun `current officeholder request is a deterministic mandatory web read`() {
        val request = DeterministicReadRouter.classify(
            "현재 대한민국 대통령이 누구야?",
            recentWeatherRead = false,
        ) as DeterministicReadRequest.WebSearch

        assertEquals("대한민국 현직 대통령 이름 공식", request.query)
        assertEquals(WebSearchAnswerIntent.CURRENT_OFFICEHOLDER, request.intent)
    }

    @Test
    fun `closed volatile facts are deterministic web reads`() {
        val cases = mapOf(
            "OpenAI 최신 뉴스" to "OpenAI 최신 뉴스",
            "비트코인 시세" to "비트코인 시세",
            "원달러 환율" to "원달러 환율",
            "한국은행 기준금리" to "한국은행 기준금리",
            "KBO 순위" to "KBO 순위",
            "삼성전자 최근 실적" to "삼성전자 최근 실적",
        )

        cases.forEach { (prompt, expectedQuery) ->
            val request = DeterministicReadRouter.classify(prompt, false)
                as DeterministicReadRequest.WebSearch
            assertEquals(prompt, expectedQuery, request.query)
            assertEquals(prompt, WebSearchAnswerIntent.GENERAL, request.intent)
            assertTrue(prompt, DeterministicReadRouter.canRunWithoutModel(prompt))
        }
    }

    @Test
    fun `volatile and correction direct routes fail closed without one safe subject`() {
        listOf(
            "최신 뉴스",
            "현재 주가를 알려줘",
            "내 비트코인 시세",
            "내프로젝트 최신 뉴스",
            "내 비밀번호 12345678 최신 뉴스",
            "우리회사 최신 실적",
            "OpenAI 최신 뉴스를 저장해",
            "OpenAI 최신 뉴스와 비트코인 시세",
            "OpenAI 최신 뉴스와 내일 일정 알려줘",
            "웹 검색을 더 잘해봐",
            "검색어를 바꿔서 다시 찾아줘",
            "웹 검색 할 수 있잖아",
        ).forEach { prompt ->
            assertEquals(prompt, null, DeterministicReadRouter.classify(prompt, false))
        }

        assertEquals(
            "OpenAI",
            (DeterministicReadRouter.classify("OpenAI를 다시 검색해줘", false)
                as DeterministicReadRequest.WebSearch).query,
        )
    }

    @Test
    fun `ephemeral direct requests redact arguments from descriptions`() {
        val weather = DeterministicReadRouter.classify("날씨 알려줘 비밀장소", false)
        val web = DeterministicReadRouter.classify("검색해줘: 비밀검색어", false)

        assertFalse(weather.toString().contains("비밀장소"))
        assertFalse(web.toString().contains("비밀검색어"))
    }
}
