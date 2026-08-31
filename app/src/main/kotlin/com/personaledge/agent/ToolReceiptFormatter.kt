package com.personaledge.agent

import com.personaledge.core.tools.AlarmNextTool
import com.personaledge.core.tools.AlarmSetTool
import com.personaledge.core.tools.CalendarCreateEventTool
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.CalendarUpdateEventTool
import com.personaledge.core.tools.CommitmentProposalTool
import com.personaledge.core.tools.NotificationSearchTool
import com.personaledge.core.tools.KakaoNotificationReplyTool
import com.personaledge.core.tools.KakaoShareMessageTool
import com.personaledge.core.tools.MemoryRememberTool
import com.personaledge.core.tools.ReminderCancelTool
import com.personaledge.core.tools.ReminderCreateTool
import com.personaledge.core.tools.ReminderQueryTool
import com.personaledge.core.tools.ReminderUpdateTool
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.ToolExecutionOutcome
import com.personaledge.core.tools.WebSearchTool
import com.personaledge.core.tools.WeatherTool

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
                MemoryRememberTool.NAME ->
                    "장기 기억 저장소가 요청한 내용을 저장하지 않은 것으로 확정했습니다."
                CommitmentProposalTool.NAME ->
                    "일정 후보 제안함이 요청한 후보를 저장하지 않은 것으로 확정했습니다."
                ReminderCreateTool.NAME ->
                    "로컬 리마인더를 만들지 않은 것으로 확정했습니다."
                ReminderUpdateTool.NAME ->
                    "로컬 리마인더를 변경하지 않은 것으로 확정했습니다."
                ReminderCancelTool.NAME ->
                    "로컬 리마인더를 취소하지 않은 것으로 확정했습니다."
                KakaoShareMessageTool.NAME ->
                    "카카오톡 공유 화면을 열지 못해 전송되지 않은 것으로 확정했습니다."
                KakaoNotificationReplyTool.NAME ->
                    "카카오톡 알림 답장을 요청하지 못한 것으로 확정했습니다."
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
                WebSearchTool.NAME -> "웹 검색을 완료했습니다."
                WeatherTool.NAME -> "현재 및 오늘 날씨를 확인했습니다."
                MemoryRememberTool.NAME -> "승인한 내용을 장기 기억에 저장했습니다."
                CommitmentProposalTool.NAME ->
                    "일정 후보 제안함에 저장했습니다. 아직 예약된 알림은 없습니다."
                ReminderCreateTool.NAME -> "로컬 리마인더를 만들었습니다."
                ReminderUpdateTool.NAME -> "로컬 리마인더를 변경했습니다."
                ReminderCancelTool.NAME -> "로컬 리마인더를 취소했습니다."
                ReminderQueryTool.NAME -> "로컬 리마인더를 조회했습니다."
                KakaoShareMessageTool.NAME ->
                    "카카오톡 공유 화면을 열었습니다. 실제 전송은 카카오톡에서 확인하세요."
                KakaoNotificationReplyTool.NAME ->
                    "카카오톡 알림에 답장을 요청했습니다. 실제 전송 결과는 카카오톡에서 확인하세요."
                else -> "확인된 Tool을 실행했습니다."
            }
        }
}
