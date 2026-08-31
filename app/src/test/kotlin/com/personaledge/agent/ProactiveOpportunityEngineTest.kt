package com.personaledge.agent

import com.personaledge.core.data.CommitmentProposalDraft
import com.personaledge.core.data.CommitmentProposalEntity
import com.personaledge.core.data.ProposalStatus
import com.personaledge.core.data.ProposalStoreResult
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveOpportunityEngineTest {
    private val engine = ProactiveOpportunityEngine()
    private val now = 2_000_000_000_000L

    @Test
    fun `every domain is default off and adapter does not access storage`() = runBlocking {
        val events = allDomainEvents()
        var historyReads = 0
        var offers = 0
        val adapter = RepositoryProactiveOpportunityAdapter(
            engine = engine,
            clock = { now },
            loadHistory = {
                historyReads += 1
                OpportunityHistory()
            },
            offerDraft = {
                offers += 1
                ProposalStoreResult.Invalid
            },
        )

        val result = adapter.evaluateAndOffer(events).completed()

        assertEquals(
            List(events.size) { OpportunityDecisionCode.DOMAIN_DISABLED },
            result.evaluation.decisions.map(OpportunityDecision::code),
        )
        assertTrue(result.stores.isEmpty())
        assertEquals(0, historyReads)
        assertEquals(0, offers)
    }

    @Test
    fun `dedupe applies within a batch and against content free history`() {
        val duplicate = commitmentEvent("same")
        val historical = commitmentEvent("historical")
        val historicalHash = engine.evaluate(
            events = listOf(historical),
            policy = commitmentPolicy(),
            nowEpochMillis = now,
        ).proposals.single().provenance.sourceRefHash

        val evaluation = engine.evaluate(
            events = listOf(duplicate, duplicate, historical),
            policy = commitmentPolicy(maxProposals = 3),
            history = OpportunityHistory(
                listOf(OpportunityHistoryEntry(historicalHash, now - MINUTE_MILLIS)),
            ),
            nowEpochMillis = now,
        )

        assertEquals(1, evaluation.proposals.size)
        assertEquals(
            listOf(
                OpportunityDecisionCode.PROPOSED,
                OpportunityDecisionCode.DUPLICATE,
                OpportunityDecisionCode.DUPLICATE,
            ),
            evaluation.decisions.map(OpportunityDecision::code),
        )
    }

    @Test
    fun `expired candidate is rejected and expiry is rechecked immediately before offer`() =
        runBlocking {
            val expired = LocalOpportunityEvent.CommitmentReview(
                eventId = eventId("expired"),
                observedAtEpochMillis = now - MINUTE_MILLIS,
                expiresAtEpochMillis = now,
            )
            assertEquals(
                OpportunityDecisionCode.EXPIRED,
                engine.evaluate(
                    listOf(expired),
                    commitmentPolicy(),
                    nowEpochMillis = now,
                ).decisions.single().code,
            )

            var clockReads = 0
            var offers = 0
            val adapter = RepositoryProactiveOpportunityAdapter(
                engine = engine,
                clock = {
                    clockReads += 1
                    if (clockReads == 1) now else now + EVENT_TTL_MILLIS
                },
                loadHistory = { OpportunityHistory() },
                offerDraft = {
                    offers += 1
                    ProposalStoreResult.Invalid
                },
            )

            val result = adapter.evaluateAndOffer(
                listOf(commitmentEvent("expires-before-offer")),
                commitmentPolicy(),
            ).completed()

            assertEquals(OpportunityStoreCode.EXPIRED, result.stores.single().code)
            assertEquals(0, offers)
        }

    @Test
    fun `rate cap includes recent history and deterministically limits the batch`() {
        val existing = engine.evaluate(
            listOf(commitmentEvent("existing")),
            commitmentPolicy(),
            nowEpochMillis = now,
        ).proposals.single().provenance.sourceRefHash

        val evaluation = engine.evaluate(
            events = listOf(
                commitmentEvent("candidate-c"),
                commitmentEvent("candidate-a"),
                commitmentEvent("candidate-b"),
            ),
            policy = commitmentPolicy(maxProposals = 2),
            history = OpportunityHistory(
                listOf(OpportunityHistoryEntry(existing, now - MINUTE_MILLIS)),
            ),
            nowEpochMillis = now,
        )

        assertEquals(1, evaluation.proposals.size)
        assertEquals(2, evaluation.decisions.count {
            it.code == OpportunityDecisionCode.RATE_LIMITED
        })
        assertEquals(
            evaluation.decisions.sortedBy { decision -> decision.provenance.eventId.value },
            evaluation.decisions,
        )
    }

    @Test
    fun `kernel cannot write and adapter can only offer a fixed local review draft`() = runBlocking {
        val privateMarker = "raw-provider-payload-must-not-cross-the-event-boundary"
        val proposal = engine.evaluate(
            listOf(commitmentEvent(privateMarker)),
            commitmentPolicy(),
            nowEpochMillis = now,
        ).proposals.single()
        var captured: CommitmentProposalDraft? = null
        val adapter = RepositoryProactiveOpportunityAdapter(
            engine = engine,
            clock = { now },
            loadHistory = { OpportunityHistory() },
            offerDraft = { draft ->
                captured = draft
                ProposalStoreResult.AlreadyHandled
            },
        )

        assertEquals("확정하지 않은 일정 후보를 검토해 주세요.", proposal.summary)
        assertFalse(proposal.toString().contains(privateMarker))
        val result = adapter.evaluateAndOffer(
            listOf(commitmentEvent(privateMarker)),
            commitmentPolicy(),
        ).completed()

        assertEquals(OpportunityStoreCode.ALREADY_HANDLED, result.stores.single().code)
        val draft = requireNotNull(captured)
        assertEquals(proposal.summary, draft.summary)
        assertFalse(draft.toString().contains(privateMarker))
        assertNull(draft.proposedDueAtEpochMillis)
        assertNull(draft.zoneId)
        assertTrue(draft.sourcePackage?.startsWith("com.personaledge.opportunity.") == true)
    }

    @Test
    fun `provenance is deterministic domain separated and bounded for every event type`() {
        val events = allDomainEvents()
        val policy = OpportunityDomainPolicy(
            enabledDomains = OpportunityDomain.entries.toSet(),
            maxProposalsPerWindow = OpportunityDomainPolicy.MAX_PROPOSALS_PER_WINDOW,
        )

        val first = engine.evaluate(events, policy, nowEpochMillis = now)
        val second = engine.evaluate(events.reversed(), policy, nowEpochMillis = now)

        assertEquals(first, second)
        assertEquals(events.size, first.proposals.size)
        first.proposals.forEach { proposal ->
            val provenance = proposal.provenance
            assertEquals(64, provenance.eventId.value.length)
            assertEquals(64, provenance.sourceRefHash.length)
            assertTrue(provenance.sourceRefHash.all { it in '0'..'9' || it in 'a'..'f' })
            assertTrue(provenance.ruleId.wireId.length <= 64)
            assertTrue(provenance.reasonId.wireId.length <= 64)
            assertTrue(
                provenance.sourceId.length <= OpportunityProvenance.MAX_SOURCE_ID_CHARACTERS,
            )
            assertFalse(proposal.summary.contains(provenance.eventId.value))
        }
        val sharedEventId = eventId("shared-event")
        val commitmentHash = engine.evaluate(
            listOf(commitmentEvent(sharedEventId)),
            commitmentPolicy(),
            nowEpochMillis = now,
        ).proposals.single().provenance.sourceRefHash
        val reminderHash = engine.evaluate(
            listOf(reminderEvent(sharedEventId)),
            OpportunityDomainPolicy(setOf(OpportunityDomain.REMINDER_REVIEW)),
            nowEpochMillis = now,
        ).proposals.single().provenance.sourceRefHash
        assertNotEquals(commitmentHash, reminderHash)
    }

    @Test
    fun `all status history survives owner action and adapter recreation`() = runBlocking {
        var offered = 0
        val durableAllStatusHistory = mutableListOf<OpportunityHistoryEntry>()
        fun recreatedAdapter() = RepositoryProactiveOpportunityAdapter(
            engine = engine,
            clock = { now },
            // The production repository projection retains this entry after dismiss/promote.
            loadHistory = { OpportunityHistory(durableAllStatusHistory.toList()) },
            offerDraft = { draft ->
                offered += 1
                durableAllStatusHistory += OpportunityHistoryEntry(draft.sourceRefHash, now)
                ProposalStoreResult.Stored(storedEntity("stored-$offered", draft))
            },
        )
        val policy = commitmentPolicy(maxProposals = 1)

        val first = recreatedAdapter().evaluateAndOffer(
            listOf(commitmentEvent("accepted-first")),
            policy,
        ).completed()
        // A fresh adapter represents process recreation after the first row changed status.
        val second = recreatedAdapter().evaluateAndOffer(
            listOf(commitmentEvent("new-after-owner-action")),
            policy,
        ).completed()

        assertEquals(OpportunityStoreCode.STORED, first.stores.single().code)
        assertEquals(OpportunityDecisionCode.RATE_LIMITED, second.evaluation.decisions.single().code)
        assertTrue(second.stores.isEmpty())
        assertEquals(1, offered)
    }

    @Test
    fun `database history failure is fail closed before offer`() = runBlocking {
        var offers = 0
        val adapter = RepositoryProactiveOpportunityAdapter(
            engine = engine,
            clock = { now },
            loadHistory = { error("database unavailable") },
            offerDraft = {
                offers += 1
                ProposalStoreResult.Invalid
            },
        )

        val result = adapter.evaluateAndOffer(
            listOf(commitmentEvent("storage-failure")),
            commitmentPolicy(),
        )

        assertTrue(result is OpportunityRunResult.StorageUnavailable)
        assertEquals(0, offers)
    }

    @Test
    fun `history cancellation propagates without offer`() {
        var offers = 0
        val adapter = RepositoryProactiveOpportunityAdapter(
            engine = engine,
            clock = { now },
            loadHistory = { throw CancellationException("cancelled") },
            offerDraft = {
                offers += 1
                ProposalStoreResult.Invalid
            },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                adapter.evaluateAndOffer(
                    listOf(commitmentEvent("cancelled-history")),
                    commitmentPolicy(),
                )
            }
        }
        assertEquals(0, offers)
    }

    @Test
    fun `content free history is bounded to 2200 rows`() {
        val maximum = List(OpportunityHistory.MAX_HISTORY_ITEMS) { index ->
            OpportunityHistoryEntry(eventId("history-$index").value, now)
        }

        assertEquals(OpportunityHistory.MAX_HISTORY_ITEMS, OpportunityHistory(maximum).entries.size)
        assertThrows(IllegalArgumentException::class.java) {
            OpportunityHistory(
                maximum + OpportunityHistoryEntry(eventId("history-overflow").value, now),
            )
        }
    }

    private fun allDomainEvents(): List<LocalOpportunityEvent> = listOf(
        commitmentEvent("commitment"),
        LocalOpportunityEvent.LeaveByReview(
            eventId = eventId("leave-by"),
            observedAtEpochMillis = now - MINUTE_MILLIS,
            expiresAtEpochMillis = now + EVENT_TTL_MILLIS,
            leaveAtEpochMillis = now + HOUR_MILLIS,
            zoneId = "Asia/Seoul",
        ),
        reminderEvent(eventId("reminder")),
    )

    private fun commitmentEvent(label: String): LocalOpportunityEvent.CommitmentReview =
        commitmentEvent(eventId(label))

    private fun commitmentEvent(
        eventId: OpportunityEventId,
    ): LocalOpportunityEvent.CommitmentReview = LocalOpportunityEvent.CommitmentReview(
        eventId = eventId,
        observedAtEpochMillis = now - MINUTE_MILLIS,
        expiresAtEpochMillis = now + EVENT_TTL_MILLIS,
    )

    private fun reminderEvent(eventId: OpportunityEventId): LocalOpportunityEvent.ReminderReview =
        LocalOpportunityEvent.ReminderReview(
            eventId = eventId,
            observedAtEpochMillis = now - MINUTE_MILLIS,
            expiresAtEpochMillis = now + EVENT_TTL_MILLIS,
            suggestedAtEpochMillis = now + HOUR_MILLIS,
            zoneId = "Asia/Seoul",
        )

    private fun commitmentPolicy(maxProposals: Int = 3) = OpportunityDomainPolicy(
        enabledDomains = setOf(OpportunityDomain.COMMITMENT_REVIEW),
        maxProposalsPerWindow = maxProposals,
        rateWindowMillis = HOUR_MILLIS,
    )

    private fun storedEntity(
        id: String,
        draft: CommitmentProposalDraft,
    ) = CommitmentProposalEntity(
        id = id,
        summary = draft.summary,
        proposedDueAtEpochMillis = draft.proposedDueAtEpochMillis,
        zoneId = draft.zoneId,
        sourcePackage = draft.sourcePackage,
        sourceRefHash = draft.sourceRefHash,
        confidence = draft.confidence,
        status = ProposalStatus.PENDING,
        promotedReminderId = null,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
    )

    private fun OpportunityRunResult.completed(): OpportunityRunResult.Completed {
        assertTrue(this is OpportunityRunResult.Completed)
        return this as OpportunityRunResult.Completed
    }

    private fun eventId(label: String): OpportunityEventId = OpportunityEventId(
        MessageDigest.getInstance("SHA-256")
            .digest(label.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) },
    )

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val HOUR_MILLIS = 60L * MINUTE_MILLIS
        const val EVENT_TTL_MILLIS = 30L * MINUTE_MILLIS
    }
}
