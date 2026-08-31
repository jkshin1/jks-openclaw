package com.personaledge.agent

import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.MessageRole
import com.personaledge.core.llm.InferenceBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly opted-in Fold8 acceptance for the automatic live web-search Tool path.
 *
 * This is absent from ordinary connected runs. It requires a physical device, the verified model,
 * owner-enabled web-search consent, a saved Tavily fallback key, and one runner argument. The test
 * sends only the fixed public query below and fails if a confirmation sheet appears. No vault
 * value is read here, printed, cleared, or replaced.
 */
@RunWith(AndroidJUnit4::class)
class WebSearchLiveToolAcceptanceTest {
    @Test
    fun publicQueryCompletesThroughRealGemmaWithoutConfirmationUi() = runBlocking {
        assumeTrue(
            "Live web-search Tool acceptance requires -e liveWebSearchTool true.",
            argument(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("Live Tool acceptance must run on a physical device.", !isEmulator())

        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PersonalEdgeApplication
        var testConversationId: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var viewModel: PersonalEdgeViewModel
                scenario.onActivity { activity ->
                    viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    viewModel.refreshCredentials()
                    viewModel.refreshNetworkSetup()
                }

                assumeTrue(
                    "Web-search consent must already be enabled by the owner.",
                    viewModel.awaitUntil(PREFLIGHT_TIMEOUT_MILLIS) {
                        networkSetup.value.webSearchEnabled
                    },
                )
                assumeTrue(
                    "The optional Tavily fallback key must be present without reading its value.",
                    viewModel.awaitUntil(PREFLIGHT_TIMEOUT_MILLIS) {
                        credentials.value.statuses.any { status ->
                            status.slot == CredentialSlot.TAVILY_API_KEY && status.stored
                        }
                    },
                )
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

                // Isolate the exact reported weather request from every owner conversation.
                scenario.onActivity { viewModel.startNewConversation() }
                assertTrue(
                    "The isolated acceptance conversation was not created.",
                    viewModel.uiState.value.messages.isEmpty(),
                )
                scenario.onActivity {
                    viewModel.updatePrompt(KOREAN_REQUEST)
                    viewModel.sendPrompt()
                }
                assertTrue(
                    "The live web-search turn did not start.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) { uiState.value.activeTurnId != null },
                )
                assertTrue(
                    "The isolated weather conversation was not persisted.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                        chatHistory.value.activeConversationId != null
                    },
                )
                testConversationId = viewModel.chatHistory.value.activeConversationId

                assertTrue(
                    "The automatic web-search turn did not terminate in time.",
                    viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                        confirmationCoordinator.pending.value != null ||
                            uiState.value.activeTurnId == null
                    },
                )
                assertNull(
                    "Read-only web search unexpectedly opened a confirmation sheet.",
                    viewModel.confirmationCoordinator.pending.value,
                )
                assertNull(
                    "The automatic web-search turn is still active.",
                    viewModel.uiState.value.activeTurnId,
                )

                val messages = viewModel.uiState.value.messages
                var userCount = 0
                var toolCount = 0
                var assistantCount = 0
                var statusCount = 0
                var sawWebReceipt = false
                var sawAssistantAnswer = false
                for (entry in messages) {
                    when (entry.role) {
                        ChatRole.USER -> userCount++
                        ChatRole.TOOL -> {
                            toolCount++
                            if (entry.text == "웹 검색을 완료했습니다.") sawWebReceipt = true
                        }
                        ChatRole.ASSISTANT -> {
                            assistantCount++
                            if (entry.text.isNotBlank()) sawAssistantAnswer = true
                        }
                        ChatRole.STATUS -> statusCount++
                    }
                }
                val messageShape = StringBuilder("message_roles=")
                    .append("USER:").append(userCount).append(',')
                    .append("TOOL:").append(toolCount).append(',')
                    .append("ASSISTANT:").append(assistantCount).append(',')
                    .append("STATUS:").append(statusCount)
                    .toString()
                assertTrue(
                    messageShape,
                    sawWebReceipt,
                )
                assertTrue(
                    messageShape,
                    sawAssistantAnswer,
                )
                assertFalse(messageShape, statusCount > 0)
            }
        } finally {
            testConversationId?.let { id ->
                assertTrue(
                    "The isolated weather conversation could not be removed.",
                    application.container.conversations.deleteConversation(id),
                )
            }
        }
    }

    @Test
    fun missingAnswerFollowUpReSearchesOriginalWeatherRequest() = runBlocking {
        assumeTrue(
            "Live web-search recovery requires -e liveWebSearchTool true.",
            argument(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("Live Tool acceptance must run on a physical device.", !isEmulator())

        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PersonalEdgeApplication
        val repository = application.container.conversations
        val testConversationId = repository.createConversation(KOREAN_REQUEST)
        try {
            assertTrue(
                "The synthetic original request could not be stored.",
                repository.appendMessage(
                    testConversationId,
                    MessageRole.USER,
                    KOREAN_REQUEST,
                ) != null,
            )
            assertTrue(
                "The synthetic incomplete Tool receipt could not be stored.",
                repository.appendMessage(
                    testConversationId,
                    MessageRole.TOOL_RECEIPT,
                    WEB_RECEIPT,
                ) != null,
            )

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var viewModel: PersonalEdgeViewModel
                scenario.onActivity { activity ->
                    viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    viewModel.refreshCredentials()
                    viewModel.refreshNetworkSetup()
                    viewModel.switchConversation(testConversationId)
                }
                assumeTrue(
                    "Web-search consent must already be enabled by the owner.",
                    viewModel.awaitUntil(PREFLIGHT_TIMEOUT_MILLIS) {
                        networkSetup.value.webSearchEnabled
                    },
                )
                assumeTrue(
                    "The optional Tavily fallback key must be present without reading its value.",
                    viewModel.awaitUntil(PREFLIGHT_TIMEOUT_MILLIS) {
                        var present = false
                        for (status in credentials.value.statuses) {
                            if (status.slot == CredentialSlot.TAVILY_API_KEY && status.stored) {
                                present = true
                            }
                        }
                        present
                    },
                )
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
                assertTrue(
                    "The incomplete synthetic conversation was not restored.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                        chatHistory.value.activeConversationId == testConversationId &&
                            uiState.value.messages.size == 2
                    },
                )

                scenario.onActivity {
                    viewModel.updatePrompt(MISSING_ANSWER_FOLLOW_UP)
                    viewModel.sendPrompt()
                }
                assertTrue(
                    "The recovered weather turn did not start.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                        uiState.value.activeTurnId != null
                    },
                )
                assertTrue(
                    "The recovered weather turn did not terminate in time.",
                    viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                        confirmationCoordinator.pending.value != null ||
                            uiState.value.activeTurnId == null
                    },
                )
                assertNull(
                    "Recovered read-only web search unexpectedly opened confirmation.",
                    viewModel.confirmationCoordinator.pending.value,
                )
                assertNull(
                    "The recovered weather turn is still active.",
                    viewModel.uiState.value.activeTurnId,
                )

                var userCount = 0
                var toolCount = 0
                var assistantCount = 0
                var finalAssistantIsNonBlank = false
                var sawToolFollowUpFailure = false
                for (entry in viewModel.uiState.value.messages) {
                    when (entry.role) {
                        ChatRole.USER -> userCount++
                        ChatRole.TOOL -> toolCount++
                        ChatRole.ASSISTANT -> {
                            assistantCount++
                            finalAssistantIsNonBlank = entry.text.isNotBlank()
                        }
                        ChatRole.STATUS -> {
                            if (entry.text.startsWith(TOOL_FOLLOW_UP_FAILURE_PREFIX)) {
                                sawToolFollowUpFailure = true
                            }
                        }
                    }
                }
                val shape = StringBuilder("ui_roles=")
                    .append("USER:").append(userCount).append(',')
                    .append("TOOL:").append(toolCount).append(',')
                    .append("ASSISTANT:").append(assistantCount)
                    .toString()
                assertTrue(shape, userCount == 2)
                assertTrue(shape, toolCount == 2)
                assertTrue(shape, assistantCount == 1 && finalAssistantIsNonBlank)
                assertFalse(shape, sawToolFollowUpFailure)
            }
        } finally {
            assertTrue(
                "The synthetic recovery conversation could not be removed.",
                repository.deleteConversation(testConversationId),
            )
        }
    }

    @Test
    fun subjectlessFollowUpSearchesTheOriginalMovieQuestion() = runBlocking {
        assumeTrue(
            "Live contextual web-search acceptance requires -e liveWebSearchTool true.",
            argument(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("Live Tool acceptance must run on a physical device.", !isEmulator())

        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PersonalEdgeApplication
        val repository = application.container.conversations
        val testConversationId = repository.createConversation(MOVIE_REQUEST)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var activeViewModel: PersonalEdgeViewModel? = null
                try {
                    scenario.onActivity { activity ->
                        activeViewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    }
                    val viewModel = requireNotNull(activeViewModel)
                    scenario.onActivity {
                        viewModel.refreshCredentials()
                        viewModel.refreshNetworkSetup()
                        viewModel.switchConversation(testConversationId)
                    }
                    assumeTrue(
                        "Web-search consent must already be enabled by the owner.",
                        viewModel.awaitUntil(PREFLIGHT_TIMEOUT_MILLIS) {
                            networkSetup.value.webSearchEnabled
                        },
                    )
                    assumeTrue(
                        "The optional Tavily fallback key must be present without reading its value.",
                        viewModel.awaitUntil(PREFLIGHT_TIMEOUT_MILLIS) {
                            credentials.value.statuses.any { status ->
                                status.slot == CredentialSlot.TAVILY_API_KEY && status.stored
                            }
                        },
                    )
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
                    assertTrue(
                        "The isolated movie conversation was not restored.",
                        viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                            chatHistory.value.activeConversationId == testConversationId &&
                                uiState.value.messages.none { entry ->
                                    entry.role == ChatRole.USER ||
                                        entry.role == ChatRole.TOOL ||
                                        entry.role == ChatRole.ASSISTANT
                                }
                        },
                    )

                    scenario.onActivity {
                        viewModel.updatePrompt(MOVIE_REQUEST)
                        viewModel.sendPrompt()
                    }
                    assertTrue(
                        "The original movie question did not start.",
                        viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                            uiState.value.activeTurnId != null
                        },
                    )
                    assertTrue(
                        "The original movie question was not accepted as a USER turn.",
                        viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                            uiState.value.messages.count { entry ->
                                entry.role == ChatRole.USER
                            } == 1
                        },
                    )
                    assertTrue(
                        "The original movie question did not terminate in time.",
                        viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                            confirmationCoordinator.pending.value != null ||
                                uiState.value.activeTurnId == null
                        },
                    )
                    assertNull(
                        "The original public-knowledge question unexpectedly opened confirmation.",
                        viewModel.confirmationCoordinator.pending.value,
                    )
                    assertNull(
                        "The original movie turn is still active.",
                        viewModel.uiState.value.activeTurnId,
                    )
                    assertNull(
                        "The original movie turn failed before the follow-up.",
                        viewModel.chatHistory.value.error,
                    )
                    val firstAnswer = viewModel.uiState.value.messages
                        .lastOrNull { entry -> entry.role == ChatRole.ASSISTANT }
                        ?.text
                        .orEmpty()
                    assertTrue(
                        "The original movie question did not produce an assistant answer.",
                        firstAnswer.isNotBlank(),
                    )
                    val firstReceiptCount = viewModel.uiState.value.messages.count { entry ->
                        entry.role == ChatRole.TOOL && entry.text == WEB_RECEIPT
                    }
                    assertTrue(
                        "The original question executed more than one automatic web search.",
                        firstReceiptCount in 0..1,
                    )

                    scenario.onActivity {
                        viewModel.updatePrompt(SUBJECTLESS_SEARCH_FOLLOW_UP)
                        viewModel.sendPrompt()
                    }
                    assertTrue(
                        "The contextual follow-up was not accepted as a new USER turn.",
                        viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                            uiState.value.messages.count { entry ->
                                entry.role == ChatRole.USER
                            } == 2
                        },
                    )
                    assertTrue(
                        "The contextual movie search did not terminate with one web receipt.",
                        viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                            uiState.value.activeTurnId == null &&
                                uiState.value.messages.count { entry ->
                                    entry.role == ChatRole.TOOL && entry.text == WEB_RECEIPT
                                } == firstReceiptCount + 1
                        },
                    )
                    assertNull(
                        "Contextual read-only web search unexpectedly opened confirmation.",
                        viewModel.confirmationCoordinator.pending.value,
                    )
                    assertNull(
                        "The contextual recovery capsule failed even though the search completed.",
                        viewModel.chatHistory.value.error,
                    )

                    assertContextualOutcomeBinding(
                        application = application,
                        conversationId = testConversationId,
                    )

                    val finalAnswer = viewModel.uiState.value.messages
                        .lastOrNull { entry -> entry.role == ChatRole.ASSISTANT }
                        ?.text
                        .orEmpty()
                    assertTrue(
                        "The contextual movie answer omitted its app-owned source section.",
                        finalAnswer.contains(SOURCE_SECTION_MARKER),
                    )
                    val answerBody = finalAnswer.substringBefore(SOURCE_SECTION_MARKER).trim()
                    val compactBody = answerBody.replace(" ", "")
                    assertTrue(
                        "The answer body lost the original movie title.",
                        compactBody.contains(MOVIE_TITLE_COMPACT),
                    )
                    assertTrue(
                        "The answer body omitted a stable movie identity fact.",
                        compactBody.contains("미야자키하야오") ||
                            compactBody.contains("스튜디오지브리") ||
                            compactBody.contains("일본애니메이션") ||
                            compactBody.contains("애니메이션영화") ||
                            compactBody.contains("2023") ||
                            compactBody.contains("아카데미") ||
                            compactBody.contains("오스카") ||
                            compactBody.contains("수상") ||
                            answerBody.contains("Hayao Miyazaki", ignoreCase = true) ||
                            answerBody.contains("Studio Ghibli", ignoreCase = true) ||
                            answerBody.contains("The Boy and the Heron", ignoreCase = true),
                    )
                    assertTrue(
                        "The answer body ended with a dangling clause before its sources.",
                        hasOnlyCompleteSentenceLines(answerBody),
                    )
                    assertTrue(
                        "The contextual movie answer omitted selected HTTPS sources.",
                        httpsLinkCount(finalAnswer) in 1..2,
                    )
                    assertFalse(
                        "The conditional follow-up itself leaked into the provider-grounded answer.",
                        finalAnswer.contains("잘 모르겠으면"),
                    )
                    assertFalse(
                        "The contextual search returned the app-owned no-result answer.",
                        finalAnswer.contains(NO_RELEVANT_RESULT),
                    )
                } finally {
                    val viewModel = activeViewModel
                    if (viewModel != null && viewModel.uiState.value.activeTurnId != null) {
                        scenario.onActivity { viewModel.cancelTurn() }
                        assertTrue(
                            "The contextual movie turn could not be cancelled before cleanup.",
                            viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                                uiState.value.activeTurnId == null
                            },
                        )
                    }
                }
            }
        } finally {
            assertTrue(
                "The isolated movie conversation could not be removed.",
                repository.deleteConversation(testConversationId),
            )
        }
    }

    private fun assertContextualOutcomeBinding(
        application: PersonalEdgeApplication,
        conversationId: String,
    ) {
        val database = application.container.database.openHelper.readableDatabase
        var state: String? = null
        var followUpUserOrdinal: Long? = null
        var recoverySourceUserOrdinal: Long? = null
        database.query(
            """
            SELECT state, user_message_ordinal, recovery_source_user_message_ordinal
            FROM turn_outcomes
            WHERE conversation_id = ?
            ORDER BY user_message_ordinal DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(conversationId),
        ).use { cursor ->
            assertTrue("The contextual turn outcome was not persisted.", cursor.moveToFirst())
            assertEquals("The contextual turn ID was not unique.", 1, cursor.count)
            state = cursor.getString(0)
            followUpUserOrdinal = cursor.getLong(1)
            if (!cursor.isNull(2)) recoverySourceUserOrdinal = cursor.getLong(2)
        }
        assertEquals("The contextual turn did not complete.", "ANSWER_COMPLETE", state)

        var firstUserOrdinal: Long? = null
        var lastUserOrdinal: Long? = null
        var userMessageCount = 0
        database.query(
            """
            SELECT MIN(ordinal), MAX(ordinal), COUNT(*)
            FROM messages
            WHERE conversation_id = ? AND role = 'USER'
            """.trimIndent(),
            arrayOf(conversationId),
        ).use { cursor ->
            assertTrue("The isolated USER rows could not be queried.", cursor.moveToFirst())
            userMessageCount = cursor.getInt(2)
            if (userMessageCount > 0) {
                firstUserOrdinal = cursor.getLong(0)
                lastUserOrdinal = cursor.getLong(1)
            }
        }
        assertEquals(
            "The isolated conversation did not contain exactly two USER rows.",
            2,
            userMessageCount,
        )
        assertEquals(
            "The contextual turn was not bound to its own follow-up USER row.",
            lastUserOrdinal,
            followUpUserOrdinal,
        )
        assertEquals(
            "The subjectless search did not retain the original movie USER row as recovery source.",
            firstUserOrdinal,
            recoverySourceUserOrdinal,
        )
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

    private fun argument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)

    private fun httpsLinkCount(value: String): Int {
        var count = 0
        var fromIndex = 0
        while (fromIndex < value.length) {
            val next = value.indexOf("https://", fromIndex)
            if (next < 0) break
            count++
            fromIndex = next + "https://".length
        }
        return count
    }

    private fun hasCompleteSentenceEnding(value: String): Boolean {
        var index = value.length
        while (index > 0) {
            val trailing = value.codePointBefore(index)
            if (!Character.isWhitespace(trailing)) break
            index -= Character.charCount(trailing)
        }
        while (index > 0) {
            val codePoint = value.codePointBefore(index)
            if (isSentenceCloser(codePoint)) {
                index -= Character.charCount(codePoint)
                continue
            }
            if (!isSentenceTerminator(codePoint)) return false
            return codePoint != '.'.code ||
                index - Character.charCount(codePoint) <= 0 ||
                value.codePointBefore(index - Character.charCount(codePoint)) != '.'.code
        }
        return false
    }

    private fun hasOnlyCompleteSentenceLines(value: String): Boolean {
        var lineStart = 0
        var index = 0
        while (index <= value.length) {
            if (index == value.length || value[index] == '\n') {
                var scan = lineStart
                var hasContent = false
                while (scan < index) {
                    val codePoint = value.codePointAt(scan)
                    if (!Character.isWhitespace(codePoint)) hasContent = true
                    scan += Character.charCount(codePoint)
                }
                if (hasContent && !hasCompleteSentenceEnding(value.substring(lineStart, index))) {
                    return false
                }
                lineStart = index + 1
            }
            index++
        }
        return true
    }

    private fun isSentenceTerminator(codePoint: Int): Boolean = when (codePoint) {
        '.'.code, '!'.code, '?'.code, '。'.code, '！'.code, '？'.code -> true
        else -> false
    }

    private fun isSentenceCloser(codePoint: Int): Boolean = when (codePoint) {
        '"'.code, '\''.code, '”'.code, '’'.code, ')'.code, ']'.code, '}'.code,
        '」'.code, '』'.code, '】'.code, '〉'.code, '》'.code,
        -> true
        else -> false
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)

    private companion object {
        const val LIVE_ARGUMENT = "liveWebSearchTool"
        const val KOREAN_REQUEST = "오늘 코스피 지수 정보를 웹에서 검색해서 출처와 함께 알려줘."
        const val WEB_RECEIPT = "웹 검색을 완료했습니다."
        const val SOURCE_SECTION_MARKER = "\n\n출처"
        const val MISSING_ANSWER_FOLLOW_UP = "왜 답변을 안 해줘."
        const val MOVIE_REQUEST = "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘"
        const val SUBJECTLESS_SEARCH_FOLLOW_UP = "잘 모르겠으면 웹에서 찾아서 알려줘"
        const val MOVIE_TITLE_COMPACT = "그대들은어떻게살것인가"
        const val NO_RELEVANT_RESULT = "질문과 직접 관련된 공개 웹 자료를 충분히 확인하지 못했습니다."
        const val TOOL_FOLLOW_UP_FAILURE_PREFIX = "Tool 결과는 위 영수증대로"
        const val PREFLIGHT_TIMEOUT_MILLIS = 5_000L
        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val START_TIMEOUT_MILLIS = 15_000L
        const val TURN_TIMEOUT_MILLIS = 240_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
