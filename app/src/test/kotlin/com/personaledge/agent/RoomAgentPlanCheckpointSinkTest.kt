package com.personaledge.agent

import com.personaledge.core.agent.AgentPlanCheckpoint
import com.personaledge.core.agent.AgentPlanDigest
import com.personaledge.core.agent.AgentPlanExecutionState
import com.personaledge.core.agent.AgentPlanId
import com.personaledge.core.agent.AgentPlanRisk
import com.personaledge.core.agent.AgentPlanStepCheckpoint
import com.personaledge.core.agent.AgentPlanStepExecutionState
import com.personaledge.core.agent.AgentPlanStepId
import com.personaledge.core.agent.AgentPlanStepOutcomeCode
import com.personaledge.core.agent.AgentPlanToolName
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomAgentPlanCheckpointSinkTest {
    @Test
    fun sparseModelStepIdsMapToStableBoundedStorageOrdinals() {
        val low = step(id = 10, toolName = "calendar_query", digestCharacter = 'b')
        val high = step(id = 10_000, toolName = "web_search", digestCharacter = 'c')

        val fromUnsortedSteps = checkpoint(listOf(high, low)).toStoredAgentPlanCheckpoint()
        val fromSortedSteps = checkpoint(listOf(low, high)).toStoredAgentPlanCheckpoint()

        assertEquals(fromSortedSteps, fromUnsortedSteps)
        assertEquals(listOf(1, 2), fromUnsortedSteps.steps.map { step -> step.ordinal })
        assertEquals(
            listOf("calendar_query", "web_search"),
            fromUnsortedSteps.steps.map { step -> step.toolId },
        )
        assertEquals(
            listOf("b".repeat(64), "c".repeat(64)),
            fromUnsortedSteps.steps.map { step -> step.argumentDigest },
        )
    }

    private fun checkpoint(steps: List<AgentPlanStepCheckpoint>) = AgentPlanCheckpoint(
        planId = requireNotNull(
            AgentPlanId.parse("00000000-0000-0000-0000-000000000001"),
        ),
        planDigest = digest('a'),
        revision = 0L,
        executionState = AgentPlanExecutionState.VERIFIED,
        steps = steps,
    )

    private fun step(
        id: Int,
        toolName: String,
        digestCharacter: Char,
    ) = AgentPlanStepCheckpoint(
        id = AgentPlanStepId(id),
        toolName = requireNotNull(AgentPlanToolName.parse(toolName)),
        argumentDigest = digest(digestCharacter),
        risk = AgentPlanRisk.READ_ONLY,
        dependsOn = emptyList(),
        state = AgentPlanStepExecutionState.VERIFIED,
        outcomeCode = AgentPlanStepOutcomeCode.NONE,
    )

    private fun digest(character: Char): AgentPlanDigest =
        requireNotNull(AgentPlanDigest.parse(character.toString().repeat(64)))
}
