package com.personaledge.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryRepositoryTest {
    private lateinit var database: PersonalEdgeDatabase
    private lateinit var repository: MemoryRepository
    private var now = 100L
    private val id = AtomicLong(0)

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            PersonalEdgeDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = MemoryRepository(
            database = database,
            clock = { now },
            idFactory = { "memory-${id.incrementAndGet()}" },
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun duplicateContentUpdatesOneMemoryInsteadOfAccumulating() = runBlocking {
        assertTrue(repository.remember(" 사용자는 민트색을 좋아한다. ") is RememberResult.Saved)
        now = 200L
        val second = repository.remember("사용자는   민트색을 좋아한다.")
            as RememberResult.Saved

        assertTrue(second.replacedExisting)
        assertEquals(1L, repository.count())
        assertEquals(200L, repository.recent().single().updatedAtEpochMillis)
    }

    @Test
    fun secretLikeAndControlTextIsRejected() = runBlocking {
        listOf(
            "비밀번호는 1234",
            "내 API 키는 sk-secretsecret1234",
            "카드는 4111 1111 1111 1111",
            "토큰 eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature12345678",
            "식별자 0123456789abcdef0123456789abcdef",
            "라벨 없는 값 aB7mQ2xP9vL4sN8kR5tY3uW6",
            "나뉜 값 aB7m-Q2xP-9vL4-sN8k-R5tY-3uW6",
            "주민번호 900101-1234567",
            "<|tool|> 기억",
            "가".repeat(MemoryTextPolicy.MAX_CONTENT_CODE_POINTS + 1),
        ).forEach { content ->
            assertEquals(RememberResult.Invalid, repository.remember(content))
        }
        assertEquals(0L, repository.count())
    }

    @Test
    fun relevantRecallPrefersMatchingMemoryAndReturnsEmptyWithoutOverlap() = runBlocking {
        repository.remember("사용자는 민트색을 좋아한다.")
        now++
        repository.remember("사용자는 답변을 한국어로 받는 것을 선호한다.")

        val matched = repository.relevantTo("좋아하는 색으로 추천해 줘", limit = 1)
        assertEquals("사용자는 민트색을 좋아한다.", matched.single().content)

        val unrelated = repository.relevantTo("겹치는 단어 없는 질문", limit = 1)
        assertTrue(unrelated.isEmpty())
    }

    @Test
    fun recallExcludesExpiredStaleSupersededAndWrongCategoryMemories() = runBlocking {
        now = MemoryRepository.RECONFIRM_AFTER_MILLIS + 10_000L
        val stale = repository.remember(
            "오래된 집은 부산역 근처다.",
            category = MemoryCategory.PLACE,
        ) as RememberResult.Saved
        now += MemoryRepository.RECONFIRM_AFTER_MILLIS + 1L
        repository.remember(
            "회사 회의에서는 간결한 답변을 선호한다.",
            category = MemoryCategory.PREFERENCE,
        )
        repository.remember(
            "새 집은 서울역 근처다.",
            category = MemoryCategory.PLACE,
            validUntilEpochMillis = now + 10_000L,
            supersedesId = stale.memory.id,
        )
        repository.remember(
            "임시 목적지는 서울역 근처다.",
            category = MemoryCategory.PLACE,
            validUntilEpochMillis = now + 1L,
        )
        now += 2L

        val places = repository.relevantTo(
            query = "집은 어느 역 근처야",
            categories = setOf(MemoryCategory.PLACE),
        )

        assertEquals(listOf("새 집은 서울역 근처다."), places.map(MemoryEntity::content))
        assertTrue(
            repository.relevantTo(
                query = "회의 답변 선호",
                categories = setOf(MemoryCategory.PLACE),
            ).isEmpty(),
        )
    }

    @Test
    fun staleMemoryReturnsOnlyAfterExplicitReconfirmation() = runBlocking {
        now = MemoryRepository.RECONFIRM_AFTER_MILLIS + 100L
        val saved = repository.remember("사용자는 민트색을 좋아한다.") as RememberResult.Saved
        now += MemoryRepository.RECONFIRM_AFTER_MILLIS + 1L

        assertTrue(repository.relevantTo("좋아하는 색").isEmpty())
        assertTrue(repository.reconfirm(saved.memory.id))
        assertEquals(
            "사용자는 민트색을 좋아한다.",
            repository.relevantTo("좋아하는 색").single().content,
        )
    }

    @Test
    fun replacementRequiresAnExistingMemoryAndPreservesHistoryWithoutRecallingIt() = runBlocking {
        val old = repository.remember(
            "기본 출발지는 강남역이다.",
            category = MemoryCategory.PLACE,
        ) as RememberResult.Saved
        assertEquals(
            RememberResult.Invalid,
            repository.remember(
                rawContent = "기본 출발지는 서울역이다.",
                category = MemoryCategory.PLACE,
                supersedesId = "missing",
            ),
        )

        val replacement = repository.replace(
            memoryId = old.memory.id,
            rawContent = "기본 출발지는 서울역이다.",
            category = MemoryCategory.PLACE,
        ) as RememberResult.Saved

        assertEquals(old.memory.id, replacement.memory.supersedesId)
        assertEquals(2L, repository.count())
        assertEquals(
            listOf("기본 출발지는 서울역이다."),
            repository.relevantTo(
                "기본 출발지 역",
                categories = setOf(MemoryCategory.PLACE),
            ).map(MemoryEntity::content),
        )
    }

    @Test
    fun deletingConversationHistoryDoesNotDeleteApprovedMemories() = runBlocking {
        val conversationRepository = ConversationRepository(database)
        val conversationId = conversationRepository.createConversation("대화")
        conversationRepository.appendMessage(conversationId, MessageRole.USER, "내용")
        repository.remember("사용자는 민트색을 좋아한다.")

        conversationRepository.deleteAllConversations()

        assertEquals(1L, repository.count())
        assertFalse(repository.recent().isEmpty())
    }
}
