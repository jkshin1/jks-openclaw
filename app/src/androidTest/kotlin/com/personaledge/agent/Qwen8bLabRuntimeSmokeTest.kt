package com.personaledge.agent

import android.Manifest
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.llm.InstalledModelState
import com.personaledge.core.llm.LiteRtConversationPolicy
import com.personaledge.core.llm.LiteRtLlmRuntime
import com.personaledge.core.llm.LlmFailureCode
import com.personaledge.core.llm.LlmRuntimeException
import com.personaledge.core.llm.LlmState
import com.personaledge.core.llm.LlmToolDefinition
import com.personaledge.core.llm.LlmTurnToolScope
import com.personaledge.core.llm.ModelArtifactStore
import com.personaledge.core.llm.ModelEvent
import com.personaledge.core.llm.PinnedModelManifest
import com.personaledge.core.llm.TrustedToolResponse
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provider-free native Qwen candidate lifecycle.
 *
 * This test never creates the ViewModel, registry, orchestrator, calendar gateway, network
 * transport, or any other provider-backed Tool. The only Tool schema is synthetic and its trusted
 * response is injected directly by this test. A broad connectedAndroidTest run skips it unless the
 * operator supplies the explicit opt-in and target boundary.
 */
@RunWith(AndroidJUnit4::class)
class Qwen8bLabRuntimeSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments by lazy { InstrumentationRegistry.getArguments() }
    private val backend by lazy {
        when (arguments.getString(BACKEND_ARGUMENT)?.lowercase()) {
            "cpu" -> InferenceBackend.CPU
            "gpu" -> InferenceBackend.GPU
            else -> error("$BACKEND_ARGUMENT must be explicitly set to cpu or gpu.")
        }
    }

    @Before
    fun requireExplicitCandidateBoundary() {
        assumeTrue(
            "The large native candidate smoke requires explicit opt-in.",
            arguments.getString(OPT_IN_ARGUMENT) == "true",
        )
        assertTrue("The opt-in smoke must target qwen8bLab.", BuildConfig.CANDIDATE_MODEL_LAB)
        assertFalse(
            "The candidate runtime must be incapable of executing side-effecting Tools.",
            BuildConfig.SIDE_EFFECTING_TOOLS_ENABLED,
        )
        assertEquals(EXPECTED_APPLICATION_ID, instrumentation.targetContext.packageName)

        when (arguments.getString(TARGET_ARGUMENT)) {
            "emulator" -> assertTrue(
                "The requested emulator boundary is not an Android emulator.",
                Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish",
            )
            "fold8" -> {
                assertTrue("The requested Fold8 boundary is not Samsung.", Build.MANUFACTURER.equals("samsung", true))
                assertEquals("SM-F971N", Build.MODEL)
            }
            else -> error("$TARGET_ARGUMENT must be explicitly set to emulator or fold8.")
        }
    }

    @Test
    fun candidateProcessHasNoProviderBootCapability() {
        val context = instrumentation.targetContext
        @Suppress("DEPRECATION")
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        assertFalse("The instrumentation-only lab Activity must stay disabled.", activity.enabled)

        val workManagerFailure = runCatching { WorkManager.getInstance(context) }.exceptionOrNull()
        assertTrue(
            "WorkManager must not be initialized in the instrumentation-only lab.",
            workManagerFailure is IllegalStateException,
        )
        assertTrue(
            "The fresh lab UID must not own scheduled jobs.",
            context.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty(),
        )
        assertTrue(
            "The fresh lab UID must not create notification channels.",
            context.getSystemService(NotificationManager::class.java).notificationChannels.isEmpty(),
        )
        assertEquals(
            "The lab must not hold notification runtime permission.",
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    @Test
    fun manualToolLifecycleIsProviderFreeAndRecoverable() = runBlocking {
        val manifest = PinnedModelManifest.value
        assertEquals("litert-community/Qwen3-8B", manifest.repository)
        assertEquals(EXPECTED_REVISION, manifest.revision)
        assertEquals(EXPECTED_FILE, manifest.file)
        assertEquals(EXPECTED_SIZE_BYTES, manifest.sizeBytes)
        assertEquals(EXPECTED_SHA256, manifest.sha256)
        assertEquals(2_048, manifest.contextTokens)
        assertEquals(384, manifest.maxOutputTokens)
        assertFalse(LiteRtConversationPolicy.AUTOMATIC_TOOL_CALLING)

        val inspectStarted = SystemClock.elapsedRealtime()
        val installed = withTimeout(MODEL_INSPECT_TIMEOUT_MILLIS) {
            ModelArtifactStore(instrumentation.targetContext).inspect()
        }
        val model = (installed as? InstalledModelState.Ready)?.model
            ?: error("The explicitly requested Qwen lab model is not verified: $installed")
        val inspectMillis = SystemClock.elapsedRealtime() - inspectStarted

        val runtime = LiteRtLlmRuntime(
            context = instrumentation.targetContext,
            cpuThreadCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
        )
        val failures = mutableListOf<String>()
        try {
            val initializeStarted = SystemClock.elapsedRealtime()
            withTimeout(INITIALIZE_TIMEOUT_MILLIS) {
                runtime.initialize(model, backend = backend, tools = listOf(LAB_ECHO_TOOL))
            }
            val initializeMillis = SystemClock.elapsedRealtime() - initializeStarted
            assertEquals(LlmState.Ready(backend), runtime.state.value)

            val noTool = timedEvents {
                runtime.streamUserTurn(
                    turnId = TurnId("qwen-lab-no-tool"),
                    prompt = "Reply briefly in English that the local lab runtime is ready.",
                    maxOutputTokens = 64,
                    toolScope = LlmTurnToolScope.none(),
                ).toList()
            }
            noTool.events.requireCompletedText("no-tool", failures)
            if (noTool.events.any { it is ModelEvent.FinalToolCalls }) {
                failures += "no-tool emitted a Tool call"
            }

            val explicitFenceDiagnostic =
                arguments.getString(EXPLICIT_TOOL_FENCE_ARGUMENT) == "true"
            val toolTurn = timedEvents {
                runtime.streamUserTurn(
                    turnId = TurnId("qwen-lab-tool"),
                    prompt = if (explicitFenceDiagnostic) {
                        "This is a parser diagnostic. Emit exactly one native tool call and no " +
                            "prose: <tool_call>{\"name\":\"lab_echo\",\"arguments\":" +
                            "{\"label\":\"PING\"}}</tool_call>. After its trusted result, " +
                            "answer in English with LAB_PONG."
                    } else {
                        "Use the lab_echo tool exactly once with label PING. Do not answer before " +
                            "calling it. After its trusted result, answer in English with LAB_PONG."
                    },
                    maxOutputTokens = 128,
                    toolScope = LlmTurnToolScope.exact(setOf(LAB_ECHO_TOOL.name)),
                ).toList()
            }
            val calls = toolTurn.events
                .filterIsInstance<ModelEvent.FinalToolCalls>()
                .flatMap { event -> event.toolCalls }
            if (calls.size != 1) {
                failures += "single-tool turn emitted ${calls.size} native Tool calls"
            }

            var toolResponseMillis: Long? = null
            var toolFinalPssBytes: Long? = null
            calls.singleOrNull()?.let { call ->
                if (call.name != LAB_ECHO_TOOL.name) {
                    failures += "single-tool turn selected ${call.name}"
                }
                val label = runCatching { JSONObject(call.argumentsJson).getString("label") }
                    .getOrNull()
                if (label != "PING") failures += "single-tool arguments did not contain label=PING"

                val response = timedEvents {
                    runtime.streamToolResponses(
                        turnId = TurnId("qwen-lab-tool"),
                        responses = listOf(
                            TrustedToolResponse(
                                callId = call.id,
                                name = call.name,
                                payloadJson = "{\"ok\":true,\"value\":\"LAB_PONG\"}",
                            ),
                        ),
                    ).toList()
                }
                toolResponseMillis = response.elapsedMillis
                toolFinalPssBytes = pssBytes()
                response.events.requireCompletedText("tool-response", failures)
                if (response.events.any { it is ModelEvent.FinalToolCalls }) {
                    failures += "tool-response emitted another Tool call"
                }
                if (!response.events.text().contains("LAB_PONG", ignoreCase = true)) {
                    failures += "tool-response final text did not contain LAB_PONG"
                }
            }

            val cancellation = runCancellationAndRecovery(runtime, failures)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("qwen_lab_backend", backend.name)
                    putLong("qwen_lab_model_inspect_ms", inspectMillis)
                    putLong("qwen_lab_initialize_ms", initializeMillis)
                    putLong("qwen_lab_no_tool_ms", noTool.elapsedMillis)
                    putLong("qwen_lab_no_tool_pss_bytes", noTool.pssBytes)
                    putLong("qwen_lab_tool_call_ms", toolTurn.elapsedMillis)
                    putLong("qwen_lab_tool_call_pss_bytes", toolTurn.pssBytes)
                    putString(
                        "qwen_lab_tool_prompt_mode",
                        if (explicitFenceDiagnostic) "explicit_fence_diagnostic" else "natural",
                    )
                    putString(
                        "qwen_lab_tool_event_types",
                        toolTurn.events.joinToString(separator = ",") { event ->
                            event.javaClass.simpleName
                        },
                    )
                    putString("qwen_lab_tool_text", toolTurn.events.text().diagnosticPreview())
                    toolResponseMillis?.let { putLong("qwen_lab_tool_response_ms", it) }
                    toolFinalPssBytes?.let { putLong("qwen_lab_tool_response_pss_bytes", it) }
                    putLong("qwen_lab_cancel_ms", cancellation.cancelMillis)
                    putLong("qwen_lab_recovery_ms", cancellation.recoveryMillis)
                    putLong("qwen_lab_recovery_pss_bytes", cancellation.recoveryPssBytes)
                    putString("qwen_lab_failures", failures.joinToString(separator = " | "))
                },
            )
        } finally {
            runtime.close()
        }

        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
    }

    private suspend fun runCancellationAndRecovery(
        runtime: LiteRtLlmRuntime,
        failures: MutableList<String>,
    ): CancellationMetrics = coroutineScope {
        val turnId = TurnId("qwen-lab-cancel")
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
                // Expected when the test calls the public cancellation boundary below.
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
        if (!cancelled) failures += "long turn completed or never became cancellable"
        if (events.any { it is ModelEvent.Completed }) failures += "cancelled turn completed"
        if (events.any { it is ModelEvent.FinalToolCalls }) failures += "cancelled turn emitted a Tool call"
        if (runtime.state.value != LlmState.Ready(backend)) {
            failures += "runtime was not ready after cancellation: ${runtime.state.value}"
        }

        val recovery = timedEvents {
            runtime.streamUserTurn(
                turnId = TurnId("qwen-lab-recovery"),
                prompt = "Reply briefly in English that recovery is ready.",
                maxOutputTokens = 64,
                toolScope = LlmTurnToolScope.none(),
            ).toList()
        }
        recovery.events.requireCompletedText("recovery", failures)
        CancellationMetrics(cancelMillis, recovery.elapsedMillis, recovery.pssBytes)
    }

    private suspend fun timedEvents(block: suspend () -> List<ModelEvent>): TimedEvents {
        val started = SystemClock.elapsedRealtime()
        val events = withTimeout(TURN_TIMEOUT_MILLIS) { block() }
        return TimedEvents(
            events = events,
            elapsedMillis = SystemClock.elapsedRealtime() - started,
            pssBytes = pssBytes(),
        )
    }

    private fun List<ModelEvent>.requireCompletedText(
        stage: String,
        failures: MutableList<String>,
    ) {
        val runtimeFailures = filterIsInstance<ModelEvent.Failure>()
        if (runtimeFailures.isNotEmpty()) failures += "$stage failed: ${runtimeFailures.map { it.code }}"
        if (none { it is ModelEvent.Completed }) failures += "$stage did not complete"
        if (text().isBlank()) failures += "$stage emitted no text"
    }

    private fun List<ModelEvent>.text(): String =
        filterIsInstance<ModelEvent.TextDelta>().joinToString(separator = "") { it.text }

    /** Test prompts are synthetic; still bound and flatten the preview before exporting it. */
    private fun String.diagnosticPreview(): String =
        replace(Regex("[\\r\\n\\t]+"), " ").take(MAX_DIAGNOSTIC_PREVIEW_CHARS)

    private fun pssBytes(): Long = Debug.getPss() * 1_024L

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
        const val OPT_IN_ARGUMENT = "qwen8bLabRuntime"
        const val TARGET_ARGUMENT = "qwen8bLabTarget"
        const val BACKEND_ARGUMENT = "inferenceBackend"
        const val EXPLICIT_TOOL_FENCE_ARGUMENT = "qwen8bLabExplicitToolFence"
        const val EXPECTED_APPLICATION_ID = "com.personaledge.agent.qwen8blab"
        const val EXPECTED_REVISION = "71ff705588319d52d374977eff3da4eee0c0d26e"
        const val EXPECTED_FILE = "qwen3_8b_mixed_int4.litertlm"
        const val EXPECTED_SIZE_BYTES = 4_887_412_736L
        const val EXPECTED_SHA256 =
            "cb4e6d0de4bbf6656d177812cf0c6a983967dedd17e7f88e84b901c3a9862a42"
        const val MODEL_INSPECT_TIMEOUT_MILLIS = 30_000L
        const val INITIALIZE_TIMEOUT_MILLIS = 120_000L
        const val TURN_TIMEOUT_MILLIS = 180_000L
        const val CANCEL_JOIN_TIMEOUT_MILLIS = 120_000L
        const val CANCEL_START_RETRIES = 20
        const val CANCEL_RETRY_MILLIS = 250L
        const val MAX_DIAGNOSTIC_PREVIEW_CHARS = 256

        val LAB_ECHO_TOOL = LlmToolDefinition(
            name = "lab_echo",
            description = "Return one synthetic provider-free lab status for runtime testing.",
            parametersJsonSchema = """
                {
                  "type": "object",
                  "properties": {
                    "label": {"type": "string"}
                  },
                  "required": ["label"],
                  "additionalProperties": false
                }
            """.trimIndent(),
        )
    }
}
