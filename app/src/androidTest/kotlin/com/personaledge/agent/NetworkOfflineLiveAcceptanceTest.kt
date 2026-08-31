package com.personaledge.agent

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.tools.InterlockDecision
import com.personaledge.core.tools.InterlockPhase
import com.personaledge.core.tools.InterlockRequest
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.ToolCapability
import com.personaledge.core.tools.ToolRisk
import com.personaledge.core.tools.WebSearchTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit physical-device proof that both external Tools fail closed while connectivity is off.
 *
 * The test invokes only the production execution interlock. It never prepares a Tool, opens a
 * confirmation, reads a credential, or calls a provider. Wi-Fi/mobile state is controlled and
 * restored by the host so this test itself has no authority to change connectivity.
 */
@RunWith(AndroidJUnit4::class)
class NetworkOfflineLiveAcceptanceTest {
    @Test
    fun consentedRouteAndSearchAreBlockedBeforeAnyGatewayWhileOffline() = runBlocking {
        assumeTrue(
            "Offline acceptance requires -e liveNetworkOffline true.",
            InstrumentationRegistry.getArguments().getString(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("Physical-device acceptance only.", !isEmulator())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as PersonalEdgeApplication
        val container = application.container
        val settings = container.settings.current()
        assumeTrue("Route consent must already be owner-enabled.", settings.routeLookupEnabled)
        assumeTrue("Web-search consent must already be owner-enabled.", settings.webSearchEnabled)

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.activeNetwork
            ?.let(connectivity::getNetworkCapabilities)
        val validatedInternet = capabilities?.let { current ->
            current.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                current.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
        assertFalse("The device still has validated internet connectivity.", validatedInternet)

        val interlock = DeviceExecutionInterlock(
            context = context,
            thermalStatus = { DiagnosticThermalStatus.NONE },
            pinnedCalendarId = container::pinnedCalendarId,
            calendarIsReadable = container::calendarIsReadable,
            alarmGateway = container.alarms,
            notificationGateway = container.notificationGateway,
            networkConsent = { toolName ->
                NetworkToolConsent.isEnabled(toolName, container.settings.current())
            },
            sideEffectingToolsEnabled = true,
        )

        val webDecision = interlock.evaluate(networkRequest(WebSearchTool.NAME))
        val routeDecision = interlock.evaluate(networkRequest(RouteEstimateTool.NAME))
        assertTrue(webDecision is InterlockDecision.Block)
        assertTrue(routeDecision is InterlockDecision.Block)
        assertEquals(OFFLINE_REASON, (webDecision as InterlockDecision.Block).reason)
        assertEquals(OFFLINE_REASON, (routeDecision as InterlockDecision.Block).reason)

        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("privacy_offline_web_blocked", "true")
                putString("privacy_offline_route_blocked", "true")
                putString("privacy_offline_gateway_calls", "0")
            },
        )
    }

    private fun networkRequest(toolName: String) = InterlockRequest(
        toolName = toolName,
        risk = ToolRisk.READ_ONLY,
        requiredCapabilities = setOf(ToolCapability.NETWORK),
        phase = InterlockPhase.PREPARE,
    )

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)

    private companion object {
        const val LIVE_ARGUMENT = "liveNetworkOffline"
        const val OFFLINE_REASON = "네트워크에 연결되어 있지 않습니다."
    }
}
