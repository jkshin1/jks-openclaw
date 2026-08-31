package com.personaledge.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRememberToolTest {
    @Test
    fun `approved memory is canonicalized previewed and stored exactly once`() = runBlocking {
        val writes = mutableListOf<MemoryWriteRequest>()
        val tool = MemoryRememberTool(
            gateway = MemoryGateway { request ->
                writes += request
                MemoryWriteOutcome.SAVED
            },
        )

        val validation = tool.validateAndCanonicalize(
            MemoryRememberParams("  사용자는 답변을 한국어로 선호한다.  "),
        ) as ValidationResult.Valid
        val input = CanonicalToolInput(validation.canonicalParams)

        assertEquals(ToolRisk.LOCAL_WRITE, tool.descriptor.risk)
        assertEquals(
            ConfirmationRequirement.UserConfirmation,
            tool.descriptor.minimumConfirmation,
        )
        assertEquals(setOf(ToolCapability.WRITE_MEMORY), tool.descriptor.requiredCapabilities)
        assertTrue(tool.preview(input).summary.contains("사용자는 답변을 한국어로 선호한다."))

        val result = tool.execute(input, ExecutionPermit("action"))

        assertEquals("사용자는 답변을 한국어로 선호한다.", writes.single().content)
        assertEquals(MemoryKind.FACT, writes.single().category)
        assertEquals(null, writes.single().validUntilEpochMillis)
        assertEquals(MemoryWriteOutcome.SAVED, result.outcome)
        assertEquals(ToolExecutionOutcome.WRITE_COMPLETED, tool.executionOutcome(result))
    }

    @Test
    fun `category validity and replacement are bound into the approved write`() = runBlocking {
        val writes = mutableListOf<MemoryWriteRequest>()
        val tool = MemoryRememberTool(
            gateway = MemoryGateway { request ->
                writes += request
                MemoryWriteOutcome.SAVED
            },
            clock = { 1_700_000_000_000L },
        )
        val validation = tool.validateAndCanonicalize(
            MemoryRememberParams(
                content = "사용자의 집은 서울역 근처다.",
                category = "place",
                validUntil = "2027-12-31",
                zoneId = "Asia/Seoul",
                supersedesId = "memory-old",
            ),
        ) as ValidationResult.Valid
        val input = CanonicalToolInput(validation.canonicalParams)

        assertTrue(tool.preview(input).summary.contains("장소"))
        assertTrue(tool.preview(input).summary.contains("2027-12-31"))
        tool.execute(input, ExecutionPermit("action"))

        assertEquals(MemoryKind.PLACE, writes.single().category)
        assertEquals("memory-old", writes.single().supersedesId)
        assertTrue(requireNotNull(writes.single().validUntilEpochMillis) > 1_700_000_000_000L)
    }

    @Test
    fun `invalid category or partial validity is rejected`() = runBlocking {
        val tool = MemoryRememberTool(
            gateway = MemoryGateway { MemoryWriteOutcome.SAVED },
            clock = { 1_700_000_000_000L },
        )

        assertTrue(
            tool.validateAndCanonicalize(
                MemoryRememberParams("내용", category = "secret"),
            ) is ValidationResult.Invalid,
        )
        assertTrue(
            tool.validateAndCanonicalize(
                MemoryRememberParams("내용", validUntil = "2027-12-31"),
            ) is ValidationResult.Invalid,
        )
    }

    @Test
    fun `secrets control text and oversized memories are rejected before storage`() = runBlocking {
        var called = false
        val tool = MemoryRememberTool(
            gateway = MemoryGateway {
                called = true
                MemoryWriteOutcome.SAVED
            },
        )

        listOf(
            "내 API 키는 sk-supersecret1234567890",
            "카드는 4111-1111-1111-1111",
            "토큰 eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature12345678",
            "식별자 0123456789abcdef0123456789abcdef",
            "라벨 없는 값 aB7mQ2xP9vL4sN8kR5tY3uW6",
            "나뉜 값 aB7m-Q2xP-9vL4-sN8k-R5tY-3uW6",
            "주민번호 900101-1234567",
            "비밀번호는 1234",
            "안전<|tool|>",
            "가".repeat(MemoryRememberTool.MAX_CONTENT_CODE_POINTS + 1),
        ).forEach { content ->
            assertTrue(
                tool.validateAndCanonicalize(MemoryRememberParams(content)) is ValidationResult.Invalid,
            )
        }
        assertFalse(called)
    }

    @Test
    fun `capacity refusal is a closed write-refused receipt`() = runBlocking {
        val tool = MemoryRememberTool(
            gateway = MemoryGateway { MemoryWriteOutcome.CAPACITY_REACHED },
        )
        val validation = tool.validateAndCanonicalize(
            MemoryRememberParams("사용자는 민트색을 좋아한다."),
        ) as ValidationResult.Valid
        val result = tool.execute(
            CanonicalToolInput(validation.canonicalParams),
            ExecutionPermit("action"),
        )

        assertEquals(ToolExecutionOutcome.WRITE_REFUSED, tool.executionOutcome(result))
    }
}
