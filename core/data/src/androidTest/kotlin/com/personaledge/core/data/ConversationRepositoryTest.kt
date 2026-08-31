package com.personaledge.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationRepositoryTest {
    private lateinit var database: PersonalEdgeDatabase
    private lateinit var repository: ConversationRepository

    private var now = 1_700_000_000_000L
    private val nextId = AtomicLong(0)

    @Before
    fun openInMemoryDatabase() {
        database = Room
            .inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                PersonalEdgeDatabase::class.java,
            )
            .allowMainThreadQueries()
            .build()
        repository = ConversationRepository(
            database = database,
            clock = { now },
            idFactory = { "id-${nextId.incrementAndGet()}" },
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun messagesAreOrderedByAssignedOrdinalNotByTheClock() = runBlocking {
        val conversationId = repository.createConversation("치과 예약")

        repository.appendMessage(conversationId, MessageRole.USER, "내일 일정 알려줘")
        now -= 60_000 // The device clock moves backwards between the two appends.
        repository.appendMessage(conversationId, MessageRole.ASSISTANT, "내일은 비어 있습니다")

        val context = repository.loadContext(conversationId)
        assertNotNull(context)
        assertEquals(listOf(1L, 2L), context!!.recentMessages.map(StoredMessage::ordinal))
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT),
            context.recentMessages.map(StoredMessage::role),
        )
    }

    @Test
    fun concurrentAppendsProduceUniqueOrdinals() = runBlocking {
        val conversationId = repository.createConversation("동시 기록")

        val ordinals = (1..24)
            .map { index ->
                async(Dispatchers.IO) {
                    repository.appendMessage(conversationId, MessageRole.USER, "메시지 $index")
                }
            }
            .awaitAll()
            .filterNotNull()

        assertEquals(24, ordinals.size)
        assertEquals(24, ordinals.toSet().size)
        assertEquals(24L, repository.messageCount())
    }

    @Test
    fun anAttachmentSummaryIsStoredAndReadBackWithItsMessage() = runBlocking {
        val conversationId = repository.createConversation("사진 질문")

        repository.appendMessage(
            conversationId = conversationId,
            role = MessageRole.USER,
            text = "이 영수증 정리해 줘",
            attachmentSummary = "IMAGE:CAMERA",
        )
        repository.appendMessage(conversationId, MessageRole.ASSISTANT, "합계는 24,000원입니다")

        val context = repository.loadContext(conversationId)
        assertNotNull(context)
        assertEquals(
            listOf("IMAGE:CAMERA", null),
            context!!.recentMessages.map(StoredMessage::attachmentSummary),
        )
    }

    @Test
    fun anAttachmentAloneIsStillARecordableMessage() = runBlocking {
        // A photo sent with no typed line is a complete request; losing it would leave the
        // assistant answer referring to a turn the transcript never recorded.
        val conversationId = repository.createConversation("사진만")

        val ordinal = repository.appendMessage(
            conversationId = conversationId,
            role = MessageRole.USER,
            text = "",
            attachmentSummary = "AUDIO:VOICE:12",
        )

        assertEquals(1L, ordinal)
        val stored = repository.loadContext(conversationId)!!.recentMessages.single()
        assertEquals("", stored.text)
        assertEquals("AUDIO:VOICE:12", stored.attachmentSummary)
    }

    @Test
    fun anOversizedAttachmentSummaryIsBoundedRatherThanStoredWhole() = runBlocking {
        val conversationId = repository.createConversation("경계")

        repository.appendMessage(
            conversationId = conversationId,
            role = MessageRole.USER,
            text = "사진",
            attachmentSummary = "IMAGE:CAMERA".repeat(20),
        )

        val stored = repository.loadContext(conversationId)!!.recentMessages.single()
        assertEquals(
            ConversationRepository.MAX_ATTACHMENT_SUMMARY_CHARACTERS,
            stored.attachmentSummary!!.length,
        )
    }

    @Test
    fun anOrdinaryMessageStoresNoAttachmentSummary() = runBlocking {
        val conversationId = repository.createConversation("평범")

        repository.appendMessage(conversationId, MessageRole.USER, "안녕")

        assertNull(repository.loadContext(conversationId)!!.recentMessages.single().attachmentSummary)
    }

    @Test
    fun appendingToAMissingConversationIsRefused() = runBlocking {
        assertNull(repository.appendMessage("absent", MessageRole.USER, "안녕"))
        assertEquals(0L, repository.messageCount())
    }

    @Test
    fun toolExecutionCommitStoresAssistantReceiptAndReadOrdinalAtomically() = runBlocking {
        val conversationId = repository.createConversation("원자 커밋")
        val userOrdinal = repository.appendMessage(
            conversationId,
            MessageRole.USER,
            "이천 날씨 알려 줘",
        )!!
        val turnId = "turn-11111111-1111-1111-1111-111111111111"
        val outcomes = TurnOutcomeRepository(database = database, clock = { now })
        assertTrue(outcomes.start(turnId, conversationId, userOrdinal))

        assertTrue(
            repository.commitToolExecution(
                conversationId = conversationId,
                turnId = turnId,
                assistantText = "먼저 확인하겠습니다.",
                toolReceipt = "현재 및 오늘 날씨를 확인했습니다.",
                toolName = "weather_current",
                toolRisk = TurnToolRisk.READ_ONLY,
                trustedOrdinal = 1,
                outcome = TurnToolCommitOutcome.READ_COMPLETED,
            ),
        )

        val messages = repository.listMessages(conversationId)
        assertEquals(listOf(1L, 2L, 3L), messages.map(MessageEntity::ordinal))
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL_RECEIPT),
            messages.map(MessageEntity::role),
        )
        assertEquals(
            listOf("weather_current"),
            database.turnOutcomeDao().listReadExecutions(turnId).map { it.toolName },
        )
        assertEquals(TurnOutcomeState.READ_EXECUTED, database.turnOutcomeDao().find(turnId)?.state)
    }

    @Test
    fun repeatedReadToolExecutionCommitIsAnExactIdempotentNoOp() = runBlocking {
        val conversationId = repository.createConversation("read replay")
        val userOrdinal = repository.appendMessage(
            conversationId,
            MessageRole.USER,
            "이천 날씨 알려 줘",
        )!!
        val turnId = "turn-55555555-5555-5555-5555-555555555555"
        val outcomes = TurnOutcomeRepository(database = database, clock = { now })
        assertTrue(outcomes.start(turnId, conversationId, userOrdinal))

        suspend fun commit(toolName: String, receipt: String): Boolean =
            repository.commitToolExecution(
                conversationId = conversationId,
                turnId = turnId,
                assistantText = "먼저 확인하겠습니다.",
                toolReceipt = receipt,
                toolName = toolName,
                toolRisk = TurnToolRisk.READ_ONLY,
                trustedOrdinal = 1,
                outcome = TurnToolCommitOutcome.READ_COMPLETED,
            )

        assertTrue(commit("weather_current", "현재 및 오늘 날씨를 확인했습니다."))
        val afterFirstCommit = repository.listMessages(conversationId)

        assertTrue(commit("weather_current", "현재 및 오늘 날씨를 확인했습니다."))
        assertFalse(commit("weather_current", "변조된 영수증"))
        assertFalse(commit("alarm_next", "다음 알람 시각을 확인했습니다."))

        val afterReplays = repository.listMessages(conversationId)
        assertEquals(afterFirstCommit, afterReplays)
        assertEquals(1, afterReplays.count { it.role == MessageRole.TOOL_RECEIPT })
        assertEquals(
            "tool-commit:$turnId:1:READ_ONLY:READ_COMPLETED:weather_current",
            afterReplays.single { it.role == MessageRole.TOOL_RECEIPT }.id,
        )
        assertEquals(
            listOf("weather_current"),
            database.turnOutcomeDao().listReadExecutions(turnId).map { it.toolName },
        )
    }

    @Test
    fun concurrentIdenticalReadCommitsStoreOneReceipt() = runBlocking {
        val conversationId = repository.createConversation("concurrent replay")
        val userOrdinal = repository.appendMessage(
            conversationId,
            MessageRole.USER,
            "날씨 알려 줘",
        )!!
        val turnId = "turn-66666666-6666-6666-6666-666666666666"
        val outcomes = TurnOutcomeRepository(database = database, clock = { now })
        assertTrue(outcomes.start(turnId, conversationId, userOrdinal))

        val results = (1..2).map {
            async(Dispatchers.IO) {
                repository.commitToolExecution(
                    conversationId = conversationId,
                    turnId = turnId,
                    assistantText = "확인 중입니다.",
                    toolReceipt = "현재 및 오늘 날씨를 확인했습니다.",
                    toolName = "weather_current",
                    toolRisk = TurnToolRisk.READ_ONLY,
                    trustedOrdinal = 1,
                    outcome = TurnToolCommitOutcome.READ_COMPLETED,
                )
            }
        }.awaitAll()

        assertEquals(listOf(true, true), results)
        val messages = repository.listMessages(conversationId)
        assertEquals(1, messages.count { it.role == MessageRole.ASSISTANT })
        assertEquals(1, messages.count { it.role == MessageRole.TOOL_RECEIPT })
    }

    @Test
    fun repeatedWriteCompletedCommitKeepsUnknownStateAndRejectsMismatch() = runBlocking {
        val conversationId = repository.createConversation("write completed replay")
        val userOrdinal = repository.appendMessage(
            conversationId,
            MessageRole.USER,
            "알람 맞춰 줘",
        )!!
        val turnId = "turn-77777777-7777-7777-7777-777777777777"
        val outcomes = TurnOutcomeRepository(database = database, clock = { now })
        assertTrue(outcomes.start(turnId, conversationId, userOrdinal))
        assertTrue(outcomes.armSideEffect(turnId, "alarm_set", TurnToolRisk.DATA_WRITE))

        suspend fun commit(outcome: TurnToolCommitOutcome, receipt: String): Boolean =
            repository.commitToolExecution(
                conversationId = conversationId,
                turnId = turnId,
                assistantText = "",
                toolReceipt = receipt,
                toolName = "alarm_set",
                toolRisk = TurnToolRisk.DATA_WRITE,
                trustedOrdinal = 1,
                outcome = outcome,
            )

        assertTrue(commit(TurnToolCommitOutcome.WRITE_COMPLETED, "알람 추가를 요청했습니다."))
        val afterFirstCommit = repository.listMessages(conversationId)

        assertTrue(commit(TurnToolCommitOutcome.WRITE_COMPLETED, "알람 추가를 요청했습니다."))
        assertFalse(commit(TurnToolCommitOutcome.WRITE_REFUSED, "알람은 추가되지 않았습니다."))

        assertEquals(afterFirstCommit, repository.listMessages(conversationId))
        assertEquals(TurnOutcomeState.WRITE_UNKNOWN, database.turnOutcomeDao().find(turnId)?.state)
        assertEquals(1, outcomes.unresolvedSideEffects().size)
    }

    @Test
    fun repeatedWriteRefusedCommitStaysDefinitiveAndRejectsMismatch() = runBlocking {
        val conversationId = repository.createConversation("write refused replay")
        val userOrdinal = repository.appendMessage(
            conversationId,
            MessageRole.USER,
            "알람 맞춰 줘",
        )!!
        val turnId = "turn-88888888-8888-8888-8888-888888888888"
        val outcomes = TurnOutcomeRepository(database = database, clock = { now })
        assertTrue(outcomes.start(turnId, conversationId, userOrdinal))
        assertTrue(outcomes.armSideEffect(turnId, "alarm_set", TurnToolRisk.DATA_WRITE))

        suspend fun commit(outcome: TurnToolCommitOutcome, receipt: String): Boolean =
            repository.commitToolExecution(
                conversationId = conversationId,
                turnId = turnId,
                assistantText = "",
                toolReceipt = receipt,
                toolName = "alarm_set",
                toolRisk = TurnToolRisk.DATA_WRITE,
                trustedOrdinal = 1,
                outcome = outcome,
            )

        assertTrue(commit(TurnToolCommitOutcome.WRITE_REFUSED, "알람은 추가되지 않았습니다."))
        val afterFirstCommit = repository.listMessages(conversationId)

        assertTrue(commit(TurnToolCommitOutcome.WRITE_REFUSED, "알람은 추가되지 않았습니다."))
        assertFalse(commit(TurnToolCommitOutcome.WRITE_COMPLETED, "알람 추가를 요청했습니다."))

        assertEquals(afterFirstCommit, repository.listMessages(conversationId))
        assertEquals(TurnOutcomeState.FAILED, database.turnOutcomeDao().find(turnId)?.state)
        assertTrue(outcomes.unresolvedSideEffects().isEmpty())
    }

    @Test
    fun failedToolOutcomeTransitionRollsBackBothTranscriptRowsAndNeverClosesTurn() = runBlocking {
        val conversationId = repository.createConversation("원자 롤백")
        val userOrdinal = repository.appendMessage(
            conversationId,
            MessageRole.USER,
            "날씨 알려 줘",
        )!!
        val turnId = "turn-22222222-2222-2222-2222-222222222222"
        val outcomes = TurnOutcomeRepository(database = database, clock = { now })
        assertTrue(outcomes.start(turnId, conversationId, userOrdinal))

        assertFalse(
            repository.commitToolExecution(
                conversationId = conversationId,
                turnId = turnId,
                assistantText = "이 텍스트는 롤백되어야 합니다.",
                toolReceipt = "이 영수증도 롤백되어야 합니다.",
                toolName = "weather_current?argument=private",
                toolRisk = TurnToolRisk.READ_ONLY,
                trustedOrdinal = 1,
                outcome = TurnToolCommitOutcome.READ_COMPLETED,
            ),
        )

        assertEquals(listOf(MessageRole.USER), repository.listMessages(conversationId).map { it.role })
        val storedOutcome = database.turnOutcomeDao().find(turnId)!!
        assertEquals(TurnOutcomeState.STARTED, storedOutcome.state)
        assertEquals(TurnRecoverability.NONE, storedOutcome.recoverability)
        assertTrue(database.turnOutcomeDao().listReadExecutions(turnId).isEmpty())
    }

    @Test
    fun maxOrdinalCorruptionFailsClosedWithoutWrappingOrCompletingTurn() = runBlocking {
        val conversationId = repository.createConversation("ordinal 손상")
        database.messageDao().insert(
            MessageEntity(
                id = "corrupt-max-ordinal",
                conversationId = conversationId,
                ordinal = Long.MAX_VALUE,
                role = MessageRole.USER,
                text = "기존 요청",
                createdAtEpochMillis = now,
            ),
        )
        val turnId = "turn-33333333-3333-3333-3333-333333333333"
        val outcomes = TurnOutcomeRepository(database = database, clock = { now })
        assertTrue(outcomes.start(turnId, conversationId, Long.MAX_VALUE))

        assertNull(repository.appendMessage(conversationId, MessageRole.USER, "wrap 금지"))
        assertFalse(
            repository.commitToolExecution(
                conversationId = conversationId,
                turnId = turnId,
                assistantText = "저장되면 안 됩니다.",
                toolReceipt = "영수증도 저장되면 안 됩니다.",
                toolName = "weather_current",
                toolRisk = TurnToolRisk.READ_ONLY,
                trustedOrdinal = 1,
                outcome = TurnToolCommitOutcome.READ_COMPLETED,
            ),
        )
        assertFalse(
            repository.finalizeTurn(
                conversationId = conversationId,
                turnId = turnId,
                assistantText = "완료되면 안 됩니다.",
                completed = true,
                cancelled = false,
                failureCode = TurnOutcomeFailureCode.UNKNOWN,
            ),
        )

        assertEquals(listOf(Long.MAX_VALUE), repository.listMessages(conversationId).map { it.ordinal })
        assertEquals(TurnOutcomeState.STARTED, database.turnOutcomeDao().find(turnId)?.state)
        assertTrue(database.turnOutcomeDao().listReadExecutions(turnId).isEmpty())
    }

    @Test
    fun lateWriteRefusalCannotCommitAFalseReceiptOrClearUnknownSideEffect() = runBlocking {
        val conversationId = repository.createConversation("write refusal")
        val userOrdinal = repository.appendMessage(
            conversationId,
            MessageRole.USER,
            "알람 맞춰 줘",
        )!!
        val turnId = "turn-44444444-4444-4444-4444-444444444444"
        val outcomes = TurnOutcomeRepository(database = database, clock = { now })
        assertTrue(outcomes.start(turnId, conversationId, userOrdinal))
        assertTrue(outcomes.armSideEffect(turnId, "alarm_set", TurnToolRisk.DATA_WRITE))
        assertTrue(outcomes.recordTool(turnId, "alarm_set", TurnToolRisk.DATA_WRITE))

        assertFalse(
            repository.commitToolExecution(
                conversationId = conversationId,
                turnId = turnId,
                assistantText = "",
                toolReceipt = "알람이 추가되지 않았습니다.",
                toolName = "alarm_set",
                toolRisk = TurnToolRisk.DATA_WRITE,
                trustedOrdinal = 1,
                outcome = TurnToolCommitOutcome.WRITE_REFUSED,
            ),
        )

        assertEquals(listOf(MessageRole.USER), repository.listMessages(conversationId).map { it.role })
        assertEquals(TurnOutcomeState.WRITE_UNKNOWN, database.turnOutcomeDao().find(turnId)?.state)
        assertEquals(1, outcomes.unresolvedSideEffects().size)
    }

    @Test
    fun messagesAfterAnOrdinalReturnTheOldestPendingBatch() = runBlocking {
        val conversationId = repository.createConversation("순차 요약")
        repeat(75) { index ->
            repository.appendMessage(conversationId, MessageRole.USER, "메시지 $index")
        }

        val firstBatch = repository.listMessagesAfter(
            conversationId = conversationId,
            afterOrdinal = 0L,
            limit = 60,
        )
        val secondBatch = repository.listMessagesAfter(
            conversationId = conversationId,
            afterOrdinal = firstBatch.last().ordinal,
            limit = 60,
        )

        assertEquals((1L..60L).toList(), firstBatch.map(MessageEntity::ordinal))
        assertEquals((61L..75L).toList(), secondBatch.map(MessageEntity::ordinal))
    }

    @Test
    fun storedTextIsTrimmedAndCapped() = runBlocking {
        val conversationId = repository.createConversation("   ")
        repository.appendMessage(
            conversationId,
            MessageRole.USER,
            "  " + "가".repeat(ConversationRepository.MAX_MESSAGE_CHARACTERS + 500) + "  ",
        )

        val stored = repository.loadContext(conversationId)!!.recentMessages.single()
        assertEquals(ConversationRepository.MAX_MESSAGE_CHARACTERS, stored.text.length)
        assertEquals(
            ConversationRepository.DEFAULT_TITLE,
            database.conversationDao().find(conversationId)!!.title,
        )
    }

    @Test
    fun summarizingKeepsTheNewestMessagesVerbatim() = runBlocking {
        val conversationId = repository.createConversation("요약")
        repeat(10) { index -> repository.appendMessage(conversationId, MessageRole.USER, "턴 $index") }

        val replaced = repository.replaceSummary(
            conversationId = conversationId,
            summary = "앞선 열 개 메시지 요약",
            throughOrdinal = 10,
            keepRecentMessages = 3,
        )

        assertTrue(replaced)
        val context = repository.loadContext(conversationId)!!
        assertEquals("앞선 열 개 메시지 요약", context.summary)
        assertEquals(listOf(8L, 9L, 10L), context.recentMessages.map(StoredMessage::ordinal))
    }

    @Test
    fun summarizingRefusesToClaimMessagesThatDoNotExist() = runBlocking {
        val conversationId = repository.createConversation("요약")
        repository.appendMessage(conversationId, MessageRole.USER, "하나")

        assertFalse(
            repository.replaceSummary(
                conversationId = conversationId,
                summary = "존재하지 않는 범위",
                throughOrdinal = 99,
            ),
        )
        assertEquals(1L, repository.messageCount())
    }

    @Test
    fun deletingAConversationRemovesItsMessages() = runBlocking {
        val kept = repository.createConversation("남길 대화")
        val removed = repository.createConversation("지울 대화")
        repository.appendMessage(kept, MessageRole.USER, "유지")
        repository.appendMessage(removed, MessageRole.USER, "삭제")

        assertTrue(repository.deleteConversation(removed))

        assertEquals(1L, repository.conversationCount())
        assertEquals(1L, repository.messageCount())
        assertNull(repository.loadContext(removed))
    }

    @Test
    fun deletingEverythingLeavesNoTranscriptBehind() = runBlocking {
        repeat(3) { index ->
            val conversationId = repository.createConversation("대화 $index")
            repository.appendMessage(conversationId, MessageRole.USER, "내용 $index")
            repository.appendMessage(conversationId, MessageRole.TOOL_RECEIPT, "실행됨")
        }

        assertEquals(3, repository.deleteAllConversations())
        assertEquals(0L, repository.conversationCount())
        assertEquals(0L, repository.messageCount())
    }
}
