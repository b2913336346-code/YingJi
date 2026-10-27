package com.yingji.app.presentation

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.common.YingJiSnackbarHost
import com.yingji.app.ui.theme.YingJiTheme
import java.io.File
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SnackbarFeedbackTest {
    @get:Rule val ui = createComposeRule()

    @Test fun feedbackHasRenderedContrastingTextInBothThemes() {
        val theme = mutableStateOf(ThemeMode.DARK)
        lateinit var host: SnackbarHostState
        var foreground = Color.Unspecified
        var background = Color.Unspecified
        ui.setContent {
            YingJiTheme(theme.value) {
                foreground = MaterialTheme.colorScheme.onSurface
                background = MaterialTheme.colorScheme.surfaceContainerHighest
                host = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                Scaffold(snackbarHost = { YingJiSnackbarHost(host) }) {
                    TextButton(onClick = { scope.launch { host.showSnackbar("此候选已加入影迹，请查看已有作品", duration = SnackbarDuration.Indefinite) } },
                        modifier = Modifier.testTag("show_feedback")) { Text("显示反馈") }
                }
            }
        }
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            ui.runOnIdle { theme.value = mode }
            ui.onNodeWithTag("show_feedback").performClick()
            ui.onNodeWithTag("yingji_snackbar_message").assertTextEquals("此候选已加入影迹，请查看已有作品").assertIsDisplayed()
            val high = maxOf(foreground.luminance(), background.luminance())
            val low = minOf(foreground.luminance(), background.luminance())
            assertTrue("Feedback contrast below 4.5:1", (high + 0.05f) / (low + 0.05f) >= 4.5f)
            val bitmap = ui.onNodeWithTag("yingji_snackbar").captureToImage().asAndroidBitmap()
            var textPixels = 0
            var backgroundPixels = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == foreground.toArgb()) textPixels++
                if (pixel == background.toArgb()) backgroundPixels++
            }
            assertTrue("Expected text color was not actually rendered", textPixels > 20)
            assertTrue("Expected background was not actually rendered", backgroundPixels > 100)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = File(context.cacheDir, "review-fix-screenshots").apply { mkdirs() }
            File(directory, "snackbar-${mode.name.lowercase()}.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
            ui.runOnIdle { host.currentSnackbarData!!.dismiss() }
            ui.waitUntil(5000) { ui.onAllNodesWithTag("yingji_snackbar_message").fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test fun actionAndDismissKeepTheirOriginalResults() {
        var result: SnackbarResult? = null
        ui.setContent {
            YingJiTheme(ThemeMode.DARK) {
                val host = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                Scaffold(snackbarHost = { YingJiSnackbarHost(host) }) {
                    TextButton(onClick = {
                        scope.launch {
                            result = host.showSnackbar("保存失败", actionLabel = "重试", withDismissAction = true, duration = SnackbarDuration.Indefinite)
                        }
                    }, modifier = Modifier.testTag("show_feedback")) { Text("显示反馈") }
                }
            }
        }
        ui.onNodeWithTag("show_feedback").performClick()
        ui.onNodeWithTag("yingji_snackbar_action").performClick()
        ui.runOnIdle { assertEquals(SnackbarResult.ActionPerformed, result) }
        ui.waitUntil(5000) { ui.onAllNodesWithTag("yingji_snackbar_message").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("show_feedback").performClick()
        ui.onNodeWithTag("yingji_snackbar_dismiss").performClick()
        ui.runOnIdle { assertEquals(SnackbarResult.Dismissed, result) }
    }
}
