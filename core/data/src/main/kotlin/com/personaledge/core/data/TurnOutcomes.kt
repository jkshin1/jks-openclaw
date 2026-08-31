package com.personaledge.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.withTransaction

/** Durable, content-free state for deciding whether an interrupted turn may be resumed safely. */
enum class TurnOutcomeState {
    STARTED,
    READ_EXECUTED,
    /** Side-effect authorization passed; execution is blocked until this state is durable. */
    WRITE_PENDING,
    ANSWER_COMPLETE,
    FAILED,
    CANCELLED,
    WRITE_UNKNOWN,
}

enum class TurnRecoverability {
    NONE,
    /** Re-run the original read through all current consent and interlock checks. */
    REQUERY_READ,
    /** Never replay; show the owner how to inspect the external target instead. */
    VERIFY_EXTERNAL_STATE,
}

enum class TurnToolRisk {
    NONE,
    READ_ONLY,
    LOCAL_WRITE,
    DATA_WRITE,
    COMMUNICATION,
    HIGH_RISK,
}

/** Closed failure classification. Exception text and provider payloads are never stored. */
enum class TurnOutcomeFailureCode {
    INVALID_MODEL_SEQUENCE,
    CONTEXT_BUDGET_EXCEEDED,
    TOOL_FAILURE,
    DEADLINE_EXCEEDED,
    USER_CANCELLED,
    THERMAL_CANCELLED,
    LIFECYCLE_CANCELLED,
    UNKNOWN,
}

@Entity(
    tableName = "turn_outcomes",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "user_message_ordinal"], unique = true),
        Index(value = ["conversation_id", "recoverability", "updated_at_epoch_millis"]),
    ],
)
data class TurnOutcomeEntity(
    @PrimaryKey
    @ColumnInfo(name = "turn_id")
    val turnId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "user_message_ordinal")
    val userMessageOrdinal: Long,
    @ColumnInfo(name = "recovery_source_user_message_ordinal")
    val recoverySourceUserMessageOrdinal: Long? = null,
    @ColumnInfo(name = "state")
    val state: TurnOutcomeState,
    @ColumnInfo(name = "recoverability")
    val recoverability: TurnRecoverability,
    @ColumnInfo(name = "failure_code")
    val failureCode: TurnOutcomeFailureCode? = null,
    @ColumnInfo(name = "last_tool_name")
    val lastToolName: String? = null,
    @ColumnInfo(name = "last_tool_risk")
    val lastToolRisk: TurnToolRisk = TurnToolRisk.NONE,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "expires_at_epoch_millis")
    val expiresAtEpochMillis: Long,
)

/**
 * Content-free identity of a trusted READ_ONLY execution.
 *
 * Tool arguments and results deliberately have no columns. The ordinal is controller-authored,
 * bounded, and part of the primary key so one model turn cannot substitute a different Tool at an
 * already accepted position.
 */
@Entity(
    tableName = "turn_read_executions",
    primaryKeys = ["turn_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = TurnOutcomeEntity::class,
            parentColumns = ["turn_id"],
            childColumns = ["turn_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["turn_id"])],
)
data class TurnReadExecutionEntity(
    @ColumnInfo(name = "turn_id")
    val turnId: String,
    @ColumnInfo(name = "ordinal")
    val ordinal: Int,
    @ColumnInfo(name = "tool_name")
    val toolName: String,
) {
    init {
        require(TURN_ID_PATTERN.matches(turnId))
        require(ordinal in 1..MAX_READ_EXECUTIONS)
        require(TOOL_NAME_PATTERN.matches(toolName))
    }
}

/**
 * Durable owner-verification obligation, intentionally independent from transcript foreign keys.
 *
 * There is no expiry column and no conversation/turn foreign key. Deleting chat history or
 * garbage-collecting an expired turn therefore cannot erase an uncertain side effect.
 */
@Entity(
    tableName = "unresolved_side_effects",
    indices = [
        Index(value = ["conversation_id", "recorded_at_epoch_millis"]),
        Index(value = ["recorded_at_epoch_millis"]),
    ],
)
data class UnresolvedSideEffectEntity(
    @PrimaryKey
    @ColumnInfo(name = "turn_id")
    val turnId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "state")
    val state: TurnOutcomeState,
    @ColumnInfo(name = "tool_name")
    val toolName: String,
    @ColumnInfo(name = "tool_risk")
    val toolRisk: TurnToolRisk,
    @ColumnInfo(name = "recorded_at_epoch_millis")
    val recordedAtEpochMillis: Long,
) {
    init {
        require(TURN_ID_PATTERN.matches(turnId))
        require(conversationId.isNotBlank())
        require(state == TurnOutcomeState.WRITE_PENDING || state == TurnOutcomeState.WRITE_UNKNOWN)
        require(TOOL_NAME_PATTERN.matches(toolName))
        require(toolRisk.isSideEffect())
        require(recordedAtEpochMillis >= 0L)
    }
}

data class TurnOutcomeWithRequest(
    @Embedded val outcome: TurnOutcomeEntity,
    @ColumnInfo(name = "user_request") val userRequest: String,
)

class TurnRecoveryRecord(
    val turnId: String,
    val conversationId: String,
    val userRequest: String,
    val state: TurnOutcomeState,
    val recoverability: TurnRecoverability,
    val failureCode: TurnOutcomeFailureCode?,
    val lastToolName: String?,
    val lastToolRisk: TurnToolRisk,
    val updatedAtEpochMillis: Long,
    expectedReadTools: List<String> = emptyList(),
) {
    /** Exact ordered multiset; duplicate Tool names at different ordinals remain significant. */
    val expectedReadTools: List<String> = expectedReadTools.toList()

    init {
        require(expectedReadTools.size <= MAX_READ_EXECUTIONS)
        require(expectedReadTools.all(TOOL_NAME_PATTERN::matches))
        require(
            recoverability != TurnRecoverability.REQUERY_READ || expectedReadTools.isNotEmpty(),
        )
    }

    /** Never render [userRequest], even when this object reaches diagnostics accidentally. */
    override fun toString(): String =
        "TurnRecoveryRecord(" +
            "turnId=$turnId, conversationId=$conversationId, state=$state, " +
            "recoverability=$recoverability, failureCode=$failureCode, " +
            "lastToolName=$lastToolName, lastToolRisk=$lastToolRisk, " +
            "updatedAtEpochMillis=$updatedAtEpochMillis, " +
            "expectedReadTools=$expectedReadTools)"
}

/** Closed outcome supplied by the app when atomically committing a Tool receipt. */
enum class TurnToolCommitOutcome {
    READ_COMPLETED,
    WRITE_COMPLETED,
    WRITE_REFUSED,
}

/**
 * Owns the recovery capsule state machine.
 *
 * The capsule stores no prompt or Tool result. It points to the already user-owned transcript row
 * and records only closed execution metadata. A read may be performed again, never continued from
 * an old payload. Any observed side-effecting Tool permanently changes the capsule to verify-only.
 */
class TurnOutcomeRepository(
    private val database: PersonalEdgeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.turnOutcomeDao()
    private val messageDao = database.messageDao()

    suspend fun start(
        turnId: String,
        conversationId: String,
        userMessageOrdinal: Long,
    ): Boolean {
        if (!validTurnId(turnId) || conversationId.isBlank() || userMessageOrdinal <= 0L) {
            return false
        }
        val now = clock()
        return runCatching {
            dao.insert(
                TurnOutcomeEntity(
                    turnId = turnId,
                    conversationId = conversationId,
                    userMessageOrdinal = userMessageOrdinal,
                    state = TurnOutcomeState.STARTED,
                    // Natural-language classification is not proof of a read-only turn. A
                    // verified READ_ONLY Tool execution is what upgrades this state later.
                    recoverability = TurnRecoverability.NONE,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    expiresAtEpochMillis = saturatedAdd(now, RECOVERY_TTL_MILLIS),
                ),
            )
            true
        }.getOrDefault(false)
    }

    /** Starts a new turn whose interrupted read must recover from an earlier owner USER row. */
    suspend fun startContextualRead(
        turnId: String,
        conversationId: String,
        userMessageOrdinal: Long,
        recoverySourceUserMessageOrdinal: Long,
    ): Boolean {
        if (
            !validTurnId(turnId) ||
            conversationId.isBlank() ||
            userMessageOrdinal <= 0L ||
            recoverySourceUserMessageOrdinal <= 0L ||
            recoverySourceUserMessageOrdinal >= userMessageOrdinal
        ) {
            return false
        }
        return runCatching {
            database.withTransaction {
                val currentRequest = messageDao.find(conversationId, userMessageOrdinal)
                val recoverySource = messageDao.find(
                    conversationId,
                    recoverySourceUserMessageOrdinal,
                )
                if (
                    currentRequest?.role != MessageRole.USER ||
                    recoverySource?.role != MessageRole.USER
                ) {
                    return@withTransaction false
                }
                dao.insert(
                    newStartedOutcome(
                        turnId = turnId,
                        conversationId = conversationId,
                        userMessageOrdinal = userMessageOrdinal,
                        recoverySourceUserMessageOrdinal = recoverySourceUserMessageOrdinal,
                        now = clock(),
                    ),
                )
                true
            }
        }.getOrDefault(false)
    }

    /** Atomically replaces a claimed read-recovery capsule with its newly persisted successor. */
    suspend fun startSuccessor(
        predecessorTurnId: String,
        turnId: String,
        conversationId: String,
        userMessageOrdinal: Long,
    ): Boolean {
        if (
            !validTurnId(predecessorTurnId) ||
            !validTurnId(turnId) ||
            predecessorTurnId == turnId ||
            conversationId.isBlank() ||
            userMessageOrdinal <= 0L
        ) {
            return false
        }
        return runCatching {
            database.withTransaction {
                val now = clock()
                val predecessor = dao.find(predecessorTurnId) ?: return@withTransaction false
                if (
                    predecessor.conversationId != conversationId ||
                    predecessor.recoverability != TurnRecoverability.REQUERY_READ ||
                    predecessor.expiresAtEpochMillis <= now
                ) {
                    return@withTransaction false
                }
                val expectedReads = dao.listReadExecutions(predecessorTurnId)
                if (!validReadExecutions(predecessorTurnId, expectedReads)) {
                    return@withTransaction false
                }
                dao.insert(
                    newStartedOutcome(
                        turnId = turnId,
                        conversationId = conversationId,
                        userMessageOrdinal = userMessageOrdinal,
                        recoverySourceUserMessageOrdinal =
                            predecessor.recoverySourceUserMessageOrdinal
                                ?: predecessor.userMessageOrdinal,
                        now = now,
                    ).copy(
                        // A successor is still recoverable if the process dies before its first
                        // fresh Tool call. STARTED distinguishes that state from an executed read.
                        recoverability = TurnRecoverability.REQUERY_READ,
                    ),
                )
                expectedReads.forEach { expected ->
                    dao.insertReadExecution(expected.copy(turnId = turnId))
                }
                check(dao.delete(predecessorTurnId) > 0) { "Recovery predecessor delete failed." }
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Durably moves a turn to verify-only before a side-effecting ledger claim or Tool call.
     * Returning false is an execution veto, not a recoverable metadata warning.
     */
    suspend fun armSideEffect(
        turnId: String,
        toolName: String,
        risk: TurnToolRisk,
    ): Boolean {
        if (
            !validTurnId(turnId) ||
            !validToolName(toolName) ||
            risk == TurnToolRisk.NONE ||
            risk == TurnToolRisk.READ_ONLY
        ) {
            return false
        }
        return runCatching {
            database.withTransaction {
                val current = dao.find(turnId) ?: return@withTransaction false
                if (
                    current.state != TurnOutcomeState.STARTED &&
                    current.state != TurnOutcomeState.READ_EXECUTED
                ) {
                    // One capsule can name exactly one side effect for owner verification. Once a
                    // write is pending/observed (or the turn is terminal), another side effect
                    // would make recovery ambiguous, so execution must stop before ledger claim.
                    return@withTransaction false
                }
                val now = clock()
                val next = current.copy(
                    state = TurnOutcomeState.WRITE_PENDING,
                    recoverability = TurnRecoverability.VERIFY_EXTERNAL_STATE,
                    failureCode = null,
                    lastToolName = toolName,
                    lastToolRisk = risk,
                    updatedAtEpochMillis = now,
                )
                dao.insertUnresolvedSideEffect(
                    UnresolvedSideEffectEntity(
                        turnId = current.turnId,
                        conversationId = current.conversationId,
                        state = TurnOutcomeState.WRITE_PENDING,
                        toolName = toolName,
                        toolRisk = risk,
                        recordedAtEpochMillis = now,
                    ),
                )
                check(dao.update(next) > 0) { "Side-effect recovery update failed." }
                dao.deleteReadExecutions(turnId)
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Compatibility entry point for writes, whose identity is already durably armed separately.
     * A read without a trusted controller ordinal fails closed until its caller is upgraded.
     */
    suspend fun recordTool(turnId: String, toolName: String, risk: TurnToolRisk): Boolean {
        if (risk == TurnToolRisk.READ_ONLY) return false
        return recordToolInternal(turnId, toolName, risk, trustedOrdinal = null)
    }

    suspend fun recordTool(
        turnId: String,
        toolName: String,
        risk: TurnToolRisk,
        trustedOrdinal: Int,
    ): Boolean = recordToolInternal(turnId, toolName, risk, trustedOrdinal)

    private suspend fun recordToolInternal(
        turnId: String,
        toolName: String,
        risk: TurnToolRisk,
        trustedOrdinal: Int?,
    ): Boolean {
        if (!validTurnId(turnId) || !validToolName(toolName) || risk == TurnToolRisk.NONE) {
            return false
        }
        if (risk == TurnToolRisk.READ_ONLY && trustedOrdinal !in 1..MAX_READ_EXECUTIONS) {
            return false
        }
        return runCatching {
            database.withTransaction {
                val current = dao.find(turnId) ?: return@withTransaction false
                if (
                    current.state == TurnOutcomeState.ANSWER_COMPLETE ||
                    current.state == TurnOutcomeState.FAILED ||
                    current.state == TurnOutcomeState.CANCELLED
                ) {
                    return@withTransaction false
                }
                val now = clock()
                val next = if (
                    (current.state == TurnOutcomeState.WRITE_PENDING ||
                        current.state == TurnOutcomeState.WRITE_UNKNOWN) &&
                    risk == TurnToolRisk.READ_ONLY
                ) {
                    // Never let a later read erase which side effect requires owner verification.
                    current.copy(failureCode = null, updatedAtEpochMillis = now)
                } else if (risk != TurnToolRisk.READ_ONLY) {
                    val unresolved = dao.findUnresolvedSideEffect(turnId)
                        ?: return@withTransaction false
                    if (
                        current.state !in setOf(
                            TurnOutcomeState.WRITE_PENDING,
                            TurnOutcomeState.WRITE_UNKNOWN,
                        ) ||
                        current.lastToolName != toolName ||
                        current.lastToolRisk != risk ||
                        unresolved.conversationId != current.conversationId ||
                        unresolved.toolName != toolName ||
                        unresolved.toolRisk != risk
                    ) {
                        return@withTransaction false
                    }
                    check(
                        dao.markUnresolvedSideEffectUnknown(turnId, toolName, risk, now) > 0,
                    ) { "Unresolved side-effect update failed." }
                    dao.deleteReadExecutions(turnId)
                    current.copy(
                        state = TurnOutcomeState.WRITE_UNKNOWN,
                        recoverability = TurnRecoverability.VERIFY_EXTERNAL_STATE,
                        failureCode = null,
                        lastToolName = toolName,
                        lastToolRisk = risk,
                        updatedAtEpochMillis = now,
                    )
                } else {
                    val ordinal = checkNotNull(trustedOrdinal)
                    val expectedReads = dao.listReadExecutions(turnId)
                    if (!validStoredReadPrefix(turnId, expectedReads)) {
                        return@withTransaction false
                    }
                    val existingAtOrdinal = expectedReads.firstOrNull { it.ordinal == ordinal }
                    if (existingAtOrdinal != null && existingAtOrdinal.toolName != toolName) {
                        // A recovery may never swap calendar_query for alarm_next (or any other
                        // Tool) while retaining the predecessor's trusted ordinal.
                        return@withTransaction false
                    }
                    if (existingAtOrdinal == null) {
                        if (ordinal != expectedReads.size + 1) return@withTransaction false
                        dao.insertReadExecution(
                            TurnReadExecutionEntity(turnId, ordinal, toolName),
                        )
                    }
                    current.copy(
                        state = TurnOutcomeState.READ_EXECUTED,
                        // Recovery successors are hard-restricted to READ_ONLY Tools. Therefore a
                        // trusted completed read, not prompt wording, is sufficient evidence.
                        recoverability = TurnRecoverability.REQUERY_READ,
                        failureCode = null,
                        lastToolName = toolName,
                        lastToolRisk = risk,
                        updatedAtEpochMillis = now,
                    )
                }
                dao.update(next) > 0
            }
        }.getOrDefault(false)
    }

    /** A typed refusal proves the currently armed side effect did not occur. */
    suspend fun recordWriteRefused(turnId: String, toolName: String): Boolean {
        if (!validTurnId(turnId) || !validToolName(toolName)) return false
        return runCatching {
            database.withTransaction {
                val current = dao.find(turnId) ?: return@withTransaction false
                if (
                    current.state != TurnOutcomeState.WRITE_PENDING ||
                    current.lastToolName != toolName
                ) {
                    // A prior WRITE_UNKNOWN must never be cleared by a later refusal.
                    return@withTransaction current.state == TurnOutcomeState.WRITE_UNKNOWN
                }
                val unresolved = dao.findUnresolvedSideEffect(turnId)
                if (
                    unresolved == null ||
                    unresolved.conversationId != current.conversationId ||
                    unresolved.toolName != toolName ||
                    unresolved.toolRisk != current.lastToolRisk
                ) {
                    return@withTransaction false
                }
                check(dao.update(
                    current.copy(
                        state = TurnOutcomeState.FAILED,
                        recoverability = TurnRecoverability.NONE,
                        failureCode = null,
                        lastToolName = null,
                        lastToolRisk = TurnToolRisk.NONE,
                        updatedAtEpochMillis = clock(),
                    ),
                ) > 0) { "Write-refusal recovery update failed." }
                check(dao.deleteUnresolvedSideEffect(turnId) > 0) {
                    "Write-refusal unresolved delete failed."
                }
                dao.deleteReadExecutions(turnId)
                true
            }
        }.getOrDefault(false)
    }

    suspend fun fail(
        turnId: String,
        cancelled: Boolean,
        failureCode: TurnOutcomeFailureCode,
    ): Boolean {
        if (!validTurnId(turnId)) return false
        return runCatching {
            database.withTransaction {
                val current = dao.find(turnId) ?: return@withTransaction false
                val nextState = when (current.state) {
                    TurnOutcomeState.READ_EXECUTED,
                    TurnOutcomeState.WRITE_PENDING,
                    TurnOutcomeState.WRITE_UNKNOWN,
                    -> current.state
                    TurnOutcomeState.STARTED,
                    TurnOutcomeState.FAILED,
                    TurnOutcomeState.CANCELLED,
                    -> if (cancelled) TurnOutcomeState.CANCELLED else TurnOutcomeState.FAILED
                    TurnOutcomeState.ANSWER_COMPLETE -> return@withTransaction false
                }
                dao.update(
                    current.copy(
                        state = nextState,
                        failureCode = failureCode,
                        updatedAtEpochMillis = clock(),
                    ),
                ) > 0
            }
        }.getOrDefault(false)
    }

    /** Complete is a terminal marker; expiry GC removes it after recovery queries can exclude it. */
    suspend fun complete(turnId: String): Boolean {
        if (!validTurnId(turnId)) return false
        return runCatching {
            database.withTransaction {
                val current = dao.find(turnId) ?: return@withTransaction false
                dao.update(
                    current.copy(
                        state = TurnOutcomeState.ANSWER_COMPLETE,
                        recoverability = TurnRecoverability.NONE,
                        failureCode = null,
                        updatedAtEpochMillis = clock(),
                    ),
                ).also { updated ->
                    if (updated > 0) dao.deleteReadExecutions(turnId)
                } > 0
            }
        }.getOrDefault(false)
    }

    suspend fun latestRecovery(conversationId: String): TurnRecoveryRecord? {
        if (conversationId.isBlank()) return null
        return runCatching {
            database.withTransaction {
                val now = clock()
                dao.deleteExpired(now)
                dao.latestUnresolvedSideEffect(conversationId)?.toRecoveryRecord()
                    ?: dao.latestRecovery(conversationId, now)?.toRecord(
                        readExecutions = { turnId -> dao.listReadExecutions(turnId) },
                    )
            }
        }.getOrNull()
    }

    suspend fun recovery(turnId: String, conversationId: String): TurnRecoveryRecord? {
        if (!validTurnId(turnId) || conversationId.isBlank()) return null
        return runCatching {
            database.withTransaction {
                dao.findUnresolvedSideEffect(turnId)
                    ?.takeIf { unresolved -> unresolved.conversationId == conversationId }
                    ?.toRecoveryRecord()
                    ?: dao.recoveryByTurnId(turnId, conversationId, clock())?.toRecord(
                        readExecutions = { recoveryTurnId ->
                            dao.listReadExecutions(recoveryTurnId)
                        },
                    )
            }
        }.getOrNull()
    }

    /** Global content-free obligations, including rows whose transcript has been deleted. */
    suspend fun unresolvedSideEffects(limit: Int = MAX_UNRESOLVED_SIDE_EFFECTS): List<TurnRecoveryRecord> =
        runCatching {
            dao.listUnresolvedSideEffects(limit.coerceIn(1, MAX_UNRESOLVED_SIDE_EFFECTS))
                .map(UnresolvedSideEffectEntity::toRecoveryRecord)
        }.getOrDefault(emptyList())

    /** Closes only a verify-only capsule after the owner explicitly checked the external target. */
    suspend fun dismissVerification(turnId: String, conversationId: String): Boolean {
        if (!validTurnId(turnId) || conversationId.isBlank()) return false
        return runCatching {
            database.withTransaction {
                val unresolved = dao.findUnresolvedSideEffect(turnId)
                    ?: return@withTransaction false
                if (unresolved.conversationId != conversationId) {
                    return@withTransaction false
                }
                check(dao.deleteUnresolvedSideEffect(turnId) > 0) {
                    "Explicit unresolved side-effect dismiss failed."
                }
                // This row may already be gone through TTL or transcript cascade. If it remains,
                // explicit owner dismissal is also authority to close its transient capsule.
                dao.delete(turnId)
                true
            }
        }.getOrDefault(false)
    }

    private fun validTurnId(value: String): Boolean = TURN_ID_PATTERN.matches(value)

    private fun validToolName(value: String): Boolean = TOOL_NAME_PATTERN.matches(value)

    private fun validReadExecutions(
        turnId: String,
        executions: List<TurnReadExecutionEntity>,
    ): Boolean = executions.isNotEmpty() && validStoredReadPrefix(turnId, executions)

    private fun validStoredReadPrefix(
        turnId: String,
        executions: List<TurnReadExecutionEntity>,
    ): Boolean =
        executions.size <= MAX_READ_EXECUTIONS &&
            executions.map(TurnReadExecutionEntity::ordinal) == (1..executions.size).toList() &&
            executions.all { execution ->
                execution.turnId == turnId && validToolName(execution.toolName)
            }

    companion object {
        const val RECOVERY_TTL_DAYS = 7L
        const val RECOVERY_TTL_MILLIS = RECOVERY_TTL_DAYS * 24L * 60L * 60L * 1_000L
        private const val MAX_UNRESOLVED_SIDE_EFFECTS = 100
    }

    private fun newStartedOutcome(
        turnId: String,
        conversationId: String,
        userMessageOrdinal: Long,
        now: Long,
        recoverySourceUserMessageOrdinal: Long? = null,
    ) = TurnOutcomeEntity(
        turnId = turnId,
        conversationId = conversationId,
        userMessageOrdinal = userMessageOrdinal,
        recoverySourceUserMessageOrdinal = recoverySourceUserMessageOrdinal,
        state = TurnOutcomeState.STARTED,
        recoverability = TurnRecoverability.NONE,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
        expiresAtEpochMillis = saturatedAdd(now, RECOVERY_TTL_MILLIS),
    )
}

private suspend fun TurnOutcomeWithRequest.toRecord(
    readExecutions: suspend (String) -> List<TurnReadExecutionEntity>,
): TurnRecoveryRecord? {
    val expectedReadTools = when (outcome.recoverability) {
        TurnRecoverability.REQUERY_READ -> {
            val executions = readExecutions(outcome.turnId)
            if (
                executions.isEmpty() ||
                executions.size > MAX_READ_EXECUTIONS ||
                executions.map(TurnReadExecutionEntity::ordinal) !=
                (1..executions.size).toList()
            ) {
                return null
            }
            executions.map(TurnReadExecutionEntity::toolName)
        }
        TurnRecoverability.NONE,
        TurnRecoverability.VERIFY_EXTERNAL_STATE,
        -> emptyList()
    }
    return TurnRecoveryRecord(
        turnId = outcome.turnId,
        conversationId = outcome.conversationId,
        userRequest = userRequest,
        state = outcome.state,
        recoverability = outcome.recoverability,
        failureCode = outcome.failureCode,
        lastToolName = outcome.lastToolName,
        lastToolRisk = outcome.lastToolRisk,
        updatedAtEpochMillis = outcome.updatedAtEpochMillis,
        expectedReadTools = expectedReadTools,
    )
}

internal const val MAX_READ_EXECUTIONS = 4
internal val TURN_ID_PATTERN =
    Regex("turn-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
internal val TOOL_NAME_PATTERN = Regex("[a-z][a-z0-9_]{0,63}")

private fun TurnToolRisk.isSideEffect(): Boolean =
    this != TurnToolRisk.NONE && this != TurnToolRisk.READ_ONLY

private fun UnresolvedSideEffectEntity.toRecoveryRecord() = TurnRecoveryRecord(
    turnId = turnId,
    conversationId = conversationId,
    // Side-effect verification never needs transcript content.
    userRequest = "",
    state = state,
    recoverability = TurnRecoverability.VERIFY_EXTERNAL_STATE,
    failureCode = null,
    lastToolName = toolName,
    lastToolRisk = toolRisk,
    updatedAtEpochMillis = recordedAtEpochMillis,
    expectedReadTools = emptyList(),
)

private fun saturatedAdd(first: Long, second: Long): Long =
    if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second
