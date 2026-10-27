package com.yingji.app.presentation.ai

import android.content.Context
import androidx.datastore.preferences.core.*
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

class AiManagementRepositoryTest {
    @get:Rule val folder = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)
    private suspend fun seed(repo: AiHomeRepository) {
        repo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.LIKE), sources = ProfileSourceSettings(true, true, true)))
        repo.saveFeedback("mock-0", AiFeedback.LIKE); repo.saveFeedback("mock-1", AiFeedback.DISLIKE)
        repo.correctInference("喜剧", true); repo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS)
        repo.setMockQuota(MockQuotaScenario.EXHAUSTED)
    }
    @Test fun oldSetupAndFeedbackAreReadWithoutAuthorizingNewSourcesOrCallingCatalog() = runBlocking<Unit> {
        val repo = AiTestRepository(); repo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.LIKE)))
        repo.saveFeedback("mock-0", AiFeedback.LIKE); val writes = repo.store.writes
        val view = repo.readManagement()
        assertEquals(repo.readSetup(), view.setup); assertEquals(ProfileSourceSettings(), view.setup.sources)
        assertEquals("科幻", view.inferred.single().feature.label); assertTrue(view.history.isEmpty())
        assertTrue(view.quota.isMock); assertEquals(0, repo.requests); assertEquals(writes, repo.store.writes)
    }
    @Test fun fourCleanupScopesPreserveExactlyTheApprovedDataAndIndependentQuota() = runBlocking<Unit> {
        for (action in AiClearAction.entries) {
            val repo = AiTestRepository(); seed(repo); val before = repo.readManagement()
            repo.store.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("future_unrelated")] = "keep" } }
            val after = repo.clearManagement(action)
            assertEquals(before.quota, after.quota); assertEquals("keep", repo.store.data.value[stringPreferencesKey("future_unrelated")])
            when (action) {
                AiClearAction.INFERRED -> { assertTrue(after.inferred.isEmpty()); assertEquals(before.setup, after.setup); assertEquals(before.corrections, after.corrections); assertEquals(before.history, after.history)
                    repo.saveFeedback("mock-0", AiFeedback.LIKE); assertEquals("科幻", repo.readManagement().inferred.single().feature.label) }
                AiClearAction.LEARNING -> { assertTrue(after.setup.feedback.isEmpty()); assertTrue(after.inferred.isEmpty()); assertTrue(after.corrections.isEmpty()); assertEquals(before.setup.preferences, after.setup.preferences); assertEquals(before.setup.sources, after.setup.sources); assertEquals(before.history, after.history) }
                AiClearAction.HISTORY -> { assertTrue(after.history.isEmpty()); assertEquals(before.setup, after.setup); assertEquals(before.inferred, after.inferred); assertEquals(before.corrections, after.corrections) }
                AiClearAction.ALL -> { assertEquals(AiDemoSetup(), after.setup); assertTrue(after.history.isEmpty()); assertTrue(after.inferred.isEmpty()); assertTrue(after.corrections.isEmpty()) }
            }
        }
    }
    @Test fun failedCleanupNeverPartiallyWritesOrClaimsSuccess() = runBlocking<Unit> {
        for (action in AiClearAction.entries) {
            val repo = AiTestRepository(); seed(repo); val before = repo.readManagement(); val raw = repo.store.data.value
            repo.store.failWrites = true
            try { repo.clearManagement(action); fail("Write failure hidden") } catch (_: IOException) { }
            assertEquals(raw, repo.store.data.value); assertEquals(before, repo.readManagement())
        }
    }
    @Test fun correctionsUndoAndSourceSwitchesShareTheSameRecommendationEvidence() = runBlocking<Unit> {
        val repo = AiTestRepository(); repo.saveSetup(AiDemoSetup()); repo.saveFeedback("mock-2", AiFeedback.LIKE)
        assertEquals("mock-2", repo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS).first().id)
        repo.correctInference("纪录片", true); assertTrue(repo.readManagement().inferred.isEmpty())
        assertEquals("mock-0", repo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS).first().id)
        repo.correctInference("纪录片", false); assertEquals("纪录片", repo.readManagement().inferred.single().feature.label)
        repo.setManagementSources(ProfileSourceSettings(false, false, false)); assertTrue(repo.readManagement().inferred.isEmpty()); assertEquals(1, repo.readSetup().feedback.size)
        repo.setManagementSources(ProfileSourceSettings()); assertEquals("纪录片", repo.readManagement().inferred.single().feature.label)
        repo.setManagementPreference("纪录片", PreferenceDirection.DISLIKE); assertFalse(repo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS).any { it.topic == "纪录片" })
        repo.setManagementPreference("纪录片", null); assertTrue(repo.readSetup().preferences.isEmpty())
    }
    @Test fun historyIsBoundedToTwentyAndExpiresAtThirtyDaysWithoutAnotherRequest() = runBlocking<Unit> {
        var now = System.currentTimeMillis(); val store = AiTestStore()
        val repo = MockAiHomeRepository(ApplicationProvider.getApplicationContext<Context>(), store, clock = { now })
        repo.saveSetup(AiDemoSetup())
        repeat(25) { now++; repo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS) }
        val history = repo.readManagement().history; assertEquals(20, history.size); assertEquals(20, history.map { it.id }.toSet().size)
        assertEquals(now, history.first().createdAt); assertEquals(now - 19, history.last().createdAt)
        now += 30L * 24 * 60 * 60 * 1000; assertTrue(repo.readManagement().history.isEmpty())
        assertEquals("[]", store.data.value[stringPreferencesKey("management_history")])
    }
    @Test fun unsuccessfulAndEmptyRequestsAreHistoryButReadDoesNotRecommend() = runBlocking<Unit> {
        val repo = AiTestRepository(); repo.saveSetup(AiDemoSetup())
        val standalone = setOf(MockScenario.SUCCESS, MockScenario.EMPTY, MockScenario.ERROR, MockScenario.OFFLINE, MockScenario.QUOTA)
        for (scenario in standalone) try { repo.recommend(AiMode.TONIGHT, "", scenario) } catch (_: MockRequestException) { }
        val calls = repo.requests; val view = repo.readManagement()
        assertEquals(standalone, view.history.map { it.outcome }.toSet()); assertEquals(5, view.history.size)
        repeat(5) { repo.readManagement(); repo.readQuota() }; assertEquals(calls, repo.requests)
    }
    @Test fun malformedHistoryCannotCommitAnUnrelatedPreferenceBeforeReturningFailure() = runBlocking<Unit> {
        val repo = AiTestRepository(); repo.saveSetup(AiDemoSetup())
        repo.store.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("management_history")] = "{" } }
        val raw = repo.store.data.value
        try { repo.setManagementPreference("科幻", PreferenceDirection.LIKE); fail("Invalid history hidden") } catch (_: org.json.JSONException) { }
        assertEquals(raw, repo.store.data.value); assertTrue(repo.readSetup().preferences.isEmpty())
    }
    @Test fun eachCleanupAndQuotaSurviveClosingAndReopeningDiskStore() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (action in AiClearAction.entries) {
            val file = File(folder.root, action.name + ".preferences_pb")
            var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val repo = MockAiHomeRepository(context, PreferenceDataStoreFactory.create(scope = scope) { file })
                seed(repo); val expected = repo.clearManagement(action)
                scope.cancel(); scope.coroutineContext[Job]?.join(); scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                val restored = MockAiHomeRepository(context, PreferenceDataStoreFactory.create(scope = scope) { file }).readManagement()
                assertEquals(expected, restored)
            } finally { scope.cancel(); scope.coroutineContext[Job]?.join() }
        }
    }
}
