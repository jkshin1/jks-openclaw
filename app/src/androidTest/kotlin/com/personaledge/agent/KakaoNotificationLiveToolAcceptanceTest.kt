package com.personaledge.agent

import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.llm.InferenceBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in Fold8 acceptance for real-model selection of the local Kakao notification search Tool.
 *
 * The fixed query is an acceptance sentinel that cannot occur in a normal Kakao message. The test
 * therefore exercises selection, interlocks, local database execution, Tool-result injection and
 * receipt rendering without reading any captured body. It also proves the read-only Tool leaves
 * the capture-store row count unchanged.
 */
@RunWith(AndroidJUnit4::class)
class KakaoNotificationLiveToolAcceptanceTest {
    @Test
    fun fixedNoMatchQueryCompletesThroughRealGemmaWithoutReadingMessageBodies() = runBlocking {
        assumeTrue(
            "Live Kakao Tool acceptance requires -e liveKakaoTool true.",
            InstrumentationRegistry.getArguments().getString(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("Physical-device acceptance only.", !isEmulator())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var viewModel: PersonalEdgeViewModel
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                viewModel.refreshNotificationSetup()
            }

            assumeTrue(
                "Notification access and app capture must already be enabled by the owner.",
                viewModel.awaitUntil(PREFLIGHT_TIMEOUT_MILLIS) {
                    notificationSetup.value.accessGranted && notificationSetup.value.captureEnabled
                },
            )
            assumeTrue(
                "The verified model is not installed on this device.",
                viewModel.awaitUntil(MODEL_TIMEOUT_MILLIS) {
                    uiState.value.modelStatus in setOf(ModelUiStatus.VERIFIED, ModelUiStatus.READY)
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

            val application = InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationContext as PersonalEdgeApplication
            val countBefore = application.container.notifications.count()

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
                "The Kakao notification-search turn did not start.",
                viewModel.awaitUntil(START_TIMEOUT_MILLIS) { uiState.value.activeTurnId != null },
            )
            assertTrue(
                "The Kakao notification-search turn did not finish in time.",
                viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) { uiState.value.activeTurnId == null },
            )

            val messages = viewModel.uiState.value.messages
            val messageShape = messages
                .groupingBy { entry -> entry.role }
                .eachCount()
                .entries
                .sortedBy { entry -> entry.key.name }
                .joinToString(prefix = "message_roles=", separator = ",") { entry ->
                    "${entry.key.name}:${entry.value}"
                }
            assertTrue(
                messageShape,
                messages.any { entry ->
                    entry.role == ChatRole.TOOL &&
                        entry.text == "수집된 카카오톡 알림을 검색했습니다."
                },
            )
            assertTrue(
                messageShape,
                messages.any { entry -> entry.role == ChatRole.ASSISTANT && entry.text.isNotBlank() },
            )
            assertFalse(messageShape, messages.any { entry -> entry.role == ChatRole.STATUS })
            assertEquals(countBefore, application.container.notifications.count())
        }
    }

    private suspend fun PersonalEdgeViewModel.awaitUntil(
        timeoutMillis: Long,
        condition: PersonalEdgeViewModel.() -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(POLL_INTERVAL_MILLIS)
        }
        return condition()
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)

    private companion object {
        const val LIVE_ARGUMENT = "liveKakaoTool"
        const val SENTINEL_QUERY = "PERSONAL_EDGE_ACCEPTANCE_NO_MATCH_20260823"
        const val KOREAN_REQUEST =
            "kakao_notification_search 도구로 최근 1일 알림에서 '$SENTINEL_QUERY'를 검색해줘."
        const val PREFLIGHT_TIMEOUT_MILLIS = 5_000L
        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val START_TIMEOUT_MILLIS = 15_000L
        const val TURN_TIMEOUT_MILLIS = 240_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
