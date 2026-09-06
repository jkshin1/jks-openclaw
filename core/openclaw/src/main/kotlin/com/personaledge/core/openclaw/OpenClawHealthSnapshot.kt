package com.personaledge.core.openclaw

import com.google.gson.JsonElement

/** Content-free last watchdog observation. It is not a live probe or uptime guarantee. */
data class OpenClawHealthSnapshot internal constructor(
    val observedAtEpochMillis: Long,
    val gatewayHealthy: Boolean,
    val dockerHealthy: Boolean,
    val policyValid: Boolean,
    val secretsClean: Boolean,
) {
    val healthy: Boolean
        get() = gatewayHealthy && dockerHealthy && policyValid && secretsClean

    companion object {
        const val MAX_AGE_MILLIS: Long = 600_000L
        const val MAX_FUTURE_SKEW_MILLIS: Long = 5_000L
        private const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L
        private val integer = Regex("[1-9][0-9]{0,15}")
        private val fields = setOf(
            "schemaVersion", "observedAtEpochMillis", "gatewayHealthy", "dockerHealthy",
            "policyValid", "secretsClean",
        )

        internal fun parse(payload: JsonElement?, nowEpochMillis: Long): OpenClawHealthSnapshot? {
            if (nowEpochMillis !in 1L..MAX_SAFE_INTEGER) return null
            val obj = payload?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            if (obj.keySet() != fields) return null
            val schema = obj["schemaVersion"]
            if (!schema.isJsonPrimitive || !schema.asJsonPrimitive.isNumber || schema.asString != "1") {
                return null
            }
            val observedElement = obj["observedAtEpochMillis"]
            if (!observedElement.isJsonPrimitive || !observedElement.asJsonPrimitive.isNumber) return null
            val observedText = observedElement.asString
            if (!integer.matches(observedText)) return null
            val observed = observedText.toLongOrNull()?.takeIf { it <= MAX_SAFE_INTEGER } ?: return null
            if (observed < nowEpochMillis - MAX_AGE_MILLIS ||
                observed > nowEpochMillis + MAX_FUTURE_SKEW_MILLIS
            ) return null
            fun flag(name: String): Boolean? = obj[name]
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.asBoolean
            return OpenClawHealthSnapshot(
                observedAtEpochMillis = observed,
                gatewayHealthy = flag("gatewayHealthy") ?: return null,
                dockerHealthy = flag("dockerHealthy") ?: return null,
                policyValid = flag("policyValid") ?: return null,
                secretsClean = flag("secretsClean") ?: return null,
            )
        }
    }
}

/** No raw response/error prose or unvalidated JSON crosses the optional health boundary. */
sealed interface OpenClawHealthReadResult {
    data class Success(val snapshot: OpenClawHealthSnapshot) : OpenClawHealthReadResult
    data object NotConnected : OpenClawHealthReadResult
    data object Unsupported : OpenClawHealthReadResult
    data object Unavailable : OpenClawHealthReadResult
    data object InvalidSnapshot : OpenClawHealthReadResult
}
