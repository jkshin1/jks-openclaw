package com.personaledge.core.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.net.URI
import java.util.concurrent.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** A closed, content-free identity suitable for receipts and bounded diagnostics. */
enum class WebSearchProvider {
    YOU_COM,
    TAVILY,
}

data class WebSearchHit(
    val title: String,
    val link: String,
    val snippet: String,
)

data class WebSearchResponse(
    val provider: WebSearchProvider,
    val hits: List<WebSearchHit>,
)

interface WebSearchGateway {
    suspend fun credentialsPresent(): Boolean

    suspend fun search(query: String, limit: Int): WebSearchResponse
}

/**
 * Keyless You.com search through one hard-scoped hosted MCP tool.
 *
 * This is deliberately not a general MCP client: callers cannot choose an endpoint, profile,
 * method, tool name, country, language, or safety policy. The only variable provider data is the
 * owner-enabled canonical query and bounded result count. The hosted free profile accepts a
 * stateless `tools/call`, so no discovery, initialize, session, redirect, or provider-supplied
 * URL is followed.
 */
class YouKeylessMcpWebSearchGateway(
    private val transport: HttpTransport,
) : WebSearchGateway {

    override suspend fun credentialsPresent(): Boolean = true

    override suspend fun search(query: String, limit: Int): WebSearchResponse {
        validateWebSearchRequest(query, limit)

        val response = transport.post(
            url = ENDPOINT,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json, text/event-stream",
                "MCP-Protocol-Version" to MCP_PROTOCOL_VERSION,
            ),
            body = requestBody(query, limit),
        )
        if (!response.isSuccessful) throw youHttpFailure(response.statusCode)

        val envelope = parseMcpEnvelope(response.body)
        envelope.obj("error")?.let { error ->
            throw RemoteServiceException(
                failureCode = when (error.long("code")) {
                    -32_600L, -32_602L -> ToolFailureCode.INVALID_REQUEST
                    -32_601L -> ToolFailureCode.ENDPOINT_NOT_FOUND
                    else -> ToolFailureCode.OTHER_PROVIDER_ERROR
                },
                message = "You.com 검색 도구가 요청을 완료하지 못했습니다.",
            )
        }
        val result = envelope.obj("result") ?: malformedYouResponse()
        if (result.boolean("isError") == true) {
            throw RemoteServiceException(
                ToolFailureCode.OTHER_PROVIDER_ERROR,
                "You.com 검색 도구가 요청을 완료하지 못했습니다.",
            )
        }

        val rawHits = result.obj("structuredContent")
            ?.extractYouHitsOrNull()
            ?: result.extractContentTextPayloadOrNull()?.extractYouHitsOrNull()
            ?: malformedYouResponse()

        return WebSearchResponse(
            provider = WebSearchProvider.YOU_COM,
            hits = normalizeWebSearchHits(rawHits, limit),
        )
    }

    private fun requestBody(query: String, limit: Int): String {
        val arguments = JsonObject().apply {
            addProperty("query", query)
            addProperty("count", limit)
            addProperty("country", "KR")
            addProperty("language", "KO")
            addProperty("safesearch", "strict")
        }
        val params = JsonObject().apply {
            addProperty("name", TOOL_NAME)
            add("arguments", arguments)
        }
        return JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", REQUEST_ID)
            addProperty("method", "tools/call")
            add("params", params)
        }.toString()
    }

    private fun parseMcpEnvelope(body: String): JsonObject {
        parseJsonObjectOrNull(body.trim())?.let { document ->
            if (document.isMatchingMcpResponse()) return document
        }

        val events = mutableListOf<JsonObject>()
        val dataLines = mutableListOf<String>()
        fun finishEvent() {
            if (dataLines.isEmpty()) return
            parseJsonObjectOrNull(dataLines.joinToString("\n"))?.let(events::add)
            dataLines.clear()
        }
        body.lineSequence().forEach { line ->
            if (line.isBlank()) {
                finishEvent()
            } else if (line.startsWith(SSE_DATA_PREFIX)) {
                dataLines += line.removePrefix(SSE_DATA_PREFIX).trimStart()
            }
        }
        finishEvent()

        return events.lastOrNull { event -> event.isMatchingMcpResponse() }
            ?: malformedYouResponse()
    }

    private fun JsonObject.isMatchingMcpResponse(): Boolean {
        if (string("jsonrpc") != "2.0") return false
        val id = primitive("id") ?: return false
        return id.asString == REQUEST_ID.toString() && (obj("result") != null || obj("error") != null)
    }

    private fun JsonObject.extractContentTextPayloadOrNull(): JsonObject? {
        val content = array("content") ?: return null
        for (index in 0 until content.size()) {
            val item = content.objectAt(index) ?: continue
            if (item.string("type") != "text") continue
            val text = item.string("text") ?: continue
            parseJsonObjectOrNull(text)?.let { return it }
        }
        return null
    }

    private fun JsonObject.extractYouHitsOrNull(): List<WebSearchHit>? {
        val results = obj("results") ?: return null
        val web = results.optionalArray("web")
        val news = results.optionalArray("news")
        if (!web.present && !news.present) return null
        if (web.malformed || news.malformed) return null

        return buildList {
            addYouItems(web.value)
            addYouItems(news.value)
        }
    }

    private fun MutableList<WebSearchHit>.addYouItems(items: JsonArray?) {
        if (items == null) return
        for (index in 0 until items.size()) {
            val item = items.objectAt(index) ?: continue
            val description = item.string("description")
                ?: item.array("snippets")?.firstNonBlankString()
                ?: ""
            add(
                WebSearchHit(
                    title = item.string("title").orEmpty(),
                    link = item.string("url").orEmpty(),
                    snippet = description,
                ),
            )
        }
    }

    private companion object {
        const val HOST = "api.you.com"
        const val ENDPOINT = "https://$HOST/mcp?profile=free"
        const val TOOL_NAME = "you-search"
        const val MCP_PROTOCOL_VERSION = "2025-06-18"
        const val REQUEST_ID = 1L
        const val SSE_DATA_PREFIX = "data:"
    }
}

/** Tavily's bounded basic search endpoint, used only after the You.com quality/failure gate. */
class TavilyWebSearchGateway(
    private val transport: HttpTransport,
    private val apiKey: suspend () -> String?,
) : WebSearchGateway {

    override suspend fun credentialsPresent(): Boolean = try {
        apiKey()?.isSafeTavilyApiKey() == true
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        false
    }

    override suspend fun search(query: String, limit: Int): WebSearchResponse {
        validateWebSearchRequest(query, limit)
        val key = apiKey() ?: throw RemoteServiceException(
            ToolFailureCode.CREDENTIALS_MISSING,
            "Tavily API 키가 설정되지 않았습니다.",
        )
        if (!key.isSafeTavilyApiKey()) {
            throw RemoteServiceException(
                ToolFailureCode.AUTHENTICATION_FAILED,
                "Tavily API 키 형식이 올바르지 않습니다.",
            )
        }

        val body = JsonObject().apply {
            addProperty("query", query)
            addProperty("search_depth", "basic")
            addProperty("topic", "general")
            addProperty("max_results", limit)
            addProperty("include_answer", false)
            addProperty("include_raw_content", false)
            addProperty("include_images", false)
            addProperty("include_image_descriptions", false)
            addProperty("include_favicon", false)
            addProperty("auto_parameters", false)
            addProperty("include_usage", false)
            addProperty("country", "south korea")
        }.toString()
        val response = transport.post(
            url = SEARCH_ENDPOINT,
            headers = mapOf(
                "Authorization" to "Bearer $key",
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            ),
            body = body,
        )
        if (!response.isSuccessful) throw tavilyHttpFailure(response.statusCode)

        val results = parseJsonObject(response.body, "Tavily 검색 응답 형식이 예상과 다릅니다.")
            .array("results")
            ?: throw RemoteServiceException(
                ToolFailureCode.MALFORMED_RESPONSE,
                "Tavily 검색 응답 형식이 예상과 다릅니다.",
            )
        val rawHits = buildList {
            for (index in 0 until results.size()) {
                val item = results.objectAt(index) ?: continue
                if (!item.passesTavilyScoreGate()) continue
                add(
                    WebSearchHit(
                        title = item.string("title").orEmpty(),
                        link = item.string("url").orEmpty(),
                        snippet = item.string("content").orEmpty(),
                    ),
                )
            }
        }
        return WebSearchResponse(
            provider = WebSearchProvider.TAVILY,
            hits = normalizeWebSearchHits(rawHits, limit),
        )
    }

    private companion object {
        const val SEARCH_ENDPOINT = "https://api.tavily.com/search"
    }
}

sealed interface TavilyApiKeyVerification {
    data class Valid(
        /** Optional provider metadata; HTTP 200 is the authentication decision. */
        val usage: Long?,
        val limit: Long?,
    ) : TavilyApiKeyVerification

    object Invalid : TavilyApiKeyVerification

    object RateLimited : TavilyApiKeyVerification
}

/** Validates a candidate key without persisting it and without sending a search query. */
class TavilyApiKeyVerifier(
    private val transport: HttpTransport,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS)
    }

    suspend fun verify(candidateKey: String): TavilyApiKeyVerification {
        if (!candidateKey.isSafeTavilyApiKey()) return TavilyApiKeyVerification.Invalid

        val response = try {
            withTimeout(timeoutMillis) {
                transport.get(
                    url = USAGE_ENDPOINT,
                    headers = mapOf(
                        "Authorization" to "Bearer $candidateKey",
                        "Accept" to "application/json",
                    ),
                )
            }
        } catch (failure: TimeoutCancellationException) {
            throw RemoteServiceException(
                ToolFailureCode.PROVIDER_TIMEOUT,
                "Tavily 키 확인 시간이 초과되었습니다.",
                failure,
            )
        }

        return when (response.statusCode) {
            200 -> {
                // This call exists to establish that Tavily accepted the candidate credential.
                // Usage counters are informative and have changed shape over time, so a 200 from
                // the fixed TLS endpoint remains valid even when those optional counters are absent.
                val keyUsage = parseJsonObjectOrNull(response.body)?.obj("key")
                TavilyApiKeyVerification.Valid(
                    usage = keyUsage?.nonNegativeLong("usage"),
                    limit = keyUsage?.nonNegativeLong("limit"),
                )
            }
            401, 403 -> TavilyApiKeyVerification.Invalid
            429 -> TavilyApiKeyVerification.RateLimited
            else -> throw tavilyHttpFailure(response.statusCode)
        }
    }

    private companion object {
        const val USAGE_ENDPOINT = "https://api.tavily.com/usage"
        const val DEFAULT_TIMEOUT_MILLIS = 6_000L
        const val MAX_TIMEOUT_MILLIS = 30_000L
    }
}

/**
 * Serial You.com-primary / Tavily-fallback router.
 *
 * Calls are never raced. Tavily starts only after You.com returns, fails with a fallback-safe
 * provider reason, or reaches its own deadline. A caller cancellation is never converted into a
 * fallback request. The provider tag is checked at the trust boundary before being returned.
 */
class YouTavilyWebSearchGateway(
    private val youGateway: WebSearchGateway,
    private val tavilyGateway: WebSearchGateway,
    private val youTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
    private val tavilyTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
    private val monotonicClockMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) : WebSearchGateway {
    private val circuitMutex = Mutex()
    private var consecutiveTransientFailures = 0
    private var circuitOpenUntilMillis = 0L
    private var halfOpenProbeInFlight = false

    init {
        require(youTimeoutMillis in 1..MAX_PROVIDER_TIMEOUT_MILLIS)
        require(tavilyTimeoutMillis in 1..MAX_PROVIDER_TIMEOUT_MILLIS)
    }

    override suspend fun credentialsPresent(): Boolean = youGateway.credentialsPresent()

    override suspend fun search(query: String, limit: Int): WebSearchResponse {
        validateWebSearchRequest(query, limit)

        var primaryAttempt = acquirePrimaryAttempt()
        if (primaryAttempt == PrimaryAttempt.BYPASS) {
            if (fallbackCredentialsPresent()) {
                return fallbackAfterFailure(
                    query = query,
                    limit = limit,
                    primaryFailure = circuitOpenFailure(),
                    fallbackKnownPresent = true,
                )
            }
            // You.com is still the zero-setup path. Without a configured fallback, retry it
            // instead of turning a transient circuit state into a 15-minute local outage.
            primaryAttempt = PrimaryAttempt.NORMAL
        }

        val primaryResponse = try {
            callBounded(
                gateway = youGateway,
                expectedProvider = WebSearchProvider.YOU_COM,
                query = query,
                limit = limit,
                timeoutMillis = youTimeoutMillis,
            )
        } catch (failure: CancellationException) {
            releaseCancelledAttempt(primaryAttempt)
            throw failure
        } catch (failure: ToolExecutionException) {
            if (failure.failureCode !in FALLBACK_ELIGIBLE_FAILURES) {
                recordNonTransientPrimaryResult(primaryAttempt)
                throw failure
            }
            recordTransientPrimaryFailure(primaryAttempt)
            return fallbackAfterFailure(query, limit, failure)
        }
        recordPrimarySuccess()

        val cleanedPrimary = primaryResponse.copy(
            hits = normalizeWebSearchHits(primaryResponse.hits, limit),
        )
        if (isAcceptableYouResponse(query, limit, cleanedPrimary.hits)) return cleanedPrimary
        if (!fallbackCredentialsPresent()) return cleanedPrimary

        return try {
            callBounded(
                gateway = tavilyGateway,
                expectedProvider = WebSearchProvider.TAVILY,
                query = query,
                limit = limit,
                timeoutMillis = tavilyTimeoutMillis,
            ).let { response ->
                response.copy(hits = normalizeWebSearchHits(response.hits, limit))
            }
        } catch (failure: ToolExecutionException) {
            // A real, if weak, primary response is safer and more useful than inventing an answer
            // or replacing it with a secondary-provider error.
            cleanedPrimary
        }
    }

    private suspend fun fallbackAfterFailure(
        query: String,
        limit: Int,
        primaryFailure: ToolExecutionException,
        fallbackKnownPresent: Boolean = false,
    ): WebSearchResponse {
        if (!fallbackKnownPresent && !fallbackCredentialsPresent()) throw primaryFailure
        return callBounded(
            gateway = tavilyGateway,
            expectedProvider = WebSearchProvider.TAVILY,
            query = query,
            limit = limit,
            timeoutMillis = tavilyTimeoutMillis,
        ).let { response ->
            response.copy(hits = normalizeWebSearchHits(response.hits, limit))
        }
    }

    private suspend fun fallbackCredentialsPresent(): Boolean = try {
        tavilyGateway.credentialsPresent()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        false
    }

    private suspend fun acquirePrimaryAttempt(): PrimaryAttempt = circuitMutex.withLock {
        val now = monotonicClockMillis()
        when {
            now < circuitOpenUntilMillis -> PrimaryAttempt.BYPASS
            circuitOpenUntilMillis > 0L && halfOpenProbeInFlight -> PrimaryAttempt.BYPASS
            circuitOpenUntilMillis > 0L -> {
                halfOpenProbeInFlight = true
                PrimaryAttempt.HALF_OPEN
            }
            else -> PrimaryAttempt.NORMAL
        }
    }

    private suspend fun recordPrimarySuccess() = circuitMutex.withLock {
        consecutiveTransientFailures = 0
        circuitOpenUntilMillis = 0L
        halfOpenProbeInFlight = false
    }

    private suspend fun recordTransientPrimaryFailure(attempt: PrimaryAttempt) = circuitMutex.withLock {
        halfOpenProbeInFlight = false
        consecutiveTransientFailures = if (attempt == PrimaryAttempt.HALF_OPEN) {
            CIRCUIT_FAILURE_THRESHOLD
        } else {
            consecutiveTransientFailures + 1
        }
        if (consecutiveTransientFailures >= CIRCUIT_FAILURE_THRESHOLD) {
            circuitOpenUntilMillis = monotonicClockMillis() + CIRCUIT_OPEN_MILLIS
        }
    }

    private suspend fun recordNonTransientPrimaryResult(attempt: PrimaryAttempt) = circuitMutex.withLock {
        if (attempt == PrimaryAttempt.HALF_OPEN) {
            halfOpenProbeInFlight = false
        }
        consecutiveTransientFailures = 0
        circuitOpenUntilMillis = 0L
    }

    private suspend fun releaseCancelledAttempt(attempt: PrimaryAttempt) = circuitMutex.withLock {
        if (attempt == PrimaryAttempt.HALF_OPEN) {
            halfOpenProbeInFlight = false
        }
    }

    private fun circuitOpenFailure() = RemoteServiceException(
        ToolFailureCode.PROVIDER_UNAVAILABLE,
        "You.com 검색 회로가 일시적으로 열려 있습니다.",
    )

    private suspend fun callBounded(
        gateway: WebSearchGateway,
        expectedProvider: WebSearchProvider,
        query: String,
        limit: Int,
        timeoutMillis: Long,
    ): WebSearchResponse {
        val response = try {
            withTimeout(timeoutMillis) { gateway.search(query, limit) }
        } catch (failure: TimeoutCancellationException) {
            throw RemoteServiceException(
                ToolFailureCode.PROVIDER_TIMEOUT,
                "웹 검색 제공자 응답 시간이 초과되었습니다.",
                failure,
            )
        }
        if (response.provider != expectedProvider) {
            throw RemoteServiceException(
                ToolFailureCode.CLIENT_POLICY_FAILURE,
                "웹 검색 제공자 식별자가 예상과 다릅니다.",
            )
        }
        return response
    }

    private fun isAcceptableYouResponse(
        query: String,
        limit: Int,
        hits: List<WebSearchHit>,
    ): Boolean {
        if (hits.isEmpty()) return false
        val substantive = hits.filter { hit -> hit.snippet.isNotBlank() }
        if (!WebSearchQueryRelevance.hasRelevantHit(query, substantive)) return false
        if (limit == 1 || query.isExplicitlyNarrowSearch()) return substantive.isNotEmpty()
        return substantive.size >= MIN_GENERAL_RESULTS &&
            substantive.mapNotNull(WebSearchHit::httpsDomain).distinct().size >= MIN_GENERAL_DOMAINS
    }

    private companion object {
        const val DEFAULT_PROVIDER_TIMEOUT_MILLIS = 8_000L
        const val MAX_PROVIDER_TIMEOUT_MILLIS = 30_000L
        const val MIN_GENERAL_RESULTS = 2
        const val MIN_GENERAL_DOMAINS = 2
        const val CIRCUIT_FAILURE_THRESHOLD = 2
        const val CIRCUIT_OPEN_MILLIS = 15L * 60L * 1_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L

        val FALLBACK_ELIGIBLE_FAILURES = setOf(
            ToolFailureCode.RATE_LIMITED,
            ToolFailureCode.API_DISABLED_OR_QUOTA_EXCEEDED,
            ToolFailureCode.PROVIDER_TIMEOUT,
            ToolFailureCode.PROVIDER_UNAVAILABLE,
            ToolFailureCode.NETWORK_FAILURE,
        )
    }

    private enum class PrimaryAttempt {
        NORMAL,
        HALF_OPEN,
        BYPASS,
    }
}

private fun validateWebSearchRequest(query: String, limit: Int) {
    if (limit !in 1..MAX_PROVIDER_RESULTS || query.isBlank() ||
        CalendarText.codePointLength(query) > WebSearchTool.MAX_QUERY_CHARACTERS ||
        !CalendarText.isSafeText(query)
    ) {
        throw RemoteServiceException(
            ToolFailureCode.INVALID_REQUEST,
            "웹 검색 요청 형식이 올바르지 않습니다.",
        )
    }
}

private fun normalizeWebSearchHits(
    hits: List<WebSearchHit>,
    limit: Int,
): List<WebSearchHit> {
    val seen = mutableSetOf<String>()
    return buildList {
        for (raw in hits) {
            if (size >= limit) break
            val link = UntrustedText.canonicalDisplayableLink(raw.link) ?: continue
            val uri = runCatching { URI(link) }.getOrNull() ?: continue
            if (!uri.scheme.equals("https", ignoreCase = true)) continue
            val title = UntrustedText.clean(raw.title, MAX_TITLE_CHARACTERS)
            if (title.isBlank()) continue
            val deduplicationKey = uri.deduplicationKey()
            if (!seen.add(deduplicationKey)) continue
            add(
                WebSearchHit(
                    title = title,
                    link = link,
                    snippet = UntrustedText.clean(raw.snippet, MAX_SNIPPET_CHARACTERS),
                ),
            )
        }
    }
}

private fun WebSearchHit.httpsDomain(): String? = runCatching { URI(link) }
    .getOrNull()
    ?.takeIf { uri -> uri.scheme.equals("https", ignoreCase = true) }
    ?.host
    ?.lowercase()
    ?.removePrefix("www.")

private fun URI.deduplicationKey(): String = buildString {
    append("https://")
    append(host.lowercase())
    if (port != -1 && port != 443) append(":$port")
    append(rawPath.orEmpty().ifEmpty { "/" })
    rawQuery?.let { query -> append("?$query") }
}

private fun String.isExplicitlyNarrowSearch(): Boolean {
    val trimmed = trim()
    val quoted = trimmed.length >= 3 && trimmed.startsWith('"') && trimmed.endsWith('"')
    return quoted || DOMAIN_IN_QUERY.containsMatchIn(trimmed)
}

private fun String.isSafeTavilyApiKey(): Boolean =
    isNotBlank() && length <= MAX_API_KEY_CHARACTERS && none { character ->
        character.isWhitespace() || Character.isISOControl(character)
    }

private fun youHttpFailure(statusCode: Int): RemoteServiceException = when (statusCode) {
    400 -> RemoteServiceException(ToolFailureCode.INVALID_REQUEST, "You.com 검색 요청이 거부되었습니다.")
    401 -> RemoteServiceException(ToolFailureCode.AUTHENTICATION_FAILED, "You.com 검색 인증에 실패했습니다.")
    403 -> RemoteServiceException(ToolFailureCode.PERMISSION_DENIED, "You.com 검색 권한이 거부되었습니다.")
    404 -> RemoteServiceException(ToolFailureCode.ENDPOINT_NOT_FOUND, "You.com 검색 연결 주소를 찾지 못했습니다.")
    405 -> RemoteServiceException(ToolFailureCode.CLIENT_POLICY_FAILURE, "You.com 검색 요청 방식이 올바르지 않습니다.")
    413 -> RemoteServiceException(ToolFailureCode.REQUEST_TOO_LARGE, "You.com 검색 요청이 허용 크기를 초과했습니다.")
    429 -> RemoteServiceException(ToolFailureCode.RATE_LIMITED, "You.com 검색 요청이 일시 제한되었습니다.")
    500, 502, 503 -> RemoteServiceException(ToolFailureCode.PROVIDER_UNAVAILABLE, "You.com 검색 서비스가 일시적으로 응답하지 않습니다.")
    504 -> RemoteServiceException(ToolFailureCode.PROVIDER_TIMEOUT, "You.com 검색 서비스 응답 시간이 초과되었습니다.")
    else -> RemoteServiceException(ToolFailureCode.OTHER_PROVIDER_ERROR, "You.com 검색 요청이 실패했습니다.")
}

private fun tavilyHttpFailure(statusCode: Int): RemoteServiceException = when (statusCode) {
    400, 422 -> RemoteServiceException(ToolFailureCode.INVALID_REQUEST, "Tavily 요청이 거부되었습니다.")
    401 -> RemoteServiceException(ToolFailureCode.AUTHENTICATION_FAILED, "Tavily 인증에 실패했습니다.")
    403 -> RemoteServiceException(ToolFailureCode.PERMISSION_DENIED, "Tavily 권한이 거부되었습니다.")
    404 -> RemoteServiceException(ToolFailureCode.ENDPOINT_NOT_FOUND, "Tavily 연결 주소를 찾지 못했습니다.")
    413 -> RemoteServiceException(ToolFailureCode.REQUEST_TOO_LARGE, "Tavily 요청이 허용 크기를 초과했습니다.")
    429 -> RemoteServiceException(ToolFailureCode.RATE_LIMITED, "Tavily 요청이 일시 제한되었습니다.")
    432, 433 -> RemoteServiceException(ToolFailureCode.API_DISABLED_OR_QUOTA_EXCEEDED, "Tavily 사용 한도를 초과했습니다.")
    500, 502, 503 -> RemoteServiceException(ToolFailureCode.PROVIDER_UNAVAILABLE, "Tavily 서비스가 일시적으로 응답하지 않습니다.")
    504 -> RemoteServiceException(ToolFailureCode.PROVIDER_TIMEOUT, "Tavily 서비스 응답 시간이 초과되었습니다.")
    else -> RemoteServiceException(ToolFailureCode.OTHER_PROVIDER_ERROR, "Tavily 요청이 실패했습니다.")
}

private fun malformedYouResponse(): Nothing = throw RemoteServiceException(
    ToolFailureCode.MALFORMED_RESPONSE,
    "You.com 검색 응답 형식이 예상과 다릅니다.",
)

private fun parseJsonObject(body: String, failureMessage: String): JsonObject =
    parseJsonObjectOrNull(body) ?: throw RemoteServiceException(
        ToolFailureCode.MALFORMED_RESPONSE,
        failureMessage,
    )

private fun parseJsonObjectOrNull(body: String): JsonObject? = try {
    JsonParser.parseString(body) as? JsonObject
} catch (_: JsonParseException) {
    null
}

private data class OptionalJsonArray(
    val present: Boolean,
    val malformed: Boolean,
    val value: JsonArray?,
)

private fun JsonObject.optionalArray(name: String): OptionalJsonArray {
    if (!has(name)) return OptionalJsonArray(present = false, malformed = false, value = null)
    val value = get(name)
    return OptionalJsonArray(
        present = true,
        malformed = value !is JsonArray,
        value = value as? JsonArray,
    )
}

private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject

private fun JsonObject.array(name: String): JsonArray? = get(name) as? JsonArray

private fun JsonObject.primitive(name: String): JsonPrimitive? = get(name) as? JsonPrimitive

private fun JsonObject.string(name: String): String? = primitive(name)
    ?.takeIf(JsonPrimitive::isString)
    ?.asString
    ?.takeIf(String::isNotBlank)

private fun JsonObject.long(name: String): Long? = primitive(name)
    ?.takeIf(JsonPrimitive::isNumber)
    ?.asString
    ?.toLongOrNull()

private fun JsonObject.nonNegativeLong(name: String): Long? = long(name)?.takeIf { it >= 0L }

private fun JsonObject.boolean(name: String): Boolean? = primitive(name)
    ?.takeIf(JsonPrimitive::isBoolean)
    ?.asBoolean

private fun JsonObject.passesTavilyScoreGate(): Boolean {
    val score = primitive("score") ?: return true
    if (!score.isNumber) return true
    val value = score.asString.toDoubleOrNull() ?: return true
    return value.isFinite() && value >= MIN_TAVILY_SCORE
}

private fun JsonArray.objectAt(index: Int): JsonObject? =
    if (index in 0 until size()) get(index) as? JsonObject else null

private fun JsonArray.firstNonBlankString(): String? {
    for (index in 0 until size()) {
        val text = (get(index) as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.asString
            ?.takeIf(String::isNotBlank)
        if (text != null) return text
    }
    return null
}

/** Hosts needed by Maps, the two explicitly selected search providers, and weather. */
val OUTBOUND_NETWORK_ALLOWED_HOSTS: Set<String> = NAVER_ALLOWED_HOSTS + setOf(
    "api.you.com",
    "api.tavily.com",
    "api.open-meteo.com",
)

private val DOMAIN_IN_QUERY = Regex(
    pattern = "(?i)(?:https://)?(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:[/\\s]|$)",
)
private const val MAX_PROVIDER_RESULTS = WebSearchTool.MAX_HITS
private const val MAX_TITLE_CHARACTERS = 120
private const val MAX_SNIPPET_CHARACTERS = 240
private const val MAX_API_KEY_CHARACTERS = 512
private const val MIN_TAVILY_SCORE = 0.5
