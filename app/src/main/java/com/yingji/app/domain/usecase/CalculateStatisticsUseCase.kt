package com.yingji.app.domain.usecase

import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.Statistics

class CalculateStatisticsUseCase {
    operator fun invoke(
        movies: List<Movie>,
        histories: List<WatchHistory>,
    ): Statistics {
        val completedHistories = histories.filter { it.endTime != null }
        val rewatchCount = completedHistories
            .groupBy { it.movieId }
            .values
            .sumOf { movieHistories -> (movieHistories.size - 1).coerceAtLeast(0) }

        return Statistics(
            movieCount = movies.size,
            statusCounts = MovieStatus.entries.associateWith { status ->
                movies.count { it.status == status.value }
            },
            watchCount = completedHistories.size,
            rewatchCount = rewatchCount,
            totalWatchDuration = completedHistories.sumOf { it.duration.coerceAtLeast(0L) },
        )
    }
}
