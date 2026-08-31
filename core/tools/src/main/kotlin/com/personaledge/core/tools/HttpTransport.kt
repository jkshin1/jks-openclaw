package com.personaledge.core.tools

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HttpResponse(
    val statusCode: Int,
    val body: String,
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299
}

class HttpTransportException(
    failureCode: ToolFailureCode,
    message: String,
    cause: Throwable? = null,
) : ToolExecutionException(failureCode, message, cause) {
    /** Compatibility for callers that have not classified an ordinary network failure yet. */
    constructor(message: String, cause: Throwable? = null) : this(
        ToolFailureCode.NETWORK_FAILURE,
        message,
        cause,
    )
}

/**
 * The single outbound network surface.
 *
 * Every remote call in this app goes through one implementation so the transport rules — HTTPS
 * only, a fixed host allowlist, bounded time and bounded body — are stated in one place rather
 * than repeated per gateway. Tests substitute a fake and never touch the network.
 */
interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse

    /**
     * Sends one bounded JSON request. The default is fail-closed so an older test double cannot
     * silently turn a POST-only provider into a GET or drop its body.
     */
    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): HttpResponse = throw HttpTransportException(
        ToolFailureCode.CLIENT_POLICY_FAILURE,
        "이 전송 구현은 POST 요청을 지원하지 않습니다.",
    )
}

/**
 * `HttpURLConnection` implementation. No third-party HTTP client: the needed surface is bounded
 * GET/POST, and this project verifies and locks every dependency it takes on.
 *
 * The host allowlist is the important part. Credentials are sent as request headers, so a URL
 * built from a bad assumption — or from any future caller — must not be able to deliver them
 * somewhere unintended. Redirects are refused for the same reason: a 302 is a server asking this
 * app to re-send those headers to a host it never checked.
 */
class UrlHttpTransport(
    private val allowedHosts: Set<String>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
) : HttpTransport {

    init {
        require(allowedHosts.isNotEmpty())
        require(allowedHosts.all { host -> host.isNotBlank() && host == host.lowercase() })
        require(maxBodyBytes > 0)
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpResponse = request(url = url, headers = headers, requestBody = null)

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): HttpResponse = request(url = url, headers = headers, requestBody = body)

    private suspend fun request(
        url: String,
        headers: Map<String, String>,
        requestBody: String?,
    ): HttpResponse = withContext(ioDispatcher) {
        val parsed = try {
            URL(url)
        } catch (failure: Exception) {
            throw HttpTransportException(
                ToolFailureCode.CLIENT_POLICY_FAILURE,
                "요청 주소를 만들 수 없습니다.",
                failure,
            )
        }

        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw HttpTransportException(
                ToolFailureCode.CLIENT_POLICY_FAILURE,
                "HTTPS가 아닌 요청은 보내지 않습니다.",
            )
        }
        if (parsed.host.lowercase() !in allowedHosts) {
            throw HttpTransportException(
                ToolFailureCode.CLIENT_POLICY_FAILURE,
                "허용되지 않은 호스트입니다.",
            )
        }
        // A header value carrying CR/LF could inject a second header, including another Host.
        if (!headers.all { (name, value) -> name.isSafeHeaderPart() && value.isSafeHeaderPart() }) {
            throw HttpTransportException(
                ToolFailureCode.CLIENT_POLICY_FAILURE,
                "안전하지 않은 요청 헤더를 보내지 않습니다.",
            )
        }
        val requestBytes = requestBody?.toByteArray(Charsets.UTF_8)
        if (requestBytes != null && requestBytes.size > DEFAULT_MAX_REQUEST_BODY_BYTES) {
            throw HttpTransportException(
                ToolFailureCode.REQUEST_TOO_LARGE,
                "요청 본문이 허용 크기를 초과했습니다.",
            )
        }

        var connection: HttpsURLConnection? = null
        try {
            connection = (parsed.openConnection() as HttpsURLConnection).apply {
                requestMethod = if (requestBytes == null) "GET" else "POST"
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                instanceFollowRedirects = false
                useCaches = false
                doInput = true
                doOutput = requestBytes != null
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (requestBytes != null) {
                    setFixedLengthStreamingMode(requestBytes.size)
                }
            }
            if (requestBytes != null) {
                connection.outputStream.use { output -> output.write(requestBytes) }
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.use { input ->
                input.readBoundedHttpResponseBody(maxBodyBytes)
            }.orEmpty()
            HttpResponse(statusCode = statusCode, body = body)
        } catch (failure: IOException) {
            throw HttpTransportException(
                failureCode = failure.toTransportFailureCode(),
                message = if (failure is SocketTimeoutException) {
                    "네트워크 요청 시간이 초과되었습니다."
                } else {
                    "네트워크 요청이 실패했습니다."
                },
                cause = failure,
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun String.isSafeHeaderPart(): Boolean =
        isNotEmpty() && none { character -> character == '\r' || character == '\n' }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000
        const val DEFAULT_MAX_BODY_BYTES = 256 * 1024
        const val DEFAULT_MAX_REQUEST_BODY_BYTES = 64 * 1024
    }
}

/**
 * Reads a UTF-8 response by bytes and proves EOF at [limit]. A valid prefix is never accepted as a
 * complete provider response: once the cap is filled, one bounded probe distinguishes exact-cap
 * EOF from an oversized body without retaining the extra byte.
 */
internal fun InputStream.readBoundedHttpResponseBody(limit: Int): String {
    require(limit > 0)
    val buffer = ByteArray(minOf(HTTP_READ_CHUNK_BYTES, limit))
    val collected = ByteArrayOutputStream(minOf(HTTP_READ_CHUNK_BYTES, limit))
    while (collected.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - collected.size()))
        when {
            read < 0 -> return collected.toString(Charsets.UTF_8.name())
            read == 0 -> continue
            else -> collected.write(buffer, 0, read)
        }
    }
    if (read() >= 0) {
        throw HttpTransportException(
            ToolFailureCode.MALFORMED_RESPONSE,
            "외부 서비스 응답이 허용 크기를 초과했습니다.",
        )
    }
    return collected.toString(Charsets.UTF_8.name())
}

private const val HTTP_READ_CHUNK_BYTES = 8 * 1024

internal fun IOException.toTransportFailureCode(): ToolFailureCode =
    if (this is SocketTimeoutException) {
        ToolFailureCode.PROVIDER_TIMEOUT
    } else {
        ToolFailureCode.NETWORK_FAILURE
    }
