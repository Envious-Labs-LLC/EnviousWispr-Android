package com.envi.wispr.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.envi.wispr.telemetry.TakeJournalDao
import com.envi.wispr.telemetry.TakeJournalEntry
import com.envi.wispr.vocabulary.CustomTermDao
import com.envi.wispr.vocabulary.CustomTermEntity

@Database(
    entities = [TranscriptEntity::class, CustomTermEntity::class, TakeJournalEntry::class],
    version = 8,
    exportSchema = true,
)
abstract class EnviousWisprDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao
    abstract fun customTermDao(): CustomTermDao
    abstract fun takeJournalDao(): TakeJournalDao

    companion object {
        @Volatile
        private var instance: EnviousWisprDatabase? = null

        fun get(context: Context): EnviousWisprDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EnviousWisprDatabase::class.java,
                    "enviouswispr.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { database -> instance = database }
            }
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE transcripts ADD COLUMN status TEXT NOT NULL DEFAULT 'completed'",
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE transcripts ADD COLUMN stateChangedAtMs INTEGER NOT NULL DEFAULT 0",
                )
                // Legacy open rows retain their recoverable zero timestamp; completed rows remain completed.
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS custom_terms (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "spelling TEXT NOT NULL, " +
                        "aliases TEXT NOT NULL, " +
                        "category TEXT, " +
                        "priority INTEGER NOT NULL, " +
                        "forceReplace INTEGER NOT NULL, " +
                        "caseSensitive INTEGER NOT NULL, " +
                        "usageCount INTEGER NOT NULL, " +
                        "imported INTEGER NOT NULL, " +
                        "createdAtMs INTEGER NOT NULL, " +
                        "updatedAtMs INTEGER NOT NULL)",
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_custom_terms_spelling ON custom_terms (spelling)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_custom_terms_category ON custom_terms (category)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_custom_terms_priority ON custom_terms (priority)")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE custom_terms ADD COLUMN minSimilarityOverride REAL",
                )
            }
        }

        /** #77: why the polish ended the way it did, three additive columns with the entity's defaults. */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transcripts ADD COLUMN polishReason TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE transcripts ADD COLUMN polishStatus INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE transcripts ADD COLUMN polishContext TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transcripts ADD COLUMN captureDevice TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * #176: the take journal. Creates one table and its indexes; touches no existing row or column,
         * so a rollback build keeps version 8 and this table rather than ever downgrading.
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS take_journal (" +
                        "take_id TEXT NOT NULL, " +
                        "process_run_id TEXT NOT NULL, " +
                        "admitted_at_ms INTEGER NOT NULL, " +
                        "stage TEXT NOT NULL, " +
                        "stage_seq INTEGER NOT NULL, " +
                        "transcript_id INTEGER, " +
                        "terminal_result TEXT, " +
                        "terminal_reason TEXT, " +
                        "terminal_at_ms INTEGER, " +
                        "trigger_source TEXT NOT NULL, " +
                        "PRIMARY KEY(take_id))",
                )
            }
        }
    }
}
