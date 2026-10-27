package com.yingji.app.presentation.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yingji.app.MainActivity
import org.junit.Rule
import org.junit.Test

class TmdbCredentialNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun actualApplicationOpensConfigurationAndReturnsToExistingManagement() {
        ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_manage_open")); ui.onNodeWithTag("ai_manage_open").performClick()
        ui.waitUntil(5000) { ui.onAllNodes(hasTestTag("ai_manage_tmdb_config") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("ai_manage_tmdb_config").performClick(); ui.onNodeWithText("TMDB 数据源配置").assertIsDisplayed()
        ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(hasTestTag("tmdb_token_input")); ui.onNodeWithTag("tmdb_token_input").assertTextContains("")
        ui.onNodeWithTag("tmdb_config_close").performClick(); ui.onNodeWithText("AI 管理 · Mock").assertIsDisplayed()
        ui.onNodeWithTag("ai_manage_scope_MOCK").assertIsSelected(); ui.onNodeWithTag("ai_manage_close").performClick()
    }
}
