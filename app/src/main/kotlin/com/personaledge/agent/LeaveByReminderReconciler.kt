package com.personaledge.agent

import com.personaledge.core.data.AgentSettings
import com.personaledge.core.data.ReminderCreateResult
import com.personaledge.core.data.ReminderCreator
import com.personaledge.core.data.ReminderDeliveryOutcome
import com.personaledge.core.data.ReminderDraft
import com.personaledge.core.data.ReminderEntity
import com.personaledge.core.data.ReminderPrecision
import com.personaledge.core.data.ReminderScheduleState
import com.personaledge.core.data.ReminderSourceType
import com.personaledge.core.data.ReminderTextPolicy
import com.personaledge.core.data.takeCodePoints
import java.security.MessageDigest
import java.util.Locale

enum class LeaveByReminderReconcileOutcome {
    DISABLED,
    CALENDAR_UNAVAILABLE,
    NO_UPCOMING_PLAN,
    PAST_DEPARTURE,
    CREATED,
    UPDATED,
    UNCHANGED,
    REFUSED,
}

/**
 * Projects one opted-in deterministic leave-by fact into the app-owned reminder store.
 *
 * Only rows carrying both SYSTEM creator and CALENDAR source are managed here. Owner-created,
 * model-proposed, and notification-proposal reminders are never selected for replacement or
 * cleanup. A calendar start change creates a new source identity; traffic-only changes update the
 * existing row with an incremented schedule version, invalidating older OS work and actions.
 */
class LeaveByReminderReconciler(
    private val container: AppContainer,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun reconcile(
        settings: AgentSettings,
        brief: TodayBrief,
    ): LeaveByReminderReconcileOutcome {
        val owned = container.reminders.active().filter(::isOwnedLeaveByReminder)
        when (
            if (settings.proactiveRoutePlanningEnabled) proactiveRoutePlanningAllowed() else false
        ) {
            null -> return LeaveByReminderReconcileOutcome.REFUSED
            false -> {
                cancelOwned(owned)
                return LeaveByReminderReconcileOutcome.DISABLED
            }
            true -> Unit
        }
        if (!brief.calendarAvailable) return LeaveByReminderReconcileOutcome.CALENDAR_UNAVAILABLE

        val plan = brief.leaveByPlan
        if (plan == null) {
            cancelOwned(owned)
            return LeaveByReminderReconcileOutcome.NO_UPCOMING_PLAN
        }
        val projection = LeaveByReminderProjection.forPlan(plan, clock())
        if (projection == null) {
            cancelOwned(owned)
            return LeaveByReminderReconcileOutcome.PAST_DEPARTURE
        }

        val matching = owned.firstOrNull { reminder ->
            reminder.sourceRefHash == projection.draft.sourceRefHash
        }
        // The alert for this calendar occurrence was already delivered. A later traffic refresh
        // must not emit a second alert for the same source occurrence.
        if (matching?.scheduleState == ReminderScheduleState.DELIVERED) {
            cancelOwned(owned.filterNot { reminder -> reminder.id == matching.id })
            return LeaveByReminderReconcileOutcome.UNCHANGED
        }
        if (matching != null && matching.matches(projection.draft)) {
            cancelOwned(owned.filterNot { reminder -> reminder.id == matching.id })
            if (matching.scheduleState != ReminderScheduleState.DELIVERED) {
                runCatching { container.reminderScheduler.schedule(matching) }
                    .onFailure { container.reminderScheduler.requestReconcile() }
            }
            return LeaveByReminderReconcileOutcome.UNCHANGED
        }

        val replacementTarget = matching ?: owned.firstOrNull()
        when (proactiveRoutePlanningAllowed()) {
            null -> return LeaveByReminderReconcileOutcome.REFUSED
            false -> {
                cancelOwned(owned)
                return LeaveByReminderReconcileOutcome.DISABLED
            }
            true -> Unit
        }
        val projected = if (replacementTarget == null) {
            when (val created = container.reminders.create(projection.draft)) {
                is ReminderCreateResult.Created -> created.reminder
                ReminderCreateResult.CapacityReached,
                ReminderCreateResult.Invalid,
                -> return LeaveByReminderReconcileOutcome.REFUSED
            }
        } else {
            container.reminders.replace(
                replacementTarget.id,
                replacementTarget.scheduleVersion,
                projection.draft,
            ) ?: return LeaveByReminderReconcileOutcome.REFUSED
        }

        cancelOwned(owned.filterNot { reminder -> reminder.id == projected.id })
        runCatching { container.reminderScheduler.schedule(projected) }
            .onFailure { container.reminderScheduler.requestReconcile() }
        return if (replacementTarget == null) {
            LeaveByReminderReconcileOutcome.CREATED
        } else {
            LeaveByReminderReconcileOutcome.UPDATED
        }
    }

    private suspend fun proactiveRoutePlanningAllowed(): Boolean? {
        val current = runCatching { container.settings.current() }.getOrNull() ?: return null
        return container.ownerConsentInterlock.allowed(
            OwnerConsentFeature.PROACTIVE_ROUTE_PLANNING,
            current.proactiveRoutePlanningEnabled,
        )
    }

    private suspend fun cancelOwned(reminders: List<ReminderEntity>) {
        reminders.forEach { reminder ->
            val cancelled = container.reminders.cancel(reminder.id, reminder.scheduleVersion)
            if (cancelled != null) {
                container.reminders.recordDelivery(
                    reminder,
                    ReminderDeliveryOutcome.CANCELLED_BY_CALENDAR_RECONCILIATION,
                )
                container.reminderScheduler.cancel(reminder.id)
            }
        }
    }

    private fun isOwnedLeaveByReminder(reminder: ReminderEntity): Boolean =
        reminder.sourceType == ReminderSourceType.CALENDAR &&
            reminder.createdBy == ReminderCreator.SYSTEM

    private fun ReminderEntity.matches(draft: ReminderDraft): Boolean =
        title == draft.title &&
            triggerAtEpochMillis == draft.triggerAtEpochMillis &&
            zoneId == draft.zoneId &&
            recurrenceRule == draft.recurrenceRule &&
            precision == draft.precision &&
            leadTimeMinutes == draft.leadTimeMinutes &&
            escalationPolicy == draft.escalationPolicy &&
            sourceType == draft.sourceType &&
            sourceRefHash == draft.sourceRefHash &&
            createdBy == draft.createdBy &&
            confirmationDigest == draft.confirmationDigest
}

internal data class LeaveByReminderProjection(val draft: ReminderDraft) {
    companion object {
        fun forPlan(plan: LeaveByPlan, nowEpochMillis: Long): LeaveByReminderProjection? {
            if (plan.leaveAtEpochMillis <= nowEpochMillis) return null
            val leadMillis = plan.eventStartEpochMillis - plan.leaveAtEpochMillis
            if (leadMillis <= 0L || leadMillis % 60_000L != 0L) return null
            val leadMinutes = leadMillis / 60_000L
            if (leadMinutes !in 1L..10_080L) return null
            val prefix = "출발 준비 · "
            val titleBudget = com.personaledge.core.data.ReminderTextPolicy.MAX_TITLE_CODE_POINTS -
                prefix.codePointCount(0, prefix.length)
            val title = ReminderTextPolicy.sanitizeTitle(
                prefix + plan.eventTitle.takeCodePoints(titleBudget),
            ) ?: return null
            val sourceRefHash = sha256(
                listOf(
                    "leave_by_source_v1",
                    plan.calendarId,
                    plan.eventId,
                    plan.eventStartEpochMillis,
                    plan.zoneId,
                ).joinToString("\u0000"),
            )
            val confirmationDigest = sha256(
                listOf(
                    "leave_by_projection_v1",
                    title,
                    plan.leaveAtEpochMillis,
                    plan.zoneId,
                    plan.usedFallback,
                    sourceRefHash,
                ).joinToString("\u0000"),
            )
            return LeaveByReminderProjection(
                ReminderDraft(
                    title = title,
                    triggerAtEpochMillis = plan.eventStartEpochMillis,
                    zoneId = plan.zoneId,
                    recurrenceRule = null,
                    precision = ReminderPrecision.FLEXIBLE,
                    leadTimeMinutes = leadMinutes.toInt(),
                    escalationPolicy = "once",
                    sourceType = ReminderSourceType.CALENDAR,
                    sourceRefHash = sourceRefHash,
                    createdBy = ReminderCreator.SYSTEM,
                    confirmationDigest = confirmationDigest,
                ),
            )
        }

        private fun sha256(value: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }
}
