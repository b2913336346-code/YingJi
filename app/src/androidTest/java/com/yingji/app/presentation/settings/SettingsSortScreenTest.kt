package com.yingji.app.presentation.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsSortScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun savedDefaultSortReflectsSelectionAndFailureKeepsPreviousChoice() {
        val sort = mutableStateOf(MovieSortOption.CREATED_DESC)
        val error = mutableStateOf<String?>(null)
        val requests = mutableListOf<MovieSortOption>()
        composeRule.setContent {
            YingJiTheme {
                SettingsScreen(
                    categories = emptyList(), tags = emptyList(), themeMode = ThemeMode.LIGHT,
                    onThemeModeChange = {}, onStatisticsClick = {}, onAddCategory = {},
                    onDeleteCategory = {}, onAddTag = {}, onDeleteTag = {},
                    sortOption = sort.value,
                    onSortOptionChange = {
                        requests.add(it)
                        if (it == MovieSortOption.WATCH_END_DESC) error.value = "默认排序保存失败，请重试"
                        else sort.value = it
                    },
                    sortSaveError = error.value,
                    onSortSaveErrorConsumed = { error.value = null },
                )
            }
        }
        val container = composeRule.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        container.performScrollToNode(hasText(MovieSortOption.NAME_ASC.displayName))
        composeRule.onNodeWithText(MovieSortOption.NAME_ASC.displayName).performClick().assertIsSelected()
        scrollSortRowTo(MovieSortOption.WATCH_END_DESC)
        composeRule.onNodeWithText(MovieSortOption.WATCH_END_DESC.displayName).performClick()
        composeRule.onNodeWithText("默认排序保存失败，请重试").assertExists()
        scrollSortRowTo(MovieSortOption.NAME_ASC)
        composeRule.onNodeWithText(MovieSortOption.NAME_ASC.displayName).assertIsSelected()
        composeRule.runOnIdle { assertEquals(listOf(MovieSortOption.NAME_ASC, MovieSortOption.WATCH_END_DESC), requests) }
    }
    private fun scrollSortRowTo(option: MovieSortOption) {
        composeRule.onNodeWithTag("mine_settings_list").performScrollToNode(hasText("默认排序"))
        val headingBottom = composeRule.onNodeWithText("默认排序").fetchSemanticsNode().boundsInRoot.bottom
        val horizontal = hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        val rowId = composeRule.onAllNodes(horizontal).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top >= headingBottom }
            .minBy { it.boundsInRoot.top }.id
        composeRule.onNode(SemanticsMatcher("the sort row found below its heading") { it.id == rowId })
            .performScrollToIndex(option.ordinal)
    }
}
