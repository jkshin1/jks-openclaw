package com.personaledge.core.tools

import java.util.Base64

/**
 * Deterministic encoding for the immutable snapshot shared by the confirmation dialog and
 * execution.
 *
 * Keys are sorted and every value is Base64url encoded, so no field value can forge a separator,
 * inject another field, or change the digest by reordering. Absent fields stay absent rather than
 * becoming empty strings, which keeps "no change requested" distinguishable from "clear this".
 */
internal object CanonicalFields {
    private const val PAIR_SEPARATOR = "."
    private const val KEY_VALUE_SEPARATOR = "~"

    fun encode(fields: Map<String, String>): String {
        require(fields.keys.all { key -> key.isNotEmpty() && key.all(::isAllowedKeyCharacter) }) {
            "Canonical field keys must be non-empty ASCII identifiers."
        }
        return fields.entries
            .sortedBy { entry -> entry.key }
            .joinToString(PAIR_SEPARATOR) { (key, value) ->
                base64(key) + KEY_VALUE_SEPARATOR + base64(value)
            }
    }

    fun decode(input: CanonicalToolInput): Map<String, String> {
        if (input.encoded.isEmpty()) return emptyMap()

        val decoded = input.encoded.split(PAIR_SEPARATOR).associate { pair ->
            val parts = pair.split(KEY_VALUE_SEPARATOR)
            check(parts.size == 2) { "Malformed canonical tool input." }
            unBase64(parts[0]) to unBase64(parts[1])
        }
        check(decoded.size == input.encoded.split(PAIR_SEPARATOR).size) {
            "Duplicate key in canonical tool input."
        }
        return decoded
    }

    private fun isAllowedKeyCharacter(character: Char): Boolean =
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '_'

    private fun base64(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun unBase64(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}

internal fun Map<String, String>.requiredLong(key: String): Long =
    this[key]?.toLongOrNull() ?: error("Canonical field $key is missing or not a number.")

internal fun Map<String, String>.requiredString(key: String): String =
    this[key] ?: error("Canonical field $key is missing.")
