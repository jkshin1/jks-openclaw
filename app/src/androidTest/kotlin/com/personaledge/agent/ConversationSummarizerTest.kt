package com.personaledge.agent

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.data.MessageRole
import com.personaledge.core.data.PersonalEdgeDatabase
import com.personaledge.core.llm.MAX_USER_PROMPT_BYTES
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
        if (::database.isInitialized) {
            database.close()
        }
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
    fun aFewLargeMessagesTriggerCompactionBeforeTheByteWindowFills() = runBlocking {
        val id = repository.createConversation("바이트 압축")
        val middleConstraint = "핵심 제약은 개인정보 포함 항목 제외입니다."
        repository.appendMessage(
            id,
            MessageRole.USER,
            "요청 시작|${"가".repeat(280)}|$middleConstraint|" +
                "${"다".repeat(280)}|핵심 요청은 마지막 조건입니다.",
        )
        repository.appendMessage(
            id,
            MessageRole.ASSISTANT,
            "답변 시작|${"나".repeat(280)}|답변 끝",
        )

        val request = summarizer.requestFor(id)

        assertNotNull(request)
        assertEquals(2L, request!!.throughOrdinal)
        assertTrue(request.prompt.contains("요청 시작"))
        assertTrue(request.prompt.contains(middleConstraint))
        assertTrue(request.prompt.contains("핵심 요청은 마지막 조건입니다."))
        assertTrue(request.prompt.contains(" … "))
    }

    @Test
    fun aLongEnoughThreadProducesARequestCoveringEveryPendingMessage() = runBlocking {
        val id = conversationWith(6)

        val request = summarizer.requestFor(id)

        assertNotNull(request)
        assertEquals(6L, request!!.throughOrdinal)
        assertNull(request.previousSummary)
        assertTrue(request.prompt.contains("메시지 0"))
        assertTrue(request.prompt.contains("메시지 5"))
        assertTrue(request.prompt.contains("요약"))
        assertTrue(request.prompt.contains("문자열·코드·식별자·숫자는 원문 그대로"))
        assertTrue(request.prompt.contains("현재 사용자의 목표와 결정"))
        assertTrue(request.prompt.contains("명시한 제약과 선호"))
        assertTrue(request.prompt.contains("아직 해결되지 않은 질문과 지시 대상"))
        assertTrue(request.prompt.contains("사람·장소·날짜·약속"))
        assertTrue(request.prompt.contains("[목표]"))
        assertTrue(request.prompt.contains("[결정·제약]"))
        assertTrue(request.prompt.contains("[미완료·참조]"))
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
        assertTrue(followUp!!.prompt.contains("[기존 압축 문맥]"))
        assertTrue(followUp.prompt.contains("치과 예약을 논의했습니다."))
        assertEquals("치과 예약을 논의했습니다.", followUp.previousSummary)
        assertTrue(followUp.prompt.contains("추가 메시지 3"))
        // The already-summarized messages are not re-sent. Matched with the role prefix, because
        // "추가 메시지 0" trivially contains "메시지 0".
        assertFalse(followUp.prompt, followUp.prompt.contains("사용자: 메시지 0"))
        assertTrue(followUp.prompt.contains("사용자: 추가 메시지 0"))
        assertEquals(10L, followUp.throughOrdinal)
    }

    @Test
    fun aFollowUpSummaryCannotEraseThePreviouslyAcceptedFacts() = runBlocking {
        val id = conversationWith(6)
        repository.appendMessage(id, MessageRole.USER, "테스트 코드는 ORCHID-7421입니다.")
        val first = summarizer.requestFor(id)!!
        assertTrue(summarizer.acceptSummary(first, "테스트 코드는 ORCHID-7421입니다."))
        repeat(4) { index ->
            repository.appendMessage(
                id,
                MessageRole.USER,
                if (index == 0) "새 일정은 금요일입니다." else "추가 메시지 $index",
            )
        }

        val followUp = summarizer.requestFor(id)!!
        assertFalse(summarizer.acceptSummary(followUp, "새 일정은 금요일입니다."))
        val complete = "테스트 코드는 ORCHID-7421입니다. 새 일정은 금요일입니다."
        assertTrue(summarizer.acceptSummary(followUp, complete))

        assertEquals(complete, repository.findConversation(id)!!.summary)
    }

    @Test
    fun aFollowUpThatAlreadyContainsThePreviousSummaryIsNotDuplicated() = runBlocking {
        val id = conversationWith(6)
        repository.appendMessage(id, MessageRole.USER, "테스트 코드는 ORCHID-7421입니다.")
        val first = summarizer.requestFor(id)!!
        assertTrue(summarizer.acceptSummary(first, "테스트 코드는 ORCHID-7421입니다."))
        repeat(4) { index ->
            repository.appendMessage(
                id,
                MessageRole.USER,
                if (index == 0) "새 일정은 금요일입니다." else "추가 메시지 $index",
            )
        }

        val complete = "테스트 코드는 ORCHID-7421입니다. 새 일정은 금요일입니다."
        assertTrue(summarizer.acceptSummary(summarizer.requestFor(id)!!, complete))

        assertEquals(complete, repository.findConversation(id)!!.summary)
    }

    @Test
    fun aNearLimitPriorCapsuleIsReplacedInsteadOfPermanentlyStalling() = runBlocking {
        val id = conversationWith(6)
        val first = summarizer.requestFor(id)!!
        val firstCapsule = "[목표] 기존 사실을 유지합니다. ".repeat(10)
        assertTrue(
            summarizer.acceptSummary(
                first,
                firstCapsule,
            ),
        )
        val previous = repository.findConversation(id)!!
        repeat(4) { index ->
            repository.appendMessage(id, MessageRole.USER, "추가 메시지 $index")
        }
        val messageCountBefore = repository.listMessages(id).size

        val replacement =
            "[목표] 기존 사실을 유지합니다. [미완료·참조] 새로 보존해야 하는 사실입니다."
        assertTrue(summarizer.acceptSummary(summarizer.requestFor(id)!!, replacement))

        val retained = repository.findConversation(id)!!
        assertEquals(replacement, retained.summary)
        assertTrue(retained.summarizedThroughMessageOrdinal > previous.summarizedThroughMessageOrdinal)
        assertTrue(retained.summary!!.toByteArray(Charsets.UTF_8).size <= 480)
        assertEquals(messageCountBefore, repository.listMessages(id).size)
    }

    @Test
    fun explicitCorrectionMayReplaceOnePriorAnchorButCannotDropAnUnrelatedOne() = runBlocking {
        val id = conversationWith(6)
        repository.appendMessage(
            id,
            MessageRole.USER,
            "현재 코드는 ORCHID-7421이고 약속 날짜는 2026-08-27입니다.",
        )
        val first = summarizer.requestFor(id)!!
        val initial =
            "[결정·제약] 코드는 ORCHID-7421이고 약속 날짜는 2026-08-27입니다."
        assertTrue(summarizer.acceptSummary(first, initial))
        repository.appendMessage(
            id,
            MessageRole.USER,
            "정정: ORCHID-7421은 폐기하고 ORCHID-9000으로 변경합니다.",
        )
        repeat(3) { index ->
            repository.appendMessage(id, MessageRole.USER, "추가 메시지 $index")
        }
        val request = summarizer.requestFor(id)!!

        assertFalse(
            summarizer.acceptSummary(
                request,
                "[결정·제약] 코드는 ORCHID-9000입니다.",
            ),
        )
        val corrected =
            "[결정·제약] 코드는 ORCHID-9000이고 약속 날짜는 2026-08-27입니다."
        assertTrue(summarizer.acceptSummary(request, corrected))
        assertEquals(corrected, repository.findConversation(id)!!.summary)
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
    fun anOverlongSummaryIsRejectedInsteadOfSilentlyTruncated() = runBlocking {
        val id = conversationWith(6)
        val request = summarizer.requestFor(id)!!

        assertFalse(summarizer.acceptSummary(request, "가".repeat(2_000)))

        assertNull(repository.findConversation(id)!!.summary)
    }

    @Test
    fun aMissingConversationYieldsNoRequest() = runBlocking {
        assertNull(summarizer.requestFor("absent"))
    }

    @Test
    fun moreThanSixtyPendingMessagesAreSummarizedInContiguousOldestFirstBatches() = runBlocking {
        val sequentialSummarizer = ConversationSummarizer(repository, messagesBeforeSummary = 1)
        val id = conversationWith(75)

        val first = sequentialSummarizer.requestFor(id)!!
        val firstThrough = first.throughOrdinal
        val lastIncludedIndex = firstThrough - 1L
        val firstExcludedIndex = firstThrough

        assertTrue(firstThrough in 1L..60L)
        assertTrue(first.prompt.contains("사용자: 메시지 0\n"))
        assertTrue(first.prompt.contains("메시지 $lastIncludedIndex\n"))
        assertFalse(first.prompt.contains("메시지 $firstExcludedIndex\n"))
        assertTrue(sequentialSummarizer.acceptSummary(first, "오래된 대화 묶음을 요약했습니다."))

        val retainedAfterFirst = repository.listMessages(id)
        assertEquals(
            ((firstThrough + 1L)..75L).toList(),
            retainedAfterFirst.map { message -> message.ordinal },
        )

        val second = sequentialSummarizer.requestFor(id)!!
        assertTrue(second.throughOrdinal > firstThrough)
        assertTrue(second.prompt.contains("메시지 $firstExcludedIndex\n"))
        assertFalse(second.prompt.contains("메시지 $lastIncludedIndex\n"))
    }

    @Test
    fun longKoreanPromptBudgetIncludesOnlyCompleteRowsAndTheNextBatchStartsAfterThem() = runBlocking {
        val sequentialSummarizer = ConversationSummarizer(repository, messagesBeforeSummary = 1)
        val id = repository.createConversation("프롬프트 경계")
        repeat(30) { index ->
            val ordinal = index + 1
            repository.appendMessage(
                id,
                MessageRole.USER,
                "ROW-$ordinal|${"가".repeat(270)}|END-$ordinal",
            )
        }

        val first = sequentialSummarizer.requestFor(id)!!
        val nextOrdinal = first.throughOrdinal + 1L

        assertTrue(first.throughOrdinal in 1L..29L)
        assertTrue(first.prompt.toByteArray(Charsets.UTF_8).size <= MAX_USER_PROMPT_BYTES)
        assertTrue(first.prompt.contains("사용자: ROW-${first.throughOrdinal}|"))
        assertTrue(first.prompt.contains("|END-${first.throughOrdinal}\n"))
        assertFalse(first.prompt.contains("사용자: ROW-$nextOrdinal|"))
        assertTrue(sequentialSummarizer.acceptSummary(first, "앞선 대화 묶음을 요약했습니다."))

        val unsummarized = repository.listMessagesAfter(id, first.throughOrdinal, limit = 30)
        assertTrue(unsummarized.isNotEmpty())
        assertEquals(nextOrdinal, unsummarized.first().ordinal)
        val second = sequentialSummarizer.requestFor(id)!!
        assertTrue(second.prompt.contains("사용자: ROW-$nextOrdinal|"))
        assertTrue(second.prompt.contains("|END-$nextOrdinal\n"))
    }

    @Test
    fun requestIsRejectedWhenNoCompleteMessageRowFits() = runBlocking {
        val constrained = ConversationSummarizer(
            repository = repository,
            messagesBeforeSummary = 1,
            maximumPromptBytes = 200,
        )
        val id = repository.createConversation("행이 들어가지 않음")
        repository.appendMessage(id, MessageRole.USER, "가".repeat(300))

        assertNull(constrained.requestFor(id))
        assertEquals(1L, repository.messageCount())
    }

    @Test
    fun summaryInstructionMakesTheLatestUserCorrectionReplaceDiscardedValues() = runBlocking {
        val correctionSummarizer = ConversationSummarizer(repository, messagesBeforeSummary = 1)
        val id = repository.createConversation("최신 정정")
        repository.appendMessage(id, MessageRole.USER, "초기 배송 장소는 부산입니다.")
        repository.appendMessage(id, MessageRole.ASSISTANT, "초기 장소를 확인했습니다.")
        repository.appendMessage(
            id,
            MessageRole.USER,
            "정정합니다. 부산은 폐기하고 현재 배송 장소를 서울로 바꿉니다.",
        )

        val request = correctionSummarizer.requestFor(id)!!

        assertTrue(request.prompt.contains("최신 사용자 정정으로 교체"))
        assertTrue(request.prompt.contains("폐기값"))
        assertTrue(
            request.prompt.indexOf("초기 배송 장소는 부산입니다.") <
                request.prompt.indexOf("현재 배송 장소를 서울로 바꿉니다."),
        )
        assertEquals(3L, request.throughOrdinal)
    }

    @Test
    fun defaultPromptBudgetIsCompatibleWithTheCoreRuntimeByteLimit() = runBlocking {
        val runtimeBoundSummarizer = ConversationSummarizer(repository, messagesBeforeSummary = 1)
        val id = repository.createConversation("런타임 바이트 상한")
        repeat(12) { index ->
            repository.appendMessage(
                id,
                MessageRole.USER,
                "한글-행-$index|${"길".repeat(280)}|끝-$index",
            )
        }

        val request = runtimeBoundSummarizer.requestFor(id)!!

        assertEquals(MAX_USER_PROMPT_BYTES, ConversationSummarizer.MAX_PROMPT_BYTES)
        assertTrue(request.prompt.toByteArray(Charsets.UTF_8).size <= MAX_USER_PROMPT_BYTES)
        assertTrue(request.throughOrdinal in 1L..11L)
        assertTrue(request.prompt.endsWith('\n'))
        assertFalse(request.prompt.contains("한글-행-${request.throughOrdinal + 1L}|"))
    }

    @Test
    fun attachmentOnlyUserRowReachesTheLongTermSummaryAsContentFreeShape() = runBlocking {
        val mediaSummarizer = ConversationSummarizer(repository, messagesBeforeSummary = 1)
        val id = repository.createConversation("첨부 문맥")
        repository.appendMessage(
            conversationId = id,
            role = MessageRole.USER,
            text = "",
            attachmentSummary = "IMAGE:GALLERY",
        )

        val request = requireNotNull(mediaSummarizer.requestFor(id))

        assertTrue(request.prompt.contains("사용자: [첨부: 사진 1장(선택)]"))
        assertFalse(request.prompt.contains("IMAGE:GALLERY"))
        assertTrue(
            mediaSummarizer.acceptSummary(
                request,
                "[미완료·참조] 사진 1장(선택)이 첨부되었습니다.",
            ),
        )
        assertTrue(repository.findConversation(id)!!.summary!!.contains("사진 1장(선택)"))
    }

    @Test
    fun aLegacyKoreanSummaryStillLeavesRoomForOneCompleteNewRow() = runBlocking {
        val id = conversationWith(4)
        assertTrue(
            repository.replaceSummary(
                conversationId = id,
                summary = "가".repeat(300),
                throughOrdinal = 1L,
            ),
        )
        repository.appendMessage(
            id,
            MessageRole.USER,
            "LONG-START|${"나".repeat(500)}|LONG-END",
        )

        val request = summarizer.requestFor(id)

        assertNotNull(request)
        assertTrue(request!!.prompt.toByteArray(Charsets.UTF_8).size <= MAX_USER_PROMPT_BYTES)
        // conversationWith alternates roles, so ordinal 2 / message index 1 is an assistant row.
        // The assertion is about retaining one complete oldest pending row, including its role.
        assertTrue(request.prompt.contains("assistant: 메시지 1\n"))
        assertTrue(request.prompt.contains("[기존 압축 문맥]"))
        assertTrue(
            summarizer.acceptSummary(
                request,
                "[목표] 이전 목표를 유지하고 새 장문 요청을 처리합니다.",
            ),
        )
        assertTrue(
            repository.findConversation(id)!!.summary!!
                .toByteArray(Charsets.UTF_8).size <= ConversationSummarizer.MAX_SUMMARY_UTF8_BYTES,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun configuredPromptBudgetCannotExceedTheCoreRuntimeLimit() {
        ConversationSummarizer(
            repository = repository,
            messagesBeforeSummary = 1,
            maximumPromptBytes = MAX_USER_PROMPT_BYTES + 1,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun configuredBytePressureTriggerCannotExceedTheCoreRuntimeLimit() {
        ConversationSummarizer(
            repository = repository,
            sourceBytesBeforeSummary = MAX_USER_PROMPT_BYTES + 1,
        )
    }

    @Test
    fun summaryRejectsHallucinatedCriticalLiteralsAndKeepsTheSourceRow() = runBlocking {
        val literalSummarizer = ConversationSummarizer(repository, messagesBeforeSummary = 1)
        val id = repository.createConversation("literal 검증")
        repository.appendMessage(
            id,
            MessageRole.USER,
            "약속은 2026-08-27 10:30, user@example.com, https://example.com/t/42, " +
                "ORCHID-7421, 제목은 '치과 예약'입니다.",
        )
        val request = literalSummarizer.requestFor(id)!!
        val hallucinations = listOf(
            "약속 날짜는 2026-08-28입니다.",
            "참석자는 17명입니다.",
            "참석자는 １７명입니다.",
            "참가자는 other@example.com입니다.",
            "링크는 https://evil.example/path입니다.",
            "확인 코드는 ORCHID-9999입니다.",
            "제목은 '비밀 회의'입니다.",
        )

        hallucinations.forEach { candidate ->
            assertFalse(candidate, literalSummarizer.acceptSummary(request, candidate))
        }
        assertNull(repository.findConversation(id)!!.summary)
        assertEquals(listOf(1L), repository.listMessages(id).map { message -> message.ordinal })
    }

    @Test
    fun summaryAcceptsCriticalLiteralsPresentInTheExactSourceWindow() = runBlocking {
        val literalSummarizer = ConversationSummarizer(repository, messagesBeforeSummary = 1)
        val id = repository.createConversation("literal 검증")
        repository.appendMessage(
            id,
            MessageRole.USER,
            "약속은 2026-08-27 10:30, user@example.com, https://example.com/t/42, " +
                "ORCHID-7421, 제목은 '치과 예약'입니다.",
        )
        val candidate =
            "2026-08-27 10:30에 '치과 예약'이 있으며 user@example.com, " +
                "https://example.com/t/42, ORCHID-7421을 유지합니다."

        assertTrue(
            literalSummarizer.acceptSummary(
                literalSummarizer.requestFor(id)!!,
                candidate,
            ),
        )
        assertEquals(candidate, repository.findConversation(id)!!.summary)
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
