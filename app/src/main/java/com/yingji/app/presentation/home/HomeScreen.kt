package com.yingji.app.presentation.home

import androidx.compose.runtime.Composable
import com.yingji.app.data.entity.Movie
import com.yingji.app.presentation.movie.MovieListScreen
import com.yingji.app.domain.model.MovieDisplayMode

@Composable
fun HomeScreen(
    movies: List<Movie>,
    categoryNamesByMovieId: Map<Long, String>,
    onMovieClick: (Long) -> Unit,
    onAddMovieClick: () -> Unit,
    onSearchClick: () -> Unit,
    defaultDisplayMode: MovieDisplayMode = MovieDisplayMode.LIST,
) {
    MovieListScreen(
        title = "首页",
        movies = movies,
        categoryNamesByMovieId = categoryNamesByMovieId,
        onMovieClick = onMovieClick,
        onAddMovieClick = onAddMovieClick,
        onSearchClick = onSearchClick,
        defaultDisplayMode = defaultDisplayMode,
    )
}
