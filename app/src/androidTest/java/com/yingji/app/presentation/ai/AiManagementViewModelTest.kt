package com.yingji.app.presentation.ai

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AiManagementViewModelTest {
    private val models = ViewModelStore()
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private suspend fun model(repo: AiHomeRepository): AiHomeViewModel {
        lateinit var vm: AiHomeViewModel
        main { vm = AiHomeViewModel(repo); models.put("vm" + models.keys().size, vm) }
        withTimeout(5000) { vm.state.first { !it.initializing } }; return vm
    }
    private suspend fun open(vm: AiHomeViewModel) { main { vm.openManagement() }; withTimeout(5000) { vm.state.first { !it.managementLoading } } }
    @After fun close() { main { models.clear() } }
    @Test fun cancelingEachCleanupLeavesDataUntouchedAndMakesNoNewRequest() = runBlocking<Unit> {
        val repo = AiTestRepository(); repo.saveSetup(AiDemoSetup()); repo.saveFeedback("mock-0", AiFeedback.LIKE)
        repo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS); val vm = model(repo); open(vm)
        val before = repo.readManagement(); val writes = repo.store.writes; val requests = repo.requests
        for (action in AiClearAction.entries) { main { vm.requestClear(action); vm.cancelClear() }; assertNull(vm.state.value.pendingClear); assertEquals(before, repo.readManagement()) }
        assertEquals(writes, repo.store.writes); assertEquals(0, repo.managementClears); assertEquals(requests, repo.requests)
    }
    @Test fun failedClearShowsNoSuccessThenOneRetryIgnoresRapidClicksAndSurvivesModelRestart() = runBlocking<Unit> {
        val repo = AiTestRepository(); repo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.LIKE)))
        repo.setMockQuota(MockQuotaScenario.EXHAUSTED); val vm = model(repo); open(vm); val before = repo.readManagement()
        repo.store.failWrites = true; main { vm.requestClear(AiClearAction.ALL); vm.confirmClear() }
        withTimeout(5000) { vm.state.first { it.managementError != null } }
        assertEquals(before, repo.readManagement()); assertNull(vm.state.value.managementMessage); assertEquals(AiClearAction.ALL, vm.state.value.pendingClear)
        repo.store.failWrites = false; val gate = CompletableDeferred<Unit>(); repo.managementGate = gate
        main { repeat(10) { vm.confirmClear() }; vm.cancelClear(); vm.closeManagement() }
        assertTrue(vm.state.value.managementSaving); assertTrue(vm.state.value.managementOpen); assertEquals(2, repo.managementClears)
        gate.complete(Unit); withTimeout(5000) { vm.state.first { it.managementMessage?.contains("已完成") == true } }
        assertNull(vm.state.value.pendingClear); assertEquals(AiDemoSetup(), vm.state.value.setup); assertFalse(vm.state.value.expanded)
        val restarted = model(repo); assertEquals(AiDemoSetup(), restarted.state.value.setup); assertEquals(AiQuotaStatus.EXHAUSTED, restarted.state.value.quota.status)
    }
    @Test fun exhaustedQuotaBlocksOnlyNewRequestsWhileHistoryProfileCleanupAndRefreshWork() = runBlocking<Unit> {
        val repo = AiTestRepository(); repo.saveSetup(AiDemoSetup()); repo.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS)
        repo.setMockQuota(MockQuotaScenario.EXHAUSTED); val vm = model(repo); val before = repo.requests
        main { vm.submit(AiMode.FOR_YOU) }; assertEquals(AiResultState.QUOTA, vm.state.value.result); assertEquals(before, repo.requests)
        open(vm); assertEquals(1, vm.state.value.management!!.history.size)
        main { vm.changeManagementPreference("喜剧", PreferenceDirection.LIKE) }; withTimeout(5000) { vm.state.first { !it.managementSaving } }
        main { vm.requestClear(AiClearAction.HISTORY); vm.confirmClear() }; withTimeout(5000) { vm.state.first { it.management!!.history.isEmpty() } }
        repo.failQuotaReads = true; main { vm.refreshQuota() }; withTimeout(5000) { vm.state.first { it.managementError != null } }; assertNull(vm.state.value.managementMessage)
        repo.failQuotaReads = false; main { vm.chooseMockQuota(MockQuotaScenario.AVAILABLE) }; withTimeout(5000) { vm.state.first { it.quota.canRequestNewTask } }
        main { vm.closeManagement(); vm.submit(AiMode.TONIGHT) }; withTimeout(5000) { vm.state.first { it.result == AiResultState.SUCCESS } }; assertEquals(before + 1, repo.requests)
    }
    @Test fun readFailureAndLateClosedReadNeverOverwriteANewerManagementSession() = runBlocking<Unit> {
        val repo = AiTestRepository(); repo.saveSetup(AiDemoSetup()); val vm = model(repo)
        repo.failReads = true; open(vm); assertNotNull(vm.state.value.managementError); assertNull(vm.state.value.management)
        repo.failReads = false; main { vm.loadManagement() }; withTimeout(5000) { vm.state.first { it.management != null } }
        main { vm.closeManagement() }
        val first = repo.readManagement(); val next = first.copy(setup = first.setup.copy(preferences = mapOf("喜剧" to PreferenceDirection.LIKE)))
        val gate = CompletableDeferred<AiManagementSnapshot>(); var reads = 0
        val delayed = object : AiHomeRepository by repo {
            override suspend fun readManagement(): AiManagementSnapshot { reads++; return if (reads == 1) gate.await() else next }
        }
        val race = model(delayed); main { race.openManagement(); race.closeManagement(); race.openManagement() }
        withTimeout(5000) { race.state.first { it.management == next } }; gate.complete(first)
        main { }; assertEquals(next, race.state.value.management)
    }
    @Test fun quotaExhaustionStillAllowsFeedbackOnAnExistingRecommendation() = runBlocking<Unit> {
        val repo = AiTestRepository(); repo.saveSetup(AiDemoSetup()); val vm = model(repo)
        main { vm.submit(AiMode.FOR_YOU) }; val item = withTimeout(5000) { vm.state.first { it.result == AiResultState.SUCCESS } }.recommendations.first()
        open(vm); main { vm.chooseMockQuota(MockQuotaScenario.EXHAUSTED) }; withTimeout(5000) { vm.state.first { it.quota.status == AiQuotaStatus.EXHAUSTED } }
        main { vm.closeManagement(); vm.feedback(item, AiFeedback.LIKE) }; withTimeout(5000) { vm.state.first { it.setup.feedback[item.id] == AiFeedback.LIKE } }
        assertEquals(1, repo.requests); assertEquals(AiFeedback.LIKE, repo.readSetup().feedback[item.id]); assertFalse(vm.state.value.quota.canRequestNewTask)
    }
}
