package com.personaledge.agent

import com.personaledge.core.data.CommitmentProposalDraft
import com.personaledge.core.data.CommitmentProposalRepository
import com.personaledge.core.data.ProposalStoreResult
import com.personaledge.core.tools.CommitmentProposalGateway
import com.personaledge.core.tools.CommitmentProposalWriteOutcome
import com.personaledge.core.tools.CommitmentProposalWriteRequest

class RepositoryCommitmentProposalGateway(
    private val repository: CommitmentProposalRepository,
) : CommitmentProposalGateway {
    override suspend fun offer(
        request: CommitmentProposalWriteRequest,
    ): CommitmentProposalWriteOutcome = when (
        repository.offer(
            CommitmentProposalDraft(
                summary = request.summary,
                proposedDueAtEpochMillis = request.proposedDueAtEpochMillis,
                zoneId = request.zoneId,
                sourcePackage = null,
                sourceRefHash = request.sourceRefHash,
                confidence = if (request.proposedDueAtEpochMillis == null) 60 else 90,
            ),
        )
    ) {
        is ProposalStoreResult.Stored -> CommitmentProposalWriteOutcome.SAVED
        ProposalStoreResult.AlreadyHandled -> CommitmentProposalWriteOutcome.ALREADY_HANDLED
        ProposalStoreResult.CapacityReached -> CommitmentProposalWriteOutcome.CAPACITY_REACHED
        ProposalStoreResult.Invalid -> CommitmentProposalWriteOutcome.REJECTED
    }
}
