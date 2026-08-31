package com.personaledge.core.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebSearchGatewaysTest {
    private data class RecordedRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?,
    )

    private class FakeHttpTransport(
        private val handler: suspend (RecordedRequest) -> HttpResponse,
    ) : HttpTransport {
        val requests = mutableListOf<RecordedRequest>()

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
            execute(RecordedRequest("GET", url, headers, null))

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse = execute(RecordedRequest("POST", url, headers, body))

        private suspend fun execute(request: RecordedRequest): HttpResponse {
            requests += request
            return handler(request)
        }
    }

    private class ConcurrencyTracker {
        var active = 0
        var maximum = 0
        val events = mutableListOf<String>()
    }

    private class FakeSearchGateway(
        private val label: String,
        private val responseProvider: WebSearchProvider,
        private val hits: List<WebSearchHit> = emptyList(),
        private val hasCredentials: Boolean = true,
        private val failure: ToolExecutionException? = null,
        private val delayMillis: Long = 0L,
        private val tracker: ConcurrencyTracker? = null,
    ) : WebSearchGateway {
        var searchCalls = 0
        var credentialChecks = 0

        override suspend fun credentialsPresent(): Boolean {
            credentialChecks += 1
            return hasCredentials
        }

        override suspend fun search(query: String, limit: Int): WebSearchResponse {
            searchCalls += 1
            tracker?.let { state ->
                state.active += 1
                state.maximum = maxOf(state.maximum, state.active)
                state.events += "$label:start"
            }
            try {
                if (delayMillis > 0) delay(delayMillis)
                failure?.let { throw it }
                return WebSearchResponse(responseProvider, hits)
            } finally {
                tracker?.let { state ->
                    state.events += "$label:end"
                    state.active -= 1
                }
            }
        }
    }

    @Test
    fun `you call is hard scoped to free you-search with Korean strict arguments`() = runBlocking {
        val transport = FakeHttpTransport { youSuccess(youPayload()) }
        val gateway = YouKeylessMcpWebSearchGateway(transport)

        gateway.search("한국 \"AI\" 소식", limit = 3)

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.you.com/mcp?profile=free", request.url)
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("application/json, text/event-stream", request.headers["Accept"])
        assertEquals("2025-06-18", request.headers["MCP-Protocol-Version"])
        assertFalse(request.headers.keys.any { it.equals("Authorization", ignoreCase = true) })

        val document = JsonParser.parseString(request.body).asJsonObject
        assertEquals("2.0", document["jsonrpc"].asString)
        assertEquals(1L, document["id"].asLong)
        assertEquals("tools/call", document["method"].asString)
        val params = document["params"].asJsonObject
        assertEquals("you-search", params["name"].asString)
        val arguments = params["arguments"].asJsonObject
        assertEquals("한국 \"AI\" 소식", arguments["query"].asString)
        assertEquals(3, arguments["count"].asInt)
        assertEquals("KR", arguments["country"].asString)
        assertEquals("KO", arguments["language"].asString)
        assertEquals("strict", arguments["safesearch"].asString)
    }

    @Test
    fun `you parses final SSE result across web and news and sanitizes hostile data`() = runBlocking {
        val payload = """
            {"results":{
              "web":[
                {"title":"<b>첫째</b>","url":"https://one.example/a","description":"<|tool|> 요약"},
                {"title":"중복","url":"https://one.example/a","description":"중복"},
                {"title":"HTTP","url":"http://insecure.example/x","description":"제외"}
              ],
              "news":[
                {"title":"둘째","url":"https://two.example/뉴스/../b","snippets":["뉴스 요약"]},
                {"title":"스크립트","url":"javascript:alert(1)","description":"제외"}
              ]
            }}
        """.trimIndent()
        val transport = FakeHttpTransport { youSuccess(payload) }

        val response = YouKeylessMcpWebSearchGateway(transport).search("AI 소식", limit = 5)

        assertEquals(WebSearchProvider.YOU_COM, response.provider)
        assertEquals(listOf("첫째", "둘째"), response.hits.map(WebSearchHit::title))
        assertEquals(listOf("요약", "뉴스 요약"), response.hits.map(WebSearchHit::snippet))
        assertEquals(
            "https://two.example/b",
            response.hits.last().link,
        )
        response.hits.forEach { hit ->
            assertFalse(hit.title.contains("<|"))
            assertFalse(hit.snippet.contains("|>"))
            assertTrue(hit.link.startsWith("https://"))
        }
    }

    @Test
    fun `you falls back to MCP content text only when structured content is absent`() = runBlocking {
        val contentPayload = """
            {"results":{"web":[{"title":"텍스트 결과","url":"https://example.com/a",
            "description":"텍스트 요약"}],"news":[]}}
        """.trimIndent()
        val transport = FakeHttpTransport { youSuccess(structuredPayload = null, contentPayload) }

        val response = YouKeylessMcpWebSearchGateway(transport).search("정확한 검색", 5)

        assertEquals("텍스트 결과", response.hits.single().title)
        assertEquals("텍스트 요약", response.hits.single().snippet)
    }

    @Test
    fun `you accepts a valid empty result without treating provider prose as a hit`() = runBlocking {
        val transport = FakeHttpTransport {
            youSuccess(
                structuredPayload = """{"results":{"web":[],"news":[]}}""",
                contentPayload = """{"results":{"web":[{"title":"ignored","url":"https://ignored.example","description":"ignored"}]}}""",
            )
        }

        val response = YouKeylessMcpWebSearchGateway(transport).search("없는 결과", 5)

        assertTrue(response.hits.isEmpty())
    }

    @Test
    fun `you JSON-RPC and malformed responses fail with closed reasons`() {
        val invalidRequestTransport = FakeHttpTransport {
            HttpResponse(
                200,
                "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602," +
                    "\"message\":\"provider-secret\"}}\n\n",
            )
        }
        val invalid = assertThrows(RemoteServiceException::class.java) {
            runBlocking { YouKeylessMcpWebSearchGateway(invalidRequestTransport).search("q", 5) }
        }
        assertEquals(ToolFailureCode.INVALID_REQUEST, invalid.failureCode)
        assertFalse(invalid.message.orEmpty().contains("provider-secret"))

        val malformedTransport = FakeHttpTransport {
            HttpResponse(200, "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}\n\n")
        }
        val malformed = assertThrows(RemoteServiceException::class.java) {
            runBlocking { YouKeylessMcpWebSearchGateway(malformedTransport).search("q", 5) }
        }
        assertEquals(ToolFailureCode.MALFORMED_RESPONSE, malformed.failureCode)
    }

    @Test
    fun `you HTTP failures map without returning provider bodies`() {
        val cases = mapOf(
            400 to ToolFailureCode.INVALID_REQUEST,
            401 to ToolFailureCode.AUTHENTICATION_FAILED,
            403 to ToolFailureCode.PERMISSION_DENIED,
            404 to ToolFailureCode.ENDPOINT_NOT_FOUND,
            405 to ToolFailureCode.CLIENT_POLICY_FAILURE,
            413 to ToolFailureCode.REQUEST_TOO_LARGE,
            429 to ToolFailureCode.RATE_LIMITED,
            500 to ToolFailureCode.PROVIDER_UNAVAILABLE,
            504 to ToolFailureCode.PROVIDER_TIMEOUT,
        )

        cases.forEach { (status, code) ->
            val transport = FakeHttpTransport {
                HttpResponse(status, """{"message":"provider-secret"}""")
            }
            val failure = assertThrows(RemoteServiceException::class.java) {
                runBlocking { YouKeylessMcpWebSearchGateway(transport).search("q", 5) }
            }
            assertEquals("HTTP $status", code, failure.failureCode)
            assertFalse(failure.message.orEmpty().contains("provider-secret"))
        }
    }

    @Test
    fun `tavily sends key only in bearer header with bounded basic search settings`() = runBlocking {
        val apiKey = "tvly-private-test-key"
        val transport = FakeHttpTransport { tavilySuccess() }

        val response = TavilyWebSearchGateway(transport) { apiKey }.search("한국 경제", 4)

        assertEquals(WebSearchProvider.TAVILY, response.provider)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.tavily.com/search", request.url)
        assertEquals("Bearer $apiKey", request.headers["Authorization"])
        assertFalse(request.url.contains(apiKey))
        assertFalse(request.body.orEmpty().contains(apiKey))
        val body = JsonParser.parseString(request.body).asJsonObject
        assertEquals("한국 경제", body["query"].asString)
        assertEquals("basic", body["search_depth"].asString)
        assertEquals("general", body["topic"].asString)
        assertEquals(4, body["max_results"].asInt)
        assertEquals("south korea", body["country"].asString)
        // Tavily currently documents safe_search as Enterprise-only. Omitting it keeps the
        // optional fallback compatible with the free Researcher plan.
        assertFalse(body.has("safe_search"))
        listOf("include_answer", "include_raw_content", "include_images", "include_usage").forEach {
            assertFalse(it, body[it].asBoolean)
        }
    }

    @Test
    fun `tavily sanitizes and deduplicates results and refuses non-HTTPS links`() = runBlocking {
        val body = """
            {"results":[
              {"title":"<b>정상</b>","url":"https://example.com/a","content":"<|tool|> 요약"},
              {"title":"중복","url":"https://example.com/a","content":"중복"},
              {"title":"평문","url":"http://example.net/b","content":"제외"},
              {"title":"둘째","url":"https://example.net/b","content":"두 번째"}
            ]}
        """.trimIndent()
        val transport = FakeHttpTransport { HttpResponse(200, body) }

        val response = TavilyWebSearchGateway(transport) { "tvly-key" }.search("q", 5)

        assertEquals(listOf("정상", "둘째"), response.hits.map(WebSearchHit::title))
        assertEquals(listOf("요약", "두 번째"), response.hits.map(WebSearchHit::snippet))
    }

    @Test
    fun `tavily drops present scores below one half and accepts absent scores`() = runBlocking {
        val transport = FakeHttpTransport {
            HttpResponse(
                200,
                """
                {"results":[
                  {"title":"낮음","url":"https://low.example/a","content":"제외","score":0.49},
                  {"title":"경계","url":"https://edge.example/a","content":"유지","score":0.5},
                  {"title":"점수 없음","url":"https://future.example/a","content":"유지"}
                ]}
                """.trimIndent(),
            )
        }

        val response = TavilyWebSearchGateway(transport) { "tvly-key" }.search("q", 5)

        assertEquals(listOf("경계", "점수 없음"), response.hits.map(WebSearchHit::title))
    }

    @Test
    fun `tavily refuses missing or unsafe keys before network`() {
        listOf<String?>(null, "", "tvly-key\r\nX-Evil: yes").forEach { key ->
            val transport = FakeHttpTransport { error("must not call") }
            val gateway = TavilyWebSearchGateway(transport) { key }

            val failure = assertThrows(RemoteServiceException::class.java) {
                runBlocking { gateway.search("q", 5) }
            }

            assertEquals(
                if (key == null) ToolFailureCode.CREDENTIALS_MISSING else ToolFailureCode.AUTHENTICATION_FAILED,
                failure.failureCode,
            )
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun `tavily HTTP and malformed responses use closed failure codes`() {
        val cases = mapOf(
            400 to ToolFailureCode.INVALID_REQUEST,
            401 to ToolFailureCode.AUTHENTICATION_FAILED,
            403 to ToolFailureCode.PERMISSION_DENIED,
            429 to ToolFailureCode.RATE_LIMITED,
            432 to ToolFailureCode.API_DISABLED_OR_QUOTA_EXCEEDED,
            433 to ToolFailureCode.API_DISABLED_OR_QUOTA_EXCEEDED,
            500 to ToolFailureCode.PROVIDER_UNAVAILABLE,
            504 to ToolFailureCode.PROVIDER_TIMEOUT,
        )
        cases.forEach { (status, code) ->
            val transport = FakeHttpTransport {
                HttpResponse(status, """{"detail":{"error":"provider-secret"}}""")
            }
            val failure = assertThrows(RemoteServiceException::class.java) {
                runBlocking { TavilyWebSearchGateway(transport) { "tvly-key" }.search("q", 5) }
            }
            assertEquals("HTTP $status", code, failure.failureCode)
            assertFalse(failure.message.orEmpty().contains("provider-secret"))
        }

        val malformedTransport = FakeHttpTransport { HttpResponse(200, """{"answer":"text"}""") }
        val malformed = assertThrows(RemoteServiceException::class.java) {
            runBlocking {
                TavilyWebSearchGateway(malformedTransport) { "tvly-key" }.search("q", 5)
            }
        }
        assertEquals(ToolFailureCode.MALFORMED_RESPONSE, malformed.failureCode)
    }

    @Test
    fun `tavily verifier returns usage and never places candidate key in URL`() = runBlocking {
        val candidate = "tvly-candidate-key"
        val transport = FakeHttpTransport {
            HttpResponse(
                200,
                """{"key":{"usage":150,"limit":1000},"account":{"plan_usage":500,"plan_limit":15000}}""",
            )
        }

        val result = TavilyApiKeyVerifier(transport).verify(candidate)

        assertEquals(TavilyApiKeyVerification.Valid(150, 1000), result)
        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://api.tavily.com/usage", request.url)
        assertEquals("Bearer $candidate", request.headers["Authorization"])
        assertFalse(request.url.contains(candidate))
        assertEquals(null, request.body)
    }

    @Test
    fun `tavily verifier has closed invalid and rate-limited outcomes`() = runBlocking {
        val invalidLocalTransport = FakeHttpTransport { error("must not call") }
        assertEquals(
            TavilyApiKeyVerification.Invalid,
            TavilyApiKeyVerifier(invalidLocalTransport).verify("bad key\n"),
        )
        assertTrue(invalidLocalTransport.requests.isEmpty())

        listOf(401, 403).forEach { status ->
            val transport = FakeHttpTransport { HttpResponse(status, "provider-secret") }
            assertEquals(
                TavilyApiKeyVerification.Invalid,
                TavilyApiKeyVerifier(transport).verify("tvly-key"),
            )
        }
        val limitedTransport = FakeHttpTransport { HttpResponse(429, "provider-secret") }
        assertEquals(
            TavilyApiKeyVerification.RateLimited,
            TavilyApiKeyVerifier(limitedTransport).verify("tvly-key"),
        )
    }

    @Test
    fun `tavily verifier accepts authenticated 200 when optional usage counters change`() = runBlocking {
        val changedUsageTransport = FakeHttpTransport {
            HttpResponse(200, """{"key":{"usage":-1,"limit":1000}}""")
        }

        assertEquals(
            TavilyApiKeyVerification.Valid(usage = null, limit = 1000),
            TavilyApiKeyVerifier(changedUsageTransport).verify("tvly-key"),
        )
    }

    @Test
    fun `tavily verifier treats server responses as typed failures`() {

        val unavailableTransport = FakeHttpTransport {
            HttpResponse(503, "provider-secret")
        }
        val unavailable = assertThrows(RemoteServiceException::class.java) {
            runBlocking { TavilyApiKeyVerifier(unavailableTransport).verify("tvly-key") }
        }
        assertEquals(ToolFailureCode.PROVIDER_UNAVAILABLE, unavailable.failureCode)
        assertFalse(unavailable.message.orEmpty().contains("provider-secret"))
    }

    @Test
    fun `tavily verifier has its own bounded timeout`() = runTest {
        val transport = FakeHttpTransport {
            delay(1_000)
            HttpResponse(200, """{"key":{"usage":1,"limit":1000}}""")
        }

        val failure = try {
            TavilyApiKeyVerifier(transport, timeoutMillis = 100).verify("tvly-key")
            fail("Expected timeout")
            error("unreachable")
        } catch (failure: RemoteServiceException) {
            failure
        }

        assertEquals(ToolFailureCode.PROVIDER_TIMEOUT, failure.failureCode)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `router accepts two substantive distinct-domain You results without Tavily`() = runBlocking {
        val you = FakeSearchGateway(
            label = "you",
            responseProvider = WebSearchProvider.YOU_COM,
            hits = twoDomainHits(),
        )
        val tavily = FakeSearchGateway("tavily", WebSearchProvider.TAVILY, hits = fallbackHits())

        val response = YouTavilyWebSearchGateway(you, tavily).search("일반 검색", 5)

        assertEquals(WebSearchProvider.YOU_COM, response.provider)
        assertEquals(1, you.searchCalls)
        assertEquals(0, tavily.searchCalls)
        assertEquals(0, tavily.credentialChecks)
    }

    @Test
    fun `router falls back for one-domain or empty general You results`() = runBlocking {
        val lowQualityCases = listOf(
            emptyList(),
            listOf(hit("하나", "https://same.example/a", "요약")),
            listOf(
                hit("하나", "https://same.example/a", "요약"),
                hit("둘", "https://same.example/b", "요약"),
            ),
        )
        lowQualityCases.forEach { weakHits ->
            val you = FakeSearchGateway("you", WebSearchProvider.YOU_COM, weakHits)
            val tavily = FakeSearchGateway("tavily", WebSearchProvider.TAVILY, fallbackHits())

            val response = YouTavilyWebSearchGateway(you, tavily).search("일반 검색", 5)

            assertEquals(WebSearchProvider.TAVILY, response.provider)
            assertEquals(1, you.searchCalls)
            assertEquals(1, tavily.searchCalls)
        }
    }

    @Test
    fun `router falls back when rich You results are unrelated to a multi-term query`() = runBlocking {
        val you = FakeSearchGateway(
            label = "you",
            responseProvider = WebSearchProvider.YOU_COM,
            hits = listOf(
                hit("오늘의 날씨", "https://weather.example/a", "서울의 기온과 강수 정보입니다."),
                hit("주식 시장", "https://finance.example/b", "국내 증시 마감 소식입니다."),
            ),
        )
        val tavily = FakeSearchGateway(
            label = "tavily",
            responseProvider = WebSearchProvider.TAVILY,
            hits = listOf(
                hit(
                    "그대들은 어떻게 살 것인가",
                    "https://film.example/a",
                    "미야자키 하야오의 애니메이션 영화 작품 정보입니다.",
                ),
            ),
        )

        val response = YouTavilyWebSearchGateway(you, tavily).search(
            "그대들은 어떻게 살것인가 영화",
            5,
        )

        assertEquals(WebSearchProvider.TAVILY, response.provider)
        assertEquals(1, you.searchCalls)
        assertEquals(1, tavily.searchCalls)
    }

    @Test
    fun `router falls back when officeholder results discuss only the office`() = runBlocking {
        val query = "대한민국 현직 대통령 이름 공식"
        val you = FakeSearchGateway(
            label = "you",
            responseProvider = WebSearchProvider.YOU_COM,
            hits = listOf(
                hit(
                    "대한민국 대통령",
                    "https://constitution.example/office",
                    "대통령은 국가원수이며 임기는 5년입니다.",
                ),
                hit(
                    "대통령 선거 제도",
                    "https://election.example/office",
                    "대한민국 헌법은 대통령 선거 방법을 규정합니다.",
                ),
            ),
        )
        val tavily = FakeSearchGateway(
            label = "tavily",
            responseProvider = WebSearchProvider.TAVILY,
            hits = listOf(
                hit(
                    "대한민국 대통령실 - 대통령 소개",
                    "https://www.president.go.kr/fixture",
                    "대한민국의 현직 대통령은 홍길동입니다.",
                ),
            ),
        )

        val response = YouTavilyWebSearchGateway(you, tavily).search(query, 5)

        assertEquals(WebSearchProvider.TAVILY, response.provider)
        assertEquals(1, you.searchCalls)
        assertEquals(1, tavily.searchCalls)
    }

    @Test
    fun `router accepts one substantive You result only for explicitly narrow requests`() = runBlocking {
        listOf(
            "\"정확한 문구\"" to hit(
                "정확한 문구",
                "https://quote.example/a",
                "정확한 문구가 포함된 자료입니다.",
            ),
            "site.example 특정 페이지" to hit(
                "특정 페이지",
                "https://site.example/a",
                "site.example의 특정 페이지입니다.",
            ),
        ).forEach { (narrowQuery, relevantHit) ->
            val you = FakeSearchGateway(
                "you",
                WebSearchProvider.YOU_COM,
                listOf(relevantHit),
            )
            val tavily = FakeSearchGateway("tavily", WebSearchProvider.TAVILY, fallbackHits())

            val response = YouTavilyWebSearchGateway(you, tavily).search(narrowQuery, 5)

            assertEquals(WebSearchProvider.YOU_COM, response.provider)
            assertEquals(0, tavily.searchCalls)
        }
    }

    @Test
    fun `router falls back for unrelated single-term and narrow results`() = runBlocking {
        listOf("인터스텔라", "\"정확한 문구\"").forEach { query ->
            val you = FakeSearchGateway(
                "you",
                WebSearchProvider.YOU_COM,
                listOf(hit("오늘 날씨", "https://weather.example/a", "서울의 기온입니다.")),
            )
            val tavily = FakeSearchGateway("tavily", WebSearchProvider.TAVILY, fallbackHits())

            val response = YouTavilyWebSearchGateway(you, tavily).search(query, 5)

            assertEquals(query, WebSearchProvider.TAVILY, response.provider)
            assertEquals(query, 1, tavily.searchCalls)
        }
    }

    @Test
    fun `router falls back only for the closed transient failure set`() = runBlocking {
        val eligible = listOf(
            ToolFailureCode.RATE_LIMITED,
            ToolFailureCode.API_DISABLED_OR_QUOTA_EXCEEDED,
            ToolFailureCode.PROVIDER_TIMEOUT,
            ToolFailureCode.PROVIDER_UNAVAILABLE,
            ToolFailureCode.NETWORK_FAILURE,
        )
        eligible.forEach { code ->
            val you = FakeSearchGateway(
                "you",
                WebSearchProvider.YOU_COM,
                failure = RemoteServiceException(code, "fixed"),
            )
            val tavily = FakeSearchGateway("tavily", WebSearchProvider.TAVILY, fallbackHits())

            val response = YouTavilyWebSearchGateway(you, tavily).search("q", 5)

            assertEquals(code.name, WebSearchProvider.TAVILY, response.provider)
            assertEquals(code.name, 1, tavily.searchCalls)
        }
    }

    @Test
    fun `router opens the You circuit after two transient failures and later probes recovery`() = runBlocking {
        var now = 1_000L
        val you = object : WebSearchGateway {
            var searchCalls = 0

            override suspend fun credentialsPresent(): Boolean = true

            override suspend fun search(query: String, limit: Int): WebSearchResponse {
                searchCalls += 1
                if (searchCalls <= 2) {
                    throw RemoteServiceException(ToolFailureCode.PROVIDER_UNAVAILABLE, "fixed")
                }
                return WebSearchResponse(WebSearchProvider.YOU_COM, twoDomainHits())
            }
        }
        val tavily = FakeSearchGateway("tavily", WebSearchProvider.TAVILY, fallbackHits())
        val router = YouTavilyWebSearchGateway(
            youGateway = you,
            tavilyGateway = tavily,
            monotonicClockMillis = { now },
        )

        assertEquals(WebSearchProvider.TAVILY, router.search("첫 검색", 5).provider)
        assertEquals(WebSearchProvider.TAVILY, router.search("둘째 검색", 5).provider)
        assertEquals(WebSearchProvider.TAVILY, router.search("회로 열린 검색", 5).provider)
        assertEquals(2, you.searchCalls)
        assertEquals(3, tavily.searchCalls)

        now += 15L * 60L * 1_000L + 1L
        assertEquals(WebSearchProvider.YOU_COM, router.search("복구 확인", 5).provider)
        assertEquals(WebSearchProvider.YOU_COM, router.search("복구 뒤 검색", 5).provider)
        assertEquals(4, you.searchCalls)
        assertEquals(3, tavily.searchCalls)
    }

    @Test
    fun `open circuit retries zero setup You when no Tavily key exists`() = runBlocking {
        val you = object : WebSearchGateway {
            var searchCalls = 0

            override suspend fun credentialsPresent(): Boolean = true

            override suspend fun search(query: String, limit: Int): WebSearchResponse {
                searchCalls += 1
                if (searchCalls <= 2) {
                    throw RemoteServiceException(ToolFailureCode.PROVIDER_UNAVAILABLE, "fixed")
                }
                return WebSearchResponse(WebSearchProvider.YOU_COM, twoDomainHits())
            }
        }
        val tavily = FakeSearchGateway(
            label = "tavily",
            responseProvider = WebSearchProvider.TAVILY,
            hasCredentials = false,
        )
        val router = YouTavilyWebSearchGateway(you, tavily)

        repeat(2) {
            assertThrows(ToolExecutionException::class.java) {
                runBlocking { router.search("일시 장애", 5) }
            }
        }
        val recovered = router.search("키 없는 복구", 5)

        assertEquals(WebSearchProvider.YOU_COM, recovered.provider)
        assertEquals(3, you.searchCalls)
        assertEquals(0, tavily.searchCalls)
    }

    @Test
    fun `router does not fall back for malformed invalid auth or policy failures`() {
        val refused = ToolFailureCode.entries - setOf(
            ToolFailureCode.RATE_LIMITED,
            ToolFailureCode.API_DISABLED_OR_QUOTA_EXCEEDED,
            ToolFailureCode.PROVIDER_TIMEOUT,
            ToolFailureCode.PROVIDER_UNAVAILABLE,
            ToolFailureCode.NETWORK_FAILURE,
        )
        refused.forEach { code ->
            val you = FakeSearchGateway(
                "you",
                WebSearchProvider.YOU_COM,
                failure = RemoteServiceException(code, "fixed"),
            )
            val tavily = FakeSearchGateway("tavily", WebSearchProvider.TAVILY, fallbackHits())
            val router = YouTavilyWebSearchGateway(you, tavily)

            val failure = assertThrows(ToolExecutionException::class.java) {
                runBlocking { router.search("q", 5) }
            }

            assertEquals(code.name, code, failure.failureCode)
            assertEquals(code.name, 0, tavily.searchCalls)
            assertEquals(code.name, 0, tavily.credentialChecks)
        }
    }

    @Test
    fun `router timeout cancels You before starting exactly one Tavily call`() = runTest {
        val tracker = ConcurrencyTracker()
        val you = FakeSearchGateway(
            "you",
            WebSearchProvider.YOU_COM,
            hits = twoDomainHits(),
            delayMillis = 1_000,
            tracker = tracker,
        )
        val tavily = FakeSearchGateway(
            "tavily",
            WebSearchProvider.TAVILY,
            hits = fallbackHits(),
            tracker = tracker,
        )

        val response = YouTavilyWebSearchGateway(
            you,
            tavily,
            youTimeoutMillis = 100,
            tavilyTimeoutMillis = 100,
        ).search("q", 5)

        assertEquals(WebSearchProvider.TAVILY, response.provider)
        assertEquals(1, you.searchCalls)
        assertEquals(1, tavily.searchCalls)
        assertEquals(1, tracker.maximum)
        assertEquals(listOf("you:start", "you:end", "tavily:start", "tavily:end"), tracker.events)
    }

    @Test
    fun `caller cancellation never starts Tavily`() = runTest {
        val you = FakeSearchGateway(
            "you",
            WebSearchProvider.YOU_COM,
            hits = twoDomainHits(),
            delayMillis = 10_000,
        )
        val tavily = FakeSearchGateway("tavily", WebSearchProvider.TAVILY, fallbackHits())
        val router = YouTavilyWebSearchGateway(you, tavily, youTimeoutMillis = 20_000)

        val job = launch { router.search("q", 5) }
        runCurrent()
        job.cancel()
        joinAll(job)

        assertTrue(job.isCancelled)
        assertEquals(1, you.searchCalls)
        assertEquals(0, tavily.searchCalls)
        assertEquals(0, tavily.credentialChecks)
    }

    @Test
    fun `router refuses a mismatched provider identity without fallback`() {
        val you = FakeSearchGateway(
            "you",
            responseProvider = WebSearchProvider.TAVILY,
            hits = twoDomainHits(),
        )
        val tavily = FakeSearchGateway("tavily", WebSearchProvider.TAVILY, fallbackHits())

        val failure = assertThrows(ToolExecutionException::class.java) {
            runBlocking { YouTavilyWebSearchGateway(you, tavily).search("q", 5) }
        }

        assertEquals(ToolFailureCode.CLIENT_POLICY_FAILURE, failure.failureCode)
        assertEquals(0, tavily.searchCalls)
    }

    @Test
    fun `router preserves weak You response when optional Tavily key is absent or fallback fails`() = runBlocking {
        val weak = listOf(hit("하나", "https://one.example/a", "요약"))
        listOf(
            FakeSearchGateway(
                "tavily",
                WebSearchProvider.TAVILY,
                hasCredentials = false,
            ),
            FakeSearchGateway(
                "tavily",
                WebSearchProvider.TAVILY,
                failure = RemoteServiceException(ToolFailureCode.PROVIDER_UNAVAILABLE, "fixed"),
            ),
        ).forEach { tavily ->
            val you = FakeSearchGateway("you", WebSearchProvider.YOU_COM, weak)

            val response = YouTavilyWebSearchGateway(you, tavily).search("일반 검색", 5)

            assertEquals(WebSearchProvider.YOU_COM, response.provider)
            assertEquals(weak, response.hits)
        }
    }

    private fun youPayload(): String = """
        {"results":{"web":[
          {"title":"하나","url":"https://one.example/a","description":"첫 요약"},
          {"title":"둘","url":"https://two.example/b","description":"둘째 요약"}
        ],"news":[]}}
    """.trimIndent()

    private fun youSuccess(
        structuredPayload: String?,
        contentPayload: String? = null,
    ): HttpResponse {
        val result = JsonObject()
        if (structuredPayload != null) {
            result.add("structuredContent", JsonParser.parseString(structuredPayload))
        }
        val content = JsonArray()
        if (contentPayload != null) {
            content.add(
                JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", contentPayload)
                },
            )
        }
        result.add("content", content)
        val envelope = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", 1)
            add("result", result)
        }
        val body = buildString {
            append("event: message\n")
            append("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}\n\n")
            append("event: message\n")
            append("data: ")
            append(envelope)
            append("\n\n")
        }
        return HttpResponse(200, body)
    }

    private fun tavilySuccess(): HttpResponse = HttpResponse(
        200,
        """{"results":[{"title":"결과","url":"https://example.com/a","content":"요약"}]}""",
    )

    private fun twoDomainHits(): List<WebSearchHit> = listOf(
        hit("복구 확인 뒤 검색 일반 결과", "https://one.example/a", "키 없는 복구 뒤 검색 일반 요약"),
        hit("둘", "https://two.example/b", "둘째 요약"),
    )

    private fun fallbackHits(): List<WebSearchHit> = listOf(
        hit("대체", "https://fallback.example/a", "대체 요약"),
    )

    private fun hit(title: String, link: String, snippet: String): WebSearchHit =
        WebSearchHit(title, link, snippet)
}
