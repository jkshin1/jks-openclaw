package com.personaledge.core.agent

import com.personaledge.core.llm.TrustedToolResponseBudget
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
import com.personaledge.core.tools.CapturedMessageSummary
import com.personaledge.core.tools.CommitmentProposalParams
import com.personaledge.core.tools.CommitmentProposalResult
import com.personaledge.core.tools.CommitmentProposalWriteOutcome
import com.personaledge.core.tools.FakeArrivalNoticeParams
import com.personaledge.core.tools.NotificationSearchParams
import com.personaledge.core.tools.NotificationSearchResult
import com.personaledge.core.tools.KakaoNotificationReplyParams
import com.personaledge.core.tools.KakaoNotificationReplyResult
import com.personaledge.core.tools.KakaoShareMessageParams
import com.personaledge.core.tools.KakaoShareMessageResult
import com.personaledge.core.tools.MemoryRememberParams
import com.personaledge.core.tools.MemoryRememberResult
import com.personaledge.core.tools.MemoryWriteOutcome
import com.personaledge.core.tools.RouteEstimateParams
import com.personaledge.core.tools.RouteEstimateResult
import com.personaledge.core.tools.ReminderCancelParams
import com.personaledge.core.tools.ReminderCreateParams
import com.personaledge.core.tools.ReminderMutationOutcome
import com.personaledge.core.tools.ReminderMutationResult
import com.personaledge.core.tools.ReminderQueryParams
import com.personaledge.core.tools.ReminderSummary
import com.personaledge.core.tools.ReminderToolPrecision
import com.personaledge.core.tools.ReminderUpdateParams
import com.personaledge.core.tools.WebSearchHit
import com.personaledge.core.tools.WebSearchParams
import com.personaledge.core.tools.WebSearchProvider
import com.personaledge.core.tools.WebSearchResult
import com.personaledge.core.tools.WeatherParams
import com.personaledge.core.tools.WeatherResult
import java.util.Locale
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

internal class NotificationSearchArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<NotificationSearchParams> =
        when (val fields = reader.read(json, ALLOWED_FIELDS, emptySet())) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                NotificationSearchParams(
                    query = fields.fields[QUERY],
                    withinDays = fields.fields[WITHIN_DAYS],
                ),
            )
        }

    private companion object {
        const val QUERY = "query"
        const val WITHIN_DAYS = "within_days"
        val ALLOWED_FIELDS = setOf(QUERY, WITHIN_DAYS)
    }
}

internal class RouteEstimateArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<RouteEstimateParams> =
        when (val fields = reader.read(json, ALLOWED_FIELDS, REQUIRED_FIELDS)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                RouteEstimateParams(
                    origin = fields.fields[ORIGIN],
                    destination = fields.fields.getValue(DESTINATION),
                ),
            )
        }

    private companion object {
        const val ORIGIN = "origin"
        const val DESTINATION = "destination"
        val REQUIRED_FIELDS = setOf(DESTINATION)
        val ALLOWED_FIELDS = REQUIRED_FIELDS + ORIGIN
    }
}

internal class WebSearchArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<WebSearchParams> =
        when (val fields = reader.read(json, ALLOWED_FIELDS, ALLOWED_FIELDS)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                WebSearchParams(query = fields.fields.getValue(QUERY)),
            )
        }

    private companion object {
        const val QUERY = "query"
        val ALLOWED_FIELDS = setOf(QUERY)
    }
}

internal class WeatherArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<WeatherParams> =
        when (val fields = reader.read(json, ALLOWED_FIELDS, ALLOWED_FIELDS)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                WeatherParams(location = fields.fields.getValue(LOCATION)),
            )
        }

    private companion object {
        const val LOCATION = "location"
        val ALLOWED_FIELDS = setOf(LOCATION)
    }
}

internal class KakaoShareMessageArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<KakaoShareMessageParams> =
        when (val fields = reader.read(json, ALLOWED_FIELDS, REQUIRED_FIELDS)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                KakaoShareMessageParams(
                    recipient = fields.fields[RECIPIENT],
                    message = fields.fields.getValue(MESSAGE),
                ),
            )
        }

    private companion object {
        const val RECIPIENT = "recipient"
        const val MESSAGE = "message"
        val REQUIRED_FIELDS = setOf(MESSAGE)
        val ALLOWED_FIELDS = REQUIRED_FIELDS + RECIPIENT
    }
}

internal class KakaoNotificationReplyArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<KakaoNotificationReplyParams> =
        when (val fields = reader.read(json, ALLOWED_FIELDS, ALLOWED_FIELDS)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                KakaoNotificationReplyParams(
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

internal class MemoryRememberArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<MemoryRememberParams> =
        when (val fields = reader.read(json, ALLOWED_FIELDS, REQUIRED_FIELDS)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                MemoryRememberParams(
                    content = fields.fields.getValue(CONTENT),
                    category = fields.fields[CATEGORY],
                    validUntil = fields.fields[VALID_UNTIL],
                    zoneId = fields.fields[ZONE_ID],
                    supersedesId = fields.fields[SUPERSEDES_ID],
                ),
            )
        }

    private companion object {
        const val CONTENT = "content"
        const val CATEGORY = "category"
        const val VALID_UNTIL = "valid_until"
        const val ZONE_ID = "zone_id"
        const val SUPERSEDES_ID = "supersedes_id"
        val REQUIRED_FIELDS = setOf(CONTENT)
        val ALLOWED_FIELDS = REQUIRED_FIELDS + setOf(CATEGORY, VALID_UNTIL, ZONE_ID, SUPERSEDES_ID)
    }
}

internal class CommitmentProposalArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<CommitmentProposalParams> =
        when (val fields = reader.read(json, ALLOWED_FIELDS, REQUIRED_FIELDS)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                CommitmentProposalParams(
                    summary = fields.fields.getValue(SUMMARY),
                    proposedAt = fields.fields[PROPOSED_AT],
                    zoneId = fields.fields[ZONE_ID],
                ),
            )
        }

    private companion object {
        const val SUMMARY = "summary"
        const val PROPOSED_AT = "proposed_at"
        const val ZONE_ID = "zone_id"
        val REQUIRED_FIELDS = setOf(SUMMARY)
        val ALLOWED_FIELDS = REQUIRED_FIELDS + setOf(PROPOSED_AT, ZONE_ID)
    }
}

internal class ReminderCreateArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<ReminderCreateParams> =
        when (val fields = reader.read(json, ReminderFields.WRITE_FIELDS, ReminderFields.WRITE_REQUIRED)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(fields.fields.toReminderCreateParams())
        }
}

internal class ReminderUpdateArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<ReminderUpdateParams> = when (
        val fields = reader.read(
            json,
            ReminderFields.WRITE_FIELDS + ReminderFields.IDENTITY_FIELDS,
            ReminderFields.WRITE_REQUIRED + ReminderFields.IDENTITY_FIELDS,
        )
    ) {
        is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
        is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
            fields.fields.toReminderCreateParams().let { create ->
                ReminderUpdateParams(
                    reminderId = fields.fields.getValue(ReminderFields.ID),
                    expectedVersion = fields.fields.getValue(ReminderFields.VERSION),
                    title = create.title,
                    triggerAt = create.triggerAt,
                    zoneId = create.zoneId,
                    recurrenceRule = create.recurrenceRule,
                    precision = create.precision,
                    leadTimeMinutes = create.leadTimeMinutes,
                    escalationPolicy = create.escalationPolicy,
                )
            },
        )
    }
}

internal class ReminderCancelArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<ReminderCancelParams> =
        when (val fields = reader.read(json, ReminderFields.IDENTITY_FIELDS, ReminderFields.IDENTITY_FIELDS)) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                ReminderCancelParams(
                    reminderId = fields.fields.getValue(ReminderFields.ID),
                    expectedVersion = fields.fields.getValue(ReminderFields.VERSION),
                ),
            )
        }
}

internal class ReminderQueryArgumentsParser(maxArgumentBytes: Int) {
    private val reader = StrictToolArgumentsReader(maxArgumentBytes)

    fun parse(json: String): ToolArgumentsParseResult<ReminderQueryParams> =
        when (val fields = reader.read(json, setOf(ReminderFields.LIMIT), emptySet())) {
            is FlatFieldsResult.Invalid -> ToolArgumentsParseResult.Invalid(fields.error)
            is FlatFieldsResult.Valid -> ToolArgumentsParseResult.Valid(
                ReminderQueryParams(fields.fields[ReminderFields.LIMIT]),
            )
        }
}

private object ReminderFields {
    const val ID = "reminder_id"
    const val VERSION = "expected_version"
    const val TITLE = "title"
    const val TRIGGER = "trigger_at"
    const val ZONE = "zone_id"
    const val RECURRENCE = "recurrence_rule"
    const val PRECISION = "precision"
    const val LEAD = "lead_time_minutes"
    const val ESCALATION = "escalation_policy"
    const val LIMIT = "limit"
    val IDENTITY_FIELDS = setOf(ID, VERSION)
    val WRITE_REQUIRED = setOf(TITLE, TRIGGER, ZONE)
    val WRITE_FIELDS = WRITE_REQUIRED + setOf(RECURRENCE, PRECISION, LEAD, ESCALATION)
}

private fun Map<String, String>.toReminderCreateParams(): ReminderCreateParams = ReminderCreateParams(
    title = getValue(ReminderFields.TITLE),
    triggerAt = getValue(ReminderFields.TRIGGER),
    zoneId = getValue(ReminderFields.ZONE),
    recurrenceRule = get(ReminderFields.RECURRENCE),
    precision = get(ReminderFields.PRECISION),
    leadTimeMinutes = get(ReminderFields.LEAD),
    escalationPolicy = get(ReminderFields.ESCALATION),
)

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

    fun encode(result: CalendarQueryResult): String = encodeBudgetedList(
        prefix = "{\"events\":[",
        records = result.events.map(::encodeEvent),
        inheritedTruncated = result.truncated,
    )

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

    fun encode(result: NotificationSearchResult): String = encodeBudgetedList(
        prefix = "{\"messages\":[",
        records = result.messages.map(::encodeMessage),
        inheritedTruncated = result.truncated,
    )

    fun encode(result: RouteEstimateResult): String = buildString {
        append("""{"origin":${quote(result.origin)}""")
        append(""","destination":${quote(result.destination)}""")
        append(""","duration_minutes":${result.durationMinutes}""")
        append(""","distance_km":${quote(result.distanceKilometres)}}""")
    }

    fun encode(result: WebSearchResult): String {
        val provider = when (result.provider) {
            WebSearchProvider.YOU_COM -> "you.com"
            WebSearchProvider.TAVILY -> "tavily"
        }
        val prefix = "{\"provider\":${quote(provider)},\"results\":["
        val records = mutableListOf<String>()
        var truncated = false
        for (hit in result.hits) {
            if (records.size >= MAX_TRUSTED_WEB_HITS) {
                truncated = true
                break
            }
            if (hit.link.length > MAX_TRUSTED_WEB_LINK_CHARACTERS) {
                truncated = true
                continue
            }
            val title = hit.title.takeCodePoints(MAX_TRUSTED_WEB_TITLE_CHARACTERS)
            val snippet = hit.snippet.takeCodePoints(MAX_TRUSTED_WEB_SNIPPET_CHARACTERS)
            if (title != hit.title || snippet != hit.snippet) truncated = true
            val encodedHit = buildString {
                append("{\"title\":${quote(title)}")
                append(",\"link\":${quote(hit.link)}")
                append(",\"snippet\":${quote(snippet)}}")
            }
            records += encodedHit
        }
        return encodeBudgetedList(
            prefix = prefix,
            records = records,
            inheritedTruncated = truncated || records.size < result.hits.size,
        )
    }

    fun encode(result: WeatherResult): String = buildString {
        append("{\"resolved_location\":${quote(result.location)}")
        append(",\"current_at\":${quote(result.currentAt)}")
        append(",\"condition\":${quote(result.condition)}")
        append(",\"temperature_c\":${quote(result.temperatureCelsius)}")
        append(",\"apparent_temperature_c\":${quote(result.apparentTemperatureCelsius)}")
        append(",\"relative_humidity_percent\":${result.relativeHumidityPercent}")
        append(",\"precipitation_mm\":${quote(result.precipitationMillimetres)}")
        append(",\"wind_speed_kmh\":${quote(result.windSpeedKilometresPerHour)}")
        append(",\"today_minimum_c\":${quote(result.todayMinimumCelsius)}")
        append(",\"today_maximum_c\":${quote(result.todayMaximumCelsius)}")
        append(",\"today_precipitation_probability_max_percent\":" +
            result.todayPrecipitationProbabilityPercent)
        append(",\"source_name\":${quote(result.sourceName)}")
        append(",\"source_url\":${quote(result.sourceUrl)}")
        append(",\"answer_requirements\":\"The app renders this result. Do not add, change, " +
            "or restate values and do not call another tool.\"}")
    }

    fun encode(result: KakaoShareMessageResult): String = buildString {
        append("{\"share_opened\":${result.shareOpened}")
        append(",\"message_sent\":false")
        append(",\"recipient_selection_required\":${result.recipientSelectionRequired}")
        result.reason?.let { reason -> append(",\"reason\":${quote(reason)}") }
        append('}')
    }

    fun encode(result: KakaoNotificationReplyResult): String = buildString {
        append("{\"reply_requested\":${result.replyRequested}")
        append(",\"message_sent\":false")
        result.reason?.let { reason -> append(",\"reason\":${quote(reason)}") }
        append('}')
    }

    fun encode(result: MemoryRememberResult): String = when (result.outcome) {
        MemoryWriteOutcome.SAVED -> """{"saved":true}"""
        MemoryWriteOutcome.CAPACITY_REACHED ->
            """{"saved":false,"reason":"capacity_reached"}"""
        MemoryWriteOutcome.REJECTED -> """{"saved":false,"reason":"rejected"}"""
    }

    fun encode(result: CommitmentProposalResult): String = when (result.outcome) {
        CommitmentProposalWriteOutcome.SAVED -> """{"saved":true,"scheduled":false}"""
        CommitmentProposalWriteOutcome.ALREADY_HANDLED ->
            """{"saved":true,"scheduled":false,"already_handled":true}"""
        CommitmentProposalWriteOutcome.CAPACITY_REACHED ->
            """{"saved":false,"scheduled":false,"reason":"capacity_reached"}"""
        CommitmentProposalWriteOutcome.REJECTED ->
            """{"saved":false,"scheduled":false,"reason":"rejected"}"""
    }

    fun encode(result: ReminderMutationResult): String = buildString {
        append("{\"saved\":")
        append(result.outcome == ReminderMutationOutcome.SAVED)
        append(",\"outcome\":")
        append(quote(result.outcome.name.lowercase(Locale.ROOT)))
        result.reminderId?.let { append(",\"reminder_id\":${quote(it)}") }
        result.scheduleVersion?.let { append(",\"schedule_version\":${quote(it.toString())}") }
        append('}')
    }

    fun encode(result: List<ReminderSummary>): String = encodeBudgetedList(
        prefix = "{\"reminders\":[",
        records = result.map(::encodeReminder),
        inheritedTruncated = false,
    )

    fun encode(result: CalendarUpdateEventResult): String = buildString {
        append("""{"updated":${result.updated}""")
        result.reason?.let { reason -> append(""","reason":${quote(reason)}""") }
        append('}')
    }

    private fun String.takeCodePoints(maximum: Int): String {
        if (codePointCount(0, length) <= maximum) return this
        return substring(0, offsetByCodePoints(0, maximum))
    }

    /** Keeps a complete ordered prefix of records; a record is never cut into invalid JSON. */
    private fun encodeBudgetedList(
        prefix: String,
        records: List<String>,
        inheritedTruncated: Boolean,
    ): String {
        var returnedCount = 0
        for (candidateCount in 1..records.size) {
            val candidate = encodeListEnvelope(
                prefix = prefix,
                records = records.subList(0, candidateCount),
                truncated = inheritedTruncated || candidateCount < records.size,
            )
            if (!TrustedToolResponseBudget.allows(listOf(candidate))) break
            returnedCount = candidateCount
        }
        val encoded = encodeListEnvelope(
            prefix = prefix,
            records = records.subList(0, returnedCount),
            truncated = inheritedTruncated || returnedCount < records.size,
        )
        check(TrustedToolResponseBudget.allows(listOf(encoded)))
        return encoded
    }

    private fun encodeListEnvelope(
        prefix: String,
        records: List<String>,
        truncated: Boolean,
    ): String = prefix + records.joinToString(",") +
        "],\"truncated\":$truncated,\"returned_count\":${records.size}}"

    private const val MAX_TRUSTED_WEB_HITS = 2
    private const val MAX_TRUSTED_WEB_TITLE_CHARACTERS = 50
    private const val MAX_TRUSTED_WEB_SNIPPET_CHARACTERS = 100
    private const val MAX_TRUSTED_WEB_LINK_CHARACTERS = 220
    internal const val MAX_TRUSTED_WEB_RESULT_BYTES =
        TrustedToolResponseBudget.MAX_TOTAL_PAYLOAD_UTF8_BYTES

    private fun encodeMessage(message: CapturedMessageSummary): String = buildString {
        append("""{"conversation":${quote(message.conversation)}""")
        // Absent rather than empty: the platform states a sender only for MessagingStyle posts,
        // and an empty string would read as an unnamed person.
        message.sender?.takeIf(String::isNotBlank)?.let { sender ->
            append(""","sender":${quote(sender)}""")
        }
        append(""","text":${quote(message.text)}""")
        append(""","received_at":${quote(message.receivedAt)}""")
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

    private fun encodeReminder(reminder: ReminderSummary): String = buildString {
        append("{\"reminder_id\":${quote(reminder.reminderId)}")
        append(",\"title\":${quote(reminder.title)}")
        append(",\"trigger_at_epoch_millis\":${quote(reminder.triggerAtEpochMillis.toString())}")
        append(",\"zone_id\":${quote(reminder.zoneId)}")
        reminder.recurrenceRule?.let { append(",\"recurrence_rule\":${quote(it)}") }
        append(",\"precision\":${quote(reminder.precision.name.lowercase(Locale.ROOT))}")
        append(",\"schedule_version\":${quote(reminder.scheduleVersion.toString())}}")
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
                character.code < 0x20 -> append("\\u%04x".format(Locale.ROOT, character.code))
                else -> append(character)
            }
        }
        append('"')
    }
}
