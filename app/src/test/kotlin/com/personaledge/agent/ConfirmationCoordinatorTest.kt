package com.personaledge.agent

import com.personaledge.core.diagnostics.DiagnosticConfirmationOutcome
import com.personaledge.core.diagnostics.DiagnosticEvent
import com.personaledge.core.diagnostics.DiagnosticSink
import com.personaledge.core.diagnostics.DiagnosticToolStage
import com.personaledge.core.tools.ActionLedger
import com.personaledge.core.tools.FakeArrivalNoticeParams
import com.personaledge.core.tools.FakeArrivalNoticeTool
import com.personaledge.core.tools.PreparationResult
import com.personaledge.core.tools.ToolOrchestrator
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmationCoordinatorTest {
    @Test
    fun matchingUiDecisionReleasesTheSuspendedOrchestrator() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        val coordinator = ConfirmationCoordinator(
            clock = { testScheduler.currentTime },
            diagnostics = DiagnosticSink { event -> events += event; true },
        )
        val orchestrator = ToolOrchestrator(
            actionLedger = ActionLedger { true },
            userConfirmationGate = coordinator,
            clock = { testScheduler.currentTime },
        )
        val prepared = when (val result = orchestrator.prepare(
            tool = FakeArrivalNoticeTool(),
            params = FakeArrivalNoticeParams("아내", "30분 뒤 도착해"),
            requestId = "turn-1",
        )) {
            is PreparationResult.Ready -> result
            is PreparationResult.Rejected -> error(result.reason)
        }

        val execution = async { orchestrator.execute(prepared.action) }
        runCurrent()

        val pending = checkNotNull(coordinator.pending.value)
        assertFalse(coordinator.resolve("wrong-action", approved = true))
        assertTrue(coordinator.resolve(pending.actionId, approved = true))
        assertTrue(execution.await().simulated)
        assertNull(coordinator.pending.value)

        val phases = events.filterIsInstance<DiagnosticEvent.ToolPhase>()
        assertEquals(
            listOf(
                DiagnosticToolStage.CONFIRMATION_REQUESTED,
                DiagnosticToolStage.CONFIRMATION_RESOLVED,
            ),
            phases.map(DiagnosticEvent.ToolPhase::stage),
        )
        assertEquals(
            listOf(
                DiagnosticConfirmationOutcome.REQUESTED,
                DiagnosticConfirmationOutcome.APPROVED,
            ),
            phases.map(DiagnosticEvent.ToolPhase::confirmationOutcome),
        )
        val diagnosticText = events.joinToString(separator = "\n")
        assertFalse(diagnosticText.contains("아내"))
        assertFalse(diagnosticText.contains("30분 뒤 도착해"))
        assertFalse(diagnosticText.contains(pending.parameterDigest))
        assertFalse(diagnosticText.contains(pending.actionId))
    }

    @Test
    fun timeoutFailsClosed() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        val coordinator = ConfirmationCoordinator(
            clock = { testScheduler.currentTime },
            diagnostics = DiagnosticSink { event -> events += event; true },
        )
        val orchestrator = ToolOrchestrator(
            actionLedger = ActionLedger { true },
            userConfirmationGate = coordinator,
            clock = { testScheduler.currentTime },
        )
        val prepared = when (val result = orchestrator.prepare(
            tool = FakeArrivalNoticeTool(),
            params = FakeArrivalNoticeParams("아내", "곧 도착해"),
            requestId = "turn-2",
            lifetimeMillis = 1_000,
        )) {
            is PreparationResult.Ready -> result
            is PreparationResult.Rejected -> error(result.reason)
        }

        val execution = async {
            runCatching { orchestrator.execute(prepared.action) }.isSuccess
        }
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()

        assertFalse(execution.await())
        assertNull(coordinator.pending.value)
        assertEquals(
            DiagnosticConfirmationOutcome.EXPIRED,
            events.filterIsInstance<DiagnosticEvent.ToolPhase>().last().confirmationOutcome,
        )
    }

    @Test
    fun denyPendingIsRecordedAsCancellationRatherThanUserDenial() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        val coordinator = ConfirmationCoordinator(
            clock = { testScheduler.currentTime },
            diagnostics = DiagnosticSink { event -> events += event; true },
        )
        val orchestrator = ToolOrchestrator(
            actionLedger = ActionLedger { true },
            userConfirmationGate = coordinator,
            clock = { testScheduler.currentTime },
        )
        val prepared = when (val result = orchestrator.prepare(
            tool = FakeArrivalNoticeTool(),
            params = FakeArrivalNoticeParams("아내", "곧 도착해"),
            requestId = "turn-3",
        )) {
            is PreparationResult.Ready -> result
            is PreparationResult.Rejected -> error(result.reason)
        }

        val execution = async {
            runCatching { orchestrator.execute(prepared.action) }.isSuccess
        }
        runCurrent()
        coordinator.denyPending()
        runCurrent()

        assertFalse(execution.await())
        assertEquals(
            DiagnosticConfirmationOutcome.CANCELLED,
            events.filterIsInstance<DiagnosticEvent.ToolPhase>().last().confirmationOutcome,
        )
    }

    @Test
    fun explicitUiRejectionIsRecordedAsDenied() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        val coordinator = ConfirmationCoordinator(
            clock = { testScheduler.currentTime },
            diagnostics = DiagnosticSink { event -> events += event; true },
        )
        val orchestrator = ToolOrchestrator(
            actionLedger = ActionLedger { true },
            userConfirmationGate = coordinator,
            clock = { testScheduler.currentTime },
        )
        val prepared = when (val result = orchestrator.prepare(
            tool = FakeArrivalNoticeTool(),
            params = FakeArrivalNoticeParams("아내", "곧 도착해"),
            requestId = "turn-4",
        )) {
            is PreparationResult.Ready -> result
            is PreparationResult.Rejected -> error(result.reason)
        }

        val execution = async {
            runCatching { orchestrator.execute(prepared.action) }.isSuccess
        }
        runCurrent()
        val pending = checkNotNull(coordinator.pending.value)
        assertTrue(coordinator.resolve(pending.actionId, approved = false))
        runCurrent()

        assertFalse(execution.await())
        assertEquals(
            DiagnosticConfirmationOutcome.DENIED,
            events.filterIsInstance<DiagnosticEvent.ToolPhase>().last().confirmationOutcome,
        )
    }
}
