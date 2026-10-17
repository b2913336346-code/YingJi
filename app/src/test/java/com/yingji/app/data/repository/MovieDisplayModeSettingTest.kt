package com.yingji.app.data.repository

import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.presentation.movie.toggled
import org.junit.Assert.assertEquals
import org.junit.Test

class MovieDisplayModeSettingTest {
    @Test
    fun missingOrUnknownSettingUsesListMode() {
        assertEquals(MovieDisplayMode.LIST, parseMovieDisplayMode(null))
        assertEquals(MovieDisplayMode.LIST, parseMovieDisplayMode("UNKNOWN"))
    }

    @Test
    fun storedPosterWallModeIsRestored() {
        assertEquals(
            MovieDisplayMode.POSTER_WALL,
            parseMovieDisplayMode(MovieDisplayMode.POSTER_WALL.name),
        )
    }

    @Test
    fun toggleChangesOnlyTheDisplayMode() {
        assertEquals(MovieDisplayMode.POSTER_WALL, MovieDisplayMode.LIST.toggled())
        assertEquals(MovieDisplayMode.LIST, MovieDisplayMode.POSTER_WALL.toggled())
    }

    @Test
    fun defaultAndStoredMovieSortOptionsAreParsed() {
        assertEquals(MovieSortOption.CREATED_DESC, parseMovieSortOption(null))
        assertEquals(MovieSortOption.CREATED_DESC, parseMovieSortOption("UNKNOWN"))
        assertEquals(
            MovieSortOption.WATCH_END_DESC,
            parseMovieSortOption(MovieSortOption.WATCH_END_DESC.name),
        )
    }
}
