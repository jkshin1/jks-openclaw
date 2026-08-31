package com.personaledge.agent

import com.personaledge.core.agent.ToolFailureDetail
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.ReminderCreateTool
import com.personaledge.core.tools.ToolFailureCode
import com.personaledge.core.tools.WebSearchTool
import com.personaledge.core.tools.WeatherTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFailurePresentationTest {
    @Test
    fun `maps quota code also tells the owner to check selected apis`() {
        assertEquals(
            "Maps Application의 Geocoding·Directions 5 선택 여부와 무료 제공량을 확인하세요.",
            toolFailureText(
                ToolFailureDetail(
                    toolName = RouteEstimateTool.NAME,
                    failureCode = ToolFailureCode.API_DISABLED_OR_QUOTA_EXCEEDED,
                ),
            ),
        )
    }

    @Test
    fun `maps and web search authentication point to their separate setup`() {
        val maps = toolFailureText(
            ToolFailureDetail(RouteEstimateTool.NAME, ToolFailureCode.AUTHENTICATION_FAILED),
        )
        val search = toolFailureText(
            ToolFailureDetail(WebSearchTool.NAME, ToolFailureCode.AUTHENTICATION_FAILED),
        )

        assertTrue(maps.contains("Maps Application"))
        assertFalse(maps.contains("Tavily"))
        assertTrue(search.contains("You.com"))
        assertTrue(search.contains("Tavily"))
        assertFalse(search.contains("네이버"))
    }

    @Test
    fun `every closed code has a static safe presentation`() {
        ToolFailureCode.values().forEach { code ->
            val text = toolFailureText(ToolFailureDetail(RouteEstimateTool.NAME, code))
            assertTrue(code.name, text.isNotBlank())
            assertFalse(code.name, text.contains(code.name))
            assertFalse(code.name, text.contains("https://"))
        }
    }

    @Test
    fun `weather place failure asks for a more specific Korean location`() {
        val text = toolFailureText(
            ToolFailureDetail(WeatherTool.NAME, ToolFailureCode.PLACE_NOT_FOUND),
        )

        assertTrue(text.contains("시·군"))
        assertFalse(text.contains("Open-Meteo"))
        assertFalse(text.contains("https://"))
    }

    @Test
    fun `reminder validation code has actionable Korean guidance`() {
        val text = toolFailureText(
            ToolFailureDetail(
                toolName = ReminderCreateTool.NAME,
                failureCode = ToolFailureCode.REMINDER_INVALID_DATE_TIME_FORMAT,
            ),
        )

        assertTrue(text.contains("날짜와 시각"))
        assertFalse(text.contains(ToolFailureCode.REMINDER_INVALID_DATE_TIME_FORMAT.name))
    }
}
