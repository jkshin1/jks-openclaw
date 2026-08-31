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
import java.util.regex.Pattern

/** Bounded Fold8 acceptance for one deterministic Dongtan answer and correction/recovery paths. */
@RunWith(AndroidJUnit4::class)
class WeatherLiveToolAcceptanceTest {
    @Test
    fun dongtanReturnsOneConcreteWeatherAnswerWithoutModelContradictions() = runBlocking {
        assumeTrue(
            "Live weather acceptance requires -e liveWeatherTool true.",
            argument(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("Live weather acceptance must run on a physical device.", !isEmulator())

        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PersonalEdgeApplication
        val settings = application.container.settings.current()
        assumeTrue(
            "Weather lookup consent must already be enabled by the owner.",
            settings.webSearchEnabled,
        )

        var testConversationId: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var viewModel: PersonalEdgeViewModel
                scenario.onActivity { activity ->
                    viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    viewModel.refreshNetworkSetup()
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

                scenario.onActivity { viewModel.startNewConversation() }
                assertTrue(
                    "The isolated weather acceptance conversation was not empty.",
                    viewModel.uiState.value.messages.isEmpty(),
                )
                scenario.onActivity {
                    viewModel.updatePrompt(KOREAN_REQUEST)
                    viewModel.sendPrompt()
                }
                assertTrue(
                    "The live weather turn did not start.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                        uiState.value.activeTurnId != null
                    },
                )
                assertTrue(
                    "The isolated weather conversation was not persisted.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                        chatHistory.value.activeConversationId != null
                    },
                )
                testConversationId = viewModel.chatHistory.value.activeConversationId

                assertTrue(
                    "The automatic weather turn did not terminate in time.",
                    viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                        confirmationCoordinator.pending.value != null ||
                            uiState.value.activeTurnId == null
                    },
                )
                assertNull(
                    "Read-only weather unexpectedly opened a confirmation sheet.",
                    viewModel.confirmationCoordinator.pending.value,
                )
                assertNull(
                    "The automatic weather turn is still active.",
                    viewModel.uiState.value.activeTurnId,
                )

                val messages = viewModel.uiState.value.messages
                var userCount = 0
                var weatherReceiptCount = 0
                var webReceiptCount = 0
                var assistantCount = 0
                var statusCount = 0
                var finalAnswer = ""
                for (entry in messages) {
                    when (entry.role) {
                        ChatRole.USER -> userCount++
                        ChatRole.TOOL -> {
                            if (entry.text == WEATHER_RECEIPT) weatherReceiptCount++
                            if (entry.text == WEB_RECEIPT) webReceiptCount++
                        }
                        ChatRole.ASSISTANT -> {
                            assistantCount++
                            finalAnswer = entry.text
                        }
                        ChatRole.STATUS -> statusCount++
                    }
                }
                val shape = StringBuilder("roles=")
                    .append("USER:").append(userCount).append(',')
                    .append("WEATHER_TOOL:").append(weatherReceiptCount).append(',')
                    .append("WEB_TOOL:").append(webReceiptCount).append(',')
                    .append("ASSISTANT:").append(assistantCount).append(',')
                    .append("STATUS:").append(statusCount)
                    .toString()

                assertTrue(shape, weatherReceiptCount == 1)
                assertTrue(shape, webReceiptCount == 0)
                assertTrue(shape, statusCount == 0)
                assertTrue(shape, assistantCount == 1 && finalAnswer.length > 0)
                assertTrue(
                    "The answer omitted concrete temperature or precipitation values.",
                    WEATHER_VALUE_PATTERN.matcher(finalAnswer).find(),
                )
                assertTrue(
                    "The answer omitted the explicit Open-Meteo source URL.",
                    finalAnswer.contains(SOURCE_URL),
                )
                assertTrue(
                    "The answer did not start with the requested place.",
                    finalAnswer.startsWith(DONGTAN_ANSWER_PREFIX),
                )
                assertTrue(
                    "The resolved location did not contain Dongtan.",
                    finalAnswer.contains("확인 위치:") && finalAnswer.contains("동탄"),
                )
                assertFalse("The answer retained an unrelated Seoul location.", finalAnswer.contains("서울"))
                assertFalse("The obsolete supplement label remained visible.", finalAnswer.contains("앱 확인 값"))
                assertFalse("A model-invented source URL remained visible.", finalAnswer.contains("open-meteo-mmet"))
                assertFalse(
                    "The answer regressed to a provider-name-only referral.",
                    finalAnswer == SOURCE_NAME ||
                        finalAnswer.endsWith("에서 확인할 수 있습니다."),
                )
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
    fun correctionStartsDirectlyWithDongtanAndUsesOneTrustedValueSet() = runBlocking {
        assumeTrue(
            "Live weather correction requires -e liveWeatherTool true.",
            argument(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("Live weather correction must run on a physical device.", !isEmulator())

        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PersonalEdgeApplication
        assumeTrue(
            "Weather lookup consent must already be enabled by the owner.",
            application.container.settings.current().webSearchEnabled,
        )
        val repository = application.container.conversations
        val testConversationId = repository.createConversation(KOREAN_REQUEST)
        try {
            assertTrue(
                repository.appendMessage(testConversationId, MessageRole.USER, KOREAN_REQUEST) != null,
            )
            assertTrue(
                repository.appendMessage(
                    testConversationId,
                    MessageRole.TOOL_RECEIPT,
                    WEATHER_RECEIPT,
                ) != null,
            )
            assertTrue(
                repository.appendMessage(
                    testConversationId,
                    MessageRole.ASSISTANT,
                    SYNTHETIC_WRONG_SEOUL_ANSWER,
                ) != null,
            )

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var viewModel: PersonalEdgeViewModel
                scenario.onActivity { activity ->
                    viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    viewModel.refreshNetworkSetup()
                    viewModel.switchConversation(testConversationId)
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
                assertTrue(
                    "The synthetic weather correction context was not restored.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                        chatHistory.value.activeConversationId == testConversationId &&
                            uiState.value.messages.size == 3
                    },
                )

                scenario.onActivity {
                    viewModel.updatePrompt(LOCATION_CORRECTION)
                    viewModel.sendPrompt()
                }
                assertTrue(
                    "The correction turn did not start.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                        uiState.value.activeTurnId != null
                    },
                )
                assertTrue(
                    "The correction turn did not terminate in time.",
                    viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                        confirmationCoordinator.pending.value != null ||
                            uiState.value.activeTurnId == null
                    },
                )
                assertNull(
                    "Read-only correction unexpectedly opened confirmation.",
                    viewModel.confirmationCoordinator.pending.value,
                )

                val finalAnswer = viewModel.uiState.value.messages
                    .last { entry -> entry.role == ChatRole.ASSISTANT }
                    .text
                assertTrue(
                    "The corrected answer did not start naturally with Dongtan.",
                    finalAnswer.startsWith(DONGTAN_ANSWER_PREFIX),
                )
                assertFalse(
                    "The answer repeated the awkward correction phrase.",
                    finalAnswer.startsWith("서울이 아니라"),
                )
                assertFalse("The corrected answer still mentioned Seoul.", finalAnswer.contains("서울"))
                assertFalse("The obsolete supplement label remained visible.", finalAnswer.contains("앱 확인 값"))
                assertTrue(
                    "The corrected answer omitted concrete weather values.",
                    WEATHER_VALUE_PATTERN.matcher(finalAnswer).find(),
                )
                assertTrue("The corrected answer omitted the source URL.", finalAnswer.contains(SOURCE_URL))
            }
        } finally {
            assertTrue(
                "The synthetic correction conversation could not be removed.",
                repository.deleteConversation(testConversationId),
            )
        }
    }

    @Test
    fun missingWeatherAnswerFollowUpRepeatsTheReadAndReturnsValues() = runBlocking {
        assumeTrue(
            "Live weather recovery requires -e liveWeatherTool true.",
            argument(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("Live weather recovery must run on a physical device.", !isEmulator())

        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PersonalEdgeApplication
        assumeTrue(
            "Weather lookup consent must already be enabled by the owner.",
            application.container.settings.current().webSearchEnabled,
        )
        val repository = application.container.conversations
        val testConversationId = repository.createConversation(KOREAN_REQUEST)
        try {
            assertTrue(
                repository.appendMessage(
                    testConversationId,
                    MessageRole.USER,
                    KOREAN_REQUEST,
                ) != null,
            )
            assertTrue(
                repository.appendMessage(
                    testConversationId,
                    MessageRole.TOOL_RECEIPT,
                    WEATHER_RECEIPT,
                ) != null,
            )

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var viewModel: PersonalEdgeViewModel
                scenario.onActivity { activity ->
                    viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    viewModel.refreshNetworkSetup()
                    viewModel.switchConversation(testConversationId)
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
                assertTrue(
                    "The incomplete weather conversation was not restored.",
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
                    "Recovered read-only weather unexpectedly opened confirmation.",
                    viewModel.confirmationCoordinator.pending.value,
                )

                var userCount = 0
                var weatherReceiptCount = 0
                var assistantCount = 0
                var statusCount = 0
                var sawToolFollowUpFailure = false
                var finalAnswer = ""
                for (entry in viewModel.uiState.value.messages) {
                    when (entry.role) {
                        ChatRole.USER -> userCount++
                        ChatRole.TOOL -> {
                            if (entry.text == WEATHER_RECEIPT) weatherReceiptCount++
                        }
                        ChatRole.ASSISTANT -> {
                            assistantCount++
                            finalAnswer = entry.text
                        }
                        ChatRole.STATUS -> {
                            statusCount++
                            if (entry.text.startsWith(TOOL_FOLLOW_UP_FAILURE_PREFIX)) {
                                sawToolFollowUpFailure = true
                            }
                        }
                    }
                }
                val shape = StringBuilder("roles=")
                    .append("USER:").append(userCount).append(',')
                    .append("WEATHER_TOOL:").append(weatherReceiptCount).append(',')
                    .append("ASSISTANT:").append(assistantCount).append(',')
                    .append("STATUS:").append(statusCount)
                    .toString()
                assertTrue(shape, userCount == 2)
                assertTrue(shape, weatherReceiptCount == 2)
                assertTrue(shape, assistantCount == 1)
                assertFalse(shape, sawToolFollowUpFailure)
                assertTrue(
                    "The recovered answer omitted concrete weather values.",
                    WEATHER_VALUE_PATTERN.matcher(finalAnswer).find(),
                )
                assertTrue(
                    "The recovered answer omitted the source URL.",
                    finalAnswer.contains(SOURCE_URL),
                )
                assertTrue(
                    "The recovered answer did not start with Dongtan.",
                    finalAnswer.startsWith(DONGTAN_ANSWER_PREFIX),
                )
                assertFalse("The recovered answer mentioned Seoul.", finalAnswer.contains("서울"))
                assertFalse("The recovered answer retained the supplement label.", finalAnswer.contains("앱 확인 값"))
            }
        } finally {
            assertTrue(
                "The synthetic weather recovery conversation could not be removed.",
                repository.deleteConversation(testConversationId),
            )
        }
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

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)

    private companion object {
        const val LIVE_ARGUMENT = "liveWeatherTool"
        const val KOREAN_REQUEST = "오늘 동탄 날씨를 알려줘"
        const val WEATHER_RECEIPT = "현재 및 오늘 날씨를 확인했습니다."
        const val WEB_RECEIPT = "웹 검색을 완료했습니다."
        const val SOURCE_NAME = "Open-Meteo"
        const val SOURCE_URL = "https://open-meteo.com/"
        const val MISSING_ANSWER_FOLLOW_UP = "왜 답변을 안 해줘"
        const val TOOL_FOLLOW_UP_FAILURE_PREFIX = "Tool 결과는 위 영수증대로"
        const val LOCATION_CORRECTION = "서울이 아니라 동탄"
        const val DONGTAN_ANSWER_PREFIX = "동탄의 현재 날씨는"
        const val SYNTHETIC_WRONG_SEOUL_ANSWER = "오늘 서울의 날씨는 5.1°C입니다."
        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val START_TIMEOUT_MILLIS = 15_000L
        const val TURN_TIMEOUT_MILLIS = 240_000L
        const val POLL_INTERVAL_MILLIS = 250L
        val WEATHER_VALUE_PATTERN: Pattern = Pattern.compile(
            "-?\\d{1,3}(?:\\.\\d+)?\\s*(?:°\\s*C|℃|도|%)",
            Pattern.CASE_INSENSITIVE,
        )
    }
}
