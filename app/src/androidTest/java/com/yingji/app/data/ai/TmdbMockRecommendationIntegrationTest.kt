package com.yingji.app.data.ai

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.database.*
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.usecase.AddMovieUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

/** Synthetic official-protocol facts, actual demo DataStore, ranking engine and isolated Room import. */
class TmdbMockRecommendationIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val folder = TemporaryFolder(context.cacheDir)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase
    private lateinit var movies: MovieRepository
    private lateinit var demo: MockAiHomeRepository
    private lateinit var file: File
    private var grant = TmdbSourcePermission(CandidateLicenseState.ACTIVE, "App-authored ranking fixture permission", true, true, true)
    private var calls = 0
    private class Memory : TmdbSecureCredentialStore {
        private var value: String? = null
        override suspend fun read() = TmdbCredentialRecord(value?.let(TmdbSecret::fromInput), 1000, "fixture")
        override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String { value = key?.useValue { it }; return "fixture" }
    }
    private val keys = TmdbCredentialRepository(Memory()) { 1000 }
    private val transport = object : TmdbCatalogTransport {
        override val isMock = false // Synthetic verified-source protocol responses; no socket or live credential.
        override fun revision() = "fixture"
        override fun isCurrent(revision: String) = revision == "fixture"
        override suspend fun read(request: TmdbCatalogRequest): String {
            calls++
            if (request.id == null) return if (request.kind == TmdbMediaKind.MOVIE) """{"page":1,"results":[{"id":7},{"id":8}]}""" else """{"page":1,"results":[{"id":7}]}"""
            return if (request.kind == TmdbMediaKind.TV) """{"id":7,"name":"虚构排序剧集","first_air_date":"2022-01-01","genres":[{"id":99,"name":"Synthetic documentary"}],"episode_run_time":[45]}"""
                else if (request.id == 7) """{"id":7,"title":"虚构排序喜剧","release_date":"2023-01-01","runtime":90,"genres":[{"id":35,"name":"Synthetic comedy"}]}"""
                else """{"id":8,"title":"虚构排序科幻","release_date":"2024-01-01","runtime":95,"genres":[{"id":878,"name":"Synthetic science fiction"}]}"""
        }
    }
    private fun reader() = TmdbCandidatePreview(TmdbCandidateCatalogRepository(transport, { grant }) { 1000 }, { 1000 })
    private fun importer() = TmdbImportUseCase(transport, keys, { grant }, CandidateImportRepository(db, movies), AddMovieUseCase(movies)) { 1000 }
    private fun input(candidate: CatalogCandidate) = RecommendedWorkInput(candidate.identity, true, candidate.title!!, 7, "既有分类", setOf(7))
    @Before fun open() = runBlocking<Unit> {
        DatabaseHealthMonitor.resetForTesting()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(); movies = MovieRepository(db)
        db.categoryDao().insert(Category(7, "既有分类", false)); db.tagDao().insert(Tag(7, "既有标签"))
        file = File(folder.root, "demo.preferences_pb")
        demo = MockAiHomeRepository(context, PreferenceDataStoreFactory.create(scope = scope) { file }, clock = { 1000 })
        demo.saveSetup(AiDemoSetup(preferences = mapOf("喜剧" to PreferenceDirection.LIKE, "科幻" to PreferenceDirection.DISLIKE)))
        demo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS) // Existing synthetic history must survive untouched.
        keys.save("TEST_ONLY_TMDB_ranking_integration")
    }
    @After fun close() = runBlocking<Unit> { scope.cancel(); scope.coroutineContext[Job]?.join(); db.close(); DatabaseHealthMonitor.resetForTesting() }
    @Test fun verifiedPreviewRanksThroughExistingEngineAndOnlyConfirmedSaveWritesWithoutHistoryOrCache() = runBlocking<Unit> {
        val history = demo.readManagement().history; val profile = demo.readIntegrationProfile(); val setup = demo.readSetup(); val storedPreferences = file.readBytes()
        val catalog = reader().readCatalog(); assertEquals(5, calls)
        val rank = TmdbMockRecommendationUseCase(demo::readIntegrationProfile, clock = { 1000 })
        val result = rank(catalog)
        assertTrue(result.mockHintsApplied); assertEquals(2, result.items.size); assertEquals("movie:7", result.items.first().candidate.identity.externalId)
        assertTrue(result.items.none { "科幻" in it.candidate.topics }); assertTrue(result.items.all { it.candidate.cache.state == CandidateCacheState.FRESH })
        assertEquals(5, calls); assertTrue(movies.observeAll().first().isEmpty())
        val selected = result.items.first().candidate
        assertTrue(runCatching { importer()(input(selected).copy(confirmed = false)) }.exceptionOrNull() is CandidateImportException)
        assertEquals(5, calls); assertTrue(movies.observeAll().first().isEmpty())
        val id = importer()(input(selected)); assertEquals(6, calls); assertEquals(1, movies.observeAll().first().size)
        assertEquals(selected.title, movies.observeById(id).first()!!.name); assertNull(movies.observeById(id).first()!!.posterPath)
        assertEquals(id, db.candidateImportDao().movieId("tmdb", "movie:7"))
        assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(id)); assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(id))
        assertEquals(history, demo.readManagement().history); assertEquals(profile, demo.readIntegrationProfile()); assertEquals(setup, demo.readSetup())
        assertArrayEquals(storedPreferences, file.readBytes())
        assertTrue(rank(catalog).mockHintsApplied); assertEquals(6, calls); assertEquals(history, demo.readManagement().history)
    }
    @Test fun displayRankingDoesNotUpgradeImportRightsAndRevocationInvalidatesTheSession() = runBlocking<Unit> {
        grant = grant.copy(importAllowed = false)
        val catalog = reader().readCatalog(); val before = calls; val version = grant
        val rank = TmdbMockRecommendationUseCase(demo::readIntegrationProfile, clock = { 1000 })
        val result = rank(catalog) { grant == version }; assertTrue(result.mockHintsApplied)
        assertTrue(runCatching { importer()(input(result.items.first().candidate)) }.exceptionOrNull() is CandidateImportException)
        assertEquals(before, calls); assertTrue(movies.observeAll().first().isEmpty())
        grant = grant.copy(state = CandidateLicenseState.REVOKED)
        assertEquals("STALE_PROFILE", (runCatching { rank(catalog) { grant == version } }.exceptionOrNull() as WorkerProblem).code)
        assertTrue(runCatching { reader().readCatalog() }.exceptionOrNull() is TmdbProblem)
        assertEquals(before, calls); assertTrue(movies.observeAll().first().isEmpty())
    }
}
