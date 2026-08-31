package com.personaledge.core.agent

import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.llm.LlmFailureCode
import com.personaledge.core.llm.LlmRuntime
import com.personaledge.core.llm.LlmState
import com.personaledge.core.llm.LlmToolCall
import com.personaledge.core.llm.LlmToolDefinition
import com.personaledge.core.llm.LlmTurnToolScope
import com.personaledge.core.llm.ModelEvent
import com.personaledge.core.llm.TrustedToolResponse
import com.personaledge.core.llm.TurnId
import com.personaledge.core.llm.VerifiedInstalledModel
import com.personaledge.core.tools.ActionChallenge
import com.personaledge.core.tools.ActionExecutionState
import com.personaledge.core.tools.ActionLedger
import com.personaledge.core.tools.AlarmGateway
import com.personaledge.core.tools.AlarmNextTool
import com.personaledge.core.tools.AlarmOutcome
import com.personaledge.core.tools.AlarmRequest
import com.personaledge.core.tools.AlarmSetTool
import com.personaledge.core.tools.CalendarAccount
import com.personaledge.core.tools.CalendarCreateEventTool
import com.personaledge.core.tools.CalendarEvent
import com.personaledge.core.tools.CalendarEventDraft
import com.personaledge.core.tools.CalendarEventPatch
import com.personaledge.core.tools.CalendarGateway
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.CalendarUpdateEventTool
import com.personaledge.core.tools.CapabilityFreeInterlock
import com.personaledge.core.tools.ExecutionInterlock
import com.personaledge.core.tools.FakeArrivalNoticeTool
import com.personaledge.core.tools.InProcessActionLedger
import com.personaledge.core.tools.InterlockDecision
import com.personaledge.core.tools.NextAlarm
import com.personaledge.core.tools.NotificationGateway
import com.personaledge.core.tools.NotificationSearchTool
import com.personaledge.core.tools.MemoryRememberTool
import com.personaledge.core.tools.MemoryGateway
import com.personaledge.core.tools.MemoryWriteOutcome
import com.personaledge.core.tools.RouteEstimate
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.RouteGateway
import com.personaledge.core.tools.ReminderCreateParams
import com.personaledge.core.tools.ReminderCreateTool
import com.personaledge.core.tools.ToolOrchestrator
import com.personaledge.core.tools.ToolExecutionOutcome
import com.personaledge.core.tools.ToolExecutionException
import com.personaledge.core.tools.ToolFailureCode
import com.personaledge.core.tools.ToolRisk
import com.personaledge.core.tools.UserConfirmationGate
import com.personaledge.core.tools.WebSearchGateway
import com.personaledge.core.tools.WebSearchHit
import com.personaledge.core.tools.WebSearchProvider
import com.personaledge.core.tools.WebSearchResponse
import com.personaledge.core.tools.WebSearchTool
import com.personaledge.core.tools.WeatherGateway
import com.personaledge.core.tools.WeatherResult
import com.personaledge.core.tools.WeatherTool
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualToolAgentControllerTest {
    @Test
    fun `thought streams before completion and never enters the answer buffer`() = runBlocking {
        val turnId = TurnId("turn-thought-stream")
        val thoughtDelivered = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flow {
                emit(ModelEvent.ThoughtDelta(turnId, "먼저 핵심 의도를 검토합니다."))
                thoughtDelivered.complete(Unit)
                allowCompletion.await()
                emit(ModelEvent.TextDelta(turnId, "핵심 의도에 맞춘 답변입니다."))
                emit(ModelEvent.Completed(turnId))
            },
        )
        val observed = mutableListOf<AgentEvent>()
        val collection = launch {
            fixture.controller.runTurn(
                turnId = turnId,
                prompt = "trusted context wrapping the current request",
                currentUserRequest = "핵심 의도에 맞게 답해 줘",
            ).collect(observed::add)
        }

        thoughtDelivered.await()
        assertEquals(
            listOf("먼저 핵심 의도를 검토합니다."),
            observed.filterIsInstance<AgentEvent.ThoughtDelta>().map(AgentEvent.ThoughtDelta::text),
        )
        assertTrue(observed.none { event -> event is AgentEvent.TextDelta })
        assertTrue(observed.none { event -> event is AgentEvent.Completed })

        allowCompletion.complete(Unit)
        collection.join()

        assertEquals(
            listOf("핵심 의도에 맞춘 답변입니다."),
            observed.filterIsInstance<AgentEvent.TextDelta>().map(AgentEvent.TextDelta::text),
        )
        assertFalse(
            observed.filterIsInstance<AgentEvent.TextDelta>().single().text.contains("검토합니다"),
        )
        assertEquals(AgentEvent.Completed(turnId), observed.last())
    }

    @Test
    fun `alternate runtime cannot exceed the per-step thought budget`() = runBlocking {
        val turnId = TurnId("turn-thought-overflow")
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.ThoughtDelta(turnId, "x".repeat(33)),
                ModelEvent.TextDelta(turnId, "노출되면 안 되는 답변입니다."),
                ModelEvent.Completed(turnId),
            ),
        )

        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = "trusted context",
            turnLimits = AgentLoopLimits(maxOutputTokens = 1),
            currentUserRequest = "질문",
        ).toList()

        assertFailure(events, AgentFailureCode.INVALID_MODEL_SEQUENCE)
        assertTrue(events.none { event -> event is AgentEvent.ThoughtDelta })
        assertTrue(events.none { event -> event is AgentEvent.TextDelta })
        assertEquals(1, fixture.runtime.cancelCount.get())
    }

    @Test
    fun `no-tool final rejects blank generic receipt and normalized current-prompt echo`() =
        runBlocking {
            val currentUserPrompt = "  ORBIT－7을   설명해 줘 "
            val invalidAnswers = listOf(
                "",
                "요청 처리를 완료했습니다.",
                "orbit-7을 설명해줘",
            )

            invalidAnswers.forEachIndexed { index, answer ->
                val turnId = TurnId("turn-no-tool-invalid-$index")
                val fixture = fixture()
                fixture.runtime.enqueueUser(
                    flow {
                        if (answer.isNotEmpty()) emit(ModelEvent.TextDelta(turnId, answer))
                        emit(ModelEvent.Completed(turnId))
                    },
                )

                val events = fixture.controller.runTurn(
                    turnId = turnId,
                    prompt = "trusted context wrapping a current request",
                    currentUserRequest = currentUserPrompt,
                ).toList()

                assertFailure(events, AgentFailureCode.INVALID_MODEL_SEQUENCE)
                assertTrue(events.none { it is AgentEvent.TextDelta })
                assertEquals(1, fixture.runtime.cancelCount.get())
            }
        }

    @Test
    fun `no-tool final still emits a bounded contentful answer`() = runBlocking {
        val turnId = TurnId("turn-no-tool-contentful")
        val fixture = fixture()
        val answer = "ORBIT-7은 오프라인에서 동작하는 후보입니다."
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.TextDelta(turnId, answer),
                ModelEvent.Completed(turnId),
            ),
        )

        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = "trusted context wrapping a current request",
            currentUserRequest = "ORBIT-7을 설명해 줘",
        ).toList()

        assertEquals(answer, events.filterIsInstance<AgentEvent.TextDelta>().single().text)
        assertEquals(AgentEvent.Completed(turnId), events.last())
        assertEquals(0, fixture.runtime.cancelCount.get())
    }

    @Test
    fun `successful fake call is confirmed and reinjected exactly once with trusted identity`() =
        runBlocking {
            val turnId = TurnId("turn-success")
            val fixture = fixture()
            fixture.runtime.enqueueUser(
                toolStep(
                    turnId,
                    call = call(
                        id = "call-1",
                        arguments = """{"recipient":" 아내 ","message":" 30분 뒤 도착 "}""",
                    ),
                ),
            )
            fixture.runtime.enqueueResponse(
                flowOf(
                    ModelEvent.TextDelta(turnId, "가상 전송을 확인했습니다."),
                    ModelEvent.Completed(turnId),
                ),
            )

            val events = fixture.controller.runTurn(turnId, "도착 알림을 보내 줘").toList()

            assertEquals(1, fixture.confirmations.size)
            assertEquals(FakeArrivalNoticeTool.NAME, fixture.confirmations.single().toolName)
            assertEquals(1, fixture.runtime.responseInvocations.size)
            val reinjection = fixture.runtime.responseInvocations.single()
            assertEquals(turnId, reinjection.turnId)
            assertEquals(1, reinjection.responses.size)
            assertEquals("call-1", reinjection.responses.single().callId)
            assertEquals(FakeArrivalNoticeTool.NAME, reinjection.responses.single().name)
            assertEquals(
                """{"simulated":true}""",
                reinjection.responses.single().payloadJson,
            )
            assertEquals(
                ToolExecutionOutcome.READ_COMPLETED,
                events.filterIsInstance<AgentEvent.ToolExecuted>().single().outcome,
            )
            assertEquals(AgentEvent.Completed(turnId), events.last())
            assertEquals(0, fixture.runtime.cancelCount.get())
        }

    @Test
    fun `registry exposes a closed strict fake tool schema`() {
        val registry = ManualToolRegistry()

        assertEquals(listOf(FakeArrivalNoticeTool.NAME), registry.definitions.map { it.name })
        val schema = registry.definitions.single().parametersJsonSchema
        assertTrue(schema.contains("\"additionalProperties\":false"))
        assertTrue(schema.contains("\"required\":[\"recipient\",\"message\"]"))
    }

    @Test
    fun `device registry exposes the confirmed memory tool with a closed schema`() {
        val registry = deviceRegistry(
            routeGateway = object : RouteGateway {
                override suspend fun credentialsPresent(): Boolean = false
                override suspend fun estimate(origin: String, destination: String): RouteEstimate =
                    error("unused")
            },
        )

        val definition = registry.definitions.single { it.name == MemoryRememberTool.NAME }
        assertTrue(definition.parametersJsonSchema.contains("\"required\":[\"content\"]"))
        assertTrue(definition.parametersJsonSchema.contains("\"additionalProperties\":false"))
    }

    @Test
    fun `explicit weather request bypasses a wrong model place and returns one trusted answer`() = runBlocking {
        val turnId = TurnId("turn-weather-supplement")
        val expected = weatherResult()
        var gatewayLocation: String? = null
        val registry = deviceRegistry(
            routeGateway = unusedRouteGateway(),
            weatherGateway = object : WeatherGateway {
                override suspend fun currentAndToday(location: String): WeatherResult {
                    gatewayLocation = location
                    return expected
                }
            },
        )
        val fixture = fixture(
            registry = registry,
            executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
        )
        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = "trusted context wrapping the current request",
            currentUserRequest = "오늘 동탄 날씨를 알려줘",
        ).toList()

        val answer = events.filterIsInstance<AgentEvent.TrustedAnswer>().single().text
        assertEquals("동탄", gatewayLocation)
        assertTrue(answer.startsWith("동탄의 현재 날씨는 맑음이고, 21.4°C입니다"))
        assertTrue(answer.contains("오늘 최저 12.3°C, 최고 23.5°C"))
        assertTrue(answer.contains("최대 강수확률 30%"))
        assertTrue(answer.contains("확인 위치: 경기도 화성시 동탄"))
        assertTrue(answer.contains("https://open-meteo.com/"))
        assertFalse(answer.contains("서울"))
        assertFalse(answer.contains("5.1°C"))
        assertFalse(answer.contains("open-meteo-mmet"))
        assertTrue(events.none { it is AgentEvent.TextDelta })
        assertTrue(fixture.runtime.userInvocations.isEmpty())
        assertTrue(fixture.confirmations.isEmpty())
        assertEquals(AgentEvent.Completed(turnId), events.last())
    }

    @Test
    fun `read-only recovery allows a direct read without consulting the side effect gate`() =
        runBlocking {
            val turnId = TurnId("turn-read-only-direct-weather")
            var gateCalls = 0
            val fixture = fixture(
                registry = deviceRegistry(
                    routeGateway = unusedRouteGateway(),
                    weatherGateway = object : WeatherGateway {
                        override suspend fun currentAndToday(location: String): WeatherResult =
                            weatherResult()
                    },
                ),
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
                sideEffectTurnGate = SideEffectTurnGate { _, _, _ ->
                    gateCalls += 1
                    false
                },
            )

            val events = fixture.controller.runTurn(
                turnId = turnId,
                prompt = "trusted recovery context",
                currentUserRequest = "오늘 동탄 날씨를 알려줘",
                readOnlyToolsOnly = true,
            ).toList()

            assertEquals(0, gateCalls)
            assertEquals(ToolExecutionOutcome.READ_COMPLETED, events
                .filterIsInstance<AgentEvent.ToolExecuted>()
                .single()
                .outcome)
            assertEquals(AgentEvent.Completed(turnId), events.last())
            assertTrue(fixture.runtime.userInvocations.isEmpty())
            assertTrue(fixture.confirmations.isEmpty())
        }

    @Test
    fun `read-only recovery blocks a model-proposed write before prepare and confirmation`() =
        runBlocking {
            val turnId = TurnId("turn-read-only-block-write")
            var memoryWrites = 0
            var gateCalls = 0
            val fixture = fixture(
                registry = deviceRegistry(
                    routeGateway = unusedRouteGateway(),
                    memoryGateway = MemoryGateway {
                        memoryWrites += 1
                        MemoryWriteOutcome.SAVED
                    },
                ),
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
                sideEffectTurnGate = SideEffectTurnGate { _, _, _ ->
                    gateCalls += 1
                    true
                },
            )
            fixture.runtime.enqueueUser(
                toolStep(
                    turnId,
                    call(
                        id = "call-recovery-write",
                        name = MemoryRememberTool.NAME,
                        arguments = """{"content":"이 내용을 기억해 줘","category":"fact"}""",
                    ),
                ),
            )

            val events = fixture.controller.runTurn(
                turnId = turnId,
                prompt = "model text cannot relax the read-only recovery boundary",
                readOnlyToolsOnly = true,
            ).toList()

            assertFailure(events, AgentFailureCode.TOOL_NOT_EXECUTED)
            assertEquals(0, memoryWrites)
            assertEquals(0, gateCalls)
            assertTrue(fixture.confirmations.isEmpty())
            assertTrue(fixture.runtime.responseInvocations.isEmpty())
            assertTrue(events.none { it is AgentEvent.ToolExecuted })
        }

    @Test
    fun `read recovery cannot finish from model prose without a fresh trusted read`() =
        runBlocking {
            val turnId = TurnId("turn-recovery-requires-read")
            val fixture = fixture()
            fixture.runtime.enqueueUser(
                flowOf(
                    ModelEvent.TextDelta(turnId, "이전 결과만으로 답합니다."),
                    ModelEvent.Completed(turnId),
                ),
            )

            val events = fixture.controller.runTurn(
                turnId = turnId,
                prompt = "trusted recovery context",
                readOnlyToolsOnly = true,
                requireReadTool = true,
            ).toList()

            assertFailure(events, AgentFailureCode.TOOL_NOT_EXECUTED)
            assertTrue(events.none { it is AgentEvent.TextDelta })
            assertTrue(events.none { it is AgentEvent.TrustedAnswer })
            assertTrue(events.none { it is AgentEvent.ToolExecuted })
        }

    @Test
    fun `trusted write terminal answers distinguish completion and refusal`() {
        assertEquals(
            "승인한 내용을 장기 기억에 저장했습니다.",
            trustedWriteTerminalAnswer(
                MemoryRememberTool.NAME,
                ToolExecutionOutcome.WRITE_COMPLETED,
            ),
        )
        assertEquals(
            "장기 기억에 저장하지 않았습니다.",
            trustedWriteTerminalAnswer(
                MemoryRememberTool.NAME,
                ToolExecutionOutcome.WRITE_REFUSED,
            ),
        )
    }

    @Test
    fun `model selected weather suppresses every contradictory model delta`() = runBlocking {
        val turnId = TurnId("turn-weather-model-delta")
        val registry = deviceRegistry(
            routeGateway = unusedRouteGateway(),
            weatherGateway = object : WeatherGateway {
                override suspend fun currentAndToday(location: String): WeatherResult = weatherResult()
            },
        )
        val fixture = fixture(
            registry = registry,
            executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
        )
        fixture.runtime.enqueueUser(
            toolStep(
                turnId,
                call(
                    id = "call-weather-model",
                    name = WeatherTool.NAME,
                    arguments = """{"location":"동탄"}""",
                ),
            ),
        )
        fixture.runtime.enqueueResponse(
            flowOf(
                ModelEvent.TextDelta(
                    turnId,
                    "서울은 5.1°C이고 https://open-meteo-mmet.com/ 입니다.",
                ),
                ModelEvent.Completed(turnId),
            ),
        )

        val events = fixture.controller.runTurn(turnId, "implicit weather intent").toList()

        assertTrue(events.none { it is AgentEvent.TextDelta })
        val answer = events.filterIsInstance<AgentEvent.TrustedAnswer>().single().text
        assertTrue(answer.startsWith("동탄의 현재 날씨는"))
        assertFalse(answer.contains("서울"))
        assertFalse(answer.contains("5.1°C"))
        assertFalse(answer.contains("open-meteo-mmet"))
    }

    @Test
    fun `weather place hints prefer the explicit target and correction`() {
        assertEquals("동탄", weatherLocationHintOrNull("오늘 동탄 날씨를 알려줘"))
        assertEquals("경기도 이천", weatherLocationHintOrNull("오늘 경기도 이천 날씨 알려줘."))
        assertEquals("동탄", weatherLocationHintOrNull("서울이 아니라 동탄"))
        assertEquals("동탄", weatherLocationHintOrNull("서울 말고 동탄의 현재 날씨를 알려줘"))
        assertNull(weatherLocationHintOrNull("오늘 날씨를 알려줘"))
        assertNull(weatherLocationHintOrNull("왜 답변을 안 해줘"))
    }

    @Test
    fun `recent weather correction starts naturally with the corrected place`() = runBlocking {
        val turnId = TurnId("turn-weather-correction")
        val fixture = fixture(
            registry = deviceRegistry(
                routeGateway = unusedRouteGateway(),
                weatherGateway = object : WeatherGateway {
                    override suspend fun currentAndToday(location: String): WeatherResult =
                        weatherResult()
                },
            ),
            executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
        )

        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = "trusted context with an app-authored prior weather receipt",
            currentUserRequest = "서울이 아니라 동탄",
            recentWeatherRead = true,
        ).toList()

        val answer = events.filterIsInstance<AgentEvent.TrustedAnswer>().single().text
        assertTrue(answer.startsWith("동탄의 현재 날씨는"))
        assertFalse(answer.startsWith("서울이 아니라"))
        assertTrue(fixture.runtime.userInvocations.isEmpty())
    }

    @Test
    fun `explicit public person search filters evidence then synthesizes with app-owned sources`() =
        runBlocking {
            val turnId = TurnId("turn-direct-public-search")
            var gatewayQuery: String? = null
            val registry = deviceRegistry(
                routeGateway = unusedRouteGateway(),
                webSearchGateway = object : WebSearchGateway {
                    override suspend fun credentialsPresent(): Boolean = true

                    override suspend fun search(query: String, limit: Int): WebSearchResponse {
                        gatewayQuery = query
                        return WebSearchResponse(
                            provider = WebSearchProvider.YOU_COM,
                            hits = listOf(
                                WebSearchHit(
                                    title = "공개 프로필 결과",
                                    link = "https://example.com/profile",
                                    snippet = "SK하이닉스 김재범 부사장은 미래기술연구원에서 R&D 전략을 담당합니다.",
                                ),
                            ),
                        )
                    }
                },
            )
            val fixture = fixture(
                registry = registry,
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
            )
            fixture.runtime.enqueueUser(
                flowOf(
                    ModelEvent.ThoughtDelta(
                        turnId,
                        "공개 검색 근거에서 이름과 소속이 일치하는지 검토합니다.",
                    ),
                    ModelEvent.TextDelta(
                        turnId,
                        "김재범은 SK하이닉스 미래기술연구원에서 R&D 전략을 담당하는 부사장입니다.",
                    ),
                    ModelEvent.Completed(turnId),
                ),
            )

            val events = fixture.controller.runTurn(
                turnId = turnId,
                prompt = "model would have failed before a tool call",
                currentUserRequest = "SK하이닉스 김재범이란 사람에 대해 찾아서 알려줘",
            ).toList()

            assertEquals("SK하이닉스 김재범", gatewayQuery)
            assertEquals(1, fixture.runtime.userInvocations.size)
            assertFalse(fixture.runtime.userInvocations.single().second.contains("https://"))
            assertEquals(
                listOf("공개 검색 근거에서 이름과 소속이 일치하는지 검토합니다."),
                events.filterIsInstance<AgentEvent.ThoughtDelta>().map(AgentEvent.ThoughtDelta::text),
            )
            assertTrue(events.single { it is AgentEvent.ToolExecuted } is AgentEvent.ToolExecuted)
            val answer = events.filterIsInstance<AgentEvent.TextDelta>().single().text
            assertTrue(answer.startsWith("김재범은 SK하이닉스"))
            assertTrue(answer.contains("공개 프로필 결과"))
            assertTrue(answer.contains("https://example.com/profile"))
            assertTrue(answer.contains("이름과 소속이 함께 확인되는 자료만 반영했습니다"))
            assertFalse(answer.contains("검색 제공:"))
            assertEquals(AgentEvent.Completed(turnId), events.last())
        }

    @Test
    fun `explicit local knowledge gap automatically searches the owner-authored film subject`() =
        runBlocking {
            val turnId = TurnId("turn-automatic-film-search")
            var gatewayQuery: String? = null
            var searchCount = 0
            val fixture = fixture(
                registry = deviceRegistry(
                    routeGateway = unusedRouteGateway(),
                    webSearchGateway = object : WebSearchGateway {
                        override suspend fun credentialsPresent(): Boolean = true

                        override suspend fun search(query: String, limit: Int): WebSearchResponse {
                            searchCount++
                            gatewayQuery = query
                            return filmSearchResponse()
                        }
                    },
                ),
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
            )
            fixture.runtime.enqueueUser(
                flowOf(
                    ModelEvent.TextDelta(
                        turnId,
                        "제가 가지고 있는 정보로는 이 영화의 자세한 정보를 제공해 드릴 수 없습니다.",
                    ),
                    ModelEvent.Completed(turnId),
                ),
            )
            fixture.runtime.enqueueUser(
                flowOf(
                    ModelEvent.TextDelta(
                        turnId,
                        "미야자키 하야오가 연출한 일본 애니메이션 영화입니다.",
                    ),
                    ModelEvent.Completed(turnId),
                ),
            )
            val request = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘"

            val events = fixture.controller.runTurn(
                turnId = turnId,
                prompt = request,
                currentUserRequest = request,
            ).toList()

            assertEquals("그대들은 어떻게 살것인가 영화", gatewayQuery)
            assertEquals(1, searchCount)
            assertEquals(2, fixture.runtime.userInvocations.size)
            assertEquals(1, events.filterIsInstance<AgentEvent.ToolExecuted>().size)
            val answer = events.filterIsInstance<AgentEvent.TextDelta>().single().text
            assertTrue(answer.startsWith("미야자키 하야오"))
            assertTrue(answer.contains("https://example.com/how-do-you-live"))
            assertFalse(events.toString().contains("가지고 있는 정보로는"))
            assertEquals(AgentEvent.Completed(turnId), events.last())
        }

    @Test
    fun `optional film scope rejects a hallucinated non-web tool before execution`() = runBlocking {
        val turnId = TurnId("turn-film-off-scope-tool")
        val fixture = fixture(
            registry = deviceRegistry(routeGateway = unusedRouteGateway()),
            executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
        )
        fixture.runtime.enqueueUser(
            toolStep(
                turnId,
                call(
                    id = "call-film-calendar",
                    name = "calendar_query",
                    arguments = "{}",
                ),
            ),
        )
        val request = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘"

        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = request,
            currentUserRequest = request,
        ).toList()

        assertFailure(events, AgentFailureCode.INVALID_MODEL_SEQUENCE)
        assertTrue(events.none { event -> event is AgentEvent.ToolExecuted })
        assertTrue(fixture.confirmations.isEmpty())
    }

    @Test
    fun `automatic film fallback obeys the existing network interlock`() = runBlocking {
        val turnId = TurnId("turn-film-network-consent-off")
        var searchCount = 0
        val fixture = fixture(
            registry = deviceRegistry(
                routeGateway = unusedRouteGateway(),
                webSearchGateway = object : WebSearchGateway {
                    override suspend fun credentialsPresent(): Boolean = true

                    override suspend fun search(query: String, limit: Int): WebSearchResponse {
                        searchCount++
                        return filmSearchResponse()
                    }
                },
            ),
            executionInterlock = ExecutionInterlock {
                InterlockDecision.Block("웹 검색 외부 전송 동의가 꺼져 있습니다.")
            },
        )
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.TextDelta(turnId, "제가 가지고 있는 정보로는 잘 모르겠습니다."),
                ModelEvent.Completed(turnId),
            ),
        )
        val request = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘"

        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = request,
            currentUserRequest = request,
        ).toList()

        assertEquals(0, searchCount)
        assertTrue(events.last() is AgentEvent.Failure)
        assertTrue(events.none { event -> event is AgentEvent.ToolExecuted })
    }

    @Test
    fun `subjectless web follow-up uses typed previous user subject instead of condition text`() =
        runBlocking {
            val turnId = TurnId("turn-contextual-film-search")
            var gatewayQuery: String? = null
            val fixture = fixture(
                registry = deviceRegistry(
                    routeGateway = unusedRouteGateway(),
                    webSearchGateway = object : WebSearchGateway {
                        override suspend fun credentialsPresent(): Boolean = true

                        override suspend fun search(query: String, limit: Int): WebSearchResponse {
                            gatewayQuery = query
                            return filmSearchResponse()
                        }
                    },
                ),
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
            )
            fixture.runtime.enqueueUser(
                flowOf(
                    ModelEvent.TextDelta(
                        turnId,
                        "미야자키 하야오가 연출한 일본 애니메이션 영화입니다.",
                    ),
                    ModelEvent.Completed(turnId),
                ),
            )
            val followUp = "잘 모르겠으면 웹에서 찾아서 알려줘"
            val trusted = requireNotNull(
                AutomaticWebSearchPolicy.contextualRequestOrNull(
                    followUp = followUp,
                    previousUserRequest = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘",
                ),
            )
            assertEquals(
                1_024,
                fixture.controller.limitsForPrompt(
                    prompt = followUp,
                    inheritLongFormRequest = true,
                ).maxOutputTokens,
            )
            assertEquals(
                TurnOutputBudgetPolicy.STRUCTURED_TOOL_OUTPUT_TOKENS,
                fixture.controller.limitsForPrompt(followUp).maxOutputTokens,
            )
            val explicitSearchFollowUp = "웹 검색해서 알려줘"
            assertEquals(
                TurnOutputBudgetPolicy.STRUCTURED_TOOL_OUTPUT_TOKENS,
                fixture.controller.limitsForPrompt(explicitSearchFollowUp).maxOutputTokens,
            )
            assertEquals(
                1_024,
                fixture.controller.limitsForPrompt(
                    prompt = explicitSearchFollowUp,
                    inheritLongFormRequest = true,
                ).maxOutputTokens,
            )

            val events = fixture.controller.runTurn(
                turnId = turnId,
                prompt = "trusted conversation context",
                currentUserRequest = followUp,
                contextualWebSearchRequest = trusted,
            ).toList()

            assertEquals("그대들은 어떻게 살것인가 영화", gatewayQuery)
            assertFalse(gatewayQuery.orEmpty().contains("모르겠으면"))
            assertEquals(1, fixture.runtime.userInvocations.size)
            assertEquals(1_024, fixture.runtime.outputTokenInvocations.single())
            assertEquals(1, events.filterIsInstance<AgentEvent.ToolExecuted>().size)
            assertTrue(
                events.filterIsInstance<AgentEvent.TextDelta>().single().text
                    .contains("https://example.com/how-do-you-live"),
            )
            assertEquals(AgentEvent.Completed(turnId), events.last())

            val hotTurnId = TurnId("turn-contextual-film-search-hot")
            fixture.runtime.enqueueUser(
                flowOf(
                    ModelEvent.TextDelta(
                        hotTurnId,
                        "미야자키 하야오가 연출한 일본 애니메이션 영화입니다.",
                    ),
                    ModelEvent.Completed(hotTurnId),
                ),
            )
            val hotEvents = fixture.controller.runTurn(
                turnId = hotTurnId,
                prompt = "trusted conversation context",
                turnLimits = AgentLoopLimits(maxOutputTokens = 256),
                currentUserRequest = followUp,
                contextualWebSearchRequest = trusted,
            ).toList()
            assertEquals(listOf(1_024, 256), fixture.runtime.outputTokenInvocations)
            assertEquals(AgentEvent.Completed(hotTurnId), hotEvents.last())
        }

    @Test
    fun `recovery of automatic web fallback reuses original knowledge request deterministically`() =
        runBlocking {
            val turnId = TurnId("turn-recover-automatic-film-search")
            var gatewayQuery: String? = null
            val fixture = fixture(
                registry = deviceRegistry(
                    routeGateway = unusedRouteGateway(),
                    webSearchGateway = object : WebSearchGateway {
                        override suspend fun credentialsPresent(): Boolean = true

                        override suspend fun search(query: String, limit: Int): WebSearchResponse {
                            gatewayQuery = query
                            return filmSearchResponse()
                        }
                    },
                ),
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
            )
            fixture.runtime.enqueueUser(
                flowOf(
                    ModelEvent.TextDelta(
                        turnId,
                        "미야자키 하야오가 연출한 일본 애니메이션 영화입니다.",
                    ),
                    ModelEvent.Completed(turnId),
                ),
            )
            val original = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘"

            val events = fixture.controller.runTurn(
                turnId = turnId,
                prompt = "trusted recovery context",
                currentUserRequest = original,
                readOnlyToolsOnly = true,
                requireReadTool = true,
                executionContract = TurnExecutionContract.exactReads(listOf(WebSearchTool.NAME)),
            ).toList()

            assertEquals("그대들은 어떻게 살것인가 영화", gatewayQuery)
            assertEquals(1, fixture.runtime.userInvocations.size)
            assertEquals(1, events.filterIsInstance<AgentEvent.ToolExecuted>().size)
            assertEquals(AgentEvent.Completed(turnId), events.last())
        }

    @Test
    fun `invalid web synthesis falls back without a second search or model-owned link`() =
        runBlocking {
            val turnId = TurnId("turn-direct-public-search-fallback")
            var searchCount = 0
            val fixture = fixture(
                registry = deviceRegistry(
                    routeGateway = unusedRouteGateway(),
                    webSearchGateway = object : WebSearchGateway {
                        override suspend fun credentialsPresent(): Boolean = true

                        override suspend fun search(query: String, limit: Int): WebSearchResponse {
                            searchCount++
                            return WebSearchResponse(
                                provider = WebSearchProvider.YOU_COM,
                                hits = listOf(
                                    WebSearchHit(
                                        title = "SK하이닉스 김재범 부사장 공개 자료",
                                        link = "https://news.skhynix.co.kr/profile",
                                        snippet = "SK하이닉스 김재범 부사장은 미래기술연구원에서 R&D 전략을 담당합니다.",
                                    ),
                                    WebSearchHit(
                                        title = "김재범 부친상",
                                        link = "https://obituary.example/kim",
                                        snippet = "김재범 씨의 부친이 별세해 빈소가 마련됐습니다.",
                                    ),
                                ),
                            )
                        }
                    },
                ),
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
            )
            fixture.runtime.enqueueUser(
                flowOf(
                    ModelEvent.TextDelta(
                        turnId,
                        "김재범 관련 내용입니다. https://attacker.example/fake",
                    ),
                    ModelEvent.Completed(turnId),
                ),
            )

            val events = fixture.controller.runTurn(
                turnId = turnId,
                prompt = "direct search",
                currentUserRequest = "SK하이닉스 김재범이라는 사람을 조사해서 알려줘",
            ).toList()

            assertEquals(1, searchCount)
            assertEquals(1, fixture.runtime.userInvocations.size)
            val answer = events.filterIsInstance<AgentEvent.TrustedAnswer>().single().text
            assertTrue(answer.contains("SK하이닉스 김재범"))
            assertTrue(answer.contains("https://news.skhynix.co.kr/profile"))
            assertFalse(answer.contains("attacker.example"))
            assertFalse(answer.contains("obituary.example"))
            assertFalse(answer.contains("부친상"))
            assertEquals(AgentEvent.Completed(turnId), events.last())
        }

    @Test
    fun `failed or tool-producing web synthesis falls back after exactly one search`() =
        runBlocking {
            val synthesisCases: List<Pair<String, (TurnId) -> Flow<ModelEvent>>> = listOf(
                "failure" to { turnId ->
                    flowOf(ModelEvent.Failure(turnId, LlmFailureCode.NATIVE_FAILURE))
                },
                "tool-call" to { turnId ->
                    toolStep(
                        turnId,
                        call(
                            id = "unexpected-synthesis-call",
                            name = WebSearchTool.NAME,
                            arguments = """{"query":"repeat"}""",
                        ),
                    )
                },
                "incomplete-text" to { turnId ->
                    flowOf(
                        ModelEvent.TextDelta(
                            turnId,
                            "OpenAI는 새로운 모델을 공개하고 개발자 문서를",
                        ),
                        ModelEvent.Completed(turnId),
                    )
                },
            )
            synthesisCases.forEach { (label, modelEvents) ->
                val turnId = TurnId("turn-web-synthesis-$label")
                var searchCount = 0
                val fixture = fixture(
                    registry = deviceRegistry(
                        routeGateway = unusedRouteGateway(),
                        webSearchGateway = object : WebSearchGateway {
                            override suspend fun credentialsPresent(): Boolean = true

                            override suspend fun search(
                                query: String,
                                limit: Int,
                            ): WebSearchResponse {
                                searchCount++
                                return WebSearchResponse(
                                    provider = WebSearchProvider.YOU_COM,
                                    hits = listOf(
                                        WebSearchHit(
                                            title = "OpenAI 새 모델 공개",
                                            link = "https://openai.com/news/model",
                                            snippet = "OpenAI는 새로운 모델을 공개했습니다.",
                                        ),
                                    ),
                                )
                            }
                        },
                    ),
                    executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
                )
                fixture.runtime.enqueueUser(modelEvents(turnId))

                val events = fixture.controller.runTurn(
                    turnId = turnId,
                    prompt = "direct search",
                    currentUserRequest = "OpenAI 최신 소식을 검색해서 알려줘",
                ).toList()

                assertEquals(label, 1, searchCount)
                assertEquals(label, 1, fixture.runtime.userInvocations.size)
                assertEquals(label, 1, events.filterIsInstance<AgentEvent.TrustedAnswer>().size)
                assertTrue(label, events.none { event -> event is AgentEvent.TextDelta })
                assertTrue(label, events.none { event -> event is AgentEvent.Failure })
                assertEquals(label, AgentEvent.Completed(turnId), events.last())
            }
        }

    @Test
    fun `explicit search extraction rejects requests that also ask for a write`() {
        assertEquals(
            "SK하이닉스 김재범",
            explicitWebSearchQueryOrNull("SK하이닉스 김재범이란 사람에 대해 찾아서 알려줘"),
        )
        assertEquals(
            "SK하이닉스 김재범",
            explicitWebSearchQueryOrNull("SK하이닉스 김재범이라는 사람을 조사해서 알려줘"),
        )
        assertNull(explicitWebSearchQueryOrNull("김재범을 찾아서 카카오톡으로 보내줘"))
        assertNull(explicitWebSearchQueryOrNull("왜 중단했어?"))
    }

    @Test
    fun `previous search summary follow-up uses conversation context without another web request`() =
        runBlocking {
            val turnId = TurnId("turn-search-follow-up")
            var searchCount = 0
            val fixture = fixture(
                registry = deviceRegistry(
                    routeGateway = unusedRouteGateway(),
                    webSearchGateway = object : WebSearchGateway {
                        override suspend fun credentialsPresent(): Boolean = true

                        override suspend fun search(query: String, limit: Int): WebSearchResponse {
                            searchCount++
                            error("A result-summary follow-up must not start a new search.")
                        }
                    },
                ),
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
            )
            fixture.runtime.enqueueUser(
                flowOf(
                    ModelEvent.TextDelta(
                        turnId,
                        "김재범은 SK하이닉스에서 R&D 전략을 담당한다는 내용이 핵심입니다.",
                    ),
                    ModelEvent.Completed(turnId),
                ),
            )
            val trustedContext = """
                [이전 대화 데이터]
                - 도우미: '김재범은 SK하이닉스에서 R&D 전략을 담당합니다.'
                [현재 사용자 요청]
                검색결과를 정리해서 요약해줘
            """.trimIndent()

            val events = fixture.controller.runTurn(
                turnId = turnId,
                prompt = trustedContext,
                currentUserRequest = "검색결과를 정리해서 요약해줘",
            ).toList()

            assertEquals(0, searchCount)
            assertTrue(events.none { event -> event is AgentEvent.ToolExecuted })
            assertEquals(trustedContext, fixture.runtime.userInvocations.single().second)
            assertTrue(
                events.filterIsInstance<AgentEvent.TextDelta>().single().text.contains("SK하이닉스"),
            )
            assertTrue(
                events.filterIsInstance<AgentEvent.TextDelta>().single().text.contains("R&D 전략"),
            )
            assertEquals(AgentEvent.Completed(turnId), events.last())
        }

    @Test
    fun `previous search summary scope rejects a hallucinated repeat search before the gateway`() =
        runBlocking {
            val turnId = TurnId("turn-search-follow-up-hidden-tool")
            var searchCount = 0
            val fixture = fixture(
                registry = deviceRegistry(
                    routeGateway = unusedRouteGateway(),
                    webSearchGateway = object : WebSearchGateway {
                        override suspend fun credentialsPresent(): Boolean = true

                        override suspend fun search(query: String, limit: Int): WebSearchResponse {
                            searchCount++
                            return WebSearchResponse(WebSearchProvider.YOU_COM, emptyList())
                        }
                    },
                ),
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
            )
            fixture.runtime.enqueueUser(
                toolStep(
                    turnId,
                    call(
                        id = "call-hidden-web",
                        name = WebSearchTool.NAME,
                        arguments = """{"query":"결과를 정리해서 요약해줘"}""",
                    ),
                ),
            )

            val events = fixture.controller.runTurn(
                turnId = turnId,
                prompt = "trusted prior search context",
                currentUserRequest = "검색결과를 정리해서 요약해줘",
            ).toList()

            assertFailure(events, AgentFailureCode.INVALID_MODEL_SEQUENCE)
            assertEquals(0, searchCount)
            assertTrue(events.none { event -> event is AgentEvent.ToolExecuted })
        }

    @Test
    fun `model-selected web read keeps synthesized prose but Kotlin owns the source links`() =
        runBlocking {
            val turnId = TurnId("turn-model-web-synthesis")
            val fixture = fixture(
                registry = deviceRegistry(
                    routeGateway = unusedRouteGateway(),
                    webSearchGateway = object : WebSearchGateway {
                        override suspend fun credentialsPresent(): Boolean = true

                        override suspend fun search(query: String, limit: Int): WebSearchResponse =
                            WebSearchResponse(
                                provider = WebSearchProvider.YOU_COM,
                                hits = listOf(
                                    WebSearchHit(
                                        title = "OpenAI 새 모델 공개",
                                        link = "https://openai.com/news/model",
                                        snippet = "OpenAI는 새로운 모델을 공개했습니다.",
                                    ),
                                    WebSearchHit(
                                        title = "OpenAI 임원 부친상",
                                        link = "https://obituary.example/openai",
                                        snippet = "OpenAI 임원의 가족상과 빈소를 안내합니다.",
                                    ),
                                ),
                            )
                    },
                ),
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
            )
            fixture.runtime.enqueueUser(
                toolStep(
                    turnId,
                    call(
                        id = "call-web",
                        name = WebSearchTool.NAME,
                        arguments = """{"query":"OpenAI 최신 뉴스"}""",
                    ),
                ),
            )
            fixture.runtime.enqueueResponse(
                flowOf(
                    ModelEvent.TextDelta(turnId, "OpenAI는 새로운 모델을 공개했습니다."),
                    ModelEvent.Completed(turnId),
                ),
            )

            val events = fixture.controller.runTurn(
                turnId = turnId,
                prompt = "OpenAI 최신 뉴스를 알려줘",
                currentUserRequest = "OpenAI 최신 뉴스를 알려줘",
            ).toList()

            assertEquals(1, fixture.runtime.responseInvocations.size)
            val reinjectedPayload = fixture.runtime.responseInvocations.single()
                .responses.single().payloadJson
            assertTrue(reinjectedPayload.contains("https://openai.com/news/model"))
            assertFalse(reinjectedPayload.contains("obituary.example"))
            assertFalse(reinjectedPayload.contains("부친상"))
            val answer = events.filterIsInstance<AgentEvent.TextDelta>().single().text
            assertTrue(answer.startsWith("OpenAI는 새로운 모델을 공개했습니다."))
            assertTrue(answer.contains("\n\n출처\n1. OpenAI 새 모델 공개"))
            assertTrue(answer.contains("https://openai.com/news/model"))
            assertFalse(answer.contains("검색 제공:"))
            assertEquals(AgentEvent.Completed(turnId), events.last())
        }

    @Test
    fun `unknown tool never reaches confirmation execution or reinjection`() = runBlocking {
        val turnId = TurnId("turn-unknown")
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            toolStep(turnId, call(id = "call-1", name = "unregistered_tool")),
        )

        val events = fixture.controller.runTurn(turnId, "do it").toList()

        assertFailure(events, AgentFailureCode.UNKNOWN_TOOL)
        assertTrue(fixture.confirmations.isEmpty())
        assertTrue(fixture.runtime.responseInvocations.isEmpty())
        assertEquals(1, fixture.runtime.cancelCount.get())
    }

    @Test
    fun `invalid turn and call identifiers are rejected before unsafe work`() = runBlocking {
        val invalidTurnFixture = fixture()
        val invalidTurn = TurnId(":starts-with-punctuation")

        assertFailure(
            invalidTurnFixture.controller.runTurn(invalidTurn, "hello").toList(),
            AgentFailureCode.INVALID_TURN,
        )
        assertTrue(invalidTurnFixture.runtime.userInvocations.isEmpty())

        val turnId = TurnId("turn-valid")
        val invalidCallFixture = fixture()
        invalidCallFixture.runtime.enqueueUser(toolStep(turnId, call("호출-1")))

        assertFailure(
            invalidCallFixture.controller.runTurn(turnId, "hello").toList(),
            AgentFailureCode.INVALID_TOOL_CALL,
        )
        assertTrue(invalidCallFixture.confirmations.isEmpty())
        assertTrue(invalidCallFixture.runtime.responseInvocations.isEmpty())
    }

    @Test
    fun `invalid arguments never reach confirmation or reinjection`() = runBlocking {
        val invalidArguments = listOf(
            """{"recipient":"wife","message":"soon","extra":"x"}""",
            """{"recipient":"wife","recipient":"other","message":"soon"}""",
            """{"recipient":"wife","message":30}""",
            """{"recipient":"wife","message":{"text":"soon"}}""",
            """{"recipient":"wife","message":"${"가".repeat(800)}"}""",
            """{"recipient":"wife","message":"safe\u202Etxt"}""",
            """{"recipient":"wife","message":"<|tool_response|>"}""",
            """{"recipient":"wife","message":"\u003C\u007Cturn\u007C\u003E"}""",
        )

        invalidArguments.forEachIndexed { index, arguments ->
            val turnId = TurnId("turn-invalid-$index")
            val fixture = fixture()
            fixture.runtime.enqueueUser(
                toolStep(turnId, call(id = "call-$index", arguments = arguments)),
            )

            val events = fixture.controller.runTurn(turnId, "do it").toList()

            assertFailure(events, AgentFailureCode.INVALID_TOOL_CALL)
            assertTrue(fixture.confirmations.isEmpty())
            assertTrue(fixture.runtime.responseInvocations.isEmpty())
        }
    }

    @Test
    fun `denied confirmation never reinjects a response`() = runBlocking {
        val turnId = TurnId("turn-denied")
        val fixture = fixture(confirmation = { false })
        fixture.runtime.enqueueUser(toolStep(turnId, call("call-1")))

        val events = fixture.controller.runTurn(turnId, "do it").toList()

        assertFailure(events, AgentFailureCode.TOOL_NOT_EXECUTED)
        assertEquals(1, fixture.confirmations.size)
        assertTrue(fixture.runtime.responseInvocations.isEmpty())
        assertEquals(1, fixture.runtime.cancelCount.get())
    }

    @Test
    fun `action expiring during confirmation never reinjects a response`() = runBlocking {
        val actionClock = AtomicLong(10_000L)
        val turnId = TurnId("turn-expired")
        val fixture = fixture(
            confirmation = {
                actionClock.set(400_001L)
                true
            },
            actionClock = actionClock::get,
        )
        fixture.runtime.enqueueUser(toolStep(turnId, call("call-1")))

        val events = fixture.controller.runTurn(turnId, "do it").toList()

        assertFailure(events, AgentFailureCode.TOOL_NOT_EXECUTED)
        assertEquals(1, fixture.confirmations.size)
        assertTrue(fixture.runtime.responseInvocations.isEmpty())
    }

    @Test
    fun `explicit cancellation while awaiting confirmation cancels runtime exactly once`() =
        runBlocking {
            val enteredConfirmation = CompletableDeferred<Unit>()
            val releaseConfirmation = CompletableDeferred<Boolean>()
            val turnId = TurnId("turn-cancel")
            val fixture = fixture(
                confirmation = {
                    enteredConfirmation.complete(Unit)
                    releaseConfirmation.await()
                },
            )
            fixture.runtime.enqueueUser(toolStep(turnId, call("call-1")))
            val observedEvents = mutableListOf<AgentEvent>()
            val collection = launch {
                fixture.controller.runTurn(turnId, "do it").collect(observedEvents::add)
            }

            enteredConfirmation.await()
            assertTrue(fixture.controller.cancel(turnId))
            assertFalse(fixture.controller.cancel(TurnId("different-turn")))
            collection.join()

            assertTrue(collection.isCancelled)
            assertTrue(fixture.runtime.responseInvocations.isEmpty())
            assertTrue(observedEvents.none { it is AgentEvent.ToolExecuted })
            assertEquals(1, fixture.runtime.cancelCount.get())
        }

    @Test
    fun `turn mismatch and incomplete model streams fail before tool work`() = runBlocking {
        val expectedTurn = TurnId("turn-expected")
        val mismatchFixture = fixture()
        mismatchFixture.runtime.enqueueUser(
            flowOf(ModelEvent.Completed(TurnId("turn-other"))),
        )

        assertFailure(
            mismatchFixture.controller.runTurn(expectedTurn, "hello").toList(),
            AgentFailureCode.TURN_MISMATCH,
        )

        val incompleteFixture = fixture()
        incompleteFixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.FinalToolCalls(expectedTurn, listOf(call("call-1"))),
            ),
        )
        assertFailure(
            incompleteFixture.controller.runTurn(expectedTurn, "hello").toList(),
            AgentFailureCode.INVALID_MODEL_SEQUENCE,
        )
        assertTrue(incompleteFixture.confirmations.isEmpty())
        assertTrue(incompleteFixture.runtime.responseInvocations.isEmpty())
    }

    @Test
    fun `multiple calls in one model step are rejected as outside MVP scope`() = runBlocking {
        val turnId = TurnId("turn-parallel-calls")
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.FinalToolCalls(
                    turnId,
                    listOf(call("call-1"), call("call-2")),
                ),
            ),
        )

        val events = fixture.controller.runTurn(turnId, "do both").toList()

        assertFailure(events, AgentFailureCode.INVALID_MODEL_SEQUENCE)
        assertTrue(fixture.confirmations.isEmpty())
        assertTrue(fixture.runtime.responseInvocations.isEmpty())
    }

    @Test
    fun `non-completion events after final tool calls are rejected before confirmation`() =
        runBlocking {
            val turnId = TurnId("turn-invalid-final-sequence")
            val fixture = fixture()
            fixture.runtime.enqueueUser(
                flowOf(
                    ModelEvent.FinalToolCalls(turnId, listOf(call("call-1"))),
                    ModelEvent.TextDelta(turnId, "unexpected"),
                    ModelEvent.Completed(turnId),
                ),
            )

            val events = fixture.controller.runTurn(turnId, "do it").toList()

            assertFailure(events, AgentFailureCode.INVALID_MODEL_SEQUENCE)
            assertTrue(fixture.confirmations.isEmpty())
            assertTrue(fixture.runtime.responseInvocations.isEmpty())
        }

    @Test
    fun `final tool calls do not execute until matching completed arrives`() = runBlocking {
        val turnId = TurnId("turn-waits-for-completed")
        val finalCallsEmitted = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flow {
                emit(ModelEvent.FinalToolCalls(turnId, listOf(call("call-1"))))
                finalCallsEmitted.complete(Unit)
                allowCompletion.await()
                emit(ModelEvent.Completed(turnId))
            },
        )
        fixture.runtime.enqueueResponse(flowOf(ModelEvent.Completed(turnId)))
        val collection = launch {
            fixture.controller.runTurn(turnId, "do it").collect()
        }

        finalCallsEmitted.await()
        assertTrue(fixture.confirmations.isEmpty())
        assertTrue(fixture.runtime.responseInvocations.isEmpty())

        allowCompletion.complete(Unit)
        collection.join()
        assertEquals(1, fixture.confirmations.size)
        assertEquals(1, fixture.runtime.responseInvocations.size)
    }

    @Test
    fun `tool call limit stops a later call before confirmation and reinjection`() = runBlocking {
        val turnId = TurnId("turn-tool-limit")
        val fixture = fixture(
            limits = AgentLoopLimits(maxSteps = 3, maxToolCalls = 1),
        )
        fixture.runtime.enqueueUser(toolStep(turnId, call("call-1")))
        fixture.runtime.enqueueResponse(toolStep(turnId, call("call-2")))

        val events = fixture.controller.runTurn(turnId, "do it").toList()

        assertFailure(events, AgentFailureCode.TOOL_CALL_LIMIT_EXCEEDED)
        assertEquals(1, fixture.confirmations.size)
        assertEquals(1, fixture.runtime.responseInvocations.size)
    }

    @Test
    fun `step limit prevents an orphan tool execution that could not be answered`() = runBlocking {
        val turnId = TurnId("turn-step-limit")
        val fixture = fixture(
            limits = AgentLoopLimits(maxSteps = 1, maxToolCalls = 1),
        )
        fixture.runtime.enqueueUser(toolStep(turnId, call("call-1")))

        val events = fixture.controller.runTurn(turnId, "do it").toList()

        assertFailure(events, AgentFailureCode.STEP_LIMIT_EXCEEDED)
        assertTrue(fixture.confirmations.isEmpty())
        assertTrue(fixture.runtime.responseInvocations.isEmpty())
    }

    @Test
    fun `duplicate call id across steps cannot be executed twice`() = runBlocking {
        val turnId = TurnId("turn-duplicate-call")
        val fixture = fixture(
            limits = AgentLoopLimits(maxSteps = 3, maxToolCalls = 2),
        )
        fixture.runtime.enqueueUser(toolStep(turnId, call("same-call")))
        fixture.runtime.enqueueResponse(toolStep(turnId, call("same-call")))

        val events = fixture.controller.runTurn(turnId, "do it").toList()

        assertFailure(events, AgentFailureCode.INVALID_TOOL_CALL)
        assertEquals(1, fixture.confirmations.size)
        assertEquals(1, fixture.runtime.responseInvocations.size)
    }

    @Test
    fun `shared in process ledger blocks same turn replay without a second reinjection`() =
        runBlocking {
            val turnId = TurnId("turn-replay")
            val fixture = fixture()
            fixture.runtime.enqueueUser(toolStep(turnId, call("call-first")))
            fixture.runtime.enqueueResponse(flowOf(ModelEvent.Completed(turnId)))
            fixture.runtime.enqueueUser(toolStep(turnId, call("call-replay")))

            val first = fixture.controller.runTurn(turnId, "do it").toList()
            val replay = fixture.controller.runTurn(turnId, "do it").toList()

            assertEquals(AgentEvent.Completed(turnId), first.last())
            assertFailure(replay, AgentFailureCode.TOOL_NOT_EXECUTED)
            assertEquals(2, fixture.confirmations.size)
            assertEquals(1, fixture.runtime.responseInvocations.size)
        }

    @Test
    fun `only one turn may own the runtime at a time`() = runBlocking {
        val firstTurn = TurnId("turn-first")
        val secondTurn = TurnId("turn-second")
        val streamStarted = CompletableDeferred<Unit>()
        val releaseStream = CompletableDeferred<Unit>()
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flow {
                streamStarted.complete(Unit)
                releaseStream.await()
                emit(ModelEvent.TextDelta(firstTurn, "첫 번째 요청에 답했습니다."))
                emit(ModelEvent.Completed(firstTurn))
            },
        )
        val firstCollection = launch {
            fixture.controller.runTurn(firstTurn, "first").collect()
        }

        streamStarted.await()
        val secondEvents = fixture.controller.runTurn(secondTurn, "second").toList()
        releaseStream.complete(Unit)
        firstCollection.join()

        assertFailure(secondEvents, AgentFailureCode.BUSY)
        assertEquals(1, fixture.runtime.userInvocations.size)
        assertEquals(0, fixture.runtime.cancelCount.get())
    }

    @Test
    fun `deadline is checked while consuming model events`() = runBlocking {
        val clock = AtomicLong(0L)
        val turnId = TurnId("turn-deadline")
        val fixture = fixture(monotonicClock = clock::get)
        fixture.runtime.enqueueUser(
            flow {
                clock.set(AgentLoopLimits().deadlineMillis + 1)
                emit(ModelEvent.Completed(turnId))
            },
        )

        val events = fixture.controller.runTurn(turnId, "hello").toList()

        assertFailure(events, AgentFailureCode.DEADLINE_EXCEEDED)
        assertEquals(1, fixture.runtime.cancelCount.get())
    }

    @Test
    fun `deadline crossing after execution emits the receipt before failing the turn`() =
        runBlocking {
            val clock = AtomicLong(0L)
            val turnId = TurnId("turn-post-execute-deadline")
            val ledger = object : ActionLedger {
                override suspend fun claim(idempotencyKey: String): Boolean = true

                override suspend fun recordState(
                    idempotencyKey: String,
                    state: com.personaledge.core.tools.ActionExecutionState,
                ): Boolean {
                    clock.set(AgentLoopLimits().deadlineMillis + 1)
                    return true
                }
            }
            val fixture = fixture(ledger = ledger, monotonicClock = clock::get)
            fixture.runtime.enqueueUser(toolStep(turnId, call("call-1")))

            val events = fixture.controller.runTurn(turnId, "do it").toList()

            val receipt = events.filterIsInstance<AgentEvent.ToolExecuted>().single()
            assertEquals(ToolExecutionOutcome.READ_COMPLETED, receipt.outcome)
            assertFailure(events, AgentFailureCode.DEADLINE_EXCEEDED)
            assertEquals(listOf(receipt), fixture.controller.retainedToolExecutions(turnId))
            assertTrue(fixture.runtime.responseInvocations.isEmpty())
        }

    @Test
    fun `read-only execution remains cancellable while recording its outcome`() =
        runBlocking {
            val terminalRecordingStarted = CompletableDeferred<Unit>()
            val releaseTerminalRecording = CompletableDeferred<Unit>()
            val claims = AtomicInteger(0)
            val ledger = object : ActionLedger {
                override suspend fun claim(idempotencyKey: String): Boolean {
                    claims.incrementAndGet()
                    return true
                }

                override suspend fun recordState(
                    idempotencyKey: String,
                    state: com.personaledge.core.tools.ActionExecutionState,
                ): Boolean {
                    terminalRecordingStarted.complete(Unit)
                    releaseTerminalRecording.await()
                    return true
                }
            }
            val turnId = TurnId("turn-cancel-after-execute")
            val fixture = fixture(ledger = ledger)
            fixture.runtime.enqueueUser(toolStep(turnId, call("call-1")))
            val observed = mutableListOf<AgentEvent>()
            val collection = launch {
                fixture.controller.runTurn(turnId, "do it").collect(observed::add)
            }

            terminalRecordingStarted.await()
            assertTrue(fixture.controller.cancel(turnId))
            releaseTerminalRecording.complete(Unit)
            collection.join()

            assertTrue(collection.isCancelled)
            assertEquals(1, claims.get())
            assertTrue(fixture.runtime.responseInvocations.isEmpty())
            assertTrue(fixture.controller.retainedToolExecutions(turnId).isEmpty())
            assertTrue(observed.none { it is AgentEvent.Completed })
        }

    @Test
    fun `model failure is typed and cannot trigger tool work`() = runBlocking {
        val turnId = TurnId("turn-model-failure")
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flowOf(ModelEvent.Failure(turnId, LlmFailureCode.CONTEXT_BUDGET_EXCEEDED)),
        )

        val events = fixture.controller.runTurn(turnId, "hello").toList()

        assertFailure(
            events,
            AgentFailureCode.MODEL_FAILURE,
            LlmFailureCode.CONTEXT_BUDGET_EXCEEDED,
        )
        assertTrue(fixture.confirmations.isEmpty())
        assertTrue(fixture.runtime.responseInvocations.isEmpty())
    }

    @Test
    fun `tool response budget failure never reexecutes or changes the completed ledger`() =
        runBlocking {
            val turnId = TurnId("turn-response-budget-failure")
            val claims = AtomicInteger(0)
            val recordedStates = mutableListOf<ActionExecutionState>()
            val ledger = object : ActionLedger {
                override suspend fun claim(idempotencyKey: String): Boolean {
                    claims.incrementAndGet()
                    return true
                }

                override suspend fun recordState(
                    idempotencyKey: String,
                    state: ActionExecutionState,
                ): Boolean {
                    recordedStates += state
                    return true
                }
            }
            val fixture = fixture(ledger = ledger)
            fixture.runtime.enqueueUser(toolStep(turnId, call("call-1")))
            fixture.runtime.enqueueResponse(
                flowOf(ModelEvent.Failure(turnId, LlmFailureCode.CONTEXT_BUDGET_EXCEEDED)),
            )

            val events = fixture.controller.runTurn(turnId, "do it").toList()

            assertFailure(
                events,
                AgentFailureCode.MODEL_FAILURE,
                LlmFailureCode.CONTEXT_BUDGET_EXCEEDED,
            )
            assertEquals(1, claims.get())
            assertEquals(listOf(ActionExecutionState.COMPLETED), recordedStates)
            assertEquals(1, events.filterIsInstance<AgentEvent.ToolExecuted>().size)
            assertEquals(1, fixture.confirmations.size)
            assertEquals(1, fixture.runtime.responseInvocations.size)
            assertEquals(1, fixture.runtime.cancelCount.get())
        }

    @Test
    fun `runtime stream exception is converted without exposing exception text`() = runBlocking {
        val turnId = TurnId("turn-stream-exception")
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flow { throw IllegalStateException("sensitive native detail") },
        )

        val events = fixture.controller.runTurn(turnId, "hello").toList()

        assertFailure(events, AgentFailureCode.MODEL_FAILURE, LlmFailureCode.NATIVE_FAILURE)
        assertEquals(1, fixture.runtime.cancelCount.get())
    }

    @Test
    fun `typed read-only failure carries only trusted app-authored detail`() = runBlocking {
        val turnId = TurnId("turn-typed-route-failure")
        val sensitiveMessage = "provider echoed private origin and credential material"
        val failureCode = ToolFailureCode.NETWORK_FAILURE
        val registry = deviceRegistry(
            routeGateway = object : RouteGateway {
                override suspend fun credentialsPresent(): Boolean = true

                override suspend fun estimate(origin: String, destination: String): RouteEstimate {
                    throw ToolExecutionException(failureCode, sensitiveMessage)
                }
            },
        )
        val fixture = fixture(
            registry = registry,
            executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
        )
        fixture.runtime.enqueueUser(
            toolStep(
                turnId,
                call(
                    id = "call-route-failure",
                    name = RouteEstimateTool.NAME,
                    arguments = """{"origin":"시청","destination":"강남역"}""",
                ),
            ),
        )

        val events = fixture.controller.runTurn(turnId, "길을 찾아 줘").toList()

        val failure = events.last() as AgentEvent.Failure
        assertEquals(AgentFailureCode.TOOL_FAILED, failure.code)
        assertEquals(
            ToolFailureDetail(RouteEstimateTool.NAME, failureCode),
            failure.toolFailure,
        )
        assertFalse(events.toString().contains(sensitiveMessage))
        assertTrue(fixture.confirmations.isEmpty())
        assertTrue(fixture.runtime.responseInvocations.isEmpty())
        assertTrue(events.none { it is AgentEvent.ToolExecuted })
    }

    @Test
    fun `a typed exception outside the resolved Tool boundary stays generic`() = runBlocking {
        val turnId = TurnId("turn-typed-policy-failure")
        val interlockCalls = AtomicInteger(0)
        val registry = deviceRegistry(
            routeGateway = object : RouteGateway {
                override suspend fun credentialsPresent(): Boolean = true

                override suspend fun estimate(origin: String, destination: String): RouteEstimate =
                    error("The interlock must stop before provider execution.")
            },
        )
        val fixture = fixture(
            registry = registry,
            executionInterlock = ExecutionInterlock {
                if (interlockCalls.incrementAndGet() == 1) {
                    InterlockDecision.Allow
                } else {
                    throw ToolExecutionException(
                        ToolFailureCode.PERMISSION_DENIED,
                        "policy-internal detail",
                    )
                }
            },
        )
        fixture.runtime.enqueueUser(
            toolStep(
                turnId,
                call(
                    id = "call-route-policy-failure",
                    name = RouteEstimateTool.NAME,
                    arguments = """{"origin":"시청","destination":"강남역"}""",
                ),
            ),
        )

        val events = fixture.controller.runTurn(turnId, "길을 찾아 줘").toList()

        val failure = events.last() as AgentEvent.Failure
        assertEquals(AgentFailureCode.TOOL_NOT_EXECUTED, failure.code)
        assertNull(failure.toolFailure)
        assertFalse(events.toString().contains("policy-internal detail"))
        assertTrue(fixture.runtime.responseInvocations.isEmpty())
        assertTrue(events.none { it is AgentEvent.ToolExecuted })
    }

    @Test
    fun `missing read-only credentials use the typed settings guidance before confirmation`() =
        runBlocking {
            val turnId = TurnId("turn-route-credentials-missing")
            val registry = deviceRegistry(
                routeGateway = object : RouteGateway {
                    override suspend fun credentialsPresent(): Boolean = false

                    override suspend fun estimate(
                        origin: String,
                        destination: String,
                    ): RouteEstimate = error("A missing credential must stop before execution.")
                },
            )
            val fixture = fixture(
                registry = registry,
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
            )
            fixture.runtime.enqueueUser(
                toolStep(
                    turnId,
                    call(
                        id = "call-route-credentials-missing",
                        name = RouteEstimateTool.NAME,
                        arguments = """{"origin":"시청","destination":"강남역"}""",
                    ),
                ),
            )

            val events = fixture.controller.runTurn(turnId, "길을 찾아 줘").toList()

            val failure = events.last() as AgentEvent.Failure
            assertEquals(AgentFailureCode.TOOL_FAILED, failure.code)
            assertEquals(
                ToolFailureDetail(
                    RouteEstimateTool.NAME,
                    ToolFailureCode.CREDENTIALS_MISSING,
                ),
                failure.toolFailure,
            )
            assertTrue(fixture.confirmations.isEmpty())
            assertTrue(fixture.runtime.responseInvocations.isEmpty())
            assertTrue(events.none { it is AgentEvent.ToolExecuted })
        }

    @Test
    fun `typed failure detail is refused for every side-effecting risk`() {
        ToolRisk.entries.filter { risk -> risk != ToolRisk.READ_ONLY }.forEach { risk ->
            assertNull(
                risk.name,
                readOnlyToolFailureDetailOrNull(
                    toolName = "trusted_tool",
                    risk = risk,
                    failureCode = ToolFailureCode.PROVIDER_UNAVAILABLE,
                ),
            )
        }
    }

    @Test
    fun `typed preparation failure is retained before any side effect can run`() {
        assertEquals(
            ToolFailureDetail(
                toolName = "reminder_create",
                failureCode = ToolFailureCode.REMINDER_INVALID_DATE_TIME_FORMAT,
            ),
            preparationToolFailureDetail(
                toolName = "reminder_create",
                failureCode = ToolFailureCode.REMINDER_INVALID_DATE_TIME_FORMAT,
            ),
        )
    }

    @Test
    fun `one reminder date validation failure gets static repair guidance before execution`() {
        val gate = ReminderValidationRepairGate()

        val payload = gate.payloadOrNull(
            toolName = ReminderCreateTool.NAME,
            failureCode = ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_TEXT,
        )

        assertEquals(
            "{\"ok\":false,\"error\":\"invalid_trigger_at\",\"retry\":\"once\"," +
                "\"instruction\":\"Retry the same reminder tool now. Copy the complete numeric " +
                "local timestamp from the current user request into trigger_at without adding or " +
                "removing characters. Copy the trusted IANA time-zone name into zone_id. Output " +
                "only the tool call.\"}",
            payload,
        )
        assertFalse(payload.orEmpty().contains("YYYY"))
    }

    @Test
    fun `a second reminder date validation failure is not repaired again`() {
        val gate = ReminderValidationRepairGate()

        assertTrue(
            gate.payloadOrNull(
                ReminderCreateTool.NAME,
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_FORMAT,
            ) != null,
        )
        assertNull(
            gate.payloadOrNull(
                ReminderCreateTool.NAME,
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_FORMAT,
            ),
        )
    }

    @Test
    fun `repair guidance is limited to reminder create or update date validation`() {
        assertNull(
            ReminderValidationRepairGate().payloadOrNull(
                ReminderCreateTool.NAME,
                ToolFailureCode.REMINDER_INVALID_TITLE,
            ),
        )
        assertNull(
            ReminderValidationRepairGate().payloadOrNull(
                "reminder_cancel",
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_FORMAT,
            ),
        )
    }

    @Test
    fun `reminder hint accepts one exact timestamp from only the current user prompt`() {
        assertEquals(
            "2026-09-01T09:00",
            ReminderDateTimeHint.fromCurrentUserPrompt(
                "보고서 확인은 2026-09-01T09:00에 알려 주세요.",
            )?.triggerAt,
        )
        assertNull(
            ReminderDateTimeHint.fromCurrentUserPrompt(
                "2026-09-01T09:00에서 2026-09-02T09:00로 바꿔 주세요.",
            ),
        )
        assertNull(ReminderDateTimeHint.fromCurrentUserPrompt("2026-09-01T09:00:30"))
        assertNull(ReminderDateTimeHint.fromCurrentUserPrompt("2026-09-01T09:00+09:00"))
    }

    @Test
    fun `typed reminder hint repairs only a date-time validation failure`() {
        val original = ReminderCreateParams(
            title = "보고서 확인",
            triggerAt = "tomorrow morning",
            zoneId = "Asia/Seoul",
            recurrenceRule = null,
            precision = "exact",
            leadTimeMinutes = null,
            escalationPolicy = null,
        )
        val hint = requireNotNull(
            ReminderDateTimeHint.fromCurrentUserPrompt("시각은 2026-09-01T09:00입니다."),
        )

        assertEquals(
            original.copy(triggerAt = "2026-09-01T09:00"),
            reminderDateTimeRepairedParamsOrNull(
                original,
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_TEXT,
                hint,
            ),
        )
        assertNull(
            reminderDateTimeRepairedParamsOrNull(
                original,
                ToolFailureCode.REMINDER_INVALID_TITLE,
                hint,
            ),
        )
    }

    @Test
    fun `an untyped read exception remains the generic unknown failure`() = runBlocking {
        val turnId = TurnId("turn-untyped-route-failure")
        val registry = deviceRegistry(
            routeGateway = object : RouteGateway {
                override suspend fun credentialsPresent(): Boolean = true

                override suspend fun estimate(origin: String, destination: String): RouteEstimate {
                    throw IllegalStateException("unexpected internal detail")
                }
            },
        )
        val fixture = fixture(
            registry = registry,
            executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
        )
        fixture.runtime.enqueueUser(
            toolStep(
                turnId,
                call(
                    id = "call-untyped-route-failure",
                    name = RouteEstimateTool.NAME,
                    arguments = """{"origin":"시청","destination":"강남역"}""",
                ),
            ),
        )

        val events = fixture.controller.runTurn(turnId, "길을 찾아 줘").toList()

        val failure = events.last() as AgentEvent.Failure
        assertEquals(AgentFailureCode.TOOL_NOT_EXECUTED, failure.code)
        assertNull(failure.toolFailure)
        assertTrue(fixture.runtime.responseInvocations.isEmpty())
        assertTrue(events.none { it is AgentEvent.ToolExecuted })
    }

    private fun fixture(
        confirmation: suspend (ActionChallenge) -> Boolean = { true },
        limits: AgentLoopLimits = AgentLoopLimits(),
        ledger: ActionLedger = InProcessActionLedger(),
        registry: ManualToolRegistry = ManualToolRegistry(),
        executionInterlock: ExecutionInterlock = CapabilityFreeInterlock(),
        actionClock: () -> Long = { 10_000L },
        monotonicClock: () -> Long = { 0L },
        sideEffectTurnGate: SideEffectTurnGate = SideEffectTurnGate { _, _, _ -> true },
    ): Fixture {
        val runtime = FakeLlmRuntime()
        val confirmations = mutableListOf<ActionChallenge>()
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate { challenge ->
                confirmations += challenge
                confirmation(challenge)
            },
            executionInterlock = executionInterlock,
            clock = actionClock,
            idFactory = { "action-${confirmations.size + 1}" },
        )
        return Fixture(
            runtime = runtime,
            confirmations = confirmations,
            controller = ManualToolAgentController(
                runtime = runtime,
                registry = registry,
                orchestrator = orchestrator,
                limits = limits,
                monotonicClockMillis = monotonicClock,
                sideEffectTurnGate = sideEffectTurnGate,
            ),
        )
    }

    private fun deviceRegistry(
        routeGateway: RouteGateway,
        webSearchGateway: WebSearchGateway = object : WebSearchGateway {
            override suspend fun credentialsPresent(): Boolean = false

            override suspend fun search(query: String, limit: Int): WebSearchResponse =
                WebSearchResponse(WebSearchProvider.YOU_COM, emptyList())
        },
        weatherGateway: WeatherGateway = object : WeatherGateway {
            override suspend fun currentAndToday(location: String): WeatherResult =
                error("Unused test gateway")
        },
        memoryGateway: MemoryGateway = MemoryGateway { MemoryWriteOutcome.SAVED },
    ): ManualToolRegistry {
        val calendar = EmptyCalendarGateway()
        val alarms = EmptyAlarmGateway()
        return ManualToolRegistry.forDeviceTools(
            queryTool = CalendarQueryTool(calendar),
            createEventTool = CalendarCreateEventTool(calendar, defaultCalendarId = { null }),
            updateEventTool = CalendarUpdateEventTool(calendar),
            alarmSetTool = AlarmSetTool(alarms),
            alarmNextTool = AlarmNextTool(alarms),
            notificationSearchTool = NotificationSearchTool(
                gateway = object : NotificationGateway {
                    override suspend fun search(
                        query: String?,
                        postedAtOrAfter: Long,
                        limit: Int,
                    ) = emptyList<com.personaledge.core.tools.CapturedMessageSummary>()
                },
            ),
            routeEstimateTool = RouteEstimateTool(routeGateway, defaultOrigin = { null }),
            webSearchTool = WebSearchTool(gateway = webSearchGateway),
            weatherTool = WeatherTool(gateway = weatherGateway),
            memoryRememberTool = MemoryRememberTool(
                gateway = memoryGateway,
            ),
        )
    }

    private fun unusedRouteGateway(): RouteGateway = object : RouteGateway {
        override suspend fun credentialsPresent(): Boolean = false

        override suspend fun estimate(origin: String, destination: String): RouteEstimate =
            error("Unused test gateway")
    }

    private fun weatherResult(): WeatherResult = WeatherResult(
        location = "경기도 화성시 동탄",
        currentAt = "2026-08-25T21:30",
        condition = "맑음",
        temperatureCelsius = "21.4",
        apparentTemperatureCelsius = "22.0",
        relativeHumidityPercent = 61,
        precipitationMillimetres = "0.0",
        windSpeedKilometresPerHour = "7.2",
        todayMinimumCelsius = "12.3",
        todayMaximumCelsius = "23.5",
        todayPrecipitationProbabilityPercent = 30,
        sourceName = "Open-Meteo",
        sourceUrl = "https://open-meteo.com/",
    )

    private fun filmSearchResponse(): WebSearchResponse = WebSearchResponse(
        provider = WebSearchProvider.YOU_COM,
        hits = listOf(
            WebSearchHit(
                title = "그대들은 어떻게 살 것인가 작품 정보",
                link = "https://example.com/how-do-you-live",
                snippet = "미야자키 하야오가 연출한 일본 애니메이션 영화입니다.",
            ),
        ),
    )

    private class EmptyCalendarGateway : CalendarGateway {
        override suspend fun writableCalendars(): List<CalendarAccount> = emptyList()

        override suspend fun findEvent(eventId: Long): CalendarEvent? = null

        override suspend fun insertEvent(draft: CalendarEventDraft): Long? = null

        override suspend fun queryEvents(
            startEpochMillis: Long,
            endEpochMillis: Long,
            limit: Int,
        ): List<CalendarEvent> = emptyList()

        override suspend fun updateEvent(eventId: Long, patch: CalendarEventPatch): Boolean = false
    }

    private class EmptyAlarmGateway : AlarmGateway {
        override suspend fun clockAppAvailable(): Boolean = false

        override suspend fun requestAlarm(request: AlarmRequest): AlarmOutcome =
            error("Unused test gateway")

        override suspend fun nextAlarm(): NextAlarm? = null
    }

    private fun toolStep(
        turnId: TurnId,
        call: LlmToolCall,
    ): Flow<ModelEvent> = flowOf(
        ModelEvent.FinalToolCalls(turnId, listOf(call)),
        ModelEvent.Completed(turnId),
    )

    private fun call(
        id: String,
        name: String = FakeArrivalNoticeTool.NAME,
        arguments: String = """{"recipient":"wife","message":"soon"}""",
    ) = LlmToolCall(
        id = id,
        name = name,
        argumentsJson = arguments,
    )

    private fun assertFailure(
        events: List<AgentEvent>,
        code: AgentFailureCode,
        runtimeCode: LlmFailureCode? = null,
    ) {
        assertEquals(AgentEvent.Failure(events.first().turnId, code, runtimeCode), events.last())
        assertTrue(events.none { it is AgentEvent.Completed })
    }

    private data class Fixture(
        val runtime: FakeLlmRuntime,
        val confirmations: MutableList<ActionChallenge>,
        val controller: ManualToolAgentController,
    )

    private class FakeLlmRuntime : LlmRuntime {
        override val state = MutableStateFlow<LlmState>(
            LlmState.Ready(InferenceBackend.CPU),
        )
        val userInvocations = mutableListOf<Pair<TurnId, String>>()
        val outputTokenInvocations = mutableListOf<Int>()
        val responseInvocations = mutableListOf<ResponseInvocation>()
        val cancelCount = AtomicInteger(0)
        private val userStreams = ArrayDeque<Flow<ModelEvent>>()
        private val responseStreams = ArrayDeque<Flow<ModelEvent>>()

        fun enqueueUser(events: Flow<ModelEvent>) {
            userStreams.addLast(events)
        }

        fun enqueueResponse(events: Flow<ModelEvent>) {
            responseStreams.addLast(events)
        }

        override suspend fun initialize(
            model: VerifiedInstalledModel,
            backend: InferenceBackend,
            tools: List<LlmToolDefinition>,
        ) = Unit

        override fun streamUserTurn(turnId: TurnId, prompt: String): Flow<ModelEvent> {
            userInvocations += turnId to prompt
            return userStreams.removeFirst()
        }

        override fun streamUserTurn(
            turnId: TurnId,
            prompt: String,
            maxOutputTokens: Int,
            toolScope: LlmTurnToolScope,
        ): Flow<ModelEvent> {
            outputTokenInvocations += maxOutputTokens
            return streamUserTurn(turnId, prompt)
        }

        override fun streamToolResponses(
            turnId: TurnId,
            responses: List<TrustedToolResponse>,
        ): Flow<ModelEvent> {
            responseInvocations += ResponseInvocation(turnId, responses.toList())
            return responseStreams.removeFirst()
        }

        override suspend fun cancel(turnId: TurnId) {
            cancelCount.incrementAndGet()
        }

        override fun close() = Unit
    }

    private data class ResponseInvocation(
        val turnId: TurnId,
        val responses: List<TrustedToolResponse>,
    )
}
