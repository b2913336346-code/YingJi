package com.yingji.app.data.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yingji.app.data.database.AppDatabase
import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.data.entity.*
import com.yingji.app.data.repository.MovieRepository
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ProfileRepository
    private lateinit var source: FakeLibrary
    private val feature = ProfileFeature.tag(7, "科幻")

    @Before fun setUp() {
        DatabaseHealthMonitor.resetForTesting()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        source = FakeLibrary()
        repo = ProfileRepository(db, source)
    }
    @After fun tearDown() { db.close(); DatabaseHealthMonitor.resetForTesting() }

    @Test fun defaultsNeverReadUnconsentedRatingsOrBehavior() = runBlocking {
        val value = repo.getProfile()
        assertEquals(ProfileSourceSettings(), value.settings)
        assertTrue(value.explicitPreferences.isEmpty())
        assertTrue(value.inferredPreferences.isEmpty())
        assertEquals(0, source.ratingReads)
        assertEquals(0, source.behaviorReads)
        assertEquals(ProfileToken(0, 0), value.token)
    }

    @Test fun allEightSourceCombinationsGateReadsAndKeepOriginalFeedback() = runBlocking {
        repo.recordFeedback("one", feature, PreferenceDirection.LIKE)
        for (mask in 0..7) {
            val settings = ProfileSourceSettings(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0)
            source.ratingReads = 0; source.behaviorReads = 0
            val value = repo.setSources(settings)
            assertEquals(settings, value.settings)
            assertEquals(settings.ratingsEnabled, source.ratingReads > 0)
            assertEquals(settings.behaviorEnabled, source.behaviorReads > 0)
            assertEquals(settings.ratingsEnabled, value.inferredPreferences.any { it.source == ProfileSource.RATING })
            assertEquals(settings.behaviorEnabled, value.inferredPreferences.any { it.source == ProfileSource.BEHAVIOR })
            assertEquals(settings.feedbackEnabled, value.inferredPreferences.any { it.source == ProfileSource.FEEDBACK })
            assertEquals(1, db.aiProfileDao().feedback().size)
        }
    }

    @Test fun explicitEditsSuppressionAndUndoSurviveRecomputation() = runBlocking {
        repo.setSources(ProfileSourceSettings(ratingsEnabled = true))
        assertEquals(PreferenceDirection.LIKE, repo.getProfile().effectiveDirections[feature.id])
        repo.setExplicitPreference(feature, PreferenceDirection.DISLIKE)
        assertEquals(PreferenceDirection.DISLIKE, repo.getProfile().effectiveDirections[feature.id])
        repo.setExplicitPreference(feature, PreferenceDirection.LIKE)
        assertEquals(1, repo.getProfile().explicitPreferences.size)
        repo.removeExplicitPreference(feature.id)
        repo.suppressInference(feature)
        source.ratings = listOf(RatingFeatureEvidence(1, feature, 9.0))
        val suppressed = repo.getProfile()
        assertFalse(suppressed.effectiveDirections.containsKey(feature.id))
        assertTrue(suppressed.inferredPreferences.isEmpty())
        assertEquals(setOf(feature.id), suppressed.suppressedFeatureIds)
        repo.undoSuppression(feature.id)
        assertEquals(PreferenceDirection.LIKE, repo.getProfile().effectiveDirections[feature.id])
    }

    @Test fun sourceAndProfileChangesInvalidateTokensWhileNoOpsKeepVersions() = runBlocking {
        val first = repo.getProfile()
        val edited = repo.setExplicitPreference(feature, PreferenceDirection.LIKE)
        assertFalse(repo.isCurrent(first.token))
        assertTrue(repo.isCurrent(edited.token))
        assertEquals(edited.token, repo.setExplicitPreference(feature, PreferenceDirection.LIKE).token)
        val enabled = repo.setSources(ProfileSourceSettings(ratingsEnabled = true))
        assertEquals(1, enabled.token.sourceSettingsVersion)
        assertFalse(repo.isCurrent(edited.token))
        source.ratings = listOf(RatingFeatureEvidence(1, feature, 4.0))
        val rescored = repo.getProfile()
        assertTrue(rescored.token.profileVersion > enabled.token.profileVersion)
        assertEquals(enabled.token.sourceSettingsVersion, rescored.token.sourceSettingsVersion)
        repo.removeExplicitPreference(feature.id)
        assertEquals(PreferenceDirection.DISLIKE, repo.getProfile().effectiveDirections[feature.id])
    }

    @Test fun rapidRepeatedFeedbackAndExplicitWritesDoNotDuplicateEvidence() = runBlocking {
        coroutineScope { (1..12).map { async(Dispatchers.IO) {
            repo.recordFeedback("same", feature, PreferenceDirection.LIKE)
            repo.setExplicitPreference(feature, PreferenceDirection.DISLIKE)
        } }.awaitAll() }
        val value = repo.getProfile()
        assertEquals(1, value.explicitPreferences.size)
        assertEquals(1, value.inferredPreferences.single().evidenceCount)
        assertEquals(1, db.aiProfileDao().feedback().size)
    }

    @Test fun failedStateWriteRollsBackPreferencesInferenceAndVersionThenRetryWorks() = runBlocking {
        val before = repo.getProfile()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_ai BEFORE INSERT ON ai_profile_state BEGIN SELECT RAISE(ABORT,'injected'); END")
        try {
            repo.setExplicitPreference(feature, PreferenceDirection.LIKE)
            fail("Failure was not reported")
        } catch (error: ProfileStorageException) { assertTrue(error.message!!.contains("保留原有数据")) }
        assertEquals(before, repo.getProfile())
        assertTrue(db.aiProfileDao().explicit().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_ai")
        assertEquals(PreferenceDirection.LIKE, repo.setExplicitPreference(feature, PreferenceDirection.LIKE).effectiveDirections[feature.id])
    }

    @Test fun failedAuthorizedReadRollsBackSourceSettingsAndNeverUsesStaleInference() = runBlocking {
        val before = repo.getProfile()
        source.failRatings = true
        try { repo.setSources(ProfileSourceSettings(ratingsEnabled = true)); fail("Read failure hidden") }
        catch (_: ProfileStorageException) { }
        assertEquals(before, repo.getProfile())
        source.failRatings = false
        repo.setSources(ProfileSourceSettings(ratingsEnabled = true))
        source.failRatings = true
        val off = repo.setSources(ProfileSourceSettings())
        assertFalse(off.settings.ratingsEnabled)
        assertTrue(off.inferredPreferences.isEmpty())
    }

    @Test fun profileOperationsNeverChangeTheBaseLibraryOrConfuseCategoryAndTagIds() = runBlocking {
        db.categoryDao().insert(Category(7, "电影", false))
        db.tagDao().insert(Tag(7, "科幻"))
        val movies = MovieRepository(db)
        val id = movies.insertWithRelations(Movie(name="原始作品",type="电影",status="想看",createTime=1,rating=8.5),7,setOf(7))
        val before = movies.observeAll().first()
        val real = ProfileRepository(db, ObserveLibraryRepository(movies))
        val value = real.setSources(ProfileSourceSettings(ratingsEnabled=true,behaviorEnabled=true))
        assertEquals(setOf("category:7","tag:7"), value.inferredPreferences.map { it.feature.id }.toSet())
        real.suppressInference(ProfileFeature.tag(7,"科幻"))
        real.setExplicitPreference(ProfileFeature.category(7,"电影"),PreferenceDirection.DISLIKE)
        real.setSources(ProfileSourceSettings())
        assertEquals(before, movies.observeAll().first())
        assertEquals(listOf(7L), db.categoryDao().getCategoryIdsForMovie(id))
        assertEquals(listOf(7L), db.tagDao().getTagIdsForMovie(id))
        assertEquals("电影", db.categoryDao().observeById(7).first()!!.name)
        assertEquals("科幻", db.tagDao().observeById(7).first()!!.name)
    }

    @Test fun activeAndWantToWatchRecordsNeverCreateBehaviorLikeSignals() = runBlocking {
        db.categoryDao().insert(Category(7,"电影",false))
        val movies = MovieRepository(db)
        val id = movies.insertWithRelations(Movie(name="观看",type="电影",status="想看",createTime=1),7,emptySet())
        db.watchHistoryDao().insert(WatchHistory(movieId=id,startTime=100,endTime=200,duration=100,watchNumber=1))
        db.watchHistoryDao().insert(WatchHistory(movieId=id,startTime=300,endTime=500,duration=200,watchNumber=2))
        db.watchHistoryDao().insert(WatchHistory(movieId=id,startTime=600,watchNumber=3))
        val real = ProfileRepository(db, ObserveLibraryRepository(movies))
        val value = real.setSources(ProfileSourceSettings(behaviorEnabled=true))
        val evidence = value.inferredPreferences.single()
        assertEquals(2, evidence.completedWatches)
        assertEquals(1, evidence.repeatWatches)
        assertNull(evidence.direction)
        assertTrue(value.effectiveDirections.isEmpty())
    }

    @Test fun liveLibraryChangesAndRatingRemovalRefreshTheObservedProfile() = runBlocking {
        db.categoryDao().insert(Category(7,"电影",false))
        val movies = MovieRepository(db)
        val id = movies.insertWithRelations(Movie(name="更新",type="电影",status="想看",createTime=1,rating=9.0),7,emptySet())
        val real = ProfileRepository(db, ObserveLibraryRepository(movies))
        real.setSources(ProfileSourceSettings(ratingsEnabled=true))
        val values = Channel<ProfileSnapshot>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { real.observeProfile().collect { values.send(it) } }
        try {
            val first = withTimeout(10000) { values.receive() }
            assertEquals(PreferenceDirection.LIKE, first.effectiveDirections["category:7"])
            val original = movies.observeById(id).first()!!
            movies.update(original.copy(rating=2.0))
            val low = withTimeout(10000) { generateSequenceOfSnapshots(values) { it.effectiveDirections["category:7"] == PreferenceDirection.DISLIKE } }
            assertTrue(low.token.profileVersion > first.token.profileVersion)
            movies.update(original.copy(rating=null))
            val removed = withTimeout(10000) { generateSequenceOfSnapshots(values) { it.inferredPreferences.isEmpty() } }
            assertTrue(removed.token.profileVersion > low.token.profileVersion)
        } finally { job.cancelAndJoin(); values.close() }
    }

    private suspend fun generateSequenceOfSnapshots(channel: Channel<ProfileSnapshot>, predicate: (ProfileSnapshot)->Boolean): ProfileSnapshot {
        while (true) { val value = channel.receive(); if (predicate(value)) return value }
    }

    private class FakeLibrary : ProfileLibrarySource {
        private val feature = ProfileFeature.tag(7,"科幻")
        var ratings = listOf(RatingFeatureEvidence(1,feature,8.0))
        var ratingReads=0; var behaviorReads=0; var failRatings=false
        override suspend fun ratings(): List<RatingFeatureEvidence> {
            ratingReads++; check(!failRatings) { "injected rating read failure" }; return ratings
        }
        override suspend fun behavior(): List<BehaviorFeatureEvidence> {
            behaviorReads++; return listOf(BehaviorFeatureEvidence(1,feature,2,1))
        }
        override fun changes(settings: ProfileSourceSettings): Flow<Unit> = flowOf(Unit)
    }
}
