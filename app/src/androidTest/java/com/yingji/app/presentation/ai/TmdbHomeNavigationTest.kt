package com.yingji.app.presentation.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.MainActivity
import com.yingji.app.YingJiApplication
import com.yingji.app.data.ai.MockCandidateCatalogRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual app wiring; never presses either network read button or changes any credential. */
class TmdbHomeNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun scroll(tag: String) { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)) }
    @Test fun actualHomeDefaultsToMockAndLeavingDiscardsSourceSelectionWithoutNetwork() {
        val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
        assertTrue(app.candidateCatalog is MockCandidateCatalogRepository)
        ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
        scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
        scroll("ai_source_tmdb"); ui.onNodeWithTag("ai_source_tmdb").performClick()
        scroll("tmdb_home_status"); ui.onNodeWithTag("tmdb_home_status").assertTextEquals("尚未读取候选")
        ui.onAllNodes(hasTestTag("tmdb_home_movie:7")).assertCountEquals(0)
        ui.onNodeWithText("我的").performClick(); ui.onNodeWithText("首页").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
        scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
        scroll("ai_source_tmdb"); ui.onNodeWithTag("ai_source_tmdb").performClick()
        scroll("tmdb_home_status"); ui.onNodeWithTag("tmdb_home_status").assertTextEquals("尚未读取候选")
        scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").performClick()
        assertTrue(app.candidateCatalog is MockCandidateCatalogRepository)
    }
}
