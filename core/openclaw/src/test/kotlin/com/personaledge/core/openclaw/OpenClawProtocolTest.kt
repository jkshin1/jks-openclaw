package com.personaledge.core.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawProtocolTest {
    @Test
    fun `constants match signed OpenClaw 2026 8 1 client package`() {
        assertEquals("2026.8.1", OpenClawProtocol.RELEASE_VERSION)
        assertEquals(4, OpenClawProtocol.WIRE_VERSION)
        assertEquals(4, OpenClawProtocol.MIN_CLIENT_WIRE_VERSION)
        assertEquals("gateway-client", OpenClawProtocol.CLIENT_ID)
        assertNotEquals("openclaw-android", OpenClawProtocol.CLIENT_ID)
        assertEquals("ui", OpenClawProtocol.CLIENT_MODE)
        assertEquals("operator", OpenClawProtocol.ROLE)
        assertEquals(
            setOf("agent", "agent.wait", "chat.abort"),
            OpenClawProtocol.APP_METHODS,
        )
        assertEquals(listOf("operator.read", "operator.write"), OpenClawProtocol.SCOPES)
        assertEquals(setOf("personaledge.health.read"), OpenClawProtocol.OPTIONAL_APP_METHODS)
    }

    @Test
    fun `qualified releases stay an exact set containing the deployed baseline`() {
        // Widening this to a prefix, a range, or a comparison would let an unqualified Gateway
        // negotiate, which is exactly what the pin exists to prevent.
        assertEquals(
            setOf("2026.8.1", "2026.9.2"),
            OpenClawProtocol.QUALIFIED_RELEASE_VERSIONS,
        )
        assertTrue(OpenClawProtocol.RELEASE_VERSION in OpenClawProtocol.QUALIFIED_RELEASE_VERSIONS)
    }
}
