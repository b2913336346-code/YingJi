package com.yingji.app.domain.usecase

import com.yingji.app.domain.model.isValidMovieRating
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieRatingTest {
    @Test fun acceptsUnratedAndEveryHalfPointIncludingBothBoundaries() {
        assertTrue(isValidMovieRating(null))
        (0..20).forEach { assertTrue(isValidMovieRating(it / 2.0)) }
    }

    @Test fun rejectsOutOfRangeNonFiniteAndNonHalfPointValues() {
        listOf(-0.5, 10.5, 0.1, 8.25, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY).forEach { assertFalse(it.toString(), isValidMovieRating(it)) }
    }
}
