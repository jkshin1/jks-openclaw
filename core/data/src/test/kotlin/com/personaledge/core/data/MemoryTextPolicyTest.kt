package com.personaledge.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryTextPolicyTest {
    @Test
    fun `unlabelled and segmented high entropy credentials are rejected`() {
        assertNull(MemoryTextPolicy.sanitize("라벨 없는 값 aB7mQ2xP9vL4sN8kR5tY3uW6"))
        assertNull(MemoryTextPolicy.sanitize("나뉜 값 aB7m-Q2xP-9vL4-sN8k-R5tY-3uW6"))
    }

    @Test
    fun `ordinary durable Korean memory is retained`() {
        assertEquals(
            "사용자는 주말에 긴 산책을 선호한다.",
            MemoryTextPolicy.sanitize("  사용자는 주말에 긴 산책을 선호한다.  "),
        )
    }
}
