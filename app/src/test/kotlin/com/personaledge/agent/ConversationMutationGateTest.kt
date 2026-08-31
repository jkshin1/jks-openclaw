package com.personaledge.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMutationGateTest {
    @Test
    fun `only one conversation mutation owns the boundary`() {
        val gate = ConversationMutationGate()
        val first = gate.tryAcquire()

        assertNotNull(first)
        assertTrue(gate.owns(requireNotNull(first)))
        assertNull(gate.tryAcquire())

        first.close()
        assertFalse(gate.owns(first))
        assertNotNull(gate.tryAcquire())
    }

    @Test
    fun `concurrent contenders cannot both enter and close is idempotent`() {
        val gate = ConversationMutationGate()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = List(2) {
                executor.submit<ConversationMutationGate.Lease?> {
                    ready.countDown()
                    start.await()
                    gate.tryAcquire()
                }
            }
            ready.await()
            start.countDown()
            val leases = attempts.map { future -> future.get() }.filterNotNull()

            assertTrue(leases.size == 1)
            leases.single().close()
            leases.single().close()
            assertNotNull(gate.tryAcquire())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `scope cancellation closes a lease even when coroutine body never starts`() {
        val gate = ConversationMutationGate()
        val lease = requireNotNull(gate.tryAcquire())
        val neverStarted = Job()

        lease.closeOnCompletion(neverStarted)
        neverStarted.cancel()

        assertFalse(gate.owns(lease))
        assertNotNull(gate.tryAcquire())
    }

    @Test
    fun `recovery handoff keeps the same lease until the caller releases it`() {
        val gate = ConversationMutationGate()
        val heldByRecovery = requireNotNull(gate.tryAcquire())

        assertTrue(gate.owns(heldByRecovery))
        assertNull(gate.tryAcquire())

        heldByRecovery.close()
        assertNotNull(gate.tryAcquire())
    }

    @Test
    fun `global verification entries replace local duplicates but keep read recovery`() {
        val readRecovery = recoveryEntry(
            turnId = "read-turn",
            conversationId = "active",
            type = ChatRecoveryType.REQUERY_READ,
        )
        val localVerification = recoveryEntry(
            turnId = "write-a",
            conversationId = "active",
            type = ChatRecoveryType.VERIFY_EXTERNAL_STATE,
        )
        val globalA = recoveryEntry(
            turnId = "write-a",
            conversationId = "active",
            type = ChatRecoveryType.VERIFY_EXTERNAL_STATE,
        )
        val globalB = recoveryEntry(
            turnId = "write-b",
            conversationId = "deleted",
            type = ChatRecoveryType.VERIFY_EXTERNAL_STATE,
        )
        val transcript = ChatEntry("user", ChatRole.USER, "질문")

        val merged = ConversationRecoveryEntryPolicy.mergeGlobalVerification(
            entries = listOf(transcript, readRecovery, localVerification),
            globalVerificationEntries = listOf(globalA, globalA, globalB),
        )

        assertEquals(listOf("user", "read-turn", "write-a", "write-b"), merged.map(ChatEntry::id))
    }

    @Test
    fun `new or deleted transcript keeps verification entries when global reload fails`() {
        val verification = recoveryEntry(
            turnId = "write-a",
            conversationId = "deleted",
            type = ChatRecoveryType.VERIFY_EXTERNAL_STATE,
        )

        assertEquals(
            listOf(verification),
            ConversationRecoveryEntryPolicy.verificationOnly(
                currentEntries = listOf(ChatEntry("user", ChatRole.USER, "질문"), verification),
                globalVerificationEntries = null,
            ),
        )
    }

    @Test
    fun `verification can resolve globally while read recovery stays conversation bound`() {
        val verification = requireNotNull(
            recoveryEntry(
                turnId = "write-a",
                conversationId = "deleted",
                type = ChatRecoveryType.VERIFY_EXTERNAL_STATE,
            ).recoveryAction,
        )
        val requery = requireNotNull(
            recoveryEntry(
                turnId = "read-a",
                conversationId = "active",
                type = ChatRecoveryType.REQUERY_READ,
            ).recoveryAction,
        )

        assertTrue(ConversationRecoveryEntryPolicy.canResolve(verification, null))
        assertTrue(ConversationRecoveryEntryPolicy.canResolve(verification, "another"))
        assertTrue(ConversationRecoveryEntryPolicy.canResolve(requery, "active"))
        assertFalse(ConversationRecoveryEntryPolicy.canResolve(requery, "another"))
    }

    private fun recoveryEntry(
        turnId: String,
        conversationId: String,
        type: ChatRecoveryType,
    ): ChatEntry = ChatEntry(
        id = turnId,
        role = ChatRole.STATUS,
        text = "복구 상태",
        recoveryAction = ChatRecoveryAction(
            turnId = turnId,
            conversationId = conversationId,
            type = type,
            label = "복구",
            expectedReadTools = if (type == ChatRecoveryType.REQUERY_READ) {
                listOf("weather_current")
            } else {
                emptyList()
            },
        ),
    )
}
