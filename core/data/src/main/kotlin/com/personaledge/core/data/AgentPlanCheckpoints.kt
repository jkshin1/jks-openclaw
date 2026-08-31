package com.personaledge.core.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import java.util.UUID

enum class StoredAgentPlanExecutionState {
    VERIFIED,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_ISSUES,
    REFUSED_UNSUPPORTED_RISK,
    EXPIRED,
    CANCELLED,
}

enum class StoredAgentPlanStepState {
    VERIFIED,
    RUNNING,
    SUCCEEDED,
    REFUSED,
    FAILED,
    BLOCKED,
    EXPIRED,
    CANCELLED,
}

enum class StoredAgentPlanStepOutcome {
    NONE,
    READ_COMPLETED,
    READ_REFUSED,
    RUNNER_FAILED,
    DEPENDENCY_NOT_SUCCEEDED,
    PLAN_RISK_NOT_SUPPORTED,
    PLAN_EXPIRED,
    EXECUTION_CANCELLED,
}

data class StoredAgentPlanStepCheckpoint(
    val ordinal: Int,
    val toolId: String,
    val argumentDigest: String,
    val state: StoredAgentPlanStepState,
    val outcome: StoredAgentPlanStepOutcome,
) {
    init {
        require(ordinal in 1..MAX_STEPS)
        require(validToolId(toolId))
        require(validDigest(argumentDigest))
    }
}

/**
 * Content-free durable snapshot. Its closed constructor cannot represent prompt, argument,
 * provider result, resource value, or exception text fields.
 */
data class StoredAgentPlanCheckpoint(
    val schemaVersion: Int,
    val planId: String,
    val planDigest: String,
    val revision: Long,
    val executionState: StoredAgentPlanExecutionState,
    val steps: List<StoredAgentPlanStepCheckpoint>,
) {
    init {
        require(schemaVersion == CHECKPOINT_SCHEMA_VERSION)
        require(validPlanId(planId))
        require(validDigest(planDigest))
        require(revision >= 0L)
        require(steps.size in 1..MAX_STEPS)
        require(steps.map(StoredAgentPlanStepCheckpoint::ordinal).distinct().size == steps.size)
        require(steps == steps.sortedBy(StoredAgentPlanStepCheckpoint::ordinal))
    }
}

@Entity(tableName = "agent_plan_checkpoints")
data class AgentPlanCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "plan_id")
    val planId: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "plan_digest")
    val planDigest: String,
    @ColumnInfo(name = "revision")
    val revision: Long,
    @ColumnInfo(name = "execution_state")
    val executionState: String,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "agent_plan_step_checkpoints",
    primaryKeys = ["plan_id", "step_ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = AgentPlanCheckpointEntity::class,
            parentColumns = ["plan_id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["plan_id"])],
)
data class AgentPlanStepCheckpointEntity(
    @ColumnInfo(name = "plan_id")
    val planId: String,
    @ColumnInfo(name = "step_ordinal")
    val stepOrdinal: Int,
    @ColumnInfo(name = "tool_id")
    val toolId: String,
    @ColumnInfo(name = "argument_digest")
    val argumentDigest: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "outcome")
    val outcome: String,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Dao
interface AgentPlanCheckpointDao {
    @Query("SELECT * FROM agent_plan_checkpoints WHERE plan_id = :planId")
    suspend fun findPlan(planId: String): AgentPlanCheckpointEntity?

    @Query(
        "SELECT * FROM agent_plan_step_checkpoints WHERE plan_id = :planId " +
            "ORDER BY step_ordinal",
    )
    suspend fun findSteps(planId: String): List<AgentPlanStepCheckpointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replacePlan(entity: AgentPlanCheckpointEntity)

    @Query("DELETE FROM agent_plan_step_checkpoints WHERE plan_id = :planId")
    suspend fun deleteSteps(planId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSteps(steps: List<AgentPlanStepCheckpointEntity>)

    @Query("DELETE FROM agent_plan_checkpoints WHERE updated_at_epoch_millis < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query(
        "SELECT plan_id FROM agent_plan_checkpoints " +
            "WHERE execution_state IN ('VERIFIED', 'RUNNING') " +
            "ORDER BY updated_at_epoch_millis, plan_id LIMIT :limit",
    )
    suspend fun listInterruptedPlanIds(limit: Int): List<String>

    @Query(
        "DELETE FROM agent_plan_checkpoints WHERE plan_id != :retainedPlanId AND plan_id IN (" +
            "SELECT plan_id FROM agent_plan_checkpoints WHERE plan_id != :retainedPlanId " +
            "ORDER BY updated_at_epoch_millis DESC, plan_id LIMIT -1 OFFSET :keepOtherCount)",
    )
    suspend fun deleteOverflow(retainedPlanId: String, keepOtherCount: Int): Int
}

class AgentPlanCheckpointRepository(
    private val database: PersonalEdgeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.agentPlanCheckpointDao()

    /**
     * Stores a monotonically revised snapshot atomically. An equal conflicting revision is
     * refused, so concurrent/stale writers cannot rewrite already durable execution history.
     */
    suspend fun store(checkpoint: StoredAgentPlanCheckpoint): Boolean = database.withTransaction {
        val existing = dao.findPlan(checkpoint.planId)
        if (existing != null && existing.revision > checkpoint.revision) {
            return@withTransaction false
        }
        if (existing != null) {
            val current = existing.toStored(dao.findSteps(checkpoint.planId))
            if (existing.revision == checkpoint.revision) {
                return@withTransaction current == checkpoint
            }
            if (!validSuccessor(current, checkpoint)) return@withTransaction false
        }

        val now = clock()
        require(now >= 0L)
        dao.replacePlan(checkpoint.toEntity(now))
        dao.deleteSteps(checkpoint.planId)
        dao.insertSteps(checkpoint.steps.sortedBy(StoredAgentPlanStepCheckpoint::ordinal).map { step ->
            step.toEntity(checkpoint.planId, now)
        })
        dao.deleteOlderThan((now - RETENTION_MILLIS).coerceAtLeast(0L))
        dao.deleteOverflow(checkpoint.planId, MAX_RETAINED_PLANS - 1)
        true
    }

    suspend fun latest(planId: String): StoredAgentPlanCheckpoint? {
        if (!validPlanId(planId)) return null
        return database.withTransaction {
            val plan = dao.findPlan(planId) ?: return@withTransaction null
            plan.toStored(dao.findSteps(planId))
        }
    }

    /**
     * Process-death reconciliation. It never resumes or dispatches a Tool: a durable VERIFIED or
     * RUNNING snapshot is atomically advanced to CANCELLED, while already terminal step evidence
     * remains unchanged.
     */
    suspend fun closeInterrupted(planId: String): Boolean {
        if (!validPlanId(planId)) return false
        return database.withTransaction {
            val plan = dao.findPlan(planId) ?: return@withTransaction false
            val currentState = StoredAgentPlanExecutionState.valueOf(plan.executionState)
            if (currentState != StoredAgentPlanExecutionState.VERIFIED &&
                currentState != StoredAgentPlanExecutionState.RUNNING
            ) {
                return@withTransaction false
            }
            val nextRevision = runCatching { Math.addExact(plan.revision, 1L) }
                .getOrNull() ?: return@withTransaction false
            val now = clock()
            require(now >= 0L)
            val steps = dao.findSteps(planId).map { step ->
                val state = StoredAgentPlanStepState.valueOf(step.state)
                if (state == StoredAgentPlanStepState.VERIFIED ||
                    state == StoredAgentPlanStepState.RUNNING
                ) {
                    step.copy(
                        state = StoredAgentPlanStepState.CANCELLED.name,
                        outcome = StoredAgentPlanStepOutcome.EXECUTION_CANCELLED.name,
                        updatedAtEpochMillis = now,
                    )
                } else {
                    step.copy(updatedAtEpochMillis = now)
                }
            }
            dao.replacePlan(
                plan.copy(
                    revision = nextRevision,
                    executionState = StoredAgentPlanExecutionState.CANCELLED.name,
                    updatedAtEpochMillis = now,
                ),
            )
            dao.deleteSteps(planId)
            dao.insertSteps(steps)
            true
        }
    }

    /** Bounded startup hook; each returned plan is atomically cancelled and never resumed. */
    suspend fun closeInterruptedPlans(limit: Int = MAX_RETAINED_PLANS): Int {
        val boundedLimit = limit.coerceIn(1, MAX_RETAINED_PLANS)
        return dao.listInterruptedPlanIds(boundedLimit).count { planId ->
            closeInterrupted(planId)
        }
    }

    private fun validSuccessor(
        current: StoredAgentPlanCheckpoint,
        next: StoredAgentPlanCheckpoint,
    ): Boolean {
        if (current.executionState.isTerminal() ||
            next.revision <= current.revision ||
            next.schemaVersion != current.schemaVersion ||
            next.planDigest != current.planDigest ||
            next.steps.map(StoredAgentPlanStepCheckpoint::ordinal) !=
            current.steps.map(StoredAgentPlanStepCheckpoint::ordinal)
        ) {
            return false
        }
        return current.steps.zip(next.steps).all { (before, after) ->
            before.ordinal == after.ordinal &&
                before.toolId == after.toolId &&
                before.argumentDigest == after.argumentDigest &&
                (!before.state.isTerminal() || before == after)
        }
    }

    private companion object {
        const val MAX_RETAINED_PLANS = 64
        const val RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}

private fun StoredAgentPlanExecutionState.isTerminal(): Boolean = when (this) {
    StoredAgentPlanExecutionState.VERIFIED,
    StoredAgentPlanExecutionState.RUNNING,
    -> false
    StoredAgentPlanExecutionState.COMPLETED,
    StoredAgentPlanExecutionState.COMPLETED_WITH_ISSUES,
    StoredAgentPlanExecutionState.REFUSED_UNSUPPORTED_RISK,
    StoredAgentPlanExecutionState.EXPIRED,
    StoredAgentPlanExecutionState.CANCELLED,
    -> true
}

private fun StoredAgentPlanStepState.isTerminal(): Boolean = when (this) {
    StoredAgentPlanStepState.VERIFIED,
    StoredAgentPlanStepState.RUNNING,
    -> false
    StoredAgentPlanStepState.SUCCEEDED,
    StoredAgentPlanStepState.REFUSED,
    StoredAgentPlanStepState.FAILED,
    StoredAgentPlanStepState.BLOCKED,
    StoredAgentPlanStepState.EXPIRED,
    StoredAgentPlanStepState.CANCELLED,
    -> true
}

private fun StoredAgentPlanCheckpoint.toEntity(now: Long): AgentPlanCheckpointEntity =
    AgentPlanCheckpointEntity(
        planId = planId,
        schemaVersion = schemaVersion,
        planDigest = planDigest,
        revision = revision,
        executionState = executionState.name,
        updatedAtEpochMillis = now,
    )

private fun StoredAgentPlanStepCheckpoint.toEntity(
    planId: String,
    now: Long,
): AgentPlanStepCheckpointEntity = AgentPlanStepCheckpointEntity(
    planId = planId,
    stepOrdinal = ordinal,
    toolId = toolId,
    argumentDigest = argumentDigest,
    state = state.name,
    outcome = outcome.name,
    updatedAtEpochMillis = now,
)

private fun AgentPlanCheckpointEntity.toStored(
    steps: List<AgentPlanStepCheckpointEntity>,
): StoredAgentPlanCheckpoint = StoredAgentPlanCheckpoint(
    schemaVersion = schemaVersion,
    planId = planId,
    planDigest = planDigest,
    revision = revision,
    executionState = StoredAgentPlanExecutionState.valueOf(executionState),
    steps = steps.map { step ->
        check(step.planId == planId)
        StoredAgentPlanStepCheckpoint(
            ordinal = step.stepOrdinal,
            toolId = step.toolId,
            argumentDigest = step.argumentDigest,
            state = StoredAgentPlanStepState.valueOf(step.state),
            outcome = StoredAgentPlanStepOutcome.valueOf(step.outcome),
        )
    },
)

private fun validPlanId(value: String): Boolean = runCatching { UUID.fromString(value) }
    .getOrNull()
    ?.toString() == value

private fun validToolId(value: String): Boolean =
    value.length <= 64 && TOOL_ID.matches(value)

private fun validDigest(value: String): Boolean = SHA256.matches(value)

private const val CHECKPOINT_SCHEMA_VERSION = 1
private const val MAX_STEPS = 4
private val TOOL_ID = Regex("[a-z][a-z0-9_]*")
private val SHA256 = Regex("[0-9a-f]{64}")
