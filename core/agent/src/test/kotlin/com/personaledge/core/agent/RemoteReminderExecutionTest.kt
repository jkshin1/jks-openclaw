package com.personaledge.core.agent

import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.llm.LlmRuntime
import com.personaledge.core.llm.LlmState
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
import com.personaledge.core.tools.CapturedMessageSummary
import com.personaledge.core.tools.ExecutionInterlock
import com.personaledge.core.tools.InProcessActionLedger
import com.personaledge.core.tools.InterlockDecision
import com.personaledge.core.tools.InterlockPhase
import com.personaledge.core.tools.NextAlarm
import com.personaledge.core.tools.NotificationGateway
import com.personaledge.core.tools.NotificationSearchTool
import com.personaledge.core.tools.ReminderCreateTool
import com.personaledge.core.tools.ReminderGateway
import com.personaledge.core.tools.ReminderMutationOutcome
import com.personaledge.core.tools.ReminderMutationResult
import com.personaledge.core.tools.ReminderSummary
import com.personaledge.core.tools.ReminderWriteRequest
import com.personaledge.core.tools.RouteEstimate
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.RouteGateway
import com.personaledge.core.tools.ToolExecutionOutcome
import com.personaledge.core.tools.ToolOrchestrator
import com.personaledge.core.tools.ToolRisk
import com.personaledge.core.tools.UserConfirmationGate
import com.personaledge.core.tools.WeatherGateway
import com.personaledge.core.tools.WeatherResult
import com.personaledge.core.tools.WeatherTool
import com.personaledge.core.tools.WebSearchGateway
import com.personaledge.core.tools.WebSearchResponse
import com.personaledge.core.tools.WebSearchTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteReminderExecutionTest {
    @Test
    fun `approved reminder uses exact confirmation durable turn gate and ledger without an LLM`() = runBlocking {
        val f = Fixture()
        val p = proposal()
        f.importStarted(p)
        val events = f.execute(p)
        val receipt = events.filterIsInstance<AgentEvent.ToolExecuted>().single()
        assertEquals(ToolExecutionOutcome.WRITE_COMPLETED, receipt.outcome)
        assertEquals("reminder_create", receipt.toolName)
        assertEquals(1, receipt.ordinal)
        assertEquals(1, f.writes.size)
        assertEquals("복약", f.writes.single().title)
        assertEquals("Asia/Seoul", f.writes.single().zoneId)
        assertTrue(f.confirmations.single().preview.summary.contains("복약"))
        assertTrue(f.confirmations.single().preview.summary.contains("2030-01-01 09:00"))
        assertEquals(listOf("confirm", "arm_started_turn", "create"), f.order)
        assertEquals(listOf(ActionExecutionState.COMPLETED), f.ledger.snapshot().values.toList())
        assertEquals(AgentEvent.Completed(p.value.requiredLocalTurnId), events.last())
        assertTrue(events.filterIsInstance<AgentEvent.TrustedAnswer>().single().text.contains("저장했습니다"))
        assertEquals(0, f.runtime.calls)
        assertEquals(listOf(receipt), f.controller.retainedToolExecutions(p.value.requiredLocalTurnId))
    }

    @Test
    fun `confirmation denial consumes the proposal and performs no claim or write`() = runBlocking {
        val f = Fixture(confirmation = { false })
        val p = proposal()
        f.importStarted(p)
        assertFailure(f.execute(p), AgentFailureCode.TOOL_NOT_EXECUTED)
        assertFailure(f.execute(p), AgentFailureCode.TOOL_NOT_EXECUTED)
        assertEquals(1, f.confirmations.size)
        assertEquals(0, f.gateCalls)
        assertEquals(0, f.ledger.claimAttempts)
        assertTrue(f.writes.isEmpty())
        assertEquals(0, f.runtime.calls)
    }

    @Test
    fun `unimported durable turn and explicit gate refusal prevent ledger claim`() = runBlocking {
        val f = Fixture()
        val p = proposal()
        assertFailure(f.execute(p), AgentFailureCode.TOOL_NOT_EXECUTED)
        assertEquals(1, f.confirmations.size)
        assertEquals(1, f.gateCalls)
        assertEquals(0, f.ledger.claimAttempts)
        assertTrue(f.writes.isEmpty())
        assertEquals(0, f.runtime.calls)
    }

    @Test
    fun `default demo gate and nonpersistent ledger cannot authorize remote writes`() = runBlocking {
        val f = Fixture()
        val p = proposal()
        val defaultGateController = ManualToolAgentController(
            runtime = f.runtime,
            registry = f.registry,
            orchestrator = f.orchestrator,
            remoteProposalClockMillis = { NOW },
        )
        assertFailure(defaultGateController.runApprovedRemoteReminderProposal(
            p.value.requiredLocalTurnId, p.value, p.source).toList(), AgentFailureCode.TOOL_NOT_EXECUTED)
        assertTrue(f.confirmations.isEmpty())
        val transient = Fixture(actionLedger = InProcessActionLedger())
        val next = proposal()
        transient.importStarted(next)
        assertFailure(transient.execute(next), AgentFailureCode.TOOL_NOT_EXECUTED)
        assertTrue(transient.confirmations.isEmpty())
        assertTrue(transient.writes.isEmpty())
    }

    @Test
    fun `wrong imported turn source or missing registry tool fails before confirmation`() = runBlocking {
        val f = Fixture()
        val p = proposal()
        f.importStarted(p)
        assertFailure(f.controller.runApprovedRemoteReminderProposal(TurnId("replacement-turn"), p.value, p.source)
            .toList(), AgentFailureCode.TURN_MISMATCH)
        assertFailure(f.controller.runApprovedRemoteReminderProposal(p.value.requiredLocalTurnId, p.value,
            proposal(run = "other-run").source).toList(), AgentFailureCode.INVALID_TOOL_CALL)
        val missing = Fixture(includeReminder = false)
        assertFailure(missing.execute(proposal()), AgentFailureCode.UNKNOWN_TOOL)
        assertTrue(f.confirmations.isEmpty())
        assertEquals(0, f.runtime.calls)
        assertTrue(missing.confirmations.isEmpty())
    }

    @Test
    fun `expired proposal and expiry during confirmation cannot create a reminder`() = runBlocking {
        val f = Fixture()
        val p = proposal()
        f.now = NOW + 120_000L
        assertFailure(f.execute(p), AgentFailureCode.DEADLINE_EXCEEDED)
        assertTrue(f.confirmations.isEmpty())
        lateinit var waiting: Fixture
        waiting = Fixture(confirmation = { waiting.now = NOW + 120_000L; true })
        val next = proposal()
        waiting.importStarted(next)
        assertFailure(waiting.execute(next), AgentFailureCode.TOOL_NOT_EXECUTED)
        assertEquals(0, waiting.ledger.claimAttempts)
        assertTrue(waiting.writes.isEmpty())
        assertEquals(0, waiting.runtime.calls)
    }

    @Test
    fun `expiry while the durable turn gate suspends is checked again before claim`() = runBlocking {
        lateinit var f: Fixture
        f = Fixture(afterArm = { f.now = NOW + 120_000L }, actionClock = { NOW })
        val p = proposal()
        f.importStarted(p)
        assertFailure(f.execute(p), AgentFailureCode.TOOL_NOT_EXECUTED)
        assertEquals(1, f.gateCalls)
        assertEquals(0, f.ledger.claimAttempts)
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun `foreground or permission interlock change after confirmation still blocks the remote path`() = runBlocking {
        val f = Fixture(blockAtExecution = true)
        val p = proposal()
        f.importStarted(p)
        assertFailure(f.execute(p), AgentFailureCode.TOOL_NOT_EXECUTED)
        assertEquals(1, f.confirmations.size)
        assertEquals(0, f.gateCalls)
        assertEquals(0, f.ledger.claimAttempts)
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun `reconstructed proposal and controller still use the original durable ledger identity`() = runBlocking {
        val ledger = RemoteReminderTestLedger()
        val first = Fixture(actionLedger = ledger)
        val p = proposal()
        first.importStarted(p)
        first.execute(p)
        val second = Fixture(actionLedger = ledger)
        val reconstructed = proposal()
        // Simulate an erroneous fresh STARTED capsule. The independent existing ledger still
        // prevents a completed action being repeated, including across controller instances.
        second.importStarted(reconstructed)
        assertFailure(second.execute(reconstructed), AgentFailureCode.TOOL_NOT_EXECUTED)
        assertEquals(2, ledger.claimAttempts)
        assertEquals(1, ledger.snapshot().size)
        assertEquals(1, first.writes.size)
        assertTrue(second.writes.isEmpty())
        assertEquals(0, second.runtime.calls)
    }

    @Test
    fun `same source with changed arguments cannot arm a second side effect in its durable turn`() = runBlocking {
        val f = Fixture()
        val first = proposal()
        f.importStarted(first)
        f.execute(first)
        val altered = proposal(response = RESPONSE.replace("복약", "다른 알림"))
        assertEquals(first.value.requiredLocalTurnId, altered.value.requiredLocalTurnId)
        assertFailure(f.execute(altered), AgentFailureCode.TOOL_NOT_EXECUTED)
        assertEquals(1, f.ledger.claimAttempts)
        assertEquals(1, f.writes.size)
    }

    @Test
    fun `cancellation during confirmation releases turn ownership without cancelling the local runtime`() = runBlocking {
        val confirming = CompletableDeferred<Unit>()
        val f = Fixture(confirmation = { confirming.complete(Unit); awaitCancellation() })
        val p = proposal()
        f.importStarted(p)
        val events = mutableListOf<AgentEvent>()
        val job = launch { f.controller.runApprovedRemoteReminderProposal(
            p.value.requiredLocalTurnId, p.value, p.source).collect(events::add) }
        confirming.await()
        assertFailure(f.controller.runTurn(TurnId("local-other"), "안녕하세요").toList(), AgentFailureCode.BUSY)
        assertFalse(f.controller.cancel(TurnId("local-other")))
        assertTrue(f.controller.cancel(p.value.requiredLocalTurnId))
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(f.controller.cancel(p.value.requiredLocalTurnId))
        assertEquals(0, f.runtime.calls)
        assertEquals(0, f.ledger.claimAttempts)
        assertTrue(f.controller.retainedToolExecutions(p.value.requiredLocalTurnId).isEmpty())
        assertTrue(events.none { it is AgentEvent.Completed })
    }

    @Test
    fun `cancellation after claim preserves the completed write receipt and prohibits replay`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val f = Fixture(onCreate = { entered.complete(Unit); finish.await(); saved() })
        val p = proposal()
        f.importStarted(p)
        val events = mutableListOf<AgentEvent>()
        val job = launch { f.controller.runApprovedRemoteReminderProposal(
            p.value.requiredLocalTurnId, p.value, p.source).collect(events::add) }
        entered.await()
        assertTrue(f.controller.cancel(p.value.requiredLocalTurnId))
        finish.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, f.writes.size)
        assertEquals(listOf(ActionExecutionState.COMPLETED), f.ledger.snapshot().values.toList())
        val receipt = f.controller.retainedToolExecutions(p.value.requiredLocalTurnId).single()
        assertEquals(ToolExecutionOutcome.WRITE_COMPLETED, receipt.outcome)
        assertTrue(events.none { it is AgentEvent.Completed })
        assertEquals(0, f.runtime.calls)
        assertFailure(f.execute(p), AgentFailureCode.TOOL_NOT_EXECUTED)
        assertEquals(1, f.writes.size)
    }

    @Test
    fun `a busy local turn is never cancelled by a rejected remote proposal`() = runBlocking {
        val f = Fixture()
        val localStarted = CompletableDeferred<Unit>()
        f.runtime.userStream = flow { localStarted.complete(Unit); awaitCancellation() }
        val localId = TurnId("local-active")
        val local = launch(start = CoroutineStart.UNDISPATCHED) {
            f.controller.runTurn(localId, "안녕하세요").collect()
        }
        localStarted.await()
        val p = proposal()
        f.importStarted(p)
        assertFailure(f.execute(p), AgentFailureCode.BUSY)
        assertFalse(f.controller.cancel(p.value.requiredLocalTurnId))
        assertEquals(1, f.runtime.calls)
        assertTrue(f.controller.cancel(localId))
        local.join()
        assertEquals(2, f.runtime.calls)
        assertEquals(AgentEvent.Completed(p.value.requiredLocalTurnId), f.execute(p).last())
        assertEquals(2, f.runtime.calls)
    }

    @Test
    fun `invalid dates never reach confirmation or trigger a repair model`() = runBlocking {
        val f = Fixture()
        val p = proposal(response = RESPONSE.replace("2030-01-01T09:00", "tomorrow morning"))
        f.importStarted(p)
        assertFailure(f.execute(p), AgentFailureCode.TOOL_NOT_EXECUTED)
        assertTrue(f.confirmations.isEmpty())
        assertTrue(f.writes.isEmpty())
        assertEquals(0, f.runtime.calls)
    }

    @Test
    fun `typed write refusal and unknown provider failure retain different honest receipts`() = runBlocking {
        val refused = Fixture(onCreate = { ReminderMutationResult(ReminderMutationOutcome.CAPACITY_REACHED) })
        val p = proposal()
        refused.importStarted(p)
        val events = refused.execute(p)
        assertEquals(ToolExecutionOutcome.WRITE_REFUSED, events.filterIsInstance<AgentEvent.ToolExecuted>().single().outcome)
        assertEquals(listOf(ActionExecutionState.REFUSED), refused.ledger.snapshot().values.toList())
        assertFalse(events.filterIsInstance<AgentEvent.TrustedAnswer>().single().text.contains("저장했습니다"))
        val unknown = Fixture(onCreate = { error("private gateway content") })
        val next = proposal()
        unknown.importStarted(next)
        val failure = unknown.execute(next)
        assertFailure(failure, AgentFailureCode.TOOL_NOT_EXECUTED)
        assertEquals(listOf(ActionExecutionState.UNKNOWN_AFTER_CLAIM), unknown.ledger.snapshot().values.toList())
        assertTrue(unknown.controller.retainedToolExecutions(next.value.requiredLocalTurnId).isEmpty())
        assertFalse(failure.toString().contains("private gateway content"))
    }

    private class Fixture(
        confirmation: suspend (ActionChallenge) -> Boolean = { true },
        actionLedger: ActionLedger? = null,
        includeReminder: Boolean = true,
        blockAtExecution: Boolean = false,
        afterArm: suspend () -> Unit = {},
        actionClock: (() -> Long)? = null,
        onCreate: suspend () -> ReminderMutationResult = { saved() },
    ) {
        var now = NOW
        val runtime = CountingRuntime()
        val ledger = actionLedger as? RemoteReminderTestLedger ?: RemoteReminderTestLedger()
        val confirmations = mutableListOf<ActionChallenge>()
        val writes = mutableListOf<ReminderWriteRequest>()
        val order = mutableListOf<String>()
        val startedTurns = mutableSetOf<TurnId>()
        var gateCalls = 0
        private val reminderGateway = object : ReminderGateway {
            override suspend fun create(request: ReminderWriteRequest): ReminderMutationResult {
                order += "create"
                writes += request
                return onCreate()
            }
            override suspend fun update(reminderId: String, expectedVersion: Long, request: ReminderWriteRequest) =
                error("Unsupported reminder update")
            override suspend fun cancel(reminderId: String, expectedVersion: Long) = error("Unsupported reminder cancel")
            override suspend fun upcoming(limit: Int): List<ReminderSummary> = error("Unsupported reminder query")
        }
        val registry = deviceRegistry(if (includeReminder) ReminderCreateTool(reminderGateway) { NOW } else null)
        val orchestrator = ToolOrchestrator(
            actionLedger = actionLedger ?: ledger,
            userConfirmationGate = UserConfirmationGate {
                order += "confirm"
                confirmations += it
                confirmation(it)
            },
            executionInterlock = ExecutionInterlock {
                if (blockAtExecution && it.phase == InterlockPhase.EXECUTE) InterlockDecision.Block("Foreground lost.")
                else InterlockDecision.Allow
            },
            clock = { actionClock?.invoke() ?: now },
        )
        val controller = ManualToolAgentController(
            runtime = runtime,
            registry = registry,
            orchestrator = orchestrator,
            sideEffectTurnGate = SideEffectTurnGate { turn, tool, risk ->
                gateCalls++
                assertEquals(ReminderCreateTool.NAME, tool)
                assertEquals(ToolRisk.LOCAL_WRITE, risk)
                order += "arm_started_turn"
                val armed = startedTurns.remove(turn)
                if (armed) afterArm()
                armed
            },
            remoteProposalClockMillis = { now },
        )

        fun importStarted(p: Parsed) { startedTurns.add(p.value.requiredLocalTurnId) }
        suspend fun execute(p: Parsed): List<AgentEvent> = controller.runApprovedRemoteReminderProposal(
            p.value.requiredLocalTurnId, p.value, p.source).toList()
    }

    private class CountingRuntime : LlmRuntime {
        var calls = 0
        var userStream: Flow<ModelEvent>? = null
        override val state = MutableStateFlow<LlmState>(LlmState.Ready(InferenceBackend.CPU))
        override suspend fun initialize(model: VerifiedInstalledModel, backend: InferenceBackend,
            tools: List<LlmToolDefinition>, mediaModalities: Set<TurnMediaKind>) { calls++ }
        override fun streamUserTurn(turnId: TurnId, prompt: String): Flow<ModelEvent> {
            calls++
            return checkNotNull(userStream) { "Remote proposal called the local runtime." }
        }
        override fun streamToolResponses(turnId: TurnId, responses: List<TrustedToolResponse>): Flow<ModelEvent> {
            calls++
            error("Remote proposal reinjected Tool output into the local runtime.")
        }
        override suspend fun cancel(turnId: TurnId) { calls++ }
        override fun close() { calls++ }
    }

    private data class Parsed(val value: RemoteReminderProposal, val source: RemoteReminderProposalSource)

    private fun proposal(response: String = RESPONSE, run: String = "gateway-run-1"): Parsed {
        val source = requireNotNull(RemoteReminderProposalSource.create("a".repeat(64),
            requireNotNull(RemoteAgentRunId.parse(run)), response, NOW))
        val result = RemoteReminderProposalParser.parse(response, source, NOW) as RemoteReminderProposalParseResult.Accepted
        return Parsed(result.proposal, source)
    }

    private fun assertFailure(events: List<AgentEvent>, code: AgentFailureCode) {
        assertEquals(code, (events.last() as AgentEvent.Failure).code)
        assertTrue(events.none { it is AgentEvent.Completed })
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val RESPONSE = """{"version":"1","tool":"reminder_create","title":"복약","trigger_at":"2030-01-01T09:00","zone_id":"Asia/Seoul"}"""
        fun saved() = ReminderMutationResult(ReminderMutationOutcome.SAVED, "fixture-reminder", 1L)

        fun deviceRegistry(reminder: ReminderCreateTool?): ManualToolRegistry {
            val calendar = object : CalendarGateway {
                override suspend fun writableCalendars(): List<CalendarAccount> = error("Unused")
                override suspend fun findEvent(eventId: Long): CalendarEvent? = error("Unused")
                override suspend fun insertEvent(draft: CalendarEventDraft): Long? = error("Unused")
                override suspend fun queryEvents(startEpochMillis: Long, endEpochMillis: Long, limit: Int): List<CalendarEvent> = error("Unused")
                override suspend fun updateEvent(eventId: Long, patch: CalendarEventPatch): Boolean = error("Unused")
            }
            val alarm = object : AlarmGateway {
                override suspend fun clockAppAvailable(): Boolean = error("Unused")
                override suspend fun requestAlarm(request: AlarmRequest): AlarmOutcome = error("Unused")
                override suspend fun nextAlarm(): NextAlarm? = error("Unused")
            }
            return ManualToolRegistry.forDeviceTools(
                queryTool = CalendarQueryTool(calendar),
                createEventTool = CalendarCreateEventTool(calendar, defaultCalendarId = { null }),
                updateEventTool = CalendarUpdateEventTool(calendar),
                alarmSetTool = AlarmSetTool(alarm),
                alarmNextTool = AlarmNextTool(alarm),
                notificationSearchTool = NotificationSearchTool(object : NotificationGateway {
                    override suspend fun search(query: String?, postedAtOrAfter: Long, limit: Int): List<CapturedMessageSummary> = error("Unused")
                }),
                routeEstimateTool = RouteEstimateTool(object : RouteGateway {
                    override suspend fun credentialsPresent(): Boolean = error("Unused")
                    override suspend fun estimate(origin: String, destination: String): RouteEstimate = error("Unused")
                }) { null },
                webSearchTool = WebSearchTool(object : WebSearchGateway {
                    override suspend fun credentialsPresent(): Boolean = error("Unused")
                    override suspend fun search(query: String, limit: Int): WebSearchResponse = error("Unused")
                }),
                weatherTool = WeatherTool(object : WeatherGateway {
                    override suspend fun currentAndToday(location: String): WeatherResult = error("Unused")
                }),
                reminderCreateTool = reminder,
            )
        }
    }
}
