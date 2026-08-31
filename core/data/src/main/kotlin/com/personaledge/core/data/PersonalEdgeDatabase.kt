package com.personaledge.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import kotlinx.coroutines.flow.Flow

class MessageRoleConverter {
    @TypeConverter
    fun toStored(role: MessageRole): String = role.name

    /** An unknown stored value means a downgrade or corruption, not something to guess at. */
    @TypeConverter
    fun fromStored(stored: String): MessageRole = MessageRole.valueOf(stored)
}

class MemoryEnumConverters {
    @TypeConverter
    fun categoryToStored(value: MemoryCategory): String = value.name

    /** Unknown values are corruption or an unsupported downgrade, so do not guess a category. */
    @TypeConverter
    fun categoryFromStored(value: String): MemoryCategory = MemoryCategory.valueOf(value)
}

class ProposalEnumConverters {
    @TypeConverter
    fun statusToStored(value: ProposalStatus): String = value.name

    @TypeConverter
    fun statusFromStored(value: String): ProposalStatus = ProposalStatus.valueOf(value)
}

class ReminderEnumConverters {
    @TypeConverter
    fun reminderStateToStored(value: ReminderState): String = value.name

    @TypeConverter
    fun reminderStateFromStored(value: String): ReminderState = ReminderState.valueOf(value)

    @TypeConverter
    fun reminderPrecisionToStored(value: ReminderPrecision): String = value.name

    @TypeConverter
    fun reminderPrecisionFromStored(value: String): ReminderPrecision = ReminderPrecision.valueOf(value)

    @TypeConverter
    fun scheduleStateToStored(value: ReminderScheduleState): String = value.name

    @TypeConverter
    fun scheduleStateFromStored(value: String): ReminderScheduleState = ReminderScheduleState.valueOf(value)

    @TypeConverter
    fun sourceTypeToStored(value: ReminderSourceType): String = value.name

    @TypeConverter
    fun sourceTypeFromStored(value: String): ReminderSourceType = ReminderSourceType.valueOf(value)

    @TypeConverter
    fun creatorToStored(value: ReminderCreator): String = value.name

    @TypeConverter
    fun creatorFromStored(value: String): ReminderCreator = ReminderCreator.valueOf(value)

    @TypeConverter
    fun deliveryOutcomeToStored(value: ReminderDeliveryOutcome): String = value.name

    @TypeConverter
    fun deliveryOutcomeFromStored(value: String): ReminderDeliveryOutcome = ReminderDeliveryOutcome.valueOf(value)
}

class TurnOutcomeConverters {
    @TypeConverter
    fun stateToStored(value: TurnOutcomeState): String = value.name

    @TypeConverter
    fun stateFromStored(value: String): TurnOutcomeState = TurnOutcomeState.valueOf(value)

    @TypeConverter
    fun recoverabilityToStored(value: TurnRecoverability): String = value.name

    @TypeConverter
    fun recoverabilityFromStored(value: String): TurnRecoverability =
        TurnRecoverability.valueOf(value)

    @TypeConverter
    fun riskToStored(value: TurnToolRisk): String = value.name

    @TypeConverter
    fun riskFromStored(value: String): TurnToolRisk = TurnToolRisk.valueOf(value)

    @TypeConverter
    fun failureToStored(value: TurnOutcomeFailureCode?): String? = value?.name

    @TypeConverter
    fun failureFromStored(value: String?): TurnOutcomeFailureCode? =
        value?.let(TurnOutcomeFailureCode::valueOf)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY created_at_epoch_millis, id")
    suspend fun listAllForTransfer(): List<ConversationEntity>

    @Query("SELECT * FROM conversations ORDER BY updated_at_epoch_millis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updated_at_epoch_millis DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun find(conversationId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertForTransfer(conversation: ConversationEntity): Long

    @Query(
        "UPDATE conversations SET title = :title, updated_at_epoch_millis = :updatedAt " +
            "WHERE id = :conversationId",
    )
    suspend fun updateTitle(conversationId: String, title: String, updatedAt: Long)

    @Query(
        "UPDATE conversations SET summary = :summary, " +
            "summarized_through_message_ordinal = :throughOrdinal, " +
            "updated_at_epoch_millis = :updatedAt WHERE id = :conversationId",
    )
    suspend fun updateSummary(
        conversationId: String,
        summary: String?,
        throughOrdinal: Long,
        updatedAt: Long,
    )

    @Query("UPDATE conversations SET updated_at_epoch_millis = :updatedAt WHERE id = :conversationId")
    suspend fun touch(conversationId: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun delete(conversationId: String): Int

    @Query("DELETE FROM conversations")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Long
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY conversation_id, ordinal")
    suspend fun listAllForTransfer(): List<MessageEntity>

    @Query(
        "SELECT * FROM (" +
            "SELECT * FROM messages WHERE conversation_id = :conversationId " +
            "ORDER BY ordinal DESC LIMIT :limit" +
            ") ORDER BY ordinal ASC",
    )
    suspend fun list(conversationId: String, limit: Int): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationId " +
            "AND ordinal = :ordinal LIMIT 1",
    )
    suspend fun find(conversationId: String, ordinal: Long): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationId " +
            "AND ordinal > :afterOrdinal ORDER BY ordinal ASC LIMIT :limit",
    )
    suspend fun listAfterOrdinal(
        conversationId: String,
        afterOrdinal: Long,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationId " +
            "ORDER BY ordinal DESC LIMIT :limit",
    )
    suspend fun listNewestFirst(conversationId: String, limit: Int): List<MessageEntity>

    @Query(
        "SELECT * FROM (" +
            "SELECT * FROM messages WHERE conversation_id = :conversationId " +
            "ORDER BY ordinal DESC LIMIT :limit" +
            ") ORDER BY ordinal ASC",
    )
    fun observe(conversationId: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT COALESCE(MAX(ordinal), 0) FROM messages WHERE conversation_id = :conversationId")
    suspend fun highestOrdinal(conversationId: String): Long

    @Query("SELECT * FROM messages WHERE id LIKE :idPrefix || '%' ORDER BY id")
    suspend fun listByIdPrefix(idPrefix: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertForTransfer(message: MessageEntity): Long

    @Query(
        "DELETE FROM messages WHERE conversation_id = :conversationId AND ordinal <= :throughOrdinal",
    )
    suspend fun deleteThrough(conversationId: String, throughOrdinal: Long): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Long
}

@Dao
interface TurnOutcomeDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(outcome: TurnOutcomeEntity)

    @Update
    suspend fun update(outcome: TurnOutcomeEntity): Int

    @Query("SELECT * FROM turn_outcomes WHERE turn_id = :turnId LIMIT 1")
    suspend fun find(turnId: String): TurnOutcomeEntity?

    @Query("DELETE FROM turn_outcomes WHERE turn_id = :turnId")
    suspend fun delete(turnId: String): Int

    @Query("DELETE FROM turn_outcomes WHERE expires_at_epoch_millis <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReadExecution(execution: TurnReadExecutionEntity)

    @Query(
        "SELECT * FROM turn_read_executions WHERE turn_id = :turnId ORDER BY ordinal ASC",
    )
    suspend fun listReadExecutions(turnId: String): List<TurnReadExecutionEntity>

    @Query("DELETE FROM turn_read_executions WHERE turn_id = :turnId")
    suspend fun deleteReadExecutions(turnId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUnresolvedSideEffect(sideEffect: UnresolvedSideEffectEntity)

    @Query("SELECT * FROM unresolved_side_effects WHERE turn_id = :turnId LIMIT 1")
    suspend fun findUnresolvedSideEffect(turnId: String): UnresolvedSideEffectEntity?

    @Query(
        "SELECT * FROM unresolved_side_effects WHERE conversation_id = :conversationId " +
            "ORDER BY recorded_at_epoch_millis DESC, turn_id DESC LIMIT 1",
    )
    suspend fun latestUnresolvedSideEffect(conversationId: String): UnresolvedSideEffectEntity?

    @Query(
        "SELECT * FROM unresolved_side_effects " +
            "ORDER BY recorded_at_epoch_millis DESC, turn_id DESC LIMIT :limit",
    )
    suspend fun listUnresolvedSideEffects(limit: Int): List<UnresolvedSideEffectEntity>

    @Query(
        "UPDATE unresolved_side_effects SET state = 'WRITE_UNKNOWN', " +
            "recorded_at_epoch_millis = :recordedAt " +
            "WHERE turn_id = :turnId AND tool_name = :toolName AND tool_risk = :toolRisk",
    )
    suspend fun markUnresolvedSideEffectUnknown(
        turnId: String,
        toolName: String,
        toolRisk: TurnToolRisk,
        recordedAt: Long,
    ): Int

    @Query("DELETE FROM unresolved_side_effects WHERE turn_id = :turnId")
    suspend fun deleteUnresolvedSideEffect(turnId: String): Int

    @Query(
        "SELECT o.*, m.text AS user_request FROM turn_outcomes o " +
            "INNER JOIN messages m ON m.conversation_id = o.conversation_id " +
            "AND m.ordinal = COALESCE(o.recovery_source_user_message_ordinal, " +
            "o.user_message_ordinal) " +
            "WHERE o.conversation_id = :conversationId " +
            "AND o.recoverability = 'REQUERY_READ' AND o.expires_at_epoch_millis > :now " +
            "AND o.state IN ('STARTED', 'READ_EXECUTED', 'WRITE_PENDING', 'WRITE_UNKNOWN', " +
            "'FAILED', 'CANCELLED') " +
            "ORDER BY o.updated_at_epoch_millis DESC, o.turn_id DESC LIMIT 1",
    )
    suspend fun latestRecovery(conversationId: String, now: Long): TurnOutcomeWithRequest?

    @Query(
        "SELECT o.*, m.text AS user_request FROM turn_outcomes o " +
            "INNER JOIN messages m ON m.conversation_id = o.conversation_id " +
            "AND m.ordinal = COALESCE(o.recovery_source_user_message_ordinal, " +
            "o.user_message_ordinal) " +
            "WHERE o.turn_id = :turnId AND o.conversation_id = :conversationId " +
            "AND o.recoverability = 'REQUERY_READ' " +
            "AND o.state IN ('STARTED', 'READ_EXECUTED', 'WRITE_PENDING', 'WRITE_UNKNOWN', " +
            "'FAILED', 'CANCELLED') " +
            "AND o.expires_at_epoch_millis > :now LIMIT 1",
    )
    suspend fun recoveryByTurnId(
        turnId: String,
        conversationId: String,
        now: Long,
    ): TurnOutcomeWithRequest?

    @Query(
        "SELECT MIN(COALESCE(recovery_source_user_message_ordinal, user_message_ordinal)) " +
            "FROM turn_outcomes " +
        "WHERE conversation_id = :conversationId AND recoverability != 'NONE' " +
            "AND state IN ('STARTED', 'READ_EXECUTED', 'WRITE_PENDING', 'WRITE_UNKNOWN', " +
            "'FAILED', 'CANCELLED') " +
            "AND expires_at_epoch_millis > :now",
    )
    suspend fun oldestRecoverableUserOrdinal(conversationId: String, now: Long): Long?
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY created_at_epoch_millis, id")
    suspend fun listAllForTransfer(): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY updated_at_epoch_millis DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<MemoryEntity>

    @Query(
        "SELECT * FROM memories WHERE " +
            "(valid_until_epoch_millis IS NULL OR valid_until_epoch_millis > :now) AND " +
            "last_confirmed_at_epoch_millis >= :confirmedAtOrAfter AND " +
            "category IN (:categories) AND " +
            "id NOT IN (SELECT supersedes_id FROM memories WHERE supersedes_id IS NOT NULL) " +
            "ORDER BY updated_at_epoch_millis DESC LIMIT :limit",
    )
    suspend fun listRecallCandidates(
        now: Long,
        confirmedAtOrAfter: Long,
        categories: Set<MemoryCategory>,
        limit: Int,
    ): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :memoryId LIMIT 1")
    suspend fun findById(memoryId: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE normalized_content = :normalizedContent LIMIT 1")
    suspend fun findByNormalizedContent(normalizedContent: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(memory: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertForTransfer(memory: MemoryEntity): Long

    @androidx.room.Update
    suspend fun update(memory: MemoryEntity)

    @Query(
        "UPDATE memories SET last_confirmed_at_epoch_millis = :confirmedAt, " +
            "updated_at_epoch_millis = :confirmedAt WHERE id = :memoryId",
    )
    suspend fun confirm(memoryId: String, confirmedAt: Long): Int

    @Query("DELETE FROM memories WHERE id = :memoryId")
    suspend fun delete(memoryId: String): Int

    @Query("DELETE FROM memories")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Long
}

@Dao
interface CapturedNotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notification: CapturedNotificationEntity)

    @Query(
        "SELECT * FROM captured_notifications WHERE package_name IN (:packageNames) " +
            "AND posted_at_epoch_millis >= :postedAtOrAfter " +
            "AND (conversation_title LIKE :pattern ESCAPE '\\' " +
            "OR sender LIKE :pattern ESCAPE '\\' OR text LIKE :pattern ESCAPE '\\') " +
            "ORDER BY posted_at_epoch_millis DESC LIMIT :limit",
    )
    suspend fun search(
        packageNames: List<String>,
        pattern: String,
        postedAtOrAfter: Long,
        limit: Int,
    ): List<CapturedNotificationEntity>

    @Query(
        "SELECT * FROM captured_notifications WHERE package_name IN (:packageNames) " +
            "AND posted_at_epoch_millis >= :postedAtOrAfter " +
            "ORDER BY posted_at_epoch_millis DESC LIMIT :limit",
    )
    suspend fun recent(
        packageNames: List<String>,
        postedAtOrAfter: Long,
        limit: Int,
    ): List<CapturedNotificationEntity>

    @Query("DELETE FROM captured_notifications WHERE posted_at_epoch_millis < :postedBefore")
    suspend fun deleteOlderThan(postedBefore: Long): Int

    /** Keeps only the newest [keep] rows so an unattended listener cannot grow without bound. */
    @Query(
        "DELETE FROM captured_notifications WHERE id NOT IN (" +
            "SELECT id FROM captured_notifications ORDER BY posted_at_epoch_millis DESC LIMIT :keep" +
            ")",
    )
    suspend fun trimTo(keep: Int): Int

    @Query("DELETE FROM captured_notifications WHERE source_key = :sourceKey")
    suspend fun deleteBySourceKey(sourceKey: String): Int

    @Query("DELETE FROM captured_notifications")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM captured_notifications")
    suspend fun count(): Long
}

@Dao
interface CommitmentProposalDao {
    @Query("SELECT * FROM commitment_proposals ORDER BY created_at_epoch_millis, id")
    suspend fun listAllForTransfer(): List<CommitmentProposalEntity>

    @Query("SELECT * FROM commitment_proposals WHERE id = :id LIMIT 1")
    suspend fun find(id: String): CommitmentProposalEntity?

    @Query("SELECT * FROM commitment_proposals WHERE source_ref_hash = :sourceRefHash LIMIT 1")
    suspend fun findBySourceRefHash(sourceRefHash: String): CommitmentProposalEntity?

    @Query(
        "SELECT * FROM commitment_proposals WHERE status = 'PENDING' " +
            "ORDER BY created_at_epoch_millis DESC LIMIT :limit",
    )
    suspend fun listPending(limit: Int): List<CommitmentProposalEntity>

    @Query(
        "SELECT source_ref_hash, created_at_epoch_millis FROM commitment_proposals " +
            "WHERE created_at_epoch_millis >= :createdAtOrAfter " +
            "AND created_at_epoch_millis <= :createdAtOrBefore " +
            "ORDER BY created_at_epoch_millis DESC, id DESC " +
            "LIMIT CASE WHEN :limit < 1 THEN 1 WHEN :limit > 2200 THEN 2200 ELSE :limit END",
    )
    suspend fun listRecentHistory(
        createdAtOrAfter: Long,
        createdAtOrBefore: Long,
        limit: Int,
    ): List<CommitmentProposalHistoryRecord>

    @Query("SELECT COUNT(*) FROM commitment_proposals WHERE status = 'PENDING'")
    suspend fun countPending(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(proposal: CommitmentProposalEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertForTransfer(proposal: CommitmentProposalEntity): Long

    @Query(
        "UPDATE commitment_proposals SET status = :nextStatus, " +
            "promoted_reminder_id = :reminderId, updated_at_epoch_millis = :updatedAt " +
            "WHERE id = :id AND status = :expectedStatus",
    )
    suspend fun transition(
        id: String,
        expectedStatus: ProposalStatus,
        nextStatus: ProposalStatus,
        reminderId: String?,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE commitment_proposals SET status = 'DISMISSED', " +
            "updated_at_epoch_millis = :updatedAt WHERE status = 'PENDING' " +
            "AND created_at_epoch_millis < :createdBefore",
    )
    suspend fun dismissOlderThan(createdBefore: Long, updatedAt: Long): Int
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY created_at_epoch_millis, id")
    suspend fun listAllForTransfer(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun find(id: String): ReminderEntity?

    @Query(
        "SELECT * FROM reminders WHERE source_ref_hash = :sourceRefHash " +
            "ORDER BY created_at_epoch_millis DESC LIMIT 1",
    )
    suspend fun findBySourceRefHash(sourceRefHash: String): ReminderEntity?

    @Query(
        "SELECT * FROM reminders WHERE state = 'ACTIVE' " +
            "ORDER BY COALESCE(snooze_until_epoch_millis, trigger_at_epoch_millis), id",
    )
    suspend fun listActive(): List<ReminderEntity>

    @Query(
        "SELECT * FROM reminders WHERE state = 'ACTIVE' " +
            "ORDER BY COALESCE(snooze_until_epoch_millis, trigger_at_epoch_millis), id LIMIT :limit",
    )
    suspend fun listUpcoming(limit: Int): List<ReminderEntity>

    @Query("SELECT COUNT(*) FROM reminders WHERE state = 'ACTIVE'")
    suspend fun countActive(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(reminder: ReminderEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertForTransfer(reminder: ReminderEntity): Long

    @Update
    suspend fun update(reminder: ReminderEntity): Int

    @Query(
        "UPDATE reminders SET schedule_state = :state, last_scheduled_at_epoch_millis = :scheduledAt, " +
            "updated_at_epoch_millis = :scheduledAt WHERE id = :id AND state = 'ACTIVE' " +
            "AND schedule_version = :scheduleVersion",
    )
    suspend fun markScheduled(
        id: String,
        scheduleVersion: Long,
        state: ReminderScheduleState,
        scheduledAt: Long,
    ): Int

    @Query(
        "UPDATE reminders SET last_delivered_at_epoch_millis = :deliveredAt, " +
            "updated_at_epoch_millis = :deliveredAt WHERE id = :id AND state = 'ACTIVE' " +
            "AND schedule_version = :scheduleVersion",
    )
    suspend fun markDelivered(id: String, scheduleVersion: Long, deliveredAt: Long): Int
}

@Dao
interface ReminderDeliveryDao {
    @Query("SELECT * FROM reminder_deliveries ORDER BY recorded_at_epoch_millis, id")
    suspend fun listAllForTransfer(): List<ReminderDeliveryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(delivery: ReminderDeliveryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertForTransfer(delivery: ReminderDeliveryEntity): Long

    @Query(
        "SELECT * FROM reminder_deliveries WHERE reminder_id = :reminderId " +
            "ORDER BY recorded_at_epoch_millis DESC LIMIT :limit",
    )
    suspend fun listForReminder(reminderId: String, limit: Int): List<ReminderDeliveryEntity>

    @Query(
        "SELECT COUNT(DISTINCT d.reminder_id) FROM reminder_deliveries d " +
            "INNER JOIN reminders r ON r.id = d.reminder_id " +
            "WHERE r.state = 'ACTIVE' " +
            "AND d.outcome IN ('SNOOZED_FROM_NOTIFICATION', 'SNOOZED_FROM_APP') " +
            "AND d.recorded_at_epoch_millis >= :startEpochMillis " +
            "AND d.recorded_at_epoch_millis < :endEpochMillis",
    )
    suspend fun countActiveSnoozedBetween(startEpochMillis: Long, endEpochMillis: Long): Int
}

/** Single source of truth consumed by Room and the deterministic release-provenance gate. */
internal const val PERSONAL_EDGE_DATABASE_VERSION = 10

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        CapturedNotificationEntity::class,
        ReminderEntity::class,
        ReminderDeliveryEntity::class,
        CommitmentProposalEntity::class,
        TurnOutcomeEntity::class,
        TurnReadExecutionEntity::class,
        UnresolvedSideEffectEntity::class,
        AgentPlanCheckpointEntity::class,
        AgentPlanStepCheckpointEntity::class,
    ],
    version = PERSONAL_EDGE_DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(
    MessageRoleConverter::class,
    MemoryEnumConverters::class,
    ProposalEnumConverters::class,
    ReminderEnumConverters::class,
    TurnOutcomeConverters::class,
)
abstract class PersonalEdgeDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun turnOutcomeDao(): TurnOutcomeDao

    abstract fun agentPlanCheckpointDao(): AgentPlanCheckpointDao

    abstract fun memoryDao(): MemoryDao

    abstract fun capturedNotificationDao(): CapturedNotificationDao

    abstract fun commitmentProposalDao(): CommitmentProposalDao

    abstract fun reminderDao(): ReminderDao

    abstract fun reminderDeliveryDao(): ReminderDeliveryDao

    companion object {
        const val DATABASE_NAME = "personal-edge.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memories` (" +
                        "`id` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`normalized_content` TEXT NOT NULL, " +
                        "`created_at_epoch_millis` INTEGER NOT NULL, " +
                        "`updated_at_epoch_millis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memories_normalized_content` " +
                        "ON `memories` (`normalized_content`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminders` (" +
                        "`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`trigger_at_epoch_millis` INTEGER NOT NULL, `zone_id` TEXT NOT NULL, " +
                        "`recurrence_rule` TEXT, `state` TEXT NOT NULL, `precision` TEXT NOT NULL, " +
                        "`schedule_state` TEXT NOT NULL, `schedule_version` INTEGER NOT NULL, " +
                        "`snooze_until_epoch_millis` INTEGER, `lead_time_minutes` INTEGER, " +
                        "`escalation_policy` TEXT, `source_type` TEXT NOT NULL, " +
                        "`source_ref_hash` TEXT, `created_by` TEXT NOT NULL, " +
                        "`confirmation_digest` TEXT NOT NULL, `last_scheduled_at_epoch_millis` INTEGER, " +
                        "`last_delivered_at_epoch_millis` INTEGER, `completed_at_epoch_millis` INTEGER, " +
                        "`created_at_epoch_millis` INTEGER NOT NULL, `updated_at_epoch_millis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reminders_state_trigger_at_epoch_millis` " +
                        "ON `reminders` (`state`, `trigger_at_epoch_millis`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reminders_schedule_state` " +
                        "ON `reminders` (`schedule_state`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminder_deliveries` (" +
                        "`id` TEXT NOT NULL, `reminder_id` TEXT NOT NULL, " +
                        "`schedule_version` INTEGER NOT NULL, `scheduled_for_epoch_millis` INTEGER NOT NULL, " +
                        "`outcome` TEXT NOT NULL, `recorded_at_epoch_millis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), FOREIGN KEY(`reminder_id`) REFERENCES `reminders`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reminder_deliveries_reminder_id_recorded_at_epoch_millis` " +
                        "ON `reminder_deliveries` (`reminder_id`, `recorded_at_epoch_millis`)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memories` ADD COLUMN `category` TEXT NOT NULL DEFAULT 'FACT'",
                )
                db.execSQL(
                    "ALTER TABLE `memories` ADD COLUMN `valid_until_epoch_millis` INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE `memories` ADD COLUMN `last_confirmed_at_epoch_millis` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `memories` ADD COLUMN `supersedes_id` TEXT",
                )
                db.execSQL(
                    "UPDATE `memories` SET `last_confirmed_at_epoch_millis` = " +
                        "`updated_at_epoch_millis` WHERE `last_confirmed_at_epoch_millis` = 0",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memories_supersedes_id` " +
                        "ON `memories` (`supersedes_id`)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `commitment_proposals` (" +
                        "`id` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
                        "`proposed_due_at_epoch_millis` INTEGER, `zone_id` TEXT, " +
                        "`source_package` TEXT, `source_ref_hash` TEXT NOT NULL, " +
                        "`confidence` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "`promoted_reminder_id` TEXT, `created_at_epoch_millis` INTEGER NOT NULL, " +
                        "`updated_at_epoch_millis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_commitment_proposals_status_created_at_epoch_millis` " +
                        "ON `commitment_proposals` (`status`, `created_at_epoch_millis`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_commitment_proposals_source_ref_hash` " +
                        "ON `commitment_proposals` (`source_ref_hash`)",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `turn_outcomes` (" +
                        "`turn_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, " +
                        "`user_message_ordinal` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                        "`recoverability` TEXT NOT NULL, `failure_code` TEXT, " +
                        "`last_tool_name` TEXT, `last_tool_risk` TEXT NOT NULL, " +
                        "`created_at_epoch_millis` INTEGER NOT NULL, " +
                        "`updated_at_epoch_millis` INTEGER NOT NULL, " +
                        "`expires_at_epoch_millis` INTEGER NOT NULL, PRIMARY KEY(`turn_id`), " +
                        "FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_turn_outcomes_conversation_id_user_message_ordinal` " +
                        "ON `turn_outcomes` (`conversation_id`, `user_message_ordinal`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_turn_outcomes_conversation_id_recoverability_updated_at_epoch_millis` " +
                        "ON `turn_outcomes` " +
                        "(`conversation_id`, `recoverability`, `updated_at_epoch_millis`)",
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_plan_checkpoints` (" +
                        "`plan_id` TEXT NOT NULL, `schema_version` INTEGER NOT NULL, " +
                        "`plan_digest` TEXT NOT NULL, `revision` INTEGER NOT NULL, " +
                        "`execution_state` TEXT NOT NULL, " +
                        "`updated_at_epoch_millis` INTEGER NOT NULL, PRIMARY KEY(`plan_id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_plan_step_checkpoints` (" +
                        "`plan_id` TEXT NOT NULL, `step_ordinal` INTEGER NOT NULL, " +
                        "`tool_id` TEXT NOT NULL, `argument_digest` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, `outcome` TEXT NOT NULL, " +
                        "`updated_at_epoch_millis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`plan_id`, `step_ordinal`), " +
                        "FOREIGN KEY(`plan_id`) REFERENCES `agent_plan_checkpoints`(`plan_id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_plan_step_checkpoints_plan_id` " +
                        "ON `agent_plan_step_checkpoints` (`plan_id`)",
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `turn_read_executions` (" +
                        "`turn_id` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, " +
                        "`tool_name` TEXT NOT NULL, PRIMARY KEY(`turn_id`, `ordinal`), " +
                        "FOREIGN KEY(`turn_id`) REFERENCES `turn_outcomes`(`turn_id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_turn_read_executions_turn_id` " +
                        "ON `turn_read_executions` (`turn_id`)",
                )
                // v7 could identify only the latest Tool. Preserve that identity as the first
                // ordered read only when every closed-format validation succeeds.
                db.execSQL(
                    "INSERT OR IGNORE INTO `turn_read_executions` " +
                        "(`turn_id`, `ordinal`, `tool_name`) " +
                        "SELECT `turn_id`, 1, `last_tool_name` FROM `turn_outcomes` " +
                        "WHERE `last_tool_risk` = 'READ_ONLY' " +
                        "AND `last_tool_name` IS NOT NULL " +
                        "AND length(`last_tool_name`) BETWEEN 1 AND 64 " +
                        "AND substr(`last_tool_name`, 1, 1) GLOB '[a-z]' " +
                        "AND `last_tool_name` NOT GLOB '*[^a-z0-9_]*'",
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `unresolved_side_effects` (" +
                        "`turn_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, `tool_name` TEXT NOT NULL, " +
                        "`tool_risk` TEXT NOT NULL, `recorded_at_epoch_millis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`turn_id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_unresolved_side_effects_conversation_id_recorded_at_epoch_millis` " +
                        "ON `unresolved_side_effects` " +
                        "(`conversation_id`, `recorded_at_epoch_millis`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_unresolved_side_effects_recorded_at_epoch_millis` " +
                        "ON `unresolved_side_effects` (`recorded_at_epoch_millis`)",
                )
                // Explicit dismissal in v8 deleted the outcome, and a definitive refusal cleared
                // its Tool identity. Every remaining side-effect identity is therefore unresolved,
                // including an ANSWER_COMPLETE turn that used to hide VERIFY recovery.
                db.execSQL(
                    "INSERT OR IGNORE INTO `unresolved_side_effects` " +
                        "(`turn_id`, `conversation_id`, `state`, `tool_name`, `tool_risk`, " +
                        "`recorded_at_epoch_millis`) " +
                        "SELECT `turn_id`, `conversation_id`, " +
                        "CASE WHEN `state` = 'WRITE_PENDING' THEN 'WRITE_PENDING' " +
                        "ELSE 'WRITE_UNKNOWN' END, " +
                        "CASE WHEN length(`last_tool_name`) BETWEEN 1 AND 64 " +
                        "AND substr(`last_tool_name`, 1, 1) GLOB '[a-z]' " +
                        "AND `last_tool_name` NOT GLOB '*[^a-z0-9_]*' " +
                        "THEN `last_tool_name` ELSE 'unknown_side_effect' END, " +
                        "`last_tool_risk`, " +
                        "`updated_at_epoch_millis` FROM `turn_outcomes` " +
                        "WHERE `state` IN ('WRITE_PENDING', 'WRITE_UNKNOWN', 'ANSWER_COMPLETE', " +
                        "'FAILED', 'CANCELLED') " +
                        "AND `last_tool_risk` IN " +
                        "('LOCAL_WRITE', 'DATA_WRITE', 'COMMUNICATION', 'HIGH_RISK') " +
                        "AND `last_tool_name` IS NOT NULL " +
                        "AND length(`conversation_id`) BETWEEN 1 AND 128",
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Content-free pointer to an earlier owner-authored USER row. Existing outcomes
                // keep their current USER row as the recovery source through COALESCE queries.
                db.execSQL(
                    "ALTER TABLE `turn_outcomes` ADD COLUMN " +
                        "`recovery_source_user_message_ordinal` INTEGER",
                )
            }
        }

        /**
         * Opens the single application database.
         *
         * It deliberately lives in `noBackupFilesDir`: conversation transcripts and captured
         * notification text must not leave the device through cloud backup or device transfer.
         * The action ledger is a separate database owned by `core:tools`, so clearing history
         * here can never erase replay protection.
         */
        fun open(context: Context): PersonalEdgeDatabase {
            val applicationContext = context.applicationContext
            val databaseFile = File(applicationContext.noBackupFilesDir, DATABASE_NAME)
            return Room
                .databaseBuilder(applicationContext, PersonalEdgeDatabase::class.java, databaseFile.absolutePath)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }
}
