package com.yingji.app.presentation.ai

import android.content.Context
import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.TmdbCandidateCatalogRepository
import com.yingji.app.data.entity.Movie
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Only app-authored protocol fixtures; never reads production credentials or opens a socket. */
class TmdbHomeScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val opened = mutableStateOf(true)
    private val theme = mutableStateOf(ThemeMode.DARK)
    private val visited = mutableListOf<String>()
    private val clicked = mutableListOf<Long>()
    private var browserUnavailable = false
    private var oldOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private lateinit var home: AiHomeViewModel
    private lateinit var preview: TmdbCredentialViewModel
    private lateinit var credentials: TmdbCredentialRepository
    private lateinit var demo: AiTestRepository
    private class Memory : TmdbSecureCredentialStore {
        var key: String? = null; var revision = "empty"; var writes = 0
        override suspend fun read() = TmdbCredentialRecord(key?.let(TmdbSecret::fromInput), 100, revision)
        override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String {
            writes++; this.key = key?.useValue { it }; revision = "fixture-" + writes; return revision
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
                else if (request.kind == TmdbMediaKind.MOVIE) """{"id":7,"title":"虚构首页电影","release_date":"2024-01-01","runtime":90}"""
                else """{"id":7,"name":"虚构首页剧集","first_air_date":"2023-01-01","episode_run_time":[45]}"""
        }
    }
    @Before fun orient() {
        val landscape = InstrumentationRegistry.getArguments().getString("tmdbOrientation") == "landscape"
        ui.runOnUiThread { oldOrientation = ui.activity.requestedOrientation; ui.activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
    }
    @After fun clear() { ui.runOnUiThread { models.clear(); ui.activity.viewModelStore.clear(); ui.activity.requestedOrientation = oldOrientation } }
    private fun show(t: Transport, configured: Boolean = true, movies: List<Movie> = emptyList(), demoReadFails: Boolean = false): Memory {
        val store = Memory(); credentials = TmdbCredentialRepository(store) { 100 }
        if (configured) runBlocking { credentials.save("TEST_ONLY_TMDB_home") }
        demo = AiTestRepository()
        runBlocking { demo.saveSetup(AiDemoSetup()) }
        demo.failReads = demoReadFails
        val catalog = TmdbCandidateCatalogRepository(t, { TmdbSourcePermission(CandidateLicenseState.ACTIVE,
            "App-authored synthetic homepage fixture permission", true, true, false) }, { 100 })
        ui.runOnUiThread { home = AiHomeViewModel(demo, tmdbCredentials = credentials, tmdbPreview = TmdbCandidatePreview(catalog, { 100 }, true)); models.put("home", home) }
        ui.setContent { CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
            override fun openUri(uri: String) { if (browserUnavailable) throw IllegalArgumentException("TEST_ONLY unavailable browser"); visited.add(uri) }
        }) { YingJiTheme(theme.value) {
            if (opened.value) HomeScreen(movies, emptyMap(), { clicked.add(it) }, {}, {}, aiViewModel = home)
            else TextButton(onClick = { opened.value = true }, modifier = Modifier.testTag("home_reopen")) { Text("返回首页") }
        } } }
        ui.waitUntil(5000) { !home.state.value.initializing }
        ui.runOnUiThread { preview = ViewModelProvider(ui.activity, TmdbCredentialViewModel.factory(credentials)).get("tmdb-home-preview", TmdbCredentialViewModel::class.java) }
        ui.waitUntil(5000) { preview.state.value.status != TmdbCredentialStatus.LOADING }
        return store
    }
    private fun scroll(tag: String) { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)) }
    private fun choose(tmdb: Boolean) { val tag = if (tmdb) "ai_source_tmdb" else "ai_source_mock"; scroll(tag); ui.onNodeWithTag(tag).performClick() }
    private fun read() { scroll("tmdb_home_read"); ui.onNodeWithTag("tmdb_home_read").performClick() }
    private fun finish() { ui.waitUntil(5000) { preview.preview.value.status !in setOf(TmdbPreviewStatus.IDLE, TmdbPreviewStatus.LOADING) } }
    private fun capture(suffix: String) {
        ui.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(); assertNotNull(bitmap)
        val direction = if (ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        val file = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "tmdb-home-screenshots/" + direction + "-" + theme.value.name.lowercase() + "-" + suffix + ".png")
        file.parentFile!!.mkdirs(); file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    private fun unchanged(writes: Int, credentialsWrites: Int, store: Memory) {
        assertEquals(writes, demo.store.writes); assertEquals(credentialsWrites, store.writes)
        assertEquals(0, demo.requests); assertTrue(runBlocking { demo.readManagement() }.history.isEmpty())
        assertTrue(home.state.value.recommendations.isEmpty())
    }
    @Test fun defaultsToMockAndExplicitReadUsesTypedCandidatesWithoutHistoryFeedbackOrImport() {
        val t = Transport(); val store = show(t); val writes = demo.store.writes
        scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected(); assertEquals(0, t.calls.get())
        choose(true); assertEquals(0, t.calls.get()); assertEquals(TmdbPreviewStatus.IDLE, preview.preview.value.status)
        val gate = CompletableDeferred<Unit>(); t.gate = gate; read()
        ui.waitUntil(5000) { t.calls.get() == 1 }; ui.onNodeWithTag("tmdb_home_read").assertIsNotEnabled()
        ui.runOnUiThread { preview.readCandidates(); preview.readCandidates() }; assertEquals(1, t.calls.get())
        gate.complete(Unit); finish(); assertEquals(4, t.calls.get())
        assertEquals(setOf("movie:7", "tv:7"), preview.preview.value.rows.map { it.identity.externalId }.toSet())
        assertTrue(preview.preview.value.rows.all { it.cache.state == CandidateCacheState.FRESH && it.identity.sourceId == "tmdb-protocol-mock" })
        scroll("tmdb_home_movie:7"); ui.onNodeWithTag("tmdb_home_movie:7").assertIsDisplayed()
        scroll("tmdb_home_tv:7"); ui.onNodeWithTag("tmdb_home_tv:7").assertIsDisplayed(); ui.onNodeWithText("剧集总片长未知").assertIsDisplayed()
        ui.onAllNodes(hasText("加入影迹")).assertCountEquals(0); ui.onAllNodes(hasText("喜欢", substring = false)).assertCountEquals(0)
        unchanged(writes, 1, store)
        choose(false); assertEquals(TmdbPreviewStatus.IDLE, preview.preview.value.status); assertTrue(preview.preview.value.rows.isEmpty())
        choose(true); scroll("tmdb_home_status"); ui.onNodeWithTag("tmdb_home_status").assertTextEquals("尚未读取候选"); assertEquals(4, t.calls.get())
    }
    @Test fun missingTokenBlocksTransportAndManagementStillHasTheExistingConfigurationEntry() {
        val t = Transport(); val store = show(t, false); val writes = demo.store.writes
        choose(true); read(); finish(); assertEquals(0, t.calls.get())
        scroll("tmdb_home_status"); ui.onNodeWithTag("tmdb_home_status").assertTextContains("请先保存", substring = true)
        scroll("ai_manage_open"); ui.onNodeWithTag("ai_manage_open").performClick()
        ui.waitUntil(5000) { ui.onAllNodes(hasTestTag("ai_manage_tmdb_config") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("ai_manage_tmdb_config").performClick(); assertEquals("", ui.onNodeWithTag("tmdb_token_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        ui.onNodeWithTag("tmdb_config_close").performClick(); ui.onNodeWithTag("ai_manage_close").performClick()
        unchanged(writes, 0, store)
    }
    @Test fun emptyAndSafeFailuresNeverAutoSubmitAndManualRetryRecovers() {
        val t = Transport(); val store = show(t); val writes = demo.store.writes; choose(true)
        t.empty = true; read(); finish(); assertEquals(TmdbPreviewStatus.EMPTY, preview.preview.value.status); assertEquals(2, t.calls.get())
        val cases = listOf(TmdbProblemCode.UNAUTHORIZED to "令牌或权限无效", TmdbProblemCode.RATE_LIMITED to "请求受限",
            TmdbProblemCode.OFFLINE to "无法连接", TmdbProblemCode.INVALID_RESPONSE to "候选响应无效")
        for ((problem, message) in cases) {
            val before = t.calls.get(); t.problem = problem; read(); finish(); assertEquals(before + 1, t.calls.get())
            scroll("tmdb_home_status"); ui.onNodeWithTag("tmdb_home_status").assertTextContains(message, substring = true)
            assertTrue(preview.preview.value.rows.isEmpty()); assertFalse(preview.preview.value.message.orEmpty().contains("TEST_ONLY_TMDB_home"))
        }
        t.empty = false; t.problem = null; read(); finish(); assertEquals(TmdbPreviewStatus.SUCCESS, preview.preview.value.status)
        unchanged(writes, 1, store)
    }
    @Test fun switchingSourceCancelsLateResponsesAndDoesNotRestoreThemOnReturn() {
        val t = Transport(); show(t); choose(true); val gate = CompletableDeferred<Unit>(); t.gate = gate; t.ignoreCancellation = true
        read(); ui.waitUntil(5000) { t.calls.get() == 1 }; choose(false)
        assertEquals(TmdbPreviewStatus.IDLE, preview.preview.value.status); gate.complete(Unit); ui.waitForIdle()
        choose(true); assertTrue(preview.preview.value.rows.isEmpty()); assertEquals(TmdbPreviewStatus.IDLE, preview.preview.value.status)
        assertEquals(1, t.calls.get()); t.gate = null; read(); finish(); assertEquals(5, t.calls.get())
    }
    @Test fun leavingHomeClearsResultsAndReturnsToMockWithoutNetworkOrSavedState() {
        val t = Transport(); val store = show(t); val writes = demo.store.writes; choose(true); read(); finish()
        ui.runOnIdle { opened.value = false }; ui.waitUntil(5000) { preview.preview.value.status == TmdbPreviewStatus.IDLE }
        ui.onNodeWithTag("home_reopen").performClick(); scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
        assertEquals(4, t.calls.get()); choose(true); assertTrue(preview.preview.value.rows.isEmpty()); unchanged(writes, 1, store)
    }
    @Test fun credentialReplacementAndDeletionInvalidateResultsAndBlockFurtherReads() {
        val t = Transport(); show(t); choose(true); read(); finish()
        runBlocking { credentials.save("TEST_ONLY_TMDB_home_replacement") }
        ui.waitUntil(5000) { preview.preview.value.status == TmdbPreviewStatus.IDLE }; assertTrue(preview.preview.value.rows.isEmpty())
        val gate = CompletableDeferred<Unit>(); t.gate = gate; t.ignoreCancellation = true; read(); ui.waitUntil(5000) { t.calls.get() == 5 }
        runBlocking { credentials.delete() }; ui.waitUntil(5000) { preview.preview.value.status == TmdbPreviewStatus.IDLE }; gate.complete(Unit); ui.waitForIdle()
        assertTrue(preview.preview.value.rows.isEmpty()); assertEquals(TmdbCredentialStatus.NOT_CONFIGURED, preview.state.value.status)
        read(); finish(); assertEquals(5, t.calls.get()); assertTrue(preview.preview.value.rows.isEmpty())
    }
    @Test fun cardsAndAttributionRemainUsableInBothThemesListAndPosterWall() {
        val t = Transport(); show(t, movies = listOf(Movie(1, "原有本地作品", type = "电影", status = "想看", createTime = 1)))
        scroll("ai_source_tmdb"); capture("mock-before-switch")
        choose(true); read(); finish()
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            ui.runOnIdle { theme.value = mode }
            for (poster in listOf(false, true)) {
                if (poster) ui.onNodeWithContentDescription("切换到海报墙").performClick()
                scroll("ai_source_tmdb"); capture(if (poster) "poster-controls" else "list-controls")
                scroll("tmdb_home_movie:7"); ui.onNodeWithTag("tmdb_home_movie:7").assertIsDisplayed()
                capture(if (poster) "poster-results" else "list-results")
                scroll("tmdb_home_credits_logo"); ui.onNodeWithTag("tmdb_home_credits_logo").assertIsDisplayed()
                scroll("tmdb_home_credits_notice"); ui.onNodeWithTag("tmdb_home_credits_notice").assertTextEquals("This product uses the TMDB API but is not endorsed or certified by TMDB.")
                scroll("tmdb_home_credits_link"); ui.onNodeWithTag("tmdb_home_credits_link").performClick(); assertEquals("https://www.themoviedb.org", visited.last())
                if (poster) ui.onNodeWithContentDescription("切换到列表").performClick()
            }
        }
        browserUnavailable = true; scroll("tmdb_home_credits_link"); ui.onNodeWithTag("tmdb_home_credits_link").performClick()
        scroll("tmdb_home_credits_error"); ui.onNodeWithTag("tmdb_home_credits_error").assertIsDisplayed(); assertEquals(4, t.calls.get())
        ui.onNodeWithTag("movie_collection").performScrollToIndex(0); ui.onNodeWithText("原有本地作品").performClick(); assertEquals(listOf(1L), clicked)
    }
    @Test fun failedDemoSettingsDisableManagementWithFeedbackAndRecoveryRestoresTheEntry() {
        val t = Transport(); show(t, demoReadFails = true)
        assertTrue(home.state.value.readFailed); choose(true)
        scroll("ai_manage_open"); ui.onNodeWithTag("ai_manage_open").assertIsNotEnabled()
        scroll("tmdb_home_management_unavailable"); ui.onNodeWithTag("tmdb_home_management_unavailable").assertIsDisplayed()
        assertFalse(home.state.value.managementOpen); assertEquals(0, t.calls.get())
        choose(false); demo.failReads = false
        scroll("ai_home_card"); ui.onNodeWithText("重试读取").performClick(); ui.waitUntil(5000) { !home.state.value.initializing && !home.state.value.readFailed }
        choose(true); scroll("ai_manage_open"); ui.onNodeWithTag("ai_manage_open").assertIsEnabled().performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithTag("ai_manage_close").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("ai_manage_close").performClick(); assertFalse(home.state.value.managementOpen); assertEquals(0, t.calls.get())
    }
    @Test fun originalMockRecommendationFeedbackAndAddFlowRemainSeparateAndBusySwitchIsBlocked() {
        val t = Transport(); show(t); scroll("ai_enter"); ui.onNodeWithTag("ai_enter").performClick()
        val gate = CompletableDeferred<Unit>(); demo.gate = gate; scroll("ai_mode_FOR_YOU"); ui.onNodeWithTag("ai_mode_FOR_YOU").performClick()
        ui.waitUntil(5000) { home.state.value.result == AiResultState.LOADING }; scroll("ai_source_tmdb"); ui.onNodeWithTag("ai_source_tmdb").assertIsNotEnabled()
        gate.complete(Unit); ui.waitUntil(5000) { home.state.value.result == AiResultState.SUCCESS }; assertEquals(1, demo.requests)
        val item = home.state.value.recommendations.first(); scroll("ai_feedback_" + item.id + "_LIKE"); ui.onNodeWithTag("ai_feedback_" + item.id + "_LIKE").performClick()
        ui.waitUntil(5000) { home.state.value.setup.feedback[item.id] == AiFeedback.LIKE && home.state.value.feedbackSaving == null }
        scroll("ai_add_" + item.id); ui.onNodeWithTag("ai_add_" + item.id).performClick(); assertEquals(item, home.state.value.pendingAdd)
        ui.onNodeWithText("取消").performClick(); assertNull(home.state.value.pendingAdd)
        val history = runBlocking { demo.readManagement() }.history
        choose(true); read(); finish(); choose(false)
        assertEquals(history, runBlocking { demo.readManagement() }.history); assertEquals(1, demo.requests)
        assertEquals(AiFeedback.LIKE, home.state.value.setup.feedback[item.id]); scroll("ai_result_" + item.id); ui.onNodeWithTag("ai_result_" + item.id).assertIsDisplayed()
    }
}
