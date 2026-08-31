package com.personaledge.agent

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Process-scope privacy persistence; emulator-only because it changes the installed app setting. */
@RunWith(AndroidJUnit4::class)
class NotificationCaptureCoordinatorTest {
    private val isEmulator: Boolean
        get() = Build.HARDWARE == "ranchu" || Build.FINGERPRINT.contains("generic")

    private lateinit var container: AppContainer
    private var originalReplyEnabled = false

    @Before
    fun enableReplyForTest() = runBlocking {
        assumeTrue("Changing the owner's Kakao reply setting is emulator-only.", isEmulator)
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PersonalEdgeApplication
        container = application.container
        originalReplyEnabled = container.settings.current().kakaoNotificationReplyEnabled
        val request = container.notificationCaptureInterlock.requestReplyEnabled(true)
        check(
            container.notificationCaptureInterlock.setReplyEnabled(
                settings = container.settings,
                request = request,
            ),
        )
    }

    @After
    fun restoreReplySetting() {
        if (!isEmulator || !::container.isInitialized) return
        runBlocking {
            val request = container.notificationCaptureInterlock.requestReplyEnabled(
                originalReplyEnabled,
            )
            container.notificationCaptureInterlock.setReplyEnabled(
                settings = container.settings,
                request = request,
            )
        }
    }

    @Test
    fun replyDisablePersistsAfterItsViewModelScopeIsCancelled() = runBlocking {
        val viewModelJob = Job()
        val coordinator = NotificationCaptureCoordinator(
            container = container,
            scope = CoroutineScope(viewModelJob + Dispatchers.Main.immediate),
        )

        coordinator.setReplyEnabled(false)
        viewModelJob.cancel()

        // The execution gate closes synchronously even though the durable write is dispatched.
        assertFalse(container.notificationCaptureInterlock.replyAllowed(durableEnabled = true))
        withTimeout(5_000) {
            while (container.settings.current().kakaoNotificationReplyEnabled) delay(10)
        }
        assertFalse(
            container.notificationCaptureInterlock.replyAllowed(
                container.settings.current().kakaoNotificationReplyEnabled,
            ),
        )
    }
}
