package com.yingji.app.presentation.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.ai.*
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.*
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AiManagementDataSafetyTest {
    @get:Rule val folder = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)
    @Test fun everyMockCleanupPreservesOriginalLibraryRealProfileRelationsWatchTimesAndPoster() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>(); DatabaseHealthMonitor.resetForTesting()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val poster = File(folder.root, "original-poster.bin"); val bytes = ByteArray(256) { it.toByte() }; poster.writeBytes(bytes)
            db.categoryDao().insert(Category(7, "保留分类", false)); db.tagDao().insert(Tag(7, "保留标签"))
            val movies = MovieRepository(db)
            val movieId = movies.insertWithRelations(Movie(name = "原始作品", posterPath = poster.path, type = "保留分类", status = "想看", createTime = 123, rating = 8.5), 7, setOf(7))
            db.watchHistoryDao().insert(WatchHistory(movieId = movieId, startTime = 100, endTime = 200, duration = 100, watchNumber = 1))
            val profile = ProfileRepository(db, ObserveLibraryRepository(movies))
            profile.setSources(ProfileSourceSettings(true, true, true)); profile.setExplicitPreference(ProfileFeature.topic("真实偏好"), PreferenceDirection.DISLIKE)
            profile.recordFeedback("real-feedback", ProfileFeature.topic("真实反馈"), PreferenceDirection.LIKE)
            profile.suppressInference(ProfileFeature.tag(7, "保留标签"))
            fun snapshot(): Map<String, List<List<String?>>> {
                val sql = db.openHelper.readableDatabase
                val tables = mutableListOf<String>()
                sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { while (it.moveToNext()) tables.add(it.getString(0)) }
                return tables.associateWith { table ->
                    val rows = mutableListOf<List<String?>>()
                    sql.query("SELECT * FROM " + table).use { c -> while (c.moveToNext()) rows.add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
                    rows.sortedBy { it.joinToString() }
                }
            }
            val before = snapshot()
            for (action in AiClearAction.entries) {
                val demo = AiTestRepository(); demo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.LIKE)))
                demo.saveFeedback("mock-0", AiFeedback.LIKE); demo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS)
                demo.clearManagement(action)
                assertEquals("Original tables changed by " + action.name, before, snapshot()); assertArrayEquals(bytes, poster.readBytes())
            }
            assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(movieId)); assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(movieId))
        } finally { db.close(); DatabaseHealthMonitor.resetForTesting() }
    }
}
