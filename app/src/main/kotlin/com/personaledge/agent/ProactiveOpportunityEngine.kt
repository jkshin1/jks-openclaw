package com.personaledge.agent

import com.personaledge.core.data.CommitmentProposalDraft
import com.personaledge.core.data.CommitmentProposalRepository
import com.personaledge.core.data.ProposalStoreResult
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.ZoneId
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Closed local domains. Every domain is disabled unless the owner explicitly enables it. */
enum class OpportunityDomain {
    COMMITMENT_REVIEW,
    LEAVE_BY_REVIEW,
    REMINDER_REVIEW,
}

/**
 * Owner policy for deterministic proposal generation.
 *
 * The empty enabled set is intentional: merely constructing the engine cannot create a row.
 */
data class OpportunityDomainPolicy(
    val enabledDomains: Set<OpportunityDomain> = emptySet(),
    val maxProposalsPerWindow: Int = DEFAULT_MAX_PROPOSALS_PER_WINDOW,
    val rateWindowMillis: Long = DEFAULT_RATE_WINDOW_MILLIS,
) {
    init {
        require(maxProposalsPerWindow in 1..MAX_PROPOSALS_PER_WINDOW)
        require(rateWindowMillis in MIN_RATE_WINDOW_MILLIS..MAX_RATE_WINDOW_MILLIS)
    }

    internal fun snapshot(): OpportunityDomainPolicy = copy(
        enabledDomains = Collections.unmodifiableSet(enabledDomains.toSet()),
    )

    companion object {
        const val DEFAULT_MAX_PROPOSALS_PER_WINDOW = 3
        const val MAX_PROPOSALS_PER_WINDOW = 12
        const val DEFAULT_RATE_WINDOW_MILLIS = 24L * 60L * 60L * 1_000L
        const val MIN_RATE_WINDOW_MILLIS = 60L * 60L * 1_000L
        const val MAX_RATE_WINDOW_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}

/** Content-free identity supplied by the local owner of an event. */
@JvmInline
value class OpportunityEventId(val value: String) {
    init {
        require(value.isSha256Hex())
    }
}

/**
 * Closed, typed, local-only inputs.
 *
 * There is deliberately no notification body, search snippet, provider response, destination, or
 * calendar title field. Producers reduce those sources to a content-free event ID and typed time
 * before crossing this boundary.
 */
sealed interface LocalOpportunityEvent {
    val eventId: OpportunityEventId
    val observedAtEpochMillis: Long
    val expiresAtEpochMillis: Long

    data class CommitmentReview(
        override val eventId: OpportunityEventId,
        override val observedAtEpochMillis: Long,
        override val expiresAtEpochMillis: Long,
        val suggestedAtEpochMillis: Long? = null,
        val zoneId: String? = null,
    ) : LocalOpportunityEvent {
        init {
            validateEventWindow(observedAtEpochMillis, expiresAtEpochMillis)
            validateSuggestion(observedAtEpochMillis, suggestedAtEpochMillis, zoneId)
        }
    }

    data class LeaveByReview(
        override val eventId: OpportunityEventId,
        override val observedAtEpochMillis: Long,
        override val expiresAtEpochMillis: Long,
        val leaveAtEpochMillis: Long,
        val zoneId: String,
    ) : LocalOpportunityEvent {
        init {
            validateEventWindow(observedAtEpochMillis, expiresAtEpochMillis)
            validateSuggestion(observedAtEpochMillis, leaveAtEpochMillis, zoneId)
        }
    }

    data class ReminderReview(
        override val eventId: OpportunityEventId,
        override val observedAtEpochMillis: Long,
        override val expiresAtEpochMillis: Long,
        val suggestedAtEpochMillis: Long? = null,
        val zoneId: String? = null,
    ) : LocalOpportunityEvent {
        init {
            validateEventWindow(observedAtEpochMillis, expiresAtEpochMillis)
            validateSuggestion(observedAtEpochMillis, suggestedAtEpochMillis, zoneId)
        }
    }
}

/** Stable, bounded IDs only; no user or provider text is encoded in these values. */
enum class OpportunityRuleId(val wireId: String) {
    COMMITMENT_REVIEW_V1("commitment_review_v1"),
    LEAVE_BY_REVIEW_V1("leave_by_review_v1"),
    REMINDER_REVIEW_V1("reminder_review_v1"),
}

enum class OpportunityReasonId(val wireId: String) {
    COMMITMENT_NEEDS_OWNER_REVIEW_V1("commitment_needs_owner_review_v1"),
    LEAVE_TIME_NEEDS_OWNER_REVIEW_V1("leave_time_needs_owner_review_v1"),
    REMINDER_NEEDS_OWNER_REVIEW_V1("reminder_needs_owner_review_v1"),
}

data class OpportunityProvenance(
    val eventId: OpportunityEventId,
    val ruleId: OpportunityRuleId,
    val reasonId: OpportunityReasonId,
    val sourceRefHash: String,
    val sourceId: String,
) {
    init {
        require(sourceRefHash.isSha256Hex())
        require(sourceId.length in 1..MAX_SOURCE_ID_CHARACTERS)
        require(sourceId.all { character ->
            character in 'a'..'z' || character in '0'..'9' || character == '.' || character == '_'
        })
    }

    companion object {
        const val MAX_SOURCE_ID_CHARACTERS = 120
    }
}

/** Fixed-template review candidate. It has no API capable of executing a reminder or Tool. */
data class OpportunityProposal internal constructor(
    val provenance: OpportunityProvenance,
    val proposedDueAtEpochMillis: Long?,
    val zoneId: String?,
    val confidence: Int,
    val expiresAtEpochMillis: Long,
) {
    val summary: String
        get() = definitionFor(provenance.ruleId).summary
}

enum class OpportunityDecisionCode {
    PROPOSED,
    DOMAIN_DISABLED,
    DUPLICATE,
    EXPIRED,
    NOT_YET_OBSERVED,
    RATE_LIMITED,
}

data class OpportunityDecision(
    val provenance: OpportunityProvenance,
    val code: OpportunityDecisionCode,
    val proposal: OpportunityProposal? = null,
) {
    init {
        require((code == OpportunityDecisionCode.PROPOSED) == (proposal != null))
    }
}

data class OpportunityEvaluation(val decisions: List<OpportunityDecision>) {
    val proposals: List<OpportunityProposal>
        get() = decisions.mapNotNull(OpportunityDecision::proposal)
}

data class OpportunityHistoryEntry(
    val sourceRefHash: String,
    val createdAtEpochMillis: Long,
) {
    init {
        require(sourceRefHash.isSha256Hex())
        require(createdAtEpochMillis >= 0L)
    }
}

/** Bounded, content-free repository history needed for dedupe and rate limiting. */
data class OpportunityHistory(
    val entries: List<OpportunityHistoryEntry> = emptyList(),
) {
    init {
        require(entries.size <= MAX_HISTORY_ITEMS)
    }

    companion object {
        const val MAX_HISTORY_ITEMS = 2_200
    }
}

/**
 * Pure proposal kernel. It has no repository, scheduler, network, platform, or Tool dependency.
 */
class ProactiveOpportunityEngine {
    fun evaluate(
        events: List<LocalOpportunityEvent>,
        policy: OpportunityDomainPolicy = OpportunityDomainPolicy(),
        history: OpportunityHistory = OpportunityHistory(),
        nowEpochMillis: Long,
    ): OpportunityEvaluation {
        require(nowEpochMillis >= 0L)
        require(events.size <= MAX_EVENTS_PER_EVALUATION)
        val policySnapshot = policy.snapshot()
        val seen = history.entries.mapTo(mutableSetOf()) { it.sourceRefHash }
        var proposalsInWindow = history.entries.count { entry ->
            val createdAt = entry.createdAtEpochMillis
            createdAt <= nowEpochMillis && nowEpochMillis - createdAt < policySnapshot.rateWindowMillis
        }
        val decisions = events
            .sortedWith(
                compareBy<LocalOpportunityEvent>(
                    { event -> event.observedAtEpochMillis },
                    { event -> event.eventId.value },
                ),
            )
            .map { event ->
                val definition = definitionFor(event)
                val provenance = provenanceFor(event, definition)
                val code = when {
                    definition.domain !in policySnapshot.enabledDomains ->
                        OpportunityDecisionCode.DOMAIN_DISABLED
                    event.observedAtEpochMillis > nowEpochMillis ->
                        OpportunityDecisionCode.NOT_YET_OBSERVED
                    event.expiresAtEpochMillis <= nowEpochMillis -> OpportunityDecisionCode.EXPIRED
                    provenance.sourceRefHash in seen -> OpportunityDecisionCode.DUPLICATE
                    proposalsInWindow >= policySnapshot.maxProposalsPerWindow ->
                        OpportunityDecisionCode.RATE_LIMITED
                    else -> OpportunityDecisionCode.PROPOSED
                }
                if (code == OpportunityDecisionCode.PROPOSED) {
                    seen += provenance.sourceRefHash
                    proposalsInWindow += 1
                    OpportunityDecision(
                        provenance = provenance,
                        code = code,
                        proposal = proposalFor(event, definition, provenance),
                    )
                } else {
                    OpportunityDecision(provenance = provenance, code = code)
                }
            }
        return OpportunityEvaluation(decisions)
    }

    companion object {
        const val MAX_EVENTS_PER_EVALUATION = 64
    }
}

enum class OpportunityStoreCode {
    STORED,
    ALREADY_HANDLED,
    CAPACITY_REACHED,
    EXPIRED,
    INVALID,
    STORAGE_UNAVAILABLE,
}

data class OpportunityStoreReceipt(
    val provenance: OpportunityProvenance,
    val code: OpportunityStoreCode,
)

sealed interface OpportunityRunResult {
    data class Completed(
        val evaluation: OpportunityEvaluation,
        val stores: List<OpportunityStoreReceipt>,
    ) : OpportunityRunResult

    /** History could not be read, so no proposal write was attempted. */
    data object StorageUnavailable : OpportunityRunResult
}

/**
 * The only production mutation adapter: it can offer a row to the existing review Inbox and
 * nothing else. Existing ReminderCoordinator dismiss/promote paths then retain owner authority.
 */
class RepositoryProactiveOpportunityAdapter internal constructor(
    private val engine: ProactiveOpportunityEngine,
    private val clock: () -> Long,
    private val loadHistory: suspend () -> OpportunityHistory,
    private val offerDraft: suspend (CommitmentProposalDraft) -> ProposalStoreResult,
) {
    constructor(
        repository: CommitmentProposalRepository,
        engine: ProactiveOpportunityEngine = ProactiveOpportunityEngine(),
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        engine = engine,
        clock = clock,
        loadHistory = {
            val recent = repository.recentHistory(
                windowMillis = OpportunityDomainPolicy.MAX_RATE_WINDOW_MILLIS,
                limit = OpportunityHistory.MAX_HISTORY_ITEMS,
            )
            OpportunityHistory(
                entries = recent.map { proposal ->
                    OpportunityHistoryEntry(
                        sourceRefHash = proposal.sourceRefHash,
                        createdAtEpochMillis = proposal.createdAtEpochMillis,
                    )
                },
            )
        },
        offerDraft = repository::offer,
    )

    private val runMutex = Mutex()

    suspend fun evaluateAndOffer(
        events: List<LocalOpportunityEvent>,
        policy: OpportunityDomainPolicy = OpportunityDomainPolicy(),
    ): OpportunityRunResult = runMutex.withLock {
        val now = clock()
        // Default-off is stronger than a write gate: it does not even read the proposal store.
        if (policy.enabledDomains.isEmpty()) {
            return@withLock OpportunityRunResult.Completed(
                evaluation = engine.evaluate(events, policy, OpportunityHistory(), now),
                stores = emptyList(),
            )
        }
        val history = try {
            loadHistory()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return@withLock OpportunityRunResult.StorageUnavailable
        }
        val evaluation = engine.evaluate(events, policy, history, now)
        val stores = evaluation.proposals.map { proposal ->
            val storeCode = when {
                !isTrustedProposal(proposal) -> OpportunityStoreCode.INVALID
                clock() >= proposal.expiresAtEpochMillis -> OpportunityStoreCode.EXPIRED
                else -> try {
                    when (offerDraft(proposal.toDraft())) {
                        is ProposalStoreResult.Stored -> OpportunityStoreCode.STORED
                        ProposalStoreResult.AlreadyHandled -> OpportunityStoreCode.ALREADY_HANDLED
                        ProposalStoreResult.CapacityReached -> OpportunityStoreCode.CAPACITY_REACHED
                        ProposalStoreResult.Invalid -> OpportunityStoreCode.INVALID
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    OpportunityStoreCode.STORAGE_UNAVAILABLE
                }
            }
            OpportunityStoreReceipt(proposal.provenance, storeCode)
        }
        OpportunityRunResult.Completed(evaluation = evaluation, stores = stores)
    }

    private fun OpportunityProposal.toDraft(): CommitmentProposalDraft = CommitmentProposalDraft(
        summary = summary,
        proposedDueAtEpochMillis = proposedDueAtEpochMillis,
        zoneId = zoneId,
        sourcePackage = provenance.sourceId,
        sourceRefHash = provenance.sourceRefHash,
        confidence = confidence,
    )
}

private data class OpportunityRuleDefinition(
    val domain: OpportunityDomain,
    val ruleId: OpportunityRuleId,
    val reasonId: OpportunityReasonId,
    val summary: String,
    val confidence: Int,
)

private fun definitionFor(event: LocalOpportunityEvent): OpportunityRuleDefinition = when (event) {
    is LocalOpportunityEvent.CommitmentReview -> definitionFor(
        OpportunityRuleId.COMMITMENT_REVIEW_V1,
    )
    is LocalOpportunityEvent.LeaveByReview -> definitionFor(OpportunityRuleId.LEAVE_BY_REVIEW_V1)
    is LocalOpportunityEvent.ReminderReview -> definitionFor(
        OpportunityRuleId.REMINDER_REVIEW_V1,
    )
}

private fun definitionFor(ruleId: OpportunityRuleId): OpportunityRuleDefinition = when (ruleId) {
    OpportunityRuleId.COMMITMENT_REVIEW_V1 -> OpportunityRuleDefinition(
        domain = OpportunityDomain.COMMITMENT_REVIEW,
        ruleId = ruleId,
        reasonId = OpportunityReasonId.COMMITMENT_NEEDS_OWNER_REVIEW_V1,
        summary = "확정하지 않은 일정 후보를 검토해 주세요.",
        confidence = 70,
    )
    OpportunityRuleId.LEAVE_BY_REVIEW_V1 -> OpportunityRuleDefinition(
        domain = OpportunityDomain.LEAVE_BY_REVIEW,
        ruleId = ruleId,
        reasonId = OpportunityReasonId.LEAVE_TIME_NEEDS_OWNER_REVIEW_V1,
        summary = "출발 시각 제안을 검토해 주세요.",
        confidence = 85,
    )
    OpportunityRuleId.REMINDER_REVIEW_V1 -> OpportunityRuleDefinition(
        domain = OpportunityDomain.REMINDER_REVIEW,
        ruleId = ruleId,
        reasonId = OpportunityReasonId.REMINDER_NEEDS_OWNER_REVIEW_V1,
        summary = "기존 리마인더의 후속 조치를 검토해 주세요.",
        confidence = 75,
    )
}

private fun provenanceFor(
    event: LocalOpportunityEvent,
    definition: OpportunityRuleDefinition,
): OpportunityProvenance {
    val sourceId = "com.personaledge.opportunity.${definition.ruleId.wireId}." +
        definition.reasonId.wireId
    return OpportunityProvenance(
        eventId = event.eventId,
        ruleId = definition.ruleId,
        reasonId = definition.reasonId,
        sourceRefHash = provenanceDigest(
            eventId = event.eventId.value,
            ruleId = definition.ruleId.wireId,
            reasonId = definition.reasonId.wireId,
        ),
        sourceId = sourceId,
    )
}

private fun proposalFor(
    event: LocalOpportunityEvent,
    definition: OpportunityRuleDefinition,
    provenance: OpportunityProvenance,
): OpportunityProposal {
    val (dueAt, zoneId) = when (event) {
        is LocalOpportunityEvent.CommitmentReview ->
            event.suggestedAtEpochMillis to event.zoneId
        is LocalOpportunityEvent.LeaveByReview -> event.leaveAtEpochMillis to event.zoneId
        is LocalOpportunityEvent.ReminderReview -> event.suggestedAtEpochMillis to event.zoneId
    }
    return OpportunityProposal(
        provenance = provenance,
        proposedDueAtEpochMillis = dueAt,
        zoneId = zoneId,
        confidence = definition.confidence,
        expiresAtEpochMillis = event.expiresAtEpochMillis,
    )
}

private fun isTrustedProposal(proposal: OpportunityProposal): Boolean {
    val definition = definitionFor(proposal.provenance.ruleId)
    if (proposal.provenance.reasonId != definition.reasonId) return false
    if (proposal.provenance.sourceId !=
        "com.personaledge.opportunity.${definition.ruleId.wireId}.${definition.reasonId.wireId}"
    ) return false
    if (proposal.provenance.sourceRefHash != provenanceDigest(
            proposal.provenance.eventId.value,
            definition.ruleId.wireId,
            definition.reasonId.wireId,
        )
    ) return false
    if (proposal.confidence != definition.confidence) return false
    if ((proposal.proposedDueAtEpochMillis == null) != (proposal.zoneId == null)) return false
    return proposal.zoneId == null || isValidZone(proposal.zoneId)
}

private fun validateEventWindow(observedAtEpochMillis: Long, expiresAtEpochMillis: Long) {
    require(observedAtEpochMillis >= 0L)
    require(expiresAtEpochMillis > observedAtEpochMillis)
    require(expiresAtEpochMillis - observedAtEpochMillis <= MAX_EVENT_LIFETIME_MILLIS)
}

private fun validateSuggestion(observedAtEpochMillis: Long, dueAt: Long?, zoneId: String?) {
    require((dueAt == null) == (zoneId == null))
    if (dueAt == null) return
    require(dueAt > observedAtEpochMillis)
    require(dueAt - observedAtEpochMillis <= MAX_SUGGESTION_FUTURE_MILLIS)
    require(isValidZone(requireNotNull(zoneId)))
}

private fun isValidZone(zoneId: String): Boolean = try {
    ZoneId.of(zoneId).id == zoneId
} catch (_: DateTimeException) {
    false
}

private fun provenanceDigest(eventId: String, ruleId: String, reasonId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(PROVENANCE_DOMAIN, eventId, ruleId, reasonId).forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            ),
        )
        digest.update(bytes)
    }
    return digest.digest().toLowerHex()
}

private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    this@toLowerHex.forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        append(HEX[unsigned ushr 4])
        append(HEX[unsigned and 0x0f])
    }
}

private fun String.isSha256Hex(): Boolean = length == SHA256_HEX_CHARACTERS && all { character ->
    character in '0'..'9' || character in 'a'..'f'
}

private const val SHA256_HEX_CHARACTERS = 64
private const val HEX = "0123456789abcdef"
private const val PROVENANCE_DOMAIN = "com.personaledge/proactive-opportunity-provenance/v1"
private const val MAX_EVENT_LIFETIME_MILLIS = 7L * 24L * 60L * 60L * 1_000L
private const val MAX_SUGGESTION_FUTURE_MILLIS = 5L * 366L * 24L * 60L * 60L * 1_000L
