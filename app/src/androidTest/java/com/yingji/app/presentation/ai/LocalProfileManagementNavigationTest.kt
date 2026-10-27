package com.yingji.app.presentation.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yingji.app.MainActivity
import org.junit.Rule
import org.junit.Test

class LocalProfileManagementNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun actualApplicationExposesBothScopesAndDefaultsBackToMockOnReopen() {
        ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_manage_open")); ui.onNodeWithTag("ai_manage_open").performClick()
        ui.onNodeWithTag("ai_manage_scope_MOCK").assertIsSelected()
        ui.waitUntil(5000) { ui.onAllNodes(hasTestTag("ai_manage_scope_LOCAL_PROFILE") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("ai_manage_scope_LOCAL_PROFILE").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithTag("real_profile_scroll").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("AI 管理 · 真实本地画像").assertIsDisplayed(); ui.onNodeWithTag("ai_manage_close").performClick()
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_manage_open")); ui.onNodeWithTag("ai_manage_open").performClick()
        ui.onNodeWithTag("ai_manage_scope_MOCK").assertIsSelected(); ui.onNodeWithText("AI 管理 · Mock").assertIsDisplayed()
        ui.onNodeWithTag("ai_manage_close").performClick()
    }
}
