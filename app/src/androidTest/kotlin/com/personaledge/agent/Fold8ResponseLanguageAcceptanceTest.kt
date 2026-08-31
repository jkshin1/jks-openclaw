package com.personaledge.agent

import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.llm.InferenceBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in physical acceptance for the real model's response-language policy.
 *
 * The prompts are fixed synthetic text and explicitly forbid Tool use. The response itself is
 * inspected only in memory and is never included in test output, diagnostics, or assertion text.
 * Only aggregate script counts and the expected language label leave the test process.
 */
@RunWith(AndroidJUnit4::class)
class Fold8ResponseLanguageAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun defaultsToKoreanAndHonorsAnExplicitEnglishRequest() {
        assumeTrue(
            "Fold8 response-language acceptance requires -e liveResponseLanguage true.",
            argument(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("This acceptance is restricted to the owner's Fold8.", isTargetFold8())

        val testConversationIds = java.util.LinkedHashSet<String>()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val viewModel = scenario.readyGpuViewModel()
            try {

                scenario.startIsolatedConversation(viewModel)
                val koreanAnswer = scenario.sendAndAwaitAnswer(
                    viewModel,
                    DEFAULT_KOREAN_PROMPT,
                    { conversationId -> testConversationIds.add(conversationId) },
                )
                val koreanMetrics = LanguageMetrics.from(koreanAnswer)
                assertTrue(
                    "The default-language answer was not predominantly Korean.",
                    koreanMetrics.isPredominantlyKorean,
                )
                assertTrue(
                    "The default-language answer ignored the explicit short-answer constraint.",
                    koreanMetrics.codePoints <= MAX_BRIEF_RESPONSE_CODE_POINTS,
                )

                assertTrue(
                    "The Fold8 thermal state did not become runnable before the explicit-language turn.",
                    viewModel.awaitRunnableThermalState(),
                )
                scenario.startIsolatedConversation(viewModel)
                val englishAnswer = scenario.sendAndAwaitAnswer(
                    viewModel,
                    EXPLICIT_ENGLISH_PROMPT,
                    { conversationId -> testConversationIds.add(conversationId) },
                )
                val englishMetrics = LanguageMetrics.from(englishAnswer)
                assertTrue(
                    "The explicit-English answer was not predominantly English.",
                    englishMetrics.isPredominantlyEnglish,
                )

                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("response_language_default", "ko")
                        putInt("response_language_default_hangul", koreanMetrics.hangulSyllables)
                        putInt("response_language_default_latin", koreanMetrics.latinLetters)
                        putInt("response_language_default_code_points", koreanMetrics.codePoints)
                        putString("response_language_default_length", "brief")
                        putString("response_language_override", "en")
                        putInt("response_language_override_hangul", englishMetrics.hangulSyllables)
                        putInt("response_language_override_latin", englishMetrics.latinLetters)
                        putInt("response_language_override_code_points", englishMetrics.codePoints)
                    },
                )
            } finally {
                scenario.removeOnlyTestConversations(viewModel, testConversationIds)
            }
        }
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

    private fun ActivityScenario<MainActivity>.startIsolatedConversation(
        viewModel: PersonalEdgeViewModel,
    ) {
        onActivity { activity ->
            ViewModelProvider(activity)[PersonalEdgeViewModel::class.java].startNewConversation()
        }
        assertTrue(
            "The isolated acceptance conversation did not start from an empty state.",
            viewModel.awaitUntil(PERSISTENCE_TIMEOUT_MILLIS) {
                chatHistory.value.activeConversationId == null && uiState.value.messages.isEmpty()
            },
        )
    }

    private fun ActivityScenario<MainActivity>.sendAndAwaitAnswer(
        viewModel: PersonalEdgeViewModel,
        prompt: String,
        onConversationCreated: (String) -> Unit,
    ): String {
        val messageCountBefore = viewModel.uiState.value.messages.size
        onActivity { activity ->
            ViewModelProvider(activity)[PersonalEdgeViewModel::class.java].apply {
                updatePrompt(prompt)
                sendPrompt()
            }
        }
        assertTrue(
            "The real-model language turn did not start.",
            viewModel.awaitUntil(START_TIMEOUT_MILLIS) { uiState.value.activeTurnId != null },
        )
        val testConversationId = viewModel.awaitActiveConversationId()
        assertNotNull("The isolated language conversation was not persisted.", testConversationId)
        onConversationCreated(requireNotNull(testConversationId))
        assertTrue(
            "The real-model language turn did not terminate in time.",
            viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) { uiState.value.activeTurnId == null },
        )

        val messages = viewModel.uiState.value.messages
        var answer: String? = null
        var toolExecuted = false
        for (index in messageCountBefore until messages.size) {
            val entry = messages[index]
            if (entry.role == ChatRole.ASSISTANT) answer = entry.text
            if (entry.role == ChatRole.TOOL) toolExecuted = true
        }
        val unexpectedConfirmation = viewModel.confirmationCoordinator.pending.value
        if (unexpectedConfirmation != null) {
            onActivity { activity ->
                ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    .resolveConfirmation(unexpectedConfirmation.actionId, false)
            }
        }
        assertTrue(
            "The language-only turn unexpectedly proposed a state-changing Tool.",
            unexpectedConfirmation == null,
        )
        assertFalse(
            "The language-only turn unexpectedly executed a Tool.",
            toolExecuted,
        )
        assertNotNull("The language turn produced no assistant answer.", answer)
        assertTrue(
            "The language turn produced an empty assistant answer.",
            containsNonWhitespace(requireNotNull(answer)),
        )
        return requireNotNull(answer)
    }

    private fun PersonalEdgeViewModel.awaitActiveConversationId(): String? {
        awaitUntil(PERSISTENCE_TIMEOUT_MILLIS) {
            chatHistory.value.activeConversationId != null
        }
        return chatHistory.value.activeConversationId
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

    private fun ActivityScenario<MainActivity>.removeOnlyTestConversations(
        viewModel: PersonalEdgeViewModel,
        testConversationIds: Set<String>,
    ) {
        viewModel.confirmationCoordinator.pending.value?.let { pending ->
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
                "The isolated language turn did not stop during cleanup.",
                viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) { uiState.value.activeTurnId == null },
            )
        }
        if (testConversationIds.isEmpty()) return

        onActivity { activity ->
            ViewModelProvider(activity)[PersonalEdgeViewModel::class.java].openHistory()
        }
        assertTrue(
            "The isolated language conversations were not visible for exact cleanup.",
            viewModel.awaitUntil(PERSISTENCE_TIMEOUT_MILLIS) {
                var found = 0
                for (conversation in chatHistory.value.conversations) {
                    if (testConversationIds.contains(conversation.id)) found += 1
                }
                found == testConversationIds.size
            },
        )

        for (testId in testConversationIds) {
            if (viewModel.chatHistory.value.activeConversationId != testId) {
                onActivity { activity ->
                    ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                        .switchConversation(testId)
                }
                assertTrue(
                    "An isolated response-language conversation could not be selected for cleanup.",
                    viewModel.awaitUntil(PERSISTENCE_TIMEOUT_MILLIS) {
                        chatHistory.value.activeConversationId == testId
                    },
                )
            }
            onActivity { activity ->
                ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    .deleteConversation(testId)
            }
            assertTrue(
                "An isolated response-language conversation could not be removed.",
                viewModel.awaitUntil(PERSISTENCE_TIMEOUT_MILLIS) {
                    chatHistory.value.activeConversationId == null
                },
            )
        }
        onActivity { activity ->
            ViewModelProvider(activity)[PersonalEdgeViewModel::class.java].closeHistory()
        }
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

    private data class LanguageMetrics(
        val hangulSyllables: Int,
        val latinLetters: Int,
        val codePoints: Int,
    ) {
        val isPredominantlyKorean: Boolean
            get() = hangulSyllables >= MIN_HANGUL_SYLLABLES &&
                hangulSyllables * KOREAN_DOMINANCE_RATIO >= latinLetters

        val isPredominantlyEnglish: Boolean
            get() = latinLetters >= MIN_LATIN_LETTERS &&
                latinLetters >= hangulSyllables * ENGLISH_DOMINANCE_RATIO

        companion object {
            fun from(text: String): LanguageMetrics {
                var hangulSyllables = 0
                var latinLetters = 0
                for (index in 0 until text.length) {
                    val character = text[index]
                    if (character in '\uAC00'..'\uD7A3') hangulSyllables += 1
                    if (character in 'A'..'Z' || character in 'a'..'z') latinLetters += 1
                }
                return LanguageMetrics(
                    hangulSyllables = hangulSyllables,
                    latinLetters = latinLetters,
                    codePoints = text.codePointCount(0, text.length),
                )
            }
        }
    }

    private companion object {
        const val LIVE_ARGUMENT = "liveResponseLanguage"
        const val MAX_BRIEF_RESPONSE_CODE_POINTS = 240
        const val GENERIC_PREFIX = "generic"
        const val TARGET_FOLD8_MODEL = "SM-F971N"
        const val DEFAULT_KOREAN_PROMPT =
            "Do not use tools. Explain in one short sentence why rainbows appear."
        const val EXPLICIT_ENGLISH_PROMPT =
            "도구를 사용하지 말고 무지개가 생기는 이유를 영어 한 문장으로 답해 주세요."
        const val MIN_HANGUL_SYLLABLES = 4
        const val MIN_LATIN_LETTERS = 10
        const val KOREAN_DOMINANCE_RATIO = 2
        const val ENGLISH_DOMINANCE_RATIO = 4
        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val START_TIMEOUT_MILLIS = 15_000L
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val THERMAL_READY_TIMEOUT_MILLIS = 300_000L
        const val PERSISTENCE_TIMEOUT_MILLIS = 15_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
