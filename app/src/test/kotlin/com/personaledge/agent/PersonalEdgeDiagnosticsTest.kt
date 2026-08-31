package com.personaledge.agent

import com.personaledge.core.agent.AgentFailureCode
import com.personaledge.core.diagnostics.DiagnosticConfirmationOutcome
import com.personaledge.core.diagnostics.DiagnosticErrorCode
import com.personaledge.core.diagnostics.DiagnosticEvent
import com.personaledge.core.diagnostics.DiagnosticExportResult
import com.personaledge.core.diagnostics.DiagnosticRecorder
import com.personaledge.core.diagnostics.DiagnosticSink
import com.personaledge.core.diagnostics.DiagnosticToolRisk
import com.personaledge.core.diagnostics.DiagnosticToolStage
import com.personaledge.core.llm.LlmFailureCode
import com.personaledge.core.llm.ModelStoreErrorCode
import com.personaledge.core.tools.AlarmNextTool
import com.personaledge.core.tools.AlarmSetTool
import com.personaledge.core.tools.CalendarCreateEventTool
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.CalendarUpdateEventTool
import com.personaledge.core.tools.CommitmentProposalTool
import com.personaledge.core.tools.FakeArrivalNoticeTool
import com.personaledge.core.tools.NotificationSearchTool
import com.personaledge.core.tools.KakaoNotificationReplyTool
import com.personaledge.core.tools.KakaoShareMessageTool
import com.personaledge.core.tools.MemoryRememberTool
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.ReminderCancelTool
import com.personaledge.core.tools.ReminderCreateTool
import com.personaledge.core.tools.ReminderQueryTool
import com.personaledge.core.tools.ReminderUpdateTool
import com.personaledge.core.tools.ToolFailureCode
import com.personaledge.core.tools.WebSearchTool
import com.personaledge.core.tools.WeatherTool
import java.io.ByteArrayOutputStream
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

        val mappedToolFailures = ToolFailureCode.values().map { code ->
            code.toDiagnosticErrorCode()
        }
        assertEquals(ToolFailureCode.values().size, mappedToolFailures.distinct().size)
        ToolFailureCode.values().zip(mappedToolFailures).forEach { (source, diagnostic) ->
            assertEquals("TOOL_${source.name}", diagnostic.name)
        }
    }

    @Test
    fun fixtureAndEveryShippedToolRecordTheirDeclaredRisk() {
        val events = mutableListOf<DiagnosticEvent>()
        val sink = DiagnosticSink { event -> events += event; true }
        val expected = listOf(
            FakeArrivalNoticeTool.NAME to DiagnosticToolRisk.READ_ONLY,
            CalendarQueryTool.NAME to DiagnosticToolRisk.READ_ONLY,
            CalendarCreateEventTool.NAME to DiagnosticToolRisk.DATA_WRITE,
            CalendarUpdateEventTool.NAME to DiagnosticToolRisk.DATA_WRITE,
            AlarmSetTool.NAME to DiagnosticToolRisk.DATA_WRITE,
            AlarmNextTool.NAME to DiagnosticToolRisk.READ_ONLY,
            NotificationSearchTool.NAME to DiagnosticToolRisk.READ_ONLY,
            RouteEstimateTool.NAME to DiagnosticToolRisk.READ_ONLY,
            WebSearchTool.NAME to DiagnosticToolRisk.READ_ONLY,
            WeatherTool.NAME to DiagnosticToolRisk.READ_ONLY,
            KakaoShareMessageTool.NAME to DiagnosticToolRisk.COMMUNICATION,
            KakaoNotificationReplyTool.NAME to DiagnosticToolRisk.COMMUNICATION,
            MemoryRememberTool.NAME to DiagnosticToolRisk.LOCAL_WRITE,
            CommitmentProposalTool.NAME to DiagnosticToolRisk.LOCAL_WRITE,
            ReminderCreateTool.NAME to DiagnosticToolRisk.LOCAL_WRITE,
            ReminderUpdateTool.NAME to DiagnosticToolRisk.LOCAL_WRITE,
            ReminderCancelTool.NAME to DiagnosticToolRisk.LOCAL_WRITE,
            ReminderQueryTool.NAME to DiagnosticToolRisk.READ_ONLY,
        )

        expected.forEach { (name, risk) ->
            assertTrue(
                sink.recordKnownToolPhase(
                    toolName = name,
                    stage = DiagnosticToolStage.EXECUTED,
                    outcome = DiagnosticConfirmationOutcome.EXECUTED_SUCCESS,
                ),
            )
            val event = events.last() as DiagnosticEvent.ToolPhase
            assertEquals(name, event.name.value)
            assertEquals(risk, event.risk)
            assertEquals(DiagnosticToolStage.EXECUTED, event.stage)
            assertEquals(DiagnosticConfirmationOutcome.EXECUTED_SUCCESS, event.confirmationOutcome)
        }
        assertEquals(expected.size, events.size)
    }

    @Test
    fun unknownToolNamesAreRejectedWithoutWritingDiagnostics() {
        val events = mutableListOf<DiagnosticEvent>()
        val sink = DiagnosticSink { event -> events += event; true }

        listOf("model_invented_tool", "calendar_query_suffix", " calendar_query").forEach { name ->
            assertFalse(
                sink.recordKnownToolPhase(
                    toolName = name,
                    stage = DiagnosticToolStage.EXECUTED,
                    outcome = DiagnosticConfirmationOutcome.EXECUTED_REFUSED,
                ),
            )
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun knownToolStillFailsOpenWhenDiagnosticSinkRejectsIt() {
        val sink = DiagnosticSink { false }

        assertFalse(
            sink.recordKnownToolPhase(
                toolName = CalendarQueryTool.NAME,
                stage = DiagnosticToolStage.EXECUTED,
                outcome = DiagnosticConfirmationOutcome.EXECUTED_SUCCESS,
            ),
        )
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

    @Test
    fun appChannelQueuesExportAfterEarlierRecordsAndClosesTheDestination() {
        val tasks = mutableListOf<Runnable>()
        val results = mutableListOf<DiagnosticExportResult>()
        val destination = CloseTrackingOutputStream()
        val channel = AppDiagnosticChannel(
            recorder = DiagnosticRecorder.noOp(),
            executor = Executor(tasks::add),
        )

        assertTrue(channel.record(DiagnosticEvent.ProcessStarted))
        assertTrue(
            channel.exportContentFreeJsonl(
                openDestination = { destination },
                onComplete = results::add,
            ),
        )
        assertEquals(2, tasks.size)

        tasks.forEach(Runnable::run)

        assertEquals(listOf(DiagnosticExportResult.Unavailable), results)
        assertTrue(destination.closed)
    }

    private class CloseTrackingOutputStream : ByteArrayOutputStream() {
        var closed: Boolean = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
