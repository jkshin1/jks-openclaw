package com.personaledge.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThermalStatusMonitorInstrumentedTest {
    @Test
    fun platformStatusCanBeReadAndListenerCanBeClosedTwice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val monitor = ThermalStatusMonitor.create(context)

        try {
            val observation = monitor.refresh()
            assertNotEquals(DiagnosticThermalStatus.UNKNOWN, observation.status)
            assertEquals(ThermalTurnPolicy.directive(observation.status), observation.directive)
        } finally {
            monitor.close()
            monitor.close()
        }
    }
}
