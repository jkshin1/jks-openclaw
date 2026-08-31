package com.personaledge.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnExecutionContractTest {
    @Test
    fun `exact ordered contract rejects substitution reorder omission and duplicate`() {
        val state = TurnExecutionContract.exactReads(
            listOf("calendar_query", "weather_current"),
        ).newState()

        assertFalse(state.reserve("alarm_next"))
        assertFalse(state.reserveAll(listOf("calendar_query")))
        assertFalse(state.reserveAll(listOf("weather_current", "calendar_query")))
        assertTrue(state.reserveAll(listOf("calendar_query", "weather_current")))
        assertFalse(state.reserve("calendar_query"))
        assertTrue(state.complete("calendar_query"))
        assertFalse(state.isSatisfied())
        assertTrue(state.complete("weather_current"))
        assertTrue(state.isSatisfied())
        assertFalse(state.complete("weather_current"))
    }

    @Test
    fun `duplicate expected tools preserve their count`() {
        val state = TurnExecutionContract.exactReads(
            listOf("weather_current", "weather_current"),
        ).newState()

        assertTrue(state.reserve("weather_current"))
        assertTrue(state.reserve("weather_current"))
        assertFalse(state.reserve("weather_current"))
        assertTrue(state.complete("weather_current"))
        assertFalse(state.isSatisfied())
        assertTrue(state.complete("weather_current"))
        assertTrue(state.isSatisfied())
    }

    @Test
    fun `descriptions reveal counts but not tool names`() {
        val contract = TurnExecutionContract.exactReads(listOf("secret_read_tool"))

        assertFalse(contract.toString().contains("secret_read_tool"))
        assertTrue(contract.toString().contains("expectedReadCount=1"))
    }
}
