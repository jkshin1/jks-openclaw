package com.personaledge.core.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Content-free startup check; neither branch exposes request keys, Tools, or parameters. */
sealed interface UnresolvedActionCheck {
    data class Available(val count: Long) : UnresolvedActionCheck {
        init {
            require(count >= 0)
        }
    }

    /** Storage could not prove that there are zero unresolved executions. */
    data object Unavailable : UnresolvedActionCheck
}

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

    override suspend fun recordState(
        idempotencyKey: String,
        state: ActionExecutionState,
    ): Boolean {
        require(idempotencyKey.isNotBlank())
        require(idempotencyKey.length <= MAXIMUM_KEY_CHARACTERS)
        require(state != ActionExecutionState.CLAIMED)

        return withContext(ioDispatcher) {
            try {
                recordStateBlocking(idempotencyKey, state)
            } catch (_: Exception) {
                // The original claim remains replay-blocking even if this enrichment fails.
                false
            }
        }
    }

    /**
     * Counts only non-terminal claims for a content-free startup warning.
     *
     * A database fault is not equivalent to zero: callers must show the unavailable warning and
     * keep automatic retries disabled.
     */
    suspend fun unresolvedActionCheck(): UnresolvedActionCheck = withContext(ioDispatcher) {
        try {
            UnresolvedActionCheck.Available(unresolvedActionCountBlocking())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UnresolvedActionCheck.Unavailable
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

    internal fun stateOf(idempotencyKey: String): ActionExecutionState? {
        helper.readableDatabase.query(
            TABLE_NAME,
            arrayOf(COLUMN_STATE),
            "$COLUMN_IDEMPOTENCY_KEY = ?",
            arrayOf(idempotencyKey),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return runCatching { ActionExecutionState.valueOf(cursor.getString(0)) }.getOrNull()
        }
    }

    private fun unresolvedActionCountBlocking(): Long = helper.readableDatabase.query(
        TABLE_NAME,
        arrayOf("COUNT(*)"),
        "$COLUMN_STATE = ? OR $COLUMN_STATE = ?",
        arrayOf(
            ActionExecutionState.CLAIMED.name,
            ActionExecutionState.UNKNOWN_AFTER_CLAIM.name,
        ),
        null,
        null,
        null,
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Missing unresolved action count row." }
        cursor.getLong(0)
    }

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

            val values = ContentValues(4).apply {
                put(COLUMN_IDEMPOTENCY_KEY, idempotencyKey)
                put(COLUMN_CLAIMED_AT, now)
                put(COLUMN_STATE, ActionExecutionState.CLAIMED.name)
                put(COLUMN_UPDATED_AT, now)
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

    private fun recordStateBlocking(
        idempotencyKey: String,
        state: ActionExecutionState,
    ): Boolean {
        val database = helper.writableDatabase
        val values = ContentValues(2).apply {
            put(COLUMN_STATE, state.name)
            put(COLUMN_UPDATED_AT, clock())
        }

        database.beginTransactionNonExclusive()
        return try {
            val updated = database.update(
                TABLE_NAME,
                values,
                "$COLUMN_IDEMPOTENCY_KEY = ? AND $COLUMN_STATE = ?",
                arrayOf(idempotencyKey, ActionExecutionState.CLAIMED.name),
            )
            val accepted = when {
                updated == 1 -> true
                updated != 0 -> false
                else -> queryState(database, idempotencyKey) == state
            }
            if (accepted) database.setTransactionSuccessful()
            accepted
        } finally {
            database.endTransaction()
        }
    }

    private fun queryState(
        database: SQLiteDatabase,
        idempotencyKey: String,
    ): ActionExecutionState? = database.query(
        TABLE_NAME,
        arrayOf(COLUMN_STATE),
        "$COLUMN_IDEMPOTENCY_KEY = ?",
        arrayOf(idempotencyKey),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        runCatching { ActionExecutionState.valueOf(cursor.getString(0)) }.getOrNull()
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
        internal const val COLUMN_STATE = "execution_state"
        internal const val COLUMN_UPDATED_AT = "updated_at_epoch_millis"
        internal const val DATABASE_NAME = "action-ledger.db"
        internal const val DATABASE_VERSION = 2

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
                ${SqliteActionLedger.COLUMN_CLAIMED_AT} INTEGER NOT NULL,
                ${SqliteActionLedger.COLUMN_STATE} TEXT NOT NULL,
                ${SqliteActionLedger.COLUMN_UPDATED_AT} INTEGER NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX idx_action_claims_claimed_at " +
                "ON ${SqliteActionLedger.TABLE_NAME}(${SqliteActionLedger.COLUMN_CLAIMED_AT})",
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            // V1 retained only a claim. It is impossible to know whether its side effect happened,
            // so preserve every key and label it conservatively instead of reopening a replay.
            database.execSQL(
                "ALTER TABLE ${SqliteActionLedger.TABLE_NAME} " +
                    "ADD COLUMN ${SqliteActionLedger.COLUMN_STATE} TEXT NOT NULL " +
                    "DEFAULT '${ActionExecutionState.UNKNOWN_AFTER_CLAIM.name}'",
            )
            database.execSQL(
                "ALTER TABLE ${SqliteActionLedger.TABLE_NAME} " +
                    "ADD COLUMN ${SqliteActionLedger.COLUMN_UPDATED_AT} INTEGER NOT NULL DEFAULT 0",
            )
            database.execSQL(
                "UPDATE ${SqliteActionLedger.TABLE_NAME} " +
                    "SET ${SqliteActionLedger.COLUMN_UPDATED_AT} = " +
                    SqliteActionLedger.COLUMN_CLAIMED_AT,
            )
            return
        }

        // Dropping the ledger would reopen every historical key. Future schema changes must
        // migrate rows explicitly rather than inheriting a destructive default.
        error("Unsupported action ledger migration from $oldVersion to $newVersion.")
    }

    override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported action ledger downgrade from $oldVersion to $newVersion.")
    }
}
