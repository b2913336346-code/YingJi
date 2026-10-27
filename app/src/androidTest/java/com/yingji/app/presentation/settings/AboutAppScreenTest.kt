package com.yingji.app.presentation.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.BuildConfig
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Tag
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AboutAppScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private var statisticsClicks = 0

    @Test
    fun versionDialogRemainsReachableAfterLongManagementLists() {
        showSettings(ThemeMode.LIGHT, withManyEntities = true)
        settingsList().performScrollToNode(hasText("标签40"))
        composeRule.onNodeWithText("标签40").assertIsDisplayed()
        openAndCheckVersion("about-light")
    }

    @Test
    fun versionDialogWorksWithEmptyDataInDarkTheme() {
        showSettings(ThemeMode.DARK)
        settingsList().performScrollToNode(hasText("还没有自定义标签"))
        composeRule.onNodeWithText("还没有自定义标签").assertIsDisplayed()
        composeRule.onNodeWithText("我的").assertIsDisplayed()
        openAndCheckVersion("about-dark")
    }

    @Test
    fun closingVersionDialogPreservesStatisticsAndDefaultSettings() {
        showSettings(ThemeMode.LIGHT)
        composeRule.onNode(hasClickAction() and hasText("查看统计")).performClick()
        composeRule.runOnIdle { assertEquals(1, statisticsClicks) }
        openAndCheckVersion()
        settingsList().performScrollToNode(hasText("默认展示方式"))
        composeRule.onNodeWithText("默认展示方式").assertIsDisplayed()
        settingsList().performScrollToNode(hasText("默认排序"))
        composeRule.onNodeWithText("默认排序").assertIsDisplayed()
        openAndCheckVersion()
    }

    private fun openAndCheckVersion(screenshot: String? = null) {
        settingsList().performScrollToNode(hasTestTag("about_app_open"))
        composeRule.onNodeWithTag("about_app_open").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("about_version_name")
            .assertTextEquals("版本名称：${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        composeRule.onNodeWithTag("about_version_code")
            .assertTextEquals("版本代码：${BuildConfig.VERSION_CODE}").assertIsDisplayed()
        composeRule.onNodeWithTag("about_content_scroll").performScrollToNode(hasTestTag("about_tmdb_notice"))
        composeRule.onNodeWithTag("about_tmdb_logo").assertIsDisplayed()
        composeRule.onNodeWithTag("about_tmdb_notice").assertTextEquals("This product uses the TMDB API but is not endorsed or certified by TMDB.").assertIsDisplayed()
        composeRule.onNodeWithTag("about_content_scroll").performScrollToNode(hasTestTag("about_tmdb_link"))
        composeRule.onNodeWithTag("about_tmdb_link").assertIsDisplayed()
        screenshot?.let { name ->
            val bitmap = composeRule.onNode(isDialog()).captureToImage().asAndroidBitmap()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = File(context.cacheDir, "review-fix-screenshots").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
        composeRule.onNodeWithTag("about_app_close").performClick()
        composeRule.onNodeWithTag("about_version_name").assertDoesNotExist()
        composeRule.onNodeWithTag("about_version_code").assertDoesNotExist()
        composeRule.onNodeWithTag("about_app_open").assertIsDisplayed()
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

    private fun settingsList() = composeRule.onNodeWithTag("mine_settings_list")
}
