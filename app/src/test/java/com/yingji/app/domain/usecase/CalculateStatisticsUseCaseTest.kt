package com.yingji.app.domain.usecase

import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.domain.model.MovieStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CalculateStatisticsUseCaseTest {
    private val calculateStatistics = CalculateStatisticsUseCase()

    @Test
    fun countsOnlyCompletedHistoriesAndCalculatesRewatches() {
        val movies = listOf(
            movie(id = 1, status = MovieStatus.WANT_TO_WATCH),
            movie(id = 2, status = MovieStatus.WATCHING),
            movie(id = 3, status = MovieStatus.FINISHED),
        )
        val histories = listOf(
            history(movieId = 1, watchNumber = 1, duration = 60_000L),
            history(movieId = 1, watchNumber = 2, duration = 120_000L),
            history(movieId = 2, watchNumber = 1, duration = 180_000L),
            history(movieId = 3, watchNumber = 1, duration = 0L, completed = false),
        )

        val result = calculateStatistics(movies, histories)

        assertEquals(3, result.movieCount)
        assertEquals(1, result.statusCounts[MovieStatus.WANT_TO_WATCH])
        assertEquals(1, result.statusCounts[MovieStatus.WATCHING])
        assertEquals(1, result.statusCounts[MovieStatus.FINISHED])
        assertEquals(3, result.watchCount)
        assertEquals(1, result.rewatchCount)
        assertEquals(360_000L, result.totalWatchDuration)
    }

    private fun movie(id: Long, status: MovieStatus) = Movie(
        id = id,
        name = "作品$id",
        type = "电影",
        status = status.value,
        createTime = 0L,
    )

    private fun history(
        movieId: Long,
        watchNumber: Int,
        duration: Long,
        completed: Boolean = true,
    ) = WatchHistory(
        movieId = movieId,
        startTime = 0L,
        endTime = if (completed) duration else null,
        duration = duration,
        watchNumber = watchNumber,
    )
}
