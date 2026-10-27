package com.yingji.app.data.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.model.MovieStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class RatingMigrationTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun versionOneMigratesToThreeWithoutLosingAnyBaseData() = checkMigration(1)
    @Test fun versionTwoMigratesToThreeAndRatingSurvivesReopenAndStatusChanges() = checkMigration(2)

    private fun checkMigration(oldVersion: Int) = runBlocking {
        DatabaseHealthMonitor.resetForTesting()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "rating-migration-" + UUID.randomUUID() + ".db"
        val poster = File(context.cacheDir, name + ".jpg").apply { writeBytes(byteArrayOf(1, 3, 5)) }
        try {
            migration.createDatabase(name, oldVersion).use { old ->
                old.execSQL("INSERT INTO movies (id,name,posterPath,type,status,currentEpisode,totalEpisode,createTime) VALUES (41,'旧作品',?,'电影','已看完',12,12,100)", arrayOf(poster.path))
                old.execSQL("INSERT INTO categories (id,name,isSystem) VALUES (7,'旧分类',0)")
                old.execSQL("INSERT INTO tags (id,name) VALUES (7,'旧标签')")
                old.execSQL("INSERT INTO movie_categories (movieId,categoryId) VALUES (41,7)")
                old.execSQL("INSERT INTO movie_tags (movieId,tagId) VALUES (41,7)")
                old.execSQL("INSERT INTO watch_history (id,movieId,startTime,endTime,duration,watchNumber) VALUES (9,41,100,200,100,1)")
                if (oldVersion == 2) old.execSQL("INSERT INTO movie_types (id,name,isSystem,sortOrder) VALUES (77,'OVA',0,1000)")
            }
            migration.runMigrationsAndValidate(name, 3, true,
                DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3).use { migrated ->
                migrated.query("SELECT * FROM movies WHERE id=41").use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals("旧作品", row.getString(row.getColumnIndexOrThrow("name")))
                    assertEquals(poster.path, row.getString(row.getColumnIndexOrThrow("posterPath")))
                    assertEquals(12, row.getInt(row.getColumnIndexOrThrow("currentEpisode")))
                    assertEquals(100L, row.getLong(row.getColumnIndexOrThrow("createTime")))
                    assertTrue(row.isNull(row.getColumnIndexOrThrow("rating")))
                }
                migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                migrated.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            }
            fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(DatabaseModule.MIGRATION_1_2, DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4,DatabaseModule.MIGRATION_4_5)
                .openHelperFactory(PreservingOpenHelperFactory()).build()
            val first = open()
            try {
                val db = first
                assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(41))
                assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(41))
                val history = requireNotNull(db.watchHistoryDao().getById(9))
                assertEquals(41L, history.movieId)
                assertEquals(100L, history.startTime)
                assertEquals(200L, history.endTime)
                assertEquals(100L, history.duration)
                assertEquals(1, history.watchNumber)
                if (oldVersion == 2) db.openHelper.readableDatabase.query("SELECT id FROM movie_types WHERE name='OVA'").use { assertTrue(it.moveToFirst()); assertEquals(77L, it.getLong(0)) }
                val movie = requireNotNull(db.movieDao().getById(41))
                MovieRepository(db).updateWithRelations(movie.copy(rating = 8.5), 7, setOf(7))
                MovieRepository(db).updateStatus(41, MovieStatus.WANT_TO_WATCH)
                assertEquals(8.5, requireNotNull(db.movieDao().getById(41)).rating)
            } finally { first.close() }
            val second = open()
            try {
                val db = second
                val movie = requireNotNull(db.movieDao().getById(41))
                assertEquals(8.5, movie.rating)
                assertEquals(MovieStatus.WANT_TO_WATCH.value, movie.status)
                assertEquals(poster.path, movie.posterPath)
                MovieRepository(db).updateWithRelations(movie.copy(rating = null), 7, setOf(7))
                assertNull(db.movieDao().getById(41)?.rating)
                assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(41))
                assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(41))
                assertEquals(historyRowId(db), 9L)
            } finally { second.close() }
            assertArrayEquals(byteArrayOf(1, 3, 5), poster.readBytes())
        } finally {
            context.deleteDatabase(name)
            poster.delete()
            DatabaseHealthMonitor.resetForTesting()
        }
    }

    private suspend fun historyRowId(db: AppDatabase) = requireNotNull(db.watchHistoryDao().getById(9)).id
}
