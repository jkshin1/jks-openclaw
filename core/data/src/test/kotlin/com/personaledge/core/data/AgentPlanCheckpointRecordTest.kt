package com.personaledge.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanCheckpointRecordTest {
    @Test
    fun `closed checkpoint contract contains only content free metadata fields`() {
        assertEquals(
            setOf("schemaVersion", "planId", "planDigest", "revision", "executionState", "steps"),
            StoredAgentPlanCheckpoint::class.java.declaredFields.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("ordinal", "toolId", "argumentDigest", "state", "outcome"),
            StoredAgentPlanStepCheckpoint::class.java.declaredFields.map { it.name }.toSet(),
        )
        val names = (
            StoredAgentPlanCheckpoint::class.java.declaredFields +
                StoredAgentPlanStepCheckpoint::class.java.declaredFields
            ).map { it.name.lowercase() }
        listOf("prompt", "argumentjson", "result", "provider", "text", "exception").forEach { forbidden ->
            assertFalse(names.any { name -> forbidden in name })
        }
    }

    @Test
    fun `identities digests ordering and four step bound fail closed`() {
        val step = validStep(1)
        assertTrue(runCatching { validCheckpoint(steps = listOf(validStep(2), step)) }.isFailure)
        assertTrue(runCatching { validCheckpoint(steps = (1..5).map(::validStep)) }.isFailure)
        assertTrue(
            runCatching {
                validCheckpoint().copy(planId = "not-a-plan-id")
            }.isFailure,
        )
        assertTrue(runCatching { step.copy(toolId = "Write Tool") }.isFailure)
        assertTrue(runCatching { step.copy(argumentDigest = "A".repeat(64)) }.isFailure)
    }

    private fun validCheckpoint(
        steps: List<StoredAgentPlanStepCheckpoint> = listOf(validStep(1)),
    ): StoredAgentPlanCheckpoint = StoredAgentPlanCheckpoint(
        schemaVersion = 1,
        planId = "00000000-0000-0000-0000-000000000001",
        planDigest = "a".repeat(64),
        revision = 0,
        executionState = StoredAgentPlanExecutionState.VERIFIED,
        steps = steps,
    )

    private fun validStep(ordinal: Int): StoredAgentPlanStepCheckpoint =
        StoredAgentPlanStepCheckpoint(
            ordinal = ordinal,
            toolId = "calendar_query",
            argumentDigest = "b".repeat(64),
            state = StoredAgentPlanStepState.VERIFIED,
            outcome = StoredAgentPlanStepOutcome.NONE,
        )
}
