package com.personaledge.agent

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.llm.InstalledModelState
import com.personaledge.core.llm.LiteRtConversationPolicy
import com.personaledge.core.llm.LiteRtLlmRuntime
import com.personaledge.core.llm.LlmFailureCode
import com.personaledge.core.llm.LlmRuntimeException
import com.personaledge.core.llm.LlmState
import com.personaledge.core.llm.LlmTurnToolScope
import com.personaledge.core.llm.ModelArtifactStore
import com.personaledge.core.llm.ModelEvent
import com.personaledge.core.llm.PinnedModelManifest
import com.personaledge.core.llm.TurnId
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provider-free lifecycle coverage for the production-pinned model on an Android emulator.
 *
 * This test does not create the ViewModel, Tool registry, orchestrator, calendar gateway, network
 * transport, or a Tool schema. It verifies the exact model, initializes the explicitly requested
 * backend, cancels one real decode, and proves a fresh turn can complete afterwards. A broad
 * instrumentation run skips it unless the operator supplies the explicit opt-in.
 */
@RunWith(AndroidJUnit4::class)
class CurrentModelAvdRuntimeSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments by lazy { InstrumentationRegistry.getArguments() }

    @Before
    fun requireExplicitAvdBoundary() {
        assumeTrue(
            "The production-model AVD smoke requires explicit opt-in.",
            arguments.getString(OPT_IN_ARGUMENT) == "true",
        )
        assertFalse("The production smoke must not run in a candidate lab variant.", BuildConfig.CANDIDATE_MODEL_LAB)
        assertEquals(EXPECTED_APPLICATION_ID, instrumentation.targetContext.packageName)
        assertTrue(
            "The production-model AVD smoke must run on an Android emulator.",
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish",
        )
        assertEquals(
            "The current emulator evidence is intentionally CPU-bound.",
            "cpu",
            arguments.getString(BACKEND_ARGUMENT)?.lowercase(),
        )
    }

    @Test
    fun currentModelCancelsAndRecoversWithoutProviders() = runBlocking {
        val manifest = PinnedModelManifest.value
        assertEquals(EXPECTED_REPOSITORY, manifest.repository)
        assertEquals(EXPECTED_REVISION, manifest.revision)
        assertEquals(EXPECTED_FILE, manifest.file)
        assertEquals(EXPECTED_SIZE_BYTES, manifest.sizeBytes)
        assertEquals(EXPECTED_SHA256, manifest.sha256)
        assertEquals(4_096, manifest.contextTokens)
        assertEquals(1_024, manifest.maxOutputTokens)
        assertFalse(LiteRtConversationPolicy.AUTOMATIC_TOOL_CALLING)

        val inspectStarted = SystemClock.elapsedRealtime()
        val installed = withTimeout(MODEL_INSPECT_TIMEOUT_MILLIS) {
            ModelArtifactStore(instrumentation.targetContext).inspect()
        }
        val model = (installed as? InstalledModelState.Ready)?.model
            ?: error("The production-pinned model is not verified: $installed")
        val inspectMillis = SystemClock.elapsedRealtime() - inspectStarted

        val backend = InferenceBackend.CPU
        val runtime = LiteRtLlmRuntime(
            context = instrumentation.targetContext,
            cpuThreadCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
        )
        try {
            val initializeStarted = SystemClock.elapsedRealtime()
            withTimeout(INITIALIZE_TIMEOUT_MILLIS) {
                runtime.initialize(model, backend = backend, tools = emptyList())
            }
            val initializeMillis = SystemClock.elapsedRealtime() - initializeStarted
            assertEquals(LlmState.Ready(backend), runtime.state.value)

            val baseline = timedEvents {
                runtime.streamUserTurn(
                    turnId = TurnId("current-e4b-avd-baseline"),
                    prompt = "Reply with one short English sentence confirming that the local runtime is ready.",
                    maxOutputTokens = 64,
                    toolScope = LlmTurnToolScope.none(),
                ).toList()
            }
            baseline.events.requireCompletedText("baseline")

            val cancellation = runCancellationAndRecovery(runtime, backend)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("current_model_sha256", manifest.sha256)
                    putString("current_model_backend", backend.name)
                    putLong("current_model_inspect_ms", inspectMillis)
                    putLong("current_model_initialize_ms", initializeMillis)
                    putLong("current_model_baseline_ms", baseline.elapsedMillis)
                    putLong("current_model_baseline_pss_bytes", baseline.pssBytes)
                    putLong("current_model_cancel_ms", cancellation.cancelMillis)
                    putLong("current_model_recovery_ms", cancellation.recoveryMillis)
                    putLong("current_model_recovery_pss_bytes", cancellation.recoveryPssBytes)
                },
            )
        } finally {
            runtime.close()
        }
    }

    private suspend fun runCancellationAndRecovery(
        runtime: LiteRtLlmRuntime,
        backend: InferenceBackend,
    ): CancellationMetrics = coroutineScope {
        val turnId = TurnId("current-e4b-avd-cancel")
        val events = Collections.synchronizedList(mutableListOf<ModelEvent>())
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                runtime.streamUserTurn(
                    turnId = turnId,
                    prompt = "Write a detailed numbered list of 300 different on-device AI " +
                        "reliability checks, with one full explanatory sentence per item.",
                    maxOutputTokens = 384,
                    toolScope = LlmTurnToolScope.none(),
                ).collect(events::add)
            } catch (_: CancellationException) {
                // Expected after the public cancellation boundary succeeds.
            }
        }

        val cancelStarted = SystemClock.elapsedRealtime()
        var cancelled = false
        for (attempt in 0 until CANCEL_START_RETRIES) {
            if (!collector.isActive) break
            delay(CANCEL_RETRY_MILLIS)
            try {
                runtime.cancel(turnId)
                cancelled = true
                break
            } catch (error: LlmRuntimeException) {
                if (error.code != LlmFailureCode.NO_PENDING_TOOL_CALLS) throw error
            }
        }
        val cancelMillis = SystemClock.elapsedRealtime() - cancelStarted
        withTimeout(CANCEL_JOIN_TIMEOUT_MILLIS) { collector.join() }
        assertTrue("The long turn completed or never became cancellable.", cancelled)
        assertTrue("The cancelled turn emitted Completed.", events.none { it is ModelEvent.Completed })
        assertTrue("The cancelled no-Tool turn emitted a Tool call.", events.none { it is ModelEvent.FinalToolCalls })
        assertEquals(LlmState.Ready(backend), runtime.state.value)

        val recovery = timedEvents {
            runtime.streamUserTurn(
                turnId = TurnId("current-e4b-avd-recovery"),
                prompt = "Reply with one short Korean sentence confirming that recovery is ready.",
                maxOutputTokens = 64,
                toolScope = LlmTurnToolScope.none(),
            ).toList()
        }
        recovery.events.requireCompletedText("recovery")
        CancellationMetrics(cancelMillis, recovery.elapsedMillis, recovery.pssBytes)
    }

    private suspend fun timedEvents(block: suspend () -> List<ModelEvent>): TimedEvents {
        val started = SystemClock.elapsedRealtime()
        val events = withTimeout(TURN_TIMEOUT_MILLIS) { block() }
        return TimedEvents(
            events = events,
            elapsedMillis = SystemClock.elapsedRealtime() - started,
            pssBytes = Debug.getPss() * 1_024L,
        )
    }

    private fun List<ModelEvent>.requireCompletedText(stage: String) {
        val failures = filterIsInstance<ModelEvent.Failure>()
        assertTrue("$stage failed: ${failures.map { it.code }}", failures.isEmpty())
        assertTrue("$stage did not complete.", any { it is ModelEvent.Completed })
        assertTrue(
            "$stage emitted no text.",
            filterIsInstance<ModelEvent.TextDelta>().any { it.text.isNotBlank() },
        )
        assertTrue("$stage emitted a Tool call.", none { it is ModelEvent.FinalToolCalls })
    }

    private data class TimedEvents(
        val events: List<ModelEvent>,
        val elapsedMillis: Long,
        val pssBytes: Long,
    )

    private data class CancellationMetrics(
        val cancelMillis: Long,
        val recoveryMillis: Long,
        val recoveryPssBytes: Long,
    )

    private companion object {
        const val OPT_IN_ARGUMENT = "currentModelAvdRuntime"
        const val BACKEND_ARGUMENT = "inferenceBackend"
        const val EXPECTED_APPLICATION_ID = "com.personaledge.agent"
        const val EXPECTED_REPOSITORY = "litert-community/gemma-4-E4B-it-litert-lm"
        const val EXPECTED_REVISION = "2eee7ac325f20eb8c9ac1d0e972f7c84663062da"
        const val EXPECTED_FILE = "gemma-4-E4B-it.litertlm"
        const val EXPECTED_SIZE_BYTES = 3_659_530_240L
        const val EXPECTED_SHA256 =
            "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
        const val MODEL_INSPECT_TIMEOUT_MILLIS = 30_000L
        const val INITIALIZE_TIMEOUT_MILLIS = 120_000L
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val CANCEL_JOIN_TIMEOUT_MILLIS = 120_000L
        const val CANCEL_START_RETRIES = 20
        const val CANCEL_RETRY_MILLIS = 250L
    }
}
