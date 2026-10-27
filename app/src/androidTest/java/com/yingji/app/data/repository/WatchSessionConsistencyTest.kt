package com.yingji.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.RecordWatchUseCase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WatchSessionConsistencyTest {
    private lateinit var database: AppDatabase
    private lateinit var histories: HistoryRepository
    private var movieId = 0L

    @Before fun setup() = runBlocking {
        DatabaseHealthMonitor.resetForTesting()
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        histories = HistoryRepository(database)
        movieId = database.movieDao().insert(Movie(name = "事务作品", type = "电影",
            status = MovieStatus.WANT_TO_WATCH.value, totalEpisode = 12, createTime = 1))
    }

    @After fun teardown() {
        database.close()
        DatabaseHealthMonitor.resetForTesting()
    }

    @Test fun startAndEndSynchronizeStatusAndPreserveEarlierWatches() = runBlocking {
        val first = requireNotNull(histories.startWatching(movieId, 100))
        assertEquals(MovieStatus.WATCHING.value, movie().status)
        assertNull(histories.startWatching(movieId, 200))
        histories.endWatching(first, 500)
        val completed = requireNotNull(histories.getById(first))
        assertEquals(400L, completed.duration)
        assertEquals(MovieStatus.FINISHED.value, movie().status)
        assertEquals(12, movie().currentEpisode)
        val second = requireNotNull(histories.startWatching(movieId, 600))
        assertNotEquals(first, second)
        assertEquals(completed, histories.getById(first))
        assertEquals(2, histories.getById(second)?.watchNumber)
        assertEquals(MovieStatus.WATCHING.value, movie().status)
    }

    @Test fun concurrentUseCasesAndRepositoriesCannotCreateTwoActiveRecords() = runBlocking {
        val ids = coroutineScope {
            (1..16).map { async { RecordWatchUseCase(HistoryRepository(database)).start(movieId, 100) } }.awaitAll()
        }
        assertEquals(1, ids.count { it != null })
        assertEquals(1, histories.observeByMovieId(movieId).first().size)
        assertEquals(MovieStatus.WATCHING.value, movie().status)
    }

    @Test fun reopeningAnOlderHistoryRejectsASecondActiveRecordWithoutChangingEitherRow() = runBlocking {
        val first = requireNotNull(histories.startWatching(movieId, 100))
        histories.endWatching(first, 200)
        val old = requireNotNull(histories.getById(first))
        val second = requireNotNull(histories.startWatching(movieId, 300))
        val active = histories.getById(second)
        assertTrue(runCatching { histories.updateTimes(first, 100, null) }.isFailure)
        assertEquals(old, histories.getById(first))
        assertEquals(active, histories.getById(second))
        assertEquals(1, histories.observeByMovieId(movieId).first().count { it.endTime == null })
        assertEquals(MovieStatus.WATCHING.value, movie().status)
    }

    @Test fun timeUpdatesUseCurrentDatabaseRowAndEndingTwiceDoesNotOverwriteTheFirstEnd() = runBlocking {
        val id = requireNotNull(histories.startWatching(movieId, 100))
        val stale = requireNotNull(histories.getById(id))
        histories.updateTimes(id, 400, null)
        assertTrue(runCatching { RecordWatchUseCase(histories).end(stale, 300) }.isFailure)
        assertNull(histories.getById(id)?.endTime)
        histories.endWatching(id, 500)
        val completed = histories.getById(id)
        RecordWatchUseCase(histories).end(stale, 600)
        assertEquals(completed, histories.getById(id))
        assertEquals(100L, completed?.duration)
        assertTrue(runCatching { histories.updateTimes(id, 800, 700) }.isFailure)
        assertEquals(completed, histories.getById(id))
    }

    @Test fun statusWriteFailureRollsBackHistoryInsertAndCanBeRetried() = runBlocking {
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_status BEFORE UPDATE ON movies BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { histories.startWatching(movieId, 100) }.isFailure)
        assertTrue(histories.observeByMovieId(movieId).first().isEmpty())
        assertEquals(MovieStatus.WANT_TO_WATCH.value, movie().status)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_status")
        assertNotNull(histories.startWatching(movieId, 100))
        assertEquals(MovieStatus.WATCHING.value, movie().status)
    }

    @Test fun statusWriteFailureRollsBackHistoryEnd() = runBlocking {
        val id = requireNotNull(histories.startWatching(movieId, 100))
        val before = histories.getById(id)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_status BEFORE UPDATE ON movies BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { histories.endWatching(id, 200) }.isFailure)
        assertEquals(before, histories.getById(id))
        assertEquals(MovieStatus.WATCHING.value, movie().status)
    }

    @Test fun statusTransitionsCreateAndCloseOneSessionAndBlockReturningToWantUntilFinished() = runBlocking {
        val movies = MovieRepository(database)
        val stale = movie()
        val changeStatus = com.yingji.app.domain.usecase.UpdateStatusUseCase(movies)
        database.movieDao().update(stale.copy(name = "最新作品名称"))
        changeStatus(stale, MovieStatus.WATCHING)
        val active = histories.observeByMovieId(movieId).first().single()
        assertEquals("最新作品名称", movie().name)
        repeat(4) { changeStatus(stale, MovieStatus.WATCHING) }
        assertEquals(listOf(active), histories.observeByMovieId(movieId).first())
        assertTrue(runCatching { changeStatus(stale, MovieStatus.WANT_TO_WATCH) }.exceptionOrNull() is ActiveWatchException)
        assertEquals(listOf(active), histories.observeByMovieId(movieId).first())
        assertEquals(MovieStatus.WATCHING.value, movie().status)
        changeStatus(stale, MovieStatus.FINISHED)
        val completed = histories.observeByMovieId(movieId).first().single()
        assertNotNull(completed.endTime)
        assertEquals(active.startTime, completed.startTime)
        assertEquals(12, movie().currentEpisode)
        repeat(4) { changeStatus(stale, MovieStatus.FINISHED) }
        assertEquals(listOf(completed), histories.observeByMovieId(movieId).first())
        changeStatus(stale, MovieStatus.WANT_TO_WATCH)
        assertEquals(MovieStatus.WANT_TO_WATCH.value, movie().status)
        assertEquals(listOf(completed), histories.observeByMovieId(movieId).first())
    }

    @Test fun formSaveUsesSameSessionRulesAndRollsBackRelationsAndHistoryTogether() = runBlocking {
        val movies = MovieRepository(database)
        val category = database.categoryDao().insert(com.yingji.app.data.entity.Category(name = "分类", isSystem = false))
        val original = movie()
        assertTrue(runCatching {
            movies.updateWithRelations(original.copy(status = MovieStatus.WATCHING.value), category, setOf(Long.MAX_VALUE))
        }.isFailure)
        assertEquals(original, movie())
        assertTrue(histories.observeByMovieId(movieId).first().isEmpty())
        assertTrue(database.categoryDao().getCategoryIdsForMovie(movieId).isEmpty())
        movies.updateWithRelations(original.copy(status = MovieStatus.WATCHING.value), category, emptySet())
        val active = histories.observeByMovieId(movieId).first().single()
        assertTrue(runCatching { movies.updateWithRelations(original, category, emptySet()) }.exceptionOrNull() is ActiveWatchException)
        assertEquals(listOf(active), histories.observeByMovieId(movieId).first())
        assertEquals(MovieStatus.WATCHING.value, movie().status)
        movies.updateWithRelations(movie().copy(status = MovieStatus.FINISHED.value), category, emptySet())
        assertNotNull(histories.getById(active.id)?.endTime)
        assertEquals(MovieStatus.FINISHED.value, movie().status)
        assertEquals(listOf(category), database.categoryDao().getCategoryIdsForMovie(movieId))
    }

    @Test fun historyFailureRollsBackStatusAndDirectFinishedCreationRecordsZeroKnownDuration() = runBlocking {
        val movies = MovieRepository(database)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_history BEFORE INSERT ON watch_history BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { movies.updateStatus(movieId, MovieStatus.WATCHING) }.isFailure)
        assertEquals(MovieStatus.WANT_TO_WATCH.value, movie().status)
        assertTrue(histories.observeByMovieId(movieId).first().isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_history")
        val id = movies.insert(Movie(name = "补录完成", type = "电影", status = MovieStatus.FINISHED.value,
            totalEpisode = 8, createTime = 1))
        val completed = histories.observeByMovieId(id).first().single()
        assertEquals(completed.startTime, completed.endTime)
        assertEquals(0L, completed.duration)
        assertEquals(8, database.movieDao().getById(id)?.currentEpisode)
    }

    @Test fun sessionStatusAndTimestampsSurviveDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "watch-session-" + java.util.UUID.randomUUID() + ".db"
        var persistent = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val id = persistent.movieDao().insert(Movie(name = "持久作品", type = "电影",
                status = MovieStatus.WANT_TO_WATCH.value, createTime = 1))
            val repository = HistoryRepository(persistent)
            val historyId = requireNotNull(repository.startWatching(id, 100))
            repository.endWatching(historyId, 200)
            val completed = repository.getById(historyId)
            persistent.close()
            persistent = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(MovieStatus.FINISHED.value, persistent.movieDao().getById(id)?.status)
            assertEquals(completed, HistoryRepository(persistent).getById(historyId))
        } finally {
            persistent.close()
            context.deleteDatabase(name) // Only this test-created database.
        }
    }

    private suspend fun movie() = requireNotNull(database.movieDao().getById(movieId))
}
