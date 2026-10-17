package com.yingji.app.presentation.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yingji.app.data.repository.HistoryRepository
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.usecase.CalculateStatisticsUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class StatisticsViewModel(
    movieRepository: MovieRepository,
    historyRepository: HistoryRepository,
    calculateStatistics: CalculateStatisticsUseCase,
) : ViewModel() {
    val uiState = combine(
        movieRepository.observeAll(),
        historyRepository.observeAll(),
    ) { movies, histories ->
        val statistics = calculateStatistics(movies, histories)
        StatisticsUiState(
            movieCount = statistics.movieCount,
            statusCounts = statistics.statusCounts,
            watchCount = statistics.watchCount,
            rewatchCount = statistics.rewatchCount,
            totalWatchDuration = statistics.totalWatchDuration,
            isLoaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatisticsUiState(),
    )

    companion object {
        fun factory(
            movieRepository: MovieRepository,
            historyRepository: HistoryRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                StatisticsViewModel(
                    movieRepository = movieRepository,
                    historyRepository = historyRepository,
                    calculateStatistics = CalculateStatisticsUseCase(),
                ) as T
        }
    }
}
