package com.personaledge.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReceiptCommitBoundaryTest {
    @Test
    fun `parent cancellation cannot split a tool receipt commit`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val committed = mutableListOf<String>()

        val job = launch {
            ToolReceiptCommitBoundary.commit {
                committed += "ui-receipt"
                entered.complete(Unit)
                release.await()
                committed += "assistant-history"
                committed += "tool-history"
                committed += "ordinal"
            }
        }

        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(
            listOf("ui-receipt", "assistant-history", "tool-history", "ordinal"),
            committed,
        )
    }
}
