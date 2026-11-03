package com.yingji.app.presentation.ai

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.ai.*
import com.yingji.app.data.database.*
import com.yingji.app.data.entity.*
import com.yingji.app.data.repository.*
import com.yingji.app.domain.ai.*
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual Room/profile/client/VM/UI chain with app-authored facts and injected HTTP; never opens a socket. */
class I1PersonalizedConsentScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val models = ViewModelStore()
    private val contentVisible = mutableStateOf(true)
    private lateinit var db: AppDatabase
    private lateinit var movies: MovieRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var keys: AiKeyConfigurationRepository
    private lateinit var home: AiHomeViewModel
    private lateinit var preview: TmdbCredentialViewModel
    private val modelCalls = AtomicInteger()
    private val catalogCalls = AtomicInteger()
    private var status = 200
    private var held = false
    @Volatile private var disconnected = false
    private val modelStarted = CountDownLatch(1)
    private val modelRelease = CountDownLatch(1)
    private val body = ByteArrayOutputStream()
    private val fakeSecret = "I1_INJECTED_FAKE_SECRET_NO_NETWORK"
    private val grant = TmdbSourcePermission(CandidateLicenseState.ACTIVE, "I1 app-authored synthetic permission", true, true, false)
    private class Memory : TmdbSecureCredentialStore {
        private var key: String? = null
        override suspend fun read() = TmdbCredentialRecord(key?.let(TmdbSecret::fromInput), 100, "fixture")
        override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String { this.key = key?.useValue { it }; return "fixture" }
    }
    private val tmdb = TmdbCredentialRepository(Memory()) { 100 }
    private fun response(): String {
        val content = JSONObject().put("preferredTopics", org.json.JSONArray(listOf("喜剧", "科幻")))
            .put("conditions", JSONObject().put("requiredTopics", org.json.JSONArray()).put("excludedTopics", org.json.JSONArray())
                .put("minYear", JSONObject.NULL).put("maxYear", JSONObject.NULL).put("maxRuntimeMinutes", JSONObject.NULL))
        return JSONObject().put("id", "i1-fixture").put("object", "chat.completion").put("model", "deepseek-flash")
            .put("choices", org.json.JSONArray().put(JSONObject().put("index", 0).put("finish_reason", "stop")
                .put("message", JSONObject().put("role", "assistant").put("content", content.toString()))))
            .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1).put("total_tokens", 2)).toString()
    }
    @Before fun open() = runBlocking<Unit> {
        DatabaseHealthMonitor.resetForTesting(); db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(); movies = MovieRepository(db)
        db.categoryDao().insert(Category(7, "I1 私有分类", false)); db.tagDao().insert(Tag(8, "科幻")); db.tagDao().insert(Tag(9, "喜剧"))
        movies.insertWithRelations(Movie(41, "RAW_PRIVATE_I1_TITLE", "/private/i1-poster.png", "I1 私有分类", "已看完", createTime = 123, rating = 8.5), 7, setOf(8))
        profiles = ProfileRepository(db, ObserveLibraryRepository(movies)) { 100 }
        profiles.setSources(ProfileSourceSettings(true, true, false)); profiles.setExplicitPreference(ProfileFeature.tag(9, "喜剧"), PreferenceDirection.DISLIKE)
        val manager = RoomLocalProfileManagementRepository(profiles, CategoryRepository(db.categoryDao(), db.tagDao()))
        keys = AiKeyConfigurationRepository(DirectTestKeyStore(fakeSecret, AiKeyVerificationKind.LIVE), DisabledAiKeyVerifier()); keys.refresh()
        val usage = LocalAiUsageRepository(context, AiTestStore())
        val client = DeepSeekHttpClient(usage) { url ->
            assertEquals("https://api.deepseek.com/chat/completions", url.toString()); modelCalls.incrementAndGet()
            object : HttpURLConnection(url) {
                override fun connect() {}; override fun usingProxy() = false
                override fun disconnect() { disconnected = true; modelRelease.countDown() }
                override fun getOutputStream() = body
                override fun getResponseCode(): Int { modelStarted.countDown(); if (held) check(modelRelease.await(10, TimeUnit.SECONDS)); return status }
                override fun getContentType() = "application/json"
                override fun getInputStream() = ByteArrayInputStream(response().toByteArray())
            }
        }
        val ranker = RoomPersonalizedRecommendation(profiles, keys, { grant }, false,
            { current -> DirectAiService(keys, usage, client, current) }, client::cancelPending, { 100 }, manager)
        val transport = object : TmdbCatalogTransport {
            override val isMock = false // Synthetic verified-source protocol fixtures only; no network client.
            override fun revision() = "fixture"
            override fun isCurrent(revision: String) = revision == "fixture"
            override suspend fun read(request: TmdbCatalogRequest): String {
                catalogCalls.incrementAndGet()
                if (request.id == null) return """{"page":1,"results":[{"id":7}]}"""
                return if (request.kind == TmdbMediaKind.MOVIE) """{"id":7,"title":"I1 虚构科幻候选","release_date":"2024-01-01","runtime":90,"genres":[{"id":878,"name":"Synthetic science fiction"}]}"""
                    else """{"id":7,"name":"I1 虚构喜剧候选","first_air_date":"2024-01-01","genres":[{"id":35,"name":"Synthetic comedy"}]}"""
            }
        }
        tmdb.save("TEST_ONLY_I1_TMDB")
        val demo = AiTestRepository(); demo.saveSetup(AiDemoSetup())
        ui.runOnUiThread {
            home = AiHomeViewModel(demo, tmdbCredentials = tmdb,
                tmdbPreview = TmdbCandidatePreview(TmdbCandidateCatalogRepository(transport, { grant }, { 100 }), { 100 }), personalizedRecommendation = ranker)
            models.put("home", home)
        }
        ui.setContent { if (contentVisible.value) YingJiTheme { HomeScreen(emptyList(), emptyMap(), {}, {}, {}, aiViewModel = home) } }
        ui.waitUntil(5000) { !home.state.value.initializing }
        ui.runOnUiThread { preview = ViewModelProvider(ui.activity, TmdbCredentialViewModel.factory(tmdb)).get("tmdb-home-preview", TmdbCredentialViewModel::class.java) }
        ui.waitUntil(5000) { preview.state.value.status != TmdbCredentialStatus.LOADING }
    }
    @After fun close() {
        modelRelease.countDown()
        ui.runOnIdle { contentVisible.value = false }
        val jobs = listOf(home, preview).mapNotNull { it.viewModelScope.coroutineContext[Job] }
        ui.runOnUiThread { models.clear(); ui.activity.viewModelStore.clear() }
        runBlocking { jobs.joinAll() }
        db.close(); DatabaseHealthMonitor.resetForTesting()
    }
    private fun scroll(tag: String) {
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag))
        ui.onNodeWithTag(tag).performScrollTo()
    }
    private fun read() {
        scroll("ai_source_tmdb"); ui.onNodeWithTag("ai_source_tmdb").performClick()
        scroll("tmdb_home_read"); ui.onNodeWithTag("tmdb_home_read").performClick()
        ui.waitUntil(5000) { preview.preview.value.status == TmdbPreviewStatus.SUCCESS }; assertEquals(4, catalogCalls.get())
    }
    private fun request() { scroll("tmdb_personal_recommend"); ui.onNodeWithTag("tmdb_personal_recommend").assertIsEnabled().performClick() }
    private fun consent() {
        try { ui.waitUntil(5000) { preview.personalizedConsent.value != null } }
        catch (failure: AssertionError) { throw AssertionError("I1 consent state=" + preview.ranking.value.status + "; message=" + preview.ranking.value.message, failure) }
        ui.onNodeWithTag("tmdb_personal_consent_confirm").assertIsDisplayed()
    }
    private fun finished() { ui.waitUntil(5000) { preview.ranking.value.status !in setOf(TmdbPreviewStatus.IDLE, TmdbPreviewStatus.LOADING) } }
    private fun originalPreserved() = runBlocking {
        val row = movies.observeAll().first().single(); assertEquals(41L, row.id); assertEquals("RAW_PRIVATE_I1_TITLE", row.name)
        assertEquals(8.5, row.rating!!, 0.0); assertEquals("/private/i1-poster.png", row.posterPath)
        assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(41)); assertEquals(listOf(8L), db.tagDao().getTagIdsForMovie(41))
        assertTrue(db.candidateImportDao().movieId("tmdb", "movie:7") == null)
    }
    @Test fun preparingConsentShowsMinimalSummaryAndFeesButCancelNeverCallsModel() {
        read(); request(); consent(); assertEquals(0, modelCalls.get())
        val value = preview.personalizedConsent.value!!; assertTrue(value.summary.explicit.any { it.topic == "喜剧" && it.direction == PreferenceDirection.DISLIKE })
        assertTrue(value.summary.inferred.any { it.topic == "科幻" && it.source == ProfileSource.RATING })
        ui.onNodeWithTag("tmdb_personal_consent_scroll").performScrollToNode(hasText("承担费用", substring = true))
        ui.onNodeWithText("承担费用", substring = true).assertIsDisplayed()
        ui.onNodeWithTag("tmdb_personal_consent_cancel").performClick(); assertNull(preview.personalizedConsent.value)
        assertEquals(0, modelCalls.get()); assertEquals(4, catalogCalls.get()); originalPreserved()
    }
    @Test fun explicitConfirmationUsesRealProfileProjectionWithInjectedHttpAndNoCandidateFactsUpstream() {
        read(); request(); consent(); ui.onNodeWithTag("tmdb_personal_consent_confirm").performClick(); finished()
        assertEquals(TmdbPreviewStatus.SUCCESS, preview.ranking.value.status); assertEquals(1, modelCalls.get())
        assertEquals(listOf("movie:7"), preview.visibleRows.map { it.identity.externalId }); assertEquals(4, catalogCalls.get())
        val encoded = body.toString("UTF-8"); val request = JSONObject(encoded)
        assertEquals("deepseek-flash", request.getString("model")); assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
        assertFalse(request.getBoolean("stream")); assertEquals("json_object", request.getJSONObject("response_format").getString("type"))
        val sent = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(setOf("mode", "userQuery", "explicit", "inferred", "hardConditions"), sent.keys().asSequence().toSet())
        for (raw in listOf("RAW_PRIVATE_I1_TITLE", "/private/i1-poster.png", "8.5", "I1 虚构", "movie:7", "tag:", fakeSecret)) assertFalse(raw, encoded.contains(raw))
        assertTrue(sent.getJSONArray("inferred").length() > 0); assertNull(preview.personalizedConsent.value); originalPreserved()
    }
    @Test fun profileChangeInvalidatesConsentBeforeAnyHttpAndDisabledSourceLeavesNoInference() = runBlocking<Unit> {
        read(); request(); consent(); profiles.setSources(ProfileSourceSettings(false, false, false))
        ui.waitUntil(5000) { preview.personalizedConsent.value == null && preview.ranking.value.status == TmdbPreviewStatus.ERROR }
        ui.onAllNodesWithTag("tmdb_personal_consent_confirm").assertCountEquals(0); assertEquals(0, modelCalls.get())
        request(); consent(); assertTrue(preview.personalizedConsent.value!!.summary.inferred.isEmpty())
        ui.onNodeWithTag("tmdb_personal_consent_cancel").performClick(); originalPreserved()
    }
    @Test fun rapidConfirmationThenDeletingKeyCancelsInFlightAndDiscardsLateResults() = runBlocking<Unit> {
        read(); held = true; request(); consent(); ui.onNodeWithTag("tmdb_personal_consent_confirm").performClick()
        ui.waitUntil(5000) { modelStarted.count == 0L }; ui.runOnUiThread { preview.confirmPersonalizedConsent(); preview.confirmPersonalizedConsent() }
        assertEquals(1, modelCalls.get()); keys.delete()
        ui.waitUntil(5000) { disconnected && preview.ranking.value.status == TmdbPreviewStatus.ERROR }
        assertTrue(preview.ranking.value.rows.isEmpty()); assertNull(preview.personalizedConsent.value); assertEquals(1, modelCalls.get()); originalPreserved()
    }
    @Test fun unconfiguredKeyBlocksModelWithoutNetworkOrLibraryWrites() = runBlocking<Unit> {
        read(); keys.delete(); request(); finished(); assertEquals(TmdbPreviewStatus.ERROR, preview.ranking.value.status); assertEquals(0, modelCalls.get())
        originalPreserved()
    }
    @Test fun injectedBillingErrorIsNotRetriedAndKeepsOnlyVerifiedLocalFallback() {
        read(); status = 402; request(); consent(); ui.onNodeWithTag("tmdb_personal_consent_confirm").performClick(); finished()
        assertEquals(1, modelCalls.get()); assertTrue(preview.ranking.value.message.orEmpty().contains("计费"))
        assertEquals(listOf("movie:7"), preview.visibleRows.map { it.identity.externalId }); assertEquals(4, catalogCalls.get()); originalPreserved()
        scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").performClick(); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
    }
}
