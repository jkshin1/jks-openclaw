package com.personaledge.core.openclaw

import kotlin.math.roundToLong
import kotlin.random.Random

/** OpenClaw 2026.8.1 reconnect series: 1, 2, 4, 8, 16, then 30 seconds. */
class OpenClawReconnectPolicy(
    private val jitterRatio: Double = 0.0,
    private val random: Random = Random.Default,
) {
    init {
        require(jitterRatio in 0.0..0.5)
    }

    fun delayMillis(attempt: Int): Long {
        require(attempt >= 0)
        val base = if (attempt >= 5) MAX_DELAY_MILLIS else INITIAL_DELAY_MILLIS shl attempt
        if (jitterRatio == 0.0) return base
        val factor = 1.0 + random.nextDouble(-jitterRatio, jitterRatio)
        return (base * factor).roundToLong().coerceIn(0L, MAX_DELAY_MILLIS)
    }

    companion object {
        const val INITIAL_DELAY_MILLIS: Long = 1_000L
        const val MAX_DELAY_MILLIS: Long = 30_000L
    }
}
