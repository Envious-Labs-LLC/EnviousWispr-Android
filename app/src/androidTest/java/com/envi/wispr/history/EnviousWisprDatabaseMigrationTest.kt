package com.envi.wispr.history

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun migration6To7PreservesTranscriptsAndDefaultsTheCaptureDevice() {
        helper.createDatabase(TEST_DATABASE, 6).use { database ->
            database.execSQL(
                "INSERT INTO transcripts (id, originalText, finalText, createdAtMs, durationMs, speechEngine, polishEngine, " +
                    "polishLatencyMs, insertionResult, kept, recovered, interrupted, status, stateChangedAtMs, " +
                    "polishReason, polishStatus, polishContext) " +
                    "VALUES (9, 'canary raw', 'canary final', 1, 2, 'Parakeet', 'None', 0, 'pending', 0, 0, 0, 'completed', 0, '', 0, '')",
            )
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 7, true, EnviousWisprDatabase.MIGRATION_6_7).use { database ->
            database.query("SELECT finalText, captureDevice FROM transcripts WHERE id = 9").use { cursor ->
                cursor.moveToFirst()
                assertEquals("canary final", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
        }
    }

    /**
     * #176: version 8 adds the take journal and touches no existing value. A populated version-7 database
     * (a transcript and a custom term) is migrated, validated against the exported schema, and every seeded
     * value is read back as the literal that went in; the new table exists and is empty.
     */
    @Test
    fun migration7To8AddsTheTakeJournalAndPreservesEveryExistingValue() {
        helper.createDatabase(TEST_DATABASE, 7).use { database ->
            database.execSQL(
                "INSERT INTO transcripts (id, originalText, finalText, createdAtMs, durationMs, speechEngine, polishEngine, " +
                    "polishLatencyMs, insertionResult, kept, recovered, interrupted, status, stateChangedAtMs, " +
                    "polishReason, polishStatus, polishContext, captureDevice) " +
                    "VALUES (11, 'canary raw', 'canary final', 1000, 2000, 'Parakeet', 'S1-mini', 410, 'committed', 1, 0, 0, 'completed', 3000, " +
                    "'POLISHED', 0, 'general', 'AirPods Pro 3')",
            )
            database.execSQL(
                "INSERT INTO custom_terms " +
                    "(id, spelling, aliases, category, priority, forceReplace, caseSensitive, usageCount, imported, createdAtMs, updatedAtMs, minSimilarityOverride) " +
                    "VALUES (3, 'fooFlux', 'foo flux', 'product', 2, 1, 0, 5, 0, 10, 20, 0.8)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 8, true, EnviousWisprDatabase.MIGRATION_7_8).use { database ->
            database.query(
                "SELECT originalText, finalText, createdAtMs, durationMs, speechEngine, polishEngine, polishLatencyMs, " +
                    "insertionResult, kept, recovered, interrupted, status, stateChangedAtMs, polishReason, polishStatus, " +
                    "polishContext, captureDevice FROM transcripts WHERE id = 11",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("canary raw", cursor.getString(0))
                assertEquals("canary final", cursor.getString(1))
                assertEquals(1000L, cursor.getLong(2))
                assertEquals(2000L, cursor.getLong(3))
                assertEquals("Parakeet", cursor.getString(4))
                assertEquals("S1-mini", cursor.getString(5))
                assertEquals(410L, cursor.getLong(6))
                assertEquals("committed", cursor.getString(7))
                assertEquals(1, cursor.getInt(8))
                assertEquals(0, cursor.getInt(9))
                assertEquals(0, cursor.getInt(10))
                assertEquals("completed", cursor.getString(11))
                assertEquals(3000L, cursor.getLong(12))
                assertEquals("POLISHED", cursor.getString(13))
                assertEquals(0, cursor.getInt(14))
                assertEquals("general", cursor.getString(15))
                assertEquals("AirPods Pro 3", cursor.getString(16))
            }
            database.query("SELECT spelling, aliases, category, priority, forceReplace, caseSensitive, usageCount, imported, createdAtMs, updatedAtMs, minSimilarityOverride FROM custom_terms WHERE id = 3").use { cursor ->
                cursor.moveToFirst()
                assertEquals("fooFlux", cursor.getString(0))
                assertEquals("foo flux", cursor.getString(1))
                assertEquals("product", cursor.getString(2))
                assertEquals(2, cursor.getInt(3))
                assertEquals(1, cursor.getInt(4))
                assertEquals(0, cursor.getInt(5))
                assertEquals(5, cursor.getInt(6))
                assertEquals(0, cursor.getInt(7))
                assertEquals(10L, cursor.getLong(8))
                assertEquals(20L, cursor.getLong(9))
                assertEquals(0.8, cursor.getDouble(10), 0.0001)
            }
            database.query("SELECT COUNT(*) FROM take_journal").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    /**
     * #288: version 9 adds the take a row records. A populated version-8 row reads null for it and keeps every other
     * value; the column is uniquely indexed, so a second row for one take is refused while two nulls are not.
     */
    @Test
    fun migration8To9AddsTheTakeIdAndPreservesEveryExistingValue() {
        helper.createDatabase(TEST_DATABASE, 8).use { database ->
            database.execSQL(
                "INSERT INTO transcripts (id, originalText, finalText, createdAtMs, durationMs, speechEngine, polishEngine, " +
                    "polishLatencyMs, insertionResult, kept, recovered, interrupted, status, stateChangedAtMs, " +
                    "polishReason, polishStatus, polishContext, captureDevice) " +
                    "VALUES (11, 'canary raw', 'canary final', 1000, 2000, 'Parakeet', 'S1-mini', 410, 'committed', 1, 0, 0, 'completed', 3000, " +
                    "'POLISHED', 0, 'general', 'AirPods Pro 3')",
            )
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 9, true, EnviousWisprDatabase.MIGRATION_8_9).use { database ->
            database.query("SELECT originalText, finalText, status, captureDevice, takeId FROM transcripts WHERE id = 11").use { cursor ->
                cursor.moveToFirst()
                assertEquals("canary raw", cursor.getString(0))
                assertEquals("canary final", cursor.getString(1))
                assertEquals("completed", cursor.getString(2))
                assertEquals("AirPods Pro 3", cursor.getString(3))
                assertTrue("an existing row records no take", cursor.isNull(4))
            }
            val insert = "INSERT INTO transcripts (originalText, finalText, createdAtMs, durationMs, speechEngine, polishEngine, " +
                "polishLatencyMs, insertionResult, kept, recovered, interrupted, status, stateChangedAtMs, " +
                "polishReason, polishStatus, polishContext, captureDevice, takeId) " +
                "VALUES ('a', 'a', 1, 1, 'Parakeet', '', 0, 'pending', 0, 0, 0, 'draft', 1, '', 0, '', '', ?)"
            database.execSQL(insert, arrayOf<Any?>(null))
            database.execSQL(insert, arrayOf<Any?>("0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"))
            val second = runCatching { database.execSQL(insert, arrayOf<Any?>("0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d")) }
            assertTrue("one row per take", second.isFailure)
        }
    }

    /** The whole supported chain, 1 through 9, against the exported schemas: what an old install walks. */
    @Test
    fun theWholeMigrationChainReachesVersion9() {
        helper.createDatabase(TEST_DATABASE, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DATABASE, 9, true,
            EnviousWisprDatabase.MIGRATION_1_2, EnviousWisprDatabase.MIGRATION_2_3, EnviousWisprDatabase.MIGRATION_3_4,
            EnviousWisprDatabase.MIGRATION_4_5, EnviousWisprDatabase.MIGRATION_5_6, EnviousWisprDatabase.MIGRATION_6_7,
            EnviousWisprDatabase.MIGRATION_7_8, EnviousWisprDatabase.MIGRATION_8_9,
        ).close()
    }

    private companion object {
        const val TEST_DATABASE = "enviouswispr-migration-test"
    }
}
