package com.yingji.app.domain.usecase

import com.yingji.app.data.entity.Movie
import com.yingji.app.utils.PinyinUtils

class SearchMoviesUseCase {
    operator fun invoke(movies: List<Movie>, query: String): List<Movie> {
        val normalizedQuery = PinyinUtils.normalize(query)
        if (normalizedQuery.isBlank()) return movies

        return movies.filter { movie ->
            val normalizedName = PinyinUtils.normalize(movie.name)
            normalizedName.contains(normalizedQuery) ||
                PinyinUtils.fullVariants(movie.name).any { it.contains(normalizedQuery) } ||
                PinyinUtils.initialVariants(movie.name).any { it.contains(normalizedQuery) }
        }
    }
}
