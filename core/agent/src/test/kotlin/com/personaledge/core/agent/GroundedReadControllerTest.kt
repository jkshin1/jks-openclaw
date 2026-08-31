package com.personaledge.core.agent

import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.llm.LlmRuntime
import com.personaledge.core.llm.LlmState
import com.personaledge.core.llm.LlmToolCall
import com.personaledge.core.llm.LlmToolDefinition
import com.personaledge.core.llm.ModelEvent
import com.personaledge.core.llm.TrustedToolResponse
import com.personaledge.core.llm.TurnId
import com.personaledge.core.llm.TurnMediaKind
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
import com.personaledge.core.tools.ExecutionInterlock
import com.personaledge.core.tools.InterlockDecision
import com.personaledge.core.tools.NextAlarm
import com.personaledge.core.tools.NotificationGateway
import com.personaledge.core.tools.NotificationSearchTool
import com.personaledge.core.tools.ReminderGateway
import com.personaledge.core.tools.ReminderMutationResult
import com.personaledge.core.tools.ReminderQueryTool
import com.personaledge.core.tools.ReminderSummary
import com.personaledge.core.tools.ReminderWriteRequest
import com.personaledge.core.tools.RouteEstimate
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.RouteGateway
import com.personaledge.core.tools.ToolExecutionOutcome
import com.personaledge.core.tools.ToolOrchestrator
import com.personaledge.core.tools.UserConfirmationGate
import com.personaledge.core.tools.WebSearchGateway
import com.personaledge.core.tools.WebSearchProvider
import com.personaledge.core.tools.WebSearchResponse
import com.personaledge.core.tools.WebSearchTool
import com.personaledge.core.tools.WeatherGateway
import com.personaledge.core.tools.WeatherResult
import com.personaledge.core.tools.WeatherTool
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedReadControllerTest {
    @Test
    fun `recovery contract rejects a different read tool before execution`() = runBlocking {
        val turnId = TurnId("recovery-read-substitution")
        val ledger = RecordingLedger()
        val fixture = fixture(ledger = ledger)
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.FinalToolCalls(
                    turnId,
                    listOf(call("wrong-read", AlarmNextTool.NAME, "{}")),
                ),
                ModelEvent.Completed(turnId),
            ),
        )

        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = "일정을 다시 확인해 줘",
            readOnlyToolsOnly = true,
            requireReadTool = true,
            executionContract = TurnExecutionContract.exactReads(
                listOf(CalendarQueryTool.NAME),
            ),
        ).toList()

        assertEquals(
            AgentFailureCode.TOOL_NOT_EXECUTED,
            events.filterIsInstance<AgentEvent.Failure>().single().code,
        )
        assertTrue(events.none { event -> event is AgentEvent.ToolExecuted })
        assertTrue(ledger.claimedKeys.isEmpty())
        assertTrue(fixture.runtime.responseInvocations.isEmpty())
    }

    @Test
    fun `satisfied recovery read completes from Kotlin without a second decode`() = runBlocking {
        val turnId = TurnId("recovery-read-kotlin-terminal")
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.FinalToolCalls(
                    turnId,
                    listOf(
                        call(
                            "calendar-recovery",
                            CalendarQueryTool.NAME,
                            """{"start":"2026-08-25T00:00","end":"2026-08-26T00:00"}""",
                        ),
                    ),
                ),
                ModelEvent.Completed(turnId),
            ),
        )

        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = "일정을 다시 확인해 줘",
            executionContract = TurnExecutionContract.exactReads(
                listOf(CalendarQueryTool.NAME),
            ),
        ).toList()

        assertTrue(events.filterIsInstance<AgentEvent.TrustedAnswer>().single().text
            .contains("조회 범위에 일정이 없습니다"))
        assertTrue(fixture.runtime.responseInvocations.isEmpty())
        assertEquals(1, fixture.runtime.cancelCount.get())
        assertEquals(AgentEvent.Completed(turnId), events.last())
    }

    @Test
    fun `independent read batch uses verified plan and returns one Kotlin grounded answer`() =
        runBlocking {
            val turnId = TurnId("grounded-read-plan")
            val calendar = StubCalendarGateway(
                events = listOf(
                    CalendarEvent(
                        eventId = 73L,
                        calendarId = 7L,
                        calendarLabel = "개인",
                        title = "계획 검토",
                        startEpochMillis = Instant.parse("2026-08-25T01:00:00Z").toEpochMilli(),
                        endEpochMillis = Instant.parse("2026-08-25T02:00:00Z").toEpochMilli(),
                        allDay = false,
                        location = null,
                    ),
                ),
            )
            val fixture = fixture(calendar = calendar, enablePlanBridge = true)
            fixture.runtime.enqueueUser(
                flowOf(
                    ModelEvent.TextDelta(turnId, "실행 전 추측은 폐기되어야 합니다."),
                    ModelEvent.FinalToolCalls(
                        turnId,
                        listOf(
                            call(
                                "calendar-plan-1",
                                CalendarQueryTool.NAME,
                                """{"start":"2026-08-25T00:00","end":"2026-08-26T00:00"}""",
                            ),
                            call("alarm-plan-2", AlarmNextTool.NAME, "{}"),
                        ),
                    ),
                    ModelEvent.Completed(turnId),
                ),
            )

            val events = fixture.controller.runTurn(
                turnId,
                "오늘 일정과 다음 알람을 함께 확인해 줘",
            ).toList()

            assertEquals(listOf(1, 2), events.filterIsInstance<AgentEvent.ToolExecuted>()
                .map(AgentEvent.ToolExecuted::ordinal))
            val answer = events.filterIsInstance<AgentEvent.TrustedAnswer>().single().text
            assertTrue(answer.indexOf("조회 1 · 일정") < answer.indexOf("조회 2 · 다음 알람"))
            assertTrue(answer.contains("계획 검토"))
            assertTrue(answer.contains("다음 알람이 없습니다"))
            assertFalse(answer.contains("실행 전 추측"))
            assertTrue(events.none { event -> event is AgentEvent.TextDelta })
            assertTrue(fixture.runtime.responseInvocations.isEmpty())
            assertEquals(1, fixture.runtime.cancelCount.get())
            assertTrue(fixture.checkpoints.isNotEmpty())
            val persisted = fixture.checkpoints.joinToString()
            assertFalse(persisted.contains("계획 검토"))
            assertFalse(persisted.contains("2026-08-25T00:00"))
            assertEquals(AgentEvent.Completed(turnId), events.last())
        }

    @Test
    fun `read plan containing a write refuses the whole batch before dispatch`() = runBlocking {
        val turnId = TurnId("grounded-plan-write-refused")
        val ledger = RecordingLedger()
        val alarm = StubAlarmGateway(next = null)
        val fixture = fixture(alarm = alarm, ledger = ledger, enablePlanBridge = true)
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.FinalToolCalls(
                    turnId,
                    listOf(
                        call("alarm-read", AlarmNextTool.NAME, "{}"),
                        call("alarm-write", AlarmSetTool.NAME, """{"time":"07:30"}"""),
                    ),
                ),
                ModelEvent.Completed(turnId),
            ),
        )

        val events = fixture.controller.runTurn(turnId, "알람을 확인하고 새로 설정해 줘").toList()

        assertEquals(
            AgentFailureCode.TOOL_NOT_EXECUTED,
            events.filterIsInstance<AgentEvent.Failure>().single().code,
        )
        assertEquals(0, alarm.requestCount.get())
        assertTrue(events.none { event -> event is AgentEvent.ToolExecuted })
        assertTrue(ledger.claimedKeys.isEmpty())
        assertTrue(fixture.confirmations.isEmpty())
        assertTrue(fixture.checkpoints.isEmpty())
    }

    @Test
    fun `malformed call in read plan refuses every call before dispatch`() = runBlocking {
        val turnId = TurnId("grounded-plan-invalid-args")
        val ledger = RecordingLedger()
        val fixture = fixture(ledger = ledger, enablePlanBridge = true)
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.FinalToolCalls(
                    turnId,
                    listOf(
                        call("alarm-read", AlarmNextTool.NAME, "{}"),
                        call("calendar-invalid", CalendarQueryTool.NAME, """{"start":7}"""),
                    ),
                ),
                ModelEvent.Completed(turnId),
            ),
        )

        val events = fixture.controller.runTurn(turnId, "알람과 일정을 확인해 줘").toList()

        assertEquals(
            AgentFailureCode.TOOL_NOT_EXECUTED,
            events.filterIsInstance<AgentEvent.Failure>().single().code,
        )
        assertTrue(events.none { event -> event is AgentEvent.ToolExecuted })
        assertTrue(ledger.claimedKeys.isEmpty())
        assertTrue(fixture.checkpoints.isEmpty())
    }

    @Test
    fun `two reads combine by ordinal and suppress every contradictory model step`() = runBlocking {
        val turnId = TurnId("grounded-two-reads")
        val calendar = StubCalendarGateway(
            events = listOf(
                CalendarEvent(
                    eventId = 41L,
                    calendarId = 7L,
                    calendarLabel = "개인",
                    title = "팀 회의",
                    startEpochMillis = Instant.parse("2026-08-25T01:00:00Z").toEpochMilli(),
                    endEpochMillis = Instant.parse("2026-08-25T02:00:00Z").toEpochMilli(),
                    allDay = false,
                    location = "회의실 A",
                ),
            ),
        )
        val alarm = StubAlarmGateway(next = null)
        val fixture = fixture(calendar = calendar, alarm = alarm)
        fixture.runtime.enqueueUser(
            toolStepWithGuess(
                turnId = turnId,
                guess = "일정은 전혀 없습니다.",
                call = call(
                    id = "calendar-1",
                    name = CalendarQueryTool.NAME,
                    arguments = """{"start":"2026-08-25T00:00","end":"2026-08-26T00:00"}""",
                ),
            ),
        )
        fixture.runtime.enqueueResponse(
            toolStepWithGuess(
                turnId = turnId,
                guess = "다음 알람은 오전 7시입니다.",
                call = call("alarm-2", AlarmNextTool.NAME, "{}"),
            ),
        )
        fixture.runtime.enqueueResponse(
            flowOf(
                ModelEvent.TextDelta(turnId, "회의는 없고 알람은 7시에 있습니다."),
                ModelEvent.Completed(turnId),
            ),
        )

        val events = fixture.controller.runTurn(turnId, "일정과 다음 알람을 확인해 줘").toList()

        assertTrue(events.none { event -> event is AgentEvent.TextDelta })
        val answer = events.filterIsInstance<AgentEvent.TrustedAnswer>().single().text
        assertTrue(answer.indexOf("조회 1 · 일정") < answer.indexOf("조회 2 · 다음 알람"))
        assertTrue(answer.contains("팀 회의"))
        assertTrue(answer.contains("이벤트 ID: 41"))
        assertTrue(answer.contains("다음 알람이 없습니다"))
        assertFalse(answer.contains("일정은 전혀 없습니다"))
        assertFalse(answer.contains("오전 7시"))
        assertEquals(listOf(1, 2), events.filterIsInstance<AgentEvent.ToolExecuted>().map { it.ordinal })
        assertEquals(2, fixture.runtime.responseInvocations.size)
        assertTrue(fixture.confirmations.isEmpty())
        assertEquals(AgentEvent.Completed(turnId), events.last())
    }

    @Test
    fun `route evidence blocks opposite hallucination and raw result stays out of ledger receipts`() =
        runBlocking {
            val turnId = TurnId("grounded-route")
            val ledger = RecordingLedger()
            val route = object : RouteGateway {
                override suspend fun credentialsPresent(): Boolean = true

                override suspend fun estimate(origin: String, destination: String): RouteEstimate =
                    RouteEstimate(
                        originLabel = "서울시청 비밀 출발지",
                        destinationLabel = "강남역 비밀 도착지",
                        durationMinutes = 31,
                        distanceMeters = 12_400,
                    )
            }
            val fixture = fixture(route = route, ledger = ledger)
            fixture.runtime.enqueueUser(
                toolStepWithGuess(
                    turnId,
                    "아마 2시간쯤 걸립니다.",
                    call(
                        "route-1",
                        RouteEstimateTool.NAME,
                        """{"origin":"시청","destination":"강남역"}""",
                    ),
                ),
            )
            fixture.runtime.enqueueResponse(
                flowOf(
                    ModelEvent.TextDelta(turnId, "거리는 99km이고 120분입니다."),
                    ModelEvent.Completed(turnId),
                ),
            )

            val events = fixture.controller.runTurn(turnId, "강남역까지 얼마나 걸려?").toList()

            val answer = events.filterIsInstance<AgentEvent.TrustedAnswer>().single().text
            assertTrue(answer.contains("31분"))
            assertTrue(answer.contains("12.4km"))
            assertFalse(answer.contains("99km"))
            assertFalse(answer.contains("120분"))
            assertTrue(events.none { event -> event is AgentEvent.TextDelta })
            val receiptText = fixture.controller.retainedToolExecutions(turnId).toString()
            val ledgerText = (ledger.claimedKeys + ledger.recordedStates.map { it.toString() })
                .joinToString()
            listOf("서울시청 비밀 출발지", "강남역 비밀 도착지", "12.4").forEach { raw ->
                assertFalse(receiptText.contains(raw))
                assertFalse(ledgerText.contains(raw))
            }
            assertEquals(
                ToolExecutionOutcome.READ_COMPLETED,
                events.filterIsInstance<AgentEvent.ToolExecuted>().single().outcome,
            )
        }

    @Test
    fun `empty reminder evidence overrides a fabricated nonempty answer`() = runBlocking {
        val turnId = TurnId("grounded-reminder-empty")
        val fixture = fixture(reminder = StubReminderGateway(emptyList()))
        fixture.runtime.enqueueUser(
            toolStepWithGuess(
                turnId,
                "리마인더가 여러 개 있을 것입니다.",
                call("reminder-1", ReminderQueryTool.NAME, """{"limit":"20"}"""),
            ),
        )
        fixture.runtime.enqueueResponse(
            flowOf(
                ModelEvent.TextDelta(turnId, "예정된 리마인더가 3개 있습니다."),
                ModelEvent.Completed(turnId),
            ),
        )

        val events = fixture.controller.runTurn(turnId, "리마인더를 보여 줘").toList()

        assertTrue(events.none { event -> event is AgentEvent.TextDelta })
        val answer = events.filterIsInstance<AgentEvent.TrustedAnswer>().single().text
        assertEquals("예정된 앱 리마인더가 없습니다.", answer)
        assertFalse(answer.contains("3개"))
        assertTrue(fixture.confirmations.isEmpty())
    }

    @Test
    fun `write path still refuses a nonpersistent ledger before confirmation and execution`() =
        runBlocking {
            val turnId = TurnId("grounded-write-unchanged")
            val alarm = StubAlarmGateway(next = null)
            val ledger = RecordingLedger()
            val fixture = fixture(alarm = alarm, ledger = ledger)
            fixture.runtime.enqueueUser(
                toolStepWithGuess(
                    turnId,
                    "확인 전에 이미 알람을 만들었습니다.",
                    call("alarm-write-1", AlarmSetTool.NAME, """{"time":"07:30"}"""),
                ),
            )
            val events = fixture.controller.runTurn(turnId, "07:30 알람을 설정해 줘").toList()

            assertEquals(0, alarm.requestCount.get())
            assertTrue(fixture.confirmations.isEmpty())
            assertTrue(fixture.runtime.responseInvocations.isEmpty())
            assertEquals(
                AgentFailureCode.TOOL_NOT_EXECUTED,
                events.filterIsInstance<AgentEvent.Failure>().single().code,
            )
            assertTrue(events.none { event -> event is AgentEvent.TextDelta })
            assertTrue(events.none { event ->
                event is AgentEvent.TextDelta && event.text.contains("확인 전에")
            })
            assertTrue(events.none { event -> event is AgentEvent.TrustedAnswer })
            assertTrue(events.none { event -> event is AgentEvent.ToolExecuted })
            assertTrue(ledger.claimedKeys.isEmpty())
            assertTrue(ledger.recordedStates.isEmpty())
        }

    @Test
    fun `bounded step buffer aborts before a tool call can execute or leak prose`() = runBlocking {
        val turnId = TurnId("grounded-buffer-bound")
        val alarm = StubAlarmGateway(next = null)
        val fixture = fixture(alarm = alarm)
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.TextDelta(turnId, "private-model-guess-" + "x".repeat(64)),
                ModelEvent.FinalToolCalls(
                    turnId,
                    listOf(call("alarm-never", AlarmSetTool.NAME, """{"time":"07:30"}""")),
                ),
                ModelEvent.Completed(turnId),
            ),
        )

        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = "set alarm",
            turnLimits = AgentLoopLimits(maxOutputTokens = 1),
        ).toList()

        assertEquals(
            AgentFailureCode.INVALID_MODEL_SEQUENCE,
            events.filterIsInstance<AgentEvent.Failure>().single().code,
        )
        assertEquals(0, alarm.requestCount.get())
        assertTrue(fixture.confirmations.isEmpty())
        assertTrue(fixture.runtime.responseInvocations.isEmpty())
        assertFalse(events.toString().contains("private-model-guess"))
    }

    @Test
    fun `a pure answer step releases its bounded deltas only after completion`() = runBlocking {
        val turnId = TurnId("grounded-pure-answer")
        val runtime = FakeRuntime()
        val deltasEmitted = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        runtime.enqueueUser(
            flow {
                emit(ModelEvent.TextDelta(turnId, "첫 번째 "))
                emit(ModelEvent.TextDelta(turnId, "두 번째"))
                deltasEmitted.complete(Unit)
                allowCompletion.await()
                emit(ModelEvent.Completed(turnId))
            },
        )
        val controller = ManualToolAgentController(
            runtime = runtime,
            registry = ManualToolRegistry(),
            orchestrator = ToolOrchestrator(actionLedger = RecordingLedger()),
        )

        val events = mutableListOf<AgentEvent>()
        val collection = launch {
            controller.runTurn(turnId, "일반 질문").collect(events::add)
        }
        deltasEmitted.await()
        assertTrue(events.isEmpty())
        allowCompletion.complete(Unit)
        collection.join()

        assertEquals(
            listOf("첫 번째 두 번째"),
            events.filterIsInstance<AgentEvent.TextDelta>().map(AgentEvent.TextDelta::text),
        )
        assertTrue(events.none { event -> event is AgentEvent.TrustedAnswer })
        assertEquals(AgentEvent.Completed(turnId), events.last())
    }

    @Test
    fun `volatile public fact rejects local prose when the grounded tool is unavailable`() = runBlocking {
        val turnId = TurnId("grounded-freshness-required")
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.TextDelta(turnId, "근거 없는 현재가"),
                ModelEvent.Completed(turnId),
            ),
        )

        val events = fixture.controller.runTurn(
            turnId,
            "삼성전자 현재 주가를 알려줘",
        ).toList()

        assertEquals(
            AgentFailureCode.TOOL_NOT_EXECUTED,
            events.filterIsInstance<AgentEvent.Failure>().single().code,
        )
        assertTrue(events.none { event -> event is AgentEvent.TextDelta })
    }

    private fun fixture(
        calendar: CalendarGateway = StubCalendarGateway(emptyList()),
        alarm: StubAlarmGateway = StubAlarmGateway(null),
        route: RouteGateway = object : RouteGateway {
            override suspend fun credentialsPresent(): Boolean = true
            override suspend fun estimate(origin: String, destination: String): RouteEstimate =
                RouteEstimate(origin, destination, 10, 1_000)
        },
        reminder: ReminderGateway = StubReminderGateway(emptyList()),
        ledger: ActionLedger = RecordingLedger(),
        enablePlanBridge: Boolean = false,
    ): Fixture {
        val runtime = FakeRuntime()
        val confirmations = mutableListOf<ActionChallenge>()
        val idCounter = AtomicInteger()
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate { challenge ->
                confirmations += challenge
                true
            },
            executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
            clock = { FIXED_NOW },
            idFactory = { "grounded-action-${idCounter.incrementAndGet()}" },
        )
        val registry = ManualToolRegistry.forDeviceTools(
            queryTool = CalendarQueryTool(
                gateway = calendar,
                zoneProvider = { SEOUL },
                clock = { FIXED_NOW },
            ),
            createEventTool = CalendarCreateEventTool(
                gateway = calendar,
                defaultCalendarId = { 7L },
                zoneProvider = { SEOUL },
                clock = { FIXED_NOW },
            ),
            updateEventTool = CalendarUpdateEventTool(
                gateway = calendar,
                zoneProvider = { SEOUL },
                clock = { FIXED_NOW },
            ),
            alarmSetTool = AlarmSetTool(alarm, zoneProvider = { SEOUL }),
            alarmNextTool = AlarmNextTool(alarm, zoneProvider = { SEOUL }),
            notificationSearchTool = NotificationSearchTool(
                gateway = object : NotificationGateway {
                    override suspend fun search(
                        query: String?,
                        postedAtOrAfter: Long,
                        limit: Int,
                    ) = emptyList<com.personaledge.core.tools.CapturedMessageSummary>()
                },
            ),
            routeEstimateTool = RouteEstimateTool(route, defaultOrigin = { null }),
            webSearchTool = WebSearchTool(
                object : WebSearchGateway {
                    override suspend fun credentialsPresent(): Boolean = true
                    override suspend fun search(query: String, limit: Int): WebSearchResponse =
                        WebSearchResponse(WebSearchProvider.YOU_COM, emptyList())
                },
            ),
            weatherTool = WeatherTool(
                object : WeatherGateway {
                    override suspend fun currentAndToday(location: String): WeatherResult =
                        weatherResult(location)
                },
            ),
            reminderQueryTool = ReminderQueryTool(reminder),
        )
        val checkpoints = mutableListOf<AgentPlanCheckpoint>()
        val planBridge = if (enablePlanBridge) {
            AgentPlanExecutionBridge(
                registry = registry,
                orchestrator = orchestrator,
                checkpointSink = AgentPlanCheckpointSink(checkpoints::add),
                clock = { FIXED_NOW },
            )
        } else {
            null
        }
        return Fixture(
            controller = ManualToolAgentController(
                runtime = runtime,
                registry = registry,
                orchestrator = orchestrator,
                agentPlanBridge = planBridge,
            ),
            runtime = runtime,
            confirmations = confirmations,
            checkpoints = checkpoints,
        )
    }

    private fun toolStepWithGuess(
        turnId: TurnId,
        guess: String,
        call: LlmToolCall,
    ): Flow<ModelEvent> = flowOf(
        ModelEvent.TextDelta(turnId, guess),
        ModelEvent.FinalToolCalls(turnId, listOf(call)),
        ModelEvent.Completed(turnId),
    )

    private fun call(id: String, name: String, arguments: String): LlmToolCall = LlmToolCall(
        id = id,
        name = name,
        argumentsJson = arguments,
    )

    private fun weatherResult(location: String): WeatherResult = WeatherResult(
        location = location,
        currentAt = "2026-08-25T12:00",
        condition = "맑음",
        temperatureCelsius = "24.0",
        apparentTemperatureCelsius = "24.0",
        relativeHumidityPercent = 50,
        precipitationMillimetres = "0.0",
        windSpeedKilometresPerHour = "4.0",
        todayMinimumCelsius = "18.0",
        todayMaximumCelsius = "28.0",
        todayPrecipitationProbabilityPercent = 10,
        sourceName = "Open-Meteo",
        sourceUrl = "https://open-meteo.com/",
    )

    private data class Fixture(
        val controller: ManualToolAgentController,
        val runtime: FakeRuntime,
        val confirmations: List<ActionChallenge>,
        val checkpoints: List<AgentPlanCheckpoint>,
    )

    private class FakeRuntime : LlmRuntime {
        override val state = MutableStateFlow<LlmState>(LlmState.Ready(InferenceBackend.CPU))
        val responseInvocations = mutableListOf<List<TrustedToolResponse>>()
        val cancelCount = AtomicInteger()
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
            mediaModalities: Set<TurnMediaKind>,
        ) = Unit

        override fun streamUserTurn(turnId: TurnId, prompt: String): Flow<ModelEvent> =
            userStreams.removeFirst()

        override fun streamToolResponses(
            turnId: TurnId,
            responses: List<TrustedToolResponse>,
        ): Flow<ModelEvent> {
            responseInvocations += responses.toList()
            return responseStreams.removeFirst()
        }

        override suspend fun cancel(turnId: TurnId) {
            cancelCount.incrementAndGet()
        }
        override fun close() = Unit
    }

    private class StubCalendarGateway(
        private val events: List<CalendarEvent>,
    ) : CalendarGateway {
        override suspend fun writableCalendars(): List<CalendarAccount> = emptyList()
        override suspend fun findEvent(eventId: Long): CalendarEvent? = null
        override suspend fun insertEvent(draft: CalendarEventDraft): Long? = null
        override suspend fun updateEvent(eventId: Long, patch: CalendarEventPatch): Boolean = false
        override suspend fun queryEvents(
            startEpochMillis: Long,
            endEpochMillis: Long,
            limit: Int,
        ): List<CalendarEvent> = events.take(limit)
    }

    private class StubAlarmGateway(
        private val next: NextAlarm?,
    ) : AlarmGateway {
        val requestCount = AtomicInteger()

        override suspend fun clockAppAvailable(): Boolean = true

        override suspend fun requestAlarm(request: AlarmRequest): AlarmOutcome {
            requestCount.incrementAndGet()
            return AlarmOutcome.Delivered
        }

        override suspend fun nextAlarm(): NextAlarm? = next
    }

    private class StubReminderGateway(
        private val reminders: List<ReminderSummary>,
    ) : ReminderGateway {
        override suspend fun create(request: ReminderWriteRequest): ReminderMutationResult =
            error("Unused write gateway")

        override suspend fun update(
            reminderId: String,
            expectedVersion: Long,
            request: ReminderWriteRequest,
        ): ReminderMutationResult = error("Unused write gateway")

        override suspend fun cancel(
            reminderId: String,
            expectedVersion: Long,
        ): ReminderMutationResult = error("Unused write gateway")

        override suspend fun upcoming(limit: Int): List<ReminderSummary> = reminders.take(limit)
    }

    private class RecordingLedger : ActionLedger {
        val claimedKeys = mutableListOf<String>()
        val recordedStates = mutableListOf<Pair<String, ActionExecutionState>>()

        override suspend fun claim(idempotencyKey: String): Boolean {
            claimedKeys += idempotencyKey
            return true
        }

        override suspend fun recordState(
            idempotencyKey: String,
            state: ActionExecutionState,
        ): Boolean {
            recordedStates += idempotencyKey to state
            return true
        }
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val FIXED_NOW: Long = Instant.parse("2026-08-25T00:00:00Z").toEpochMilli()
    }
}
