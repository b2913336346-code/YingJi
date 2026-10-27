package com.yingji.app.presentation.ai

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Read-only rotation check on the real home screen; does not change saved AI or library data. */
class AiHomeRotationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun realHomeKeepsBottomAnchoringAfterLandscapeToPortraitRotation() {
        val original = ui.activity.requestedOrientation
        try {
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                ui.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
                val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
                ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == expected }
                ui.waitUntil(10000) { ui.onAllNodesWithTag("ai_manage_open").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag("ai_manage_open"))
                ui.onNodeWithTag("ai_manage_open").assertIsDisplayed()
                ui.onNodeWithContentDescription("新增作品").assertIsDisplayed()
                if (expected == Configuration.ORIENTATION_PORTRAIT) {
                    val collection = ui.onNodeWithTag("movie_collection").fetchSemanticsNode().boundsInRoot
                    val card = ui.onNodeWithTag("ai_home_card").fetchSemanticsNode().boundsInRoot
                    assertEquals(collection.bottom - with(ui.density) { 88.dp.toPx() }, card.bottom, 2f)
                    assertTrue(card.bottom < ui.onNodeWithContentDescription("新增作品").fetchSemanticsNode().boundsInRoot.top)
                }
                ui.waitForIdle(); val automation = InstrumentationRegistry.getInstrumentation().uiAutomation; automation.waitForIdle(100, 5000)
                val image = requireNotNull(automation.takeScreenshot())
                val dir = File(ui.activity.cacheDir, "a7-portrait-screenshots").apply { mkdirs() }
                File(dir, "real-home-" + (if (expected == Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape") + ".png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
            }
        } finally { ui.activityRule.scenario.onActivity { it.requestedOrientation = original } }
    }
}
