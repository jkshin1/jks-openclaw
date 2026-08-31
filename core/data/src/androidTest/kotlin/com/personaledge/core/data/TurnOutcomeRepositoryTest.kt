package com.personaledge.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
class TurnOutcomeRepositoryTest {
    private lateinit var database: PersonalEdgeDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var outcomes: TurnOutcomeRepository
    private val ids = AtomicInteger()
    private var now = 1_000_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            PersonalEdgeDatabase::class.java,
        ).build()
        conversations = ConversationRepository(
            database = database,
            clock = { now },
            idFactory = { "fixture-${ids.incrementAndGet()}" },
        )
        outcomes = TurnOutcomeRepository(database = database, clock = { now })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun startedRequestIsNotRecoverableUntilATrustedReadCompletes() = runBlocking {
        val turn = startTurn(
            turnId = TURN_ONE,
            userRequest = "이천 날씨를 다시 확인해 줘",
        )

        assertNull(outcomes.latestRecovery(turn.conversationId))
        assertTrue(outcomes.recordTool(turn.turnId, "weather", TurnToolRisk.READ_ONLY, 1))
        val recovery = outcomes.latestRecovery(turn.conversationId)

        assertNotNull(recovery)
        assertEquals(TurnOutcomeState.READ_EXECUTED, recovery!!.state)
        assertEquals(TurnRecoverability.REQUERY_READ, recovery.recoverability)
        assertEquals("이천 날씨를 다시 확인해 줘", recovery.userRequest)
        assertEquals(TurnToolRisk.READ_ONLY, recovery.lastToolRisk)
        assertEquals("weather", recovery.lastToolName)
        assertEquals(listOf("weather"), recovery.expectedReadTools)
    }

    @Test
    fun completedReadEnablesRestrictedRequeryAndSideEffectArmOverridesIt() = runBlocking {
        val pureRead = startTurn(
            turnId = TURN_ONE,
            userRequest = "날씨를 조회해 줘",
        )
        val mixed = startTurn(
            turnId = TURN_TWO,
            userRequest = "날씨를 조회하고 메시지로 보내 줘",
        )

        assertTrue(outcomes.recordTool(pureRead.turnId, "weather", TurnToolRisk.READ_ONLY, 1))
        assertTrue(outcomes.recordTool(mixed.turnId, "weather", TurnToolRisk.READ_ONLY, 1))
        assertTrue(outcomes.armSideEffect(mixed.turnId, "message_send", TurnToolRisk.COMMUNICATION))

        val pureReadState = database.turnOutcomeDao().find(pureRead.turnId)!!
        val mixedState = database.turnOutcomeDao().find(mixed.turnId)!!
        assertEquals(TurnOutcomeState.READ_EXECUTED, pureReadState.state)
        assertEquals(TurnRecoverability.REQUERY_READ, pureReadState.recoverability)
        assertEquals(TurnOutcomeState.WRITE_PENDING, mixedState.state)
        assertEquals(TurnRecoverability.VERIFY_EXTERNAL_STATE, mixedState.recoverability)
        assertNotNull(outcomes.latestRecovery(pureRead.conversationId))
        assertNotNull(outcomes.latestRecovery(mixed.conversationId))
    }

    @Test
    fun sideEffectPermanentlyChangesRecoveryToVerifyOnly() = runBlocking {
        val turn = startTurn(
            turnId = TURN_ONE,
            userRequest = "일정을 확인하고 필요하면 등록해 줘",
        )

        assertTrue(outcomes.armSideEffect(turn.turnId, "calendar_create", TurnToolRisk.DATA_WRITE))
        assertTrue(outcomes.recordTool(turn.turnId, "calendar_create", TurnToolRisk.DATA_WRITE))
        now++
        assertTrue(outcomes.recordTool(turn.turnId, "calendar_query", TurnToolRisk.READ_ONLY, 2))
        now++
        assertTrue(
            outcomes.fail(
                turnId = turn.turnId,
                cancelled = false,
                failureCode = TurnOutcomeFailureCode.TOOL_FAILURE,
            ),
        )

        val stored = database.turnOutcomeDao().find(turn.turnId)!!
        val recovery = outcomes.latestRecovery(turn.conversationId)!!
        assertEquals(TurnOutcomeState.WRITE_UNKNOWN, stored.state)
        assertEquals(TurnRecoverability.VERIFY_EXTERNAL_STATE, stored.recoverability)
        assertEquals(TurnOutcomeFailureCode.TOOL_FAILURE, stored.failureCode)
        assertEquals("calendar_create", stored.lastToolName)
        assertEquals(TurnToolRisk.DATA_WRITE, stored.lastToolRisk)
        assertEquals(TurnOutcomeState.WRITE_UNKNOWN, recovery.state)
        assertEquals(TurnRecoverability.VERIFY_EXTERNAL_STATE, recovery.recoverability)
    }

    @Test
    fun completedAnswerClosesRecoveryCapsule() = runBlocking {
        val turn = startTurn(TURN_ONE, "검색해 줘")
        assertTrue(outcomes.recordTool(turn.turnId, "web_search", TurnToolRisk.READ_ONLY, 1))

        assertTrue(outcomes.complete(turn.turnId))

        assertEquals(
            TurnOutcomeState.ANSWER_COMPLETE,
            database.turnOutcomeDao().find(turn.turnId)?.state,
        )
        assertNull(outcomes.latestRecovery(turn.conversationId))
        assertTrue(outcomes.complete(turn.turnId))
    }

    @Test
    fun contextualReadKeepsItsOwnTurnBindingAndRecoversFromTheEarlierUserRow() = runBlocking {
        val original = "그대들은 어떻게 살 것인가 영화에 대해 알려줘"
        val conversationId = conversations.createConversation("문맥 검색")
        val originalOrdinal = conversations.appendMessage(
            conversationId = conversationId,
            role = MessageRole.USER,
            text = original,
        )!!
        assertTrue(outcomes.start(TURN_ONE, conversationId, originalOrdinal))
        assertTrue(outcomes.complete(TURN_ONE))
        conversations.appendMessage(conversationId, MessageRole.ASSISTANT, "잘 모르겠습니다.")
        val followUpOrdinal = conversations.appendMessage(
            conversationId = conversationId,
            role = MessageRole.USER,
            text = "잘 모르겠으면 웹에서 찾아줘",
        )!!

        assertTrue(
            outcomes.startContextualRead(
                turnId = TURN_TWO,
                conversationId = conversationId,
                userMessageOrdinal = followUpOrdinal,
                recoverySourceUserMessageOrdinal = originalOrdinal,
            ),
        )
        assertEquals(TurnOutcomeState.ANSWER_COMPLETE, database.turnOutcomeDao().find(TURN_ONE)?.state)
        assertEquals(
            originalOrdinal,
            database.turnOutcomeDao().find(TURN_TWO)?.recoverySourceUserMessageOrdinal,
        )
        assertTrue(outcomes.recordTool(TURN_TWO, "web_search", TurnToolRisk.READ_ONLY, 1))
        assertTrue(
            outcomes.fail(
                TURN_TWO,
                cancelled = false,
                failureCode = TurnOutcomeFailureCode.CONTEXT_BUDGET_EXCEEDED,
            ),
        )

        assertEquals(original, outcomes.latestRecovery(conversationId)?.userRequest)
        assertFalse(
            outcomes.startContextualRead(
                turnId = "turn-33333333-3333-3333-3333-333333333333",
                conversationId = conversationId,
                userMessageOrdinal = originalOrdinal,
                recoverySourceUserMessageOrdinal = followUpOrdinal,
            ),
        )
    }

    @Test
    fun sideEffectMustBeDurablyArmedAndARefusalClearsOnlyThePendingWrite() = runBlocking {
        val turn = startTurn(TURN_ONE, "오전 7시에 알람 맞춰 줘")

        assertFalse(outcomes.armSideEffect("missing-turn", "alarm_set", TurnToolRisk.DATA_WRITE))
        assertFalse(outcomes.armSideEffect(turn.turnId, "alarm_set", TurnToolRisk.READ_ONLY))
        assertTrue(outcomes.armSideEffect(turn.turnId, "alarm_set", TurnToolRisk.DATA_WRITE))
        assertEquals(TurnOutcomeState.WRITE_PENDING, database.turnOutcomeDao().find(turn.turnId)?.state)

        assertTrue(outcomes.recordWriteRefused(turn.turnId, "alarm_set"))
        val refused = database.turnOutcomeDao().find(turn.turnId)!!
        assertEquals(TurnOutcomeState.FAILED, refused.state)
        assertEquals(TurnRecoverability.NONE, refused.recoverability)
        assertNull(refused.lastToolName)
        assertEquals(TurnToolRisk.NONE, refused.lastToolRisk)
        assertTrue(outcomes.unresolvedSideEffects().isEmpty())
    }

    @Test
    fun aLaterRefusalNeverClearsAnAlreadyUnknownWrite() = runBlocking {
        val turn = startTurn(TURN_ONE, "일정을 등록해 줘")
        assertTrue(outcomes.armSideEffect(turn.turnId, "calendar_create", TurnToolRisk.DATA_WRITE))
        assertTrue(outcomes.recordTool(turn.turnId, "calendar_create", TurnToolRisk.DATA_WRITE))

        assertTrue(outcomes.recordWriteRefused(turn.turnId, "calendar_create"))

        val stored = database.turnOutcomeDao().find(turn.turnId)!!
        assertEquals(TurnOutcomeState.WRITE_UNKNOWN, stored.state)
        assertEquals(TurnRecoverability.VERIFY_EXTERNAL_STATE, stored.recoverability)
        assertEquals("calendar_create", stored.lastToolName)
    }

    @Test
    fun aSecondSideEffectIsVetoedOnceOneWriteNeedsVerification() = runBlocking {
        val turn = startTurn(TURN_ONE, "일정을 만들고 메시지로 보내 줘")
        assertTrue(outcomes.armSideEffect(turn.turnId, "calendar_create", TurnToolRisk.DATA_WRITE))
        assertTrue(outcomes.recordTool(turn.turnId, "calendar_create", TurnToolRisk.DATA_WRITE))

        assertFalse(
            outcomes.armSideEffect(
                turn.turnId,
                "kakao_share_message",
                TurnToolRisk.COMMUNICATION,
            ),
        )

        val stored = database.turnOutcomeDao().find(turn.turnId)!!
        assertEquals(TurnOutcomeState.WRITE_UNKNOWN, stored.state)
        assertEquals("calendar_create", stored.lastToolName)
        assertEquals(TurnToolRisk.DATA_WRITE, stored.lastToolRisk)
    }

    @Test
    fun readRecoveryHandoffAtomicallyCreatesOneRestrictedSuccessor() = runBlocking {
        val predecessor = startTurn(TURN_ONE, "이천 날씨를 알려 줘")
        assertTrue(
            outcomes.recordTool(
                predecessor.turnId,
                "calendar_query",
                TurnToolRisk.READ_ONLY,
                1,
            ),
        )
        assertTrue(outcomes.recordTool(predecessor.turnId, "weather", TurnToolRisk.READ_ONLY, 2))
        val successorOrdinal = conversations.appendMessage(
            conversationId = predecessor.conversationId,
            role = MessageRole.USER,
            text = "이천 날씨를 알려 줘",
        )!!

        assertTrue(
            outcomes.startSuccessor(
                predecessorTurnId = predecessor.turnId,
                turnId = TURN_TWO,
                conversationId = predecessor.conversationId,
                userMessageOrdinal = successorOrdinal,
            ),
        )
        assertNull(database.turnOutcomeDao().find(predecessor.turnId))
        val successor = database.turnOutcomeDao().find(TURN_TWO)!!
        assertEquals(TurnOutcomeState.STARTED, successor.state)
        assertEquals(TurnRecoverability.REQUERY_READ, successor.recoverability)
        assertEquals(
            listOf("calendar_query", "weather"),
            database.turnOutcomeDao().listReadExecutions(TURN_TWO).map { it.toolName },
        )
        val restartedRepository = TurnOutcomeRepository(database = database, clock = { now })
        val restartedRecovery = restartedRepository.latestRecovery(predecessor.conversationId)!!
        assertEquals(TurnOutcomeState.STARTED, restartedRecovery.state)
        assertEquals(
            listOf("calendar_query", "weather"),
            restartedRecovery.expectedReadTools,
        )
        assertFalse(
            restartedRepository.recordTool(
                TURN_TWO,
                "alarm_next",
                TurnToolRisk.READ_ONLY,
                1,
            ),
        )
        assertTrue(
            restartedRepository.recordTool(
                TURN_TWO,
                "calendar_query",
                TurnToolRisk.READ_ONLY,
                1,
            ),
        )
        assertFalse(
            outcomes.startSuccessor(
                predecessorTurnId = predecessor.turnId,
                turnId = "turn-33333333-3333-3333-3333-333333333333",
                conversationId = predecessor.conversationId,
                userMessageOrdinal = successorOrdinal,
            ),
        )
    }

    @Test
    fun readExecutionIdentityPreservesOrderMultiplicityAndIsIdempotent() = runBlocking {
        val turn = startTurn(TURN_ONE, "일정과 날씨를 두 번 비교해 줘")

        assertFalse(outcomes.recordTool(turn.turnId, "calendar_query", TurnToolRisk.READ_ONLY))
        assertFalse(
            outcomes.recordTool(turn.turnId, "calendar_query", TurnToolRisk.READ_ONLY, 2),
        )
        assertTrue(
            outcomes.recordTool(turn.turnId, "calendar_query", TurnToolRisk.READ_ONLY, 1),
        )
        assertTrue(outcomes.recordTool(turn.turnId, "weather", TurnToolRisk.READ_ONLY, 2))
        assertTrue(outcomes.recordTool(turn.turnId, "weather", TurnToolRisk.READ_ONLY, 3))
        assertTrue(outcomes.recordTool(turn.turnId, "weather", TurnToolRisk.READ_ONLY, 2))
        assertTrue(outcomes.recordTool(turn.turnId, "web_search", TurnToolRisk.READ_ONLY, 4))
        assertFalse(outcomes.recordTool(turn.turnId, "alarm_next", TurnToolRisk.READ_ONLY, 2))
        assertFalse(outcomes.recordTool(turn.turnId, "weather", TurnToolRisk.READ_ONLY, 5))

        val rows = database.turnOutcomeDao().listReadExecutions(turn.turnId)
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.ordinal })
        assertEquals(
            listOf("calendar_query", "weather", "weather", "web_search"),
            rows.map { it.toolName },
        )
        assertEquals(
            listOf("calendar_query", "weather", "weather", "web_search"),
            outcomes.latestRecovery(turn.conversationId)?.expectedReadTools,
        )
    }

    @Test
    fun readExecutionSchemaCannotStoreArgumentsOrResultsAndEnforcesBoundedEntities() {
        val columns = mutableListOf<String>()
        database.openHelper.readableDatabase
            .query("PRAGMA table_info(`turn_read_executions`)")
            .use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
        assertEquals(listOf("turn_id", "ordinal", "tool_name"), columns)

        val createSql = database.openHelper.readableDatabase
            .query(
                "SELECT sql FROM sqlite_master WHERE type = 'table' " +
                    "AND name = 'turn_read_executions'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0).lowercase()
            }
        assertFalse(createSql.contains("argument"))
        assertFalse(createSql.contains("result"))
        assertTrue(
            runCatching { TurnReadExecutionEntity(TURN_ONE, 0, "weather") }.isFailure,
        )
        assertTrue(
            runCatching { TurnReadExecutionEntity(TURN_ONE, 5, "weather") }.isFailure,
        )
        assertTrue(
            runCatching { TurnReadExecutionEntity(TURN_ONE, 1, "weather?location=secret") }
                .isFailure,
        )
    }

    @Test
    fun finalAssistantAndCapsuleCloseTogetherAndRepeatedFinalizeIsIdempotent() = runBlocking {
        val turn = startTurn(TURN_ONE, "현재 날씨를 알려 줘")
        assertTrue(outcomes.recordTool(turn.turnId, "weather", TurnToolRisk.READ_ONLY, 1))

        assertTrue(
            conversations.finalizeTurn(
                conversationId = turn.conversationId,
                turnId = turn.turnId,
                assistantText = "현재 날씨 답변",
                completed = true,
                cancelled = false,
                failureCode = TurnOutcomeFailureCode.UNKNOWN,
            ),
        )
        assertTrue(
            conversations.finalizeTurn(
                conversationId = turn.conversationId,
                turnId = turn.turnId,
                assistantText = "중복 답변",
                completed = true,
                cancelled = false,
                failureCode = TurnOutcomeFailureCode.UNKNOWN,
            ),
        )

        assertEquals(
            listOf("현재 날씨를 알려 줘", "현재 날씨 답변"),
            conversations.listMessages(turn.conversationId).map(MessageEntity::text),
        )
        assertEquals(
            TurnOutcomeState.ANSWER_COMPLETE,
            database.turnOutcomeDao().find(turn.turnId)?.state,
        )
        assertNull(outcomes.latestRecovery(turn.conversationId))
    }

    @Test
    fun concurrentDoubleTapHandsRecoveryToExactlyOneSuccessor() = runBlocking {
        val turn = startTurn(TURN_ONE, "현재 날씨를 알려 줘")
        assertTrue(outcomes.recordTool(turn.turnId, "weather", TurnToolRisk.READ_ONLY, 1))
        val successorOrdinal = conversations.appendMessage(
            conversationId = turn.conversationId,
            role = MessageRole.USER,
            text = "현재 날씨를 알려 줘",
        )!!

        val started = coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    outcomes.startSuccessor(
                        turn.turnId,
                        TURN_TWO,
                        turn.conversationId,
                        successorOrdinal,
                    )
                },
                async(Dispatchers.IO) {
                    outcomes.startSuccessor(
                        turn.turnId,
                        "turn-33333333-3333-3333-3333-333333333333",
                        turn.conversationId,
                        successorOrdinal,
                    )
                },
            ).awaitAll()
        }.filter { it }

        assertEquals(1, started.size)
        assertNull(database.turnOutcomeDao().find(turn.turnId))
    }

    @Test
    fun recoveryExpiresAtClosedBoundaryAndIsDeleted() = runBlocking {
        val turn = startTurn(TURN_ONE, "다시 검색해 줘")
        assertTrue(outcomes.recordTool(turn.turnId, "web_search", TurnToolRisk.READ_ONLY, 1))
        val expiresAt = now + TurnOutcomeRepository.RECOVERY_TTL_MILLIS

        now = expiresAt - 1L
        assertNotNull(outcomes.latestRecovery(turn.conversationId))
        now = expiresAt
        assertNull(outcomes.latestRecovery(turn.conversationId))

        assertNull(database.turnOutcomeDao().find(turn.turnId))
    }

    @Test
    fun unresolvedWriteSurvivesCompletionAndTurnOutcomeExpiryUntilExplicitDismiss() = runBlocking {
        val turn = startTurn(TURN_ONE, "오전 7시에 알람 맞춰 줘")
        assertTrue(outcomes.armSideEffect(turn.turnId, "alarm_set", TurnToolRisk.DATA_WRITE))
        assertTrue(outcomes.recordTool(turn.turnId, "alarm_set", TurnToolRisk.DATA_WRITE))
        assertTrue(outcomes.complete(turn.turnId))

        val afterCompletion = outcomes.latestRecovery(turn.conversationId)!!
        assertEquals(TurnRecoverability.VERIFY_EXTERNAL_STATE, afterCompletion.recoverability)
        assertEquals("alarm_set", afterCompletion.lastToolName)
        assertEquals("", afterCompletion.userRequest)

        now += TurnOutcomeRepository.RECOVERY_TTL_MILLIS
        val afterExpiry = outcomes.latestRecovery(turn.conversationId)!!
        assertEquals(TurnRecoverability.VERIFY_EXTERNAL_STATE, afterExpiry.recoverability)
        assertNull(database.turnOutcomeDao().find(turn.turnId))
        assertEquals(1, outcomes.unresolvedSideEffects().size)

        assertTrue(outcomes.dismissVerification(turn.turnId, turn.conversationId))
        assertNull(outcomes.latestRecovery(turn.conversationId))
        assertTrue(outcomes.unresolvedSideEffects().isEmpty())
    }

    @Test
    fun deletingTranscriptCannotCascadeAnUnresolvedSideEffect() = runBlocking {
        val turn = startTurn(TURN_ONE, "일정을 등록해 줘")
        assertTrue(
            outcomes.armSideEffect(
                turn.turnId,
                "calendar_create_event",
                TurnToolRisk.DATA_WRITE,
            ),
        )
        assertTrue(
            outcomes.recordTool(
                turn.turnId,
                "calendar_create_event",
                TurnToolRisk.DATA_WRITE,
            ),
        )

        assertTrue(conversations.deleteConversation(turn.conversationId))

        assertNull(database.turnOutcomeDao().find(turn.turnId))
        val unresolved = outcomes.unresolvedSideEffects().single()
        assertEquals(turn.turnId, unresolved.turnId)
        assertEquals(turn.conversationId, unresolved.conversationId)
        assertEquals("calendar_create_event", unresolved.lastToolName)
        assertNotNull(outcomes.recovery(turn.turnId, turn.conversationId))
        assertTrue(outcomes.dismissVerification(turn.turnId, turn.conversationId))
        assertTrue(outcomes.unresolvedSideEffects().isEmpty())
    }

    @Test
    fun deletingConversationCascadesRecoveryCapsuleAndTranscript() = runBlocking {
        val turn = startTurn(TURN_ONE, "서울 날씨를 알려 줘")
        assertTrue(outcomes.recordTool(turn.turnId, "weather", TurnToolRisk.READ_ONLY, 1))
        assertNotNull(database.turnOutcomeDao().find(turn.turnId))

        assertTrue(conversations.deleteConversation(turn.conversationId))

        assertNull(database.turnOutcomeDao().find(turn.turnId))
        assertNull(outcomes.latestRecovery(turn.conversationId))
        assertEquals(0L, conversations.messageCount())
    }

    private suspend fun startTurn(
        turnId: String,
        userRequest: String,
    ): StartedTurn {
        val conversationId = conversations.createConversation("복구 테스트")
        val userMessageOrdinal = conversations.appendMessage(
            conversationId = conversationId,
            role = MessageRole.USER,
            text = userRequest,
        )!!
        assertTrue(
            outcomes.start(
                turnId = turnId,
                conversationId = conversationId,
                userMessageOrdinal = userMessageOrdinal,
            ),
        )
        return StartedTurn(turnId, conversationId)
    }

    private data class StartedTurn(
        val turnId: String,
        val conversationId: String,
    )

    private companion object {
        const val TURN_ONE = "turn-11111111-1111-1111-1111-111111111111"
        const val TURN_TWO = "turn-22222222-2222-2222-2222-222222222222"
    }
}
