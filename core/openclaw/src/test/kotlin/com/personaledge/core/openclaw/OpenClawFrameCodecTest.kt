package com.personaledge.core.openclaw

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawFrameCodecTest {
    @Test
    fun `rejects malformed unicode in decoded and encoded strings`() {
        val malformedResponse =
            "{\"type\":\"res\",\"id\":\"bad\\uD800id\",\"ok\":true,\"payload\":{}}"

        assertFailure(OpenClawFrameFailure.MALFORMED_JSON) {
            codec.decodeServerFrame(malformedResponse)
        }
        assertFailure(OpenClawFrameFailure.MALFORMED_JSON) {
            codec.encodeRequest(
                id = "request-1",
                method = "agent",
                params = JsonObject().apply { addProperty("message", "bad\uD800prompt") },
                maxBytes = OpenClawProtocol.MAX_LOCAL_FRAME_BYTES,
            )
        }
    }

    private val codec = OpenClawFrameCodec()

    @Test
    fun `decodes exact response and event envelopes`() {
        val response = codec.decodeServerFrame(
            """{"type":"res","id":"r-1","ok":true,"payload":{"accepted":true}}""",
        ) as OpenClawResponseFrame
        val event = codec.decodeServerFrame(
            """{"type":"event","event":"agent","payload":null,"seq":3,"stateVersion":{"presence":1,"health":2}}""",
        ) as OpenClawEventFrame

        assertEquals("r-1", response.id)
        assertTrue(response.ok)
        assertEquals("agent", event.event)
        assertEquals(3L, event.sequence)
        assertFalse(response.toString().contains("accepted"))
        assertFalse(event.toString().contains("accepted"))
    }

    @Test
    fun `rejects duplicate keys trailing input unknown fields and inbound requests`() {
        assertFailure(OpenClawFrameFailure.DUPLICATE_FIELD) {
            codec.decodeServerFrame("""{"type":"event","event":"a","event":"b"}""")
        }
        assertFailure(OpenClawFrameFailure.MALFORMED_JSON) {
            codec.decodeServerFrame("""{"type":"event","event":"a"} false""")
        }
        assertFailure(OpenClawFrameFailure.INVALID_ENVELOPE) {
            codec.decodeServerFrame("""{"type":"event","event":"a","secret":"x"}""")
        }
        assertFailure(OpenClawFrameFailure.UNSUPPORTED_FRAME) {
            codec.decodeServerFrame("""{"type":"req","id":"x","method":"health"}""")
        }
        assertFailure(OpenClawFrameFailure.INVALID_ENVELOPE) {
            codec.decodeServerFrame("""{"type":"event","event":"agent\nforged"}""")
        }
        assertFailure(OpenClawFrameFailure.INVALID_ENVELOPE) {
            codec.decodeServerFrame(
                """{"type":"res","id":"x","ok":true,"error":{"code":"X","message":"secret"}}""",
            )
        }
        assertFailure(OpenClawFrameFailure.INVALID_ENVELOPE) {
            codec.decodeServerFrame("""{"type":"res","id":"x","ok":false}""")
        }
        assertFailure(OpenClawFrameFailure.INVALID_ENVELOPE) {
            codec.decodeServerFrame(
                """{"type":"res","id":"x","ok":false,"payload":{},"error":{"code":"X","message":"secret"}}""",
            )
        }
    }

    @Test
    fun `enforces byte depth field array and string bounds`() {
        assertFailure(OpenClawFrameFailure.TOO_LARGE) {
            codec.decodeServerFrame("""{"type":"event","event":"a"}""", maxBytes = 4)
        }
        val nested = "[".repeat(OpenClawProtocol.MAX_JSON_DEPTH) +
            "null" + "]".repeat(OpenClawProtocol.MAX_JSON_DEPTH)
        assertFailure(OpenClawFrameFailure.TOO_DEEP) {
            codec.decodeServerFrame("""{"type":"event","event":"a","payload":$nested}""")
        }
        val tooManyFields = (0..OpenClawProtocol.MAX_JSON_FIELDS_PER_OBJECT)
            .joinToString(",") { "\"f$it\":null" }
        assertFailure(OpenClawFrameFailure.TOO_MANY_FIELDS) {
            codec.decodeServerFrame("""{"type":"event","event":"a","payload":{$tooManyFields}}""")
        }
        val tooManyItems = List(OpenClawProtocol.MAX_JSON_ARRAY_ITEMS + 1) { "null" }.joinToString(",")
        assertFailure(OpenClawFrameFailure.TOO_MANY_ITEMS) {
            codec.decodeServerFrame("""{"type":"event","event":"a","payload":[$tooManyItems]}""")
        }
        val huge = "가".repeat(OpenClawProtocol.MAX_JSON_STRING_UTF8_BYTES / 3 + 1)
        assertFailure(OpenClawFrameFailure.STRING_TOO_LARGE) {
            codec.decodeServerFrame("""{"type":"event","event":"a","payload":"$huge"}""")
        }
    }

    @Test
    fun `request encoder is bounded and omits absent params`() {
        val encoded = codec.encodeRequest("r-1", "agent.wait", null, 1_024)
        val params = JsonObject().apply { addProperty("runId", "run-1") }

        assertEquals("""{"type":"req","id":"r-1","method":"agent.wait"}""", encoded)
        assertTrue(codec.encodeRequest("r-2", "agent.wait", params, 1_024).contains("runId"))
        assertFailure(OpenClawFrameFailure.TOO_LARGE) {
            codec.encodeRequest("r-3", "agent", params, 8)
        }
    }

    private fun assertFailure(expected: OpenClawFrameFailure, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull() as? OpenClawFrameException
        assertEquals(expected, failure?.failure)
    }
}
