package com.yingji.app.presentation.movie

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.data.repository.PosterRepository
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.AddMovieUseCase
import com.yingji.app.domain.usecase.UpdateStatusUseCase
import com.yingji.app.presentation.common.runDatabaseWriteAction
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MovieViewModel(
    private val movieRepository: MovieRepository,
    private val addMovieUseCase: AddMovieUseCase,
    private val updateStatusUseCase: UpdateStatusUseCase,
    private val posterRepository: PosterRepository,
) : ViewModel() {
    private val _formActionState = MutableStateFlow(MovieFormActionState())
    val formActionState: StateFlow<MovieFormActionState> = _formActionState.asStateFlow()
    private val _actionState = MutableStateFlow(MovieActionState())
    val actionState: StateFlow<MovieActionState> = _actionState.asStateFlow()

    val uiState = movieRepository.observeAll()
        .map { movies ->
            MovieUiState(movies = movies, isLoaded = true)
        }
        .catch { exception ->
            if (exception is CancellationException) throw exception
            DatabaseHealthMonitor.reportIfCorruption(exception)
            Log.e(TAG, "Failed to load movies", exception)
            emit(MovieUiState(isLoaded = false))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MovieUiState(),
        )

    fun addMovie(data: MovieFormData, onComplete: () -> Unit) {
        if (_formActionState.value.isSaving) return
        _formActionState.value = MovieFormActionState(isSaving = true)
        viewModelScope.launch {
            var posterPath: String? = null
            try {
                posterPath = posterRepository.persist(data.posterPath)
                addMovieUseCase(
                    name = data.name,
                    categoryName = data.categoryName,
                    status = data.status,
                    currentEpisode = data.currentEpisode,
                    totalEpisode = data.totalEpisode,
                    posterPath = posterPath,
                    categoryId = data.categoryId,
                    tagIds = data.tagIds,
                )
                _formActionState.value = MovieFormActionState()
                onComplete()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (posterPath != data.posterPath) posterRepository.delete(posterPath)
                Log.e(TAG, "Failed to add movie with relations", exception)
                _formActionState.value = MovieFormActionState(message = "保存失败，请重试")
            }
        }
    }

    fun updateMovie(movie: Movie, data: MovieFormData, onComplete: () -> Unit) {
        if (_formActionState.value.isSaving) return
        _formActionState.value = MovieFormActionState(isSaving = true)
        viewModelScope.launch {
            var posterPath = movie.posterPath
            try {
                posterPath = if (data.posterPath == movie.posterPath) {
                    movie.posterPath
                } else if (data.posterPath == null) {
                    null
                } else {
                    posterRepository.persist(data.posterPath) ?: movie.posterPath
                }
                movieRepository.updateWithRelations(
                    movie = movie.copy(
                        name = data.name.trim(),
                        type = data.categoryName.trim(),
                        status = data.status.value,
                        currentEpisode = data.currentEpisode,
                        totalEpisode = data.totalEpisode,
                        posterPath = posterPath,
                    ),
                    categoryId = data.categoryId,
                    tagIds = data.tagIds,
                )
                if (movie.posterPath != posterPath) posterRepository.delete(movie.posterPath)
                _formActionState.value = MovieFormActionState()
                onComplete()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (posterPath != movie.posterPath && posterPath != data.posterPath) {
                    posterRepository.delete(posterPath)
                }
                Log.e(TAG, "Failed to update movie with relations", exception)
                _formActionState.value = MovieFormActionState(message = "保存失败，请重试")
            }
        }
    }

    fun consumeFormMessage() {
        _formActionState.value = MovieFormActionState()
    }

    fun deleteMovie(movie: Movie, onComplete: () -> Unit) {
        runMovieAction(
            failureMessage = "删除失败，请稍后重试",
            onSuccess = onComplete,
        ) {
            movieRepository.delete(movie)
            posterRepository.delete(movie.posterPath)
        }
    }

    fun updateStatus(movie: Movie, status: MovieStatus) {
        runMovieAction(failureMessage = "状态更新失败，请重试") {
            updateStatusUseCase(movie, status)
        }
    }

    fun consumeActionMessage() {
        _actionState.value = MovieActionState()
    }

    private fun runMovieAction(
        failureMessage: String,
        onSuccess: () -> Unit = {},
        action: suspend () -> Unit,
    ) {
        if (_actionState.value.isRunning) return
        _actionState.value = MovieActionState(isRunning = true)
        viewModelScope.launch {
            try {
                val succeeded = runDatabaseWriteAction(
                    action = action,
                    onFailure = { exception ->
                        Log.e(TAG, failureMessage, exception)
                        _actionState.value = MovieActionState(message = failureMessage)
                    },
                )
                if (succeeded) {
                    _actionState.value = MovieActionState()
                    onSuccess()
                }
            } catch (exception: CancellationException) {
                _actionState.value = MovieActionState()
                throw exception
            }
        }
    }

    companion object {
        private const val TAG = "MovieViewModel"

        fun factory(
            movieRepository: MovieRepository,
            posterRepository: PosterRepository,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MovieViewModel(
                        movieRepository = movieRepository,
                        addMovieUseCase = AddMovieUseCase(movieRepository),
                        updateStatusUseCase = UpdateStatusUseCase(movieRepository),
                        posterRepository = posterRepository,
                    ) as T
            }
    }
}
