package com.personaledge.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import java.util.UUID

enum class ProposalStatus {
    PENDING,
    PROMOTED,
    DISMISSED,
}

@Entity(
    tableName = "commitment_proposals",
    indices = [
        Index(value = ["status", "created_at_epoch_millis"]),
        Index(value = ["source_ref_hash"], unique = true),
    ],
)
data class CommitmentProposalEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "summary")
    val summary: String,
    @ColumnInfo(name = "proposed_due_at_epoch_millis")
    val proposedDueAtEpochMillis: Long?,
    @ColumnInfo(name = "zone_id")
    val zoneId: String?,
    @ColumnInfo(name = "source_package")
    val sourcePackage: String?,
    @ColumnInfo(name = "source_ref_hash")
    val sourceRefHash: String,
    @ColumnInfo(name = "confidence")
    val confidence: Int,
    @ColumnInfo(name = "status")
    val status: ProposalStatus,
    @ColumnInfo(name = "promoted_reminder_id")
    val promotedReminderId: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

data class CommitmentProposalDraft(
    val summary: String,
    val proposedDueAtEpochMillis: Long?,
    val zoneId: String?,
    val sourcePackage: String?,
    val sourceRefHash: String,
    val confidence: Int,
)

/** Bounded, content-free history projection for proposal dedupe and fatigue controls. */
data class CommitmentProposalHistoryRecord(
    @ColumnInfo(name = "source_ref_hash")
    val sourceRefHash: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
) {
    init {
        require(
            sourceRefHash.length == CommitmentProposalRepository.SHA256_HEX_CHARACTERS &&
                sourceRefHash.all { character ->
                    CommitmentProposalRepository.isLowerHex(character)
                },
        )
        require(createdAtEpochMillis >= 0L)
    }
}

sealed interface ProposalStoreResult {
    data class Stored(val proposal: CommitmentProposalEntity) : ProposalStoreResult

    data object AlreadyHandled : ProposalStoreResult

    data object CapacityReached : ProposalStoreResult

    data object Invalid : ProposalStoreResult
}

/** Local proposal inbox. Nothing here is allowed to create a reminder or calendar event. */
class CommitmentProposalRepository(
    private val database: PersonalEdgeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.commitmentProposalDao()

    suspend fun offer(draft: CommitmentProposalDraft): ProposalStoreResult {
        val summary = draft.summary.trim().replace(WHITESPACE, " ").takeCodePoints(MAX_SUMMARY_CODE_POINTS)
        if (
            summary.isEmpty() ||
            draft.sourceRefHash.length != SHA256_HEX_CHARACTERS ||
            !draft.sourceRefHash.all(::isLowerHex) ||
            draft.confidence !in MIN_CONFIDENCE..MAX_CONFIDENCE ||
            (draft.proposedDueAtEpochMillis == null) != (draft.zoneId == null)
        ) {
            return ProposalStoreResult.Invalid
        }
        val now = clock()
        if (draft.proposedDueAtEpochMillis != null && draft.proposedDueAtEpochMillis <= now) {
            return ProposalStoreResult.Invalid
        }
        return database.withTransaction {
            // An updated notification must not revive a candidate the user already dismissed or
            // promoted. The source hash is the durable idempotency boundary.
            if (dao.findBySourceRefHash(draft.sourceRefHash) != null) {
                return@withTransaction ProposalStoreResult.AlreadyHandled
            }
            if (dao.countPending() >= MAX_PENDING_PROPOSALS) {
                return@withTransaction ProposalStoreResult.CapacityReached
            }
            val proposal = CommitmentProposalEntity(
                id = idFactory(),
                summary = summary,
                proposedDueAtEpochMillis = draft.proposedDueAtEpochMillis,
                zoneId = draft.zoneId,
                sourcePackage = draft.sourcePackage?.takeCodePoints(MAX_PACKAGE_CODE_POINTS),
                sourceRefHash = draft.sourceRefHash,
                confidence = draft.confidence,
                status = ProposalStatus.PENDING,
                promotedReminderId = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            dao.insert(proposal)
            ProposalStoreResult.Stored(proposal)
        }
    }

    suspend fun pending(limit: Int = DEFAULT_PAGE): List<CommitmentProposalEntity> {
        val now = clock()
        dao.dismissOlderThan(now - RETENTION_MILLIS, now)
        return dao.listPending(limit.coerceIn(1, MAX_PENDING_PROPOSALS))
    }

    /**
     * Returns only source hashes and creation times across every status. The window and row count
     * are clamped so a caller cannot turn this fatigue-control projection into an unbounded export.
     */
    suspend fun recentHistory(
        windowMillis: Long = MAX_RECENT_HISTORY_WINDOW_MILLIS,
        limit: Int = MAX_RECENT_HISTORY,
    ): List<CommitmentProposalHistoryRecord> {
        val now = clock()
        require(now >= 0L)
        val boundedWindow = windowMillis.coerceIn(1L, MAX_RECENT_HISTORY_WINDOW_MILLIS)
        val createdAtOrAfter = if (now >= boundedWindow) now - boundedWindow + 1L else 0L
        return dao.listRecentHistory(
            createdAtOrAfter = createdAtOrAfter,
            createdAtOrBefore = now,
            limit = limit.coerceIn(1, MAX_RECENT_HISTORY),
        )
    }

    suspend fun findPending(id: String): CommitmentProposalEntity? =
        dao.find(id)?.takeIf { it.status == ProposalStatus.PENDING }

    suspend fun dismiss(id: String): Boolean = id.isNotBlank() &&
        dao.transition(id, ProposalStatus.PENDING, ProposalStatus.DISMISSED, null, clock()) > 0

    suspend fun markPromoted(id: String, reminderId: String): Boolean =
        id.isNotBlank() && reminderId.isNotBlank() &&
            dao.transition(
                id,
                ProposalStatus.PENDING,
                ProposalStatus.PROMOTED,
                reminderId,
                clock(),
            ) > 0

    companion object {
        const val MAX_PENDING_PROPOSALS = 100
        const val DEFAULT_PAGE = 50
        const val MAX_SUMMARY_CODE_POINTS = 180
        const val MAX_PACKAGE_CODE_POINTS = 160
        const val MIN_CONFIDENCE = 1
        const val MAX_CONFIDENCE = 100
        const val RETENTION_DAYS = 30L
        const val RETENTION_MILLIS = RETENTION_DAYS * 24L * 60L * 60L * 1_000L
        const val MAX_RECENT_HISTORY = 2_200
        const val MAX_RECENT_HISTORY_WINDOW_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        internal const val SHA256_HEX_CHARACTERS = 64
        private val WHITESPACE = Regex("\\s+")
        internal fun isLowerHex(character: Char): Boolean =
            character in '0'..'9' || character in 'a'..'f'
    }
}
