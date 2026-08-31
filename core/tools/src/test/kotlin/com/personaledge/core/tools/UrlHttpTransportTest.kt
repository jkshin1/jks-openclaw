package com.personaledge.core.tools

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport's job is to refuse before it connects.
 *
 * Every case here is rejected during URL and header inspection, so nothing reaches the network —
 * which is the property worth testing. TLS behaviour and real responses are the remote services'
 * concern and are covered by the gateway tests against recorded shapes.
 */
class UrlHttpTransportTest {
    private val transport = UrlHttpTransport(allowedHosts = setOf("maps.apigw.ntruss.com"))

    private fun refusal(url: String, headers: Map<String, String> = emptyMap()): HttpTransportException =
        assertThrows(HttpTransportException::class.java) {
            runBlocking { transport.get(url, headers) }
        }

    private fun refusalFor(url: String, headers: Map<String, String> = emptyMap()): String =
        refusal(url, headers).message.orEmpty()

    @Test
    fun `a host outside the allowlist is refused`() {
        // Credentials travel as request headers, so an unexpected host must never be dialled.
        assertEquals("허용되지 않은 호스트입니다.", refusalFor("https://evil.example.com/map-direction"))
        assertEquals(
            "허용되지 않은 호스트입니다.",
            refusalFor("https://maps.apigw.ntruss.com.evil.example.com/x"),
        )
        assertEquals(
            ToolFailureCode.CLIENT_POLICY_FAILURE,
            refusal("https://evil.example.com/map-direction").failureCode,
        )
    }

    @Test
    fun `a plain http url is refused even for an allowed host`() {
        assertEquals(
            "HTTPS가 아닌 요청은 보내지 않습니다.",
            refusalFor("http://maps.apigw.ntruss.com/map-geocode/v2/geocode"),
        )
        assertEquals(
            ToolFailureCode.CLIENT_POLICY_FAILURE,
            refusal("http://maps.apigw.ntruss.com/x").failureCode,
        )
    }

    @Test
    fun `an unparseable url is refused without throwing something untyped`() {
        assertEquals("요청 주소를 만들 수 없습니다.", refusalFor("not a url at all"))
        assertEquals("요청 주소를 만들 수 없습니다.", refusalFor(""))
        assertEquals(ToolFailureCode.CLIENT_POLICY_FAILURE, refusal("").failureCode)
    }

    @Test
    fun `a userinfo host trick does not smuggle a different destination`() {
        // https://allowed@evil.example.com/ has host evil.example.com, not the allowed one.
        assertEquals(
            "허용되지 않은 호스트입니다.",
            refusalFor("https://maps.apigw.ntruss.com@evil.example.com/x"),
        )
    }

    @Test
    fun `a header carrying a newline is rejected`() {
        val url = "https://maps.apigw.ntruss.com/map-geocode/v2/geocode?query=x"

        val valueFailure = assertThrows(HttpTransportException::class.java) {
            runBlocking { transport.get(url, mapOf("X-Key" to "value\r\nHost: evil.example.com")) }
        }
        val nameFailure = assertThrows(HttpTransportException::class.java) {
            runBlocking { transport.get(url, mapOf("X-Key\n" to "value")) }
        }
        assertEquals(ToolFailureCode.CLIENT_POLICY_FAILURE, valueFailure.failureCode)
        assertEquals(ToolFailureCode.CLIENT_POLICY_FAILURE, nameFailure.failureCode)
    }

    @Test
    fun `the allowlist cannot be empty or mixed case`() {
        assertThrows(IllegalArgumentException::class.java) {
            UrlHttpTransport(allowedHosts = emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            UrlHttpTransport(allowedHosts = setOf("Maps.apigw.ntruss.com"))
        }
    }

    @Test
    fun `host matching ignores case in the url`() {
        // Rejected for the scheme, not the host: the uppercase host still matched the allowlist.
        assertEquals(
            "HTTPS가 아닌 요청은 보내지 않습니다.",
            refusalFor("http://MAPS.apigw.ntruss.com/map-geocode/v2/geocode"),
        )
    }

    @Test
    fun `the remaining naver allowlist contains maps only`() {
        assertEquals(
            setOf("maps.apigw.ntruss.com"),
            NAVER_ALLOWED_HOSTS,
        )
        assertTrue(NAVER_ALLOWED_HOSTS.all { host -> host == host.lowercase() })
    }

    @Test
    fun `the complete outbound allowlist contains only selected network hosts`() {
        assertEquals(
            setOf(
                "maps.apigw.ntruss.com",
                "api.you.com",
                "api.tavily.com",
                "api.open-meteo.com",
            ),
            OUTBOUND_NETWORK_ALLOWED_HOSTS,
        )
        assertTrue(OUTBOUND_NETWORK_ALLOWED_HOSTS.all { host -> host == host.lowercase() })
    }

    @Test
    fun `transport IO reasons are closed and legacy construction stays compatible`() {
        assertEquals(
            ToolFailureCode.PROVIDER_TIMEOUT,
            SocketTimeoutException("private timeout").toTransportFailureCode(),
        )
        assertEquals(
            ToolFailureCode.NETWORK_FAILURE,
            IOException("private network detail").toTransportFailureCode(),
        )
        assertEquals(
            ToolFailureCode.NETWORK_FAILURE,
            HttpTransportException("legacy message").failureCode,
        )
    }

    @Test
    fun `response body at the exact byte cap is accepted`() {
        val body = "exact-cap"

        assertEquals(
            body,
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
                .readBoundedHttpResponseBody(body.toByteArray(Charsets.UTF_8).size),
        )
    }

    @Test
    fun `response body one byte over the cap fails closed with a typed reason`() {
        val failure = assertThrows(HttpTransportException::class.java) {
            ByteArrayInputStream("12345".toByteArray(Charsets.UTF_8))
                .readBoundedHttpResponseBody(4)
        }

        assertEquals(ToolFailureCode.MALFORMED_RESPONSE, failure.failureCode)
        assertEquals("외부 서비스 응답이 허용 크기를 초과했습니다.", failure.message)
    }

    @Test
    fun `valid json prefix followed by a trailer is never accepted as a complete response`() {
        val failure = assertThrows(HttpTransportException::class.java) {
            ByteArrayInputStream("{}ignored-trailer".toByteArray(Charsets.UTF_8))
                .readBoundedHttpResponseBody(2)
        }

        assertEquals(ToolFailureCode.MALFORMED_RESPONSE, failure.failureCode)
    }

    @Test
    fun `response cap counts utf8 bytes without splitting a character silently`() {
        val body = "가a"
        val bytes = body.toByteArray(Charsets.UTF_8)
        assertEquals(4, bytes.size)
        assertEquals(body, ByteArrayInputStream(bytes).readBoundedHttpResponseBody(4))

        val failure = assertThrows(HttpTransportException::class.java) {
            ByteArrayInputStream(bytes).readBoundedHttpResponseBody(3)
        }
        assertEquals(ToolFailureCode.MALFORMED_RESPONSE, failure.failureCode)
    }
}
