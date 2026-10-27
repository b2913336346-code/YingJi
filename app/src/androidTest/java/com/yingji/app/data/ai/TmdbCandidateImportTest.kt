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
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class TmdbCandidateImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "tmdb-import-" + UUID.randomUUID() + ".db"
    private lateinit var db: AppDatabase
    private lateinit var movies: MovieRepository
    private var grant = TmdbSourcePermission(CandidateLicenseState.ACTIVE, "App-authored import protocol fixture only", true, true, true)
    private val catalog = TmdbCandidateCatalogRepository(object : TmdbCatalogTransport {
        override val isMock = true
        override fun revision() = "fixture"
        override fun isCurrent(revision: String) = revision == "fixture"
        override suspend fun read(request: TmdbCatalogRequest): String = if (request.id == null) """{"page":1,"results":[{"id":7}]}"""
            else if (request.kind == TmdbMediaKind.MOVIE) """{"id":7,"title":"虚构电影入库样本","runtime":90}"""
            else """{"id":7,"name":"虚构剧集入库样本","episode_run_time":[45]}"""
    }, { grant }) { 100 }
    private fun useCase() = AddRecommendedWorkUseCase(catalog, CandidateImportRepository(db, movies), AddMovieUseCase(movies)) { 100 }
    private fun input(kind: String) = RecommendedWorkInput(CandidateIdentity("tmdb-protocol-mock", kind + ":7"), true,
        if (kind == "movie") "虚构电影入库样本" else "虚构剧集入库样本", 7, "已有分类", setOf(7), allowMock = true)
    @Before fun open() = runBlocking<Unit> {
        DatabaseHealthMonitor.resetForTesting(); db = Room.databaseBuilder(context, AppDatabase::class.java, name).build(); movies = MovieRepository(db)
        db.categoryDao().insert(Category(7, "已有分类", false)); db.tagDao().insert(Tag(7, "已有标签"))
    }
    @After fun close() { db.close(); check(context.deleteDatabase(name)); DatabaseHealthMonitor.resetForTesting() }
    @Test fun sameNumericMovieAndTvIdsImportSeparatelyThroughExistingBaseTransaction() = runBlocking<Unit> {
        val movie = useCase()(input("movie").copy(rating = 0.0)); val tv = useCase()(input("tv").copy(rating = 8.5, status = MovieStatus.WATCHING, currentEpisode = 1, totalEpisode = 12))
        assertNotEquals(movie, tv); assertEquals(2, movies.observeAll().first().size)
        assertEquals(movie, db.candidateImportDao().movieId("tmdb-protocol-mock", "movie:7")); assertEquals(tv, db.candidateImportDao().movieId("tmdb-protocol-mock", "tv:7"))
        for (id in listOf(movie, tv)) { assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(id)); assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(id)); assertNull(movies.observeById(id).first()!!.posterPath) }
        assertEquals(0.0, movies.observeById(movie).first()!!.rating!!, 0.0); assertEquals(8.5, movies.observeById(tv).first()!!.rating!!, 0.0)
        assertEquals(1, db.watchHistoryDao().getForMovie(tv).size)
        assertTrue(runCatching { useCase()(input("movie")) }.exceptionOrNull() is CandidateImportException)
        for (table in listOf("categories", "tags")) db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM " + table).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
    }
    @Test fun noConfirmationImportPermissionOrMockOptInNeverWrites() = runBlocking<Unit> {
        for (value in listOf(input("movie").copy(confirmed = false), input("movie").copy(allowMock = false), input("movie").copy(categoryId = 0))) {
            assertTrue(runCatching { useCase()(value) }.isFailure); assertTrue(movies.observeAll().first().isEmpty())
        }
        grant = grant.copy(importAllowed = false); assertTrue(runCatching { useCase()(input("movie")) }.exceptionOrNull() is CandidateImportException)
        assertTrue(movies.observeAll().first().isEmpty())
        grant = grant.copy(state = CandidateLicenseState.REVOKED); assertTrue(runCatching { useCase()(input("movie")) }.exceptionOrNull() is TmdbProblem)
        assertTrue(movies.observeAll().first().isEmpty())
    }
    @Test fun mappingFailureRollsBackRelationsAndWatchSessionAndCanRetry() = runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER tmdb_reject_mapping BEFORE INSERT ON ai_candidate_imports BEGIN SELECT RAISE(ABORT,'fixture failure'); END")
        assertTrue(runCatching { useCase()(input("tv").copy(status = MovieStatus.WATCHING)) }.isFailure)
        for (table in listOf("movies", "movie_categories", "movie_tags", "watch_history", "ai_candidate_imports"))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM " + table).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER tmdb_reject_mapping"); useCase()(input("tv")); assertEquals(1, movies.observeAll().first().size)
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
}
