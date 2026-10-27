package com.yingji.app.presentation.ai

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.*
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class WorkerViewModelTest {
    private val models = ViewModelStore()
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private class Service : AiWorkerService {
        var quotaValue = AiQuotaSnapshot.mock(MockQuotaScenario.AVAILABLE)
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        override suspend fun quota() = quotaValue
        override suspend fun hints(request: WorkerHintRequest): WorkerTaskResponse {
            calls++; started.complete(Unit); gate?.await()
            return WorkerTaskResponse(request.requestId, request.token, WorkerTaskStatus.SUCCESS, WorkerHints(listOf("科幻"), CandidateHardConditions()), quotaValue,
                WorkerUsage(true, 1, 0, 100, 40, 140), null)
        }
    }
    private fun demo() = MockAiHomeRepository(ApplicationProvider.getApplicationContext<Context>(), AiTestStore())
    private suspend fun model(repo: AiHomeRepository): AiHomeViewModel {
        lateinit var vm: AiHomeViewModel
        main { vm = AiHomeViewModel(repo); models.put("vm", vm) }
        withTimeout(5000) { vm.state.first { !it.initializing } }; return vm
    }
    @After fun close() { main { models.clear() } }
    @Test fun lateOldProfileResponseCannotReplaceNewStateOrWriteHistoryAndRapidClicksShareTask() = runBlocking<Unit> {
        val demo = demo(); demo.saveSetup(AiDemoSetup()); val service = Service(); val gate = CompletableDeferred<Unit>(); service.gate = gate
        val repo = WorkerDemoHomeRepository(demo, service, MockCandidateCatalogRepository()); val vm = model(repo)
        main { vm.setScenario(MockScenario.WORKER); vm.submit(AiMode.FOR_YOU); repeat(10) { vm.submit(AiMode.FOR_YOU) } }
        withTimeout(5000) { service.started.await() }; assertEquals(1, service.calls)
        demo.setManagementPreference("科幻", PreferenceDirection.DISLIKE)
        withTimeout(5000) { vm.state.first { it.result == AiResultState.IDLE } }
        gate.complete(Unit)
        // Await the same engine mutex: the old request has finished its profile fence before this returns.
        repo.readQuota(); main { }
        assertTrue(vm.state.value.recommendations.isEmpty()); assertNull(vm.state.value.pendingAdd)
        assertTrue(demo.readManagement().history.isEmpty()); assertEquals(AiResultState.IDLE, vm.state.value.result)
        main { vm.submit(AiMode.FOR_YOU) }
        val current = withTimeout(5000) { vm.state.first { it.result == AiResultState.SUCCESS } }
        assertTrue(current.recommendations.none { it.topic == "科幻" }); assertEquals(2, service.calls)
    }
    @Test fun exhaustedQuotaStillAllowsReliableLocalFallbackWithoutCreatingModelTask() = runBlocking<Unit> {
        val demo = demo(); demo.saveSetup(AiDemoSetup()); val service = Service(); service.quotaValue = AiQuotaSnapshot.mock(MockQuotaScenario.EXHAUSTED)
        val repo = WorkerDemoHomeRepository(demo, service, MockCandidateCatalogRepository()); val vm = model(repo)
        main { vm.setScenario(MockScenario.WORKER) }
        withTimeout(5000) { vm.state.first { it.quota.status == AiQuotaStatus.EXHAUSTED } }
        main { vm.submit(AiMode.TONIGHT) }
        val done = withTimeout(5000) { vm.state.first { it.result == AiResultState.SUCCESS } }
        assertEquals(3, done.recommendations.size); assertTrue(done.message!!.contains("本地可信")); assertEquals(0, service.calls)
        assertEquals(AiQuotaStatus.EXHAUSTED, done.quota.status)
    }
    @Test fun releaseStyleRepositoryCannotSelectWorkerOrGrantTransmission() = runBlocking<Unit> {
        val demo = demo(); demo.saveSetup(AiDemoSetup()); val vm = model(demo)
        assertFalse(vm.state.value.workerDemoAvailable); main { vm.setScenario(MockScenario.WORKER) }
        assertEquals(MockScenario.SUCCESS, vm.state.value.scenario)
    }
}
