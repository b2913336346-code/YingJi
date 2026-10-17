package com.yingji.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieSortOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = ThemeRepository(context)

    @Before
    fun resetBeforeTest() = runBlocking {
        repository.setThemeMode(ThemeMode.SYSTEM)
        repository.setMovieDisplayMode(MovieDisplayMode.LIST)
        repository.setMovieSortOption(MovieSortOption.CREATED_DESC)
    }

    @After
    fun resetAfterTest() = runBlocking {
        repository.setThemeMode(ThemeMode.SYSTEM)
        repository.setMovieDisplayMode(MovieDisplayMode.LIST)
        repository.setMovieSortOption(MovieSortOption.CREATED_DESC)
    }

    @Test
    fun savesAndRestoresThemeMode() = runBlocking {
        repository.setThemeMode(ThemeMode.DARK)

        val restoredMode = ThemeRepository(context).themeMode.first()

        assertEquals(ThemeMode.DARK, restoredMode)
    }

    @Test
    fun savesAndRestoresMovieDisplayModeFromANewRepositoryInstance() = runBlocking {
        repository.setMovieDisplayMode(MovieDisplayMode.POSTER_WALL)

        val restoredMode = ThemeRepository(context).movieDisplayMode.first()

        assertEquals(MovieDisplayMode.POSTER_WALL, restoredMode)
    }

    @Test
    fun savesAndRestoresMovieSortOptionFromANewRepositoryInstance() = runBlocking {
        repository.setMovieSortOption(MovieSortOption.WATCH_END_DESC)

        val restoredOption = ThemeRepository(context).movieSortOption.first()

        assertEquals(MovieSortOption.WATCH_END_DESC, restoredOption)
    }
}
