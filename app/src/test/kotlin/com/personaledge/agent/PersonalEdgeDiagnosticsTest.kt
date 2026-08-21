package com.personaledge.agent

import com.personaledge.core.agent.AgentFailureCode
import com.personaledge.core.diagnostics.DiagnosticConfirmationOutcome
import com.personaledge.core.diagnostics.DiagnosticErrorCode
import com.personaledge.core.diagnostics.DiagnosticEvent
import com.personaledge.core.diagnostics.DiagnosticRecorder
import com.personaledge.core.diagnostics.DiagnosticSink
import com.personaledge.core.diagnostics.DiagnosticToolStage
import com.personaledge.core.llm.LlmFailureCode
import com.personaledge.core.llm.ModelStoreErrorCode
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalEdgeDiagnosticsTest {
    @Test
    fun exactSubsystemCodesRemainDistinct() {
        assertEquals(
            DiagnosticErrorCode.INVALID_DIGEST,
            ModelStoreErrorCode.INVALID_DIGEST.toDiagnosticErrorCode(),
        )
        assertEquals(
            DiagnosticErrorCode.CONTEXT_BUDGET_EXCEEDED,
            LlmFailureCode.CONTEXT_BUDGET_EXCEEDED.toDiagnosticErrorCode(),
        )
        assertEquals(
            DiagnosticErrorCode.TOOL_CALL_LIMIT_EXCEEDED,
            AgentFailureCode.TOOL_CALL_LIMIT_EXCEEDED.toDiagnosticErrorCode(),
        )
    }

    @Test
    fun onlyClosedRegistryToolNamesEnterDiagnostics() {
        val events = mutableListOf<DiagnosticEvent>()
        val sink = DiagnosticSink { event -> events += event; true }

        assertFalse(
            sink.recordKnownToolPhase(
                toolName = "model_invented_tool",
                stage = DiagnosticToolStage.EXECUTED,
                outcome = DiagnosticConfirmationOutcome.NOT_REQUIRED,
            ),
        )
        assertTrue(
            sink.recordKnownToolPhase(
                toolName = "fake_arrival_notice",
                stage = DiagnosticToolStage.EXECUTED,
                outcome = DiagnosticConfirmationOutcome.APPROVED,
            ),
        )
        assertEquals(1, events.size)
    }

    @Test
    fun diagnosticSinkFailuresNeverEscape() {
        val sink = DiagnosticSink { throw IllegalStateException("must stay isolated") }

        assertFalse(sink.recordSafely(DiagnosticEvent.ProcessStarted))
    }

    @Test
    fun elapsedTimeNeverBecomesNegative() {
        assertEquals(0L, elapsedMillisSince(startedAtMillis = 20L, nowMillis = 10L))
        assertEquals(5L, elapsedMillisSince(startedAtMillis = 10L, nowMillis = 15L))
    }

    @Test
    fun appChannelQueuesFileWorkAndRejectedSubmissionFailsOpen() {
        val tasks = mutableListOf<Runnable>()
        val queued = AppDiagnosticChannel(
            recorder = DiagnosticRecorder.noOp(),
            executor = Executor(tasks::add),
        )

        assertTrue(queued.record(DiagnosticEvent.ProcessStarted))
        assertEquals(1, tasks.size)
        tasks.single().run()

        val rejected = AppDiagnosticChannel(
            recorder = DiagnosticRecorder.noOp(),
            executor = Executor { throw RejectedExecutionException("closed") },
        )
        assertFalse(rejected.record(DiagnosticEvent.ProcessStarted))
    }
}
