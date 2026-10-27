package com.yingji.app.data.database

import android.util.Log
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseModule {
    private const val DATABASE_NAME = "yingji.db"

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `movie_types` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `isSystem` INTEGER NOT NULL,
                    `sortOrder` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_movie_types_name` " +
                    "ON `movie_types` (`name`)",
            )
            SYSTEM_TYPES.forEachIndexed { index, name ->
                database.execSQL(
                    "INSERT OR IGNORE INTO `movie_types` (`name`, `isSystem`, `sortOrder`) " +
                        "VALUES (?, 1, ?)",
                    arrayOf(name, index),
                )
            }
            database.execSQL(
                """
                INSERT OR IGNORE INTO `movie_types` (`name`, `isSystem`, `sortOrder`)
                SELECT DISTINCT TRIM(`type`), 0, 1000
                FROM `movies`
                WHERE TRIM(`type`) <> ''
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE movies ADD COLUMN rating REAL")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS ai_profile_state (id INTEGER NOT NULL PRIMARY KEY, ratingsEnabled INTEGER NOT NULL, behaviorEnabled INTEGER NOT NULL, feedbackEnabled INTEGER NOT NULL, profileVersion INTEGER NOT NULL, sourceSettingsVersion INTEGER NOT NULL, libraryFingerprint TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE IF NOT EXISTS ai_explicit_preferences (featureId TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL, direction TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE IF NOT EXISTS ai_profile_corrections (featureId TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE IF NOT EXISTS ai_profile_feedback (eventId TEXT NOT NULL PRIMARY KEY, featureId TEXT NOT NULL, label TEXT NOT NULL, direction TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE IF NOT EXISTS ai_inferred_preferences (featureId TEXT NOT NULL, source TEXT NOT NULL, label TEXT NOT NULL, evidenceCount INTEGER NOT NULL, positiveEvidence INTEGER NOT NULL, negativeEvidence INTEGER NOT NULL, completedWatches INTEGER NOT NULL, repeatWatches INTEGER NOT NULL, PRIMARY KEY(featureId, source))")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS ai_candidate_imports (sourceId TEXT NOT NULL, externalId TEXT NOT NULL, movieId INTEGER NOT NULL, PRIMARY KEY(sourceId, externalId), FOREIGN KEY(movieId) REFERENCES movies(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_ai_candidate_imports_movieId ON ai_candidate_imports(movieId)")
        }
    }

    private val migrations: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

    @Volatile
    private var instance: AppDatabase? = null

    fun provideDatabase(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(*migrations)
                .openHelperFactory(PreservingOpenHelperFactory())
                .addCallback(INTEGRITY_CHECK_CALLBACK)
                .build()
                .also { instance = it }
        }

    private val INTEGRITY_CHECK_CALLBACK = object : RoomDatabase.Callback() {
        override fun onOpen(database: SupportSQLiteDatabase) {
            super.onOpen(database)
            try {
                val isHealthy = database.query("PRAGMA quick_check(1)").use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
                }
                if (!isHealthy) DatabaseHealthMonitor.reportIntegrityCheckFailure()
            } catch (exception: Exception) {
                if (!DatabaseHealthMonitor.reportIfCorruption(exception)) {
                    Log.e(TAG, "Database integrity check could not be completed", exception)
                    DatabaseHealthMonitor.stop(classifyDatabaseIssue(exception))
                }
            }
        }
    }

    private val SYSTEM_TYPES = listOf("动漫", "电影", "电视剧", "综艺", "纪录片", "短剧", "其他")

    private const val TAG = "DatabaseModule"
}
