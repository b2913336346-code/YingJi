package com.yingji.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTest {
    @Test
    fun collectionRoutesUseStableIds() {
        assertEquals("category/12", Screen.CategoryMovies.createRoute(12L))
        assertEquals("tag/34", Screen.TagMovies.createRoute(34L))
    }
}
