package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/** Opt-in loopback integration over synthetic data. Real Room profiles are never read here. */
class WorkerDemoHomeRepository(private val demo: MockAiHomeRepository, service: AiWorkerService, catalog: CandidateCatalogRepository, private val keys: AiKeyConfigurationRepository? = null) : AiHomeRepository by demo {
    @Volatile private var workerMode = false
    private val consentRevision = AtomicLong()
    private val profiles = object : AiIntegrationProfileSource {
        override suspend fun snapshot(): AiIntegrationContext {
            val key = keys?.state?.value
            val allowed = keys == null || (key?.storage == AiKeyStorageStatus.CONFIGURED && key.kind == AiKeyVerificationKind.MOCK && !key.verifying)
            return AiIntegrationContext(demo.readIntegrationProfile(), workerMode && allowed, consentRevision.get(), key?.revision)
        }
    }
    private val engine = IntegratedRecommendationUseCase(service, profiles, catalog, mockService = true)
    override val workerDemoAvailable = true
    override val workerUsesConfiguredKey get() = keys != null
    override val integrationChanges get() = demo.observeIntegrationToken()
    override fun selectIntegrationMode(enabled: Boolean) {
        if (workerMode != enabled) { workerMode = enabled; consentRevision.incrementAndGet(); engine.invalidate() }
    }
    override suspend fun readQuota(): AiQuotaSnapshot {
        if (!workerMode) return demo.readQuota()
        val key = keys?.state?.value
        if (keys != null && (key?.storage != AiKeyStorageStatus.CONFIGURED || key.kind != AiKeyVerificationKind.MOCK || key.verifying))
            return AiQuotaSnapshot(AiQuotaStatus.UNAVAILABLE, true)
        return engine.refreshQuota()
    }
    private suspend fun safeQuota(): AiQuotaSnapshot = try { readQuota() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { AiQuotaSnapshot(AiQuotaStatus.UNAVAILABLE, true) }
    private suspend fun managed(snapshot: AiManagementSnapshot) = if (workerMode) snapshot.copy(quota = safeQuota(), quotaFixtureEditable = false) else snapshot
    override suspend fun readManagement() = managed(demo.readManagement())
    override suspend fun saveSetup(setup: AiDemoSetup) = demo.saveSetup(setup).also { engine.invalidate() }
    override suspend fun saveFeedback(id: String, feedback: AiFeedback?) = demo.saveFeedback(id, feedback).also { engine.invalidate() }
    override suspend fun setManagementPreference(topic: String, direction: PreferenceDirection?) = managed(demo.setManagementPreference(topic, direction).also { engine.invalidate() })
    override suspend fun setManagementSources(settings: ProfileSourceSettings) = managed(demo.setManagementSources(settings).also { engine.invalidate() })
    override suspend fun correctInference(topic: String, suppressed: Boolean) = managed(demo.correctInference(topic, suppressed).also { engine.invalidate() })
    override suspend fun clearManagement(action: AiClearAction) = managed(demo.clearManagement(action).also { if (action != AiClearAction.HISTORY) engine.invalidate() })
    override suspend fun setMockQuota(scenario: MockQuotaScenario): AiManagementSnapshot {
        if (workerMode) throw WorkerProblem("SERVER_QUOTA_READ_ONLY")
        return demo.setMockQuota(scenario)
    }
    override suspend fun recommend(mode: AiMode, input: String, scenario: MockScenario) = recommendWithMetadata(mode, input, scenario).items
    override suspend fun recommendWithMetadata(mode: AiMode, input: String, scenario: MockScenario): AiHomeRecommendationBatch {
        if (scenario != MockScenario.WORKER) return AiHomeRecommendationBatch(demo.recommend(mode, input, scenario))
        check(workerMode && demo.readSetup().completed)
        val consent = consentRevision.get()
        val keyRevision = keys?.state?.value?.revision
        val result = engine(AiHintInput(mode, input, allowMock = true))
        if (!workerMode || consent != consentRevision.get()) throw WorkerProblem("STALE_PROFILE")
        val prefix = when (result.origin) {
            AiRecommendationOrigin.WORKER -> "本地 Worker 联调（Mock）：仅演示排序，没有真实模型或真实影视来源。"
            AiRecommendationOrigin.CACHE -> "使用许可有效的演示缓存，未发起新的 AI 任务。"
            AiRecommendationOrigin.LOCAL -> "使用本地可信演示候选，未把故障结果当作真实推荐。"
            AiRecommendationOrigin.EMPTY -> "当前没有可验证且符合条件的演示候选，未补充作品。"
        }
        val problem = when (result.problem) {
            null -> ""
            "QUOTA_UNAVAILABLE", "QUOTA_EXHAUSTED", "BUDGET_EXHAUSTED" -> " 服务端额度或预算不可用，未发起新的付费任务。"
            "TASK_PENDING", "OUTCOME_UNKNOWN" -> " 原任务状态待确认；再次提交复用原任务 ID。"
            "CONSENT_REQUIRED" -> " 未启用演示摘要传输。"
            "UNAUTHORIZED" -> " 联调访问验证失败。"
            "INVALID_RESPONSE", "MODEL_FORMAT" -> " 联调响应格式无效，已拒绝。"
            "OFFLINE", "TRANSPORT_TIMEOUT", "UPSTREAM_TIMEOUT" -> " 联调连接暂不可用。"
            else -> " 联调服务暂不可用。"
        }
        val rows = result.items.map { row -> val c = row.candidate
            MockRecommendation(c.identity.externalId, c.title!!, c.topics.sorted().firstOrNull().orEmpty(), c.description.orEmpty(), prefix, c.identity)
        }
        val outcome = when {
            result.problem in setOf("OFFLINE", "TRANSPORT_TIMEOUT", "UPSTREAM_TIMEOUT") -> MockScenario.OFFLINE
            result.problem in setOf("QUOTA_UNAVAILABLE", "QUOTA_EXHAUSTED", "BUDGET_EXHAUSTED") -> MockScenario.QUOTA
            result.problem != null && result.problem != "NO_RELIABLE_CANDIDATES" -> MockScenario.ERROR
            rows.isEmpty() -> MockScenario.EMPTY
            else -> MockScenario.SUCCESS
        }
        suspend fun record() { demo.recordIntegrationHistory(result.token, mode, rows.map { it.title }, outcome) }
        if (keys != null) keys.withCurrentConfiguration(keyRevision) { record() } else record()
        return AiHomeRecommendationBatch(rows, result.quota, prefix + problem)
    }
}
