package com.yingji.app.data.repository

import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.room.Room
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.Tag
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.domain.model.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Also accepts prepare/verify phases for the host to run across a real process restart. */
class WatchSessionRestartTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun fixtureName(): String {
        val name = InstrumentationRegistry.getArguments().getString("b2FixtureName")
            ?: ("b2-restart-" + java.util.UUID.randomUUID() + ".db")
        require(name.matches(Regex("b2-restart-[0-9a-f-]{36}\\.db")))
        return name
    }

    @Test fun sessionsAndPostersPersist() = runBlocking {
        val name = fixtureName()
        when (InstrumentationRegistry.getArguments().getString("b2Phase")) {
            null -> { prepare(name); verify(name) }
            "prepare" -> prepare(name)
            "verify" -> verify(name)
            else -> error("未知的重启测试阶段")
        }
    }

    private suspend fun prepare(name: String) {
        check(!context.getDatabasePath(name).exists()) { "拒绝覆盖已有数据库" }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val source = File(context.cacheDir, name + ".jpg")
        try {
            source.writeBytes(byteArrayOf(7, 8, 9))
            val poster = requireNotNull(PosterRepository(context).persist(Uri.fromFile(source).toString()))
            val category = database.categoryDao().insert(Category(name = "重启分类", isSystem = false))
            val tag = database.tagDao().insert(Tag(name = "重启标签"))
            val movie = MovieRepository(database).insertWithRelations(Movie(name = "重启作品", type = "重启分类",
                status = MovieStatus.WATCHING.value, posterPath = poster, totalEpisode = 12, createTime = 1, rating = 8.5),
                category, setOf(tag))
            val histories = HistoryRepository(database)
            val active = database.watchHistoryDao().getForMovie(movie).single()
            histories.endWatching(active.id, active.startTime + 100)
            assertNotNull(histories.startWatching(movie, active.startTime + 200))
            assertEquals(MovieStatus.WATCHING.value, database.movieDao().getById(movie)?.status)
            checkSettings(name, prepare = true)
            println("B2_PROCESS_ID=" + Process.myPid())
        } finally {
            database.close()
            source.delete()
        }
    }

    private suspend fun verify(name: String) {
        check(context.getDatabasePath(name).isFile)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var poster: String? = null
        try {
            checkSettings(name, prepare = false)
            val movie = requireNotNull(database.movieDao().getById(1))
            poster = requireNotNull(movie.posterPath)
            assertEquals("重启作品", movie.name)
            assertEquals(8.5, requireNotNull(movie.rating), 0.0)
            assertEquals(MovieStatus.WATCHING.value, movie.status)
            assertEquals(listOf(1L), database.categoryDao().getCategoryIdsForMovie(movie.id))
            assertEquals(listOf(1L), database.tagDao().getTagIdsForMovie(movie.id))
            val records = database.watchHistoryDao().getForMovie(movie.id).sortedBy { it.watchNumber }
            assertEquals(2, records.size)
            assertEquals(listOf(1, 2), records.map { it.watchNumber })
            assertEquals(100L, records.first().duration)
            assertNotNull(records.first().endTime)
            assertNull(records.last().endTime)
            assertArrayEquals(byteArrayOf(7, 8, 9), File(poster).readBytes())
            println("B2_PROCESS_ID=" + Process.myPid())
        } finally {
            database.close()
            context.deleteDatabase(name) // Only the explicitly named UUID test fixture.
            PosterRepository(context).delete(poster)
        }
    }
    private suspend fun checkSettings(name: String, prepare: Boolean) {
        val file = File(context.cacheDir, name + ".preferences_pb")
        if (prepare) check(!file.exists()) else check(file.isFile)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val repository = ThemeRepository(context, store)
            if (prepare) {
                repository.setThemeMode(ThemeMode.DARK)
                repository.setMovieDisplayMode(MovieDisplayMode.POSTER_WALL)
                repository.setMovieSortOption(MovieSortOption.WATCH_END_DESC)
            } else {
                assertEquals(ThemeMode.DARK, repository.themeMode.first())
                assertEquals(MovieDisplayMode.POSTER_WALL, repository.movieDisplayMode.first())
                assertEquals(MovieSortOption.WATCH_END_DESC, repository.movieSortOption.first())
            }
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
            if (!prepare) check(file.delete()) // Only this test's UUID preferences file.
        }
    }
}
