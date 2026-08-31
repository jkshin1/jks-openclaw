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
 * Closed allowlist matching the demonstration registry and every tool shipped on-device.
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
    WeatherTool.NAME to DiagnosticToolRisk.READ_ONLY,
    KakaoShareMessageTool.NAME to DiagnosticToolRisk.COMMUNICATION,
    KakaoNotificationReplyTool.NAME to DiagnosticToolRisk.COMMUNICATION,
    MemoryRememberTool.NAME to DiagnosticToolRisk.LOCAL_WRITE,
    CommitmentProposalTool.NAME to DiagnosticToolRisk.LOCAL_WRITE,
    ReminderCreateTool.NAME to DiagnosticToolRisk.LOCAL_WRITE,
    ReminderUpdateTool.NAME to DiagnosticToolRisk.LOCAL_WRITE,
    ReminderCancelTool.NAME to DiagnosticToolRisk.LOCAL_WRITE,
    ReminderQueryTool.NAME to DiagnosticToolRisk.READ_ONLY,
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
    AgentFailureCode.TOOL_FAILED -> DiagnosticErrorCode.TOOL_FAILED
    AgentFailureCode.DEADLINE_EXCEEDED -> DiagnosticErrorCode.DEADLINE_EXCEEDED
}

internal fun ToolFailureCode.toDiagnosticErrorCode(): DiagnosticErrorCode = when (this) {
    ToolFailureCode.CREDENTIALS_MISSING -> DiagnosticErrorCode.TOOL_CREDENTIALS_MISSING
    ToolFailureCode.AUTHENTICATION_FAILED -> DiagnosticErrorCode.TOOL_AUTHENTICATION_FAILED
    ToolFailureCode.PERMISSION_DENIED -> DiagnosticErrorCode.TOOL_PERMISSION_DENIED
    ToolFailureCode.API_DISABLED_OR_QUOTA_EXCEEDED ->
        DiagnosticErrorCode.TOOL_API_DISABLED_OR_QUOTA_EXCEEDED
    ToolFailureCode.RATE_LIMITED -> DiagnosticErrorCode.TOOL_RATE_LIMITED
    ToolFailureCode.INVALID_REQUEST -> DiagnosticErrorCode.TOOL_INVALID_REQUEST
    ToolFailureCode.REMINDER_INVALID_TITLE -> DiagnosticErrorCode.TOOL_REMINDER_INVALID_TITLE
    ToolFailureCode.REMINDER_INVALID_TIME_ZONE ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_TIME_ZONE
    ToolFailureCode.REMINDER_INVALID_DATE_TIME ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_FORMAT ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_FORMAT
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_OFFSET_CONFLICT ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_OFFSET_CONFLICT
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_ZERO_SECONDS ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_NON_ZERO_SECONDS
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_DATE_ONLY ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_DATE_ONLY
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NATURAL_LANGUAGE ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_NATURAL_LANGUAGE
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_OTHER_FORMAT ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_OTHER_FORMAT
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_EMPTY ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_EMPTY
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_PLACEHOLDER ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_PLACEHOLDER
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_TEXT ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_TEXT
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_RELATIVE ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_RELATIVE
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_MONTH_NAME ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_MONTH_NAME
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_AM_PM ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_AM_PM
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ZONE_LABEL ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_ZONE_LABEL
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_NUMERIC ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_NUMERIC
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_LIKE ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_ISO_LIKE
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ALPHA ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_ISO_ALPHA
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_NO_COLON ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_ISO_NO_COLON
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_SUFFIX_SYMBOLS ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_SUFFIX_SYMBOLS
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_EXTRA_DIGITS ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_EXTRA_DIGITS
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_OTHER ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_OTHER
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_MULTI_COLON ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_ISO_MULTI_COLON
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_OTHER ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_ASCII_OTHER
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_HANGUL_UNICODE ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_NON_HANGUL_UNICODE
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_VALUE ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_VALUE
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_DST ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_DST
    ToolFailureCode.REMINDER_INVALID_DATE_TIME_RANGE ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_DATE_TIME_RANGE
    ToolFailureCode.REMINDER_INVALID_RECURRENCE ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_RECURRENCE
    ToolFailureCode.REMINDER_INVALID_PRECISION ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_PRECISION
    ToolFailureCode.REMINDER_INVALID_LEAD_TIME ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_LEAD_TIME
    ToolFailureCode.REMINDER_INVALID_ESCALATION ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_ESCALATION
    ToolFailureCode.REMINDER_INVALID_IDENTITY ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_IDENTITY
    ToolFailureCode.REMINDER_INVALID_QUERY_LIMIT ->
        DiagnosticErrorCode.TOOL_REMINDER_INVALID_QUERY_LIMIT
    ToolFailureCode.PLACE_NOT_FOUND -> DiagnosticErrorCode.TOOL_PLACE_NOT_FOUND
    ToolFailureCode.SAME_LOCATION -> DiagnosticErrorCode.TOOL_SAME_LOCATION
    ToolFailureCode.POINT_NOT_NEAR_ROAD -> DiagnosticErrorCode.TOOL_POINT_NOT_NEAR_ROAD
    ToolFailureCode.NO_DRIVING_ROUTE -> DiagnosticErrorCode.TOOL_NO_DRIVING_ROUTE
    ToolFailureCode.ROUTE_TOO_LONG -> DiagnosticErrorCode.TOOL_ROUTE_TOO_LONG
    ToolFailureCode.ENDPOINT_NOT_FOUND -> DiagnosticErrorCode.TOOL_ENDPOINT_NOT_FOUND
    ToolFailureCode.REQUEST_TOO_LARGE -> DiagnosticErrorCode.TOOL_REQUEST_TOO_LARGE
    ToolFailureCode.NETWORK_FAILURE -> DiagnosticErrorCode.TOOL_NETWORK_FAILURE
    ToolFailureCode.PROVIDER_TIMEOUT -> DiagnosticErrorCode.TOOL_PROVIDER_TIMEOUT
    ToolFailureCode.PROVIDER_UNAVAILABLE -> DiagnosticErrorCode.TOOL_PROVIDER_UNAVAILABLE
    ToolFailureCode.MALFORMED_RESPONSE -> DiagnosticErrorCode.TOOL_MALFORMED_RESPONSE
    ToolFailureCode.CLIENT_POLICY_FAILURE -> DiagnosticErrorCode.TOOL_CLIENT_POLICY_FAILURE
    ToolFailureCode.OTHER_PROVIDER_ERROR -> DiagnosticErrorCode.TOOL_OTHER_PROVIDER_ERROR
}

internal fun elapsedMillisSince(startedAtMillis: Long, nowMillis: Long): Long =
    (nowMillis - startedAtMillis).coerceAtLeast(0L)
