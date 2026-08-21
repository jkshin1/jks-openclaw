package com.personaledge.core.agent

import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.llm.LlmFailureCode
import com.personaledge.core.llm.LlmRuntime
import com.personaledge.core.llm.LlmState
import com.personaledge.core.llm.LlmToolCall
import com.personaledge.core.llm.LlmToolDefinition
import com.personaledge.core.llm.ModelEvent
import com.personaledge.core.llm.TrustedToolResponse
import com.personaledge.core.llm.TurnId
import com.personaledge.core.llm.VerifiedInstalledModel
import com.personaledge.core.tools.ActionChallenge
import com.personaledge.core.tools.ActionLedger
import com.personaledge.core.tools.FakeArrivalNoticeTool
import com.personaledge.core.tools.InProcessActionLedger
import com.personaledge.core.tools.ToolOrchestrator
import com.personaledge.core.tools.UserConfirmationGate
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
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualToolAgentControllerTest {
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
            assertTrue(events.any { it is AgentEvent.ToolExecuted })
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
    fun `failed tool response stream is never retried`() = runBlocking {
        val turnId = TurnId("turn-response-failure")
        val fixture = fixture()
        fixture.runtime.enqueueUser(toolStep(turnId, call("call-1")))
        fixture.runtime.enqueueResponse(
            flowOf(ModelEvent.Failure(turnId, LlmFailureCode.NATIVE_FAILURE)),
        )

        val events = fixture.controller.runTurn(turnId, "do it").toList()

        assertFailure(events, AgentFailureCode.MODEL_FAILURE, LlmFailureCode.NATIVE_FAILURE)
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

    private fun fixture(
        confirmation: suspend (ActionChallenge) -> Boolean = { true },
        limits: AgentLoopLimits = AgentLoopLimits(),
        ledger: ActionLedger = InProcessActionLedger(),
        actionClock: () -> Long = { 10_000L },
        monotonicClock: () -> Long = { 0L },
    ): Fixture {
        val runtime = FakeLlmRuntime()
        val confirmations = mutableListOf<ActionChallenge>()
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate { challenge ->
                confirmations += challenge
                confirmation(challenge)
            },
            clock = actionClock,
            idFactory = { "action-${confirmations.size + 1}" },
        )
        return Fixture(
            runtime = runtime,
            confirmations = confirmations,
            controller = ManualToolAgentController(
                runtime = runtime,
                registry = ManualToolRegistry(),
                orchestrator = orchestrator,
                limits = limits,
                monotonicClockMillis = monotonicClock,
            ),
        )
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
