package com.personaledge.agent

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.tools.AlarmDay
import com.personaledge.core.tools.AlarmOutcome
import com.personaledge.core.tools.AlarmRequest
import com.personaledge.core.tools.AndroidAlarmGateway
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the one part `core:tools` instrumentation cannot: starting the clock app's activity from
 * this app's own foreground, with this app's UID, manifest, and package-visibility rules.
 *
 * `ACTION_SET_ALARM` returns no result, so "the request was delivered" is only meaningful if the
 * alarm actually appears afterwards — the assertion here is on `getNextAlarmClock()`, not on the
 * gateway's own return value alone.
 *
 * Emulator only: an alarm cannot be removed through any API, and the cleanup clears the clock
 * app's data. On a physical device that would destroy the owner's real alarms, so it is skipped.
 */
@RunWith(AndroidJUnit4::class)
class AlarmForegroundRequestTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val zone: ZoneId = ZoneId.systemDefault()

    private val isEmulator: Boolean
        get() = Build.HARDWARE == "ranchu" || Build.FINGERPRINT.contains("generic")

    @After
    fun clearEmulatorAlarms() {
        if (isEmulator) {
            shell("pm clear com.google.android.deskclock")
        }
    }

    @Test
    fun aForegroundAppCanCreateAnAlarmThroughTheClockApp() {
        assumeTrue("Alarm creation is emulator-only; it cannot be undone.", isEmulator)

        val gateway = AndroidAlarmGateway(instrumentation.targetContext)
        val target = Instant.now().atZone(zone).plusDays(1).withHour(6).withMinute(15)

        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val outcome = gateway.requestAlarm(
                    AlarmRequest(
                        hour = target.hour,
                        minute = target.minute,
                        label = "회귀 테스트 알람",
                        days = emptySet(),
                    ),
                )

                assertEquals(AlarmOutcome.Delivered, outcome)

                val next = awaitNextAlarm(gateway)
                assertNotNull("The clock app did not create the requested alarm.", next)
                val reported = Instant.ofEpochMilli(next!!.triggerAtEpochMillis).atZone(zone)
                assertEquals(target.hour, reported.hour)
                assertEquals(target.minute, reported.minute)
            }
        }
    }

    @Test
    fun aRepeatingAlarmFiresOnTheNextRequestedWeekday() {
        assumeTrue("Alarm creation is emulator-only; it cannot be undone.", isEmulator)

        val gateway = AndroidAlarmGateway(instrumentation.targetContext)
        val today = Instant.now().atZone(zone)
        // Choose a weekday that is not today, so "next occurrence" is unambiguous.
        val targetDay = AlarmDay.entries.first { day ->
            day.korean != KOREAN_DAYS[today.dayOfWeek.value - 1]
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val outcome = gateway.requestAlarm(
                    AlarmRequest(
                        hour = 5,
                        minute = 45,
                        label = null,
                        days = setOf(targetDay),
                    ),
                )

                assertEquals(AlarmOutcome.Delivered, outcome)

                val next = awaitNextAlarm(gateway)
                assertNotNull("The clock app did not create the repeating alarm.", next)
                val reported = Instant.ofEpochMilli(next!!.triggerAtEpochMillis).atZone(zone)
                assertEquals(5, reported.hour)
                assertEquals(45, reported.minute)
                assertEquals(targetDay.korean, KOREAN_DAYS[reported.dayOfWeek.value - 1])
            }
        }
    }

    private suspend fun awaitNextAlarm(gateway: AndroidAlarmGateway) = run {
        repeat(POLL_ATTEMPTS) {
            gateway.nextAlarm()?.let { alarm -> return@run alarm }
            delay(POLL_INTERVAL_MILLIS)
        }
        gateway.nextAlarm()
    }

    /**
     * Drains and closes the shell output.
     *
     * The descriptor must be consumed through AutoCloseInputStream: wrapping the raw
     * FileDescriptor leaves the ParcelFileDescriptor unowned, and a read can then fail with
     * EBADF once it is collected.
     */
    private fun shell(command: String) {
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { stream -> stream.readBytes() }
    }

    private companion object {
        /** Indexed by `DayOfWeek.value - 1`, so Monday first. */
        val KOREAN_DAYS = listOf("월", "화", "수", "목", "금", "토", "일")
        const val POLL_ATTEMPTS = 20
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
