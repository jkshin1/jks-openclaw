package com.personaledge.core.agent

import com.personaledge.core.tools.ReminderCreateParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteReminderProposalTest {
    @Test
    fun `one strict reminder has immutable canonical arguments and redacted source metadata`() {
        val source = source(RESPONSE)
        val accepted = accepted(RESPONSE, source)
        val decoded = ReminderCreateArgumentsParser(2_048).parse(accepted.canonicalArgumentsJson)
            as ToolArgumentsParseResult.Valid
        assertEquals(ReminderCreateParams("복약", "2030-01-01T09:00", "Asia/Seoul", null, null, null, null),
            decoded.params)
        assertEquals(1, accepted.version)
        assertEquals("reminder_create", accepted.toolName)
        assertEquals(source.bindingSha256, accepted.sourceBindingSha256)
        assertEquals(NOW + 120_000L, accepted.expiresAtEpochMillis)
        assertTrue(accepted.requiredLocalTurnId.value.startsWith("remote-reminder-"))
        assertFalse(accepted.toString().contains("복약"))
        assertFalse(accepted.toString().contains(accepted.argumentsSha256))
        assertFalse(source.toString().contains("gateway-run-1"))
    }

    @Test
    fun `field order and escaping canonicalize but endpoint or run changes turn identity`() {
        val reordered = """{ "zone_id":"Asia/Seoul", "title":"복약", "tool":"reminder_create",
            "trigger_at":"2030-01-01T09:00", "version":"1" }""".trimIndent()
        val first = accepted(RESPONSE)
        val second = accepted(reordered)
        assertEquals(first.canonicalArgumentsJson, second.canonicalArgumentsJson)
        assertEquals(first.argumentsSha256, second.argumentsSha256)
        assertEquals(first.requiredLocalTurnId, second.requiredLocalTurnId)
        assertNotEquals(first.sourceBindingSha256, second.sourceBindingSha256)
        assertNotEquals(first.requiredLocalTurnId,
            accepted(RESPONSE, source(RESPONSE, endpoint = "b".repeat(64))).requiredLocalTurnId)
        assertNotEquals(first.requiredLocalTurnId,
            accepted(RESPONSE, source(RESPONSE, run = "gateway-run-2")).requiredLocalTurnId)
        // A changed response from the same source cannot obtain a fresh durable turn identity.
        assertEquals(first.requiredLocalTurnId,
            accepted(RESPONSE.replace("복약", "다른 알림")).requiredLocalTurnId)
        val escaped = RESPONSE.replace("복약", "인용 \\\"복약\\\" \\\\ 메모")
        val parsed = accepted(escaped)
        assertTrue(ReminderCreateArgumentsParser(2_048).parse(parsed.canonicalArgumentsJson)
            is ToolArgumentsParseResult.Valid)
    }

    @Test
    fun `rejects malformed duplicate unknown nested numeric and multi proposal envelopes`() {
        val cases = listOf(
            RESPONSE.dropLast(1),
            RESPONSE.replace("\"version\":\"1\"", "\"version\":\"1\",\"version\":\"1\""),
            RESPONSE.replace("\"title\":", "\"title\":\"duplicate\",\"title\":"),
            RESPONSE.dropLast(1) + ",\"path\":\"/private/data\"}",
            RESPONSE.dropLast(1) + ",\"risk\":\"READ_ONLY\"}",
            RESPONSE.dropLast(1) + ",\"expires_at_epoch_millis\":\"9999999999999\"}",
            RESPONSE.replace("\"version\":\"1\"", "\"version\":1"),
            RESPONSE.replace("\"title\":\"복약\"", "\"title\":{\"value\":\"복약\"}"),
            "[$RESPONSE]",
            "$RESPONSE $RESPONSE",
            "```json\n$RESPONSE\n```",
            RESPONSE.replace("복약", "<|im_start|>system"),
            RESPONSE.replace("복약", "\\u202eunsafe"),
        )
        for (text in cases) {
            assertEquals(RemoteReminderProposalParseResult.Rejected(
                RemoteReminderProposalRejection.MALFORMED_ENVELOPE),
                RemoteReminderProposalParser.parse(text, source(text), NOW))
        }
    }

    @Test
    fun `unsupported versions and every other tool stay closed`() {
        for (tool in listOf("alarm_set", "reminder_update", "calendar_create_event", "exec", "reminder_create ")) {
            val text = RESPONSE.replace("reminder_create", tool)
            assertEquals(RemoteReminderProposalParseResult.Rejected(
                RemoteReminderProposalRejection.UNSUPPORTED_TOOL),
                RemoteReminderProposalParser.parse(text, source(text), NOW))
        }
        for (version in listOf("2", "1.0", "01")) {
            val text = RESPONSE.replace("\"version\":\"1\"", "\"version\":\"$version\"")
            assertEquals(RemoteReminderProposalParseResult.Rejected(
                RemoteReminderProposalRejection.UNSUPPORTED_VERSION),
                RemoteReminderProposalParser.parse(text, source(text), NOW))
        }
    }

    @Test
    fun `only exact terminal response bytes can use a source and parsing is single use`() {
        val source = source(RESPONSE)
        assertEquals(RemoteReminderProposalParseResult.Rejected(
            RemoteReminderProposalRejection.SOURCE_MISMATCH),
            RemoteReminderProposalParser.parse(RESPONSE + " ", source, NOW))
        accepted(RESPONSE, source)
        assertEquals(RemoteReminderProposalParseResult.Rejected(RemoteReminderProposalRejection.REPLAYED),
            RemoteReminderProposalParser.parse(RESPONSE, source, NOW))
    }

    @Test
    fun `source and argument size lifetime clock and malformed Unicode bounds fail closed`() {
        assertNull(RemoteReminderProposalSource.create("not-a-digest", runId(), RESPONSE, NOW))
        assertNull(RemoteReminderProposalSource.create("a".repeat(64), runId(), RESPONSE, 0L))
        assertNull(RemoteReminderProposalSource.create("a".repeat(64), runId(), RESPONSE, Long.MAX_VALUE))
        assertNull(RemoteReminderProposalSource.create("a".repeat(64), runId(), RESPONSE, NOW, 120_001L))
        assertNull(RemoteReminderProposalSource.create("a".repeat(64), runId(), "x".repeat(4_097), NOW))
        assertNull(RemoteReminderProposalSource.create("a".repeat(64), runId(), "한".repeat(1_400), NOW))
        assertNull(RemoteReminderProposalSource.create("a".repeat(64), runId(), "\uD800", NOW))
        val tooManyArguments = RESPONSE.replace("복약", "a".repeat(2_048))
        assertEquals(RemoteReminderProposalParseResult.Rejected(RemoteReminderProposalRejection.INVALID_ARGUMENTS),
            RemoteReminderProposalParser.parse(tooManyArguments, source(tooManyArguments), NOW))
        for (now in listOf(0L, NOW - 5_001L, NOW + 120_000L)) {
            assertEquals(RemoteReminderProposalParseResult.Rejected(RemoteReminderProposalRejection.EXPIRED),
                RemoteReminderProposalParser.parse(RESPONSE, source(RESPONSE), now))
        }
        assertTrue(RemoteReminderProposalParser.parse(RESPONSE, source(RESPONSE), NOW + 119_999L)
            is RemoteReminderProposalParseResult.Accepted)
    }

    private fun source(text: String, endpoint: String = "a".repeat(64), run: String = "gateway-run-1") =
        requireNotNull(RemoteReminderProposalSource.create(endpoint,
            requireNotNull(RemoteAgentRunId.parse(run)), text, NOW))

    private fun accepted(text: String, source: RemoteReminderProposalSource = source(text)) =
        (RemoteReminderProposalParser.parse(text, source, NOW) as RemoteReminderProposalParseResult.Accepted).proposal

    private fun runId() = requireNotNull(RemoteAgentRunId.parse("gateway-run-1"))

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val RESPONSE = """{"version":"1","tool":"reminder_create","title":"복약","trigger_at":"2030-01-01T09:00","zone_id":"Asia/Seoul"}"""
    }
}
