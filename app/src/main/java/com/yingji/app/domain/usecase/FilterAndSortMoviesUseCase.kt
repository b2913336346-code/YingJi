package com.yingji.app.domain.usecase

import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieCategory
import com.yingji.app.data.entity.MovieTag
import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.WatchTimeField

data class MovieSearchCriteria(
    val query: String = "",
    val categoryId: Long? = null,
    val tagId: Long? = null,
    val status: MovieStatus? = null,
    val timeField: WatchTimeField? = null,
    val fromTime: Long? = null,
    val toTime: Long? = null,
    val sortOption: MovieSortOption = MovieSortOption.CREATED_DESC,
)

class FilterAndSortMoviesUseCase(
    private val searchMovies: SearchMoviesUseCase = SearchMoviesUseCase(),
) {
    operator fun invoke(
        movies: List<Movie>,
        histories: List<WatchHistory>,
        movieCategories: List<MovieCategory>,
        movieTags: List<MovieTag>,
        criteria: MovieSearchCriteria,
    ): List<Movie> {
        val categoryMovieIds = criteria.categoryId?.let { selectedId ->
            movieCategories.asSequence()
                .filter { it.categoryId == selectedId }
                .mapTo(mutableSetOf()) { it.movieId }
        }
        val tagMovieIds = criteria.tagId?.let { selectedId ->
            movieTags.asSequence()
                .filter { it.tagId == selectedId }
                .mapTo(mutableSetOf()) { it.movieId }
        }
        val historiesByMovieId = histories.groupBy(WatchHistory::movieId)

        val filtered = movies.filter { movie ->
            (categoryMovieIds == null || movie.id in categoryMovieIds) &&
                (tagMovieIds == null || movie.id in tagMovieIds) &&
                (criteria.status == null || movie.status == criteria.status.value) &&
                matchesTimeRange(historiesByMovieId[movie.id].orEmpty(), criteria)
        }
        val keywordMatched = searchMovies(filtered, criteria.query)
        return sortMovies(keywordMatched, historiesByMovieId, criteria.sortOption)
    }

    private fun matchesTimeRange(
        histories: List<WatchHistory>,
        criteria: MovieSearchCriteria,
    ): Boolean {
        val field = criteria.timeField ?: return true
        return histories.any { history ->
            val timestamp = when (field) {
                WatchTimeField.START_TIME -> history.startTime
                WatchTimeField.END_TIME -> history.endTime
            } ?: return@any false
            (criteria.fromTime == null || timestamp >= criteria.fromTime) &&
                (criteria.toTime == null || timestamp <= criteria.toTime)
        }
    }

    private fun sortMovies(
        movies: List<Movie>,
        historiesByMovieId: Map<Long, List<WatchHistory>>,
        option: MovieSortOption,
    ): List<Movie> = when (option) {
        MovieSortOption.CREATED_DESC -> movies.sortedWith(
            compareByDescending<Movie> { it.createTime }.thenByDescending { it.id },
        )
        MovieSortOption.CREATED_ASC -> movies.sortedWith(
            compareBy<Movie> { it.createTime }.thenBy { it.id },
        )
        MovieSortOption.NAME_ASC -> movies.sortedWith(
            compareBy<Movie> { it.name.lowercase() }.thenByDescending { it.createTime }
                .thenByDescending { it.id },
        )
        MovieSortOption.WATCH_START_DESC -> movies.sortedWith(
            compareByDescending<Movie> { movie ->
                historiesByMovieId[movie.id]?.maxOfOrNull(WatchHistory::startTime)
                    ?: Long.MIN_VALUE
            }.thenByDescending { it.createTime }.thenByDescending { it.id },
        )
        MovieSortOption.WATCH_END_DESC -> movies.sortedWith(
            compareByDescending<Movie> { movie ->
                historiesByMovieId[movie.id]?.mapNotNull(WatchHistory::endTime)?.maxOrNull()
                    ?: Long.MIN_VALUE
            }.thenByDescending { it.createTime }.thenByDescending { it.id },
        )
    }
}
