package com.yingji.app.presentation.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AiHomeAppearanceTest {
    @get:Rule val ui = createComposeRule()
    @Test fun mockControlsAndOnboardingRemainUsableInBothThemes() {
        val repo = AiTestRepository()
        val models = ViewModelStore()
        lateinit var vm: AiHomeViewModel
        val theme = mutableStateOf(ThemeMode.LIGHT)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = AiHomeViewModel(repo); models.put("ai", vm) }
            ui.setContent { YingJiTheme(theme.value) { HomeScreen(emptyList(), emptyMap(), {}, {}, {}, aiViewModel = vm) } }
            ui.waitUntil(5000) { !vm.state.value.initializing }
            ui.onNodeWithTag("movie_collection").performScrollToIndex(1)
            ui.onNodeWithTag("ai_home_card").assertIsDisplayed(); capture("home-light")
            ui.onNodeWithTag("ai_enter").performClick()
            ui.onNodeWithText("1/2 · 选择偏好").assertIsDisplayed(); capture("onboarding-light")
            ui.onNodeWithTag("ai_setup_next").performClick()
            ui.onNodeWithTag("ai_source_ratings").assertIsOff()
            ui.onNodeWithTag("ai_source_feedback").assertIsOn(); capture("sources-light")
            ui.onNodeWithTag("ai_setup_skip").performClick()
            ui.waitUntil(5000) { vm.state.value.setup.completed }
            ui.onNodeWithTag("movie_collection").performScrollToIndex(1)
            ui.onNodeWithTag("ai_mode_FOR_YOU").performClick()
            ui.waitUntil(5000) { vm.state.value.result == AiResultState.SUCCESS }
            ui.onNodeWithTag("movie_collection").performScrollToIndex(2)
            ui.onNodeWithText("Mock 演示作品 1").assertExists(); capture("recommendation-light")
            ui.runOnIdle { theme.value = ThemeMode.DARK }
            ui.onNodeWithTag("movie_collection").performScrollToIndex(1)
            ui.onNodeWithTag("ai_submit").assertExists(); capture("controls-dark")
            ui.onNodeWithTag("movie_collection").performScrollToIndex(2)
            ui.onNodeWithText("Mock 演示作品 1").assertExists(); capture("recommendation-dark")
            assertTrue(runBlocking { repo.readSetup() }.completed)
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() } }
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val dialogVisible = ui.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        val bitmap = if (dialogVisible) {
            // Dialog pixels belong to another window; capture the display after window events settle.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.waitForIdle(100, 5000)
            requireNotNull(automation.takeScreenshot())
        } else {
            // PixelCopy waits for drawing instead of capturing the blank first activity frame.
            ui.onRoot().captureToImage().asAndroidBitmap()
        }
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) colors.add(bitmap.getPixel(x, y))
        assertTrue("Screenshot must contain rendered UI, not a blank frame: " + name, colors.size > 4)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "a3-ui-screenshots").apply { mkdirs() }
        File(directory, name + ".png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
