package com.yingji.app.presentation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.presentation.history.HistoryActionState
import com.yingji.app.presentation.history.HistoryScreen
import com.yingji.app.presentation.movie.MovieActionState
import com.yingji.app.presentation.movie.MovieDetailScreen
import com.yingji.app.ui.theme.YingJiTheme
import org.junit.Rule
import org.junit.Test

class DatabaseErrorFeedbackScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val movie = Movie(
        id = 1L,
        name = "测试作品",
        type = "电影",
        status = MovieStatus.WATCHING.value,
        createTime = 1L,
    )

    @Test
    fun movieDetailDisplaysWriteFailureSnackbar() {
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.DARK) {
                MovieDetailScreen(
                    movie = movie,
                    categories = emptyList(),
                    selectedCategories = emptyList(),
                    tags = emptyList(),
                    selectedTags = emptyList(),
                    onBack = {},
                    onHistory = {},
                    onEdit = {},
                    onDelete = {},
                    onStatusChange = {},
                    onCategoryChange = { _, _ -> },
                    onTagChange = { _, _ -> },
                    actionState = MovieActionState(message = "状态更新失败，请重试"),
                )
            }
        }

        composeRule.onNodeWithText("状态更新失败，请重试").assertExists()
    }

    @Test
    fun historyDisplaysWriteFailureSnackbar() {
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.DARK) {
                HistoryScreen(
                    movie = movie,
                    histories = emptyList(),
                    onBack = {},
                    onStartWatching = {},
                    onEndWatching = {},
                    onUpdateTimes = { _, _, _ -> },
                    actionState = HistoryActionState(message = "观看记录保存失败，请重试"),
                )
            }
        }

        composeRule.onNodeWithText("观看记录保存失败，请重试").assertExists()
    }
}
