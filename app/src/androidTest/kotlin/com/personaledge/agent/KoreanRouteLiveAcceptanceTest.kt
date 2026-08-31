package com.personaledge.agent

import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.llm.InferenceBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly opted-in provider acceptance for the owner's Fold8 and saved NAVER Maps credentials.
 *
 * This is not part of an ordinary connected test run. It requires the runner argument
 * `liveNaverRoute=true`, a physical device, saved credentials, enabled route consent, and the
 * verified model. It uses public civic/commercial road addresses and fails if a confirmation sheet
 * appears; it never reads, clears, prints, or replaces the credential vault.
 */
@RunWith(AndroidJUnit4::class)
class KoreanRouteLiveAcceptanceTest {
    @Test
    fun koreanRoadAddressesCompleteTheLiveRouteWithoutConfirmation() = runBlocking {
        assumeTrue(
            "Live NAVER route acceptance requires -e liveNaverRoute true.",
            InstrumentationRegistry.getArguments().getString(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("Live provider acceptance must run on a physical device.", !isEmulator())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var viewModel: PersonalEdgeViewModel
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                viewModel.refreshCredentials()
                viewModel.refreshNetworkSetup()
            }

            assumeTrue(
                "Both NAVER Maps credential slots must be present without reading their values.",
                viewModel.awaitUntil(PREFLIGHT_TIMEOUT_MILLIS) {
                    val requiredSlots = setOf(
                        CredentialSlot.NAVER_MAP_CLIENT_ID,
                        CredentialSlot.NAVER_MAP_CLIENT_SECRET,
                    )
                    credentials.value.statuses
                        .filter { status -> status.slot in requiredSlots }
                        .let { statuses ->
                            statuses.size == requiredSlots.size && statuses.all { status -> status.stored }
                        }
                },
            )
            assumeTrue(
                "Route lookup consent must already be enabled by the owner.",
                viewModel.awaitUntil(PREFLIGHT_TIMEOUT_MILLIS) {
                    networkSetup.value.routeLookupEnabled
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

            // Keep prior private conversation context out of this provider acceptance turn. The
            // stored conversation remains untouched and this test never deletes any history.
            scenario.onActivity { viewModel.startNewConversation() }
            assertTrue(
                "The isolated acceptance conversation was not created.",
                viewModel.uiState.value.messages.isEmpty(),
            )
            val firstNewMessage = 0
            scenario.onActivity {
                viewModel.updatePrompt(KOREAN_REQUEST)
                viewModel.sendPrompt()
            }
            assertTrue(
                "The Korean route turn did not start.",
                viewModel.awaitUntil(START_TIMEOUT_MILLIS) { uiState.value.activeTurnId != null },
            )

            assertTrue(
                "The automatic route turn did not terminate in time.",
                viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                    confirmationCoordinator.pending.value != null || uiState.value.activeTurnId == null
                },
            )
            assertNull(
                "Read-only route lookup unexpectedly opened a confirmation sheet.",
                viewModel.confirmationCoordinator.pending.value,
            )
            assertNull(
                "The automatic route turn is still active.",
                viewModel.uiState.value.activeTurnId,
            )

            val newMessages = viewModel.uiState.value.messages.drop(firstNewMessage)
            val messageShape = newMessages
                .groupingBy { entry -> entry.role }
                .eachCount()
                .entries
                .sortedBy { entry -> entry.key.name }
                .joinToString(prefix = "message_roles=", separator = ",") { entry ->
                    "${entry.key.name}:${entry.value}"
                }
            assertTrue(
                messageShape,
                newMessages.any { entry ->
                    entry.role == ChatRole.TOOL &&
                        entry.text == "네이버 지도에서 이동 시간을 조회했습니다."
                },
            )
            assertFalse(
                messageShape,
                newMessages.any { entry -> entry.role == ChatRole.STATUS },
            )
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
        const val LIVE_ARGUMENT = "liveNaverRoute"
        const val ORIGIN = "서울특별시 중구 세종대로 110"
        const val DESTINATION = "서울특별시 강남구 강남대로 396"
        const val KOREAN_REQUEST =
            "route_estimate 도구로 출발지 '$ORIGIN', 도착지 '$DESTINATION'의 자동차 이동 시간을 조회해줘."
        const val PREFLIGHT_TIMEOUT_MILLIS = 5_000L
        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val START_TIMEOUT_MILLIS = 15_000L
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
