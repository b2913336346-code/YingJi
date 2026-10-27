package com.yingji.app.presentation.ai

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.*
import com.yingji.app.data.entity.Movie
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AiHomeScreenTest {
    @get:Rule val ui = createComposeRule()
    private val models = ViewModelStore()
    private lateinit var vm: AiHomeViewModel
    private fun show(repo: AiTestRepository, movies: List<Movie> = emptyList(), onAdd: (String) -> Unit = {}) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = AiHomeViewModel(repo); models.put("ai", vm) }
        ui.setContent { YingJiTheme(themeMode = ThemeMode.DARK) {
            HomeScreen(movies, emptyMap(), {}, {}, {}, aiViewModel = vm, onAddAiMovie = { onAdd(it.title) })
        } }
        ui.waitUntil(5000) { !vm.state.value.initializing }
        ui.waitUntil(5000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }
    private fun controls(index: Int = 1) { ui.onNodeWithTag("movie_collection").performScrollToIndex(index) }
    private fun enter() { controls(); ui.onNodeWithTag("ai_enter").performClick() }
    private fun awaitResult(result: AiResultState) { ui.waitUntil(5000) { vm.state.value.result == result } }
    private fun seed(repo: AiTestRepository) { runBlocking { repo.saveSetup(AiDemoSetup()) } }
    @After fun close() { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() } }

    @Test fun firstEntryHasTwoStepsWithEmptyPreferencesAndSourceDefaults() {
        val repo = AiTestRepository(); show(repo); enter()
        ui.onNodeWithText("1/2 · 选择偏好").assertIsDisplayed()
        ui.onNodeWithTag("ai_pref_科幻_LIKE").assertIsNotSelected().performClick().assertIsSelected()
        ui.onNodeWithTag("ai_setup_next").performClick()
        ui.onNodeWithText("2/2 · 画像来源").assertIsDisplayed()
        ui.onNodeWithTag("ai_source_ratings").assertIsOff()
        ui.onNodeWithTag("ai_source_behavior").assertIsOff()
        ui.onNodeWithTag("ai_source_feedback").assertIsOn()
        ui.onNodeWithTag("ai_source_ratings").performClick()
        ui.onNodeWithTag("ai_setup_next").performClick()
        ui.waitUntil(5000) { vm.state.value.setup.completed }
        assertNull(vm.state.value.step); assertEquals(0, repo.requests)
        val saved = runBlocking { repo.readSetup() }
        assertEquals(PreferenceDirection.LIKE, saved.preferences["科幻"])
        assertEquals(ProfileSourceSettings(true, false, true), saved.sources)
    }
    @Test fun skippingDiscardsSelectionsAndDoesNotSubmit() {
        val repo = AiTestRepository(); show(repo); enter()
        ui.onNodeWithTag("ai_pref_科幻_LIKE").performClick()
        ui.onNodeWithTag("ai_setup_skip").performClick()
        ui.waitUntil(5000) { vm.state.value.setup.completed }
        assertEquals(ProfileSourceSettings(), vm.state.value.setup.sources)
        assertTrue(vm.state.value.setup.preferences.isEmpty()); assertEquals(0, repo.requests)
    }
    @Test fun savingFailureKeepsTheDialogAndSuccessfulRetryCompletesIt() {
        val repo = AiTestRepository(); repo.store.failWrites = true; show(repo); enter()
        ui.onNodeWithTag("ai_setup_next").performClick()
        ui.onNodeWithTag("ai_setup_next").performClick()
        ui.waitUntil(5000) { vm.state.value.message?.contains("保存失败") == true }
        ui.onNodeWithTag("ai_setup_error").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("2/2 · 画像来源").assertIsDisplayed()
        assertFalse(runBlocking { repo.readSetup() }.completed)
        repo.store.failWrites = false
        ui.onNodeWithTag("ai_setup_next").performClick()
        ui.waitUntil(5000) { vm.state.value.setup.completed }
        ui.onNodeWithText("2/2 · 画像来源").assertDoesNotExist()
    }
    @Test fun shortcutsAndNaturalInputRequireExplicitSubmission() {
        val repo = AiTestRepository(); seed(repo); show(repo); enter()
        for (mode in listOf(AiMode.FOR_YOU, AiMode.TONIGHT, AiMode.EXPLORE)) {
            controls(); ui.onNodeWithTag("ai_mode_" + mode.name).performClick(); awaitResult(AiResultState.SUCCESS)
            assertEquals(mode, repo.lastMode)
        }
        controls(); ui.onNodeWithTag("ai_submit").performClick()
        ui.onNodeWithText("请输入想看的内容").assertExists()
        val before = repo.requests
        ui.onNodeWithTag("ai_input").performTextInput("今晚想看科幻")
        assertEquals(before, repo.requests)
        ui.onNodeWithTag("ai_submit").performClick(); awaitResult(AiResultState.SUCCESS)
        assertEquals(AiMode.QUERY, repo.lastMode); assertEquals("今晚想看科幻", repo.lastInput)
    }
    @Test fun loadingEmptyErrorOfflineAndQuotaStatesAreVisibleAndCanRecover() {
        val repo = AiTestRepository(); seed(repo); val gate = CompletableDeferred<Unit>(); repo.gate = gate
        show(repo); enter(); controls(); ui.onNodeWithTag("ai_mode_FOR_YOU").performClick()
        ui.onNodeWithText("正在加载 Mock 推荐").assertExists()
        ui.onNodeWithTag("ai_submit").assertIsNotEnabled()
        gate.complete(Unit); awaitResult(AiResultState.SUCCESS); repo.gate = null
        for ((scenario, result) in listOf(MockScenario.EMPTY to AiResultState.EMPTY, MockScenario.ERROR to AiResultState.ERROR,
            MockScenario.OFFLINE to AiResultState.OFFLINE, MockScenario.QUOTA to AiResultState.QUOTA, MockScenario.SUCCESS to AiResultState.SUCCESS)) {
            controls(); ui.onNodeWithTag("ai_scenario").performClick()
            ui.onNodeWithTag("ai_scenario_" + scenario.name).performClick()
            ui.onNodeWithTag("ai_mode_FOR_YOU").performClick(); awaitResult(result)
            if (result != AiResultState.SUCCESS) {
                assertTrue(vm.state.value.recommendations.isEmpty())
                val text = when (result) {
                    AiResultState.EMPTY -> "暂无符合条件的演示结果"
                    AiResultState.ERROR -> "Mock 请求失败"
                    AiResultState.OFFLINE -> "Mock 离线"
                    else -> "Mock 额度已耗尽"
                }
                ui.onNode(hasText(text, substring = true)).assertExists()
            }
        }
        ui.onNodeWithText("Mock 结果：3 项，继续向下查看").assertExists()
    }
    @Test fun feedbackAndAddRequireExplicitActionsAndCancellationDoesNotNavigate() {
        val repo = AiTestRepository(); seed(repo); val added = mutableListOf<String>(); show(repo, onAdd = { added.add(it) }); enter()
        controls(); ui.onNodeWithTag("ai_mode_FOR_YOU").performClick(); awaitResult(AiResultState.SUCCESS)
        ui.onNodeWithTag("movie_collection").performScrollToIndex(2)
        ui.onNodeWithTag("ai_feedback_mock-0_LIKE").performClick()
        ui.waitUntil(5000) { vm.state.value.setup.feedback["mock-0"] == AiFeedback.LIKE }
        ui.onNodeWithTag("ai_feedback_mock-0_LIKE").assertIsSelected()
        ui.onNodeWithTag("ai_add_mock-0").performClick()
        assertTrue(added.isEmpty()); ui.onNodeWithText("取消").performClick(); assertTrue(added.isEmpty())
        ui.onNodeWithTag("ai_add_mock-0").performClick()
        ui.onNodeWithTag("ai_add_confirm").performClick()
        assertEquals(listOf("Mock 演示作品 1"), added)
        ui.onNodeWithTag("ai_add_confirm").assertDoesNotExist()
    }
    @Test fun aiCardIsAfterAllMoviesInListAndFullSpanInPosterWall() {
        val movies = (1L..30L).map { Movie(id = it, name = "作品" + it, type = "电影", status = "想看", createTime = it) }
        show(AiTestRepository(), movies)
        ui.onNodeWithTag("movie_collection").performScrollToIndex(29)
        ui.onNodeWithText("作品30").assertExists()
        ui.onNodeWithTag("movie_collection").performScrollToIndex(30)
        ui.onNodeWithTag("ai_home_card").assertIsDisplayed()
        ui.onNodeWithContentDescription("切换到海报墙").performClick()
        ui.onNodeWithTag("movie_collection").performScrollToIndex(30)
        val collection = ui.onNodeWithTag("movie_collection").fetchSemanticsNode().boundsInRoot
        val card = ui.onNodeWithTag("ai_home_card").fetchSemanticsNode().boundsInRoot
        assertTrue(card.width > collection.width * 0.85f)
        ui.onNodeWithTag("movie_collection").performScrollToIndex(29)
        ui.onNodeWithText("作品30").assertExists()
    }
}
