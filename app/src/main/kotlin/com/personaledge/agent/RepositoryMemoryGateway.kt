package com.personaledge.agent

import com.personaledge.core.data.MemoryRepository
import com.personaledge.core.data.MemoryCategory
import com.personaledge.core.data.RememberResult
import com.personaledge.core.tools.MemoryGateway
import com.personaledge.core.tools.MemoryWriteRequest
import com.personaledge.core.tools.MemoryWriteOutcome

/** Adapts the local Room repository to the narrow Tool-owned write surface. */
class RepositoryMemoryGateway(
    private val repository: MemoryRepository,
) : MemoryGateway {
    override suspend fun remember(request: MemoryWriteRequest): MemoryWriteOutcome =
        when (
            repository.remember(
                rawContent = request.content,
                category = MemoryCategory.valueOf(request.category.name),
                validUntilEpochMillis = request.validUntilEpochMillis,
                supersedesId = request.supersedesId,
            )
        ) {
            is RememberResult.Saved -> MemoryWriteOutcome.SAVED
            RememberResult.CapacityReached -> MemoryWriteOutcome.CAPACITY_REACHED
            RememberResult.Invalid -> MemoryWriteOutcome.REJECTED
        }
}
