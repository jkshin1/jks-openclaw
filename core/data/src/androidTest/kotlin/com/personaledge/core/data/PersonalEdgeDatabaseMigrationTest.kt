package com.personaledge.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalEdgeDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        PersonalEdgeDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    @Throws(IOException::class)
    fun migrationFromOneToTenPreservesConversationAndAddsAllLaterTables() {
        val databaseName = "memory-migration-${System.nanoTime()}"
        helper.createDatabase(databaseName, 1).apply {
            execSQL(
                "INSERT INTO conversations " +
                    "(id, title, created_at_epoch_millis, updated_at_epoch_millis, summary, " +
                    "summarized_through_message_ordinal) VALUES " +
                    "('conversation-1', '기존 대화', 1, 1, NULL, 0)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            10,
            true,
            PersonalEdgeDatabase.MIGRATION_1_2,
            PersonalEdgeDatabase.MIGRATION_2_3,
            PersonalEdgeDatabase.MIGRATION_3_4,
            PersonalEdgeDatabase.MIGRATION_4_5,
            PersonalEdgeDatabase.MIGRATION_5_6,
            PersonalEdgeDatabase.MIGRATION_6_7,
            PersonalEdgeDatabase.MIGRATION_7_8,
            PersonalEdgeDatabase.MIGRATION_8_9,
            PersonalEdgeDatabase.MIGRATION_9_10,
        ).use { migrated ->
            migrated.query("SELECT COUNT(*) FROM conversations").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM memories").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM reminders").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM reminder_deliveries").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM commitment_proposals").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM turn_outcomes").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM agent_plan_checkpoints").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM agent_plan_step_checkpoints").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM turn_read_executions").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM unresolved_side_effects").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationFromThreeKeepsMemoryAndBackfillsSafeMetadata() {
        val databaseName = "memory-v4-migration-${System.nanoTime()}"
        helper.createDatabase(databaseName, 3).apply {
            execSQL(
                "INSERT INTO memories " +
                    "(id, content, normalized_content, created_at_epoch_millis, " +
                    "updated_at_epoch_millis) VALUES " +
                    "('memory-1', '기존 기억', '기존 기억', 10, 20)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            4,
            true,
            PersonalEdgeDatabase.MIGRATION_3_4,
        ).use { migrated ->
            migrated.query(
                "SELECT category, valid_until_epoch_millis, last_confirmed_at_epoch_millis, " +
                    "supersedes_id FROM memories WHERE id = 'memory-1'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("FACT", cursor.getString(0))
                assertEquals(true, cursor.isNull(1))
                assertEquals(20L, cursor.getLong(2))
                assertEquals(true, cursor.isNull(3))
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationFromFourAddsEmptyProposalInbox() {
        val databaseName = "proposal-v5-migration-${System.nanoTime()}"
        helper.createDatabase(databaseName, 4).close()

        helper.runMigrationsAndValidate(
            databaseName,
            5,
            true,
            PersonalEdgeDatabase.MIGRATION_4_5,
        ).use { migrated ->
            migrated.query("SELECT COUNT(*) FROM commitment_proposals").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationFromFivePreservesDataAddsEmptyTurnOutcomesAndCascadesConversationDelete() {
        val databaseName = "turn-outcome-v6-migration-${System.nanoTime()}"
        helper.createDatabase(databaseName, 5).apply {
            execSQL(
                "INSERT INTO conversations " +
                    "(id, title, created_at_epoch_millis, updated_at_epoch_millis, summary, " +
                    "summarized_through_message_ordinal) VALUES " +
                    "('conversation-v5', '기존 대화', 10, 20, '기존 요약', 0)",
            )
            execSQL(
                "INSERT INTO messages " +
                    "(id, conversation_id, ordinal, role, text, created_at_epoch_millis) VALUES " +
                    "('message-v5', 'conversation-v5', 1, 'USER', '기존 요청', 11)",
            )
            execSQL(
                "INSERT INTO commitment_proposals " +
                    "(id, summary, proposed_due_at_epoch_millis, zone_id, source_package, " +
                    "source_ref_hash, confidence, status, promoted_reminder_id, " +
                    "created_at_epoch_millis, updated_at_epoch_millis) VALUES " +
                    "('proposal-v5', '기존 제안', NULL, NULL, NULL, 'aabbcc', 80, " +
                    "'PENDING', NULL, 12, 12)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            6,
            true,
            PersonalEdgeDatabase.MIGRATION_5_6,
        ).use { migrated ->
            // MigrationTestHelper exposes a raw SQLite connection rather than Room's runtime
            // connection, so enable foreign-key enforcement before exercising the cascade.
            migrated.execSQL("PRAGMA foreign_keys = ON")
            migrated.query("PRAGMA foreign_keys").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            migrated.query(
                "SELECT title, summary FROM conversations WHERE id = 'conversation-v5'",
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("기존 대화", cursor.getString(0))
                assertEquals("기존 요약", cursor.getString(1))
            }
            migrated.query(
                "SELECT role, text FROM messages WHERE id = 'message-v5'",
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("USER", cursor.getString(0))
                assertEquals("기존 요청", cursor.getString(1))
            }
            migrated.query(
                "SELECT summary, status FROM commitment_proposals WHERE id = 'proposal-v5'",
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("기존 제안", cursor.getString(0))
                assertEquals("PENDING", cursor.getString(1))
            }
            migrated.query("SELECT COUNT(*) FROM turn_outcomes").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }

            migrated.execSQL(
                "INSERT INTO turn_outcomes " +
                    "(turn_id, conversation_id, user_message_ordinal, state, recoverability, " +
                    "failure_code, last_tool_name, last_tool_risk, created_at_epoch_millis, " +
                    "updated_at_epoch_millis, expires_at_epoch_millis) VALUES " +
                    "('turn-55555555-5555-5555-5555-555555555555', 'conversation-v5', 1, " +
                    "'STARTED', 'REQUERY_READ', NULL, NULL, 'NONE', 21, 21, 1000)",
            )
            migrated.execSQL("DELETE FROM conversations WHERE id = 'conversation-v5'")

            migrated.query("SELECT COUNT(*) FROM turn_outcomes").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM messages WHERE id = 'message-v5'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationFromSixPreservesTurnOutcomesAndAddsEmptyContentFreePlanTables() {
        val databaseName = "agent-plan-v7-migration-${System.nanoTime()}"
        helper.createDatabase(databaseName, 6).apply {
            execSQL(
                "INSERT INTO conversations " +
                    "(id, title, created_at_epoch_millis, updated_at_epoch_millis, summary, " +
                    "summarized_through_message_ordinal) VALUES " +
                    "('conversation-v6', '기존 대화', 10, 20, NULL, 0)",
            )
            execSQL(
                "INSERT INTO turn_outcomes " +
                    "(turn_id, conversation_id, user_message_ordinal, state, recoverability, " +
                    "failure_code, last_tool_name, last_tool_risk, created_at_epoch_millis, " +
                    "updated_at_epoch_millis, expires_at_epoch_millis) VALUES " +
                    "('turn-66666666-6666-6666-6666-666666666666', 'conversation-v6', 1, " +
                    "'READ_EXECUTED', 'REQUERY_READ', NULL, 'weather', 'READ_ONLY', 21, 22, 1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            7,
            true,
            PersonalEdgeDatabase.MIGRATION_6_7,
        ).use { migrated ->
            migrated.query(
                "SELECT state, recoverability, last_tool_name FROM turn_outcomes " +
                    "WHERE turn_id = 'turn-66666666-6666-6666-6666-666666666666'",
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("READ_EXECUTED", cursor.getString(0))
                assertEquals("REQUERY_READ", cursor.getString(1))
                assertEquals("weather", cursor.getString(2))
            }
            migrated.query("SELECT COUNT(*) FROM agent_plan_checkpoints").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM agent_plan_step_checkpoints").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationFromSevenBackfillsOnlyStrictReadIdentityAndKeepsSchemaContentFree() {
        val databaseName = "turn-read-v8-migration-${System.nanoTime()}"
        helper.createDatabase(databaseName, 7).apply {
            execSQL(
                "INSERT INTO conversations " +
                    "(id, title, created_at_epoch_millis, updated_at_epoch_millis, summary, " +
                    "summarized_through_message_ordinal) VALUES " +
                    "('conversation-v7', '기존 대화', 10, 20, NULL, 0)",
            )
            insertV7Turn(
                turnId = "turn-71111111-1111-1111-1111-111111111111",
                userOrdinal = 1,
                toolName = "calendar_query",
                risk = "READ_ONLY",
            )
            insertV7Turn(
                turnId = "turn-72222222-2222-2222-2222-222222222222",
                userOrdinal = 2,
                toolName = "alarm_set",
                risk = "DATA_WRITE",
            )
            insertV7Turn(
                turnId = "turn-73333333-3333-3333-3333-333333333333",
                userOrdinal = 3,
                toolName = "calendar query --secret=abc",
                risk = "READ_ONLY",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            8,
            true,
            PersonalEdgeDatabase.MIGRATION_7_8,
        ).use { migrated ->
            migrated.query(
                "SELECT turn_id, ordinal, tool_name FROM turn_read_executions " +
                    "ORDER BY turn_id, ordinal",
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(
                    "turn-71111111-1111-1111-1111-111111111111",
                    cursor.getString(0),
                )
                assertEquals(1, cursor.getInt(1))
                assertEquals("calendar_query", cursor.getString(2))
                assertEquals(false, cursor.moveToNext())
            }
            val columns = mutableListOf<String>()
            migrated.query("PRAGMA table_info(`turn_read_executions`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertEquals(listOf("turn_id", "ordinal", "tool_name"), columns)

            migrated.execSQL("PRAGMA foreign_keys = ON")
            migrated.execSQL(
                "DELETE FROM turn_outcomes " +
                    "WHERE turn_id = 'turn-71111111-1111-1111-1111-111111111111'",
            )
            migrated.query("SELECT COUNT(*) FROM turn_read_executions").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationFromEightSeparatesUnresolvedWritesFromTranscriptLifetime() {
        val databaseName = "unresolved-write-v9-migration-${System.nanoTime()}"
        helper.createDatabase(databaseName, 8).apply {
            execSQL(
                "INSERT INTO conversations " +
                    "(id, title, created_at_epoch_millis, updated_at_epoch_millis, summary, " +
                    "summarized_through_message_ordinal) VALUES " +
                    "('conversation-v8', '기존 대화', 10, 20, NULL, 0)",
            )
            insertV8Turn(
                turnId = "turn-81111111-1111-1111-1111-111111111111",
                userOrdinal = 1,
                state = "WRITE_PENDING",
                recoverability = "VERIFY_EXTERNAL_STATE",
                toolName = "calendar_create_event",
                risk = "DATA_WRITE",
            )
            insertV8Turn(
                turnId = "turn-82222222-2222-2222-2222-222222222222",
                userOrdinal = 2,
                state = "ANSWER_COMPLETE",
                recoverability = "NONE",
                toolName = "alarm_set",
                risk = "DATA_WRITE",
            )
            insertV8Turn(
                turnId = "turn-83333333-3333-3333-3333-333333333333",
                userOrdinal = 3,
                state = "READ_EXECUTED",
                recoverability = "REQUERY_READ",
                toolName = "weather_current",
                risk = "READ_ONLY",
            )
            insertV8Turn(
                turnId = "turn-84444444-4444-4444-4444-444444444444",
                userOrdinal = 4,
                state = "WRITE_UNKNOWN",
                recoverability = "VERIFY_EXTERNAL_STATE",
                toolName = "calendar create -- private payload",
                risk = "DATA_WRITE",
            )
            execSQL(
                "INSERT INTO turn_read_executions (turn_id, ordinal, tool_name) VALUES " +
                    "('turn-83333333-3333-3333-3333-333333333333', 1, 'weather_current')",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            9,
            true,
            PersonalEdgeDatabase.MIGRATION_8_9,
        ).use { migrated ->
            migrated.query(
                "SELECT turn_id, state, tool_name, tool_risk FROM unresolved_side_effects " +
                    "ORDER BY turn_id",
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("turn-81111111-1111-1111-1111-111111111111", cursor.getString(0))
                assertEquals("WRITE_PENDING", cursor.getString(1))
                assertEquals("calendar_create_event", cursor.getString(2))
                assertEquals("DATA_WRITE", cursor.getString(3))
                assertEquals(true, cursor.moveToNext())
                assertEquals("turn-82222222-2222-2222-2222-222222222222", cursor.getString(0))
                assertEquals("WRITE_UNKNOWN", cursor.getString(1))
                assertEquals("alarm_set", cursor.getString(2))
                assertEquals(true, cursor.moveToNext())
                assertEquals("turn-84444444-4444-4444-4444-444444444444", cursor.getString(0))
                assertEquals("WRITE_UNKNOWN", cursor.getString(1))
                assertEquals("unknown_side_effect", cursor.getString(2))
                assertEquals(false, cursor.moveToNext())
            }
            migrated.query("SELECT COUNT(*) FROM turn_read_executions").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            val columns = mutableListOf<String>()
            migrated.query("PRAGMA table_info(`unresolved_side_effects`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertEquals(
                listOf(
                    "turn_id",
                    "conversation_id",
                    "state",
                    "tool_name",
                    "tool_risk",
                    "recorded_at_epoch_millis",
                ),
                columns,
            )
            migrated.query(
                "SELECT sql FROM sqlite_master WHERE type = 'table' " +
                    "AND name = 'unresolved_side_effects'",
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                val createSql = cursor.getString(0).lowercase()
                assertEquals(false, createSql.contains("argument"))
                assertEquals(false, createSql.contains("result"))
                assertEquals(false, createSql.contains("request"))
                assertEquals(false, createSql.contains("expires"))
            }
            migrated.query("PRAGMA foreign_key_list(`unresolved_side_effects`)").use { cursor ->
                assertEquals(0, cursor.count)
            }

            migrated.execSQL("PRAGMA foreign_keys = ON")
            migrated.execSQL("DELETE FROM conversations WHERE id = 'conversation-v8'")
            migrated.query("SELECT COUNT(*) FROM turn_outcomes").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM turn_read_executions").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM unresolved_side_effects").use { cursor ->
                cursor.moveToFirst()
                assertEquals(3, cursor.getInt(0))
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationFromNineAddsAnEmptyContextualRecoverySource() {
        val databaseName = "turn-context-v10-migration-${System.nanoTime()}"
        helper.createDatabase(databaseName, 9).apply {
            execSQL(
                "INSERT INTO conversations " +
                    "(id, title, created_at_epoch_millis, updated_at_epoch_millis, summary, " +
                    "summarized_through_message_ordinal) VALUES " +
                    "('conversation-v9', '기존 대화', 1, 1, NULL, 0)",
            )
            execSQL(
                "INSERT INTO turn_outcomes " +
                    "(turn_id, conversation_id, user_message_ordinal, state, recoverability, " +
                    "failure_code, last_tool_name, last_tool_risk, created_at_epoch_millis, " +
                    "updated_at_epoch_millis, expires_at_epoch_millis) VALUES " +
                    "('turn-91111111-1111-1111-1111-111111111111', 'conversation-v9', 1, " +
                    "'ANSWER_COMPLETE', 'NONE', NULL, NULL, 'NONE', 1, 2, 1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            10,
            true,
            PersonalEdgeDatabase.MIGRATION_9_10,
        ).use { migrated ->
            migrated.query(
                "SELECT recovery_source_user_message_ordinal FROM turn_outcomes " +
                    "WHERE turn_id = 'turn-91111111-1111-1111-1111-111111111111'",
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(true, cursor.isNull(0))
            }
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertV7Turn(
        turnId: String,
        userOrdinal: Int,
        toolName: String,
        risk: String,
    ) {
        execSQL(
            "INSERT INTO turn_outcomes " +
                "(turn_id, conversation_id, user_message_ordinal, state, recoverability, " +
                "failure_code, last_tool_name, last_tool_risk, created_at_epoch_millis, " +
                "updated_at_epoch_millis, expires_at_epoch_millis) VALUES " +
                "('$turnId', 'conversation-v7', $userOrdinal, 'READ_EXECUTED', " +
                "'REQUERY_READ', NULL, '$toolName', '$risk', 21, 22, 1000)",
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertV8Turn(
        turnId: String,
        userOrdinal: Int,
        state: String,
        recoverability: String,
        toolName: String,
        risk: String,
    ) {
        execSQL(
            "INSERT INTO turn_outcomes " +
                "(turn_id, conversation_id, user_message_ordinal, state, recoverability, " +
                "failure_code, last_tool_name, last_tool_risk, created_at_epoch_millis, " +
                "updated_at_epoch_millis, expires_at_epoch_millis) VALUES " +
                "('$turnId', 'conversation-v8', $userOrdinal, '$state', '$recoverability', " +
                "NULL, '$toolName', '$risk', 21, 22, 1000)",
        )
    }
}
