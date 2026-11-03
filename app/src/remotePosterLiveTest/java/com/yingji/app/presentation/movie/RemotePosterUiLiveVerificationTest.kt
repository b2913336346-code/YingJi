package com.yingji.app.presentation.movie

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.YingJiApplication
import com.yingji.app.data.ai.*
import com.yingji.app.data.database.DatabaseModule
import com.yingji.app.data.entity.*
import com.yingji.app.domain.ai.*
import com.yingji.app.navigation.YingJiNavGraph
import com.yingji.app.presentation.ai.AiHomeViewModel
import com.yingji.app.ui.theme.YingJiTheme
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Uses production network clients, shared form, navigation and default detail save wiring. */
class RemotePosterUiLiveVerificationTest {
    @get:Rule val ui = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
    private val db get() = DatabaseModule.provideDatabase(app)
    private lateinit var nonce: String
    @Volatile private var catalog: CandidateCatalog? = null
    @Volatile private var readFinished = false
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun marker() = File(app.filesDir, "remote-poster-ui-live-" + nonce + ".properties")
    private fun posters() = File(app.filesDir, "posters").listFiles()?.filter { it.isFile }?.map { it.name }?.toSet().orEmpty()
    private fun gate() {
        check(app.packageName == "com.yingji.app.posterlive.acceptance")
        val args = InstrumentationRegistry.getArguments(); check(args.getString("posterLive") == "explicit")
        nonce = args.getString("posterLiveNonce").orEmpty(); check(Regex("[a-f0-9]{32}").matches(nonce))
    }
    private fun waitTag(tag: String, timeout: Long = 30000) {
        ui.waitUntil(timeout) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun homeClick(tag: String) {
        waitTag("movie_collection"); ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)); ui.onNodeWithTag(tag).performClick()
    }
    private fun openWork(title: String) {
        waitTag("movie_collection"); ui.onNodeWithTag("movie_collection").performScrollToNode(hasText(title))
        ui.onNodeWithText(title).performClick(); waitTag("detail_remote_poster_open")
    }
    private fun localImage() {
        ui.onNodeWithTag("detail_local_poster").performScrollTo()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("作品海报").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("作品海报").assertIsDisplayed()
    }
    private suspend fun relations(id: Long) = listOf(db.categoryDao().getCategoryIdsForMovie(id), db.tagDao().getTagIdsForMovie(id),
        db.watchHistoryDao().getForMovie(id), db.candidateImportDao().tmdbSourcesForMovie(id)).toString()
    private fun report(phase: String, checks: JSONObject) = File(app.filesDir, "remote-poster-ui-live-" + nonce + "-" + phase + ".json").writeText(checks.toString())

    @Test fun realHomeSharedFormAndDetailConfirmation() = runBlocking<Unit> {
        gate(); check(!marker().exists())
        val before = posters()
        val permission = { TmdbSourcePermission(CandidateLicenseState.ACTIVE, "User-authorized isolated live UI verification", true, true, true) }
        val source = TmdbCandidateCatalogRepository(TmdbAuthenticatedTransport(TmdbHttpClient.forReadOnlyPreview(), app.tmdbCredentialRepository), permission)
        val preview = TmdbCandidatePreview(object : CandidateCatalogRepository {
            override suspend fun load(): CandidateCatalog = try { source.load().also { catalog = it } } finally { readFinished = true }
        })
        val home = AiHomeViewModel(app.mockAiHomeRepository, tmdbCredentials = app.tmdbCredentialRepository, tmdbPreview = preview)
        val categoryName = "界面实测分类-" + nonce.take(8); val tagName = "界面实测标签-" + nonce.take(8)
        val categoryId = db.categoryDao().insert(Category(name = categoryName, isSystem = false))
        val tagId = db.tagDao().insert(Tag(name = tagName))
        ui.setContent { YingJiTheme { YingJiNavGraph(aiHomeViewModelOverride = home) } }
        homeClick("ai_source_tmdb"); homeClick("tmdb_home_read")
        ui.waitUntil(240000) { readFinished }
        val row = requireNotNull(catalog).candidates.firstOrNull {
            it.poster != null && runBlocking { db.candidateImportDao().movieId("tmdb", it.identity.externalId) } == null
        } ?: error("UNIMPORTED_REAL_POSTER_CANDIDATE_REQUIRED")
        val rowTag = "tmdb_home_" + row.identity.externalId
        ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(rowTag))
        waitTag(rowTag + "_poster_loaded", 60000); assertEquals(before, posters())
        homeClick("tmdb_add_" + row.identity.externalId); ui.onNodeWithTag("tmdb_add_confirm").performClick()
        ui.onNodeWithText(categoryName).performScrollTo().performClick(); ui.onNodeWithText(tagName).performScrollTo().performClick()
        ui.onNodeWithTag("movie_rating_input").performScrollTo().performTextReplacement("8.5")
        ui.onNodeWithText("保存").performScrollTo().performClick()
        ui.waitUntil(60000) { runBlocking { db.candidateImportDao().movieId("tmdb", row.identity.externalId) } != null }
        val id = requireNotNull(db.candidateImportDao().movieId("tmdb", row.identity.externalId))
        val original = requireNotNull(db.movieDao().getById(id)); val originalRelations = relations(id)
        assertNull(original.posterPath); assertEquals(8.5, original.rating); assertEquals(before, posters())
        assertEquals(listOf(categoryId), db.categoryDao().getCategoryIdsForMovie(id)); assertEquals(listOf(tagId), db.tagDao().getTagIdsForMovie(id))
        openWork(original.name)
        ui.onNodeWithTag("detail_remote_poster_open").performScrollTo().performClick(); waitTag("detail_remote_poster_dialog", 10000); ui.onNodeWithTag("detail_remote_poster_confirm").assertIsEnabled(); ui.onAllNodesWithTag("detail_remote_poster_preview_loaded").assertCountEquals(0)
        assertEquals(before, posters()); assertNull(db.movieDao().getById(id)?.posterPath)
        ui.onNodeWithTag("detail_remote_poster_cancel").performClick(); assertEquals(before, posters())
        ui.onNodeWithTag("detail_remote_poster_open").performScrollTo().performClick(); waitTag("detail_remote_poster_dialog", 10000); ui.onNodeWithTag("detail_remote_poster_confirm").assertIsEnabled(); ui.onAllNodesWithTag("detail_remote_poster_preview_loaded").assertCountEquals(0)
        val confirm = requireNotNull(ui.onNodeWithTag("detail_remote_poster_confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        ui.runOnIdle { assertTrue(confirm()); assertTrue(confirm()) }
        ui.waitUntil(60000) { ui.onAllNodesWithText("海报已保存").fetchSemanticsNodes().isNotEmpty() }
        localImage()
        val saved = requireNotNull(db.movieDao().getById(id)); val path = requireNotNull(saved.posterPath)
        assertEquals(original, saved.copy(posterPath = null)); assertEquals(originalRelations, relations(id))
        assertEquals(before.size + 1, posters().size); assertTrue(File(path).isFile)
        val proof = Properties().apply {
            setProperty("id", id.toString()); setProperty("pid", android.os.Process.myPid().toString())
            setProperty("posterHash", hash(File(path).readBytes())); setProperty("rowHash", hash(saved.toString().toByteArray()))
            setProperty("relationsHash", hash(originalRelations.toByteArray()))
        }
        ui.onNodeWithContentDescription("返回").performClick(); openWork(original.name); localImage()
        assertEquals(path, db.movieDao().getById(id)?.posterPath); assertEquals(before.size + 1, posters().size)
        marker().outputStream().use { proof.store(it, "Owned isolated real UI restart fixture") }
        report("prepare", JSONObject().put("realOnlinePreview", true).put("sharedFormImportNoPoster", true)
            .put("cancelNoWrite", true).put("cancelNoDetailPreview", true).put("rapidDoubleConfirmSingleFile", true).put("defaultProductionSaveWiring", true)
            .put("immediateLocalImage", true).put("backReopenLocalImage", true).put("fieldsAndRelationsPreserved", true))
    }
    @Test fun localImageSurvivesProcessRestartWithUnavailableProxy() = runBlocking<Unit> {
        gate()
        assertEquals("127.0.0.1:17897", android.provider.Settings.Global.getString(app.contentResolver, "http_proxy"))
        var proxyUnavailable = false
        try { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 17897), 1000) } }
        catch (_: java.io.IOException) { proxyUnavailable = true }
        assertTrue("USB_PROXY_MUST_BE_UNAVAILABLE_FOR_OFFLINE_UI_CHECK", proxyUnavailable)
        val proof = Properties().apply { marker().inputStream().use { load(it) } }
        assertNotEquals(proof.getProperty("pid").toInt(), android.os.Process.myPid())
        val id = proof.getProperty("id").toLong(); val row = requireNotNull(db.movieDao().getById(id))
        ui.setContent { YingJiTheme { YingJiNavGraph() } }
        openWork(row.name); localImage(); ui.onNodeWithTag("detail_remote_poster_dialog").assertDoesNotExist()
        assertEquals(proof.getProperty("rowHash"), hash(requireNotNull(db.movieDao().getById(id)).toString().toByteArray()))
        assertEquals(proof.getProperty("relationsHash"), hash(relations(id).toByteArray()))
        assertEquals(proof.getProperty("posterHash"), hash(File(requireNotNull(row.posterPath)).readBytes()))
        report("restart", JSONObject().put("newProcess", true).put("localImageDisplayed", true).put("unavailableProxyEnvironment", true)
            .put("fieldsRelationsAndBytesPreserved", true).put("unconfirmedSelectionNotRestored", true))
    }
}
