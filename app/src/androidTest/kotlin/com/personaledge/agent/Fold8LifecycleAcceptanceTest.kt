package com.personaledge.agent

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.llm.InferenceBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly opted-in physical Fold8 lifecycle receipt.
 *
 * The Activity is recreated and moved through stopped/resumed lifecycle states. The test never
 * reads a credential or notification body. It compares only settings, credential-presence flags,
 * and the capture-store row count around the lifecycle changes. Physical display rotation is a
 * separate shell acceptance because Android 17 ignores app orientation requests on large screens.
 */
@RunWith(AndroidJUnit4::class)
class Fold8LifecycleAcceptanceTest {
    @Test
    fun gpuRuntimeSurvivesRecreationAndBackgroundWithoutChangingPrivateState() = runBlocking {
        assumeTrue(
            "Fold8 lifecycle acceptance requires -e liveFoldLifecycle true.",
            InstrumentationRegistry.getArguments().getString(LIVE_ARGUMENT) == "true",
        )
        assumeTrue("Physical-device acceptance only.", !isEmulator())

        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PersonalEdgeApplication
        val settingsBefore = application.container.settings.current()
        val credentialsBefore = credentialPresence(application.container.credentials.statuses())
        val notificationCountBefore = application.container.notifications.count()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var viewModel: PersonalEdgeViewModel
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
            }
            assumeTrue(
                "The verified model is not installed on this device.",
                viewModel.awaitUntil(MODEL_TIMEOUT_MILLIS) {
                    uiState.value.modelStatus == ModelUiStatus.VERIFIED ||
                        uiState.value.modelStatus == ModelUiStatus.READY
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

            scenario.recreate()
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
            }
            assertEquals(ModelUiStatus.READY, viewModel.uiState.value.modelStatus)

            scenario.moveToState(Lifecycle.State.STARTED)
            Thread.sleep(BACKGROUND_SETTLE_MILLIS)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
            }
            assertEquals(ModelUiStatus.READY, viewModel.uiState.value.modelStatus)
        }

        assertEquals(settingsBefore, application.container.settings.current())
        assertEquals(
            credentialsBefore,
            credentialPresence(application.container.credentials.statuses()),
        )
        assertEquals(notificationCountBefore, application.container.notifications.count())
    }

    @Test
    fun gpuRuntimeSurvivesExternalSystemRotationWithoutChangingPrivateState() = runBlocking {
        assumeTrue(
            "Interactive rotation requires -e liveFoldRotation true.",
            InstrumentationRegistry.getArguments().getString(ROTATION_ARGUMENT) == "true",
        )
        assumeTrue("Physical-device acceptance only.", !isEmulator())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication
        val settingsBefore = application.container.settings.current()
        val credentialsBefore = credentialPresence(application.container.credentials.statuses())
        val notificationCountBefore = application.container.notifications.count()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var viewModel: PersonalEdgeViewModel
            var initialOrientation = Configuration.ORIENTATION_UNDEFINED
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                initialOrientation = activity.resources.configuration.orientation
            }
            assumeTrue(
                "The Activity did not start in a stable orientation.",
                initialOrientation in setOf(
                    Configuration.ORIENTATION_PORTRAIT,
                    Configuration.ORIENTATION_LANDSCAPE,
                ),
            )
            assumeTrue(
                "The verified model is not installed on this device.",
                viewModel.awaitUntil(MODEL_TIMEOUT_MILLIS) {
                    uiState.value.modelStatus == ModelUiStatus.VERIFIED ||
                        uiState.value.modelStatus == ModelUiStatus.READY
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

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("privacy_rotation_ready", "true")
                    putString("privacy_rotation_initial", orientationName(initialOrientation))
                },
            )
            assertTrue(
                "No external system rotation was observed.",
                scenario.awaitOrientationDifferentFrom(initialOrientation),
            )
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
                val orientation = activity.resources.configuration.orientation
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    assertTrue(activity.window.decorView.width > activity.window.decorView.height)
                } else {
                    assertTrue(activity.window.decorView.height > activity.window.decorView.width)
                }
            }
            assertEquals(ModelUiStatus.READY, viewModel.uiState.value.modelStatus)
            instrumentation.sendStatus(
                0,
                Bundle().apply { putString("privacy_rotation_alternate_observed", "true") },
            )

            assertTrue(
                "The external system rotation was not restored.",
                scenario.awaitOrientation(initialOrientation),
            )
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[PersonalEdgeViewModel::class.java]
            }
            assertEquals(ModelUiStatus.READY, viewModel.uiState.value.modelStatus)
        }

        assertEquals(settingsBefore, application.container.settings.current())
        assertEquals(
            credentialsBefore,
            credentialPresence(application.container.credentials.statuses()),
        )
        assertEquals(notificationCountBefore, application.container.notifications.count())
    }

    private suspend fun ActivityScenario<MainActivity>.awaitOrientation(expected: Int): Boolean {
        val deadline = System.currentTimeMillis() + EXTERNAL_ROTATION_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            var current = Configuration.ORIENTATION_UNDEFINED
            onActivity { activity -> current = activity.resources.configuration.orientation }
            if (current == expected) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return false
    }

    private suspend fun ActivityScenario<MainActivity>.awaitOrientationDifferentFrom(
        initial: Int,
    ): Boolean {
        val deadline = System.currentTimeMillis() + EXTERNAL_ROTATION_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            var current = initial
            onActivity { activity -> current = activity.resources.configuration.orientation }
            if (current != initial && current != Configuration.ORIENTATION_UNDEFINED) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return false
    }

    private suspend fun PersonalEdgeViewModel.awaitUntil(
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

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)

    private fun orientationName(orientation: Int): String = when (orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> "landscape"
        Configuration.ORIENTATION_PORTRAIT -> "portrait"
        else -> "undefined"
    }

    private fun credentialPresence(
        statuses: List<CredentialStatus>,
    ): Map<CredentialSlot, Boolean> {
        val snapshot = java.util.LinkedHashMap<CredentialSlot, Boolean>()
        for (status in statuses) snapshot[status.slot] = status.stored
        return snapshot
    }

    private companion object {
        const val LIVE_ARGUMENT = "liveFoldLifecycle"
        const val ROTATION_ARGUMENT = "liveFoldRotation"
        const val MODEL_TIMEOUT_MILLIS = 15_000L
        const val RUNTIME_TIMEOUT_MILLIS = 120_000L
        const val EXTERNAL_ROTATION_TIMEOUT_MILLIS = 90_000L
        const val BACKGROUND_SETTLE_MILLIS = 2_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
