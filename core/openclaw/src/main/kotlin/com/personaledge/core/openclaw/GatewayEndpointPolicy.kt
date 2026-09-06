package com.personaledge.core.openclaw

import java.net.IDN
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** A canonical, production-safe OpenClaw WebSocket endpoint. */
class GatewayEndpoint private constructor(
    val url: String,
    val stableId: String,
) {
    override fun equals(other: Any?): Boolean = other is GatewayEndpoint && url == other.url

    override fun hashCode(): Int = url.hashCode()

    override fun toString(): String = "GatewayEndpoint(<redacted>)"

    companion object {
        fun parseRelease(input: String): GatewayEndpoint? = parse(input, allowTestLoopback = false)

        /** Host-test only cleartext endpoint; never exposed to release callers. */
        internal fun parseLoopbackForTest(input: String): GatewayEndpoint? =
            parse(input, allowTestLoopback = true)

        private fun parse(input: String, allowTestLoopback: Boolean): GatewayEndpoint? {
            if (input.isBlank() ||
                input != input.trim() ||
                input.length > MAX_ENDPOINT_UTF8_BYTES ||
                input.toByteArray(Charsets.UTF_8).size > MAX_ENDPOINT_UTF8_BYTES ||
                input.any(Char::isISOControl)
            ) {
                return null
            }
            val parsed = runCatching { URI(input) }.getOrNull() ?: return null
            val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "wss" && !(allowTestLoopback && scheme == "ws")) return null
            if (parsed.rawUserInfo != null || parsed.rawQuery != null || parsed.rawFragment != null) {
                return null
            }
            if (parsed.port !in -1..65_535 || parsed.port == 0) return null

            val rawHost = parsed.host
                ?.removePrefix("[")
                ?.removeSuffix("]")
                ?.takeIf(String::isNotBlank)
                ?: return null
            if ('%' in rawHost) return null // IPv6 zone ids are not stable endpoint identities.
            val canonicalHost = canonicalHost(rawHost) ?: return null
            if (scheme == "ws" && !isLoopback(canonicalHost)) return null
            val rawPath = parsed.rawPath.orEmpty().ifEmpty { "/" }
            if (!isSafeCanonicalPath(rawPath, parsed)) return null

            val defaultPort = if (scheme == "wss") 443 else 80
            val effectivePort = if (parsed.port == -1) defaultPort else parsed.port
            val renderedHost = if (':' in canonicalHost) "[$canonicalHost]" else canonicalHost
            val renderedPort = if (effectivePort == defaultPort) "" else ":$effectivePort"
            val canonical = "$scheme://$renderedHost$renderedPort$rawPath"
            return GatewayEndpoint(url = canonical, stableId = sha256Hex(canonical))
        }

        private fun isLoopback(host: String): Boolean =
            host == "localhost" || host == "127.0.0.1" || host == "::1"

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

        private fun isSafeCanonicalPath(rawPath: String, parsed: URI): Boolean {
            if (!rawPath.startsWith('/') || '\\' in rawPath || rawPath.any(Char::isISOControl)) {
                return false
            }
            if (parsed.normalize().rawPath.orEmpty().ifEmpty { "/" } != rawPath) return false
            val lowered = rawPath.lowercase(Locale.ROOT)
            if ("%2f" in lowered || "%5c" in lowered || "%00" in lowered) return false
            return rawPath.all { character ->
                character.code in 0x21..0x7e && (character.isLetterOrDigit() || character in PATH_PUNCTUATION)
            }
        }

        private fun sha256Hex(value: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

        private const val PATH_PUNCTUATION = "/-._~!$&'()*+,;=:@%"
        private const val MAX_ENDPOINT_UTF8_BYTES = 2_048
    }
}
