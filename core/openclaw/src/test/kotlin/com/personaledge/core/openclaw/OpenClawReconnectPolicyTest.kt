package com.personaledge.core.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OpenClawReconnectPolicyTest {
    @Test
    fun `default policy follows official capped series`() {
        val policy = OpenClawReconnectPolicy()

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map(policy::delayMillis),
        )
    }

    @Test
    fun `optional jitter remains bounded by ratio and hard cap`() {
        val policy = OpenClawReconnectPolicy(jitterRatio = 0.2, random = Random(7))

        assertTrue(policy.delayMillis(0) in 800L..1_200L)
        assertTrue(policy.delayMillis(99) in 24_000L..30_000L)
    }
}
