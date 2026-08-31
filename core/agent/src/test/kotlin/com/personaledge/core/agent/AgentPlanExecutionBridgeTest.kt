package com.personaledge.core.agent

import com.personaledge.core.tools.FakeArrivalNoticeParams
import com.personaledge.core.tools.FakeArrivalNoticeTool
import com.personaledge.core.tools.InProcessActionLedger
import com.personaledge.core.tools.ExecutionInterlock
import com.personaledge.core.tools.InterlockDecision
import com.personaledge.core.tools.ToolOrchestrator
import com.personaledge.core.tools.UserConfirmationGate
import com.personaledge.core.tools.ValidationResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanExecutionBridgeTest {
    private val now = 100_000L

    @Test
    fun `strictly bound read executes through orchestrator and persists no content`() = runBlocking {
        val fixture = fixture()
        val arguments = FakeArrivalNoticeParams("Alice", "arrived safely")
        val proposal = plan(
            listOf(readStep(1, fixture.canonicalDigest(arguments))),
        )
        val expectedDigest = requireNotNull(fixture.bridge.verifiedPlanDigest(proposal))

        val result = fixture.bridge.execute(
            proposal = proposal,
            expectedPlanDigest = expectedDigest,
            arguments = listOf(
                EphemeralAgentPlanArguments(
                    stepId = AgentPlanStepId(1),
                    toolName = toolName(FakeArrivalNoticeTool.NAME),
                    argumentsJson = """{"recipient":"Alice","message":"arrived safely"}""",
                ),
            ),
        ) as AgentPlanBridgeResult.Executed

        assertEquals(1, fixture.confirmations)
        assertEquals(
            AgentPlanExecutionState.COMPLETED,
            result.result.checkpoint.executionState,
        )
        assertTrue(fixture.checkpoints.isNotEmpty())
        val persisted = fixture.checkpoints.joinToString()
        assertFalse(persisted.contains("Alice"))
        assertFalse(persisted.contains("arrived safely"))
        assertFalse(persisted.contains("recipient"))
        assertFalse(persisted.contains("message"))
        val ephemeralDescription = EphemeralAgentPlanArguments(
            AgentPlanStepId(1),
            toolName(FakeArrivalNoticeTool.NAME),
            """{"recipient":"Alice","message":"arrived safely"}""",
        ).toString()
        assertFalse(ephemeralDescription.contains("Alice"))
        assertFalse(ephemeralDescription.contains("arrived safely"))
    }

    @Test
    fun `canonical argument digest mismatch refuses before confirmation or checkpoint`() =
        runBlocking {
            val fixture = fixture()
            val proposal = plan(
                listOf(
                    readStep(
                        1,
                        fixture.canonicalDigest(FakeArrivalNoticeParams("Alice", "expected")),
                    ),
                ),
            )

            val result = fixture.bridge.execute(
                proposal = proposal,
                expectedPlanDigest = requireNotNull(fixture.bridge.verifiedPlanDigest(proposal)),
                arguments = listOf(
                    EphemeralAgentPlanArguments(
                        stepId = AgentPlanStepId(1),
                        toolName = toolName(FakeArrivalNoticeTool.NAME),
                        argumentsJson = """{"recipient":"Alice","message":"tampered"}""",
                    ),
                ),
            )

            assertEquals(
                AgentPlanBridgeResult.Refused(
                    AgentPlanBridgeRefusalCode.ARGUMENT_DIGEST_MISMATCH,
                ),
                result,
            )
            assertEquals(0, fixture.confirmations)
            assertTrue(fixture.checkpoints.isEmpty())
        }

    @Test
    fun `missing duplicate malformed and wrong tool bindings fail closed`() = runBlocking {
        val fixture = fixture()
        val step = readStep(
            1,
            fixture.canonicalDigest(FakeArrivalNoticeParams("Alice", "expected")),
        )
        val proposal = plan(listOf(step))
        val expectedDigest = requireNotNull(fixture.bridge.verifiedPlanDigest(proposal))
        val valid = EphemeralAgentPlanArguments(
            stepId = AgentPlanStepId(1),
            toolName = toolName(FakeArrivalNoticeTool.NAME),
            argumentsJson = """{"recipient":"Alice","message":"expected"}""",
        )

        assertEquals(
            AgentPlanBridgeRefusalCode.ARGUMENT_BINDING_MISMATCH,
            (fixture.bridge.execute(proposal, expectedDigest, emptyList())
                as AgentPlanBridgeResult.Refused).code,
        )
        assertEquals(
            AgentPlanBridgeRefusalCode.ARGUMENT_BINDING_MISMATCH,
            (fixture.bridge.execute(proposal, expectedDigest, listOf(valid, valid))
                as AgentPlanBridgeResult.Refused).code,
        )
        assertEquals(
            AgentPlanBridgeRefusalCode.ARGUMENT_BINDING_MISMATCH,
            (fixture.bridge.execute(
                proposal,
                expectedDigest,
                listOf(valid.copy(argumentsJson = """{"recipient":7}""")),
            ) as AgentPlanBridgeResult.Refused).code,
        )
        assertEquals(
            AgentPlanBridgeRefusalCode.TOOL_BINDING_MISMATCH,
            (fixture.bridge.execute(
                proposal,
                expectedDigest,
                listOf(valid.copy(toolName = toolName("calendar_query"))),
            ) as AgentPlanBridgeResult.Refused).code,
        )
        assertEquals(0, fixture.confirmations)
        assertTrue(fixture.checkpoints.isEmpty())
    }

    @Test
    fun `plan digest drift and any non-read plan refuse before dispatch`() = runBlocking {
        val fixture = fixture()
        val proposal = plan(
            listOf(
                readStep(
                    1,
                    fixture.canonicalDigest(FakeArrivalNoticeParams("Alice", "expected")),
                ),
            ),
        )
        val argument = EphemeralAgentPlanArguments(
            stepId = AgentPlanStepId(1),
            toolName = toolName(FakeArrivalNoticeTool.NAME),
            argumentsJson = """{"recipient":"Alice","message":"expected"}""",
        )

        assertEquals(
            AgentPlanBridgeRefusalCode.PLAN_DIGEST_MISMATCH,
            (fixture.bridge.execute(proposal, digest('f'), listOf(argument))
                as AgentPlanBridgeResult.Refused).code,
        )

        val writeProposal = plan(
            listOf(
                AgentPlanStep(
                    id = AgentPlanStepId(1),
                    toolName = toolName("reminder_create"),
                    argumentDigest = digest('b'),
                    declaredRisk = AgentPlanRisk.LOCAL_WRITE,
                ),
            ),
        )
        assertEquals(null, fixture.bridge.verifiedPlanDigest(writeProposal))
        assertEquals(0, fixture.confirmations)
        assertTrue(fixture.checkpoints.isEmpty())
    }

    @Test
    fun `production limits cap steps at four and parallel reads at two`() = runBlocking {
        val fixture = fixture()
        val canonical = fixture.canonicalDigest(FakeArrivalNoticeParams("Alice", "expected"))
        val oversized = plan((1..5).map { ordinal -> readStep(ordinal, canonical) })

        assertEquals(null, fixture.bridge.verifiedPlanDigest(oversized))
        assertTrue(runCatching { AgentPlanBridgeLimits(maxParallelReads = 3) }.isFailure)
        assertTrue(runCatching { AgentPlanBridgeLimits(maxSteps = 5) }.isFailure)
    }

    @Test
    fun `near expiry is never extended to the orchestrator minimum lifetime`() = runBlocking {
        val movingClock = AtomicLong(now)
        val fixture = fixture(clock = movingClock::get)
        val arguments = FakeArrivalNoticeParams("Alice", "expected")
        val proposal = plan(
            steps = listOf(readStep(1, fixture.canonicalDigest(arguments))),
            expiresAt = now + 900L,
        )
        val expectedDigest = requireNotNull(fixture.bridge.verifiedPlanDigest(proposal))
        movingClock.set(now + 500L)

        val result = fixture.bridge.execute(
            proposal = proposal,
            expectedPlanDigest = expectedDigest,
            arguments = listOf(
                EphemeralAgentPlanArguments(
                    stepId = AgentPlanStepId(1),
                    toolName = toolName(FakeArrivalNoticeTool.NAME),
                    argumentsJson = """{"recipient":"Alice","message":"expected"}""",
                ),
            ),
        )

        assertEquals(
            AgentPlanBridgeResult.Refused(AgentPlanBridgeRefusalCode.PLAN_REJECTED),
            result,
        )
        assertEquals(0, fixture.confirmations)
        assertTrue(fixture.checkpoints.isEmpty())
    }

    @Test
    fun `orchestrator interlock refusal cannot be bypassed by the plan bridge`() = runBlocking {
        val fixture = fixture(
            executionInterlock = ExecutionInterlock {
                InterlockDecision.Block("closed policy detail")
            },
        )
        val params = FakeArrivalNoticeParams("Alice", "expected")
        val proposal = plan(listOf(readStep(1, fixture.canonicalDigest(params))))

        val result = fixture.bridge.execute(
            proposal = proposal,
            expectedPlanDigest = requireNotNull(fixture.bridge.verifiedPlanDigest(proposal)),
            arguments = listOf(
                EphemeralAgentPlanArguments(
                    stepId = AgentPlanStepId(1),
                    toolName = toolName(FakeArrivalNoticeTool.NAME),
                    argumentsJson = """{"recipient":"Alice","message":"expected"}""",
                ),
            ),
        )

        assertEquals(
            AgentPlanBridgeResult.Refused(
                AgentPlanBridgeRefusalCode.TOOL_PREPARATION_REFUSED,
            ),
            result,
        )
        assertEquals(0, fixture.confirmations)
        assertTrue(fixture.checkpoints.isEmpty())
        assertFalse(result.toString().contains("closed policy detail"))
    }

    private fun fixture(
        clock: () -> Long = { now },
        executionInterlock: ExecutionInterlock = ExecutionInterlock {
            InterlockDecision.Allow
        },
    ): Fixture {
        val tool = FakeArrivalNoticeTool()
        val checkpoints = mutableListOf<AgentPlanCheckpoint>()
        var confirmations = 0
        val bridge = AgentPlanExecutionBridge(
            registry = ManualToolRegistry(tool),
            orchestrator = ToolOrchestrator(
                actionLedger = InProcessActionLedger(),
                userConfirmationGate = UserConfirmationGate {
                    confirmations++
                    true
                },
                executionInterlock = executionInterlock,
                clock = clock,
                idFactory = { "read-action-$confirmations" },
            ),
            checkpointSink = AgentPlanCheckpointSink { checkpoint -> checkpoints += checkpoint },
            clock = clock,
        )
        return Fixture(
            tool = tool,
            bridge = bridge,
            checkpoints = checkpoints,
            confirmationCount = { confirmations },
        )
    }

    private fun plan(
        steps: List<AgentPlanStep>,
        expiresAt: Long = now + 60_000L,
    ): AgentPlan = AgentPlan(
        id = requireNotNull(AgentPlanId.parse("00000000-0000-0000-0000-000000000001")),
        objectiveDigest = digest('a'),
        expiresAtEpochMillis = expiresAt,
        steps = steps,
    )

    private fun readStep(ordinal: Int, argumentDigest: AgentPlanDigest): AgentPlanStep =
        AgentPlanStep(
            id = AgentPlanStepId(ordinal),
            toolName = toolName(FakeArrivalNoticeTool.NAME),
            argumentDigest = argumentDigest,
            readSet = setOf(
                AgentPlanResource(AgentPlanResourceKind.APP_STATE, digest('e')),
            ),
            declaredRisk = AgentPlanRisk.READ_ONLY,
        )

    private fun toolName(value: String): AgentPlanToolName =
        requireNotNull(AgentPlanToolName.parse(value))

    private fun digest(character: Char): AgentPlanDigest =
        requireNotNull(AgentPlanDigest.parse(character.toString().repeat(64)))

    private class Fixture(
        val tool: FakeArrivalNoticeTool,
        val bridge: AgentPlanExecutionBridge,
        val checkpoints: List<AgentPlanCheckpoint>,
        private val confirmationCount: () -> Int,
    ) {
        val confirmations: Int
            get() = confirmationCount()

        suspend fun canonicalDigest(params: FakeArrivalNoticeParams): AgentPlanDigest {
            val validation = tool.validateAndCanonicalize(params) as ValidationResult.Valid
            return AgentPlanDigest.sha256(validation.canonicalParams)
        }
    }
}
