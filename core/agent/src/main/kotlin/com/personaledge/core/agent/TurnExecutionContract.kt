package com.personaledge.core.agent

/**
 * Content-free contract pinning a recovery turn to the trusted READ_ONLY Tool multiset observed
 * before interruption. Arguments are intentionally not pinned because relative date/time inputs
 * must be recomputed against the fresh turn context.
 */
class TurnExecutionContract private constructor(
    expectedReadTools: List<String>,
) {
    internal val expectedReadTools: List<String> = expectedReadTools.toList()

    init {
        require(this.expectedReadTools.size in 1..MAX_EXPECTED_READS)
        require(this.expectedReadTools.all(TOOL_NAME::matches))
    }

    internal fun newState(): TurnExecutionContractState =
        TurnExecutionContractState(expectedReadTools)

    override fun toString(): String =
        "TurnExecutionContract(expectedReadCount=${expectedReadTools.size})"

    companion object {
        fun exactReads(toolNames: List<String>): TurnExecutionContract =
            TurnExecutionContract(toolNames)

        const val MAX_EXPECTED_READS: Int = 4
        private val TOOL_NAME = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

/** Ordered reservation plus order-independent completion for a parallel AgentPlan callback. */
internal class TurnExecutionContractState(
    expectedReadTools: List<String>,
) {
    private val lock = Any()
    private val expectedTools = expectedReadTools.toList()
    private val expectedCounts = expectedTools.groupingBy(String::toString).eachCount()
    private val reservedTools = mutableListOf<String>()
    private val completedCounts = mutableMapOf<String, Int>()

    fun reserve(toolName: String): Boolean = synchronized(lock) {
        if (expectedTools.getOrNull(reservedTools.size) != toolName) return@synchronized false
        reservedTools += toolName
        true
    }

    fun reserveAll(toolNames: List<String>): Boolean = synchronized(lock) {
        if (toolNames != expectedTools || reservedTools.isNotEmpty()) return@synchronized false
        reservedTools.addAll(toolNames)
        true
    }

    fun complete(toolName: String): Boolean = synchronized(lock) {
        val reserved = reservedTools.count(toolName::equals)
        if (reserved == 0) return@synchronized false
        val completed = completedCounts[toolName] ?: 0
        if (completed >= reserved) return@synchronized false
        completedCounts[toolName] = completed + 1
        true
    }

    fun isSatisfied(): Boolean = synchronized(lock) {
        reservedTools == expectedTools && completedCounts == expectedCounts
    }

    override fun toString(): String = synchronized(lock) {
        "TurnExecutionContractState(expected=${expectedCounts.values.sum()}, " +
            "reserved=${reservedTools.size}, completed=${completedCounts.values.sum()})"
    }
}
