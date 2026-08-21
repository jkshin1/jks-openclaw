package com.personaledge.core.agent

import com.personaledge.core.tools.FakeArrivalNoticeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictToolArgumentsTest {
    private val parser = FakeArrivalNoticeArgumentsParser(maxArgumentBytes = 256)

    @Test
    fun `valid flat object decodes strings and unicode escapes`() {
        val result = parser.parse(
            """ {"recipient":"\uC544\uB0B4","message":"30\uBD84 \"뒤\" 도착"} """,
        ) as ToolArgumentsParseResult.Valid

        assertEquals("아내", result.params.recipient)
        assertEquals("30분 \"뒤\" 도착", result.params.message)
    }

    @Test
    fun `field order does not matter`() {
        val result = parser.parse("""{"message":"soon","recipient":"wife"}""")

        assertTrue(result is ToolArgumentsParseResult.Valid)
    }

    @Test
    fun `unknown extra and escaped duplicate fields are rejected`() {
        assertInvalid(
            """{"recipient":"wife","message":"soon","urgent":"true"}""",
            ToolArgumentsError.UNKNOWN_FIELD,
        )
        assertInvalid(
            """{"recipient":"wife","message":"soon","\u0072ecipient":"other"}""",
            ToolArgumentsError.DUPLICATE_FIELD,
        )
    }

    @Test
    fun `missing and wrong typed fields are rejected`() {
        assertInvalid("""{"recipient":"wife"}""", ToolArgumentsError.MISSING_FIELD)
        assertInvalid(
            """{"recipient":"wife","message":30}""",
            ToolArgumentsError.WRONG_TYPE,
        )
        assertInvalid(
            """{"recipient":null,"message":"soon"}""",
            ToolArgumentsError.WRONG_TYPE,
        )
    }

    @Test
    fun `nested objects and arrays are rejected`() {
        assertInvalid(
            """{"recipient":{"name":"wife"},"message":"soon"}""",
            ToolArgumentsError.NESTING_NOT_ALLOWED,
        )
        assertInvalid(
            """{"recipient":"wife","message":["soon"]}""",
            ToolArgumentsError.NESTING_NOT_ALLOWED,
        )
    }

    @Test
    fun `bidi and invisible presentation controls are rejected`() {
        assertInvalid(
            """{"recipient":"wife","message":"safe\u202Etxt"}""",
            ToolArgumentsError.UNSAFE_TEXT,
        )
        assertInvalid(
            """{"recipient":"wi\u200Bfe","message":"soon"}""",
            ToolArgumentsError.UNSAFE_TEXT,
        )
        assertInvalid(
            """{"recipient":"wife","message":"first\u2028second"}""",
            ToolArgumentsError.UNSAFE_TEXT,
        )
    }

    @Test
    fun `pinned model control delimiters are rejected directly and after unicode decoding`() {
        assertInvalid(
            """{"recipient":"wife","message":"<|tool_response|>"}""",
            ToolArgumentsError.UNSAFE_TEXT,
        )
        assertInvalid(
            """{"recipient":"wife","message":"\u003C\u007Ctool_response\u007C\u003E"}""",
            ToolArgumentsError.UNSAFE_TEXT,
        )
        assertInvalid(
            """{"recipient":"wife|>","message":"soon"}""",
            ToolArgumentsError.UNSAFE_TEXT,
        )
    }

    @Test
    fun `oversized utf8 and malformed documents are rejected`() {
        assertInvalid(
            """{"recipient":"wife","message":"${"가".repeat(100)}"}""",
            ToolArgumentsError.OVERSIZED,
        )
        assertInvalid(
            """{"recipient":"wife","message":"soon"} trailing""",
            ToolArgumentsError.MALFORMED_JSON,
        )
        assertInvalid(
            """{"recipient":"wife","message":"\uD800"}""",
            ToolArgumentsError.MALFORMED_JSON,
        )
    }

    @Test
    fun `trusted result encoder does not echo model controlled arguments`() {
        val encoded = TrustedToolResultJson.encode(FakeArrivalNoticeResult())

        assertEquals("""{"simulated":true}""", encoded)
    }

    private fun assertInvalid(json: String, expected: ToolArgumentsError) {
        val result = parser.parse(json) as ToolArgumentsParseResult.Invalid
        assertEquals(expected, result.error)
    }
}
