package com.personaledge.agent

import android.content.ContentValues
import android.os.Bundle
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.ReminderCreateResult
import com.personaledge.core.data.ReminderCreator
import com.personaledge.core.data.ReminderDraft
import com.personaledge.core.data.ReminderPrecision
import com.personaledge.core.data.ReminderSourceType
import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.tools.ReminderCancelTool
import com.personaledge.core.tools.ReminderCreateTool
import com.personaledge.core.tools.ReminderUpdateTool
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-Gemma selection receipt for the three state-changing reminder Tools.
 *
 * A fixed synthetic repository row is used only for update/cancel preparation. Every confirmation
 * is denied before ledger claim or execution, then the exact synthetic row and exact isolated
 * conversations are removed. No owner reminder, conversation text, credential, or provider is
 * queried or printed, and no consent setting is changed.
 */
@RunWith(AndroidJUnit4::class)
class Fold8ReminderToolSelectionAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun realModelSelectsCreateUpdateAndCancelBeforeExplicitDenial() = runBlocking {
        assumeTrue(
            "Reminder Tool selection requires -e liveReminderToolSelection true.",
            InstrumentationRegistry.getArguments().getString(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("This acceptance is restricted to the owner's Fold8.", isTargetFold8())

        val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication
        val container = application.container
        val zone = ZoneId.of("Asia/Seoul")
        val today = java.time.LocalDate.now(zone)
        val createTrigger = today.plusDays(7).atTime(9, 0)
        val updateTrigger = today.plusDays(8).atTime(10, 0)
        val created = container.reminders.create(
            ReminderDraft(
                title = SYNTHETIC_EXISTING_TITLE,
                triggerAtEpochMillis = today.plusDays(14).atTime(10, 0)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli(),
                zoneId = zone.id,
                precision = ReminderPrecision.FLEXIBLE,
                sourceType = ReminderSourceType.DIRECT,
                createdBy = ReminderCreator.USER,
                confirmationDigest = SYNTHETIC_CONFIRMATION_DIGEST,
            ),
        )
        assertTrue(created is ReminderCreateResult.Created)
        val synthetic = (created as ReminderCreateResult.Created).reminder
        val testConversationIds = linkedSetOf<String>()

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val viewModel = scenario.readyGpuViewModel()
                try {
                    val cases = arrayOf(
                        SelectionCase(
                            expectedTool = ReminderCreateTool.NAME,
                            prompt = "합성 보고서 확인 앱 리마인더를 만들어 주세요. 시각은 " +
                                "${createTrigger}, 시간대는 ${zone.id}입니다. 날짜와 시각을 " +
                                "바꾸지 마세요.",
                        ),
                        SelectionCase(
                            expectedTool = ReminderUpdateTool.NAME,
                            prompt = "목록에서 이미 확인한 앱 리마인더 ID ${synthetic.id}, 현재 버전 " +
                                "${synthetic.scheduleVersion}을 재조회 없이 수정해 주세요. 새 제목은 " +
                                "합성 보고서 재확인, 새 시각은 " +
                                "${updateTrigger}, 시간대는 ${zone.id}입니다. 날짜와 시각을 " +
                                "바꾸지 마세요.",
                        ),
                        SelectionCase(
                            expectedTool = ReminderCancelTool.NAME,
                            prompt = "목록에서 이미 확인한 앱 리마인더 ID ${synthetic.id}, 현재 버전 " +
                                "${synthetic.scheduleVersion}을 재조회 없이 취소해 주세요.",
                        ),
                    )

                    for ((index, selectionCase) in cases.withIndex()) {
                        assertTrue(
                            "The Fold8 thermal state did not become runnable before selection.",
                            viewModel.awaitRunnableThermalState(),
                        )
                        scenario.onActivity { activity ->
                            ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                                .startNewConversation()
                        }
                        assertTrue(
                            "The isolated Tool-selection conversation was not reset.",
                            viewModel.awaitUntil(PERSISTENCE_TIMEOUT_MILLIS) {
                                chatHistory.value.activeConversationId == null && uiState.value.messages.isEmpty()
                            },
                        )
                        scenario.onActivity { activity ->
                            ViewModelProvider(activity)[PersonalEdgeViewModel::class.java].apply {
                                updatePrompt(selectionCase.prompt)
                                sendPrompt()
                            }
                        }
                        assertTrue(
                            "The real-model Tool-selection turn did not start.",
                            viewModel.awaitUntil(START_TIMEOUT_MILLIS) {
                                uiState.value.activeTurnId != null
                            },
                        )
                        val testConversationId = viewModel.awaitActiveConversationId()
                        assertNotNull(
                            "The isolated Tool-selection conversation was not persisted.",
                            testConversationId,
                        )
                        testConversationIds += requireNotNull(testConversationId)

                        val pending = viewModel.awaitConfirmation()
                        val failureCode = if (pending == null) {
                            scenario.exportLastTurnFailureCode()
                        } else {
                            null
                        }
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString(
                                    "reminder_tool_selection_${index + 1}_terminal",
                                    terminalKind(viewModel, pending),
                                )
                                failureCode?.let { code ->
                                    putString(
                                        "reminder_tool_selection_${index + 1}_error_code",
                                        code,
                                    )
                                }
                            },
                        )
                        assertNotNull("The model produced no confirmable reminder Tool.", pending)
                        assertEquals(selectionCase.expectedTool, pending?.toolName)
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString("reminder_tool_selection_${index + 1}", selectionCase.expectedTool)
                            },
                        )
                        scenario.onActivity { activity ->
                            ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                                .resolveConfirmation(requireNotNull(pending).actionId, false)
                        }
                        assertTrue(
                            "The denied reminder Tool turn did not terminate.",
                            viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) {
                                confirmationCoordinator.pending.value == null &&
                                    uiState.value.activeTurnId == null
                            },
                        )
                        val unchanged = container.reminders.find(synthetic.id)
                        assertNotNull("A denied Tool removed the synthetic row.", unchanged)
                        assertEquals(synthetic.scheduleVersion, unchanged?.scheduleVersion)
                        assertEquals(SYNTHETIC_EXISTING_TITLE, unchanged?.title)
                    }
                } finally {
                    scenario.removeOnlyTestConversations(viewModel, testConversationIds)
                }
            }
        } finally {
            container.database.openHelper.writableDatabase.execSQL(
                "DELETE FROM reminders WHERE id = ? AND confirmation_digest = ?",
                arrayOf<Any>(synthetic.id, SYNTHETIC_CONFIRMATION_DIGEST),
            )
            assertNull(container.reminders.find(synthetic.id))
        }
    }

    private fun ActivityScenario<MainActivity>.readyGpuViewModel(): PersonalEdgeViewModel {
        lateinit var viewModel: PersonalEdgeViewModel
        onActivity { activity ->
            viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
        }
        assumeTrue(
            "The verified model is not installed.",
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
                "The GPU runtime did not become ready.",
                viewModel.awaitUntil(RUNTIME_TIMEOUT_MILLIS) {
                    uiState.value.modelStatus == ModelUiStatus.READY
                },
            )
        }
        assertEquals(InferenceBackend.GPU, viewModel.uiState.value.activeBackend)
        return viewModel
    }

    private fun PersonalEdgeViewModel.awaitActiveConversationId(): String? {
        awaitUntil(PERSISTENCE_TIMEOUT_MILLIS) {
            chatHistory.value.activeConversationId != null
        }
        return chatHistory.value.activeConversationId
    }

    private fun PersonalEdgeViewModel.awaitConfirmation(): PendingConfirmation? {
        awaitUntil(TURN_TIMEOUT_MILLIS) {
            confirmationCoordinator.pending.value != null || uiState.value.activeTurnId == null
        }
        return confirmationCoordinator.pending.value
    }

    private fun PersonalEdgeViewModel.awaitRunnableThermalState(): Boolean =
        awaitUntil(THERMAL_READY_TIMEOUT_MILLIS) {
            ThermalTurnPolicy.canStart(uiState.value.thermalStatus)
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
                "The isolated reminder Tool turn did not stop during cleanup.",
                viewModel.awaitUntil(TURN_TIMEOUT_MILLIS) { uiState.value.activeTurnId == null },
            )
        }
        if (testConversationIds.isEmpty()) return

        onActivity { activity ->
            ViewModelProvider(activity)[PersonalEdgeViewModel::class.java].openHistory()
        }
        assertTrue(
            "The isolated reminder Tool conversations were not visible for exact cleanup.",
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
                    "An isolated reminder Tool conversation could not be selected for cleanup.",
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
                "An isolated reminder Tool conversation could not be removed.",
                viewModel.awaitUntil(PERSISTENCE_TIMEOUT_MILLIS) {
                    chatHistory.value.activeConversationId == null
                },
            )
        }
        onActivity { activity ->
            ViewModelProvider(activity)[PersonalEdgeViewModel::class.java].closeHistory()
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

    private fun isTargetFold8(): Boolean =
        android.os.Build.MODEL == TARGET_FOLD8_MODEL &&
            !android.os.Build.FINGERPRINT.startsWith(GENERIC_PREFIX)

    private fun ActivityScenario<MainActivity>.exportLastTurnFailureCode(): String {
        val resolver = instrumentation.targetContext.contentResolver
        val destination = requireNotNull(
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        "personal-edge-reminder-tool-diagnostics-${System.currentTimeMillis()}.jsonl",
                    )
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/jsonl")
                },
            ),
        )
        try {
            onActivity { activity ->
                ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                    .exportDiagnostics(destination)
            }
            val deadline = System.currentTimeMillis() + DIAGNOSTIC_EXPORT_TIMEOUT_MILLIS
            var lastFailure: String? = null
            while (System.currentTimeMillis() < deadline && lastFailure == null) {
                try {
                    BufferedReader(
                        InputStreamReader(
                            requireNotNull(resolver.openInputStream(destination)),
                            StandardCharsets.UTF_8,
                        ),
                    ).use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            if (line.contains(TURN_FAILED_MARKER)) lastFailure = line
                            line = reader.readLine()
                        }
                    }
                } catch (_: Exception) {
                    // The exporter may still have the MediaStore item open; retry until deadline.
                }
                if (lastFailure == null) Thread.sleep(POLL_INTERVAL_MILLIS)
            }
            assertNotNull("The content-free export contained no turn failure.", lastFailure)
            val errorStart = requireNotNull(lastFailure).indexOf(ERROR_CODE_MARKER)
            assertTrue("The content-free turn failure had no closed error code.", errorStart >= 0)
            val valueStart = errorStart + ERROR_CODE_MARKER.length
            val valueEnd = requireNotNull(lastFailure).indexOf('"', valueStart)
            assertTrue("The content-free error code was malformed.", valueEnd > valueStart)
            return requireNotNull(lastFailure).substring(valueStart, valueEnd)
        } finally {
            assertEquals(
                "The exact synthetic diagnostic export was not removed.",
                1,
                resolver.delete(destination, null, null),
            )
        }
    }

    private fun terminalKind(
        viewModel: PersonalEdgeViewModel,
        pending: PendingConfirmation?,
    ): String {
        if (pending != null) return "confirmation"
        var assistantSeen = false
        var statusSeen = false
        var toolSeen = false
        for (entry in viewModel.uiState.value.messages) {
            when (entry.role) {
                ChatRole.ASSISTANT -> assistantSeen = true
                ChatRole.STATUS -> statusSeen = true
                ChatRole.TOOL -> toolSeen = true
                ChatRole.USER -> Unit
            }
        }
        return when {
            toolSeen -> "tool_without_confirmation"
            statusSeen -> "closed_failure"
            assistantSeen -> "assistant_without_tool"
            else -> "empty"
        }
    }

    private data class SelectionCase(
        val expectedTool: String,
        val prompt: String,
    )

    private companion object {
        const val LIVE_ARGUMENT = "liveReminderToolSelection"
        const val TARGET_FOLD8_MODEL = "SM-F971N"
        const val GENERIC_PREFIX = "generic"
        const val SYNTHETIC_EXISTING_TITLE = "Personal Edge synthetic reminder Tool selection"
        const val SYNTHETIC_CONFIRMATION_DIGEST =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val START_TIMEOUT_MILLIS = 15_000L
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val THERMAL_READY_TIMEOUT_MILLIS = 300_000L
        const val PERSISTENCE_TIMEOUT_MILLIS = 15_000L
        const val DIAGNOSTIC_EXPORT_TIMEOUT_MILLIS = 15_000L
        const val POLL_INTERVAL_MILLIS = 250L
        const val TURN_FAILED_MARKER = "\"event\":\"turn_failed\""
        const val ERROR_CODE_MARKER = "\"error_code\":\""
    }
}
