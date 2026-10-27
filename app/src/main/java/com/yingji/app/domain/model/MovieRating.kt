package com.yingji.app.domain.model

fun isValidMovieRating(rating: Double?): Boolean = rating == null ||
    (rating.isFinite() && rating in 0.0..10.0 && rating * 2.0 % 1.0 == 0.0)

fun formatMovieRating(rating: Double?): String = when {
    rating == null -> "未评分"
    rating % 1.0 == 0.0 -> rating.toInt().toString()
    else -> rating.toString()
}
