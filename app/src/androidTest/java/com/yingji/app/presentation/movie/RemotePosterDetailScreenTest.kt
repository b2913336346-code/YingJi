package com.yingji.app.presentation.movie

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.ai.*
import com.yingji.app.data.database.*
import com.yingji.app.data.entity.*
import com.yingji.app.data.repository.*
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.domain.usecase.SaveRemotePosterUseCase
import com.yingji.app.presentation.ai.LocalTmdbPosterPreviewLoader
import com.yingji.app.ui.theme.YingJiTheme
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class RemotePosterDetailScreenTest {
    @get:Rule val ui = createComposeRule()
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val token = UUID.randomUUID().toString()
    private val directory = File(base.cacheDir, "remote-poster-ui-" + token)
    private val dbName = "remote-poster-ui-" + token + ".db"
    private val context = object : ContextWrapper(base) { override fun getFilesDir() = directory }
    private lateinit var db: AppDatabase
    private lateinit var movies: MovieRepository
    private lateinit var original: Movie
    private lateinit var files: PosterRepository
    private val models = ViewModelStore()
    private lateinit var vm: RemotePosterViewModel
    private val shown = mutableStateOf(true)
    private val theme = mutableStateOf(ThemeMode.LIGHT)
    private val metadataReads = AtomicInteger()
    private val imageReads = AtomicInteger()
    private val previewReads = AtomicInteger()
    private var lookupGate: suspend () -> Unit = {}
    private var imageGate: suspend () -> Unit = {}
    private var backs = 0
    private var remotePath = "/fictionalUiPoster.png"
    private fun image(color: Int) = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private val keys = TmdbCredentialRepository(PosterTestKeys()) { 100 }
    private val transport = object : TmdbCatalogTransport {
        override val isMock = false // App-authored protocol fixture, never a real socket.
        override fun revision() = keys.state.value.revision.orEmpty()
        override fun isCurrent(revision: String) = revision == this.revision()
        override suspend fun read(request: TmdbCatalogRequest): String {
            assertEquals(TmdbCatalogRequest(TmdbMediaKind.MOVIE, 7), request)
            metadataReads.incrementAndGet(); lookupGate()
            return "{\"id\":7,\"title\":\"虚构海报详情作品\",\"poster_path\":\"" + remotePath + "\"}"
        }
    }
    @Before fun prepare() = runBlocking<Unit> {
        DatabaseHealthMonitor.resetForTesting(); check(directory.mkdirs())
        db = Room.databaseBuilder(base, AppDatabase::class.java, dbName).build()
        movies = MovieRepository(db); files = PosterRepository(context)
        db.categoryDao().insert(Category(7, "保留分类", false)); db.tagDao().insert(Tag(7, "保留标签"))
        val initialImage = image(Color.DKGRAY)
        val old = files.persistRemote(initialImage)
        initialImage.recycle()
        val id = movies.insertWithRelations(Movie(name = "虚构海报详情作品", type = "保留分类", status = MovieStatus.WATCHING.value,
            currentEpisode = 2, totalEpisode = 12, createTime = 100, posterPath = old, rating = 8.5), 7, setOf(7))
        db.candidateImportDao().insert(AiCandidateImport("tmdb", "movie:7", id))
        original = requireNotNull(db.movieDao().getById(id)); keys.save("TEST_ONLY_detail_ui")
    }
    @After fun close() {
        ui.runOnIdle { shown.value = false }; ui.waitForIdle()
        ui.runOnUiThread { models.clear() }
        db.close(); check(base.deleteDatabase(dbName)); check(directory.deleteRecursively()); DatabaseHealthMonitor.resetForTesting()
    }
    private fun show() {
        val lookup = TmdbPosterLookup(transport, keys, { TmdbSourcePermission(CandidateLicenseState.ACTIVE, "Synthetic detail UI", true, true, true) }) { 100 }
        val save = SaveRemotePosterUseCase(movies, files, TmdbPosterPreviewLoader { imageReads.incrementAndGet(); imageGate(); image(Color.GRAY) })
        ui.runOnUiThread {
            vm = RemotePosterViewModel({ movies.hasRemotePosterSource(original.id) }, { lookup(movies.remotePosterTarget(original.id).externalId) },
                { save(original.id, it, true) }); models.put("poster", vm)
        }
        val movieFlow = movies.observeById(original.id)
        ui.setContent {
            if (shown.value) {
                val movie by movieFlow.collectAsState(initial = original)
                val state by vm.uiState.collectAsState()
                DisposableEffect(vm) { vm.enter(); onDispose { vm.leave() } }
                CompositionLocalProvider(LocalTmdbPosterPreviewLoader provides TmdbPosterPreviewLoader { previewReads.incrementAndGet(); image(Color.GRAY) }) {
                    YingJiTheme(theme.value) {
                        MovieDetailScreen(requireNotNull(movie), listOf(Category(7, "保留分类", false)), listOf(Category(7, "保留分类", false)),
                            listOf(Tag(7, "保留标签")), listOf(Tag(7, "保留标签")), onBack = { backs++ }, onHistory = {}, onEdit = {}, onDelete = {},
                            onStatusChange = {}, onCategoryChange = { _, _ -> }, onTagChange = { _, _ -> }, remotePosterState = state,
                            onRemotePosterOpen = vm::open, onRemotePosterConfirm = vm::confirm, onRemotePosterDismiss = vm::dismiss,
                            onRemotePosterMessageConsumed = vm::consumeMessage)
                    }
                }
            }
        }
        ui.waitUntil(10000) { !vm.uiState.value.checking }
    }
    private fun open() {
        ui.onNodeWithTag("detail_remote_poster_open").performScrollTo().performClick()
        ui.waitUntil(10000) { vm.uiState.value.confirmationPending }
        ui.onNodeWithTag("detail_remote_poster_confirm").assertIsEnabled()
        ui.onAllNodesWithTag("detail_remote_poster_preview_loaded").assertCountEquals(0)
    }
    private fun confirm() = ui.onNodeWithTag("detail_remote_poster_confirm").performClick()
    private fun message(text: String) {
        ui.waitUntil(10000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(text).assertIsDisplayed()
    }
    private fun localPixel() = ui.onNodeWithTag("detail_local_poster").captureToImage().asAndroidBitmap().let { it.getPixel(it.width / 2, it.height / 2) }
    private fun waitLocal(color: Int) {
        ui.onNodeWithTag("detail_local_poster").performScrollTo()
        ui.waitUntil(10000) { runCatching { localPixel() == color }.getOrDefault(false) }
        assertEquals(color, localPixel())
    }
    private fun names() = File(directory, "posters").list().orEmpty().toSet()
    private fun unchanged() = runBlocking {
        assertEquals(original, db.movieDao().getById(original.id)); assertTrue(File(original.posterPath!!).isFile)
        assertEquals(setOf(File(original.posterPath!!).name), names())
    }
    private fun capture(name: String) {
        val evidence = File(base.cacheDir, "remote-poster-ui-evidence").apply { mkdirs() }
        val target = if (name.startsWith("confirm")) ui.onNodeWithTag("detail_remote_poster_dialog") else ui.onRoot()
        File(evidence, name + ".png").outputStream().use { target.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun anOrdinaryWorkDoesNotGainTheTmdbEntryOrStartAnyRequest() = runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL("DELETE FROM ai_candidate_imports WHERE movieId=?", arrayOf(original.id))
        show(); ui.onNodeWithTag("detail_remote_poster_open").assertDoesNotExist()
        assertEquals(0, metadataReads.get()); assertEquals(0, imageReads.get()); assertEquals(0, previewReads.get()); unchanged()
    }
    @Test fun openingAndCancelInBothThemesStartNoRequestsAndPreserveTheOldImageAndRecord() {
        show()
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            ui.runOnIdle { theme.value = mode }; waitLocal(Color.DKGRAY); capture("before-" + mode.name.lowercase())
            assertEquals(0, metadataReads.get()); assertEquals(0, imageReads.get()); assertEquals(0, previewReads.get()); unchanged()
            open(); ui.onNodeWithText("确认后联网读取", substring = true).assertIsDisplayed()
            ui.onAllNodesWithTag("detail_remote_poster_preview_loaded").assertCountEquals(0)
            capture("confirm-" + mode.name.lowercase())
            assertEquals(0, metadataReads.get()); assertEquals(0, imageReads.get()); assertEquals(0, previewReads.get()); unchanged()
            ui.onNodeWithTag("detail_remote_poster_cancel").performClick()
            ui.onNodeWithTag("detail_remote_poster_dialog").assertDoesNotExist(); waitLocal(Color.DKGRAY); unchanged()
            assertEquals(0, metadataReads.get()); assertEquals(0, imageReads.get()); assertEquals(0, previewReads.get())
        }
    }
    @Test fun successfulConfirmationImmediatelyShowsTheLocalImageAndKeepsDetailAndRelations() = runBlocking<Unit> {
        show(); open(); confirm(); message("海报已保存"); waitLocal(Color.GRAY); capture("after-light")
        val stored = requireNotNull(db.movieDao().getById(original.id)); assertNotEquals(original.posterPath, stored.posterPath)
        assertEquals(original.copy(posterPath = stored.posterPath), stored); assertFalse(File(original.posterPath!!).exists())
        assertTrue(File(stored.posterPath!!).isFile); assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(stored.id))
        assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(stored.id)); assertEquals(1, db.watchHistoryDao().getForMovie(stored.id).count { it.endTime == null })
        assertEquals(1, metadataReads.get()); assertEquals(1, imageReads.get()); assertEquals(0, previewReads.get()); assertEquals(0, backs)
        ui.runOnIdle { theme.value = ThemeMode.DARK }; waitLocal(Color.GRAY); capture("after-dark")
        ui.onNodeWithText("作品详情").assertIsDisplayed()
    }
    @Test fun detailLookupFailureReportsAnErrorAndRetryRequiresConfirmation() {
        lookupGate = { throw TmdbProblem(TmdbProblemCode.OFFLINE) }; show(); open()
        assertEquals(0, metadataReads.get()); confirm()
        message("海报读取失败，请检查网络后重试"); unchanged(); assertEquals(1, metadataReads.get()); assertEquals(0, imageReads.get())
        lookupGate = {}; open(); assertEquals(1, metadataReads.get()); assertEquals(0, imageReads.get()); unchanged()
        ui.onNodeWithTag("detail_remote_poster_cancel").performClick()
        assertEquals(1, metadataReads.get()); assertEquals(0, imageReads.get()); assertEquals(0, previewReads.get()); unchanged()
        open(); confirm(); message("海报已保存"); waitLocal(Color.GRAY)
        assertEquals(2, metadataReads.get()); assertEquals(1, imageReads.get())
    }
    @Test fun imageDownloadFailureKeepsTheOldImageAndCanBeRetried() {
        imageGate = { throw IOException("synthetic image failure") }; show(); open(); confirm()
        message("海报保存未完成，请重试或重新打开详情确认"); waitLocal(Color.DKGRAY); unchanged()
        imageGate = {}; open(); confirm(); message("海报已保存"); waitLocal(Color.GRAY); assertEquals(2, imageReads.get())
    }
    @Test fun failedDatabaseWriteHasNoFalseSuccessAndRetainsOriginalDataUntilRetry() {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_remote_poster BEFORE UPDATE OF posterPath ON movies BEGIN SELECT RAISE(ABORT, 'synthetic poster update rejection'); END")
        show(); open(); confirm(); message("海报保存未完成，请重试或重新打开详情确认")
        ui.onNodeWithText("海报已保存").assertDoesNotExist(); waitLocal(Color.DKGRAY); unchanged()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_remote_poster")
        open(); confirm(); message("海报已保存"); waitLocal(Color.GRAY)
    }
    @Test fun savingDisablesRepeatedConfirmationAndCannotStartAnotherDownload() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        imageGate = { started.complete(Unit); finish.await() }; show(); open(); confirm()
        withTimeout(5000) { started.await() }
        ui.onNodeWithTag("detail_remote_poster_confirm").assertIsNotEnabled()
        ui.onNodeWithTag("detail_remote_poster_cancel").assertIsNotEnabled()
        ui.onNodeWithTag("detail_remote_poster_confirm").performClick()
        assertEquals(1, imageReads.get()); unchanged()
        finish.complete(Unit); message("海报已保存"); waitLocal(Color.GRAY); assertEquals(1, imageReads.get())
    }
    @Test fun leavingDuringLookupCancelsTheRequestAndReentryDoesNotRestoreTheDialog() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        lookupGate = { started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        show(); open(); assertEquals(0, metadataReads.get()); confirm()
        withTimeout(5000) { started.await() }
        ui.onNodeWithTag("detail_remote_poster_confirm").assertIsNotEnabled()
        ui.runOnIdle { shown.value = false }; ui.waitForIdle(); withTimeout(5000) { cancelled.await() }
        ui.runOnIdle { shown.value = true }; ui.waitUntil { !vm.uiState.value.checking }
        ui.onNodeWithTag("detail_remote_poster_dialog").assertDoesNotExist(); waitLocal(Color.DKGRAY)
        assertEquals(1, metadataReads.get()); assertEquals(0, imageReads.get()); unchanged()
    }
    @Test fun cancellingConfirmedLookupStopsBeforeImageDownloadAndRetryNeedsFreshConfirmation() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        lookupGate = { started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        show(); open(); confirm(); withTimeout(5000) { started.await() }
        ui.onNodeWithTag("detail_remote_poster_confirm").assertIsNotEnabled()
        ui.onNodeWithTag("detail_remote_poster_cancel").assertIsEnabled().performClick()
        withTimeout(5000) { cancelled.await() }
        ui.onNodeWithTag("detail_remote_poster_dialog").assertDoesNotExist()
        assertEquals(1, metadataReads.get()); assertEquals(0, imageReads.get()); assertEquals(0, previewReads.get()); unchanged()
        lookupGate = {}; open()
        assertEquals(1, metadataReads.get()); assertEquals(0, imageReads.get()); unchanged()
        confirm(); message("海报已保存"); waitLocal(Color.GRAY)
        assertEquals(2, metadataReads.get()); assertEquals(1, imageReads.get())
    }

}
