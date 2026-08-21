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
    fun appendingToAMissingConversationIsRefused() = runBlocking {
        assertNull(repository.appendMessage("absent", MessageRole.USER, "안녕"))
        assertEquals(0L, repository.messageCount())
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
