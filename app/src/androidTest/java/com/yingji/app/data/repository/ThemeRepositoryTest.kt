package com.yingji.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieSortOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@RunWith(AndroidJUnit4::class)
class ThemeRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule
    val folder = TemporaryFolder(context.cacheDir)
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repository: ThemeRepository

    @Before
    fun resetBeforeTest() = runBlocking {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = PreferenceDataStoreFactory.create(scope = scope) {
            File(folder.root, "settings.preferences_pb")
        }
        repository = ThemeRepository(context, store)
        repository.setThemeMode(ThemeMode.SYSTEM)
        repository.setMovieDisplayMode(MovieDisplayMode.LIST)
        repository.setMovieSortOption(MovieSortOption.CREATED_DESC)
    }

    @After
    fun resetAfterTest() = runBlocking {
        scope.cancel()
        scope.coroutineContext[Job]?.join()
        Unit
    }

    @Test
    fun savesAndRestoresThemeMode() = runBlocking {
        repository.setThemeMode(ThemeMode.DARK)

        val restoredMode = ThemeRepository(context, store).themeMode.first()

        assertEquals(ThemeMode.DARK, restoredMode)
    }

    @Test
    fun savesAndRestoresMovieDisplayModeFromANewRepositoryInstance() = runBlocking {
        repository.setMovieDisplayMode(MovieDisplayMode.POSTER_WALL)

        val restoredMode = ThemeRepository(context, store).movieDisplayMode.first()

        assertEquals(MovieDisplayMode.POSTER_WALL, restoredMode)
    }

    @Test
    fun savesAndRestoresMovieSortOptionFromANewRepositoryInstance() = runBlocking {
        repository.setMovieSortOption(MovieSortOption.WATCH_END_DESC)

        val restoredOption = ThemeRepository(context, store).movieSortOption.first()

        assertEquals(MovieSortOption.WATCH_END_DESC, restoredOption)
    }

    @Test
    fun displayModeSurvivesClosingAndReopeningTheDataStore() = runBlocking {
        repository.setMovieDisplayMode(MovieDisplayMode.POSTER_WALL)
        repository.setThemeMode(ThemeMode.DARK)
        scope.cancel()
        scope.coroutineContext[Job]?.join()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = PreferenceDataStoreFactory.create(scope = scope) {
            File(folder.root, "settings.preferences_pb")
        }
        val reopened = ThemeRepository(context, store)
        assertEquals(MovieDisplayMode.POSTER_WALL, reopened.movieDisplayMode.first())
        assertEquals(ThemeMode.DARK, reopened.themeMode.first())
        assertEquals(MovieSortOption.CREATED_DESC, reopened.movieSortOption.first())
    }

    @Test
    fun sortSurvivesClosingAndReopeningWithoutChangingOtherSettings() = runBlocking {
        repository.setMovieSortOption(MovieSortOption.WATCH_START_DESC)
        scope.cancel()
        scope.coroutineContext[Job]?.join()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = PreferenceDataStoreFactory.create(scope = scope) {
            File(folder.root, "settings.preferences_pb")
        }
        val reopened = ThemeRepository(context, store)
        assertEquals(MovieSortOption.WATCH_START_DESC, reopened.movieSortOption.first())
        assertEquals(MovieDisplayMode.LIST, reopened.movieDisplayMode.first())
        assertEquals(ThemeMode.SYSTEM, reopened.themeMode.first())
    }
}
