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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationSummarizerTest {
    private lateinit var database: PersonalEdgeDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var summarizer: ConversationSummarizer

    @Before
    fun openInMemoryDatabase() {
        database = Room
            .inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                PersonalEdgeDatabase::class.java,
            )
            .build()
        repository = ConversationRepository(database)
        summarizer = ConversationSummarizer(repository, messagesBeforeSummary = 4)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private suspend fun conversationWith(messageCount: Int): String {
        val id = repository.createConversation("요약 대상")
        repeat(messageCount) { index ->
            repository.appendMessage(
                conversationId = id,
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                text = "메시지 $index",
            )
        }
        return id
    }

    @Test
    fun aShortThreadIsNotWorthSummarizing() = runBlocking {
        assertNull(summarizer.requestFor(conversationWith(3)))
    }

    @Test
    fun aLongEnoughThreadProducesARequestCoveringEveryPendingMessage() = runBlocking {
        val id = conversationWith(6)

        val request = summarizer.requestFor(id)

        assertNotNull(request)
        assertEquals(6L, request!!.throughOrdinal)
        assertTrue(request.prompt.contains("메시지 0"))
        assertTrue(request.prompt.contains("메시지 5"))
        assertTrue(request.prompt.contains("요약"))
    }

    @Test
    fun anAcceptedSummaryIsStoredAndStopsTheThreadFromResummarizing() = runBlocking {
        val id = conversationWith(6)
        val request = summarizer.requestFor(id)!!

        assertTrue(summarizer.acceptSummary(request, "  치과 예약을 논의했습니다.  "))

        assertEquals("치과 예약을 논의했습니다.", repository.findConversation(id)!!.summary)
        // Nothing new has arrived, so there is nothing left to summarize.
        assertNull(summarizer.requestFor(id))
    }

    @Test
    fun newMessagesAfterASummaryProduceAFollowUpThatCarriesTheOldOne() = runBlocking {
        val id = conversationWith(6)
        summarizer.acceptSummary(summarizer.requestFor(id)!!, "치과 예약을 논의했습니다.")
        repeat(4) { index ->
            repository.appendMessage(id, MessageRole.USER, "추가 메시지 $index")
        }

        val followUp = summarizer.requestFor(id)

        assertNotNull(followUp)
        assertTrue(followUp!!.prompt.contains("기존 요약"))
        assertTrue(followUp.prompt.contains("치과 예약을 논의했습니다."))
        assertTrue(followUp.prompt.contains("추가 메시지 3"))
        // The already-summarized messages are not re-sent. Matched with the role prefix, because
        // "추가 메시지 0" trivially contains "메시지 0".
        assertFalse(followUp.prompt, followUp.prompt.contains("사용자: 메시지 0"))
        assertTrue(followUp.prompt.contains("사용자: 추가 메시지 0"))
        assertEquals(10L, followUp.throughOrdinal)
    }

    @Test
    fun anEmptyOrTrivialSummaryIsRefused() = runBlocking {
        val request = summarizer.requestFor(conversationWith(6))!!

        assertFalse(summarizer.acceptSummary(request, ""))
        assertFalse(summarizer.acceptSummary(request, "   "))
        assertFalse(summarizer.acceptSummary(request, "짧음"))
        assertNull(repository.findConversation(request.conversationId)!!.summary)
    }

    @Test
    fun anOverlongSummaryIsCappedRatherThanRejected() = runBlocking {
        val id = conversationWith(6)
        val request = summarizer.requestFor(id)!!

        assertTrue(summarizer.acceptSummary(request, "가".repeat(2_000)))

        assertEquals(
            ConversationSummarizer.MAX_SUMMARY_CHARACTERS,
            repository.findConversation(id)!!.summary!!.length,
        )
    }

    @Test
    fun aMissingConversationYieldsNoRequest() = runBlocking {
        assertNull(summarizer.requestFor("absent"))
    }

    @Test
    fun summarizingKeepsTheNewestMessagesReadable() = runBlocking {
        val id = conversationWith(20)
        val request = summarizer.requestFor(id)!!

        summarizer.acceptSummary(request, "스무 개 메시지를 요약했습니다.")

        // replaceSummary protects a recent window, so restoring the thread still shows context.
        val remaining = repository.listMessages(id)
        assertTrue(remaining.isNotEmpty())
        assertEquals(20L, remaining.last().ordinal)
    }
}
