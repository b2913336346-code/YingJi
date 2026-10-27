package com.yingji.app.presentation.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.Movie
import com.yingji.app.data.repository.HistoryRepository
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MineMovieCountTest {
    @get:Rule val composeRule = createComposeRule()
    private val contentVisible = mutableStateOf(true)
    private val renderedMovieCount = AtomicInteger(-1)
    private lateinit var database: AppDatabase
    @Before fun setup() {
        DatabaseHealthMonitor.resetForTesting()
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    }
    @After fun teardown() {
        // Dispose the Room collector while its database is still open.
        composeRule.runOnIdle { contentVisible.value = false }
        composeRule.waitForIdle()
        database.close()
        DatabaseHealthMonitor.resetForTesting()
    }

    @Test fun totalUsesUniqueMoviesAndRefreshesAfterInsertRewatchAndDelete() {
        val repository = MovieRepository(database)
        val moviesFlow = repository.observeAll()
        composeRule.setContent {
            if (!contentVisible.value) return@setContent
            val movies by moviesFlow.collectAsState(initial = emptyList())
            val movieCount = movies.size
            SideEffect { renderedMovieCount.set(movieCount) }
            YingJiTheme(ThemeMode.LIGHT) {
                SettingsScreen(emptyList(), emptyList(), ThemeMode.LIGHT, {}, onStatisticsClick = {},
                    onAddCategory = {}, onDeleteCategory = {}, onAddTag = {}, onDeleteTag = {}, movieCount = movieCount)
            }
        }
        assertCount(0)
        val id = runBlocking { repository.insert(Movie(name = "唯一作品", type = "电影", status = MovieStatus.WANT_TO_WATCH.value, createTime = 1)) }
        assertCount(1)
        val histories = HistoryRepository(database)
        runBlocking {
            val first = requireNotNull(histories.startWatching(id, 100))
            histories.endWatching(first, 200)
            histories.startWatching(id, 300)
        }
        assertCount(1)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "b3-ui-screenshots").apply { mkdirs() }
        File(directory, "mine-light.png").outputStream().use {
            assertTrue(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        runBlocking { repository.delete(requireNotNull(database.movieDao().getById(id))) }
        assertCount(0)
    }

    private fun assertCount(count: Int) {
        val text = "作品总数：" + count
        composeRule.waitForIdle()
        // The predicate must not enter Espresso synchronization while waitUntil advances frames.
        composeRule.waitUntil(timeoutMillis = 5_000) { renderedMovieCount.get() == count }
        composeRule.onNodeWithTag("mine_settings_list")
            .performScrollToNode(hasText(text))
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }
}
