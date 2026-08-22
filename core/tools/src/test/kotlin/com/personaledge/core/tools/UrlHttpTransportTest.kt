package com.personaledge.core.tools

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
    private val transport = UrlHttpTransport(allowedHosts = setOf("naveropenapi.apigw.ntruss.com"))

    private fun refusalFor(url: String, headers: Map<String, String> = emptyMap()): String =
        assertThrows(HttpTransportException::class.java) {
            runBlocking { transport.get(url, headers) }
        }.message.orEmpty()

    @Test
    fun `a host outside the allowlist is refused`() {
        // Credentials travel as request headers, so an unexpected host must never be dialled.
        assertEquals("허용되지 않은 호스트입니다.", refusalFor("https://evil.example.com/map-direction"))
        assertEquals(
            "허용되지 않은 호스트입니다.",
            refusalFor("https://naveropenapi.apigw.ntruss.com.evil.example.com/x"),
        )
    }

    @Test
    fun `a plain http url is refused even for an allowed host`() {
        assertEquals(
            "HTTPS가 아닌 요청은 보내지 않습니다.",
            refusalFor("http://naveropenapi.apigw.ntruss.com/map-geocode/v2/geocode"),
        )
    }

    @Test
    fun `an unparseable url is refused without throwing something untyped`() {
        assertEquals("요청 주소를 만들 수 없습니다.", refusalFor("not a url at all"))
        assertEquals("요청 주소를 만들 수 없습니다.", refusalFor(""))
    }

    @Test
    fun `a userinfo host trick does not smuggle a different destination`() {
        // https://allowed@evil.example.com/ has host evil.example.com, not the allowed one.
        assertEquals(
            "허용되지 않은 호스트입니다.",
            refusalFor("https://naveropenapi.apigw.ntruss.com@evil.example.com/x"),
        )
    }

    @Test
    fun `a header carrying a newline is rejected`() {
        val url = "https://naveropenapi.apigw.ntruss.com/map-geocode/v2/geocode?query=x"

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transport.get(url, mapOf("X-Key" to "value\r\nHost: evil.example.com")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transport.get(url, mapOf("X-Key\n" to "value")) }
        }
    }

    @Test
    fun `the allowlist cannot be empty or mixed case`() {
        assertThrows(IllegalArgumentException::class.java) {
            UrlHttpTransport(allowedHosts = emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            UrlHttpTransport(allowedHosts = setOf("NaverOpenApi.apigw.ntruss.com"))
        }
    }

    @Test
    fun `host matching ignores case in the url`() {
        // Rejected for the scheme, not the host: the uppercase host still matched the allowlist.
        assertEquals(
            "HTTPS가 아닌 요청은 보내지 않습니다.",
            refusalFor("http://NAVEROPENAPI.apigw.ntruss.com/map-geocode/v2/geocode"),
        )
    }

    @Test
    fun `the shipped allowlist is exactly the two naver hosts`() {
        assertEquals(
            setOf("naveropenapi.apigw.ntruss.com", "openapi.naver.com"),
            NAVER_ALLOWED_HOSTS,
        )
        assertTrue(NAVER_ALLOWED_HOSTS.all { host -> host == host.lowercase() })
    }
}
