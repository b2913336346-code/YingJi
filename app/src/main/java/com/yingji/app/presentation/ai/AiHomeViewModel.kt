package com.yingji.app.presentation.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AiManagementTab(val label: String) { PROFILE("画像"), HISTORY("历史"), CLEANUP("清理"), QUOTA("额度") }
enum class AiResultState { IDLE, LOADING, SUCCESS, EMPTY, ERROR, OFFLINE, QUOTA }
data class AiHomeState(
    val directDemoAvailable: Boolean = false,
    val directUsesMockKey: Boolean = true,
    val directConsentPending: Boolean = false,
    val workerDemoAvailable: Boolean = false,
    val keyConfiguration: AiKeyConfigurationState? = null,
    val initializing: Boolean = true,
    val readFailed: Boolean = false,
    val expanded: Boolean = false,
    val setup: AiDemoSetup = AiDemoSetup(),
    val draft: AiDemoSetup = AiDemoSetup(),
    val step: Int? = null,
    val saving: Boolean = false,
    val input: String = "",
    val scenario: MockScenario = MockScenario.SUCCESS,
    val result: AiResultState = AiResultState.IDLE,
    val recommendations: List<MockRecommendation> = emptyList(),
    val feedbackSaving: String? = null,
    val message: String? = null,
    val pendingAdd: MockRecommendation? = null,
    val quota: AiQuotaSnapshot = AiQuotaSnapshot.mock(MockQuotaScenario.UNAVAILABLE),
    val localProfileAvailable: Boolean = false,
    val managementScope: AiManagementScope = AiManagementScope.MOCK,
    val localProfile: LocalProfileManagementState = LocalProfileManagementState(),
    val managementOpen: Boolean = false,
    val managementLoading: Boolean = false,
    val managementSaving: Boolean = false,
    val management: AiManagementSnapshot? = null,
    val managementTab: AiManagementTab = AiManagementTab.PROFILE,
    val managementError: String? = null,
    val managementMessage: String? = null,
    val pendingClear: AiClearAction? = null,
    val pendingPreferenceRemoval: String? = null,
)

class AiHomeViewModel(private val repository: AiHomeRepository, private val keyConfiguration: AiKeyConfigurationRepository? = null, private val localProfiles: LocalProfileManagementRepository? = null, val tmdbCredentials: TmdbCredentialRepository? = null, val tmdbPreview: TmdbCandidatePreview? = null, val tmdbRanking: TmdbMockRecommendationUseCase? = null, val personalizedRecommendation: PersonalizedRecommendation? = null) : ViewModel() {
    val tmdbRankingChanges get() = repository.integrationChanges
    private val mutableState = MutableStateFlow(AiHomeState(localProfileAvailable = localProfiles != null, directDemoAvailable = repository.directDemoAvailable, directUsesMockKey = repository.directUsesMockKey, workerDemoAvailable = repository.workerDemoAvailable && (keyConfiguration == null || repository.workerUsesConfiguredKey), keyConfiguration = keyConfiguration?.state?.value))
    val state = mutableState.asStateFlow()
    private val configurationEvents = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val keyConfigurationRequests = configurationEvents.asSharedFlow()
    fun requestKeyConfiguration() { configurationEvents.tryEmit(Unit) }
    private var requestGeneration = 0L
    init {
        repository.selectIntegrationMode(false)
        repository.selectDirectMode(false)
        if (keyConfiguration != null) viewModelScope.launch {
            keyConfiguration.refresh()
            keyConfiguration.state.collect { value ->
                val previous = state.value.keyConfiguration
                if (state.value.scenario in setOf(MockScenario.WORKER, MockScenario.DIRECT) && (previous?.revision != value.revision || previous?.storage != value.storage || previous?.verifying != value.verifying)) {
                    requestGeneration++
                    mutableState.update { it.copy(keyConfiguration = value, result = AiResultState.IDLE, recommendations = emptyList(), pendingAdd = null) }
                } else mutableState.update { it.copy(keyConfiguration = value) }
            }
        }
        load()
        viewModelScope.launch {
            var previous: ProfileToken? = null
            repository.integrationChanges.catch { error ->
                if (error is CancellationException) throw error
                requestGeneration++
                mutableState.update { it.copy(readFailed = true, initializing = false, result = AiResultState.ERROR,
                    recommendations = emptyList(), pendingAdd = null, message = "演示版本读取失败，请重新打开页面后重试") }
            }.collect { token ->
                if (previous != null && previous != token && state.value.scenario in setOf(MockScenario.WORKER, MockScenario.DIRECT)) {
                    requestGeneration++
                    mutableState.update { it.copy(result = AiResultState.IDLE, recommendations = emptyList(), pendingAdd = null) }
                }
                previous = token
            }
        }
    }

    fun load() {
        mutableState.update { it.copy(initializing = true, readFailed = false, message = null) }
        viewModelScope.launch {
            try {
                val setup = repository.readSetup()
                val quota = try { repository.readQuota() } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { AiQuotaSnapshot.mock(MockQuotaScenario.UNAVAILABLE) }
                mutableState.update { it.copy(initializing = false, setup = setup, draft = setup, quota = quota) }
            } catch (exception: CancellationException) { throw exception
            } catch (exception: Exception) {
                mutableState.update { it.copy(initializing = false, readFailed = true, message = "演示设置读取失败，请重试") }
            }
        }
    }

    fun enter() {
        if (state.value.initializing || state.value.readFailed) return
        mutableState.update { if (it.setup.completed) it.copy(expanded = true) else it.copy(step = 1, draft = it.setup, message = null) }
    }
    fun editSetup() { if (!state.value.saving && state.value.result != AiResultState.LOADING)
        mutableState.update { it.copy(step = 1, draft = it.setup, message = null) } }
    fun closeSetup() { if (!state.value.saving) mutableState.update { it.copy(step = null, message = null) } }
    fun setStep(step: Int) { if (!state.value.saving && step in 1..2) mutableState.update { it.copy(step = step, message = null) } }
    fun setPreference(topic: String, direction: PreferenceDirection) {
        if (state.value.saving || topic !in AiDemoRules.topics) return
        mutableState.update {
            val preferences = it.draft.preferences.toMutableMap()
            if (preferences[topic] == direction) preferences.remove(topic) else preferences[topic] = direction
            it.copy(draft = it.draft.copy(preferences = preferences))
        }
    }
    fun setSources(sources: ProfileSourceSettings) {
        if (!state.value.saving) mutableState.update { it.copy(draft = it.draft.copy(sources = sources)) }
    }
    fun saveSetup(skip: Boolean = false) {
        if (state.value.saving || state.value.step == null) return
        val draft = if (skip) {
            if (state.value.setup.completed) state.value.setup else AiDemoSetup()
        } else state.value.draft
        mutableState.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            try {
                val saved = repository.saveSetup(draft)
                mutableState.update { it.copy(setup = saved, draft = saved, saving = false, step = null,
                    expanded = true, result = AiResultState.IDLE, recommendations = emptyList()) }
            } catch (exception: CancellationException) { throw exception
            } catch (exception: Exception) {
                mutableState.update { it.copy(saving = false, message = "引导保存失败，尚未完成，请重试") }
            }
        }
    }
    fun setInput(input: String) { mutableState.update { it.copy(input = input, message = null) } }
    fun setScenario(scenario: MockScenario) {
        if (scenario == MockScenario.DIRECT) {
            val current = state.value
            if (current.directDemoAvailable && current.scenario != scenario && current.result != AiResultState.LOADING &&
                !current.saving && !current.managementOpen && current.feedbackSaving == null && current.step == null && current.pendingAdd == null)
                mutableState.update { it.copy(directConsentPending = true) }
            return
        }
        applyScenario(scenario)
    }
    fun cancelDirectConsent() { mutableState.update { it.copy(directConsentPending = false) } }
    fun confirmDirectConsent() {
        if (!state.value.directConsentPending) return
        mutableState.update { it.copy(directConsentPending = false) }
        applyScenario(MockScenario.DIRECT)
    }
    private fun applyScenario(scenario: MockScenario) {
        val current = state.value
        if (current.result == AiResultState.LOADING || current.saving || current.managementOpen || current.feedbackSaving != null ||
            scenario == current.scenario || (scenario == MockScenario.WORKER && !state.value.workerDemoAvailable)) return
        if (scenario in setOf(MockScenario.WORKER, MockScenario.DIRECT) || current.scenario in setOf(MockScenario.WORKER, MockScenario.DIRECT)) {
            val generation = ++requestGeneration
            repository.selectIntegrationMode(scenario == MockScenario.WORKER)
            repository.selectDirectMode(scenario == MockScenario.DIRECT)
            mutableState.update { it.copy(scenario = scenario, result = AiResultState.IDLE, recommendations = emptyList(), pendingAdd = null,
                message = null, quota = AiQuotaSnapshot.mock(MockQuotaScenario.UNAVAILABLE)) }
            viewModelScope.launch {
                val quota = try { repository.readQuota() } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { AiQuotaSnapshot.mock(MockQuotaScenario.UNAVAILABLE) }
                if (generation == requestGeneration) mutableState.update { it.copy(quota = quota) }
            }
        } else mutableState.update { it.copy(scenario = scenario) }
    }
    fun submit(mode: AiMode) {
        val current = state.value
        if (!current.setup.completed || current.result == AiResultState.LOADING || current.saving || current.step != null || current.managementOpen || current.directConsentPending) return
        if (current.scenario == MockScenario.WORKER && keyConfiguration != null &&
            (current.keyConfiguration?.storage != AiKeyStorageStatus.CONFIGURED || current.keyConfiguration.kind != AiKeyVerificationKind.MOCK || current.keyConfiguration.verifying)) {
            mutableState.update { it.copy(message = "请先在 AI 模型配置中验证并保存本地联调测试 Key") }
            requestKeyConfiguration(); return
        }
        if (current.scenario == MockScenario.DIRECT &&
            (current.keyConfiguration?.storage != AiKeyStorageStatus.CONFIGURED || current.keyConfiguration.verifying ||
                current.keyConfiguration.kind != if (current.directUsesMockKey) AiKeyVerificationKind.MOCK else AiKeyVerificationKind.LIVE)) {
            mutableState.update { it.copy(message = "请先在 AI 模型配置中验证并保存本次直连使用的 Key") }
            requestKeyConfiguration(); return
        }
        if (!current.quota.canRequestNewTask && current.scenario !in setOf(MockScenario.WORKER, MockScenario.DIRECT)) { mutableState.update { it.copy(result = AiResultState.QUOTA, message = "额度不可用：可继续管理画像、查看历史或刷新额度") }; return }
        val input = if (mode == AiMode.QUERY) current.input.trim() else ""
        AiDemoRules.validateInput(mode, input)?.let { message -> mutableState.update { it.copy(message = message) }; return }
        val generation = ++requestGeneration
        mutableState.update { it.copy(result = AiResultState.LOADING, recommendations = emptyList(), message = null) }
        viewModelScope.launch {
            try {
                val batch = repository.recommendWithMetadata(mode, input, current.scenario)
                if (generation == requestGeneration) mutableState.update { it.copy(result = if (batch.items.isEmpty()) AiResultState.EMPTY else AiResultState.SUCCESS,
                    recommendations = batch.items, quota = batch.quota ?: it.quota, message = batch.notice) }
            } catch (exception: CancellationException) { throw exception
            } catch (exception: Exception) {
                if (generation != requestGeneration) return@launch
                val result = when ((exception as? MockRequestException)?.scenario) {
                    MockScenario.OFFLINE -> AiResultState.OFFLINE
                    MockScenario.QUOTA -> AiResultState.QUOTA
                    else -> AiResultState.ERROR
                }
                mutableState.update { it.copy(result = result) }
            }
        }
    }
    fun feedback(item: MockRecommendation, feedback: AiFeedback) {
        val current = state.value
        if (current.feedbackSaving != null || item !in current.recommendations) return
        val value = feedback.takeUnless { current.setup.feedback[item.id] == it }
        mutableState.update { it.copy(feedbackSaving = item.id, message = null) }
        viewModelScope.launch {
            try {
                val setup = repository.saveFeedback(item.id, value)
                mutableState.update { it.copy(setup = setup, feedbackSaving = null) }
            } catch (exception: CancellationException) { throw exception
            } catch (exception: Exception) {
                mutableState.update { it.copy(feedbackSaving = null, message = "反馈保存失败，请重试") }
            }
        }
    }
    fun requestAdd(item: MockRecommendation) { if (item in state.value.recommendations) mutableState.update { it.copy(pendingAdd = item) } }
    fun cancelAdd() { mutableState.update { it.copy(pendingAdd = null) } }
    fun confirmAdd(): MockRecommendation? {
        val item = state.value.pendingAdd ?: return null
        mutableState.update { it.copy(pendingAdd = null) }
        return item
    }
    private var localProfileJob: kotlinx.coroutines.Job? = null
    private var localGeneration = 0L
    fun selectManagementScope(scope: AiManagementScope) {
        val current = state.value
        if (!current.managementOpen || current.managementLoading || current.managementSaving || current.localProfile.saving ||
            current.pendingClear != null || current.pendingPreferenceRemoval != null || current.localProfile.pendingRemoval != null ||
            current.localProfile.pendingReset || scope == current.managementScope || (scope == AiManagementScope.LOCAL_PROFILE && localProfiles == null)) return
        localGeneration++; localProfileJob?.cancel()
        mutableState.update { it.copy(managementScope = scope, localProfile = LocalProfileManagementState()) }
        if (scope == AiManagementScope.LOCAL_PROFILE) loadLocalProfile()
    }
    fun loadLocalProfile() {
        val source = localProfiles ?: return
        if (!state.value.managementOpen || state.value.managementScope != AiManagementScope.LOCAL_PROFILE || state.value.localProfile.saving) return
        val generation = ++localGeneration
        localProfileJob?.cancel()
        mutableState.update { it.copy(localProfile = it.localProfile.copy(loading = true, error = null, message = null)) }
        localProfileJob = viewModelScope.launch {
            source.observe().catch { error ->
                if (error is CancellationException) throw error
                if (generation == localGeneration) mutableState.update { it.copy(localProfile = it.localProfile.copy(loading = false, snapshot = null,
                    error = "真实本地画像读取失败，请重试；未发送任何数据")) }
            }.collect { snapshot ->
                if (generation == localGeneration) mutableState.update { current ->
                    val old = current.localProfile.snapshot
                    val accepted = old == null || snapshot.profile.token.profileVersion >= old.profile.token.profileVersion
                    current.copy(localProfile = current.localProfile.copy(loading = false, snapshot = if (accepted) snapshot else old))
                }
            }
        }
    }
    private fun localProfileAction(message: String, confirmed: Boolean = false, operation: suspend (LocalProfileManagementRepository) -> ProfileSnapshot) {
        val source = localProfiles ?: return
        val current = state.value
        if (!current.managementOpen || current.managementScope != AiManagementScope.LOCAL_PROFILE || current.localProfile.loading ||
            current.localProfile.saving || current.localProfile.snapshot == null ||
            (!confirmed && (current.localProfile.pendingRemoval != null || current.localProfile.pendingReset))) return
        mutableState.update { it.copy(localProfile = it.localProfile.copy(saving = true, error = null, message = null)) }
        viewModelScope.launch {
            try {
                val saved = operation(source)
                mutableState.update { current ->
                    val snapshot = current.localProfile.snapshot
                    val next = if (snapshot == null || saved.token.profileVersion < snapshot.profile.token.profileVersion) snapshot else snapshot.copy(profile = saved)
                    current.copy(localProfile = current.localProfile.copy(saving = false, snapshot = next, message = message, pendingRemoval = null, pendingReset = false))
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(localProfile = it.localProfile.copy(saving = false, error = "操作未确认成功，请重试；未修改作品数据")) }
            }
        }
    }
    fun changeLocalPreference(feature: ProfileFeature, direction: PreferenceDirection) {
        val local = state.value.localProfile
        if (local.saving || local.loading || local.pendingRemoval != null || local.pendingReset || state.value.managementScope != AiManagementScope.LOCAL_PROFILE) return
        val current = local.snapshot ?: return
        if (current.features.none { it.id == feature.id }) return
        if (current.profile.explicitPreferences.any { it.feature.id == feature.id && it.direction == direction })
            mutableState.update { it.copy(localProfile = it.localProfile.copy(pendingRemoval = feature, error = null, message = null)) }
        else localProfileAction("真实本地偏好已保存") { it.setPreference(feature, direction) }
    }
    fun changeLocalSources(settings: ProfileSourceSettings) = localProfileAction("真实本地来源已保存") { it.setSources(settings) }
    fun changeLocalCorrection(feature: ProfileFeature, suppressed: Boolean) = localProfileAction(if (suppressed) "真实本地纠错已保存" else "真实本地纠错已撤销") { it.correct(feature, suppressed) }
    fun requestLocalReset() {
        val current = state.value
        if (current.managementOpen && current.managementScope == AiManagementScope.LOCAL_PROFILE && current.localProfile.snapshot != null &&
            !current.localProfile.loading && !current.localProfile.saving && current.localProfile.pendingRemoval == null)
            mutableState.update { it.copy(localProfile = it.localProfile.copy(pendingReset = true, error = null, message = null)) }
    }
    fun cancelLocalConfirmation() {
        if (!state.value.localProfile.saving) mutableState.update { it.copy(localProfile = it.localProfile.copy(pendingRemoval = null, pendingReset = false, error = null)) }
    }
    fun confirmLocalRemoval() {
        val feature = state.value.localProfile.pendingRemoval ?: return
        localProfileAction("真实本地明确偏好已移除", confirmed = true) { it.removePreference(feature.id) }
    }
    fun confirmLocalReset() {
        if (state.value.localProfile.pendingReset) localProfileAction("真实本地画像已重置", confirmed = true) { it.reset() }
    }
    private var managementGeneration = 0L
    fun openManagement() {
        if (state.value.managementOpen || state.value.initializing || state.value.readFailed || state.value.result == AiResultState.LOADING ||
            state.value.saving || state.value.feedbackSaving != null || state.value.step != null || state.value.pendingAdd != null || state.value.directConsentPending) return
        mutableState.update { it.copy(managementOpen = true, managementTab = AiManagementTab.PROFILE) }
        loadManagement()
    }
    fun closeManagement() {
        if (state.value.managementSaving || state.value.localProfile.saving) return
        localGeneration++; localProfileJob?.cancel()
        managementGeneration++
        mutableState.update { it.copy(managementOpen = false, managementLoading = false, managementScope = AiManagementScope.MOCK, localProfile = LocalProfileManagementState(), pendingClear = null, pendingPreferenceRemoval = null, managementError = null, managementMessage = null) }
    }
    fun loadManagement() {
        if (!state.value.managementOpen || state.value.managementLoading || state.value.managementSaving) return
        val generation = ++managementGeneration
        mutableState.update { it.copy(managementLoading = true, management = null, managementError = null, managementMessage = null) }
        viewModelScope.launch {
            try {
                val value = repository.readManagement()
                if (generation == managementGeneration) mutableState.update { it.copy(managementLoading = false, management = value, setup = value.setup, quota = value.quota) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == managementGeneration) mutableState.update { it.copy(managementLoading = false, managementError = "AI 管理读取失败，数据未修改，请重试") }
            }
        }
    }
    fun selectManagementTab(tab: AiManagementTab) {
        if (!state.value.managementSaving) mutableState.update { it.copy(managementTab = tab, managementError = null, managementMessage = null) }
    }
    private fun managementAction(message: String, invalidate: Boolean = true, reset: Boolean = false, operation: suspend () -> AiManagementSnapshot) {
        if (!state.value.managementOpen || state.value.managementScope != AiManagementScope.MOCK || state.value.managementLoading || state.value.managementSaving || state.value.management == null) return
        mutableState.update { it.copy(managementSaving = true, managementError = null, managementMessage = null) }
        viewModelScope.launch {
            try {
                val value = operation()
                mutableState.update { it.copy(managementSaving = false, management = value, setup = value.setup, draft = value.setup, quota = value.quota,
                    managementMessage = message, pendingClear = null, pendingPreferenceRemoval = null,
                    result = if (invalidate) AiResultState.IDLE else it.result,
                    recommendations = if (invalidate) emptyList() else it.recommendations,
                    expanded = if (reset) false else it.expanded, input = if (reset) "" else it.input,
                    scenario = if (reset && it.scenario == MockScenario.DIRECT) MockScenario.SUCCESS else it.scenario) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(managementSaving = false, managementError = "操作失败，未提示成功，请重试") }
            }
        }
    }
    fun changeManagementPreference(topic: String, direction: PreferenceDirection) {
        val current = state.value
        if (!current.managementOpen || current.managementScope != AiManagementScope.MOCK || current.managementLoading || current.managementSaving || current.management == null ||
            current.pendingPreferenceRemoval != null || current.pendingClear != null || topic !in AiDemoRules.topics) return
        if (current.management.setup.preferences[topic] == direction) {
            mutableState.update { it.copy(pendingPreferenceRemoval = topic, managementError = null, managementMessage = null) }
        } else managementAction("演示偏好已保存") { repository.setManagementPreference(topic, direction) }
    }
    fun cancelPreferenceRemoval() {
        if (!state.value.managementSaving) mutableState.update { it.copy(pendingPreferenceRemoval = null, managementError = null) }
    }
    fun confirmPreferenceRemoval() {
        val topic = state.value.pendingPreferenceRemoval ?: return
        managementAction("演示明确偏好已移除") { repository.setManagementPreference(topic, null) }
    }
    fun changeManagementSources(settings: ProfileSourceSettings) = managementAction("演示来源已保存") { repository.setManagementSources(settings) }
    fun changeCorrection(topic: String, suppressed: Boolean) = managementAction(if (suppressed) "演示纠错已保存" else "演示纠错已撤销") { repository.correctInference(topic, suppressed) }
    fun requestClear(action: AiClearAction) {
        if (state.value.managementOpen && state.value.managementScope == AiManagementScope.MOCK && !state.value.managementLoading && !state.value.managementSaving && state.value.management != null)
            mutableState.update { it.copy(pendingClear = action, managementError = null, managementMessage = null) }
    }
    fun cancelClear() { if (!state.value.managementSaving) mutableState.update { it.copy(pendingClear = null, managementError = null) } }
    fun confirmClear() {
        val action = state.value.pendingClear ?: return
        managementAction("已完成：" + action.label, invalidate = action != AiClearAction.HISTORY, reset = action == AiClearAction.ALL) { repository.clearManagement(action) }
    }
    fun refreshQuota() = managementAction("额度状态已刷新", invalidate = false) {
        val quota = repository.readQuota()
        checkNotNull(state.value.management).copy(quota = quota, localUsage = repository.readLocalUsage(), localUsageUnavailable = false)
    }
    fun chooseMockQuota(scenario: MockQuotaScenario) = managementAction("已切换 Mock 额度场景", invalidate = false) { repository.setMockQuota(scenario) }
    override fun onCleared() {
        repository.selectIntegrationMode(false)
        repository.selectDirectMode(false)
        super.onCleared()
    }
    companion object {
        fun factory(repository: AiHomeRepository, keyConfiguration: AiKeyConfigurationRepository? = null, localProfiles: LocalProfileManagementRepository? = null, tmdbCredentials: TmdbCredentialRepository? = null, tmdbPreview: TmdbCandidatePreview? = null, tmdbRanking: TmdbMockRecommendationUseCase? = null, personalizedRecommendation: PersonalizedRecommendation? = null) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AiHomeViewModel(repository, keyConfiguration, localProfiles, tmdbCredentials, tmdbPreview, tmdbRanking, personalizedRecommendation) as T
        }
    }
}
