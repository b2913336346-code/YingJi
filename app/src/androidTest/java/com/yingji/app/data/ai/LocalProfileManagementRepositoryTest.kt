package com.yingji.app.data.ai

import com.yingji.app.data.database.DatabaseHealthMonitor
import com.yingji.app.domain.ai.*
import com.yingji.app.presentation.ai.AiTestStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class LocalProfileManagementRepositoryTest {
    private lateinit var f: LocalProfileTestFixture
    @Before fun setup() { DatabaseHealthMonitor.resetForTesting(); f = LocalProfileTestFixture() }
    @After fun close() { f.close() }
    @Test fun sameIdCategoryAndTagAreIndependentAndRemovingPreferenceRestoresEnabledInference() = runBlocking<Unit> {
        f.seed(); val before = f.tables(); val snapshot = f.manager.observe().first()
        assertTrue(snapshot.features.any { it.id == "category:7" }); assertTrue(snapshot.features.any { it.id == "tag:7" })
        f.manager.setPreference(f.category, PreferenceDirection.DISLIKE)
        assertEquals(PreferenceDirection.DISLIKE, f.profiles.getProfile().effectiveDirections[f.category.id])
        assertEquals(PreferenceDirection.DISLIKE, f.profiles.getProfile().effectiveDirections[f.tag.id])
        f.manager.removePreference(f.tag.id)
        assertEquals(PreferenceDirection.LIKE, f.profiles.getProfile().effectiveDirections[f.tag.id]); assertEquals(before, f.tables())
    }
    @Test fun sourceSwitchesCorrectionAndUndoPersistAndWatchingIsNeutral() = runBlocking<Unit> {
        f.seed(); val first = f.profiles.getProfile()
        assertTrue(first.inferredPreferences.any { it.source == ProfileSource.BEHAVIOR && it.completedWatches == 1 && it.direction == null })
        val changed = f.manager.setSources(ProfileSourceSettings(false, false, true))
        assertTrue(changed.token.profileVersion > first.token.profileVersion); assertTrue(changed.inferredPreferences.all { it.source == ProfileSource.FEEDBACK })
        f.manager.correct(f.topic, true); assertTrue(f.profiles.getProfile().inferredPreferences.isEmpty())
        assertTrue(f.manager.observe().first().features.any { it.id == f.topic.id })
        f.manager.correct(f.topic, false); assertEquals(f.topic.id, f.profiles.getProfile().inferredPreferences.single().feature.id)
        assertEquals(1, f.db.aiProfileDao().feedback().size)
    }
    @Test fun resetOnlyClearsRealAiRowsAndRestoresDefaultsWithoutChangingLibraryMockUsageOrPoster() = runBlocking<Unit> {
        f.seed(); f.manager.correct(f.tag, true); val before = f.tables(); val bytes = f.poster!!.readBytes()
        val demoStore = AiTestStore(); val usage = LocalAiUsageRepository(f.context, AiTestStore())
        val demo = MockAiHomeRepository(f.context, demoStore, localUsage = usage)
        demo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.DISLIKE))); demo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS)
        usage.task {}; val demoBefore = demoStore.data.value; val usageBefore = usage.snapshot()
        val token = f.profiles.getProfile().token; val reset = f.manager.reset()
        assertEquals(ProfileSourceSettings(), reset.settings); assertTrue(reset.explicitPreferences.isEmpty()); assertTrue(reset.inferredPreferences.isEmpty()); assertTrue(reset.suppressedFeatureIds.isEmpty())
        assertTrue(reset.token.profileVersion > token.profileVersion); assertTrue(f.db.aiProfileDao().feedback().isEmpty())
        assertEquals(reset, f.profiles.getProfile()); assertEquals(before, f.tables()); assertArrayEquals(bytes, f.poster!!.readBytes())
        assertEquals(demoBefore, demoStore.data.value); assertEquals(usageBefore, usage.snapshot())
    }
    @Test fun failedResetRollsBackEveryRealAiTableAndSourceThenRetrySucceeds() = runBlocking<Unit> {
        f.seed(); f.manager.correct(f.tag, true); val before = f.tables(includeAi = true)
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_real_profile_reset BEFORE DELETE ON ai_profile_feedback BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { f.manager.reset() }.exceptionOrNull() is ProfileStorageException)
        assertEquals(before, f.tables(includeAi = true))
        f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_real_profile_reset"); DatabaseHealthMonitor.resetForTesting()
        assertTrue(f.manager.reset().explicitPreferences.isEmpty()); assertEquals(ProfileSourceSettings(), f.profiles.getProfile().settings)
    }
    @Test fun freshEmptyProfileHasDefaultsAndNoImaginaryFeedback() = runBlocking<Unit> {
        val snapshot = f.manager.observe().first()
        assertEquals(ProfileSourceSettings(), snapshot.profile.settings); assertEquals(5, snapshot.features.size)
        assertTrue(snapshot.profile.explicitPreferences.isEmpty()); assertTrue(snapshot.profile.inferredPreferences.isEmpty()); assertTrue(f.db.aiProfileDao().feedback().isEmpty())
    }
}
