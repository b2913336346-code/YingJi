package com.yingji.app.presentation.movie

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.YingJiApplication
import com.yingji.app.data.ai.*
import com.yingji.app.data.database.DatabaseModule
import com.yingji.app.data.entity.*
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.AddMovieUseCase
import com.yingji.app.domain.usecase.RemotePosterSaveException
import com.yingji.app.domain.usecase.SaveRemotePosterUseCase
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/** Manual source set only; isolated package and explicit opt-in required. UI paths are checked separately on the phone. */
class RemotePosterLiveVerificationTest {
    private val app = ApplicationProvider.getApplicationContext<YingJiApplication>()
    private val db get() = DatabaseModule.provideDatabase(app)
    private val apiOpens = AtomicInteger()
    private val cdnOpens = AtomicInteger()
    private lateinit var nonce: String
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun digest(value: Any) = hash(value.toString().toByteArray())
    private fun posters() = File(app.filesDir, "posters").listFiles()?.filter { it.isFile }?.map { it.name }?.toSet().orEmpty()
    private fun marker() = File(app.filesDir, "remote-poster-live-" + nonce + ".properties")
    private fun grant() = TmdbSourcePermission(CandidateLicenseState.ACTIVE,
        "User-authorized isolated physical-device TMDB development verification", true, true, true)
    private fun api(blocked: Boolean = false) = TmdbAuthenticatedTransport(TmdbHttpClient(true) { url ->
        check(!blocked) { "LIVE_NETWORK_BLOCKED" }
        check(apiOpens.incrementAndGet() <= 24) { "LIVE_API_BUDGET_EXCEEDED" }
        url.openConnection() as HttpsURLConnection
    }, app.tmdbCredentialRepository)
    private fun loader(blocked: Boolean = false) = MemoryOnlyTmdbPosterLoader { url ->
        check(!blocked) { "LIVE_NETWORK_BLOCKED" }
        check(cdnOpens.incrementAndGet() <= 8) { "LIVE_CDN_BUDGET_EXCEEDED" }
        url.openConnection() as HttpsURLConnection
    }
    private fun stage(value: String) { File(app.filesDir, "remote-poster-live-progress.txt").writeText(value) }
    private suspend fun gate() {
        check(app.packageName == "com.yingji.app.posterlive.acceptance") { "ISOLATED_PACKAGE_REQUIRED" }
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("posterLive") == "explicit") { "EXPLICIT_LIVE_OPT_IN_REQUIRED" }
        nonce = args.getString("posterLiveNonce").orEmpty()
        check(Regex("[a-f0-9]{32}").matches(nonce)) { "LIVE_NONCE_REQUIRED" }
        stage("reading-local-credential")
        app.tmdbCredentialRepository.refresh()
        check(app.tmdbCredentialRepository.state.value.status == TmdbCredentialStatus.SAVED_UNVERIFIED) { "SAVE_TOKEN_IN_ISOLATED_PHONE_APP_FIRST" }
    }
    private suspend fun snapshot(id: Long) = Properties().apply {
        val row = requireNotNull(db.movieDao().getById(id))
        setProperty("id", id.toString()); setProperty("pid", android.os.Process.myPid().toString())
        setProperty("movieHash", digest(row.copy(posterPath = null)))
        setProperty("categories", digest(db.categoryDao().getCategoryIdsForMovie(id)))
        setProperty("tags", digest(db.tagDao().getTagIdsForMovie(id)))
        setProperty("history", digest(db.watchHistoryDao().getForMovie(id)))
        setProperty("sources", digest(db.candidateImportDao().tmdbSourcesForMovie(id)))
    }
    private suspend fun assertSnapshot(id: Long, proof: Properties): String {
        val current = snapshot(id)
        for (key in listOf("movieHash", "categories", "tags", "history", "sources")) assertEquals(proof.getProperty(key), current.getProperty(key))
        val path = requireNotNull(db.movieDao().getById(id)?.posterPath)
        assertEquals(File(app.filesDir, "posters").canonicalFile, File(path).canonicalFile.parentFile)
        assertEquals(proof.getProperty("posterHash"), hash(File(path).readBytes()))
        return path
    }
    private fun report(phase: String, checks: JSONObject) {
        checks.put("phase", phase).put("apiConnectionOpensMeasured", apiOpens.get())
            .put("cdnConnectionOpensMeasured", cdnOpens.get())
            .put("formalDatabaseAccessed", false).put("paidModelCalled", false)
        File(app.filesDir, "remote-poster-live-" + nonce + "-" + phase + ".json").writeText(checks.toString())
    }
    @Test fun realPreviewImportAndExplicitPosterSave() = runBlocking<Unit> {
        gate(); check(!marker().exists()) { "FRESH_NONCE_REQUIRED" }
        val beforeFiles = posters()
        val credentials = app.tmdbCredentialRepository
        val transport = api()
        stage("reading-real-candidates")
        val candidates = TmdbCandidatePreview(TmdbCandidateCatalogRepository(transport, ::grant)).read()
        val candidate = candidates.firstOrNull { it.poster != null && it.identity.externalId.startsWith("movie:") }
            ?: error("REAL_CANDIDATE_WITH_POSTER_REQUIRED")
        assertEquals(beforeFiles, posters())
        stage("loading-real-preview")
        val images = loader()
        val preview = images.load(requireNotNull(candidate.poster))
        assertTrue(preview.width in 1..512 && preview.height in 1..512)
        assertEquals(beforeFiles, posters())
        val categoryName = "实测分类-" + nonce.take(8)
        val category = db.categoryDao().insert(Category(name = categoryName, isSystem = false))
        val tag = db.tagDao().insert(Tag(name = "实测标签-" + nonce.take(8)))
        val importer = TmdbImportUseCase(transport, credentials, ::grant, CandidateImportRepository(db, app.movieRepository), AddMovieUseCase(app.movieRepository))
        val input = RecommendedWorkInput(candidate.identity, true, requireNotNull(candidate.title), category, categoryName,
            setOf(tag), MovieStatus.WATCHING, 2, 12, rating = 8.5)
        stage("importing-without-poster")
        val id = importer(input)
        assertNull(db.movieDao().getById(id)?.posterPath); assertEquals(beforeFiles, posters())
        assertEquals(8.5, db.movieDao().getById(id)?.rating)
        assertEquals(listOf(category), db.categoryDao().getCategoryIdsForMovie(id)); assertEquals(listOf(tag), db.tagDao().getTagIdsForMovie(id))
        assertEquals(1, db.watchHistoryDao().getForMovie(id).count { it.endTime == null })
        val proof = snapshot(id)
        val lookup = TmdbPosterLookup(transport, credentials, ::grant)
        val selected = lookup(app.movieRepository.remotePosterTarget(id).externalId)
        images.load(selected)
        assertNull(db.movieDao().getById(id)?.posterPath); assertEquals(beforeFiles, posters())
        val save = SaveRemotePosterUseCase(app.movieRepository, app.posterRepository, images)
        try { save(id, selected, false); fail("UNCONFIRMED_SAVE_ACCEPTED") } catch (_: RemotePosterSaveException) {}
        assertNull(db.movieDao().getById(id)?.posterPath); assertEquals(beforeFiles, posters())
        stage("explicit-real-save")
        val saved = save(id, selected, true).localPath
        proof.setProperty("posterHash", hash(File(saved).readBytes()))
        assertEquals(saved, assertSnapshot(id, proof)); assertEquals(beforeFiles.size + 1, posters().size)
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val gated = SaveRemotePosterUseCase(app.movieRepository, app.posterRepository, TmdbPosterPreviewLoader {
            calls.incrementAndGet(); entered.complete(Unit); release.await(); throw IOException("CONTROLLED_NETWORK_FAILURE")
        })
        supervisorScope {
            val first = async { runCatching { gated(id, selected, true) } }
            entered.await()
            assertTrue(runCatching { gated(id, selected, true) }.isFailure)
            assertEquals(1, calls.get()); release.complete(Unit); assertTrue(first.await().isFailure)
        }
        assertEquals(saved, assertSnapshot(id, proof)); assertEquals(beforeFiles.size + 1, posters().size)
        // Keep only private restart evidence. The host never receives the credential or catalogue body.
        marker().outputStream().use { proof.store(it, "Owned isolated live restart fixture") }
        stage("prepare-passed")
        report("prepare", JSONObject().put("realCandidatePreview", true).put("previewNoDiskWrite", true)
            .put("productionImportUseCase", true).put("importDoesNotSavePoster", true).put("unconfirmedSaveRejected", true)
            .put("explicitSaveUsesRealCdn", true).put("concurrentSaveRejected", true)
            .put("controlledNetworkFailurePreservesOldPoster", true).put("uiPathsCheckedSeparately", true))
    }
    @Test fun restartedLocalPosterWithAllTestClientsBlocked() = runBlocking<Unit> {
        gate(); val proof = Properties().apply { marker().inputStream().use { load(it) } }
        assertNotEquals(proof.getProperty("pid").toInt(), android.os.Process.myPid())
        val id = proof.getProperty("id").toLong()
        val path = assertSnapshot(id, proof)
        val bitmap = android.graphics.BitmapFactory.decodeFile(path)
        assertNotNull(bitmap); assertTrue(bitmap.width in 1..512 && bitmap.height in 1..512)
        val images = loader(blocked = true)
        val lookup = TmdbPosterLookup(api(blocked = true), app.tmdbCredentialRepository, ::grant)
        val model = RemotePosterViewModel({ app.movieRepository.hasRemotePosterSource(id) },
            { lookup(app.movieRepository.remotePosterTarget(id).externalId) },
            { SaveRemotePosterUseCase(app.movieRepository, app.posterRepository, images)(id, it, true) })
        withContext(Dispatchers.Main) { model.enter() }
        withTimeout(10000) { while (model.uiState.value.checking) yield() }
        assertTrue(model.uiState.value.available); assertFalse(model.uiState.value.dialogVisible)
        withContext(Dispatchers.Main) { model.leave() }
        assertSnapshot(id, proof)
        assertEquals(0, apiOpens.get()); assertEquals(0, cdnOpens.get())
        stage("restart-passed")
        report("restart", JSONObject().put("newProcess", true).put("localBytesDecodable", true)
            .put("localBytesAndRelationsUnchanged", true).put("testClientNetworkBlocked", true)
            .put("detailEntryDoesNotAutoRequest", true).put("systemOfflineNotChanged", true))
    }
}
