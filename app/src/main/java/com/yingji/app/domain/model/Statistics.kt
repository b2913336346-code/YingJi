package com.yingji.app.domain.model

data class Statistics(
    val movieCount: Int,
    val statusCounts: Map<MovieStatus, Int>,
    val watchCount: Int,
    val rewatchCount: Int,
    val totalWatchDuration: Long,
)
