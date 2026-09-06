package com.personaledge.core.openclaw

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

sealed interface OpenClawInboundFrame

class OpenClawResponseFrame(
    val id: String,
    val ok: Boolean,
    val payload: JsonElement?,
    val error: OpenClawError?,
) : OpenClawInboundFrame {
    override fun toString(): String = "OpenClawResponseFrame(id=<redacted>, ok=$ok)"
}

class OpenClawEventFrame(
    val event: String,
    val payload: JsonElement?,
    val sequence: Long?,
) : OpenClawInboundFrame {
    override fun toString(): String =
        "OpenClawEventFrame(event=$event, payload=<redacted>, sequence=$sequence)"
}

class OpenClawError internal constructor(
    val code: String,
    val retryable: Boolean?,
    val retryAfterMillis: Long?,
    internal val connectDetailCode: String?,
    internal val connectDetailsValid: Boolean,
) {
    override fun toString(): String =
        "OpenClawError(code=<redacted>, detailCode=<redacted>, " +
            "retryable=$retryable, retryAfterMillis=$retryAfterMillis)"
}

enum class OpenClawFrameFailure {
    TOO_LARGE,
    MALFORMED_JSON,
    TOO_DEEP,
    TOO_MANY_FIELDS,
    TOO_MANY_ITEMS,
    STRING_TOO_LARGE,
    DUPLICATE_FIELD,
    INVALID_ENVELOPE,
    UNSUPPORTED_FRAME,
}

class OpenClawFrameException(val failure: OpenClawFrameFailure) :
    IllegalArgumentException("OpenClaw frame rejected: ${failure.name}")

/** Strict, bounded codec for the req/res/event envelope in protocol v4. */
class OpenClawFrameCodec {
    fun decodeServerFrame(
        text: String,
        maxBytes: Int = OpenClawProtocol.MAX_LOCAL_FRAME_BYTES,
    ): OpenClawInboundFrame {
        require(maxBytes in 1..OpenClawProtocol.MAX_LOCAL_FRAME_BYTES)
        val encodedBytes = text.strictUtf8ByteCountOrNull()
            ?: throw OpenClawFrameException(OpenClawFrameFailure.MALFORMED_JSON)
        if (encodedBytes > maxBytes) {
            throw OpenClawFrameException(OpenClawFrameFailure.TOO_LARGE)
        }
        val root = parseJson(text)
        if (!root.isJsonObject) reject(OpenClawFrameFailure.INVALID_ENVELOPE)
        val obj = root.asJsonObject
        return when (requiredString(obj, "type", MAX_DISPATCH_STRING_CHARACTERS)) {
            "res" -> decodeResponse(obj)
            "event" -> decodeEvent(obj)
            "req" -> reject(OpenClawFrameFailure.UNSUPPORTED_FRAME)
            else -> reject(OpenClawFrameFailure.INVALID_ENVELOPE)
        }
    }

    fun encodeRequest(
        id: String,
        method: String,
        params: JsonElement?,
        maxBytes: Int,
    ): String {
        require(id.isNotBlank() && id.length <= MAX_ID_CHARACTERS)
        require(method.isNotBlank() && method.length <= MAX_METHOD_CHARACTERS)
        require(maxBytes in 1..OpenClawProtocol.MAX_LOCAL_FRAME_BYTES)
        params?.let { validateElementLimits(it, depth = 2) }
        val frame = JsonObject().apply {
            addProperty("type", "req")
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        val encoded = frame.toString()
        val encodedBytes = encoded.strictUtf8ByteCountOrNull()
            ?: throw OpenClawFrameException(OpenClawFrameFailure.MALFORMED_JSON)
        if (encodedBytes > maxBytes) {
            throw OpenClawFrameException(OpenClawFrameFailure.TOO_LARGE)
        }
        return encoded
    }

    private fun decodeResponse(obj: JsonObject): OpenClawResponseFrame {
        requireOnly(obj, RESPONSE_FIELDS)
        val id = requiredString(obj, "id", MAX_ID_CHARACTERS)
        val ok = obj["ok"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean
            ?: reject(OpenClawFrameFailure.INVALID_ENVELOPE)
        val payload = obj["payload"]
        val errorElement = obj["error"]
        if (ok && errorElement != null) reject(OpenClawFrameFailure.INVALID_ENVELOPE)
        if (!ok && (errorElement == null || payload != null)) {
            reject(OpenClawFrameFailure.INVALID_ENVELOPE)
        }
        return OpenClawResponseFrame(
            id = id,
            ok = ok,
            payload = payload,
            error = errorElement?.let(::decodeError),
        )
    }

    private fun decodeError(element: JsonElement): OpenClawError {
        if (!element.isJsonObject) reject(OpenClawFrameFailure.INVALID_ENVELOPE)
        val obj = element.asJsonObject
        requireOnly(obj, ERROR_FIELDS)
        // The remote prose is validated for the pinned envelope but deliberately discarded.
        // Only the bounded structured detail code may cross into connect retry/UI policy.
        requiredString(obj, "message", MAX_ERROR_MESSAGE_CHARACTERS)
        val retryable = obj["retryable"]?.let { value ->
            value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.asBoolean
                ?: reject(OpenClawFrameFailure.INVALID_ENVELOPE)
        }
        val retryAfter = obj["retryAfterMs"]?.let(::nonNegativeLong)
        val details = projectConnectErrorDetails(obj["details"])
        return OpenClawError(
            code = requiredString(obj, "code", MAX_ERROR_CODE_CHARACTERS),
            retryable = retryable,
            retryAfterMillis = retryAfter,
            connectDetailCode = details.code,
            connectDetailsValid = details.valid,
        )
    }

    /**
     * Projects only the pinned v2026.8.1 connect-error discriminator.
     *
     * Generic RPC error details remain accepted by the frame codec, but a `connect` caller treats
     * [ConnectErrorDetailsProjection.valid] as mandatory. This avoids retaining remote recovery
     * prose, device metadata, or arbitrary extension values while still allowing the exact fields
     * emitted by the pinned Gateway.
     */
    private fun projectConnectErrorDetails(element: JsonElement?): ConnectErrorDetailsProjection {
        if (element == null) return ConnectErrorDetailsProjection(code = null, valid = true)
        if (!element.isJsonObject) return ConnectErrorDetailsProjection(code = null, valid = false)
        val obj = element.asJsonObject
        if (obj.keySet().any { it !in CONNECT_ERROR_DETAIL_FIELDS }) {
            return ConnectErrorDetailsProjection(code = null, valid = false)
        }
        val codeElement = obj["code"]
            ?: return ConnectErrorDetailsProjection(code = null, valid = true)
        if (!codeElement.isJsonPrimitive || !codeElement.asJsonPrimitive.isString) {
            return ConnectErrorDetailsProjection(code = null, valid = false)
        }
        val normalized = normalizeConnectErrorCode(codeElement.asString)
            ?: return ConnectErrorDetailsProjection(code = null, valid = false)
        return ConnectErrorDetailsProjection(code = normalized, valid = true)
    }

    private fun decodeEvent(obj: JsonObject): OpenClawEventFrame {
        requireOnly(obj, EVENT_FIELDS)
        obj["stateVersion"]?.let(::validateStateVersion)
        return OpenClawEventFrame(
            event = requiredString(obj, "event", MAX_EVENT_NAME_CHARACTERS),
            payload = obj["payload"],
            sequence = obj["seq"]?.let(::nonNegativeLong),
        )
    }

    private fun validateStateVersion(element: JsonElement) {
        if (!element.isJsonObject) reject(OpenClawFrameFailure.INVALID_ENVELOPE)
        val obj = element.asJsonObject
        requireOnly(obj, STATE_VERSION_FIELDS)
        nonNegativeLong(obj["presence"] ?: reject(OpenClawFrameFailure.INVALID_ENVELOPE))
        nonNegativeLong(obj["health"] ?: reject(OpenClawFrameFailure.INVALID_ENVELOPE))
    }

    private fun parseJson(text: String): JsonElement {
        if (hasTrailingRootContent(text)) reject(OpenClawFrameFailure.MALFORMED_JSON)
        val reader = JsonReader(StringReader(text)).apply { setStrictness(Strictness.STRICT) }
        return try {
            val value = readElement(reader, depth = 1)
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                reject(OpenClawFrameFailure.MALFORMED_JSON)
            }
            value
        } catch (failure: OpenClawFrameException) {
            throw failure
        } catch (_: RuntimeException) {
            throw OpenClawFrameException(OpenClawFrameFailure.MALFORMED_JSON)
        } finally {
            runCatching { reader.close() }
        }
    }

    /** Gson's streaming reader accepts another top-level value; the wire contract never does. */
    private fun hasTrailingRootContent(text: String): Boolean {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0 || text[start] != '{') return false
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{', '[' -> depth += 1
                '}', ']' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(index + 1).any { !it.isWhitespace() }
                    }
                }
            }
        }
        return false
    }

    private fun readElement(reader: JsonReader, depth: Int): JsonElement {
        if (depth > OpenClawProtocol.MAX_JSON_DEPTH) reject(OpenClawFrameFailure.TOO_DEEP)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val result = JsonObject()
                val names = HashSet<String>()
                reader.beginObject()
                var fields = 0
                while (reader.hasNext()) {
                    if (++fields > OpenClawProtocol.MAX_JSON_FIELDS_PER_OBJECT) {
                        reject(OpenClawFrameFailure.TOO_MANY_FIELDS)
                    }
                    val name = reader.nextName()
                    checkStringLimit(name)
                    if (!names.add(name)) reject(OpenClawFrameFailure.DUPLICATE_FIELD)
                    result.add(name, readElement(reader, depth + 1))
                }
                reader.endObject()
                result
            }
            JsonToken.BEGIN_ARRAY -> {
                val result = JsonArray()
                reader.beginArray()
                var items = 0
                while (reader.hasNext()) {
                    if (++items > OpenClawProtocol.MAX_JSON_ARRAY_ITEMS) {
                        reject(OpenClawFrameFailure.TOO_MANY_ITEMS)
                    }
                    result.add(readElement(reader, depth + 1))
                }
                reader.endArray()
                result
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString().also(::checkStringLimit))
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull.INSTANCE
            }
            else -> reject(OpenClawFrameFailure.MALFORMED_JSON)
        }
    }

    private fun validateElementLimits(element: JsonElement, depth: Int) {
        if (depth > OpenClawProtocol.MAX_JSON_DEPTH) reject(OpenClawFrameFailure.TOO_DEEP)
        when {
            element.isJsonObject -> {
                val entries = element.asJsonObject.entrySet()
                if (entries.size > OpenClawProtocol.MAX_JSON_FIELDS_PER_OBJECT) {
                    reject(OpenClawFrameFailure.TOO_MANY_FIELDS)
                }
                entries.forEach { (name, value) ->
                    checkStringLimit(name)
                    validateElementLimits(value, depth + 1)
                }
            }
            element.isJsonArray -> {
                if (element.asJsonArray.size() > OpenClawProtocol.MAX_JSON_ARRAY_ITEMS) {
                    reject(OpenClawFrameFailure.TOO_MANY_ITEMS)
                }
                element.asJsonArray.forEach { value -> validateElementLimits(value, depth + 1) }
            }
            element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                checkStringLimit(element.asString)
        }
    }

    private fun requiredString(obj: JsonObject, name: String, maxCharacters: Int): String {
        val element = obj[name]
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            reject(OpenClawFrameFailure.INVALID_ENVELOPE)
        }
        val value = element.asString
        if (value.isBlank() || value.length > maxCharacters || value.any(Char::isISOControl)) {
            reject(OpenClawFrameFailure.INVALID_ENVELOPE)
        }
        return value
    }

    private fun nonNegativeLong(element: JsonElement): Long {
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            reject(OpenClawFrameFailure.INVALID_ENVELOPE)
        }
        return runCatching { element.asBigDecimal.longValueExact() }
            .getOrNull()
            ?.takeIf { it >= 0 }
            ?: reject(OpenClawFrameFailure.INVALID_ENVELOPE)
    }

    private fun requireOnly(obj: JsonObject, names: Set<String>) {
        if (obj.keySet().any { it !in names }) reject(OpenClawFrameFailure.INVALID_ENVELOPE)
    }

    private fun checkStringLimit(value: String) {
        val utf8Bytes = value.strictUtf8ByteCountOrNull()
            ?: reject(OpenClawFrameFailure.MALFORMED_JSON)
        if (utf8Bytes > OpenClawProtocol.MAX_JSON_STRING_UTF8_BYTES) {
            reject(OpenClawFrameFailure.STRING_TOO_LARGE)
        }
    }

    private fun reject(failure: OpenClawFrameFailure): Nothing = throw OpenClawFrameException(failure)

    private companion object {
        const val MAX_DISPATCH_STRING_CHARACTERS = 16
        const val MAX_ID_CHARACTERS = 128
        const val MAX_METHOD_CHARACTERS = 128
        const val MAX_EVENT_NAME_CHARACTERS = 128
        const val MAX_ERROR_CODE_CHARACTERS = 128
        const val MAX_ERROR_MESSAGE_CHARACTERS = 4_096

        val RESPONSE_FIELDS = setOf("type", "id", "ok", "payload", "error")
        val EVENT_FIELDS = setOf("type", "event", "payload", "seq", "stateVersion")
        val ERROR_FIELDS = setOf("code", "message", "details", "retryable", "retryAfterMs")
        val CONNECT_ERROR_DETAIL_FIELDS = setOf(
            "code",
            "authReason",
            "canRetryWithDeviceToken",
            "recommendedNextStep",
            "reason",
            "requestId",
            "remediationHint",
            "retryable",
            "pauseReconnect",
            "deviceId",
            "requestedRole",
            "requestedScopes",
            "approvedRoles",
            "approvedScopes",
            "clientMinProtocol",
            "clientMaxProtocol",
            "expectedProtocol",
            "minimumProbeProtocol",
            "gatewayBuildId",
            "reloadRequired",
            "clientVersion",
            "gatewayVersion",
        )
        val STATE_VERSION_FIELDS = setOf("presence", "health")
    }
}

private data class ConnectErrorDetailsProjection(
    val code: String?,
    val valid: Boolean,
)

internal fun normalizeConnectErrorCode(value: String): String? {
    if (value.any(Char::isISOControl)) return null
    val normalized = value.trim().uppercase(Locale.ROOT)
    return normalized.takeIf {
        it.isNotEmpty() &&
            it.length <= 128 &&
            CONNECT_ERROR_CODE_PATTERN.matches(it)
    }
}

private val CONNECT_ERROR_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,127}")

private fun String.strictUtf8ByteCountOrNull(): Int? = runCatching {
    Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(this))
        .remaining()
}.getOrNull()
