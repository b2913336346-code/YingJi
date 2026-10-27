package com.yingji.app.presentation.movie

import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.model.MovieStatus

data class MovieUiState(
    val movies: List<Movie> = emptyList(),
    val isLoaded: Boolean = false,
)

data class MovieFormData(
    val name: String,
    val categoryName: String,
    val status: MovieStatus,
    val currentEpisode: Int,
    val totalEpisode: Int?,
    val posterPath: String?,
    val categoryId: Long,
    val tagIds: Set<Long>,
    val rating: Double? = null,
)

data class MovieFormActionState(
    val isSaving: Boolean = false,
    val message: String? = null,
)

data class MovieActionState(
    val isRunning: Boolean = false,
    val message: String? = null,
)
