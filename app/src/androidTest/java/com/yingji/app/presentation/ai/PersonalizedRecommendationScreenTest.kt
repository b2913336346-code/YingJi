package com.yingji.app.presentation.ai

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.*
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic transport and TEST_ONLY key; no sockets or original user database. */
class PersonalizedRecommendationScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val f = TmdbRankingUiFixture()
    private val local = LocalProfileTestFixture()
    private val models = ViewModelStore()
    private val visible = mutableStateOf(true)
    private val theme = mutableStateOf(ThemeMode.DARK)
    private val calls = AtomicInteger()
    private var gate: CompletableDeferred<Unit>? = null
    private lateinit var keys: AiKeyConfigurationRepository
    private lateinit var home: AiHomeViewModel
    private lateinit var preview: TmdbCredentialViewModel
    @Before fun open() = runBlocking<Unit> {
        f.prepare(); local.seed()
        local.profiles.setExplicitPreference(ProfileFeature.topic("科幻"), PreferenceDirection.LIKE)
        local.profiles.setExplicitPreference(ProfileFeature.topic("喜剧"), PreferenceDirection.DISLIKE)
        keys = AiKeyConfigurationRepository(DirectTestKeyStore("TEST_ONLY_personal_ui", AiKeyVerificationKind.MOCK), DisabledAiKeyVerifier())
        keys.refresh()
        val real = RoomPersonalizedRecommendation(local.profiles, keys, { f.grant }, true,
            serviceFactory = { current -> object : AiWorkerService {
                override suspend fun quota() = AiQuotaSnapshot.mock()
                override suspend fun hints(request: WorkerHintRequest): WorkerTaskResponse {
                    calls.incrementAndGet(); gate?.await()
                    return DirectMockService(keys, current).hints(request)
                }
            } }, clock = { 1000 }, localProfiles = local.manager)
        ui.runOnUiThread {
            home = AiHomeViewModel(f.demo, keys, local.manager, f.keys, f.reader, f.ranking, real)
            models.put("home", home)
        }
        ui.setContent { YingJiTheme(theme.value) {
            if (visible.value) HomeScreen(emptyList(), emptyMap(), {}, {}, {}, aiViewModel = home, onAddTmdbMovie = {})
            else Text("页面已离开")
        } }
        ui.waitUntil(10000) { !home.state.value.initializing }
        ui.runOnUiThread { preview = ViewModelProvider(ui.activity, TmdbCredentialViewModel.factory(f.keys)).get("tmdb-home-preview", TmdbCredentialViewModel::class.java) }
        ui.waitUntil(10000) { preview.state.value.status != TmdbCredentialStatus.LOADING }
    }
    @After fun close() {
        gate?.complete(Unit)
        ui.runOnIdle { visible.value = false }
        val jobs = listOf(home, preview).mapNotNull { it.viewModelScope.coroutineContext[Job] }
        ui.runOnUiThread { models.clear(); ui.activity.viewModelStore.clear() }
        runBlocking { jobs.joinAll() }; ui.waitForIdle(); local.close()
    }
    private fun click(tag: String) {
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag))
        ui.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun readCandidates() {
        click("ai_source_tmdb"); click("tmdb_home_read")
        ui.waitUntil(10000) { preview.preview.value.status == TmdbPreviewStatus.SUCCESS }
    }
    private fun consent() {
        click("tmdb_personal_recommend")
        ui.waitUntil(10000) { preview.personalizedConsent.value != null }
        ui.onNodeWithText("确认本次真实画像推荐？").assertIsDisplayed()
    }
    private fun ranked() { ui.waitUntil(10000) { preview.ranking.value.status == TmdbPreviewStatus.SUCCESS } }
    private fun capture(name: String) {
        ui.waitForIdle()
        val bitmap = ui.onNode(isDialog()).captureToImage().asAndroidBitmap()
        assertNotNull(bitmap)
        val file = File(f.context.filesDir, "personalized-screenshots/" + name + ".png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    @Test fun consentCancelAndConfirmUseRealRoomProfileWithoutExtraReadsOrWrites() = runBlocking<Unit> {
        val before = local.tables(true); val demoBefore = f.demo.readManagement()
        readCandidates(); val reads = f.calls.get()
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            ui.runOnIdle { theme.value = mode }; consent()
            ui.onNodeWithTag("tmdb_personal_consent_scroll").performScrollToNode(hasTestTag("tmdb_personal_explicit"))
            ui.onNodeWithTag("tmdb_personal_explicit").assertTextEquals("明确偏好：同名 · 排除；喜剧 · 排除；科幻 · 喜欢")
            capture("consent-" + mode.name.lowercase())
            ui.onNodeWithTag("tmdb_personal_consent_cancel").performClick()
            assertEquals(0, calls.get()); assertNull(preview.personalizedConsent.value)
        }
        consent(); ui.onNodeWithTag("tmdb_personal_consent_confirm").performClick(); ranked()
        assertEquals(1, calls.get()); assertEquals(reads, f.calls.get())
        assertTrue(preview.visibleRows.any { "科幻" in it.topics }); assertTrue(preview.visibleRows.none { "喜剧" in it.topics })
        assertEquals(before, local.tables(true)); assertEquals(demoBefore, f.demo.readManagement())
        click("tmdb_add_" + preview.visibleRows.first().identity.externalId)
        ui.onNodeWithTag("tmdb_add_cancel").performClick()
        assertEquals(before, local.tables(true)); assertEquals(reads, f.calls.get())
    }
    @Test fun profileChangesInvalidateConsentAndLeavingCancelsPendingRecommendation() = runBlocking<Unit> {
        readCandidates(); consent()
        local.profiles.setSources(ProfileSourceSettings(false, false, false))
        ui.waitUntil(10000) { preview.personalizedConsent.value == null }
        assertEquals(0, calls.get()); assertTrue(preview.visibleRows.isEmpty())
        gate = CompletableDeferred(); consent(); ui.onNodeWithTag("tmdb_personal_consent_confirm").performClick()
        ui.waitUntil(10000) { calls.get() == 1 }
        ui.runOnIdle { visible.value = false }; gate!!.complete(Unit)
        ui.waitUntil(10000) { preview.ranking.value.status == TmdbPreviewStatus.IDLE && preview.preview.value.rows.isEmpty() }
        ui.onNodeWithText("页面已离开").assertIsDisplayed(); assertTrue(preview.visibleRows.isEmpty())
    }
    @Test fun deletingModelKeyDiscardsResultsAndReportsConfigurationProblem() = runBlocking<Unit> {
        readCandidates(); consent(); ui.onNodeWithTag("tmdb_personal_consent_confirm").performClick(); ranked()
        keys.delete(); ui.waitUntil(10000) { preview.ranking.value.status == TmdbPreviewStatus.ERROR }
        assertTrue(preview.visibleRows.isEmpty()); click("tmdb_personal_recommend")
        ui.waitUntil(10000) { preview.ranking.value.message?.contains("模型 Key") == true }
        assertNull(preview.personalizedConsent.value); assertEquals(1, calls.get())
    }
}
