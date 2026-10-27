package com.yingji.app.data.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.database.*
import com.yingji.app.data.entity.*
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.ai.*
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.usecase.AddMovieUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.util.UUID

class CandidateImportTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="a4-import-"+UUID.randomUUID()+".db"
    private lateinit var db: AppDatabase
    private lateinit var movies: MovieRepository
    private val catalog=MockCandidateCatalogRepository()
    private fun open() = Room.databaseBuilder(context,AppDatabase::class.java,name).build()
    private fun useCase(source: CandidateCatalogRepository=catalog)=AddRecommendedWorkUseCase(source,CandidateImportRepository(db,movies),AddMovieUseCase(movies))
    private fun input(id: String="mock-0")=RecommendedWorkInput(CandidateIdentity(MockCandidateCatalogRepository.SOURCE_ID,id),true,
        "Mock 演示作品 "+(id.substringAfter('-').toInt()+1),7,"分类",allowMock=true)
    @Before fun setup() = runBlocking<Unit> {
        DatabaseHealthMonitor.resetForTesting();db=open();movies=MovieRepository(db)
        db.categoryDao().insert(Category(7,"分类",false));db.tagDao().insert(Tag(7,"标签"))
    }
    @After fun cleanup() { db.close();context.deleteDatabase(name);DatabaseHealthMonitor.resetForTesting() }
    @Test fun confirmedImportUsesBaseUseCaseRelationsRatingAndWatchSession() = runBlocking<Unit> {
        val id=useCase()(input().copy(tagIds=setOf(7),rating=8.5,status=MovieStatus.WATCHING,currentEpisode=1,totalEpisode=12))
        val saved=movies.observeById(id).first()!!
        assertEquals("观看中",saved.status);assertEquals(8.5,saved.rating!!,0.0);assertNull(saved.posterPath)
        assertEquals(listOf(7L),db.categoryDao().getCategoryIdsForMovie(id));assertEquals(listOf(7L),db.tagDao().getTagIdsForMovie(id))
        assertEquals(id,db.candidateImportDao().movieId(input().identity.sourceId,input().identity.externalId))
        assertEquals(1,db.watchHistoryDao().getForMovie(id).size)
    }
    @Test fun missingConfirmationCategoryCandidateAndRemotePosterDoNotWrite() = runBlocking<Unit> {
        val bad=listOf(input().copy(confirmed=false),input().copy(categoryId=0),input().copy(categoryName=""),
            input().copy(identity=CandidateIdentity("untrusted","fiction")),input().copy(posterPath="https://invalid/poster.jpg"),
            input().copy(rating=7.2),input().copy(allowMock=false),input().copy(currentEpisode=-1))
        for (value in bad) {
            assertTrue(runCatching { useCase()(value) }.exceptionOrNull() is CandidateImportException)
            assertTrue(movies.observeAll().first().isEmpty())
        }
    }
    @Test fun licenseRevokedBetweenRecommendationAndSavePreventsImport() = runBlocking<Unit> {
        val shown=catalog.load();assertEquals(3,shown.candidates.size)
        val revoked=object:CandidateCatalogRepository { override suspend fun load()=shown.copy(sources=shown.sources.map {
            it.copy(license=it.license.copy(state=CandidateLicenseState.REVOKED)) }) }
        assertTrue(runCatching { useCase(revoked)(input()) }.exceptionOrNull() is CandidateImportException)
        assertTrue(movies.observeAll().first().isEmpty())
    }
    @Test fun repeatedConcurrentCandidateImportsNeverDuplicateOrOverwriteExistingState() = runBlocking<Unit> {
        val results=coroutineScope { (1..8).map { async { runCatching { useCase()(input().copy(rating=9.0)) } } }.awaitAll() }
        assertEquals(1,results.count { it.isSuccess });assertEquals(7,results.count { it.exceptionOrNull() is CandidateImportException })
        val before=movies.observeAll().first().single()
        assertTrue(runCatching { useCase()(input().copy(name="改名也不能重复",status=MovieStatus.FINISHED,rating=0.0)) }.exceptionOrNull() is CandidateImportException)
        assertEquals(listOf(before),movies.observeAll().first());assertTrue(db.watchHistoryDao().getForMovie(before.id).isEmpty())
    }
    @Test fun legacySameNameBlocksAmbiguousImportWithoutChangingManualDuplicatePolicy() = runBlocking<Unit> {
        val manual=AddMovieUseCase(movies)
        val first=manual("  mock  演示作品 1 ","分类",MovieStatus.FINISHED,0,null,null,7,emptySet(),4.0)
        val before=movies.observeById(first).first()
        assertTrue(runCatching { useCase()(input()) }.exceptionOrNull() is CandidateImportException)
        assertEquals(before,movies.observeById(first).first());assertEquals(1,movies.observeAll().first().size)
        manual("  mock  演示作品 1 ","分类",MovieStatus.WANT_TO_WATCH,0,null,null,7,emptySet())
        assertEquals(2,movies.observeAll().first().size)
    }
    @Test fun mappingWriteFailureRollsBackMovieRelationsAndSessionThenRetrySucceeds() = runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER a4_reject_mapping BEFORE INSERT ON ai_candidate_imports BEGIN SELECT RAISE(ABORT,'A4 injected failure'); END")
        assertTrue(runCatching { useCase()(input().copy(status=MovieStatus.WATCHING,tagIds=setOf(7))) }.isFailure)
        for (table in listOf("movies","watch_history","movie_categories","movie_tags","ai_candidate_imports")) {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM "+table).use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
        }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER a4_reject_mapping")
        useCase()(input());assertEquals(1,movies.observeAll().first().size)
    }
    @Test fun orphanCategoryAndTagRollbackEverythingAndOptionalTagsRemainOptional() = runBlocking<Unit> {
        assertTrue(runCatching { useCase()(input().copy(categoryId=999)) }.isFailure)
        assertTrue(runCatching { useCase()(input().copy(tagIds=setOf(999))) }.isFailure)
        assertTrue(movies.observeAll().first().isEmpty())
        val id=useCase()(input());assertTrue(db.tagDao().getTagIdsForMovie(id).isEmpty())
        assertNull(movies.observeById(id).first()!!.rating)
    }
    @Test fun identitySurvivesReopenAndDeletingMovieReleasesOnlyItsMapping() = runBlocking<Unit> {
        val id=useCase()(input());val original=movies.observeById(id).first()!!
        db.close();db=open();movies=MovieRepository(db)
        assertEquals(id,db.candidateImportDao().movieId(input().identity.sourceId,"mock-0"))
        assertTrue(runCatching { useCase()(input()) }.exceptionOrNull() is CandidateImportException)
        assertEquals(original,movies.observeById(id).first())
        movies.delete(original);assertNull(db.candidateImportDao().movieId(input().identity.sourceId,"mock-0"))
        useCase()(input());assertEquals(1,movies.observeAll().first().size)
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
}
