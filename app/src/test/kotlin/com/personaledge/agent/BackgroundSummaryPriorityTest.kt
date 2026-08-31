package com.personaledge.agent

import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `thermal policy cancels an active background summary`() = runTest {
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

        val cancelled = BackgroundSummaryPriority.cancelForThermalPolicy(summary)
        BackgroundSummaryPriority.awaitRelease(cancelled)

        assertTrue(summary.isCancelled)
        assertTrue(released.isCompleted)
    }

    @Test
    fun `only a completed clean cool summary may be accepted`() {
        val cool = ThermalObservation(
            status = DiagnosticThermalStatus.LIGHT,
            directive = ThermalDirective.CONTINUE,
            stopSequence = 0L,
        )
        val severe = cool.copy(status = DiagnosticThermalStatus.SEVERE)
        val critical = cool.copy(
            status = DiagnosticThermalStatus.CRITICAL,
            directive = ThermalDirective.COOPERATIVE_CANCEL,
        )

        assertTrue(BackgroundSummaryPriority.mayAccept(true, false, cool))
        assertFalse(BackgroundSummaryPriority.mayAccept(false, false, cool))
        assertFalse(BackgroundSummaryPriority.mayAccept(true, true, cool))
        assertTrue(BackgroundSummaryPriority.mayAccept(true, false, severe))
        assertFalse(BackgroundSummaryPriority.mayAccept(true, false, critical))
    }
}
