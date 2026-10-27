package com.yingji.app.domain.ai

/** Transient provider metadata; never a Movie.posterPath or a persisted image. */
data class TmdbPoster(val path: String) {
    init { require(Regex("/[A-Za-z0-9_-]{1,120}\\.(jpg|jpeg|png)").matches(path)) }

    // A documented TMDB image size, bounded again by the memory-only reader.
    val previewUrl: String get() = "https://image.tmdb.org/t/p/w500" + path
}
