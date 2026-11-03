package com.yingji.app.presentation.ai

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AiHomeFooterLayoutTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private lateinit var vm: AiHomeViewModel
    private val height = mutableStateOf(900.dp)
    private val theme = mutableStateOf(ThemeMode.DARK)
    private val clicked = mutableListOf<Long>()
    @Before fun establishPortraitViewport() {
        ui.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
    }
    private fun show(repo: AiTestRepository, movies: List<Movie> = emptyList()) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = AiHomeViewModel(repo); models.put("ai", vm) }
        ui.setContent { YingJiTheme(theme.value) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(height.value)) {
                    HomeScreen(movies, emptyMap(), { clicked.add(it) }, {}, {}, aiViewModel = vm)
                }
            }
        } }
        ui.waitUntil(5000) { !vm.state.value.initializing }
    }
    private fun scroll(tag: String) {
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag))
        ui.onNodeWithTag(tag).performScrollTo()
    }
    private fun assertBottom() {
        // Scroll the fixture footer fully into view before measuring clipped semantics bounds.
        val viewport = ui.onNodeWithTag("movie_collection")
        val distance = viewport.fetchSemanticsNode().boundsInRoot.height
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, distance) }
        ui.waitForIdle()
        val collection = ui.onNodeWithTag("movie_collection").fetchSemanticsNode().boundsInRoot
        val card = ui.onNodeWithTag("ai_home_card").fetchSemanticsNode().boundsInRoot
        val padding = with(ui.density) { 88.dp.toPx() }
        assertEquals("AI card bottom must follow the available viewport", collection.bottom - padding, card.bottom, 2f)
        assertTrue("Add button must not cover AI card", card.bottom < ui.onNodeWithContentDescription("新增作品").fetchSemanticsNode().boundsInRoot.top)
    }
    @After fun close() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
        ui.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test fun emptyHomeAnchorsAiAtBottomInBothThemesWithoutMovingTheEmptyMessage() {
        show(AiTestRepository())
        val emptyTop = ui.onNodeWithText("还没有作品").fetchSemanticsNode().boundsInRoot.top
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            ui.runOnIdle { theme.value = mode }; assertBottom()
            assertEquals(emptyTop, ui.onNodeWithText("还没有作品").fetchSemanticsNode().boundsInRoot.top, 1f)
            capture("portrait-empty-" + mode.name.lowercase())
        }
    }
    @Test fun shortCollectionStaysAtTopAndAiIsFullWidthAtBottomInListAndPosterWall() {
        show(AiTestRepository(), listOf(Movie(id = 1, name = "定位作品", type = "电影", status = "想看", createTime = 1)))
        for (poster in listOf(false, true)) {
            if (poster) ui.onNodeWithContentDescription("切换到海报墙").performClick()
            assertBottom()
            val collection = ui.onNodeWithTag("movie_collection").fetchSemanticsNode().boundsInRoot
            val title = ui.onNodeWithText("定位作品").fetchSemanticsNode().boundsInRoot
            assertTrue("Movie stays before the AI area", title.bottom < ui.onNodeWithTag("ai_home_card").fetchSemanticsNode().boundsInRoot.top)
            if (!poster) assertTrue(title.top < collection.top + with(ui.density) { 60.dp.toPx() })
            val card = ui.onNodeWithTag("ai_home_card").fetchSemanticsNode().boundsInRoot
            assertEquals(collection.width - with(ui.density) { 32.dp.toPx() }, card.width, 2f)
            ui.onNodeWithText("定位作品").performClick()
        }
        assertEquals(listOf(1L, 1L), clicked)
    }
    @Test fun expandedInputSurvivesViewportChangesAndCanStillSubmitAndOpenManagement() {
        val repo = AiTestRepository(); runBlocking { repo.saveSetup(AiDemoSetup()) }; show(repo)
        scroll("ai_enter"); ui.onNodeWithTag("ai_enter").performClick(); assertBottom()
        scroll("ai_input"); ui.onNodeWithTag("ai_input").performTextInput("竖屏想看科幻")
        Espresso.closeSoftKeyboard()
        ui.runOnIdle { height.value = 360.dp }
        scroll("ai_submit"); ui.onNodeWithTag("ai_submit").assertIsDisplayed()
        assertEquals("竖屏想看科幻", vm.state.value.input)
        ui.runOnIdle { height.value = 900.dp }
        scroll("ai_submit"); ui.onNodeWithTag("ai_submit").performClick()
        ui.waitUntil(5000) { vm.state.value.result == AiResultState.SUCCESS }
        assertEquals("竖屏想看科幻", repo.lastInput); assertEquals(1, repo.requests)
        scroll("ai_manage_open"); ui.onNodeWithTag("ai_manage_open").performClick()
        ui.onNodeWithText("AI 管理 · Mock").assertIsDisplayed(); ui.onNodeWithTag("ai_manage_close").performClick()
        scroll("ai_result_mock-2"); ui.onNodeWithTag("ai_result_mock-2").assertIsDisplayed()
    }
    @Test fun longCollectionStillScrollsToItsLastMovieAiInputAndRecommendationInBothModes() {
        val repo = AiTestRepository(); runBlocking { repo.saveSetup(AiDemoSetup()) }
        show(repo, (1L..30L).map { Movie(id = it, name = "滚动作品" + it, type = "电影", status = "想看", createTime = it) })
        for (poster in listOf(false, true)) {
            if (poster) ui.onNodeWithContentDescription("切换到海报墙").performClick()
            ui.onNodeWithTag("movie_collection").performScrollToNode(hasText("滚动作品30"))
            ui.onNodeWithText("滚动作品30").performClick()
            if (!vm.state.value.expanded) { scroll("ai_enter"); ui.onNodeWithTag("ai_enter").performClick() }
            scroll("ai_mode_FOR_YOU"); ui.onNodeWithTag("ai_mode_FOR_YOU").performClick()
            ui.waitUntil(5000) { vm.state.value.result == AiResultState.SUCCESS }
            scroll("ai_result_mock-2"); ui.onNodeWithTag("ai_result_mock-2").assertIsDisplayed()
        }
        assertEquals(listOf(30L, 30L), clicked); assertEquals(2, repo.requests)
    }
    private fun capture(name: String) {
        ui.waitForIdle(); val automation = InstrumentationRegistry.getInstrumentation().uiAutomation; automation.waitForIdle(100, 5000)
        val image = requireNotNull(automation.takeScreenshot())
        val context = ApplicationProvider.getApplicationContext<Context>(); val dir = File(context.cacheDir, "a7-portrait-screenshots").apply { mkdirs() }
        File(dir, name + ".png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
    }
}
