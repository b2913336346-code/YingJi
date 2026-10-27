package com.yingji.app.presentation.ai

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.TmdbCandidateCatalogRepository
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.ui.theme.YingJiTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class TmdbCandidatePreviewScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val opened = mutableStateOf(true)
    private val theme = mutableStateOf(ThemeMode.DARK)
    private val visited = mutableListOf<String>()
    private var browserUnavailable = false
    private lateinit var vm: TmdbCredentialViewModel
    private lateinit var credentials: TmdbCredentialRepository
    private var oldOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private class Memory : TmdbSecureCredentialStore {
        var key: String? = null; var revision = "empty"; var writes = 0
        override suspend fun read() = TmdbCredentialRecord(key?.let(TmdbSecret::fromInput), 100, revision)
        override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String {
            writes++; this.key = key?.useValue { it }; revision = "revision-" + writes; return revision
        }
    }
    private class Transport : TmdbCatalogTransport {
        override val isMock = true
        val calls = AtomicInteger()
        var empty = false
        var problem: TmdbProblemCode? = null
        var gate: CompletableDeferred<Unit>? = null
        var ignoreCancellation = false
        override fun revision() = "fixture"
        override fun isCurrent(revision: String) = revision == "fixture"
        override suspend fun read(request: TmdbCatalogRequest): String {
            calls.incrementAndGet()
            gate?.let { if (ignoreCancellation) withContext(NonCancellable) { it.await() } else it.await() }
            problem?.let { throw TmdbProblem(it) }
            return if (request.id == null) if (empty) """{"page":1,"results":[]}""" else """{"page":1,"results":[{"id":7}]}"""
                else if (request.kind == TmdbMediaKind.MOVIE) """{"id":7,"title":"虚构预览电影","release_date":"2024-01-01","runtime":90}"""
                else """{"id":7,"name":"虚构预览剧集","first_air_date":"2023-01-01","episode_run_time":[45]}"""
        }
    }
    @Before fun orient() {
        val landscape = InstrumentationRegistry.getArguments().getString("tmdbOrientation") == "landscape"
        ui.runOnUiThread { oldOrientation = ui.activity.requestedOrientation; ui.activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
    }
    @After fun clear() { ui.runOnUiThread { models.clear(); ui.activity.requestedOrientation = oldOrientation } }
    private fun show(t: Transport, configured: Boolean = true): Memory {
        val store = Memory(); credentials = TmdbCredentialRepository(store) { 100 }
        if (configured) runBlocking { credentials.save("TEST_ONLY_TMDB_preview") }
        val catalog = TmdbCandidateCatalogRepository(t, { TmdbSourcePermission(CandidateLicenseState.ACTIVE,
            "App-authored synthetic fixture permission", true, true, false) }, { 100 })
        ui.runOnUiThread { vm = TmdbCredentialViewModel(credentials, TmdbCandidatePreview(catalog, { 100 }, true)); models.put("preview", vm) }
        ui.setContent { CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
            override fun openUri(uri: String) { if (browserUnavailable) throw IllegalArgumentException("TEST_ONLY browser unavailable"); visited.add(uri) }
        }) { YingJiTheme(theme.value) {
            if (opened.value) TmdbCredentialDialog(vm) { opened.value = false }
            else TextButton(onClick = { opened.value = true }, modifier = Modifier.testTag("preview_reopen")) { Text("重新打开") }
        } } }
        ui.waitUntil(5000) { vm.state.value.status != TmdbCredentialStatus.LOADING }
        return store
    }
    private fun scroll(tag: String) { ui.onNodeWithTag("tmdb_config_scroll").performScrollToNode(hasTestTag(tag)) }
    private fun read() { scroll("tmdb_preview_read"); ui.onNodeWithTag("tmdb_preview_read").performClick() }
    private fun finished() { ui.waitUntil(5000) { vm.preview.value.status !in setOf(TmdbPreviewStatus.IDLE, TmdbPreviewStatus.LOADING) } }
    private fun screenshot(suffix: String) {
        ui.waitForIdle(); val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(); assertNotNull(bitmap)
        val direction = if (ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(context.filesDir, "tmdb-preview-screenshots/" + direction + "-" + theme.value.name.lowercase() + "-" + suffix + ".png")
        output.parentFile!!.mkdirs(); output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    @Test fun explicitClickUsesAdapterAndClosingClearsMemoryWithoutPersistingResults() {
        val t = Transport(); val gate = CompletableDeferred<Unit>(); t.gate = gate
        val store = show(t); assertEquals(0, t.calls.get()); assertEquals(1, store.writes)
        read(); ui.waitUntil(5000) { t.calls.get() == 1 }
        ui.onNodeWithTag("tmdb_preview_read").assertIsNotEnabled(); ui.runOnUiThread { vm.readCandidates(); vm.readCandidates() }; assertEquals(1, t.calls.get())
        gate.complete(Unit); finished(); assertEquals(4, t.calls.get()); assertEquals(2, vm.preview.value.rows.size)
        scroll("tmdb_config_status"); ui.onNodeWithTag("tmdb_config_status").assertTextContains("本次读取成功", substring = true)
        scroll("tmdb_preview_movie:7"); ui.onNodeWithTag("tmdb_preview_movie:7").assertIsDisplayed()
        scroll("tmdb_preview_tv:7"); ui.onNodeWithTag("tmdb_preview_tv:7").assertIsDisplayed(); ui.onNodeWithText("剧集总片长未知").assertIsDisplayed()
        assertEquals(setOf("movie:7", "tv:7"), vm.preview.value.rows.map { it.identity.externalId }.toSet())
        assertTrue(vm.preview.value.rows.all { it.identity.sourceId == "tmdb-protocol-mock" && it.cache.state == CandidateCacheState.FRESH })
        assertEquals(1, store.writes); assertEquals(TmdbCredentialStatus.SAVED_UNVERIFIED, credentials.state.value.status)
        ui.onNodeWithTag("tmdb_config_close").performClick(); ui.waitUntil(5000) { vm.preview.value.status == TmdbPreviewStatus.IDLE }
        ui.onNodeWithTag("preview_reopen").performClick(); scroll("tmdb_preview_status"); ui.onNodeWithTag("tmdb_preview_status").assertTextEquals("尚未读取候选")
        assertEquals(4, t.calls.get()); assertEquals(1, store.writes); assertTrue(vm.preview.value.rows.isEmpty())
    }
    @Test fun missingCredentialDoesNotReadTransport() {
        val t = Transport(); val store = show(t, false); read(); finished()
        scroll("tmdb_preview_status"); ui.onNodeWithTag("tmdb_preview_status").assertTextContains("请先保存", substring = true)
        assertEquals(0, t.calls.get()); assertEquals(0, store.writes)
    }
    @Test fun emptyAndAuthenticationFailuresHaveFeedbackAndManualRetryWorks() {
        val t = Transport(); t.empty = true; show(t); read(); finished()
        assertEquals(TmdbPreviewStatus.EMPTY, vm.preview.value.status); assertTrue(vm.preview.value.rows.isEmpty()); assertEquals(2, t.calls.get())
        scroll("tmdb_preview_status"); ui.onNodeWithTag("tmdb_preview_status").assertTextContains("不补充虚构作品", substring = true)
        t.empty = false; t.problem = TmdbProblemCode.UNAUTHORIZED; read(); finished(); assertEquals(3, t.calls.get())
        scroll("tmdb_preview_status"); ui.onNodeWithTag("tmdb_preview_status").assertTextContains("令牌或权限无效", substring = true)
        t.problem = null; read(); finished(); assertEquals(TmdbPreviewStatus.SUCCESS, vm.preview.value.status); assertEquals(7, t.calls.get())
    }
    @Test fun networkRateLimitAndMalformedErrorsAreSafeAndNeverAutoResubmittedByUi() {
        val t = Transport(); show(t)
        val cases = mapOf(TmdbProblemCode.OFFLINE to "无法连接", TmdbProblemCode.RATE_LIMITED to "请求受限",
            TmdbProblemCode.INVALID_RESPONSE to "响应无效", TmdbProblemCode.SECURE_CONNECTION to "安全连接失败")
        var expected = 0
        for ((problem, message) in cases) {
            t.problem = problem; read(); finished(); expected++; assertEquals(expected, t.calls.get())
            assertTrue(vm.preview.value.rows.isEmpty()); scroll("tmdb_preview_status")
            ui.onNodeWithTag("tmdb_preview_status").assertTextContains(message, substring = true)
            assertFalse(vm.preview.value.message.orEmpty().contains("TEST_ONLY_TMDB_preview"))
        }
    }
    @Test fun replacementDeletionAndCloseDiscardLateResponses() {
        val t = Transport(); val gate = CompletableDeferred<Unit>(); t.gate = gate; t.ignoreCancellation = true
        show(t); val existingJobs = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        read(); ui.waitUntil(5000) { t.calls.get() == 1 }
        val pending = vm.viewModelScope.coroutineContext[Job]!!.children.single { it !in existingJobs }
        ui.runOnUiThread { vm.save("TEST_ONLY_TMDB_replacement") {} }
        ui.waitUntil(5000) { vm.state.value.revision == "revision-2" && !vm.state.value.saving }
        gate.complete(Unit); runBlocking { withTimeout(5000) { pending.join() } }; ui.waitForIdle()
        assertEquals(TmdbPreviewStatus.IDLE, vm.preview.value.status); assertTrue(vm.preview.value.rows.isEmpty())
        t.gate = null; read(); finished(); assertEquals(TmdbPreviewStatus.SUCCESS, vm.preview.value.status)
        scroll("tmdb_token_delete"); ui.onNodeWithTag("tmdb_token_delete").performClick(); assertTrue(vm.preview.value.rows.isEmpty())
        ui.onNodeWithTag("tmdb_delete_confirm").performClick(); ui.waitUntil(5000) { credentials.state.value.status == TmdbCredentialStatus.NOT_CONFIGURED }
        assertTrue(vm.preview.value.rows.isEmpty()); val previous = t.calls.get(); read(); finished(); assertEquals(previous, t.calls.get())
    }
    @Test fun closingWhileLoadingCancelsAndReopeningDoesNotReadAgain() {
        val t = Transport(); t.gate = CompletableDeferred()
        show(t); val existingJobs = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        read(); ui.waitUntil(5000) { t.calls.get() == 1 }
        val pending = vm.viewModelScope.coroutineContext[Job]!!.children.single { it !in existingJobs }
        ui.onNodeWithTag("tmdb_config_close").performClick()
        ui.onNodeWithTag("preview_reopen").assertIsDisplayed(); ui.waitForIdle()
        runBlocking { withTimeout(5000) { pending.join() } }; assertTrue(pending.isCancelled)
        ui.onNodeWithTag("preview_reopen").performClick(); scroll("tmdb_preview_status")
        ui.onNodeWithTag("tmdb_preview_status").assertTextEquals("尚未读取候选")
        assertTrue(vm.preview.value.rows.isEmpty()); assertEquals(1, t.calls.get())
    }
    @Test fun creditsAndResultsRemainScrollableInBothThemes() {
        val t = Transport(); show(t); read(); finished()
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            ui.runOnUiThread { theme.value = mode }
            scroll("tmdb_preview_read"); ui.onNodeWithTag("tmdb_preview_read").assertIsDisplayed(); screenshot("preview")
            scroll("tmdb_credits_logo"); ui.onNodeWithTag("tmdb_credits_logo").assertIsDisplayed()
            scroll("tmdb_credits_notice"); ui.onNodeWithTag("tmdb_credits_notice").assertTextEquals("This product uses the TMDB API but is not endorsed or certified by TMDB.")
            scroll("tmdb_credits_link"); ui.onNodeWithTag("tmdb_credits_link").assertIsDisplayed(); ui.onNodeWithTag("tmdb_config_close").assertIsDisplayed(); screenshot("credits")
        }
        ui.onNodeWithTag("tmdb_credits_link").performClick(); assertEquals(listOf("https://www.themoviedb.org"), visited)
        ui.runOnUiThread { browserUnavailable = true }; ui.onNodeWithTag("tmdb_credits_link").performClick()
        scroll("tmdb_credits_error"); ui.onNodeWithTag("tmdb_credits_error").assertTextEquals("无法打开浏览器，请稍后重试")
        assertEquals(4, t.calls.get())
    }
}
