package com.personaledge.core.data

import java.net.IDN
import java.net.URI
import java.util.Locale

/** Trust anchors the owner may choose for the remote OpenClaw Gateway. */
enum class OpenClawGatewayTrustMode {
    /** Normal platform CA and hostname verification. */
    SYSTEM,

    /** Normal TLS plus an exact SHA-256 fingerprint of the leaf certificate DER bytes. */
    PINNED_CERT_SHA256,
}

/**
 * Durable, non-secret connection policy for an OpenClaw Gateway.
 *
 * The feature is disabled until the owner both configures a valid endpoint and opts in. The
 * current integration is deliberately foreground-only; accepting `false` here would let a future
 * caller silently broaden the data-transfer boundary without a new policy decision.
 */
data class OpenClawGatewaySettings(
    val enabled: Boolean = false,
    val endpointUrl: String? = null,
    val trustMode: OpenClawGatewayTrustMode = OpenClawGatewayTrustMode.SYSTEM,
    val leafCertificateDerSha256: String? = null,
    val foregroundOnly: Boolean = true,
) {
    init {
        validateOpenClawGatewayConnectionPolicy(
            endpointUrl = endpointUrl,
            trustMode = trustMode,
            leafCertificateDerSha256 = leafCertificateDerSha256,
            foregroundOnly = foregroundOnly,
        )
        require(!enabled || endpointUrl != null) {
            "OpenClaw Gateway cannot be enabled without an endpoint."
        }
    }

    /** Endpoint and fingerprint are intentionally absent from logs and diagnostics. */
    override fun toString(): String =
        "OpenClawGatewaySettings(enabled=$enabled, " +
            "endpointConfigured=${endpointUrl != null}, trustMode=$trustMode, " +
            "leafFingerprintConfigured=${leafCertificateDerSha256 != null}, " +
            "foregroundOnly=$foregroundOnly)"
}

/**
 * Non-secret Gateway connection policy that deliberately cannot carry owner consent.
 *
 * Callers may build this value from a stale UI snapshot without being able to reopen a Gateway
 * the owner has since disabled. [SettingsRepository] merges it with the latest durable consent in
 * the same DataStore transaction.
 */
data class OpenClawGatewayConnectionPolicy(
    val endpointUrl: String? = null,
    val trustMode: OpenClawGatewayTrustMode = OpenClawGatewayTrustMode.SYSTEM,
    val leafCertificateDerSha256: String? = null,
    val foregroundOnly: Boolean = true,
) {
    init {
        validateOpenClawGatewayConnectionPolicy(
            endpointUrl = endpointUrl,
            trustMode = trustMode,
            leafCertificateDerSha256 = leafCertificateDerSha256,
            foregroundOnly = foregroundOnly,
        )
    }

    /** Endpoint and fingerprint are intentionally absent from logs and diagnostics. */
    override fun toString(): String =
        "OpenClawGatewayConnectionPolicy(" +
            "endpointConfigured=${endpointUrl != null}, trustMode=$trustMode, " +
            "leafFingerprintConfigured=${leafCertificateDerSha256 != null}, " +
            "foregroundOnly=$foregroundOnly)"
}

/** Applies connection policy while retaining the receiver's latest owner-consent state. */
internal fun OpenClawGatewaySettings.withConnectionPolicy(
    policy: OpenClawGatewayConnectionPolicy,
): OpenClawGatewaySettings = copy(
    endpointUrl = policy.endpointUrl,
    trustMode = policy.trustMode,
    leafCertificateDerSha256 = policy.leafCertificateDerSha256,
    foregroundOnly = policy.foregroundOnly,
)

private fun validateOpenClawGatewayConnectionPolicy(
    endpointUrl: String?,
    trustMode: OpenClawGatewayTrustMode,
    leafCertificateDerSha256: String?,
    foregroundOnly: Boolean,
) {
    require(endpointUrl == null || OpenClawGatewaySettingsPolicy.isSafeEndpointUrl(endpointUrl)) {
        "OpenClaw Gateway endpoint must be a bounded wss URL without credentials or a query."
    }
    require(foregroundOnly) {
        "Background OpenClaw Gateway use is not supported."
    }
    when (trustMode) {
        OpenClawGatewayTrustMode.SYSTEM -> require(leafCertificateDerSha256 == null) {
            "A certificate fingerprint is only valid with pinned-certificate trust."
        }

        OpenClawGatewayTrustMode.PINNED_CERT_SHA256 -> require(
            leafCertificateDerSha256 != null &&
                OpenClawGatewaySettingsPolicy.isSha256Fingerprint(
                    leafCertificateDerSha256,
                ),
        ) {
            "Pinned-certificate trust requires an exact 64-digit SHA-256 fingerprint."
        }
    }
}

/** Pure validation shared by construction and corrupt-DataStore recovery. */
internal object OpenClawGatewaySettingsPolicy {
    const val MAX_ENDPOINT_UTF8_BYTES: Int = 2_048

    private val SHA256_HEX = Regex("[0-9A-Fa-f]{64}")
    private const val PATH_PUNCTUATION = "/-._~!$&'()*+,;=:@%"

    fun isSha256Fingerprint(value: String): Boolean = SHA256_HEX.matches(value)

    fun isSafeEndpointUrl(value: String): Boolean {
        if (
            value.isBlank() ||
            value != value.trim() ||
            value.toByteArray(Charsets.UTF_8).size > MAX_ENDPOINT_UTF8_BYTES ||
            value.any(Char::isISOControl)
        ) {
            return false
        }

        val parsed = runCatching { URI(value) }.getOrNull() ?: return false
        if (!parsed.scheme.equals("wss", ignoreCase = true)) return false
        if (parsed.rawUserInfo != null || parsed.rawQuery != null || parsed.rawFragment != null) {
            return false
        }
        if (parsed.port !in -1..65_535 || parsed.port == 0) return false

        val rawHost = parsed.host?.takeIf(String::isNotBlank) ?: return false
        if ('%' in rawHost || canonicalHost(rawHost) == null) return false

        val rawPath = parsed.rawPath.orEmpty().ifEmpty { "/" }
        if (!rawPath.startsWith('/') || '\\' in rawPath || rawPath.any(Char::isISOControl)) {
            return false
        }
        if (parsed.normalize().rawPath.orEmpty().ifEmpty { "/" } != rawPath) return false
        val loweredPath = rawPath.lowercase(Locale.ROOT)
        if ("%2f" in loweredPath || "%5c" in loweredPath || "%00" in loweredPath) return false
        return rawPath.all { character ->
            character.code in 0x21..0x7e &&
                (character.isLetterOrDigit() || character in PATH_PUNCTUATION)
        }
    }

    private fun canonicalHost(host: String): String? {
        val lowered = host.lowercase(Locale.ROOT)
        if (':' in lowered) {
            return lowered.takeIf { candidate ->
                candidate.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }
            }
        }
        return runCatching { IDN.toASCII(lowered, IDN.USE_STD3_ASCII_RULES) }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it.length <= 253 }
    }
}
