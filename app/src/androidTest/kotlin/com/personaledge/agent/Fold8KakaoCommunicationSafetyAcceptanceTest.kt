package com.personaledge.agent

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.llm.InferenceBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical acceptance for Kakao communication gates without sending or opening KakaoTalk.
 *
 * The test resolves the production-shaped share Intent, verifies notification reply remains off
 * by default, then asks the real model for the fixed share request and denies the confirmation.
 * Since execution is never approved, no external Activity or RemoteInput is invoked.
 */
@RunWith(AndroidJUnit4::class)
class Fold8KakaoCommunicationSafetyAcceptanceTest {
    @Test
    fun shareRequestStopsAtConfirmationWithoutSending() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Kakao communication acceptance requires -e kakaoCommunicationSafety true.",
            InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true",
        )
        assumeTrue("Physical-device acceptance only.", !isEmulator())

        val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(KAKAO_TALK_PACKAGE)
            putExtra(Intent.EXTRA_TEXT, FIXED_MESSAGE)
        }
        val shareResolvable = shareIntent.resolveActivity(application.packageManager) != null
        val notificationAccessGranted = application.container.notificationGateway.accessGranted()
        val replyEnabled = application.container.settings.current().kakaoNotificationReplyEnabled
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("kakao_share_resolvable", shareResolvable.toString())
                putString("kakao_notification_access_granted", notificationAccessGranted.toString())
                putString("kakao_reply_default_enabled", replyEnabled.toString())
            },
        )
        assertTrue("KakaoTalk cannot resolve the text share Intent.", shareResolvable)
        assertTrue("Notification-listener access is not currently granted.", notificationAccessGranted)
        assertFalse("Kakao notification reply must remain owner-disabled by default.", replyEnabled)

        var testConversationId: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var viewModel: PersonalEdgeViewModel
                scenario.onActivity { activity ->
                    viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                }
                try {
                    assumeTrue(
                        "The verified model is not installed.",
                        viewModel.awaitUntil(MODEL_TIMEOUT_MILLIS) {
                            uiState.value.modelStatus == ModelUiStatus.VERIFIED ||
                                uiState.value.modelStatus == ModelUiStatus.READY
                        },
                    )
                    if (viewModel.uiState.value.modelStatus != ModelUiStatus.READY) {
                        scenario.onActivity { viewModel.initializeRuntime(InferenceBackend.GPU) }
                        assumeTrue(
                            "The GPU runtime did not become ready.",
                            viewModel.awaitUntil(RUNTIME_TIMEOUT_MILLIS) {
                                uiState.value.modelStatus == ModelUiStatus.READY
                            },
                        )
                    }

                    scenario.onActivity { viewModel.startNewConversation() }
                    assertTrue(viewModel.uiState.value.messages.isEmpty())
                    scenario.onActivity {
                        viewModel.updatePrompt(KOREAN_REQUEST)
                        viewModel.sendPrompt()
                    }
                    assertTrue(
                        "The Kakao share-selection turn did not start.",
                        viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                            uiState.value.activeTurnId != null
                        },
                    )
                    assertTrue(
                        "The isolated Kakao conversation was not persisted.",
                        viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                            chatHistory.value.activeConversationId != null
                        },
                    )
                    testConversationId = viewModel.chatHistory.value.activeConversationId
                    assertTrue(
                        "The Kakao share-selection turn did not reach a terminal gate.",
                        viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                            confirmationCoordinator.pending.value != null ||
                                uiState.value.activeTurnId == null
                        },
                    )
                    val pending = viewModel.confirmationCoordinator.pending.value
                    assertNotNull("The model produced no confirmable Kakao share Tool.", pending)
                    assertEquals(KAKAO_SHARE_TOOL, pending?.toolName)

                    scenario.onActivity {
                        viewModel.resolveConfirmation(requireNotNull(pending).actionId, false)
                    }
                    assertTrue(
                        "The denied Kakao share turn did not terminate.",
                        viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                            confirmationCoordinator.pending.value == null &&
                                uiState.value.activeTurnId == null
                        },
                    )
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString("kakao_selected_tool", KAKAO_SHARE_TOOL)
                            putString("kakao_confirmation_outcome", "denied_before_execution")
                            putString("kakao_external_activity_opened", "false")
                            putString("kakao_message_sent", "false")
                        },
                    )
                } finally {
                    viewModel.confirmationCoordinator.pending.value?.let { pending ->
                        scenario.onActivity {
                            viewModel.resolveConfirmation(pending.actionId, false)
                        }
                    }
                    if (viewModel.uiState.value.activeTurnId != null) {
                        scenario.onActivity { viewModel.cancelTurn() }
                        viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                            uiState.value.activeTurnId == null
                        }
                    }
                }
            }
        } finally {
            testConversationId?.let { id ->
                assertTrue(
                    "The isolated Kakao conversation could not be removed.",
                    application.container.conversations.deleteConversation(id),
                )
            }
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

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)

    private companion object {
        const val ARGUMENT = "kakaoCommunicationSafety"
        const val KAKAO_TALK_PACKAGE = "com.kakao.talk"
        const val KAKAO_SHARE_TOOL = "kakao_share_message"
        const val FIXED_MESSAGE = "안녕"
        const val KOREAN_REQUEST = "카카오톡으로 안녕이라고 보내줘."
        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val START_TIMEOUT_MILLIS = 15_000L
        const val TURN_TIMEOUT_MILLIS = 240_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
