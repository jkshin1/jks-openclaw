package com.personaledge.core.agent

import com.personaledge.core.tools.FakeArrivalNoticeParams
import com.personaledge.core.tools.FakeArrivalNoticeResult

internal enum class ToolArgumentsError {
    OVERSIZED,
    MALFORMED_JSON,
    NESTING_NOT_ALLOWED,
    UNKNOWN_FIELD,
    DUPLICATE_FIELD,
    WRONG_TYPE,
    MISSING_FIELD,
    UNSAFE_TEXT,
}

internal sealed interface ToolArgumentsParseResult {
    data class Valid(val params: FakeArrivalNoticeParams) : ToolArgumentsParseResult

    data class Invalid(val error: ToolArgumentsError) : ToolArgumentsParseResult
}

/** Strict, flat JSON parser for the single fake-tool contract used by the first vertical slice. */
internal class FakeArrivalNoticeArgumentsParser(
    private val maxArgumentBytes: Int,
) {
    init {
        require(maxArgumentBytes in 64..16_384)
    }

    fun parse(json: String): ToolArgumentsParseResult {
        if (json.length > maxArgumentBytes) {
            return ToolArgumentsParseResult.Invalid(ToolArgumentsError.OVERSIZED)
        }
        if (json.toByteArray(Charsets.UTF_8).size > maxArgumentBytes) {
            return ToolArgumentsParseResult.Invalid(ToolArgumentsError.OVERSIZED)
        }

        return try {
            val fields = FlatStringObjectParser(json).parse()
            val unknown = fields.keys - REQUIRED_FIELDS
            if (unknown.isNotEmpty()) {
                ToolArgumentsParseResult.Invalid(ToolArgumentsError.UNKNOWN_FIELD)
            } else if (!fields.keys.containsAll(REQUIRED_FIELDS)) {
                ToolArgumentsParseResult.Invalid(ToolArgumentsError.MISSING_FIELD)
            } else if (fields.values.any(::containsUnsafeModelText)) {
                ToolArgumentsParseResult.Invalid(ToolArgumentsError.UNSAFE_TEXT)
            } else {
                ToolArgumentsParseResult.Valid(
                    FakeArrivalNoticeParams(
                        recipient = fields.getValue(RECIPIENT),
                        message = fields.getValue(MESSAGE),
                    ),
                )
            }
        } catch (failure: ParseFailure) {
            ToolArgumentsParseResult.Invalid(failure.error)
        }
    }

    private class FlatStringObjectParser(
        private val source: String,
    ) {
        private var offset = 0

        fun parse(): Map<String, String> {
            skipWhitespace()
            expect('{')
            skipWhitespace()

            val result = linkedMapOf<String, String>()
            if (consumeIf('}')) {
                finishDocument()
                return result
            }

            while (true) {
                skipWhitespace()
                val key = parseString()
                if (result.containsKey(key)) {
                    fail(ToolArgumentsError.DUPLICATE_FIELD)
                }

                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = when (peek()) {
                    '"' -> parseString()
                    '{', '[' -> fail(ToolArgumentsError.NESTING_NOT_ALLOWED)
                    else -> fail(ToolArgumentsError.WRONG_TYPE)
                }
                result[key] = value

                skipWhitespace()
                when {
                    consumeIf(',') -> Unit
                    consumeIf('}') -> {
                        finishDocument()
                        return result
                    }
                    else -> fail(ToolArgumentsError.MALFORMED_JSON)
                }
            }
        }

        private fun finishDocument() {
            skipWhitespace()
            if (offset != source.length) {
                fail(ToolArgumentsError.MALFORMED_JSON)
            }
        }

        private fun parseString(): String {
            expect('"')
            val value = StringBuilder()

            while (offset < source.length) {
                val character = source[offset++]
                when {
                    character == '"' -> return value.toString()
                    character == '\\' -> appendEscape(value)
                    character.code < 0x20 -> fail(ToolArgumentsError.MALFORMED_JSON)
                    character.isHighSurrogate() -> {
                        if (offset >= source.length || !source[offset].isLowSurrogate()) {
                            fail(ToolArgumentsError.MALFORMED_JSON)
                        }
                        value.append(character)
                        value.append(source[offset++])
                    }
                    character.isLowSurrogate() -> fail(ToolArgumentsError.MALFORMED_JSON)
                    else -> value.append(character)
                }
            }
            fail(ToolArgumentsError.MALFORMED_JSON)
        }

        private fun appendEscape(target: StringBuilder) {
            if (offset >= source.length) {
                fail(ToolArgumentsError.MALFORMED_JSON)
            }
            when (val escaped = source[offset++]) {
                '"', '\\', '/' -> target.append(escaped)
                'b' -> target.append('\b')
                'f' -> target.append('\u000C')
                'n' -> target.append('\n')
                'r' -> target.append('\r')
                't' -> target.append('\t')
                'u' -> appendUnicodeEscape(target)
                else -> fail(ToolArgumentsError.MALFORMED_JSON)
            }
        }

        private fun appendUnicodeEscape(target: StringBuilder) {
            val first = readHexCodeUnit()
            when {
                first in HIGH_SURROGATE_RANGE -> {
                    if (offset + 2 > source.length || source[offset] != '\\' || source[offset + 1] != 'u') {
                        fail(ToolArgumentsError.MALFORMED_JSON)
                    }
                    offset += 2
                    val second = readHexCodeUnit()
                    if (second !in LOW_SURROGATE_RANGE) {
                        fail(ToolArgumentsError.MALFORMED_JSON)
                    }
                    target.append(first.toChar())
                    target.append(second.toChar())
                }
                first in LOW_SURROGATE_RANGE -> fail(ToolArgumentsError.MALFORMED_JSON)
                else -> target.append(first.toChar())
            }
        }

        private fun readHexCodeUnit(): Int {
            if (offset + 4 > source.length) {
                fail(ToolArgumentsError.MALFORMED_JSON)
            }
            var result = 0
            repeat(4) {
                val digit = source[offset++].digitToIntOrNull(16)
                    ?: fail(ToolArgumentsError.MALFORMED_JSON)
                result = result * 16 + digit
            }
            return result
        }

        private fun skipWhitespace() {
            while (offset < source.length && source[offset] in JSON_WHITESPACE) {
                offset++
            }
        }

        private fun expect(expected: Char) {
            if (!consumeIf(expected)) {
                fail(ToolArgumentsError.MALFORMED_JSON)
            }
        }

        private fun consumeIf(expected: Char): Boolean {
            if (peek() != expected) return false
            offset++
            return true
        }

        private fun peek(): Char? = source.getOrNull(offset)

        private fun fail(error: ToolArgumentsError): Nothing = throw ParseFailure(error)
    }

    private class ParseFailure(val error: ToolArgumentsError) : RuntimeException(null, null, false, false)

    private fun containsUnsafeModelText(value: String): Boolean =
        value.contains(MODEL_CONTROL_TOKEN_OPEN) ||
            value.contains(MODEL_CONTROL_TOKEN_CLOSE) ||
            value.codePoints().anyMatch { codePoint ->
                when (Character.getType(codePoint)) {
                    Character.FORMAT.toInt(),
                    Character.LINE_SEPARATOR.toInt(),
                    Character.PARAGRAPH_SEPARATOR.toInt(),
                    -> true
                    else -> false
                }
            }

    private companion object {
        const val RECIPIENT = "recipient"
        const val MESSAGE = "message"
        val REQUIRED_FIELDS = setOf(RECIPIENT, MESSAGE)
        val JSON_WHITESPACE = charArrayOf(' ', '\t', '\n', '\r')
        val HIGH_SURROGATE_RANGE = 0xD800..0xDBFF
        val LOW_SURROGATE_RANGE = 0xDC00..0xDFFF
        const val MODEL_CONTROL_TOKEN_OPEN = "<|"
        const val MODEL_CONTROL_TOKEN_CLOSE = "|>"
    }
}

internal object TrustedToolResultJson {
    fun encode(result: FakeArrivalNoticeResult): String =
        """{"simulated":${result.simulated}}"""
}
