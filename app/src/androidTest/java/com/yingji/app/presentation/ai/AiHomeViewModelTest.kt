package com.yingji.app.presentation.ai

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AiHomeViewModelTest {
    private val models = ViewModelStore()
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private suspend fun model(repo: AiTestRepository): AiHomeViewModel {
        lateinit var vm: AiHomeViewModel
        main { vm = AiHomeViewModel(repo); models.put("vm", vm) }
        withTimeout(5000) { vm.state.first { !it.initializing } }
        return vm
    }
    @After fun close() { main { models.clear() } }

    @Test fun skipDiscardsDraftAndNeverRequestsRecommendations() = runBlocking<Unit> {
        val repo = AiTestRepository(); val vm = model(repo)
        main { vm.enter(); vm.setPreference("科幻", PreferenceDirection.LIKE)
            vm.setSources(ProfileSourceSettings(true, true, false)); vm.saveSetup(skip = true) }
        val done = withTimeout(5000) { vm.state.first { it.setup.completed } }
        assertNull(done.step); assertTrue(done.setup.preferences.isEmpty())
        assertEquals(ProfileSourceSettings(false, false, true), repo.readSetup().sources)
        assertEquals(0, repo.requests)
        assertTrue(repo.readSetup().completed)
    }
    @Test fun failedSaveRetainsDraftAndIncompleteMarkerThenRetries() = runBlocking<Unit> {
        val repo = AiTestRepository(); val vm = model(repo)
        repo.store.failWrites = true
        main { vm.enter(); vm.setPreference("科幻", PreferenceDirection.DISLIKE); vm.setStep(2); vm.saveSetup() }
        val failed = withTimeout(5000) { vm.state.first { it.message?.contains("保存失败") == true } }
        assertEquals(2, failed.step); assertFalse(repo.readSetup().completed)
        assertEquals(PreferenceDirection.DISLIKE, failed.draft.preferences["科幻"])
        repo.store.failWrites = false
        main { vm.saveSetup() }
        withTimeout(5000) { vm.state.first { it.setup.completed } }
        val restarted = model(repo)
        main { restarted.enter() }
        assertNull(restarted.state.value.step)
        assertTrue(restarted.state.value.expanded)
        assertEquals(PreferenceDirection.DISLIKE, restarted.state.value.setup.preferences["科幻"])
    }
    @Test fun explicitSubmitValidatesInputAndSuppressesRapidDuplicateRequests() = runBlocking<Unit> {
        val repo = AiTestRepository(); repo.saveSetup(AiDemoSetup()); val vm = model(repo)
        main { vm.enter(); vm.submit(AiMode.QUERY) }
        assertEquals("请输入想看的内容", vm.state.value.message); assertEquals(0, repo.requests)
        val gate = CompletableDeferred<Unit>(); repo.gate = gate
        main { vm.setInput("  科幻  "); vm.submit(AiMode.QUERY); repeat(10) { vm.submit(AiMode.QUERY) } }
        assertEquals(AiResultState.LOADING, vm.state.value.result); assertEquals(1, repo.requests)
        assertEquals("科幻", repo.lastInput)
        gate.complete(Unit)
        withTimeout(5000) { vm.state.first { it.result == AiResultState.SUCCESS } }
        assertEquals(3, vm.state.value.recommendations.size)
    }
    @Test fun shortcutsAndAllFailureStatesAreExplicitAndRecoverable() = runBlocking<Unit> {
        val repo = AiTestRepository(); repo.saveSetup(AiDemoSetup()); val vm = model(repo)
        for (mode in listOf(AiMode.FOR_YOU, AiMode.TONIGHT, AiMode.EXPLORE)) {
            main { vm.submit(mode) }
            withTimeout(5000) { vm.state.first { it.result == AiResultState.SUCCESS } }
            assertEquals(mode, repo.lastMode); assertEquals("", repo.lastInput)
        }
        for ((scenario, expected) in listOf(MockScenario.EMPTY to AiResultState.EMPTY, MockScenario.ERROR to AiResultState.ERROR,
            MockScenario.OFFLINE to AiResultState.OFFLINE, MockScenario.QUOTA to AiResultState.QUOTA)) {
            main { vm.setScenario(scenario); vm.submit(AiMode.FOR_YOU) }
            withTimeout(5000) { vm.state.first { it.result == expected } }
            assertTrue(vm.state.value.recommendations.isEmpty())
        }
        main { vm.setScenario(MockScenario.SUCCESS); vm.submit(AiMode.TONIGHT) }
        withTimeout(5000) { vm.state.first { it.result == AiResultState.SUCCESS } }
    }
    @Test fun feedbackFailureDoesNotClaimSavedAndConfirmationIsConsumedOnce() = runBlocking<Unit> {
        val repo = AiTestRepository(); repo.saveSetup(AiDemoSetup()); val vm = model(repo)
        main { vm.submit(AiMode.FOR_YOU) }
        val item = withTimeout(5000) { vm.state.first { it.result == AiResultState.SUCCESS } }.recommendations.first()
        repo.store.failWrites = true
        main { vm.feedback(item, AiFeedback.LIKE) }
        withTimeout(5000) { vm.state.first { it.message == "反馈保存失败，请重试" } }
        assertTrue(vm.state.value.setup.feedback.isEmpty())
        repo.store.failWrites = false
        main { vm.feedback(item, AiFeedback.LIKE) }
        withTimeout(5000) { vm.state.first { it.setup.feedback[item.id] == AiFeedback.LIKE } }
        assertEquals(AiFeedback.LIKE, repo.readSetup().feedback[item.id])
        main { vm.requestAdd(item); vm.cancelAdd() }
        assertNull(vm.state.value.pendingAdd)
        main { vm.requestAdd(item); assertEquals(item, vm.confirmAdd()); assertNull(vm.confirmAdd()) }
    }
    @Test fun editingThenSkippingPreservesPreviouslySavedSelections() = runBlocking<Unit> {
        val repo = AiTestRepository()
        val original = repo.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.DISLIKE), sources = ProfileSourceSettings(true, false, false)))
        val vm = model(repo)
        main { vm.enter(); vm.editSetup(); vm.setPreference("喜剧", PreferenceDirection.LIKE); vm.setSources(ProfileSourceSettings()); vm.saveSetup(skip = true) }
        withTimeout(5000) { vm.state.first { it.step == null && !it.saving } }
        assertEquals(original, repo.readSetup()); assertEquals(0, repo.requests)
    }
    @Test fun readFailureIsVisibleAndRetryDoesNotAuthorizeDefaults() = runBlocking<Unit> {
        val repo = AiTestRepository(); repo.failReads = true; val vm = model(repo)
        assertTrue(vm.state.value.readFailed)
        main { vm.enter() }; assertNull(vm.state.value.step)
        repo.failReads = false
        main { vm.load() }
        withTimeout(5000) { vm.state.first { !it.initializing && !it.readFailed } }
        main { vm.enter() }
        assertEquals(1, vm.state.value.step); assertFalse(repo.readSetup().completed)
    }
}
