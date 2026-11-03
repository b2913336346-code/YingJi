package com.yingji.app.presentation.ai

import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.MainActivity
import com.yingji.app.YingJiApplication
import com.yingji.app.data.ai.TmdbAuthenticatedTransport
import com.yingji.app.data.ai.TmdbCandidateCatalogRepository
import com.yingji.app.data.ai.TmdbHttpClient
import com.yingji.app.data.database.DatabaseModule
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

private object RankingLiveScope {
    const val CLONE = "com.yingji.app.tmdb.uiacceptance"
    const val APPROVAL = "owned-clone-readonly-mock-ranking-once"
    fun gate(packageName: String, approval: String?, nonce: String?, phase: String?) {
        require(packageName == CLONE && approval == APPROVAL && nonce != null && Regex("[a-f0-9]{32}").matches(nonce) && phase in setOf("exercise", "restart"))
    }
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun digest(file: File): String? = if (file.isFile) digest(file.readBytes()) else null
}

/** Observation only: delegate the exact production catalog and HTTPS opener, enforce the approved live budget. */
private class RankingReadTrace(private val preview: TmdbCandidatePreview) {
    val loads = AtomicInteger()
    val attempts = AtomicInteger()
    @Volatile var readAllowed = false
    var catalog: CandidateCatalog? = null
    private val catalogField = TmdbCandidatePreview::class.java.getDeclaredField("catalog").apply { isAccessible = true }
    private val original = catalogField.get(preview) as TmdbCandidateCatalogRepository
    private val transport = TmdbCandidateCatalogRepository::class.java.getDeclaredField("transport").apply { isAccessible = true }.get(original) as TmdbAuthenticatedTransport
    private val client = TmdbAuthenticatedTransport::class.java.getDeclaredField("client").apply { isAccessible = true }.get(transport) as TmdbHttpClient
    private val openField = TmdbHttpClient::class.java.getDeclaredField("open").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") private val originalOpen = openField.get(client) as (URL) -> HttpURLConnection
    init {
        openField.set(client, { url: URL ->
            check(attempts.incrementAndGet() <= 48 && readAllowed)
            check(url.protocol == "https" && url.host == "api.themoviedb.org" && url.port == -1 && url.userInfo == null)
            originalOpen(url)
        })
        catalogField.set(preview, object : CandidateCatalogRepository {
            override suspend fun load(): CandidateCatalog {
                check(loads.incrementAndGet() <= 3 && readAllowed)
                return original.load().also { catalog = it }
            }
        })
    }
    fun restore() { catalog = null; catalogField.set(preview, original); openField.set(client, originalOpen) }
}

class TmdbRankingLivePreflightTest {
    @Test fun liveScopeRejectsMissingApprovalWrongPackageNonceAndPhase() {
        val nonce = "a".repeat(32)
        for (args in listOf(arrayOf("com.yingji.app", RankingLiveScope.APPROVAL, nonce, "exercise"),
            arrayOf(RankingLiveScope.CLONE, null, nonce, "exercise"), arrayOf(RankingLiveScope.CLONE, "read-only", nonce, "exercise"),
            arrayOf(RankingLiveScope.CLONE, RankingLiveScope.APPROVAL, "../", "exercise"), arrayOf(RankingLiveScope.CLONE, RankingLiveScope.APPROVAL, nonce, "save")))
            assertTrue(runCatching { RankingLiveScope.gate(args[0]!!, args[1], args[2], args[3]) }.isFailure)
        RankingLiveScope.gate(RankingLiveScope.CLONE, RankingLiveScope.APPROVAL, nonce, "exercise")
        RankingLiveScope.gate(RankingLiveScope.CLONE, RankingLiveScope.APPROVAL, nonce, "restart")
    }
}

/** Explicit manual only. Same Application/MainActivity/factories/HTTP/ranking; no repository or VM replacement. */
class TmdbHomeRankingLiveVerificationTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<YingJiApplication>()
    private fun scroll(tag: String) { ui.onNodeWithTag("movie_collection").performScrollToNode(hasTestTag(tag)) }
    // Inspect the existing navigation-owned model; never create an activity-scoped replacement.
    private fun homePreview(root: ViewModelStore): TmdbCredentialViewModel {
        fun models(store: ViewModelStore): List<Any> {
            val field = ViewModelStore::class.java.declaredFields.single { Map::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
            return (field.get(store) as Map<*, *>).values.filterNotNull()
        }
        val nested = models(root).filter { it.javaClass.simpleName == "NavControllerViewModel" }.flatMap { nav ->
            val field = nav.javaClass.getDeclaredField("viewModelStores").apply { isAccessible = true }
            (field.get(nav) as Map<*, *>).values.filterIsInstance<ViewModelStore>()
        }
        return (listOf(root) + nested).flatMap { models(it) }.filterIsInstance<TmdbCredentialViewModel>().single { it.rankingAvailable }
    }
    private fun count() = runBlocking { app.movieRepository.observeAll().first().size }
    private fun businessDigest(): String {
        val db = DatabaseModule.provideDatabase(app).openHelper.readableDatabase
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val rows = tables.flatMap { table ->
            require(Regex("[a-zA-Z_][a-zA-Z0-9_]*").matches(table))
            db.query("SELECT * FROM " + table).use { c -> buildList { while (c.moveToNext()) add(table + ":" + (0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "<null>" else c.getString(it) }) } }
        }.sorted()
        return RankingLiveScope.digest(rows.joinToString("\n").toByteArray())
    }
    @Test fun preflightCloneOnlyObservesIdleProductionHomeWithoutNetwork() {
        val args = InstrumentationRegistry.getArguments()
        RankingLiveScope.gate(app.packageName, args.getString("tmdbRankingApproved"), args.getString("tmdbNonce"), "exercise")
        var scenario: ActivityScenario<MainActivity>? = null; var trace: RankingReadTrace? = null; var passed = false
        try {
            val observer = RankingReadTrace(app.tmdbCandidatePreview); trace = observer
            scenario = ActivityScenario.launch(MainActivity::class.java)
            ui.waitUntil(15000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { check(homePreview(it.viewModelStore).rankingAvailable) }
            scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected()
            check(observer.loads.get() == 0 && observer.attempts.get() == 0); passed = true
        } catch (_: Throwable) { /* Never export app state or credentials in diagnostics. */ }
        finally { runCatching { scenario?.close() }; runCatching { trace?.restore() } }
        assertTrue("READONLY_IDLE_PREFLIGHT_FAILED", passed)
    }
    @Test fun actualHomeRealCandidatesSortLocallyAndClearWithoutHistoryCacheOrWorkWrites() {
        val args = InstrumentationRegistry.getArguments(); val nonce = args.getString("tmdbNonce"); val phase = args.getString("tmdbRankingPhase")
        RankingLiveScope.gate(app.packageName, args.getString("tmdbRankingApproved"), nonce, phase)
        val directory = File(app.filesDir, "tmdb-ranking-live").apply { check(exists() || mkdir()) }
        check(File(directory, nonce + "-" + phase + "-consumed").createNewFile())
        val stateFile = File(directory, nonce + "-state.json")
        val key = File(app.noBackupFilesDir, "tmdb-credentials/config.enc")
        val demo = File(app.filesDir, "datastore/ai_home_mock.preferences_pb")
        val keyBefore = RankingLiveScope.digest(key); val demoBefore = RankingLiveScope.digest(demo)
        val result = JSONObject().put("phase", phase).put("pid", Process.myPid()).put("passed", false)
            .put("isolatedApplicationId", true).put("realProfileTransferred", false).put("deepSeekCalled", false)
            .put("paidModelCalled", false).put("saveActions", 0).put("readActions", 0).put("screenshotsCaptured", false).put("metadataInReport", false)
        var scenario: ActivityScenario<MainActivity>? = null; var trace: RankingReadTrace? = null; var passed = false
        try {
            check(keyBefore != null && demoBefore != null); check(!File(app.noBackupFilesDir, "ai-byok/config.enc").exists())
            val beforeBusiness = businessDigest(); check(count() == 0)
            val observer = RankingReadTrace(app.tmdbCandidatePreview); trace = observer
            scenario = ActivityScenario.launch(MainActivity::class.java)
            ui.waitUntil(15000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
            lateinit var preview: TmdbCredentialViewModel
            scenario.onActivity { preview = homePreview(it.viewModelStore) }
            scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected(); check(observer.loads.get() == 0 && observer.attempts.get() == 0)
            fun cleared() { check(preview.preview.value.status == TmdbPreviewStatus.IDLE && preview.preview.value.rows.isEmpty()); check(preview.ranking.value.status == TmdbPreviewStatus.IDLE && preview.ranking.value.rows.isEmpty()); check(preview.pendingImport.value == null) }
            fun choose(tmdb: Boolean) { val tag = if (tmdb) "ai_source_tmdb" else "ai_source_mock"; scroll(tag); ui.onNodeWithTag(tag).performClick() }
            fun read() {
                observer.catalog = null; val before = observer.loads.get(); observer.readAllowed = true
                try {
                    scroll("tmdb_home_read"); ui.onNodeWithTag("tmdb_home_read").performClick(); result.put("readActions", result.getInt("readActions") + 1)
                    check(preview.ranking.value.status == TmdbPreviewStatus.IDLE && preview.ranking.value.rows.isEmpty())
                    ui.waitUntil(120000) { preview.preview.value.status !in setOf(TmdbPreviewStatus.IDLE, TmdbPreviewStatus.LOADING) }
                    check(preview.preview.value.status == TmdbPreviewStatus.SUCCESS && observer.loads.get() == before + 1)
                    check(preview.preview.value.rows.size in 1..6 && preview.preview.value.rows == requireNotNull(observer.catalog).candidates)
                } finally { observer.readAllowed = false }
            }
            fun rank() {
                val loads = observer.loads.get(); val attempts = observer.attempts.get(); val catalog = requireNotNull(observer.catalog)
                scroll("tmdb_home_rank"); ui.onNodeWithTag("tmdb_home_rank").assertIsEnabled().performClick()
                ui.waitUntil(10000) { preview.ranking.value.status !in setOf(TmdbPreviewStatus.IDLE, TmdbPreviewStatus.LOADING) }
                val state = preview.ranking.value; check(state.status in setOf(TmdbPreviewStatus.SUCCESS, TmdbPreviewStatus.EMPTY))
                val expected = runBlocking { TmdbMockRecommendationUseCase(app.mockAiHomeRepository::readIntegrationProfile)(catalog) }
                check(state.rows == expected.items.map { it.candidate } && state.rows.size <= 3)
                check(state.rows.map { it.identity }.distinct().size == state.rows.size)
                check(state.rows.all { row -> row in catalog.candidates && row.identity.sourceId == "tmdb" && Regex("(movie|tv):[1-9][0-9]*").matches(row.identity.externalId) && row.cache.state == CandidateCacheState.FRESH })
                for (row in state.rows) { scroll("tmdb_home_" + row.identity.externalId); ui.onNodeWithTag("tmdb_home_" + row.identity.externalId).assertIsDisplayed() }
                ui.waitForIdle(); check(observer.loads.get() == loads && observer.attempts.get() == attempts)
                check(count() == 0 && RankingLiveScope.digest(demo) == demoBefore)
                result.put("rankedCount", state.rows.size).put("rankActions", result.optInt("rankActions") + 1).put("rankingExtraCatalogLoads", 0).put("rankingExtraHttpAttempts", 0)
            }
            choose(true); cleared()
            if (phase == "exercise") {
                check(!stateFile.exists()); read(); rank(); rank()
                read(); rank(); result.put("rereadClearedOldRanking", true)
                val calls = observer.attempts.get(); choose(false); cleared(); observer.catalog = null; choose(true); cleared(); check(observer.attempts.get() == calls)
                result.put("sourceSwitchClearedWithoutRead", true)
                read(); rank(); observer.catalog = null
                ui.onNodeWithText("我的").performClick(); ui.waitUntil(10000) { preview.preview.value.status == TmdbPreviewStatus.IDLE }; cleared()
                ui.onNodeWithText("首页").performClick(); ui.waitUntil(10000) { ui.onAllNodesWithTag("movie_collection").fetchSemanticsNodes().isNotEmpty() }
                scroll("ai_source_mock"); ui.onNodeWithTag("ai_source_mock").assertIsSelected(); choose(true); cleared()
                scroll("tmdb_home_rank"); ui.onNodeWithTag("tmdb_home_rank").assertIsNotEnabled()
                result.put("leavingAndReturnClearedWithoutRead", true)
                stateFile.writeText(JSONObject().put("pid", Process.myPid()).put("keyDigest", keyBefore).put("demoDigest", demoBefore).put("businessDigest", beforeBusiness).toString())
            } else {
                val saved = JSONObject(stateFile.readText()); check(saved.getInt("pid") != Process.myPid())
                cleared(); scroll("tmdb_home_rank"); ui.onNodeWithTag("tmdb_home_rank").assertIsNotEnabled()
                check(saved.getString("keyDigest") == keyBefore && saved.getString("demoDigest") == demoBefore && saved.getString("businessDigest") == beforeBusiness)
                result.put("freshProcess", true).put("coldRestartDefaultMock", true).put("coldRestartIdleWithoutNetwork", true)
            }
            check(observer.loads.get() == if (phase == "exercise") 3 else 0)
            check(observer.attempts.get() in if (phase == "exercise") 1..48 else 0..0)
            check(count() == 0 && businessDigest() == beforeBusiness)
            check(RankingLiveScope.digest(demo) == demoBefore && RankingLiveScope.digest(key) == keyBefore)
            result.put("providerCatalogLoads", observer.loads.get()).put("actualHttpAttempts", observer.attempts.get()).put("httpAttemptsMeasured", true)
                .put("businessDataUnchanged", true).put("demoPreferencesAndHistoryUnchanged", true).put("encryptedConfigurationUnchanged", true)
                .put("noPersistentCandidateCache", true).put("noWorkImported", true).put("sameProductionChainWithReadCounters", true)
            passed = true
        } catch (_: Throwable) { result.put("problem", "READONLY_RANKING_UI_ASSERTION_FAILED") }
        finally {
            runCatching { scenario?.close() }; runCatching { trace?.restore() }
            result.put("passed", passed)
            File(directory, nonce + "-" + phase + ".json").writeText(result.toString())
        }
        assertTrue("READONLY_RANKING_LIVE_UI_FAILED", passed)
    }
}
