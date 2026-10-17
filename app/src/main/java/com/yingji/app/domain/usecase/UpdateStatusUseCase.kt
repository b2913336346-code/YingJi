package com.yingji.app.domain.usecase

import com.yingji.app.data.entity.Movie
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.model.MovieStatus

class UpdateStatusUseCase(
    private val movieRepository: MovieRepository,
) {
    suspend operator fun invoke(movie: Movie, status: MovieStatus) {
        val currentStatus = MovieStatus.entries.firstOrNull { it.value == movie.status }
        movieRepository.update(
            movie.copy(
                status = status.value,
                currentEpisode = resolveCurrentEpisodeForStatusTransition(
                    currentStatus = currentStatus,
                    newStatus = status,
                    currentEpisode = movie.currentEpisode,
                    totalEpisode = movie.totalEpisode,
                ),
            ),
        )
    }
}

internal fun resolveCurrentEpisodeForStatusTransition(
    currentStatus: MovieStatus?,
    newStatus: MovieStatus,
    currentEpisode: Int,
    totalEpisode: Int?,
): Int = if (
    currentStatus != MovieStatus.FINISHED &&
    newStatus == MovieStatus.FINISHED &&
    totalEpisode != null &&
    totalEpisode > 0
) {
    totalEpisode
} else {
    currentEpisode
}
