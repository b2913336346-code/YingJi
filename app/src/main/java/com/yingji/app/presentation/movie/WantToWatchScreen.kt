package com.yingji.app.presentation.movie

import androidx.compose.runtime.Composable
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieSortOption

@Composable
fun WantToWatchScreen(
    movies: List<Movie>,
    categoryNamesByMovieId: Map<Long, String>,
    onMovieClick: (Long) -> Unit,
    onAddMovieClick: () -> Unit,
    defaultDisplayMode: MovieDisplayMode = MovieDisplayMode.LIST,
    sortOption: MovieSortOption? = null,
    onSortOptionChange: (MovieSortOption) -> Unit = {},
) {
    MovieListScreen(
        title = MovieStatus.WANT_TO_WATCH.value,
        movies = movies.filter { it.status == MovieStatus.WANT_TO_WATCH.value },
        categoryNamesByMovieId = categoryNamesByMovieId,
        onMovieClick = onMovieClick,
        onAddMovieClick = onAddMovieClick,
        defaultDisplayMode = defaultDisplayMode,
        sortOption = sortOption,
        onSortOptionChange = onSortOptionChange,
    )
}
