package com.personaledge.agent

import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.agent.TurnOutputBudgetPolicy
import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.data.MessageRole
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.llm.InferenceBackend
import java.util.LinkedHashSet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in physical acceptance for the production model's conversation-answer quality.
 *
 * Every fixture is fixed synthetic text. The test drives [PersonalEdgeViewModel], so the actual
 * conversation repository, trusted context builder, per-turn Tool scope, and LiteRT runtime stay in
 * the path. The fixtures deliberately name no supported Tool domain, and any Tool row or
 * confirmation is a failure. Answers are inspected only in memory; assertion text and status never
 * contain model output. Only the exact conversation IDs created below are deleted in cleanup.
 */
@RunWith(AndroidJUnit4::class)
class Fold8ConversationQualityAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun understandsLongPrimaryIntentAndBoundedConversationContext() = runBlocking {
        assumeTrue(
            "Fold8 conversation-quality acceptance requires -e liveConversationQuality true.",
            argument(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("This acceptance is restricted to the owner's Fold8.", isTargetFold8())

        val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication
        val repository = application.container.conversations
        val conversationCountBefore = repository.conversationCount()
        val messageCountBefore = repository.messageCount()
        val testConversationIds = LinkedHashSet<String>()

        try {
            val fixtures = seedSyntheticConversations(repository, testConversationIds)
            val longPromptBytes = LONG_PRIMARY_INTENT_PROMPT.toByteArray(Charsets.UTF_8).size
            assertTrue(
                "The fixed long-prompt fixture left its reviewed byte band.",
                longPromptBytes in MIN_LONG_PROMPT_BYTES..MAX_LONG_PROMPT_BYTES,
            )

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val viewModel = scenario.readyGpuViewModel()
                val qualityResults = mutableListOf<QualityCaseResult>()
                try {
                    assertTrue(
                        "The Fold8 thermal state did not become runnable before conversation-quality acceptance.",
                        viewModel.awaitRunnableThermalState(),
                    )
                    val longBudget = qualityBudgetObservation(LONG_PRIMARY_INTENT_PROMPT)

                    scenario.activateConversation(
                        viewModel = viewModel,
                        conversationId = fixtures.longPrompt,
                        expectedStoredEntries = 0,
                    )
                    val longAnswer = scenario.sendAndAwaitAnswer(
                        viewModel,
                        LONG_PRIMARY_INTENT_PROMPT,
                    )
                    qualityResults += assessCodeOnly(
                        id = "long_primary_intent",
                        answer = longAnswer,
                        expected = LONG_PRIMARY_INTENT_EXPECTED,
                        forbidden = listOf("CEDAR-18", "MINT-46"),
                        budget = longBudget,
                    )

                    assertTrue(
                        "The Fold8 thermal state did not become runnable before prior-context reference acceptance.",
                        viewModel.awaitRunnableThermalState(),
                    )
                    val referenceBudget = qualityBudgetObservation(PRIOR_REFERENCE_PROMPT)
                    scenario.activateConversation(
                        viewModel = viewModel,
                        conversationId = fixtures.priorReference,
                        expectedStoredEntries = 2,
                    )
                    val referenceAnswer = scenario.sendAndAwaitAnswer(
                        viewModel,
                        PRIOR_REFERENCE_PROMPT,
                    )
                    qualityResults += assessCodeOnly(
                        id = "prior_reference",
                        answer = referenceAnswer,
                        expected = PRIOR_REFERENCE_EXPECTED,
                        forbidden = listOf("POLAR-82"),
                        budget = referenceBudget,
                    )

                    assertTrue(
                        "The Fold8 thermal state did not become runnable before latest-correction acceptance.",
                        viewModel.awaitRunnableThermalState(),
                    )
                    val correctionBudget = qualityBudgetObservation(LATEST_CORRECTION_PROMPT)
                    scenario.activateConversation(
                        viewModel = viewModel,
                        conversationId = fixtures.latestCorrection,
                        expectedStoredEntries = 3,
                    )
                    val correctionAnswer = scenario.sendAndAwaitAnswer(
                        viewModel,
                        LATEST_CORRECTION_PROMPT,
                    )
                    qualityResults += assessCodeOnly(
                        id = "latest_correction",
                        answer = correctionAnswer,
                        expected = LATEST_CORRECTION_EXPECTED,
                        forbidden = listOf(LATEST_CORRECTION_SUPERSEDED),
                        budget = correctionBudget,
                    )

                    assertTrue(
                        "The Fold8 thermal state did not become runnable before irrelevant-history acceptance.",
                        viewModel.awaitRunnableThermalState(),
                    )
                    val irrelevantBudget = qualityBudgetObservation(IRRELEVANT_HISTORY_PROMPT)
                    scenario.activateConversation(
                        viewModel = viewModel,
                        conversationId = fixtures.irrelevantHistory,
                        expectedStoredEntries = 4,
                    )
                    val irrelevantAnswer = scenario.sendAndAwaitAnswer(
                        viewModel,
                        IRRELEVANT_HISTORY_PROMPT,
                    )
                    qualityResults += assessCodeOnly(
                        id = "irrelevant_history",
                        answer = irrelevantAnswer,
                        expected = IRRELEVANT_HISTORY_EXPECTED,
                        forbidden = listOf(
                            IRRELEVANT_HISTORY_FIRST,
                            IRRELEVANT_HISTORY_SECOND,
                        ),
                        budget = irrelevantBudget,
                    )

                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putInt("conversation_quality_case_count", QUALITY_CASE_COUNT)
                            putInt("conversation_quality_long_prompt_bytes", longPromptBytes)
                            qualityResults.forEach { result ->
                                putString(
                                    "conversation_quality_${result.id}",
                                    result.classification,
                                )
                                putInt(
                                    "conversation_quality_${result.id}_output_budget",
                                    result.outputBudget,
                                )
                                putString(
                                    "conversation_quality_${result.id}_thermal_workload",
                                    result.thermalWorkload,
                                )
                            }
                        },
                    )
                    assertTrue(
                        "Conversation-quality failures: " + qualityResults
                            .filterNot(QualityCaseResult::passed)
                            .joinToString { result -> "${result.id}:${result.classification}" },
                        qualityResults.all(QualityCaseResult::passed),
                    )
                } finally {
                    scenario.stopActiveTurn(viewModel)
                }
            }
        } finally {
            removeOnlyCreatedConversations(repository, testConversationIds)
            assertEquals(conversationCountBefore, repository.conversationCount())
            assertEquals(messageCountBefore, repository.messageCount())
        }
    }

    private suspend fun seedSyntheticConversations(
        repository: ConversationRepository,
        testConversationIds: LinkedHashSet<String>,
    ): QualityConversationIds {
        val longPrompt = repository.createConversation("$TEST_TITLE_PREFIX long-primary-intent")
        testConversationIds.add(longPrompt)

        val priorReference = repository.createConversation("$TEST_TITLE_PREFIX prior-reference")
        testConversationIds.add(priorReference)
        seedMessage(
            repository,
            priorReference,
            MessageRole.USER,
            "가상 해양 표본 메모입니다. 파란 상자의 표본 코드는 NEBULA-31이고 " +
                "흰 상자의 표본 코드는 POLAR-82입니다.",
        )
        seedMessage(
            repository,
            priorReference,
            MessageRole.ASSISTANT,
            "두 개의 가상 표본을 구분했습니다.",
        )

        val latestCorrection = repository.createConversation("$TEST_TITLE_PREFIX latest-correction")
        testConversationIds.add(latestCorrection)
        seedMessage(
            repository,
            latestCorrection,
            MessageRole.USER,
            "가상 품질 기록의 초기 승인 코드는 OLD-17입니다.",
        )
        seedMessage(
            repository,
            latestCorrection,
            MessageRole.ASSISTANT,
            "초기 가상 값이 기록되었습니다.",
        )
        assertTrue(
            "The synthetic correction summary could not be seeded.",
            repository.replaceSummary(
                conversationId = latestCorrection,
                summary = "가상 품질 기록의 초기 승인 코드는 OLD-17입니다.",
                throughOrdinal = 2L,
                keepRecentMessages = 1,
            ),
        )
        seedMessage(
            repository,
            latestCorrection,
            MessageRole.USER,
            "정정합니다. 앞의 OLD-17은 폐기되었고 최종 승인 코드는 NEW-83입니다.",
        )
        seedMessage(
            repository,
            latestCorrection,
            MessageRole.ASSISTANT,
            "최신 정정 내용을 확인했습니다.",
        )

        val irrelevantHistory = repository.createConversation("$TEST_TITLE_PREFIX irrelevant-history")
        testConversationIds.add(irrelevantHistory)
        seedMessage(
            repository,
            irrelevantHistory,
            MessageRole.USER,
            "가상 도서 분류 연습에서 노을 표지의 코드는 SUNSET-44입니다.",
        )
        seedMessage(
            repository,
            irrelevantHistory,
            MessageRole.ASSISTANT,
            "첫 번째 가상 분류 항목을 확인했습니다.",
        )
        seedMessage(
            repository,
            irrelevantHistory,
            MessageRole.USER,
            "같은 연습에서 바다 표지의 코드는 OCEAN-76입니다.",
        )
        seedMessage(
            repository,
            irrelevantHistory,
            MessageRole.ASSISTANT,
            "두 번째 가상 분류 항목을 확인했습니다.",
        )

        return QualityConversationIds(
            longPrompt = longPrompt,
            priorReference = priorReference,
            latestCorrection = latestCorrection,
            irrelevantHistory = irrelevantHistory,
        )
    }

    private suspend fun seedMessage(
        repository: ConversationRepository,
        conversationId: String,
        role: MessageRole,
        text: String,
    ) {
        assertNotNull(
            "A fixed synthetic conversation row could not be seeded.",
            repository.appendMessage(conversationId, role, text),
        )
    }

    private fun ActivityScenario<MainActivity>.readyGpuViewModel(): PersonalEdgeViewModel {
        lateinit var viewModel: PersonalEdgeViewModel
        onActivity { activity ->
            viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
        }
        assumeTrue(
            "The verified model is not installed on this device.",
            viewModel.awaitUntil(MODEL_TIMEOUT_MILLIS) {
                uiState.value.modelStatus == ModelUiStatus.VERIFIED ||
                    uiState.value.modelStatus == ModelUiStatus.READY
            },
        )
        if (viewModel.uiState.value.modelStatus != ModelUiStatus.READY) {
            onActivity { activity ->
                ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    .initializeRuntime(InferenceBackend.GPU)
            }
            assumeTrue(
                "The GPU runtime did not become ready in time.",
                viewModel.awaitUntil(RUNTIME_TIMEOUT_MILLIS) {
                    uiState.value.modelStatus == ModelUiStatus.READY
                },
            )
        }
        assertEquals(InferenceBackend.GPU, viewModel.uiState.value.activeBackend)
        return viewModel
    }

    private fun ActivityScenario<MainActivity>.activateConversation(
        viewModel: PersonalEdgeViewModel,
        conversationId: String,
        expectedStoredEntries: Int,
    ) {
        val deadline = System.currentTimeMillis() + PERSISTENCE_TIMEOUT_MILLIS
        var active = false
        while (!active && System.currentTimeMillis() < deadline) {
            onActivity { activity ->
                ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    .switchConversation(conversationId)
            }
            active = viewModel.awaitUntil(SWITCH_RETRY_MILLIS) {
                chatHistory.value.activeConversationId == conversationId &&
                    persistedEntryCount() == expectedStoredEntries
            }
        }
        assertTrue("The fixed synthetic conversation could not be activated.", active)
    }

    private fun ActivityScenario<MainActivity>.sendAndAwaitAnswer(
        viewModel: PersonalEdgeViewModel,
        prompt: String,
    ): String {
        val userCountBefore = viewModel.roleCount(ChatRole.USER)
        val assistantCountBefore = viewModel.roleCount(ChatRole.ASSISTANT)
        val toolCountBefore = viewModel.roleCount(ChatRole.TOOL)
        val sendDeadline = System.currentTimeMillis() + SEND_TIMEOUT_MILLIS
        var accepted = false

        while (!accepted && System.currentTimeMillis() < sendDeadline) {
            onActivity { activity ->
                ViewModelProvider(activity)[PersonalEdgeViewModel::class.java].apply {
                    updatePrompt(prompt)
                    sendPrompt()
                }
            }
            accepted = viewModel.awaitUntil(SEND_RETRY_MILLIS) {
                roleCount(ChatRole.USER) == userCountBefore + 1 && lastUserText() == prompt
            }
        }
        assertTrue("The fixed quality prompt was not accepted as a USER turn.", accepted)

        assertTrue(
            "The fixed quality turn did not terminate with an assistant answer.",
            viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                confirmationCoordinator.pending.value != null ||
                    uiState.value.activeTurnId == null &&
                    roleCount(ChatRole.ASSISTANT) > assistantCountBefore
            },
        )

        val unexpectedConfirmation = viewModel.confirmationCoordinator.pending.value
        if (unexpectedConfirmation != null) {
            onActivity { activity ->
                ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    .resolveConfirmation(unexpectedConfirmation.actionId, false)
            }
        }
        assertTrue(
            "A no-Tool conversation-quality turn unexpectedly requested confirmation.",
            unexpectedConfirmation == null,
        )
        assertEquals(
            "A no-Tool conversation-quality turn unexpectedly executed a Tool.",
            toolCountBefore,
            viewModel.roleCount(ChatRole.TOOL),
        )

        val answer = viewModel.lastAssistantTextAfter(assistantCountBefore)
        assertNotNull("The fixed quality turn produced no assistant answer.", answer)
        assertTrue(
            "The fixed quality turn produced an empty assistant answer.",
            containsNonWhitespace(answer.orEmpty()),
        )
        return answer.orEmpty()
    }

    private fun assessCodeOnly(
        id: String,
        answer: String,
        expected: String,
        forbidden: List<String>,
        budget: QualityBudgetObservation,
    ): QualityCaseResult {
        val compact = compactAnswer(answer)
        val expectedPresent = compact.contains(expected)
        val forbiddenPresent = forbidden.any(compact::contains)
        val classification = when {
            compact == expected -> "passed"
            expectedPresent && forbiddenPresent -> "expected_and_forbidden"
            forbiddenPresent -> "forbidden_without_expected"
            expectedPresent -> "expected_with_extra"
            else -> "expected_missing"
        }
        return QualityCaseResult(
            id = id,
            passed = classification == "passed",
            classification = classification,
            outputBudget = budget.outputBudget,
            thermalWorkload = budget.workload,
        )
    }

    /** Records only the content-free production request budget for a fixed synthetic turn. */
    private fun qualityBudgetObservation(prompt: String): QualityBudgetObservation {
        return QualityBudgetObservation(
            // Thermal policy no longer transforms this deterministic request budget at any
            // runnable status. The ViewModel's live thermal gate remains in the exercised path.
            outputBudget = TurnOutputBudgetPolicy.forPrompt(prompt).maxOutputTokens,
            workload = "normal",
        )
    }

    private fun compactAnswer(answer: String): String {
        val compact = StringBuilder(answer.length)
        var index = 0
        while (index < answer.length) {
            val codePoint = answer.codePointAt(index)
            index += Character.charCount(codePoint)
            if (
                Character.isWhitespace(codePoint) ||
                codePoint == '`'.code ||
                codePoint == '"'.code ||
                codePoint == '\''.code ||
                codePoint == '*'.code ||
                codePoint == '.'.code ||
                codePoint == '。'.code
            ) {
                continue
            }
            compact.appendCodePoint(codePoint)
        }
        return compact.toString()
    }

    private fun PersonalEdgeViewModel.persistedEntryCount(): Int {
        var count = 0
        for (entry in uiState.value.messages) {
            if (
                entry.role == ChatRole.USER ||
                entry.role == ChatRole.ASSISTANT ||
                entry.role == ChatRole.TOOL
            ) {
                count += 1
            }
        }
        return count
    }

    private fun PersonalEdgeViewModel.roleCount(role: ChatRole): Int {
        var count = 0
        for (entry in uiState.value.messages) {
            if (entry.role == role) count += 1
        }
        return count
    }

    private fun PersonalEdgeViewModel.lastUserText(): String? {
        var text: String? = null
        for (entry in uiState.value.messages) {
            if (entry.role == ChatRole.USER) text = entry.text
        }
        return text
    }

    private fun PersonalEdgeViewModel.lastAssistantTextAfter(previousCount: Int): String? {
        var seen = 0
        var text: String? = null
        for (entry in uiState.value.messages) {
            if (entry.role == ChatRole.ASSISTANT) {
                if (seen >= previousCount) text = entry.text
                seen += 1
            }
        }
        return text
    }

    private fun ActivityScenario<MainActivity>.stopActiveTurn(viewModel: PersonalEdgeViewModel) {
        val pending = viewModel.confirmationCoordinator.pending.value
        if (pending != null) {
            onActivity { activity ->
                ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    .resolveConfirmation(pending.actionId, false)
            }
        }
        if (viewModel.uiState.value.activeTurnId != null) {
            onActivity { activity ->
                ViewModelProvider(activity)[PersonalEdgeViewModel::class.java].cancelTurn()
            }
            assertTrue(
                "The fixed conversation-quality turn did not stop during cleanup.",
                viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) { uiState.value.activeTurnId == null },
            )
        }
    }

    private suspend fun removeOnlyCreatedConversations(
        repository: ConversationRepository,
        testConversationIds: LinkedHashSet<String>,
    ) {
        for (conversationId in testConversationIds) {
            if (repository.findConversation(conversationId) != null) {
                assertTrue(
                    "An exact synthetic quality conversation could not be removed.",
                    repository.deleteConversation(conversationId),
                )
            }
        }
    }

    private fun PersonalEdgeViewModel.awaitRunnableThermalState(): Boolean =
        awaitUntil(THERMAL_READY_TIMEOUT_MILLIS) {
            ThermalTurnPolicy.canStart(uiState.value.thermalStatus)
        }

    private fun PersonalEdgeViewModel.awaitUntil(
        timeoutMillis: Long,
        condition: PersonalEdgeViewModel.() -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return condition()
    }

    private fun containsNonWhitespace(text: String): Boolean {
        for (index in 0 until text.length) {
            if (!Character.isWhitespace(text[index])) return true
        }
        return false
    }

    private fun argument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)

    private fun isTargetFold8(): Boolean {
        val fingerprint = android.os.Build.FINGERPRINT
        val genericFingerprint = fingerprint.length >= GENERIC_PREFIX.length &&
            fingerprint.substring(0, GENERIC_PREFIX.length).equals(GENERIC_PREFIX)
        return android.os.Build.MODEL.equals(TARGET_FOLD8_MODEL) && !genericFingerprint
    }

    private data class QualityConversationIds(
        val longPrompt: String,
        val priorReference: String,
        val latestCorrection: String,
        val irrelevantHistory: String,
    )

    private data class QualityCaseResult(
        val id: String,
        val passed: Boolean,
        val classification: String,
        val outputBudget: Int,
        val thermalWorkload: String,
    )

    private data class QualityBudgetObservation(
        val outputBudget: Int,
        val workload: String,
    )

    private companion object {
        const val LIVE_ARGUMENT = "liveConversationQuality"
        const val TEST_TITLE_PREFIX = "품질수용성 합성자료"
        const val TARGET_FOLD8_MODEL = "SM-F971N"
        const val GENERIC_PREFIX = "generic"
        const val QUALITY_CASE_COUNT = 4
        const val MIN_LONG_PROMPT_BYTES = 1_000
        const val MAX_LONG_PROMPT_BYTES = 1_350

        val LONG_PRIMARY_INTENT_PROMPT = """
            도구를 사용하지 마세요. 다음 내용은 응답 품질 시험을 위한 가상 검토 기록입니다.
            첫 번째 후보 CEDAR-18은 무게가 1.4킬로그램이고 연속 작동 값은 24이며 비용 값은 80입니다.
            두 번째 후보 ORBIT-73은 무게가 1.2킬로그램이고 연속 작동 값은 19이며 비용 값은 95입니다.
            세 번째 후보 MINT-46은 무게가 1.1킬로그램이고 연속 작동 값은 17이며 비용 값은 70입니다.
            검토자는 포장 색상, 회의 좌석, 문서 표지, 발표 순서에 관한 메모도 남겼지만 이 값들은 판정과 무관합니다.
            포장 색상은 첫 번째가 회색, 두 번째가 파란색, 세 번째가 녹색이라는 가상 기록입니다.
            발표 순서는 세 번째, 첫 번째, 두 번째였고 문서 표지는 모두 흰색이었다는 가상 기록도 있습니다.
            다른 메모에는 창고 구역이 북쪽, 좌석 번호가 열둘, 검토 용지가 세 장이라고 적혀 있습니다.
            이 기록의 실제 판정 기준은 무게가 1.3킬로그램 이하이고, 연속 작동 값이 18 이상이며, 비용 값이 100 이하인지를 모두 만족하는 것입니다.
            위 세 조건을 동시에 만족하는 후보의 코드 하나만 답하세요. 설명, 인사말, 따옴표, 목록은 쓰지 마세요.
        """.trimIndent()
        const val LONG_PRIMARY_INTENT_EXPECTED = "ORBIT-73"

        const val PRIOR_REFERENCE_PROMPT =
            "도구를 사용하지 말고, 앞서 사용자가 말한 파란 상자의 표본 코드만 답하세요."
        const val PRIOR_REFERENCE_EXPECTED = "NEBULA-31"

        const val LATEST_CORRECTION_PROMPT =
            "도구를 사용하지 말고, 가장 최근 정정을 따른 최종 승인 코드만 답하세요."
        const val LATEST_CORRECTION_EXPECTED = "NEW-83"
        const val LATEST_CORRECTION_SUPERSEDED = "OLD-17"

        const val IRRELEVANT_HISTORY_PROMPT =
            "도구를 사용하지 말고, 이전 도서 분류와 무관한 현재 질문에 답하세요. " +
                "2 더하기 3의 결과를 숫자 하나만 써 주세요."
        const val IRRELEVANT_HISTORY_EXPECTED = "5"
        const val IRRELEVANT_HISTORY_FIRST = "SUNSET-44"
        const val IRRELEVANT_HISTORY_SECOND = "OCEAN-76"

        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val PERSISTENCE_TIMEOUT_MILLIS = 15_000L
        const val SWITCH_RETRY_MILLIS = 1_000L
        const val SEND_TIMEOUT_MILLIS = 20_000L
        const val SEND_RETRY_MILLIS = 1_000L
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val THERMAL_READY_TIMEOUT_MILLIS = 300_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
