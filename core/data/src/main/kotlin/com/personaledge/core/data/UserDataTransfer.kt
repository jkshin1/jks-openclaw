package com.personaledge.core.data

import androidx.room.withTransaction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class UserDataSelection(
    val conversations: Boolean = true,
    val memories: Boolean = true,
    val reminders: Boolean = true,
    val proposals: Boolean = true,
) {
    val flags: Int
        get() = (if (conversations) FLAG_CONVERSATIONS else 0) or
            (if (memories) FLAG_MEMORIES else 0) or
            (if (reminders) FLAG_REMINDERS else 0) or
            (if (proposals) FLAG_PROPOSALS else 0)

    val isEmpty: Boolean
        get() = flags == 0

    companion object {
        const val FLAG_CONVERSATIONS = 1
        const val FLAG_MEMORIES = 1 shl 1
        const val FLAG_REMINDERS = 1 shl 2
        const val FLAG_PROPOSALS = 1 shl 3
        const val KNOWN_FLAGS = FLAG_CONVERSATIONS or FLAG_MEMORIES or FLAG_REMINDERS or FLAG_PROPOSALS

        fun fromFlags(flags: Int): UserDataSelection? {
            if (flags and KNOWN_FLAGS.inv() != 0) return null
            return UserDataSelection(
                conversations = flags and FLAG_CONVERSATIONS != 0,
                memories = flags and FLAG_MEMORIES != 0,
                reminders = flags and FLAG_REMINDERS != 0,
                proposals = flags and FLAG_PROPOSALS != 0,
            )
        }
    }
}

data class UserDataSnapshot(
    val selection: UserDataSelection,
    val conversations: List<ConversationEntity>,
    val messages: List<MessageEntity>,
    val memories: List<MemoryEntity>,
    val reminders: List<ReminderEntity>,
    val deliveries: List<ReminderDeliveryEntity>,
    val proposals: List<CommitmentProposalEntity>,
    /** Provider row ids are intentionally never serialized; the user must reselect on a new device. */
    val calendarRemapRequired: Boolean,
    val defaultCalendarLabelHint: String?,
)

data class UserDataTransferPreview(
    val selection: UserDataSelection,
    val conversationCount: Int,
    val messageCount: Int,
    val memoryCount: Int,
    val reminderCount: Int,
    val deliveryCount: Int,
    val proposalCount: Int,
    val calendarRemapRequired: Boolean,
    val defaultCalendarLabelHint: String?,
)

data class UserDataImportResult(
    val importedConversations: Int,
    val importedMessages: Int,
    val importedMemories: Int,
    val importedReminders: Int,
    val importedDeliveries: Int,
    val importedProposals: Int,
    val skippedRows: Int,
    val calendarRemapRequired: Boolean,
)

enum class UserDataArchiveFailure {
    INVALID_PASSPHRASE,
    TOO_LARGE,
    INVALID_FORMAT,
    UNSUPPORTED_VERSION,
    SCHEMA_MISMATCH,
    AUTHENTICATION_FAILED,
    HASH_MISMATCH,
    INVALID_PAYLOAD,
}

sealed interface UserDataArchiveReadResult {
    data class Ready(val snapshot: UserDataSnapshot) : UserDataArchiveReadResult

    data class Failed(val failure: UserDataArchiveFailure) : UserDataArchiveReadResult
}

/** Reads and merges only user-selectable rows. Credentials, notification text and ledgers are absent. */
class UserDataTransferRepository(
    private val database: PersonalEdgeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun snapshot(
        selection: UserDataSelection,
        calendarRemapRequired: Boolean,
        defaultCalendarLabelHint: String?,
    ): UserDataSnapshot {
        require(!selection.isEmpty)
        return database.withTransaction {
            UserDataSnapshot(
                selection = selection,
                conversations = if (selection.conversations) {
                    database.conversationDao().listAllForTransfer()
                } else {
                    emptyList()
                },
                messages = if (selection.conversations) {
                    database.messageDao().listAllForTransfer()
                } else {
                    emptyList()
                },
                memories = if (selection.memories) {
                    database.memoryDao().listAllForTransfer()
                } else {
                    emptyList()
                },
                reminders = if (selection.reminders) {
                    database.reminderDao().listAllForTransfer()
                } else {
                    emptyList()
                },
                deliveries = if (selection.reminders) {
                    database.reminderDeliveryDao().listAllForTransfer()
                } else {
                    emptyList()
                },
                proposals = if (selection.proposals) {
                    database.commitmentProposalDao().listAllForTransfer()
                } else {
                    emptyList()
                },
                calendarRemapRequired = calendarRemapRequired,
                defaultCalendarLabelHint = defaultCalendarLabelHint?.takeCodePoints(120),
            )
        }
    }

    suspend fun import(snapshot: UserDataSnapshot): UserDataImportResult = database.withTransaction {
        require(UserDataSnapshotValidator.isSafeForImport(snapshot)) {
            "Transfer snapshot did not pass validation."
        }
        var skipped = 0
        val insertedConversationIds = mutableSetOf<String>()
        var conversationCount = 0
        snapshot.conversations.forEach { conversation ->
            if (database.conversationDao().insertForTransfer(conversation) >= 0) {
                conversationCount++
                insertedConversationIds += conversation.id
            } else {
                skipped++
            }
        }
        var messageCount = 0
        snapshot.messages.forEach { message ->
            if (message.conversationId !in insertedConversationIds) {
                skipped++
            } else if (database.messageDao().insertForTransfer(message) >= 0) {
                messageCount++
            } else {
                skipped++
            }
        }

        var memoryCount = 0
        snapshot.memories.forEach { memory ->
            if (database.memoryDao().insertForTransfer(memory) >= 0) memoryCount++ else skipped++
        }

        val insertedReminderIds = mutableSetOf<String>()
        var reminderCount = 0
        snapshot.reminders.forEach { archivedReminder ->
            val reminder = archivedReminder.normalizedForImport(clock())
            val duplicateSource = reminder.sourceRefHash?.let {
                database.reminderDao().findBySourceRefHash(it)
            }
            if (duplicateSource != null) {
                skipped++
            } else if (database.reminderDao().insertForTransfer(reminder) >= 0) {
                reminderCount++
                insertedReminderIds += reminder.id
            } else {
                skipped++
            }
        }
        var deliveryCount = 0
        snapshot.deliveries.forEach { delivery ->
            if (delivery.reminderId !in insertedReminderIds) {
                skipped++
            } else if (database.reminderDeliveryDao().insertForTransfer(delivery) >= 0) {
                deliveryCount++
            } else {
                skipped++
            }
        }

        var proposalCount = 0
        snapshot.proposals.forEach { proposal ->
            if (database.commitmentProposalDao().insertForTransfer(proposal) >= 0) {
                proposalCount++
            } else {
                skipped++
            }
        }

        UserDataImportResult(
            importedConversations = conversationCount,
            importedMessages = messageCount,
            importedMemories = memoryCount,
            importedReminders = reminderCount,
            importedDeliveries = deliveryCount,
            importedProposals = proposalCount,
            skippedRows = skipped,
            calendarRemapRequired = snapshot.calendarRemapRequired,
        )
    }

    private fun ReminderEntity.normalizedForImport(now: Long): ReminderEntity {
        if (state != ReminderState.ACTIVE) return copy(
            scheduleState = ReminderScheduleState.PENDING,
            scheduleVersion = scheduleVersion + 1,
            lastScheduledAtEpochMillis = null,
        )
        val effective = effectiveTriggerAtEpochMillis
        if (effective > now) return copy(
            scheduleState = ReminderScheduleState.PENDING,
            scheduleVersion = scheduleVersion + 1,
            lastScheduledAtEpochMillis = null,
            snoozeUntilEpochMillis = null,
        )
        val nextRecurring = nextFutureRecurringTrigger(now)
        return if (nextRecurring == null) {
            // Preserve it as an overdue, incomplete item without firing an old alert immediately.
            copy(
                scheduleState = ReminderScheduleState.DELIVERED,
                scheduleVersion = scheduleVersion + 1,
                snoozeUntilEpochMillis = null,
                lastScheduledAtEpochMillis = null,
            )
        } else {
            copy(
                triggerAtEpochMillis = nextRecurring,
                scheduleState = ReminderScheduleState.PENDING,
                scheduleVersion = scheduleVersion + 1,
                snoozeUntilEpochMillis = null,
                lastScheduledAtEpochMillis = null,
            )
        }
    }

    private fun ReminderEntity.nextFutureRecurringTrigger(now: Long): Long? {
        val rule = recurrenceRule ?: return null
        val zone = ZoneId.of(zoneId)
        var local = Instant.ofEpochMilli(triggerAtEpochMillis).atZone(zone).toLocalDateTime()
        repeat(MAX_RECURRENCE_ADVANCE_STEPS) {
            local = when {
                rule == "daily" -> local.plusDays(1)
                rule.startsWith("weekly:") -> {
                    val allowed = rule.removePrefix("weekly:").split(',')
                        .mapNotNull(DAY_BY_TOKEN::get).toSet()
                    if (allowed.isEmpty()) return null
                    (1L..7L).map(local::plusDays).first { it.dayOfWeek in allowed }
                }
                else -> return null
            }
            val candidate = local.atZone(zone).toInstant().toEpochMilli()
            if (candidate > now) return candidate
        }
        return null
    }

    companion object {
        private const val MAX_RECURRENCE_ADVANCE_STEPS = 3_660
        private val DAY_BY_TOKEN = mapOf(
            "mon" to DayOfWeek.MONDAY,
            "tue" to DayOfWeek.TUESDAY,
            "wed" to DayOfWeek.WEDNESDAY,
            "thu" to DayOfWeek.THURSDAY,
            "fri" to DayOfWeek.FRIDAY,
            "sat" to DayOfWeek.SATURDAY,
            "sun" to DayOfWeek.SUNDAY,
        )
    }
}

internal object UserDataTransferLimits {
    const val MAX_STRING_BYTES = 256 * 1024
    const val MAX_CONVERSATIONS = 10_000
    const val MAX_MESSAGES = 200_000
    const val MAX_MEMORIES = 50
    const val MAX_REMINDERS = 10_000
    const val MAX_DELIVERIES = 200_000
    const val MAX_PROPOSALS = 100
}

/**
 * Validates the complete transfer graph before any imported row is written.
 *
 * A summarized conversation may legitimately have a deleted prefix while retaining newer rows
 * that overlap the summary. The first retained ordinal may therefore be at most one beyond the
 * summary boundary. This preserves existing archives while rejecting unexplained gaps and
 * summaries that claim nonexistent rows.
 */
internal object UserDataSnapshotValidator {
    fun isSafeForImport(snapshot: UserDataSnapshot): Boolean = with(snapshot) {
        if (selection.isEmpty) return false
        if (!hasBoundedCollections()) return false
        if (!selection.conversations && (conversations.isNotEmpty() || messages.isNotEmpty())) return false
        if (!selection.memories && memories.isNotEmpty()) return false
        if (!selection.reminders && (reminders.isNotEmpty() || deliveries.isNotEmpty())) return false
        if (!selection.proposals && proposals.isNotEmpty()) return false
        if (defaultCalendarLabelHint?.isSafeText(120) == false) return false

        val conversationsById = HashMap<String, ConversationEntity>()
        for (value in conversations) {
            if (!value.id.isSafeId() || !value.title.isSafeText(240) ||
                value.summary?.isSafeText(32_000) == false ||
                value.createdAtEpochMillis < 0 || value.updatedAtEpochMillis < 0 ||
                value.summarizedThroughMessageOrdinal < 0 ||
                (value.summarizedThroughMessageOrdinal > 0 && value.summary.isNullOrBlank()) ||
                conversationsById.put(value.id, value) != null
            ) {
                return false
            }
        }

        val messageIds = HashSet<String>()
        val ordinalsByConversation = HashMap<String, MutableList<Long>>()
        for (value in messages) {
            if (!value.id.isSafeId() || value.conversationId !in conversationsById ||
                value.ordinal !in 1 until Long.MAX_VALUE || !value.text.isSafeText(64_000) ||
                value.createdAtEpochMillis < 0 || !messageIds.add(value.id)
            ) {
                return false
            }
            ordinalsByConversation.getOrPut(value.conversationId, ::mutableListOf).add(value.ordinal)
        }
        for (conversation in conversations) {
            val ordinals = ordinalsByConversation[conversation.id].orEmpty().sorted()
            val summaryBoundary = conversation.summarizedThroughMessageOrdinal
            if (ordinals.isEmpty()) {
                if (summaryBoundary != 0L) return false
                continue
            }
            var previous = ordinals.first()
            for (index in 1 until ordinals.size) {
                val current = ordinals[index]
                if (current != previous + 1L) return false
                previous = current
            }
            val firstRetainedOrdinal = ordinals.first()
            val highestOrdinal = ordinals.last()
            if (summaryBoundary > highestOrdinal) return false
            if (firstRetainedOrdinal > 1L && summaryBoundary < firstRetainedOrdinal - 1L) return false
        }

        val memoryIds = HashSet<String>()
        for (value in memories) {
            if (!value.id.isSafeId() || MemoryTextPolicy.sanitize(value.content) != value.content ||
                value.normalizedContent.isBlank() || value.normalizedContent.length > 1_000 ||
                value.createdAtEpochMillis < 0 || value.updatedAtEpochMillis < 0 ||
                value.lastConfirmedAtEpochMillis < 0 ||
                value.validUntilEpochMillis?.let { it < 0 } == true ||
                value.supersedesId?.isSafeId() == false || !memoryIds.add(value.id)
            ) {
                return false
            }
        }

        val reminderIds = HashSet<String>()
        for (value in reminders) {
            if (!value.id.isSafeId() || ReminderTextPolicy.sanitizeTitle(value.title) != value.title ||
                value.triggerAtEpochMillis <= 0 || runCatching { ZoneId.of(value.zoneId) }.isFailure ||
                ReminderRecurrencePolicy.validate(value.recurrenceRule) ==
                ReminderRecurrenceValidation.Invalid ||
                value.scheduleVersion !in 1 until Long.MAX_VALUE ||
                value.leadTimeMinutes?.let { it !in 0..10_080 } == true ||
                value.escalationPolicy?.let { it !in setOf("once", "until_completed") } == true ||
                !value.confirmationDigest.isSha256() || value.sourceRefHash?.isSha256() == false ||
                value.createdAtEpochMillis < 0 || value.updatedAtEpochMillis < 0 ||
                !reminderIds.add(value.id)
            ) {
                return false
            }
        }

        val deliveryIds = HashSet<String>()
        for (value in deliveries) {
            if (!value.id.isSafeId() || value.reminderId !in reminderIds || value.scheduleVersion <= 0 ||
                value.scheduledForEpochMillis <= 0 || value.recordedAtEpochMillis < 0 ||
                !deliveryIds.add(value.id)
            ) {
                return false
            }
        }

        val proposalIds = HashSet<String>()
        for (value in proposals) {
            if (!value.id.isSafeId() || value.summary.isBlank() || !value.summary.isSafeText(180) ||
                value.sourceRefHash.isSha256().not() || value.confidence !in 1..100 ||
                value.proposedDueAtEpochMillis?.let { it <= 0 } == true ||
                (value.proposedDueAtEpochMillis == null) != (value.zoneId == null) ||
                value.zoneId?.let { runCatching { ZoneId.of(it) }.isFailure } == true ||
                value.sourcePackage?.let { !it.isSafeText(160) } == true ||
                value.promotedReminderId?.isSafeId() == false ||
                value.createdAtEpochMillis < 0 || value.updatedAtEpochMillis < 0 ||
                !proposalIds.add(value.id)
            ) {
                return false
            }
        }
        true
    }

    private fun UserDataSnapshot.hasBoundedCollections(): Boolean =
        conversations.size <= UserDataTransferLimits.MAX_CONVERSATIONS &&
            messages.size <= UserDataTransferLimits.MAX_MESSAGES &&
            memories.size <= UserDataTransferLimits.MAX_MEMORIES &&
            reminders.size <= UserDataTransferLimits.MAX_REMINDERS &&
            deliveries.size <= UserDataTransferLimits.MAX_DELIVERIES &&
            proposals.size <= UserDataTransferLimits.MAX_PROPOSALS

    private fun String.isSafeId(): Boolean = matches(SAFE_ID)

    private fun String.isSafeText(maxCodePoints: Int): Boolean =
        codePointCount(0, length) in 0..maxCodePoints && codePoints().noneMatch { codePoint ->
            (Character.isISOControl(codePoint) && codePoint !in ALLOWED_TEXT_CONTROLS) ||
                when (Character.getType(codePoint)) {
                Character.FORMAT.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(),
                -> true
                else -> false
            }
        }

    private fun String.isSha256(): Boolean = length == 64 && all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }

    private val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,160}")
    private val ALLOWED_TEXT_CONTROLS = setOf('\t'.code, '\n'.code, '\r'.code)
}

object EncryptedUserDataArchive {
    const val DATABASE_SCHEMA_VERSION = 5
    const val ARCHIVE_VERSION = 1
    const val MIN_PASSPHRASE_CODE_POINTS = 12
    const val MAX_PASSPHRASE_CODE_POINTS = 128
    const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val HASH_BYTES = 32
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / Byte.SIZE_BITS
    private const val HEADER_BYTES = 8 + (5 * Int.SIZE_BYTES) + 3 + SALT_BYTES + NONCE_BYTES + HASH_BYTES
    private const val ARCHIVE_FIXED_OVERHEAD_BYTES = HEADER_BYTES + Int.SIZE_BYTES + GCM_TAG_BYTES
    internal const val MAX_PAYLOAD_BYTES = MAX_ARCHIVE_BYTES - ARCHIVE_FIXED_OVERHEAD_BYTES
    private val MAGIC = "PEDGEBK1".toByteArray(Charsets.US_ASCII)

    fun preview(snapshot: UserDataSnapshot): UserDataTransferPreview = UserDataTransferPreview(
        selection = snapshot.selection,
        conversationCount = snapshot.conversations.size,
        messageCount = snapshot.messages.size,
        memoryCount = snapshot.memories.size,
        reminderCount = snapshot.reminders.size,
        deliveryCount = snapshot.deliveries.size,
        proposalCount = snapshot.proposals.size,
        calendarRemapRequired = snapshot.calendarRemapRequired,
        defaultCalendarLabelHint = snapshot.defaultCalendarLabelHint,
    )

    fun encrypt(snapshot: UserDataSnapshot, passphrase: String): ByteArray {
        requireValidPassphrase(passphrase)
        require(UserDataSnapshotValidator.isSafeForImport(snapshot)) {
            "Transfer snapshot did not pass validation."
        }
        val plaintext = UserDataPayloadCodec.encode(snapshot)
        require(plaintext.size <= MAX_PAYLOAD_BYTES) { "Transfer payload is too large." }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val hash = sha256(plaintext)
        val header = header(
            selectionFlags = snapshot.selection.flags,
            plaintextLength = plaintext.size,
            salt = salt,
            nonce = nonce,
            hash = hash,
        )
        val key = deriveKey(passphrase, salt)
        val cipherText = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                updateAAD(header)
                doFinal(plaintext)
            }
        } finally {
            key.fill(0)
            plaintext.fill(0)
        }
        val archiveSize = header.size.toLong() + Int.SIZE_BYTES + cipherText.size.toLong()
        require(archiveSize <= MAX_ARCHIVE_BYTES.toLong()) { "Transfer archive is too large." }
        val archive = ByteArrayOutputStream(archiveSize.toInt()).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(header)
                output.writeInt(cipherText.size)
                output.write(cipherText)
            }
            bytes.toByteArray()
        }
        require(archive.size <= MAX_ARCHIVE_BYTES)
        return archive
    }

    fun decrypt(archive: ByteArray, passphrase: String): UserDataArchiveReadResult {
        if (!isValidPassphrase(passphrase)) {
            return UserDataArchiveReadResult.Failed(UserDataArchiveFailure.INVALID_PASSPHRASE)
        }
        if (archive.size !in MIN_ARCHIVE_BYTES..MAX_ARCHIVE_BYTES) {
            return UserDataArchiveReadResult.Failed(UserDataArchiveFailure.TOO_LARGE)
        }
        val parsed = runCatching { parseEnvelope(archive) }.getOrNull()
            ?: return UserDataArchiveReadResult.Failed(UserDataArchiveFailure.INVALID_FORMAT)
        if (parsed.archiveVersion != ARCHIVE_VERSION.toLong()) {
            return UserDataArchiveReadResult.Failed(UserDataArchiveFailure.UNSUPPORTED_VERSION)
        }
        if (parsed.databaseSchemaVersion != DATABASE_SCHEMA_VERSION.toLong()) {
            return UserDataArchiveReadResult.Failed(UserDataArchiveFailure.SCHEMA_MISMATCH)
        }
        val selection = UserDataSelection.fromFlags(parsed.selectionFlags)
            ?: return UserDataArchiveReadResult.Failed(UserDataArchiveFailure.INVALID_FORMAT)
        if (selection.isEmpty) {
            return UserDataArchiveReadResult.Failed(UserDataArchiveFailure.INVALID_FORMAT)
        }
        val key = deriveKey(passphrase, parsed.salt)
        val plaintext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(GCM_TAG_BITS, parsed.nonce),
                )
                updateAAD(parsed.header)
                doFinal(parsed.cipherText)
            }
        } catch (_: AEADBadTagException) {
            return UserDataArchiveReadResult.Failed(UserDataArchiveFailure.AUTHENTICATION_FAILED)
        } catch (_: Exception) {
            return UserDataArchiveReadResult.Failed(UserDataArchiveFailure.INVALID_FORMAT)
        } finally {
            key.fill(0)
        }
        try {
            if (plaintext.size != parsed.plaintextLength) {
                return UserDataArchiveReadResult.Failed(UserDataArchiveFailure.INVALID_FORMAT)
            }
            if (!MessageDigest.isEqual(sha256(plaintext), parsed.hash)) {
                return UserDataArchiveReadResult.Failed(UserDataArchiveFailure.HASH_MISMATCH)
            }
            val snapshot = runCatching { UserDataPayloadCodec.decode(plaintext, selection) }
                .getOrNull()
                ?: return UserDataArchiveReadResult.Failed(UserDataArchiveFailure.INVALID_PAYLOAD)
            return UserDataArchiveReadResult.Ready(snapshot)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun parseEnvelope(archive: ByteArray): Envelope {
        return DataInputStream(ByteArrayInputStream(archive)).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC))
            val archiveVersionBits = input.readInt()
            val archiveVersion = Integer.toUnsignedLong(archiveVersionBits)
            val databaseSchemaVersionBits = input.readInt()
            val databaseSchemaVersion = Integer.toUnsignedLong(databaseSchemaVersionBits)
            val iterations = input.readInt()
            require(iterations == PBKDF2_ITERATIONS)
            val selectionFlags = input.readInt()
            val salt = input.readBoundedBytes(SALT_BYTES)
            val nonce = input.readBoundedBytes(NONCE_BYTES)
            val plaintextLengthValue = Integer.toUnsignedLong(input.readInt())
            require(plaintextLengthValue in 1L..MAX_PAYLOAD_BYTES.toLong())
            val plaintextLength = plaintextLengthValue.toInt()
            val hash = input.readBoundedBytes(HASH_BYTES)
            val header = header(
                archiveVersion = archiveVersionBits,
                databaseSchemaVersion = databaseSchemaVersionBits,
                selectionFlags = selectionFlags,
                plaintextLength = plaintextLength,
                salt = salt,
                nonce = nonce,
                hash = hash,
            )
            val cipherLengthValue = Integer.toUnsignedLong(input.readInt())
            require(cipherLengthValue == plaintextLengthValue + GCM_TAG_BYTES.toLong())
            require(cipherLengthValue == input.available().toLong())
            val cipherLength = cipherLengthValue.toInt()
            val cipherText = ByteArray(cipherLength).also(input::readFully)
            require(input.read() == -1)
            Envelope(
                archiveVersion,
                databaseSchemaVersion,
                selectionFlags,
                plaintextLength,
                salt,
                nonce,
                hash,
                header,
                cipherText,
            )
        }
    }

    private fun header(
        archiveVersion: Int = ARCHIVE_VERSION,
        databaseSchemaVersion: Int = DATABASE_SCHEMA_VERSION,
        selectionFlags: Int,
        plaintextLength: Int,
        salt: ByteArray,
        nonce: ByteArray,
        hash: ByteArray,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC)
            output.writeInt(archiveVersion)
            output.writeInt(databaseSchemaVersion)
            output.writeInt(PBKDF2_ITERATIONS)
            output.writeInt(selectionFlags)
            output.writeBoundedBytes(salt)
            output.writeBoundedBytes(nonce)
            output.writeInt(plaintextLength)
            output.writeBoundedBytes(hash)
        }
        bytes.toByteArray()
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        val characters = passphrase.toCharArray()
        val spec = PBEKeySpec(characters, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            characters.fill('\u0000')
            spec.clearPassword()
        }
    }

    private fun requireValidPassphrase(passphrase: String) {
        require(isValidPassphrase(passphrase)) {
            "Passphrase must contain 12 to 128 Unicode code points."
        }
    }

    private fun isValidPassphrase(passphrase: String): Boolean =
        passphrase.codePointCount(0, passphrase.length) in
            MIN_PASSPHRASE_CODE_POINTS..MAX_PASSPHRASE_CODE_POINTS &&
            passphrase.codePoints().noneMatch { Character.isISOControl(it) }

    private fun sha256(value: ByteArray): ByteArray = MessageDigest
        .getInstance("SHA-256")
        .digest(value)

    private data class Envelope(
        val archiveVersion: Long,
        val databaseSchemaVersion: Long,
        val selectionFlags: Int,
        val plaintextLength: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
        val hash: ByteArray,
        val header: ByteArray,
        val cipherText: ByteArray,
    )

    private const val MIN_ARCHIVE_BYTES = ARCHIVE_FIXED_OVERHEAD_BYTES + 1
}

internal object UserDataPayloadCodec {
    private const val PAYLOAD_VERSION = 1

    fun encode(snapshot: UserDataSnapshot): ByteArray {
        require(UserDataSnapshotValidator.isSafeForImport(snapshot)) {
            "Transfer snapshot did not pass validation."
        }
        return BoundedByteArrayOutputStream(EncryptedUserDataArchive.MAX_PAYLOAD_BYTES).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(PAYLOAD_VERSION)
                output.writeBoolean(snapshot.calendarRemapRequired)
                output.writeNullableString(snapshot.defaultCalendarLabelHint)
                output.writeList(
                    snapshot.conversations,
                    UserDataTransferLimits.MAX_CONVERSATIONS,
                    ::writeConversation,
                )
                output.writeList(snapshot.messages, UserDataTransferLimits.MAX_MESSAGES, ::writeMessage)
                output.writeList(snapshot.memories, UserDataTransferLimits.MAX_MEMORIES, ::writeMemory)
                output.writeList(snapshot.reminders, UserDataTransferLimits.MAX_REMINDERS, ::writeReminder)
                output.writeList(snapshot.deliveries, UserDataTransferLimits.MAX_DELIVERIES, ::writeDelivery)
                output.writeList(snapshot.proposals, UserDataTransferLimits.MAX_PROPOSALS, ::writeProposal)
            }
            bytes.toByteArray()
        }
    }

    fun decode(payload: ByteArray, selection: UserDataSelection): UserDataSnapshot =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(payload.size in 1..EncryptedUserDataArchive.MAX_PAYLOAD_BYTES)
            require(Integer.toUnsignedLong(input.readInt()) == PAYLOAD_VERSION.toLong())
            val remap = input.readStrictBoolean()
            val label = input.readNullableString()
            val conversations = input.readList(UserDataTransferLimits.MAX_CONVERSATIONS, ::readConversation)
            val messages = input.readList(UserDataTransferLimits.MAX_MESSAGES, ::readMessage)
            val memories = input.readList(UserDataTransferLimits.MAX_MEMORIES, ::readMemory)
            val reminders = input.readList(UserDataTransferLimits.MAX_REMINDERS, ::readReminder)
            val deliveries = input.readList(UserDataTransferLimits.MAX_DELIVERIES, ::readDelivery)
            val proposals = input.readList(UserDataTransferLimits.MAX_PROPOSALS, ::readProposal)
            require(input.read() == -1)
            val snapshot = UserDataSnapshot(
                selection,
                conversations,
                messages,
                memories,
                reminders,
                deliveries,
                proposals,
                remap,
                label,
            )
            require(UserDataSnapshotValidator.isSafeForImport(snapshot)) {
                "Transfer payload did not pass validation."
            }
            snapshot
        }

    private fun writeConversation(out: DataOutputStream, value: ConversationEntity) = with(out) {
        writeString(value.id); writeString(value.title); writeLong(value.createdAtEpochMillis)
        writeLong(value.updatedAtEpochMillis); writeNullableString(value.summary)
        writeLong(value.summarizedThroughMessageOrdinal)
    }

    private fun readConversation(input: DataInputStream) = with(input) {
        ConversationEntity(readString(), readString(), readLong(), readLong(), readNullableString(), readLong())
    }

    private fun writeMessage(out: DataOutputStream, value: MessageEntity) = with(out) {
        writeString(value.id); writeString(value.conversationId); writeLong(value.ordinal)
        writeString(value.role.name); writeString(value.text); writeLong(value.createdAtEpochMillis)
    }

    private fun readMessage(input: DataInputStream) = with(input) {
        MessageEntity(readString(), readString(), readLong(), MessageRole.valueOf(readString()), readString(), readLong())
    }

    private fun writeMemory(out: DataOutputStream, value: MemoryEntity) = with(out) {
        writeString(value.id); writeString(value.content); writeString(value.normalizedContent)
        writeLong(value.createdAtEpochMillis); writeLong(value.updatedAtEpochMillis)
        writeString(value.category.name); writeNullableLong(value.validUntilEpochMillis)
        writeLong(value.lastConfirmedAtEpochMillis); writeNullableString(value.supersedesId)
    }

    private fun readMemory(input: DataInputStream) = with(input) {
        MemoryEntity(
            readString(), readString(), readString(), readLong(), readLong(),
            MemoryCategory.valueOf(readString()), readNullableLong(), readLong(), readNullableString(),
        )
    }

    private fun writeReminder(out: DataOutputStream, value: ReminderEntity) = with(out) {
        writeString(value.id); writeString(value.title); writeLong(value.triggerAtEpochMillis)
        writeString(value.zoneId); writeNullableString(value.recurrenceRule); writeString(value.state.name)
        writeString(value.precision.name); writeString(value.scheduleState.name); writeLong(value.scheduleVersion)
        writeNullableLong(value.snoozeUntilEpochMillis); writeNullableInt(value.leadTimeMinutes)
        writeNullableString(value.escalationPolicy); writeString(value.sourceType.name)
        writeNullableString(value.sourceRefHash); writeString(value.createdBy.name)
        writeString(value.confirmationDigest); writeNullableLong(value.lastScheduledAtEpochMillis)
        writeNullableLong(value.lastDeliveredAtEpochMillis); writeNullableLong(value.completedAtEpochMillis)
        writeLong(value.createdAtEpochMillis); writeLong(value.updatedAtEpochMillis)
    }

    private fun readReminder(input: DataInputStream) = with(input) {
        ReminderEntity(
            readString(), readString(), readLong(), readString(), readNullableString(),
            ReminderState.valueOf(readString()), ReminderPrecision.valueOf(readString()),
            ReminderScheduleState.valueOf(readString()), readLong(), readNullableLong(),
            readNullableInt(), readNullableString(), ReminderSourceType.valueOf(readString()),
            readNullableString(), ReminderCreator.valueOf(readString()), readString(),
            readNullableLong(), readNullableLong(), readNullableLong(), readLong(), readLong(),
        )
    }

    private fun writeDelivery(out: DataOutputStream, value: ReminderDeliveryEntity) = with(out) {
        writeString(value.id); writeString(value.reminderId); writeLong(value.scheduleVersion)
        writeLong(value.scheduledForEpochMillis); writeString(value.outcome.name)
        writeLong(value.recordedAtEpochMillis)
    }

    private fun readDelivery(input: DataInputStream) = with(input) {
        ReminderDeliveryEntity(
            readString(), readString(), readLong(), readLong(),
            ReminderDeliveryOutcome.valueOf(readString()), readLong(),
        )
    }

    private fun writeProposal(out: DataOutputStream, value: CommitmentProposalEntity) = with(out) {
        writeString(value.id); writeString(value.summary); writeNullableLong(value.proposedDueAtEpochMillis)
        writeNullableString(value.zoneId); writeNullableString(value.sourcePackage)
        writeString(value.sourceRefHash); writeInt(value.confidence); writeString(value.status.name)
        writeNullableString(value.promotedReminderId); writeLong(value.createdAtEpochMillis)
        writeLong(value.updatedAtEpochMillis)
    }

    private fun readProposal(input: DataInputStream) = with(input) {
        CommitmentProposalEntity(
            readString(), readString(), readNullableLong(), readNullableString(), readNullableString(),
            readString(), readInt(), ProposalStatus.valueOf(readString()), readNullableString(),
            readLong(), readLong(),
        )
    }

    private fun <T> DataOutputStream.writeList(
        values: List<T>,
        maximum: Int,
        write: (DataOutputStream, T) -> Unit,
    ) {
        require(values.size <= maximum)
        writeInt(values.size)
        values.forEach { write(this, it) }
    }

    private fun <T> DataInputStream.readList(
        maximum: Int,
        read: (DataInputStream) -> T,
    ): List<T> {
        val sizeValue = Integer.toUnsignedLong(readInt())
        require(sizeValue <= maximum.toLong())
        require(sizeValue <= available().toLong())
        val size = sizeValue.toInt()
        return List(size) { read(this) }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= UserDataTransferLimits.MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val sizeValue = Integer.toUnsignedLong(readInt())
        require(sizeValue <= UserDataTransferLimits.MAX_STRING_BYTES.toLong())
        require(sizeValue <= available().toLong())
        val size = sizeValue.toInt()
        val bytes = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder().run {
            onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readStrictBoolean()) readString() else null

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? = if (readStrictBoolean()) readLong() else null

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private fun DataInputStream.readNullableInt(): Int? = if (readStrictBoolean()) readInt() else null
}

private class BoundedByteArrayOutputStream(
    private val maximumBytes: Int,
) : ByteArrayOutputStream(minOf(maximumBytes, 32 * 1024)) {
    override fun write(value: Int) {
        require(count < maximumBytes) { "Transfer payload is too large." }
        super.write(value)
    }

    override fun write(value: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= value.size - length)
        require(count.toLong() + length.toLong() <= maximumBytes.toLong()) {
            "Transfer payload is too large."
        }
        super.write(value, offset, length)
    }
}

private fun DataInputStream.readStrictBoolean(): Boolean = when (readUnsignedByte()) {
    0 -> false
    1 -> true
    else -> throw IllegalArgumentException("Invalid transfer boolean marker.")
}

private fun DataOutputStream.writeBoundedBytes(value: ByteArray) {
    require(value.size <= 255)
    writeByte(value.size)
    write(value)
}

private fun DataInputStream.readBoundedBytes(expectedSize: Int): ByteArray {
    val size = readUnsignedByte()
    require(size == expectedSize)
    return ByteArray(size).also(::readFully)
}
