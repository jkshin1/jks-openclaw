package com.personaledge.agent

import com.personaledge.core.agent.AgentPlanCheckpoint
import com.personaledge.core.agent.AgentPlanCheckpointSink
import com.personaledge.core.agent.AgentPlanExecutionState
import com.personaledge.core.agent.AgentPlanStepExecutionState
import com.personaledge.core.agent.AgentPlanStepOutcomeCode
import com.personaledge.core.data.AgentPlanCheckpointRepository
import com.personaledge.core.data.StoredAgentPlanCheckpoint
import com.personaledge.core.data.StoredAgentPlanExecutionState
import com.personaledge.core.data.StoredAgentPlanStepCheckpoint
import com.personaledge.core.data.StoredAgentPlanStepOutcome
import com.personaledge.core.data.StoredAgentPlanStepState

/** Production adapter whose output type has no fields for prompt, arguments, results, or text. */
internal class RoomAgentPlanCheckpointSink(
    private val repository: AgentPlanCheckpointRepository,
) : AgentPlanCheckpointSink {
    override suspend fun store(checkpoint: AgentPlanCheckpoint) {
        check(repository.store(checkpoint.toStoredAgentPlanCheckpoint())) {
            "AgentPlan checkpoint revision was stale or conflicting."
        }
    }
}

/**
 * Model step IDs are sparse numeric identities (1..10,000); Room ordinals are only the stable
 * 1..4 storage order for this production bridge. Sorting before enumeration keeps that mapping
 * deterministic across every revision without widening or changing the v7 schema.
 */
internal fun AgentPlanCheckpoint.toStoredAgentPlanCheckpoint(): StoredAgentPlanCheckpoint =
    StoredAgentPlanCheckpoint(
        schemaVersion = schemaVersion,
        planId = planId.value,
        planDigest = planDigest.hex,
        revision = revision,
        executionState = executionState.toStored(),
        steps = steps.sortedBy { step -> step.id }.mapIndexed { index, step ->
            StoredAgentPlanStepCheckpoint(
                ordinal = index + 1,
                toolId = step.toolName.value,
                argumentDigest = step.argumentDigest.hex,
                state = step.state.toStored(),
                outcome = step.outcomeCode.toStored(),
            )
        },
    )

private fun AgentPlanExecutionState.toStored(): StoredAgentPlanExecutionState =
    StoredAgentPlanExecutionState.valueOf(name)

private fun AgentPlanStepExecutionState.toStored(): StoredAgentPlanStepState =
    StoredAgentPlanStepState.valueOf(name)

private fun AgentPlanStepOutcomeCode.toStored(): StoredAgentPlanStepOutcome =
    StoredAgentPlanStepOutcome.valueOf(name)
