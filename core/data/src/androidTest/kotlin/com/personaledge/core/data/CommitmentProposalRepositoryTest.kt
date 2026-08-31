package com.personaledge.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommitmentProposalRepositoryTest {
    private lateinit var database: PersonalEdgeDatabase
    private lateinit var repository: CommitmentProposalRepository
    private var now = 1_800_000_000_000L
    private val ids = AtomicLong()

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            PersonalEdgeDatabase::class.java,
        ).build()
        repository = CommitmentProposalRepository(
            database = database,
            clock = { now },
            idFactory = { "proposal-${ids.incrementAndGet()}" },
        )
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun sourceHashIsIdempotentEvenAfterDismissal() = runBlocking {
        val first = repository.offer(draft()) as ProposalStoreResult.Stored
        assertTrue(repository.dismiss(first.proposal.id))

        assertEquals(ProposalStoreResult.AlreadyHandled, repository.offer(draft()))
        assertTrue(repository.pending().isEmpty())
    }

    @Test
    fun promotionRecordsTheReminderWithoutCreatingIt() = runBlocking {
        val stored = repository.offer(draft()) as ProposalStoreResult.Stored

        assertTrue(repository.markPromoted(stored.proposal.id, "reminder-1"))
        assertTrue(repository.pending().isEmpty())
        assertEquals(0, database.reminderDao().countActive())
    }

    @Test
    fun invalidOrExpiredProposalIsRefusedAndOldPendingProposalExpiresClosed() = runBlocking {
        assertEquals(
            ProposalStoreResult.Invalid,
            repository.offer(draft().copy(sourceRefHash = "raw-source-key")),
        )
        assertEquals(
            ProposalStoreResult.Invalid,
            repository.offer(draft().copy(proposedDueAtEpochMillis = now - 1L)),
        )
        repository.offer(draft())
        now += CommitmentProposalRepository.RETENTION_MILLIS + 1L

        assertTrue(repository.pending().isEmpty())
    }

    @Test
    fun recentHistoryIsContentFreeBoundedAllStatusAndSurvivesRepositoryRecreation() = runBlocking {
        val dismissed = repository.offer(
            draft(sourceRefHash = "a".repeat(64), summary = "dismissed private summary"),
        ) as ProposalStoreResult.Stored
        assertTrue(repository.dismiss(dismissed.proposal.id))
        now += 1L
        val promoted = repository.offer(
            draft(sourceRefHash = "b".repeat(64), summary = "promoted private summary"),
        ) as ProposalStoreResult.Stored
        assertTrue(repository.markPromoted(promoted.proposal.id, "reminder-1"))
        now += 1L
        repository.offer(
            draft(sourceRefHash = "c".repeat(64), summary = "pending private summary"),
        ) as ProposalStoreResult.Stored

        val recreated = CommitmentProposalRepository(
            database = database,
            clock = { now },
            idFactory = { "recreated-${ids.incrementAndGet()}" },
        )
        val bounded = recreated.recentHistory(limit = 2)
        val allStatuses = recreated.recentHistory(limit = Int.MAX_VALUE)

        assertEquals(listOf("c".repeat(64), "b".repeat(64)), bounded.map { it.sourceRefHash })
        assertEquals(
            listOf("c".repeat(64), "b".repeat(64), "a".repeat(64)),
            allStatuses.map { it.sourceRefHash },
        )
        assertFalse(allStatuses.toString().contains("private summary"))
        assertTrue(CommitmentProposalRepository.MAX_RECENT_HISTORY <= 2_200)

        now += CommitmentProposalRepository.MAX_RECENT_HISTORY_WINDOW_MILLIS + 1L
        recreated.offer(
            draft(sourceRefHash = "d".repeat(64), summary = "new private summary"),
        ) as ProposalStoreResult.Stored

        assertEquals(
            listOf("d".repeat(64)),
            recreated.recentHistory().map { it.sourceRefHash },
        )
    }

    private fun draft(
        sourceRefHash: String = "a".repeat(64),
        summary: String = "철수: 내일 오후 7시에 만나자",
    ) = CommitmentProposalDraft(
        summary = summary,
        proposedDueAtEpochMillis = now + 60_000L,
        zoneId = "Asia/Seoul",
        sourcePackage = "com.kakao.talk",
        sourceRefHash = sourceRefHash,
        confidence = 90,
    )
}
