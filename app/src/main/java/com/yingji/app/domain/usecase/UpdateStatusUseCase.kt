package com.yingji.app.domain.usecase

import com.yingji.app.data.entity.Movie
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.model.MovieStatus

class UpdateStatusUseCase(
    private val movieRepository: MovieRepository,
) {
    suspend operator fun invoke(movie: Movie, status: MovieStatus) {
        movieRepository.updateStatus(movie.id, status)
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
