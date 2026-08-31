package com.personaledge.agent

import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.MessageRole
import com.personaledge.core.llm.InferenceBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded Fold8 acceptance for a synthesized public person answer and safe recovery. */
@RunWith(AndroidJUnit4::class)
class PublicPersonSearchLiveAcceptanceTest {
    @Test
    fun explicitPublicPersonSearchReturnsQuotedWebResultsWithoutModelToolParsing() = runBlocking {
        assumeLivePhysicalSearch()
        val application = application()
        var testConversationId: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val viewModel = readyViewModel(scenario)
                scenario.onActivity { viewModel.startNewConversation() }
                sendWhenAccepted(scenario, viewModel, PUBLIC_PERSON_REQUEST)
                assertTrue(
                    "The public search did not start or produce its receipt.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                        uiState.value.activeTurnId != null ||
                            uiState.value.messages.any { entry -> entry.text == WEB_RECEIPT }
                    },
                )
                assertTrue(
                    "The public search conversation was not persisted.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                        chatHistory.value.activeConversationId != null
                    },
                )
                testConversationId = viewModel.chatHistory.value.activeConversationId
                assertTrue(
                    "The public search did not terminate in time.",
                    viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                        confirmationCoordinator.pending.value != null ||
                            uiState.value.activeTurnId == null &&
                            uiState.value.messages.any { entry -> entry.text == WEB_RECEIPT }
                    },
                )
                assertNull(
                    "Read-only public search unexpectedly opened confirmation.",
                    viewModel.confirmationCoordinator.pending.value,
                )

                var userCount = 0
                var receiptCount = 0
                var assistantCount = 0
                var statusCount = 0
                var answer = ""
                for (entry in viewModel.uiState.value.messages) {
                    when (entry.role) {
                        ChatRole.USER -> userCount++
                        ChatRole.TOOL -> if (entry.text == WEB_RECEIPT) receiptCount++
                        ChatRole.ASSISTANT -> {
                            assistantCount++
                            answer = entry.text
                        }
                        ChatRole.STATUS -> statusCount++
                    }
                }
                val shape = "roles=USER:$userCount,WEB_TOOL:$receiptCount," +
                    "ASSISTANT:$assistantCount,STATUS:$statusCount"
                assertTrue(shape, userCount == 1)
                assertTrue(shape, receiptCount == 1)
                assertTrue(shape, assistantCount == 1)
                assertTrue(shape, statusCount == 0)
                assertSafePublicSearchAnswer(answer)
            }
        } finally {
            testConversationId?.let { id ->
                assertTrue(
                    "The isolated public-search conversation could not be removed.",
                    application.container.conversations.deleteConversation(id),
                )
            }
        }
    }

    @Test
    fun aPersonRequestPhrasedWithADifferentReferenceStillReachesTheProvider() = runBlocking {
        assumeLivePhysicalSearch()
        val application = application()
        var testConversationId: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val viewModel = readyViewModel(scenario)
                scenario.onActivity { viewModel.startNewConversation() }
                sendWhenAccepted(scenario, viewModel, ALTERNATE_REFERENCE_REQUEST)
                assertTrue(
                    "The alternate-phrasing search did not finish.",
                    viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                        uiState.value.activeTurnId == null &&
                            uiState.value.messages.any { entry -> entry.text == WEB_RECEIPT }
                    },
                )
                testConversationId = viewModel.chatHistory.value.activeConversationId

                var answer = ""
                var userCount = 0
                for (entry in viewModel.uiState.value.messages) {
                    if (entry.role == ChatRole.ASSISTANT) answer = entry.text
                    if (entry.role == ChatRole.USER) userCount++
                }
                // Exactly one request proves the test drove this turn by itself, rather than
                // asserting against a conversation someone sent by hand.
                assertTrue("Expected one request in this conversation, got $userCount.", userCount == 1)
                // "…에 대한 정보를" used to survive query extraction and was sent to the provider
                // as a literal sentence fragment, which returned nothing for a widely covered
                // subject and produced the no-evidence answer instead of a sourced one.
                assertFalse(answer, answer.contains(NO_EVIDENCE_ANSWER))
                assertTrue(answer, answer.contains("곽노정"))
                assertTrue(answer, answer.contains("\n\n출처"))
                val sourceCount = httpsLinkCount(answer)
                assertTrue("Expected one or two selected HTTPS sources, got $sourceCount.", sourceCount in 1..2)
            }
        } finally {
            testConversationId?.let { id ->
                assertTrue(
                    "The alternate-phrasing conversation could not be removed.",
                    application.container.conversations.deleteConversation(id),
                )
            }
        }
    }

    @Test
    fun whyDidYouStopRecoversAnUnansweredPublicSearch() = runBlocking {
        assumeLivePhysicalSearch()
        val application = application()
        val repository = application.container.conversations
        val testConversationId = repository.createConversation(PUBLIC_PERSON_REQUEST)
        try {
            assertTrue(
                repository.appendMessage(
                    testConversationId,
                    MessageRole.USER,
                    PUBLIC_PERSON_REQUEST,
                ) != null,
            )
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val viewModel = readyViewModel(scenario, testConversationId)
                sendWhenAccepted(scenario, viewModel, WHY_STOPPED_FOLLOW_UP)
                assertTrue(
                    "The recovered search did not start or produce its receipt.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                        uiState.value.activeTurnId != null ||
                            uiState.value.messages.any { entry -> entry.text == WEB_RECEIPT }
                    },
                )
                assertTrue(
                    "The recovered search did not terminate in time.",
                    viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                        confirmationCoordinator.pending.value != null ||
                            uiState.value.activeTurnId == null &&
                            uiState.value.messages.any { entry -> entry.text == WEB_RECEIPT }
                    },
                )
                assertNull(
                    "Recovered read-only search unexpectedly opened confirmation.",
                    viewModel.confirmationCoordinator.pending.value,
                )

                var userCount = 0
                var receiptCount = 0
                var assistantCount = 0
                var answer = ""
                var sawGenericFailure = false
                for (entry in viewModel.uiState.value.messages) {
                    when (entry.role) {
                        ChatRole.USER -> userCount++
                        ChatRole.TOOL -> if (entry.text == WEB_RECEIPT) receiptCount++
                        ChatRole.ASSISTANT -> {
                            assistantCount++
                            answer = entry.text
                        }
                        ChatRole.STATUS -> if (entry.text.contains(GENERIC_MODEL_FAILURE)) {
                            sawGenericFailure = true
                        }
                    }
                }
                val shape = "roles=USER:$userCount,WEB_TOOL:$receiptCount," +
                    "ASSISTANT:$assistantCount"
                assertTrue(shape, userCount == 2)
                assertTrue(shape, receiptCount == 1)
                assertTrue(shape, assistantCount == 1)
                assertFalse(shape, sawGenericFailure)
                assertSafePublicSearchAnswer(answer)
            }
        } finally {
            assertTrue(
                "The synthetic public-search recovery conversation could not be removed.",
                repository.deleteConversation(testConversationId),
            )
        }
    }

    @Test
    fun summarizePreviousSearchUsesContextWithoutASecondSearch() = runBlocking {
        assumeLivePhysicalSearch()
        val application = application()
        var testConversationId: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val viewModel = readyViewModel(scenario)
                scenario.onActivity { viewModel.startNewConversation() }
                sendWhenAccepted(scenario, viewModel, PUBLIC_PERSON_REQUEST)
                assertTrue(
                    "The initial public search did not finish.",
                    viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                        uiState.value.activeTurnId == null &&
                            uiState.value.messages.count { entry -> entry.text == WEB_RECEIPT } == 1 &&
                            uiState.value.messages.count { entry -> entry.role == ChatRole.ASSISTANT } == 1
                    },
                )
                testConversationId = viewModel.chatHistory.value.activeConversationId

                sendWhenAccepted(scenario, viewModel, SUMMARY_FOLLOW_UP)
                assertTrue(
                    "The context-only summary follow-up did not finish.",
                    viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                        uiState.value.activeTurnId == null &&
                            uiState.value.messages.count { entry -> entry.role == ChatRole.USER } == 2 &&
                            uiState.value.messages.count { entry -> entry.role == ChatRole.ASSISTANT } == 2
                    },
                )

                val entries = viewModel.uiState.value.messages
                assertTrue(
                    "The follow-up unexpectedly performed another web search.",
                    entries.count { entry -> entry.text == WEB_RECEIPT } == 1,
                )
                assertNull(
                    "The context-only follow-up unexpectedly opened confirmation.",
                    viewModel.confirmationCoordinator.pending.value,
                )
                val answer = entries.last { entry -> entry.role == ChatRole.ASSISTANT }.text
                assertTrue(
                    "The follow-up lost the prior person-search context.",
                    answer.contains("김재범") || answer.contains("SK하이닉스"),
                )
                assertTrue(
                    "The follow-up did not preserve a fact found only in the prior result.",
                    answer.contains("미래기술연구원") || answer.contains("국가전략기술"),
                )
                assertFalse(answer.contains("결과를 정리해서 요약해줘 공개 웹 검색 결과"))
                assertFalse(answer.contains("paraphrasing.io", ignoreCase = true))
                assertFalse(answer.contains("lilys.ai", ignoreCase = true))
                assertFalse(answer.contains("canva.com", ignoreCase = true))
            }
        } finally {
            testConversationId?.let { id ->
                assertTrue(
                    "The isolated summary-follow-up conversation could not be removed.",
                    application.container.conversations.deleteConversation(id),
                )
            }
        }
    }

    private suspend fun assumeLivePhysicalSearch() {
        assumeTrue(
            "Live public search requires -e livePublicPersonSearch true.",
            argument(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("Live public search must run on a physical device.", !isEmulator())
        assumeTrue(
            "Web search consent must already be enabled by the owner.",
            application().container.settings.current().webSearchEnabled,
        )
    }

    private fun readyViewModel(
        scenario: ActivityScenario<MainActivity>,
        conversationId: String? = null,
    ): PersonalEdgeViewModel {
        lateinit var viewModel: PersonalEdgeViewModel
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
            viewModel.refreshNetworkSetup()
            if (conversationId != null) viewModel.switchConversation(conversationId)
        }
        assumeTrue(
            "The verified model is not installed on this device.",
            viewModel.awaitUntil(MODEL_TIMEOUT_MILLIS) {
                uiState.value.modelStatus == ModelUiStatus.VERIFIED ||
                    uiState.value.modelStatus == ModelUiStatus.READY
            },
        )
        if (viewModel.uiState.value.modelStatus != ModelUiStatus.READY) {
            scenario.onActivity { viewModel.initializeRuntime(InferenceBackend.GPU) }
            assumeTrue(
                "The runtime did not become ready in time.",
                viewModel.awaitUntil(RUNTIME_TIMEOUT_MILLIS) {
                    uiState.value.modelStatus == ModelUiStatus.READY
                },
            )
        }
        if (conversationId != null) {
            assertTrue(
                "The synthetic public-search conversation was not restored.",
                viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                    chatHistory.value.activeConversationId == conversationId &&
                        uiState.value.messages.size == 1
                },
            )
        }
        return viewModel
    }

    private fun assertSafePublicSearchAnswer(answer: String) {
        assertTrue(
            "The exact person-search answer omitted its selected-source footer.",
            answer.contains("\n\n출처"),
        )
        val sourceCount = httpsLinkCount(answer)
        assertTrue("Expected one or two selected HTTPS sources, got $sourceCount.", sourceCount in 1..2)
        assertTrue("The exact answer lost the requested person.", answer.contains("김재범"))
        assertTrue("The exact answer lost the requested organization.", answer.contains("SK하이닉스"))
        assertTrue(
            "The answer contains no fact grounded only in the selected search result.",
            answer.contains("미래기술연구원") || answer.contains("국가전략기술"),
        )
        assertTrue(
            "The search answer omitted the identity-disambiguation notice.",
            answer.contains("동명이인") || answer.contains("동일 인물을 추정하지 않았습니다"),
        )
        assertFalse(
            "The provider's raw-result heading leaked into the synthesized answer.",
            answer.startsWith("“$EXPECTED_QUERY” 공개 웹 검색 결과입니다."),
        )
        assertFalse("The provider label should not be user-facing answer content.", answer.contains("검색 제공:"))
        assertFalse("An unrelated obituary result reached the answer.", answer.contains("부친상"))
        assertFalse("An unrelated obituary result reached the answer.", answer.contains("모친상"))
    }

    /**
     * Counts source links without a stdlib sequence facade: the release target these run against
     * is minified, and only the Kotlin classes it uses itself survive into the shared classloader.
     */
    /**
     * Sends only when the app actually accepts the turn.
     *
     * `sendPrompt` is refused while the conversation mutation gate is held — creating a new
     * conversation holds it — and the refusal is silent: the text stays in the field and the
     * previous conversation stays on screen. A test that assumed it was sent would then assert
     * against the previous turn's receipt and answer, and report whatever that conversation said.
     */
    private fun sendWhenAccepted(
        scenario: ActivityScenario<MainActivity>,
        viewModel: PersonalEdgeViewModel,
        prompt: String,
    ) {
        val deadline = System.currentTimeMillis() + SEND_TIMEOUT_MILLIS
        var accepted = false
        while (!accepted && System.currentTimeMillis() < deadline) {
            scenario.onActivity {
                viewModel.updatePrompt(prompt)
                viewModel.sendPrompt()
            }
            accepted = viewModel.awaitUntil(SEND_RETRY_MILLIS) {
                uiState.value.messages.any { entry ->
                    entry.role == ChatRole.USER && entry.text == prompt
                }
            }
        }
        assertTrue("The app never accepted this prompt as a turn: $prompt", accepted)
    }

    private fun httpsLinkCount(answer: String): Int = answer.split("https://").size - 1

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

    private fun application(): PersonalEdgeApplication = InstrumentationRegistry
        .getInstrumentation().targetContext.applicationContext as PersonalEdgeApplication

    private fun argument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)

    private companion object {
        const val LIVE_ARGUMENT = "livePublicPersonSearch"
        const val SEND_TIMEOUT_MILLIS = 20_000L
        const val SEND_RETRY_MILLIS = 2_000L
        const val PUBLIC_PERSON_REQUEST = "SK하이닉스 김재범이라는 사람을 조사해서 알려줘"
        const val ALTERNATE_REFERENCE_REQUEST = "SK하이닉스 곽노정이란 사람에 대한 정보를 찾아서 알려줘"
        const val NO_EVIDENCE_ANSWER = "질문과 직접 관련된 공개 웹 자료를 충분히 확인하지 못했습니다."
        const val EXPECTED_QUERY = "SK하이닉스 김재범"
        const val WHY_STOPPED_FOLLOW_UP = "왜 중단했어?"
        const val SUMMARY_FOLLOW_UP = "검색결과를 정리해서 요약해줘"
        const val WEB_RECEIPT = "웹 검색을 완료했습니다."
        const val GENERIC_MODEL_FAILURE = "모델 응답을 안전하게 처리할 수 없어"
        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val START_TIMEOUT_MILLIS = 15_000L
        const val TURN_TIMEOUT_MILLIS = 120_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
