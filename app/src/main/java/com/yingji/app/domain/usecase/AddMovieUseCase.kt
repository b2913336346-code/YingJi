package com.yingji.app.domain.usecase

import com.yingji.app.data.entity.Movie
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.model.MovieStatus

class AddMovieUseCase(
    private val movieRepository: MovieRepository,
) {
    suspend operator fun invoke(
        name: String,
        categoryName: String,
        status: MovieStatus,
        currentEpisode: Int,
        totalEpisode: Int?,
        posterPath: String?,
        categoryId: Long,
        tagIds: Set<Long>,
    ): Long = movieRepository.insertWithRelations(
        movie = Movie(
            name = name.trim(),
            type = categoryName.trim(),
            status = status.value,
            currentEpisode = currentEpisode,
            totalEpisode = totalEpisode,
            posterPath = posterPath,
            createTime = System.currentTimeMillis(),
        ),
        categoryId = categoryId,
        tagIds = tagIds,
    )
}
