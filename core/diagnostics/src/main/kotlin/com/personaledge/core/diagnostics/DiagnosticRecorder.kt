package com.personaledge.core.diagnostics

import android.content.Context
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DiagnosticRecorder private constructor(
    private val store: RotatingJsonlStore?,
    private val checkpointStore: ExitCheckpointStore?,
    private val providers: DiagnosticProviders?,
    private val currentTimeMillis: () -> Long,
    private val mutex: ReentrantLock,
) : DiagnosticSink {
    private var lastMarkedPhase: DiagnosticPhase? = null

    override fun record(event: DiagnosticEvent): Boolean = failOpen(false) {
        mutex.withLock { recordLocked(event) }
    }

    fun markPhase(phase: DiagnosticPhase): Boolean = failOpen(false) {
        mutex.withLock {
            val writer = providers?.phaseSummary ?: return@withLock false
            if (lastMarkedPhase == phase) return@withLock true
            writer.mark(phase)
            lastMarkedPhase = phase
            true
        }
    }

    fun recordHistoricalExits(maxCount: Int = DEFAULT_HISTORICAL_EXIT_COUNT): Int {
        if (maxCount !in 1..MAX_HISTORICAL_EXIT_RECORDS) return 0
        return failOpen(0) {
            mutex.withLock {
                val exitProvider = providers?.historicalExits ?: return@withLock 0
                val checkpoints = checkpointStore ?: return@withLock 0
                var checkpoint = checkpoints.load()
                var recordedCount = 0
                exitProvider.load(maxCount)
                    .take(maxCount)
                    .sortedWith(
                        compareBy<HistoricalExitRecord> { it.timestampMillis }
                            .thenBy(HistoricalExitRecord::stableIdentitySha256),
                    )
                    .forEach { exit ->
                        if (!checkpoint.contains(exit)) {
                            val encoded = encode(DiagnosticEvent.HistoricalExit(exit))
                                ?: return@withLock recordedCount
                            val nextCheckpoint = checkpoint.advance(exit)
                            // Persist first: after a clean restart an exit is at-most-once even if
                            // the following fail-open log append cannot be completed.
                            checkpoints.save(nextCheckpoint)
                            checkpoint = nextCheckpoint
                            if (!appendEncoded(encoded)) return@withLock recordedCount
                            recordedCount += 1
                        }
                    }
                recordedCount
            }
        }
    }

    fun recordResourceSnapshot(): Boolean = failOpen(false) {
        val snapshotProvider = providers?.resources ?: return@failOpen false
        record(DiagnosticEvent.ResourceSnapshot(snapshotProvider.snapshot()))
    }

    private fun recordLocked(event: DiagnosticEvent): Boolean {
        val encoded = encode(event) ?: return false
        return appendEncoded(encoded)
    }

    private fun encode(event: DiagnosticEvent): String? =
        DiagnosticJsonEncoder.encode(event, currentTimeMillis())

    private fun appendEncoded(encoded: String): Boolean = store?.append(encoded) ?: false

    companion object {
        fun create(context: Context): DiagnosticRecorder {
            return failOpen(noOp()) {
                val applicationContext = context.applicationContext ?: context
                create(
                    context = applicationContext,
                    providers = DiagnosticProviders(
                        historicalExits = Api31HistoricalExitProvider(applicationContext),
                        resources = Api31ResourceSnapshotProvider(applicationContext),
                        phaseSummary = Api31ProcessStateSummaryWriter(applicationContext),
                    ),
                )
            }
        }

        fun create(
            context: Context,
            providers: DiagnosticProviders,
        ): DiagnosticRecorder = failOpen(noOp()) {
            val applicationContext = context.applicationContext ?: context
            val root = applicationContext.noBackupFilesDir.canonicalFile
            val paths = DiagnosticsPaths(root)
            val fileSystem = AndroidSecureDiagnosticsFileSystem(paths)
            DiagnosticRecorder(
                store = RotatingJsonlStore(paths, fileSystem),
                checkpointStore = ExitCheckpointStore(paths, fileSystem),
                providers = providers,
                currentTimeMillis = System::currentTimeMillis,
                mutex = PROCESS_MUTEX,
            )
        }

        fun noOp(): DiagnosticRecorder = DiagnosticRecorder(
            store = null,
            checkpointStore = null,
            providers = null,
            currentTimeMillis = { 0 },
            mutex = PROCESS_MUTEX,
        )

        internal fun createForTest(
            paths: DiagnosticsPaths,
            fileSystem: SecureDiagnosticsFileSystem,
            providers: DiagnosticProviders? = null,
            maxFileBytes: Long = RotatingJsonlStore.DEFAULT_MAX_FILE_BYTES,
            currentTimeMillis: () -> Long = { 1 },
            mutex: ReentrantLock = ReentrantLock(),
        ): DiagnosticRecorder = DiagnosticRecorder(
            store = RotatingJsonlStore(paths, fileSystem, maxFileBytes),
            checkpointStore = ExitCheckpointStore(paths, fileSystem),
            providers = providers,
            currentTimeMillis = currentTimeMillis,
            mutex = mutex,
        )

        private val PROCESS_MUTEX = ReentrantLock()
        private const val DEFAULT_HISTORICAL_EXIT_COUNT = 16
    }
}

private inline fun <T> failOpen(fallback: T, block: () -> T): T = try {
    block()
} catch (_: Throwable) {
    fallback
}
