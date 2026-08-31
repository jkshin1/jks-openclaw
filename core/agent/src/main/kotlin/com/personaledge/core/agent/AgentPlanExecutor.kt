package com.personaledge.core.agent

import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Closed outcome returned by an app-owned read adapter. No result content enters this kernel. */
enum class AgentPlanReadOutcome {
    SUCCEEDED,
    REFUSED,
}

/**
 * App-owned boundary for an already verified READ_ONLY step.
 *
 * Implementations must use the existing strict argument decoder and [com.personaledge.core.tools.ToolOrchestrator]
 * path. This scheduler neither accepts canonical arguments nor owns a Tool, permit, confirmation,
 * interlock, or ledger, so it cannot replace any of those controls.
 */
fun interface AgentPlanReadStepRunner {
    suspend fun run(step: VerifiedAgentPlanStep): AgentPlanReadOutcome
}

data class AgentPlanExecutorLimits(
    val maxParallelReads: Int = 2,
) {
    init {
        require(maxParallelReads in 1..MAX_PARALLEL_READS)
    }

    companion object {
        const val MAX_PARALLEL_READS = 4
    }
}

enum class AgentPlanExecutionState {
    VERIFIED,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_ISSUES,
    REFUSED_UNSUPPORTED_RISK,
    EXPIRED,
    CANCELLED,
}

enum class AgentPlanStepExecutionState {
    VERIFIED,
    RUNNING,
    SUCCEEDED,
    REFUSED,
    FAILED,
    BLOCKED,
    EXPIRED,
    CANCELLED,
}

/** Closed status classification. Exception messages and tool output are deliberately absent. */
enum class AgentPlanStepOutcomeCode {
    NONE,
    READ_COMPLETED,
    READ_REFUSED,
    RUNNER_FAILED,
    DEPENDENCY_NOT_SUCCEEDED,
    PLAN_RISK_NOT_SUPPORTED,
    PLAN_EXPIRED,
    EXECUTION_CANCELLED,
}

/**
 * Content-free, persistence-safe step state.
 *
 * It contains only verifier-trusted metadata and numeric dependencies. Prompt, arguments, result
 * content, resource identifiers, and exception text never enter this type.
 */
data class AgentPlanStepCheckpoint(
    val id: AgentPlanStepId,
    val toolName: AgentPlanToolName,
    val argumentDigest: AgentPlanDigest,
    val risk: AgentPlanRisk,
    val dependsOn: List<AgentPlanStepId>,
    val state: AgentPlanStepExecutionState,
    val outcomeCode: AgentPlanStepOutcomeCode,
)

/** Immutable, monotonically revised snapshot suitable for an app-owned checkpoint store. */
data class AgentPlanCheckpoint(
    val schemaVersion: Int = SCHEMA_VERSION,
    val planId: AgentPlanId,
    val planDigest: AgentPlanDigest,
    val revision: Long,
    val executionState: AgentPlanExecutionState,
    val steps: List<AgentPlanStepCheckpoint>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION)
        require(revision >= 0L)
        require(steps.map(AgentPlanStepCheckpoint::id).distinct().size == steps.size)
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

fun interface AgentPlanCheckpointSink {
    suspend fun store(checkpoint: AgentPlanCheckpoint)
}

enum class AgentPlanRefusalCode {
    UNSUPPORTED_STEP_RISK,
    PLAN_EXPIRED,
}

sealed interface AgentPlanExecutionResult {
    val checkpoint: AgentPlanCheckpoint

    data class Completed(
        override val checkpoint: AgentPlanCheckpoint,
    ) : AgentPlanExecutionResult

    data class Refused(
        val code: AgentPlanRefusalCode,
        override val checkpoint: AgentPlanCheckpoint,
    ) : AgentPlanExecutionResult
}

/**
 * Bounded scheduler for independent READ_ONLY verified steps.
 *
 * This MVP rejects the entire plan before dispatch when any write, communication, vehicle, or
 * high-risk step is present. It schedules ready steps in numeric ID order and runs at most one
 * bounded batch concurrently. A failed/refused step blocks its dependants but not independent
 * reads. The verified plan is scheduling metadata, not an execution permit.
 */
class AgentPlanExecutor(
    private val runner: AgentPlanReadStepRunner,
    private val checkpointSink: AgentPlanCheckpointSink,
    private val limits: AgentPlanExecutorLimits = AgentPlanExecutorLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(plan: VerifiedAgentPlan): AgentPlanExecutionResult {
        val states = plan.steps.associate { step ->
            step.id to MutableStepState(
                step = step,
                state = AgentPlanStepExecutionState.VERIFIED,
                outcomeCode = AgentPlanStepOutcomeCode.NONE,
            )
        }.toMutableMap()
        var revision = 0L
        var planState = AgentPlanExecutionState.VERIFIED

        suspend fun persist(): AgentPlanCheckpoint = snapshot(
            plan = plan,
            revision = revision,
            planState = planState,
            states = states,
        ).also { checkpoint -> checkpointSink.store(checkpoint) }

        persist()

        if (plan.steps.any { step -> step.risk != AgentPlanRisk.READ_ONLY }) {
            states.values.forEach { state ->
                state.state = AgentPlanStepExecutionState.REFUSED
                state.outcomeCode = AgentPlanStepOutcomeCode.PLAN_RISK_NOT_SUPPORTED
            }
            planState = AgentPlanExecutionState.REFUSED_UNSUPPORTED_RISK
            revision++
            return AgentPlanExecutionResult.Refused(
                code = AgentPlanRefusalCode.UNSUPPORTED_STEP_RISK,
                checkpoint = persist(),
            )
        }

        if (clock() >= plan.expiresAtEpochMillis) {
            expireUnfinished(states)
            planState = AgentPlanExecutionState.EXPIRED
            revision++
            return AgentPlanExecutionResult.Refused(
                code = AgentPlanRefusalCode.PLAN_EXPIRED,
                checkpoint = persist(),
            )
        }

        try {
            while (states.values.any { state -> !state.state.isTerminal() }) {
                if (clock() >= plan.expiresAtEpochMillis) {
                    expireUnfinished(states)
                    planState = AgentPlanExecutionState.EXPIRED
                    revision++
                    return AgentPlanExecutionResult.Refused(
                        code = AgentPlanRefusalCode.PLAN_EXPIRED,
                        checkpoint = persist(),
                    )
                }

                val blocked = blockFailedDependants(states)
                if (blocked) {
                    planState = AgentPlanExecutionState.RUNNING
                    revision++
                    persist()
                }

                val ready = states.values
                    .asSequence()
                    .filter { state -> state.state == AgentPlanStepExecutionState.VERIFIED }
                    .filter { state ->
                        state.step.dependsOn.all { dependency ->
                            states.getValue(dependency).state == AgentPlanStepExecutionState.SUCCEEDED
                        }
                    }
                    .sortedBy { state -> state.step.id }
                    .take(limits.maxParallelReads)
                    .toList()
                if (ready.isEmpty()) break

                ready.forEach { state ->
                    state.state = AgentPlanStepExecutionState.RUNNING
                    state.outcomeCode = AgentPlanStepOutcomeCode.NONE
                }
                planState = AgentPlanExecutionState.RUNNING
                revision++
                persist()

                val outcomes = coroutineScope {
                    ready.map { state ->
                        async {
                            state.step.id to runReadStep(
                                step = state.step,
                                expiresAtEpochMillis = plan.expiresAtEpochMillis,
                            )
                        }
                    }.awaitAll()
                }.toMap()
                var expiredDuringDispatch = false
                ready.forEach { state ->
                    when (outcomes.getValue(state.step.id)) {
                        InternalReadOutcome.SUCCEEDED -> {
                            state.state = AgentPlanStepExecutionState.SUCCEEDED
                            state.outcomeCode = AgentPlanStepOutcomeCode.READ_COMPLETED
                        }

                        InternalReadOutcome.REFUSED -> {
                            state.state = AgentPlanStepExecutionState.REFUSED
                            state.outcomeCode = AgentPlanStepOutcomeCode.READ_REFUSED
                        }

                        InternalReadOutcome.FAILED -> {
                            state.state = AgentPlanStepExecutionState.FAILED
                            state.outcomeCode = AgentPlanStepOutcomeCode.RUNNER_FAILED
                        }

                        InternalReadOutcome.EXPIRED -> {
                            state.state = AgentPlanStepExecutionState.EXPIRED
                            state.outcomeCode = AgentPlanStepOutcomeCode.PLAN_EXPIRED
                            expiredDuringDispatch = true
                        }
                    }
                }
                if (expiredDuringDispatch) {
                    expireUnfinished(states)
                    planState = AgentPlanExecutionState.EXPIRED
                    revision++
                    return AgentPlanExecutionResult.Refused(
                        code = AgentPlanRefusalCode.PLAN_EXPIRED,
                        checkpoint = persist(),
                    )
                }
                revision++
                persist()
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                states.values.filter { state -> !state.state.isTerminal() }.forEach { state ->
                    state.state = AgentPlanStepExecutionState.CANCELLED
                    state.outcomeCode = AgentPlanStepOutcomeCode.EXECUTION_CANCELLED
                }
                planState = AgentPlanExecutionState.CANCELLED
                revision++
                persist()
            }
            throw cancelled
        }

        planState = if (states.values.all { state ->
                state.state == AgentPlanStepExecutionState.SUCCEEDED
            }
        ) {
            AgentPlanExecutionState.COMPLETED
        } else {
            AgentPlanExecutionState.COMPLETED_WITH_ISSUES
        }
        revision++
        return AgentPlanExecutionResult.Completed(persist())
    }

    private suspend fun runReadStep(
        step: VerifiedAgentPlanStep,
        expiresAtEpochMillis: Long,
    ): InternalReadOutcome = try {
        if (clock() >= expiresAtEpochMillis) return InternalReadOutcome.EXPIRED
        when (runner.run(step)) {
            AgentPlanReadOutcome.SUCCEEDED -> InternalReadOutcome.SUCCEEDED
            AgentPlanReadOutcome.REFUSED -> InternalReadOutcome.REFUSED
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InternalReadOutcome.FAILED
    }

    private fun blockFailedDependants(
        states: MutableMap<AgentPlanStepId, MutableStepState>,
    ): Boolean {
        var anyChanged = false
        var changed: Boolean
        do {
            changed = false
            states.values
                .filter { state -> state.state == AgentPlanStepExecutionState.VERIFIED }
                .sortedBy { state -> state.step.id }
                .forEach { state ->
                    if (state.step.dependsOn.any { dependency ->
                            val dependencyState = states.getValue(dependency).state
                            dependencyState.isTerminal() &&
                                dependencyState != AgentPlanStepExecutionState.SUCCEEDED
                        }
                    ) {
                        state.state = AgentPlanStepExecutionState.BLOCKED
                        state.outcomeCode = AgentPlanStepOutcomeCode.DEPENDENCY_NOT_SUCCEEDED
                        changed = true
                        anyChanged = true
                    }
                }
        } while (changed)
        return anyChanged
    }

    private fun expireUnfinished(states: MutableMap<AgentPlanStepId, MutableStepState>) {
        states.values.filter { state -> !state.state.isTerminal() }.forEach { state ->
            state.state = AgentPlanStepExecutionState.EXPIRED
            state.outcomeCode = AgentPlanStepOutcomeCode.PLAN_EXPIRED
        }
    }

    private fun snapshot(
        plan: VerifiedAgentPlan,
        revision: Long,
        planState: AgentPlanExecutionState,
        states: Map<AgentPlanStepId, MutableStepState>,
    ): AgentPlanCheckpoint = AgentPlanCheckpoint(
        planId = plan.id,
        planDigest = plan.planDigest,
        revision = revision,
        executionState = planState,
        steps = Collections.unmodifiableList(
            states.values.sortedBy { state -> state.step.id }.map { state ->
                AgentPlanStepCheckpoint(
                    id = state.step.id,
                    toolName = state.step.toolName,
                    argumentDigest = state.step.argumentDigest,
                    risk = state.step.risk,
                    dependsOn = Collections.unmodifiableList(state.step.dependsOn.sorted()),
                    state = state.state,
                    outcomeCode = state.outcomeCode,
                )
            },
        ),
    )

    private fun AgentPlanStepExecutionState.isTerminal(): Boolean = when (this) {
        AgentPlanStepExecutionState.VERIFIED,
        AgentPlanStepExecutionState.RUNNING,
        -> false

        AgentPlanStepExecutionState.SUCCEEDED,
        AgentPlanStepExecutionState.REFUSED,
        AgentPlanStepExecutionState.FAILED,
        AgentPlanStepExecutionState.BLOCKED,
        AgentPlanStepExecutionState.EXPIRED,
        AgentPlanStepExecutionState.CANCELLED,
        -> true
    }

    private data class MutableStepState(
        val step: VerifiedAgentPlanStep,
        var state: AgentPlanStepExecutionState,
        var outcomeCode: AgentPlanStepOutcomeCode,
    )

    private enum class InternalReadOutcome {
        SUCCEEDED,
        REFUSED,
        FAILED,
        EXPIRED,
    }
}
