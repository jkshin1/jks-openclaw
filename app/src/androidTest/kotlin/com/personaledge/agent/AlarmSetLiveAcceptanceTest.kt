package com.personaledge.agent

import android.os.Build
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.ConversationEntity
import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.tools.AlarmSetTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Explicitly opted-in physical acceptance for the one production Tool that creates a real alarm.
 *
 * This test never clears or edits the clock app. It requires an owner-chosen time at least three
 * minutes ahead, waits for a real tap on the production confirmation sheet, verifies the resulting
 * next-alarm timestamp, and leaves the alarm for the owner to remove in Samsung Clock.
 */
@RunWith(AndroidJUnit4::class)
class AlarmSetLiveAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun ownerApprovedOneShotAlarmAppearsAsTheNextClockAlarm() = runBlocking {
        assumeTrue(
            "Physical alarm creation requires -e liveAlarmSet true.",
            argument(LIVE_ARGUMENT) == "true",
        )
        assumeTrue(
            "Interactive alarm confirmation requires -e interactiveAlarmSetApproval true.",
            argument(INTERACTIVE_APPROVAL_ARGUMENT) == "true",
        )
        assumeTrue("Live alarm acceptance must run on a physical device.", !isEmulator())

        val requestedTime = argument(TIME_ARGUMENT).orEmpty()
        assumeTrue(
            "Pass an owner-approved alarm time as -e alarmSetTime HH:mm.",
            TIME_PATTERN.matches(requestedTime),
        )
        val zone = ZoneId.systemDefault()
        val now = Instant.now().atZone(zone)
        val localTime = LocalTime.parse(requestedTime)
        var target = now.toLocalDate().atTime(localTime).atZone(zone)
        if (!target.toInstant().isAfter(now.toInstant())) target = target.plusDays(1)
        assumeTrue(
            "Choose an alarm at least three minutes ahead so the acceptance cannot ring mid-test.",
            target.toInstant().toEpochMilli() - now.toInstant().toEpochMilli() >= MIN_LEAD_MILLIS,
        )

        val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication
        val existingNext = application.container.alarms.nextAlarm()
        assumeTrue(
            "An existing alarm occurs before or at the requested acceptance time; choose an earlier " +
                "free time or inspect the clock first.",
            existingNext == null ||
                existingNext.triggerAtEpochMillis > target.toInstant().toEpochMilli() + MATCH_TOLERANCE_MILLIS,
        )

        val ownerConversationIds = application.container.conversations
            .recentConversations(MAX_CONVERSATION_SNAPSHOT)
            .map(ConversationEntity::id)
            .toSet()
        val conversationCountBefore = application.container.conversations.conversationCount()
        val messageCountBefore = application.container.conversations.messageCount()
        val settingsBefore = application.container.settings.current()
        val credentialsBefore = application.container.credentials.statuses()
            .associate { status -> status.slot to status.stored }
        val notificationCountBefore = application.container.notifications.count()
        var testConversationId: String? = null

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var viewModel: PersonalEdgeViewModel
                scenario.onActivity { activity ->
                    viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                }
                assumeTrue(
                    "The verified model is not installed on this device.",
                    viewModel.awaitUntil(MODEL_TIMEOUT_MILLIS) {
                        uiState.value.modelStatus in setOf(ModelUiStatus.VERIFIED, ModelUiStatus.READY)
                    },
                )
                if (viewModel.uiState.value.modelStatus != ModelUiStatus.READY) {
                    scenario.onActivity { viewModel.initializeRuntime(InferenceBackend.GPU) }
                    assumeTrue(
                        "The GPU runtime did not become ready in time.",
                        viewModel.awaitUntil(RUNTIME_TIMEOUT_MILLIS) {
                            uiState.value.modelStatus == ModelUiStatus.READY
                        },
                    )
                }
                assumeTrue(
                    "The Fold8 thermal state did not become runnable before the alarm acceptance turn.",
                    viewModel.awaitUntil(THERMAL_READY_TIMEOUT_MILLIS) {
                        ThermalTurnPolicy.canStart(uiState.value.thermalStatus)
                    },
                )

                scenario.onActivity { viewModel.startNewConversation() }
                scenario.onActivity {
                    viewModel.updatePrompt(request(requestedTime))
                    viewModel.sendPrompt()
                }
                assertTrue(
                    "The alarm-set turn did not start.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                        uiState.value.activeTurnId != null
                    },
                )
                testConversationId = viewModel.awaitActiveConversationId()
                assertNotNull("The isolated alarm conversation was not persisted.", testConversationId)
                assertFalse(
                    "The alarm acceptance reused an owner's conversation.",
                    testConversationId in ownerConversationIds,
                )

                val pending = viewModel.awaitConfirmation()
                assertNotNull("The model produced no confirmable alarm_set call.", pending)
                assertEquals(AlarmSetTool.NAME, pending!!.toolName)
                assertEquals("알람 추가", pending.preview.title)
                assertEquals(expectedPreview(requestedTime), pending.preview.summary)
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("privacy_alarm_set_confirmation_ready", "true")
                        putString("privacy_alarm_set_time", requestedTime)
                    },
                )

                assertTrue(
                    "The on-screen alarm confirmation was not resolved in time.",
                    viewModel.awaitUntil(APPROVAL_TIMEOUT_MILLIS) {
                        confirmationCoordinator.pending.value == null
                    },
                )
                assertTrue(
                    "The owner-approved alarm turn did not terminate in time.",
                    viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                        uiState.value.activeTurnId == null
                    },
                )
                val messages = viewModel.uiState.value.messages
                assertTrue(
                    "No trusted alarm-set receipt was rendered.",
                    messages.any { entry ->
                        entry.role == ChatRole.TOOL &&
                            entry.text == "시계 앱에 알람 추가를 요청했습니다."
                    },
                )
                assertTrue(
                    "The alarm-set turn produced no assistant answer.",
                    messages.any { entry ->
                        entry.role == ChatRole.ASSISTANT && entry.text.isNotBlank()
                    },
                )
                assertFalse(
                    "The alarm-set turn ended with a runtime status error.",
                    messages.any { entry -> entry.role == ChatRole.STATUS },
                )

                val observed = application.container.alarms.awaitNextAlarmMatching(
                    targetEpochMillis = target.toInstant().toEpochMilli(),
                )
                assertNotNull(
                    "Samsung Clock did not expose the owner-approved time as the next alarm.",
                    observed,
                )
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("privacy_alarm_set_next_matches", "true")
                        putString("privacy_alarm_set_owner_cleanup_required", "true")
                    },
                )
            }
        } finally {
            removeOnlyTestConversation(application, ownerConversationIds, testConversationId)
        }

        assertEquals(settingsBefore, application.container.settings.current())
        assertEquals(
            credentialsBefore,
            application.container.credentials.statuses()
                .associate { status -> status.slot to status.stored },
        )
        assertEquals(notificationCountBefore, application.container.notifications.count())
        assertEquals(conversationCountBefore, application.container.conversations.conversationCount())
        assertEquals(messageCountBefore, application.container.conversations.messageCount())
    }

    private suspend fun PersonalEdgeViewModel.awaitConfirmation(): PendingConfirmation? {
        awaitUntil(TURN_TIMEOUT_MILLIS) {
            confirmationCoordinator.pending.value != null || uiState.value.activeTurnId == null
        }
        return confirmationCoordinator.pending.value
    }

    private suspend fun PersonalEdgeViewModel.awaitActiveConversationId(): String? {
        awaitUntil(PERSISTENCE_TIMEOUT_MILLIS) {
            chatHistory.value.activeConversationId != null
        }
        return chatHistory.value.activeConversationId
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

    private suspend fun com.personaledge.core.tools.AlarmGateway.awaitNextAlarmMatching(
        targetEpochMillis: Long,
    ) = run {
        repeat(ALARM_POLL_ATTEMPTS) {
            nextAlarm()?.let { alarm ->
                if (abs(alarm.triggerAtEpochMillis - targetEpochMillis) <= MATCH_TOLERANCE_MILLIS) {
                    return@run alarm
                }
            }
            delay(ALARM_POLL_INTERVAL_MILLIS)
        }
        null
    }

    private suspend fun removeOnlyTestConversation(
        application: PersonalEdgeApplication,
        ownerConversationIds: Set<String>,
        knownTestConversationId: String?,
    ) {
        val candidate = knownTestConversationId?.takeUnless { it in ownerConversationIds }
            ?: application.container.conversations
                .recentConversations(MAX_CONVERSATION_SNAPSHOT)
                .firstOrNull { conversation ->
                    conversation.id !in ownerConversationIds &&
                        conversation.title.startsWith(TEST_TITLE_PREFIX)
                }
                ?.id
        if (candidate != null) {
            assertTrue(
                "The isolated alarm acceptance conversation could not be removed.",
                application.container.conversations.deleteConversation(candidate),
            )
        }
    }

    private fun request(time: String): String =
        "alarm_set 도구로 $time 에 '$ALARM_LABEL'이라는 1회 알람을 설정해줘."

    private fun expectedPreview(time: String): String =
        "$time · \"$ALARM_LABEL\"\n다음 해당 시각에 한 번 울립니다."

    private fun argument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)

    private companion object {
        const val LIVE_ARGUMENT = "liveAlarmSet"
        const val INTERACTIVE_APPROVAL_ARGUMENT = "interactiveAlarmSetApproval"
        const val TIME_ARGUMENT = "alarmSetTime"
        const val TEST_TITLE_PREFIX = "alarm_set 도구로"
        const val ALARM_LABEL = "Personal Edge 검증"
        const val MAX_CONVERSATION_SNAPSHOT = 500
        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val THERMAL_READY_TIMEOUT_MILLIS = 300_000L
        const val START_TIMEOUT_MILLIS = 15_000L
        const val APPROVAL_TIMEOUT_MILLIS = 120_000L
        const val TURN_TIMEOUT_MILLIS = 240_000L
        const val PERSISTENCE_TIMEOUT_MILLIS = 15_000L
        const val MIN_LEAD_MILLIS = 3 * 60 * 1_000L
        const val MATCH_TOLERANCE_MILLIS = 60_000L
        const val ALARM_POLL_ATTEMPTS = 20
        const val ALARM_POLL_INTERVAL_MILLIS = 250L
        const val POLL_INTERVAL_MILLIS = 250L
        val TIME_PATTERN = Regex("""([01]\d|2[0-3]):[0-5]\d""")
    }
}
