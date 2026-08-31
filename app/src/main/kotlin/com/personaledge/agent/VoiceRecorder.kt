package com.personaledge.agent

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.personaledge.core.llm.TurnMediaAttachment
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal sealed interface VoiceRecordingResult {
    data class Captured(
        val attachment: TurnMediaAttachment,
        val durationMillis: Int,
    ) : VoiceRecordingResult

    /** The owner stopped before enough audio existed to be worth a full multimodal prefill. */
    data object TooShort : VoiceRecordingResult

    /** Audio arrived but carried no speech-level signal. */
    data object Silent : VoiceRecordingResult

    /** The microphone could not be opened or read. */
    data object Unavailable : VoiceRecordingResult
}

/**
 * Records straight into the shape the audio encoder wants: 16 kHz, mono, 16-bit PCM.
 *
 * `AudioRecord` rather than `MediaRecorder` because the model's input is uncompressed mono at a
 * fixed rate. Going through a container codec would mean encoding, then having the native
 * decoder undo it, with a resample on each side; reading raw frames avoids both and makes the
 * clip's length exactly what was recorded rather than what a muxer reported.
 *
 * The recording is capped by [VoiceCapturePolicy.MAX_MILLIS] inside the read loop, so a caller
 * that forgets to stop still cannot grow the buffer without bound.
 */
internal class VoiceRecorder {
    /**
     * Records until [shouldContinue] returns false, the cap is reached, or the coroutine is
     * cancelled. [onElapsed] reports whole milliseconds so the UI can show a live timer.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(
        shouldContinue: () -> Boolean,
        onElapsed: (Int) -> Unit,
    ): VoiceRecordingResult = withContext(Dispatchers.IO) {
        val minimumBuffer = try {
            AudioRecord.getMinBufferSize(
                VoiceCapturePolicy.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } catch (_: Exception) {
            AudioRecord.ERROR
        }
        if (minimumBuffer <= 0) return@withContext VoiceRecordingResult.Unavailable

        val bufferBytes = maxOf(minimumBuffer * 2, READ_CHUNK_BYTES * 2)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                VoiceCapturePolicy.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (_: SecurityException) {
            return@withContext VoiceRecordingResult.Unavailable
        } catch (_: Exception) {
            return@withContext VoiceRecordingResult.Unavailable
        }

        val captured = ByteArrayOutputStream()
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext VoiceRecordingResult.Unavailable
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@withContext VoiceRecordingResult.Unavailable
            }

            val maxBytes = VoiceCapturePolicy.maxPcmByteCount()
            val chunk = ByteArray(READ_CHUNK_BYTES)
            var lastReportedMillis = -1
            while (currentCoroutineContext().isActive && shouldContinue()) {
                if (captured.size() >= maxBytes) break
                val remaining = maxBytes - captured.size()
                val read = recorder.read(chunk, 0, minOf(chunk.size, remaining))
                if (read <= 0) {
                    // ERROR_INVALID_OPERATION and friends are terminal for this session.
                    if (read < 0) return@withContext VoiceRecordingResult.Unavailable
                    continue
                }
                captured.write(chunk, 0, read)
                val elapsed = VoiceCapturePolicy.durationMillis(captured.size())
                if (elapsed / PROGRESS_STEP_MILLIS != lastReportedMillis / PROGRESS_STEP_MILLIS) {
                    lastReportedMillis = elapsed
                    onElapsed(elapsed)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext VoiceRecordingResult.Unavailable
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (_: Exception) {
                // Already stopped or never started; release is what actually matters.
            }
            try {
                recorder.release()
            } catch (_: Exception) {
                // Nothing further to do with a handle the platform refuses to release.
            }
        }

        val pcm = captured.toByteArray()
        val durationMillis = VoiceCapturePolicy.durationMillis(pcm.size)
        if (durationMillis < VoiceCapturePolicy.MIN_MILLIS) return@withContext VoiceRecordingResult.TooShort
        // Near-silence reliably produces a confident invented transcript, so refuse it here
        // instead of spending a multimodal prefill on it.
        if (VoiceCapturePolicy.isLikelySilent(pcm)) return@withContext VoiceRecordingResult.Silent

        val wav = WavEncoder.encodeOrNull(pcm) ?: return@withContext VoiceRecordingResult.Unavailable
        val attachment = TurnMediaAttachment.audioOrNull(wav, durationMillis)
            ?: return@withContext VoiceRecordingResult.Unavailable
        VoiceRecordingResult.Captured(attachment, durationMillis)
    }

    private companion object {
        const val READ_CHUNK_BYTES = 4_096
        const val PROGRESS_STEP_MILLIS = 100
    }
}
