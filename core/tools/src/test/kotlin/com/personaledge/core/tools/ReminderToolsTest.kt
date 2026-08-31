package com.personaledge.core.tools

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderToolsTest {
    private val now = LocalDateTime.parse("2026-08-23T09:00")
        .atZone(ZoneId.of("Asia/Seoul"))
        .toInstant()
        .toEpochMilli()

    private class Gateway : ReminderGateway {
        var created: ReminderWriteRequest? = null

        override suspend fun create(request: ReminderWriteRequest): ReminderMutationResult {
            created = request
            return ReminderMutationResult(ReminderMutationOutcome.SAVED, "reminder-1", 1)
        }

        override suspend fun update(
            reminderId: String,
            expectedVersion: Long,
            request: ReminderWriteRequest,
        ) = ReminderMutationResult(ReminderMutationOutcome.SAVED, reminderId, expectedVersion + 1)

        override suspend fun cancel(reminderId: String, expectedVersion: Long) =
            ReminderMutationResult(ReminderMutationOutcome.SAVED, reminderId, expectedVersion + 1)

        override suspend fun upcoming(limit: Int): List<ReminderSummary> = emptyList()
    }

    @Test
    fun `absolute tomorrow reminder preserves date zone and one shot meaning`() = runBlocking {
        val gateway = Gateway()
        val tool = ReminderCreateTool(gateway, clock = { now })
        val validation = tool.validateAndCanonicalize(
            ReminderCreateParams(
                title = "병원 가기",
                triggerAt = "2026-08-24T07:00",
                zoneId = "Asia/Seoul",
                recurrenceRule = null,
                precision = "exact",
                leadTimeMinutes = null,
                escalationPolicy = "once",
            ),
        ) as ValidationResult.Valid
        val input = CanonicalToolInput(validation.canonicalParams)

        val result = tool.execute(input, ExecutionPermit("action"))

        assertEquals(ReminderMutationOutcome.SAVED, result.outcome)
        assertEquals("Asia/Seoul", gateway.created?.zoneId)
        assertEquals(null, gateway.created?.recurrenceRule)
        assertEquals(ReminderToolPrecision.EXACT, gateway.created?.precision)
        assertEquals(
            LocalDateTime.parse("2026-08-24T07:00")
                .atZone(ZoneId.of("Asia/Seoul"))
                .toInstant()
                .toEpochMilli(),
            gateway.created?.triggerAtEpochMillis,
        )
        assertTrue(tool.preview(input).summary.contains("2026-08-24 07:00"))
        assertTrue(tool.descriptor.requiredCapabilities.isEmpty())
    }

    @Test
    fun `reminder writes do not confuse durable storage with notification delivery permission`() {
        assertTrue(ReminderCreateTool(Gateway()).descriptor.requiredCapabilities.isEmpty())
        assertTrue(ReminderUpdateTool(Gateway()).descriptor.requiredCapabilities.isEmpty())
        assertEquals(
            ConfirmationRequirement.UserConfirmation,
            ReminderCreateTool(Gateway()).descriptor.minimumConfirmation,
        )
        assertEquals(ToolRisk.LOCAL_WRITE, ReminderCreateTool(Gateway()).descriptor.risk)
    }

    @Test
    fun `DST gap and overlap both fail closed`() = runBlocking {
        val tool = ReminderCreateTool(Gateway(), clock = { 0 })
        listOf("2026-03-08T02:30", "2026-11-01T01:30").forEach { local ->
            val result = tool.validateAndCanonicalize(
                ReminderCreateParams(
                    title = "DST test",
                    triggerAt = local,
                    zoneId = "America/New_York",
                    recurrenceRule = null,
                    precision = null,
                    leadTimeMinutes = null,
                    escalationPolicy = null,
                ),
            )
            assertTrue(result is ValidationResult.Invalid)
        }
    }

    @Test
    fun `weekly days are canonicalized and duplicate days rejected`() = runBlocking {
        val tool = ReminderCreateTool(Gateway(), clock = { now })
        val valid = tool.validateAndCanonicalize(
            ReminderCreateParams(
                "운동",
                "2026-08-24T19:00",
                "Asia/Seoul",
                "weekly:fri,mon",
                null,
                null,
                null,
            ),
        ) as ValidationResult.Valid
        assertTrue(tool.preview(CanonicalToolInput(valid.canonicalParams)).summary.contains("weekly:mon,fri"))

        assertTrue(
            tool.validateAndCanonicalize(
                ReminderCreateParams(
                    "운동",
                    "2026-08-24T19:00",
                    "Asia/Seoul",
                    "weekly:mon,mon",
                    null,
                    null,
                    null,
                ),
            ) is ValidationResult.Invalid,
        )
    }

    @Test
    fun `every rejected reminder field carries a closed diagnostic code`() = runBlocking {
        val create = ReminderCreateTool(Gateway(), clock = { now })
        val valid = ReminderCreateParams(
            title = "운동",
            triggerAt = "2026-08-24T19:00",
            zoneId = "Asia/Seoul",
            recurrenceRule = null,
            precision = null,
            leadTimeMinutes = null,
            escalationPolicy = null,
        )
        val cases = listOf(
            valid.copy(title = "") to ToolFailureCode.REMINDER_INVALID_TITLE,
            valid.copy(zoneId = "Not/A_Zone") to ToolFailureCode.REMINDER_INVALID_TIME_ZONE,
            valid.copy(triggerAt = "2026-08-24T19:00:30") to
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_ZERO_SECONDS,
            valid.copy(recurrenceRule = "hourly") to
                ToolFailureCode.REMINDER_INVALID_RECURRENCE,
            valid.copy(precision = "strict") to ToolFailureCode.REMINDER_INVALID_PRECISION,
            valid.copy(leadTimeMinutes = "10081") to
                ToolFailureCode.REMINDER_INVALID_LEAD_TIME,
            valid.copy(escalationPolicy = "forever") to
                ToolFailureCode.REMINDER_INVALID_ESCALATION,
        )

        cases.forEach { (params, expectedCode) ->
            val rejected = create.validateAndCanonicalize(params) as ValidationResult.Invalid
            assertEquals(expectedCode, rejected.failureCode)
        }

        val updateRejected = ReminderUpdateTool(Gateway(), clock = { now })
            .validateAndCanonicalize(
                ReminderUpdateParams(
                    reminderId = "invalid id",
                    expectedVersion = "1",
                    title = valid.title,
                    triggerAt = valid.triggerAt,
                    zoneId = valid.zoneId,
                    recurrenceRule = valid.recurrenceRule,
                    precision = valid.precision,
                    leadTimeMinutes = valid.leadTimeMinutes,
                    escalationPolicy = valid.escalationPolicy,
                ),
            ) as ValidationResult.Invalid
        assertEquals(ToolFailureCode.REMINDER_INVALID_IDENTITY, updateRejected.failureCode)

        val cancelRejected = ReminderCancelTool(Gateway()).validateAndCanonicalize(
            ReminderCancelParams(reminderId = "valid-id", expectedVersion = "0"),
        ) as ValidationResult.Invalid
        assertEquals(ToolFailureCode.REMINDER_INVALID_IDENTITY, cancelRejected.failureCode)

        val queryRejected = ReminderQueryTool(Gateway()).validateAndCanonicalize(
            ReminderQueryParams(limit = "101"),
        ) as ValidationResult.Invalid
        assertEquals(ToolFailureCode.REMINDER_INVALID_QUERY_LIMIT, queryRejected.failureCode)
    }

    @Test
    fun `zero ISO seconds are safely canonicalized to minute precision`() = runBlocking {
        val tool = ReminderCreateTool(Gateway(), clock = { now })

        listOf(
            "2026-08-24T19:00:00",
            "2026-08-24T19:00:00.000",
            "2026-08-24 19:00",
            "2026-08-24T19:00+09:00",
            "2026-08-24 19:00:00+09:00",
            "2026-08-24T19:00 Asia/Seoul",
            "2026-08-24T19:00+09:00[Asia/Seoul]",
            "2026-08-24 오후 7:00",
            "2026년 8월 24일 오후 7시",
            "2026년 8월 24일 오후 7시 0분",
            "2026년 8월 24일 오후 7:00",
            "2026년 8월 24일 19시",
            "2026년 8월 24일 19:00",
            "2026/8/24 19:00",
            "2026-8-24T9:00",
            "2026-08-24T 19:00",
            "2026-08-24T19 : 00",
            "2026-08-24T 19 : 000",
            "2026-08-24 7:00 PM",
            "2026-08-24 19:00 KST",
            "2026-08-24 at 19:00",
            "2026-08-24T19:00 (Asia/Seoul)",
            "2026.08.24 19:00",
            "２０２６－０８－２４ １９：００",
            "2026-08-24t19:0",
            "2026-08-24T19:00:00.000000000000",
            "2026-08-24T19:00:00,000",
            "2026-08-24T19:00.000",
            "2026-08-24T19:00,000",
            "2026-08-24T19:00.",
            "2026-08-24T19:00,",
            "2026-08-24T19:00;",
            "2026-08-24T19",
            "2026-08-24T19:000",
            "2026-08-24T19:000000",
            "2026-08-24T19:000 local",
            "2026-08-24 19:00 local time",
            "2026-08-24 19:00 local wall time",
            "2026-08-24 19:00 (local)",
            "2026-08-24 19:00 in local time",
            LocalDateTime.parse("2026-08-24T19:00")
                .atZone(ZoneId.of("Asia/Seoul"))
                .toInstant()
                .toEpochMilli()
                .toString(),
        ).forEach { trigger ->
            val validation = tool.validateAndCanonicalize(
                ReminderCreateParams(
                    title = "운동",
                    triggerAt = trigger,
                    zoneId = "Asia/Seoul",
                    recurrenceRule = null,
                    precision = null,
                    leadTimeMinutes = null,
                    escalationPolicy = null,
                ),
            )
            assertTrue(trigger, validation is ValidationResult.Valid)
        }

        listOf(
            "2026-08-24T19:00:30",
            "2026-08-24T19:00.001",
            "2026-08-24T19:001",
            "2026-08-24T19:00Z",
            "2026-08-24T19:00+08:00",
        ).forEach { trigger ->
            val validation = tool.validateAndCanonicalize(
                ReminderCreateParams(
                    title = "운동",
                    triggerAt = trigger,
                    zoneId = "Asia/Seoul",
                    recurrenceRule = null,
                    precision = null,
                    leadTimeMinutes = null,
                    escalationPolicy = null,
                ),
            ) as ValidationResult.Invalid
            val expected = if (trigger.endsWith("30")) {
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_NON_ZERO_SECONDS
            } else if (trigger.endsWith(":001")) {
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_EXTRA_DIGITS
            } else if (trigger.endsWith("001")) {
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_OTHER
            } else {
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_OFFSET_CONFLICT
            }
            assertEquals(expected, validation.failureCode)
        }
    }

    @Test
    fun `invalid ASCII ISO values expose only closed structural categories`() = runBlocking {
        val tool = ReminderCreateTool(Gateway(), clock = { now })
        val cases = mapOf(
            "2026-08-24T1900" to
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_NO_COLON,
            "2026-08-24T19:00)" to
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_SUFFIX_SYMBOLS,
            "2026-08-24T19:001" to
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_EXTRA_DIGITS,
            "2026-08-24T_19:00" to
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ONE_COLON_OTHER,
            "2026-8-24T19:00:00)" to
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_MULTI_COLON,
            "2026-08-24T19h00" to
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_ISO_ALPHA,
            "2026-08-24 19:00 local-clock" to
                ToolFailureCode.REMINDER_INVALID_DATE_TIME_ASCII_TEXT,
        )

        cases.forEach { (trigger, expectedCode) ->
            val validation = tool.validateAndCanonicalize(
                ReminderCreateParams(
                    title = "운동",
                    triggerAt = trigger,
                    zoneId = "Asia/Seoul",
                    recurrenceRule = null,
                    precision = null,
                    leadTimeMinutes = null,
                    escalationPolicy = null,
                ),
            ) as ValidationResult.Invalid

            assertEquals(expectedCode, validation.failureCode)
        }
    }
}
