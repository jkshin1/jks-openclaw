package com.personaledge.core.tools

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settles two platform questions the documentation does not answer clearly:
 * whether `getNextAlarmClock()` needs a permission, and whether it reports alarms owned by the
 * clock app rather than only those the caller scheduled.
 *
 * Creating an alarm is a real, persistent change to the clock app, and there is no API to remove
 * one. The write half therefore runs on an emulator only and cleans up with `pm clear`; on a
 * physical device it is skipped rather than left to wipe the owner's real alarms.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAlarmGatewayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val zone: ZoneId = ZoneId.systemDefault()

    private lateinit var gateway: AndroidAlarmGateway

    private val isEmulator: Boolean
        get() = Build.HARDWARE == "ranchu" || Build.FINGERPRINT.contains("generic")

    @Before
    fun createGateway() {
        gateway = AndroidAlarmGateway(context)
    }

    @After
    fun clearEmulatorAlarms() {
        if (isEmulator) {
            shell("pm clear $CLOCK_PACKAGE")
        }
    }

    @Test
    fun theClockAppIsVisibleThroughTheQueriesDeclaration() = runBlocking {
        // Without the <queries> entry this returns false on API 30+ even with a clock installed.
        assertTrue(gateway.clockAppAvailable())
    }

    @Test
    fun readingTheNextAlarmNeedsNoPermission() = runBlocking {
        // The test APK holds no alarm-related permission beyond normal SET_ALARM, so a throw
        // here would mean the read half of "알람 조회" is not actually available to this app.
        val next = gateway.nextAlarm()

        assertTrue(next == null || next.triggerAtEpochMillis > 0)
    }

    @Test
    fun anAlarmCreatedByTheClockAppIsReportedAsTheNextAlarm() = runBlocking {
        assumeTrue("Alarm creation is emulator-only; it cannot be undone.", isEmulator)

        // Created through the platform intent from the shell, so this measures the clock app's
        // behavior rather than this process's ability to start a background activity.
        val target = Instant.now().atZone(zone).plusDays(1).withHour(4).withMinute(37)
        shell(
            "am start -a android.intent.action.SET_ALARM " +
                "--ei android.intent.extra.alarm.HOUR ${target.hour} " +
                "--ei android.intent.extra.alarm.MINUTES ${target.minute} " +
                "--ez android.intent.extra.alarm.SKIP_UI true",
        )

        val next = awaitNextAlarm()

        assertNotNull("getNextAlarmClock did not report the clock app's alarm.", next)
        val reported = Instant.ofEpochMilli(next!!.triggerAtEpochMillis).atZone(zone)
        assertEquals(target.hour, reported.hour)
        assertEquals(target.minute, reported.minute)
        assertTrue(next.triggerAtEpochMillis > System.currentTimeMillis())
    }

    private suspend fun awaitNextAlarm(): NextAlarm? {
        repeat(POLL_ATTEMPTS) {
            gateway.nextAlarm()?.let { alarm -> return alarm }
            delay(POLL_INTERVAL_MILLIS)
        }
        return gateway.nextAlarm()
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
        const val CLOCK_PACKAGE = "com.google.android.deskclock"
        const val POLL_ATTEMPTS = 20
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
