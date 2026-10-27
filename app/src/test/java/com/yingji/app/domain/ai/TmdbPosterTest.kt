package com.yingji.app.domain.ai

import org.junit.Assert.*
import org.junit.Test

class TmdbPosterTest {
    @Test fun onlyProviderRelativeImagePathsCanBecomeOfficialPreviewUrls() {
        assertEquals("https://image.tmdb.org/t/p/w500/fictionalPoster_7.jpg", TmdbPoster("/fictionalPoster_7.jpg").previewUrl)
        assertEquals("https://image.tmdb.org/t/p/w500/test-image.png", TmdbPoster("/test-image.png").previewUrl)
    }
    @Test fun arbitraryHostsTraversalQueriesCredentialsAndUnsupportedFormatsAreRejected() {
        val invalid = listOf("", "poster.jpg", "https://example.invalid/a.jpg", "//example.invalid/a.jpg",
            "/../a.jpg", "/a/b.jpg", "/a.jpg?key=value", "/a.jpg#fragment", "/a%2f.jpg", "/a.svg", "/a.jpg\n",
            "/" + "a".repeat(121) + ".jpg")
        for (path in invalid) assertTrue(path, runCatching { TmdbPoster(path) }.isFailure)
    }
}
