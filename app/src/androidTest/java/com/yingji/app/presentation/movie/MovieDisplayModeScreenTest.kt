package com.yingji.app.presentation.movie

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.TemporaryFolder
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.model.MovieDisplayMode
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import com.yingji.app.ui.common.LocalPoster
import org.junit.Rule
import org.junit.Test

class MovieDisplayModeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)

    private fun movie(id: Long, name: String = "作品$id") = Movie(
        id = id, name = name, type = "电影",
        status = MovieStatus.WANT_TO_WATCH.value, createTime = id,
    )

    private fun collection() = composeRule.onNode(
        hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    )

    @Test
    fun switchingLayoutKeepsTheSameMoviesAndShowsMissingPosterPlaceholder() {
        val clickedIds = mutableListOf<Long>()
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
                    onMovieClick = { clickedIds.add(it) },
                    defaultDisplayMode = MovieDisplayMode.LIST,
                )
            }
        }

        composeRule.onNodeWithText("作品甲").assertExists()
        composeRule.onNodeWithText("作品乙").assertExists()
        composeRule.onNode(hasClickAction() and hasText("作品乙")).performClick()
        composeRule.onNodeWithContentDescription("切换到海报墙").performClick()

        composeRule.onNodeWithContentDescription("切换到列表").assertExists()
        composeRule.onNodeWithText("作品甲").assertExists()
        composeRule.onNodeWithText("作品乙").assertExists()
        composeRule.onAllNodesWithText("无海报")[0].assertExists()
        composeRule.onNode(hasClickAction() and hasText("作品乙")).performClick()
        composeRule.onNodeWithContentDescription("切换到列表").performClick()
        composeRule.onNodeWithText("作品甲").assertExists()
        composeRule.runOnIdle { assertEquals(listOf(2L, 2L), clickedIds) }
    }

    @Test
    fun posterWallHasThreeColumnsAndTruncatesLongTitles() {
        val longTitle = "很长的作品名称".repeat(80)
        composeRule.setContent {
            YingJiTheme {
                MovieListScreen(
                    title = "三列验收", movies = listOf(movie(1L, longTitle), movie(2L), movie(3L)),
                    defaultDisplayMode = MovieDisplayMode.POSTER_WALL, onMovieClick = {},
                )
            }
        }

        val viewport = collection().fetchSemanticsNode().boundsInRoot
        val cards = listOf(longTitle, "作品2", "作品3").map { name ->
            composeRule.onNode(hasClickAction() and hasText(name))
                .fetchSemanticsNode().boundsInRoot
        }
        assertTrue(cards[0].left < cards[1].left && cards[1].left < cards[2].left)
        assertEquals(cards[0].top, cards[1].top, 1f)
        assertEquals(cards[0].top, cards[2].top, 1f)
        assertEquals(cards[0].width, cards[2].width, 1f)
        assertEquals(cards[0].left - viewport.left, viewport.right - cards[2].right, 1f)
        composeRule.onNodeWithText("作品2").assertExists()
        composeRule.onNodeWithText("作品3").assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(longTitle, useUnmergedTree = true).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult,
        ) { it(layouts) }
        assertEquals(1, layouts.size)
        assertEquals(2, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(1))
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.cacheDir, "stage2-three-columns.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun changingQueryResultsKeepsSessionModeAndCorrectDetailId() {
        val movies = mutableStateOf(listOf(movie(1L), movie(2L), movie(3L)))
        var selectedId: Long? = null
        composeRule.setContent {
            YingJiTheme {
                MovieListScreen(
                    title = "共享数据", movies = movies.value,
                    onMovieClick = { selectedId = it },
                )
            }
        }
        composeRule.onNodeWithContentDescription("切换到海报墙").performClick()
        composeRule.runOnIdle { movies.value = listOf(movie(3L)) }
        composeRule.onNodeWithContentDescription("切换到列表").assertExists()
        composeRule.onNodeWithText("作品1").assertDoesNotExist()
        composeRule.onNodeWithText("作品2").assertDoesNotExist()
        composeRule.onNode(hasClickAction() and hasText("作品3")).performClick()
        composeRule.runOnIdle { assertEquals(3L, selectedId) }
        composeRule.onNodeWithContentDescription("切换到列表").performClick()
        composeRule.onNodeWithText("作品3").assertExists()
        composeRule.onNodeWithText("作品1").assertDoesNotExist()
    }

    @Test
    fun largeCollectionScrollsToSameLastMovieInBothModes() {
        val clickedIds = mutableListOf<Long>()
        composeRule.setContent {
            YingJiTheme {
                MovieListScreen(
                    title = "大量作品", movies = (1L..500L).map { movie(it) },
                    onMovieClick = { clickedIds.add(it) },
                )
            }
        }
        collection().performScrollToIndex(499)
        composeRule.onNode(hasClickAction() and hasText("作品500")).performClick()
        composeRule.onNodeWithContentDescription("切换到海报墙").performClick()
        collection().performScrollToIndex(499)
        composeRule.onNode(hasClickAction() and hasText("作品500")).performClick()
        composeRule.runOnIdle { assertEquals(listOf(500L, 500L), clickedIds) }
    }

    @Test
    fun emptyStateRemainsAvailableAfterRepeatedToggles() {
        composeRule.setContent {
            YingJiTheme {
                MovieListScreen(title = "空数据", movies = emptyList(), onMovieClick = {})
            }
        }
        repeat(6) {
            composeRule.onNodeWithContentDescription("切换到海报墙").performClick()
            composeRule.onNodeWithText("还没有作品").assertExists()
            composeRule.onNodeWithContentDescription("切换到列表").performClick()
        }
        composeRule.onNodeWithText("还没有作品").assertExists()
    }

    @Test
    fun localPosterLoadsLocalFileAndDistinguishesMissingAndFailedImages() {
        val poster = File(temporaryFolder.root, "poster.png")
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        try {
            poster.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        val path = mutableStateOf<String?>(poster.absolutePath)
        composeRule.setContent {
            YingJiTheme { LocalPoster(path.value, Modifier.size(100.dp, 150.dp)) }
        }
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithContentDescription("作品海报")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("作品海报").assertExists()
        composeRule.runOnIdle { path.value = File(temporaryFolder.root, "missing.png").absolutePath }
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("海报加载失败").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("海报加载失败").assertExists()
        composeRule.runOnIdle { path.value = null }
        composeRule.onNodeWithText("无海报").assertExists()
    }
}
