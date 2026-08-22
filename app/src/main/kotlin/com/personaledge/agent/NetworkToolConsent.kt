package com.personaledge.agent

import com.personaledge.core.data.AgentSettings
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.WebSearchTool

/** Persistent, fail-closed opt-in checked at both orchestrator interlock phases. */
internal object NetworkToolConsent {
    fun isEnabled(toolName: String, settings: AgentSettings): Boolean = when (toolName) {
        RouteEstimateTool.NAME -> settings.routeLookupEnabled
        WebSearchTool.NAME -> settings.webSearchEnabled
        else -> false
    }

    fun disabledReason(toolName: String): String = when (toolName) {
        RouteEstimateTool.NAME -> "설정에서 네이버 경로 조회 외부 전송 동의를 먼저 켜 주세요."
        WebSearchTool.NAME -> "설정에서 네이버 웹 검색 외부 전송 동의를 먼저 켜 주세요."
        else -> "이 네트워크 기능의 외부 전송 동의가 구성되지 않았습니다."
    }
}
