package com.personaledge.agent

import android.Manifest
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.llm.InferenceBackend
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reported failure: a pasted mail plus "일정등록해줘" registered nothing and the model kept
 * asking the same question forever.
 *
 * Two separate defects produced that loop. The mail is multi-line, and a line break used to clear
 * the whole Tool schema, so the model could only talk. The request also names two writable domains
 * at once, which exposed no schema while still demanding that a Tool run — a turn nothing could
 * satisfy. This drives the real ViewModel because `adb input text` cannot type Hangul.
 *
 * The confirmation is **denied**: this proves the app asked, resumed the original request, and
 * selected the dated reminder Tool, without putting a reminder on the owner's device.
 *
 * It runs against the minified release target, so it stays on array literals and blocking polls
 * rather than the stdlib collection and coroutine facades that R8 may drop from that artifact.
 */
@RunWith(AndroidJUnit4::class)
class PastedMailScheduleAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val requestedBackend: InferenceBackend by lazy {
        when (InstrumentationRegistry.getArguments().getString(BACKEND_ARGUMENT)?.lowercase()) {
            null, "", "gpu" -> InferenceBackend.GPU
            "cpu" -> InferenceBackend.CPU
            else -> error("$BACKEND_ARGUMENT must be either cpu or gpu.")
        }
    }

    @Before
    fun grantCalendarAccess() {
        val permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        for (permission in permissions) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                permission,
            )
        }
    }

    @Test
    fun aPastedMailAsksWhereToRegisterAndThenSelectsTheDatedReminderTool() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var viewModel: PersonalEdgeViewModel
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
            }

            assumeTrue("The verified model is not installed on this device.", viewModel.awaitModel())
            if (viewModel.uiState.value.modelStatus != ModelUiStatus.READY) {
                scenario.onActivity { viewModel.initializeRuntime(requestedBackend) }
                assumeTrue(
                    "The runtime did not become ready in time.",
                    viewModel.awaitStatus(ModelUiStatus.READY, RUNTIME_TIMEOUT_MILLIS),
                )
            }
            assertEquals(
                "The test must run on the explicitly requested inference backend.",
                requestedBackend,
                viewModel.uiState.value.activeBackend,
            )

            // The conversation mutation gate refuses a turn while a new conversation is still
            // being created, so wait for the empty transcript before typing into it.
            scenario.onActivity { viewModel.startNewConversation() }
            assertTrue(
                "The new conversation never became empty.",
                awaitUntil(SETUP_TIMEOUT_MILLIS) { viewModel.lastAssistantText().isEmpty() },
            )
            try {
                scenario.onActivity {
                    viewModel.updatePrompt(PASTED_MAIL)
                    viewModel.sendPrompt()
                }
                assertTrue("The pasted mail was never accepted as a turn.", viewModel.awaitSent(PASTED_MAIL))
                assertTrue("The pasted mail turn never finished.", viewModel.awaitTurnIdle())

                // The app asks; the model is never handed an ambiguous write schema to guess from.
                val question = viewModel.lastAssistantText()
                assertTrue(question, question.contains("캘린더 일정"))
                assertTrue(question, question.contains("리마인더 알림"))
                assertNull(
                    "An ambiguous write must not reach a confirmation.",
                    viewModel.confirmationCoordinator.pending.value,
                )

                scenario.onActivity {
                    viewModel.updatePrompt(OWNER_CHOICE)
                    viewModel.sendPrompt()
                }
                assertTrue("The answer was never accepted as a turn.", viewModel.awaitSent(OWNER_CHOICE))

                // The local model sometimes answers a question with a question instead of calling the
                // Tool it now has. That is allowed; what is not allowed is the reported loop, where a
                // bare confirmation carries no scope and the same question returns forever. One "응"
                // must reach the write Tool the resumed request already earned.
                var pending = viewModel.awaitConfirmationOrIdle()
                if (pending == null) {
                    val asked = viewModel.lastAssistantText()
                    assertTrue(asked, asked.contains("리마인더") || asked.contains("알림"))
                    scenario.onActivity {
                        viewModel.updatePrompt(BARE_CONFIRMATION)
                        viewModel.sendPrompt()
                    }
                    assertTrue(
                        "The confirmation reply was never accepted as a turn.",
                        viewModel.awaitSent(BARE_CONFIRMATION),
                    )
                    pending = viewModel.awaitConfirmationOrIdle()
                }
                assertNotNull(
                    "No confirmable Tool call appeared, even after a bare confirmation: " +
                        viewModel.lastAssistantText(),
                    pending,
                )
                scenario.onActivity { viewModel.resolveConfirmation(pending!!.actionId, false) }

                // The deadline is 9월 12일 and the mail asks for five days earlier, so the resumed
                // request — not the one-word reply — is what the turn actually carried.
                assertEquals("reminder_create", pending!!.toolName)
                val summary = pending.preview.summary
                assertTrue(summary, summary.contains(expectedTriggerDate()))
            } finally {
                // The fixture mail is long and would otherwise pile up in the owner's history.
                viewModel.chatHistory.value.activeConversationId?.let { id ->
                    scenario.onActivity { viewModel.deleteConversation(id) }
                    awaitUntil(SETUP_TIMEOUT_MILLIS) {
                        viewModel.chatHistory.value.conversations.none { entry -> entry.id == id }
                    }
                }
            }
        }
    }

    /** The mail names 9월 12일 with no year, so the deadline is the next one still ahead. */
    private fun expectedTriggerDate(): String {
        val today = LocalDate.now(ZoneId.systemDefault())
        val thisYear = LocalDate.of(today.year, 9, 12)
        val deadline = if (thisYear.isBefore(today)) thisYear.plusYears(1) else thisYear
        return deadline.minusDays(5).toString()
    }

    private fun PersonalEdgeViewModel.lastAssistantText(): String {
        var text = ""
        for (entry in uiState.value.messages) {
            if (entry.role == ChatRole.ASSISTANT && entry.text.isNotBlank()) text = entry.text
        }
        return text
    }

    /** The transcript, not the transient turn id, is the reliable proof that a prompt landed. */
    private fun PersonalEdgeViewModel.awaitSent(prompt: String): Boolean =
        awaitUntil(SETUP_TIMEOUT_MILLIS) {
            var sent = false
            for (entry in uiState.value.messages) {
                if (entry.role == ChatRole.USER && entry.text == prompt) sent = true
            }
            sent
        }

    private fun PersonalEdgeViewModel.awaitTurnIdle(): Boolean =
        awaitUntil(TURN_TIMEOUT_MILLIS) { uiState.value.activeTurnId == null }

    private fun PersonalEdgeViewModel.awaitModel(): Boolean = awaitUntil(MODEL_TIMEOUT_MILLIS) {
        val status = uiState.value.modelStatus
        status == ModelUiStatus.VERIFIED || status == ModelUiStatus.READY
    }

    private fun PersonalEdgeViewModel.awaitStatus(
        status: ModelUiStatus,
        timeoutMillis: Long,
    ): Boolean = awaitUntil(timeoutMillis) { uiState.value.modelStatus == status }

    /**
     * A pending confirmation keeps its turn active, so waiting for either outcome distinguishes
     * "the model is still deciding" from "the turn ended with prose" without burning the deadline.
     */
    private fun PersonalEdgeViewModel.awaitConfirmationOrIdle(): PendingConfirmation? {
        awaitUntil(TURN_TIMEOUT_MILLIS) {
            confirmationCoordinator.pending.value != null || uiState.value.activeTurnId == null
        }
        return confirmationCoordinator.pending.value
    }

    private fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return condition()
    }

    private companion object {
        const val BACKEND_ARGUMENT = "inferenceBackend"
        val PASTED_MAIL = """
            안녕하세요, 반도체데이터사이언스학과 행정실입니다.

            아래와 같이 재직 현황 확인을 위한 서류를 요청드리오니, 기한 내 제출하여 주시기 바랍니다.

            제출서류: 4대 보험 가입증명서, 재직증명서 각 1부
            제출기한: 9월 12일(토)
            제출방법: 학과사무실(공학관 514호)로 원본 제출

            위 메일 내용 반영해서 제출기한 5일전에 알람이 오도록 일정등록해줘
        """.trimIndent()
        const val OWNER_CHOICE = "리마인더 알림"
        const val BARE_CONFIRMATION = "응"
        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val SETUP_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
