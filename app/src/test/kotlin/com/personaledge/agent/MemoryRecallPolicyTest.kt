package com.personaledge.agent

import com.personaledge.core.data.MemoryCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallPolicyTest {
    @Test
    fun `route shaped requests can recall only place memories`() {
        assertEquals(
            setOf(MemoryCategory.PLACE),
            MemoryRecallPolicy.categoriesFor("내일 병원까지 얼마나 걸려?"),
        )
    }

    @Test
    fun `ordinary requests retain all memory categories`() {
        assertTrue(
            MemoryRecallPolicy.categoriesFor("내가 좋아하는 색은 뭐야?")
                .containsAll(MemoryCategory.entries),
        )
    }
}
