package com.yingji.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieSortOption
import java.io.IOException
import com.yingji.app.data.backup.BackupPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "theme_settings")

class ThemeRepository(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.themeDataStore,
) {
    val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[THEME_MODE_KEY]
                ?.let { storedValue ->
                    ThemeMode.entries.firstOrNull { it.name == storedValue }
                }
                ?: ThemeMode.SYSTEM
        }

    val movieDisplayMode: Flow<MovieDisplayMode> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            parseMovieDisplayMode(preferences[MOVIE_DISPLAY_MODE_KEY])
        }

    val movieSortOption: Flow<MovieSortOption> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            parseMovieSortOption(preferences[MOVIE_SORT_OPTION_KEY])
        }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = themeMode.name
        }
    }

    suspend fun setMovieDisplayMode(mode: MovieDisplayMode) {
        dataStore.edit { preferences ->
            preferences[MOVIE_DISPLAY_MODE_KEY] = mode.name
        }
    }

    suspend fun setMovieSortOption(option: MovieSortOption) {
        dataStore.edit { preferences ->
            preferences[MOVIE_SORT_OPTION_KEY] = option.name
        }
    }

    suspend fun backupPreferences(): BackupPreferences {
        val p = dataStore.data.first()
        return BackupPreferences(ThemeMode.entries.firstOrNull { it.name == p[THEME_MODE_KEY] }?.name ?: ThemeMode.SYSTEM.name,
            parseMovieDisplayMode(p[MOVIE_DISPLAY_MODE_KEY]).name, parseMovieSortOption(p[MOVIE_SORT_OPTION_KEY]).name)
    }

    suspend fun applyBackupPreferences(value: BackupPreferences) {
        value.validate()
        dataStore.edit { p -> p[THEME_MODE_KEY] = value.theme; p[MOVIE_DISPLAY_MODE_KEY] = value.display; p[MOVIE_SORT_OPTION_KEY] = value.sort }
    }

    private companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val MOVIE_DISPLAY_MODE_KEY = stringPreferencesKey("movie_display_mode")
        val MOVIE_SORT_OPTION_KEY = stringPreferencesKey("movie_sort_option")
    }
}

internal fun parseMovieDisplayMode(storedValue: String?): MovieDisplayMode =
    MovieDisplayMode.entries.firstOrNull { it.name == storedValue } ?: MovieDisplayMode.LIST

internal fun parseMovieSortOption(storedValue: String?): MovieSortOption =
    MovieSortOption.entries.firstOrNull { it.name == storedValue } ?: MovieSortOption.CREATED_DESC
