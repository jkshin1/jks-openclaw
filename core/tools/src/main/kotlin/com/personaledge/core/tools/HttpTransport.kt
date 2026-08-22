package com.personaledge.core.tools

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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

class HttpTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The single outbound network surface.
 *
 * Every remote call in this app goes through one implementation so the transport rules — HTTPS
 * only, a fixed host allowlist, bounded time and bounded body — are stated in one place rather
 * than repeated per gateway. Tests substitute a fake and never touch the network.
 */
interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
}

/**
 * `HttpURLConnection` implementation. No third-party HTTP client: the needed surface is one
 * bounded GET, and this project verifies and locks every dependency it takes on.
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
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpResponse = withContext(ioDispatcher) {
        val parsed = try {
            URL(url)
        } catch (failure: Exception) {
            throw HttpTransportException("요청 주소를 만들 수 없습니다.", failure)
        }

        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw HttpTransportException("HTTPS가 아닌 요청은 보내지 않습니다.")
        }
        if (parsed.host.lowercase() !in allowedHosts) {
            throw HttpTransportException("허용되지 않은 호스트입니다.")
        }
        // A header value carrying CR/LF could inject a second header, including another Host.
        require(headers.all { (name, value) -> name.isSafeHeaderPart() && value.isSafeHeaderPart() })

        var connection: HttpsURLConnection? = null
        try {
            connection = (parsed.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                instanceFollowRedirects = false
                useCaches = false
                doInput = true
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.use { input -> input.readBounded(maxBodyBytes) }.orEmpty()
            HttpResponse(statusCode = statusCode, body = body)
        } catch (failure: IOException) {
            throw HttpTransportException("네트워크 요청이 실패했습니다.", failure)
        } finally {
            connection?.disconnect()
        }
    }

    /** Reads at most [limit] bytes so a hostile or broken server cannot exhaust memory. */
    private fun InputStream.readBounded(limit: Int): String {
        val buffer = ByteArray(READ_CHUNK_BYTES)
        val collected = ByteArrayOutputStream()
        while (collected.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - collected.size()))
            if (read <= 0) break
            collected.write(buffer, 0, read)
        }
        return collected.toString(Charsets.UTF_8.name())
    }

    private fun String.isSafeHeaderPart(): Boolean =
        isNotEmpty() && none { character -> character == '\r' || character == '\n' }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000
        const val DEFAULT_MAX_BODY_BYTES = 256 * 1024
        const val READ_CHUNK_BYTES = 8 * 1024
    }
}
