package com.personaledge.agent

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.data.MessageRole
import com.personaledge.core.data.PersonalEdgeDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Before
    fun openInMemoryDatabase() {
        database = Room
            .inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                PersonalEdgeDatabase::class.java,
            )
            .build()
        coordinator = ChatHistoryCoordinator(ConversationRepository(database))
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

        val switched = coordinator.switchTo(older!!)

        assertEquals(older, switched.conversationId)
        assertEquals(listOf("예전 대화", "예전 답변"), switched.entries.map(ChatEntry::text))
        assertEquals(2, coordinator.listConversations().size)
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
    fun aTurnWithNoConversationIsSilentlyNotPersisted() = runBlocking {
        // Models the storage-unavailable path: the turn must still run, just without history.
        coordinator.record(null, MessageRole.USER, "저장되지 않는 질문")

        assertTrue(coordinator.listConversations().isEmpty())
        assertNull(coordinator.restoreMostRecent().conversationId)
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
