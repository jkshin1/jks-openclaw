package com.personaledge.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionInterlockTest {
    private data class EventParams(val title: String) : ToolParams

    private class FakePersistentLedger : PersistentActionLedger() {
        val claims = mutableSetOf<String>()

        override suspend fun claim(idempotencyKey: String): Boolean = claims.add(idempotencyKey)
    }

    private class CalendarWriteTool : AgentTool<EventParams, String> {
        override val descriptor = ToolDescriptor(
            name = "calendar_create_event",
            description = "Create a calendar event",
            risk = ToolRisk.DATA_WRITE,
            requiredCapabilities = setOf(ToolCapability.WRITE_CALENDAR),
        )
        var executions = 0

        override suspend fun validateAndCanonicalize(params: EventParams): ValidationResult =
            ValidationResult.Valid(params.title)

        override fun preview(input: CanonicalToolInput): ActionPreview =
            ActionPreview("일정 등록", input.encoded)

        override suspend fun execute(
            input: CanonicalToolInput,
            permit: ExecutionPermit,
        ): String {
            executions += 1
            return input.encoded
        }
    }

    private class RecordingInterlock(
        private val decide: (InterlockRequest) -> InterlockDecision,
    ) : ExecutionInterlock {
        val phases = mutableListOf<InterlockPhase>()

        override suspend fun evaluate(request: InterlockRequest): InterlockDecision {
            phases += request.phase
            return decide(request)
        }
    }

    @Test
    fun `preparation stops before the user is asked for an impossible action`() = runBlocking {
        val tool = CalendarWriteTool()
        val orchestrator = ToolOrchestrator(
            actionLedger = FakePersistentLedger(),
            userConfirmationGate = UserConfirmationGate { error("Confirmation must not be shown.") },
            executionInterlock = { InterlockDecision.Block("캘린더 권한이 없습니다.") },
        )

        val result = orchestrator.prepare(
            tool = tool,
            params = EventParams("치과"),
            requestId = "request-permission",
        )

        assertEquals("캘린더 권한이 없습니다.", (result as PreparationResult.Rejected).reason)
        assertEquals(0, tool.executions)
    }

    @Test
    fun `a permission revoked during confirmation blocks execution and spends no claim`() =
        runBlocking {
            val tool = CalendarWriteTool()
            val ledger = FakePersistentLedger()
            val interlock = RecordingInterlock { request ->
                when (request.phase) {
                    InterlockPhase.PREPARE -> InterlockDecision.Allow
                    InterlockPhase.EXECUTE -> InterlockDecision.Block("캘린더 권한이 해제되었습니다.")
                }
            }
            val orchestrator = ToolOrchestrator(
                actionLedger = ledger,
                userConfirmationGate = UserConfirmationGate { true },
                executionInterlock = interlock,
            )
            val prepared = orchestrator.prepare(
                tool = tool,
                params = EventParams("치과"),
                requestId = "request-revoked",
            ) as PreparationResult.Ready

            val failure = assertThrows(IllegalStateException::class.java) {
                runBlocking { orchestrator.execute(prepared.action) }
            }

            assertEquals("캘린더 권한이 해제되었습니다.", failure.message)
            assertEquals(0, tool.executions)
            // The action stays retryable because the durable key was never consumed.
            assertTrue(ledger.claims.isEmpty())
            assertEquals(
                listOf(InterlockPhase.PREPARE, InterlockPhase.EXECUTE),
                interlock.phases,
            )
        }

    @Test
    fun `the interlock sees the trusted descriptor capabilities`() = runBlocking {
        val seen = mutableListOf<InterlockRequest>()
        val orchestrator = ToolOrchestrator(
            actionLedger = FakePersistentLedger(),
            userConfirmationGate = UserConfirmationGate { true },
            executionInterlock = { request ->
                seen += request
                InterlockDecision.Allow
            },
        )
        val tool = CalendarWriteTool()
        val prepared = orchestrator.prepare(
            tool = tool,
            params = EventParams("치과"),
            requestId = "request-capabilities",
        ) as PreparationResult.Ready

        orchestrator.execute(prepared.action)

        assertEquals(2, seen.size)
        assertTrue(
            seen.all { request ->
                request.toolName == "calendar_create_event" &&
                    request.risk == ToolRisk.DATA_WRITE &&
                    request.requiredCapabilities == setOf(ToolCapability.WRITE_CALENDAR)
            },
        )
        assertEquals(1, tool.executions)
    }

    @Test
    fun `the default interlock refuses any tool that declares a capability`() = runBlocking {
        val allowed = CapabilityFreeInterlock().evaluate(
            InterlockRequest(
                toolName = "read_only",
                risk = ToolRisk.READ_ONLY,
                requiredCapabilities = emptySet(),
                phase = InterlockPhase.EXECUTE,
            ),
        )
        val blocked = CapabilityFreeInterlock().evaluate(
            InterlockRequest(
                toolName = "calendar_create_event",
                risk = ToolRisk.DATA_WRITE,
                requiredCapabilities = setOf(ToolCapability.WRITE_CALENDAR),
                phase = InterlockPhase.EXECUTE,
            ),
        )

        assertTrue(allowed is InterlockDecision.Allow)
        assertFalse(blocked is InterlockDecision.Allow)
    }
}
