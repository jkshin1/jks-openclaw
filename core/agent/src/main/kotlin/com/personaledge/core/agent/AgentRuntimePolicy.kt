package com.personaledge.core.agent

data class AgentLoopLimits(
    val maxSteps: Int = 2,
    val deadlineMillis: Long = 60_000,
    val maxOutputTokens: Int = 1_024,
    val maxToolCalls: Int = 1,
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
