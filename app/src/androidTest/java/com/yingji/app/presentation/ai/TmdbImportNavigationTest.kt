package com.yingji.app.presentation.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.YingJiApplication
import com.yingji.app.navigation.YingJiNavGraph
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class TmdbImportNavigationTest {
    @get:Rule val ui = createComposeRule()
    private val models = ViewModelStore()
    private val fixture = TmdbImportUiFixture()
    private lateinit var home: AiHomeViewModel
    private val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
    @Before fun prepare() { runBlocking { fixture.prepare() }; ui.runOnUiThread { home = fixture.home(); models.put("home", home) } }
    @After fun close() { ui.runOnUiThread { models.clear() } }
    private fun scroll(tag: String) { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)) }
    private fun openForm() {
        ui.waitUntil(10000) { ui.onAllNodesWithTag("ai_source_tmdb").fetchSemanticsNodes().isNotEmpty() }
        scroll("ai_source_tmdb"); ui.onNodeWithTag("ai_source_tmdb").performClick()
        scroll("tmdb_home_read"); ui.onNodeWithTag("tmdb_home_read").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithTag("tmdb_add_movie:7").fetchSemanticsNodes().isNotEmpty() }
        scroll("tmdb_add_movie:7"); ui.onNodeWithTag("tmdb_add_movie:7").performClick(); ui.onNodeWithTag("tmdb_add_confirm").performClick()
        ui.onNodeWithText("虚构表单电影").assertExists()
    }
    @Test fun actualNavigationReusesSharedFormAndMissingCategoryOrBackNeverWrites() {
        val before = runBlocking { app.movieRepository.observeAll().first() }
        ui.setContent { YingJiTheme { YingJiNavGraph(aiHomeViewModelOverride = home) } }; openForm()
        ui.onNodeWithText("保存").performScrollTo().performClick(); ui.onNodeWithText("请选择作品分类").performScrollTo().assertIsDisplayed()
        assertEquals(4, fixture.calls); assertEquals(before, runBlocking { app.movieRepository.observeAll().first() })
        ui.onNodeWithContentDescription("返回").performClick(); scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
        assertEquals(before, runBlocking { app.movieRepository.observeAll().first() })
    }
    @Test fun restoredPendingImportWithoutItsMemoryDraftCannotFallBackToOrdinarySave() {
        val before = runBlocking { app.movieRepository.observeAll().first() }
        val restoration = StateRestorationTester(ui)
        restoration.setContent { YingJiTheme { YingJiNavGraph(aiHomeViewModelOverride = home) } }; openForm()
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithText("候选会话已失效").assertIsDisplayed(); ui.onAllNodes(hasText("保存", substring = false)).assertCountEquals(0)
        assertEquals(before, runBlocking { app.movieRepository.observeAll().first() }); assertEquals(4, fixture.calls)
        ui.onNodeWithText("返回首页").performClick(); scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
    }
}
