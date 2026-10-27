package com.yingji.app.presentation.movie

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.data.repository.ActiveWatchException
import com.yingji.app.data.repository.PosterSaveException
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class MovieViewModel(
    private val movieRepository: MovieRepository,
    private val addMovieUseCase: AddMovieUseCase,
    private val updateStatusUseCase: UpdateStatusUseCase,
    private val posterRepository: PosterRepository,
    private val addRecommendedWork: com.yingji.app.domain.ai.AddRecommendedWorkUseCase? = null,
    private val importTmdbWork: (suspend (com.yingji.app.domain.ai.RecommendedWorkInput) -> Long)? = null,
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

    fun addMovie(data: MovieFormData, recommendedIdentity: com.yingji.app.domain.ai.CandidateIdentity? = null, onComplete: () -> Unit) {
        if (_formActionState.value.isSaving) return
        _formActionState.value = MovieFormActionState(isSaving = true)
        viewModelScope.launch {
            var posterPath: String? = null
            try {
                posterPath = posterRepository.persist(data.posterPath)
                if (recommendedIdentity != null) {
                    val input = com.yingji.app.domain.ai.RecommendedWorkInput(recommendedIdentity, confirmed = true,
                        name = data.name, categoryId = data.categoryId, categoryName = data.categoryName, tagIds = data.tagIds,
                        status = data.status, currentEpisode = data.currentEpisode, totalEpisode = data.totalEpisode,
                        posterPath = posterPath, rating = data.rating, allowMock = recommendedIdentity.sourceId != "tmdb")
                    if (recommendedIdentity.sourceId == "tmdb") {
                        val import = importTmdbWork ?: throw com.yingji.app.domain.ai.CandidateImportException("TMDB 入库暂不可用，请重试")
                        import(input)
                    } else {
                        val import = addRecommendedWork ?: throw com.yingji.app.domain.ai.CandidateImportException("候选入库暂不可用，请重试")
                        import(input)
                    }
                } else addMovieUseCase(
                    name = data.name,
                    categoryName = data.categoryName,
                    status = data.status,
                    currentEpisode = data.currentEpisode,
                    totalEpisode = data.totalEpisode,
                    posterPath = posterPath,
                    categoryId = data.categoryId,
                    tagIds = data.tagIds,
                    rating = data.rating,
                )
            } catch (exception: CancellationException) {
                deleteUnusedPoster(posterPath)
                _formActionState.value = MovieFormActionState()
                throw exception
            } catch (exception: Exception) {
                deleteUnusedPoster(posterPath)
                Log.e(TAG, "Failed to add movie with relations", exception)
                _formActionState.value = MovieFormActionState(message = formFailureMessage(exception))
                return@launch
            }
            _formActionState.value = MovieFormActionState()
            onComplete()
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
                } else {
                    posterRepository.persist(data.posterPath)
                }
                movieRepository.updateWithRelations(
                    movie = movie.copy(
                        name = data.name.trim(),
                        type = data.categoryName.trim(),
                        status = data.status.value,
                        currentEpisode = data.currentEpisode,
                        totalEpisode = data.totalEpisode,
                        posterPath = posterPath,
                        rating = data.rating,
                    ),
                    categoryId = data.categoryId,
                    tagIds = data.tagIds,
                )
            } catch (exception: CancellationException) {
                if (posterPath != movie.posterPath) deleteUnusedPoster(posterPath)
                _formActionState.value = MovieFormActionState()
                throw exception
            } catch (exception: Exception) {
                if (posterPath != movie.posterPath) deleteUnusedPoster(posterPath)
                Log.e(TAG, "Failed to update movie with relations", exception)
                _formActionState.value = MovieFormActionState(message = formFailureMessage(exception))
                return@launch
            }
            if (movie.posterPath != posterPath) deleteUnusedPoster(movie.posterPath)
            _formActionState.value = MovieFormActionState()
            onComplete()
        }
    }

    private suspend fun deleteUnusedPoster(path: String?) {
        if (path == null) return
        withContext(NonCancellable) {
            // If reference checking fails, retain the file instead of risking a live poster.
            runCatching {
                if (!movieRepository.isPosterReferenced(path)) posterRepository.delete(path)
            }
        }
    }

    private fun formFailureMessage(exception: Exception): String =
        if (exception is PosterSaveException || exception is ActiveWatchException || exception is com.yingji.app.domain.ai.CandidateImportException) requireNotNull(exception.message) else "保存失败，请重试"

    fun consumeFormMessage() {
        if (!_formActionState.value.isSaving) _formActionState.value = MovieFormActionState()
    }

    fun deleteMovie(movie: Movie, onComplete: () -> Unit) {
        runMovieAction(
            failureMessage = "删除失败，请稍后重试",
            onSuccess = onComplete,
        ) {
            movieRepository.delete(movie)
            deleteUnusedPoster(movie.posterPath)
        }
    }

    fun updateStatus(movie: Movie, status: MovieStatus) {
        runMovieAction(failureMessage = "状态更新失败，请重试") {
            updateStatusUseCase(movie, status)
        }
    }

    fun consumeActionMessage() {
        _actionState.value = _actionState.value.copy(message = null)
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
                        _actionState.value = MovieActionState(message = if (exception is ActiveWatchException) exception.message else failureMessage)
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
            addRecommendedWork: com.yingji.app.domain.ai.AddRecommendedWorkUseCase? = null,
            importTmdbWork: (suspend (com.yingji.app.domain.ai.RecommendedWorkInput) -> Long)? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MovieViewModel(
                        movieRepository = movieRepository,
                        addMovieUseCase = AddMovieUseCase(movieRepository),
                        updateStatusUseCase = UpdateStatusUseCase(movieRepository),
                        posterRepository = posterRepository,
                        addRecommendedWork = addRecommendedWork,
                        importTmdbWork = importTmdbWork,
                    ) as T
            }
    }
}
