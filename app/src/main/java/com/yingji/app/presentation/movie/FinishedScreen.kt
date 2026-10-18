package com.yingji.app.presentation.movie

import androidx.compose.runtime.Composable
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieSortOption

@Composable
fun FinishedScreen(
    movies: List<Movie>,
    categoryNamesByMovieId: Map<Long, String>,
    onMovieClick: (Long) -> Unit,
    onAddMovieClick: () -> Unit,
    defaultDisplayMode: MovieDisplayMode = MovieDisplayMode.LIST,
    sortOption: MovieSortOption? = null,
    onSortOptionChange: (MovieSortOption) -> Unit = {},
) {
    MovieListScreen(
        title = MovieStatus.FINISHED.value,
        movies = movies.filter { it.status == MovieStatus.FINISHED.value },
        categoryNamesByMovieId = categoryNamesByMovieId,
        onMovieClick = onMovieClick,
        onAddMovieClick = onAddMovieClick,
        defaultDisplayMode = defaultDisplayMode,
        sortOption = sortOption,
        onSortOptionChange = onSortOptionChange,
    )
}
