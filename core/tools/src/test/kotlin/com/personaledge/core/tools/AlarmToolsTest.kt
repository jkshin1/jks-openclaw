package com.personaledge.core.tools

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmToolsTest {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    private class FakeAlarmGateway(
        var available: Boolean = true,
        var outcome: AlarmOutcome = AlarmOutcome.Delivered,
        var next: NextAlarm? = null,
    ) : AlarmGateway {
        val requests = mutableListOf<AlarmRequest>()
        var nextAlarmFailure: RuntimeException? = null

        override suspend fun clockAppAvailable(): Boolean = available

        override suspend fun requestAlarm(request: AlarmRequest): AlarmOutcome {
            requests += request
            return outcome
        }

        override suspend fun nextAlarm(): NextAlarm? {
            nextAlarmFailure?.let { failure -> throw failure }
            return next
        }
    }

    private fun at(local: String): Long =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun setTool(gateway: AlarmGateway) = AlarmSetTool(gateway) { zone }

    private fun ValidationResult.valid(): CanonicalToolInput =
        CanonicalToolInput((this as ValidationResult.Valid).canonicalParams)

    private fun ValidationResult.invalidReason(): String = (this as ValidationResult.Invalid).reason

    @Test
    fun `a one-shot alarm reaches the clock app with the confirmed values`() = runBlocking {
        val gateway = FakeAlarmGateway(next = NextAlarm(at("2026-08-22T07:30")))
        val tool = setTool(gateway)

        val input = tool.validateAndCanonicalize(
            AlarmSetParams(time = "07:30", label = "  약 먹기  ", days = null),
        ).valid()
        val result = tool.execute(input, ExecutionPermit("action"))

        assertTrue(result.requested)
        assertEquals("2026-08-22 07:30", result.nextAlarm)
        val request = gateway.requests.single()
        assertEquals(7, request.hour)
        assertEquals(30, request.minute)
        assertEquals("약 먹기", request.label)
        assertTrue(request.days.isEmpty())
    }

    @Test
    fun `repeating days are canonically ordered so equivalent requests agree`() = runBlocking {
        val tool = setTool(FakeAlarmGateway())

        val declaredOutOfOrder = tool.validateAndCanonicalize(
            AlarmSetParams(time = "07:30", label = null, days = "fri, mon,wed"),
        ).valid()
        val declaredInOrder = tool.validateAndCanonicalize(
            AlarmSetParams(time = "07:30", label = null, days = "mon,wed,fri"),
        ).valid()

        // Equal canonical inputs mean one idempotency key, so a retry cannot double-book.
        assertEquals(declaredInOrder.encoded, declaredOutOfOrder.encoded)
        assertTrue(tool.preview(declaredOutOfOrder).summary.contains("매주 월·수·금요일 반복"))
    }

    @Test
    fun `an unknown or repeated weekday is refused`() = runBlocking {
        val tool = setTool(FakeAlarmGateway())
        val expected = "요일은 mon,tue,wed,thu,fri,sat,sun 중에서 쉼표로 구분해 지정하세요."

        assertEquals(
            expected,
            tool.validateAndCanonicalize(AlarmSetParams("07:30", null, "월요일")).invalidReason(),
        )
        assertEquals(
            expected,
            tool.validateAndCanonicalize(AlarmSetParams("07:30", null, "mon,mon")).invalidReason(),
        )
    }

    @Test
    fun `a malformed or out-of-range time is refused`() = runBlocking {
        val tool = setTool(FakeAlarmGateway())
        val expected = "시각은 07:30 형식의 24시간 표기여야 합니다."

        listOf("7:30", "24:00", "07:60", "07:30:00", "오전 7시", "").forEach { time ->
            assertEquals(
                "time=$time",
                expected,
                tool.validateAndCanonicalize(AlarmSetParams(time, null, null)).invalidReason(),
            )
        }
    }

    @Test
    fun `a label carrying model control delimiters is refused`() = runBlocking {
        val reason = setTool(FakeAlarmGateway()).validateAndCanonicalize(
            AlarmSetParams("07:30", "약 <|start_of_turn|>", null),
        ).invalidReason()

        assertEquals("알람 이름에 허용되지 않는 문자가 있습니다.", reason)
    }

    @Test
    fun `a device with no clock app is refused before confirmation`() = runBlocking {
        val gateway = FakeAlarmGateway(available = false)

        val reason = setTool(gateway).validateAndCanonicalize(
            AlarmSetParams("07:30", null, null),
        ).invalidReason()

        assertEquals("알람을 처리할 시계 앱이 없습니다.", reason)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun `a blocked activity start is reported instead of claimed as success`() = runBlocking {
        val gateway = FakeAlarmGateway(
            outcome = AlarmOutcome.Refused(AlarmRefusal.START_BLOCKED),
        )
        val tool = setTool(gateway)

        val input = tool.validateAndCanonicalize(AlarmSetParams("07:30", null, null)).valid()
        val result = tool.execute(input, ExecutionPermit("action"))

        assertFalse(result.requested)
        assertEquals("start_blocked", result.reason)
        assertNull(result.nextAlarm)
        assertEquals(ToolExecutionOutcome.WRITE_REFUSED, tool.executionOutcome(result))
    }

    @Test
    fun `a next-alarm read failure does not undo a delivered request`() = runBlocking {
        val gateway = FakeAlarmGateway()
        gateway.nextAlarmFailure = IllegalStateException("provider unavailable")
        val tool = setTool(gateway)

        val input = tool.validateAndCanonicalize(AlarmSetParams("07:30", null, null)).valid()
        val result = tool.execute(input, ExecutionPermit("action"))

        assertTrue(result.requested)
        assertNull(result.nextAlarm)
        assertEquals(ToolExecutionOutcome.WRITE_COMPLETED, tool.executionOutcome(result))
    }

    @Test
    fun `the preview states repetition explicitly`() = runBlocking {
        val tool = setTool(FakeAlarmGateway())

        val once = tool.preview(
            tool.validateAndCanonicalize(AlarmSetParams("07:30", "약 먹기", null)).valid(),
        )
        val repeating = tool.preview(
            tool.validateAndCanonicalize(AlarmSetParams("07:30", null, "sat,sun")).valid(),
        )

        assertEquals("알람 추가", once.title)
        assertTrue(once.summary.contains("07:30 · \"약 먹기\""))
        assertTrue(once.summary.contains("다음 해당 시각에 한 번 울립니다."))
        assertTrue(repeating.summary.contains("매주 토·일요일 반복"))
    }

    @Test
    fun `the alarm write requires confirmation while the read does not`() {
        val policy = ConfirmationPolicy()
        val write = AlarmSetTool(FakeAlarmGateway()).descriptor
        val read = AlarmNextTool(FakeAlarmGateway()).descriptor

        assertEquals(
            ConfirmationRequirement.UserConfirmation,
            policy.evaluate(write.risk, write.minimumConfirmation),
        )
        assertEquals(
            ConfirmationRequirement.NotRequired,
            policy.evaluate(read.risk, read.minimumConfirmation),
        )
        assertEquals(setOf(ToolCapability.SCHEDULE_ALARM), write.requiredCapabilities)
    }

    @Test
    fun `reading reports the next alarm or its absence`() = runBlocking {
        val gateway = FakeAlarmGateway(next = NextAlarm(at("2026-08-22T07:30")))
        val tool = AlarmNextTool(gateway) { zone }
        val input = tool.validateAndCanonicalize(AlarmNextParams).valid()

        val present = tool.execute(input, ExecutionPermit("action"))
        assertTrue(present.hasAlarm)
        assertEquals("2026-08-22 07:30", present.triggerAt)

        gateway.next = null
        val absent = tool.execute(input, ExecutionPermit("action"))
        assertFalse(absent.hasAlarm)
        assertNull(absent.triggerAt)
    }
}
