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
import java.io.File
import kotlinx.coroutines.flow.Flow

class MessageRoleConverter {
    @TypeConverter
    fun toStored(role: MessageRole): String = role.name

    /** An unknown stored value means a downgrade or corruption, not something to guess at. */
    @TypeConverter
    fun fromStored(stored: String): MessageRole = MessageRole.valueOf(stored)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updated_at_epoch_millis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun find(conversationId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(conversation: ConversationEntity)

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
    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationId " +
            "ORDER BY ordinal ASC LIMIT :limit",
    )
    suspend fun list(conversationId: String, limit: Int): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationId " +
            "ORDER BY ordinal DESC LIMIT :limit",
    )
    suspend fun listNewestFirst(conversationId: String, limit: Int): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationId " +
            "ORDER BY ordinal ASC LIMIT :limit",
    )
    fun observe(conversationId: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT COALESCE(MAX(ordinal), 0) FROM messages WHERE conversation_id = :conversationId")
    suspend fun highestOrdinal(conversationId: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageEntity)

    @Query(
        "DELETE FROM messages WHERE conversation_id = :conversationId AND ordinal <= :throughOrdinal",
    )
    suspend fun deleteThrough(conversationId: String, throughOrdinal: Long): Int

    @Query("SELECT COUNT(*) FROM messages")
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

    @Query("DELETE FROM captured_notifications")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM captured_notifications")
    suspend fun count(): Long
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        CapturedNotificationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(MessageRoleConverter::class)
abstract class PersonalEdgeDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun capturedNotificationDao(): CapturedNotificationDao

    companion object {
        const val DATABASE_NAME = "personal-edge.db"

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
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }
}
