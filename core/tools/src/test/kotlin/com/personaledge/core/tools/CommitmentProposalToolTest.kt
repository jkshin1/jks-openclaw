package com.personaledge.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentProposalToolTest {
    @Test
    fun `confirmed proposal is stored without scheduling`() = runBlocking {
        val writes = mutableListOf<CommitmentProposalWriteRequest>()
        val tool = CommitmentProposalTool(
            gateway = CommitmentProposalGateway { request ->
                writes += request
                CommitmentProposalWriteOutcome.SAVED
            },
            clock = { 1_700_000_000_000L },
        )
        val valid = tool.validateAndCanonicalize(
            CommitmentProposalParams("다음 주 자동차 보험 확인"),
        ) as ValidationResult.Valid
        val input = CanonicalToolInput(valid.canonicalParams)

        assertTrue(tool.preview(input).summary.contains("아직 리마인더"))
        val result = tool.execute(input, ExecutionPermit("action"))

        assertEquals(CommitmentProposalWriteOutcome.SAVED, result.outcome)
        assertNull(writes.single().proposedDueAtEpochMillis)
        assertEquals(64, writes.single().sourceRefHash.length)
        assertEquals(ToolExecutionOutcome.WRITE_COMPLETED, tool.executionOutcome(result))
    }

    @Test
    fun `date and zone must be complete unique and future`() = runBlocking {
        val tool = CommitmentProposalTool(
            gateway = CommitmentProposalGateway { CommitmentProposalWriteOutcome.SAVED },
            clock = { 1_700_000_000_000L },
        )

        assertTrue(
            tool.validateAndCanonicalize(
                CommitmentProposalParams("보험 확인", proposedAt = "2027-01-01T09:00"),
            ) is ValidationResult.Invalid,
        )
        assertTrue(
            tool.validateAndCanonicalize(
                CommitmentProposalParams(
                    "보험 확인",
                    proposedAt = "2027-01-01T09:00",
                    zoneId = "Asia/Seoul",
                ),
            ) is ValidationResult.Valid,
        )
    }
}
