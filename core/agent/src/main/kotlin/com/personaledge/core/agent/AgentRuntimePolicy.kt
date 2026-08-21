package com.personaledge.core.agent

/**
 * Budget for one top-level request.
 *
 * The fake-only slice allowed a single tool call in two model steps. Real calendar work needs one
 * more round trip — read the day, then write to it — so the loop allows two calls across four
 * steps. The deadline is generous because CPU decode on the Fold8 is the slow part, not the tool;
 * revisit these numbers with physical-device latency receipts rather than by feel.
 */
data class AgentLoopLimits(
    val maxSteps: Int = 4,
    val deadlineMillis: Long = 120_000,
    val maxOutputTokens: Int = 1_024,
    val maxToolCalls: Int = 2,
    val maxToolArgumentBytes: Int = 2_048,
) {
    init {
        require(maxSteps in 1..20)
        require(deadlineMillis in 1_000..300_000)
        require(maxOutputTokens in 1..4_000)
        require(maxToolCalls in 1..maxSteps)
        require(maxToolArgumentBytes in 64..16_384)
    }
}
