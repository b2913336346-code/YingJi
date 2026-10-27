package com.yingji.app.presentation.movie

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.YingJiApplication
import com.yingji.app.data.ai.*
import com.yingji.app.data.database.*
import com.yingji.app.data.entity.*
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.SaveRemotePosterUseCase
import com.yingji.app.navigation.YingJiNavGraph
import com.yingji.app.presentation.ai.LocalTmdbPosterPreviewLoader
import com.yingji.app.ui.theme.YingJiTheme
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

internal class NavigationPosterFixture(private val ui: ComposeContentTestRule, private val suffix: String) {
    private val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
    private val db = DatabaseModule.provideDatabase(app)
    private val movies = app.movieRepository
    private val files = app.posterRepository
    private val marker = File(app.filesDir, "remote-poster-nav-" + suffix + ".properties")
    val shown = mutableStateOf(true)
    val availabilityChecks = AtomicInteger()
    val metadataReads = AtomicInteger()
    val imageReads = AtomicInteger()
    private val keys = TmdbCredentialRepository(PosterTestKeys()) { 100 }
    private var categoryId = 0L
    private var tagId = 0L
    var movieId = 0L; private set
    private var title = "虚构导航海报-" + suffix
    private var savedPath: String? = null
    private fun image() = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
    private val transport = object : TmdbCatalogTransport {
        override val isMock = false
        override fun revision() = keys.state.value.revision.orEmpty()
        override fun isCurrent(revision: String) = revision == this.revision()
        override suspend fun read(request: TmdbCatalogRequest): String {
            assertEquals(TmdbCatalogRequest(TmdbMediaKind.MOVIE, movieId.toInt()), request); metadataReads.incrementAndGet()
            return "{\"id\":" + movieId + ",\"title\":\"虚构导航海报\",\"poster_path\":\"/fictionalNavigation.png\"}"
        }
    }
    suspend fun prepare(restore: Boolean = false) {
        DatabaseHealthMonitor.resetForTesting()
        keys.save("TEST_ONLY_navigation_poster")
        if (restore) {
            check(marker.isFile)
            val p = Properties().apply { marker.inputStream().use { load(it) } }
            movieId = p.getProperty("movieId").toLong(); categoryId = p.getProperty("categoryId").toLong()
            tagId = p.getProperty("tagId").toLong(); title = p.getProperty("title"); savedPath = p.getProperty("posterPath")
            assertNotEquals(p.getProperty("pid").toInt(), android.os.Process.myPid())
        } else {
            check(!marker.exists())
            categoryId = db.categoryDao().insert(Category(name = "导航分类-" + suffix, isSystem = false))
            tagId = db.tagDao().insert(Tag(name = "导航标签-" + suffix))
            movieId = movies.insertWithRelations(Movie(name = title, type = "导航分类-" + suffix,
                status = MovieStatus.WATCHING.value, currentEpisode = 2, totalEpisode = 12, createTime = 100, rating = 8.5),
                categoryId, setOf(tagId), afterInsert = { id -> db.candidateImportDao().insert(AiCandidateImport("tmdb", "movie:" + id, id)) })
        }
    }
    fun content(): @Composable () -> Unit = {
        if (shown.value) {
            val lookup = remember { TmdbPosterLookup(transport, keys,
                { TmdbSourcePermission(CandidateLicenseState.ACTIVE, "Synthetic navigation permission", true, true, true) }) { 100 } }
            val save = remember { SaveRemotePosterUseCase(movies, files, TmdbPosterPreviewLoader { imageReads.incrementAndGet(); image() }) }
            CompositionLocalProvider(LocalTmdbPosterPreviewLoader provides TmdbPosterPreviewLoader { image() }) {
                YingJiTheme {
                    YingJiNavGraph(remotePosterFactory = { id -> RemotePosterViewModel.factory(
                        { val found = movies.hasRemotePosterSource(id); availabilityChecks.incrementAndGet(); found }, { lookup(movies.remotePosterTarget(id).externalId) }, { save(id, it, true) }) })
                }
            }
        }
    }
    fun openWork(expectEntry: Boolean = true) {
        ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasText(title))
        ui.onNodeWithText(title).performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_detail_content").fetchSemanticsNodes().isNotEmpty() && availabilityChecks.get() > 0 }
        ui.waitForIdle()
        if (expectEntry) ui.waitUntil(10000) { ui.onAllNodesWithTag("detail_remote_poster_open").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("作品详情").assertIsDisplayed()
    }
    fun openDialog() {
        ui.onNodeWithTag("detail_remote_poster_open").performScrollTo().performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("detail_remote_poster_preview_loaded").fetchSemanticsNodes().isNotEmpty() }
    }
    fun confirm() {
        ui.onNodeWithTag("detail_remote_poster_confirm").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("海报已保存").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("海报已保存").assertIsDisplayed()
    }
    suspend fun assertSaved(): String {
        val movie = requireNotNull(db.movieDao().getById(movieId))
        assertEquals(title, movie.name); assertEquals(8.5, movie.rating); assertEquals(100L, movie.createTime)
        assertEquals(2, movie.currentEpisode); assertEquals(12, movie.totalEpisode); assertEquals(MovieStatus.WATCHING.value, movie.status)
        assertEquals(listOf(categoryId), db.categoryDao().getCategoryIdsForMovie(movieId)); assertEquals(listOf(tagId), db.tagDao().getTagIdsForMovie(movieId))
        assertEquals(1, db.watchHistoryDao().getForMovie(movieId).count { it.endTime == null })
        assertEquals(movieId, db.candidateImportDao().movieId("tmdb", "movie:" + movieId))
        val path = requireNotNull(movie.posterPath); assertFalse(path.contains("://")); assertTrue(File(path).isFile)
        savedPath?.let { assertEquals(it, path) }
        return path
    }
    fun assertLocalImage() {
        ui.onNodeWithTag("detail_local_poster").performScrollTo()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("作品海报").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("作品海报").assertIsDisplayed()
    }
    suspend fun assertNotSaved() { assertNull(db.movieDao().getById(movieId)?.posterPath); assertEquals(0, imageReads.get()) }
    suspend fun persistRestartEvidence() {
        val path = assertSaved()
        val p = Properties().apply {
            setProperty("movieId", movieId.toString()); setProperty("categoryId", categoryId.toString()); setProperty("tagId", tagId.toString())
            setProperty("title", title); setProperty("posterPath", path); setProperty("pid", android.os.Process.myPid().toString())
        }
        check(!marker.exists()); marker.outputStream().use { p.store(it, "Owned physical-device restart fixture") }
    }
    suspend fun removeSource() { db.openHelper.writableDatabase.execSQL("DELETE FROM ai_candidate_imports WHERE movieId=?", arrayOf(movieId)) }
    fun stopContent() { ui.runOnIdle { shown.value = false }; ui.waitForIdle() }
    suspend fun cleanup() {
        db.movieDao().getById(movieId)?.let { movie ->
            movies.delete(movie)
            movie.posterPath?.let { path -> movies.removePosterFileIfUnused(path) { files.deleteManagedFile(path) } }
        }
        db.categoryDao().delete(Category(categoryId, "导航分类-" + suffix, false)); db.tagDao().delete(Tag(tagId, "导航标签-" + suffix))
        if (marker.exists()) check(marker.delete())
    }
}

class RemotePosterNavigationTest {
    @get:Rule val ui = createComposeRule()
    private lateinit var fixture: NavigationPosterFixture
    @Before fun prepare() = runBlocking<Unit> { fixture = NavigationPosterFixture(ui, UUID.randomUUID().toString()); fixture.prepare() }
    @After fun close() = runBlocking<Unit> { fixture.stopContent(); fixture.cleanup() }

    @Test fun confirmedSaveStaysInActualDetailAndSurvivesBackAndReopeningWithoutAutomaticReplacement() = runBlocking<Unit> {
        ui.setContent(fixture.content()); fixture.openWork()
        fixture.assertNotSaved(); assertEquals(0, fixture.metadataReads.get())
        fixture.openDialog(); fixture.assertNotSaved(); fixture.confirm(); fixture.assertLocalImage()
        val path = fixture.assertSaved(); assertEquals(1, fixture.imageReads.get())
        ui.onNodeWithContentDescription("返回").performClick(); fixture.openWork(); fixture.assertLocalImage()
        assertEquals(path, fixture.assertSaved()); assertEquals(1, fixture.metadataReads.get()); assertEquals(1, fixture.imageReads.get())
    }
    @Test fun restoredDetailDropsAnUnconfirmedOnlineSelectionWithoutFallbackOrAutomaticSave() = runBlocking<Unit> {
        val restoration = StateRestorationTester(ui)
        restoration.setContent(fixture.content()); fixture.openWork(); fixture.openDialog(); fixture.assertNotSaved()
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithText("作品详情").assertIsDisplayed(); ui.onNodeWithTag("detail_remote_poster_dialog").assertDoesNotExist()
        fixture.assertNotSaved(); assertEquals(1, fixture.metadataReads.get())
        ui.onNodeWithTag("detail_remote_poster_open").performScrollTo().assertIsDisplayed()
    }
    @Test fun ordinaryWorkKeepsActualDetailLayoutWithoutATmdbEntry() = runBlocking<Unit> {
        fixture.removeSource(); ui.setContent(fixture.content()); fixture.openWork(expectEntry = false)
        ui.onNodeWithTag("detail_remote_poster_open").assertDoesNotExist(); fixture.assertNotSaved()
        assertEquals(0, fixture.metadataReads.get())
    }
}

class RemotePosterNavigationRestartTest {
    @get:Rule val ui = createComposeRule()
    @Test fun savedLocalPosterRemainsVisibleAfterARealApplicationProcessRestart() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val token = requireNotNull(args.getString("posterUiFixture")); require(Regex("[a-f0-9]{32}").matches(token))
        val phase = args.getString("posterUiPhase"); require(phase in listOf("prepare", "verify"))
        val fixture = NavigationPosterFixture(ui, token)
        fixture.prepare(restore = phase == "verify")
        try {
            ui.setContent(fixture.content()); fixture.openWork()
            if (phase == "prepare") {
                fixture.assertNotSaved(); fixture.openDialog(); fixture.confirm(); fixture.assertLocalImage()
                assertEquals(1, fixture.imageReads.get()); fixture.persistRestartEvidence()
            } else {
                fixture.assertLocalImage(); fixture.assertSaved()
                assertEquals(0, fixture.metadataReads.get()); assertEquals(0, fixture.imageReads.get())
            }
        } finally { fixture.stopContent() }
        if (phase == "verify") fixture.cleanup()
    }
}
