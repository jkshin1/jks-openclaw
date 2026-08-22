package com.personaledge.agent

import com.personaledge.core.tools.UnresolvedActionCheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnresolvedActionWarningTest {
    @Test
    fun `zero unresolved actions has no banner`() {
        assertNull(UnresolvedActionWarning.text(UnresolvedActionCheck.Available(0)))
    }

    @Test
    fun `unresolved count produces a content-free no-retry warning`() {
        val warning = requireNotNull(
            UnresolvedActionWarning.text(UnresolvedActionCheck.Available(2)),
        )

        assertTrue(warning.contains("2건"))
        assertTrue(warning.contains("직접 확인"))
        assertTrue(warning.contains("다시 실행하지 마세요"))
        assertFalse(warning.contains("calendar_create_event"))
    }

    @Test
    fun `unavailable ledger state fails closed without claiming zero`() {
        val warning = requireNotNull(UnresolvedActionWarning.text(UnresolvedActionCheck.Unavailable))

        assertTrue(warning.contains("확인할 수 없습니다"))
        assertTrue(warning.contains("다시 실행하지 마세요"))
    }

    @Test
    fun `startup warning gate checks and emits at most once`() = runBlocking {
        val gate = UnresolvedActionWarningGate()
        var checks = 0

        val first = gate.load {
            checks++
            UnresolvedActionCheck.Available(1)
        }
        val second = gate.load {
            checks++
            UnresolvedActionCheck.Available(1)
        }

        assertTrue(first!!.contains("1건"))
        assertNull(second)
        assertEquals(1, checks)
    }
}
