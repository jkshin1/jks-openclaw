package com.personaledge.agent

import com.personaledge.core.tools.AlarmSetTool
import com.personaledge.core.tools.CalendarCreateEventTool
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.CalendarUpdateEventTool
import com.personaledge.core.tools.ToolExecutionOutcome
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
                CalendarQueryTool.NAME,
                ToolExecutionOutcome.READ_COMPLETED,
            ).contains("읽었습니다"),
        )
    }
}
