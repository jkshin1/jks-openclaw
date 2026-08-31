package com.personaledge.agent

import com.personaledge.core.data.AgentSettings
import com.personaledge.core.data.SecretKeyName
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.WebSearchTool
import com.personaledge.core.tools.WeatherTool
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
        assertFalse(NetworkToolConsent.isEnabled(WeatherTool.NAME, defaults))
        assertFalse(NetworkToolConsent.isEnabled("future_network_tool", defaults))

        val routeOnly = defaults.copy(routeLookupEnabled = true)
        assertTrue(NetworkToolConsent.isEnabled(RouteEstimateTool.NAME, routeOnly))
        assertFalse(NetworkToolConsent.isEnabled(WebSearchTool.NAME, routeOnly))
        assertFalse(NetworkToolConsent.isEnabled(WeatherTool.NAME, routeOnly))

        val searchOnly = defaults.copy(webSearchEnabled = true)
        assertFalse(NetworkToolConsent.isEnabled(RouteEstimateTool.NAME, searchOnly))
        assertTrue(NetworkToolConsent.isEnabled(WebSearchTool.NAME, searchOnly))
        assertTrue(NetworkToolConsent.isEnabled(WeatherTool.NAME, searchOnly))
    }

    @Test
    fun `network consent intersects durable settings with the process gate`() {
        val settings = AgentSettings(routeLookupEnabled = true, webSearchEnabled = true)
        val interlock = OwnerConsentInterlock()
        assertTrue(NetworkToolConsent.isAllowed(RouteEstimateTool.NAME, settings, interlock))
        assertTrue(NetworkToolConsent.isAllowed(WebSearchTool.NAME, settings, interlock))
        assertTrue(NetworkToolConsent.isAllowed(WeatherTool.NAME, settings, interlock))

        interlock.requestEnabled(OwnerConsentFeature.WEB_SEARCH, enabled = false)

        assertTrue(NetworkToolConsent.isAllowed(RouteEstimateTool.NAME, settings, interlock))
        assertFalse(NetworkToolConsent.isAllowed(WebSearchTool.NAME, settings, interlock))
        assertFalse(NetworkToolConsent.isAllowed(WeatherTool.NAME, settings, interlock))
    }

    @Test
    fun `weather setup identifies device geocoding and Open-Meteo`() {
        val reason = NetworkToolConsent.disabledReason(WeatherTool.NAME)

        assertTrue(reason.contains("기기 위치 검색"))
        assertTrue(reason.contains("Open-Meteo"))
        assertFalse(reason.contains("You.com"))
    }

    @Test
    fun `web search setup identifies both outbound providers without provider tuning`() {
        val reason = NetworkToolConsent.disabledReason(WebSearchTool.NAME)
        val tavily = CredentialSlot.TAVILY_API_KEY

        assertTrue(reason.contains("You.com"))
        assertTrue(reason.contains("Tavily"))
        assertFalse(reason.contains("NAVER"))
        assertEquals(SecretKeyName.TAVILY_API_KEY, tavily.key)
        assertTrue(tavily.label.contains("Tavily"))
        assertTrue(tavily.label.contains("백업"))
        assertTrue(tavily.hint.contains("저장"))
        assertTrue(tavily.hint.contains("확인"))
        assertFalse(tavily.label.contains("재시도"))
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
