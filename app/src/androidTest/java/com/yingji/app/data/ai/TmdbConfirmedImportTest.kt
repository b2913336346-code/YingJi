package com.yingji.app.data.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.database.*
import com.yingji.app.data.entity.Category
import com.yingji.app.data.entity.Tag
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.AddMovieUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Entirely synthetic transport and an owned isolated Room database. No socket or production key. */
class TmdbConfirmedImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "tmdb-confirmed-" + UUID.randomUUID() + ".db"
    private lateinit var db: AppDatabase
    private lateinit var movies: MovieRepository
    private var grant = TmdbSourcePermission(CandidateLicenseState.ACTIVE, "App-authored synthetic import fixture", true, true, true)
    private class Memory : TmdbSecureCredentialStore {
        var key: String? = null; var version = 0
        override suspend fun read() = TmdbCredentialRecord(key?.let(TmdbSecret::fromInput), 100, version.toString())
        override suspend fun replace(key: TmdbSecret?, savedAt: Long?): String { this.key = key?.useValue { it }; return (++version).toString() }
    }
    private val keys = TmdbCredentialRepository(Memory()) { 100 }
    private val calls = mutableListOf<TmdbCatalogRequest>()
    private var problem: TmdbProblemCode? = null
    private var wrongId = false
    private var afterRead: (suspend () -> Unit)? = null
    private val transport = object : TmdbCatalogTransport {
        override val isMock = false // Simulates the production protocol using app-authored response bodies only.
        override fun revision() = keys.state.value.revision.orEmpty()
        override fun isCurrent(revision: String) = revision == this.revision() && !keys.state.value.saving
        override suspend fun read(request: TmdbCatalogRequest): String {
            assertNotNull("DISCOVERY_MUST_NOT_BE_READ", request.id); calls.add(request)
            afterRead?.invoke(); problem?.let { throw TmdbProblem(it) }
            val id = if (wrongId) 999 else request.id
            return if (request.kind == TmdbMediaKind.MOVIE) """{"id":$id,"title":"虚构确认电影","release_date":"2024-01-01","runtime":90,"poster_path":"/fictionalMoviePoster.jpg"}"""
                else """{"id":$id,"name":"虚构确认剧集","first_air_date":"2023-01-01","episode_run_time":[45],"poster_path":"/fictionalTvPoster.png"}"""
        }
    }
    private fun service(store: CandidateImportStore = CandidateImportRepository(db, movies)) = TmdbImportUseCase(transport, keys, { grant }, store, AddMovieUseCase(movies)) { 100 }
    private fun input(kind: String = "movie") = RecommendedWorkInput(CandidateIdentity("tmdb", kind + ":7"), true,
        if (kind == "movie") "虚构确认电影" else "虚构确认剧集", 7, "既有分类", setOf(7), rating = 8.5)
    private fun openDb() { db = Room.databaseBuilder(context, AppDatabase::class.java, name).build(); movies = MovieRepository(db) }
    @Before fun open() = runBlocking<Unit> { DatabaseHealthMonitor.resetForTesting(); openDb(); keys.save("TEST_ONLY_TMDB_confirmed"); db.categoryDao().insert(Category(7, "既有分类", false)); db.tagDao().insert(Tag(7, "既有标签")) }
    @After fun close() { db.close(); check(context.deleteDatabase(name)); DatabaseHealthMonitor.resetForTesting() }
    private fun empty() = runBlocking {
        for (table in listOf("movies", "movie_categories", "movie_tags", "watch_history", "ai_candidate_imports"))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM " + table).use { assertTrue(it.moveToFirst()); assertEquals(table, 0, it.getInt(0)) }
    }
    @Test fun oneTypedDetailPerSaveUsesExistingTransactionAndPreservesLocalRelations() = runBlocking<Unit> {
        val movie = service()(input()); val tv = service()(input("tv").copy(status = MovieStatus.WATCHING, currentEpisode = 1, totalEpisode = 12))
        assertNotEquals(movie, tv); assertEquals(listOf(TmdbCatalogRequest(TmdbMediaKind.MOVIE, 7), TmdbCatalogRequest(TmdbMediaKind.TV, 7)), calls)
        assertEquals(2, movies.observeAll().first().size)
        assertEquals(movie, db.candidateImportDao().movieId("tmdb", "movie:7")); assertEquals(tv, db.candidateImportDao().movieId("tmdb", "tv:7"))
        for (id in listOf(movie, tv)) { assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(id)); assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(id)); assertNull(movies.observeById(id).first()!!.posterPath); assertEquals(8.5, movies.observeById(id).first()!!.rating!!, 0.0) }
        assertEquals(1, db.watchHistoryDao().getForMovie(tv).size)
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
    @Test fun missingConfirmationCategoryWrongSourceAndInvalidIdentityNeverReadOrWrite() = runBlocking<Unit> {
        val invalid = listOf(input().copy(confirmed = false), input().copy(categoryId = 0), input().copy(identity = CandidateIdentity("mock", "movie:7")), input().copy(allowMock = true), input().copy(identity = CandidateIdentity("tmdb", "movie:0")), input().copy(identity = CandidateIdentity("tmdb", "movie:2147483648")), input().copy(posterPath = "https://example.invalid/poster"))
        for (value in invalid) assertTrue(runCatching { service()(value) }.exceptionOrNull() is CandidateImportException)
        assertTrue(calls.isEmpty()); empty()
    }
    @Test fun unavailableImportPermissionRevokedAndExpiredGrantsNeverReachTransport() = runBlocking<Unit> {
        for (value in listOf(grant.copy(importAllowed = false), grant.copy(state = CandidateLicenseState.REVOKED), grant.copy(validUntil = 100))) {
            grant = value; assertTrue(runCatching { service()(input()) }.exceptionOrNull() is CandidateImportException); empty()
        }; assertTrue(calls.isEmpty())
    }
    @Test fun authenticationNetworkRateLimitMissingAndMalformedDetailsFailWithoutWritingAndCanRetry() = runBlocking<Unit> {
        for (code in listOf(TmdbProblemCode.UNAUTHORIZED, TmdbProblemCode.OFFLINE, TmdbProblemCode.RATE_LIMITED, TmdbProblemCode.NOT_FOUND)) {
            problem = code; val before = calls.size; val error = runCatching { service()(input()) }.exceptionOrNull()
            assertTrue(error is CandidateImportException); assertFalse(error!!.message.orEmpty().contains("TEST_ONLY")); assertEquals(before + 1, calls.size); empty()
        }
        problem = null; wrongId = true; assertTrue(runCatching { service()(input()) }.isFailure); empty()
        wrongId = false; service()(input()); assertEquals(1, movies.observeAll().first().size)
    }
    @Test fun duplicateMappingAndRenamedSameTitleAreBlockedWithoutPartialRows() = runBlocking<Unit> {
        val id = service()(input()); assertTrue(runCatching { service()(input().copy(name = "用户重命名")) }.isFailure)
        assertTrue(runCatching { service()(input().copy(identity = CandidateIdentity("tmdb", "movie:8"), name = "用户另一个名称")) }.isFailure)
        assertEquals(1, movies.observeAll().first().size); assertEquals(id, db.candidateImportDao().movieId("tmdb", "movie:7")); assertNull(db.candidateImportDao().movieId("tmdb", "movie:8"))
    }
    @Test fun revokedCredentialOrPermissionDuringDetailReadNeverWrites() = runBlocking<Unit> {
        afterRead = { keys.delete() }; assertTrue(runCatching { service()(input()) }.isFailure); empty()
        val count = calls.size; assertTrue(runCatching { service()(input()) }.isFailure); assertEquals(count, calls.size)
        keys.save("TEST_ONLY_TMDB_replaced"); afterRead = { grant = grant.copy(importAllowed = false) }
        assertTrue(runCatching { service()(input()) }.isFailure); empty()
    }
    @Test fun revocationInsideTransactionAndMappingFailureRollbackWorkAndAllRelations() = runBlocking<Unit> {
        val delegate = CandidateImportRepository(db, movies)
        val revoking = object : CandidateImportStore by delegate {
            override suspend fun checkBeforeInsert(identity: CandidateIdentity, originalTitle: String, savedTitle: String) {
                delegate.checkBeforeInsert(identity, originalTitle, savedTitle); keys.delete()
            }
        }
        assertTrue(runCatching { service(revoking)(input("tv").copy(status = MovieStatus.WATCHING)) }.isFailure); empty()
        keys.save("TEST_ONLY_TMDB_retry"); db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_tmdb_confirmed BEFORE INSERT ON ai_candidate_imports BEGIN SELECT RAISE(ABORT,'synthetic mapping failure'); END")
        assertTrue(runCatching { service()(input("tv").copy(status = MovieStatus.WATCHING)) }.isFailure); empty()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_tmdb_confirmed"); service()(input("tv")); assertEquals(1, movies.observeAll().first().size)
    }
    @Test fun reopenedDatabaseRetainsIdentityAndRelationsAndStillRejectsDuplicate() = runBlocking<Unit> {
        val id = service()(input()); db.close(); openDb()
        assertEquals(id, db.candidateImportDao().movieId("tmdb", "movie:7")); assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(id)); assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(id))
        assertTrue(runCatching { service()(input()) }.isFailure); assertEquals(1, movies.observeAll().first().size)
        db.openHelper.readableDatabase.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); assertEquals(5, it.getInt(0)) }
    }
}
