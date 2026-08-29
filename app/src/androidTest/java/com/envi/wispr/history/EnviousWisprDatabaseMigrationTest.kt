package com.envi.wispr.history

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    private companion object {
        const val TEST_DATABASE = "enviouswispr-migration-test"
    }
}
