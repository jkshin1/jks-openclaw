package com.personaledge.agent

import com.personaledge.core.data.ReminderCreateResult
import com.personaledge.core.data.ReminderCreator
import com.personaledge.core.data.ReminderDraft
import com.personaledge.core.data.ReminderPrecision
import com.personaledge.core.data.ReminderRepository
import com.personaledge.core.data.ReminderSourceType
import com.personaledge.core.tools.ReminderGateway
import com.personaledge.core.tools.ReminderMutationOutcome
import com.personaledge.core.tools.ReminderMutationResult
import com.personaledge.core.tools.ReminderSummary
import com.personaledge.core.tools.ReminderToolPrecision
import com.personaledge.core.tools.ReminderWriteRequest

/** Adapts the Room source of truth to the Tool-owned contract and schedules only persisted rows. */
class RepositoryReminderGateway(
    private val repository: ReminderRepository,
    private val scheduler: ReminderScheduler,
) : ReminderGateway {
    override suspend fun create(request: ReminderWriteRequest): ReminderMutationResult =
        when (val result = repository.create(request.toDraft())) {
            is ReminderCreateResult.Created -> {
                scheduleOrReconcile(result.reminder)
                ReminderMutationResult(
                    outcome = ReminderMutationOutcome.SAVED,
                    reminderId = result.reminder.id,
                    scheduleVersion = result.reminder.scheduleVersion,
                )
            }
            ReminderCreateResult.CapacityReached ->
                ReminderMutationResult(ReminderMutationOutcome.CAPACITY_REACHED)
            ReminderCreateResult.Invalid -> ReminderMutationResult(ReminderMutationOutcome.REJECTED)
        }

    override suspend fun update(
        reminderId: String,
        expectedVersion: Long,
        request: ReminderWriteRequest,
    ): ReminderMutationResult {
        val before = repository.find(reminderId)
            ?: return ReminderMutationResult(ReminderMutationOutcome.NOT_FOUND)
        if (before.scheduleVersion != expectedVersion) {
            return ReminderMutationResult(ReminderMutationOutcome.VERSION_CONFLICT)
        }
        val updated = repository.replace(reminderId, expectedVersion, request.toDraft())
            ?: return ReminderMutationResult(ReminderMutationOutcome.REJECTED)
        scheduleOrReconcile(updated)
        return ReminderMutationResult(
            ReminderMutationOutcome.SAVED,
            updated.id,
            updated.scheduleVersion,
        )
    }

    override suspend fun cancel(reminderId: String, expectedVersion: Long): ReminderMutationResult {
        val before = repository.find(reminderId)
            ?: return ReminderMutationResult(ReminderMutationOutcome.NOT_FOUND)
        if (before.scheduleVersion != expectedVersion) {
            return ReminderMutationResult(ReminderMutationOutcome.VERSION_CONFLICT)
        }
        val cancelled = repository.cancel(reminderId, expectedVersion)
            ?: return ReminderMutationResult(ReminderMutationOutcome.REJECTED)
        scheduler.cancel(cancelled.id)
        return ReminderMutationResult(
            ReminderMutationOutcome.SAVED,
            cancelled.id,
            cancelled.scheduleVersion,
        )
    }

    override suspend fun upcoming(limit: Int): List<ReminderSummary> = repository.upcoming(limit).map { reminder ->
        ReminderSummary(
            reminderId = reminder.id,
            title = reminder.title,
            triggerAtEpochMillis = reminder.effectiveTriggerAtEpochMillis,
            zoneId = reminder.zoneId,
            recurrenceRule = reminder.recurrenceRule,
            precision = reminder.precision.toToolPrecision(),
            scheduleVersion = reminder.scheduleVersion,
        )
    }

    private suspend fun scheduleOrReconcile(reminder: com.personaledge.core.data.ReminderEntity) {
        runCatching { scheduler.schedule(reminder) }
            .onFailure { scheduler.requestReconcile() }
    }

    private fun ReminderWriteRequest.toDraft(): ReminderDraft = ReminderDraft(
        title = title,
        triggerAtEpochMillis = triggerAtEpochMillis,
        zoneId = zoneId,
        recurrenceRule = recurrenceRule,
        precision = when (precision) {
            ReminderToolPrecision.FLEXIBLE -> ReminderPrecision.FLEXIBLE
            ReminderToolPrecision.EXACT -> ReminderPrecision.EXACT
        },
        leadTimeMinutes = leadTimeMinutes,
        escalationPolicy = escalationPolicy,
        sourceType = ReminderSourceType.MODEL_TOOL,
        sourceRefHash = null,
        createdBy = ReminderCreator.MODEL_PROPOSAL,
        confirmationDigest = confirmationDigest,
    )

    private fun ReminderPrecision.toToolPrecision(): ReminderToolPrecision = when (this) {
        ReminderPrecision.FLEXIBLE -> ReminderToolPrecision.FLEXIBLE
        ReminderPrecision.EXACT -> ReminderToolPrecision.EXACT
    }
}
