package com.yingji.app.presentation.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsDisplayModeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun defaultDisplayChoiceDispatchesSelectionsAndReflectsSavedState() {
        val mode = mutableStateOf(MovieDisplayMode.LIST)
        val requests = mutableListOf<MovieDisplayMode>()
        composeRule.setContent {
            YingJiTheme {
                SettingsScreen(
                    categories = emptyList(), tags = emptyList(), themeMode = ThemeMode.LIGHT,
                    onThemeModeChange = {}, onStatisticsClick = {}, onAddCategory = {},
                    onDeleteCategory = {}, onAddTag = {}, onDeleteTag = {},
                    displayMode = mode.value,
                    onDisplayModeChange = { requests.add(it); mode.value = it },
                )
            }
        }
        composeRule.onNodeWithText("默认展示方式").assertExists()
        composeRule.onNodeWithText("列表").assertIsSelected()
        composeRule.onNodeWithText("海报墙").performClick()
        composeRule.onNodeWithText("海报墙").assertIsSelected()
        composeRule.onNodeWithText("列表").performClick()
        composeRule.onNodeWithText("列表").assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(listOf(MovieDisplayMode.POSTER_WALL, MovieDisplayMode.LIST), requests)
        }
    }

    @Test
    fun failedDefaultSaveShowsFeedbackAndKeepsSavedChoice() {
        val message = "默认展示方式保存失败，请重试"
        val error = mutableStateOf<String?>(null)
        composeRule.setContent {
            YingJiTheme {
                SettingsScreen(
                    categories = emptyList(), tags = emptyList(), themeMode = ThemeMode.LIGHT,
                    onThemeModeChange = {}, onStatisticsClick = {}, onAddCategory = {},
                    onDeleteCategory = {}, onAddTag = {}, onDeleteTag = {},
                    displayMode = MovieDisplayMode.LIST,
                    onDisplayModeChange = { error.value = message },
                    displayModeSaveError = error.value,
                    onDisplayModeSaveErrorConsumed = { error.value = null },
                )
            }
        }
        composeRule.onNodeWithText("海报墙").performClick()
        composeRule.onNodeWithText(message).assertExists()
        composeRule.onNodeWithText("列表").assertIsSelected()
    }
}
