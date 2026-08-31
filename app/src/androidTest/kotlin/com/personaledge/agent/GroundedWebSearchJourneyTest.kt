package com.personaledge.agent

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.agent.AgentEvent
import com.personaledge.core.agent.ManualToolAgentController
import com.personaledge.core.agent.ManualToolRegistry
import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.data.MessageRole
import com.personaledge.core.data.PersonalEdgeDatabase
import com.personaledge.core.data.TurnOutcomeFailureCode
import com.personaledge.core.data.TurnOutcomeRepository
import com.personaledge.core.data.TurnOutcomeState
import com.personaledge.core.data.TurnToolCommitOutcome
import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.llm.LlmRuntime
import com.personaledge.core.llm.LlmState
import com.personaledge.core.llm.LlmToolDefinition
import com.personaledge.core.llm.LlmTurnToolScope
import com.personaledge.core.llm.MAX_USER_PROMPT_BYTES
import com.personaledge.core.llm.ModelEvent
import com.personaledge.core.llm.TrustedToolResponse
import com.personaledge.core.llm.TurnId
import com.personaledge.core.llm.TurnMediaKind
import com.personaledge.core.llm.VerifiedInstalledModel
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
import com.personaledge.core.tools.InProcessActionLedger
import com.personaledge.core.tools.InterlockDecision
import com.personaledge.core.tools.NextAlarm
import com.personaledge.core.tools.NotificationGateway
import com.personaledge.core.tools.NotificationSearchTool
import com.personaledge.core.tools.RouteEstimate
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.RouteGateway
import com.personaledge.core.tools.ToolExecutionOutcome
import com.personaledge.core.tools.ToolOrchestrator
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provider-free end-to-end coverage for the grounded web-search conversation contract.
 *
 * The only scripted boundaries are LiteRT output and the network gateway. Routing, Tool scope,
 * evidence filtering, answer ownership, transcript context, Room commits, and recovery identity
 * all use the production implementations. This makes the test suitable for the account-free API
 * 37 AVD while still exercising the seams that separate otherwise isolated JVM tests.
 */
@RunWith(AndroidJUnit4::class)
class GroundedWebSearchJourneyTest {
    private lateinit var database: PersonalEdgeDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var outcomes: TurnOutcomeRepository
    private lateinit var history: ChatHistoryCoordinator

    @Before
    fun openInMemoryDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            PersonalEdgeDatabase::class.java,
        ).build()
        conversations = ConversationRepository(database)
        outcomes = TurnOutcomeRepository(database)
        history = ChatHistoryCoordinator(conversations, outcomes)
    }

    @After
    fun closeInMemoryDatabase() {
        database.close()
    }

    @Test
    fun groundedWebJourneysPreserveRoutingRecoveryAndStoredContext() = runBlocking {
        val trace = mutableListOf<String>()
        val gateway = FixtureWebSearchGateway(trace)
        val runtime = ScriptedLlmRuntime(trace)
        val controller = controller(runtime, gateway)

        val mandatoryConversation = requireNotNull(
            history.ensureConversation(null, OFFICEHOLDER_REQUEST),
        )
        runtime.enqueueAnswer("현재 대한민국 대통령은 홍길동입니다.")
        val mandatoryTraceStart = trace.size
        val mandatory = runStoredTurn(
            controller = controller,
            conversationId = mandatoryConversation,
            prompt = OFFICEHOLDER_REQUEST,
            turnId = OFFICEHOLDER_TURN_ID,
        )

        assertEquals(
            listOf(
                "search:$OFFICEHOLDER_QUERY",
                "model:$OFFICEHOLDER_TURN_ID",
            ),
            trace.drop(mandatoryTraceStart),
        )
        assertEquals(OFFICEHOLDER_QUERY, gateway.queries.single())
        assertEquals(1, mandatory.events.filterIsInstance<AgentEvent.ToolExecuted>().size)
        assertTrue(mandatory.answer.startsWith("현재 대한민국 대통령은 홍길동입니다."))
        assertTrue(mandatory.answer.contains(OFFICIAL_OFFICEHOLDER_URL))
        assertFalse(mandatory.answer.contains("constitution.example"))
        assertFalse(mandatory.answer.contains("obituary.example"))
        assertFalse(
            runtime.invocations.single().prompt.contains("https://"),
        )

        val filmConversation = requireNotNull(
            history.ensureConversation(null, FILM_REQUEST),
        )
        runtime.enqueueAnswer(
            "제가 가지고 있는 정보로는 이 영화의 자세한 정보를 제공해 드릴 수 없습니다.",
        )
        runtime.enqueueAnswer("미야자키 하야오가 연출한 일본 애니메이션 영화입니다.")
        val optionalTraceStart = trace.size
        val optional = runStoredTurn(
            controller = controller,
            conversationId = filmConversation,
            prompt = FILM_REQUEST,
            turnId = FILM_TURN_ID,
        )

        assertEquals(
            listOf(
                "model:$FILM_TURN_ID",
                "search:$FILM_QUERY",
                "model:$FILM_TURN_ID",
            ),
            trace.drop(optionalTraceStart),
        )
        assertEquals(1, gateway.queries.count { query -> query == FILM_QUERY })
        assertEquals(2, runtime.invocations.count { invocation ->
            invocation.turnId.value == FILM_TURN_ID
        })
        assertEquals(1, optional.events.filterIsInstance<AgentEvent.ToolExecuted>().size)
        assertTrue(optional.answer.startsWith("미야자키 하야오"))
        assertTrue(optional.answer.contains(FILM_SOURCE_URL))
        assertFalse(optional.answer.contains("가지고 있는 정보로는"))

        val correctionConversation = requireNotNull(
            history.ensureConversation(null, OFFICEHOLDER_REQUEST),
        )
        val firstQuestionOrdinal = requireNotNull(
            history.recordOrdinal(
                correctionConversation,
                MessageRole.USER,
                OFFICEHOLDER_REQUEST,
            ),
        )
        assertTrue(
            history.record(
                correctionConversation,
                MessageRole.ASSISTANT,
                "실시간 정보를 제공할 수 없습니다.",
            ),
        )
        // A failed correction has no following assistant row. The explicit first-question wording
        // may skip this one closed correction row, but no arbitrary USER row.
        assertTrue(
            history.record(
                correctionConversation,
                MessageRole.USER,
                "웹 검색 할 수 있잖아",
            ),
        )
        val correctionPrompt = "웹검색을 해서 첫질문에 대한 답을 해줘"
        val contextualRequest = requireNotNull(
            history.contextualWebSearchRequestForFollowUp(
                correctionConversation,
                correctionPrompt,
            ),
        )
        assertEquals(firstQuestionOrdinal, contextualRequest.userMessageOrdinal)
        runtime.enqueueAnswer("현재 대한민국 대통령은 홍길동입니다.")
        val correctionTraceStart = trace.size
        val correction = runStoredTurn(
            controller = controller,
            conversationId = correctionConversation,
            prompt = correctionPrompt,
            turnId = CORRECTION_TURN_ID,
            contextualRequest = contextualRequest,
        )

        assertEquals(
            listOf(
                "search:$OFFICEHOLDER_QUERY",
                "model:$CORRECTION_TURN_ID",
            ),
            trace.drop(correctionTraceStart),
        )
        assertTrue(correction.answer.startsWith("현재 대한민국 대통령은 홍길동입니다."))
        val correctionOutcome = requireNotNull(
            database.turnOutcomeDao().find(CORRECTION_TURN_ID),
        )
        assertEquals(correction.userMessageOrdinal, correctionOutcome.userMessageOrdinal)
        assertEquals(
            firstQuestionOrdinal,
            correctionOutcome.recoverySourceUserMessageOrdinal,
        )
        assertEquals(TurnOutcomeState.ANSWER_COMPLETE, correctionOutcome.state)

        val summaryPrompt = "검색결과를 정리해서 요약해줘"
        val priorResult = requireNotNull(
            history.priorWebResultForFollowUp(filmConversation, summaryPrompt),
        )
        runtime.enqueueAnswer(
            "이 작품은 미야자키 하야오가 연출한 일본 애니메이션 영화라는 점이 핵심입니다.",
        )
        val searchesBeforeSummary = gateway.queries.size
        val summaryTraceStart = trace.size
        val summary = runStoredTurn(
            controller = controller,
            conversationId = filmConversation,
            prompt = summaryPrompt,
            turnId = SUMMARY_TURN_ID,
            requiredPriorAnswer = priorResult,
        )

        assertEquals(listOf("model:$SUMMARY_TURN_ID"), trace.drop(summaryTraceStart))
        assertEquals(searchesBeforeSummary, gateway.queries.size)
        assertTrue(summary.context.requiredPriorAnswerIncluded)
        assertTrue(summary.context.text.contains("미야자키 하야오"))
        assertTrue(summary.context.text.contains(FILM_SOURCE_URL))
        assertTrue(summary.context.text.contains("[신뢰 검색 후속 정책]"))
        assertTrue(
            runtime.invocations.single { invocation ->
                invocation.turnId.value == SUMMARY_TURN_ID
            }.toolScope?.toolNames?.isEmpty() == true,
        )
        assertTrue(summary.events.none { event -> event is AgentEvent.ToolExecuted })
        assertTrue(summary.answer.contains("미야자키 하야오"))
        assertEquals(0, runtime.remainingAnswers)
    }

    private suspend fun runStoredTurn(
        controller: ManualToolAgentController,
        conversationId: String,
        prompt: String,
        turnId: String,
        contextualRequest: ContextualWebSearchRequest? = null,
        requiredPriorAnswer: PriorWebResultReference? = null,
    ): StoredTurn {
        val context = TurnContextBuilder.buildResult(
            prompt = prompt,
            device = TurnDeviceContext(
                localTimestamp = "2026-09-01T10:00:00+09:00",
                timeZoneId = "Asia/Seoul",
                calendarId = null,
                calendarLabel = null,
            ),
            conversation = conversations.loadContext(conversationId),
            maximumBytes = MAX_USER_PROMPT_BYTES,
            requiredPriorAnswer = requiredPriorAnswer,
        )
        assertTrue(context.deviceContextIncluded)
        if (requiredPriorAnswer != null) assertTrue(context.requiredPriorAnswerIncluded)

        val userMessageOrdinal = requireNotNull(
            history.recordOrdinal(conversationId, MessageRole.USER, prompt),
        )
        assertTrue(
            history.startTurnRecoveryCapsule(
                turnId = turnId,
                conversationId = conversationId,
                persistedUserMessageOrdinal = userMessageOrdinal,
                unfinishedReadRequest = null,
                contextualWebSearchRequest = contextualRequest,
                predecessorTurnId = null,
            ),
        )

        val typedTurnId = TurnId(turnId)
        val events = controller.runTurn(
            turnId = typedTurnId,
            prompt = context.text,
            currentUserRequest = prompt,
            contextualWebSearchRequest = contextualRequest?.trustedRequest,
            readOnlyToolsOnly = contextualRequest != null,
            requireReadTool = contextualRequest != null,
        ).toList()

        val answer = StringBuilder()
        events.forEach { event ->
            assertEquals(typedTurnId, event.turnId)
            when (event) {
                is AgentEvent.TextDelta -> answer.append(event.text)
                is AgentEvent.TrustedAnswer -> answer.append(event.text)
                is AgentEvent.ToolExecuted -> {
                    val committed = history.commitToolExecution(
                        conversationId = conversationId,
                        turnId = turnId,
                        assistantText = answer.toString(),
                        toolReceipt = ToolReceiptFormatter.text(event.toolName, event.outcome),
                        toolName = event.toolName,
                        toolRisk = TurnRecoveryPolicy.toolRisk(event.toolName),
                        trustedOrdinal = event.ordinal,
                        outcome = event.outcome.toStoredOutcome(),
                    )
                    assertTrue("Tool outcome was not committed for $turnId", committed)
                    answer.clear()
                }
                is AgentEvent.Failure -> error("Unexpected agent failure: ${event.code}")
                is AgentEvent.Completed,
                is AgentEvent.ThoughtDelta,
                -> Unit
            }
        }
        assertTrue(events.lastOrNull() is AgentEvent.Completed)
        assertTrue(answer.isNotBlank())
        assertTrue(
            history.finalizeTurn(
                conversationId = conversationId,
                turnId = turnId,
                assistantText = answer.toString(),
                completed = true,
                cancelled = false,
                failureCode = TurnOutcomeFailureCode.UNKNOWN,
            ),
        )
        assertEquals(
            TurnOutcomeState.ANSWER_COMPLETE,
            requireNotNull(database.turnOutcomeDao().find(turnId)).state,
        )
        return StoredTurn(
            answer = answer.toString(),
            events = events,
            context = context,
            userMessageOrdinal = userMessageOrdinal,
        )
    }

    private fun controller(
        runtime: ScriptedLlmRuntime,
        gateway: WebSearchGateway,
    ): ManualToolAgentController {
        val calendar = EmptyCalendarGateway()
        val alarm = EmptyAlarmGateway()
        val registry = ManualToolRegistry.forDeviceTools(
            queryTool = CalendarQueryTool(calendar),
            createEventTool = CalendarCreateEventTool(calendar, defaultCalendarId = { null }),
            updateEventTool = CalendarUpdateEventTool(calendar),
            alarmSetTool = AlarmSetTool(alarm),
            alarmNextTool = AlarmNextTool(alarm),
            notificationSearchTool = NotificationSearchTool(
                object : NotificationGateway {
                    override suspend fun search(
                        query: String?,
                        postedAtOrAfter: Long,
                        limit: Int,
                    ) = emptyList<com.personaledge.core.tools.CapturedMessageSummary>()
                },
            ),
            routeEstimateTool = RouteEstimateTool(
                object : RouteGateway {
                    override suspend fun credentialsPresent(): Boolean = false

                    override suspend fun estimate(
                        origin: String,
                        destination: String,
                    ): RouteEstimate = error("Route gateway is outside this test.")
                },
                defaultOrigin = { null },
            ),
            webSearchTool = WebSearchTool(gateway),
            weatherTool = WeatherTool(
                object : WeatherGateway {
                    override suspend fun currentAndToday(location: String): WeatherResult =
                        error("Weather gateway is outside this test.")
                },
            ),
        )
        return ManualToolAgentController(
            runtime = runtime,
            registry = registry,
            orchestrator = ToolOrchestrator(
                actionLedger = InProcessActionLedger(),
                userConfirmationGate = UserConfirmationGate {
                    error("Read-only web search must not request confirmation.")
                },
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
            ),
        )
    }

    private fun ToolExecutionOutcome.toStoredOutcome(): TurnToolCommitOutcome = when (this) {
        ToolExecutionOutcome.READ_COMPLETED -> TurnToolCommitOutcome.READ_COMPLETED
        ToolExecutionOutcome.WRITE_COMPLETED -> TurnToolCommitOutcome.WRITE_COMPLETED
        ToolExecutionOutcome.WRITE_REFUSED -> TurnToolCommitOutcome.WRITE_REFUSED
    }

    private data class StoredTurn(
        val answer: String,
        val events: List<AgentEvent>,
        val context: TurnContextBuildResult,
        val userMessageOrdinal: Long,
    )

    private data class RuntimeInvocation(
        val turnId: TurnId,
        val prompt: String,
        val toolScope: LlmTurnToolScope?,
    )

    private class ScriptedLlmRuntime(
        private val trace: MutableList<String>,
    ) : LlmRuntime {
        override val state = MutableStateFlow<LlmState>(LlmState.Ready(InferenceBackend.CPU))
        val invocations = mutableListOf<RuntimeInvocation>()
        private val answers = ArrayDeque<String>()

        val remainingAnswers: Int
            get() = answers.size

        fun enqueueAnswer(answer: String) {
            answers.addLast(answer)
        }

        override suspend fun initialize(
            model: VerifiedInstalledModel,
            backend: InferenceBackend,
            tools: List<LlmToolDefinition>,
            mediaModalities: Set<TurnMediaKind>,
        ) = Unit

        override fun streamUserTurn(turnId: TurnId, prompt: String): Flow<ModelEvent> =
            nextAnswer(turnId, prompt, null)

        override fun streamUserTurn(
            turnId: TurnId,
            prompt: String,
            maxOutputTokens: Int,
        ): Flow<ModelEvent> = nextAnswer(turnId, prompt, null)

        override fun streamUserTurn(
            turnId: TurnId,
            prompt: String,
            maxOutputTokens: Int,
            toolScope: LlmTurnToolScope,
        ): Flow<ModelEvent> = nextAnswer(turnId, prompt, toolScope)

        private fun nextAnswer(
            turnId: TurnId,
            prompt: String,
            toolScope: LlmTurnToolScope?,
        ): Flow<ModelEvent> {
            trace += "model:${turnId.value}"
            invocations += RuntimeInvocation(turnId, prompt, toolScope)
            val answer = answers.removeFirst()
            return flowOf(
                ModelEvent.TextDelta(turnId, answer),
                ModelEvent.Completed(turnId),
            )
        }

        override fun streamToolResponses(
            turnId: TurnId,
            responses: List<TrustedToolResponse>,
        ): Flow<ModelEvent> = error("Direct grounded web journeys do not inject Tool responses.")

        override suspend fun cancel(turnId: TurnId) = Unit

        override fun close() = Unit
    }

    private class FixtureWebSearchGateway(
        private val trace: MutableList<String>,
    ) : WebSearchGateway {
        val queries = mutableListOf<String>()

        override suspend fun credentialsPresent(): Boolean = true

        override suspend fun search(query: String, limit: Int): WebSearchResponse {
            trace += "search:$query"
            queries += query
            assertTrue(limit > 0)
            return when (query) {
                OFFICEHOLDER_QUERY -> WebSearchResponse(
                    provider = WebSearchProvider.YOU_COM,
                    hits = listOf(
                        WebSearchHit(
                            title = "대한민국 대통령실 - 대통령 소개",
                            link = OFFICIAL_OFFICEHOLDER_URL,
                            snippet = "대한민국의 현직 대통령은 홍길동입니다.",
                        ),
                        WebSearchHit(
                            title = "대한민국 대통령 선거와 임기",
                            link = "https://constitution.example/president",
                            snippet = "대한민국 대통령은 선거로 선출되며 임기는 5년입니다.",
                        ),
                        WebSearchHit(
                            title = "대통령실 관계자 부친상",
                            link = "https://obituary.example/president-office",
                            snippet = "대통령실 관계자의 부친상과 빈소를 안내합니다.",
                        ),
                    ),
                )
                FILM_QUERY -> WebSearchResponse(
                    provider = WebSearchProvider.YOU_COM,
                    hits = listOf(
                        WebSearchHit(
                            title = "그대들은 어떻게 살 것인가 작품 정보",
                            link = FILM_SOURCE_URL,
                            snippet = "미야자키 하야오가 연출한 일본 애니메이션 영화입니다.",
                        ),
                        WebSearchHit(
                            title = "영화계 인사 부고",
                            link = "https://obituary.example/film",
                            snippet = "영화계 관계자의 별세와 빈소를 안내합니다.",
                        ),
                    ),
                )
                else -> error("Unexpected provider-free query: $query")
            }
        }
    }

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
            error("Alarm gateway is outside this test.")

        override suspend fun nextAlarm(): NextAlarm? = null
    }

    private companion object {
        const val OFFICEHOLDER_REQUEST = "현재 대한민국 대통령이 누구야?"
        const val OFFICEHOLDER_QUERY = "대한민국 현직 대통령 이름 공식"
        const val OFFICIAL_OFFICEHOLDER_URL =
            "https://www.president.go.kr/provider-free-fixture"
        const val FILM_REQUEST = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘"
        const val FILM_QUERY = "그대들은 어떻게 살것인가 영화"
        const val FILM_SOURCE_URL = "https://example.com/how-do-you-live"

        const val OFFICEHOLDER_TURN_ID = "turn-10000000-0000-0000-0000-000000000001"
        const val FILM_TURN_ID = "turn-10000000-0000-0000-0000-000000000002"
        const val CORRECTION_TURN_ID = "turn-10000000-0000-0000-0000-000000000003"
        const val SUMMARY_TURN_ID = "turn-10000000-0000-0000-0000-000000000004"
    }
}
