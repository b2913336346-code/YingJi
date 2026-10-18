package com.yingji.app.presentation.movie

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.FilterAndSortMoviesUseCase
import com.yingji.app.domain.usecase.MovieSearchCriteria
import com.yingji.app.ui.theme.YingJiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MovieSortScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun sortMenuReordersBothLayoutsAndKeepsMovieClickIdentity() {
        val sort = mutableStateOf(MovieSortOption.CREATED_DESC)
        val clicked = mutableListOf<Long>()
        val requests = mutableListOf<MovieSortOption>()
        val source = listOf(1L, 2L, 3L).map {
            Movie(id = it, name = "作品$it", type = "电影", status = MovieStatus.WANT_TO_WATCH.value, createTime = it)
        }
        composeRule.setContent {
            YingJiTheme {
                MovieListScreen(
                    title = "排序验收",
                    movies = FilterAndSortMoviesUseCase()(source, emptyList(), emptyList(), emptyList(), MovieSearchCriteria(sortOption = sort.value)),
                    onMovieClick = { clicked.add(it) },
                    sortOption = sort.value,
                    onSortOptionChange = { sort.value = it; requests.add(it) },
                )
            }
        }
        assertFirstMovie("作品3")
        composeRule.onNodeWithContentDescription("作品排序").performClick()
        composeRule.onNodeWithText("未记录观看时间的作品排在最后").assertExists()
        composeRule.onNodeWithText(MovieSortOption.CREATED_ASC.displayName).performClick()
        assertFirstMovie("作品1")
        composeRule.onNode(hasClickAction() and hasText("作品1")).performClick()
        composeRule.onNodeWithContentDescription("切换到海报墙").performClick()
        val first = composeRule.onNode(hasClickAction() and hasText("作品1")).fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNode(hasClickAction() and hasText("作品2")).fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(first.left < second.left)
        composeRule.onNode(hasClickAction() and hasText("作品1")).performClick()
        composeRule.onNodeWithContentDescription("作品排序").performClick()
        composeRule.onNodeWithText(MovieSortOption.WATCH_START_DESC.displayName).performClick()
        composeRule.onNodeWithText("排序：观看开始降序").assertExists()
        composeRule.runOnIdle {
            assertEquals(listOf(1L, 1L), clicked)
            assertEquals(listOf(MovieSortOption.CREATED_ASC, MovieSortOption.WATCH_START_DESC), requests)
        }
    }

    private fun assertFirstMovie(name: String) {
        val list = composeRule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        list.performScrollToIndex(0)
        val top = composeRule.onNode(hasClickAction() and hasText(name)).fetchSemanticsNode().boundsInRoot.top
        val nextTop = composeRule.onNode(hasClickAction() and hasText("作品2")).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Expected $name to be first", top < nextTop)
    }
}
