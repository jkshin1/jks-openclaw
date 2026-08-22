package com.personaledge.agent

import android.Manifest
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.llm.InferenceBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the real model pick the right tool from a Korean request?
 *
 * The keyboard is the reason this is a test rather than a UI script: `adb input text` refuses
 * non-ASCII and injected key events bypass the IME's Hangul composer, so the only way to get
 * Korean into the field from automation is to hand the ViewModel a Kotlin string.
 *
 * The confirmation is **denied**, so this proves selection, argument decoding, validation, and the
 * preview without writing anything to the owner's calendar. The write half is covered separately.
 *
 * Requires the verified 3.66GB model to be installed; skips otherwise. It is slow by nature —
 * on-device decode, not test overhead.
 */
@RunWith(AndroidJUnit4::class)
class KoreanToolSelectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun grantCalendarAccess() {
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                it,
            )
        }
    }

    @Test
    fun aKoreanRequestSelectsTheCalendarWriteTool() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var viewModel: PersonalEdgeViewModel
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
            }

            val installed = viewModel.awaitModel()
            assumeTrue("The verified model is not installed on this device.", installed)

            if (viewModel.uiState.value.modelStatus != ModelUiStatus.READY) {
                scenario.onActivity { viewModel.initializeRuntime(InferenceBackend.GPU) }
                assumeTrue(
                    "The runtime did not become ready in time.",
                    viewModel.awaitStatus(ModelUiStatus.READY, RUNTIME_TIMEOUT_MILLIS),
                )
            }

            scenario.onActivity { viewModel.refreshCalendarSetup() }
            val pinned = viewModel.awaitPinnedCalendar()
            assumeTrue("No writable calendar is available to pin.", pinned)

            scenario.onActivity {
                viewModel.updatePrompt(KOREAN_REQUEST)
                viewModel.sendPrompt()
            }

            val pending = viewModel.awaitConfirmation()
            assertNotNull(
                "The model produced no confirmable tool call for a Korean request.",
                pending,
            )

            // Deny: the point is that the right tool was chosen with the right arguments, not that
            // another event lands in the owner's calendar.
            scenario.onActivity { viewModel.resolveConfirmation(pending!!.actionId, false) }

            assertEquals("calendar_create_event", pending!!.toolName)
            assertEquals("일정 등록", pending.preview.title)
            val summary = pending.preview.summary
            assertTrue(summary, summary.contains("치과"))
            assertTrue(summary, summary.contains("15:00"))
            assertTrue(summary, summary.contains(tomorrow()))
        }
    }

    private fun tomorrow(): String = java.time.LocalDate
        .now(java.time.ZoneId.systemDefault())
        .plusDays(1)
        .toString()

    private suspend fun PersonalEdgeViewModel.awaitModel(): Boolean = awaitUntil(MODEL_TIMEOUT_MILLIS) {
        uiState.value.modelStatus in setOf(ModelUiStatus.VERIFIED, ModelUiStatus.READY)
    }

    private suspend fun PersonalEdgeViewModel.awaitStatus(
        status: ModelUiStatus,
        timeoutMillis: Long,
    ): Boolean = awaitUntil(timeoutMillis) { uiState.value.modelStatus == status }

    private suspend fun PersonalEdgeViewModel.awaitPinnedCalendar(): Boolean =
        awaitUntil(SETUP_TIMEOUT_MILLIS) { calendarSetup.value.calendars.isNotEmpty() }.also {
            val writable = calendarSetup.value.calendars.firstOrNull(CalendarOption::writable)
            if (calendarSetup.value.pinnedCalendarId == null && writable != null) {
                pinCalendar(writable)
                awaitUntil(SETUP_TIMEOUT_MILLIS) { calendarSetup.value.pinnedCalendarId != null }
            }
        }.let { calendarSetup.value.pinnedCalendarId != null }

    private suspend fun PersonalEdgeViewModel.awaitConfirmation(): PendingConfirmation? {
        awaitUntil(TURN_TIMEOUT_MILLIS) { confirmationCoordinator.pending.value != null }
        return confirmationCoordinator.pending.value
    }

    private suspend fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(POLL_INTERVAL_MILLIS)
        }
        return condition()
    }

    private companion object {
        const val KOREAN_REQUEST = "내일 오후 3시부터 4시까지 치과 일정 넣어줘"
        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val SETUP_TIMEOUT_MILLIS = 15_000L
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
