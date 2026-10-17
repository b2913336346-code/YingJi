package com.yingji.app.presentation.statistics

import com.yingji.app.domain.model.MovieStatus

data class StatisticsUiState(
    val movieCount: Int = 0,
    val statusCounts: Map<MovieStatus, Int> = MovieStatus.entries.associateWith { 0 },
    val watchCount: Int = 0,
    val rewatchCount: Int = 0,
    val totalWatchDuration: Long = 0L,
    val isLoaded: Boolean = false,
)
