package com.yingji.app.presentation.ai

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yingji.app.MainActivity
import com.yingji.app.YingJiApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AiKeyConfigurationNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun bothHomeAndMineOpenSameConfigurationWithoutChangingLibraryOrExposingSavedKey() = runBlocking<Unit> {
        val app = ui.activity.application as YingJiApplication; val before = app.movieRepository.observeAll().first()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_key_configure"))
        ui.onNodeWithTag("ai_key_configure").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithTag("ai_key_input").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("DeepSeek", useUnmergedTree = true).assertExists()
        assertEquals("", ui.onNodeWithTag("ai_key_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        ui.onNodeWithContentDescription("返回").performClick(); ui.onNodeWithText("我的").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithTag("mine_settings_list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("mine_settings_list").performScrollToNode(hasTestTag("ai_model_configuration"))
        ui.onNodeWithTag("ai_model_configuration").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithTag("ai_key_input").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("", ui.onNodeWithTag("ai_key_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        ui.onNodeWithContentDescription("返回").performClick(); assertEquals(before, app.movieRepository.observeAll().first())
    }
}
