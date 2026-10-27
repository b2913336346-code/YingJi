package com.yingji.app.data.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.*
import com.yingji.app.data.repository.*
import com.yingji.app.domain.ai.*
import java.io.File

internal class LocalProfileTestFixture(name: String? = null) : AutoCloseable {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = if (name == null) Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        else Room.databaseBuilder(context, AppDatabase::class.java, name).build()
    val movies = MovieRepository(db)
    val profiles = ProfileRepository(db, ObserveLibraryRepository(movies))
    val manager = RoomLocalProfileManagementRepository(profiles, CategoryRepository(db.categoryDao(), db.tagDao()))
    val category = ProfileFeature.category(7, "同名")
    val tag = ProfileFeature.tag(7, "同名")
    val topic = ProfileFeature.topic("科幻")
    var poster: File? = null
    var keepPoster = false
    suspend fun seed() {
        db.categoryDao().insert(Category(7, "同名", false)); db.tagDao().insert(Tag(7, "同名"))
        poster = File(context.cacheDir, "profile-fixture-" + java.util.UUID.randomUUID() + ".png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        movies.insertWithRelations(Movie(41, "保留的作品", poster!!.path, "同名", "已看完", createTime = 100, rating = 8.5), 7, setOf(7))
        val history = db.watchHistoryDao().getForMovie(41).single()
        db.watchHistoryDao().update(history.copy(startTime = 100, endTime = 200, duration = 100))
        profiles.setSources(ProfileSourceSettings(true, true, true))
        profiles.setExplicitPreference(category, PreferenceDirection.LIKE)
        profiles.setExplicitPreference(tag, PreferenceDirection.DISLIKE)
        profiles.recordFeedback("real-test-feedback", topic, PreferenceDirection.LIKE)
    }
    fun tables(includeAi: Boolean = false): Map<String, List<List<String?>>> {
        val sql = db.openHelper.readableDatabase
        val names = mutableListOf<String>()
        sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c -> while (c.moveToNext()) names.add(c.getString(0)) }
        return names.filter { includeAi || !it.startsWith("ai_") }.associateWith { name ->
            val rows = mutableListOf<List<String?>>()
            val quote = 34.toChar().toString()
            sql.query("SELECT * FROM " + quote + name.replace(quote, quote + quote) + quote).use { c ->
                while (c.moveToNext()) rows.add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) })
            }
            rows.sortedBy { it.toString() }
        }
    }
    override fun close() { db.close(); if (!keepPoster) poster?.delete(); DatabaseHealthMonitor.resetForTesting() }
}
