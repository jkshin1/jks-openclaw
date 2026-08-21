package com.personaledge.core.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteActionLedgerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseFile = File(context.noBackupFilesDir, SqliteActionLedger.DATABASE_NAME)

    private var now = 1_700_000_000_000L
    private val openLedgers = mutableListOf<SqliteActionLedger>()

    @Before
    fun clearPreviousLedger() {
        deleteLedgerFiles()
    }

    @After
    fun closeAndClear() {
        openLedgers.forEach(SqliteActionLedger::close)
        openLedgers.clear()
        deleteLedgerFiles()
    }

    private fun openLedger(
        retentionMillis: Long = SqliteActionLedger.DEFAULT_RETENTION_MILLIS,
        maximumClaims: Int = SqliteActionLedger.DEFAULT_MAXIMUM_CLAIMS,
    ): SqliteActionLedger = SqliteActionLedger.open(
        context = context,
        clock = { now },
        retentionMillis = retentionMillis,
        maximumClaims = maximumClaims,
        ioDispatcher = Dispatchers.IO,
    ).also(openLedgers::add)

    private fun deleteLedgerFiles() {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            File(databaseFile.path + suffix).delete()
        }
    }

    @Test
    fun claimsAreStoredOutsideTheBackupSet() = runBlocking {
        assertTrue(openLedger().claim("key-backup-location"))

        assertTrue(databaseFile.isFile)
        assertEquals(context.noBackupFilesDir, databaseFile.parentFile)
    }

    @Test
    fun aClaimIsRefusedTwiceWithinOneProcess() = runBlocking {
        val ledger = openLedger()

        assertTrue(ledger.claim("key-single-use"))
        assertFalse(ledger.claim("key-single-use"))
        assertEquals(1L, ledger.claimCount())
    }

    @Test
    fun aClaimSurvivesReopeningTheDatabase() = runBlocking {
        assertTrue(openLedger().claim("key-across-restart"))
        openLedgers.removeLast().close()

        // A new helper models the next process start after the app was killed.
        assertFalse(openLedger().claim("key-across-restart"))
    }

    @Test
    fun concurrentClaimsOfOneKeyProduceExactlyOneWinner() = runBlocking {
        val ledger = openLedger()

        val results = (1..16)
            .map { async(Dispatchers.IO) { ledger.claim("key-concurrent") } }
            .awaitAll()

        assertEquals(1, results.count { claimed -> claimed })
        assertEquals(1L, ledger.claimCount())
    }

    @Test
    fun expiredClaimsArePrunedButRecentOnesAreKept() = runBlocking {
        val retentionMillis = 24L * 60 * 60 * 1_000
        val ledger = openLedger(retentionMillis = retentionMillis)

        assertTrue(ledger.claim("key-old"))
        now += retentionMillis - 1_000
        assertTrue(ledger.claim("key-recent"))
        now += 2_000

        // Any claim triggers pruning; the old key is now beyond the retention window.
        assertTrue(ledger.claim("key-trigger"))
        assertEquals(2L, ledger.claimCount())
        assertTrue(ledger.claim("key-old"))
        assertFalse(ledger.claim("key-recent"))
    }

    @Test
    fun aFullLedgerFailsClosedInsteadOfEvictingLiveKeys() = runBlocking {
        val ledger = openLedger(maximumClaims = 2)

        assertTrue(ledger.claim("key-1"))
        assertTrue(ledger.claim("key-2"))
        assertFalse(ledger.claim("key-3"))
        assertEquals(2L, ledger.claimCount())
    }

    @Test
    fun aClockJumpedFarForwardDoesNotPinRowsForever() = runBlocking {
        val retentionMillis = 24L * 60 * 60 * 1_000
        val ledger = openLedger(retentionMillis = retentionMillis)

        now += 400L * 24 * 60 * 60 * 1_000
        assertTrue(ledger.claim("key-from-the-future"))

        now -= 400L * 24 * 60 * 60 * 1_000
        assertTrue(ledger.claim("key-after-clock-correction"))
        assertEquals(1L, ledger.claimCount())
    }
}
