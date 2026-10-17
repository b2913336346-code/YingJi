package com.yingji.app.presentation.movie

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import org.junit.Rule
import org.junit.Test

class MovieDisplayModeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun switchingLayoutKeepsTheSameMoviesAndShowsMissingPosterPlaceholder() {
        val movies = listOf(
            Movie(
                id = 1L,
                name = "作品甲",
                type = "电影",
                status = MovieStatus.WANT_TO_WATCH.value,
                createTime = 2L,
            ),
            Movie(
                id = 2L,
                name = "作品乙",
                type = "动漫",
                status = MovieStatus.WATCHING.value,
                createTime = 1L,
            ),
        )
        composeRule.setContent {
            YingJiTheme(themeMode = ThemeMode.DARK) {
                MovieListScreen(
                    title = "测试",
                    movies = movies,
                    onMovieClick = {},
                    defaultDisplayMode = MovieDisplayMode.LIST,
                )
            }
        }

        composeRule.onNodeWithText("作品甲").assertExists()
        composeRule.onNodeWithText("作品乙").assertExists()
        composeRule.onNodeWithContentDescription("切换到海报墙").performClick()

        composeRule.onNodeWithContentDescription("切换到列表").assertExists()
        composeRule.onNodeWithText("作品甲").assertExists()
        composeRule.onNodeWithText("作品乙").assertExists()
        composeRule.onAllNodesWithText("无海报")[0].assertExists()
    }
}
