package com.personaledge.agent

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.data.MessageRole
import com.personaledge.core.data.PersonalEdgeDatabase
import com.personaledge.core.data.TurnOutcomeFailureCode
import com.personaledge.core.data.TurnOutcomeRepository
import com.personaledge.core.data.TurnRecoverability
import com.personaledge.core.data.TurnToolCommitOutcome
import com.personaledge.core.data.TurnToolRisk
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
class ChatHistoryCoordinatorTest {
    private lateinit var database: PersonalEdgeDatabase
    private lateinit var coordinator: ChatHistoryCoordinator
    private lateinit var outcomes: TurnOutcomeRepository

    @Before
    fun openInMemoryDatabase() {
        database = Room
            .inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                PersonalEdgeDatabase::class.java,
            )
            .build()
        outcomes = TurnOutcomeRepository(database)
        coordinator = ChatHistoryCoordinator(ConversationRepository(database), outcomes)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private suspend fun recordTurn(
        conversationId: String?,
        prompt: String,
        answer: String,
        toolReceipt: String? = null,
    ) {
        coordinator.record(conversationId, MessageRole.USER, prompt)
        toolReceipt?.let { receipt ->
            coordinator.record(conversationId, MessageRole.TOOL_RECEIPT, receipt)
        }
        coordinator.record(conversationId, MessageRole.ASSISTANT, answer)
    }

    @Test
    fun theFirstMessageCreatesAConversationTitledFromThePrompt() = runBlocking {
        val id = coordinator.ensureConversation(null, "내일 오후 3시에 치과 일정 넣어줘")

        assertNotNull(id)
        assertEquals(
            listOf("내일 오후 3시에 치과 일정 넣어줘"),
            coordinator.listConversations().map(ConversationSummaryUi::title),
        )
    }

    @Test
    fun anExistingConversationIsReusedRatherThanDuplicated() = runBlocking {
        val first = coordinator.ensureConversation(null, "첫 질문")
        val second = coordinator.ensureConversation(first, "두 번째 질문")

        assertEquals(first, second)
        assertEquals(1, coordinator.listConversations().size)
    }

    @Test
    fun aRestoredTranscriptKeepsOrderAndRoles() = runBlocking {
        val id = coordinator.ensureConversation(null, "내일 일정 알려줘")
        recordTurn(id, "내일 일정 알려줘", "일정을 확인했습니다.", "캘린더에서 일정을 읽었습니다.")

        val restored = coordinator.restoreMostRecent()

        assertEquals(id, restored.conversationId)
        assertEquals(
            listOf(ChatRole.USER, ChatRole.TOOL, ChatRole.ASSISTANT),
            restored.entries.map(ChatEntry::role),
        )
        assertEquals("내일 일정 알려줘", restored.entries.first().text)
        assertEquals("일정을 확인했습니다.", restored.entries.last().text)
    }

    @Test
    fun theNewestConversationIsTheOneRestored() = runBlocking {
        val older = coordinator.ensureConversation(null, "예전 대화")
        recordTurn(older, "예전 대화", "예전 답변")
        val newer = coordinator.ensureConversation(null, "최근 대화")
        recordTurn(newer, "최근 대화", "최근 답변")

        assertEquals(newer, coordinator.restoreMostRecent().conversationId)
    }

    @Test
    fun switchingLoadsTheOtherThreadWithoutTouchingIt() = runBlocking {
        val older = coordinator.ensureConversation(null, "예전 대화")
        recordTurn(older, "예전 대화", "예전 답변")
        val newer = coordinator.ensureConversation(null, "최근 대화")
        recordTurn(newer, "최근 대화", "최근 답변")

        val switched = coordinator.switchTo(older!!) as ConversationSwitchResult.Success

        assertEquals(older, switched.restored.conversationId)
        assertEquals(listOf("예전 대화", "예전 답변"), switched.restored.entries.map(ChatEntry::text))
        assertEquals(2, coordinator.listConversations().size)
    }

    @Test
    fun switchingToAMissingConversationDoesNotActivateAPhantomThread() = runBlocking {
        assertEquals(ConversationSwitchResult.NotFound, coordinator.switchTo("missing"))
    }

    @Test
    fun aDeletedActiveConversationIsReplacedBeforeTheNextWrite() = runBlocking {
        val removed = coordinator.ensureConversation(null, "삭제될 대화")!!
        assertTrue(coordinator.delete(removed))

        val replacement = coordinator.ensureConversation(removed, "새 질문")

        assertNotNull(replacement)
        assertTrue(replacement != removed)
        assertTrue(coordinator.record(replacement, MessageRole.USER, "새 질문"))
        assertEquals(replacement, coordinator.restoreMostRecent().conversationId)
    }

    @Test
    fun restoringAConversationKeepsTheNewestFiveHundredMessages() = runBlocking {
        val id = coordinator.ensureConversation(null, "긴 대화")!!
        repeat(505) { index ->
            assertTrue(coordinator.record(id, MessageRole.USER, "메시지 ${index + 1}"))
        }

        val restored = coordinator.restoreMostRecent()

        assertEquals(500, restored.entries.size)
        assertEquals("메시지 6", restored.entries.first().text)
        assertEquals("메시지 505", restored.entries.last().text)
    }

    @Test
    fun blankTextIsNeverStored() = runBlocking {
        val id = coordinator.ensureConversation(null, "질문")
        coordinator.record(id, MessageRole.USER, "질문")
        // A cancelled turn leaves an empty assistant entry; storing it would show a blank bubble.
        coordinator.record(id, MessageRole.ASSISTANT, "   ")

        assertEquals(listOf(ChatRole.USER), coordinator.restoreMostRecent().entries.map(ChatEntry::role))
    }

    @Test
    fun aMissingPostSearchAnswerRecoversTheOriginalReadRequest() = runBlocking {
        val id = coordinator.ensureConversation(null, "오늘 경기도 이천 날씨 알려줘")
        coordinator.record(id, MessageRole.USER, "오늘 경기도 이천 날씨 알려줘")
        coordinator.record(id, MessageRole.TOOL_RECEIPT, "웹 검색을 완료했습니다.")

        assertEquals(
            "오늘 경기도 이천 날씨 알려줘",
            coordinator.unfinishedReadRequestForFollowUp(id, "왜 답변을 안 해줘")?.text,
        )
        assertEquals(
            "오늘 경기도 이천 날씨 알려줘",
            coordinator.unfinishedReadRequestForFollowUp(id, "계속 답해줘")?.text,
        )
    }

    @Test
    fun aMissingPostWeatherAnswerRecoversTheOriginalReadRequest() = runBlocking {
        val id = coordinator.ensureConversation(null, "오늘 동탄 날씨를 알려줘")
        coordinator.record(id, MessageRole.USER, "오늘 동탄 날씨를 알려줘")
        coordinator.record(id, MessageRole.TOOL_RECEIPT, "현재 및 오늘 날씨를 확인했습니다.")

        assertEquals(
            "오늘 동탄 날씨를 알려줘",
            coordinator.unfinishedReadRequestForFollowUp(id, "왜 답변을 안 해줘")?.text,
        )
        assertTrue(coordinator.hasRecentWeatherRead(id))
    }

    @Test
    fun legacyRetryKeepsASecondInterruptedReadBoundToTheOriginalRequestRow() = runBlocking {
        val originalRequest = "오늘 경기도 이천 날씨 알려줘"
        val followUp = "왜 답변을 안 해줘"
        val conversationId = coordinator.ensureConversation(null, originalRequest)!!
        val originalOrdinal = coordinator.recordOrdinal(
            conversationId,
            MessageRole.USER,
            originalRequest,
        )!!
        assertTrue(
            coordinator.record(
                conversationId,
                MessageRole.TOOL_RECEIPT,
                "웹 검색을 완료했습니다.",
            ),
        )
        val unfinished = requireNotNull(
            coordinator.unfinishedReadRequestForFollowUp(conversationId, followUp),
        )
        assertEquals(originalOrdinal, unfinished.userMessageOrdinal)
        assertEquals(originalRequest, unfinished.text)

        val followUpOrdinal = coordinator.recordOrdinal(
            conversationId,
            MessageRole.USER,
            followUp,
        )!!
        val retryTurnId = "turn-33333333-3333-3333-3333-333333333333"
        assertTrue(
            coordinator.startTurnRecoveryCapsule(
                turnId = retryTurnId,
                conversationId = conversationId,
                persistedUserMessageOrdinal = followUpOrdinal,
                unfinishedReadRequest = unfinished,
                predecessorTurnId = null,
            ),
        )
        assertTrue(
            outcomes.recordTool(
                retryTurnId,
                "weather_current",
                TurnToolRisk.READ_ONLY,
                1,
            ),
        )
        assertTrue(
            outcomes.fail(
                retryTurnId,
                cancelled = false,
                failureCode = TurnOutcomeFailureCode.CONTEXT_BUDGET_EXCEEDED,
            ),
        )

        val secondRecovery = requireNotNull(outcomes.latestRecovery(conversationId))
        assertEquals(TurnRecoverability.REQUERY_READ, secondRecovery.recoverability)
        assertEquals(originalRequest, secondRecovery.userRequest)
        assertFalse(secondRecovery.userRequest == followUp)
    }

    @Test
    fun weatherCorrectionContextRequiresAnAppAuthoredWeatherReceipt() = runBlocking {
        val plain = coordinator.ensureConversation(null, "서울이 아니라 동탄")
        coordinator.record(plain, MessageRole.USER, "현재 및 오늘 날씨를 확인했습니다.")
        assertFalse(coordinator.hasRecentWeatherRead(plain))

        val old = coordinator.ensureConversation(null, "오래된 날씨")
        coordinator.record(old, MessageRole.TOOL_RECEIPT, "현재 및 오늘 날씨를 확인했습니다.")
        repeat(6) { index -> coordinator.record(old, MessageRole.USER, "새 메시지 $index") }
        assertFalse(coordinator.hasRecentWeatherRead(old))
    }

    @Test
    fun aPreToolFailureIsNotTreatedAsProofOfARead() = runBlocking {
        val request = "SK하이닉스 김재범이란 사람에 대해 찾아서 알려줘"
        val id = coordinator.ensureConversation(null, request)
        coordinator.record(id, MessageRole.USER, request)

        assertNull(coordinator.unfinishedReadRequestForFollowUp(id, "왜 중단했어?"))
    }

    @Test
    fun subjectlessWebFollowUpUsesOnlyTheImmediatelyPreviousCompletedUserQuestion() = runBlocking {
        val original = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘"
        val conversationId = coordinator.ensureConversation(null, original)!!
        val originalOrdinal = coordinator.recordOrdinal(
            conversationId,
            MessageRole.USER,
            original,
        )!!
        assertTrue(
            coordinator.record(
                conversationId,
                MessageRole.ASSISTANT,
                "제가 가지고 있는 정보로는 자세한 정보를 제공하기 어렵습니다.",
            ),
        )

        val inherited = requireNotNull(
            coordinator.contextualWebSearchRequestForFollowUp(
                conversationId,
                "잘 모르겠으면 웹에서 찾아서 알려줘",
            ),
        )

        assertEquals(conversationId, inherited.conversationId)
        assertEquals(originalOrdinal, inherited.userMessageOrdinal)
        assertTrue(inherited.inheritLongFormRequest)
        assertFalse(inherited.toString().contains("그대들은"))
        assertNull(
            coordinator.contextualWebSearchRequestForFollowUp(
                conversationId,
                "OpenAI 최신 뉴스를 검색해줘",
            ),
        )
        assertNull(
            coordinator.contextualWebSearchRequestForFollowUp(
                null,
                "잘 모르겠으면 웹에서 찾아서 알려줘",
            ),
        )
    }

    @Test
    fun contextualWebSearchRecoveryStaysBoundToTheOriginalQuestionRow() = runBlocking {
        val original = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘"
        val followUp = "잘 모르겠으면 웹에서 찾아서 알려줘"
        val conversationId = coordinator.ensureConversation(null, original)!!
        val originalOrdinal = coordinator.recordOrdinal(
            conversationId,
            MessageRole.USER,
            original,
        )!!
        val originalTurnId = "turn-44444444-5555-5555-5555-555555555555"
        assertTrue(outcomes.start(originalTurnId, conversationId, originalOrdinal))
        assertTrue(outcomes.complete(originalTurnId))
        coordinator.record(conversationId, MessageRole.ASSISTANT, "잘 모르겠습니다.")
        val inherited = requireNotNull(
            coordinator.contextualWebSearchRequestForFollowUp(conversationId, followUp),
        )
        assertEquals(originalOrdinal, inherited.userMessageOrdinal)
        val followUpOrdinal = coordinator.recordOrdinal(
            conversationId,
            MessageRole.USER,
            followUp,
        )!!
        val turnId = "turn-55555555-5555-5555-5555-555555555555"

        assertTrue(
            coordinator.startTurnRecoveryCapsule(
                turnId = turnId,
                conversationId = conversationId,
                persistedUserMessageOrdinal = followUpOrdinal,
                unfinishedReadRequest = null,
                contextualWebSearchRequest = inherited,
                predecessorTurnId = null,
            ),
        )
        assertTrue(outcomes.recordTool(turnId, "web_search", TurnToolRisk.READ_ONLY, 1))
        assertTrue(
            outcomes.fail(
                turnId,
                cancelled = false,
                failureCode = TurnOutcomeFailureCode.CONTEXT_BUDGET_EXCEEDED,
            ),
        )

        val recovery = requireNotNull(outcomes.latestRecovery(conversationId))
        assertEquals(original, recovery.userRequest)
        assertFalse(recovery.userRequest == followUp)
    }

    @Test
    fun contextualWebSearchNeverInheritsWritesOrIncompleteTurns() = runBlocking {
        val write = "김재범에게 카카오 메시지를 보내는 방법에 대해 알려줘"
        val writeConversation = coordinator.ensureConversation(null, write)!!
        coordinator.record(writeConversation, MessageRole.USER, write)
        coordinator.record(writeConversation, MessageRole.ASSISTANT, "답변")
        assertNull(
            coordinator.contextualWebSearchRequestForFollowUp(
                writeConversation,
                "모르면 웹에서 찾아줘",
            ),
        )

        val incomplete = "그대들은 어떻게 살것인가 영화에 대해 알려줘"
        val incompleteConversation = coordinator.ensureConversation(null, incomplete)!!
        coordinator.record(incompleteConversation, MessageRole.USER, incomplete)
        assertNull(
            coordinator.contextualWebSearchRequestForFollowUp(
                incompleteConversation,
                "모르면 웹에서 찾아줘",
            ),
        )
    }

    @Test
    fun recoveryDoesNotReplayCompletedOrWriteTurns() = runBlocking {
        val completed = coordinator.ensureConversation(null, "이천 날씨")
        coordinator.record(completed, MessageRole.USER, "이천 날씨")
        coordinator.record(completed, MessageRole.TOOL_RECEIPT, "웹 검색을 완료했습니다.")
        coordinator.record(completed, MessageRole.ASSISTANT, "이천 날씨는 맑습니다.")
        assertNull(coordinator.unfinishedReadRequestForFollowUp(completed, "다시 답해줘"))

        val write = coordinator.ensureConversation(null, "카카오톡으로 안녕 보내줘")
        coordinator.record(write, MessageRole.USER, "카카오톡으로 안녕 보내줘")
        coordinator.record(write, MessageRole.TOOL_RECEIPT, "카카오톡 공유 화면을 열었습니다.")
        assertNull(coordinator.unfinishedReadRequestForFollowUp(write, "왜 답변을 안 해줘"))

        val unansweredWrite = coordinator.ensureConversation(null, "김재범에게 메시지 보내줘")
        coordinator.record(unansweredWrite, MessageRole.USER, "김재범에게 메시지 보내줘")
        assertNull(coordinator.unfinishedReadRequestForFollowUp(unansweredWrite, "왜 중단했어?"))

        val mixed = coordinator.ensureConversation(null, "일정을 만들고 다시 조회해줘")
        coordinator.record(mixed, MessageRole.USER, "일정을 만들고 다시 조회해줘")
        coordinator.record(mixed, MessageRole.TOOL_RECEIPT, "캘린더에 일정을 등록했습니다.")
        coordinator.record(mixed, MessageRole.TOOL_RECEIPT, "캘린더에서 일정을 읽었습니다.")
        assertNull(coordinator.unfinishedReadRequestForFollowUp(mixed, "계속 답해줘"))
    }

    @Test
    fun unrelatedFollowUpsDoNotRecoverAnOldReadRequest() = runBlocking {
        val id = coordinator.ensureConversation(null, "오늘 경기도 이천 날씨 알려줘")
        coordinator.record(id, MessageRole.USER, "오늘 경기도 이천 날씨 알려줘")
        coordinator.record(id, MessageRole.TOOL_RECEIPT, "웹 검색을 완료했습니다.")

        assertNull(coordinator.unfinishedReadRequestForFollowUp(id, "서울 맛집 추천해줘"))
    }

    @Test
    fun aTurnWithNoConversationIsSilentlyNotPersisted() = runBlocking {
        // Models the storage-unavailable path: the turn must still run, just without history.
        coordinator.record(null, MessageRole.USER, "저장되지 않는 질문")

        assertTrue(coordinator.listConversations().isEmpty())
        assertNull(coordinator.restoreMostRecent().conversationId)
    }

    @Test
    fun typedReadRecoveryIsTransientAndCanBeInspectedWithoutConsumption() = runBlocking {
        val conversationId = coordinator.ensureConversation(null, "이천 날씨 알려줘")!!
        val ordinal = coordinator.recordOrdinal(
            conversationId,
            MessageRole.USER,
            "이천 날씨 알려줘",
        )!!
        val turnId = "turn-11111111-1111-1111-1111-111111111111"
        assertTrue(outcomes.start(turnId, conversationId, ordinal))
        assertTrue(outcomes.recordTool(turnId, "calendar_query", TurnToolRisk.READ_ONLY, 1))
        assertTrue(outcomes.recordTool(turnId, "weather_current", TurnToolRisk.READ_ONLY, 2))
        assertTrue(
            outcomes.fail(
                turnId,
                cancelled = false,
                failureCode = TurnOutcomeFailureCode.CONTEXT_BUDGET_EXCEEDED,
            ),
        )

        val restored = coordinator.restoreMostRecent()
        val recoveryEntry = restored.entries.last()
        assertEquals(ChatRole.STATUS, recoveryEntry.role)
        assertEquals(ChatRecoveryType.REQUERY_READ, recoveryEntry.recoveryAction?.type)
        val action = recoveryEntry.recoveryAction!!
        assertEquals(listOf("calendar_query", "weather_current"), action.expectedReadTools)
        assertFalse(action.toString().contains(action.label))
        val inspected = coordinator.recoveryRecord(action)
        assertEquals(TurnRecoverability.REQUERY_READ, inspected?.recoverability)
        assertEquals(
            listOf("calendar_query", "weather_current"),
            inspected?.expectedReadTools,
        )
        assertNull(
            coordinator.recoveryRecord(
                ChatRecoveryAction(
                    turnId = action.turnId,
                    conversationId = action.conversationId,
                    type = ChatRecoveryType.REQUERY_READ,
                    label = "변조된 복구",
                    expectedReadTools = listOf("alarm_next", "weather_current"),
                ),
            ),
        )
        assertEquals(
            ChatRecoveryType.REQUERY_READ,
            coordinator.restoreMostRecent().entries.last().recoveryAction?.type,
        )
    }

    @Test
    fun typedWriteRecoveryNeverOffersReplay() = runBlocking {
        val conversationId = coordinator.ensureConversation(null, "알람 맞춰줘")!!
        val ordinal = coordinator.recordOrdinal(conversationId, MessageRole.USER, "알람 맞춰줘")!!
        val turnId = "turn-22222222-2222-2222-2222-222222222222"
        assertTrue(outcomes.start(turnId, conversationId, ordinal))
        assertTrue(outcomes.armSideEffect(turnId, "alarm_set", TurnToolRisk.DATA_WRITE))
        assertTrue(outcomes.recordTool(turnId, "alarm_set", TurnToolRisk.DATA_WRITE))
        assertTrue(
            outcomes.fail(
                turnId,
                cancelled = true,
                failureCode = TurnOutcomeFailureCode.USER_CANCELLED,
            ),
        )

        val recovery = coordinator.restoreMostRecent().entries.last().recoveryAction
        assertEquals(ChatRecoveryType.VERIFY_EXTERNAL_STATE, recovery?.type)
        assertTrue(recovery?.label?.contains("확인") == true)
        assertTrue(coordinator.dismissRecovery(recovery!!))
        assertNull(coordinator.restoreMostRecent().entries.last().recoveryAction)
    }

    @Test
    fun unresolvedWriteRemainsGloballyDismissibleAfterTranscriptDeletion() = runBlocking {
        val conversationId = coordinator.ensureConversation(null, "알람 맞춰줘")!!
        val userOrdinal = coordinator.recordOrdinal(
            conversationId,
            MessageRole.USER,
            "알람 맞춰줘",
        )!!
        val turnId = "turn-44444444-4444-4444-4444-444444444444"
        assertTrue(outcomes.start(turnId, conversationId, userOrdinal))
        assertTrue(outcomes.armSideEffect(turnId, "alarm_set", TurnToolRisk.DATA_WRITE))
        assertTrue(
            coordinator.commitToolExecution(
                conversationId = conversationId,
                turnId = turnId,
                assistantText = "",
                toolReceipt = "시계 앱에 알람 추가를 요청했습니다.",
                toolName = "alarm_set",
                toolRisk = TurnToolRisk.DATA_WRITE,
                trustedOrdinal = 1,
                outcome = TurnToolCommitOutcome.WRITE_COMPLETED,
            ),
        )
        assertTrue(coordinator.delete(conversationId))

        val globalEntry = coordinator.unresolvedSideEffectEntries().single()
        val action = globalEntry.recoveryAction!!
        assertEquals(ChatRecoveryType.VERIFY_EXTERNAL_STATE, action.type)
        assertEquals(conversationId, action.conversationId)
        assertNotNull(coordinator.recoveryRecord(action))
        assertTrue(coordinator.dismissRecovery(action))
        assertTrue(coordinator.unresolvedSideEffectEntries().isEmpty())
    }

    @Test
    fun deletingOneThreadLeavesTheOthers() = runBlocking {
        val kept = coordinator.ensureConversation(null, "남길 대화")
        recordTurn(kept, "남길 대화", "답변")
        val removed = coordinator.ensureConversation(null, "지울 대화")
        recordTurn(removed, "지울 대화", "답변")

        assertTrue(coordinator.delete(removed!!))

        assertEquals(listOf("남길 대화"), coordinator.listConversations().map(ConversationSummaryUi::title))
        assertEquals(kept, coordinator.restoreMostRecent().conversationId)
    }

    @Test
    fun deletingEverythingLeavesNothingToRestore() = runBlocking {
        repeat(3) { index ->
            val id = coordinator.ensureConversation(null, "대화 $index")
            recordTurn(id, "대화 $index", "답변 $index")
        }

        assertTrue(coordinator.deleteAll())

        assertTrue(coordinator.listConversations().isEmpty())
        assertNull(coordinator.restoreMostRecent().conversationId)
        assertTrue(coordinator.restoreMostRecent().entries.isEmpty())
    }
}
