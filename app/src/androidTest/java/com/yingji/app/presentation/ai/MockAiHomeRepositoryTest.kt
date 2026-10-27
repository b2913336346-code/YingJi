package com.yingji.app.presentation.ai

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.yingji.app.data.ai.MockAiHomeRepository
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MockAiHomeRepositoryTest {
    @get:Rule val folder = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)
    @Test fun completionAndPreferencesAreAtomicAndFailedSaveLeavesDefaults() = runBlocking<Unit> {
        val repo = AiTestRepository()
        assertEquals(AiDemoSetup(), repo.readSetup())
        repo.store.failWrites = true
        try { repo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.DISLIKE), sources = ProfileSourceSettings(true, true, false))); fail() }
        catch (expected: IOException) { assertEquals(AiDemoSetup(), repo.readSetup()) }
        repo.store.failWrites = false
        val saved = repo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.DISLIKE), sources = ProfileSourceSettings(true, false, false)))
        assertTrue(saved.completed); assertEquals(1, repo.store.writes)
        assertEquals(PreferenceDirection.DISLIKE, saved.preferences["科幻"])
        assertEquals(ProfileSourceSettings(true, false, false), saved.sources)
    }
    @Test fun exclusionsAreNeverRelaxedAndEmptyResultsAreNotPadded() = runBlocking<Unit> {
        val repo = AiTestRepository()
        repo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.DISLIKE)))
        val filtered = repo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS)
        assertEquals(2, filtered.size); assertFalse(filtered.any { it.topic == "科幻" })
        assertTrue(filtered.all { it.title.startsWith("Mock 演示作品") && it.reason.contains("演示") })
        repo.saveSetup(AiDemoSetup(preferences = listOf("科幻", "喜剧", "纪录片").associateWith { PreferenceDirection.DISLIKE }))
        assertTrue(repo.recommend(AiMode.TONIGHT, "", MockScenario.SUCCESS).isEmpty())
    }
    @Test fun localSortingUsesExplicitPreferenceBeforeFeedbackAndSourceDisableRetainsRawFeedback() = runBlocking<Unit> {
        val repo=AiTestRepository()
        repo.saveSetup(AiDemoSetup(preferences=mapOf("喜剧" to PreferenceDirection.LIKE)))
        repo.saveFeedback("mock-0",AiFeedback.LIKE)
        assertEquals("mock-1",repo.recommend(AiMode.FOR_YOU,"",MockScenario.SUCCESS).first().id)
        repo.saveSetup(AiDemoSetup())
        assertEquals("mock-0",repo.recommend(AiMode.FOR_YOU,"",MockScenario.SUCCESS).first().id)
        repo.saveFeedback("mock-2",AiFeedback.LIKE)
        repo.saveFeedback("mock-0",AiFeedback.DISLIKE)
        assertEquals("mock-2",repo.recommend(AiMode.FOR_YOU,"",MockScenario.SUCCESS).first().id)
        repo.saveSetup(AiDemoSetup(sources=ProfileSourceSettings(false,false,false)))
        assertEquals("mock-0",repo.recommend(AiMode.FOR_YOU,"",MockScenario.SUCCESS).first().id)
        assertEquals(2,repo.readSetup().feedback.size)
    }
    @Test fun feedbackCanBeChangedAndClearedWithoutChangingSources() = runBlocking<Unit> {
        val repo = AiTestRepository(); val setup = repo.saveSetup(AiDemoSetup())
        repo.saveFeedback("mock-0", AiFeedback.LIKE)
        repo.saveFeedback("mock-0", AiFeedback.UNINTERESTED)
        assertEquals(mapOf("mock-0" to AiFeedback.UNINTERESTED), repo.readSetup().feedback)
        repo.saveFeedback("mock-0", null)
        assertEquals(setup, repo.readSetup())
    }
    @Test fun setupAndFeedbackSurviveClosingAndReopeningDiskStore() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(folder.root, "mock.preferences_pb")
        var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var store = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            val repository = MockAiHomeRepository(context, store)
            val expected = repository.saveSetup(AiDemoSetup(preferences = mapOf("喜剧" to PreferenceDirection.LIKE), sources = ProfileSourceSettings(true, false, false)))
            repository.saveFeedback("mock-1", AiFeedback.LIKE)
            scope.cancel(); scope.coroutineContext[Job]?.join()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val restored = MockAiHomeRepository(context, store).readSetup()
            assertEquals(expected.copy(feedback = mapOf("mock-1" to AiFeedback.LIKE)), restored)
        } finally { scope.cancel(); scope.coroutineContext[Job]?.join() }
    }
}
