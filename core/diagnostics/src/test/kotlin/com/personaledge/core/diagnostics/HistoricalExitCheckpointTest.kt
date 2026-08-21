package com.personaledge.core.diagnostics

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoricalExitCheckpointTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `clean restart does not record the same historical exits twice`() {
        val paths = newPaths()
        val fileSystem = TestSecureDiagnosticsFileSystem(paths)
        val exits = listOf(
            exit(timestamp = 200, reason = DiagnosticExitReason.LOW_MEMORY),
            exit(timestamp = 100, reason = DiagnosticExitReason.CRASH),
        )
        val providers = providers(exits)

        val firstRecorder = DiagnosticRecorder.createForTest(paths, fileSystem, providers)
        assertEquals(2, firstRecorder.recordHistoricalExits())

        val restartedRecorder = DiagnosticRecorder.createForTest(paths, fileSystem, providers)
        assertEquals(0, restartedRecorder.recordHistoricalExits())
        assertEquals(2, allLogLines(paths).size)
        assertTrue(paths.checkpoint.isFile)
        assertFalse(paths.checkpoint.readText().contains("low_memory"))
    }

    @Test
    fun `same timestamp uses stable identity to preserve distinct records once`() {
        val paths = newPaths()
        val fileSystem = TestSecureDiagnosticsFileSystem(paths)
        val exits = listOf(
            exit(timestamp = 300, reason = DiagnosticExitReason.PACKAGE_UPDATED),
            exit(timestamp = 300, reason = DiagnosticExitReason.USER_REQUESTED),
        )
        val recorder = DiagnosticRecorder.createForTest(paths, fileSystem, providers(exits))

        assertEquals(2, recorder.recordHistoricalExits())
        assertEquals(0, recorder.recordHistoricalExits())
        assertEquals(2, allLogLines(paths).size)
    }

    @Test
    fun `corrupt checkpoint is fail open and repaired within log caps`() {
        val paths = newPaths()
        val fileSystem = TestSecureDiagnosticsFileSystem(paths)
        val recorder = DiagnosticRecorder.createForTest(
            paths = paths,
            fileSystem = fileSystem,
            providers = providers(listOf(exit(400, DiagnosticExitReason.CRASH_NATIVE))),
            maxFileBytes = 512,
        )
        assertEquals(1, recorder.recordHistoricalExits())
        paths.checkpoint.writeText("not-a-checkpoint\nrecipient-message-secret")

        assertEquals(1, recorder.recordHistoricalExits())
        assertTrue(logFiles(paths).all { it.length() <= 512 })
        assertFalse(paths.checkpoint.readText().contains("recipient-message-secret"))
    }

    @Test
    fun `history count is bounded before invoking provider`() {
        val calls = AtomicInteger()
        val paths = newPaths()
        val fileSystem = TestSecureDiagnosticsFileSystem(paths)
        val providers = DiagnosticProviders(
            historicalExits = HistoricalExitProvider {
                calls.incrementAndGet()
                emptyList()
            },
            resources = ResourceSnapshotProvider {
                ResourceMetrics(0, 0, DiagnosticThermalStatus.UNKNOWN)
            },
            phaseSummary = ProcessStateSummaryWriter {},
        )
        val recorder = DiagnosticRecorder.createForTest(paths, fileSystem, providers)

        assertEquals(0, recorder.recordHistoricalExits(0))
        assertEquals(0, recorder.recordHistoricalExits(33))
        assertEquals(0, calls.get())
    }

    @Test
    fun `checkpoint failure prevents append and therefore restart duplicates`() {
        val paths = newPaths()
        val delegate = TestSecureDiagnosticsFileSystem(paths)
        val failingCheckpointFileSystem = object : SecureDiagnosticsFileSystem by delegate {
            override fun atomicReplace(
                target: java.io.File,
                temporary: java.io.File,
                bytes: ByteArray,
                maxBytes: Int,
            ) {
                throw java.io.IOException("checkpoint unavailable")
            }
        }
        val recorder = DiagnosticRecorder.createForTest(
            paths,
            failingCheckpointFileSystem,
            providers(listOf(exit(500, DiagnosticExitReason.LOW_MEMORY))),
        )

        assertEquals(0, recorder.recordHistoricalExits())
        assertTrue(allLogLines(paths).isEmpty())
    }

    @Test
    fun `phase summary codec round trips allowlist and rejects unknown bytes`() {
        DiagnosticPhase.entries.forEach { phase ->
            assertEquals(phase, DiagnosticPhaseCodec.decode(DiagnosticPhaseCodec.encode(phase)))
            assertTrue(DiagnosticPhaseCodec.encode(phase).size <= 128)
        }
        assertEquals(null, DiagnosticPhaseCodec.decode(byteArrayOf(99, 1)))
        assertEquals(null, DiagnosticPhaseCodec.decode(byteArrayOf(1)))
        assertEquals(null, DiagnosticPhaseCodec.decode(null))
    }

    private fun providers(exits: List<HistoricalExitRecord>) = DiagnosticProviders(
        historicalExits = HistoricalExitProvider { exits },
        resources = ResourceSnapshotProvider {
            ResourceMetrics(0, 0, DiagnosticThermalStatus.UNKNOWN)
        },
        phaseSummary = ProcessStateSummaryWriter {},
    )

    private fun exit(
        timestamp: Long,
        reason: DiagnosticExitReason,
    ) = HistoricalExitRecord(
        reason = reason,
        status = 9,
        importance = 100,
        pssBytes = 1024,
        rssBytes = 2048,
        timestampMillis = timestamp,
        phase = DiagnosticPhase.TURN_PROCESSING,
    )

    private fun newPaths() = DiagnosticsPaths(temporaryFolder.newFolder().canonicalFile)

    private fun logFiles(paths: DiagnosticsPaths) =
        (listOf(paths.activeLog) + paths.archiveLogs).filter { it.isFile }

    private fun allLogLines(paths: DiagnosticsPaths) = logFiles(paths).flatMap { it.readLines() }
}
