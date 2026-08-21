package com.personaledge.agent

import android.app.Application
import com.personaledge.core.diagnostics.DiagnosticEvent
import com.personaledge.core.diagnostics.DiagnosticPhase
import com.personaledge.core.diagnostics.DiagnosticRecorder
import com.personaledge.core.diagnostics.DiagnosticSink
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PersonalEdgeApplication : Application() {
    private val diagnosticExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "personal-edge-diagnostics").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private lateinit var recorder: DiagnosticRecorder
    private lateinit var channel: AppDiagnosticChannel

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
        // Previous low-memory, crash and ANR exits are available only after a new start.
        channel.recordHistoricalExits()
        channel.recordResourceSnapshot()
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
