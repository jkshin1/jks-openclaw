package com.personaledge.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticJsonEncoderTest {
    @Test
    fun `escapes every JSON control character`() {
        assertEquals(
            "quote\\\" slash\\\\ back\\b form\\f line\\n return\\r tab\\t nul\\u0000",
            DiagnosticJsonEncoder.escape(
                "quote\" slash\\ back\b form\u000c line\n return\r tab\t nul\u0000",
            ),
        )
    }

    @Test
    fun `failure never retains throwable message or stack file name`() {
        val secretMessage = "api-token-super-secret"
        val secretPath = "/private/recipient-message-secret.kt"
        val throwable = IllegalStateException(secretMessage).apply {
            stackTrace = arrayOf(StackTraceElement("example.Safe", "invoke", secretPath, 44))
        }
        val json = DiagnosticJsonEncoder.encode(
            DiagnosticEvent.TurnFailed(
                durationMillis = 7,
                deltaCount = 1,
                deltaByteCount = 3,
                errorCode = DiagnosticErrorCode.MODEL_FAILURE,
                failure = DiagnosticFailure.from(throwable),
            ),
            recordedAtMillis = 9,
        )

        assertNotNull(json)
        assertFalse(json!!.contains(secretMessage))
        assertFalse(json.contains(secretPath))
        assertTrue(json.contains("\"throwable_type\":\"java.lang.IllegalStateException\""))
        assertTrue(json.contains(Regex("\\\"stack_fingerprint_sha256\\\":\\\"[0-9a-f]{64}\\\"")))
    }

    @Test
    fun `tool name boundary rejects paths recipients and JSON`() {
        assertNotNull(DiagnosticToolName.parse("calendar_lookup_2"))
        assertNull(DiagnosticToolName.parse("recipient@example.com"))
        assertNull(DiagnosticToolName.parse("/data/user/0/model.litertlm"))
        assertNull(DiagnosticToolName.parse("tool\"payload"))
        assertNull(DiagnosticToolName.parse("a".repeat(65)))
    }

    @Test
    fun `invalid metrics and impossible tool chronology are rejected`() {
        assertNull(
            DiagnosticJsonEncoder.encode(
                DiagnosticEvent.TurnStarted(-1),
                recordedAtMillis = 1,
            ),
        )
        assertNull(
            DiagnosticJsonEncoder.encode(
                DiagnosticEvent.ToolPhase(
                    name = requireNotNull(DiagnosticToolName.parse("calendar_lookup")),
                    stage = DiagnosticToolStage.EXECUTED,
                    risk = DiagnosticToolRisk.READ_ONLY,
                    confirmationOutcome = DiagnosticConfirmationOutcome.REQUESTED,
                ),
                recordedAtMillis = 1,
            ),
        )
    }

    @Test
    fun `historical package update reason is typed`() {
        val json = DiagnosticJsonEncoder.encode(
            DiagnosticEvent.HistoricalExit(
                HistoricalExitRecord(
                    reason = DiagnosticExitReason.PACKAGE_UPDATED,
                    status = 0,
                    importance = 100,
                    pssBytes = 0,
                    rssBytes = 0,
                    timestampMillis = 123,
                    phase = DiagnosticPhase.IDLE,
                ),
            ),
            recordedAtMillis = 456,
        )

        assertTrue(json!!.contains("\"reason\":\"package_updated\""))
        assertFalse(json.contains("packageName"))
    }

    @Test
    fun `thermal guard records only typed status and action`() {
        val json = DiagnosticJsonEncoder.encode(
            DiagnosticEvent.ThermalGuard(
                status = DiagnosticThermalStatus.CRITICAL,
                action = DiagnosticThermalAction.COOPERATIVE_CANCEL_REQUESTED,
            ),
            recordedAtMillis = 789,
        )

        assertEquals(
            "{\"schema_version\":1,\"recorded_at_ms\":789,\"event\":\"thermal_guard\",\"thermal_status\":\"critical\",\"action\":\"cooperative_cancel_requested\"}",
            json,
        )
        assertNull(
            DiagnosticJsonEncoder.encode(
                DiagnosticEvent.ThermalGuard(
                    status = DiagnosticThermalStatus.SEVERE,
                    action = DiagnosticThermalAction.COOPERATIVE_CANCEL_REQUESTED,
                ),
                recordedAtMillis = 789,
            ),
        )
    }

    @Test
    fun `thermal turn cancellation requires a typed stop status`() {
        assertNull(
            DiagnosticJsonEncoder.encode(
                DiagnosticEvent.TurnCancelled(
                    durationMillis = 10,
                    deltaCount = 1,
                    deltaByteCount = 2,
                    cause = DiagnosticTurnCancellationCause.THERMAL,
                ),
                recordedAtMillis = 11,
            ),
        )
        val json = DiagnosticJsonEncoder.encode(
            DiagnosticEvent.TurnCancelled(
                durationMillis = 10,
                deltaCount = 1,
                deltaByteCount = 2,
                cause = DiagnosticTurnCancellationCause.THERMAL,
                thermalStatus = DiagnosticThermalStatus.CRITICAL,
            ),
            recordedAtMillis = 11,
        )

        assertTrue(json!!.contains("\"cause\":\"thermal\""))
        assertTrue(json.contains("\"thermal_status\":\"critical\""))
        assertNull(
            DiagnosticJsonEncoder.encode(
                DiagnosticEvent.TurnCancelled(
                    durationMillis = 10,
                    deltaCount = 1,
                    deltaByteCount = 2,
                    cause = DiagnosticTurnCancellationCause.THERMAL,
                    thermalStatus = DiagnosticThermalStatus.SEVERE,
                ),
                recordedAtMillis = 11,
            ),
        )
    }

    @Test
    fun `runtime initialization cancellation accepts only stop status`() {
        assertNotNull(
            DiagnosticJsonEncoder.encode(
                DiagnosticEvent.ThermalGuard(
                    status = DiagnosticThermalStatus.CRITICAL,
                    action = DiagnosticThermalAction.RUNTIME_INITIALIZATION_CANCEL_REQUESTED,
                ),
                recordedAtMillis = 12,
            ),
        )
        assertNull(
            DiagnosticJsonEncoder.encode(
                DiagnosticEvent.ThermalGuard(
                    status = DiagnosticThermalStatus.SEVERE,
                    action = DiagnosticThermalAction.RUNTIME_INITIALIZATION_CANCEL_REQUESTED,
                ),
                recordedAtMillis = 12,
            ),
        )
    }
}
