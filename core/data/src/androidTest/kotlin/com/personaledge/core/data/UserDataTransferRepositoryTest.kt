package com.personaledge.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class UserDataTransferRepositoryTest {
    private val passphrase = "fixture transfer passphrase"
    private lateinit var source: PersonalEdgeDatabase
    private lateinit var destination: PersonalEdgeDatabase

    @Before
    fun openDatabases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        source = Room.inMemoryDatabaseBuilder(context, PersonalEdgeDatabase::class.java).build()
        destination = Room.inMemoryDatabaseBuilder(context, PersonalEdgeDatabase::class.java).build()
    }

    @After
    fun closeDatabases() {
        source.close()
        destination.close()
    }

    @Test
    fun selectedRowsMergeWithoutOverwritingAndExcludedNotificationTextNeverAppears() = runBlocking {
        source.memoryDao().insert(
            MemoryEntity(
                "memory", "사용자는 민트색을 좋아한다.", "사용자는 민트색을 좋아한다.",
                1, 1, MemoryCategory.PREFERENCE, null, 1, null,
            ),
        )
        source.conversationDao().insert(ConversationEntity("conversation", "대화", 1, 1))
        source.messageDao().insert(MessageEntity("message", "conversation", 1, MessageRole.USER, "비공개 대화", 1))
        source.capturedNotificationDao().upsert(
            CapturedNotificationEntity(
                "notification", "a".repeat(64), "com.kakao.talk", "방", "상대", "제외할 원문", 1,
            ),
        )
        val snapshot = UserDataTransferRepository(source).snapshot(
            selection = UserDataSelection(
                conversations = false,
                memories = true,
                reminders = false,
                proposals = false,
            ),
            calendarRemapRequired = true,
            defaultCalendarLabelHint = "개인",
        )

        assertTrue(snapshot.conversations.isEmpty())
        assertTrue(snapshot.messages.isEmpty())
        assertEquals(1, snapshot.memories.size)
        assertTrue(snapshot.toString().contains("제외할 원문").not())

        val repository = UserDataTransferRepository(destination)
        val first = repository.import(snapshot)
        val second = repository.import(snapshot)

        assertEquals(1, first.importedMemories)
        assertEquals(0, second.importedMemories)
        assertEquals(1, second.skippedRows)
        assertEquals("사용자는 민트색을 좋아한다.", destination.memoryDao().listRecent(10).single().content)
        assertEquals(0L, destination.capturedNotificationDao().count())
    }

    @Test
    fun contentFreeAttachmentSummarySurvivesSnapshotArchiveAndImport() = runBlocking {
        val conversation = ConversationEntity("media-conversation", "미디어 대화", 1, 2)
        source.conversationDao().insert(conversation)
        source.messageDao().insert(
            MessageEntity(
                id = "media-message",
                conversationId = conversation.id,
                ordinal = 1,
                role = MessageRole.USER,
                text = "",
                createdAtEpochMillis = 2,
                attachmentSummary = "IMAGE:CAMERA",
            ),
        )
        val selection = UserDataSelection(
            conversations = true,
            memories = false,
            reminders = false,
            proposals = false,
        )
        val snapshot = UserDataTransferRepository(source).snapshot(
            selection = selection,
            calendarRemapRequired = false,
            defaultCalendarLabelHint = null,
        )
        val archive = EncryptedUserDataArchive.encrypt(snapshot, passphrase)
        val decoded = EncryptedUserDataArchive.decrypt(archive, passphrase)
            as UserDataArchiveReadResult.Ready

        val result = UserDataTransferRepository(destination).import(decoded.snapshot)

        assertEquals(1, result.importedMessages)
        assertEquals(
            "IMAGE:CAMERA",
            destination.messageDao().listAllForTransfer().single().attachmentSummary,
        )
    }

    @Test
    fun invalidMessageGraphIsRejectedBeforeAnyConversationRowIsImported() = runBlocking {
        val conversation = ConversationEntity("conversation", "대화", 1, 1)
        val snapshot = UserDataSnapshot(
            selection = UserDataSelection(
                conversations = true,
                memories = false,
                reminders = false,
                proposals = false,
            ),
            conversations = listOf(conversation),
            messages = listOf(
                MessageEntity("message-1", conversation.id, 1, MessageRole.USER, "첫 메시지", 1),
                MessageEntity("message-3", conversation.id, 3, MessageRole.ASSISTANT, "셋째 메시지", 2),
            ),
            memories = emptyList(),
            reminders = emptyList(),
            deliveries = emptyList(),
            proposals = emptyList(),
            calendarRemapRequired = false,
            defaultCalendarLabelHint = null,
        )

        val failure = runCatching { UserDataTransferRepository(destination).import(snapshot) }

        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertTrue(destination.conversationDao().listAllForTransfer().isEmpty())
        assertEquals(0L, destination.messageDao().count())
    }

    @Test
    fun overdueImportedRemindersDoNotImmediatelyFireAndRecurringOnesAdvance() = runBlocking {
        val now = Instant.parse("2026-08-23T03:00:00Z").toEpochMilli()
        val oldTrigger = Instant.parse("2026-08-20T09:00:00Z").toEpochMilli()
        val snapshot = UserDataSnapshot(
            selection = UserDataSelection(
                conversations = false,
                memories = false,
                reminders = true,
                proposals = false,
            ),
            conversations = emptyList(),
            messages = emptyList(),
            memories = emptyList(),
            reminders = listOf(
                reminder(id = "one-shot", trigger = oldTrigger, recurrence = null),
                reminder(id = "daily", trigger = oldTrigger, recurrence = "daily"),
            ),
            deliveries = emptyList(),
            proposals = emptyList(),
            calendarRemapRequired = false,
            defaultCalendarLabelHint = null,
        )

        val result = UserDataTransferRepository(destination, clock = { now }).import(snapshot)
        val imported = destination.reminderDao().listAllForTransfer().associateBy(ReminderEntity::id)

        assertEquals(2, result.importedReminders)
        assertEquals(ReminderScheduleState.DELIVERED, imported.getValue("one-shot").scheduleState)
        assertTrue(imported.getValue("one-shot").triggerAtEpochMillis < now)
        assertEquals(ReminderScheduleState.PENDING, imported.getValue("daily").scheduleState)
        assertTrue(imported.getValue("daily").triggerAtEpochMillis > now)
        assertEquals(8L, imported.getValue("daily").scheduleVersion)
    }

    private fun reminder(id: String, trigger: Long, recurrence: String?): ReminderEntity =
        ReminderEntity(
            id = id,
            title = "가져온 리마인더",
            triggerAtEpochMillis = trigger,
            zoneId = "UTC",
            recurrenceRule = recurrence,
            state = ReminderState.ACTIVE,
            precision = ReminderPrecision.FLEXIBLE,
            scheduleState = ReminderScheduleState.SCHEDULED_INEXACT,
            scheduleVersion = 7,
            snoozeUntilEpochMillis = null,
            leadTimeMinutes = null,
            escalationPolicy = null,
            sourceType = ReminderSourceType.DIRECT,
            sourceRefHash = null,
            createdBy = ReminderCreator.USER,
            confirmationDigest = "a".repeat(64),
            lastScheduledAtEpochMillis = trigger - 1_000,
            lastDeliveredAtEpochMillis = null,
            completedAtEpochMillis = null,
            createdAtEpochMillis = trigger - 10_000,
            updatedAtEpochMillis = trigger - 5_000,
        )
}
