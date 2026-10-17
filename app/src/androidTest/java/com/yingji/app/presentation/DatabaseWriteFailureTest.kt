package com.yingji.app.presentation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.data.repository.HistoryRepository
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.data.repository.PosterRepository
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.AddMovieUseCase
import com.yingji.app.domain.usecase.UpdateStatusUseCase
import com.yingji.app.presentation.history.HistoryViewModel
import com.yingji.app.presentation.movie.MovieViewModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseWriteFailureTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
    }

    @Test
    fun deleteAndStatusFailuresPublishMessagesWithoutSuccessCallback() = runBlocking {
        val movie = Movie(
            id = database.movieDao().insert(
                Movie(
                    name = "故障注入作品",
                    type = "电影",
                    status = MovieStatus.WATCHING.value,
                    currentEpisode = 8,
                    totalEpisode = 12,
                    createTime = 1L,
                ),
            ),
            name = "故障注入作品",
            type = "电影",
            status = MovieStatus.WATCHING.value,
            currentEpisode = 8,
            totalEpisode = 12,
            createTime = 1L,
        )
        val repository = MovieRepository(database)
        val viewModel = MovieViewModel(
            movieRepository = repository,
            addMovieUseCase = AddMovieUseCase(repository),
            updateStatusUseCase = UpdateStatusUseCase(repository),
            posterRepository = PosterRepository(context),
        )
        database.close()

        viewModel.updateStatus(movie, MovieStatus.FINISHED)
        assertEquals(
            "状态更新失败，请重试",
            awaitMovieMessage(viewModel),
        )

        viewModel.consumeActionMessage()
        var deleteCompleted = false
        viewModel.deleteMovie(movie) { deleteCompleted = true }
        assertEquals(
            "删除失败，请稍后重试",
            awaitMovieMessage(viewModel),
        )
        assertFalse(deleteCompleted)
    }

    @Test
    fun historyWriteFailuresPublishOneRetryableMessage() = runBlocking {
        val repository = HistoryRepository(database.watchHistoryDao())
        val viewModel = HistoryViewModel(repository)
        val history = WatchHistory(
            id = 1L,
            movieId = 1L,
            startTime = 1_000L,
            watchNumber = 1,
        )
        database.close()

        repeat(8) { viewModel.startWatching(1L) }
        assertEquals("观看记录保存失败，请重试", awaitHistoryMessage(viewModel))

        viewModel.consumeActionMessage()
        viewModel.endWatching(history)
        assertEquals("观看记录保存失败，请重试", awaitHistoryMessage(viewModel))

        viewModel.consumeActionMessage()
        viewModel.updateTimes(history, startTime = 2_000L, endTime = 3_000L)
        assertEquals("观看记录保存失败，请重试", awaitHistoryMessage(viewModel))
    }

    private suspend fun awaitMovieMessage(viewModel: MovieViewModel): String = withTimeout(5_000L) {
        requireNotNull(viewModel.actionState.filter { it.message != null }.first().message)
    }

    private suspend fun awaitHistoryMessage(viewModel: HistoryViewModel): String =
        withTimeout(5_000L) {
            requireNotNull(viewModel.actionState.filter { it.message != null }.first().message)
        }
}
