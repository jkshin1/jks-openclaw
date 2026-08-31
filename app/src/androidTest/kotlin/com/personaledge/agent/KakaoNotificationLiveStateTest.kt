package com.personaledge.agent

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reports only the two Kakao capture gates and the number of cached rows on a physical device.
 *
 * The test deliberately never searches or reads a captured notification. It also uses the raw
 * repository count instead of the settings-screen helper, because that helper prunes expired rows
 * and this acceptance probe must be read-only.
 */
@RunWith(AndroidJUnit4::class)
class KakaoNotificationLiveStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val isEmulator: Boolean
        get() = Build.HARDWARE == "ranchu" || Build.FINGERPRINT.contains("generic")

    @Test
    fun grantedCaptureStateReportsOnlyBooleanAndCount() = runBlocking {
        assumeTrue(
            "Run explicitly with -e liveKakaoState true.",
            InstrumentationRegistry.getArguments().getString("liveKakaoState") == "true",
        )
        assumeTrue("Physical-device acceptance only.", !isEmulator)

        val application = context.applicationContext as PersonalEdgeApplication
        val current = application.container.settings.current()
        val accessGranted = application.container.notificationGateway.accessGranted()
        val storedCount = application.container.notifications.count()
        val kakaoRowCount = application.container.database.openHelper.readableDatabase.query(
            SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM captured_notifications WHERE package_name = ?",
                arrayOf(NotificationCapture.KAKAO_TALK_PACKAGE),
            ),
        ).use { cursor ->
            assertTrue("The aggregate count query returned no row.", cursor.moveToFirst())
            cursor.getLong(0)
        }
        val allRowsAreKakao = storedCount == kakaoRowCount

        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("privacy_kakao_access_granted", accessGranted.toString())
                putString(
                    "privacy_kakao_capture_enabled",
                    current.notificationCaptureEnabled.toString(),
                )
                putString("privacy_kakao_stored_count", storedCount.toString())
                putString("privacy_kakao_all_rows_allowlisted", allRowsAreKakao.toString())
            },
        )

        assertTrue("Notification-listener access is not granted.", accessGranted)
        assertTrue(
            "Kakao notification capture is disabled in app settings.",
            current.notificationCaptureEnabled,
        )
        assertTrue("The capture store contains a non-KakaoTalk package.", allRowsAreKakao)
    }
}
