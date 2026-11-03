package com.yingji.app.presentation

import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.yingji.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Exercises the real navigation wiring without saving settings or user records. */
class AppNavigationStabilityTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun oneHundredReadOnlyNavigationActionsKeepTheMainActivityResponsive() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription("搜索").fetchSemanticsNodes().isNotEmpty()
        }
        var actions = 0
        repeat(10) {
            for (title in listOf("想看", "观看中", "已看完", "我的", "首页")) {
                composeRule.onNode(hasClickAction() and hasText(title) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
                assertTitle(title)
                actions += 1
            }

            val toWall = composeRule.onAllNodesWithContentDescription("切换到海报墙")
                .fetchSemanticsNodes().isNotEmpty()
            composeRule.onNodeWithContentDescription(if (toWall) "切换到海报墙" else "切换到列表")
                .performClick()
            composeRule.onNodeWithContentDescription(if (toWall) "切换到列表" else "切换到海报墙")
                .assertIsDisplayed()
            actions += 1

            composeRule.onNodeWithContentDescription("搜索").performClick()
            assertTitle("搜索")
            composeRule.onNodeWithContentDescription("返回").performClick()
            assertTitle("首页")
            actions += 2

            composeRule.onNodeWithContentDescription("新增作品").performClick()
            assertTitle("新增作品")
            composeRule.onNodeWithContentDescription("返回").performClick()
            assertTitle("首页")
            actions += 2
        }
        composeRule.runOnIdle {
            assertEquals(100, actions)
            assertFalse(composeRule.activity.isFinishing)
            assertEquals(Lifecycle.State.RESUMED, composeRule.activity.lifecycle.currentState)
        }
        Log.i("YingJiStability", "NAVIGATION_ACTIONS=$actions; CYCLES=10")
    }

    private fun assertTitle(title: String) {
        composeRule.onNode(hasText(title) and !hasClickAction()).assertIsDisplayed()
    }
}
