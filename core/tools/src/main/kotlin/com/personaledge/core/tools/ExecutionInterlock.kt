package com.personaledge.core.tools

/**
 * A precondition that a tool cannot verify from its own canonical input.
 *
 * The orchestrator carries these opaquely and hands them to the [ExecutionInterlock], so adding a
 * capability never lets a tool decide for itself whether it is allowed to run.
 */
enum class ToolCapability {
    READ_CALENDAR,
    WRITE_CALENDAR,
    SCHEDULE_ALARM,
    READ_NOTIFICATIONS,
    POST_NOTIFICATIONS,
    OPEN_KAKAO_SHARE,
    REPLY_KAKAO_NOTIFICATION,
    NETWORK,
    WRITE_MEMORY,
    WRITE_PROPOSALS,
}

enum class InterlockPhase {
    /** Before the confirmation dialog, so the user is never asked for an impossible action. */
    PREPARE,

    /** Immediately before the durable claim and the side effect itself. */
    EXECUTE,
}

data class InterlockRequest(
    val toolName: String,
    val risk: ToolRisk,
    val requiredCapabilities: Set<ToolCapability>,
    val phase: InterlockPhase,
)

sealed interface InterlockDecision {
    data object Allow : InterlockDecision

    /** [reason] is shown to the user, so it must never contain model-controlled text. */
    data class Block(val reason: String) : InterlockDecision
}

/**
 * Re-evaluated at both [InterlockPhase] points. Permission grants, thermal state, and target
 * accounts can all change while a confirmation dialog is on screen, so a decision taken during
 * preparation is never reused for execution.
 */
fun interface ExecutionInterlock {
    suspend fun evaluate(request: InterlockRequest): InterlockDecision
}

/** Default for read-only slices that hold no capabilities. It refuses to vouch for anything else. */
class CapabilityFreeInterlock : ExecutionInterlock {
    override suspend fun evaluate(request: InterlockRequest): InterlockDecision =
        if (request.requiredCapabilities.isEmpty()) {
            InterlockDecision.Allow
        } else {
            InterlockDecision.Block("이 기능에 필요한 사전 점검이 구성되지 않았습니다.")
        }
}
