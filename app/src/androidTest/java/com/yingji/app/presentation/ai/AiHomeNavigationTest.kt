package com.yingji.app.presentation.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.YingJiApplication
import com.yingji.app.navigation.YingJiNavGraph
import com.yingji.app.ui.theme.YingJiTheme
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AiHomeNavigationTest {
    @get:Rule val ui = createComposeRule()
    @Test fun confirmedMockTitleOpensSharedFormAndCategoryIsStillRequired() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
        val original = app.movieRepository.observeAll().first()
        val repo = AiTestRepository(); repo.saveSetup(AiDemoSetup())
        ui.setContent { YingJiTheme { YingJiNavGraph(aiHomeRepository = repo) } }
        ui.waitUntil(5000) { ui.onAllNodesWithTag("ai_enter").fetchSemanticsNodes().isNotEmpty() }
        val footer = if (original.isEmpty()) 1 else original.size
        ui.onNodeWithTag("movie_collection").performScrollToIndex(footer)
        ui.onNodeWithTag("ai_enter").performClick()
        ui.onNodeWithTag("ai_mode_FOR_YOU").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText("Mock 结果：3 项，继续向下查看").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("movie_collection").performScrollToIndex(footer + 1)
        ui.onNodeWithTag("ai_add_mock-0").performClick()
        assertEquals(original, app.movieRepository.observeAll().first())
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithTag("ai_add_mock-0").performClick()
        ui.onNodeWithTag("ai_add_confirm").performClick()
        ui.onNodeWithText("Mock 演示作品 1").assertExists()
        ui.onNodeWithText("保存").performScrollTo().performClick()
        ui.onNodeWithText("请选择作品分类").performScrollTo().assertIsDisplayed()
        assertEquals(original, app.movieRepository.observeAll().first())
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithContentDescription("搜索").assertExists()
        assertEquals(original, app.movieRepository.observeAll().first())
    }
}
