package com.personaledge.core.diagnostics

import java.util.Locale

internal object DiagnosticJsonEncoder {
    fun encode(
        event: DiagnosticEvent,
        recordedAtMillis: Long,
    ): String? {
        if (recordedAtMillis < 0) return null
        val json = JsonObjectWriter()
            .number("schema_version", 1)
            .number("recorded_at_ms", recordedAtMillis)

        when (event) {
            DiagnosticEvent.ProcessStarted -> json.string("event", "process_started")
            DiagnosticEvent.SessionStarted -> json.string("event", "session_started")
            is DiagnosticEvent.HistoricalExit -> {
                val exit = event.exit
                if (exit.pssBytes < 0 || exit.rssBytes < 0 || exit.timestampMillis < 0) return null
                json.string("event", "historical_exit")
                    .enum("reason", exit.reason)
                    .number("status", exit.status)
                    .number("importance", exit.importance)
                    .number("pss_bytes", exit.pssBytes)
                    .number("rss_bytes", exit.rssBytes)
                    .number("exit_timestamp_ms", exit.timestampMillis)
                    .optionalEnum("phase", exit.phase)
            }
            is DiagnosticEvent.ModelInspected -> {
                if (event.sizeBytes < 0 || event.durationMillis < 0) return null
                json.string("event", "model_inspected")
                    .number("size_bytes", event.sizeBytes)
                    .number("duration_ms", event.durationMillis)
                    .enum("result", event.result)
                    .optionalEnum("error_code", event.errorCode)
                    .failure(event.failure)
            }
            is DiagnosticEvent.ModelImported -> {
                if (event.sizeBytes < 0 || event.durationMillis < 0) return null
                json.string("event", "model_imported")
                    .number("size_bytes", event.sizeBytes)
                    .number("duration_ms", event.durationMillis)
                    .enum("result", event.result)
                    .optionalEnum("error_code", event.errorCode)
                    .failure(event.failure)
            }
            is DiagnosticEvent.RuntimeInitialized -> {
                if (event.durationMillis < 0) return null
                if (event.result == DiagnosticResult.SUCCESS && event.activeBackend == null) return null
                json.string("event", "runtime_initialized")
                    .enum("requested_backend", event.requestedBackend)
                    .optionalEnum("active_backend", event.activeBackend)
                    .number("duration_ms", event.durationMillis)
                    .enum("result", event.result)
                    .optionalEnum("error_code", event.errorCode)
                    .failure(event.failure)
            }
            is DiagnosticEvent.TurnStarted -> {
                if (event.promptByteCount < 0) return null
                json.string("event", "turn_started")
                    .number("prompt_byte_count", event.promptByteCount)
            }
            is DiagnosticEvent.TurnFirstToken -> {
                if (event.ttftMillis < 0) return null
                json.string("event", "turn_first_token")
                    .number("ttft_ms", event.ttftMillis)
            }
            is DiagnosticEvent.TurnCompleted -> {
                if (!validTurnMetrics(event.durationMillis, event.deltaCount, event.deltaByteCount)) return null
                json.string("event", "turn_completed")
                    .turnMetrics(event.durationMillis, event.deltaCount, event.deltaByteCount)
            }
            is DiagnosticEvent.TurnCancelled -> {
                if (!validTurnMetrics(event.durationMillis, event.deltaCount, event.deltaByteCount)) return null
                if ((event.cause == DiagnosticTurnCancellationCause.THERMAL) != (event.thermalStatus != null)) {
                    return null
                }
                if (event.thermalStatus != null &&
                    event.thermalStatus !in thermalCancellationStatuses
                ) {
                    return null
                }
                json.string("event", "turn_cancelled")
                    .turnMetrics(event.durationMillis, event.deltaCount, event.deltaByteCount)
                    .enum("cause", event.cause)
                    .optionalEnum("thermal_status", event.thermalStatus)
            }
            is DiagnosticEvent.TurnFailed -> {
                if (!validTurnMetrics(event.durationMillis, event.deltaCount, event.deltaByteCount)) return null
                json.string("event", "turn_failed")
                    .turnMetrics(event.durationMillis, event.deltaCount, event.deltaByteCount)
                    .enum("error_code", event.errorCode)
                    .failure(event.failure)
            }
            is DiagnosticEvent.ToolPhase -> {
                if (!isValidToolChronology(event.stage, event.confirmationOutcome)) return null
                json.string("event", "tool_phase")
                    .string("name", event.name.value)
                    .enum("stage", event.stage)
                    .enum("risk", event.risk)
                    .enum("confirmation_outcome", event.confirmationOutcome)
            }
            is DiagnosticEvent.ResourceSnapshot -> {
                val metrics = event.metrics
                if (metrics.pssBytes < 0 || metrics.javaHeapBytes < 0) return null
                json.string("event", "resource_snapshot")
                    .number("pss_bytes", metrics.pssBytes)
                    .number("java_heap_bytes", metrics.javaHeapBytes)
                    .enum("thermal_status", metrics.thermalStatus)
            }
            is DiagnosticEvent.ThermalGuard -> {
                if (!isValidThermalGuard(event.status, event.action)) return null
                json.string("event", "thermal_guard")
                    .enum("thermal_status", event.status)
                    .enum("action", event.action)
            }
        }
        return json.finish()
    }

    internal fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }

    private fun validTurnMetrics(durationMillis: Long, deltaCount: Int, deltaByteCount: Long): Boolean =
        durationMillis >= 0 && deltaCount >= 0 && deltaByteCount >= 0

    private fun isValidToolChronology(
        stage: DiagnosticToolStage,
        outcome: DiagnosticConfirmationOutcome,
    ): Boolean = when (stage) {
        DiagnosticToolStage.CONFIRMATION_REQUESTED ->
            outcome == DiagnosticConfirmationOutcome.REQUESTED
        DiagnosticToolStage.CONFIRMATION_RESOLVED -> outcome in setOf(
            DiagnosticConfirmationOutcome.APPROVED,
            DiagnosticConfirmationOutcome.DENIED,
            DiagnosticConfirmationOutcome.AUTHENTICATION_FAILED,
            DiagnosticConfirmationOutcome.EXPIRED,
            DiagnosticConfirmationOutcome.CANCELLED,
        )
        DiagnosticToolStage.EXECUTED -> outcome in setOf(
            DiagnosticConfirmationOutcome.EXECUTED_SUCCESS,
            DiagnosticConfirmationOutcome.EXECUTED_REFUSED,
        )
    }

    private fun isValidThermalGuard(
        status: DiagnosticThermalStatus,
        action: DiagnosticThermalAction,
    ): Boolean = when (action) {
        DiagnosticThermalAction.STATUS_OBSERVED -> true
        DiagnosticThermalAction.RUNTIME_INITIALIZATION_REJECTED,
        DiagnosticThermalAction.TURN_REJECTED -> status in thermalStopStatuses
        DiagnosticThermalAction.RUNTIME_INITIALIZATION_CANCEL_REQUESTED ->
            status in thermalStopStatuses
        DiagnosticThermalAction.COOPERATIVE_CANCEL_REQUESTED ->
            status == DiagnosticThermalStatus.CRITICAL
        DiagnosticThermalAction.IMMEDIATE_ABORT_REQUESTED -> status in setOf(
            DiagnosticThermalStatus.EMERGENCY,
            DiagnosticThermalStatus.SHUTDOWN,
            DiagnosticThermalStatus.UNKNOWN,
        )
    }

    private val thermalCancellationStatuses = setOf(
        DiagnosticThermalStatus.CRITICAL,
        DiagnosticThermalStatus.EMERGENCY,
        DiagnosticThermalStatus.SHUTDOWN,
        DiagnosticThermalStatus.UNKNOWN,
    )

    private val thermalStopStatuses = setOf(
        DiagnosticThermalStatus.CRITICAL,
        DiagnosticThermalStatus.EMERGENCY,
        DiagnosticThermalStatus.SHUTDOWN,
        DiagnosticThermalStatus.UNKNOWN,
    )

    private class JsonObjectWriter {
        private val content = StringBuilder("{")
        private var first = true

        fun string(name: String, value: String): JsonObjectWriter = apply {
            prefix(name)
            content.append('"').append(escape(value)).append('"')
        }

        fun number(name: String, value: Number): JsonObjectWriter = apply {
            prefix(name)
            content.append(value)
        }

        fun enum(name: String, value: Enum<*>): JsonObjectWriter =
            string(name, value.name.lowercase(Locale.ROOT))

        fun optionalEnum(name: String, value: Enum<*>?): JsonObjectWriter = apply {
            if (value != null) enum(name, value)
        }

        fun failure(failure: DiagnosticFailure?): JsonObjectWriter = apply {
            if (failure != null) {
                string("throwable_type", failure.throwableType)
                string("stack_fingerprint_sha256", failure.stackFingerprintSha256)
            }
        }

        fun turnMetrics(
            durationMillis: Long,
            deltaCount: Int,
            deltaByteCount: Long,
        ): JsonObjectWriter = number("duration_ms", durationMillis)
            .number("delta_count", deltaCount)
            .number("delta_byte_count", deltaByteCount)

        fun finish(): String = content.append('}').toString()

        private fun prefix(name: String) {
            if (!first) content.append(',')
            first = false
            content.append('"').append(name).append("\":")
        }
    }
}
