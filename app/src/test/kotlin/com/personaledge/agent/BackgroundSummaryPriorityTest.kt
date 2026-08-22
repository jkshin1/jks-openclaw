package com.personaledge.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSummaryPriorityTest {
    @Test
    fun `user turn waits until cancelled summary releases controller`() = runTest {
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val summary = launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                released.complete(Unit)
            }
        }
        runCurrent()
        started.await()

        val cancelled = BackgroundSummaryPriority.cancelForUserTurn(summary)
        BackgroundSummaryPriority.awaitRelease(cancelled)

        assertTrue(summary.isCancelled)
        assertTrue(released.isCompleted)
    }
}
