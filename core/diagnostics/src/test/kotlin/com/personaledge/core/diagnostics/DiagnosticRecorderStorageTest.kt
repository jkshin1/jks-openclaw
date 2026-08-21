package com.personaledge.core.diagnostics

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticRecorderStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `rotates active plus three archives and enforces every file cap`() {
        val fixture = fixture(maxFileBytes = 150)
        repeat(20) { assertTrue(fixture.recorder.record(DiagnosticEvent.ProcessStarted)) }

        val files = fixture.paths.logDirectory.listFiles().orEmpty().sortedBy { it.name }
        assertEquals(
            setOf(
                "diagnostics.jsonl",
                "diagnostics.1.jsonl",
                "diagnostics.2.jsonl",
                "diagnostics.3.jsonl",
            ),
            files.map { it.name }.toSet(),
        )
        assertTrue(files.all { it.length() in 1..150 })
        assertTrue(files.all { it.readBytes().last() == '\n'.code.toByte() })
    }

    @Test
    fun `single event larger than cap fails without creating a log`() {
        val fixture = fixture(maxFileBytes = 32)

        assertFalse(fixture.recorder.record(DiagnosticEvent.SessionStarted))
        assertFalse(fixture.paths.activeLog.exists())
    }

    @Test
    fun `preexisting oversized active or archive fails closed`() {
        listOf(0, 2).forEach { oversizedIndex ->
            val paths = newPaths()
            val fileSystem = TestSecureDiagnosticsFileSystem(paths)
            fileSystem.ensureLogDirectory()
            val oversized = if (oversizedIndex == 0) paths.activeLog else paths.archiveLogs[1]
            oversized.writeBytes(ByteArray(129) { 'x'.code.toByte() })
            val recorder = DiagnosticRecorder.createForTest(
                paths = paths,
                fileSystem = fileSystem,
                maxFileBytes = 128,
            )

            assertFalse(recorder.record(DiagnosticEvent.ProcessStarted))
            assertEquals(129, oversized.length())
            assertEquals(1, paths.logDirectory.listFiles().orEmpty().size)
        }
    }

    @Test
    fun `partial JSON tail is repaired before the next append`() {
        val paths = newPaths()
        val delegate = TestSecureDiagnosticsFileSystem(paths)
        var appendAttempt = 0
        val partialWriteFileSystem = object : SecureDiagnosticsFileSystem by delegate {
            override fun append(file: java.io.File, bytes: ByteArray, maxFileBytes: Long): AppendResult {
                appendAttempt += 1
                if (appendAttempt == 2) {
                    Files.write(
                        file.toPath(),
                        bytes.copyOfRange(0, 11),
                        StandardOpenOption.APPEND,
                    )
                    throw java.io.IOException("simulated ENOSPC after short write")
                }
                return delegate.append(file, bytes, maxFileBytes)
            }
        }
        val recorder = DiagnosticRecorder.createForTest(paths, partialWriteFileSystem)

        assertTrue(recorder.record(DiagnosticEvent.ProcessStarted))
        assertFalse(recorder.record(DiagnosticEvent.SessionStarted))
        assertTrue(recorder.record(DiagnosticEvent.SessionStarted))

        val text = paths.activeLog.readText()
        val lines = text.lines().filter(String::isNotEmpty)
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.startsWith('{') && it.endsWith('}') })
        assertFalse(text.contains("{\"schema_\n"))
    }

    @Test
    fun `concurrent appends produce complete noninterleaved JSON lines`() {
        val fixture = fixture(maxFileBytes = 1024 * 1024)
        val workers = 8
        val recordsPerWorker = 75
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures = (0 until workers).map {
                executor.submit<Boolean> {
                    start.await()
                    repeat(recordsPerWorker) {
                        if (!fixture.recorder.record(DiagnosticEvent.SessionStarted)) return@submit false
                    }
                    true
                }
            }
            start.countDown()
            assertTrue(futures.all { it.get(20, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }

        val lines = fixture.paths.activeLog.readLines()
        assertEquals(workers * recordsPerWorker, lines.size)
        assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") })
        assertTrue(lines.all { it.contains("\"event\":\"session_started\"") })
    }

    @Test
    fun `active symlink is rejected without touching its target`() {
        val paths = newPaths()
        val fileSystem = TestSecureDiagnosticsFileSystem(paths)
        fileSystem.ensureLogDirectory()
        val target = temporaryFolder.newFile("outside-target").apply { writeText("unchanged") }
        Files.createSymbolicLink(paths.activeLog.toPath(), target.toPath())
        val recorder = DiagnosticRecorder.createForTest(paths, fileSystem)

        assertFalse(recorder.record(DiagnosticEvent.ProcessStarted))
        assertEquals("unchanged", target.readText())
        assertTrue(Files.isSymbolicLink(paths.activeLog.toPath()))
    }

    @Test
    fun `hardlinked active file is rejected without modifying either link`() {
        val paths = newPaths()
        val fileSystem = TestSecureDiagnosticsFileSystem(paths)
        fileSystem.ensureLogDirectory()
        val target = temporaryFolder.newFile("outside-hardlink").apply { writeText("unchanged") }
        Files.createLink(paths.activeLog.toPath(), target.toPath())
        val recorder = DiagnosticRecorder.createForTest(paths, fileSystem)

        assertFalse(recorder.record(DiagnosticEvent.ProcessStarted))
        assertEquals("unchanged", target.readText())
        assertEquals("unchanged", paths.activeLog.readText())
    }

    @Test
    fun `nonregular active node is rejected`() {
        val paths = newPaths()
        val fileSystem = TestSecureDiagnosticsFileSystem(paths)
        fileSystem.ensureLogDirectory()
        assertTrue(paths.activeLog.mkdir())
        val recorder = DiagnosticRecorder.createForTest(paths, fileSystem)

        assertFalse(recorder.record(DiagnosticEvent.ProcessStarted))
        assertTrue(paths.activeLog.isDirectory)
    }

    @Test
    fun `rotation refuses archive symlink before moving or unlinking anything`() {
        val paths = newPaths()
        val fileSystem = TestSecureDiagnosticsFileSystem(paths)
        fileSystem.ensureLogDirectory()
        val encoded = requireNotNull(DiagnosticJsonEncoder.encode(DiagnosticEvent.ProcessStarted, 1))
        val exactLineBytes = encoded.toByteArray().size + 1L
        val target = temporaryFolder.newFile("outside-archive").apply { writeText("unchanged") }
        val recorder = DiagnosticRecorder.createForTest(
            paths = paths,
            fileSystem = fileSystem,
            maxFileBytes = exactLineBytes,
        )

        assertTrue(recorder.record(DiagnosticEvent.ProcessStarted))
        val activeBefore = paths.activeLog.readText()
        Files.createSymbolicLink(paths.archiveLogs.last().toPath(), target.toPath())
        assertFalse(recorder.record(DiagnosticEvent.ProcessStarted))
        assertEquals(activeBefore, paths.activeLog.readText())
        assertEquals("unchanged", target.readText())
        assertTrue(Files.isSymbolicLink(paths.archiveLogs.last().toPath()))
    }

    @Test
    fun `storage and provider failures are isolated`() {
        val paths = newPaths()
        val delegate = TestSecureDiagnosticsFileSystem(paths)
        val failingFileSystem = object : SecureDiagnosticsFileSystem by delegate {
            override fun append(file: java.io.File, bytes: ByteArray, maxFileBytes: Long): AppendResult {
                throw AssertionError("storage failure")
            }
        }
        val throwingProviders = DiagnosticProviders(
            historicalExits = HistoricalExitProvider { throw AssertionError("history failure") },
            resources = ResourceSnapshotProvider { throw AssertionError("resource failure") },
            phaseSummary = ProcessStateSummaryWriter { throw AssertionError("phase failure") },
        )
        val recorder = DiagnosticRecorder.createForTest(
            paths = paths,
            fileSystem = failingFileSystem,
            providers = throwingProviders,
        )

        assertFalse(recorder.record(DiagnosticEvent.ProcessStarted))
        assertFalse(recorder.markPhase(DiagnosticPhase.IDLE))
        assertFalse(recorder.recordResourceSnapshot())
        assertEquals(0, recorder.recordHistoricalExits())
    }

    @Test
    fun `no op recorder has stable fail open results`() {
        val recorder = DiagnosticRecorder.noOp()

        assertFalse(recorder.record(DiagnosticEvent.ProcessStarted))
        assertFalse(recorder.markPhase(DiagnosticPhase.IDLE))
        assertFalse(recorder.recordResourceSnapshot())
        assertEquals(0, recorder.recordHistoricalExits())
    }

    private fun fixture(maxFileBytes: Long): Fixture {
        val paths = newPaths()
        val fileSystem = TestSecureDiagnosticsFileSystem(paths)
        return Fixture(
            paths = paths,
            recorder = DiagnosticRecorder.createForTest(
                paths = paths,
                fileSystem = fileSystem,
                maxFileBytes = maxFileBytes,
            ),
        )
    }

    private fun newPaths(): DiagnosticsPaths = DiagnosticsPaths(
        temporaryFolder.newFolder().canonicalFile,
    )

    private data class Fixture(
        val paths: DiagnosticsPaths,
        val recorder: DiagnosticRecorder,
    )
}
