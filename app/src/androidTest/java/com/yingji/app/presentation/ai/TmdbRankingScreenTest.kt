package com.yingji.app.presentation.ai

import android.content.Context
import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.*
import com.yingji.app.data.database.*
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.repository.*
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.domain.usecase.*
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.presentation.movie.*
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** App-authored protocol fixtures only; never reads a real key/profile or opens a socket. */
internal class TmdbRankingUiFixture {
    private class Memory : TmdbSecureCredentialStore {
        private var value: String? = null; private var revision = 0
        override suspend fun read() = TmdbCredentialRecord(value?.let(TmdbSecret::fromInput), 1000, revision.toString())
        override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String { value = key?.useValue { it }; return (++revision).toString() }
    }
    val context = ApplicationProvider.getApplicationContext<Context>()
    val keys = TmdbCredentialRepository(Memory()) { 1000 }
    val store = AiTestStore()
    val demo = MockAiHomeRepository(context, store, clock = { 1000 })
    val calls = AtomicInteger()
    val profileReads = AtomicInteger()
    var empty = false
    var profileFailure = false
    var hintFailure = false
    var profileGate: CompletableDeferred<Unit>? = null
    var ignoreCancellation = false
    var problem: TmdbProblemCode? = null
    var importGate: CompletableDeferred<Unit>? = null
    val grant = TmdbSourcePermission(CandidateLicenseState.ACTIVE, "App-authored synthetic ranking UI permission", true, true, true)
    val transport = object : TmdbCatalogTransport {
        override val isMock = false // Synthetic verified-source protocol responses; never a network transport.
        override fun revision() = keys.state.value.revision.orEmpty()
        override fun isCurrent(revision: String) = revision == this.revision() && !keys.state.value.saving
        override suspend fun read(request: TmdbCatalogRequest): String {
            calls.incrementAndGet(); importGate?.await(); problem?.let { throw TmdbProblem(it) }
            if (request.id == null) return if (empty) """{"page":1,"results":[]}""" else """{"page":1,"results":[{"id":7},{"id":8},{"id":9}]}"""
            val id = request.id!!
            val genre = if (request.kind == TmdbMediaKind.MOVIE) when (id) { 7 -> 35; 8 -> 878; else -> 99 }
                else when (id) { 7 -> 99; 8 -> 35; else -> 99 }
            val name = if (request.kind == TmdbMediaKind.MOVIE) "title" else "name"
            val date = if (request.kind == TmdbMediaKind.MOVIE) "release_date" else "first_air_date"
            return """{"id":$id,"$name":"虚构排序候选$genre-$id","$date":"2024-01-01","runtime":90,"genres":[{"id":$genre,"name":"Synthetic genre"}]}"""
        }
    }
    val reader = TmdbCandidatePreview(TmdbCandidateCatalogRepository(transport, { grant }, { 1000 }), { 1000 })
    val ranking = TmdbMockRecommendationUseCase(demoProfile = {
        profileReads.incrementAndGet()
        profileGate?.let { if (ignoreCancellation) withContext(NonCancellable) { it.await() } else it.await() }
        if (profileFailure) throw java.io.IOException("TEST_ONLY private fixture failure")
        demo.readIntegrationProfile()
    }, localHints = { request ->
        if (hintFailure) throw WorkerProblem("OFFLINE")
        WorkerHints(request.profileSummary.explicit.filter { it.direction == PreferenceDirection.LIKE }.map { it.topic }, CandidateHardConditions())
    }, clock = { 1000 })
    fun home() = AiHomeViewModel(demo, tmdbCredentials = keys, tmdbPreview = reader, tmdbRanking = ranking)
    suspend fun prepare() {
        keys.save("TEST_ONLY_TMDB_rank_ui")
        demo.saveSetup(AiDemoSetup(preferences = mapOf("喜剧" to PreferenceDirection.LIKE, "科幻" to PreferenceDirection.DISLIKE)))
        demo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS)
    }
}

class TmdbRankingScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val f = TmdbRankingUiFixture()
    private val models = ViewModelStore()
    private val opened = mutableStateOf(true)
    private val theme = mutableStateOf(ThemeMode.DARK)
    private val draft = mutableStateOf<CatalogCandidate?>(null)
    private lateinit var home: AiHomeViewModel
    private lateinit var preview: TmdbCredentialViewModel
    private lateinit var db: AppDatabase
    private lateinit var movies: MovieRepository
    private lateinit var movie: MovieViewModel
    private var oldOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var lastData: MovieFormData? = null
    private val landscape get() = InstrumentationRegistry.getArguments().getString("tmdbOrientation") == "landscape"
    @Before fun open() = runBlocking<Unit> {
        DatabaseHealthMonitor.resetForTesting()
        db = Room.inMemoryDatabaseBuilder(f.context, AppDatabase::class.java).build(); movies = MovieRepository(db)
        db.categoryDao().insert(Category(7, "既有分类", false)); db.tagDao().insert(Tag(7, "独立标签")); f.prepare()
        val importer = TmdbImportUseCase(f.transport, f.keys, { f.grant }, CandidateImportRepository(db, movies), AddMovieUseCase(movies)) { 1000 }
        ui.runOnUiThread {
            oldOrientation = ui.activity.requestedOrientation; ui.activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            home = f.home(); models.put("home", home)
            movie = MovieViewModel(movies, AddMovieUseCase(movies), UpdateStatusUseCase(movies), PosterRepository(f.context), importTmdbWork = importer::invoke); models.put("movie", movie)
        }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
        ui.setContent { YingJiTheme(theme.value) {
            val action by movie.formActionState.collectAsStateWithLifecycle(); val row = draft.value
            if (!opened.value) TextButton(onClick = { opened.value = true }, modifier = Modifier.testTag("rank_reopen")) { Text("返回首页") }
            else if (row == null) HomeScreen(emptyList(), emptyMap(), {}, {}, {}, aiViewModel = home, onAddTmdbMovie = { draft.value = it })
            else MovieFormScreen("新增作品", categories = listOf(Category(7, "既有分类", false)), tags = listOf(Tag(7, "独立标签")), initialName = row.title.orEmpty(),
                isSaving = action.isSaving, saveMessage = action.message, onSaveMessageConsumed = movie::consumeFormMessage,
                onBack = { draft.value = null }, onSave = { data -> lastData = data; movie.addMovie(data, row.identity) { draft.value = null } })
        } }
        ui.waitUntil(5000) { !home.state.value.initializing }
        ui.runOnUiThread { preview = ViewModelProvider(ui.activity, TmdbCredentialViewModel.factory(f.keys)).get("tmdb-home-preview", TmdbCredentialViewModel::class.java) }
        ui.waitUntil(5000) { preview.state.value.status != TmdbCredentialStatus.LOADING }
    }
    @After fun close() { ui.runOnUiThread { models.clear(); ui.activity.viewModelStore.clear(); ui.activity.requestedOrientation = oldOrientation }; db.close(); DatabaseHealthMonitor.resetForTesting() }
    private fun scroll(tag: String) { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)) }
    private fun choose(tmdb: Boolean) { val tag = if (tmdb) "ai_source_tmdb" else "ai_source_mock"; scroll(tag); ui.onNodeWithTag(tag).performClick() }
    private fun read() { scroll("tmdb_home_read"); ui.onNodeWithTag("tmdb_home_read").performClick(); ui.waitUntil(5000) { preview.preview.value.status !in setOf(TmdbPreviewStatus.IDLE, TmdbPreviewStatus.LOADING) } }
    private fun rank() { scroll("tmdb_home_rank"); ui.onNodeWithTag("tmdb_home_rank").performClick() }
    private fun ranked() { ui.waitUntil(5000) { preview.ranking.value.status !in setOf(TmdbPreviewStatus.IDLE, TmdbPreviewStatus.LOADING) } }
    private fun count() = runBlocking { movies.observeAll().first().size }
    private fun capture(name: String) {
        ui.waitForIdle(); val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(); assertNotNull(bitmap)
        val file = File(f.context.filesDir, "tmdb-ranking-screenshots/" + name + ".png"); file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    @Test fun sortingIsExplicitBoundedUsesDemoOnlyAndNeverReadsAgainOrWritesHistory() {
        val before = runBlocking { f.demo.readManagement() }; val writes = f.store.writes
        choose(true); scroll("tmdb_home_rank"); ui.onNodeWithTag("tmdb_home_rank").assertIsNotEnabled(); assertEquals(0, f.calls.get()); assertEquals(0, f.profileReads.get())
        read(); assertEquals(8, f.calls.get()); assertEquals(6, preview.preview.value.rows.size); assertEquals(0, f.profileReads.get())
        rank(); ranked(); assertEquals(TmdbPreviewStatus.SUCCESS, preview.ranking.value.status)
        val rows = preview.ranking.value.rows; assertEquals(3, rows.size); assertEquals(rows.size, rows.map { it.identity }.distinct().size)
        assertEquals("movie:7", rows.first().identity.externalId); assertTrue(rows.none { "科幻" in it.topics }); assertEquals(6, preview.preview.value.rows.size)
        scroll("tmdb_home_rank_status"); ui.onNodeWithTag("tmdb_home_rank_status").assertTextEquals("本机 Mock 排序：3 项 · 非真实画像推荐")
        scroll("tmdb_home_" + rows.first().identity.externalId); ui.onNodeWithTag("tmdb_home_" + rows.first().identity.externalId).assertIsDisplayed()
        scroll("tmdb_home_credits_notice"); ui.onNodeWithTag("tmdb_home_credits_notice").assertExists()
        assertEquals(8, f.calls.get()); assertEquals(writes, f.store.writes); assertEquals(before, runBlocking { f.demo.readManagement() }); assertEquals(0, count())
        rank(); ranked(); assertEquals(8, f.calls.get()); assertEquals(before, runBlocking { f.demo.readManagement() })
        choose(false); assertTrue(home.state.value.recommendations.isEmpty()); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
    }
    @Test fun emptyOrFullyExcludedCandidatesNeverGetPaddedOrImported() = runBlocking<Unit> {
        f.demo.saveSetup(AiDemoSetup(preferences = AiDemoRules.topics.associateWith { PreferenceDirection.DISLIKE }))
        choose(true); read(); rank(); ranked(); assertEquals(TmdbPreviewStatus.EMPTY, preview.ranking.value.status); assertTrue(preview.visibleRows.isEmpty())
        ui.runOnUiThread { preview.requestImport(preview.preview.value.rows.first()) }; assertNull(preview.pendingImport.value)
        ui.onAllNodes(hasText("加入影迹", substring = false)).assertCountEquals(0)
        f.empty = true; read(); assertEquals(TmdbPreviewStatus.EMPTY, preview.preview.value.status); assertEquals(TmdbPreviewStatus.IDLE, preview.ranking.value.status)
        scroll("tmdb_home_rank"); ui.onNodeWithTag("tmdb_home_rank").assertIsNotEnabled(); assertEquals(0, count())
    }
    @Test fun rereadSourceSwitchAndLeavingHomeDiscardRankingWithoutAutomaticRequests() {
        choose(true); read(); rank(); ranked(); read(); assertEquals(TmdbPreviewStatus.IDLE, preview.ranking.value.status); assertEquals(16, f.calls.get())
        rank(); ranked(); choose(false); assertTrue(preview.ranking.value.rows.isEmpty()); choose(true); assertEquals(TmdbPreviewStatus.IDLE, preview.preview.value.status); assertEquals(16, f.calls.get())
        read(); rank(); ranked(); ui.runOnIdle { opened.value = false }; ui.waitUntil(5000) { preview.ranking.value.status == TmdbPreviewStatus.IDLE }
        ui.onNodeWithTag("rank_reopen").performClick(); scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected(); assertEquals(24, f.calls.get())
        choose(true); assertTrue(preview.visibleRows.isEmpty()); assertEquals(TmdbPreviewStatus.IDLE, preview.ranking.value.status)
    }
    @Test fun rapidClickAndPreferenceOrSourceChangesCancelAndDiscardLateRanking() = runBlocking<Unit> {
        choose(true); read(); val gate = CompletableDeferred<Unit>(); f.profileGate = gate; f.ignoreCancellation = true
        rank(); ui.waitUntil(5000) { f.profileReads.get() == 1 }; scroll("tmdb_home_rank"); ui.onNodeWithTag("tmdb_home_rank").assertIsNotEnabled()
        ui.runOnUiThread { preview.rankCandidates(); preview.rankCandidates(); preview.requestImport(preview.preview.value.rows.first()) }
        assertEquals(1, f.profileReads.get()); assertNull(preview.pendingImport.value)
        f.demo.setManagementPreference("纪录片", PreferenceDirection.LIKE)
        ui.waitUntil(5000) { preview.ranking.value.status == TmdbPreviewStatus.ERROR }; gate.complete(Unit); ui.waitForIdle()
        assertTrue(preview.ranking.value.rows.isEmpty()); assertEquals(8, f.calls.get()); f.profileGate = null
        rank(); ranked(); assertEquals(TmdbPreviewStatus.SUCCESS, preview.ranking.value.status)
        f.demo.setManagementSources(ProfileSourceSettings(false, false, false))
        ui.waitUntil(5000) { preview.ranking.value.status == TmdbPreviewStatus.ERROR }; assertTrue(preview.ranking.value.rows.isEmpty()); assertEquals(0, count())
    }
    @Test fun replacingDeletingCredentialsNeverRestoreLateResultsOrConfirmStaleImport() = runBlocking<Unit> {
        choose(true); read(); val gate = CompletableDeferred<Unit>(); f.profileGate = gate; f.ignoreCancellation = true
        rank(); ui.waitUntil(5000) { f.profileReads.get() == 1 }; f.keys.save("TEST_ONLY_TMDB_rank_replaced")
        ui.waitUntil(5000) { preview.preview.value.status == TmdbPreviewStatus.IDLE }; gate.complete(Unit); ui.waitForIdle()
        assertTrue(preview.ranking.value.rows.isEmpty()); assertNull(preview.pendingImport.value); assertEquals(8, f.calls.get())
        f.profileGate = null; read(); rank(); ranked(); val selected = preview.visibleRows.first()
        ui.runOnUiThread { preview.requestImport(selected) }; assertNotNull(preview.pendingImport.value)
        f.keys.delete(); ui.waitUntil(5000) { preview.preview.value.status == TmdbPreviewStatus.IDLE }; assertNull(preview.pendingImport.value)
        scroll("tmdb_home_rank"); ui.onNodeWithTag("tmdb_home_rank").assertIsNotEnabled(); assertTrue(preview.ranking.value.rows.isEmpty()); assertEquals(16, f.calls.get())
    }
    @Test fun safeRankingFailureAndLocalFallbackAllowManualRetryWithoutHistory() {
        choose(true); read(); val before = runBlocking { f.demo.readManagement() }; val writes = f.store.writes
        f.profileFailure = true; rank(); ranked(); assertEquals(TmdbPreviewStatus.ERROR, preview.ranking.value.status)
        assertFalse(preview.ranking.value.message.orEmpty().contains("TEST_ONLY")); assertEquals(6, preview.visibleRows.size)
        f.profileFailure = false; f.hintFailure = true; rank(); ranked(); assertEquals(TmdbPreviewStatus.SUCCESS, preview.ranking.value.status)
        scroll("tmdb_home_rank_message"); ui.onNodeWithTag("tmdb_home_rank_message").assertTextContains("本地规则", substring = true)
        f.hintFailure = false; rank(); ranked(); assertNull(preview.ranking.value.message); assertEquals(8, f.calls.get())
        assertEquals(before, runBlocking { f.demo.readManagement() }); assertEquals(writes, f.store.writes); assertEquals(0, count())
    }
    @Test fun rankedCandidateReusesConfirmationSharedFormAndAtomicImportOnlyAfterFinalSave() {
        choose(true); read(); rank(); ranked(); val selected = preview.ranking.value.rows.first(); val before = runBlocking { f.demo.readManagement() }
        scroll("tmdb_add_" + selected.identity.externalId); ui.onNodeWithTag("tmdb_add_" + selected.identity.externalId).performClick()
        ui.onNodeWithTag("tmdb_add_cancel").performClick(); assertEquals(0, count()); assertEquals(8, f.calls.get())
        scroll("tmdb_add_" + selected.identity.externalId); ui.onNodeWithTag("tmdb_add_" + selected.identity.externalId).performClick()
        ui.onNodeWithTag("tmdb_add_confirm").performClick(); ui.waitUntil(5000) { draft.value != null }; assertEquals(selected, draft.value)
        ui.onNodeWithText("保存").performScrollTo().performClick(); ui.onNodeWithText("请选择作品分类").performScrollTo().assertIsDisplayed(); assertEquals(0, count())
        ui.onNodeWithText("既有分类").performScrollTo().performClick(); ui.onNodeWithText("独立标签").performScrollTo().performClick()
        f.problem = TmdbProblemCode.OFFLINE; ui.onNodeWithText("保存").performScrollTo().performClick()
        ui.waitUntil(5000) { ui.onAllNodes(hasText("无法完成 TMDB 详情校验", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, count()); assertNotNull(draft.value)
        f.problem = null; val gate = CompletableDeferred<Unit>(); f.importGate = gate
        ui.onNodeWithText("保存").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.waitUntil(5000) { movie.formActionState.value.isSaving }; ui.runOnUiThread { movie.addMovie(lastData!!, selected.identity) {} }; gate.complete(Unit)
        ui.waitUntil(5000) { draft.value == null }; assertEquals(1, count()); assertEquals(10, f.calls.get())
        val stored = runBlocking { movies.observeAll().first().single() }; assertEquals(selected.title, stored.name); assertEquals("想看", stored.status)
        assertNull(stored.rating); assertNull(stored.posterPath); assertNull(stored.totalEpisode)
        runBlocking { assertEquals(stored.id, db.candidateImportDao().movieId("tmdb", selected.identity.externalId)); assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(stored.id)); assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(stored.id)) }
        assertEquals(before, runBlocking { f.demo.readManagement() }); assertEquals(TmdbPreviewStatus.IDLE, preview.ranking.value.status)
    }
    // Each orientation is launched before setContent; Activity recreation is covered by the production entry test.
    @Test fun sortingControlsResultsAndCreditsRemainScrollableInBothThemes() {
        choose(true); read(); scroll("tmdb_home_rank"); capture((if (landscape) "landscape" else "portrait") + "-dark-before-rank"); rank(); ranked()
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        assertEquals(expected, ui.activity.resources.configuration.orientation)
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            ui.runOnIdle { theme.value = mode }; scroll("tmdb_home_rank"); ui.onNodeWithTag("tmdb_home_rank").assertIsDisplayed().assertIsEnabled()
            capture(expected.toString() + "-" + mode.name.lowercase() + "-controls")
            val first = preview.ranking.value.rows.first(); scroll("tmdb_add_" + first.identity.externalId); ui.onNodeWithTag("tmdb_add_" + first.identity.externalId).assertIsDisplayed()
            capture(expected.toString() + "-" + mode.name.lowercase() + "-results")
            scroll("tmdb_home_credits_notice"); ui.onNodeWithTag("tmdb_home_credits_notice").assertTextEquals("This product uses the TMDB API but is not endorsed or certified by TMDB.")
        }
        assertEquals(8, f.calls.get()); assertEquals(0, count())
    }
}
