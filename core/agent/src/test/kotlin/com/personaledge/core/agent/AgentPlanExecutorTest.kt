package com.personaledge.core.agent

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanExecutorTest {
    private val verifierNow = 100_000L
    private val expiresAt = verifierNow + 60_000L
    private val readTool = toolName("calendar_query")
    private val writeTool = toolName("reminder_create")
    private val communicationTool = toolName("message_send")
    private val catalog = AgentPlanPolicyCatalog.create(
        listOf(
            TrustedAgentPlanToolPolicy.create(readTool, AgentPlanRisk.READ_ONLY),
            TrustedAgentPlanToolPolicy.create(
                toolName = writeTool,
                risk = AgentPlanRisk.LOCAL_WRITE,
                writableResourceKinds = setOf(AgentPlanResourceKind.REMINDER),
            ),
            TrustedAgentPlanToolPolicy.create(
                toolName = communicationTool,
                risk = AgentPlanRisk.COMMUNICATION,
                writableResourceKinds = setOf(AgentPlanResourceKind.COMMUNICATION_TARGET),
            ),
        ),
    )

    @Test
    fun `independent reads run in deterministic bounded parallel batches`() = runBlocking {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val started = AtomicInteger()
        val firstTwoStarted = CompletableDeferred<Unit>()
        val releaseFirstBatch = CompletableDeferred<Unit>()
        val callOrder = Collections.synchronizedList(mutableListOf<Int>())
        val checkpoints = mutableListOf<AgentPlanCheckpoint>()
        val runner = AgentPlanReadStepRunner { step ->
            callOrder += step.id.value
            val activeNow = active.incrementAndGet()
            maximumActive.updateAndGet { previous -> maxOf(previous, activeNow) }
            if (started.incrementAndGet() == 2) firstTwoStarted.complete(Unit)
            releaseFirstBatch.await()
            active.decrementAndGet()
            AgentPlanReadOutcome.SUCCEEDED
        }
        val executor = AgentPlanExecutor(
            runner = runner,
            checkpointSink = AgentPlanCheckpointSink { checkpoint -> checkpoints += checkpoint },
            limits = AgentPlanExecutorLimits(maxParallelReads = 2),
            clock = { verifierNow },
        )
        val verified = verifiedPlan(
            listOf(readStep(3, 'd'), readStep(1, 'b'), readStep(2, 'c')),
        )

        val execution = async { executor.execute(verified) }
        withTimeout(1_000L) { firstTwoStarted.await() }
        assertEquals(2, maximumActive.get())
        assertEquals(listOf(1, 2), callOrder.toList())
        releaseFirstBatch.complete(Unit)

        val result = execution.await() as AgentPlanExecutionResult.Completed
        assertEquals(listOf(1, 2, 3), callOrder.toList())
        assertEquals(AgentPlanExecutionState.COMPLETED, result.checkpoint.executionState)
        assertTrue(result.checkpoint.steps.all { step ->
            step.state == AgentPlanStepExecutionState.SUCCEEDED
        })
        assertTrue(checkpoints.zipWithNext().all { (first, second) ->
            second.revision > first.revision
        })
    }

    @Test
    fun `dependencies run only after successful prerequisites`() = runBlocking {
        val callOrder = mutableListOf<Int>()
        val executor = AgentPlanExecutor(
            runner = AgentPlanReadStepRunner { step ->
                callOrder += step.id.value
                AgentPlanReadOutcome.SUCCEEDED
            },
            checkpointSink = noOpCheckpointSink(),
            clock = { verifierNow },
        )
        val verified = verifiedPlan(
            listOf(
                readStep(2, 'c', setOf(stepId(1))),
                readStep(1, 'b'),
            ),
        )

        val result = executor.execute(verified) as AgentPlanExecutionResult.Completed

        assertEquals(listOf(1, 2), callOrder)
        assertEquals(AgentPlanExecutionState.COMPLETED, result.checkpoint.executionState)
    }

    @Test
    fun `any write or communication step refuses the whole plan before runner dispatch`() = runBlocking {
        val runnerCalls = AtomicInteger()
        val executor = AgentPlanExecutor(
            runner = AgentPlanReadStepRunner {
                runnerCalls.incrementAndGet()
                AgentPlanReadOutcome.SUCCEEDED
            },
            checkpointSink = noOpCheckpointSink(),
            clock = { verifierNow },
        )
        val reminder = AgentPlanResource(AgentPlanResourceKind.REMINDER, digest('d'))
        val recipient = AgentPlanResource(
            AgentPlanResourceKind.COMMUNICATION_TARGET,
            digest('e'),
        )
        val writePlan = verifiedPlan(
            listOf(
                readStep(1, 'b'),
                AgentPlanStep(
                    id = stepId(2),
                    toolName = writeTool,
                    argumentDigest = digest('c'),
                    writeSet = setOf(reminder),
                    declaredRisk = AgentPlanRisk.LOCAL_WRITE,
                ),
            ),
        )
        val communicationPlan = verifiedPlan(
            listOf(
                AgentPlanStep(
                    id = stepId(1),
                    toolName = communicationTool,
                    argumentDigest = digest('b'),
                    writeSet = setOf(recipient),
                    declaredRisk = AgentPlanRisk.COMMUNICATION,
                ),
            ),
            idSuffix = "0002",
        )

        val writeResult = executor.execute(writePlan) as AgentPlanExecutionResult.Refused
        val communicationResult = executor.execute(communicationPlan)
            as AgentPlanExecutionResult.Refused

        assertEquals(AgentPlanRefusalCode.UNSUPPORTED_STEP_RISK, writeResult.code)
        assertEquals(AgentPlanRefusalCode.UNSUPPORTED_STEP_RISK, communicationResult.code)
        assertEquals(0, runnerCalls.get())
        assertTrue(writeResult.checkpoint.steps.all { step ->
            step.state == AgentPlanStepExecutionState.REFUSED
        })
    }

    @Test
    fun `refusal blocks dependants while independent reads still complete`() = runBlocking {
        val executor = AgentPlanExecutor(
            runner = AgentPlanReadStepRunner { step ->
                if (step.id == stepId(1)) AgentPlanReadOutcome.REFUSED
                else AgentPlanReadOutcome.SUCCEEDED
            },
            checkpointSink = noOpCheckpointSink(),
            clock = { verifierNow },
        )
        val verified = verifiedPlan(
            listOf(
                readStep(1, 'b'),
                readStep(2, 'c', setOf(stepId(1))),
                readStep(3, 'd'),
            ),
        )

        val result = executor.execute(verified) as AgentPlanExecutionResult.Completed
        val states = result.checkpoint.steps.associate { step -> step.id.value to step.state }

        assertEquals(AgentPlanExecutionState.COMPLETED_WITH_ISSUES, result.checkpoint.executionState)
        assertEquals(AgentPlanStepExecutionState.REFUSED, states.getValue(1))
        assertEquals(AgentPlanStepExecutionState.BLOCKED, states.getValue(2))
        assertEquals(AgentPlanStepExecutionState.SUCCEEDED, states.getValue(3))
    }

    @Test
    fun `runner exception is reduced to a closed code and checkpoint has no content fields`() =
        runBlocking {
            val checkpoints = mutableListOf<AgentPlanCheckpoint>()
            val executor = AgentPlanExecutor(
                runner = AgentPlanReadStepRunner {
                    throw IllegalStateException("RAW_EXCEPTION_SECRET")
                },
                checkpointSink = AgentPlanCheckpointSink { checkpoint ->
                    checkpoints += checkpoint
                },
                clock = { verifierNow },
            )

            val result = executor.execute(verifiedPlan(listOf(readStep(1, 'b'))))
                as AgentPlanExecutionResult.Completed

            assertEquals(
                AgentPlanStepOutcomeCode.RUNNER_FAILED,
                result.checkpoint.steps.single().outcomeCode,
            )
            val persisted = checkpoints.joinToString()
            assertFalse(persisted.contains("RAW_EXCEPTION_SECRET"))
            assertFalse(persisted.contains("prompt", ignoreCase = true))
            assertFalse(persisted.contains("argumentsJson", ignoreCase = true))
            assertFalse(persisted.contains("resource", ignoreCase = true))
        }

    @Test
    fun `expiry is rechecked before dispatching a dependent batch`() = runBlocking {
        var currentTime = verifierNow
        val calls = mutableListOf<Int>()
        val executor = AgentPlanExecutor(
            runner = AgentPlanReadStepRunner { step ->
                calls += step.id.value
                currentTime = expiresAt
                AgentPlanReadOutcome.SUCCEEDED
            },
            checkpointSink = noOpCheckpointSink(),
            clock = { currentTime },
        )
        val verified = verifiedPlan(
            listOf(readStep(1, 'b'), readStep(2, 'c', setOf(stepId(1)))),
        )

        val result = executor.execute(verified) as AgentPlanExecutionResult.Refused

        assertEquals(AgentPlanRefusalCode.PLAN_EXPIRED, result.code)
        assertEquals(listOf(1), calls)
        assertEquals(
            AgentPlanStepExecutionState.EXPIRED,
            result.checkpoint.steps.single { step -> step.id == stepId(2) }.state,
        )
    }

    @Test
    fun `expiry is rechecked immediately after running checkpoint and before runner call`() =
        runBlocking {
            var currentTime = verifierNow
            var runnerCalls = 0
            val executor = AgentPlanExecutor(
                runner = AgentPlanReadStepRunner {
                    runnerCalls++
                    AgentPlanReadOutcome.SUCCEEDED
                },
                checkpointSink = AgentPlanCheckpointSink { checkpoint ->
                    if (checkpoint.executionState == AgentPlanExecutionState.RUNNING) {
                        currentTime = expiresAt
                    }
                },
                clock = { currentTime },
            )

            val result = executor.execute(verifiedPlan(listOf(readStep(1, 'b'))))
                as AgentPlanExecutionResult.Refused

            assertEquals(AgentPlanRefusalCode.PLAN_EXPIRED, result.code)
            assertEquals(0, runnerCalls)
            assertEquals(AgentPlanExecutionState.EXPIRED, result.checkpoint.executionState)
            assertEquals(
                AgentPlanStepExecutionState.EXPIRED,
                result.checkpoint.steps.single().state,
            )
        }

    @Test
    fun `cancellation propagates after a content free cancellation checkpoint`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        val checkpoints = mutableListOf<AgentPlanCheckpoint>()
        val executor = AgentPlanExecutor(
            runner = AgentPlanReadStepRunner {
                started.complete(Unit)
                neverComplete.await()
                AgentPlanReadOutcome.SUCCEEDED
            },
            checkpointSink = AgentPlanCheckpointSink { checkpoint -> checkpoints += checkpoint },
            clock = { verifierNow },
        )

        val execution = launch {
            executor.execute(verifiedPlan(listOf(readStep(1, 'b'))))
        }
        withTimeout(1_000L) { started.await() }
        execution.cancelAndJoin()

        assertTrue(execution.isCancelled)
        assertEquals(AgentPlanExecutionState.CANCELLED, checkpoints.last().executionState)
        assertEquals(
            AgentPlanStepOutcomeCode.EXECUTION_CANCELLED,
            checkpoints.last().steps.single().outcomeCode,
        )
    }

    private fun verifiedPlan(
        steps: List<AgentPlanStep>,
        idSuffix: String = "0001",
    ): VerifiedAgentPlan {
        val proposal = AgentPlan(
            id = requireNotNull(
                AgentPlanId.parse("00000000-0000-0000-0000-00000000$idSuffix"),
            ),
            objectiveDigest = digest('a'),
            expiresAtEpochMillis = expiresAt,
            steps = steps,
        )
        return (PlanVerifier(catalog = catalog, clock = { verifierNow }).verify(proposal)
            as PlanVerificationResult.Accepted).plan
    }

    private fun readStep(
        id: Int,
        argument: Char,
        dependsOn: Set<AgentPlanStepId> = emptySet(),
    ): AgentPlanStep = AgentPlanStep(
        id = stepId(id),
        toolName = readTool,
        argumentDigest = digest(argument),
        dependsOn = dependsOn,
        declaredRisk = AgentPlanRisk.READ_ONLY,
    )

    private fun noOpCheckpointSink(): AgentPlanCheckpointSink = AgentPlanCheckpointSink { }

    private fun stepId(value: Int): AgentPlanStepId = AgentPlanStepId(value)

    private fun digest(character: Char): AgentPlanDigest = requireNotNull(
        AgentPlanDigest.parse(character.toString().repeat(64)),
    )

    private fun toolName(value: String): AgentPlanToolName = requireNotNull(
        AgentPlanToolName.parse(value),
    )
}
