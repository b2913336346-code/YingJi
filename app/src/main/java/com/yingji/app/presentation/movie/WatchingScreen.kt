package com.yingji.app.presentation.movie

import androidx.compose.runtime.Composable
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.MovieDisplayMode

@Composable
fun WatchingScreen(
    movies: List<Movie>,
    categoryNamesByMovieId: Map<Long, String>,
    onMovieClick: (Long) -> Unit,
    onAddMovieClick: () -> Unit,
    defaultDisplayMode: MovieDisplayMode = MovieDisplayMode.LIST,
) {
    MovieListScreen(
        title = MovieStatus.WATCHING.value,
        movies = movies.filter { it.status == MovieStatus.WATCHING.value },
        categoryNamesByMovieId = categoryNamesByMovieId,
        onMovieClick = onMovieClick,
        onAddMovieClick = onAddMovieClick,
        defaultDisplayMode = defaultDisplayMode,
    )
}
