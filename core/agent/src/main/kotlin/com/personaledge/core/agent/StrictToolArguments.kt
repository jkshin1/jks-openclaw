package com.personaledge.core.agent

import com.personaledge.core.tools.AlarmNextParams
import com.personaledge.core.tools.AlarmNextResult
import com.personaledge.core.tools.AlarmSetParams
import com.personaledge.core.tools.AlarmSetResult
import com.personaledge.core.tools.CalendarCreateEventParams
import com.personaledge.core.tools.CalendarCreateEventResult
import com.personaledge.core.tools.CalendarEventSummary
import com.personaledge.core.tools.CalendarQueryParams
import com.personaledge.core.tools.CalendarQueryResult
import com.personaledge.core.tools.CalendarUpdateEventParams
import com.personaledge.core.tools.CalendarUpdateEventResult
import com.personaledge.core.tools.FakeArrivalNoticeParams
import com.personaledge.core.tools.FakeArrivalNoticeResult
import com.personaledge.core.tools.ToolParams

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

internal sealed interface ToolArgumentsParseResult<out P : ToolParams> {
    data class Valid<P : ToolParams>(val params: P) : ToolArgumentsParseResult<P>

    data class Invalid(val error: ToolArgumentsError) : ToolArgumentsParseResult<Nothing>
}

internal sealed interface FlatFieldsResult {
    data class Valid(val fields: Map<String, String>) : FlatFieldsResult

    data class Invalid(val error: ToolArgumentsError) : FlatFieldsResult
}

/**
 * Strict, flat, string-only JSON reader shared by every registered tool contract.
 *
 * Values stay strings on purpose: LiteRT-LM hands arguments over as a parsed Map that is
 * re-serialized here, so accepting JSON numbers would let a float round-trip silently change an
 * identifier. Tools that need a number declare a decimal string and parse it themselves.
 */
internal class StrictToolArgumentsReader(
    private val maxArgumentBytes: Int,
) {
    init {
        require(maxArgumentBytes in 64..16_384)
    }

    fun read(
        json: String,
        allowedFields: Set<String>,
        requiredFields: Set<String>,
    ): FlatFieldsResult {
        require(allowedFields.containsAll(requiredFields))

        if (json.length > maxArgumentBytes) {
            return FlatFieldsResult.Invalid(ToolArgumentsError.OVERSIZED)
        }
        if (json.toByteArray(Charsets.UTF_8).size > maxArgumentBytes) {
            return FlatFieldsResult.Invalid(ToolArgumentsError.OVERSIZED)
        }

        return try {
            val fields = FlatStringObjectParser(json).parse()
            val unknown = fields.keys - allowedFields
            when {
                unknown.isNotEmpty() -> FlatFieldsResult.Invalid(ToolArgumentsError.UNKNOWN_FIELD)
                !fields.keys.containsAll(requiredFields) ->
                    FlatFieldsResult.Invalid(ToolArgumentsError.MISSING_FIELD)
                fields.values.any(::containsUnsafeModelText) ->
                    FlatFieldsResult.Invalid(ToolArgumentsError.UNSAFE_TEXT)
                else -> FlatFieldsResult.Valid(fields)
            }
        } catch (failure: ParseFailure) {
            FlatFieldsResult.Invalid(failure.error)
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
        val JSON_WHITESPACE = charArrayOf(' ', '\t', '\n', '\r')
        val HIGH_SURROGATE_RANGE = 0xD800..0xDBFF
        val LOW_SURROGATE_RANGE = 0xDC00..0xDFFF
        const val MODEL_CONTROL_TOKEN_OPEN = "<|"
        const val MODEL_CONTROL_TOKEN_CLOSE = "|>"
    }
}

/** Per-tool contracts. Each one names its own fields so no tool can read another's arguments. */
internal class FakeArrivalNoticeArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<FakeArrivalNoticeParams> =
        when (val fields = reader.read(json, ALLOWED_FIELDS, ALLOWED_FIELDS)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                FakeArrivalNoticeParams(
                    recipient = fields.fields.getValue(RECIPIENT),
                    message = fields.fields.getValue(MESSAGE),
                ),
            )
        }

    private companion object {
        const val RECIPIENT = "recipient"
        const val MESSAGE = "message"
        val ALLOWED_FIELDS = setOf(RECIPIENT, MESSAGE)
    }
}

internal class CalendarQueryArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<CalendarQueryParams> =
        when (val fields = reader.read(json, ALLOWED_FIELDS, ALLOWED_FIELDS)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                CalendarQueryParams(
                    start = fields.fields.getValue(START),
                    end = fields.fields.getValue(END),
                ),
            )
        }

    private companion object {
        const val START = "start"
        const val END = "end"
        val ALLOWED_FIELDS = setOf(START, END)
    }
}

internal class CalendarCreateEventArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<CalendarCreateEventParams> =
        when (val fields = reader.read(json, ALLOWED_FIELDS, REQUIRED_FIELDS)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                CalendarCreateEventParams(
                    title = fields.fields.getValue(TITLE),
                    start = fields.fields.getValue(START),
                    end = fields.fields.getValue(END),
                    location = fields.fields[LOCATION],
                ),
            )
        }

    private companion object {
        const val TITLE = "title"
        const val START = "start"
        const val END = "end"
        const val LOCATION = "location"
        val REQUIRED_FIELDS = setOf(TITLE, START, END)
        val ALLOWED_FIELDS = REQUIRED_FIELDS + LOCATION
    }
}

internal class CalendarUpdateEventArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<CalendarUpdateEventParams> =
        when (val fields = reader.read(json, ALLOWED_FIELDS, REQUIRED_FIELDS)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                CalendarUpdateEventParams(
                    eventId = fields.fields.getValue(EVENT_ID),
                    title = fields.fields[TITLE],
                    start = fields.fields[START],
                    end = fields.fields[END],
                    location = fields.fields[LOCATION],
                ),
            )
        }

    private companion object {
        const val EVENT_ID = "event_id"
        const val TITLE = "title"
        const val START = "start"
        const val END = "end"
        const val LOCATION = "location"
        val REQUIRED_FIELDS = setOf(EVENT_ID)
        val ALLOWED_FIELDS = REQUIRED_FIELDS + setOf(TITLE, START, END, LOCATION)
    }
}

internal class AlarmSetArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<AlarmSetParams> =
        when (val fields = reader.read(json, ALLOWED_FIELDS, REQUIRED_FIELDS)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                AlarmSetParams(
                    time = fields.fields.getValue(TIME),
                    label = fields.fields[LABEL],
                    days = fields.fields[DAYS],
                ),
            )
        }

    private companion object {
        const val TIME = "time"
        const val LABEL = "label"
        const val DAYS = "days"
        val REQUIRED_FIELDS = setOf(TIME)
        val ALLOWED_FIELDS = REQUIRED_FIELDS + setOf(LABEL, DAYS)
    }
}

/** Accepts only the empty object, so a model cannot smuggle a filter this tool does not honor. */
internal class AlarmNextArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<AlarmNextParams> =
        when (val fields = reader.read(json, emptySet(), emptySet())) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(AlarmNextParams)
        }
}

/**
 * Encodes the trusted result the runtime reinjects.
 *
 * Only app-produced values reach these strings. Calendar text originates on the device rather
 * than from the model, but it is still escaped and was already stripped of control-token
 * delimiters by the tool, so it cannot break out of the Gemma tool-response template.
 */
internal object TrustedToolResultJson {
    fun encode(result: FakeArrivalNoticeResult): String =
        """{"simulated":${result.simulated}}"""

    fun encode(result: CalendarQueryResult): String = buildString {
        append("""{"events":[""")
        result.events.forEachIndexed { index, event ->
            if (index > 0) append(',')
            append(encodeEvent(event))
        }
        append("""],"truncated":${result.truncated}}""")
    }

    fun encode(result: CalendarCreateEventResult): String = buildString {
        append("""{"created":${result.created}""")
        // The id is a string so the model copies it back verbatim into calendar_update_event.
        result.eventId?.let { id -> append(""","event_id":${quote(id.toString())}""") }
        append('}')
    }

    fun encode(result: AlarmSetResult): String = buildString {
        // "requested", not "created": ACTION_SET_ALARM returns no result, so claiming creation
        // would tell the model something this app cannot know.
        append("""{"requested":${result.requested}""")
        result.reason?.let { reason -> append(""","reason":${quote(reason)}""") }
        result.nextAlarm?.let { next -> append(""","next_alarm":${quote(next)}""") }
        append('}')
    }

    fun encode(result: AlarmNextResult): String = buildString {
        append("""{"has_alarm":${result.hasAlarm}""")
        result.triggerAt?.let { triggerAt -> append(""","trigger_at":${quote(triggerAt)}""") }
        append('}')
    }

    fun encode(result: CalendarUpdateEventResult): String = buildString {
        append("""{"updated":${result.updated}""")
        result.reason?.let { reason -> append(""","reason":${quote(reason)}""") }
        append('}')
    }

    private fun encodeEvent(event: CalendarEventSummary): String = buildString {
        append("""{"event_id":${quote(event.eventId.toString())}""")
        append(""","title":${quote(event.title)}""")
        append(""","start":${quote(event.start)}""")
        append(""","end":${quote(event.end)}""")
        append(""","all_day":${event.allDay}""")
        event.location?.let { location -> append(""","location":${quote(location)}""") }
        append('}')
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character == '\n' -> append("\\n")
                character == '\r' -> append("\\r")
                character == '\t' -> append("\\t")
                character.code < 0x20 -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
        append('"')
    }
}
