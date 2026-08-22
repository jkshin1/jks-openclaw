package com.personaledge.core.diagnostics

import java.util.Locale

/**
 * Treats retained diagnostics as untrusted at the export boundary. Only the flat, typed schemas
 * emitted by [DiagnosticJsonEncoder] are allowed to leave the app.
 */
internal object DiagnosticExportJsonValidator {
    fun validate(record: String) {
        val fields = FlatJsonObjectParser(record).parse()
        check(fields.keys.containsAll(COMMON_FIELDS)) { "Missing diagnostics envelope field" }
        check(fields.integer("schema_version") == SCHEMA_VERSION)
        fields.nonNegativeInteger("recorded_at_ms")

        when (fields.text("event")) {
            "process_started", "session_started" -> requireFields(fields, COMMON_FIELDS)
            "historical_exit" -> validateHistoricalExit(fields)
            "model_inspected", "model_imported" -> validateModelOperation(fields)
            "runtime_initialized" -> validateRuntimeInitialized(fields)
            "turn_started" -> validateTurnStarted(fields)
            "turn_first_token" -> validateTurnFirstToken(fields)
            "turn_completed" -> validateTurnCompleted(fields)
            "turn_cancelled" -> validateTurnCancelled(fields)
            "turn_failed" -> validateTurnFailed(fields)
            "tool_phase" -> validateToolPhase(fields)
            "resource_snapshot" -> validateResourceSnapshot(fields)
            "thermal_guard" -> validateThermalGuard(fields)
            else -> error("Unknown diagnostics event")
        }
    }

    private fun validateHistoricalExit(fields: Map<String, JsonAtom>) {
        requireFields(
            fields,
            required = COMMON_FIELDS + setOf(
                "reason",
                "status",
                "importance",
                "pss_bytes",
                "rss_bytes",
                "exit_timestamp_ms",
            ),
            optional = setOf("phase"),
        )
        fields.enum("reason", EXIT_REASONS)
        fields.int("status")
        fields.int("importance")
        fields.nonNegativeInteger("pss_bytes")
        fields.nonNegativeInteger("rss_bytes")
        fields.nonNegativeInteger("exit_timestamp_ms")
        fields.optionalEnum("phase", PHASES)
    }

    private fun validateModelOperation(fields: Map<String, JsonAtom>) {
        requireFields(
            fields,
            required = COMMON_FIELDS + setOf("size_bytes", "duration_ms", "result"),
            optional = setOf("error_code") + FAILURE_FIELDS,
        )
        fields.nonNegativeInteger("size_bytes")
        fields.nonNegativeInteger("duration_ms")
        fields.enum("result", RESULTS)
        fields.optionalEnum("error_code", ERROR_CODES)
        validateFailure(fields)
    }

    private fun validateRuntimeInitialized(fields: Map<String, JsonAtom>) {
        requireFields(
            fields,
            required = COMMON_FIELDS + setOf("requested_backend", "duration_ms", "result"),
            optional = setOf("active_backend", "error_code") + FAILURE_FIELDS,
        )
        fields.enum("requested_backend", BACKENDS)
        fields.optionalEnum("active_backend", BACKENDS)
        fields.nonNegativeInteger("duration_ms")
        val result = fields.enum("result", RESULTS)
        fields.optionalEnum("error_code", ERROR_CODES)
        validateFailure(fields)
        check(result != "success" || fields.containsKey("active_backend"))
    }

    private fun validateTurnStarted(fields: Map<String, JsonAtom>) {
        requireFields(fields, COMMON_FIELDS + setOf("prompt_byte_count"))
        fields.nonNegativeInt("prompt_byte_count")
    }

    private fun validateTurnFirstToken(fields: Map<String, JsonAtom>) {
        requireFields(fields, COMMON_FIELDS + setOf("ttft_ms"))
        fields.nonNegativeInteger("ttft_ms")
    }

    private fun validateTurnCompleted(fields: Map<String, JsonAtom>) {
        requireFields(fields, COMMON_FIELDS + TURN_METRIC_FIELDS)
        validateTurnMetrics(fields)
    }

    private fun validateTurnCancelled(fields: Map<String, JsonAtom>) {
        requireFields(
            fields,
            required = COMMON_FIELDS + TURN_METRIC_FIELDS + setOf("cause"),
            optional = setOf("thermal_status"),
        )
        validateTurnMetrics(fields)
        val cause = fields.enum("cause", TURN_CANCELLATION_CAUSES)
        val thermalStatus = fields.optionalEnum("thermal_status", THERMAL_STATUSES)
        check((cause == "thermal") == (thermalStatus != null))
        check(thermalStatus == null || thermalStatus in THERMAL_STOP_STATUSES)
    }

    private fun validateTurnFailed(fields: Map<String, JsonAtom>) {
        requireFields(
            fields,
            required = COMMON_FIELDS + TURN_METRIC_FIELDS + setOf("error_code"),
            optional = FAILURE_FIELDS,
        )
        validateTurnMetrics(fields)
        fields.enum("error_code", ERROR_CODES)
        validateFailure(fields)
    }

    private fun validateToolPhase(fields: Map<String, JsonAtom>) {
        requireFields(
            fields,
            COMMON_FIELDS + setOf("name", "stage", "risk", "confirmation_outcome"),
        )
        check(DiagnosticToolName.parse(fields.text("name")) != null)
        val stage = fields.enum("stage", TOOL_STAGES)
        fields.enum("risk", TOOL_RISKS)
        val outcome = fields.enum("confirmation_outcome", CONFIRMATION_OUTCOMES)
        check(
            when (stage) {
                "confirmation_requested" -> outcome == "requested"
                "confirmation_resolved" -> outcome in RESOLVED_CONFIRMATION_OUTCOMES
                "executed" -> outcome in EXECUTED_CONFIRMATION_OUTCOMES
                else -> false
            },
        )
    }

    private fun validateResourceSnapshot(fields: Map<String, JsonAtom>) {
        requireFields(
            fields,
            COMMON_FIELDS + setOf("pss_bytes", "java_heap_bytes", "thermal_status"),
        )
        fields.nonNegativeInteger("pss_bytes")
        fields.nonNegativeInteger("java_heap_bytes")
        fields.enum("thermal_status", THERMAL_STATUSES)
    }

    private fun validateThermalGuard(fields: Map<String, JsonAtom>) {
        requireFields(fields, COMMON_FIELDS + setOf("thermal_status", "action"))
        val status = fields.enum("thermal_status", THERMAL_STATUSES)
        val action = fields.enum("action", THERMAL_ACTIONS)
        check(
            when (action) {
                "status_observed" -> true
                "runtime_initialization_rejected",
                "runtime_initialization_cancel_requested",
                "turn_rejected",
                -> status in THERMAL_STOP_STATUSES
                "cooperative_cancel_requested" -> status == "critical"
                "immediate_abort_requested" -> status in IMMEDIATE_ABORT_STATUSES
                else -> false
            },
        )
    }

    private fun validateTurnMetrics(fields: Map<String, JsonAtom>) {
        fields.nonNegativeInteger("duration_ms")
        fields.nonNegativeInt("delta_count")
        fields.nonNegativeInteger("delta_byte_count")
    }

    private fun validateFailure(fields: Map<String, JsonAtom>) {
        val hasThrowableType = fields.containsKey("throwable_type")
        val hasFingerprint = fields.containsKey("stack_fingerprint_sha256")
        check(hasThrowableType == hasFingerprint)
        if (!hasThrowableType) return

        val throwableType = fields.text("throwable_type")
        check(throwableType.length in 1..MAX_THROWABLE_TYPE_LENGTH)
        check(THROWABLE_TYPE.matches(throwableType))
        check(STACK_FINGERPRINT.matches(fields.text("stack_fingerprint_sha256")))
    }

    private fun requireFields(
        fields: Map<String, JsonAtom>,
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        check(fields.keys.containsAll(required)) { "Missing diagnostics field" }
        check(fields.keys.all { it in required || it in optional }) {
            "Unexpected diagnostics field"
        }
    }

    private fun Map<String, JsonAtom>.text(name: String): String {
        val atom = getValue(name)
        check(atom is JsonAtom.Text) { "Diagnostics field has the wrong type" }
        return atom.value
    }

    private fun Map<String, JsonAtom>.integer(name: String): Long {
        val atom = getValue(name)
        check(atom is JsonAtom.Integer) { "Diagnostics field has the wrong type" }
        return atom.value
    }

    private fun Map<String, JsonAtom>.nonNegativeInteger(name: String): Long =
        integer(name).also { check(it >= 0) { "Negative diagnostics metric" } }

    private fun Map<String, JsonAtom>.int(name: String): Int {
        val value = integer(name)
        check(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return value.toInt()
    }

    private fun Map<String, JsonAtom>.nonNegativeInt(name: String): Int =
        int(name).also { check(it >= 0) { "Negative diagnostics metric" } }

    private fun Map<String, JsonAtom>.enum(name: String, allowed: Set<String>): String =
        text(name).also { check(it in allowed) { "Unknown diagnostics enum" } }

    private fun Map<String, JsonAtom>.optionalEnum(
        name: String,
        allowed: Set<String>,
    ): String? = if (containsKey(name)) enum(name, allowed) else null

    private fun <E : Enum<E>> enumNames(values: Array<E>): Set<String> =
        values.mapTo(linkedSetOf()) { it.name.lowercase(Locale.ROOT) }

    private const val SCHEMA_VERSION = 1L
    private const val MAX_THROWABLE_TYPE_LENGTH = 160
    private val COMMON_FIELDS = setOf("schema_version", "recorded_at_ms", "event")
    private val FAILURE_FIELDS = setOf("throwable_type", "stack_fingerprint_sha256")
    private val TURN_METRIC_FIELDS = setOf("duration_ms", "delta_count", "delta_byte_count")
    private val THROWABLE_TYPE = Regex(
        "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*",
    )
    private val STACK_FINGERPRINT = Regex("[0-9a-f]{64}")
    private val PHASES = enumNames(enumValues<DiagnosticPhase>())
    private val BACKENDS = enumNames(enumValues<DiagnosticBackend>())
    private val RESULTS = enumNames(enumValues<DiagnosticResult>())
    private val TOOL_RISKS = enumNames(enumValues<DiagnosticToolRisk>())
    private val TOOL_STAGES = enumNames(enumValues<DiagnosticToolStage>())
    private val CONFIRMATION_OUTCOMES = enumNames(enumValues<DiagnosticConfirmationOutcome>())
    private val THERMAL_STATUSES = enumNames(enumValues<DiagnosticThermalStatus>())
    private val THERMAL_ACTIONS = enumNames(enumValues<DiagnosticThermalAction>())
    private val TURN_CANCELLATION_CAUSES = enumNames(
        enumValues<DiagnosticTurnCancellationCause>(),
    )
    private val EXIT_REASONS = enumNames(enumValues<DiagnosticExitReason>())
    private val ERROR_CODES = enumNames(enumValues<DiagnosticErrorCode>())
    private val THERMAL_STOP_STATUSES = setOf("critical", "emergency", "shutdown", "unknown")
    private val IMMEDIATE_ABORT_STATUSES = setOf("emergency", "shutdown", "unknown")
    private val RESOLVED_CONFIRMATION_OUTCOMES = setOf(
        "approved",
        "denied",
        "authentication_failed",
        "expired",
        "cancelled",
    )
    private val EXECUTED_CONFIRMATION_OUTCOMES = setOf(
        "executed_success",
        "executed_refused",
    )
}

private sealed interface JsonAtom {
    data class Text(val value: String) : JsonAtom
    data class Integer(val value: Long) : JsonAtom
}

/** Minimal RFC 8259 parser for the deliberately flat diagnostic record shape. */
private class FlatJsonObjectParser(
    private val input: String,
) {
    private var index = 0

    fun parse(): Map<String, JsonAtom> {
        skipSpaces()
        expect('{')
        skipSpaces()
        val fields = linkedMapOf<String, JsonAtom>()
        if (consume('}')) {
            finish()
            return fields
        }

        while (true) {
            val name = parseString()
            check(!fields.containsKey(name)) { "Duplicate diagnostics JSON key" }
            skipSpaces()
            expect(':')
            skipSpaces()
            fields[name] = parseAtom()
            skipSpaces()
            when {
                consume(',') -> skipSpaces()
                consume('}') -> {
                    finish()
                    return fields
                }
                else -> error("Invalid diagnostics JSON object")
            }
        }
    }

    private fun parseAtom(): JsonAtom {
        check(index < input.length) { "Missing diagnostics JSON value" }
        return if (input[index] == '"') {
            JsonAtom.Text(parseString())
        } else {
            JsonAtom.Integer(parseInteger())
        }
    }

    private fun parseInteger(): Long {
        val start = index
        if (consume('-')) {
            check(index < input.length) { "Invalid diagnostics JSON number" }
        }
        when {
            consume('0') -> check(index >= input.length || input[index] !in '0'..'9') {
                "Leading zero in diagnostics JSON number"
            }
            index < input.length && input[index] in '1'..'9' -> {
                index += 1
                while (index < input.length && input[index] in '0'..'9') index += 1
            }
            else -> error("Invalid diagnostics JSON number")
        }
        return input.substring(start, index).toLongOrNull()
            ?: error("Diagnostics JSON number is out of range")
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < input.length) {
            val character = input[index++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> appendEscape(result)
                character.code < 0x20 -> error("Raw control character in diagnostics JSON string")
                character.isHighSurrogate() -> {
                    check(index < input.length && input[index].isLowSurrogate()) {
                        "Unpaired surrogate in diagnostics JSON string"
                    }
                    result.append(character).append(input[index++])
                }
                character.isLowSurrogate() -> error("Unpaired surrogate in diagnostics JSON string")
                else -> result.append(character)
            }
        }
        error("Unterminated diagnostics JSON string")
    }

    private fun appendEscape(result: StringBuilder) {
        check(index < input.length) { "Incomplete diagnostics JSON escape" }
        when (val escaped = input[index++]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> appendUnicodeEscape(result)
            else -> error("Invalid diagnostics JSON escape")
        }
    }

    private fun appendUnicodeEscape(result: StringBuilder) {
        val first = parseHexCodeUnit()
        when {
            first.isHighSurrogate() -> {
                check(index + 2 <= input.length && input[index] == '\\' && input[index + 1] == 'u') {
                    "Unpaired surrogate in diagnostics JSON escape"
                }
                index += 2
                val second = parseHexCodeUnit()
                check(second.isLowSurrogate()) { "Unpaired surrogate in diagnostics JSON escape" }
                result.append(first).append(second)
            }
            first.isLowSurrogate() -> error("Unpaired surrogate in diagnostics JSON escape")
            else -> result.append(first)
        }
    }

    private fun parseHexCodeUnit(): Char {
        check(index + 4 <= input.length) { "Incomplete diagnostics JSON unicode escape" }
        var value = 0
        repeat(4) {
            val digit = when (val character = input[index++]) {
                in '0'..'9' -> character - '0'
                in 'a'..'f' -> character - 'a' + 10
                in 'A'..'F' -> character - 'A' + 10
                else -> error("Invalid diagnostics JSON unicode escape")
            }
            value = value * 16 + digit
        }
        return value.toChar()
    }

    private fun finish() {
        skipSpaces()
        check(index == input.length) { "Trailing data after diagnostics JSON object" }
    }

    private fun skipSpaces() {
        while (index < input.length && input[index] == ' ') index += 1
    }

    private fun expect(expected: Char) {
        check(consume(expected)) { "Invalid diagnostics JSON syntax" }
    }

    private fun consume(expected: Char): Boolean {
        if (index >= input.length || input[index] != expected) return false
        index += 1
        return true
    }
}
