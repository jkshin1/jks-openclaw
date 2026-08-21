package com.personaledge.core.diagnostics

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDiagnosticsInstrumentationTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var paths: DiagnosticsPaths

    @Before
    fun setUp() {
        paths = DiagnosticsPaths(context.noBackupFilesDir.canonicalFile)
        cleanFixedTestStorage()
    }

    @After
    fun tearDown() {
        cleanFixedTestStorage()
    }

    @Test
    fun secureAndroidStoreRotatesFixedPrivateSingleLinkFiles() {
        val fileSystem = AndroidSecureDiagnosticsFileSystem(paths)
        val recorder = DiagnosticRecorder.createForTest(
            paths = paths,
            fileSystem = fileSystem,
            maxFileBytes = 180,
        )

        repeat(24) { assertTrue(recorder.record(DiagnosticEvent.ProcessStarted)) }

        val files = paths.logDirectory.listFiles().orEmpty().sortedBy(File::getName)
        assertEquals(
            setOf(
                "diagnostics.jsonl",
                "diagnostics.1.jsonl",
                "diagnostics.2.jsonl",
                "diagnostics.3.jsonl",
            ),
            files.map(File::getName).toSet(),
        )
        files.forEach { file ->
            val stat = Os.lstat(file.path)
            assertTrue(OsConstants.S_ISREG(stat.st_mode))
            assertEquals(1L, stat.st_nlink)
            assertEquals(0, stat.st_mode and 0x3f)
            assertTrue(file.length() in 1..180)
            assertEquals('\n'.code.toByte(), file.readBytes().last())
        }

        val checkpointStore = ExitCheckpointStore(paths, fileSystem)
        val checkpoint = ExitCheckpoint(
            maxTimestampMillis = 123,
            identitiesAtMaxTimestamp = setOf("a".repeat(64)),
        )
        checkpointStore.save(checkpoint)
        assertEquals(checkpoint, checkpointStore.load())
        val checkpointStat = Os.lstat(paths.checkpoint.path)
        assertTrue(OsConstants.S_ISREG(checkpointStat.st_mode))
        assertEquals(1L, checkpointStat.st_nlink)
        assertEquals(0, checkpointStat.st_mode and 0x3f)
    }

    @Test
    fun api31ProvidersAndRecorderAreFailOpenOnTheRealPlatform() {
        val providers = DiagnosticProviders(
            historicalExits = Api31HistoricalExitProvider(context),
            resources = Api31ResourceSnapshotProvider(context),
            phaseSummary = Api31ProcessStateSummaryWriter(context),
        )
        val recorder = DiagnosticRecorder.create(context, providers)

        assertTrue(recorder.markPhase(DiagnosticPhase.IDLE))
        assertTrue(recorder.recordResourceSnapshot())
        assertTrue(providers.resources.snapshot().pssBytes >= 0)
        assertTrue(providers.resources.snapshot().javaHeapBytes >= 0)
        assertTrue(providers.historicalExits.load(8).size <= 8)
        assertTrue(recorder.recordHistoricalExits(8) in 0..8)
    }

    private fun cleanFixedTestStorage() {
        val allowedLogs = setOf(
            paths.activeLog.name,
            *paths.archiveLogs.map(File::getName).toTypedArray(),
        )
        paths.logDirectory.listFiles().orEmpty().forEach { file ->
            check(file.name in allowedLogs)
            check(file.delete())
        }
        if (paths.logDirectory.exists()) check(paths.logDirectory.delete())
        listOf(paths.checkpoint, paths.checkpointTemporary).forEach { file ->
            if (file.exists()) check(file.delete())
        }
    }
}
