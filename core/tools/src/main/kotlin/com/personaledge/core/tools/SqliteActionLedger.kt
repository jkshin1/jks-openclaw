package com.personaledge.core.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Durable, atomically claimed replay protection for side-effecting tools.
 *
 * The ledger owns a dedicated database under `noBackupFilesDir` so that clearing conversation
 * history, exporting data, or restoring a cloud backup can never resurrect a spent key. Claims
 * are committed with `synchronous=FULL` before [claim] returns, so a claim that returned `true`
 * survives process death. The resulting guarantee is at-most-once: an action interrupted between
 * its claim and its side effect is never retried automatically.
 *
 * Every failure path fails closed by returning `false`.
 */
class SqliteActionLedger internal constructor(
    private val helper: SQLiteOpenHelper,
    private val clock: () -> Long,
    private val retentionMillis: Long,
    private val maximumClaims: Int,
    private val ioDispatcher: CoroutineDispatcher,
) : PersistentActionLedger() {

    init {
        require(retentionMillis in MINIMUM_RETENTION_MILLIS..MAXIMUM_RETENTION_MILLIS)
        require(maximumClaims in 1..MAXIMUM_ALLOWED_CLAIMS)
    }

    override suspend fun claim(idempotencyKey: String): Boolean {
        require(idempotencyKey.isNotBlank())
        require(idempotencyKey.length <= MAXIMUM_KEY_CHARACTERS)

        return withContext(ioDispatcher) {
            try {
                claimBlocking(idempotencyKey)
            } catch (_: Exception) {
                // A storage fault must never be read as "this action was not executed before".
                false
            }
        }
    }

    /** Releases the pooled connections. The next call reopens the same durable file. */
    fun close() {
        helper.close()
    }

    /** Test and maintenance surface. Returns the number of claims currently retained. */
    internal fun claimCount(): Long = helper.readableDatabase.compileStatement(
        "SELECT COUNT(*) FROM $TABLE_NAME",
    ).use { statement -> statement.simpleQueryForLong() }

    private fun claimBlocking(idempotencyKey: String): Boolean {
        val database = helper.writableDatabase
        val now = clock()

        // One transaction so pruning, the capacity check, and the insert cannot interleave with
        // another process claiming the same key.
        database.beginTransactionNonExclusive()
        return try {
            pruneExpired(database, now)
            if (countClaims(database) >= maximumClaims) {
                // Refusing is the safe answer: the alternative is evicting a live key.
                return false
            }

            val values = ContentValues(2).apply {
                put(COLUMN_IDEMPOTENCY_KEY, idempotencyKey)
                put(COLUMN_CLAIMED_AT, now)
            }
            try {
                database.insertOrThrow(TABLE_NAME, null, values)
            } catch (_: SQLiteConstraintException) {
                return false
            }

            database.setTransactionSuccessful()
            true
        } finally {
            database.endTransaction()
        }
    }

    private fun pruneExpired(database: SQLiteDatabase, now: Long) {
        // Idempotency keys are derived from a per-turn request ID, so an expired key can never
        // legitimately recur. Pruning bounds the file without reopening a replay window.
        val oldestAllowed = now - retentionMillis
        database.delete(
            TABLE_NAME,
            "$COLUMN_CLAIMED_AT < ? OR $COLUMN_CLAIMED_AT > ?",
            arrayOf(oldestAllowed.toString(), (now + MAXIMUM_CLOCK_SKEW_MILLIS).toString()),
        )
    }

    private fun countClaims(database: SQLiteDatabase): Long = database
        .compileStatement("SELECT COUNT(*) FROM $TABLE_NAME")
        .use { statement -> statement.simpleQueryForLong() }

    companion object {
        internal const val TABLE_NAME = "action_claims"
        internal const val COLUMN_IDEMPOTENCY_KEY = "idempotency_key"
        internal const val COLUMN_CLAIMED_AT = "claimed_at_epoch_millis"
        internal const val DATABASE_NAME = "action-ledger.db"
        internal const val DATABASE_VERSION = 1

        const val DEFAULT_RETENTION_MILLIS = 180L * 24 * 60 * 60 * 1_000
        const val DEFAULT_MAXIMUM_CLAIMS = 100_000

        private const val MINIMUM_RETENTION_MILLIS = 24L * 60 * 60 * 1_000
        private const val MAXIMUM_RETENTION_MILLIS = 3_650L * 24 * 60 * 60 * 1_000
        private const val MAXIMUM_ALLOWED_CLAIMS = 1_000_000
        private const val MAXIMUM_KEY_CHARACTERS = 256

        /** A device clock moved far forward would otherwise pin rows past any retention window. */
        private const val MAXIMUM_CLOCK_SKEW_MILLIS = 24L * 60 * 60 * 1_000

        /**
         * Opens the single production ledger. The database lives outside the backup set so a
         * device transfer starts with an empty ledger instead of stale foreign claims.
         */
        fun open(
            context: Context,
            clock: () -> Long = System::currentTimeMillis,
            retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
            maximumClaims: Int = DEFAULT_MAXIMUM_CLAIMS,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): SqliteActionLedger {
            val applicationContext = context.applicationContext
            val databaseFile = File(applicationContext.noBackupFilesDir, DATABASE_NAME)
            return SqliteActionLedger(
                helper = ActionLedgerOpenHelper(applicationContext, databaseFile.absolutePath),
                clock = clock,
                retentionMillis = retentionMillis,
                maximumClaims = maximumClaims,
                ioDispatcher = ioDispatcher,
            )
        }
    }
}

internal class ActionLedgerOpenHelper(
    context: Context,
    absoluteDatabasePath: String,
) : SQLiteOpenHelper(
    context,
    absoluteDatabasePath,
    null,
    SqliteActionLedger.DATABASE_VERSION,
) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(database: SQLiteDatabase) {
        // WAL defaults to synchronous=NORMAL, which does not fsync on commit. A claim must be
        // durable before the caller performs the side effect it protects.
        database.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE ${SqliteActionLedger.TABLE_NAME} (
                ${SqliteActionLedger.COLUMN_IDEMPOTENCY_KEY} TEXT NOT NULL PRIMARY KEY,
                ${SqliteActionLedger.COLUMN_CLAIMED_AT} INTEGER NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX idx_action_claims_claimed_at " +
                "ON ${SqliteActionLedger.TABLE_NAME}(${SqliteActionLedger.COLUMN_CLAIMED_AT})",
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Dropping the ledger would reopen every historical key. Future schema changes must
        // migrate rows explicitly rather than inheriting a destructive default.
        error("Unsupported action ledger migration from $oldVersion to $newVersion.")
    }

    override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported action ledger downgrade from $oldVersion to $newVersion.")
    }
}
