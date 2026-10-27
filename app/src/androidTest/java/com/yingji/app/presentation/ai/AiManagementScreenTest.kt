package com.yingji.app.presentation.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AiManagementScreenTest {
    @get:Rule val ui = createComposeRule()
    private val models = ViewModelStore()
    private lateinit var vm: AiHomeViewModel
    private fun show(repo: AiTestRepository, theme: State<ThemeMode> = mutableStateOf(ThemeMode.DARK)) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = AiHomeViewModel(repo); models.put("vm", vm) }
        ui.setContent { YingJiTheme(theme.value) { HomeScreen(emptyList(), emptyMap(), {}, {}, {}, aiViewModel = vm) } }
        ui.waitUntil(5000) { !vm.state.value.initializing }
    }
    private fun open() {
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_manage_open"))
        ui.onNodeWithTag("ai_manage_open").performClick(); ui.waitUntil(5000) { !vm.state.value.managementLoading }
        ui.onNodeWithText("AI 管理 · Mock").assertIsDisplayed()
    }
    private fun tab(tab: AiManagementTab) { ui.onNodeWithTag("ai_manage_tab_" + tab.name).performClick() }
    private fun click(tag: String) { ui.onNodeWithTag("ai_manage_scroll").performScrollToNode(hasTestTag(tag)); ui.onNodeWithTag(tag).performClick(); ui.waitUntil(5000) { !vm.state.value.managementSaving } }
    private fun seed(repo: AiTestRepository) = runBlocking { repo.saveSetup(AiDemoSetup()) }
    @After fun close() { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() } }

    @Test fun profileEditsSourcesAndCorrectionUndoAreWiredAndPersistAfterReopening() {
        val repo = AiTestRepository(); seed(repo); runBlocking { repo.saveFeedback("mock-0", AiFeedback.LIKE) }; show(repo); open()
        click("ai_manage_pref_科幻_DISLIKE"); assertEquals(PreferenceDirection.DISLIKE, runBlocking { repo.readSetup() }.preferences["科幻"])
        click("ai_manage_source_feedback"); assertTrue(vm.state.value.management!!.inferred.isEmpty()); assertEquals(1, runBlocking { repo.readSetup() }.feedback.size)
        click("ai_manage_source_feedback"); click("ai_manage_correct_科幻"); assertEquals(setOf("科幻"), vm.state.value.management!!.corrections)
        click("ai_manage_undo_科幻"); assertEquals("科幻", vm.state.value.management!!.inferred.single().feature.label)
        ui.onNodeWithTag("ai_manage_close").performClick(); open()
        ui.onNodeWithTag("ai_manage_scroll").performScrollToNode(hasTestTag("ai_manage_pref_科幻_DISLIKE")); ui.onNodeWithTag("ai_manage_pref_科幻_DISLIKE").assertIsSelected()
        assertEquals(0, repo.requests)
    }
    @Test fun allFourCleanupConfirmationsCanBeCanceledAndFailedClearCanRetry() {
        val repo = AiTestRepository(); seed(repo); runBlocking { repo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS) }; show(repo); open(); tab(AiManagementTab.CLEANUP)
        val before = runBlocking { repo.readManagement() }
        for (action in AiClearAction.entries) {
            click("ai_manage_clear_" + action.name); ui.onNodeWithText("确认" + action.label + "？").assertIsDisplayed()
            ui.onNodeWithTag("ai_manage_clear_cancel").performClick(); assertEquals(before, runBlocking { repo.readManagement() })
        }
        click("ai_manage_clear_HISTORY"); repo.store.failWrites = true; ui.onNodeWithTag("ai_manage_clear_confirm").performClick()
        ui.waitUntil(5000) { vm.state.value.managementError != null }; ui.onNodeWithTag("ai_manage_clear_error").assertIsDisplayed(); assertEquals(before, runBlocking { repo.readManagement() })
        repo.store.failWrites = false; ui.onNodeWithTag("ai_manage_clear_confirm").performClick(); ui.waitUntil(5000) { vm.state.value.pendingClear == null }
        tab(AiManagementTab.HISTORY); ui.onNodeWithTag("ai_manage_history_empty").assertIsDisplayed(); assertEquals(before.setup, runBlocking { repo.readSetup() })
    }
    @Test fun quotaExhaustionKeepsManagementAndHistoryUsableAndOnlyDisablesNewRequests() {
        val repo = AiTestRepository(); seed(repo); runBlocking { repo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS) }; show(repo); open(); tab(AiManagementTab.QUOTA)
        click("ai_manage_quota_EXHAUSTED"); assertEquals(AiQuotaStatus.EXHAUSTED, vm.state.value.quota.status)
        tab(AiManagementTab.HISTORY); ui.onNodeWithTag("ai_manage_scroll").performScrollToIndex(1); ui.onNodeWithText("Mock 演示作品 1").assertExists()
        ui.onNodeWithTag("ai_manage_close").performClick(); ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_enter")); ui.onNodeWithTag("ai_enter").performClick()
        ui.onNodeWithTag("ai_mode_FOR_YOU").assertIsNotEnabled(); ui.onNodeWithTag("ai_submit").assertIsNotEnabled(); assertEquals(1, repo.requests)
        open(); tab(AiManagementTab.QUOTA); click("ai_manage_quota_AVAILABLE"); ui.onNodeWithTag("ai_manage_close").performClick()
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_mode_FOR_YOU")); ui.onNodeWithTag("ai_mode_FOR_YOU").assertIsEnabled(); assertEquals(1, repo.requests)
    }
    @Test fun managementHasReadableLayoutsAndScreenshotsInBothThemes() {
        val repo = AiTestRepository(); seed(repo); val theme = mutableStateOf(ThemeMode.LIGHT); show(repo, theme); open()
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            ui.runOnIdle { theme.value = mode }
            for (section in AiManagementTab.entries) {
                tab(section); ui.onNodeWithTag("ai_manage_scroll").assertIsDisplayed(); ui.onNodeWithTag("ai_manage_close").assertIsDisplayed()
                capture(mode.name.lowercase() + "-" + section.name.lowercase())
            }
        }
    }
    private fun capture(name: String) {
        ui.waitForIdle(); val automation = InstrumentationRegistry.getInstrumentation().uiAutomation; automation.waitForIdle(100, 5000)
        val image = requireNotNull(automation.takeScreenshot()); val colors = mutableSetOf<Int>()
        for (y in 0 until image.height step 8) for (x in 0 until image.width step 8) colors.add(image.getPixel(x, y))
        assertTrue("Rendered screenshot required", colors.size > 4)
        val context = ApplicationProvider.getApplicationContext<Context>(); val target = File(context.cacheDir, "a7-ui-screenshots").apply { mkdirs() }
        File(target, name + ".png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
    }
}
