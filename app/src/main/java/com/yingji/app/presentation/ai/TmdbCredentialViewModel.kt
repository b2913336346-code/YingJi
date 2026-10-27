package com.yingji.app.presentation.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yingji.app.domain.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TmdbCredentialActions(val confirmDelete: Boolean = false, val message: String? = null)
enum class TmdbPreviewStatus { IDLE, LOADING, SUCCESS, EMPTY, ERROR }
data class TmdbPreviewState(val status: TmdbPreviewStatus = TmdbPreviewStatus.IDLE,
    val rows: List<CatalogCandidate> = emptyList(), val message: String? = null, val personalized: Boolean = false)
class TmdbCredentialViewModel(
    private val repository: TmdbCredentialRepository,
    private val candidatePreview: TmdbCandidatePreview? = null,
    private val mockRanking: TmdbMockRecommendationUseCase? = null,
    rankingChanges: Flow<ProfileToken> = emptyFlow(),
    private val personalizedRecommendation: PersonalizedRecommendation? = null,
) : ViewModel() {
    val state = repository.state
    private val mutableActions = MutableStateFlow(TmdbCredentialActions())
    val actions = mutableActions.asStateFlow()
    private val mutablePreview = MutableStateFlow(TmdbPreviewState())
    val preview = mutablePreview.asStateFlow()
    private val mutableRanking = MutableStateFlow(TmdbPreviewState())
    val ranking = mutableRanking.asStateFlow()
    val rankingAvailable get() = mockRanking != null
    val personalizedAvailable get() = personalizedRecommendation != null
    val personalizedUsesMockKey get() = personalizedRecommendation?.usesMockKey != false
    private val mutableConsent = MutableStateFlow<PersonalizedConsent?>(null)
    val personalizedConsent = mutableConsent.asStateFlow()
    val visibleRows get() = when {
        ranking.value.status in setOf(TmdbPreviewStatus.SUCCESS, TmdbPreviewStatus.EMPTY) -> ranking.value.rows
        ranking.value.personalized -> emptyList()
        else -> preview.value.rows
    }
    private var rankingJob: Job? = null
    @Volatile private var rankingGeneration = 0L
    private var catalogSnapshot: CandidateCatalog? = null
    private var rankingObservationFailed = false
    private var personalizedObservationFailed = false
    private val mutablePendingImport = MutableStateFlow<CatalogCandidate?>(null)
    val pendingImport = mutablePendingImport.asStateFlow()
    fun requestImport(row: CatalogCandidate) {
        if (row in visibleRows && ranking.value.status != TmdbPreviewStatus.LOADING && preview.value.status == TmdbPreviewStatus.SUCCESS &&
            state.value.status == TmdbCredentialStatus.SAVED_UNVERIFIED && !state.value.saving) mutablePendingImport.value = row
    }
    fun cancelImport() { mutablePendingImport.value = null }
    fun confirmImport(): CatalogCandidate? {
        val row = pendingImport.value; mutablePendingImport.value = null
        return row?.takeIf { it in visibleRows && ranking.value.status != TmdbPreviewStatus.LOADING && state.value.status == TmdbCredentialStatus.SAVED_UNVERIFIED && !state.value.saving }
    }
    val previewAvailable get() = candidatePreview != null
    private var previewJob: Job? = null
    @Volatile private var previewGeneration = 0L
    init {
        if (mockRanking != null) viewModelScope.launch {
            var previous: ProfileToken? = null
            rankingChanges.catch { error ->
                if (error is CancellationException) throw error
                rankingObservationFailed = true
                clearRanking()
                mutableRanking.value = TmdbPreviewState(TmdbPreviewStatus.ERROR, message = "演示版本读取失败，请重新打开首页后重试")
            }.collect { token ->
                if (previous != null && previous != token && !ranking.value.personalized && personalizedConsent.value == null) {
                    val hadRanking = ranking.value.status != TmdbPreviewStatus.IDLE
                    clearRanking()
                    if (hadRanking) mutableRanking.value = TmdbPreviewState(TmdbPreviewStatus.ERROR, message = "演示偏好或来源已变化，请重新排序")
                }
                previous = token
            }
        }
        viewModelScope.launch {
            var revision = state.value.revision
            repository.state.collect { next ->
                if (next.revision != revision || next.saving || next.status != TmdbCredentialStatus.SAVED_UNVERIFIED) clearPreview()
                revision = next.revision
            }
        }
        if (personalizedRecommendation != null) viewModelScope.launch {
            var previous: PersonalizedRevision? = null
            personalizedRecommendation.changes.catch { error ->
                if (error is CancellationException) throw error
                personalizedObservationFailed = true
                clearRanking()
                mutableRanking.value = TmdbPreviewState(TmdbPreviewStatus.ERROR, message = "真实画像或模型配置读取失败，请重新打开首页重试", personalized = true)
            }.collect { next ->
                if (previous != null && previous != next && (ranking.value.personalized || personalizedConsent.value != null)) {
                    clearRanking()
                    mutableRanking.value = TmdbPreviewState(TmdbPreviewStatus.ERROR, message = "真实画像、来源开关或模型 Key 已变化，请重新确认推荐", personalized = true)
                }
                previous = next
            }
        }
        refresh()
    }
    private fun clearRanking() {
        mutableConsent.value = null
        mutablePendingImport.value = null
        rankingGeneration++; rankingJob?.cancel(); rankingJob = null
        mutableRanking.value = TmdbPreviewState()
    }
    fun clearPreview() {
        clearRanking(); catalogSnapshot = null
        previewGeneration++; previewJob?.cancel(); previewJob = null
        mutablePreview.value = TmdbPreviewState()
    }
    fun readCandidates() {
        if (preview.value.status == TmdbPreviewStatus.LOADING || state.value.saving || actions.value.confirmDelete) return
        val reader = candidatePreview ?: return
        if (state.value.status != TmdbCredentialStatus.SAVED_UNVERIFIED) {
            mutablePreview.value = TmdbPreviewState(TmdbPreviewStatus.ERROR, message = "请先保存可用的 TMDB 读访问令牌")
            return
        }
        clearRanking(); catalogSnapshot = null
        val generation = ++previewGeneration
        val revision = state.value.revision
        fun current() = generation == previewGeneration && state.value.revision == revision &&
            !state.value.saving && state.value.status == TmdbCredentialStatus.SAVED_UNVERIFIED
        mutablePreview.value = TmdbPreviewState(TmdbPreviewStatus.LOADING)
        previewJob = viewModelScope.launch {
            try {
                val catalog = reader.readCatalog()
                if (current()) {
                    catalogSnapshot = catalog
                    mutablePreview.value = TmdbPreviewState(
                        if (catalog.candidates.isEmpty()) TmdbPreviewStatus.EMPTY else TmdbPreviewStatus.SUCCESS, catalog.candidates)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: TmdbProblem) {
                if (current()) mutablePreview.value = TmdbPreviewState(TmdbPreviewStatus.ERROR, message = when (e.code) {
                    TmdbProblemCode.KEY_REQUIRED, TmdbProblemCode.KEY_UNAVAILABLE -> "令牌不可用，请重新配置"
                    TmdbProblemCode.UNAUTHORIZED, TmdbProblemCode.FORBIDDEN -> "TMDB 令牌或权限无效，请检查配置；未自动重试"
                    TmdbProblemCode.RATE_LIMITED -> "TMDB 请求受限，请稍后手动再试；未自动重试"
                    TmdbProblemCode.OFFLINE -> "无法连接 TMDB，请检查设备网络后手动重试"
                    TmdbProblemCode.TIMEOUT, TmdbProblemCode.UNAVAILABLE -> "TMDB 暂时不可用，请稍后手动重试"
                    TmdbProblemCode.SECURE_CONNECTION -> "安全连接失败，未读取候选"
                    TmdbProblemCode.LICENSE_REQUIRED -> "TMDB 来源许可不可用，未读取候选"
                    TmdbProblemCode.STALE_KEY, TmdbProblemCode.BUSY -> "配置已变化，请重新读取"
                    TmdbProblemCode.LIVE_DISABLED -> "只读预览尚未启用"
                    else -> "候选响应无效，已拒绝展示"
                })
            } catch (_: Exception) {
                if (current()) mutablePreview.value = TmdbPreviewState(TmdbPreviewStatus.ERROR, message = "读取失败，请手动重试")
            }
        }
    }
    fun rankCandidates() {
        val ranker = mockRanking ?: return
        val catalog = catalogSnapshot ?: return
        if (rankingObservationFailed) {
            mutableRanking.value = TmdbPreviewState(TmdbPreviewStatus.ERROR, message = "演示版本读取失败，请重新打开首页后重试")
            return
        }
        if (ranking.value.status == TmdbPreviewStatus.LOADING ||
            preview.value.status != TmdbPreviewStatus.SUCCESS || state.value.saving || actions.value.confirmDelete ||
            pendingImport.value != null || personalizedConsent.value != null || state.value.status != TmdbCredentialStatus.SAVED_UNVERIFIED) return
        val generation = ++rankingGeneration
        val previewVersion = previewGeneration
        val revision = state.value.revision
        fun current() = generation == rankingGeneration && previewVersion == previewGeneration &&
            state.value.revision == revision && !state.value.saving && state.value.status == TmdbCredentialStatus.SAVED_UNVERIFIED
        mutableRanking.value = TmdbPreviewState(TmdbPreviewStatus.LOADING)
        rankingJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { ranker(catalog, sessionCurrent = ::current) }
                if (current()) mutableRanking.value = TmdbPreviewState(
                    if (result.items.isEmpty()) TmdbPreviewStatus.EMPTY else TmdbPreviewStatus.SUCCESS,
                    result.items.map { it.candidate },
                    if (result.problem == null) null else "Mock 方向暂不可用，本次仅按本地规则排序已核验候选")
            } catch (e: CancellationException) { throw e }
            catch (e: TmdbProblem) {
                if (current()) mutableRanking.value = TmdbPreviewState(TmdbPreviewStatus.ERROR, message =
                    if (e.code == TmdbProblemCode.LICENSE_REQUIRED) "来源许可不可用，请重新读取候选" else "候选证据无效，请重新读取后排序")
            } catch (e: WorkerProblem) {
                if (current()) mutableRanking.value = TmdbPreviewState(TmdbPreviewStatus.ERROR, message =
                    if (e.code == "STALE_PROFILE") "演示偏好或来源已变化，请重新排序" else "本地排序暂不可用，请手动重试")
            } catch (_: Exception) {
                if (current()) mutableRanking.value = TmdbPreviewState(TmdbPreviewStatus.ERROR, message = "本地排序失败，请手动重试")
            }
        }
    }
    fun requestPersonalizedRecommendation() {
        val recommender = personalizedRecommendation ?: return
        if (personalizedObservationFailed) {
            mutableRanking.value = TmdbPreviewState(TmdbPreviewStatus.ERROR, personalized = true, message = "真实画像或模型配置读取失败，请重新打开首页重试")
            return
        }
        val catalog = catalogSnapshot ?: return
        if (preview.value.status != TmdbPreviewStatus.SUCCESS || ranking.value.status == TmdbPreviewStatus.LOADING ||
            state.value.saving || actions.value.confirmDelete || pendingImport.value != null || personalizedConsent.value != null ||
            state.value.status != TmdbCredentialStatus.SAVED_UNVERIFIED) return
        clearRanking()
        val generation = ++rankingGeneration
        val previewVersion = previewGeneration
        val revision = state.value.revision
        fun current() = generation == rankingGeneration && previewVersion == previewGeneration &&
            state.value.revision == revision && !state.value.saving && state.value.status == TmdbCredentialStatus.SAVED_UNVERIFIED
        mutableRanking.value = TmdbPreviewState(TmdbPreviewStatus.LOADING, personalized = true)
        rankingJob = viewModelScope.launch {
            try {
                val consent = withContext(Dispatchers.Default) { recommender.prepare(catalog) }
                if (current()) {
                    mutableRanking.value = TmdbPreviewState(personalized = true)
                    mutableConsent.value = consent
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (current()) personalFailure(error) }
        }
    }
    fun cancelPersonalizedConsent() { if (personalizedConsent.value != null) clearRanking() }
    fun confirmPersonalizedConsent() {
        val recommender = personalizedRecommendation ?: return
        val consent = personalizedConsent.value ?: return
        val catalog = catalogSnapshot ?: return
        mutableConsent.value = null
        val generation = ++rankingGeneration
        val previewVersion = previewGeneration
        val revision = state.value.revision
        fun current() = generation == rankingGeneration && previewVersion == previewGeneration &&
            state.value.revision == revision && !state.value.saving && state.value.status == TmdbCredentialStatus.SAVED_UNVERIFIED
        mutableRanking.value = TmdbPreviewState(TmdbPreviewStatus.LOADING, personalized = true)
        rankingJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { recommender.recommend(catalog, consent, ::current) }
                if (current()) mutableRanking.value = TmdbPreviewState(
                    if (result.items.isEmpty()) TmdbPreviewStatus.EMPTY else TmdbPreviewStatus.SUCCESS,
                    result.items.map { it.candidate },
                    if (result.problem == null) {
                        if (recommender.usesMockKey) "真实本地画像 · 协议 Mock：未调用模型或增加真实用量"
                        else "真实本地画像 · DeepSeek 方向提示；作品信息来自已核验候选"
                    } else personalProblem(result.problem) + "；本次仅按真实本地画像过滤、排序已核验候选",
                    personalized = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (current()) personalFailure(error) }
        }
    }
    private fun personalProblem(code: String) = when (code) {
        "KEY_REQUIRED", "KEY_UNAVAILABLE", "STALE_KEY" -> "模型 Key 不可用，请在 我的 → AI 模型配置 中检查"
        "UPSTREAM_AUTH", "UPSTREAM_FORBIDDEN" -> "模型 Key 或权限无效，未自动重试"
        "UPSTREAM_BILLING" -> "模型账户计费不可用，请检查供应商账户"
        "UPSTREAM_RATE_LIMIT" -> "模型请求受限，未自动重试"
        "QUOTA_UNAVAILABLE", "LOCAL_LIMIT" -> "本地软额度不可用，未开始新任务"
        "LOCAL_USAGE_STORAGE" -> "用量保存失败，已阻止新请求；已发出的请求仍可能收费"
        "STALE_PROFILE" -> "真实画像或授权已变化，请重新确认推荐"
        "INVALID_INPUT" -> "画像摘要包含过长或无效主题，请在真实本地画像管理中检查"
        "MODEL_FORMAT", "INVALID_RESPONSE" -> "模型响应格式无效，已拒绝"
        else -> "模型方向暂不可用，请手动重试"
    }
    private fun personalFailure(error: Exception) {
        mutableRanking.value = TmdbPreviewState(TmdbPreviewStatus.ERROR, personalized = true, message = when (error) {
            is TmdbProblem -> "来源许可或候选证据不可用，请重新读取候选"
            is WorkerProblem -> personalProblem(error.code)
            else -> "真实画像推荐暂不可用，未写入作品或历史，请重试"
        })
    }
    override fun onCleared() { clearPreview(); super.onCleared() }
    fun refresh() { viewModelScope.launch { repository.refresh() } }
    fun save(input: String, onSaved: () -> Unit) {
        if (state.value.saving || state.value.status == TmdbCredentialStatus.LOADING || actions.value.confirmDelete) return
        clearPreview()
        mutableActions.value = TmdbCredentialActions()
        viewModelScope.launch {
            try { repository.save(input); mutableActions.value = TmdbCredentialActions(message = "已保存到本机；尚未联网验证，首页须单独选择来源并主动读取"); onSaved() }
            catch (e: CancellationException) { throw e }
            catch (e: TmdbCredentialException) { mutableActions.value = TmdbCredentialActions(message = when (e.problem) {
                TmdbCredentialProblem.EMPTY_INPUT -> "请输入读访问令牌，空白输入不会保存"
                TmdbCredentialProblem.INVALID_INPUT -> "令牌格式无效，不会保存"
                TmdbCredentialProblem.BUSY -> "正在处理，请稍候"
                TmdbCredentialProblem.STORAGE_FAILED -> "保存失败，未报告成功，请重试"
            }) }
        }
    }
    fun requestDelete() { if (!state.value.saving && state.value.status != TmdbCredentialStatus.LOADING) { clearPreview(); mutableActions.value = TmdbCredentialActions(confirmDelete = true) } }
    fun cancelDelete() { if (!state.value.saving) mutableActions.value = TmdbCredentialActions() }
    fun delete(onDeleted: () -> Unit) {
        if (state.value.saving || !actions.value.confirmDelete) return
        viewModelScope.launch {
            try { repository.delete(); mutableActions.value = TmdbCredentialActions(message = "TMDB 本地令牌已删除；DeepSeek 配置及其他数据保留"); onDeleted() }
            catch (e: CancellationException) { throw e }
            catch (_: TmdbCredentialException) { mutableActions.value = TmdbCredentialActions(true, "删除失败，未报告成功，请重试") }
        }
    }
    companion object {
        fun factory(repository: TmdbCredentialRepository, preview: TmdbCandidatePreview? = null,
            ranking: TmdbMockRecommendationUseCase? = null, rankingChanges: Flow<ProfileToken> = emptyFlow(),
            personalized: PersonalizedRecommendation? = null) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TmdbCredentialViewModel(repository, preview, ranking, rankingChanges, personalized) as T
        }
    }
}
