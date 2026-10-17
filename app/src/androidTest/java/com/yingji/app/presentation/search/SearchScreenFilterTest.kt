package com.yingji.app.presentation.search

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.domain.model.WatchTimeField
import com.yingji.app.ui.theme.YingJiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SearchScreenFilterTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun statusTimeAndSortControlsDispatchSelections() {
        var selectedStatus: MovieStatus? = null
        var selectedTimeField: WatchTimeField? = null
        var selectedSort: MovieSortOption? = null
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.DARK) {
                SearchScreen(
                    state = SearchUiState(),
                    onBack = {},
                    onQueryChange = {},
                    onCategorySelected = {},
                    onTagSelected = {},
                    onStatusSelected = { selectedStatus = it },
                    onTimeFieldSelected = { selectedTimeField = it },
                    onSortSelected = { selectedSort = it },
                    onMovieClick = {},
                )
            }
        }

        composeRule.onNodeWithText(MovieStatus.FINISHED.value).performClick()
        composeRule.onNodeWithText(WatchTimeField.END_TIME.displayName).performClick()
        composeRule.onNodeWithText(MovieSortOption.CREATED_ASC.displayName).performClick()

        composeRule.runOnIdle {
            assertEquals(MovieStatus.FINISHED, selectedStatus)
            assertEquals(WatchTimeField.END_TIME, selectedTimeField)
            assertEquals(MovieSortOption.CREATED_ASC, selectedSort)
        }
    }
}
