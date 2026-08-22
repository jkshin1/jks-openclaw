package com.personaledge.agent

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.tools.InterlockDecision
import com.personaledge.core.tools.InterlockPhase
import com.personaledge.core.tools.InterlockRequest
import com.personaledge.core.tools.ToolCapability
import com.personaledge.core.tools.ToolRisk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceExecutionInterlockTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication

    @Before
    fun grantReadCalendarPermission() {
        instrumentation.uiAutomation.grantRuntimePermission(
            application.packageName,
            Manifest.permission.READ_CALENDAR,
        )
    }

    @Test
    fun calendarReadWithoutAPinIsBlockedInsteadOfLookingEmpty() = runBlocking {
        val decision = interlock(pinnedCalendarId = null, readable = true).evaluate(READ_REQUEST)

        assertTrue(decision is InterlockDecision.Block)
        assertTrue((decision as InterlockDecision.Block).reason.contains("먼저 선택"))
    }

    @Test
    fun removedPinnedCalendarIsBlockedAtExecutionTime() = runBlocking {
        val decision = interlock(pinnedCalendarId = 77, readable = false).evaluate(READ_REQUEST)

        assertTrue(decision is InterlockDecision.Block)
        assertTrue((decision as InterlockDecision.Block).reason.contains("더 이상 읽을 수 없습니다"))
    }

    @Test
    fun existingReadablePinnedCalendarAllowsTheRead() = runBlocking {
        var checkedCalendarId: Long? = null
        val decision = interlock(
            pinnedCalendarId = 77,
            readable = true,
            onReadableCheck = { checkedCalendarId = it },
        ).evaluate(READ_REQUEST)

        assertEquals(77L, checkedCalendarId)
        assertEquals(InterlockDecision.Allow, decision)
    }

    private fun interlock(
        pinnedCalendarId: Long?,
        readable: Boolean,
        onReadableCheck: (Long) -> Unit = {},
    ): DeviceExecutionInterlock = DeviceExecutionInterlock(
        context = application,
        thermalStatus = { DiagnosticThermalStatus.NONE },
        pinnedCalendarId = { pinnedCalendarId },
        calendarIsReadable = { calendarId ->
            onReadableCheck(calendarId)
            readable
        },
        alarmGateway = application.container.alarms,
        notificationGateway = application.container.notificationGateway,
        networkConsent = { false },
    )

    private companion object {
        val READ_REQUEST = InterlockRequest(
            toolName = "calendar_query",
            risk = ToolRisk.READ_ONLY,
            requiredCapabilities = setOf(ToolCapability.READ_CALENDAR),
            phase = InterlockPhase.EXECUTE,
        )
    }
}
