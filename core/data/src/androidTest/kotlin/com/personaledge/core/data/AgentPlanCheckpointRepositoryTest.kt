package com.personaledge.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentPlanCheckpointRepositoryTest {
    private lateinit var database: PersonalEdgeDatabase
    private lateinit var repository: AgentPlanCheckpointRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PersonalEdgeDatabase::class.java).build()
        repository = AgentPlanCheckpointRepository(database, clock = { 50_000L })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun monotonicSnapshotIsDurableIdempotentAndRejectsConflicts() = runBlocking {
        val verified = checkpoint(revision = 0, StoredAgentPlanExecutionState.VERIFIED)
        assertTrue(repository.store(verified))
        assertTrue(repository.store(verified))
        assertEquals(verified, repository.latest(verified.planId))

        val completed = checkpoint(revision = 2, StoredAgentPlanExecutionState.COMPLETED)
        assertTrue(repository.store(completed))
        assertFalse(repository.store(verified))
        assertFalse(
            repository.store(
                completed.copy(executionState = StoredAgentPlanExecutionState.CANCELLED),
            ),
        )
        assertFalse(
            repository.store(
                verified.copy(
                    revision = 3,
                    planDigest = "c".repeat(64),
                    executionState = StoredAgentPlanExecutionState.RUNNING,
                ),
            ),
        )
        assertEquals(completed, repository.latest(completed.planId))
    }

    @Test
    fun interruptedSnapshotClosesAtomicallyAndIsNeverMarkedForResume() = runBlocking {
        val running = checkpoint(revision = 4, StoredAgentPlanExecutionState.RUNNING).copy(
            steps = listOf(
                StoredAgentPlanStepCheckpoint(
                    ordinal = 1,
                    toolId = "calendar_query",
                    argumentDigest = "b".repeat(64),
                    state = StoredAgentPlanStepState.RUNNING,
                    outcome = StoredAgentPlanStepOutcome.NONE,
                ),
            ),
        )
        assertTrue(repository.store(running))

        assertTrue(repository.closeInterrupted(running.planId))
        val closed = requireNotNull(repository.latest(running.planId))
        assertEquals(5L, closed.revision)
        assertEquals(StoredAgentPlanExecutionState.CANCELLED, closed.executionState)
        assertEquals(StoredAgentPlanStepState.CANCELLED, closed.steps.single().state)
        assertEquals(
            StoredAgentPlanStepOutcome.EXECUTION_CANCELLED,
            closed.steps.single().outcome,
        )
        assertFalse(repository.closeInterrupted(running.planId))
    }

    @Test
    fun startupReconciliationIsBoundedAndNeverChangesCompletedPlans() = runBlocking {
        val first = checkpoint(1, StoredAgentPlanExecutionState.VERIFIED)
        val second = checkpoint(1, StoredAgentPlanExecutionState.RUNNING).copy(
            planId = "00000000-0000-0000-0000-000000000002",
        )
        val completed = checkpoint(2, StoredAgentPlanExecutionState.COMPLETED).copy(
            planId = "00000000-0000-0000-0000-000000000003",
        )
        assertTrue(repository.store(first))
        assertTrue(repository.store(second))
        assertTrue(repository.store(completed))

        assertEquals(1, repository.closeInterruptedPlans(limit = 1))
        assertEquals(1, repository.closeInterruptedPlans(limit = 1))
        assertEquals(0, repository.closeInterruptedPlans(limit = 1))
        assertEquals(
            StoredAgentPlanExecutionState.COMPLETED,
            repository.latest(completed.planId)?.executionState,
        )
    }

    @Test
    fun sqliteSchemaHasOnlyBoundedContentFreeColumns() {
        val sqlite = database.openHelper.writableDatabase
        val planColumns = sqlite.query("PRAGMA table_info(agent_plan_checkpoints)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        val stepColumns = sqlite.query("PRAGMA table_info(agent_plan_step_checkpoints)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

        assertEquals(
            setOf(
                "plan_id",
                "schema_version",
                "plan_digest",
                "revision",
                "execution_state",
                "updated_at_epoch_millis",
            ),
            planColumns,
        )
        assertEquals(
            setOf(
                "plan_id",
                "step_ordinal",
                "tool_id",
                "argument_digest",
                "state",
                "outcome",
                "updated_at_epoch_millis",
            ),
            stepColumns,
        )
        assertNotNull(database.agentPlanCheckpointDao())
    }

    private fun checkpoint(
        revision: Long,
        state: StoredAgentPlanExecutionState,
    ): StoredAgentPlanCheckpoint = StoredAgentPlanCheckpoint(
        schemaVersion = 1,
        planId = "00000000-0000-0000-0000-000000000001",
        planDigest = "a".repeat(64),
        revision = revision,
        executionState = state,
        steps = listOf(
            StoredAgentPlanStepCheckpoint(
                ordinal = 1,
                toolId = "calendar_query",
                argumentDigest = "b".repeat(64),
                state = if (state == StoredAgentPlanExecutionState.COMPLETED) {
                    StoredAgentPlanStepState.SUCCEEDED
                } else {
                    StoredAgentPlanStepState.VERIFIED
                },
                outcome = if (state == StoredAgentPlanExecutionState.COMPLETED) {
                    StoredAgentPlanStepOutcome.READ_COMPLETED
                } else {
                    StoredAgentPlanStepOutcome.NONE
                },
            ),
        ),
    )
}
