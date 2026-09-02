package com.envi.wispr.history

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnviousWisprDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EnviousWisprDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration4To5PreservesTermsAndAddsDefaultStrictness() {
        helper.createDatabase(TEST_DATABASE, 4).apply {
            execSQL(
                "INSERT INTO custom_terms " +
                    "(id, spelling, aliases, category, priority, forceReplace, caseSensitive, usageCount, imported, createdAtMs, updatedAtMs) " +
                    "VALUES (1, 'CanaryTerm', 'canary alias', NULL, 0, 0, 0, 0, 1, 1, 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 5, true, EnviousWisprDatabase.MIGRATION_4_5).use { database ->
            database.query("SELECT minSimilarityOverride FROM custom_terms WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertNull(cursor.getString(0))
            }
        }
    }

    @Test
    fun migration5To6PreservesTranscriptsAndDefaultsThePolishFacts() {
        helper.createDatabase(TEST_DATABASE, 5).apply {
            execSQL(
                "INSERT INTO transcripts " +
                    "(id, originalText, finalText, createdAtMs, durationMs, speechEngine, polishEngine, polishLatencyMs, insertionResult, kept, recovered, interrupted, status, stateChangedAtMs) " +
                    "VALUES (7, 'canary original', 'canary final', 1, 1, 'Parakeet', 'Deterministic fallback', 12, 'clipboard', 0, 0, 0, 'completed', 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 6, true, EnviousWisprDatabase.MIGRATION_5_6).use { database ->
            database.query("SELECT finalText, polishReason, polishStatus, polishContext FROM transcripts WHERE id = 7").use { cursor ->
                cursor.moveToFirst()
                assertEquals("canary final", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals("", cursor.getString(3))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "enviouswispr-migration-test"
    }
}
