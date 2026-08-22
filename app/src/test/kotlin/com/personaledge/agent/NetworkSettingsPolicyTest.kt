package com.personaledge.agent

import com.personaledge.core.data.AgentSettings
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.WebSearchTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSettingsPolicyTest {
    @Test
    fun `network use requires internet validation and a non-suspended link`() {
        assertEquals(
            NetworkReachability.USABLE,
            NetworkReachabilityPolicy.evaluate(
                hasInternet = true,
                isValidated = true,
                isNotSuspended = true,
            ),
        )
        assertEquals(
            NetworkReachability.UNAVAILABLE,
            NetworkReachabilityPolicy.evaluate(false, isValidated = true, isNotSuspended = true),
        )
        assertEquals(
            NetworkReachability.UNVALIDATED,
            NetworkReachabilityPolicy.evaluate(true, isValidated = false, isNotSuspended = true),
        )
        assertEquals(
            NetworkReachability.SUSPENDED,
            NetworkReachabilityPolicy.evaluate(true, isValidated = true, isNotSuspended = false),
        )
    }

    @Test
    fun `network tools are disabled by default and opt in independently`() {
        val defaults = AgentSettings()
        assertFalse(NetworkToolConsent.isEnabled(RouteEstimateTool.NAME, defaults))
        assertFalse(NetworkToolConsent.isEnabled(WebSearchTool.NAME, defaults))
        assertFalse(NetworkToolConsent.isEnabled("future_network_tool", defaults))

        val routeOnly = defaults.copy(routeLookupEnabled = true)
        assertTrue(NetworkToolConsent.isEnabled(RouteEstimateTool.NAME, routeOnly))
        assertFalse(NetworkToolConsent.isEnabled(WebSearchTool.NAME, routeOnly))

        val searchOnly = defaults.copy(webSearchEnabled = true)
        assertFalse(NetworkToolConsent.isEnabled(RouteEstimateTool.NAME, searchOnly))
        assertTrue(NetworkToolConsent.isEnabled(WebSearchTool.NAME, searchOnly))
    }

    @Test
    fun `default origin rejects model delimiters bidi and overlong values`() {
        assertTrue(SettingsTextPolicy.validateDefaultOrigin("서울시청") is SettingsTextValidation.Valid)
        assertTrue(
            SettingsTextPolicy.validateDefaultOrigin("<|tool|>") is SettingsTextValidation.Invalid,
        )
        assertTrue(
            SettingsTextPolicy.validateDefaultOrigin("서울\u202E시청") is SettingsTextValidation.Invalid,
        )
        assertTrue(
            SettingsTextPolicy.validateDefaultOrigin("가".repeat(81)) is SettingsTextValidation.Invalid,
        )
    }

    @Test
    fun `provider label sanitizer removes spoofing controls and caps by code point`() {
        val safe = SettingsTextPolicy.sanitizeProviderLabel("업무\u202E<|tool|>\n")
        assertFalse(safe.contains("\u202E"))
        assertFalse(safe.contains("<|"))
        assertFalse(safe.contains("|>"))
        assertEquals(120, SettingsTextPolicy.sanitizeProviderLabel("😀".repeat(200)).codePointCount(0, 240))
    }
}
