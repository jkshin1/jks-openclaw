package com.personaledge.core.diagnostics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticExportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `exports all retained rotations from oldest to active`() {
        val paths = newPaths()
        val clock = AtomicLong(1_000)
        val encoded = requireNotNull(
            DiagnosticJsonEncoder.encode(DiagnosticEvent.ProcessStarted, clock.get()),
        )
        val lineBytes = encoded.toByteArray(StandardCharsets.UTF_8).size + 1L
        val recorder = DiagnosticRecorder.createForTest(
            paths = paths,
            fileSystem = TestSecureDiagnosticsFileSystem(paths),
            maxFileBytes = lineBytes * 2,
            currentTimeMillis = clock::getAndIncrement,
        )
        repeat(10) { assertTrue(recorder.record(DiagnosticEvent.ProcessStarted)) }

        val destination = ByteArrayOutputStream()
        val result = recorder.exportContentFreeJsonl(destination)

        assertEquals(
            DiagnosticExportResult.Success(sourceFileCount = 4, byteCount = destination.size().toLong()),
            result,
        )
        val lines = destination.toString(StandardCharsets.UTF_8).lines().filter(String::isNotEmpty)
        assertEquals(8, lines.size)
        assertEquals(
            (1_002L..1_009L).toList(),
            lines.map { line ->
                RECORDED_AT.find(line)?.groupValues?.get(1)?.toLong()
            },
        )
        assertTrue(lines.all { it.contains("\"event\":\"process_started\"") })
    }

    @Test
    fun `exported failures never contain throwable messages`() {
        val fixture = fixture()
        val secret = "owner prompt and credential must not leave device"
        assertTrue(
            fixture.recorder.record(
                DiagnosticEvent.TurnFailed(
                    durationMillis = 10,
                    deltaCount = 1,
                    deltaByteCount = 2,
                    errorCode = DiagnosticErrorCode.MODEL_FAILURE,
                    failure = DiagnosticFailure.from(IllegalStateException(secret)),
                ),
            ),
        )

        val destination = ByteArrayOutputStream()
        assertTrue(fixture.recorder.exportContentFreeJsonl(destination) is DiagnosticExportResult.Success)

        val exported = destination.toString(StandardCharsets.UTF_8)
        assertFalse(exported.contains(secret))
        assertFalse(exported.contains("prompt and credential"))
        assertTrue(exported.contains("\"throwable_type\":\"java.lang.IllegalStateException\""))
        assertTrue(exported.contains("\"stack_fingerprint_sha256\":"))
    }

    @Test
    fun `malformed JSON is rejected before destination write`() {
        assertSourceRejected(
            "{\"schema_version\":1,\"recorded_at_ms\":1," +
                "\"event\":\"process_started\",}",
        )
    }

    @Test
    fun `nested JSON values are rejected before destination write`() {
        listOf(
            "{\"schema_version\":1,\"recorded_at_ms\":1," +
                "\"event\":{\"name\":\"process_started\"}}",
            "{\"schema_version\":1,\"recorded_at_ms\":1," +
                "\"event\":[\"process_started\"]}",
        ).forEach(::assertSourceRejected)
    }

    @Test
    fun `decoded duplicate JSON keys are rejected before destination write`() {
        assertSourceRejected(
            "{\"schema_version\":1,\"recorded_at_ms\":1," +
                "\"event\":\"process_started\",\"ev\\u0065nt\":\"process_started\"}",
        )
    }

    @Test
    fun `unexpected sensitive field is rejected before destination write`() {
        assertSourceRejected(
            "{\"schema_version\":1,\"recorded_at_ms\":1," +
                "\"event\":\"process_started\",\"prompt\":\"owner secret\"}",
        )
    }

    @Test
    fun `every typed writer event remains exportable byte for byte`() {
        val startedAt = 10_000L
        val clock = AtomicLong(startedAt)
        val fixture = fixture(currentTimeMillis = clock::getAndIncrement)
        val failure = DiagnosticFailure.from(IOException("private failure message"))
        val events = listOf(
            DiagnosticEvent.ProcessStarted,
            DiagnosticEvent.SessionStarted,
            DiagnosticEvent.HistoricalExit(
                HistoricalExitRecord(
                    reason = DiagnosticExitReason.PACKAGE_UPDATED,
                    status = -1,
                    importance = 100,
                    pssBytes = 1_024,
                    rssBytes = 2_048,
                    timestampMillis = 9_999,
                    phase = DiagnosticPhase.IDLE,
                ),
            ),
            DiagnosticEvent.ModelInspected(
                sizeBytes = 4_096,
                durationMillis = 3,
                result = DiagnosticResult.SUCCESS,
            ),
            DiagnosticEvent.ModelImported(
                sizeBytes = 4_096,
                durationMillis = 4,
                result = DiagnosticResult.FAILURE,
                errorCode = DiagnosticErrorCode.IMPORT_FAILED,
                failure = failure,
            ),
            DiagnosticEvent.RuntimeInitialized(
                requestedBackend = DiagnosticBackend.GPU,
                activeBackend = DiagnosticBackend.GPU,
                durationMillis = 5,
                result = DiagnosticResult.SUCCESS,
            ),
            DiagnosticEvent.TurnStarted(promptByteCount = 12),
            DiagnosticEvent.TurnFirstToken(ttftMillis = 6),
            DiagnosticEvent.TurnCompleted(
                durationMillis = 7,
                deltaCount = 2,
                deltaByteCount = 8,
            ),
            DiagnosticEvent.TurnCancelled(
                durationMillis = 9,
                deltaCount = 3,
                deltaByteCount = 10,
                cause = DiagnosticTurnCancellationCause.THERMAL,
                thermalStatus = DiagnosticThermalStatus.CRITICAL,
            ),
            DiagnosticEvent.TurnFailed(
                durationMillis = 11,
                deltaCount = 4,
                deltaByteCount = 12,
                errorCode = DiagnosticErrorCode.MODEL_FAILURE,
                failure = failure,
            ),
            DiagnosticEvent.ToolPhase(
                name = requireNotNull(DiagnosticToolName.parse("calendar_lookup")),
                stage = DiagnosticToolStage.EXECUTED,
                risk = DiagnosticToolRisk.READ_ONLY,
                confirmationOutcome = DiagnosticConfirmationOutcome.EXECUTED_SUCCESS,
            ),
            DiagnosticEvent.ResourceSnapshot(
                ResourceMetrics(
                    pssBytes = 13,
                    javaHeapBytes = 14,
                    thermalStatus = DiagnosticThermalStatus.SEVERE,
                ),
            ),
            DiagnosticEvent.ThermalGuard(
                status = DiagnosticThermalStatus.CRITICAL,
                action = DiagnosticThermalAction.COOPERATIVE_CANCEL_REQUESTED,
            ),
        )
        events.forEach { event -> assertTrue(recorderMessage(event), fixture.recorder.record(event)) }
        val expected = events.mapIndexed { index, event ->
            requireNotNull(DiagnosticJsonEncoder.encode(event, startedAt + index))
        }.joinToString(separator = "\n", postfix = "\n")

        val destination = ByteArrayOutputStream()
        val result = fixture.recorder.exportContentFreeJsonl(destination)

        assertEquals(
            DiagnosticExportResult.Success(
                sourceFileCount = 1,
                byteCount = expected.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            ),
            result,
        )
        assertArrayEquals(
            expected.toByteArray(StandardCharsets.UTF_8),
            destination.toByteArray(),
        )
    }

    @Test
    fun `symlink source rejects the whole snapshot before destination write`() {
        val fixture = fixture()
        assertTrue(fixture.recorder.record(DiagnosticEvent.ProcessStarted))
        val outside = temporaryFolder.newFile("outside-export").apply { writeText("secret") }
        Files.createSymbolicLink(fixture.paths.archiveLogs[1].toPath(), outside.toPath())
        val destination = sentinelDestination()

        val result = fixture.recorder.exportContentFreeJsonl(destination)

        assertEquals(DiagnosticExportResult.SourceRejected, result)
        assertArrayEquals(SENTINEL, destination.toByteArray())
        assertEquals("secret", outside.readText())
    }

    @Test
    fun `oversized source rejects the whole snapshot before destination write`() {
        val paths = newPaths()
        val recorder = DiagnosticRecorder.createForTest(
            paths = paths,
            fileSystem = TestSecureDiagnosticsFileSystem(paths),
            maxFileBytes = 128,
        )
        paths.activeLog.writeBytes(ByteArray(129) { 'x'.code.toByte() })
        val destination = sentinelDestination()

        val result = recorder.exportContentFreeJsonl(destination)

        assertEquals(DiagnosticExportResult.SourceRejected, result)
        assertArrayEquals(SENTINEL, destination.toByteArray())
    }

    @Test
    fun `unexpected directory child rejects the whole snapshot`() {
        val fixture = fixture()
        assertTrue(fixture.recorder.record(DiagnosticEvent.ProcessStarted))
        fixture.paths.logDirectory.resolve("diagnostics.unexpected.jsonl").writeText("secret")
        val destination = sentinelDestination()

        val result = fixture.recorder.exportContentFreeJsonl(destination)

        assertEquals(DiagnosticExportResult.SourceRejected, result)
        assertArrayEquals(SENTINEL, destination.toByteArray())
    }

    @Test
    fun `concurrent records and exports produce complete immutable snapshots`() {
        val fixture = fixture(maxFileBytes = 1024 * 1024)
        assertTrue(fixture.recorder.record(DiagnosticEvent.ProcessStarted))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)
        try {
            val writers = (0 until 4).map {
                executor.submit<Boolean> {
                    start.await()
                    repeat(50) {
                        if (!fixture.recorder.record(DiagnosticEvent.SessionStarted)) {
                            return@submit false
                        }
                    }
                    true
                }
            }
            val exporters = (0 until 2).map {
                executor.submit<Boolean> {
                    start.await()
                    repeat(20) {
                        val destination = ByteArrayOutputStream()
                        val result = fixture.recorder.exportContentFreeJsonl(destination)
                        if (result !is DiagnosticExportResult.Success) return@submit false
                        val bytes = destination.toByteArray()
                        if (bytes.isEmpty() || bytes.last() != '\n'.code.toByte()) {
                            return@submit false
                        }
                        if (destination.toString(StandardCharsets.UTF_8)
                                .lines()
                                .filter(String::isNotEmpty)
                                .any { line -> !line.startsWith("{") || !line.endsWith("}") }
                        ) {
                            return@submit false
                        }
                    }
                    true
                }
            }
            start.countDown()
            assertTrue((writers + exporters).all { it.get(20, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `destination failure is reported without closing caller stream`() {
        val fixture = fixture()
        assertTrue(fixture.recorder.record(DiagnosticEvent.ProcessStarted))
        var closed = false
        val destination = object : OutputStream() {
            override fun write(value: Int) {
                throw IOException("destination unavailable")
            }

            override fun close() {
                closed = true
            }
        }

        assertEquals(
            DiagnosticExportResult.DestinationFailed,
            fixture.recorder.exportContentFreeJsonl(destination),
        )
        assertFalse(closed)
    }

    @Test
    fun `no op recorder reports unavailable without writing`() {
        val destination = sentinelDestination()

        assertEquals(
            DiagnosticExportResult.Unavailable,
            DiagnosticRecorder.noOp().exportContentFreeJsonl(destination),
        )
        assertArrayEquals(SENTINEL, destination.toByteArray())
    }

    private fun fixture(
        maxFileBytes: Long = RotatingJsonlStore.DEFAULT_MAX_FILE_BYTES,
        currentTimeMillis: () -> Long = { 1 },
    ): Fixture {
        val paths = newPaths()
        return Fixture(
            paths = paths,
            recorder = DiagnosticRecorder.createForTest(
                paths = paths,
                fileSystem = TestSecureDiagnosticsFileSystem(paths),
                maxFileBytes = maxFileBytes,
                currentTimeMillis = currentTimeMillis,
            ),
        )
    }

    private fun assertSourceRejected(line: String) {
        val fixture = fixture()
        fixture.paths.activeLog.writeText("$line\n", StandardCharsets.UTF_8)
        val destination = sentinelDestination()

        assertEquals(
            DiagnosticExportResult.SourceRejected,
            fixture.recorder.exportContentFreeJsonl(destination),
        )
        assertArrayEquals(SENTINEL, destination.toByteArray())
    }

    private fun recorderMessage(event: DiagnosticEvent): String =
        "Typed writer event was unexpectedly rejected: ${event::class.java.simpleName}"

    private fun newPaths(): DiagnosticsPaths = DiagnosticsPaths(
        temporaryFolder.newFolder().canonicalFile,
    )

    private fun sentinelDestination(): ByteArrayOutputStream =
        ByteArrayOutputStream().apply { write(SENTINEL) }

    private data class Fixture(
        val paths: DiagnosticsPaths,
        val recorder: DiagnosticRecorder,
    )

    private companion object {
        val SENTINEL = "unchanged".toByteArray(StandardCharsets.UTF_8)
        val RECORDED_AT = Regex("\\\"recorded_at_ms\\\":(\\d+)")
    }
}
