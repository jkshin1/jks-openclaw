package com.personaledge.core.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayEndpointPolicyTest {
    @Test
    fun `release accepts only canonicalized wss endpoints without URL credentials`() {
        val endpoint = GatewayEndpoint.parseRelease("WSS://EXAMPLE.com:443/gateway")

        assertNotNull(endpoint)
        assertEquals("wss://example.com/gateway", endpoint?.url)
        assertEquals("GatewayEndpoint(<redacted>)", endpoint.toString())
        assertFalse(endpoint.toString().contains("example.com"))
        assertFalse(endpoint.toString().contains(requireNotNull(endpoint).stableId.take(12)))
        assertEquals(endpoint, GatewayEndpoint.parseRelease("wss://example.com/gateway"))
    }

    @Test
    fun `release rejects cleartext smuggling redirects and ambiguous paths`() {
        listOf(
            "ws://example.com/",
            "http://example.com/",
            "wss://user:secret@example.com/",
            "wss://example.com/?token=secret",
            "wss://example.com/#fragment",
            " wss://example.com/",
            "wss://example.com/a/../b",
            "wss://example.com/a%2fb",
            "wss://example.com/a%5cb",
            "wss://example.com:0/",
            "wss://example.com/${"a".repeat(2_048)}",
        ).forEach { assertNull(it, GatewayEndpoint.parseRelease(it)) }
    }

    @Test
    fun `loopback cleartext constructor is internal test only and rejects remote hosts`() {
        val local = GatewayEndpoint.parseLoopbackForTest("ws://127.0.0.1:8080/socket")

        assertNotNull(local)
        assertEquals("ws://127.0.0.1:8080/socket", local?.url)
        assertNull(GatewayEndpoint.parseLoopbackForTest("ws://example.com/socket"))
        assertNotEquals(
            local?.stableId,
            GatewayEndpoint.parseRelease("wss://127.0.0.1:8080/socket")?.stableId,
        )
    }
}
