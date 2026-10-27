package com.yingji.app.presentation

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.YingJiApplication
import com.yingji.app.data.database.DatabaseModule
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.ai.AiDemoSetup
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.navigation.YingJiNavGraph
import com.yingji.app.presentation.ai.AiHomeViewModel
import com.yingji.app.presentation.ai.AiTestRepository
import com.yingji.app.ui.theme.YingJiTheme
import com.yingji.app.utils.TimeUtils
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Opt-in, isolated package only: real navigation -> ViewModels -> Room -> statistics. */
class B4HistoryStatisticsAcceptanceTest {
    @get:Rule val ui = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
    private val db get() = DatabaseModule.provideDatabase(app)
    private val models = ViewModelStore()
    private val shown = mutableStateOf(true)
    private val name = "B4历史统计-" + UUID.randomUUID()
    private var categoryId = 0L
    private var movieId = 0L
    @After fun cleanup() = runBlocking<Unit> {
        ui.runOnIdle { shown.value = false; models.clear() }; ui.waitForIdle()
        if (movieId > 0) db.movieDao().getById(movieId)?.let { app.movieRepository.delete(it) }
        if (categoryId > 0) db.categoryDao().delete(Category(categoryId, name + "分类", false))
    }
    private fun awaitText(text: String) = ui.waitUntil(10000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitData(predicate: suspend () -> Boolean) = ui.waitUntil(10000) { runBlocking { predicate() } }
    private fun card(title: String, value: String) {
        ui.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(title))
        ui.onNode(hasText(title) and hasAnySibling(hasText(value))).assertIsDisplayed()
    }
    @Test fun actualHistoryEditsRewatchAndStatisticsRefreshAcrossNavigation() = runBlocking<Unit> {
        check(app.packageName == "com.yingji.app.b4regression.acceptance")
        categoryId = db.categoryDao().insert(Category(name = name + "分类", isSystem = false))
        movieId = app.movieRepository.insertWithRelations(Movie(name = name, type = name + "分类",
            status = MovieStatus.WANT_TO_WATCH.value, createTime = 1), categoryId, emptySet())
        val demo = AiTestRepository(); demo.saveSetup(AiDemoSetup(completed = true))
        lateinit var ai: AiHomeViewModel
        ui.runOnUiThread { ai = AiHomeViewModel(demo); models.put("ai", ai) }
        ui.setContent { if (shown.value) YingJiTheme { YingJiNavGraph(aiHomeViewModelOverride = ai) } }
        awaitText(name); ui.onNodeWithText(name).performClick()
        ui.onNodeWithText("观看历史").performScrollTo().performClick()
        awaitText("开始观看"); ui.onNodeWithText("开始观看").performClick()
        awaitData { db.watchHistoryDao().getForMovie(movieId).size == 1 }
        val first = db.watchHistoryDao().getForMovie(movieId).single()
        assertNull(first.endTime); assertEquals(MovieStatus.WATCHING.value, db.movieDao().getById(movieId)?.status)
        awaitText("结束观看"); ui.onNodeWithText("结束观看").performClick()
        awaitData { db.watchHistoryDao().getForMovie(movieId).single().endTime != null }
        assertEquals(MovieStatus.FINISHED.value, db.movieDao().getById(movieId)?.status)
        ui.onNodeWithContentDescription("修改时间").performScrollTo().performClick()
        ui.onNode(hasSetTextAction() and hasText("开始时间")).performTextReplacement("2026-10-20 10:00")
        ui.onNode(hasSetTextAction() and hasText("结束时间")).performTextReplacement("2026-10-20 09:00")
        ui.onNodeWithText("保存").performClick()
        ui.onNodeWithText("结束时间不能早于开始时间").assertIsDisplayed()
        assertNotEquals(TimeUtils.parse("2026-10-20 10:00"), db.watchHistoryDao().getForMovie(movieId).single().startTime)
        ui.onNode(hasSetTextAction() and hasText("结束时间")).performTextReplacement("2026-10-20 11:00")
        ui.onNodeWithText("保存").performClick()
        awaitData { db.watchHistoryDao().getForMovie(movieId).single().duration == 3600000L }
        assertEquals(first.id, db.watchHistoryDao().getForMovie(movieId).single().id)
        ui.onNodeWithText("开始观看").performClick()
        awaitData { db.watchHistoryDao().getForMovie(movieId).size == 2 }
        awaitText("结束观看"); ui.onNodeWithText("结束观看").performClick()
        awaitData { db.watchHistoryDao().getForMovie(movieId).all { it.endTime != null } }
        assertEquals(listOf(1, 2), db.watchHistoryDao().getForMovie(movieId).map { it.watchNumber }.sorted())
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("观看历史").performScrollTo().performClick()
        awaitText("开始观看")
        assertEquals(2, db.watchHistoryDao().getForMovie(movieId).size)
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("我的").performClick()
        awaitText("作品总数：1"); ui.onNodeWithText("作品总数：1").assertExists()
        ui.onNodeWithTag("mine_settings_list").performScrollToNode(hasText("查看统计"))
        ui.onNodeWithText("查看统计").performClick()
        awaitText("作品数量"); card("作品数量", "1"); card("观看次数", "2"); card("重刷次数", "1")
        val duration = db.watchHistoryDao().getForMovie(movieId).sumOf { it.duration ?: 0L }
        card("累计观看时间", TimeUtils.formatDuration(duration))
        ui.onNodeWithContentDescription("返回").performClick()
        awaitText("作品总数：1")
        assertEquals(0, demo.requests)
    }
}
