package com.personaledge.core.tools

/**
 * Bounded, thread-safe replay protection for read-only and simulated tools in one app process.
 * Capacity exhaustion fails closed; this type intentionally does not claim process persistence.
 */
class InProcessActionLedger(
    private val maximumClaims: Int = DEFAULT_MAXIMUM_CLAIMS,
) : ActionLedger {
    private val lock = Any()
    private val claimedKeys = hashSetOf<String>()

    init {
        require(maximumClaims in 1..MAXIMUM_ALLOWED_CLAIMS)
    }

    override suspend fun claim(idempotencyKey: String): Boolean {
        require(idempotencyKey.isNotBlank())
        return synchronized(lock) {
            when {
                idempotencyKey in claimedKeys -> false
                claimedKeys.size >= maximumClaims -> false
                else -> claimedKeys.add(idempotencyKey)
            }
        }
    }

    private companion object {
        const val DEFAULT_MAXIMUM_CLAIMS = 4_096
        const val MAXIMUM_ALLOWED_CLAIMS = 65_536
    }
}
