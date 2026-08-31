package com.personaledge.core.agent

import com.personaledge.core.llm.TrustedToolResponseBudget
import com.personaledge.core.tools.CalendarEventSummary
import com.personaledge.core.tools.CalendarQueryResult
import com.personaledge.core.tools.CapturedMessageSummary
import com.personaledge.core.tools.FakeArrivalNoticeResult
import com.personaledge.core.tools.CommitmentProposalResult
import com.personaledge.core.tools.CommitmentProposalWriteOutcome
import com.personaledge.core.tools.MemoryRememberResult
import com.personaledge.core.tools.MemoryWriteOutcome
import com.personaledge.core.tools.NotificationSearchResult
import com.personaledge.core.tools.ReminderSummary
import com.personaledge.core.tools.ReminderToolPrecision
import com.personaledge.core.tools.WebSearchHit
import com.personaledge.core.tools.WebSearchProvider
import com.personaledge.core.tools.WebSearchResult
import com.personaledge.core.tools.WeatherResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictToolArgumentsTest {
    private val parser = FakeArrivalNoticeArgumentsParser(maxArgumentBytes = 256)

    @Test
    fun `valid flat object decodes strings and unicode escapes`() {
        val result = parser.parse(
            """ {"recipient":"\uC544\uB0B4","message":"30\uBD84 \"뒤\" 도착"} """,
        ) as ToolArgumentsParseResult.Valid

        assertEquals("아내", result.params.recipient)
        assertEquals("30분 \"뒤\" 도착", result.params.message)
    }

    @Test
    fun `field order does not matter`() {
        val result = parser.parse("""{"message":"soon","recipient":"wife"}""")

        assertTrue(result is ToolArgumentsParseResult.Valid)
    }

    @Test
    fun `unknown extra and escaped duplicate fields are rejected`() {
        assertInvalid(
            """{"recipient":"wife","message":"soon","urgent":"true"}""",
            ToolArgumentsError.UNKNOWN_FIELD,
        )
        assertInvalid(
            """{"recipient":"wife","message":"soon","\u0072ecipient":"other"}""",
            ToolArgumentsError.DUPLICATE_FIELD,
        )
    }

    @Test
    fun `missing and wrong typed fields are rejected`() {
        assertInvalid("""{"recipient":"wife"}""", ToolArgumentsError.MISSING_FIELD)
        assertInvalid(
            """{"recipient":"wife","message":30}""",
            ToolArgumentsError.WRONG_TYPE,
        )
        assertInvalid(
            """{"recipient":null,"message":"soon"}""",
            ToolArgumentsError.WRONG_TYPE,
        )
    }

    @Test
    fun `nested objects and arrays are rejected`() {
        assertInvalid(
            """{"recipient":{"name":"wife"},"message":"soon"}""",
            ToolArgumentsError.NESTING_NOT_ALLOWED,
        )
        assertInvalid(
            """{"recipient":"wife","message":["soon"]}""",
            ToolArgumentsError.NESTING_NOT_ALLOWED,
        )
    }

    @Test
    fun `bidi and invisible presentation controls are rejected`() {
        assertInvalid(
            """{"recipient":"wife","message":"safe\u202Etxt"}""",
            ToolArgumentsError.UNSAFE_TEXT,
        )
        assertInvalid(
            """{"recipient":"wi\u200Bfe","message":"soon"}""",
            ToolArgumentsError.UNSAFE_TEXT,
        )
        assertInvalid(
            """{"recipient":"wife","message":"first\u2028second"}""",
            ToolArgumentsError.UNSAFE_TEXT,
        )
    }

    @Test
    fun `pinned model control delimiters are rejected directly and after unicode decoding`() {
        assertInvalid(
            """{"recipient":"wife","message":"<|tool_response|>"}""",
            ToolArgumentsError.UNSAFE_TEXT,
        )
        assertInvalid(
            """{"recipient":"wife","message":"\u003C\u007Ctool_response\u007C\u003E"}""",
            ToolArgumentsError.UNSAFE_TEXT,
        )
        assertInvalid(
            """{"recipient":"wife|>","message":"soon"}""",
            ToolArgumentsError.UNSAFE_TEXT,
        )
    }

    @Test
    fun `oversized utf8 and malformed documents are rejected`() {
        assertInvalid(
            """{"recipient":"wife","message":"${"가".repeat(100)}"}""",
            ToolArgumentsError.OVERSIZED,
        )
        assertInvalid(
            """{"recipient":"wife","message":"soon"} trailing""",
            ToolArgumentsError.MALFORMED_JSON,
        )
        assertInvalid(
            """{"recipient":"wife","message":"\uD800"}""",
            ToolArgumentsError.MALFORMED_JSON,
        )
    }

    @Test
    fun `trusted result encoder does not echo model controlled arguments`() {
        val encoded = TrustedToolResultJson.encode(FakeArrivalNoticeResult())

        assertEquals("""{"simulated":true}""", encoded)
    }

    @Test
    fun `web search result carries closed provider provenance into the trusted response`() {
        val encoded = TrustedToolResultJson.encode(
            WebSearchResult(
                provider = WebSearchProvider.TAVILY,
                hits = listOf(WebSearchHit("제목", "https://example.com", "요약")),
            ),
        )

        assertEquals(
            """{"provider":"tavily","results":[{"title":"제목","link":"https://example.com","snippet":"요약"}],"truncated":false,"returned_count":1}""",
            encoded,
        )
    }

    @Test
    fun `web search result is bounded to the runtime Tool response reserve`() {
        val encoded = TrustedToolResultJson.encode(
            WebSearchResult(
                provider = WebSearchProvider.YOU_COM,
                hits = List(5) { index ->
                    WebSearchHit(
                        title = "제목$index" + "가".repeat(120),
                        link = "https://example.com/weather/$index",
                        snippet = "날씨 설명" + "나".repeat(240),
                    )
                },
            ),
        )

        assertTrue(
            encoded.toByteArray(Charsets.UTF_8).size <=
                TrustedToolResultJson.MAX_TRUSTED_WEB_RESULT_BYTES,
        )
        assertTrue(encoded.contains("\"truncated\":true"))
        assertEquals(encoded.count { it == '{' } - 1, returnedCount(encoded))
        assertTrue(encoded.count { it == '{' } <= 3)
    }

    @Test
    fun `all read list results keep whole records within the shared utf8 budget`() {
        val calendar = TrustedToolResultJson.encode(
            CalendarQueryResult(
                events = List(10) { index ->
                    CalendarEventSummary(
                        eventId = index.toLong(),
                        title = "일정-$index-${"가".repeat(12)}",
                        start = "2026-08-25T10:00+09:00[Asia/Seoul]",
                        end = "2026-08-25T11:00+09:00[Asia/Seoul]",
                        allDay = false,
                        location = "서울",
                        calendar = "기본",
                    )
                },
                truncated = false,
            ),
        )
        assertBudgetedRecords(calendar, "\"event_id\":", expectedInputCount = 10)

        val notifications = TrustedToolResultJson.encode(
            NotificationSearchResult(
                messages = List(10) { index ->
                    CapturedMessageSummary(
                        conversation = "대화-$index",
                        sender = "보낸이-$index",
                        text = "메시지-${"나".repeat(20)}",
                        receivedAt = "2026-08-25T10:00+09:00[Asia/Seoul]",
                    )
                },
                truncated = false,
            ),
        )
        assertBudgetedRecords(notifications, "\"conversation\":", expectedInputCount = 10)

        val web = TrustedToolResultJson.encode(
            WebSearchResult(
                provider = WebSearchProvider.YOU_COM,
                hits = List(5) { index ->
                    WebSearchHit(
                        title = "검색-$index-${"다".repeat(30)}",
                        link = "https://example.com/$index",
                        snippet = "검색 결과-${"라".repeat(60)}",
                    )
                },
            ),
        )
        assertBudgetedRecords(web, "\"title\":", expectedInputCount = 5)

        val reminders = TrustedToolResultJson.encode(
            List(10) { index ->
                ReminderSummary(
                    reminderId = "reminder-$index",
                    title = "리마인더-$index-${"마".repeat(12)}",
                    triggerAtEpochMillis = 1_777_777_777_000L + index,
                    zoneId = "Asia/Seoul",
                    recurrenceRule = null,
                    precision = ReminderToolPrecision.EXACT,
                    scheduleVersion = index.toLong() + 1,
                )
            },
        )
        assertBudgetedRecords(reminders, "\"reminder_id\":", expectedInputCount = 10)
    }

    @Test
    fun `weather arguments and result require concrete values plus a source URL`() {
        val parsed = WeatherArgumentsParser(maxArgumentBytes = 512).parse(
            """{"location":"동탄"}""",
        ) as ToolArgumentsParseResult.Valid
        assertEquals("동탄", parsed.params.location)
        assertTrue(
            WeatherArgumentsParser(512).parse(
                """{"location":"동탄","query":"날씨"}""",
            ) is ToolArgumentsParseResult.Invalid,
        )

        val encoded = TrustedToolResultJson.encode(
            WeatherResult(
                location = "대한민국 경기도 화성시 동탄",
                currentAt = "2026-08-25T14:15",
                condition = "구름 조금",
                temperatureCelsius = "29.4",
                apparentTemperatureCelsius = "31.2",
                relativeHumidityPercent = 68,
                precipitationMillimetres = "0.0",
                windSpeedKilometresPerHour = "8.7",
                todayMinimumCelsius = "23.1",
                todayMaximumCelsius = "31.8",
                todayPrecipitationProbabilityPercent = 40,
                sourceName = "Open-Meteo",
                sourceUrl = "https://open-meteo.com/",
            ),
        )

        assertTrue(encoded.contains("\"temperature_c\":\"29.4\""))
        assertTrue(encoded.contains("\"today_precipitation_probability_max_percent\":40"))
        assertTrue(encoded.contains("\"source_url\":\"https://open-meteo.com/\""))
        assertTrue(encoded.contains("The app renders this result"))
        assertTrue(encoded.contains("do not call another tool"))
    }

    @Test
    fun `Kakao communication arguments remain flat exact string contracts`() {
        val share = KakaoShareMessageArgumentsParser(1_024).parse(
            """{"recipient":"가족방","message":"안녕"}""",
        ) as ToolArgumentsParseResult.Valid
        assertEquals("가족방", share.params.recipient)
        assertEquals("안녕", share.params.message)

        val reply = KakaoNotificationReplyArgumentsParser(1_024).parse(
            """{"recipient":"홍길동","message":"곧 도착해"}""",
        ) as ToolArgumentsParseResult.Valid
        assertEquals("홍길동", reply.params.recipient)
        assertEquals("곧 도착해", reply.params.message)

        assertTrue(
            KakaoNotificationReplyArgumentsParser(1_024).parse(
                """{"recipient":"홍길동","message":"안녕","auto_send":"true"}""",
            ) is ToolArgumentsParseResult.Invalid,
        )
    }

    @Test
    fun `memory parser accepts only one string content field`() {
        val parser = MemoryRememberArgumentsParser(maxArgumentBytes = 512)
        val valid = parser.parse("""{"content":"사용자는 민트색을 좋아한다."}""")
            as ToolArgumentsParseResult.Valid
        assertEquals("사용자는 민트색을 좋아한다.", valid.params.content)

        assertTrue(
            parser.parse("""{"content":"기억","secret":"x"}""") is
                ToolArgumentsParseResult.Invalid,
        )
    }

    @Test
    fun `memory result encoder never echoes stored content`() {
        assertEquals(
            """{"saved":true}""",
            TrustedToolResultJson.encode(MemoryRememberResult(MemoryWriteOutcome.SAVED)),
        )
        assertEquals(
            """{"saved":false,"reason":"capacity_reached"}""",
            TrustedToolResultJson.encode(
                MemoryRememberResult(MemoryWriteOutcome.CAPACITY_REACHED),
            ),
        )
    }

    @Test
    fun `commitment proposal parser remains flat and result states it was not scheduled`() {
        val parsed = CommitmentProposalArgumentsParser(maxArgumentBytes = 512).parse(
            """{"summary":"다음 주 보험 확인","proposed_at":"2026-08-30T09:00","zone_id":"Asia/Seoul"}""",
        ) as ToolArgumentsParseResult.Valid
        assertEquals("다음 주 보험 확인", parsed.params.summary)
        assertEquals("Asia/Seoul", parsed.params.zoneId)
        assertTrue(
            CommitmentProposalArgumentsParser(512).parse(
                """{"summary":"후보","schedule_now":"true"}""",
            ) is ToolArgumentsParseResult.Invalid,
        )
        assertEquals(
            """{"saved":true,"scheduled":false}""",
            TrustedToolResultJson.encode(
                CommitmentProposalResult(CommitmentProposalWriteOutcome.SAVED),
            ),
        )
    }

    @Test
    fun `dated requests cannot smuggle a date into next occurrence alarm`() {
        val alarm = AlarmSetArgumentsParser(maxArgumentBytes = 512).parse(
            """{"time":"07:00","date":"2026-08-24"}""",
        ) as ToolArgumentsParseResult.Invalid
        assertEquals(ToolArgumentsError.UNKNOWN_FIELD, alarm.error)

        val reminder = ReminderCreateArgumentsParser(maxArgumentBytes = 1_024).parse(
            """{"title":"병원","trigger_at":"2026-08-24T07:00","zone_id":"Asia/Seoul"}""",
        ) as ToolArgumentsParseResult.Valid
        assertEquals("2026-08-24T07:00", reminder.params.triggerAt)
    }

    private fun assertInvalid(json: String, expected: ToolArgumentsError) {
        val result = parser.parse(json) as ToolArgumentsParseResult.Invalid
        assertEquals(expected, result.error)
    }

    private fun assertBudgetedRecords(
        encoded: String,
        recordMarker: String,
        expectedInputCount: Int,
    ) {
        assertTrue(TrustedToolResponseBudget.allows(listOf(encoded)))
        assertTrue(encoded.endsWith('}'))
        assertTrue(encoded.contains("\"truncated\":true"))
        val returnedCount = returnedCount(encoded)
        assertTrue(returnedCount in 0 until expectedInputCount)
        assertEquals(returnedCount, Regex(Regex.escape(recordMarker)).findAll(encoded).count())
    }

    private fun returnedCount(encoded: String): Int = checkNotNull(
        Regex("\\\"returned_count\\\":(\\d+)").find(encoded),
    ).groupValues[1].toInt()
}
