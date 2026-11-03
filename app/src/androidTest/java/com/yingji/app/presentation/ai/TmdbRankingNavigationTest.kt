package com.yingji.app.presentation.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.MainActivity
import com.yingji.app.YingJiApplication
import com.yingji.app.navigation.YingJiNavGraph
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Real navigation with an isolated demo profile and synthetic provider transport; never saves to the real library. */
class TmdbRankingNavigationTest {
    @get:Rule val ui = createComposeRule()
    private val models = ViewModelStore()
    private val f = TmdbRankingUiFixture()
    private lateinit var home: AiHomeViewModel
    private val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
    @Before fun open() { runBlocking { f.prepare() }; ui.runOnUiThread { home = f.home(); models.put("home", home) } }
    @After fun close() { ui.runOnUiThread { models.clear() } }
    private fun scroll(tag: String) { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)); ui.onNodeWithTag(tag).performScrollTo() }
    private fun rank() {
        ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
        scroll("ai_source_tmdb"); ui.onNodeWithTag("ai_source_tmdb").performClick()
        scroll("tmdb_home_read"); ui.onNodeWithTag("tmdb_home_read").performClick()
        ui.waitUntil(5000) { ui.onAllNodes(hasTestTag("tmdb_home_rank") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        scroll("tmdb_home_rank"); ui.onNodeWithTag("tmdb_home_rank").performClick()
        scroll("tmdb_home_rank_status")
        ui.waitUntil(5000) { ui.onAllNodes(hasTestTag("tmdb_home_rank_status") and hasText("本机 Mock 排序：3 项 · 非真实画像推荐")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(8, f.calls.get())
    }
    @Test fun rankedSelectionUsesExistingNavigationAndBackDiscardsResultsWithoutWriting() {
        val before = runBlocking { app.movieRepository.observeAll().first() }
        ui.setContent { YingJiTheme { YingJiNavGraph(aiHomeViewModelOverride = home) } }; rank()
        scroll("tmdb_add_movie:7"); ui.onNodeWithTag("tmdb_add_movie:7").performClick(); ui.onNodeWithTag("tmdb_add_confirm").performClick()
        ui.onNodeWithText("虚构排序候选35-7").assertExists(); ui.onNodeWithText("保存").performScrollTo().performClick()
        ui.onNodeWithText("请选择作品分类").performScrollTo().assertIsDisplayed()
        assertEquals(before, runBlocking { app.movieRepository.observeAll().first() }); assertEquals(8, f.calls.get())
        ui.onNodeWithContentDescription("返回").performClick(); scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
        scroll("ai_source_tmdb"); ui.onNodeWithTag("ai_source_tmdb").performClick(); scroll("tmdb_home_rank"); ui.onNodeWithTag("tmdb_home_rank").assertIsNotEnabled()
        assertEquals(before, runBlocking { app.movieRepository.observeAll().first() }); assertEquals(8, f.calls.get())
    }
    @Test fun savedStateRestorationDefaultsToMockAndDoesNotRestoreRankedMetadata() {
        val before = runBlocking { f.demo.readManagement() }; val restore = StateRestorationTester(ui)
        restore.setContent { YingJiTheme { YingJiNavGraph(aiHomeViewModelOverride = home) } }; rank()
        restore.emulateSavedInstanceStateRestore(); scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
        scroll("ai_source_tmdb"); ui.onNodeWithTag("ai_source_tmdb").performClick()
        scroll("tmdb_home_status"); ui.onNodeWithTag("tmdb_home_status").assertTextEquals("尚未读取候选")
        scroll("tmdb_home_rank"); ui.onNodeWithTag("tmdb_home_rank").assertIsNotEnabled(); assertEquals(8, f.calls.get())
        assertEquals(before, runBlocking { f.demo.readManagement() })
    }
}

/** Actual Application -> NavGraph -> factories expose the new button, without pressing either provider read button. */
class TmdbRankingProductionEntryTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun scroll(tag: String) { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)); ui.onNodeWithTag(tag).performScrollTo() }
    @Test fun actualFactoriesExposeTheIdleRankingEntryAndKeepDefaultMockAndNavigation() {
        ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
        scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
        scroll("ai_source_tmdb"); ui.onNodeWithTag("ai_source_tmdb").performClick()
        scroll("tmdb_home_rank"); ui.onNodeWithTag("tmdb_home_rank").assertIsDisplayed().assertIsNotEnabled()
        scroll("tmdb_home_rank_status"); ui.onNodeWithTag("tmdb_home_rank_status").assertTextEquals("读取候选后可主动排序，不增加联网请求")
        scroll("tmdb_personal_recommend"); ui.onNodeWithTag("tmdb_personal_recommend").assertIsDisplayed().assertIsNotEnabled()
        scroll("tmdb_personal_status"); ui.onNodeWithTag("tmdb_personal_status").assertTextEquals("读取候选后可使用真实本地画像；每次单独确认，取消不请求模型。")
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
        scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
        ui.onNodeWithText("我的").performClick(); ui.onNodeWithText("首页").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
        scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
    }
}
