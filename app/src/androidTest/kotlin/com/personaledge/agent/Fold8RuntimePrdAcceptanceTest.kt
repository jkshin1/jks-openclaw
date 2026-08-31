package com.personaledge.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.ConversationEntity
import com.personaledge.core.data.MessageRole
import com.personaledge.core.diagnostics.Api31ResourceSnapshotProvider
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.llm.InferenceBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Explicitly opted-in physical receipts for the remaining bounded runtime PRD gates.
 *
 * Every prompt is fixed, content-free test data. Each test records the owner's existing
 * conversation IDs before it starts, creates one isolated thread, and removes only that thread in
 * `finally`. Credential values, notification fields, and existing transcript text are never read.
 */
@RunWith(AndroidJUnit4::class)
class Fold8RuntimePrdAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun realSummaryCarriesAnEarlySyntheticFactAfterCompaction() = runBlocking {
        assumeTrue(
            "Fold8 summary acceptance requires -e liveFoldSummary true.",
            argument(SUMMARY_ARGUMENT) == "true",
        )
        assumeTrue("Physical-device acceptance only.", !isEmulator())

        val application = application()
        val ownerConversationIds = application.container.conversations
            .recentConversations(MAX_CONVERSATION_SNAPSHOT)
            .map(ConversationEntity::id)
            .toSet()
        val conversationCountBefore = application.container.conversations.conversationCount()
        val messageCountBefore = application.container.conversations.messageCount()
        var testConversationId: String? = null

        try {
            testConversationId = application.container.conversations.createConversation(
                "$TEST_TITLE_PREFIX 요약 수용성",
            )
            application.container.conversations.appendMessage(
                requireNotNull(testConversationId),
                MessageRole.USER,
                "테스트 코드 $SYNTHETIC_FACT 를 기억하세요.",
            )
            application.container.conversations.appendMessage(
                requireNotNull(testConversationId),
                MessageRole.ASSISTANT,
                "READY",
            )
            for (ordinal in 2..4) {
                application.container.conversations.appendMessage(
                    requireNotNull(testConversationId),
                    MessageRole.USER,
                    "ACK$ordinal 만 답하세요.",
                )
                application.container.conversations.appendMessage(
                    requireNotNull(testConversationId),
                    MessageRole.ASSISTANT,
                    "ACK$ordinal",
                )
            }

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val viewModel = scenario.readyGpuViewModel()
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                        .switchConversation(requireNotNull(testConversationId))
                }
                assertTrue(
                    "The synthetic acceptance conversation was not restored.",
                    viewModel.awaitUntil(PERSISTENCE_TIMEOUT_MILLIS) {
                        chatHistory.value.activeConversationId == testConversationId &&
                            uiState.value.messages.size == 8
                    },
                )
                assertFalse(
                    "The acceptance turn reused an owner's conversation.",
                    testConversationId in ownerConversationIds,
                )
                assertTrue(
                    "The Fold8 thermal state did not become runnable before summary work.",
                    viewModel.awaitRunnableThermalState(),
                )
                scenario.sendAndAwaitTurn(viewModel, "도구를 사용하지 말고 ACK5 만 답하세요.")
                val firstSummaryStartedAt = System.currentTimeMillis()
                assertTrue(
                    "The first real model summary did not finish in time.",
                    application.container.conversations.awaitSummaryThrough(
                        requireNotNull(testConversationId),
                        throughOrdinal = 10L,
                    ),
                )
                val firstSummaryMillis = System.currentTimeMillis() - firstSummaryStartedAt
                val firstSummary = application.container.conversations
                    .findConversation(requireNotNull(testConversationId))
                    ?.summary
                    .orEmpty()
                assertTrue(
                    "The first real model summary omitted the synthetic fact: $firstSummary",
                    firstSummary.contains(SYNTHETIC_FACT),
                )

                assertTrue(
                    "The Fold8 thermal state did not become runnable after the first summary.",
                    viewModel.awaitRunnableThermalState(),
                )
                for (ordinal in 6..9) {
                    application.container.conversations.appendMessage(
                        requireNotNull(testConversationId),
                        MessageRole.USER,
                        "ACK$ordinal 만 답하세요.",
                    )
                    application.container.conversations.appendMessage(
                        requireNotNull(testConversationId),
                        MessageRole.ASSISTANT,
                        "ACK$ordinal",
                    )
                }
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                        .switchConversation(requireNotNull(testConversationId))
                }
                assertTrue(
                    "The second synthetic message window was not restored.",
                    viewModel.awaitUntil(PERSISTENCE_TIMEOUT_MILLIS) {
                        uiState.value.messages.size == 18
                    },
                )
                scenario.sendAndAwaitTurn(viewModel, "도구를 사용하지 말고 ACK10 만 답하세요.")
                val secondSummaryStartedAt = System.currentTimeMillis()
                assertTrue(
                    "The second real model summary did not finish in time.",
                    application.container.conversations.awaitSummaryThrough(
                        requireNotNull(testConversationId),
                        throughOrdinal = 20L,
                    ),
                )
                val secondSummaryMillis = System.currentTimeMillis() - secondSummaryStartedAt

                val compactedContext = application.container.conversations.loadContext(
                    conversationId = requireNotNull(testConversationId),
                    recentMessageLimit = 12,
                )
                assertNotNull(compactedContext)
                assertTrue(
                    "The synthetic fact was not retained by the real model summary.",
                    compactedContext!!.summary.orEmpty().contains(SYNTHETIC_FACT),
                )
                assertFalse(
                    "The oldest synthetic fact was still present verbatim after compaction.",
                    compactedContext.recentMessages.any { message ->
                        message.text.contains(SYNTHETIC_FACT)
                    },
                )

                assertTrue(
                    "The Fold8 thermal state did not become runnable before recall.",
                    viewModel.awaitRunnableThermalState(),
                )
                val messagesBeforeRecall = viewModel.uiState.value.messages.size
                scenario.sendAndAwaitTurn(
                    viewModel,
                    "처음 기억하라고 한 테스트 코드를 그대로 답하세요. 다른 말은 하지 마세요.",
                )
                val recall = viewModel.uiState.value.messages
                    .drop(messagesBeforeRecall)
                    .lastOrNull { entry -> entry.role == ChatRole.ASSISTANT }
                    ?.text
                    .orEmpty()
                assertTrue(
                    "The model did not recall the summarized test fact: $recall",
                    recall.contains(SYNTHETIC_FACT),
                )
                assertFalse(
                    "A summary acceptance turn unexpectedly requested a Tool.",
                    viewModel.uiState.value.messages.any { entry -> entry.role == ChatRole.TOOL },
                )

                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("privacy_summary_first_millis", firstSummaryMillis.toString())
                        putString("privacy_summary_second_millis", secondSummaryMillis.toString())
                        putString("privacy_summary_recall_passed", "true")
                        putString("privacy_summary_compacted_oldest_fact", "true")
                    },
                )
            }
        } finally {
            removeOnlyTestConversation(application, ownerConversationIds, testConversationId)
            assertEquals(conversationCountBefore, application.container.conversations.conversationCount())
            assertEquals(messageCountBefore, application.container.conversations.messageCount())
        }
    }

    @Test
    fun sustainedGpuTurnCompletesWhileActivityIsStopped() = runBlocking {
        assumeTrue(
            "Fold8 sustained acceptance requires -e liveFoldSustained true.",
            argument(SUSTAINED_ARGUMENT) == "true",
        )
        assumeTrue("Physical-device acceptance only.", !isEmulator())

        val application = application()
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
        val batteryBefore = batterySnapshot(application)
        val resourcesBefore = Api31ResourceSnapshotProvider(application).snapshot()
        var highestThermal = resourcesBefore.thermalStatus
        var testConversationId: String? = null
        var turnDurationMillis = 0L
        val observedReasoningLengths = mutableSetOf<Int>()
        var reasoningTargetMatched = false

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val viewModel = scenario.readyGpuViewModel()
                assertTrue(
                    "The Fold8 thermal state did not become runnable after GPU initialization.",
                    viewModel.awaitRunnableThermalState(),
                )
                scenario.onActivity { itView ->
                    ViewModelProvider(itView)[PersonalEdgeViewModel::class.java]
                        .startNewConversation()
                }
                assertTrue(
                    "The isolated sustained conversation did not become ready.",
                    viewModel.awaitUntil(PERSISTENCE_TIMEOUT_MILLIS) {
                        chatHistory.value.activeConversationId == null
                    },
                )

                val turnStartedAt = System.currentTimeMillis()
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[PersonalEdgeViewModel::class.java].apply {
                        updatePrompt(LONG_FIXED_PROMPT)
                        sendPrompt()
                    }
                }
                assertTrue(
                    "The sustained turn did not start.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                        uiState.value.activeTurnId != null
                    },
                )
                testConversationId = viewModel.awaitActiveConversationId()
                assertNotNull("The isolated sustained conversation was not persisted.", testConversationId)
                assertFalse(
                    "The sustained turn reused an owner's conversation.",
                    testConversationId in ownerConversationIds,
                )

                assertTrue(
                    "The sustained turn emitted no reasoning or answer token before backgrounding.",
                    viewModel.awaitUntil(FIRST_TOKEN_TIMEOUT_MILLIS) {
                        val state = uiState.value
                        state.activeReasoning?.takeIf { reasoning ->
                            reasoning.text.isNotEmpty()
                        }?.let { reasoning ->
                            observedReasoningLengths += reasoning.text.length
                            reasoningTargetMatched = reasoningTargetMatched ||
                                reasoning.assistantEntryId == state.messages.lastOrNull { entry ->
                                    entry.role == ChatRole.ASSISTANT
                                }?.id
                        }
                        state.activeReasoning?.text?.isNotBlank() == true ||
                            state.messages.any { entry ->
                                entry.role == ChatRole.ASSISTANT && entry.text.isNotBlank()
                            }
                    },
                )
                highestThermal = higherThermal(highestThermal, viewModel.uiState.value.thermalStatus)

                // CREATED means onStop has completed but the Activity/ViewModel are retained.
                scenario.moveToState(Lifecycle.State.CREATED)
                val backgroundStartedAt = System.currentTimeMillis()
                while (System.currentTimeMillis() - backgroundStartedAt < MIN_BACKGROUND_MILLIS) {
                    viewModel.uiState.value.activeReasoning
                        ?.text
                        ?.takeIf(String::isNotEmpty)
                        ?.let { reasoning -> observedReasoningLengths += reasoning.length }
                    highestThermal = higherThermal(
                        highestThermal,
                        viewModel.uiState.value.thermalStatus,
                    )
                    assertTrue(
                        "The fixed sustained decode ended before the background interval elapsed.",
                        viewModel.uiState.value.activeTurnId != null,
                    )
                    assertTrue(
                        "The platform reached a stop-level thermal state during the bounded run.",
                        ThermalTurnPolicy.canStart(viewModel.uiState.value.thermalStatus),
                    )
                    delay(POLL_INTERVAL_MILLIS)
                }

                assertTrue(
                    "The sustained turn did not terminate while the Activity was stopped.",
                    viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                        uiState.value.activeReasoning
                            ?.text
                            ?.takeIf(String::isNotEmpty)
                            ?.let { reasoning -> observedReasoningLengths += reasoning.length }
                        highestThermal = higherThermal(highestThermal, uiState.value.thermalStatus)
                        uiState.value.activeTurnId == null
                    },
                )
                turnDurationMillis = System.currentTimeMillis() - turnStartedAt
                scenario.moveToState(Lifecycle.State.RESUMED)

                assertEquals(ModelUiStatus.READY, viewModel.uiState.value.modelStatus)
                assertTrue(
                    "The real thought channel did not produce multiple streamed UI updates.",
                    observedReasoningLengths.size >= MIN_REASONING_UPDATE_COUNT,
                )
                assertTrue(
                    "The real thought channel targeted a stale assistant placeholder.",
                    reasoningTargetMatched,
                )
                assertNull(
                    "Ephemeral thought text remained after the turn completed.",
                    viewModel.uiState.value.activeReasoning,
                )
                assertTrue(
                    "The background-completed turn produced no assistant text.",
                    viewModel.uiState.value.messages.any { entry ->
                        entry.role == ChatRole.ASSISTANT && entry.text.isNotBlank()
                    },
                )
                assertFalse(
                    "The background-completed turn ended with a runtime status error.",
                    viewModel.uiState.value.messages.any { entry -> entry.role == ChatRole.STATUS },
                )
                assertFalse(
                    "The fixed no-tool prompt unexpectedly requested a Tool.",
                    viewModel.uiState.value.messages.any { entry -> entry.role == ChatRole.TOOL },
                )
            }
        } finally {
            removeOnlyTestConversation(application, ownerConversationIds, testConversationId)
        }

        val batteryAfter = batterySnapshot(application)
        val resourcesAfter = Api31ResourceSnapshotProvider(application).snapshot()
        highestThermal = higherThermal(highestThermal, resourcesAfter.thermalStatus)
        assertEquals(settingsBefore, application.container.settings.current())
        assertEquals(
            credentialsBefore,
            application.container.credentials.statuses()
                .associate { status -> status.slot to status.stored },
        )
        assertEquals(notificationCountBefore, application.container.notifications.count())
        assertEquals(conversationCountBefore, application.container.conversations.conversationCount())
        assertEquals(messageCountBefore, application.container.conversations.messageCount())
        assertTrue(
            "The Activity was not stopped for the required interval.",
            turnDurationMillis >= MIN_BACKGROUND_MILLIS,
        )

        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("privacy_sustained_turn_millis", turnDurationMillis.toString())
                putString("privacy_sustained_background_millis", MIN_BACKGROUND_MILLIS.toString())
                putString("privacy_sustained_highest_thermal", highestThermal.name.lowercase())
                putString(
                    "privacy_sustained_reasoning_updates",
                    observedReasoningLengths.size.toString(),
                )
                putString("privacy_sustained_reasoning_targeted", reasoningTargetMatched.toString())
                putString("privacy_sustained_reasoning_cleared", "true")
                putString("privacy_sustained_pss_before", resourcesBefore.pssBytes.toString())
                putString("privacy_sustained_pss_after", resourcesAfter.pssBytes.toString())
                putString("privacy_sustained_heap_before", resourcesBefore.javaHeapBytes.toString())
                putString("privacy_sustained_heap_after", resourcesAfter.javaHeapBytes.toString())
                putString("privacy_battery_capacity_before", batteryBefore.capacityPercent.toString())
                putString("privacy_battery_capacity_after", batteryAfter.capacityPercent.toString())
                putString(
                    "privacy_battery_charge_before",
                    batteryBefore.chargeCounterMicroAh.toString(),
                )
                putString(
                    "privacy_battery_charge_after",
                    batteryAfter.chargeCounterMicroAh.toString(),
                )
                putString(
                    "privacy_battery_energy_before",
                    batteryBefore.energyCounterNanoWh.toString(),
                )
                putString(
                    "privacy_battery_energy_after",
                    batteryAfter.energyCounterNanoWh.toString(),
                )
                putString(
                    "privacy_battery_temp_before_tenths_c",
                    batteryBefore.temperatureTenthsC.toString(),
                )
                putString(
                    "privacy_battery_temp_after_tenths_c",
                    batteryAfter.temperatureTenthsC.toString(),
                )
                putString("privacy_battery_plugged_before", batteryBefore.plugged.toString())
                putString("privacy_battery_plugged_after", batteryAfter.plugged.toString())
            },
        )
    }

    @Test
    fun activeGpuTurnSurvivesAFullPhysicalFoldAndRestore() = runBlocking {
        assumeTrue(
            "Interactive active-fold acceptance requires -e liveFoldActiveTurn true.",
            argument(ACTIVE_FOLD_ARGUMENT) == "true",
        )
        assumeTrue("Physical-device acceptance only.", !isEmulator())

        val application = application()
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
                val viewModel = scenario.readyGpuViewModel()
                assertTrue(
                    "The Fold8 thermal state did not become runnable before active-fold work.",
                    viewModel.awaitRunnableThermalState(),
                )

                val initialDeviceState = requireNotNull(currentPhysicalDeviceState()) {
                    "The Fold8 physical device state could not be read."
                }
                val alternateDeviceState = when (initialDeviceState) {
                    FOLD8_CLOSED_STATE -> FOLD8_OPENED_STATE
                    FOLD8_OPENED_STATE -> FOLD8_CLOSED_STATE
                    else -> null
                }
                assumeTrue(
                    "Start the physical-fold receipt fully opened or fully closed; " +
                        "current state=$initialDeviceState.",
                    alternateDeviceState != null,
                )

                var initialDisplay = DisplaySignature.UNKNOWN
                scenario.onActivity { activity ->
                    initialDisplay = activity.displaySignature()
                    ViewModelProvider(activity)[PersonalEdgeViewModel::class.java].apply {
                        startNewConversation()
                        updatePrompt(LONG_FIXED_PROMPT)
                    }
                }

                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("privacy_active_fold_ready", "true")
                        putString("privacy_active_fold_initial_state", initialDeviceState.toString())
                        putString("privacy_active_fold_initial_display", initialDisplay.receipt())
                    },
                )
                assertTrue(
                    "Physical fold motion did not begin after the test was armed.",
                    awaitPhysicalStateDifferentFrom(initialDeviceState),
                )
                instrumentation.runOnMainSync { viewModel.sendPrompt() }
                assertTrue(
                    "The active-fold turn did not start when physical fold motion began.",
                    viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                        uiState.value.activeTurnId != null
                    },
                )
                val activeTurnId = requireNotNull(viewModel.uiState.value.activeTurnId)
                testConversationId = viewModel.awaitActiveConversationId()
                assertNotNull("The isolated active-fold conversation was not persisted.", testConversationId)
                assertFalse(
                    "The active-fold turn reused an owner's conversation.",
                    testConversationId in ownerConversationIds,
                )
                assertTrue(
                    "A full physical fold transition was not observed during the active turn.",
                    viewModel.awaitPhysicalStateDuringActiveTurn(
                        expectedState = requireNotNull(alternateDeviceState),
                    ),
                )
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("privacy_active_fold_alternate_observed", "true")
                        putString(
                            "privacy_active_fold_alternate_state",
                            alternateDeviceState.toString(),
                        )
                    },
                )

                assertTrue(
                    "The Fold8 was not restored to its initial physical state during the active turn.",
                    viewModel.awaitPhysicalStateDuringActiveTurn(expectedState = initialDeviceState),
                )
                assertTrue(
                    "The Activity window did not settle back to its initial display bounds " +
                        "during the active turn.",
                    scenario.awaitDisplaySignatureDuringActiveTurn(
                        viewModel = viewModel,
                        expected = initialDisplay,
                    ),
                )

                assertTrue(
                    "The active-fold turn emitted no token across the physical fold cycle.",
                    viewModel.awaitUntil(FIRST_TOKEN_TIMEOUT_MILLIS) {
                        uiState.value.messages.any { entry ->
                            entry.role == ChatRole.ASSISTANT && entry.text.isNotBlank()
                        }
                    },
                )

                lateinit var restoredViewModel: PersonalEdgeViewModel
                var restoredDisplay = DisplaySignature.UNKNOWN
                scenario.onActivity { activity ->
                    restoredViewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    restoredDisplay = activity.displaySignature()
                }
                assertEquals(
                    "The active turn was not retained across the physical fold cycle.",
                    activeTurnId,
                    restoredViewModel.uiState.value.activeTurnId,
                )
                assertTrue(
                    "The active-fold turn did not terminate after restoration.",
                    restoredViewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                        uiState.value.activeTurnId == null
                    },
                )
                assertEquals(ModelUiStatus.READY, restoredViewModel.uiState.value.modelStatus)
                assertTrue(
                    "The fold-surviving turn produced no assistant text.",
                    restoredViewModel.uiState.value.messages.any { entry ->
                        entry.role == ChatRole.ASSISTANT && entry.text.isNotBlank()
                    },
                )
                assertFalse(
                    "The fold-surviving turn ended with a runtime status error.",
                    restoredViewModel.uiState.value.messages.any { entry -> entry.role == ChatRole.STATUS },
                )
                assertFalse(
                    "The fixed no-tool prompt unexpectedly requested a Tool.",
                    restoredViewModel.uiState.value.messages.any { entry -> entry.role == ChatRole.TOOL },
                )

                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("privacy_active_fold_restored", "true")
                        putString("privacy_active_fold_restored_display", restoredDisplay.receipt())
                        putString("privacy_active_fold_turn_completed", "true")
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

    private suspend fun ActivityScenario<MainActivity>.readyGpuViewModel(): PersonalEdgeViewModel {
        lateinit var viewModel: PersonalEdgeViewModel
        onActivity { activity ->
            viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
        }
        assumeTrue(
            "The verified model is not installed on this device.",
            viewModel.awaitUntil(MODEL_TIMEOUT_MILLIS) {
                uiState.value.modelStatus in setOf(ModelUiStatus.VERIFIED, ModelUiStatus.READY)
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

    private suspend fun ActivityScenario<MainActivity>.sendAndAwaitTurn(
        viewModel: PersonalEdgeViewModel,
        prompt: String,
    ) {
        val messageCountBefore = viewModel.uiState.value.messages.size
        onActivity { activity ->
            ViewModelProvider(activity)[PersonalEdgeViewModel::class.java].apply {
                updatePrompt(prompt)
                sendPrompt()
            }
        }
        assertTrue(
            "The real-model turn did not start.",
            viewModel.awaitUntil(START_TIMEOUT_MILLIS) { uiState.value.activeTurnId != null },
        )
        assertTrue(
            "The real-model turn did not terminate in time.",
            viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) { uiState.value.activeTurnId == null },
        )
        val newMessages = viewModel.uiState.value.messages.drop(messageCountBefore)
        assertTrue(
            "The real-model turn produced no assistant answer: $newMessages",
            newMessages.any { entry -> entry.role == ChatRole.ASSISTANT && entry.text.isNotBlank() },
        )
        assertFalse(
            "The real-model turn ended with a status error: $newMessages",
            newMessages.any { entry -> entry.role == ChatRole.STATUS },
        )
    }

    private suspend fun PersonalEdgeViewModel.awaitActiveConversationId(): String? {
        awaitUntil(PERSISTENCE_TIMEOUT_MILLIS) {
            chatHistory.value.activeConversationId != null
        }
        return chatHistory.value.activeConversationId
    }

    private suspend fun PersonalEdgeViewModel.awaitRunnableThermalState(): Boolean =
        awaitUntil(THERMAL_READY_TIMEOUT_MILLIS) {
            ThermalTurnPolicy.canStart(uiState.value.thermalStatus)
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

    private suspend fun PersonalEdgeViewModel.awaitPhysicalStateDuringActiveTurn(
        expectedState: Int,
    ): Boolean {
        val deadline = System.currentTimeMillis() + ACTIVE_FOLD_TRANSITION_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (uiState.value.activeTurnId == null) return false
            if (currentPhysicalDeviceState() == expectedState) return true
            delay(ACTIVE_FOLD_POLL_INTERVAL_MILLIS)
        }
        return uiState.value.activeTurnId != null &&
            currentPhysicalDeviceState() == expectedState
    }

    private suspend fun awaitPhysicalStateDifferentFrom(initialState: Int): Boolean {
        val deadline = System.currentTimeMillis() + ACTIVE_FOLD_ARM_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val current = currentPhysicalDeviceState()
            if (current != null && current != initialState) return true
            delay(ACTIVE_FOLD_POLL_INTERVAL_MILLIS)
        }
        val current = currentPhysicalDeviceState()
        return current != null && current != initialState
    }

    private suspend fun ActivityScenario<MainActivity>.awaitDisplaySignatureDuringActiveTurn(
        viewModel: PersonalEdgeViewModel,
        expected: DisplaySignature,
    ): Boolean {
        val deadline = System.currentTimeMillis() + ACTIVE_FOLD_DISPLAY_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (viewModel.uiState.value.activeTurnId == null) return false
            var current = DisplaySignature.UNKNOWN
            runCatching {
                onActivity { activity -> current = activity.displaySignature() }
            }
            if (current == expected) return true
            delay(ACTIVE_FOLD_POLL_INTERVAL_MILLIS)
        }
        return false
    }

    private suspend fun com.personaledge.core.data.ConversationRepository.awaitSummaryThrough(
        conversationId: String,
        throughOrdinal: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + SUMMARY_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val conversation = findConversation(conversationId)
            if (
                conversation != null &&
                conversation.summarizedThroughMessageOrdinal >= throughOrdinal &&
                !conversation.summary.isNullOrBlank()
            ) {
                return true
            }
            delay(POLL_INTERVAL_MILLIS)
        }
        return findConversation(conversationId)?.let { conversation ->
            conversation.summarizedThroughMessageOrdinal >= throughOrdinal &&
                !conversation.summary.isNullOrBlank()
        } == true
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
                "The isolated acceptance conversation could not be removed.",
                application.container.conversations.deleteConversation(candidate),
            )
        }
    }

    private fun batterySnapshot(context: Context): BatterySnapshot {
        val batteryManager = requireNotNull(context.getSystemService(BatteryManager::class.java))
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return BatterySnapshot(
            capacityPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            chargeCounterMicroAh = batteryManager.getLongProperty(
                BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,
            ),
            energyCounterNanoWh = batteryManager.getLongProperty(
                BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER,
            ),
            temperatureTenthsC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1,
            plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
        )
    }

    private fun currentPhysicalDeviceState(): Int? {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "cmd device_state print-state",
        )
        return descriptor.use { parcelFileDescriptor ->
            FileInputStream(parcelFileDescriptor.fileDescriptor)
                .bufferedReader()
                .use { reader -> reader.readText().trim().toIntOrNull() }
        }
    }

    private fun MainActivity.displaySignature(): DisplaySignature {
        val bounds = windowManager.currentWindowMetrics.bounds
        return DisplaySignature(
            displayId = display?.displayId ?: -1,
            widthPixels = bounds.width(),
            heightPixels = bounds.height(),
            orientation = resources.configuration.orientation,
        )
    }

    private fun higherThermal(
        first: DiagnosticThermalStatus,
        second: DiagnosticThermalStatus,
    ): DiagnosticThermalStatus =
        if (thermalSeverity(second) > thermalSeverity(first)) second else first

    private fun thermalSeverity(status: DiagnosticThermalStatus): Int = when (status) {
        DiagnosticThermalStatus.NONE -> 0
        DiagnosticThermalStatus.LIGHT -> 1
        DiagnosticThermalStatus.MODERATE -> 2
        DiagnosticThermalStatus.SEVERE -> 3
        DiagnosticThermalStatus.CRITICAL -> 4
        DiagnosticThermalStatus.EMERGENCY -> 5
        DiagnosticThermalStatus.SHUTDOWN -> 6
        DiagnosticThermalStatus.UNKNOWN -> 7
    }

    private fun argument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)

    private fun application(): PersonalEdgeApplication =
        instrumentation.targetContext.applicationContext as PersonalEdgeApplication

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)

    private data class BatterySnapshot(
        val capacityPercent: Int,
        val chargeCounterMicroAh: Long,
        val energyCounterNanoWh: Long,
        val temperatureTenthsC: Int,
        val plugged: Boolean,
    )

    private data class DisplaySignature(
        val displayId: Int,
        val widthPixels: Int,
        val heightPixels: Int,
        val orientation: Int,
    ) {
        fun receipt(): String = "$displayId:${widthPixels}x$heightPixels:$orientation"

        companion object {
            val UNKNOWN = DisplaySignature(-1, -1, -1, -1)
        }
    }

    private companion object {
        const val SUMMARY_ARGUMENT = "liveFoldSummary"
        const val SUSTAINED_ARGUMENT = "liveFoldSustained"
        const val ACTIVE_FOLD_ARGUMENT = "liveFoldActiveTurn"
        const val TEST_TITLE_PREFIX = "도구를 사용하지"
        const val SYNTHETIC_FACT = "ORCHID-7421"
        const val LONG_FIXED_PROMPT =
            "도구를 사용하지 마세요. SUSTAIN 단어를 공백으로 구분해 정확히 320번 출력하고 다른 말은 하지 마세요."
        const val MAX_CONVERSATION_SNAPSHOT = 500
        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val START_TIMEOUT_MILLIS = 15_000L
        const val FIRST_TOKEN_TIMEOUT_MILLIS = 30_000L
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val SUMMARY_TIMEOUT_MILLIS = 120_000L
        const val THERMAL_READY_TIMEOUT_MILLIS = 300_000L
        const val PERSISTENCE_TIMEOUT_MILLIS = 15_000L
        const val MIN_BACKGROUND_MILLIS = 8_000L
        const val MIN_REASONING_UPDATE_COUNT = 2
        const val ACTIVE_FOLD_ARM_TIMEOUT_MILLIS = 300_000L
        const val ACTIVE_FOLD_TRANSITION_TIMEOUT_MILLIS = 60_000L
        const val ACTIVE_FOLD_DISPLAY_TIMEOUT_MILLIS = 30_000L
        const val ACTIVE_FOLD_POLL_INTERVAL_MILLIS = 500L
        const val POLL_INTERVAL_MILLIS = 250L
        const val FOLD8_CLOSED_STATE = 0
        const val FOLD8_OPENED_STATE = 3
    }
}
