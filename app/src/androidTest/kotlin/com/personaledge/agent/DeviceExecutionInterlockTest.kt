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
import org.junit.Assume.assumeTrue
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
        assertTrue(
            (decision as InterlockDecision.Block).reason.contains(
                "읽기 범위의 캘린더 하나 이상을 사용할 수 없습니다",
            ),
        )
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

    @Test
    fun memoryWriteRequiresTheIndependentMemoryOptIn() = runBlocking {
        val blocked = interlock(
            pinnedCalendarId = null,
            readable = false,
            memoryEnabled = false,
        ).evaluate(MEMORY_WRITE_REQUEST)
        assertTrue(blocked is InterlockDecision.Block)
        assertTrue((blocked as InterlockDecision.Block).reason.contains("장기 기억"))

        val allowed = interlock(
            pinnedCalendarId = null,
            readable = false,
            memoryEnabled = true,
        ).evaluate(MEMORY_WRITE_REQUEST)
        assertEquals(InterlockDecision.Allow, allowed)
    }

    @Test
    fun KakaoShareRequiresAResolvableKakaoTalkActivity() = runBlocking {
        val blocked = interlock(
            pinnedCalendarId = null,
            readable = false,
            kakaoShareAvailable = false,
        ).evaluate(KAKAO_SHARE_REQUEST)
        assertTrue(blocked is InterlockDecision.Block)

        val allowed = interlock(
            pinnedCalendarId = null,
            readable = false,
            kakaoShareAvailable = true,
        ).evaluate(KAKAO_SHARE_REQUEST)
        assertEquals(InterlockDecision.Allow, allowed)
    }

    @Test
    fun candidateModelLabBlocksWritesBeforeProviderChecks() = runBlocking {
        val decision = interlock(
            pinnedCalendarId = 77,
            readable = true,
            memoryEnabled = true,
            sideEffectingToolsEnabled = false,
        ).evaluate(MEMORY_WRITE_REQUEST)

        assertTrue(decision is InterlockDecision.Block)
        assertTrue((decision as InterlockDecision.Block).reason.contains("읽기 전용"))
    }

    @Test
    fun qwenLabBuildConfigAllowsOnlyReadOnlyRiskAtBothInterlockPhases() = runBlocking {
        assumeTrue("qwen8bLab variant only", BuildConfig.CANDIDATE_MODEL_LAB)
        assertEquals(false, BuildConfig.SIDE_EFFECTING_TOOLS_ENABLED)
        val locked = interlock(
            pinnedCalendarId = null,
            readable = false,
            sideEffectingToolsEnabled = BuildConfig.SIDE_EFFECTING_TOOLS_ENABLED,
        )

        InterlockPhase.entries.forEach { phase ->
            assertEquals(
                InterlockDecision.Allow,
                locked.evaluate(
                    InterlockRequest(
                        toolName = "lab_read",
                        risk = ToolRisk.READ_ONLY,
                        requiredCapabilities = emptySet(),
                        phase = phase,
                    ),
                ),
            )
            ToolRisk.entries.filterNot { it == ToolRisk.READ_ONLY }.forEach { risk ->
                assertTrue(
                    locked.evaluate(
                        InterlockRequest(
                            toolName = "lab_blocked",
                            risk = risk,
                            requiredCapabilities = emptySet(),
                            phase = phase,
                        ),
                    ) is InterlockDecision.Block,
                )
            }
        }
    }

    private fun interlock(
        pinnedCalendarId: Long?,
        readable: Boolean,
        memoryEnabled: Boolean = false,
        kakaoShareAvailable: Boolean = false,
        sideEffectingToolsEnabled: Boolean = true,
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
        memoryConsent = { memoryEnabled },
        kakaoShareAvailable = { kakaoShareAvailable },
        sideEffectingToolsEnabled = sideEffectingToolsEnabled,
    )

    private companion object {
        val READ_REQUEST = InterlockRequest(
            toolName = "calendar_query",
            risk = ToolRisk.READ_ONLY,
            requiredCapabilities = setOf(ToolCapability.READ_CALENDAR),
            phase = InterlockPhase.EXECUTE,
        )
        val MEMORY_WRITE_REQUEST = InterlockRequest(
            toolName = "memory_remember",
            risk = ToolRisk.LOCAL_WRITE,
            requiredCapabilities = setOf(ToolCapability.WRITE_MEMORY),
            phase = InterlockPhase.EXECUTE,
        )
        val KAKAO_SHARE_REQUEST = InterlockRequest(
            toolName = "kakao_share_message",
            risk = ToolRisk.COMMUNICATION,
            requiredCapabilities = setOf(ToolCapability.OPEN_KAKAO_SHARE),
            phase = InterlockPhase.EXECUTE,
        )
    }
}
