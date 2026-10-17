package com.yingji.app.presentation.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yingji.app.data.repository.ThemeRepository
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieSortOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val themeRepository: ThemeRepository,
) : ViewModel() {
    val themeMode = themeRepository.themeMode
        .catch { exception ->
            if (exception is CancellationException) throw exception
            Log.e(TAG, "Failed to load theme setting", exception)
            emit(ThemeMode.SYSTEM)
        }
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemeMode.SYSTEM,
    )

    val movieDisplayMode = themeRepository.movieDisplayMode
        .catch { exception ->
            if (exception is CancellationException) throw exception
            Log.e(TAG, "Failed to load movie display setting", exception)
            emit(MovieDisplayMode.LIST)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MovieDisplayMode.LIST,
        )

    val movieSortOption = themeRepository.movieSortOption
        .catch { exception ->
            if (exception is CancellationException) throw exception
            Log.e(TAG, "Failed to load movie sort setting", exception)
            emit(MovieSortOption.CREATED_DESC)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MovieSortOption.CREATED_DESC,
        )

    fun setThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            try {
                themeRepository.setThemeMode(themeMode)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to save theme setting", exception)
            }
        }
    }

    fun setMovieDisplayMode(mode: MovieDisplayMode) {
        viewModelScope.launch {
            try {
                themeRepository.setMovieDisplayMode(mode)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to save movie display setting", exception)
            }
        }
    }

    fun setMovieSortOption(option: MovieSortOption) {
        viewModelScope.launch {
            try {
                themeRepository.setMovieSortOption(option)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to save movie sort setting", exception)
            }
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"

        fun factory(themeRepository: ThemeRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(themeRepository) as T
            }
    }
}
