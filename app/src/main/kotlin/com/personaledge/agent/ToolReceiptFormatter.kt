package com.personaledge.agent

import com.personaledge.core.tools.AlarmNextTool
import com.personaledge.core.tools.AlarmSetTool
import com.personaledge.core.tools.CalendarCreateEventTool
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.CalendarUpdateEventTool
import com.personaledge.core.tools.NotificationSearchTool
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.ToolExecutionOutcome
import com.personaledge.core.tools.WebSearchTool

/** App-authored, content-free receipts derived only from registry metadata and typed outcome. */
internal object ToolReceiptFormatter {
    fun text(toolName: String, outcome: ToolExecutionOutcome): String =
        if (outcome == ToolExecutionOutcome.WRITE_REFUSED) {
            when (toolName) {
                CalendarCreateEventTool.NAME ->
                    "캘린더가 일정 등록을 완료하지 않아 쓰기 없음으로 확정했습니다."
                CalendarUpdateEventTool.NAME ->
                    "캘린더가 일정 수정을 완료하지 않아 쓰기 없음으로 확정했습니다."
                AlarmSetTool.NAME ->
                    "시계 앱이 알람 요청을 받아들이지 않아 추가되지 않은 것으로 확정했습니다."
                else -> "Tool이 요청한 쓰기를 완료하지 않은 것으로 확정했습니다."
            }
        } else {
            when (toolName) {
                CalendarQueryTool.NAME -> "캘린더에서 일정을 읽었습니다."
                CalendarCreateEventTool.NAME -> "캘린더에 일정을 등록했습니다."
                CalendarUpdateEventTool.NAME -> "캘린더 일정을 수정했습니다."
                AlarmSetTool.NAME -> "시계 앱에 알람 추가를 요청했습니다."
                AlarmNextTool.NAME -> "다음 알람 시각을 확인했습니다."
                NotificationSearchTool.NAME -> "수집된 카카오톡 알림을 검색했습니다."
                RouteEstimateTool.NAME -> "네이버 지도에서 이동 시간을 조회했습니다."
                WebSearchTool.NAME -> "네이버에서 웹 검색을 했습니다."
                else -> "확인된 Tool을 실행했습니다."
            }
        }
}
