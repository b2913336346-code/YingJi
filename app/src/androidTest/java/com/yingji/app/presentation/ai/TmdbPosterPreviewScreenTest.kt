package com.yingji.app.presentation.ai

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yingji.app.data.ai.TmdbPosterPreviewLoader
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TmdbPosterPreviewScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private fun candidate(source: String = "tmdb", poster: TmdbPoster? = TmdbPoster("/fictionalPoster.png")) =
        CatalogCandidate(CandidateIdentity(source, "movie:7"), "虚构海报测试作品", verifiedAt = 1, poster = poster)
    private fun bitmap() = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
    private fun screenshot(name: String) {
        val directory = File(ui.activity.cacheDir, "poster-preview-test-evidence")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val destination = File(directory, name + ".png")
        destination.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun loadingThenDecodedPosterKeepsTheExistingAddActionInLightAndDarkThemes() {
        val result = CompletableDeferred<Bitmap>()
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val calls = AtomicInteger()
        var added = 0
        val loader = TmdbPosterPreviewLoader { calls.incrementAndGet(); result.await() }
        ui.setContent {
            CompositionLocalProvider(LocalTmdbPosterPreviewLoader provides loader) {
                YingJiTheme(theme.value) { TmdbCandidateCard(candidate(), onAdd = { added++ }) }
            }
        }
        ui.onNodeWithTag("tmdb_preview_movie:7_poster_loading").assertIsDisplayed()
        ui.onNodeWithTag("tmdb_add_movie:7").performClick()
        assertEquals(1, added)
        result.complete(bitmap())
        ui.waitUntil { ui.onAllNodesWithTag("tmdb_preview_movie:7_poster_loaded").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("在线海报预览").assertIsDisplayed()
        screenshot("poster-preview-light")
        ui.runOnIdle { theme.value = ThemeMode.DARK }
        ui.onNodeWithTag("tmdb_preview_movie:7_poster_loaded").assertIsDisplayed()
        screenshot("poster-preview-dark")
        assertEquals(1, calls.get())
    }
    @Test fun failedImageDoesNotRemoveCandidateOrBlockConfirmedImportEntry() {
        var added = 0
        ui.setContent {
            CompositionLocalProvider(LocalTmdbPosterPreviewLoader provides TmdbPosterPreviewLoader { throw java.io.IOException("synthetic failure") }) {
                YingJiTheme(ThemeMode.LIGHT) { TmdbCandidateCard(candidate(), onAdd = { added++ }) }
            }
        }
        ui.waitUntil { ui.onAllNodesWithTag("tmdb_preview_movie:7_poster_error").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("海报预览失败").assertIsDisplayed()
        ui.onNodeWithText("虚构海报测试作品").assertIsDisplayed()
        ui.onNodeWithTag("tmdb_add_movie:7").performClick()
        assertEquals(1, added)
    }
    @Test fun absentPostersAndProtocolMocksNeverStartAnImageRequest() {
        val calls = AtomicInteger()
        val row = mutableStateOf(candidate(poster = null))
        ui.setContent {
            CompositionLocalProvider(LocalTmdbPosterPreviewLoader provides TmdbPosterPreviewLoader { calls.incrementAndGet(); bitmap() }) {
                YingJiTheme(ThemeMode.LIGHT) { TmdbCandidateCard(row.value) }
            }
        }
        ui.onNodeWithText("虚构海报测试作品").assertIsDisplayed()
        ui.onNodeWithTag("tmdb_preview_movie:7_poster").assertDoesNotExist()
        ui.runOnIdle { row.value = candidate(source = "tmdb-protocol-mock") }
        ui.onNodeWithTag("tmdb_preview_movie:7_poster").assertDoesNotExist()
        ui.runOnIdle { assertEquals(0, calls.get()) }
    }
    @Test fun leavingTheCandidateListCancelsTheInFlightImageAndRemovesItsState() {
        val shown = mutableStateOf(true)
        val started = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val loader = TmdbPosterPreviewLoader {
            started.set(true)
            try { awaitCancellation() } finally { cancelled.set(true) }
        }
        ui.setContent {
            CompositionLocalProvider(LocalTmdbPosterPreviewLoader provides loader) {
                YingJiTheme(ThemeMode.LIGHT) { if (shown.value) TmdbCandidateCard(candidate()) }
            }
        }
        ui.waitUntil { started.get() }
        ui.runOnIdle { shown.value = false }
        ui.waitUntil { cancelled.get() }
        ui.onNodeWithTag("tmdb_preview_movie:7_poster").assertDoesNotExist()
    }
}
