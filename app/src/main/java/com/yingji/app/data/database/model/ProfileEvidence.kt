package com.yingji.app.data.database.model

// Only fields needed by local profile computation, never titles or poster paths.
data class RatingFeatureRow(val movieId: Long, val featureId: String, val label: String, val rating: Double)
data class BehaviorFeatureRow(
    val movieId: Long,
    val featureId: String,
    val label: String,
    val completedWatches: Int,
    val repeatWatches: Int,
)
