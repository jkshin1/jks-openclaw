package com.personaledge.agent

import com.personaledge.core.tools.AlarmSetTool
import com.personaledge.core.tools.CalendarCreateEventTool
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.CalendarUpdateEventTool
import com.personaledge.core.tools.CommitmentProposalTool
import com.personaledge.core.tools.KakaoNotificationReplyTool
import com.personaledge.core.tools.KakaoShareMessageTool
import com.personaledge.core.tools.ToolExecutionOutcome
import com.personaledge.core.tools.MemoryRememberTool
import com.personaledge.core.tools.ReminderQueryTool
import com.personaledge.core.tools.WeatherTool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReceiptFormatterTest {
    @Test
    fun `normal provider refusal never becomes a success receipt`() {
        listOf(
            CalendarCreateEventTool.NAME to "등록했습니다",
            CalendarUpdateEventTool.NAME to "수정했습니다",
            AlarmSetTool.NAME to "요청했습니다",
        ).forEach { (toolName, forbiddenSuccess) ->
            val receipt = ToolReceiptFormatter.text(
                toolName = toolName,
                outcome = ToolExecutionOutcome.WRITE_REFUSED,
            )
            assertFalse(receipt, receipt.contains(forbiddenSuccess))
            assertTrue(receipt, receipt.contains("않"))
        }
    }

    @Test
    fun `typed completed results retain the expected trusted receipt`() {
        assertTrue(
            ToolReceiptFormatter.text(
                CalendarCreateEventTool.NAME,
                ToolExecutionOutcome.WRITE_COMPLETED,
            ).contains("등록했습니다"),
        )
        assertTrue(
            ToolReceiptFormatter.text(
                CommitmentProposalTool.NAME,
                ToolExecutionOutcome.WRITE_COMPLETED,
            ).contains("예약된 알림은 없습니다"),
        )
        assertTrue(
            ToolReceiptFormatter.text(
                MemoryRememberTool.NAME,
                ToolExecutionOutcome.WRITE_COMPLETED,
            ).contains("장기 기억"),
        )
        assertTrue(
            ToolReceiptFormatter.text(
                CalendarQueryTool.NAME,
                ToolExecutionOutcome.READ_COMPLETED,
            ).contains("읽었습니다"),
        )
        val share = ToolReceiptFormatter.text(
            KakaoShareMessageTool.NAME,
            ToolExecutionOutcome.WRITE_COMPLETED,
        )
        assertTrue(share.contains("공유 화면을 열었습니다"))
        assertTrue(share.contains("실제 전송은 카카오톡에서 확인"))

        val reply = ToolReceiptFormatter.text(
            KakaoNotificationReplyTool.NAME,
            ToolExecutionOutcome.WRITE_COMPLETED,
        )
        assertTrue(reply.contains("답장을 요청했습니다"))
        assertTrue(reply.contains("실제 전송 결과"))

        val weather = ToolReceiptFormatter.text(
            WeatherTool.NAME,
            ToolExecutionOutcome.READ_COMPLETED,
        )
        assertTrue(weather.contains("현재 및 오늘 날씨"))

        val reminders = ToolReceiptFormatter.text(
            ReminderQueryTool.NAME,
            ToolExecutionOutcome.READ_COMPLETED,
        )
        assertTrue(reminders.contains("리마인더를 조회"))
    }
}
