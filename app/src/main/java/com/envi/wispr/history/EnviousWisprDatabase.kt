package com.envi.wispr.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.envi.wispr.vocabulary.CustomTermDao
import com.envi.wispr.vocabulary.CustomTermEntity

@Database(
    entities = [TranscriptEntity::class, CustomTermEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class EnviousWisprDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao
    abstract fun customTermDao(): CustomTermDao

    companion object {
        @Volatile
        private var instance: EnviousWisprDatabase? = null

        fun get(context: Context): EnviousWisprDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EnviousWisprDatabase::class.java,
                    "enviouswispr.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { database -> instance = database }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE transcripts ADD COLUMN status TEXT NOT NULL DEFAULT 'completed'",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE transcripts ADD COLUMN stateChangedAtMs INTEGER NOT NULL DEFAULT 0",
                )
                // Legacy open rows retain their recoverable zero timestamp; completed rows remain completed.
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
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
    }
}
