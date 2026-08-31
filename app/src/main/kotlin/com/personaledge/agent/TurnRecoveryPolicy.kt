package com.personaledge.agent

import com.personaledge.core.agent.AgentEvent
import com.personaledge.core.agent.AgentFailureCode
import com.personaledge.core.data.TurnOutcomeFailureCode
import com.personaledge.core.data.TurnRecoveryRecord
import com.personaledge.core.data.TurnToolRisk
import com.personaledge.core.diagnostics.DiagnosticTurnCancellationCause
import com.personaledge.core.llm.LlmFailureCode
import com.personaledge.core.tools.AlarmNextTool
import com.personaledge.core.tools.AlarmSetTool
import com.personaledge.core.tools.CalendarCreateEventTool
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.CalendarUpdateEventTool
import com.personaledge.core.tools.CommitmentProposalTool
import com.personaledge.core.tools.KakaoNotificationReplyTool
import com.personaledge.core.tools.KakaoShareMessageTool
import com.personaledge.core.tools.MemoryRememberTool
import com.personaledge.core.tools.NotificationSearchTool
import com.personaledge.core.tools.ReminderCancelTool
import com.personaledge.core.tools.ReminderCreateTool
import com.personaledge.core.tools.ReminderQueryTool
import com.personaledge.core.tools.ReminderUpdateTool
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.WeatherTool
import com.personaledge.core.tools.WebSearchTool
import com.personaledge.core.tools.ToolRisk

/** Closed app-owned classification; model text can never choose recovery risk. */
internal object TurnRecoveryPolicy {
    fun toolRisk(toolName: String): TurnToolRisk = when (toolName) {
        CalendarQueryTool.NAME,
        AlarmNextTool.NAME,
        NotificationSearchTool.NAME,
        RouteEstimateTool.NAME,
        WebSearchTool.NAME,
        WeatherTool.NAME,
        ReminderQueryTool.NAME,
        -> TurnToolRisk.READ_ONLY

        MemoryRememberTool.NAME,
        CommitmentProposalTool.NAME,
        ReminderCreateTool.NAME,
        ReminderUpdateTool.NAME,
        ReminderCancelTool.NAME,
        -> TurnToolRisk.LOCAL_WRITE

        CalendarCreateEventTool.NAME,
        CalendarUpdateEventTool.NAME,
        AlarmSetTool.NAME,
        -> TurnToolRisk.DATA_WRITE

        KakaoShareMessageTool.NAME,
        KakaoNotificationReplyTool.NAME,
        -> TurnToolRisk.COMMUNICATION

        // ToolExecuted names are registry-resolved, but an unknown future entry must fail closed.
        else -> TurnToolRisk.HIGH_RISK
    }

    /** Accepts only a registry descriptor that still agrees with this app-owned closed policy. */
    fun validatedToolRisk(toolName: String, trustedRisk: ToolRisk): TurnToolRisk? {
        if (toolName !in KNOWN_TOOL_NAMES) return null
        val mapped = when (trustedRisk) {
            ToolRisk.READ_ONLY -> TurnToolRisk.READ_ONLY
            ToolRisk.LOCAL_WRITE -> TurnToolRisk.LOCAL_WRITE
            ToolRisk.DATA_WRITE -> TurnToolRisk.DATA_WRITE
            ToolRisk.COMMUNICATION -> TurnToolRisk.COMMUNICATION
            ToolRisk.VEHICLE_CONTROL,
            ToolRisk.HIGH_RISK,
            -> TurnToolRisk.HIGH_RISK
        }
        return mapped.takeIf { risk -> risk == toolRisk(toolName) }
    }

    /** Closed, bounded validation for durable read recovery identities. */
    fun validExpectedReadTools(expected: List<String>): Boolean =
        expected.size in 1..MAX_EXPECTED_READ_TOOLS &&
            expected.all { toolName ->
                toolName in KNOWN_TOOL_NAMES && toolRisk(toolName) == TurnToolRisk.READ_ONLY
            }

    /**
     * Exact list equality intentionally preserves both order and multiplicity. A set comparison
     * would accept reordered reads or silently collapse a duplicate Tool execution.
     */
    fun matchesExpectedReadTools(expected: List<String>, observed: List<String>): Boolean =
        validExpectedReadTools(expected) &&
            validExpectedReadTools(observed) &&
            expected == observed

    fun matchesExpectedReadTool(
        expected: List<String>,
        trustedOrdinal: Int,
        observedToolName: String,
    ): Boolean =
        validExpectedReadTools(expected) &&
            trustedOrdinal in 1..expected.size &&
            expected[trustedOrdinal - 1] == observedToolName

    fun failureCode(event: AgentEvent.Failure): TurnOutcomeFailureCode = when {
        event.runtimeCode == LlmFailureCode.CONTEXT_BUDGET_EXCEEDED ->
            TurnOutcomeFailureCode.CONTEXT_BUDGET_EXCEEDED
        event.code == AgentFailureCode.DEADLINE_EXCEEDED ->
            TurnOutcomeFailureCode.DEADLINE_EXCEEDED
        event.code == AgentFailureCode.TOOL_FAILED -> TurnOutcomeFailureCode.TOOL_FAILURE
        event.code in INVALID_SEQUENCE_FAILURES -> TurnOutcomeFailureCode.INVALID_MODEL_SEQUENCE
        else -> TurnOutcomeFailureCode.UNKNOWN
    }

    fun cancellationCode(cause: DiagnosticTurnCancellationCause): TurnOutcomeFailureCode =
        when (cause) {
            DiagnosticTurnCancellationCause.USER -> TurnOutcomeFailureCode.USER_CANCELLED
            DiagnosticTurnCancellationCause.THERMAL -> TurnOutcomeFailureCode.THERMAL_CANCELLED
            DiagnosticTurnCancellationCause.LIFECYCLE -> TurnOutcomeFailureCode.LIFECYCLE_CANCELLED
            DiagnosticTurnCancellationCause.UNKNOWN -> TurnOutcomeFailureCode.UNKNOWN
        }

    fun verificationGuidance(recovery: TurnRecoveryRecord): String = when (recovery.lastToolName) {
        CalendarCreateEventTool.NAME,
        CalendarUpdateEventTool.NAME,
        -> "캘린더 앱에서 해당 일정의 존재와 내용을 직접 확인하세요. 확인 전에는 같은 요청을 다시 실행하지 않습니다."
        AlarmSetTool.NAME ->
            "시계 앱의 알람 목록에서 시간과 활성 상태를 직접 확인하세요. 확인 전에는 알람을 다시 만들지 않습니다."
        KakaoShareMessageTool.NAME,
        KakaoNotificationReplyTool.NAME,
        -> "카카오톡의 대상 대화에서 실제 전송 여부를 직접 확인하세요. 앱은 메시지를 자동 재전송하지 않습니다."
        ReminderCreateTool.NAME,
        ReminderUpdateTool.NAME,
        ReminderCancelTool.NAME,
        CommitmentProposalTool.NAME,
        -> "앱의 리마인더와 일정 후보 목록에서 현재 상태를 확인하세요. 확인 전에는 변경을 반복하지 않습니다."
        MemoryRememberTool.NAME ->
            "설정의 장기 기억 목록에서 저장 여부를 확인하세요. 같은 내용을 자동으로 다시 저장하지 않습니다."
        else ->
            "대상 앱이나 서비스에서 결과를 직접 확인하세요. 상태가 확실해질 때까지 요청을 자동 재실행하지 않습니다."
    }

    private val INVALID_SEQUENCE_FAILURES = setOf(
        AgentFailureCode.INVALID_TURN,
        AgentFailureCode.TURN_MISMATCH,
        AgentFailureCode.INVALID_MODEL_SEQUENCE,
        AgentFailureCode.UNKNOWN_TOOL,
        AgentFailureCode.INVALID_TOOL_CALL,
        AgentFailureCode.TOOL_NOT_EXECUTED,
    )

    private val KNOWN_TOOL_NAMES = setOf(
        CalendarQueryTool.NAME,
        AlarmNextTool.NAME,
        NotificationSearchTool.NAME,
        RouteEstimateTool.NAME,
        WebSearchTool.NAME,
        WeatherTool.NAME,
        ReminderQueryTool.NAME,
        MemoryRememberTool.NAME,
        CommitmentProposalTool.NAME,
        ReminderCreateTool.NAME,
        ReminderUpdateTool.NAME,
        ReminderCancelTool.NAME,
        CalendarCreateEventTool.NAME,
        CalendarUpdateEventTool.NAME,
        AlarmSetTool.NAME,
        KakaoShareMessageTool.NAME,
        KakaoNotificationReplyTool.NAME,
    )

    private const val MAX_EXPECTED_READ_TOOLS = 4
}
