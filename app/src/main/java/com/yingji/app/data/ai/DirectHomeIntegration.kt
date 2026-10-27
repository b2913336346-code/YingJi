package com.yingji.app.data.ai

import com.yingji.app.domain.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong

/** Explicit, session-only consent. Only the synthetic demo store supplies the profile. */
internal class DirectHomeIntegration(
    private val demo: MockAiHomeRepository,
    private val keys: AiKeyConfigurationRepository,
    catalog: CandidateCatalogRepository,
    val mockKey: Boolean,
    serviceFactory: (() -> Boolean) -> AiWorkerService,
    private val cancelPending: () -> Unit = {},
) {
    @Volatile var active = false
        private set
    private val revision = AtomicLong()
    private val submitting = Mutex()
    @Volatile private var runningRevision = -1L
    @Volatile private var runningKey: String? = null
    private fun configured() = keys.state.value.let {
        it.storage == AiKeyStorageStatus.CONFIGURED && !it.verifying &&
            it.kind == if (mockKey) AiKeyVerificationKind.MOCK else AiKeyVerificationKind.LIVE
    }
    private val service = serviceFactory {
        active && configured() && runningRevision == revision.get() && runningKey == keys.state.value.revision
    }
    private val profiles = object : AiIntegrationProfileSource {
        override suspend fun snapshot() = AiIntegrationContext(demo.readIntegrationProfile(), active && configured(), revision.get(), keys.state.value.revision)
    }
    private val engine = IntegratedRecommendationUseCase(service, profiles, catalog, mockService = mockKey,
        serviceOwnsRetry = true, uncertainRequestReplay = false, refreshQuotaBeforeTask = true)
    fun select(enabled: Boolean) { if (active != enabled) { active = enabled; invalidate() } }
    fun invalidate() { revision.incrementAndGet(); cancelPending(); engine.invalidate() }
    suspend fun quota(): AiQuotaSnapshot = if (active && configured()) engine.refreshQuota() else AiQuotaSnapshot(AiQuotaStatus.UNAVAILABLE, mockKey)
    suspend fun managed(value: AiManagementSnapshot): AiManagementSnapshot {
        if (!active) return value
        val quota = try { quota() } catch (error: CancellationException) { throw error }
            catch (_: Exception) { AiQuotaSnapshot(AiQuotaStatus.UNAVAILABLE, mockKey) }
        return value.copy(quota = quota, quotaFixtureEditable = false, directMode = true)
    }
    suspend fun recommend(mode: AiMode, input: String): AiHomeRecommendationBatch {
        if (!submitting.tryLock()) throw WorkerProblem("REQUEST_IN_PROGRESS")
        try {
            check(active && demo.readSetup().completed)
            if (!configured()) throw WorkerProblem("KEY_REQUIRED")
            runningRevision = revision.get(); runningKey = keys.state.value.revision
            val expected = runningRevision
            val result = engine(AiHintInput(mode, input, allowMock = true))
            if (!active || expected != revision.get()) throw WorkerProblem("STALE_PROFILE")
            val prefix = when (result.origin) {
                AiRecommendationOrigin.WORKER -> if (mockKey) "直连协议 Mock：本机验证方向提示与候选链，不联网、不计真实用量。" else "直连方向提示已验证；作品信息来自本地演示候选。"
                AiRecommendationOrigin.CACHE -> "使用许可有效的演示缓存，没有新 AI 请求。"
                AiRecommendationOrigin.LOCAL -> "使用本地可信演示候选，本次未取得有效 AI 方向提示。"
                AiRecommendationOrigin.EMPTY -> "没有可验证且符合条件的演示候选，未补充作品。"
            }
            val problem = when (result.problem) {
                null, "NO_RELIABLE_CANDIDATES" -> ""
                "UPSTREAM_AUTH", "UPSTREAM_FORBIDDEN" -> " Key 或权限无效，请重新配置；未自动重试认证错误。"
                "UPSTREAM_BILLING" -> " 账户余额或计费不可用，请检查自己的 DeepSeek 账户。"
                "UPSTREAM_RATE_LIMIT" -> " 请求受限，请稍后手动再试；未自动重试。"
                "QUOTA_UNAVAILABLE", "LOCAL_LIMIT" -> " 本地软额度不可用，未开始新任务。"
                "LOCAL_USAGE_STORAGE" -> " 本地用量存储失败，已阻止新请求；已发出的请求仍可能收费。"
                "INVALID_RESPONSE", "MODEL_FORMAT" -> " 响应格式无效，已拒绝。"
                "OFFLINE", "TRANSPORT_TIMEOUT", "UPSTREAM_UNAVAILABLE" -> " 连接暂不可用；瞬时错误至多重试一次，可能产生额外费用。"
                "CONSENT_REQUIRED" -> " 尚未允许本次演示摘要处理。"
                else -> " 直连任务失败，可继续使用基础影迹功能。"
            }
            val rows = result.items.map { row -> val c = row.candidate
                MockRecommendation(c.identity.externalId, c.title!!, c.topics.sorted().firstOrNull().orEmpty(), c.description.orEmpty(), prefix, c.identity)
            }
            val outcome = when {
                result.problem != null && result.problem != "NO_RELIABLE_CANDIDATES" -> MockScenario.ERROR
                rows.isEmpty() -> MockScenario.EMPTY
                else -> MockScenario.SUCCESS
            }
            keys.withCurrentConfiguration(runningKey) {
                if (!active || expected != revision.get()) throw WorkerProblem("STALE_PROFILE")
                demo.recordIntegrationHistory(result.token, mode, rows.map { it.title }, outcome)
            }
            return AiHomeRecommendationBatch(rows, result.quota, prefix + problem)
        } finally { submitting.unlock() }
    }
}
