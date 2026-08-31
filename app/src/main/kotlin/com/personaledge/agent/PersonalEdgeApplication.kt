package com.personaledge.agent

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.personaledge.core.diagnostics.DiagnosticEvent
import com.personaledge.core.diagnostics.DiagnosticExportResult
import com.personaledge.core.diagnostics.DiagnosticPhase
import com.personaledge.core.diagnostics.DiagnosticRecorder
import com.personaledge.core.diagnostics.DiagnosticSink
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.OutputStream

class PersonalEdgeApplication : Application() {
    private val diagnosticExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "personal-edge-diagnostics").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private lateinit var recorder: DiagnosticRecorder
    private lateinit var channel: AppDiagnosticChannel
    private var calendarObserver: ContentObserver? = null

    /** Created eagerly but resolved lazily; see [AppContainer]. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        recorder = runCatching { DiagnosticRecorder.create(this) }
            .getOrElse { DiagnosticRecorder.noOp() }
        channel = AppDiagnosticChannel(recorder, diagnosticExecutor)

        channel.markPhase(DiagnosticPhase.PROCESS_START)
        channel.recordSafely(DiagnosticEvent.ProcessStarted)
        channel.markPhase(DiagnosticPhase.SESSION_START)
        channel.recordSafely(DiagnosticEvent.SessionStarted)
        channel.markPhase(DiagnosticPhase.IDLE)
        // A camera process can outlive this process and leave its one staging file behind. Sweep
        // only the fixed private cache directory, off the main thread, before any new capture is
        // prepared. Payload bytes and paths never enter diagnostics.
        runCatching {
            diagnosticExecutor.execute {
                MediaCaptureStaging(this@PersonalEdgeApplication).sweepProcessStartLeftovers()
            }
        }
        // Previous low-memory, crash and ANR exits are available only after a new start.
        channel.recordHistoricalExits()
        channel.recordResourceSnapshot()
        if (CandidateProcessPolicy.providerIntegrationsEnabled(BuildConfig.CANDIDATE_MODEL_LAB)) {
            ReminderWorkBootstrap.start(this)
            ensureCalendarReconciliationObserver()
        }
    }

    /** Debounces live CalendarContract edits into the deterministic leave-by worker. */
    fun ensureCalendarReconciliationObserver() {
        if (!CandidateProcessPolicy.providerIntegrationsEnabled(BuildConfig.CANDIDATE_MODEL_LAB) ||
            calendarObserver != null ||
            checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED
        ) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                ReminderWorkBootstrap.enqueueLeaveByReconcile(this@PersonalEdgeApplication)
            }
        }
        if (runCatching {
                contentResolver.registerContentObserver(
                    CalendarContract.Events.CONTENT_URI,
                    true,
                    observer,
                )
            }.isSuccess
        ) {
            calendarObserver = observer
        }
    }

    internal fun diagnosticChannel(): AppDiagnosticChannel =
        if (::channel.isInitialized) channel else NO_OP_DIAGNOSTICS

    companion object {
        private val DIRECT_EXECUTOR = Executor(Runnable::run)
        private val NO_OP_DIAGNOSTICS = AppDiagnosticChannel(
            recorder = DiagnosticRecorder.noOp(),
            executor = DIRECT_EXECUTOR,
        )
    }
}

/**
 * Serializes fsync and memory sampling away from the main thread. Phase markers stay synchronous
 * so a native crash is attributed to the operation that was actually in progress.
 */
internal class AppDiagnosticChannel(
    private val recorder: DiagnosticRecorder,
    private val executor: Executor,
) : DiagnosticSink {
    override fun record(event: DiagnosticEvent): Boolean = submit {
        recorder.record(event)
    }

    fun markPhase(phase: DiagnosticPhase): Boolean =
        runCatching { recorder.markPhase(phase) }.getOrDefault(false)

    fun recordHistoricalExits(): Boolean = submit {
        recorder.recordHistoricalExits()
    }

    fun recordResourceSnapshot(): Boolean = submit {
        recorder.recordResourceSnapshot()
    }

    /**
     * Queued on the same executor as records, so everything accepted before this call is present.
     * The destination is opened and closed here; a slow SAF provider never runs on the main thread.
     */
    fun exportContentFreeJsonl(
        openDestination: () -> OutputStream?,
        onComplete: (DiagnosticExportResult) -> Unit,
    ): Boolean = try {
        executor.execute {
            val result = try {
                val destination = openDestination()
                if (destination == null) {
                    DiagnosticExportResult.DestinationFailed
                } else {
                    destination.use(recorder::exportContentFreeJsonl)
                }
            } catch (_: Throwable) {
                DiagnosticExportResult.DestinationFailed
            }
            runCatching { onComplete(result) }
        }
        true
    } catch (_: Throwable) {
        false
    }

    private fun submit(block: () -> Unit): Boolean = try {
        executor.execute { runCatching(block) }
        true
    } catch (_: Throwable) {
        false
    }
}

internal fun Application.personalEdgeDiagnostics(): AppDiagnosticChannel =
    (this as? PersonalEdgeApplication)?.diagnosticChannel()
        ?: AppDiagnosticChannel(DiagnosticRecorder.noOp(), Executor(Runnable::run))

/** Keeps the isolated candidate process from starting provider-backed app integrations. */
internal object CandidateProcessPolicy {
    fun providerIntegrationsEnabled(candidateModelLab: Boolean): Boolean = !candidateModelLab
}
