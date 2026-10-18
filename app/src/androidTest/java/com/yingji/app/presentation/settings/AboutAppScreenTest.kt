package com.yingji.app.presentation.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.yingji.app.BuildConfig
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Tag
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AboutAppScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private var statisticsClicks = 0

    @Test
    fun aboutAndVersionAreAbsentAtEndOfLongManagementLists() {
        showSettings(ThemeMode.LIGHT, withManyEntities = true)
        scrollToEnd("标签40")
        composeRule.onNodeWithText("标签40").assertIsDisplayed()
        assertAboutRemoved()
    }

    @Test
    fun aboutAndVersionAreAbsentWithEmptyDataInDarkTheme() {
        showSettings(ThemeMode.DARK)
        scrollToEnd("还没有自定义标签")
        composeRule.onNodeWithText("还没有自定义标签").assertIsDisplayed()
        composeRule.onNodeWithText("我的").assertIsDisplayed()
        assertAboutRemoved()
    }

    @Test
    fun statisticsAndDefaultSettingsRemainAvailableWithoutAbout() {
        showSettings(ThemeMode.LIGHT)
        composeRule.onNode(hasClickAction() and hasText("查看统计")).performClick()
        composeRule.runOnIdle { assertEquals(1, statisticsClicks) }
        scrollToEnd("还没有自定义标签")
        assertAboutRemoved()
        settingsList().performScrollToNode(hasText("默认展示方式"))
        composeRule.onNodeWithText("默认展示方式").assertIsDisplayed()
        settingsList().performScrollToNode(hasText("默认排序"))
        composeRule.onNodeWithText("默认排序").assertIsDisplayed()
    }

    private fun showSettings(theme: ThemeMode, withManyEntities: Boolean = false) {
        composeRule.setContent {
            YingJiTheme(themeMode = theme) {
                SettingsScreen(
                    categories = if (withManyEntities) (1L..40L).map { Category(it, "分类$it", false) } else emptyList(),
                    tags = if (withManyEntities) (1L..40L).map { Tag(it, "标签$it") } else emptyList(),
                    themeMode = theme, onThemeModeChange = {}, onStatisticsClick = { statisticsClicks += 1 },
                    onAddCategory = {}, onDeleteCategory = {}, onAddTag = {}, onDeleteTag = {},
                )
            }
        }
    }

    private fun settingsList() = composeRule.onNode(
        hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    )

    private fun scrollToEnd(lastText: String) {
        settingsList().performScrollToNode(hasText(lastText))
        // Reach the actual end so a regressed About item below the last tag
        // cannot evade the absence assertions merely by staying off screen.
        settingsList().performTouchInput { swipeUp() }
    }

    private fun assertAboutRemoved() {
        composeRule.onNodeWithText("关于应用").assertDoesNotExist()
        composeRule.onNodeWithText("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertDoesNotExist()
        composeRule.onNodeWithText("版本名称：${BuildConfig.VERSION_NAME}").assertDoesNotExist()
        composeRule.onNodeWithText("版本代码：${BuildConfig.VERSION_CODE}").assertDoesNotExist()
    }
}
