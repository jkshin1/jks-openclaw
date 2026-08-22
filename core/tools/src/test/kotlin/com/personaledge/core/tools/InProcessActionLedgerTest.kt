package com.personaledge.core.tools

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InProcessActionLedgerTest {
    @Test
    fun `claim is single use`() = kotlinx.coroutines.runBlocking {
        val ledger = InProcessActionLedger()

        assertTrue(ledger.claim("key"))
        assertEquals(ActionExecutionState.CLAIMED, ledger.stateOf("key"))
        assertFalse(ledger.claim("key"))
    }

    @Test
    fun `terminal state is monotonic and idempotent`() = kotlinx.coroutines.runBlocking {
        val ledger = InProcessActionLedger()

        assertTrue(ledger.claim("key"))
        assertTrue(ledger.recordState("key", ActionExecutionState.REFUSED))
        assertTrue(ledger.recordState("key", ActionExecutionState.REFUSED))
        assertFalse(ledger.recordState("key", ActionExecutionState.COMPLETED))
        assertFalse(ledger.recordState("missing", ActionExecutionState.COMPLETED))
        assertEquals(ActionExecutionState.REFUSED, ledger.stateOf("key"))
    }

    @Test
    fun `concurrent duplicate claims have exactly one winner`() {
        val ledger = InProcessActionLedger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(
                List(32) {
                    Callable {
                        kotlinx.coroutines.runBlocking { ledger.claim("same-key") }
                    }
                },
            ).map { it.get() }

            assertEquals(1, results.count { it })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `blank key is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { InProcessActionLedger().claim(" ") }
        }
    }

    @Test
    fun `capacity exhaustion fails closed without evicting replay history`() =
        kotlinx.coroutines.runBlocking {
            val ledger = InProcessActionLedger(maximumClaims = 2)

            assertTrue(ledger.claim("first"))
            assertTrue(ledger.claim("second"))
            assertFalse(ledger.claim("third"))
            assertFalse(ledger.claim("first"))
        }
}
