package com.personaledge.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ReminderState {
    ACTIVE,
    COMPLETED,
    CANCELLED,
}

enum class ReminderPrecision {
    FLEXIBLE,
    EXACT,
}

enum class ReminderScheduleState {
    PENDING,
    SCHEDULED_INEXACT,
    SCHEDULED_EXACT,
    DEGRADED_TO_INEXACT,
    BLOCKED_NOTIFICATION_PERMISSION,
    BLOCKED_EXACT_PERMISSION,
    DELIVERY_FAILED,
    DELIVERED,
}

enum class ReminderSourceType {
    DIRECT,
    INBOX,
    MODEL_TOOL,
    CALENDAR,
    NOTIFICATION_PROPOSAL,
}

enum class ReminderCreator {
    USER,
    MODEL_PROPOSAL,
    SYSTEM,
}

@Entity(
    tableName = "reminders",
    indices = [
        Index(value = ["state", "trigger_at_epoch_millis"]),
        Index(value = ["schedule_state"]),
    ],
)
data class ReminderEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "trigger_at_epoch_millis")
    val triggerAtEpochMillis: Long,
    @ColumnInfo(name = "zone_id")
    val zoneId: String,
    /** Null, `daily`, or `weekly:mon,tue,...`; parsed by deterministic Kotlin policy. */
    @ColumnInfo(name = "recurrence_rule")
    val recurrenceRule: String?,
    @ColumnInfo(name = "state")
    val state: ReminderState,
    @ColumnInfo(name = "precision")
    val precision: ReminderPrecision,
    @ColumnInfo(name = "schedule_state")
    val scheduleState: ReminderScheduleState,
    @ColumnInfo(name = "schedule_version")
    val scheduleVersion: Long,
    @ColumnInfo(name = "snooze_until_epoch_millis")
    val snoozeUntilEpochMillis: Long?,
    @ColumnInfo(name = "lead_time_minutes")
    val leadTimeMinutes: Int?,
    @ColumnInfo(name = "escalation_policy")
    val escalationPolicy: String?,
    @ColumnInfo(name = "source_type")
    val sourceType: ReminderSourceType,
    @ColumnInfo(name = "source_ref_hash")
    val sourceRefHash: String?,
    @ColumnInfo(name = "created_by")
    val createdBy: ReminderCreator,
    @ColumnInfo(name = "confirmation_digest")
    val confirmationDigest: String,
    @ColumnInfo(name = "last_scheduled_at_epoch_millis")
    val lastScheduledAtEpochMillis: Long?,
    @ColumnInfo(name = "last_delivered_at_epoch_millis")
    val lastDeliveredAtEpochMillis: Long?,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
) {
    val effectiveTriggerAtEpochMillis: Long
        get() = snoozeUntilEpochMillis ?: triggerAtEpochMillis
}

enum class ReminderDeliveryOutcome {
    POSTED,
    NOTIFICATION_PERMISSION_BLOCKED,
    FAILED,
    COMPLETED_FROM_NOTIFICATION,
    SNOOZED_FROM_NOTIFICATION,
    STALE_ACTION_REFUSED,
    COMPLETED_FROM_APP,
    SNOOZED_FROM_APP,
    CANCELLED_FROM_APP,
    CANCELLED_BY_CALENDAR_RECONCILIATION,
}

@Entity(
    tableName = "reminder_deliveries",
    foreignKeys = [
        ForeignKey(
            entity = ReminderEntity::class,
            parentColumns = ["id"],
            childColumns = ["reminder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["reminder_id", "recorded_at_epoch_millis"])],
)
data class ReminderDeliveryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "reminder_id")
    val reminderId: String,
    @ColumnInfo(name = "schedule_version")
    val scheduleVersion: Long,
    @ColumnInfo(name = "scheduled_for_epoch_millis")
    val scheduledForEpochMillis: Long,
    @ColumnInfo(name = "outcome")
    val outcome: ReminderDeliveryOutcome,
    @ColumnInfo(name = "recorded_at_epoch_millis")
    val recordedAtEpochMillis: Long,
)
