package com.yingji.app.presentation.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
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
import com.yingji.app.domain.usecase.*
import com.yingji.app.presentation.home.HomeScreen
import com.yingji.app.presentation.movie.*
import com.yingji.app.ui.theme.YingJiTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

internal class TmdbImportUiFixture {
    private class Memory : TmdbSecureCredentialStore {
        var key: String? = null; var version = 0
        override suspend fun read() = TmdbCredentialRecord(key?.let(TmdbSecret::fromInput), 100, version.toString())
        override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String { this.key = key?.useValue { it }; return (++version).toString() }
    }
    val credentials = TmdbCredentialRepository(Memory()) { 100 }
    val demo = AiTestRepository()
    var calls = 0
    var problem: TmdbProblemCode? = null
    var gate: CompletableDeferred<Unit>? = null
    val grant = TmdbSourcePermission(CandidateLicenseState.ACTIVE, "App-authored synthetic UI fixture, no real metadata", true, true, true)
    val transport = object : TmdbCatalogTransport {
        override val isMock = false // Synthetic official-protocol responses, never a network client.
        override fun revision() = credentials.state.value.revision.orEmpty()
        override fun isCurrent(revision: String) = revision == this.revision() && !credentials.state.value.saving
        override suspend fun read(request: TmdbCatalogRequest): String {
            calls++; gate?.await(); problem?.let { throw TmdbProblem(it) }
            return if (request.id == null) """{"page":1,"results":[{"id":7}]}"""
                else if (request.kind == TmdbMediaKind.MOVIE) """{"id":7,"title":"虚构表单电影","release_date":"2024-01-01","runtime":90}"""
                else """{"id":7,"name":"虚构表单剧集","first_air_date":"2023-01-01","episode_run_time":[45]}"""
        }
    }
    fun home() = AiHomeViewModel(demo, tmdbCredentials = credentials,
        tmdbPreview = TmdbCandidatePreview(TmdbCandidateCatalogRepository(transport, { grant }, { 100 }), { 100 }))
    suspend fun prepare() { credentials.save("TEST_ONLY_TMDB_ui_import"); demo.saveSetup(AiDemoSetup()) }
}

class TmdbImportScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "tmdb-import-ui-" + UUID.randomUUID() + ".db"
    private val models = ViewModelStore()
    private val fixture = TmdbImportUiFixture()
    private lateinit var db: AppDatabase
    private lateinit var movies: MovieRepository
    private lateinit var home: AiHomeViewModel
    private lateinit var movie: MovieViewModel
    private lateinit var preview: TmdbCredentialViewModel
    private val draft = mutableStateOf<CatalogCandidate?>(null)
    private var lastData: MovieFormData? = null
    @Before fun open() = runBlocking<Unit> {
        DatabaseHealthMonitor.resetForTesting(); db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build(); movies = MovieRepository(db)
        db.categoryDao().insert(Category(7, "既有分类", false)); db.tagDao().insert(Tag(7, "独立标签")); fixture.prepare()
        val service = TmdbImportUseCase(fixture.transport, fixture.credentials, { fixture.grant }, CandidateImportRepository(db, movies), AddMovieUseCase(movies)) { 100 }
        ui.runOnUiThread {
            home = fixture.home(); models.put("home", home)
            movie = MovieViewModel(movies, AddMovieUseCase(movies), UpdateStatusUseCase(movies), PosterRepository(context), importTmdbWork = service::invoke); models.put("movie", movie)
        }
        ui.setContent { YingJiTheme {
            val row = draft.value
            val action by movie.formActionState.collectAsStateWithLifecycle()
            if (row == null) HomeScreen(emptyList(), emptyMap(), {}, {}, {}, aiViewModel = home, onAddTmdbMovie = { draft.value = it })
            else MovieFormScreen("新增作品", categories = listOf(Category(7, "既有分类", false)), tags = listOf(Tag(7, "独立标签")), initialName = row.title.orEmpty(),
                isSaving = action.isSaving, saveMessage = action.message, onSaveMessageConsumed = movie::consumeFormMessage,
                onBack = { draft.value = null }, onSave = { data -> lastData = data; movie.addMovie(data, row.identity) { draft.value = null } })
        } }
        ui.waitUntil(5000) { !home.state.value.initializing }
        ui.runOnUiThread { preview = ViewModelProvider(ui.activity, TmdbCredentialViewModel.factory(fixture.credentials)).get("tmdb-home-preview", TmdbCredentialViewModel::class.java) }
        ui.waitUntil(5000) { preview.state.value.status != TmdbCredentialStatus.LOADING }
    }
    @After fun close() { ui.runOnUiThread { models.clear(); ui.activity.viewModelStore.clear() }; db.close(); check(context.deleteDatabase(dbName)); DatabaseHealthMonitor.resetForTesting() }
    private fun scroll(tag: String) { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)) }
    private fun read() {
        scroll("ai_source_tmdb"); ui.onNodeWithTag("ai_source_tmdb").performClick()
        scroll("tmdb_home_read"); ui.onNodeWithTag("tmdb_home_read").performClick()
        ui.waitUntil(5000) { preview.preview.value.status == TmdbPreviewStatus.SUCCESS }; assertEquals(4, fixture.calls)
    }
    private fun request(kind: String = "movie") { scroll("tmdb_add_" + kind + ":7"); ui.onNodeWithTag("tmdb_add_" + kind + ":7").performClick() }
    private fun confirm() { ui.onNodeWithTag("tmdb_add_confirm").performClick(); ui.waitUntil(5000) { draft.value != null }; ui.onNodeWithContentDescription("返回").assertExists() }
    private fun category() { ui.onNodeWithText("既有分类").performScrollTo().performClick() }
    private fun save() { ui.onNodeWithText("保存").performScrollTo().performClick() }
    // A failure snackbar can cover the bottom button; invoke its stable click semantics for retry.
    private fun retrySave() { ui.onNodeWithText("保存").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() } }
    private fun count() = runBlocking { movies.observeAll().first().size }
    private fun error(text: String) { ui.waitUntil(5000) { ui.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() } }
    private fun capture(suffix: String) {
        ui.waitForIdle()
        val node = if (suffix == "confirmation") ui.onNode(isDialog()) else ui.onRoot()
        val bitmap = node.captureToImage().asAndroidBitmap()
        val file = File(context.filesDir, "tmdb-import-screenshots/" + suffix + ".png"); file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    @Test fun cancelConfirmationAndReturningFromFormNeverWriteOrCallTheImportTransport() {
        read(); request(); ui.onNodeWithText("保存作品后也可到详情确认使用在线海报", substring = true).assertIsDisplayed(); capture("confirmation"); ui.onNodeWithTag("tmdb_add_cancel").performClick()
        assertNull(draft.value); assertEquals(0, count()); assertEquals(4, fixture.calls)
        request(); confirm(); capture("shared-form"); ui.onNodeWithText("虚构表单电影").assertExists()
        ui.onNodeWithContentDescription("返回").performClick(); assertEquals(0, count()); assertEquals(4, fixture.calls)
    }
    @Test fun categoryIsRequiredAndConfirmedSavePreservesSelectedCategoryAndTagWithOnlyNamePrefilled() {
        read(); request("tv"); confirm(); save()
        ui.onNodeWithText("请选择作品分类").performScrollTo().assertIsDisplayed(); assertEquals(0, count()); assertEquals(4, fixture.calls)
        category(); ui.onNodeWithText("独立标签").performScrollTo().performClick(); save()
        ui.waitUntil(5000) { draft.value == null && !movie.formActionState.value.isSaving }; assertEquals(1, count()); assertEquals(5, fixture.calls)
        val stored = runBlocking { movies.observeAll().first().single() }
        assertEquals("虚构表单剧集", stored.name); assertEquals("想看", stored.status); assertNull(stored.rating); assertNull(stored.posterPath); assertNull(stored.totalEpisode)
        assertEquals(7L, lastData!!.categoryId); assertEquals(setOf(7L), lastData!!.tagIds)
        runBlocking { assertEquals(stored.id, db.candidateImportDao().movieId("tmdb", "tv:7")); assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(stored.id)); assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(stored.id)) }
    }
    @Test fun offlineSaveRetainsFormAndRetryAndRapidSubmitCreateOnlyOneWork() {
        read(); request(); confirm(); category(); fixture.problem = TmdbProblemCode.OFFLINE; save()
        error("无法完成 TMDB 详情校验"); assertEquals(0, count()); assertNotNull(draft.value); ui.onNodeWithText("虚构表单电影").assertExists()
        fixture.problem = null; val gate = CompletableDeferred<Unit>(); fixture.gate = gate; retrySave()
        ui.waitUntil(5000) { movie.formActionState.value.isSaving }; ui.onNodeWithText("保存中").assertIsNotEnabled()
        ui.runOnUiThread { movie.addMovie(lastData!!, draft.value!!.identity) {} }; gate.complete(Unit)
        ui.waitUntil(5000) { draft.value == null }; assertEquals(1, count()); assertEquals(6, fixture.calls)
    }
    @Test fun deletingCredentialClosesConfirmationAndBlocksSavingAnAlreadyOpenedForm() {
        read(); request(); runBlocking { fixture.credentials.delete() }
        ui.waitUntil(5000) { preview.pendingImport.value == null }; ui.onNodeWithTag("tmdb_add_confirm").assertDoesNotExist(); assertEquals(0, count())
        runBlocking { fixture.credentials.save("TEST_ONLY_TMDB_retry_ui") }; scroll("tmdb_home_read"); ui.onNodeWithTag("tmdb_home_read").performClick()
        ui.waitUntil(5000) { preview.preview.value.status == TmdbPreviewStatus.SUCCESS }; request(); confirm(); category()
        runBlocking { fixture.credentials.delete() }; val before = fixture.calls; save(); error("TMDB 令牌不可用")
        assertEquals(before, fixture.calls); assertEquals(0, count()); assertNotNull(draft.value)
    }
    @Test fun failedMappingLeavesFormAndRollsBackAllWritesThenRetrySucceeds() {
        read(); request(); confirm(); category()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_ui_import BEFORE INSERT ON ai_candidate_imports BEGIN SELECT RAISE(ABORT,'synthetic UI failure'); END")
        save(); error("保存失败，请重试"); assertEquals(0, count()); assertNotNull(draft.value)
        for (table in listOf("movie_categories", "movie_tags", "watch_history", "ai_candidate_imports")) db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM " + table).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_ui_import"); retrySave(); ui.waitUntil(5000) { draft.value == null }; assertEquals(1, count())
    }
}
