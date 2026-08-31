package com.personaledge.core.data

import androidx.room.withTransaction
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class ReminderDraft(
    val title: String,
    val triggerAtEpochMillis: Long,
    val zoneId: String,
    val recurrenceRule: String? = null,
    val precision: ReminderPrecision = ReminderPrecision.FLEXIBLE,
    val leadTimeMinutes: Int? = null,
    val escalationPolicy: String? = null,
    val sourceType: ReminderSourceType,
    val sourceRefHash: String? = null,
    val createdBy: ReminderCreator,
    val confirmationDigest: String,
)

sealed interface ReminderCreateResult {
    data class Created(val reminder: ReminderEntity) : ReminderCreateResult

    data object CapacityReached : ReminderCreateResult

    data object Invalid : ReminderCreateResult
}

/** The single source of truth for reminders; OS alarms are always reconciled back to these rows. */
class ReminderRepository(
    private val database: PersonalEdgeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.reminderDao()
    private val deliveryDao = database.reminderDeliveryDao()

    suspend fun create(draft: ReminderDraft): ReminderCreateResult {
        val valid = draft.validated() ?: return ReminderCreateResult.Invalid
        return database.withTransaction {
            if (dao.countActive() >= MAX_ACTIVE_REMINDERS) {
                return@withTransaction ReminderCreateResult.CapacityReached
            }
            val now = clock()
            val reminder = ReminderEntity(
                id = idFactory(),
                title = valid.title,
                triggerAtEpochMillis = valid.triggerAtEpochMillis,
                zoneId = valid.zoneId,
                recurrenceRule = valid.recurrenceRule,
                state = ReminderState.ACTIVE,
                precision = valid.precision,
                scheduleState = ReminderScheduleState.PENDING,
                scheduleVersion = 1,
                snoozeUntilEpochMillis = null,
                leadTimeMinutes = valid.leadTimeMinutes,
                escalationPolicy = valid.escalationPolicy,
                sourceType = valid.sourceType,
                sourceRefHash = valid.sourceRefHash,
                createdBy = valid.createdBy,
                confirmationDigest = valid.confirmationDigest,
                lastScheduledAtEpochMillis = null,
                lastDeliveredAtEpochMillis = null,
                completedAtEpochMillis = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            dao.insert(reminder)
            ReminderCreateResult.Created(reminder)
        }
    }

    suspend fun find(id: String): ReminderEntity? = if (id.isBlank()) null else dao.find(id)

    suspend fun findBySourceRefHash(sourceRefHash: String): ReminderEntity? =
        if (sourceRefHash.matches(SHA256)) dao.findBySourceRefHash(sourceRefHash) else null

    suspend fun active(): List<ReminderEntity> = dao.listActive()

    suspend fun upcoming(limit: Int = DEFAULT_PAGE_SIZE): List<ReminderEntity> =
        dao.listUpcoming(limit.coerceIn(1, MAX_PAGE_SIZE))

    suspend fun complete(
        id: String,
        expectedScheduleVersion: Long? = null,
    ): ReminderEntity? = mutateActive(id, expectedScheduleVersion) { current, now ->
        current.copy(
            state = ReminderState.COMPLETED,
            completedAtEpochMillis = now,
            snoozeUntilEpochMillis = null,
            scheduleState = ReminderScheduleState.PENDING,
            scheduleVersion = current.scheduleVersion + 1,
            updatedAtEpochMillis = now,
        )
    }

    suspend fun cancel(
        id: String,
        expectedScheduleVersion: Long? = null,
    ): ReminderEntity? = mutateActive(id, expectedScheduleVersion) { current, now ->
        current.copy(
            state = ReminderState.CANCELLED,
            snoozeUntilEpochMillis = null,
            scheduleState = ReminderScheduleState.PENDING,
            scheduleVersion = current.scheduleVersion + 1,
            updatedAtEpochMillis = now,
        )
    }

    suspend fun snooze(
        id: String,
        untilEpochMillis: Long,
        expectedScheduleVersion: Long? = null,
    ): ReminderEntity? {
        if (untilEpochMillis <= clock()) return null
        return mutateActive(id, expectedScheduleVersion) { current, now ->
            current.copy(
                snoozeUntilEpochMillis = untilEpochMillis,
                scheduleState = ReminderScheduleState.PENDING,
                scheduleVersion = current.scheduleVersion + 1,
                updatedAtEpochMillis = now,
            )
        }
    }

    suspend fun replace(
        id: String,
        expectedScheduleVersion: Long,
        draft: ReminderDraft,
    ): ReminderEntity? {
        val valid = draft.validated() ?: return null
        return database.withTransaction {
            val current = dao.find(id) ?: return@withTransaction null
            if (current.state != ReminderState.ACTIVE || current.scheduleVersion != expectedScheduleVersion) {
                return@withTransaction null
            }
            val now = clock()
            val updated = current.copy(
                title = valid.title,
                triggerAtEpochMillis = valid.triggerAtEpochMillis,
                zoneId = valid.zoneId,
                recurrenceRule = valid.recurrenceRule,
                precision = valid.precision,
                leadTimeMinutes = valid.leadTimeMinutes,
                escalationPolicy = valid.escalationPolicy,
                sourceType = valid.sourceType,
                sourceRefHash = valid.sourceRefHash,
                createdBy = valid.createdBy,
                confirmationDigest = valid.confirmationDigest,
                scheduleState = ReminderScheduleState.PENDING,
                scheduleVersion = current.scheduleVersion + 1,
                snoozeUntilEpochMillis = null,
                updatedAtEpochMillis = now,
            )
            dao.update(updated)
            updated
        }
    }

    suspend fun markScheduled(
        id: String,
        scheduleVersion: Long,
        state: ReminderScheduleState,
    ): Boolean = dao.markScheduled(id, scheduleVersion, state, clock()) == 1

    suspend fun markDelivered(id: String, scheduleVersion: Long): Boolean =
        dao.markDelivered(id, scheduleVersion, clock()) == 1

    /**
     * Atomically records a posted notification and moves the source of truth forward.
     *
     * A one-shot remains ACTIVE until the owner marks it complete, but DELIVERED prevents every
     * reconciliation pass from posting it again. A recurring reminder advances by local calendar
     * date in its canonical zone and gets a new version, invalidating stale PendingIntents.
     */
    suspend fun afterNotificationPosted(
        id: String,
        scheduleVersion: Long,
    ): ReminderEntity? = database.withTransaction {
        val current = dao.find(id) ?: return@withTransaction null
        if (current.state != ReminderState.ACTIVE || current.scheduleVersion != scheduleVersion) {
            return@withTransaction null
        }
        val now = clock()
        deliveryDao.insert(current.delivery(ReminderDeliveryOutcome.POSTED, now))
        val nextTrigger = ReminderRecurrencePolicy.nextFutureTrigger(
            triggerAtEpochMillis = current.triggerAtEpochMillis,
            zoneId = current.zoneId,
            recurrenceRule = current.recurrenceRule,
            nowEpochMillis = now,
        )
        val updated = when {
            nextTrigger != null -> {
                current.copy(
                    triggerAtEpochMillis = nextTrigger,
                    snoozeUntilEpochMillis = null,
                    scheduleState = ReminderScheduleState.PENDING,
                    scheduleVersion = current.scheduleVersion + 1,
                    lastDeliveredAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
            }
            current.escalationPolicy == "until_completed" -> current.copy(
                snoozeUntilEpochMillis = now + ESCALATION_INTERVAL_MILLIS,
                scheduleState = ReminderScheduleState.PENDING,
                scheduleVersion = current.scheduleVersion + 1,
                lastDeliveredAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            else -> current.copy(
                scheduleState = ReminderScheduleState.DELIVERED,
                lastDeliveredAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        }
        dao.update(updated)
        updated
    }

    suspend fun recordDeliveryFailure(
        id: String,
        scheduleVersion: Long,
        outcome: ReminderDeliveryOutcome,
        scheduleState: ReminderScheduleState,
    ): Boolean = database.withTransaction {
        val current = dao.find(id) ?: return@withTransaction false
        if (current.state != ReminderState.ACTIVE || current.scheduleVersion != scheduleVersion) {
            return@withTransaction false
        }
        val now = clock()
        deliveryDao.insert(current.delivery(outcome, now))
        dao.update(
            current.copy(
                scheduleState = scheduleState,
                updatedAtEpochMillis = now,
            ),
        ) == 1
    }

    suspend fun recordDelivery(
        reminder: ReminderEntity,
        outcome: ReminderDeliveryOutcome,
    ) {
        deliveryDao.insert(reminder.delivery(outcome, clock()))
    }

    /** Records a user action that was safely refused because its notification was obsolete. */
    suspend fun recordStaleAction(id: String, attemptedScheduleVersion: Long): Boolean {
        if (id.isBlank() || attemptedScheduleVersion < 1) return false
        return database.withTransaction {
            val current = dao.find(id) ?: return@withTransaction false
            deliveryDao.insert(
                current.delivery(ReminderDeliveryOutcome.STALE_ACTION_REFUSED, clock()).copy(
                    scheduleVersion = attemptedScheduleVersion,
                ),
            )
            true
        }
    }

    suspend fun deliveries(reminderId: String, limit: Int = 50): List<ReminderDeliveryEntity> =
        deliveryDao.listForReminder(reminderId, limit.coerceIn(1, 200))

    suspend fun countActiveSnoozedBetween(startEpochMillis: Long, endEpochMillis: Long): Int {
        if (startEpochMillis >= endEpochMillis) return 0
        return deliveryDao.countActiveSnoozedBetween(startEpochMillis, endEpochMillis)
    }

    private suspend fun mutateActive(
        id: String,
        expectedScheduleVersion: Long? = null,
        transform: (ReminderEntity, Long) -> ReminderEntity,
    ): ReminderEntity? = database.withTransaction {
        val current = dao.find(id) ?: return@withTransaction null
        if (current.state != ReminderState.ACTIVE ||
            (expectedScheduleVersion != null && current.scheduleVersion != expectedScheduleVersion)
        ) return@withTransaction null
        val updated = transform(current, clock())
        dao.update(updated)
        updated
    }

    private fun ReminderEntity.delivery(
        outcome: ReminderDeliveryOutcome,
        recordedAt: Long,
    ): ReminderDeliveryEntity = ReminderDeliveryEntity(
        id = idFactory(),
        reminderId = id,
        scheduleVersion = scheduleVersion,
        scheduledForEpochMillis = effectiveTriggerAtEpochMillis,
        outcome = outcome,
        recordedAtEpochMillis = recordedAt,
    )

    private fun ReminderDraft.validated(): ReminderDraft? {
        val safeTitle = ReminderTextPolicy.sanitizeTitle(title) ?: return null
        if (triggerAtEpochMillis <= 0) return null
        try {
            ZoneId.of(zoneId)
        } catch (_: DateTimeException) {
            return null
        }
        val recurrence = when (val result = ReminderRecurrencePolicy.validate(recurrenceRule)) {
            is ReminderRecurrenceValidation.Valid -> result.canonical
            ReminderRecurrenceValidation.Invalid -> return null
        }
        if (leadTimeMinutes != null && leadTimeMinutes !in 0..MAX_LEAD_TIME_MINUTES) return null
        if (!confirmationDigest.matches(SHA256)) return null
        if (sourceRefHash != null && !sourceRefHash.matches(SHA256)) return null
        if (escalationPolicy != null && escalationPolicy !in ALLOWED_ESCALATION_POLICIES) return null
        return copy(title = safeTitle, recurrenceRule = recurrence)
    }

    companion object {
        const val MAX_ACTIVE_REMINDERS = 2_000
        const val DEFAULT_PAGE_SIZE = 100
        const val MAX_PAGE_SIZE = 500
        const val MAX_LEAD_TIME_MINUTES = 7 * 24 * 60
        const val ESCALATION_INTERVAL_MILLIS = 15L * 60 * 1_000
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val ALLOWED_ESCALATION_POLICIES = setOf("once", "until_completed")
    }
}

object ReminderTextPolicy {
    const val MAX_TITLE_CODE_POINTS = 120

    fun sanitizeTitle(raw: String): String? {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ")
        if (collapsed.codePointCount(0, collapsed.length) !in 1..MAX_TITLE_CODE_POINTS) return null
        if (collapsed.contains("<|") || collapsed.contains("|>")) return null
        if (collapsed.codePoints().anyMatch { codePoint ->
                Character.isISOControl(codePoint) || when (Character.getType(codePoint)) {
                    Character.FORMAT.toInt(),
                    Character.LINE_SEPARATOR.toInt(),
                    Character.PARAGRAPH_SEPARATOR.toInt(),
                    -> true
                    else -> false
                }
            }
        ) return null
        return collapsed
    }
}

object ReminderRecurrencePolicy {
    private val DAY_ORDER = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private val DAY_BY_TOKEN = mapOf(
        "mon" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY,
    )

    fun validate(raw: String?): ReminderRecurrenceValidation {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (value.isEmpty() || value == "none") return ReminderRecurrenceValidation.Valid(null)
        if (value == "daily") return ReminderRecurrenceValidation.Valid(value)
        if (!value.startsWith("weekly:")) return ReminderRecurrenceValidation.Invalid
        val tokens = value.removePrefix("weekly:").split(',').map(String::trim)
        if (tokens.isEmpty() || tokens.any { it !in DAY_ORDER } || tokens.toSet().size != tokens.size) {
            return ReminderRecurrenceValidation.Invalid
        }
        return ReminderRecurrenceValidation.Valid(
            "weekly:" + DAY_ORDER.filter(tokens::contains).joinToString(","),
        )
    }

    /**
     * Advances directly to the first future, unambiguous local occurrence.
     *
     * This prevents a device that was offline for several occurrences from posting a catch-up
     * burst. A local wall time inside a DST gap or overlap is skipped instead of allowing
     * `atZone` to move it or choose an offset the owner never approved.
     */
    fun nextFutureTrigger(
        triggerAtEpochMillis: Long,
        zoneId: String,
        recurrenceRule: String?,
        nowEpochMillis: Long,
    ): Long? {
        val canonical = (validate(recurrenceRule) as? ReminderRecurrenceValidation.Valid)
            ?.canonical ?: return null
        val zone = try {
            ZoneId.of(zoneId)
        } catch (_: DateTimeException) {
            return null
        }
        val currentLocal = try {
            Instant.ofEpochMilli(triggerAtEpochMillis).atZone(zone).toLocalDateTime()
        } catch (_: DateTimeException) {
            return null
        }
        val allowedDays = if (canonical.startsWith("weekly:")) {
            canonical.removePrefix("weekly:").split(',').mapNotNull(DAY_BY_TOKEN::get).toSet()
        } else {
            emptySet()
        }

        for (days in 1L..MAX_ADVANCE_DAYS) {
            val candidate = try {
                currentLocal.plusDays(days)
            } catch (_: DateTimeException) {
                return null
            } catch (_: ArithmeticException) {
                return null
            }
            if (canonical != "daily" && candidate.dayOfWeek !in allowedDays) continue
            val offsets = zone.rules.getValidOffsets(candidate)
            if (offsets.size != 1) continue
            val instant = candidate.toInstant(offsets.single()).toEpochMilli()
            if (instant > nowEpochMillis) return instant
        }
        return null
    }

    private const val MAX_ADVANCE_DAYS = 10L * 366L
}

sealed interface ReminderRecurrenceValidation {
    data class Valid(val canonical: String?) : ReminderRecurrenceValidation

    data object Invalid : ReminderRecurrenceValidation
}
