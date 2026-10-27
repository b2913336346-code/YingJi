package com.yingji.app.presentation.ai

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class LocalProfileManagementViewModelTest {
    private val models = ViewModelStore()
    private val feature = ProfileFeature.topic("科幻")
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private inner class Local : LocalProfileManagementRepository {
        val values = MutableStateFlow(LocalProfileManagementSnapshot(ProfileSnapshot(explicitPreferences = listOf(ExplicitPreference(feature, PreferenceDirection.LIKE))), listOf(feature)))
        var reads = 0; var writes = 0; var failRead = false; var failWrite = false; var gate: CompletableDeferred<Unit>? = null
        override fun observe() = flow { reads++; if (failRead) error("fixture read failure"); emitAll(values) }
        private suspend fun save(edit: (ProfileSnapshot) -> ProfileSnapshot): ProfileSnapshot {
            writes++; gate?.await(); if (failWrite) error("fixture write failure")
            val old = values.value.profile
            val next = edit(old).copy(token = old.token.copy(profileVersion = old.token.profileVersion + 1))
            values.value = values.value.copy(profile = next); return next
        }
        override suspend fun setPreference(feature: ProfileFeature, direction: PreferenceDirection) = save { it.copy(explicitPreferences = listOf(ExplicitPreference(feature, direction))) }
        override suspend fun removePreference(featureId: String) = save { it.copy(explicitPreferences = emptyList()) }
        override suspend fun setSources(settings: ProfileSourceSettings) = save { it.copy(settings = settings) }
        override suspend fun correct(feature: ProfileFeature, suppressed: Boolean) = save { it.copy(suppressedFeatureIds = if (suppressed) setOf(feature.id) else emptySet()) }
        override suspend fun reset() = save { ProfileSnapshot() }
    }
    private suspend fun model(mock: AiTestRepository, local: Local): AiHomeViewModel {
        lateinit var vm: AiHomeViewModel
        main { vm = AiHomeViewModel(mock, localProfiles = local); models.put("vm" + models.keys().size, vm) }
        withTimeout(5000) { vm.state.first { !it.initializing } }; return vm
    }
    private suspend fun open(vm: AiHomeViewModel) {
        main { vm.openManagement() }; withTimeout(5000) { vm.state.first { !it.managementLoading } }
        main { vm.selectManagementScope(AiManagementScope.LOCAL_PROFILE) }
        withTimeout(5000) { vm.state.first { !it.localProfile.loading } }
    }
    @After fun close() { main { models.clear() } }
    @Test fun scopeIsOptInAndRealChangesNeverModifyMockSetupResultsOrHistory() = runBlocking<Unit> {
        val mock = AiTestRepository(); mock.saveSetup(AiDemoSetup(preferences = mapOf("科幻" to PreferenceDirection.DISLIKE)))
        mock.recommend(AiMode.FOR_YOU, "", MockScenario.SUCCESS); val before = mock.readManagement()
        val local = Local(); val vm = model(mock, local); assertEquals(0, local.reads); assertEquals(AiManagementScope.MOCK, vm.state.value.managementScope)
        open(vm); main { vm.changeLocalPreference(feature, PreferenceDirection.DISLIKE) }
        withTimeout(5000) { vm.state.first { !it.localProfile.saving } }
        assertEquals(PreferenceDirection.DISLIKE, vm.state.value.localProfile.snapshot!!.profile.explicitPreferences.single().direction)
        assertEquals(before, mock.readManagement()); assertEquals(before.setup, vm.state.value.setup); assertEquals(1, mock.requests)
        main { vm.requestClear(AiClearAction.ALL); vm.changeManagementPreference("喜剧", PreferenceDirection.LIKE) }
        assertNull(vm.state.value.pendingClear); assertEquals(before, mock.readManagement())
    }
    @Test fun cancellationAndFailedRemovalPreservePreferenceThenRetryPersists() = runBlocking<Unit> {
        val mock = AiTestRepository(); val local = Local(); val vm = model(mock, local); open(vm)
        main { vm.changeLocalPreference(feature, PreferenceDirection.LIKE); vm.cancelLocalConfirmation() }
        assertEquals(0, local.writes); assertEquals(1, local.values.value.profile.explicitPreferences.size)
        local.failWrite = true; main { vm.changeLocalPreference(feature, PreferenceDirection.LIKE); vm.confirmLocalRemoval() }
        withTimeout(5000) { vm.state.first { it.localProfile.error != null } }
        assertEquals(feature, vm.state.value.localProfile.pendingRemoval); assertNull(vm.state.value.localProfile.message); assertEquals(1, local.values.value.profile.explicitPreferences.size)
        local.failWrite = false; main { vm.confirmLocalRemoval() }; withTimeout(5000) { vm.state.first { !it.localProfile.saving && it.localProfile.pendingRemoval == null } }
        assertTrue(vm.state.value.localProfile.snapshot!!.profile.explicitPreferences.isEmpty()); assertEquals(2, local.writes)
        main { vm.closeManagement() }; open(vm); assertTrue(vm.state.value.localProfile.snapshot!!.profile.explicitPreferences.isEmpty())
    }
    @Test fun resetRequiresConfirmationAndRapidClicksOrScopeChangesCannotDuplicateWrite() = runBlocking<Unit> {
        val local = Local(); val vm = model(AiTestRepository(), local); open(vm)
        main { vm.confirmLocalReset() }; assertEquals(0, local.writes)
        main { vm.requestLocalReset(); vm.cancelLocalConfirmation() }; assertEquals(0, local.writes)
        val held = CompletableDeferred<Unit>(); local.gate = held
        main { vm.requestLocalReset(); repeat(10) { vm.confirmLocalReset() }; vm.closeManagement(); vm.selectManagementScope(AiManagementScope.MOCK); vm.cancelLocalConfirmation() }
        assertEquals(1, local.writes); assertTrue(vm.state.value.managementOpen); assertTrue(vm.state.value.localProfile.saving); assertTrue(vm.state.value.localProfile.pendingReset)
        held.complete(Unit); withTimeout(5000) { vm.state.first { !it.localProfile.saving } }
        assertTrue(vm.state.value.localProfile.snapshot!!.profile.explicitPreferences.isEmpty()); assertFalse(vm.state.value.localProfile.pendingReset); assertEquals(1, local.writes)
    }
    @Test fun readFailureHasRetryAndClosedScopeCannotReceiveLateDataOrWrite() = runBlocking<Unit> {
        val local = Local(); local.failRead = true; val vm = model(AiTestRepository(), local); open(vm)
        assertNull(vm.state.value.localProfile.snapshot); assertNotNull(vm.state.value.localProfile.error)
        local.failRead = false; main { vm.loadLocalProfile() }; withTimeout(5000) { vm.state.first { it.localProfile.snapshot != null } }
        main { vm.closeManagement() }; local.values.value = local.values.value.copy(profile = ProfileSnapshot(token = ProfileToken(99, 0)))
        main { vm.changeLocalSources(ProfileSourceSettings(true, true, true)); vm.requestLocalReset() }
        assertFalse(vm.state.value.managementOpen); assertNull(vm.state.value.localProfile.snapshot); assertEquals(0, local.writes)
    }
    @Test fun staleProfileEmissionCannotReplaceNewerSavedState() = runBlocking<Unit> {
        val local = Local(); val vm = model(AiTestRepository(), local); open(vm)
        main { vm.changeLocalSources(ProfileSourceSettings(true, false, true)) }; withTimeout(5000) { vm.state.first { !it.localProfile.saving } }
        val saved = vm.state.value.localProfile.snapshot!!.profile
        local.values.value = local.values.value.copy(profile = ProfileSnapshot())
        main {} // Observe state on the main dispatcher after the queued emission.
        assertEquals(saved, vm.state.value.localProfile.snapshot!!.profile); assertTrue(saved.settings.ratingsEnabled)
    }
}
