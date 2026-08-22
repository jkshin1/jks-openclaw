package com.personaledge.agent

import com.personaledge.core.agent.AgentFailureCode
import com.personaledge.core.diagnostics.DiagnosticConfirmationOutcome
import com.personaledge.core.diagnostics.DiagnosticErrorCode
import com.personaledge.core.diagnostics.DiagnosticEvent
import com.personaledge.core.diagnostics.DiagnosticFailure
import com.personaledge.core.diagnostics.DiagnosticSink
import com.personaledge.core.diagnostics.DiagnosticToolName
import com.personaledge.core.diagnostics.DiagnosticToolRisk
import com.personaledge.core.diagnostics.DiagnosticToolStage
import com.personaledge.core.llm.LlmFailureCode
import com.personaledge.core.llm.ModelStoreErrorCode
import com.personaledge.core.tools.AlarmNextTool
import com.personaledge.core.tools.AlarmSetTool
import com.personaledge.core.tools.CalendarCreateEventTool
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.CalendarUpdateEventTool
import com.personaledge.core.tools.FakeArrivalNoticeTool
import com.personaledge.core.tools.NotificationSearchTool
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.WebSearchTool

/** Diagnostics are best-effort and must never alter the app's control flow. */
internal fun DiagnosticSink.recordSafely(event: DiagnosticEvent): Boolean =
    runCatching { record(event) }.getOrDefault(false)

/** Failure fingerprinting is diagnostic work too, so even allocation failure stays isolated. */
internal fun Throwable.toDiagnosticFailureOrNull(): DiagnosticFailure? =
    try {
        DiagnosticFailure.from(this)
    } catch (_: Throwable) {
        null
    }

private data class KnownDiagnosticTool(
    val name: DiagnosticToolName,
    val risk: DiagnosticToolRisk,
)

/**
 * Closed allowlist matching the demonstration registry and the eight tools shipped on-device.
 * Invalid constants fail closed during construction, and model-originated names can never add an
 * entry. Arguments, previews, action IDs and confirmation digests are deliberately not retained.
 */
private val knownDiagnosticTools: Map<String, KnownDiagnosticTool> = listOf(
    FakeArrivalNoticeTool.NAME to DiagnosticToolRisk.READ_ONLY,
    CalendarQueryTool.NAME to DiagnosticToolRisk.READ_ONLY,
    CalendarCreateEventTool.NAME to DiagnosticToolRisk.DATA_WRITE,
    CalendarUpdateEventTool.NAME to DiagnosticToolRisk.DATA_WRITE,
    AlarmSetTool.NAME to DiagnosticToolRisk.DATA_WRITE,
    AlarmNextTool.NAME to DiagnosticToolRisk.READ_ONLY,
    NotificationSearchTool.NAME to DiagnosticToolRisk.READ_ONLY,
    RouteEstimateTool.NAME to DiagnosticToolRisk.READ_ONLY,
    WebSearchTool.NAME to DiagnosticToolRisk.READ_ONLY,
).mapNotNull { (name, risk) ->
    DiagnosticToolName.parse(name)?.let { safeName ->
        name to KnownDiagnosticTool(name = safeName, risk = risk)
    }
}.toMap()

internal fun DiagnosticSink.recordKnownToolPhase(
    toolName: String,
    stage: DiagnosticToolStage,
    outcome: DiagnosticConfirmationOutcome,
): Boolean {
    val tool = knownDiagnosticTools[toolName] ?: return false
    return recordSafely(
        DiagnosticEvent.ToolPhase(
            name = tool.name,
            stage = stage,
            risk = tool.risk,
            confirmationOutcome = outcome,
        ),
    )
}

internal fun ModelStoreErrorCode.toDiagnosticErrorCode(): DiagnosticErrorCode = when (this) {
    ModelStoreErrorCode.SOURCE_UNAVAILABLE -> DiagnosticErrorCode.SOURCE_UNAVAILABLE
    ModelStoreErrorCode.INSUFFICIENT_STORAGE -> DiagnosticErrorCode.INSUFFICIENT_STORAGE
    ModelStoreErrorCode.INVALID_SIZE -> DiagnosticErrorCode.INVALID_SIZE
    ModelStoreErrorCode.INVALID_DIGEST -> DiagnosticErrorCode.INVALID_DIGEST
    ModelStoreErrorCode.INVALID_FILE_TYPE -> DiagnosticErrorCode.INVALID_FILE_TYPE
    ModelStoreErrorCode.STORAGE_FAILURE -> DiagnosticErrorCode.STORAGE_FAILURE
}

internal fun LlmFailureCode.toDiagnosticErrorCode(): DiagnosticErrorCode = when (this) {
    LlmFailureCode.CLOSED -> DiagnosticErrorCode.CLOSED
    LlmFailureCode.NOT_INITIALIZED -> DiagnosticErrorCode.NOT_INITIALIZED
    LlmFailureCode.ALREADY_INITIALIZED -> DiagnosticErrorCode.ALREADY_INITIALIZED
    LlmFailureCode.MODEL_REJECTED -> DiagnosticErrorCode.MODEL_REJECTED
    LlmFailureCode.CACHE_REJECTED -> DiagnosticErrorCode.CACHE_REJECTED
    LlmFailureCode.INVALID_BACKEND -> DiagnosticErrorCode.INVALID_BACKEND
    LlmFailureCode.INITIALIZATION_FAILED -> DiagnosticErrorCode.INITIALIZATION_FAILED
    LlmFailureCode.RUNTIME_BUSY -> DiagnosticErrorCode.RUNTIME_BUSY
    LlmFailureCode.INVALID_TURN_ID -> DiagnosticErrorCode.INVALID_TURN
    LlmFailureCode.TURN_REPLAYED -> DiagnosticErrorCode.TURN_REPLAYED
    LlmFailureCode.TURN_MISMATCH -> DiagnosticErrorCode.TURN_MISMATCH
    LlmFailureCode.INVALID_PROMPT -> DiagnosticErrorCode.INVALID_PROMPT
    LlmFailureCode.INVALID_TOOL_DEFINITION -> DiagnosticErrorCode.INVALID_TOOL_DEFINITION
    LlmFailureCode.INVALID_TOOL_CALL -> DiagnosticErrorCode.INVALID_TOOL_CALL
    LlmFailureCode.INVALID_TOOL_RESPONSE -> DiagnosticErrorCode.INVALID_TOOL_RESPONSE
    LlmFailureCode.NO_PENDING_TOOL_CALLS -> DiagnosticErrorCode.NO_PENDING_TOOL_CALLS
    LlmFailureCode.CONTEXT_BUDGET_EXCEEDED -> DiagnosticErrorCode.CONTEXT_BUDGET_EXCEEDED
    LlmFailureCode.NATIVE_FAILURE -> DiagnosticErrorCode.NATIVE_FAILURE
}

internal fun AgentFailureCode.toDiagnosticErrorCode(): DiagnosticErrorCode = when (this) {
    AgentFailureCode.BUSY -> DiagnosticErrorCode.RUNTIME_BUSY
    AgentFailureCode.INVALID_TURN -> DiagnosticErrorCode.INVALID_TURN
    AgentFailureCode.TURN_MISMATCH -> DiagnosticErrorCode.TURN_MISMATCH
    AgentFailureCode.INVALID_MODEL_SEQUENCE -> DiagnosticErrorCode.INVALID_MODEL_SEQUENCE
    AgentFailureCode.MODEL_FAILURE -> DiagnosticErrorCode.MODEL_FAILURE
    AgentFailureCode.STEP_LIMIT_EXCEEDED -> DiagnosticErrorCode.STEP_LIMIT_EXCEEDED
    AgentFailureCode.TOOL_CALL_LIMIT_EXCEEDED -> DiagnosticErrorCode.TOOL_CALL_LIMIT_EXCEEDED
    AgentFailureCode.UNKNOWN_TOOL -> DiagnosticErrorCode.UNKNOWN_TOOL
    AgentFailureCode.INVALID_TOOL_CALL -> DiagnosticErrorCode.INVALID_TOOL_CALL
    AgentFailureCode.TOOL_NOT_EXECUTED -> DiagnosticErrorCode.TOOL_NOT_EXECUTED
    AgentFailureCode.DEADLINE_EXCEEDED -> DiagnosticErrorCode.DEADLINE_EXCEEDED
}

internal fun elapsedMillisSince(startedAtMillis: Long, nowMillis: Long): Long =
    (nowMillis - startedAtMillis).coerceAtLeast(0L)
