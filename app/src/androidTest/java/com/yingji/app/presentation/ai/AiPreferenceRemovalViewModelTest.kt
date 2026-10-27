package com.yingji.app.presentation.ai

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.data.ai.DeepSeekAiKeyVerifier
import com.yingji.app.data.ai.DeepSeekHttpClient
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AiPreferenceRemovalViewModelTest {
    private val models=ViewModelStore()
    private fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private suspend fun model(repo:AiHomeRepository,keys:AiKeyConfigurationRepository?=null):AiHomeViewModel {
        lateinit var vm:AiHomeViewModel
        main { vm=AiHomeViewModel(repo,keys); models.put("vm"+models.keys().size,vm) }
        withTimeout(5000){vm.state.first{!it.initializing}}; return vm
    }
    private suspend fun open(vm:AiHomeViewModel) { main { vm.openManagement() }; withTimeout(5000){vm.state.first{!it.managementLoading}} }
    private suspend fun seed(repo:AiTestRepository) {
        repo.saveSetup(AiDemoSetup(preferences=mapOf("科幻" to PreferenceDirection.LIKE)))
        repo.saveFeedback("mock-0",AiFeedback.LIKE); repo.recommend(AiMode.FOR_YOU,"",MockScenario.SUCCESS); repo.setMockQuota(MockQuotaScenario.EXHAUSTED)
    }
    @After fun close(){main{models.clear()}}
    @Test fun cancelAndCloseNeverRemovePreferenceOrWriteAnyData()=runBlocking<Unit> {
        val repo=AiTestRepository(); seed(repo); val vm=model(repo); open(vm); val before=repo.readManagement(); val writes=repo.store.writes
        main { repeat(10){vm.changeManagementPreference("科幻",PreferenceDirection.LIKE)} }
        assertEquals("科幻",vm.state.value.pendingPreferenceRemoval); assertEquals(before,repo.readManagement()); assertEquals(writes,repo.store.writes)
        main { vm.cancelPreferenceRemoval() }; assertNull(vm.state.value.pendingPreferenceRemoval); assertEquals(before,repo.readManagement())
        main { vm.changeManagementPreference("科幻",PreferenceDirection.LIKE); vm.closeManagement() }
        assertNull(vm.state.value.pendingPreferenceRemoval); assertEquals(before,repo.readManagement()); assertEquals(writes,repo.store.writes)
    }
    @Test fun failedRemovalRetainsConfirmationAndRetryIgnoresRapidClicksAndSurvivesModelRestart()=runBlocking<Unit> {
        val repo=AiTestRepository(); seed(repo); var removals=0; var gate:CompletableDeferred<Unit>?=null
        val wrapper=object:AiHomeRepository by repo {
            override suspend fun setManagementPreference(topic:String,direction:PreferenceDirection?):AiManagementSnapshot {
                if(direction==null){removals++;gate?.await()};return repo.setManagementPreference(topic,direction)
            }
        }
        val vm=model(wrapper);open(vm);val before=repo.readManagement();repo.store.failWrites=true
        main { vm.changeManagementPreference("科幻",PreferenceDirection.LIKE);vm.confirmPreferenceRemoval() }
        withTimeout(5000){vm.state.first{it.managementError!=null}};assertEquals(before,repo.readManagement());assertEquals("科幻",vm.state.value.pendingPreferenceRemoval);assertNull(vm.state.value.managementMessage)
        repo.store.failWrites=false;val held=CompletableDeferred<Unit>();gate=held
        main { repeat(10){vm.confirmPreferenceRemoval()};vm.cancelPreferenceRemoval();vm.closeManagement() }
        assertEquals(2,removals);assertTrue(vm.state.value.managementSaving);assertTrue(vm.state.value.managementOpen);assertEquals("科幻",vm.state.value.pendingPreferenceRemoval)
        held.complete(Unit);withTimeout(5000){vm.state.first{it.pendingPreferenceRemoval==null&&!it.managementSaving}}
        val after=repo.readManagement();assertTrue(after.setup.preferences.isEmpty());assertEquals(before.setup.feedback,after.setup.feedback);assertEquals(before.inferred,after.inferred);assertEquals(before.history,after.history);assertEquals(before.quota,after.quota)
        val restarted=model(repo);open(restarted);assertEquals(after,restarted.state.value.management);assertEquals(1,repo.requests)
    }
    @Test fun selectingAnotherDirectionIsAnEditAndCallsOutsideManagementAreIgnored()=runBlocking<Unit> {
        val repo=AiTestRepository();seed(repo);val vm=model(repo);val before=repo.store.writes
        main { vm.changeManagementPreference("科幻",PreferenceDirection.LIKE);vm.confirmPreferenceRemoval() }
        assertNull(vm.state.value.pendingPreferenceRemoval);assertEquals(before,repo.store.writes)
        open(vm);main { vm.changeManagementPreference("科幻",PreferenceDirection.DISLIKE) };withTimeout(5000){vm.state.first{!it.managementSaving}}
        assertNull(vm.state.value.pendingPreferenceRemoval);assertEquals(PreferenceDirection.DISLIKE,repo.readSetup().preferences["科幻"])
    }
    @Test fun deletingKeyBlocksNewDirectHttpButFreeManagementAndMockRecommendationRemainUsable()=runBlocking<Unit> {
        var value:String?="FAKE_DIRECT_CREDENTIAL_00000000";var revision="initial";var http=0
        val client=DeepSeekHttpClient { http++;error("No HTTP is allowed in this test") }
        val keys=AiKeyConfigurationRepository(object:AiSecureKeyStore {
            override suspend fun read()=AiKeyRecord(value?.let(AiSecret::fromInput),if(value==null)null else 1L,if(value==null)null else AiKeyVerificationKind.LIVE,revision)
            override suspend fun replace(key:AiSecret?,verifiedAt:Long?,kind:AiKeyVerificationKind?):String { value=key?.useValue{it};revision=java.util.UUID.randomUUID().toString();return revision }
        },DeepSeekAiKeyVerifier(client));keys.refresh()
        val repo=AiTestRepository();repo.saveSetup(AiDemoSetup());val vm=model(repo,keys);keys.delete()
        withTimeout(5000){vm.state.first{it.keyConfiguration?.storage==AiKeyStorageStatus.NOT_CONFIGURED}}
        val request=WorkerRequestBuilder.build(AiHintInput(AiMode.FOR_YOU),AiIntegrationContext(ProfileSnapshot(token=ProfileToken(1,1)),true))
        assertEquals("KEY_REQUIRED",(runCatching{client.hints(request,keys)}.exceptionOrNull() as WorkerProblem).code)
        assertFalse(keys.state.value.canRequestLiveAi);assertNull(value);assertEquals(0,http)
        main{vm.submit(AiMode.FOR_YOU)};withTimeout(5000){vm.state.first{it.result==AiResultState.SUCCESS}};open(vm)
        main{vm.requestClear(AiClearAction.HISTORY);vm.confirmClear()};withTimeout(5000){vm.state.first{!it.managementSaving}}
        assertTrue(repo.readManagement().history.isEmpty());assertEquals(1,repo.requests);assertEquals(0,http)
    }
}
