package com.personaledge.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UnicodeTextTest {
    @Test
    fun `code point truncation never splits a supplementary character`() {
        val truncated = "가😀나".takeCodePoints(2)

        assertEquals("가😀", truncated)
        assertFalse(truncated.last().isHighSurrogate())
    }

    @Test
    fun `utf8 truncation keeps the longest complete prefix`() {
        assertEquals("가😀", "가😀나".takeUtf8Bytes(7))
        assertEquals("가", "가😀나".takeUtf8Bytes(6))
        assertEquals("", "😀".takeUtf8Bytes(3))
    }
}
