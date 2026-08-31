package com.personaledge.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One top-level chat thread.
 *
 * [summary] holds a bounded, model-produced recap used to seed later turns. Raw model thinking is
 * never written here, and the turn transcript itself lives in [MessageEntity].
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "summary")
    val summary: String? = null,
    @ColumnInfo(name = "summarized_through_message_ordinal")
    val summarizedThroughMessageOrdinal: Long = 0,
)

enum class MessageRole {
    USER,
    ASSISTANT,

    /** A trusted, app-authored record of a tool execution. Never model-controlled text. */
    TOOL_RECEIPT,
}

/**
 * [ordinal] is assigned by the repository, not the clock, so ordering survives a device clock
 * change. Deleting a conversation cascades here, which is what the delete-everything path relies on.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversation_id", "ordinal"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "ordinal")
    val ordinal: Long,
    @ColumnInfo(name = "role")
    val role: MessageRole,
    @ColumnInfo(name = "text")
    val text: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

enum class MemoryCategory {
    PREFERENCE,
    PERSON,
    PLACE,
    ROUTINE,
    FACT,
}

/**
 * One user-approved fact that can be recalled across chat threads.
 *
 * Memories are deliberately independent from [ConversationEntity]: deleting a transcript must
 * not silently delete a preference the user chose to keep. [normalizedContent] is a repository-
 * produced deduplication key and is never shown to the model or user.
 */
@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["normalized_content"], unique = true),
        Index(value = ["supersedes_id"]),
    ],
)
data class MemoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "normalized_content")
    val normalizedContent: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "category")
    val category: MemoryCategory = MemoryCategory.FACT,
    @ColumnInfo(name = "valid_until_epoch_millis")
    val validUntilEpochMillis: Long? = null,
    @ColumnInfo(name = "last_confirmed_at_epoch_millis")
    val lastConfirmedAtEpochMillis: Long = updatedAtEpochMillis,
    @ColumnInfo(name = "supersedes_id")
    val supersedesId: String? = null,
)

/**
 * A notification observed by the listener service, stored so it can be searched later.
 *
 * [sourceKey] is the platform notification key. It is unique so a repeated post of the same
 * notification updates one row instead of accumulating duplicates.
 */
@Entity(
    tableName = "captured_notifications",
    indices = [
        Index(value = ["source_key"], unique = true),
        Index(value = ["posted_at_epoch_millis"]),
        Index(value = ["package_name"]),
    ],
)
data class CapturedNotificationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "conversation_title")
    val conversationTitle: String,
    @ColumnInfo(name = "sender")
    val sender: String,
    @ColumnInfo(name = "text")
    val text: String,
    @ColumnInfo(name = "posted_at_epoch_millis")
    val postedAtEpochMillis: Long,
)
