package com.yingji.app.presentation.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.MainActivity
import com.yingji.app.YingJiApplication
import com.yingji.app.data.ai.MockCandidateCatalogRepository
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class TmdbPreviewNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun realApplicationWiresPreviewWithoutActivatingHomepageOrReadingOnOpen() {
        val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
        assertTrue(app.candidateCatalog is MockCandidateCatalogRepository)
        assertNotNull(app.tmdbCandidatePreview)
        ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_manage_open")); ui.onNodeWithTag("ai_manage_open").performClick()
        ui.waitUntil(5000) { ui.onAllNodes(hasTestTag("ai_manage_tmdb_config") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("ai_manage_tmdb_config").performClick()
        ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(hasTestTag("tmdb_preview_read"))
        ui.onNodeWithTag("tmdb_preview_read").assertIsEnabled()
        ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(hasTestTag("tmdb_preview_status"))
        ui.onNodeWithTag("tmdb_preview_status").assertTextEquals("尚未读取候选")
        ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(hasTestTag("tmdb_credits_logo"))
        ui.onNodeWithTag("tmdb_credits_logo").assertIsDisplayed()
        ui.onNodeWithTag("tmdb_config_close").performClick(); ui.onNodeWithText("AI 管理 · Mock").assertIsDisplayed()
        ui.onNodeWithTag("ai_manage_tmdb_config").performClick()
        ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(hasTestTag("tmdb_preview_status"))
        ui.onNodeWithTag("tmdb_preview_status").assertTextEquals("尚未读取候选")
        ui.onNodeWithTag("tmdb_config_close").performClick(); ui.onNodeWithTag("ai_manage_close").performClick()
        assertTrue(app.candidateCatalog is MockCandidateCatalogRepository)
    }
}
