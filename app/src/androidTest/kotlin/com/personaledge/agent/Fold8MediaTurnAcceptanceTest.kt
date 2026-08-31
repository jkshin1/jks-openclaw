package com.personaledge.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.agent.TurnMediaIntent
import com.personaledge.core.agent.TurnMediaPolicy
import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.llm.InstalledModelState
import com.personaledge.core.llm.LiteRtLlmRuntime
import com.personaledge.core.llm.LlmState
import com.personaledge.core.llm.LlmTurnToolScope
import com.personaledge.core.llm.ModelArtifactStore
import com.personaledge.core.llm.ModelEvent
import com.personaledge.core.llm.PinnedModelManifest
import com.personaledge.core.llm.TurnId
import com.personaledge.core.llm.TurnMediaAttachment
import com.personaledge.core.llm.TurnMediaBudget
import com.personaledge.core.llm.TurnMediaKind
import com.personaledge.core.llm.VerifiedInstalledModel
import java.io.ByteArrayOutputStream
import kotlin.math.sin
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical acceptance gate for proving that a photo and a voice clip reach this model on the phone.
 *
 * Deliberately provider-free and ViewModel-free, in the shape of the existing runtime smokes: no
 * Tool registry, no orchestrator, no calendar, no network, no conversation row, and no owner
 * media. Both attachments are synthesized in process, so nothing the owner photographed or said is
 * read, and nothing is written to Room, the ledger, or settings.
 *
 * The load-bearing assertion is the context-token delta rather than the words in the answer. A
 * model will happily produce a plausible Korean sentence about a photo it never received. Each
 * modality is therefore paired with a control that sends the identical prompt and ceiling with no
 * attachment. Every observation owns a newly initialized runtime and exactly one turn; subtracting
 * the before/after increment prevents accumulated conversation state from masquerading as encoder
 * cost. Decode is capped below either required delta, so answer-length variation alone cannot pass.
 */
@RunWith(AndroidJUnit4::class)
class Fold8MediaTurnAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments by lazy { InstrumentationRegistry.getArguments() }

    @Test
    fun realModelPrefillsAPhotoAndAClipWithoutAnyToolExposure() = runBlocking {
        assumeTrue(
            "The media turn acceptance requires -e liveMediaTurn true.",
            arguments.getString(OPT_IN_ARGUMENT) == "true",
        )
        assumeTrue("This acceptance is restricted to the owner's Fold8.", isTargetFold8())
        assertFalse(
            "The production acceptance must not run in a candidate lab variant.",
            BuildConfig.CANDIDATE_MODEL_LAB,
        )

        val manifest = PinnedModelManifest.value
        assertTrue("The pinned artifact must declare image input.", manifest.supportsImageInput)
        assertTrue("The pinned artifact must declare audio input.", manifest.supportsAudioInput)

        val installed = withTimeout(MODEL_INSPECT_TIMEOUT_MILLIS) {
            ModelArtifactStore(instrumentation.targetContext).inspect()
        }
        val model = (installed as? InstalledModelState.Ready)?.model
            ?: error("The production-pinned model is not verified: $installed")

        val backend = when (arguments.getString(BACKEND_ARGUMENT)?.lowercase()) {
            "gpu" -> InferenceBackend.GPU
            else -> InferenceBackend.CPU
        }

        val report = StringBuilder()
        try {
            report.appendLine("backend.requested=$backend")

            // Each observation gets a fresh runtime and exactly one top-level conversation. All
            // four runtimes load the same two encoders, including the controls, so initialization
            // configuration cannot explain a token difference. An earlier test subtracted totals
            // observed after sequential turns; that could confuse accumulated state with media.
            val imagePlan = TurnMediaPolicy
                .planOrNull(TurnMediaKind.IMAGE, IMAGE_QUESTION, TurnMediaIntent.IMAGE_TEXT_EXTRACT)!!
            val imageOutputTokens = minOf(
                imagePlan.maxOutputTokens,
                ACCEPTANCE_OUTPUT_TOKEN_CEILING,
            )
            val image = requireNotNull(TurnMediaAttachment.imageOrNull(syntheticCardJpeg())) {
                "The synthetic card was not accepted as an attachment."
            }
            report.appendLine("image.byteCount=${image.byteCount}")
            report.appendLine("image.maxOutputTokens=$imageOutputTokens")

            val imageControl = runFreshTurn(
                model = model,
                backend = backend,
                turnId = TurnId("turn-media-acceptance-image-control"),
                prompt = imagePlan.prompt,
                maxOutputTokens = imageOutputTokens,
                media = emptyList(),
            )
            report.appendLine(imageControl.render("imageControl"))
            val imageTurn = runFreshTurn(
                model = model,
                backend = backend,
                turnId = TurnId("turn-media-acceptance-image"),
                prompt = imagePlan.prompt,
                maxOutputTokens = imageOutputTokens,
                media = listOf(image),
            )
            report.appendLine(imageTurn.render("image"))
            report.appendLine("image.containsRenderedDigits=${CARD_DIGITS in imageTurn.turn.text}")

            // A tone, not speech: this measures that the audio encoder ran and what it cost, and
            // deliberately claims nothing about transcription quality. The clip is long enough
            // that its cost cannot be confused with ordinary response-length variation.
            val audioPlan = TurnMediaPolicy
                .planOrNull(TurnMediaKind.AUDIO, "", TurnMediaIntent.AUDIO_QUESTION)!!
            val audioOutputTokens = minOf(
                audioPlan.maxOutputTokens,
                ACCEPTANCE_OUTPUT_TOKEN_CEILING,
            )
            val audio = requireNotNull(
                TurnMediaAttachment.audioOrNull(
                    bytes = requireNotNull(WavEncoder.encodeOrNull(syntheticTonePcm(CLIP_MILLIS))),
                    durationMillis = CLIP_MILLIS,
                ),
            ) { "The synthetic clip was not accepted as an attachment." }
            report.appendLine("audio.byteCount=${audio.byteCount}")
            report.appendLine("audio.clipMillis=$CLIP_MILLIS")
            report.appendLine("audio.maxOutputTokens=$audioOutputTokens")

            val audioControl = runFreshTurn(
                model = model,
                backend = backend,
                turnId = TurnId("turn-media-acceptance-audio-control"),
                prompt = audioPlan.prompt,
                maxOutputTokens = audioOutputTokens,
                media = emptyList(),
            )
            report.appendLine(audioControl.render("audioControl"))
            val audioTurn = runFreshTurn(
                model = model,
                backend = backend,
                turnId = TurnId("turn-media-acceptance-audio"),
                prompt = audioPlan.prompt,
                maxOutputTokens = audioOutputTokens,
                media = listOf(audio),
            )
            report.appendLine(audioTurn.render("audio"))

            report.appendLine("documented.imageContextTokens=${TurnMediaBudget.DOCUMENTED_IMAGE_CONTEXT_TOKENS}")
            report.appendLine(
                "documented.audioContextTokens=" +
                    TurnMediaBudget.documentedContextTokens(listOf(audio)),
            )
            report.appendLine("post.pssBytes=${Debug.getPss() * 1_024L}")

            val activeBackends = listOf(
                imageControl,
                imageTurn,
                audioControl,
                audioTurn,
            ).map(FreshTurnObservation::activeBackend).toSet()
            assertEquals(
                "Paired runs did not use one consistent active backend.",
                1,
                activeBackends.size,
            )

            // Nothing here may reach a Tool: an attachment can carry text that reads like an
            // instruction, and the empty scope is the whole reason that is harmless.
            assertTrue(
                "A media turn must never produce a Tool call.",
                imageTurn.turn.toolCallCount == 0 && audioTurn.turn.toolCallCount == 0,
            )
            assertTrue("The image turn produced no answer.", imageTurn.turn.text.isNotBlank())
            assertTrue("The audio turn produced no answer.", audioTurn.turn.text.isNotBlank())

            // Compare per-turn increments from separate fresh conversations. Raw after-turn totals
            // are not comparable if a runtime has retained any earlier conversation state.
            val imageDelta = requireNotNull(imageTurn.turn.contextTokenIncrement) {
                "The image turn reported no token increment."
            } - requireNotNull(imageControl.turn.contextTokenIncrement) {
                "The image control turn reported no token increment."
            }
            val audioDelta = requireNotNull(audioTurn.turn.contextTokenIncrement) {
                "The audio turn reported no token increment."
            } - requireNotNull(audioControl.turn.contextTokenIncrement) {
                "The audio control turn reported no token increment."
            }
            report.appendLine("measured.imageContextDelta=$imageDelta")
            report.appendLine("measured.audioContextDelta=$audioDelta")

            assertTrue(
                "An image prefill added only $imageDelta context tokens over its control turn, " +
                    "which is too small for the vision encoder to have run.",
                imageDelta >= MINIMUM_IMAGE_CONTEXT_DELTA,
            )
            assertTrue(
                "An audio prefill added only $audioDelta context tokens over its control turn, " +
                    "which is too small for the audio encoder to have run.",
                audioDelta >= MINIMUM_AUDIO_CONTEXT_DELTA,
            )
        } finally {
            // The report is the receipt; it carries counts and timings, never model text.
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "\n$report")
            })
        }
    }

    private data class FreshTurnObservation(
        val turn: TurnObservation,
        val activeBackend: InferenceBackend,
        val initializeMillis: Long,
    ) {
        fun render(label: String): String = buildString {
            appendLine("$label.backend.active=$activeBackend")
            appendLine("$label.initializeMillis=$initializeMillis")
            append(turn.render(label))
        }
    }

    private data class TurnObservation(
        val text: String,
        val toolCallCount: Int,
        val contextTokensBefore: Int?,
        val contextTokensAfter: Int?,
        val contextTokenIncrement: Int?,
        val ttftMillis: Long?,
        val durationMillis: Long,
    ) {
        /** Counts and timings only. The answer body is never printed into the receipt. */
        fun render(label: String): String = buildString {
            appendLine("$label.durationMillis=$durationMillis")
            appendLine("$label.ttftMillis=${ttftMillis ?: -1}")
            appendLine("$label.contextTokensBefore=${contextTokensBefore ?: -1}")
            appendLine("$label.contextTokensAfter=${contextTokensAfter ?: -1}")
            appendLine("$label.contextTokenIncrement=${contextTokenIncrement ?: -1}")
            appendLine("$label.answerCodePoints=${text.codePointCount(0, text.length)}")
            append("$label.toolCallCount=$toolCallCount")
        }
    }

    private suspend fun runFreshTurn(
        model: VerifiedInstalledModel,
        backend: InferenceBackend,
        turnId: TurnId,
        prompt: String,
        maxOutputTokens: Int,
        media: List<TurnMediaAttachment>,
    ): FreshTurnObservation {
        val runtime = LiteRtLlmRuntime(instrumentation.targetContext)
        try {
            val initializeStarted = SystemClock.elapsedRealtime()
            // Controls load the same encoders as media turns. The comparison is the input to the
            // encoder, not a media-enabled engine versus a cheaper text-only engine.
            runtime.initialize(
                model = model,
                backend = backend,
                mediaModalities = setOf(TurnMediaKind.IMAGE, TurnMediaKind.AUDIO),
            )
            val initializeMillis = SystemClock.elapsedRealtime() - initializeStarted
            val ready = runtime.state.value as? LlmState.Ready
                ?: error("Fresh runtime did not become ready: ${runtime.state.value}")
            return FreshTurnObservation(
                turn = runTurn(runtime, turnId, prompt, maxOutputTokens, media),
                activeBackend = ready.backend,
                initializeMillis = initializeMillis,
            )
        } finally {
            runtime.close()
        }
    }

    private suspend fun runTurn(
        runtime: LiteRtLlmRuntime,
        turnId: TurnId,
        prompt: String,
        maxOutputTokens: Int,
        media: List<TurnMediaAttachment>,
    ): TurnObservation {
        val contextTokensBefore = runtime.contextTokenCount()
        val startedAt = SystemClock.elapsedRealtime()
        var ttftMillis: Long? = null
        val answer = StringBuilder()
        var toolCallCount = 0

        val events = withTimeout(TURN_TIMEOUT_MILLIS) {
            runtime
                .streamUserTurn(turnId, prompt, maxOutputTokens, LlmTurnToolScope.none(), media)
                .onEach { event ->
                    when (event) {
                        is ModelEvent.TextDelta -> {
                            if (ttftMillis == null && event.text.isNotEmpty()) {
                                ttftMillis = SystemClock.elapsedRealtime() - startedAt
                            }
                            answer.append(event.text)
                        }

                        is ModelEvent.FinalToolCalls -> toolCallCount += event.toolCalls.size
                        is ModelEvent.Failure -> error("Turn ${turnId.value} failed: ${event.code}")
                        else -> Unit
                    }
                }
                .toList()
        }
        assertNotNull(
            "Turn ${turnId.value} never completed.",
            events.lastOrNull { it is ModelEvent.Completed },
        )
        val durationMillis = SystemClock.elapsedRealtime() - startedAt
        val contextTokensAfter = runtime.contextTokenCount()
        val contextTokenIncrement = if (
            contextTokensBefore != null && contextTokensAfter != null
        ) {
            contextTokensAfter - contextTokensBefore
        } else {
            null
        }
        assertTrue(
            "A fresh one-turn conversation reported a negative token increment.",
            contextTokenIncrement == null || contextTokenIncrement >= 0,
        )
        return TurnObservation(
            text = answer.toString(),
            toolCallCount = toolCallCount,
            contextTokensBefore = contextTokensBefore,
            contextTokensAfter = contextTokensAfter,
            contextTokenIncrement = contextTokenIncrement,
            ttftMillis = ttftMillis,
            durationMillis = durationMillis,
        )
    }

    /** A high-contrast card whose digits a working vision encoder has a fair chance of reading. */
    private fun syntheticCardJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(768, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 180f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(CARD_DIGITS, 384f, 320f, paint)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    /** A tone, not speech. It exercises the encoder without asserting anything about transcription. */
    private fun syntheticTonePcm(millis: Int): ByteArray {
        val bytes = ByteArray(VoiceCapturePolicy.pcmByteCountFor(millis))
        val frames = bytes.size / 2
        for (index in 0 until frames) {
            val sample = (8_000 * sin(index * 2.0 * Math.PI * 440.0 / VoiceCapturePolicy.SAMPLE_RATE_HZ)).toInt()
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun isTargetFold8(): Boolean =
        Build.MODEL == EXPECTED_MODEL && Build.VERSION.SDK_INT == EXPECTED_API

    private companion object {
        const val OPT_IN_ARGUMENT = "liveMediaTurn"
        const val BACKEND_ARGUMENT = "mediaBackend"
        const val EXPECTED_MODEL = "SM-F971N"
        const val EXPECTED_API = 37
        const val CARD_DIGITS = "482913"
        const val CLIP_MILLIS = 15_000
        const val IMAGE_QUESTION = "사진에 적힌 숫자를 그대로 옮겨 적어 줘."
        const val ACCEPTANCE_OUTPUT_TOKEN_CEILING = 32
        const val MODEL_INSPECT_TIMEOUT_MILLIS = 300_000L
        const val TURN_TIMEOUT_MILLIS = 300_000L

        /**
         * Well under the documented 256 tokens per image, because the point is to separate "the
         * encoder ran" from "the model answered without it", not to re-derive the published cost.
         */
        const val MINIMUM_IMAGE_CONTEXT_DELTA = 100

        /**
         * Fifteen seconds is 94 tokens at the Gemma 3n rate and 375 at the Gemma 4 rate, so this
         * floor remains above the 32-token maximum difference between the paired answers.
         */
        const val MINIMUM_AUDIO_CONTEXT_DELTA = 60
    }
}
