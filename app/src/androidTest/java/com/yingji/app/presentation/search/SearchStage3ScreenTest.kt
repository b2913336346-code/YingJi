package com.yingji.app.presentation.search

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieCategory
import com.yingji.app.data.entity.MovieTag
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.WatchTimeField
import com.yingji.app.domain.usecase.FilterAndSortMoviesUseCase
import com.yingji.app.domain.usecase.MovieSearchCriteria
import com.yingji.app.ui.theme.YingJiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SearchStage3ScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun scrollingFiltersKeepsCombinedResultsClickableInListAndPosterWall() {
        val filters = mutableStateOf(SearchUiState())
        val clicked = mutableListOf<Long>()
        val movies = listOf(
            Movie(id = 1, name = "碧蓝之海", type = "电影", status = MovieStatus.FINISHED.value, createTime = 100),
            Movie(id = 2, name = "其他作品", type = "电影", status = MovieStatus.WATCHING.value, createTime = 200),
        )
        val histories = listOf(WatchHistory(movieId = 1, startTime = 100, endTime = 200, duration = 100, watchNumber = 1))
        composeRule.setContent {
            val f = filters.value
            val result = FilterAndSortMoviesUseCase()(movies, histories,
                listOf(MovieCategory(1, 1)), listOf(MovieTag(1, 1)),
                MovieSearchCriteria(query = f.query, categoryId = f.selectedCategoryId,
                    tagId = f.selectedTagId, status = f.selectedStatus, timeField = f.timeField,
                    fromTime = f.fromTime, toTime = f.toTime, sortOption = f.sortOption))
            YingJiTheme {
                SearchScreen(
                    state = f.copy(movies = result, categories = listOf(Category(1, "分类甲", false)), tags = listOf(Tag(1, "标签甲"))),
                    onBack = {}, onQueryChange = { filters.value = filters.value.copy(query = it) },
                    onCategorySelected = { filters.value = filters.value.copy(selectedCategoryId = it) },
                    onTagSelected = { filters.value = filters.value.copy(selectedTagId = it) },
                    onStatusSelected = { filters.value = filters.value.copy(selectedStatus = it) },
                    onTimeFieldSelected = { filters.value = filters.value.copy(timeField = it) },
                    onSortSelected = { filters.value = filters.value.copy(sortOption = it) },
                    onMovieClick = { clicked.add(it) },
                )
            }
        }
        composeRule.onNodeWithText("名称、拼音或首字母").performTextInput("bilan")
        select("分类甲")
        select("标签甲")
        select(MovieStatus.FINISHED.value)
        select(WatchTimeField.END_TIME.displayName)
        // The fifth horizontal row is sorting; locate its scroll semantics independently of lazy chips.
        composeRule.onAllNodes(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
            hasAnyAncestor(hasTestTag("search_filters")))[4]
            .performScrollToIndex(MovieSortOption.WATCH_END_DESC.ordinal)
        select(MovieSortOption.WATCH_END_DESC.displayName)
        composeRule.onNodeWithText("其他作品").assertDoesNotExist()
        val list = composeRule.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        list.performScrollToNode(hasText("碧蓝之海"))
        composeRule.onNode(hasClickAction() and hasText("碧蓝之海")).assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("切换到海报墙").performClick()
        composeRule.onNode(hasClickAction() and hasText("碧蓝之海")).assertIsDisplayed().performClick()
        select("不限")
        composeRule.runOnIdle {
            assertEquals(listOf(1L, 1L), clicked)
            assertEquals(1L, filters.value.selectedCategoryId)
            assertEquals(1L, filters.value.selectedTagId)
            assertEquals(MovieStatus.FINISHED, filters.value.selectedStatus)
            assertEquals(MovieSortOption.WATCH_END_DESC, filters.value.sortOption)
        }
    }

    @Test
    fun dateDialogConfirmAndCancelExecuteCallbacksWithoutFixedDelays() {
        val date = LocalDate.now()
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        val selectedEnds = mutableListOf<Long?>()
        val selectedStarts = mutableListOf<Long?>()
        composeRule.setContent {
            YingJiTheme {
                SearchScreen(
                    state = SearchUiState(timeField = WatchTimeField.END_TIME, fromTime = start, toTime = end),
                    onBack = {}, onQueryChange = {}, onCategorySelected = {}, onTagSelected = {},
                    onFromTimeSelected = { selectedStarts.add(it) },
                    onToTimeSelected = { selectedEnds.add(it) }, onMovieClick = {},
                )
            }
        }
        // Equal formatted dates are differentiated by their button position, not an entity ID.
        val dateButtons = composeRule.onAllNodes(hasClickAction() and hasText(date.toString()))
        dateButtons.assertCountEquals(2)
        dateButtons[1].performScrollTo().performClick()
        composeRule.onNodeWithText("确定").performClick()
        dateButtons[0].performScrollTo().performClick()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(end), selectedEnds)
            assertTrue(selectedStarts.isEmpty())
        }
    }

    @Test
    fun dataFailureIsVisibleInResultArea() {
        composeRule.setContent {
            YingJiTheme {
                SearchScreen(
                    state = SearchUiState(errorMessage = "搜索数据加载失败，请重新打开应用重试"),
                    onBack = {}, onQueryChange = {}, onCategorySelected = {}, onTagSelected = {}, onMovieClick = {},
                )
            }
        }
        composeRule.onNodeWithText("搜索数据加载失败，请重新打开应用重试").assertIsDisplayed()
    }

    private fun select(text: String) = composeRule.scrollToSearchFilter(hasText(text)).performClick()
}
