package com.personaledge.core.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDataArchiveTest {
    private val passphrase = "correct horse battery staple"

    @Test
    fun `selected data round trips with schema hash and calendar remap metadata`() {
        val snapshot = sampleSnapshot()

        val archive = EncryptedUserDataArchive.encrypt(snapshot, passphrase)
        val result = EncryptedUserDataArchive.decrypt(archive, passphrase)
            as UserDataArchiveReadResult.Ready

        assertEquals(snapshot, result.snapshot)
        assertEquals(1, EncryptedUserDataArchive.preview(result.snapshot).messageCount)
        assertTrue(result.snapshot.calendarRemapRequired)
    }

    @Test
    fun `wrong passphrase and ciphertext tampering fail authentication`() {
        val archive = EncryptedUserDataArchive.encrypt(sampleSnapshot(), passphrase)

        assertEquals(
            UserDataArchiveFailure.AUTHENTICATION_FAILED,
            (EncryptedUserDataArchive.decrypt(archive, "wrong password long enough") as
                UserDataArchiveReadResult.Failed).failure,
        )
        val tampered = archive.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last() + 1).toByte() }
        assertEquals(
            UserDataArchiveFailure.AUTHENTICATION_FAILED,
            (EncryptedUserDataArchive.decrypt(tampered, passphrase) as
                UserDataArchiveReadResult.Failed).failure,
        )
    }

    @Test
    fun `short passphrase and unknown envelope are rejected closed`() {
        assertEquals(
            UserDataArchiveFailure.INVALID_PASSPHRASE,
            (EncryptedUserDataArchive.decrypt(ByteArray(100), "too short") as
                UserDataArchiveReadResult.Failed).failure,
        )
        assertEquals(
            UserDataArchiveFailure.INVALID_FORMAT,
            (EncryptedUserDataArchive.decrypt(ByteArray(128), passphrase) as
                UserDataArchiveReadResult.Failed).failure,
        )
    }

    @Test
    fun `archive and schema versions reject unsupported and Long MAX encodings`() {
        val archive = EncryptedUserDataArchive.encrypt(sampleSnapshot(), passphrase)

        listOf(0, 2, Int.MAX_VALUE).forEach { version ->
            assertArchiveFailure(
                archive.copyOf().putInt(ARCHIVE_VERSION_OFFSET, version),
                UserDataArchiveFailure.UNSUPPORTED_VERSION,
            )
        }
        listOf(0, 4, 6, Int.MAX_VALUE).forEach { version ->
            assertArchiveFailure(
                archive.copyOf().putInt(SCHEMA_VERSION_OFFSET, version),
                UserDataArchiveFailure.SCHEMA_MISMATCH,
            )
        }

        assertArchiveFailure(
            archive.copyOf().putLong(ARCHIVE_VERSION_OFFSET, Long.MAX_VALUE),
            UserDataArchiveFailure.UNSUPPORTED_VERSION,
        )
        // The fixed v1 envelope stores two adjacent 32-bit words. An eight-byte schema injection
        // also corrupts the authenticated iteration word and must be rejected before allocation.
        assertArchiveFailure(
            archive.copyOf().putLong(SCHEMA_VERSION_OFFSET, Long.MAX_VALUE),
            UserDataArchiveFailure.INVALID_FORMAT,
        )
    }

    @Test
    fun `declared archive lengths cannot exceed or disagree with available bytes`() {
        val archive = EncryptedUserDataArchive.encrypt(sampleSnapshot(), passphrase)

        assertArchiveFailure(
            archive.copyOf().putInt(PLAINTEXT_LENGTH_OFFSET, Int.MAX_VALUE),
            UserDataArchiveFailure.INVALID_FORMAT,
        )
        assertArchiveFailure(
            archive.copyOf().putInt(CIPHER_LENGTH_OFFSET, Int.MAX_VALUE),
            UserDataArchiveFailure.INVALID_FORMAT,
        )
        val actualCipherLength = archive.size - CIPHER_TEXT_OFFSET
        assertArchiveFailure(
            archive.copyOf().putInt(CIPHER_LENGTH_OFFSET, actualCipherLength - 1),
            UserDataArchiveFailure.INVALID_FORMAT,
        )
    }

    @Test
    fun `payload version booleans counts and string sizes fail closed before allocation`() {
        assertPayloadRejected(payload { writeLong(Long.MAX_VALUE) })
        assertPayloadRejected(payload {
            writeInt(1)
            writeByte(2)
        })
        assertPayloadRejected(payloadWithPrelude { writeLong(Long.MAX_VALUE) })
        assertPayloadRejected(payloadWithPrelude { writeInt(-1) })
        assertPayloadRejected(payloadWithPrelude {
            writeInt(UserDataTransferLimits.MAX_CONVERSATIONS)
        })
        assertPayloadRejected(payloadWithPrelude {
            writeInt(1)
            writeInt(32)
        })
        assertPayloadRejected(payloadWithPrelude {
            writeInt(1)
            writeInt(Int.MAX_VALUE)
        })
    }

    @Test
    fun `message ordinals are positive unique contiguous and scoped to their conversation`() {
        val snapshot = sampleSnapshot()
        val conversation = snapshot.conversations.single()
        val message = snapshot.messages.single()

        assertRejected(snapshot.copy(messages = listOf(message.copy(ordinal = 0))))
        assertRejected(snapshot.copy(messages = listOf(message.copy(ordinal = Long.MAX_VALUE))))
        assertRejected(
            snapshot.copy(
                messages = listOf(
                    message.copy(id = "message-1", ordinal = 1),
                    message.copy(id = "message-2", ordinal = 1),
                ),
            ),
        )
        assertRejected(
            snapshot.copy(
                messages = listOf(
                    message.copy(id = "message-1", ordinal = 1),
                    message.copy(id = "message-3", ordinal = 3),
                ),
            ),
        )
        assertRejected(snapshot.copy(messages = listOf(message.copy(conversationId = "missing"))))

        val otherConversation = conversation.copy(id = "conversation-2")
        assertTrue(
            UserDataSnapshotValidator.isSafeForImport(
                snapshot.copy(
                    conversations = listOf(conversation, otherConversation),
                    messages = listOf(
                        message.copy(id = "message-1", conversationId = conversation.id, ordinal = 1),
                        message.copy(id = "message-2", conversationId = otherConversation.id, ordinal = 1),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `summary boundary cannot exceed messages and may cover only an adjacent deleted prefix`() {
        val snapshot = sampleSnapshot()
        val conversation = snapshot.conversations.single()
        val message = snapshot.messages.single()

        assertRejected(
            snapshot.copy(
                conversations = listOf(conversation.copy(summarizedThroughMessageOrdinal = 2)),
            ),
        )
        assertRejected(
            snapshot.copy(
                conversations = listOf(
                    conversation.copy(summarizedThroughMessageOrdinal = Long.MAX_VALUE),
                ),
            ),
        )
        assertRejected(
            snapshot.copy(
                conversations = listOf(conversation.copy(summarizedThroughMessageOrdinal = 1)),
                messages = emptyList(),
            ),
        )
        assertRejected(
            snapshot.copy(
                conversations = listOf(conversation.copy(summarizedThroughMessageOrdinal = 5)),
                messages = listOf(message.copy(ordinal = 8)),
            ),
        )

        assertTrue(
            UserDataSnapshotValidator.isSafeForImport(
                snapshot.copy(
                    conversations = listOf(conversation.copy(summarizedThroughMessageOrdinal = 7)),
                    messages = listOf(
                        message.copy(id = "message-8", ordinal = 8),
                        message.copy(id = "message-9", ordinal = 9),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `every imported entity collection rejects duplicate ids`() {
        val snapshot = sampleSnapshot()

        assertRejected(snapshot.copy(conversations = snapshot.conversations + snapshot.conversations.single()))
        assertRejected(snapshot.copy(messages = snapshot.messages + snapshot.messages.single()))
        assertRejected(snapshot.copy(memories = snapshot.memories + snapshot.memories.single()))
        assertRejected(snapshot.copy(reminders = snapshot.reminders + snapshot.reminders.single()))
        assertRejected(snapshot.copy(deliveries = snapshot.deliveries + snapshot.deliveries.single()))
        assertRejected(snapshot.copy(proposals = snapshot.proposals + snapshot.proposals.single()))
    }

    @Test
    fun `all transfer collections enforce fixed row limits`() {
        val snapshot = sampleSnapshot()

        assertRejected(
            snapshot.copy(
                conversations = List(UserDataTransferLimits.MAX_CONVERSATIONS + 1) {
                    snapshot.conversations.single()
                },
            ),
        )
        assertRejected(
            snapshot.copy(
                messages = List(UserDataTransferLimits.MAX_MESSAGES + 1) { snapshot.messages.single() },
            ),
        )
        assertRejected(
            snapshot.copy(
                memories = List(UserDataTransferLimits.MAX_MEMORIES + 1) { snapshot.memories.single() },
            ),
        )
        assertRejected(
            snapshot.copy(
                reminders = List(UserDataTransferLimits.MAX_REMINDERS + 1) { snapshot.reminders.single() },
            ),
        )
        assertRejected(
            snapshot.copy(
                deliveries = List(UserDataTransferLimits.MAX_DELIVERIES + 1) {
                    snapshot.deliveries.single()
                },
            ),
        )
        assertRejected(
            snapshot.copy(
                proposals = List(UserDataTransferLimits.MAX_PROPOSALS + 1) { snapshot.proposals.single() },
            ),
        )
    }

    @Test
    fun `reminder schedule version that would overflow during normalization is rejected`() {
        val snapshot = sampleSnapshot()

        assertRejected(
            snapshot.copy(
                reminders = listOf(snapshot.reminders.single().copy(scheduleVersion = Long.MAX_VALUE)),
            ),
        )
    }

    private fun assertArchiveFailure(archive: ByteArray, expected: UserDataArchiveFailure) {
        val result = EncryptedUserDataArchive.decrypt(archive, passphrase) as UserDataArchiveReadResult.Failed
        assertEquals(expected, result.failure)
    }

    private fun assertPayloadRejected(payload: ByteArray) {
        assertTrue(
            runCatching { UserDataPayloadCodec.decode(payload, UserDataSelection()) }.isFailure,
        )
    }

    private fun assertRejected(snapshot: UserDataSnapshot) {
        assertFalse(UserDataSnapshotValidator.isSafeForImport(snapshot))
    }

    private fun payload(write: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output -> output.write() }
            bytes.toByteArray()
        }

    private fun payloadWithPrelude(write: DataOutputStream.() -> Unit): ByteArray = payload {
        writeInt(1)
        writeByte(0)
        writeByte(0)
        write()
    }

    private fun ByteArray.putInt(offset: Int, value: Int): ByteArray = apply {
        ByteBuffer.wrap(this).putInt(offset, value)
    }

    private fun ByteArray.putLong(offset: Int, value: Long): ByteArray = apply {
        ByteBuffer.wrap(this).putLong(offset, value)
    }

    private fun sampleSnapshot(): UserDataSnapshot {
        // The repository intentionally retains recent messages that overlap the summary boundary.
        // Keeping this in the round-trip fixture protects compatibility with those v1 archives.
        val conversation = ConversationEntity("conversation", "제목", 1, 2, "요약", 1)
        val message = MessageEntity("message", conversation.id, 1, MessageRole.USER, "안녕", 2)
        val memory = MemoryEntity(
            id = "memory",
            content = "사용자는 민트색을 좋아한다.",
            normalizedContent = "사용자는 민트색을 좋아한다.",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
            category = MemoryCategory.PREFERENCE,
            validUntilEpochMillis = 9_999,
            lastConfirmedAtEpochMillis = 2,
        )
        val reminder = ReminderEntity(
            id = "reminder",
            title = "보험 확인",
            triggerAtEpochMillis = 9_000,
            zoneId = "Asia/Seoul",
            recurrenceRule = null,
            state = ReminderState.ACTIVE,
            precision = ReminderPrecision.FLEXIBLE,
            scheduleState = ReminderScheduleState.PENDING,
            scheduleVersion = 1,
            snoozeUntilEpochMillis = null,
            leadTimeMinutes = null,
            escalationPolicy = "once",
            sourceType = ReminderSourceType.DIRECT,
            sourceRefHash = null,
            createdBy = ReminderCreator.USER,
            confirmationDigest = "a".repeat(64),
            lastScheduledAtEpochMillis = null,
            lastDeliveredAtEpochMillis = null,
            completedAtEpochMillis = null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )
        val delivery = ReminderDeliveryEntity(
            id = "delivery",
            reminderId = reminder.id,
            scheduleVersion = 1,
            scheduledForEpochMillis = 9_000,
            outcome = ReminderDeliveryOutcome.POSTED,
            recordedAtEpochMillis = 9_001,
        )
        val proposal = CommitmentProposalEntity(
            id = "proposal",
            summary = "다음 주 보험 확인",
            proposedDueAtEpochMillis = null,
            zoneId = null,
            sourcePackage = null,
            sourceRefHash = "b".repeat(64),
            confidence = 60,
            status = ProposalStatus.PENDING,
            promotedReminderId = null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )
        return UserDataSnapshot(
            selection = UserDataSelection(),
            conversations = listOf(conversation),
            messages = listOf(message),
            memories = listOf(memory),
            reminders = listOf(reminder),
            deliveries = listOf(delivery),
            proposals = listOf(proposal),
            calendarRemapRequired = true,
            defaultCalendarLabelHint = "개인 일정",
        )
    }

    private companion object {
        const val ARCHIVE_VERSION_OFFSET = 8
        const val SCHEMA_VERSION_OFFSET = 12
        const val PLAINTEXT_LENGTH_OFFSET = 54
        const val CIPHER_LENGTH_OFFSET = 91
        const val CIPHER_TEXT_OFFSET = 95
    }
}
