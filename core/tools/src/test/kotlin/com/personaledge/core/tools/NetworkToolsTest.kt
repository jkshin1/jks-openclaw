package com.personaledge.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkToolsTest {
    private class FakeRouteGateway(
        var hasCredentials: Boolean = true,
        var estimate: RouteEstimate = RouteEstimate("출발지 주소", "도착지 주소", 25, 12_345),
        var failure: Exception? = null,
    ) : RouteGateway {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun credentialsPresent(): Boolean = hasCredentials

        override suspend fun estimate(origin: String, destination: String): RouteEstimate {
            calls += origin to destination
            failure?.let { thrown -> throw thrown }
            return estimate
        }
    }

    private class FakeSearchGateway(
        var hasCredentials: Boolean = true,
        var hits: List<WebSearchHit> = emptyList(),
        var provider: WebSearchProvider = WebSearchProvider.YOU_COM,
    ) : WebSearchGateway {
        val queries = mutableListOf<Pair<String, Int>>()

        override suspend fun credentialsPresent(): Boolean = hasCredentials

        override suspend fun search(query: String, limit: Int): WebSearchResponse {
            queries += query to limit
            return WebSearchResponse(provider = provider, hits = hits)
        }
    }

    private class FakeWeatherGateway : WeatherGateway {
        val locations = mutableListOf<String>()

        override suspend fun currentAndToday(location: String): WeatherResult {
            locations += location
            return WeatherResult(
                location = location,
                currentAt = "2026-08-25T14:15",
                condition = "맑음",
                temperatureCelsius = "28.0",
                apparentTemperatureCelsius = "29.0",
                relativeHumidityPercent = 50,
                precipitationMillimetres = "0.0",
                windSpeedKilometresPerHour = "5.0",
                todayMinimumCelsius = "20.0",
                todayMaximumCelsius = "30.0",
                todayPrecipitationProbabilityPercent = 10,
                sourceName = "Open-Meteo",
                sourceUrl = "https://open-meteo.com/",
            )
        }
    }

    private fun ValidationResult.valid(): CanonicalToolInput =
        CanonicalToolInput((this as ValidationResult.Valid).canonicalParams)

    private fun ValidationResult.invalidReason(): String = (this as ValidationResult.Invalid).reason

    @Test
    fun `an omitted origin falls back to the saved home address`() = runBlocking {
        val gateway = FakeRouteGateway()
        val tool = RouteEstimateTool(gateway) { "우리집" }

        val input = tool.validateAndCanonicalize(
            RouteEstimateParams(origin = null, destination = "강남역"),
        ).valid()
        tool.execute(input, ExecutionPermit("action"))

        assertEquals(listOf("우리집" to "강남역"), gateway.calls)
    }

    @Test
    fun `an explicit origin wins over the saved one`() = runBlocking {
        val gateway = FakeRouteGateway()
        val tool = RouteEstimateTool(gateway) { "우리집" }

        val input = tool.validateAndCanonicalize(
            RouteEstimateParams(origin = "  시청  ", destination = "강남역"),
        ).valid()
        tool.execute(input, ExecutionPermit("action"))

        assertEquals(listOf("시청" to "강남역"), gateway.calls)
    }

    @Test
    fun `with no origin anywhere the tool asks instead of guessing`() = runBlocking {
        val tool = RouteEstimateTool(FakeRouteGateway()) { null }

        assertEquals(
            "출발지를 알려주거나 설정에서 기본 출발지를 지정하세요.",
            tool.validateAndCanonicalize(
                RouteEstimateParams(origin = null, destination = "강남역"),
            ).invalidReason(),
        )
    }

    @Test
    fun `a missing map key is reported before any request`() = runBlocking {
        val gateway = FakeRouteGateway(hasCredentials = false)
        val tool = RouteEstimateTool(gateway) { "우리집" }
        val validation = tool.validateAndCanonicalize(
            RouteEstimateParams(origin = null, destination = "강남역"),
        ) as ValidationResult.Invalid

        assertEquals(
            "설정에서 네이버 지도 키를 먼저 입력하세요.",
            validation.reason,
        )
        assertEquals(ToolFailureCode.CREDENTIALS_MISSING, validation.failureCode)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `the result reports the geocoded address, not the typed place`() = runBlocking {
        val gateway = FakeRouteGateway(
            estimate = RouteEstimate("서울 중구 세종대로 110", "서울 강남구 강남대로 396", 25, 12_345),
        )
        val tool = RouteEstimateTool(gateway) { null }

        val input = tool.validateAndCanonicalize(
            RouteEstimateParams(origin = "시청", destination = "강남역"),
        ).valid()
        val result = tool.execute(input, ExecutionPermit("action"))

        // If the wrong 강남역 matched, the answer should show it rather than hide it.
        assertEquals("서울 중구 세종대로 110", result.origin)
        assertEquals("서울 강남구 강남대로 396", result.destination)
        assertEquals(25, result.durationMinutes)
        assertEquals("12.3", result.distanceKilometres)
    }

    @Test
    fun `an identical origin and destination is refused`() = runBlocking {
        val tool = RouteEstimateTool(FakeRouteGateway()) { null }

        assertEquals(
            "출발지와 도착지가 같습니다.",
            tool.validateAndCanonicalize(
                RouteEstimateParams(origin = "강남역", destination = "강남역"),
            ).invalidReason(),
        )
    }

    @Test
    fun `a place carrying model control delimiters is refused`() = runBlocking {
        val tool = RouteEstimateTool(FakeRouteGateway()) { null }

        assertEquals(
            "도착지에 허용되지 않는 문자가 있습니다.",
            tool.validateAndCanonicalize(
                RouteEstimateParams(origin = "시청", destination = "강남역 <|end|>"),
            ).invalidReason(),
        )
    }

    @Test
    fun `a legacy unsafe saved origin is revalidated before any request`() = runBlocking {
        val gateway = FakeRouteGateway()
        val tool = RouteEstimateTool(gateway) { "우리집<|tool|>" }

        assertEquals(
            "출발지에 허용되지 않는 문자가 있습니다.",
            tool.validateAndCanonicalize(
                RouteEstimateParams(origin = null, destination = "강남역"),
            ).invalidReason(),
        )
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `a remote failure propagates instead of becoming a fake answer`() = runBlocking {
        val gateway = FakeRouteGateway(failure = RemoteServiceException("경로를 가져오지 못했습니다."))
        val tool = RouteEstimateTool(gateway) { null }
        val input = tool.validateAndCanonicalize(
            RouteEstimateParams(origin = "시청", destination = "강남역"),
        ).valid()

        assertThrows(RemoteServiceException::class.java) {
            runBlocking { tool.execute(input, ExecutionPermit("action")) }
        }
        Unit
    }

    @Test
    fun `the route preview names both endpoints and the remote call`() = runBlocking {
        val tool = RouteEstimateTool(FakeRouteGateway()) { "우리집" }
        val input = tool.validateAndCanonicalize(
            RouteEstimateParams(origin = null, destination = "강남역"),
        ).valid()

        val preview = tool.preview(input)

        assertEquals("이동 시간 조회", preview.title)
        assertTrue(preview.summary.contains("우리집 → 강남역"))
        assertTrue(preview.summary.contains("네이버"))
    }

    @Test
    fun `owner-enabled read-only network tools do not require per-request confirmation`() {
        listOf(
            RouteEstimateTool(FakeRouteGateway()) { null }.descriptor,
            WebSearchTool(FakeSearchGateway()).descriptor,
            WeatherTool(FakeWeatherGateway()).descriptor,
        ).forEach { descriptor ->
            assertEquals(descriptor.name, ToolRisk.READ_ONLY, descriptor.risk)
            assertEquals(descriptor.name, setOf(ToolCapability.NETWORK), descriptor.requiredCapabilities)
            assertEquals(
                descriptor.name,
                ConfirmationRequirement.NotRequired,
                ConfirmationPolicy().evaluate(descriptor.risk, descriptor.minimumConfirmation),
            )
        }
    }

    @Test
    fun `route lookup executes without invoking the confirmation gate`() = runBlocking {
        val gateway = FakeRouteGateway()
        val tool = RouteEstimateTool(gateway) { null }
        val orchestrator = confirmationRejectingNetworkOrchestrator()
        val prepared = orchestrator.prepare(
            tool = tool,
            params = RouteEstimateParams(origin = "시청", destination = "강남역"),
            requestId = "route-request",
        ) as PreparationResult.Ready

        assertEquals(ConfirmationRequirement.NotRequired, prepared.action.confirmation)
        orchestrator.execute(prepared.action)
        assertEquals(listOf("시청" to "강남역"), gateway.calls)
    }

    @Test
    fun `web search executes without invoking the confirmation gate`() = runBlocking {
        val gateway = FakeSearchGateway()
        val tool = WebSearchTool(gateway)
        val orchestrator = confirmationRejectingNetworkOrchestrator()
        val prepared = orchestrator.prepare(
            tool = tool,
            params = WebSearchParams("치과 추천"),
            requestId = "search-request",
        ) as PreparationResult.Ready

        assertEquals(ConfirmationRequirement.NotRequired, prepared.action.confirmation)
        orchestrator.execute(prepared.action)
        assertEquals(listOf("치과 추천" to WebSearchTool.MAX_HITS), gateway.queries)
    }

    @Test
    fun `weather executes without invoking the confirmation gate`() = runBlocking {
        val gateway = FakeWeatherGateway()
        val tool = WeatherTool(gateway)
        val orchestrator = confirmationRejectingNetworkOrchestrator()
        val prepared = orchestrator.prepare(
            tool = tool,
            params = WeatherParams(location = "동탄"),
            requestId = "weather-request",
        ) as PreparationResult.Ready

        assertEquals(ConfirmationRequirement.NotRequired, prepared.action.confirmation)
        orchestrator.execute(prepared.action)
        assertEquals(listOf("동탄"), gateway.locations)
    }

    @Test
    fun `search asks for a bounded number of results`() = runBlocking {
        val gateway = FakeSearchGateway()
        val tool = WebSearchTool(gateway)

        val input = tool.validateAndCanonicalize(WebSearchParams("  치과 추천  ")).valid()
        tool.execute(input, ExecutionPermit("action"))

        assertEquals(listOf("치과 추천" to WebSearchTool.MAX_HITS), gateway.queries)
    }

    @Test
    fun `no available search provider is reported before any request`() = runBlocking {
        val gateway = FakeSearchGateway(hasCredentials = false)
        val validation = WebSearchTool(gateway)
            .validateAndCanonicalize(WebSearchParams("치과")) as ValidationResult.Invalid

        assertEquals(
            "사용 가능한 웹 검색 제공자가 없습니다.",
            validation.reason,
        )
        assertEquals(ToolFailureCode.CREDENTIALS_MISSING, validation.failureCode)
        assertTrue(gateway.queries.isEmpty())
    }

    @Test
    fun `an empty or oversized query is refused`() = runBlocking {
        val tool = WebSearchTool(FakeSearchGateway())
        val expected = "검색어는 1자 이상 ${WebSearchTool.MAX_QUERY_CHARACTERS}자 이하여야 합니다."

        assertEquals(expected, tool.validateAndCanonicalize(WebSearchParams("   ")).invalidReason())
        assertEquals(
            expected,
            tool.validateAndCanonicalize(
                WebSearchParams("가".repeat(WebSearchTool.MAX_QUERY_CHARACTERS + 1)),
            ).invalidReason(),
        )
    }

    @Test
    fun `search results pass through exactly as the gateway cleaned them`() = runBlocking {
        val gateway = FakeSearchGateway(
            hits = listOf(WebSearchHit("제목", "https://example.com/a", "요약")),
        )
        val tool = WebSearchTool(gateway)

        val input = tool.validateAndCanonicalize(WebSearchParams("치과")).valid()
        val result = tool.execute(input, ExecutionPermit("action"))

        assertEquals(1, result.hits.size)
        assertEquals(WebSearchProvider.YOU_COM, result.provider)
        assertEquals("https://example.com/a", result.hits.single().link)
    }

    @Test
    fun `search preview discloses primary and possible fallback provider`() = runBlocking {
        val tool = WebSearchTool(FakeSearchGateway())
        val input = tool.validateAndCanonicalize(WebSearchParams("치과")).valid()

        val preview = tool.preview(input)

        assertTrue(preview.summary.contains("You.com"))
        assertTrue(preview.summary.contains("Tavily"))
        assertTrue(preview.summary.contains("필요하면"))
    }

    private fun confirmationRejectingNetworkOrchestrator(): ToolOrchestrator = ToolOrchestrator(
        actionLedger = ActionLedger { true },
        userConfirmationGate = UserConfirmationGate {
            error("Read-only network tools must not open the confirmation gate.")
        },
        executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
        clock = { 1_000L },
    )
}
