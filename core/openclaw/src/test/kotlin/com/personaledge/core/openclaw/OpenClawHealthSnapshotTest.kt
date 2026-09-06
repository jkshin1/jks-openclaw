package com.personaledge.core.openclaw

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenClawHealthSnapshotTest {
    @Test
    fun `valid snapshots preserve unhealthy observations instead of claiming current health`() {
        val payload = valid().apply { addProperty("dockerHealthy", false) }
        val snapshot = requireNotNull(OpenClawHealthSnapshot.parse(payload, NOW))
        assertEquals(NOW, snapshot.observedAtEpochMillis)
        assertFalse(snapshot.healthy)
        assertFalse(snapshot.dockerHealthy)
    }

    @Test
    fun `freshness accepts exact boundaries and rejects stale future or invalid clocks`() {
        for (delta in listOf(-600_000L, 5_000L)) {
            assertNotNull(OpenClawHealthSnapshot.parse(valid(NOW + delta), NOW))
        }
        for (delta in listOf(-600_001L, 5_001L)) {
            assertNull(OpenClawHealthSnapshot.parse(valid(NOW + delta), NOW))
        }
        for (clock in listOf(0L, -1L, Long.MAX_VALUE)) {
            assertNull(OpenClawHealthSnapshot.parse(valid(), clock))
        }
    }

    @Test
    fun `schema is exact and never coerces text numbers or nested flags`() {
        val mutations: List<(JsonObject) -> Unit> = listOf(
            { it.addProperty("schemaVersion", 2) },
            { it.addProperty("schemaVersion", "1") },
            { it.addProperty("schemaVersion", 1.0) },
            { it.addProperty("observedAtEpochMillis", NOW.toString()) },
            { it.addProperty("observedAtEpochMillis", 9_007_199_254_740_992L) },
            { it.addProperty("observedAtEpochMillis", NOW + 0.5) },
            { it.add("observedAtEpochMillis", JsonParser.parseString("1.8e12")) },
            { it.addProperty("gatewayHealthy", "true") },
            { it.add("dockerHealthy", JsonObject()) },
            { it.add("policyValid", JsonNull.INSTANCE) },
            { it.remove("secretsClean") },
            { it.addProperty("secret", "untrusted-content") },
        )
        for (mutate in mutations) {
            assertNull(OpenClawHealthSnapshot.parse(valid().apply(mutate), NOW))
        }
        for (value in listOf(null, JsonNull.INSTANCE, JsonPrimitive("untrusted-content"))) {
            assertNull(OpenClawHealthSnapshot.parse(value, NOW))
        }
    }

    private fun valid(observed: Long = NOW): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("observedAtEpochMillis", observed)
        addProperty("gatewayHealthy", true)
        addProperty("dockerHealthy", true)
        addProperty("policyValid", true)
        addProperty("secretsClean", true)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
